package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 结构化记忆（key/value）—— D 方案的核心行为。
 *
 * 目标：**同一个键只有一个值**，事件可以多条，面板能编辑/置顶/合并/清空。
 */
class KeyedMemoryTest {

    private fun bank(): MemoryBank {
        var n = 0
        return MemoryBank(
            store = InMemoryStore(),
            config = MemoryConfig(tokensPerChar = 1.0),
            idGenerator = { "id-${++n}" },
        )
    }

    @Test
    fun `同一个键只会保留一个值`() {
        val b = bank()
        b.recordRecent("简洁", key = "pref.reply_style")
        b.recordRecent("详细一点", key = "pref.reply_style")
        val list = b.list()
        assertEquals(1, list.size)
        assertEquals("详细一点", list.first().content)
    }

    @Test
    fun `覆盖时保留身份与命中次数`() {
        val b = bank()
        val first = b.recordRecent("上海", key = "user.city")
        val second = b.recordRecent("杭州", key = "user.city")
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(2, second.hitCount)
    }

    @Test
    fun `事件键允许同键多条`() {
        val b = bank()
        b.recordRecent("去上海出差", key = "event.2026-09-01_trip")
        b.recordRecent("去北京出差", key = "event.2026-10-01_trip")
        assertEquals(2, b.list().size)
    }

    @Test
    fun `非法键降级为自由文本而不是写脏数据`() {
        val b = bank()
        val e = b.recordRecent("随手记的一条", key = "偏好.回答风格")
        assertNull(e.key)
    }

    @Test
    fun `注入文本带上分类前缀`() {
        val b = bank()
        b.recordRecent("简洁，不要客套", key = "pref.reply_style")
        val out = b.composeContext(100_000).text
        assertTrue(out.contains("[偏好] 简洁，不要客套"))
    }

    @Test
    fun `不同键即使文字相近也不会被渲染去重`() {
        val b = bank()
        b.save("上海", key = "user.city")
        b.save("上海", key = "project.location")
        val out = b.composeContext(100_000).text
        assertEquals(2, out.lines().count { it.startsWith("- ") })
    }

    @Test
    fun `编辑会更新内容与键`() {
        val b = bank()
        val e = b.recordRecent("旧内容", key = "user.city")
        val updated = b.update(e.id, "新内容", "user.city_v2")
        assertNotNull(updated)
        assertEquals("新内容", updated.content)
        assertEquals("user.city_v2", updated.key)
    }

    @Test
    fun `置顶等于升级为长期`() {
        val b = bank()
        val e = b.recordRecent("重要的事")
        b.setPinned(e.id, true)
        assertEquals(MemoryKind.SAVED, b.list().first().kind)
        b.setPinned(e.id, false)
        assertEquals(MemoryKind.RECENT, b.list().first().kind)
    }

    @Test
    fun `能找出重复组并合并`() {
        val now = 1_000L
        fun e(id: String, text: String) = MemoryEntry(
            id = id,
            content = text,
            kind = MemoryKind.RECENT,
            sourceConversationId = null,
            sourceMessageId = null,
            createdAt = now,
        )
        // 直接种进 store：走 recordRecent 会被写入期去重提前合并，测不到"分组"这一步
        val store = InMemoryStore(
            listOf(
                e("a", "迭代固定提示词"),
                e("b", "固定提示词迭代"),
                e("c", "完全无关的天气"),
            ),
        )
        val b = MemoryBank(store = store, config = MemoryConfig(tokensPerChar = 1.0))

        val groups = b.findDuplicateGroups()
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().size)

        b.mergeGroup(groups.first().map { it.id })
        assertEquals(2, b.list().size)                       // 一组变一条 + 无关那条
        assertEquals(1, b.list(includeDeleted = true).count { it.deleted })
    }

    @Test
    fun `不同键的两条不会被判为重复组`() {
        val b = bank()
        b.save("上海", key = "user.city")
        b.save("上海", key = "project.location")
        assertTrue(b.findDuplicateGroups().isEmpty())
    }

    @Test
    fun `清空回收站会物理删除墓碑`() {
        val b = bank()
        val e = b.recordRecent("要删掉的")
        b.delete(e.id)
        assertEquals(1, b.list(includeDeleted = true).size)
        assertEquals(1, b.purgeDeleted())
        assertEquals(0, b.list(includeDeleted = true).size)
    }
}
