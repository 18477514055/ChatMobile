package com.chatmobile.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息查询与分页。
 *
 * ★ 这一组测试的重点是**分页边界**，不是"能查出数据"。
 *   分页的 bug 有个特点：小数据量下看起来完全正常。
 *   只有到了「正好整除」「只剩一条」「已到顶」「翻页期间插入新数据」这几种边界才暴露。
 */
class MessageDaoTest : RoomTestBase() {

    /** 建一个会话 + n 条消息，内容为 "m1".."mn" */
    private suspend fun seed(conversationId: String = "c-1", n: Int): List<MessageEntity> {
        conversations.insert(conversation(id = conversationId))
        val list = (1..n).map { i ->
            message(
                id = "$conversationId-m$i",
                conversationId = conversationId,
                content = "m$i",
                role = if (i % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            )
        }
        list.forEach { messages.insert(it) }
        return list
    }

    // ---------------------------------------------------------------
    // seq 分配
    // ---------------------------------------------------------------

    @Test
    fun `insert 会自动分配从 1 开始的连续 seq`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))

        val s1 = messages.insert(message(id = "a"))
        val s2 = messages.insert(message(id = "b"))
        val s3 = messages.insert(message(id = "c"))

        assertEquals(1L, s1)
        assertEquals(2L, s2)
        assertEquals(3L, s3)
    }

    @Test
    fun `seq 按会话独立编号，不互相影响`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        conversations.insert(conversation(id = "c-2"))

        assertEquals(1L, messages.insert(message(id = "m1", conversationId = "c-1")))
        assertEquals(2L, messages.insert(message(id = "m2", conversationId = "c-1")))
        assertEquals(1L, messages.insert(message(id = "n1", conversationId = "c-2")))
    }

    @Test
    fun `同毫秒插入的消息靠 seq 保持插入顺序（createdAt 相同时的关键）`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        // 三条消息 createdAt 完全相同 —— 模拟"用户消息 + 助手占位"同帧插入
        val sameTime = 1_700_000_000_000L
        repeat(3) { i ->
            messages.insert(message(id = "m$i", createdAt = sameTime, content = "m$i"))
        }

        val all = messages.allIn("c-1")
        assertEquals(listOf("m0", "m1", "m2"), all.map { it.id })
        // 时间戳确实相同 —— 证明顺序真的只靠 seq
        assertEquals(1, all.map { it.createdAt }.distinct().size)
    }

    @Test
    fun `删除中间一条后 seq 不会重排，新消息接在最大值之后`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(message(id = "m1"))
        messages.insert(message(id = "m2"))
        messages.insert(message(id = "m3"))

        messages.delete("m2")
        val newSeq = messages.insert(message(id = "m4"))

        assertEquals(4L, newSeq)
        assertEquals(listOf("m1", "m3", "m4"), messages.allIn("c-1").map { it.id })
    }

    // ---------------------------------------------------------------
    // 分页
    // ---------------------------------------------------------------

    @Test
    fun `游标分页可以从最新一路翻到最老，不重不漏`() = runBlocking {
        seed(n = 10)

        // 从最新往前翻，每页收集后**整体反转**（页顺序是"新→旧"，页内是"早→晚"）
        val pagesNewestFirst = mutableListOf<List<String>>()
        var cursor: Long? = null
        var guard = 0
        do {
            val page = messages.page("c-1", cursor, limit = 3)
            pagesNewestFirst += page.items.map { it.content }
            cursor = page.nextBeforeSeq
            guard++
        } while (cursor != null && guard < 20)

        // 反转页顺序才是完整的时间线
        val timeline = pagesNewestFirst.asReversed().flatten()

        assertEquals((1..10).map { "m$it" }, timeline)
        // 每页内部都是正序
        pagesNewestFirst.forEach { page ->
            assertEquals("页内必须正序：$page", page.sortedBy { it.removePrefix("m").toInt() }, page)
        }
    }

    @Test
    fun `页内顺序是正序（早到新）`() = runBlocking {
        seed(n = 10)
        val page = messages.page("c-1", beforeSeq = null, limit = 3)
        // 最新三条是 m8 m9 m10，正序应为 m8 m9 m10
        assertEquals(listOf("m8", "m9", "m10"), page.items.map { it.content })
    }

    @Test
    fun `正好整除时最后一页之后不能多吐一个空页`() = runBlocking {
        seed(n = 9)

        val p1 = messages.page("c-1", null, 3)
        val p2 = messages.page("c-1", p1.nextBeforeSeq, 3)
        val p3 = messages.page("c-1", p2.nextBeforeSeq, 3)

        assertEquals(3, p1.items.size)
        assertEquals(3, p2.items.size)
        assertEquals(3, p3.items.size)
        // ★ 这条就是修过的真 bug：正好用完时原先会声称"还有下一页"
        assertNull("9 条 / 每页 3 条，取完必须到头", p3.nextBeforeSeq)
        // 而且它确实取完了
        assertEquals(listOf("m1", "m2", "m3"), p3.items.map { it.content })
    }

    @Test
    fun `只剩一条时 nextBeforeSeq 为 null`() = runBlocking {
        seed(n = 4)
        val p1 = messages.page("c-1", null, 3)   // 拿到 m2 m3 m4
        assertEquals(3, p1.items.size)
        assertNotNull(p1.nextBeforeSeq)

        val p2 = messages.page("c-1", p1.nextBeforeSeq, 3)   // 只剩 m1
        assertEquals(listOf("m1"), p2.items.map { it.content })
        assertNull(p2.nextBeforeSeq)
    }

    @Test
    fun `空会话分页返回空页且不声称还能加载`() = runBlocking {
        conversations.insert(conversation(id = "empty"))
        val page = messages.page("empty", null, 10)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextBeforeSeq)
        assertFalse(page.canLoadMore)
    }

    @Test
    fun `翻页期间插入新消息不会导致重复或漏条`() = runBlocking {
        seed(n = 6)   // m1..m6

        // 第一页：最新 3 条 = m4 m5 m6
        val p1 = messages.page("c-1", null, 3)
        val firstIds = p1.items.map { it.id }
        assertEquals(listOf("m4", "m5", "m6"), p1.items.map { it.content })

        // ★ 关键动作：翻页途中来了新消息（聊天里这是常态，不是边界情况）
        messages.insert(message(id = "c-1-m7", content = "m7"))
        messages.insert(message(id = "c-1-m8", content = "m8"))

        // 第二页用第一页给的游标继续
        val p2 = messages.page("c-1", p1.nextBeforeSeq, 3)

        // 不能重复：第二页不能包含第一页的任何一条
        val overlap = p2.items.map { it.id }.intersect(firstIds.toSet())
        assertTrue("游标分页不该重复返回第一页的消息，实际重叠=$overlap", overlap.isEmpty())

        // 也不能漏：第二页应正好是老的三条 m1 m2 m3
        assertEquals(listOf("m1", "m2", "m3"), p2.items.map { it.content })
    }

    @Test
    fun `偏移分页可以按 seq 正序取到指定区间`() = runBlocking {
        seed(n = 10)

        val page2 = messages.pageByOffset("c-1", limit = 3, offset = 3)
        assertEquals(listOf("m4", "m5", "m6"), page2.map { it.content })

        val first = messages.pageByOffset("c-1", limit = 3, offset = 0)
        assertEquals(listOf("m1", "m2", "m3"), first.map { it.content })
    }

    @Test
    fun `latest 返回降序，latestInOrder 才给正序`() = runBlocking {
        seed(n = 5)
        assertEquals(listOf("m5", "m4"), messages.latest("c-1", 2).map { it.content })
        assertEquals(listOf("m4", "m5"), messages.latest("c-1", 2).toAscending().map { it.content })
    }

    @Test
    fun `countIn 与 lastIn`() = runBlocking {
        seed(n = 3)
        assertEquals(3, messages.countIn("c-1"))
        assertEquals("m3", messages.lastIn("c-1")!!.content)
        assertEquals(0, messages.countIn("nobody"))
        assertNull(messages.lastIn("nobody"))
    }

    // ---------------------------------------------------------------
    // 状态与崩溃恢复
    // ---------------------------------------------------------------

    @Test
    fun `setStatus 能同时落 errorMessage 与 reasoningMs`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(message(id = "m1", status = MessageStatus.STREAMING))

        messages.setStatus(
            id = "m1",
            status = MessageStatus.ERROR.value,
            errorMessage = "连接被重置",
            reasoningMs = 2_345L,
        )

        val m = messages.findById("m1")!!
        assertEquals(MessageStatus.ERROR, m.statusEnum)
        assertEquals("连接被重置", m.errorMessage)
        assertEquals(2_345L, m.reasoningMs)
    }

    @Test
    fun `stopInFlight 把所有 streaming 与 pending 标成 stopped`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(message(id = "streaming-1", status = MessageStatus.STREAMING))
        messages.insert(message(id = "pending-1", status = MessageStatus.PENDING))
        messages.insert(message(id = "done-1", status = MessageStatus.DONE))
        messages.insert(message(id = "error-1", status = MessageStatus.ERROR))
        messages.insert(message(id = "stopped-1", status = MessageStatus.STOPPED))

        val rows = messages.stopInFlight()

        assertEquals(2, rows)
        assertEquals(MessageStatus.STOPPED, messages.findById("streaming-1")!!.statusEnum)
        assertEquals(MessageStatus.STOPPED, messages.findById("pending-1")!!.statusEnum)
        // 已经是终态的不许被改
        assertEquals(MessageStatus.DONE, messages.findById("done-1")!!.statusEnum)
        assertEquals(MessageStatus.ERROR, messages.findById("error-1")!!.statusEnum)
        assertEquals(MessageStatus.STOPPED, messages.findById("stopped-1")!!.statusEnum)
    }

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    @Test
    fun `deleteFrom 删掉某条及之后的所有消息，之前的不动`() = runBlocking {
        seed(n = 5)

        val fromSeq = messages.findById("c-1-m3")!!.seq
        val rows = messages.deleteFrom("c-1", fromSeq)

        assertEquals(3, rows)   // m3 m4 m5
        assertEquals(listOf("m1", "m2"), messages.allIn("c-1").map { it.content })
    }

    @Test
    fun `deleteAllIn 只清掉指定会话`() = runBlocking {
        seed("c-1", 3)
        seed("c-2", 2)

        messages.deleteAllIn("c-1")

        assertEquals(0, messages.countIn("c-1"))
        assertEquals(2, messages.countIn("c-2"))
    }

    // ---------------------------------------------------------------
    // reasoning 与正文分离
    // ---------------------------------------------------------------

    @Test
    fun `reasoning 与 content 是两个独立字段，互不污染`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(
            message(id = "m1", role = MessageRole.ASSISTANT, content = "答案", reasoning = "思考过程"),
        )

        val m = messages.findById("m1")!!
        assertEquals("答案", m.content)
        assertEquals("思考过程", m.reasoning)
        // 落库后两者仍是独立的，没有拼在一起
        assertFalse(m.content.contains("思考过程"))
    }

    @Test
    fun `setReasoning 不影响正文`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(message(id = "m1", content = "正文"))

        messages.setReasoning("m1", "推理")

        assertEquals("正文", messages.findById("m1")!!.content)
        assertEquals("推理", messages.findById("m1")!!.reasoning)
    }
}
