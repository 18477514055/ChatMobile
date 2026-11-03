package com.chatmobile.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 CRUD。
 *
 * 重点不是"能不能增删改查"，而是三件容易出错的事：
 * 1. 列表**排序规则**（置顶优先 + 更新时间倒序）
 * 2. `rename` / `pinned` / `archived` **不应该**推 `updatedAt`（否则会话会乱跳）
 * 3. 删除会话必须**连带删消息**（外键 CASCADE 是否真的生效）
 */
class ConversationDaoTest : RoomTestBase() {

    @Test
    fun `insert 后能按 id 读回，字段一个不丢`() = runBlocking {
        val c = conversation(id = "c-1", title = "第一段对话", modelId = "deepseek-flash")
        conversations.insert(c)

        val loaded = conversations.findById("c-1")
        assertNotNull(loaded)
        assertEquals("第一段对话", loaded!!.title)
        assertEquals("deepseek-flash", loaded.modelId)
        assertEquals(c.createdAt, loaded.createdAt)
        assertEquals(c.updatedAt, loaded.updatedAt)
        assertFalse(loaded.pinned)
        assertFalse(loaded.archived)
    }

    @Test
    fun `不存在的 id 返回 null，而不是抛异常`() = runBlocking {
        assertNull(conversations.findById("不存在"))
    }

    @Test
    fun `列表按 updatedAt 倒序`() = runBlocking {
        conversations.insert(conversation(id = "old", title = "旧", updatedAt = 1_000))
        conversations.insert(conversation(id = "new", title = "新", updatedAt = 3_000))
        conversations.insert(conversation(id = "mid", title = "中", updatedAt = 2_000))

        val titles = conversations.list(includeArchived = false).map { it.id }
        assertEquals(listOf("new", "mid", "old"), titles)
    }

    @Test
    fun `置顶的排在所有未置顶之前，即使它更旧`() = runBlocking {
        conversations.insert(conversation(id = "recent", updatedAt = 9_000))
        conversations.insert(conversation(id = "pinned-old", updatedAt = 1_000, pinned = true))
        conversations.insert(conversation(id = "middle", updatedAt = 5_000))

        val ids = conversations.list(includeArchived = false).map { it.id }
        assertEquals(listOf("pinned-old", "recent", "middle"), ids)
    }

    @Test
    fun `多个置顶之间也按 updatedAt 倒序`() = runBlocking {
        conversations.insert(conversation(id = "p1", updatedAt = 1_000, pinned = true))
        conversations.insert(conversation(id = "p2", updatedAt = 2_000, pinned = true))
        conversations.insert(conversation(id = "free", updatedAt = 9_000))

        assertEquals(listOf("p2", "p1", "free"), conversations.list(includeArchived = false).map { it.id })
    }

    @Test
    fun `默认列表不含归档，includeArchived 才含`() = runBlocking {
        conversations.insert(conversation(id = "alive", updatedAt = 1_000))
        conversations.insert(conversation(id = "archived", updatedAt = 2_000, archived = true))

        assertEquals(listOf("alive"), conversations.list(includeArchived = false).map { it.id })
        assertEquals(listOf("archived", "alive"), conversations.list(includeArchived = true).map { it.id })
    }

    @Test
    fun `rename 只改标题，不推 updatedAt`() = runBlocking {
        val base = conversation(id = "c-1", title = "原标题", updatedAt = 5_000)
        conversations.insert(base)

        val rows = conversations.rename("c-1", "新标题")

        assertEquals(1, rows)
        val loaded = conversations.findById("c-1")!!
        assertEquals("新标题", loaded.title)
        // ★ 这条是重点：重命名不该让会话跳到列表顶部
        assertEquals(5_000L, loaded.updatedAt)
    }

    @Test
    fun `置顶与归档切换不推 updatedAt`() = runBlocking {
        conversations.insert(conversation(id = "c-1", updatedAt = 5_000))

        conversations.setPinned("c-1", true)
        conversations.setArchived("c-1", true)

        val loaded = conversations.findById("c-1")!!
        assertTrue(loaded.pinned)
        assertTrue(loaded.archived)
        assertEquals(5_000L, loaded.updatedAt)
    }

    @Test
    fun `touch 是唯一会推 updatedAt 的操作`() = runBlocking {
        conversations.insert(conversation(id = "c-1", updatedAt = 5_000))

        conversations.touch("c-1", 9_999)

        assertEquals(9_999L, conversations.findById("c-1")!!.updatedAt)
    }

    @Test
    fun `对不存在的行做更新返回 0 行而不报错`() = runBlocking {
        assertEquals(0, conversations.rename("ghost", "x"))
        assertEquals(0, conversations.setPinned("ghost", true))
        assertEquals(0, conversations.touch("ghost", 1))
        assertEquals(0, conversations.delete("ghost"))
    }

    @Test
    fun `删除会话会连带删掉它的消息（外键 CASCADE 真的生效）`() = runBlocking {
        conversations.insert(conversation(id = "c-1"))
        conversations.insert(conversation(id = "c-2"))
        messages.insert(message(id = "m1", conversationId = "c-1"))
        messages.insert(message(id = "m2", conversationId = "c-1"))
        messages.insert(message(id = "m3", conversationId = "c-2"))

        conversations.delete("c-1")

        assertEquals(0, messages.countIn("c-1"))
        // 别的会话的消息不能被误删
        assertEquals(1, messages.countIn("c-2"))
    }

    @Test
    fun `同 id 重复 insert 会失败，不会静默覆盖`() = runBlocking {
        conversations.insert(conversation(id = "dup"))
        val failed = runCatching { conversations.insert(conversation(id = "dup", title = "覆盖")) }.isFailure
        assertTrue("重复主键应当抛异常（ABORT），而不是静默替换", failed)
        assertEquals("测试会话", conversations.findById("dup")!!.title)
    }

    @Test
    fun `upsert 用于显式覆盖`() = runBlocking {
        conversations.insert(conversation(id = "c-1", title = "旧"))
        conversations.upsert(conversation(id = "c-1", title = "新"))
        assertEquals("新", conversations.findById("c-1")!!.title)
        assertEquals(1, conversations.count())
    }

    /**
     * 响应式列表。
     *
     * ★ 这里必须用**真实时间**（`runBlocking` + `withTimeout`），不能用 `runTest`：
     *   `runTest` 是虚拟时钟，`delay()` 会瞬间跳过（真实线程一毫秒都没走），
     *   而 Room 的 `InvalidationTracker` 是在真实执行器上异步推送的。
     *   拿虚拟时间去等真实线程 = 必挂，而且挂得随机 —— 这就是 L1 那类"尺子错"。
     */
    @Test
    fun `observe 能在写入后推送新列表`() = runBlocking {
        conversations.insert(conversation(id = "c-1", updatedAt = 1))
        conversations.insert(conversation(id = "c-2", updatedAt = 2))

        // 等它推出"两条都在"的那一版（真实时间，最多 5 秒）
        val seen = withTimeout(5_000) {
            conversations.observe(includeArchived = false).first { it.size == 2 }
        }

        assertEquals(listOf("c-2", "c-1"), seen.map { it.id })
    }
}
