package chatmobile.memory

import kotlin.math.sqrt

/**
 * **内置轻量向量**（零依赖、零下载、零内存）。
 *
 * ## 它是什么，不是什么
 *
 * - 是：把文本映射成 **256 维稀疏哈希向量**（字符 2/3-gram 哈希 → 桶 → L2 归一化）。
 * - 不是：神经网络模型。它没有语义泛化能力，只是把"字面相近"变成"向量相近"，
 *   让检索可以先按向量粗排、再按字面精排。
 *
 * ★ 为什么不直接塞一个真模型：bge-small-zh 的 int8 也要 ~24MB，还要带 tokenizer，
 *   会拖垮 APK 体积与冷启动。**这个实现的价值是让"向量开关"现在就能打开**，
 *   并验证整条链路（配置 → 向量 → 检索 → 回退）；将来换成 ONNX 只是替换 provider。
 *
 * ★ 与 `Dedupe` 的关系：Dedupe 是**写入侧**判重（Jaccard 精确比较）；
 *   这里是**读取侧**召回（向量近似）。两者都用字符 n-gram，但不是同一份代码。
 */
class HashEmbeddingProvider(
    override val dimensions: Int = 256,
    private val charGramSizes: IntArray = intArrayOf(2, 3),
) : EmbeddingProvider {

    override val id: String = "builtin-hash-$dimensions"
    override val available: Boolean = true

    override fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return null
        return texts.map { embedOne(it) }
    }

    private fun embedOne(text: String): FloatArray {
        val v = FloatArray(dimensions)
        val normalized = Dedupe.normalize(text)
        if (normalized.isEmpty()) return v

        for (n in charGramSizes) {
            if (normalized.length < n) continue
            for (i in 0..(normalized.length - n)) {
                val gram = normalized.substring(i, i + n)
                val h = murmur(gram)
                val idx = (h and Int.MAX_VALUE) % dimensions
                // 符号哈希：同一 gram 稳定落点，不同 gram 尽量不互相叠加放大
                val sign = if ((h ushr 31) and 1 == 1) -1f else 1f
                v[idx] += sign * weightFor(n)
            }
        }

        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        if (norm <= 0.0) return v
        val inv = (1.0 / sqrt(norm)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    /** 3-gram 比 2-gram 更有区分度，权重略高。 */
    private fun weightFor(n: Int): Float = if (n >= 3) 1.0f else 0.6f

    /** 32 位 murmur 风格混合（够用且稳定；不引第三方库）。 */
    private fun murmur(s: String): Int {
        var h = -0x340d631b
        for (c in s) {
            var k = c.code
            k *= 0xcc9e2d51.toInt()
            k = (k shl 15) or (k ushr 17)
            k *= 0x1b873593
            h = h xor k
            h = (h shl 13) or (h ushr 19)
            h = h * 5 + -0x19ab949c
        }
        h = h xor s.length
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        return h
    }
}
