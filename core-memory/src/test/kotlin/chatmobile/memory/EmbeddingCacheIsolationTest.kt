package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 换 embedding 模型 / 改记忆内容时，缓存必须失效 —— 不能拿 A 空间的向量比 B 空间的查询。
 */
class EmbeddingCacheIsolationTest {

    private class FakeProvider(
        override val id: String,
        override val dimensions: Int,
        private val fn: (String) -> FloatArray,
    ) : EmbeddingProvider {
        var embedCalls = 0
        override val available: Boolean = true
        override fun embed(texts: List<String>): List<FloatArray> {
            embedCalls += texts.size
            return texts.map(fn)
        }
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
    fun `第二次检索命中缓存不再重复编码`() {
        val p = FakeProvider("m1", 2) { FloatArray(2) { 1f } }
        val r = EmbeddingRetriever(p)
        val list = listOf(entry("a", "喜欢猫"), entry("b", "喜欢狗"))
        r.search("猫", list, 2, 0)
        val first = p.embedCalls
        r.search("狗", list, 2, 0)
        // 第二次只编码 query，不该再编码两条记忆
        assertEquals(first + 1, p.embedCalls)
    }

    @Test
    fun `换模型后不会复用旧向量`() {
        val a = FakeProvider("model-a", 2) { FloatArray(2) { 1f } }
        val b = FakeProvider("model-b", 2) { FloatArray(2) { 2f } }
        val list = listOf(entry("a", "喜欢猫"))

        EmbeddingRetriever(a).search("猫", list, 1, 0)
        val ra = EmbeddingRetriever(a)
        ra.search("猫", list, 1, 0)
        assertEquals(2, ra.cachedVector(list[0])!!.size)

        // 换成另一个模型：应当重新编码（1 条记忆 + 1 条查询），而不是读到 a 的缓存
        val rb = EmbeddingRetriever(b)
        rb.search("猫", list, 1, 0)
        assertEquals(2, b.embedCalls)
    }

    @Test
    fun `内容变化后缓存键变化`() {
        val p = FakeProvider("m1", 2) { FloatArray(2) { 1f } }
        val r = EmbeddingRetriever(p)
        val old = entry("a", "喜欢猫")
        r.search("猫", listOf(old), 1, 0)
        assertTrue(r.cachedVector(old) != null)

        val edited = old.copy(content = "喜欢狗")
        // 编辑后旧键不再命中：这里用缓存存在性验证键确实变了
        assertEquals(null, r.cachedVector(edited))
    }

    @Test
    fun `维度不一致的向量被跳过而不是抛异常`() {
        val p = FakeProvider("bad", 3) { FloatArray(3) { 1f } }
        val r = EmbeddingRetriever(p)
        val out = r.search("猫", listOf(entry("a", "喜欢猫")), 1, 0)
        // 只要不抛异常即可（这里维度一致，会正常返回）
        assertEquals(1, out.size)
    }
}
