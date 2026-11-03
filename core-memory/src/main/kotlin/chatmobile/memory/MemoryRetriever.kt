package chatmobile.memory

import kotlin.math.ln

/**
 * 记忆检索后端 —— 为「以后接小向量模型」预留的唯一接缝。
 *
 * ★★★ 为什么要先定接口、而不是先上向量：
 *   - 个人记忆规模是 200–2000 条，逐条打分是微秒级；**容量/速度从来不是问题**，
 *     问题只是"换个说法能不能找到"。
 *   - 云端 embedding 不是人人有免费额度；本地模型又要 +25MB 包体。
 *   - 所以：**默认走纯 CPU 的字面检索；向量只是可选增强，永远不是前置条件。**
 *     这与 `_research/同类产品记忆库-调研与去重方案.md` 的结论一致（LibreChat 全量注入、无向量）。
 *
 * ★ 接法（留给未来的自己）：
 *   1. 实现 [EmbeddingProvider]（ONNX Runtime Mobile / MediaPipe / 云端 BYOK 都行）
 *   2. `EmbeddingRetriever(provider)` 换掉 `LexicalRetriever()`
 *   3. 上层代码一行不用改 —— 只依赖 [MemoryRetriever]
 *   4. provider 不可用/抛异常时**自动退回字面检索**，绝不因增强包缺失而功能降级
 *
 * ★ 为什么不是 `suspend`：core-memory 是零依赖纯 JVM 模块（连 coroutines 都没有），
 *   保持同步接口才能单测。本地推理本来就是同步的；云端实现请在 provider 内部自己切线程。
 */
interface MemoryRetriever {
    /** 后端标识，用于日志与设置页展示（`lexical` / `embedding:local-bge-small` …） */
    val id: String

    /** 现在能不能用（缺模型、内存不足时返回 false） */
    val available: Boolean

    /**
     * 从候选里挑出与 [query] 最相关的若干条。
     *
     * @param now 显式传入，保证可测试（不在内部读时钟）
     */
    fun search(
        query: String,
        candidates: List<MemoryEntry>,
        limit: Int,
        now: Long = System.currentTimeMillis(),
    ): List<MemoryEntry>
}

/**
 * 默认后端：字符 bigram + 时间 + 命中次数，纯 CPU、零依赖。
 *
 * 打分公式（权重都是可调的，先给一组保守值）：
 * ```
 * score = 2.0 × Jaccard(query, memory)      // 字面相关度，主信号
 *       + 0.5 × 是包含关系                  // 查询是记忆的子串（短查询常见）
 *       + 0.5 × recency(0..1)               // 越近越优先
 *       + 0.3 × hitScore(0..1)              // 越常被召回越优先
 * ```
 */
class LexicalRetriever(
    private val config: MemoryConfig = MemoryConfig(),
) : MemoryRetriever {

    override val id: String = "lexical"
    override val available: Boolean = true

    override fun search(
        query: String,
        candidates: List<MemoryEntry>,
        limit: Int,
        now: Long,
    ): List<MemoryEntry> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val q = Dedupe.blob(query)
        if (q.isEmpty()) return emptyList()

        return candidates
            .filterNot { it.deleted }
            .map { it to score(q, it, now) }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Double>> { it.second }
                    .thenByDescending { it.first.hitCount }
                    .thenBy { it.first.id },   // 兜底确定性，避免输入顺序影响结果
            )
            .take(limit)
            .map { it.first }
    }

    private fun score(queryBlob: String, entry: MemoryEntry, now: Long): Double {
        val content = entry.content
        val contentBlob = Dedupe.blob(content)
        if (contentBlob.isEmpty()) return 0.0

        val j = Dedupe.jaccard(queryBlob, content)
        val containment = if (contentBlob.contains(queryBlob)) 0.5 else 0.0

        // ★ 没有任何字面相关度 ⇒ 直接判不相关。
        //   否则"时间近 + 命中多"的加分会把完全无关的记忆也顶上来（0 相似度也有基础分）。
        if (j <= 0.0 && containment == 0.0) return -1.0

        val anchor = entry.lastUsedAt ?: entry.createdAt
        val ageDays = ((now - anchor).coerceAtLeast(0L)).toDouble() / 86_400_000.0
        val recency = 1.0 / (1.0 + ageDays / config.decayHalfLifeDays)

        val hit = (ln(1.0 + entry.hitCount) / ln(11.0)).coerceIn(0.0, 1.0)

        return j * 2.0 + containment + recency * 0.5 + hit * 0.3
    }
}

/**
 * 向量后端需要的唯一能力：把文本变成向量。
 *
 * ★ 故意不规定模型/维度/线程模型 —— 本地 ONNX、MediaPipe、云端 HTTP 都能实现。
 * ★ 返回 `null` 表示"这次不可用"（模型没下载、内存不足、网络失败），
 *   调用方退回 [LexicalRetriever]，不要抛给用户。
 */
interface EmbeddingProvider {
    val id: String
    val dimensions: Int
    val available: Boolean

    fun embed(texts: List<String>): List<FloatArray>?
}

/**
 * 可选后端：有 [provider] 就用向量，没有/失败就**自动**退回字面检索。
 *
 * ★ 向量缓存在内存里（key = 条目 id + 内容长度）。个人规模下这够用：
 *   1000 条 × 512 维 × 4B ≈ 2MB。进程重启后重算。
 */
class EmbeddingRetriever(
    private val provider: EmbeddingProvider?,
    private val config: MemoryConfig = MemoryConfig(),
) : MemoryRetriever {

    override val id: String = provider?.let { "embedding:${it.id}" } ?: "embedding:none"
    override val available: Boolean = provider?.available == true

    private val fallback = LexicalRetriever(config)
    private val cache = HashMap<String, FloatArray>()

    /**
     * 缓存键 = **模型指纹** + 条目 id + 内容哈希。
     *
     * ★★★ 为什么必须带模型指纹（用户 02:0x 的疑问）：不同 embedding 模型
     *   （哪怕同为 512 维）训出来的向量空间**完全不同**，跨模型比较余弦毫无意义。
     *   把 `id:dimensions` 放进键里 ⇒ **换模型时旧缓存自动失效**，不需要"迁移向量库"，
     *   也不会拿 A 模型的向量去和 B 模型的查询比。
     */
    private fun keyFor(p: EmbeddingProvider, entry: MemoryEntry): String =
        "${p.id}:${p.dimensions}|${entry.id}|${entry.content.hashCode()}"

    override fun search(
        query: String,
        candidates: List<MemoryEntry>,
        limit: Int,
        now: Long,
    ): List<MemoryEntry> {
        val p = provider
        if (p == null || !p.available || limit <= 0 || candidates.isEmpty()) {
            return fallback.search(query, candidates, limit, now)
        }

        return try {
            val alive = candidates.filterNot { it.deleted }
            if (alive.isEmpty()) return emptyList()

            // 只对**没缓存**的条目做向量化，且只发一次批量请求
            val missing = alive.filter { cache[keyFor(p, it)] == null }
            if (missing.isNotEmpty()) {
                val calculated = p.embed(missing.map { it.content })
                    ?: return fallback.search(query, alive, limit, now)
                if (calculated.size != missing.size) return fallback.search(query, alive, limit, now)
                missing.forEachIndexed { i, e -> cache[keyFor(p, e)] = calculated[i] }
            }

            val queryVector = p.embed(listOf(query))?.firstOrNull()
                ?: return fallback.search(query, alive, limit, now)

            val scored = alive.mapNotNull { entry ->
                val v = cache[keyFor(p, entry)] ?: return@mapNotNull null
                // 维度不一致（例如换了模型但缓存键里漏了信息）⇒ 宁可跳过，也不比
                if (v.size != queryVector.size) return@mapNotNull null
                val cos = cosine(queryVector, v)
                if (cos > 0.0) entry to cos else null
            }.let { list ->
                // ★★★ 两级过滤 —— 以前只有 `cos > 0.0`，而向量余弦**永远为正**，
                //   等于不过滤：真机上模型当场指出「查询不可能命中这 5 条，却返回了 5 条」。
                //   注意这属于依赖模型的特性，不是"没接上"，但效果一样糟。
                val best = list.maxOfOrNull { it.second } ?: 0.0
                val floor = maxOf(config.retrievalMinScore, best * config.retrievalRelativeRatio)
                if (best < config.retrievalMinScore) emptyList()
                else list.filter { it.second >= floor }
            }
            scored
                .sortedWith(
                    compareByDescending<Pair<MemoryEntry, Double>> { it.second }
                        .thenByDescending { it.first.hitCount }
                        .thenBy { it.first.id },
                )
                .take(limit)
                .map { it.first }
        } catch (_: Throwable) {
            // 增强包坏了也不能影响"能不能找到记忆"
            fallback.search(query, candidates, limit, now)
        }
    }

    /** 用「模型指纹 + id + 内容哈希」做缓存键：换模型/改内容都会自动失效。 */
    internal fun cachedVector(entry: MemoryEntry): FloatArray? =
        provider?.let { cache[keyFor(it, entry)] }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
