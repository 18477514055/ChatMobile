package com.chatmobile.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息表。
 *
 * ## 对 MVP 方案模型的两处调整（都已在 README 里说明理由）
 *
 * 1. **加了 [seq]（会话内单调序号）**
 *    排序如果只用 `createdAt`，会出现同毫秒碰撞：用户消息与助手占位消息是
 *    在同一次点击里连着插入的，`System.currentTimeMillis()` 经常给出**相同**的值。
 *    一旦相同，SQLite 的排序就退化成"磁盘顺序"，而**分页会因此错乱**——
 *    同一条消息可能在第 1 页和第 2 页各出现一次，或者干脆被跳过。
 *    这不是理论问题，是 `LIMIT/OFFSET` 分页的经典陷阱。
 *    `seq` 由 [MessageDao.insert] 在同一事务里取 `MAX(seq)+1` 分配，保证严格递增。
 *
 * 2. **加了外键 CASCADE**
 *    删会话必须连带删消息，否则会留下永久孤儿行（越用越大，且永远不会被发现）。
 *    靠应用层记得手动删是不可靠的，交给 SQLite。
 *
 * ## 为什么 role / status 是 String 而不是 enum
 * 用 TypeConverter 把 enum 落库要额外写转换器，且**枚举改名就会静默读错**。
 * 这里用 String 落库、用 [MessageRole] / [MessageStatus] 在代码侧做类型约束，
 * 再由两边各提供一个解析函数做边界转换——边界只有一处，比散落的 Converter 好查。
 */
@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        // 分页顺序的支撑索引；唯一约束顺带防住 seq 重复
        Index(value = ["conversationId", "seq"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** user / assistant / system —— 见 [MessageRole] */
    val role: String,
    /** 正文。流式期间会被 [MessageDao.appendContent] 不断追加 */
    val content: String = "",
    /** ★ 推理内容**单独存**：它可折叠、可回看，且绝不能混进正文（混进去就再也拆不开） */
    val reasoning: String? = null,
    /** ★ 思考耗时（毫秒），用于 "Thought for Ns"（0x7f1315d3） */
    val reasoningMs: Long? = null,
    /** pending / streaming / done / error / stopped —— 见 [MessageStatus] */
    val status: String = MessageStatus.PENDING.value,
    val errorMessage: String? = null,
    val createdAt: Long,
    /** ★ 支持分支（`Branch in new chat`）：这条消息接在哪条之后 */
    val parentId: String? = null,
    /** 会话内单调序号，见类注释。0 表示"尚未分配"，由 insert 时填上 */
    val seq: Long = 0L,
)

/** 角色。落库值就是 [value]，不要改 —— 改了历史数据就读不出来了。 */
enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    ;

    companion object {
        fun parse(raw: String): MessageRole? = entries.firstOrNull { it.value == raw }
    }
}

/**
 * 消息状态机。
 *
 * ```
 * pending ──► streaming ──► done
 *    │            ├─────► stopped   （用户点"停止生成"）
 *    │            └─────► error     （网络/协议错误，errorMessage 带原因）
 *    └──────────────────► error
 * ```
 *
 * ★ [STREAMING] 与 [STOPPED] 是分开的：进程被杀时，库里留下的
 * `streaming` 行说明"这条是被中断的，内容不完整"。启动时扫一遍把它们
 * 标成 [STOPPED]，界面上就能如实显示，而不是伪装成已完成的回答。
 */
enum class MessageStatus(val value: String) {
    PENDING("pending"),
    STREAMING("streaming"),
    DONE("done"),
    ERROR("error"),
    STOPPED("stopped"),
    ;

    companion object {
        fun parse(raw: String): MessageStatus? = entries.firstOrNull { it.value == raw }

        /** 内容已经不会再变了（可以安全地整条覆盖） */
        fun isTerminal(raw: String): Boolean = raw == DONE.value || raw == ERROR.value || raw == STOPPED.value
    }
}

// ---- 便利访问器：让上层写 `msg.roleEnum` 而不是到处 parse 字符串 ----

val MessageEntity.roleEnum: MessageRole?
    get() = MessageRole.parse(role)

val MessageEntity.statusEnum: MessageStatus?
    get() = MessageStatus.parse(status)
