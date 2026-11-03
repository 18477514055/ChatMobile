package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 向量召回必须**能拒绝**：余弦为正 ≠ 相关。
 *
 * 背景：真机上模型当场指出「查询 remote-call-test 不可能命中这 5 条，却返回了 5 条」。
 * 根因是旧过滤条件 `cos > 0.0` —— 向量余弦几乎永远为正，等于不过滤。
 */
class RetrievalThresholdTest {

    private class FixedProvider(
        private val vectors: Map<String, FloatArray>,
        override val dimensions: Int = 3,
    ) : EmbeddingProvider {
        override val id: String = "fixed"
        override val available: Boolean = true
        override fun embed(texts: List<String>): List<FloatArray>? =
            texts.map { vectors[it] ?: FloatArray(3) { 0.1f } }
    }

    private fun entry(id: String, text: String) = MemoryEntry(
        id = id,
        content = text,
        kind = MemoryKind.RECENT,
        sourceConversationId = null,
        sourceMessageId = null,
        createdAt = 0L,
    )

    @Test
    fun `低于绝对下限时一条都不返回`() {
        // 三条记忆与查询的余弦都是 ~0.1，远低于 0.45 的下限
        val provider = FixedProvider(
            mapOf(
                "查询" to floatArrayOf(1f, 0f, 0f),
                "甲" to floatArrayOf(0.1f, 0.995f, 0f),
                "乙" to floatArrayOf(0.1f, 0f, 0.995f),
            ),
        )
        val r = EmbeddingRetriever(provider, MemoryConfig())
        val out = r.search("查询", listOf(entry("a", "甲"), entry("b", "乙")), limit = 10, now = 0)
        assertTrue(out.isEmpty(), "全部低分却返回了 ${out.size} 条 ⇒ 阈值没生效")
    }

    @Test
    fun `只保留相对最高分百分之九十以上的条目`() {
        val provider = FixedProvider(
            mapOf(
                "查询" to floatArrayOf(1f, 0f, 0f),
                "高" to floatArrayOf(0.95f, 0.31f, 0f),   // cos ≈ 0.95
                "中" to floatArrayOf(0.60f, 0.80f, 0f),   // cos ≈ 0.60
            ),
        )
        val r = EmbeddingRetriever(provider, MemoryConfig())
        val out = r.search("查询", listOf(entry("a", "高"), entry("b", "中")), limit = 10, now = 0)
        assertEquals(1, out.size)
        assertEquals("a", out.first().id)
    }

    @Test
    fun `参数可调：把下限降到 0 就退回旧行为`() {
        val provider = FixedProvider(
            mapOf(
                "查询" to floatArrayOf(1f, 0f, 0f),
                "甲" to floatArrayOf(0.1f, 0.995f, 0f),
            ),
        )
        val loose = MemoryConfig(retrievalMinScore = 0.0, retrievalRelativeRatio = 0.0)
        val r = EmbeddingRetriever(provider, loose)
        val out = r.search("查询", listOf(entry("a", "甲")), limit = 10, now = 0)
        assertEquals(1, out.size)
    }
}
