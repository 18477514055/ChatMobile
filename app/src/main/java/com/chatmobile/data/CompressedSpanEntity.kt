package com.chatmobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chatmobile.memory.CompressedSpan

/**
 * 压缩记录表。
 *
 * ## 存的是「摘要把哪一段折掉了」，不是被折掉的内容本身
 *
 * ★ 这是本表和「压缩」这个词最容易混淆的地方：
 *   被折掉的消息**原封不动还在 `messages` 表里**，一条都没删。
 *   本表只记录「哪一段已经被摘要代表了，不用再原样喂给模型」。
 *
 *   ⇒ 所以用户随时能翻回原文，模型也能按 `fromMessageId`/`toMessageId` 回库读。
 *   这是与业界主流做法（摘要**替换**原文、逐字保留率约 12%）的根本差别。
 *
 * ## 字段一一对应 `CompressedSpan`，一个不丢
 *
 * 因为要支持"读回原文"这件事，指针必须完整；
 * 少存一个字段，那条压缩记录就变成不可回溯的死记录。
 */
@Entity(
    tableName = "compressed_spans",
    indices = [
        // 按会话查询压缩记录（渲染历史、判断哪些段已被折）
        Index("conversationId"),
        // 按时间排序
        Index("createdAt"),
    ],
)
data class CompressedSpanEntity(
    @PrimaryKey
    val id: String,

    /** 属于哪个会话 */
    val conversationId: String,

    /** 摘要正文（给模型看的那段） */
    val summary: String,

    /** 被折掉的历史：起始消息 id（含） */
    val fromMessageId: String,

    /** 被折掉的历史：结束消息 id（含） */
    val toMessageId: String,

    /** 被折掉的消息条数 */
    val messageCount: Int,

    /** 估算 token，用于水位累计 */
    val approxTokens: Int,

    val createdAt: Long,
) {
    fun toSpan(): CompressedSpan = CompressedSpan(
        id = id,
        summary = summary,
        fromMessageId = fromMessageId,
        toMessageId = toMessageId,
        messageCount = messageCount,
        approxTokens = approxTokens,
        createdAt = createdAt,
    )

    companion object {
        fun from(conversationId: String, span: CompressedSpan): CompressedSpanEntity =
            CompressedSpanEntity(
                id = span.id,
                conversationId = conversationId,
                summary = span.summary,
                fromMessageId = span.fromMessageId,
                toMessageId = span.toMessageId,
                messageCount = span.messageCount,
                approxTokens = span.approxTokens,
                createdAt = span.createdAt,
            )
    }
}
