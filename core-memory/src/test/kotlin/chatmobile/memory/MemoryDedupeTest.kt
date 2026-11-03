package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 写入侧去重（本轮的修复重点）。
 *
 * 设计目标：**同一件事无论被抽几次，库里只有一条**，且层级不被降级。
 */
class MemoryDedupeTest {

    private fun bank(
        recentCapacity: Int = 400,
        maxEntryChars: Int = 400,
    ): MemoryBank {
        var n = 0
        return MemoryBank(
            store = InMemoryStore(),
            config = MemoryConfig(
                recentCapacity = recentCapacity,
                maxEntryChars = maxEntryChars,
                tokensPerChar = 1.0,
            ),
            idGenerator = { "id-${++n}" },
        )
    }

    @Test
    fun `同来源重试不会长出新条目`() {
        val b = bank()
        b.recordRecent("用户喜欢喝咖啡", sourceMessageId = "m1#0")
        b.recordRecent("用户喜欢喝咖啡", sourceMessageId = "m1#0")
        assertEquals(1, b.list().size)
    }

    @Test
    fun `同来源但内容更完整时取更长的`() {
        val b = bank()
        b.recordRecent("用户喜欢咖啡", sourceMessageId = "m1#0")
        val merged = b.recordRecent("用户喜欢喝手冲咖啡", sourceMessageId = "m1#0")
        assertEquals(1, b.list().size)
        assertEquals("用户喜欢喝手冲咖啡", merged.content)
    }

    @Test
    fun `换语序的同一件事会合并并累加命中`() {
        val b = bank()
        b.recordRecent("迭代固定提示词")
        val merged = b.recordRecent("固定提示词迭代")
        assertEquals(1, b.list().size)
        assertEquals(2, merged.hitCount)
    }

    @Test
    fun `显式保存会升级已有条目的层级`() {
        val b = bank()
        b.recordRecent("用户用 Kotlin")
        val saved = b.save("用户用 Kotlin")
        assertEquals(1, b.list().size)
        assertEquals(MemoryKind.SAVED, saved.kind)
    }

    @Test
    fun `超长内容会被截断到上限`() {
        val b = bank(maxEntryChars = 20)
        val e = b.recordRecent("啊".repeat(200))
        assertEquals(20, e.content.length)
    }

    @Test
    fun `recent 超容量时删掉最早的`() {
        val b = bank(recentCapacity = 2)
        val now = 1_000_000L
        b.recordRecent("第一条不同的事", now = now)
        b.recordRecent("第二条不同的事", now = now + 1_000)
        b.recordRecent("第三条不同的事", now = now + 2_000)
        val alive = b.list()
        assertEquals(2, alive.size)
        assertTrue(alive.none { it.content.contains("第一条") })
    }

    @Test
    fun `已删除的条目不会被重新写活`() {
        val b = bank()
        val e = b.recordRecent("一条会被删掉的记忆")
        b.delete(e.id)
        b.recordRecent("一条会被删掉的记忆")
        // 墓碑守门：库里仍只有那一条（墓碑），不会多出一条活的
        assertEquals(1, b.list(includeDeleted = true).size)
        assertTrue(b.list().isEmpty())
    }
}
