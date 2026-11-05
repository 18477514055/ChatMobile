package com.chatmobile.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 会话 + 消息的仓储 —— 上层（ViewModel）唯一需要的门面。
 *
 * ## 为什么还要这一层
 *
 * DAO 只是"SQL 的映射"。真正容易出错的是**跨表的约定**：
 *
 * - 插入消息必须 `touch` 会话的 `updatedAt`（否则侧边栏排序不动）
 * - 新建会话要有合法的 UUID 和标题兜底
 * - 分页第一页的游标起点
 *
 * 这些如果散在 ViewModel 里，某个调用点漏掉一条就变成一个
 * "看起来正常但顺序不对"的 bug。集中在这里，就只有一个地方要查。
 *
 * ★ 全部 suspend：Room 的 DAO 是 suspend 的，这一层不引入 `runBlocking`，
 *   也不假装同步。UI 侧用 `viewModelScope` 调。
 */
class ChatRepository(
    private val db: ChatDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()

    // ---------------------------------------------------------------
    // 会话
    // ---------------------------------------------------------------

    /**
     * 新建会话。
     *
     * @param title 空串或纯空白时用 [DEFAULT_TITLE] 兜底 —— 侧边栏不允许出现空白行
     */
    suspend fun createConversation(
        title: String = DEFAULT_TITLE,
        modelId: String? = null,
        systemPromptOverride: String? = null,
    ): ConversationEntity {
        val now = clock()
        val entity = ConversationEntity(
            id = idGenerator(),
            title = title.trim().ifEmpty { DEFAULT_TITLE },
            createdAt = now,
            updatedAt = now,
            modelId = modelId,
            systemPromptOverride = systemPromptOverride,
        )
        conversations.insert(entity)
        return entity
    }

    suspend fun conversation(id: String): ConversationEntity? = conversations.findById(id)

    suspend fun listConversations(includeArchived: Boolean = false): List<ConversationEntity> =
        conversations.list(includeArchived)

    fun observeConversations(includeArchived: Boolean = false): Flow<List<ConversationEntity>> =
        conversations.observe(includeArchived)

    /** 重命名。空白标题会被忽略（返回 false），不写进库。 */
    suspend fun renameConversation(id: String, title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        return conversations.rename(id, clean) > 0
    }

    suspend fun setPinned(id: String, pinned: Boolean): Boolean = conversations.setPinned(id, pinned) > 0

    suspend fun setArchived(id: String, archived: Boolean): Boolean =
        conversations.setArchived(id, archived) > 0

    /** 删除会话。消息由外键 CASCADE 连带删除（数据库已开 `foreign_keys`）。 */
    suspend fun deleteConversation(id: String): Boolean = conversations.delete(id) > 0

    /**
     * 给会话绑定/解绑工作区。
     *
     * ★ 不动 `updatedAt`：换目录是**改设置**，不是"有新内容"，
     *   不该让这条会话跳到侧边栏顶部。
     */
    suspend fun setWorkspace(conversationId: String, workspaceId: String?): Boolean =
        conversations.setWorkspaceId(conversationId, workspaceId) > 0

    /** 改本会话的工作区权限档位（null = 用默认档）。 */
    suspend fun setWorkspacePermission(conversationId: String, permission: String?): Boolean =
        conversations.setWorkspacePermission(conversationId, permission) > 0

    // ---------------------------------------------------------------
    // 消息
    // ---------------------------------------------------------------

    /**
     * 追加一条消息，并顺手把会话的 `updatedAt` 推到当前。
     *
     * ★ `touch` 与插入**不是**在同一个事务里。刻意的：
     *   让会话排序在"消息插入失败"时不跟着乱动，也更少锁冲突。
     *   代价是极端情况下会话 `updatedAt` 比消息新 —— 无害（排序只需要"最近有过活动"）。
     */
    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String = "",
        reasoning: String? = null,
        status: MessageStatus = MessageStatus.PENDING,
        parentId: String? = null,
        id: String = idGenerator(),
        createdAt: Long = clock(),
        /**
         * 附件**元数据**（不含字节）。
         *
         * ★ 空列表会存成 NULL —— 见 `MessageAttachmentCodec.encode` 的注释。
         */
        attachments: List<MessageAttachmentMeta> = emptyList(),
    ): MessageEntity {
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role.value,
            content = content,
            reasoning = reasoning,
            status = status.value,
            createdAt = createdAt,
            parentId = parentId,
            attachments = MessageAttachmentCodec.encode(attachments),
        )
        messages.insert(entity)
        conversations.touch(conversationId, clock())
        // 重新读一次拿到分配好的 seq —— 调用方（尤其流式）需要它来建 writer
        return messages.findById(id) ?: entity
    }

    /**
     * 给"即将流式输出"的助手消息建一个缓冲写入器。
     *
     * 消息必须是已经插入的（有 id），否则每次 flush 都会影响 0 行。
     */
    suspend fun streamingWriter(
        messageId: String,
        policy: StreamingMessageWriter.BufferPolicy = StreamingMessageWriter.BufferPolicy(),
    ): StreamingMessageWriter {
        require(messages.findById(messageId) != null) {
            "消息 $messageId 不存在，不能建流式写入器（否则 flush 永远影响 0 行）"
        }
        return StreamingMessageWriter(messages, messageId, policy)
    }

    /** 首屏：最近 [limit] 条（正序）。 */
    suspend fun latestMessages(conversationId: String, limit: Int = DEFAULT_PAGE_SIZE): List<MessageEntity> =
        messages.latest(conversationId, limit).toAscending()

    /**
     * 往上翻历史的一页。
     *
     * @param beforeSeq 上一页返回的 [MessagePage.nextBeforeSeq]；首次传 null
     *
     * ★ 内部**多取一条**（`limit + 1`）来判断是否到顶 —— 详见 [MessagePaging.assemble]。
     *   传 `limit` 给 DAO 是本文件里最容易犯的错，所以这里用注释钉死。
     */
    suspend fun pageMessages(
        conversationId: String,
        beforeSeq: Long? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): MessagePage {
        require(limit > 0) { "limit 必须为正" }
        val cursor = beforeSeq ?: Long.MAX_VALUE
        val raw = messages.pageBefore(conversationId, cursor, limit + 1)
        return MessagePaging.assemble(raw, limit)
    }

    suspend fun message(id: String): MessageEntity? = messages.findById(id)

    /**
     * 取一段消息 id 区间内的全部 id（含两端）。
     *
     * 供压缩层展开「哪一段已被摘要覆盖」。★ id 是 UUID 不保序，
     * 所以 DAO 里先用两端查出 seq 再按 seq 取区间（见 `MessageDao.idsBetween`）。
     */
    suspend fun idsBetween(conversationId: String, fromId: String, toId: String): List<String> =
        messages.idsBetween(conversationId, fromId, toId)

    suspend fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messages.observe(conversationId)

    suspend fun messageCount(conversationId: String): Int = messages.countIn(conversationId)

    suspend fun deleteMessage(id: String): Boolean = messages.delete(id) > 0

    /** "重新生成"：删掉某条及其之后的所有消息。 */
    suspend fun deleteFrom(conversationId: String, fromSeq: Long): Int =
        messages.deleteFrom(conversationId, fromSeq)

    /** 崩溃恢复：把上次遗留在 `streaming` / `pending` 的消息标成 stopped。
     *
     * **App 启动时调一次。** 详见 [MessageDao.stopInFlight]。
     */
    suspend fun recoverInterruptedStreams(): Int = messages.stopInFlight()

    // ---------------------------------------------------------------
    // ★★★ 跨会话历史检索（`search_history` 工具的落地）
    // ---------------------------------------------------------------

    /**
     * 按关键词跨会话搜消息。
     *
     * ★ **排除当前会话**：它已经在模型的上下文里了，再搜出自己刚说过的话
     *   只是浪费 token，而且会让模型误以为"这是从历史里查到的"。
     */
    suspend fun searchMessagesAcross(
        query: String,
        excludeConversationId: String?,
        limit: Int,
    ): List<MessageEntity> =
        messages.searchAcrossConversations(query, excludeConversationId, limit)

    /** 某会话最近的若干条用户消息 —— 用来概括"这个对话在干什么"。 */
    suspend fun recentUserMessages(conversationId: String, limit: Int): List<MessageEntity> =
        messages.recentUserMessages(conversationId, limit)

    // ★ 这里刻意**不提供** "把 writer 的内存内容刷进库" 的方法。
    //   想立刻让库里有内容，就调 `writer.flush()` —— 那是缓冲器自己的职责，
    //   它知道 dirty 标记怎么维护。绕过去直接写 setContent 会让缓冲器以为
    //   "还没写过"，下一次 flush 再把旧快照盖回去。少一个入口，少一类 bug。

    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val DEFAULT_PAGE_SIZE = 40
    }
}
