package com.chatmobile.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatRepository] —— 跨表约定的守卫。
 *
 * 这里不重复测 DAO 已经测过的东西，只测**仓储自己加的约定**：
 * 1. 插入消息会推会话 `updatedAt`
 * 2. 空白标题有兜底、重命名会被拒绝
 * 3. 删除会话时消息连带消失（通过仓储这条路径）
 * 4. 首屏 + 往上翻的完整流程能走通
 */
class ChatRepositoryTest : RoomTestBase() {

    /** 默认用固定时钟 + 递增 id，让断言可以写成精确值 */
    private fun newRepo(clock: () -> Long = { now }): ChatRepository {
        var nextId = 0
        return ChatRepository(db, clock = clock, idGenerator = { "gen-${nextId++}" })
    }

    @Test
    fun `createConversation 生成合法 id 与默认标题`() = runBlocking {
        val repo = newRepo()

        val c = repo.createConversation()

        assertTrue("id 不应为空", c.id.isNotBlank())
        assertEquals(ChatRepository.DEFAULT_TITLE, c.title)
        assertEquals(c.createdAt, c.updatedAt)
        assertNotNull(repo.conversation(c.id))
    }

    @Test
    fun `空白标题用默认标题兜底，不允许侧边栏出现空白行`() = runBlocking {
        val repo = newRepo()

        assertEquals(ChatRepository.DEFAULT_TITLE, repo.createConversation(title = "   ").title)
        assertEquals(ChatRepository.DEFAULT_TITLE, repo.createConversation(title = "").title)
        assertEquals("带空格的标题应去掉首尾空白", "真标题", repo.createConversation(title = "  真标题  ").title)
    }

    @Test
    fun `renameConversation 拒绝空白标题，不写库`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation(title = "原标题")

        assertFalse(repo.renameConversation(c.id, "   "))
        assertFalse(repo.renameConversation(c.id, ""))
        assertEquals("原标题", repo.conversation(c.id)!!.title)

        assertTrue(repo.renameConversation(c.id, " 新标题 "))
        assertEquals("新标题", repo.conversation(c.id)!!.title)
    }

    @Test
    fun `appendMessage 会把会话的 updatedAt 推到当前`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        val createdAt = c.updatedAt

        now += 5_000
        repo.appendMessage(c.id, MessageRole.USER, content = "你好")

        val reloaded = repo.conversation(c.id)!!
        assertTrue(
            "插入消息必须推 updatedAt，否则侧边栏排序永远不动（实际 $createdAt -> ${reloaded.updatedAt}）",
            reloaded.updatedAt > createdAt,
        )
    }

    @Test
    fun `appendMessage 返回带 seq 的完整实体`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()

        val m1 = repo.appendMessage(c.id, MessageRole.USER, content = "第一句")
        val m2 = repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "第一答")

        assertTrue("seq 必须在返回实体里（流式 writer 需要它）", m1.seq > 0)
        assertEquals(m1.seq + 1, m2.seq)
        assertEquals(MessageRole.USER, m1.roleEnum)
        assertEquals(MessageRole.ASSISTANT, m2.roleEnum)
    }

    @Test
    fun `角色与状态用枚举落库，读回归一化正确`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()

        repo.appendMessage(c.id, MessageRole.SYSTEM, content = "系统提示")
        repo.appendMessage(c.id, MessageRole.USER, content = "问", status = MessageStatus.DONE)
        repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "", status = MessageStatus.STREAMING)

        val all = repo.latestMessages(c.id, 100)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
            all.map { it.roleEnum },
        )
        assertEquals(
            listOf(MessageStatus.PENDING, MessageStatus.DONE, MessageStatus.STREAMING),
            all.map { it.statusEnum },
        )
    }

    @Test
    fun `父消息 id 支持分支`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()

        val root = repo.appendMessage(c.id, MessageRole.USER, content = "原问题")
        val branch1 = repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "回答 A", parentId = root.id)
        val branch2 = repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "回答 B", parentId = root.id)

        assertEquals(root.id, branch1.parentId)
        assertEquals(root.id, branch2.parentId)
        assertNull(root.parentId)
    }

    @Test
    fun `streamingWriter 拒绝为不存在的消息建写入器`() = runBlocking {
        val repo = newRepo()

        val failed = runCatching { repo.streamingWriter("ghost") }.isFailure

        assertTrue(
            "必须提前失败：否则每次 flush 都影响 0 行，表现为'流式一直不出字'且没有任何报错",
            failed,
        )
    }

    @Test
    fun `streamingWriter 走完整流程后库里是完整正文`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        val userMsg = repo.appendMessage(c.id, MessageRole.USER, content = "讲个笑话")
        val assistant = repo.appendMessage(
            c.id, MessageRole.ASSISTANT, status = MessageStatus.STREAMING, parentId = userMsg.id,
        )

        val writer = repo.streamingWriter(assistant.id)
        listOf("从前", "有个人", "他讲了", "个笑话").forEach { writer.append(it) }
        writer.finish(reasoningMs = 800)

        val loaded = repo.message(assistant.id)!!
        assertEquals("从前有个人他讲了个笑话", loaded.content)
        assertEquals(MessageStatus.DONE, loaded.statusEnum)
        assertEquals(800L, loaded.reasoningMs)
    }

    @Test
    fun `latestMessages 返回正序，pageMessages 从最新开始`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        (1..10).forEach { repo.appendMessage(c.id, MessageRole.USER, content = "m$it") }

        val latest3 = repo.latestMessages(c.id, 3)
        assertEquals(listOf("m8", "m9", "m10"), latest3.map { it.content })

        val page = repo.pageMessages(c.id, beforeSeq = null, limit = 3)
        assertEquals(listOf("m8", "m9", "m10"), page.items.map { it.content })
        assertNotNull(page.nextBeforeSeq)
    }

    @Test
    fun `完整翻页流程能读到全部消息且顺序正确`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        (1..25).forEach { repo.appendMessage(c.id, MessageRole.USER, content = "m$it") }

        // 从最新往前翻，每页 10 条：25 = 10 + 10 + 5，正好 3 页
        val pagesNewestFirst = mutableListOf<MessagePage>()
        var cursor: Long? = null
        var guard = 0
        do {
            val p = repo.pageMessages(c.id, cursor, limit = 10)
            pagesNewestFirst += p
            cursor = p.nextBeforeSeq
            guard++
        } while (cursor != null && guard < 10)

        assertEquals("25 条 / 每页 10 条应是 3 页", 3, pagesNewestFirst.size)
        // 页序是"新→旧"，页内正序
        assertEquals((16..25).map { "m$it" }, pagesNewestFirst[0].items.map { it.content })
        assertEquals((6..15).map { "m$it" }, pagesNewestFirst[1].items.map { it.content })
        assertEquals((1..5).map { "m$it" }, pagesNewestFirst[2].items.map { it.content })
        assertNull("翻完必须到头，否则界面会永远显示'加载更多'", pagesNewestFirst[2].nextBeforeSeq)

        // 拼起来必须是完整时间线，不重不漏
        val timeline = pagesNewestFirst.asReversed().flatMap { it.items }.map { it.content }
        assertEquals((1..25).map { "m$it" }, timeline)
    }

    @Test
    fun `删除会话连带删消息（走仓储路径）`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        repeat(3) { repo.appendMessage(c.id, MessageRole.USER, content = "m$it") }

        assertEquals(3, repo.messageCount(c.id))
        assertTrue(repo.deleteConversation(c.id))

        assertEquals(0, repo.messageCount(c.id))
        assertNull(repo.conversation(c.id))
    }

    @Test
    fun `recoverInterruptedStreams 修复上次崩溃留下的半截消息`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        repo.appendMessage(c.id, MessageRole.USER, content = "问", status = MessageStatus.DONE)
        repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "答到一半", status = MessageStatus.STREAMING)

        val fixed = repo.recoverInterruptedStreams()

        assertEquals(1, fixed)
        val all = repo.latestMessages(c.id, 10)
        assertEquals(MessageStatus.DONE, all[0].statusEnum)
        assertEquals(MessageStatus.STOPPED, all[1].statusEnum)
        // 内容保留，不丢
        assertEquals("答到一半", all[1].content)
    }

    @Test
    fun `deleteFrom 用于重新生成`() = runBlocking {
        val repo = newRepo()
        val c = repo.createConversation()
        repo.appendMessage(c.id, MessageRole.USER, content = "问题")
        val answer = repo.appendMessage(c.id, MessageRole.ASSISTANT, content = "旧答案")
        repo.appendMessage(c.id, MessageRole.USER, content = "追问")

        val deleted = repo.deleteFrom(c.id, answer.seq)

        assertEquals(2, deleted)
        assertEquals(listOf("问题"), repo.latestMessages(c.id, 10).map { it.content })
    }

    @Test
    fun `固定时钟下可以断言精确时间戳`() = runBlocking {
        now = 1_700_000_000_000L
        val repo = newRepo()

        val c = repo.createConversation()
        assertEquals(1_700_000_000_000L, c.createdAt)
        assertEquals(1_700_000_000_000L, c.updatedAt)
    }
}
