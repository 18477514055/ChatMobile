package chatmobile.memory

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 内置轻量向量（哈希 n-gram）：确定性、归一化、字面相近者相似度更高。 */
class HashEmbeddingProviderTest {

    private val p = HashEmbeddingProvider()

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    @Test
    fun `维度与可用性`() {
        assertEquals(256, p.dimensions)
        assertTrue(p.available)
    }

    @Test
    fun `同一文本向量完全一致`() {
        val a = p.embed(listOf("用户喜欢喝手冲咖啡"))!!.first()
        val b = p.embed(listOf("用户喜欢喝手冲咖啡"))!!.first()
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `向量已 L2 归一化`() {
        val v = p.embed(listOf("手机端 Agent 调用 API"))!!.first()
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue(abs(norm - 1f) < 1e-3f, "norm=$norm")
    }

    @Test
    fun `字面相近的两句相似度高于无关句`() {
        val q = p.embed(listOf("迭代固定提示词"))!!.first()
        val near = p.embed(listOf("固定提示词迭代"))!!.first()
        val far = p.embed(listOf("用户喜欢喝手冲咖啡"))!!.first()
        val cNear = cos(q, near)
        val cFar = cos(q, far)
        assertTrue(cNear > cFar, "near=$cNear far=$cFar")
    }

    @Test
    fun `空文本返回零向量而不是 null`() {
        val v = p.embed(listOf(""))!!.first()
        assertEquals(0f, v.sum())
    }

    @Test
    fun `embed 空列表返回 null`() {
        assertEquals(null, p.embed(emptyList()))
    }

    @Test
    fun `批量返回条数与输入一致`() {
        val out = p.embed(listOf("a", "b", "c"))
        assertNotNull(out)
        assertEquals(3, out!!.size)
    }
}
