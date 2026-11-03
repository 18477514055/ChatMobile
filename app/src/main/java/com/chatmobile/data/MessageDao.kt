package com.chatmobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 消息 DAO。
 *
 * ## ★ 分页为什么用 `seq` 游标而不是 `LIMIT/OFFSET`
 *
 * 聊天记录是**往上翻**的（最新在底部，用户向上滑加载更早的）。
 * 用 `LIMIT n OFFSET m` 在聊天场景有两个致命问题：
 *
 * 1. **翻页期间来了新消息** ⇒ OFFSET 整体位移 ⇒ 用户会看到重复或跳过的消息。
 *    聊天 App 里新消息是常态，这不是边界情况，是常态。
 * 2. **OFFSET 越大越慢** —— SQLite 要先扫过前面 m 行再丢掉。翻到 5000 条要扫 5000 行。
 *
 * 所以这里主路径是**基于 [MessageEntity.seq] 的游标分页**：
 * 「给我 seq 小于 X 的最近 N 条」。它不随插入而位移，且能直接用
 * `(conversationId, seq)` 唯一索引反向扫描。
 *
 * 同时保留 [pageByOffset]（`LIMIT/OFFSET`）—— 分页测试与"跳转到第 N 页"
 * 这类少数场景确实需要它，但**主路径不要用它**。
 *
 * ## 为什么"查一页"的 SQL 用 `ORDER BY seq DESC LIMIT n`
 * 走 `(conversationId, seq)` 索引从尾部反向扫，是最省的；
 * 而 `ORDER BY seq ASC LIMIT n` 取到的是**最老**的 n 条 —— 对"往上翻历史"正好取反。
 * 所以 SQL 倒序取、由调用方（或 repository）在 Kotlin 侧套 [toAscending] 变正序。
 * ★ 刻意**不**在 DAO 里藏这层反转：返回值顺序与 SQL 保持一致，
 * 后人看 SQL 就知道拿到什么顺序，不会猜。
 */
@Dao
interface MessageDao {

    // ---------------------------------------------------------------
    // 写入
    // ---------------------------------------------------------------

    /**
     * 插入一条消息，并自动分配会话内单调的 `seq`。
     *
     * ★ 为什么要在事务里 `MAX(seq)+1` 而不是用 `createdAt` 排序：
     *   用户消息和助手占位消息通常在同一毫秒内插入，`createdAt` 会**相同**。
     *   `(conversationId, seq)` 上的唯一索引同时充当并发保险：
     *   万一有两条并发插入拿到同一个 seq，第二条会撞唯一约束**立刻报错**，
     *   而不是静默地产生一个顺序错乱的会话。
     *
     * 返回分配到的 seq（调用方一般不需要，测试要）。
     */
    @Transaction
    suspend fun insert(message: MessageEntity): Long {
        val next = nextSeq(message.conversationId)
        insertRaw(message.copy(seq = next))
        return next
    }

    /** 不做 seq 分配的裸插入 —— 只给 [insert] 与测试构造夹具用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRaw(message: MessageEntity)

    /** 整条覆盖（重试 / 编辑消息 / 从分支复制） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE conversationId = :conversationId")
    suspend fun nextSeq(conversationId: String): Long

    // ---------------------------------------------------------------
    // 流式写入
    // ---------------------------------------------------------------

    /**
     * 追加正文 —— 流式输出的热路径。
     *
     * ★ 用 SQL 的 `content || :delta` 而不是「读出来拼好再写回去」：
     *   后者在并发下会丢数据（读到的旧值覆盖了别人刚写的），而且要多一次读。
     *   SQLite 的字符串拼接在一条 UPDATE 里完成，天然原子。
     *
     * ⚠️ 这个方法本身**不慢**（单条 UPDATE 走主键），慢的是**调用频率**。
     *   高频调用请走 [StreamingMessageWriter]，它负责攒批。
     */
    @Query("UPDATE message SET content = content || :delta WHERE id = :id")
    suspend fun appendContent(id: String, delta: String): Int

    /**
     * 一次性写入累积的正文（[StreamingMessageWriter] 的 flush 用它）。
     *
     * 与 [appendContent] 的区别：这是**绝对写**，不是相对追加。
     * 缓冲层已经把"上次写到哪"记下来了，绝对写比分段追加更好排查
     * （数据库里永远是完整前缀，不会因为一次丢帧就永久缺口）。
     */
    @Query("UPDATE message SET content = :content WHERE id = :id")
    suspend fun setContent(id: String, content: String): Int

    @Query("UPDATE message SET reasoning = :reasoning WHERE id = :id")
    suspend fun setReasoning(id: String, reasoning: String?): Int

    /** 正文与推理一起写 —— 缓冲层 flush 时用，省一次语句往返 */
    @Query("UPDATE message SET content = :content, reasoning = :reasoning WHERE id = :id")
    suspend fun setContentAndReasoning(id: String, content: String, reasoning: String?): Int

    /**
     * 状态流转。终态（done/error/stopped）时顺手把 errorMessage 与思考耗时落库。
     *
     * ★ 参数**不给默认值**：Room 对 DAO 方法上的 Kotlin 默认参数支持不完整，
     *   生成的 Java 实现会调不到那个桥接方法。默认值放在
     *   [StreamingMessageWriter.finish] 这一层。
     */
    @Query(
        """
        UPDATE message
        SET status = :status, errorMessage = :errorMessage, reasoningMs = :reasoningMs
        WHERE id = :id
        """,
    )
    suspend fun setStatus(
        id: String,
        status: String,
        errorMessage: String?,
        reasoningMs: Long?,
    ): Int

    /**
     * 崩溃恢复：把仍然停在 `streaming` / `pending` 的行标成 [MessageStatus.STOPPED]。
     *
     * ★ 为什么必须做：进程被杀时库里的 `streaming` 行是**内容不完整的**。
     *   如果不管它，下次进来界面会显示一条"正常"但实际被截断的回答，
     *   或者更糟 —— 一个永远转圈的气泡。启动时扫一遍就能如实显示。
     *
     * @return 被修复的行数
     */
    @Query(
        """
        UPDATE message SET status = 'stopped'
        WHERE status IN ('streaming', 'pending')
        """,
    )
    suspend fun stopInFlight(): Int

    // ---------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------

    /** 整会话全量（正序）。**只用于导出/统计，不要用于界面渲染** */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY seq ASC")
    suspend fun allIn(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    /**
     * 取一段消息 id 区间内的**全部 id**（含两端）。
     *
     * ★ 用途：压缩记录只存首尾两个 id，恢复"哪些消息已被摘要覆盖"时要把区间展开。
     *
     * ★ 为什么用 `seq` 而不是直接比 id：**消息 id 是 UUID，不保序**。
     *   直接 `id BETWEEN :from AND :to` 会按字典序比 UUID，结果是随机的。
     *   ⇒ 先按 id 查出两个端点各自的 `seq`，再用 `seq BETWEEN` 取区间。
     */
    @Query(
        """
        SELECT id FROM message
        WHERE conversationId = :conversationId
          AND seq >= (SELECT seq FROM message WHERE id = :fromId)
          AND seq <= (SELECT seq FROM message WHERE id = :toId)
        ORDER BY seq ASC
        """,
    )
    suspend fun idsBetween(conversationId: String, fromId: String, toId: String): List<String>

    /**
     * 最近 N 条。进入会话时用它填首屏。
     *
     * ⚠️ 返回**降序**（新 → 旧），与 SQL 一致。要塞进界面请套 [toAscending]。
     * 刻意不在 DAO 里反转：Room 的返回值类型由 SQL 决定，藏一层反转会让
     * "为什么这个方法返回的顺序和 SQL 相反"变成后人 debugging 的坑。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversationId = :conversationId
        ORDER BY seq DESC
        LIMIT :limit
        """,
    )
    suspend fun latest(conversationId: String, limit: Int): List<MessageEntity>

    /**
     * **游标分页**（主路径）：取 seq 小于 [beforeSeq] 的最近 [limit] 条。
     *
     * @param beforeSeq 上一页最靠前那条的 `seq`；传 [Long.MAX_VALUE] 表示从最新开始
     * @return **降序**（新 → 旧）的一页，与 SQL 一致；界面请套 [toAscending]
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversationId = :conversationId AND seq < :beforeSeq
        ORDER BY seq DESC
        LIMIT :limit
        """,
    )
    suspend fun pageBefore(conversationId: String, beforeSeq: Long, limit: Int): List<MessageEntity>

    /**
     * **偏移分页**：`LIMIT/OFFSET`。
     *
     * ⚠️ 只给"跳转到第 N 页"与测试用。聊天主路径请用 [pageBefore] ——
     * 理由见类注释：聊天中随时会插入新消息，OFFSET 会位移导致重复/漏条。
     *
     * 参数顺序按 `seq ASC` 算（offset=0 是最早的那页）。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversationId = :conversationId
        ORDER BY seq ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageByOffset(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    /** 响应式：整个会话（正序）。消息一变就推送 */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY seq ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    /** 会话的最后一条消息 —— 侧边栏显示"最后说了什么"用 */
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY seq DESC LIMIT 1")
    suspend fun lastIn(conversationId: String): MessageEntity?

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteAllIn(conversationId: String): Int

    /**
     * 删除某条及其之后的全部（"重新生成"用）。
     *
     * ★ 必须按 `seq >=` 删，而不是 `createdAt >=`：
     *   同毫秒的多条消息用时间戳切会切错，且切不干净。
     */
    @Query("DELETE FROM message WHERE conversationId = :conversationId AND seq >= :fromSeq")
    suspend fun deleteFrom(conversationId: String, fromSeq: Long): Int
}

/**
 * 游标分页的**组装层** —— 不依赖数据库，纯函数，能单独测。
 *
 * 为什么单独拿出来：分页最容易错的地方是"下一页游标算错了"，
 * 而这个错误在小数据量下**看起来是对的**（第一页正常），
 * 只有到了数据量边界（正好整除、只剩一条、已到顶）才暴露。
 * 抽成纯函数就能把那些边界一次性测穿。
 */
object MessagePaging {

    /**
     * 从一次 **`limit + 1` 条**的查询结果（降序）组装出 [MessagePage]。
     *
     * ## ★ 为什么必须多取一条（这条是修过的真 bug）
     *
     * 原实现用「返回条数 < limit ⇒ 已到顶」判断结尾。**这个判断是错的**：
     * 数据量正好整除时会翻车 —— 9 条消息、每页 3 条，第 3 页正好返回 3 条，
     * 于是它声称"还有下一页"，第 4 页返回空数组。表现是**列表底部永远有个
     * 转圈的"加载更多"**，而它加载不出任何东西。
     *
     * 正确做法（也是 `Paging3`、SQL 游标分页的标准做法）：**多取一条**。
     * 取到第 `limit + 1` 条就说明后面还有；取不满就说明真的到顶了。
     * 多出来的那一条**丢弃**，它会在下一页被取到（因为 `seq < cursor` 包含它）。
     *
     * @param rawDescendingFetchedWithLimitPlusOne DAO 以 `limit + 1` 请求后原样返回的降序列表
     * @param limit 每页条数（**不是** raw 的实际长度）
     */
    fun assemble(rawDescendingFetchedWithLimitPlusOne: List<MessageEntity>, limit: Int): MessagePage {
        require(limit > 0) { "limit 必须为正，当前 $limit" }
        val raw = rawDescendingFetchedWithLimitPlusOne
        if (raw.isEmpty()) return MessagePage(emptyList(), nextBeforeSeq = null)

        val hasMore = raw.size > limit
        // 多取的那一条丢掉，留给下一页
        val pageDescending = if (hasMore) raw.subList(0, limit) else raw

        // 有下一页时，游标是本页**最靠前**那条（降序列表的最后一条）；
        // 没有下一页时游标为 null —— 明确告诉调用方"别再来问了"。
        val cursor = if (hasMore) pageDescending.last().seq else null

        return MessagePage(
            items = pageDescending.toAscending(),
            nextBeforeSeq = cursor,
        )
    }
}

// ---- 分页结果与 Kotlin 侧的正序化 ----

/**
 * 一页消息。
 *
 * [nextBeforeSeq] 是继续往前翻时该传给 `pageBefore` 的游标；
 * 为 null 表示已经到顶（没有更早的消息了）。
 */
data class MessagePage(
    /** 正序（早 → 晚） */
    val items: List<MessageEntity>,
    val nextBeforeSeq: Long?,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 继续取下一页要用的游标；null = 到头了 */
    val canLoadMore: Boolean get() = nextBeforeSeq != null
}

/** 把 `ORDER BY seq DESC` 的结果反转成正序。 */
internal fun List<MessageEntity>.toAscending(): List<MessageEntity> = asReversed()

/**
 * **翻页的唯一正确入口。**
 *
 * 把它做成扩展函数而不是让调用方"记得传 limit + 1"，是因为后者是个**静默**的坑：
 * 忘了 +1 代码照样跑，只是列表底部多一个永远转圈、永远加载不出东西的"加载更多"。
 * 本包的一致性测试第一次跑就抓到了这个（见 [MessagePaging.assemble] 的注释）。
 *
 * ```kotlin
 * var cursor: Long? = null
 * do {
 *     val page = messages.page(conversationId, cursor, limit = 30)
 *     // page.items 是正序的一页
 *     cursor = page.nextBeforeSeq          // null ⇒ 到顶了
 * } while (cursor != null)
 * ```
 *
 * ★ 直接用 [MessageDao.pageBefore] 也可以，但那样你得自己加 1、自己调
 *   [MessagePaging.assemble]。除了写 DAO 层测试，没有理由这么做。
 */
suspend fun MessageDao.page(
    conversationId: String,
    beforeSeq: Long? = null,
    limit: Int = 30,
): MessagePage {
    require(limit > 0) { "limit 必须为正，当前 $limit" }
    val raw = pageBefore(conversationId, beforeSeq ?: Long.MAX_VALUE, limit + 1)
    return MessagePaging.assemble(raw, limit)
}
