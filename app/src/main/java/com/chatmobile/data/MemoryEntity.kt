package com.chatmobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chatmobile.memory.MemoryEntry
import chatmobile.memory.MemoryKind

/**
 * 记忆表。
 *
 * ## ★ 与 MVP 方案 §2.1 的一处**刻意偏离**：不存 `weight`
 *
 * 方案里写了 `val weight: Float = 1.0f`，但 `core-memory` 的 `MemoryEntry` **没有**这个字段。
 * 这不是遗漏，是那边的设计正确：
 *
 * > 权重是**时间的函数**（`Decay.effectiveWeight` = 0.5^(age/halfLife) × 命中加成）。
 * > 一旦落库，它在下一次被读到时就**已经是错的**了。
 *
 * 存下来的权重只有两种命运：要么被当成真值用（于是排序按陈旧的数走），
 * 要么每次读都重算（于是这个字段纯属浪费）。所以这里**不存**，只存算权重需要的原料：
 * [createdAt] / [lastUsedAt] / [hitCount] / [kind]。
 *
 * ## 字段一一对应 `MemoryEntry`，一个不丢
 *
 * 特别是这三个 —— 它们各自撑着一条官方行为，丢了就等于砍功能：
 * - [hitCount] → 决定 `autoPromote()` 能不能触发（≥3 次提升为 saved）
 * - [lastUsedAt] → 决定衰减从哪个时间点算（经常被用的老记忆不该沉底）
 * - [deleted] → 墓碑，撑起 "Saved memory (deleted)"，同时保证 `restore()` 可用
 */
@Entity(
    tableName = "memory",
    indices = [
        // MemoryBank.stats() / autoPromote() 都是按 kind + deleted 过滤
        Index(value = ["deleted", "kind"]),
        // 衰减排序要按 lastUsedAt
        Index("lastUsedAt"),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    /** "saved" | "recent" —— 见 [MemoryKind] */
    val kind: String,
    /** ★ 可解释性："Why this memory?" 要能回答「从哪条对话来的」 */
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    val createdAt: Long,
    /** 最近一次被召回的时间；从未召回过为 null */
    val lastUsedAt: Long? = null,
    /** 被召回次数 */
    val hitCount: Int = 0,
    /** 墓碑。物理行仍在，物理删除只由用户"彻底删除"触发 */
    val deleted: Boolean = false,
    /**
     * 结构化槽位键（`pref.reply_style` 等）。null = 旧数据 / 自由文本。
     *
     * ★ 同 key（非 event.*）只有一个值 —— 这是去重的结构性保证。
     * ★ 加列是 v2→v3 纯增量迁移（`ALTER TABLE ... ADD COLUMN`），不动已有行。
     */
    val key: String? = null,
) {
    /** 转回 core-memory 的领域模型。 */
    fun toEntry(): MemoryEntry = MemoryEntry(
        id = id,
        content = content,
        kind = if (kind == MemoryKind.SAVED.name.lowercase()) MemoryKind.SAVED else MemoryKind.RECENT,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        hitCount = hitCount,
        deleted = deleted,
        key = key,
    )

    companion object {
        const val KIND_SAVED = "saved"
        const val KIND_RECENT = "recent"

        fun from(entry: MemoryEntry): MemoryEntity = MemoryEntity(
            id = entry.id,
            content = entry.content,
            kind = when (entry.kind) {
                MemoryKind.SAVED -> KIND_SAVED
                MemoryKind.RECENT -> KIND_RECENT
            },
            sourceConversationId = entry.sourceConversationId,
            sourceMessageId = entry.sourceMessageId,
            createdAt = entry.createdAt,
            lastUsedAt = entry.lastUsedAt,
            hitCount = entry.hitCount,
            deleted = entry.deleted,
            key = entry.key,
        )
    }
}
