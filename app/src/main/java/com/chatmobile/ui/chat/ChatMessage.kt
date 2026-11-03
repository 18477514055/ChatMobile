package com.chatmobile.ui.chat

/**
 * 聊天消息模型。
 *
 * ⚠️ 这是 UI 层的**展示模型**，不是持久化模型。
 *    `data/` 和 `net/` 由别的队友负责，集成时做一次映射即可（不要反向依赖）。
 *
 * 保留 `reasoning` 字段是有意的：DeepSeek 等厂商会把思维链单独放在
 * `reasoning_content` 里（见 net 队友的协议笔记），UI 需要能折叠展示它。
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    /** 思维链 / 推理过程。为空表示这家模型不返回推理。 */
    val reasoning: String? = null,
    /** 这条消息是否还在流式接收中（接收完就是 false）。 */
    val isStreaming: Boolean = false,
    /** 时间戳，用于分组展示。 */
    val timestamp: Long = 0L,
) {
    enum class Role { User, Assistant }
}

/** 侧边栏里的会话条目。同样只是展示模型。 */
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long = 0L,
)
