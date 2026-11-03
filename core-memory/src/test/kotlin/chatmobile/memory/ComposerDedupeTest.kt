package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 出口去重：库里可能**已经**有历史遗留的重复（改写入逻辑之前进的库），
 * 渲染时必须再拦一次，否则用户升级完 App 仍然看到 9 条。
 */
class ComposerDedupeTest {

    private val config = MemoryConfig(
        savedBudgetRatio = 0.5,
        recentBudgetRatio = 0.5,
        tokensPerChar = 1.0,
        maxInjectedEntries = 100,
        maxEntryChars = 400,
    )

    private fun entry(id: String, content: String, kind: MemoryKind = MemoryKind.RECENT) = MemoryEntry(
        id = id,
        content = content,
        kind = kind,
        sourceConversationId = null,
        sourceMessageId = null,
        createdAt = 1_000L,
    )

    @Test
    fun `近义重复只注入一条`() {
        val memories = listOf(
            entry("a", "迭代固定提示词"),
            entry("b", "固定提示词迭代"),
            entry("c", "迭代固定提示词（第二版）"),
            entry("d", "用户喜欢喝咖啡"),
        )
        val out = MemoryComposer(config).compose(memories, 10_000, 2_000L)
        val lines = out.text.lines().filter { it.startsWith("- ") }
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("咖啡") })
    }

    @Test
    fun `注入条数不超过上限`() {
        val cfg = config.copy(maxInjectedEntries = 3)
        val memories = (1..10).map { entry("id$it", "互不相同的事实第 $it 条") }
        val out = MemoryComposer(cfg).compose(memories, 100_000, 2_000L)
        assertEquals(3, out.text.lines().count { it.startsWith("- ") })
    }

    @Test
    fun `超长历史条目在渲染时被截断`() {
        val cfg = config.copy(maxEntryChars = 10)
        val out = MemoryComposer(cfg).compose(listOf(entry("a", "啊".repeat(500))), 10_000, 2_000L)
        assertTrue(out.text.contains("- " + "啊".repeat(10)))
        assertTrue(!out.text.contains("啊".repeat(11)))
    }

    @Test
    fun `墓碑不参与注入`() {
        val memories = listOf(entry("a", "已经删掉的记忆").copy(deleted = true))
        val out = MemoryComposer(config).compose(memories, 10_000, 2_000L)
        assertTrue(out.isEmpty)
    }
}
