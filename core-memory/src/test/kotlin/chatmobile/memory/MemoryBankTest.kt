package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryBankTest {

    private val config = MemoryConfig(
        savedCapacity = 5,
        promoteThreshold = 3,
        tokensPerChar = 1.0,
    )

    private fun bank(): MemoryBank {
        var n = 0
        return MemoryBank(
            store = InMemoryStore(),
            config = config,
            idGenerator = { "id-${++n}" },
        )
    }

    @Test
    fun `保存的记忆是 saved 层`() {
        val b = bank()
        val e = b.save("用户住在上海")
        assertEquals(MemoryKind.SAVED, e.kind)
        assertEquals("用户住在上海", e.content)
    }

    @Test
    fun `记录的近期记忆是 recent 层`() {
        val b = bank()
        val e = b.recordRecent("昨天聊了天气")
        assertEquals(MemoryKind.RECENT, e.kind)
        assertEquals(1, e.hitCount)
    }

    @Test
    fun `重复的近期记忆会合并而不是重复`() {
        val b = bank()
        b.recordRecent("喜欢喝咖啡")
        b.recordRecent("喜欢喝咖啡")
        val e = b.recordRecent("喜欢喝咖啡")

        assertEquals(1, b.list().size, "重复内容不应产生多条")
        assertEquals(3, e.hitCount)
    }

    @Test
    fun `saved 层达到容量后最旧的降级为 recent`() {
        val b = bank()
        repeat(5) { i -> b.save("事实$i", now = i.toLong()) }
        assertEquals(5, b.stats().savedCount)

        // 第 6 条触发降级
        b.save("事实5", now = 5L)

        val stats = b.stats()
        assertEquals(5, stats.savedCount, "saved 不应超过容量")
        assertTrue(stats.recentCount >= 1, "最旧的应被降级为 recent")
    }

    @Test
    fun `命中次数达标会自动提升为 saved`() {
        val b = bank()
        b.recordRecent("用户是程序员")
        b.recordRecent("用户是程序员")
        b.recordRecent("用户是程序员")

        val promoted = b.autoPromote()
        assertEquals(1, promoted.size)
        assertEquals(MemoryKind.SAVED, promoted.first().kind)
    }

    @Test
    fun `删除是墓碑不是物理删除`() {
        val b = bank()
        val e = b.save("会被删掉的事实")
        assertTrue(b.delete(e.id))

        // 默认列表看不到
        assertEquals(0, b.list().size)
        // 但带上墓碑能看到
        assertEquals(1, b.list(includeDeleted = true).size)
        assertTrue(b.list(includeDeleted = true).first().deleted)
    }

    @Test
    fun `被删除的记忆可以恢复`() {
        val b = bank()
        val e = b.save("临时删掉")
        b.delete(e.id)
        assertTrue(b.restore(e.id))
        assertEquals(1, b.list().size)
    }

    @Test
    fun `可解释性 能查到来源`() {
        val b = bank()
        val e = b.save(
            "用户偏好简洁回答",
            sourceConversationId = "conv-123",
            sourceMessageId = "msg-456",
        )
        val exp = b.explain(e.id)
        assertNotNull(exp)
        assertEquals("conv-123", exp.entry.sourceConversationId)
        assertEquals("msg-456", exp.entry.sourceMessageId)
        assertEquals(1.0, exp.weight, 0.001)
    }

    @Test
    fun `不存在的记忆返回 null`() {
        val b = bank()
        assertNull(b.explain("不存在"))
        assertFalse(b.delete("不存在"))
    }

    @Test
    fun `组装上下文会更新使用记录`() {
        val b = bank()
        val e = b.save("用户住在上海")

        b.composeContext(totalContextTokens = 1000, now = 0L)

        val after = b.list().first { it.id == e.id }
        assertNotNull(after.lastUsedAt, "被用到应记录 lastUsedAt")
        assertTrue(after.hitCount > 0, "被用到应累加 hitCount")
    }

    @Test
    fun `统计能反映容量接近上限`() {
        val b = bank()
        repeat(5) { i -> b.save("事实$i", now = i.toLong()) }
        assertTrue(b.stats().isAlmostFull, "5/5 应该报几乎满")
    }

    @Test
    fun `空内容被拒绝`() {
        val b = bank()
        var threw = false
        try {
            b.save("   ")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "空记忆应被拒绝")
    }
}
