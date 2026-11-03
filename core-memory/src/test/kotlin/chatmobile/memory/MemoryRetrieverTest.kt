package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 字面检索后端 + 向量接缝。
 *
 * ★ 这里测的是"接口与降级"，不是"检索质量" —— 质量要真实数据才说得清。
 */
class MemoryRetrieverTest {

    private val now = 1_700_000_000_000L

    private fun entry(
        id: String,
        content: String,
        createdAt: Long = now,
        hit: Int = 1,
    ) = MemoryEntry(
        id = id,
        content = content,
        kind = MemoryKind.RECENT,
        sourceConversationId = null,
        sourceMessageId = null,
        createdAt = createdAt,
        hitCount = hit,
    )

    @Test
    fun `字面检索把最相关的排在第一`() {
        val list = listOf(
            entry("a", "用户住在上海"),
            entry("b", "用户喜欢喝咖啡"),
            entry("c", "下周三要交报告"),
        )
        val out = LexicalRetriever().search("上海的天气", list, limit = 3, now = now)
        assertEquals("a", out.first().id)
    }

    @Test
    fun `完全无关的条目不返回`() {
        val list = listOf(entry("a", "用户住在上海"), entry("b", "喜欢猫"))
        val out = LexicalRetriever().search("量子力学", list, limit = 3, now = now)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `同样相关时新的优先`() {
        val old = entry("old", "项目叫 ChatMobile", createdAt = now - 30L * 86_400_000)
        val fresh = entry("new", "项目叫 ChatMobile", createdAt = now)
        val out = LexicalRetriever().search("ChatMobile", listOf(old, fresh), limit = 1, now = now)
        assertEquals("new", out.first().id)
    }

    @Test
    fun `limit 生效`() {
        val list = (1..10).map { entry("id$it", "关于上海的第 $it 条记录") }
        assertEquals(3, LexicalRetriever().search("上海", list, limit = 3, now = now).size)
    }

    @Test
    fun `没有向量模型时自动退回字面检索`() {
        val list = listOf(entry("a", "用户住在上海"))
        val r = EmbeddingRetriever(provider = null)
        assertEquals(false, r.available)
        assertEquals("a", r.search("上海", list, 1, now).first().id)
    }

    @Test
    fun `向量模型失败也退回字面检索`() {
        val broken = object : EmbeddingProvider {
            override val id = "broken"
            override val dimensions = 3
            override val available = true
            override fun embed(texts: List<String>): List<FloatArray>? = null
        }
        val list = listOf(entry("a", "用户住在上海"))
        val out = EmbeddingRetriever(broken).search("上海", list, 1, now)
        assertEquals("a", out.first().id)
    }

    @Test
    fun `向量模型可用时按余弦排序`() {
        // 用一个玩具向量：包含"上海"→ [1,0]，否则 [0,1]
        val toy = object : EmbeddingProvider {
            override val id = "toy"
            override val dimensions = 2
            override val available = true
            override fun embed(texts: List<String>): List<FloatArray> =
                texts.map { if (it.contains("上海")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        }
        val list = listOf(entry("a", "用户住在上海"), entry("b", "用户喜欢咖啡"))
        val out = EmbeddingRetriever(toy).search("上海", list, 2, now)
        assertEquals("a", out.first().id)
    }
}
