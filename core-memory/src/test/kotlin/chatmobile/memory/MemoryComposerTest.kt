package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryComposerTest {

    private val config = MemoryConfig(
        savedBudgetRatio = 0.5,
        recentBudgetRatio = 0.5,
        decayHalfLifeDays = 7.0,
        savedCapacity = 100,
        tokensPerChar = 1.0,   // 测试里让 1 字符 = 1 token，便于算
    )
    private val composer = MemoryComposer(config)

    private fun mem(
        id: String,
        content: String,
        kind: MemoryKind,
        createdAt: Long = 0L,
        hitCount: Int = 0,
        deleted: Boolean = false,
    ) = MemoryEntry(
        id = id, content = content, kind = kind,
        sourceConversationId = null, sourceMessageId = null,
        createdAt = createdAt, hitCount = hitCount, deleted = deleted,
    )

    @Test
    fun `空记忆返回空文本`() {
        val r = composer.compose(emptyList(), 1000, 0L)
        assertTrue(r.isEmpty)
        assertEquals("", r.text)
    }

    @Test
    fun `saved 和 recent 分层渲染`() {
        val r = composer.compose(
            listOf(
                mem("1", "用户住在上海", MemoryKind.SAVED),
                mem("2", "昨天聊了天气", MemoryKind.RECENT),
            ),
            totalContextTokens = 1000,
            now = 0L,
        )
        assertContains(r.text, "长期设定")
        assertContains(r.text, "用户住在上海")
        assertContains(r.text, "近期对话")
        assertContains(r.text, "昨天聊了天气")
        assertEquals(1, r.usedSaved.size)
        assertEquals(1, r.usedRecent.size)
    }

    @Test
    fun `已删除的记忆不参与组装`() {
        val r = composer.compose(
            listOf(mem("1", "被删掉的", MemoryKind.SAVED, deleted = true)),
            1000, 0L,
        )
        assertTrue(r.isEmpty, "墓碑记忆不应出现")
    }

    @Test
    fun `绝不超出预算`() {
        // 50 条各 100 字符，预算只有 1000 token
        val many = (1..50).map {
            mem("$it", "x".repeat(100), MemoryKind.SAVED, createdAt = it.toLong())
        }
        val r = composer.compose(many, totalContextTokens = 1000, now = 0L)

        assertTrue(
            r.usedTokens <= 1000,
            "超出预算：用了 ${r.usedTokens} / 1000",
        )
    }

    @Test
    fun `saved 预算满了不会挤占 recent 预算`() {
        val manySaved = (1..20).map {
            mem("s$it", "y".repeat(50), MemoryKind.SAVED, createdAt = it.toLong())
        }
        val recents = (1..5).map {
            mem("r$it", "z".repeat(20), MemoryKind.RECENT, createdAt = 0L)
        }
        val r = composer.compose(manySaved + recents, totalContextTokens = 1000, now = 0L)

        // saved 预算 500，recent 预算 500
        assertTrue(r.savedTokens <= 500, "saved 超预算：${r.savedTokens}")
        assertTrue(r.recentTokens <= 500, "recent 超预算：${r.recentTokens}")
        assertTrue(r.usedRecent.isNotEmpty(), "recent 不该被 saved 挤没")
    }

    @Test
    fun `recent 按权重降序排列`() {
        val r = composer.compose(
            listOf(
                mem("old", "很久以前的事", MemoryKind.RECENT, createdAt = 0L),
                mem("new", "刚刚发生的事", MemoryKind.RECENT, createdAt = 30 * 86_400_000L),
            ),
            totalContextTokens = 1000,
            now = 30 * 86_400_000L,
        )
        // 新的应该排在前面
        assertEquals("new", r.usedRecent.first().id)
    }

    @Test
    fun `预算为0时报错`() {
        var threw = false
        try {
            composer.compose(emptyList(), 0, 0L)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "预算为 0 应该抛异常")
    }
}
