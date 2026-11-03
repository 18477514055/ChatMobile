package com.chatmobile.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 流式消息的**写入缓冲器**。
 *
 * ## 问题：为什么不能每条 delta 都写库
 *
 * 一次 SSE 流式回答的 delta 粒度很小 —— 常见是 1~3 个字符。一段 500 字的回答
 * 大约产生 **300~1500 次** delta 回调。如果每次回调都执行一条 `UPDATE`：
 *
 * | 代价 | 量级 |
 * |---|---|
 * | SQLite 语句编译 + B-tree 查找 | 每次数百微秒 |
 * | **WAL 提交**（未合批时每次都是独立事务） | 每次 **1~10 ms**，含 `fsync` |
 * | 主线程/IO 线程争用 | 每一帧都可能撞上 |
 *
 * 1500 × 5ms ≈ **7.5 秒的纯 IO**，而这本来应该是 15 秒的顺畅输出 ——
 * 也就是说一半的时间在等磁盘，且表现为"字一个一个卡着蹦"。
 * 更糟的是 `fsync` 是**突发性**的：它会周期性把整个 App 的掉帧推到 16ms 以上。
 *
 * ## 方案：攒批 + 绝对写
 *
 * 三条触发条件，谁先到谁触发 flush：
 *
 * | 条件 | 默认值 | 作用 |
 * |---|---|---|
 * | 累积 delta 条数 ≥ [BufferPolicy.maxPendingDeltas] | 32 | 高频短 delta 的兜底 |
 * | 累积字符数 ≥ [BufferPolicy.maxPendingChars] | 1200 | ★ 主力：delta 大小差异极大，按**字符**算才稳定 |
 * | 距上次写入 ≥ [BufferPolicy.maxDelayMs] | 120 ms | 空闲尾巴：网络抖一下也不至于让最后几个字干等 |
 *
 * 为什么"按字符"而不是只按"条数"：delta 的长度完全不可控 ——
 * 有的 provider 一次给一个字，有的一次给一整句。只数条数会让攒批行为
 * 在不同厂商之间差一个数量级（本项目的卖点就是兼容各家）。
 *
 * ### ★ 关键取舍：写**绝对快照**，不写增量
 *
 * 每次 flush 写的是"到目前为止的完整正文"，而不是"这 32 条拼起来的增量"。
 *
 * 代价：第 k 次 flush 要写 k 个字节，总写入量是 O(n²)（n = 回答长度）。
 * 一次 10 KB 的回答、每 120ms 一次，总计约 400 KB 的额外写入 ——
 * **在现代手机的 UFS 上完全可以忽略**（一次回答的语音数据都是这个量级的几十倍）。
 *
 * 收益（这三条才是决定性的）：
 * 1. **幂等**。写失败、进程被杀、重试，都不会双写。增量追加一旦"其实写成功了但抛了异常"，
 *    重试就会**永久性地把正文写坏**（重复片段），而且用户看不出来是 bug。
 * 2. **自愈**。库里的内容永远是"某个时刻的完整前缀"，不会出现中间缺口。
 * 3. **可断言**。测试可以直接比"库里 == 内存里"。
 *
 * > 如果哪天回答长度真的到了 O(n²) 成为瓶颈（比如长篇小说生成），
 * > 正确的优化不是换回增量写，而是**分段**：把一条消息拆成多行存储。
 * > 那是 schema 层面的决定，不是缓冲器能兜住的。
 *
 * ## 用法
 *
 * ```kotlin
 * val writer = StreamingMessageWriter(messageDao, message.id)
 * try {
 *     provider.streamChat(req).collect { event ->
 *         when (event) {
 *             is ChatEvent.Delta -> writer.append(event.text)
 *             is ChatEvent.Reasoning -> writer.appendReasoning(event.text)
 *             else -> {}
 *         }
 *     }
 *     writer.finish(MessageStatus.DONE)
 * } catch (e: CancellationException) {
 *     writer.finish(MessageStatus.STOPPED)   // 用户点了"停止生成"
 *     throw e
 * } catch (e: Exception) {
 *     writer.fail(e.message)
 * }
 * ```
 *
 * ★ [append] / [appendReasoning] 是 `suspend` 的，但**绝大多数调用不会碰磁盘**：
 *   只有越过阈值的那一次才会真正写库。把它做成 suspend 是为了"越过阈值时能就地写" ——
 *   否则就得依赖调用方另外起一个后台任务，而那个任务一旦没起来，
 *   数据会一直躺在内存里直到进程被杀。
 *
 * ★ 线程安全：缓冲区用一把短临界区的锁保护（只做字符串追加，微秒级），
 *   写库用 [ioMutex] 串行化。两者分开，避免"写库期间的慢 IO 挡住 append"。
 */
class StreamingMessageWriter(
    private val dao: MessageDao,
    private val messageId: String,
    private val policy: BufferPolicy = BufferPolicy(),
) {

    /** 缓冲策略。全部可调 —— 这些数字要拿真实设备调，写死等于放弃调优。 */
    data class BufferPolicy(
        /** 攒够多少条 delta 就写 */
        val maxPendingDeltas: Int = 32,
        /** 攒够多少字符就写（主力条件） */
        val maxPendingChars: Int = 1200,
        /** 距上次写入最多等多久（毫秒） */
        val maxDelayMs: Long = 120L,
        /** [run] 的轮询粒度；同时也是 [maxDelayMs] 的判定精度 */
        val pollIntervalMs: Long = 20L,
    ) {
        init {
            require(maxPendingDeltas > 0) { "maxPendingDeltas 必须为正" }
            require(maxPendingChars > 0) { "maxPendingChars 必须为正" }
            require(maxDelayMs > 0) { "maxDelayMs 必须为正" }
            require(pollIntervalMs > 0) { "pollIntervalMs 必须为正" }
        }

        /**
         * 空缓冲区需要空转多少次轮询才算"超时"。
         *
         * ★ **必须向上取整**（修过的真 bug）：原先写的是整数除法 `maxDelay / pollInterval`，
         *   于是 `maxDelay = 121, pollInterval = 20` 得到 6 —— 6 × 20 = **120ms**，
         *   比配置的 121ms **早** 1ms 就 flush 了。单次误差看起来无所谓，
         *   但它意味着"配置的 maxDelay 是上界"这个承诺是假的。
         *   向上取整保证 `n × pollInterval >= maxDelay`，永远不早于配置值。
         */
        internal val idleTicksBeforeTimeout: Long
            get() {
                val ticks = (maxDelayMs + pollIntervalMs - 1) / pollIntervalMs
                return ticks.coerceAtLeast(1L)
            }
    }

    // ---------------------------------------------------------------
    // 内存侧状态（唯一的真值来源，DB 只是它的投影）
    // ---------------------------------------------------------------

    private val lock = Any()

    /** 已收到的全部正文 */
    private val content = StringBuilder()

    /** 已收到的全部推理内容 */
    private val reasoning = StringBuilder()

    /** 自上次 flush 起的 delta 条数（正文 + 推理合计，它们共用一次写库） */
    private var pendingDeltas = 0

    /** 自上次 flush 起的字符数 */
    private var pendingChars = 0

    /** 自上次 flush 起空转的轮询次数，用于判定 [BufferPolicy.maxDelayMs] */
    private var idleTicks = 0L

    /** 是否有尚未落库的改动 */
    private var dirty = false

    private val ioMutex = Mutex()

    /** 累计写库次数（测试与性能观测用） */
    @Volatile
    var flushCount: Int = 0
        private set

    /** 当前已收到的完整正文（内存快照，UI 可以直接拿它渲染，不必读库） */
    val text: String get() = synchronized(lock) { content.toString() }

    /** 当前已收到的完整推理内容；没有推理时为 null */
    val reasoningText: String?
        get() = synchronized(lock) {
            if (reasoning.isEmpty()) null else reasoning.toString()
        }

    // ---------------------------------------------------------------
    // 写入
    // ---------------------------------------------------------------

    /** 追加一段正文。越过阈值时会就地写库。 */
    suspend fun append(delta: String) {
        if (delta.isEmpty()) return
        val shouldFlush = synchronized(lock) {
            content.append(delta)
            pendingDeltas++
            pendingChars += delta.length
            idleTicks = 0
            dirty = true
            isThresholdReached()
        }
        if (shouldFlush) flush()
    }

    /** 追加一段推理内容。与正文共用同一次写库，各自独立累积。 */
    suspend fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return
        val shouldFlush = synchronized(lock) {
            reasoning.append(delta)
            pendingDeltas++
            pendingChars += delta.length
            idleTicks = 0
            dirty = true
            isThresholdReached()
        }
        if (shouldFlush) flush()
    }

    /**
     * 整体设置正文（重新生成 / 编辑消息后重建）。
     *
     * 与 [append] 的区别：这是**替换**，用于"这条消息要从头再来"。
     */
    suspend fun resetContent(newContent: String = "", newReasoning: String? = null) {
        synchronized(lock) {
            content.setLength(0)
            content.append(newContent)
            reasoning.setLength(0)
            newReasoning?.let { reasoning.append(it) }
            pendingDeltas++
            pendingChars += newContent.length
            idleTicks = 0
            dirty = true
        }
        flush()
    }

    private fun isThresholdReached(): Boolean =
        pendingDeltas >= policy.maxPendingDeltas || pendingChars >= policy.maxPendingChars

    // ---------------------------------------------------------------
    // 落库
    // ---------------------------------------------------------------

    /**
     * 把当前内存快照写进数据库。没有改动时是**零成本**的空操作。
     *
     * @return 实际影响的行数（0 表示没有改动，或消息行不存在）
     */
    suspend fun flush(): Int = ioMutex.withLock {
        // 取快照并清标记。注意这里就把 dirty 清掉了：
        // 写失败时 catch 会把它重新置上 —— 因为写的是绝对快照，重放是安全的。
        val snapshot = synchronized(lock) {
            if (!dirty) {
                null
            } else {
                val s = Snapshot(
                    content = content.toString(),
                    reasoning = if (reasoning.isEmpty()) null else reasoning.toString(),
                )
                dirty = false
                pendingDeltas = 0
                pendingChars = 0
                idleTicks = 0
                s
            }
        } ?: return@withLock 0

        try {
            val rows = dao.setContentAndReasoning(messageId, snapshot.content, snapshot.reasoning)
            if (rows > 0) flushCount++
            rows
        } catch (t: Throwable) {
            // 写失败：内容一个字都没丢（内存里还在），把 dirty 放回去等下次重试。
            // 因为是绝对写，重试不会造成重复片段。
            synchronized(lock) { dirty = true }
            throw t
        }
    }

    /** flush 用的不可变快照 */
    private data class Snapshot(val content: String, val reasoning: String?)

    /**
     * 空闲时的定时 flush —— 由外部循环每 [BufferPolicy.pollIntervalMs] 调一次。
     *
     * 只有"距上次写入已超过 [BufferPolicy.maxDelayMs]"且"确实有改动"才会真正写。
     * 之所以需要它：如果 delta 突然停了（网络卡住/模型在思考），
     * 缓冲里剩下的那几十个字符不该一直等到流结束才落库。
     *
     * @return 本次是否真的写了库
     */
    suspend fun tick(): Boolean {
        val due = synchronized(lock) {
            if (!dirty) {
                idleTicks = 0
                false
            } else {
                idleTicks++
                idleTicks >= policy.idleTicksBeforeTimeout
            }
        }
        if (!due) return false
        return flush() > 0
    }

    /**
     * 后台定时 flush 循环。挂在收集流的同一作用域里即可，随它一起取消。
     *
     * ```kotlin
     * coroutineScope {
     *     launch { writer.run() }        // 定时 flush
     *     provider.streamChat(req).collect { ... }   // 主收集
     * }
     * ```
     */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            delay(policy.pollIntervalMs)
            tick()
        }
    }

    // ---------------------------------------------------------------
    // 收尾
    // ---------------------------------------------------------------

    /** 正常结束：flush 后标 [MessageStatus.DONE]。 */
    suspend fun finish(reasoningMs: Long? = null) = finish(MessageStatus.DONE, null, reasoningMs)

    /** 用户点了"停止生成"：内容保留（不完整但它就是用户要的），状态标 stopped。 */
    suspend fun stop(reasoningMs: Long? = null) = finish(MessageStatus.STOPPED, null, reasoningMs)

    /** 出错：内容保留，错误原因单独存 —— 绝不能用错误信息覆盖正文。 */
    suspend fun fail(errorMessage: String?, reasoningMs: Long? = null) =
        finish(MessageStatus.ERROR, errorMessage, reasoningMs)

    /**
     * 收尾：flush 剩余内容 + 落状态。
     *
     * ★ 顺序很重要：**先 flush 再改状态**。反过来的话，如果 flush 失败，
     *   库里会留下一条"已完成"但正文被截断的消息 —— 那是**看起来能用但错了**，
     *   比直接失败难查得多。
     */
    suspend fun finish(
        status: MessageStatus,
        errorMessage: String? = null,
        reasoningMs: Long? = null,
    ) {
        flush()
        dao.setStatus(
            id = messageId,
            status = status.value,
            errorMessage = errorMessage,
            reasoningMs = reasoningMs,
        )
    }
}
