package chatmobile.memory

/**
 * 记忆库 —— 记忆系统的门面。
 *
 * 上层（ViewModel / Repository）只跟这个类打交道。
 * 内部负责：写入、召回、衰减、提升、删除（墓碑）、可解释性查询。
 */
class MemoryBank(
    private val store: MemoryStore,
    config: MemoryConfig = MemoryConfig(),
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    /**
     * 当前生效的配置。
     *
     * ★ 为什么是 `var` 而不是构造后不可变：用户可以在面板上**当场改**整理频率、
     *   单条字数、容量、阈值…… 改完必须立刻生效，不能要求重启 App。
     *   外面改完调 [updateConfig]，内部同步重建 composer。
     */
    var config: MemoryConfig = config
        private set

    private var composer = MemoryComposer(config)

    /**
     * 检索后端 —— **记忆库唯一的"找"的入口**。
     *
     * ★★★ 为什么必须是这里的字段，而不是让调用方自己挑：
     *   上一版把 `search_memory` 写成 `content.contains(q)`、注入路径又完全不过检索器
     *   ⇒ 向量模型装好了、探针也通了，但**没有任何一条路径去问它**。
     *   "机制都在，唯独连接不存在"就是这么长出来的。
     *   现在所有召回（工具检索 / 注入筛选）都走这一个字段。
     *
     * ★ 默认 [LexicalRetriever]：零依赖、永不失败；换成向量后端由上层注入。
     */
    var retriever: MemoryRetriever = LexicalRetriever(config)
        private set

    /**
     * 上层注入的向量后端（null = 只用字面检索）。
     *
     * ★ 单独存一份的原因：`updateConfig` 要重建 retriever（它内部持 config），
     *   重建时必须把向量后端**带上**，否则用户在面板上改一个滑块就把向量检索弄丢了。
     */
    private var embeddingProvider: EmbeddingProvider? = null

    /**
     * 当前检索后端是否真的在做向量检索（供面板/日志如实显示）。
     *
     * ★ 别拿"配置里选了 ONNX"当"在跑向量"——降级是静默的，
     *   只有这个值才说明**这一刻**用的是谁。
     */
    val retrieverId: String get() = if (retriever.available) retriever.id else "lexical(fallback)"

    /** 装/拆向量后端；传 null 即退回纯字面检索。 */
    fun setEmbeddingProvider(provider: EmbeddingProvider?) {
        embeddingProvider = provider
        retriever = if (provider == null) LexicalRetriever(config) else EmbeddingRetriever(provider, config)
    }

    fun setRetriever(newRetriever: MemoryRetriever) {
        retriever = newRetriever
    }

    /**
     * 换配置（面板改参数后调用）。
     *
     * ★ 已经落库的条目**不重算**：容量/阈值只在下次读写时生效。
     *   这是刻意的 —— 改一个阈值就把用户几百条记忆批量删一遍，是不可接受的副作用。
     */
    fun updateConfig(newConfig: MemoryConfig) {
        config = newConfig
        composer = MemoryComposer(newConfig)
        // 检索器内部持有 config（预算/半衰期/阈值）⇒ 必须重建；但**保留向量后端**
        retriever = embeddingProvider
            ?.let { EmbeddingRetriever(it, newConfig) }
            ?: LexicalRetriever(newConfig)
    }

    // ---------------------------------------------------------------
    // 写入
    // ---------------------------------------------------------------

    /**
     * 显式保存一条永久记忆。
     *
     * 对应官方：用户说"记住…" / 手动添加记忆。
     *
     * ★ 也走去重：用户可能把同一件事说两遍（「记住我用 Kotlin」/「我用的是 Kotlin」），
     *   两条 SAVED 并存对模型没有任何好处。
     */
    fun save(
        content: String,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
        now: Long = System.currentTimeMillis(),
        key: String? = null,
    ): MemoryEntry {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        return upsert(content, MemoryKind.SAVED, sourceConversationId, sourceMessageId, now, key)
    }

    /**
     * 记录一条近期记忆。
     *
     * 对应官方：对话结束后异步抽取的上下文。
     *
     * ★★★ 去重分三层（顺序不能换）：
     *   1. **同来源幂等**：`sourceMessageId` 相同 ⇒ 同一次抽取的重试，只累加不新增
     *   2. **字面相同**：归一化后完全一致
     *   3. **近义相同**：见 [Dedupe.isSameTopic]（这才是治「同一件事 9 遍」的那一刀）
     */
    fun recordRecent(
        content: String,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
        now: Long = System.currentTimeMillis(),
        key: String? = null,
    ): MemoryEntry {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        return upsert(content, MemoryKind.RECENT, sourceConversationId, sourceMessageId, now, key)
    }

    /**
     * 写入的统一入口。
     *
     * 判定顺序（**不能换**）：
     *   0. **同 key 覆盖**（结构化记忆的主力）—— 非 event 键只有一个值
     *   1. **同来源幂等** —— 同一次抽取的重试不能长出新条目
     *   2. **字面相同**
     *   3. **近义相同** —— 没 key 的自由文本兜底
     *   4. **墓碑守门** —— 用户删过的不许复活
     *
     * @return 实际落库的那一条（可能是被合并/覆盖的旧条目）
     */
    private fun upsert(
        content: String,
        kind: MemoryKind,
        sourceConversationId: String?,
        sourceMessageId: String?,
        now: Long,
        key: String? = null,
    ): MemoryEntry {
        val trimmed = content.trim().let {
            if (it.length > config.maxEntryChars) it.take(config.maxEntryChars) else it
        }
        require(trimmed.isNotBlank()) { "记忆内容不能为空" }
        val normalizedKey = MemoryKey.normalize(key)

        val alive = store.all().filterNot { it.deleted }

        // 容量上限：saved 满了就把最旧的降级为 recent，而不是直接删
        // 依据：官方有 "Memory almost full"，说明它是软上限而非硬失败
        if (kind == MemoryKind.SAVED) {
            val saved = alive.filter { it.kind == MemoryKind.SAVED }
            if (saved.size >= config.savedCapacity) {
                saved.minByOrNull { it.createdAt }?.let { oldest ->
                    store.put(oldest.copy(kind = MemoryKind.RECENT))
                }
            }
        }

        // ---- 0) 同 key 覆盖：结构化记忆的主力 ----
        //   ★ 只有 event.* 允许同 key 多条；其余键"一个键一个值"。
        //     这就是「回答风格被抽 9 次也只留一条」的结构性保证。
        if (normalizedKey != null && !MemoryKey.allowsDuplicates(normalizedKey)) {
            alive.firstOrNull { it.key == normalizedKey }?.let { existing ->
                return replaceValue(existing, trimmed, kind, now)
            }
        }

        // ---- 1) 同来源幂等：同一条消息抽出来的东西，重试不能长出新条目 ----
        if (sourceMessageId != null) {
            alive.firstOrNull { it.sourceMessageId == sourceMessageId }?.let { existing ->
                return merge(existing, trimmed, kind, sourceConversationId, sourceMessageId, now, normalizedKey)
            }
        }

        // ---- 2) 字面相同 ----
        val incomingBlob = Dedupe.blob(trimmed)
        val exact = alive.firstOrNull { Dedupe.blob(it.content) == incomingBlob }

        // ---- 3) 近义相同（措辞有微小差异的同一批事实）----
        //   ★ 但两个**不同的非空 key** 占的是两个槽位，哪怕值一模一样也不合并：
        //     `user.city=上海` 和 `project.location=上海` 是两条设定，不是重复。
        val duplicate = (exact ?: alive.firstOrNull {
            Dedupe.isSameTopic(it.content, trimmed, config.nearDuplicateThreshold)
        })?.takeIf { other ->
            !(normalizedKey != null && other.key != null && other.key != normalizedKey)
        }
        if (duplicate != null) {
            return merge(duplicate, trimmed, kind, sourceConversationId, sourceMessageId, now, normalizedKey)
        }

        // ---- 4) 墓碑守门：用户删掉过的内容，抽取器再看到也不许复活 ----
        //   ★ 这是删除语义的一部分：删除是用户的决定，不是"暂时隐藏"。
        //     返回墓碑本身（不写库），调用方拿到一个条目，但库里不会多出一条。
        val tombstone = store.all()
            .filter { it.deleted }
            .firstOrNull { Dedupe.isSameTopic(it.content, trimmed, config.nearDuplicateThreshold) }
        if (tombstone != null) return tombstone

        val entry = MemoryEntry(
            id = idGenerator(),
            content = trimmed,
            kind = kind,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            createdAt = now,
            hitCount = 1,
            key = normalizedKey,
        )
        store.put(entry)
        if (kind == MemoryKind.RECENT) pruneRecent(now)
        return entry
    }

    /**
     * 同 key 覆盖：**值换、身份不换**。
     *
     * 保留 id / createdAt / 来源，这样：
     * - 面板上这条记忆不会"跳位"
     * - `explain()` 还能回答"它最早从哪来"
     * - 缓存顺序不变
     */
    private fun replaceValue(
        existing: MemoryEntry,
        incoming: String,
        kind: MemoryKind,
        now: Long,
    ): MemoryEntry {
        val updated = existing.copy(
            content = incoming,
            kind = if (existing.kind == MemoryKind.SAVED || kind == MemoryKind.SAVED) {
                MemoryKind.SAVED
            } else {
                existing.kind
            },
            lastUsedAt = now,
            hitCount = existing.hitCount + 1,
        )
        store.put(updated)
        return updated
    }

    /**
     * 合并到一个已有条目上。
     *
     * 规则：
     * - **层级取高**：任一方是 SAVED 就保持 SAVED（用户显式保存过的东西不能被抽取降级）
     * - **内容取长的**：更长的版本通常信息更全（如"用户喜欢猫粮" ⊃ "用户喜欢猫"），
     *   单条有 [MemoryConfig.maxEntryChars] 封顶，不会无限膨胀
     * - **来源只补不覆盖**：旧条目没有来源时把新的填上（用于 explain / 幂等）
     */
    private fun merge(
        existing: MemoryEntry,
        incoming: String,
        kind: MemoryKind,
        sourceConversationId: String?,
        sourceMessageId: String?,
        now: Long,
        incomingKey: String? = null,
    ): MemoryEntry {
        val merged = existing.copy(
            content = if (incoming.length > existing.content.length) incoming else existing.content,
            kind = if (existing.kind == MemoryKind.SAVED || kind == MemoryKind.SAVED) {
                MemoryKind.SAVED
            } else {
                existing.kind
            },
            sourceConversationId = existing.sourceConversationId ?: sourceConversationId,
            sourceMessageId = existing.sourceMessageId ?: sourceMessageId,
            lastUsedAt = now,
            hitCount = existing.hitCount + 1,
            // 旧条目没 key 而新来的有 ⇒ 补上（旧数据可以逐步结构化）
            key = existing.key ?: incomingKey,
        )
        store.put(merged)
        return merged
    }

    /**
     * recent 超容量时删掉**权重最低**的几条。
     *
     * ★ 只动 RECENT：它是"可能被遗忘"的那层，物理删除符合产品语义；
     *   SAVED 永远只降级、不删除。
     */
    private fun pruneRecent(now: Long) {
        val recent = store.all().filter { !it.deleted && it.kind == MemoryKind.RECENT }
        val overflow = recent.size - config.recentCapacity
        if (overflow <= 0) return
        recent
            .sortedWith(
                compareBy<MemoryEntry> {
                    Decay.effectiveWeight(it, now, config.decayHalfLifeDays)
                }.thenBy { it.createdAt },
            )
            .take(overflow)
            .forEach { store.remove(it.id) }
    }

    // ---------------------------------------------------------------
    // 用户直接管理（面板用）—— 编辑 / 置顶 / 合并重复 / 清空回收站
    // ---------------------------------------------------------------

    /**
     * 编辑一条记忆的内容（和可选的键）。
     *
     * ★ 编辑**不做**近义合并：用户明确写下的文字优先，不能被自动逻辑改掉。
     *   但会把 key 归一化；非法 key 保持原值（宁可不动，也不写脏数据）。
     */
    fun update(
        id: String,
        content: String,
        key: String? = null,
        now: Long = System.currentTimeMillis(),
    ): MemoryEntry? {
        val entry = store.all().firstOrNull { it.id == id } ?: return null
        val trimmed = content.trim().let {
            if (it.length > config.maxEntryChars) it.take(config.maxEntryChars) else it
        }
        if (trimmed.isBlank()) return null
        val normalizedKey = MemoryKey.normalize(key) ?: entry.key
        val updated = entry.copy(content = trimmed, key = normalizedKey, lastUsedAt = now)
        store.put(updated)
        return updated
    }

    /**
     * 置顶 = 升为长期（SAVED）；取消置顶 = 降回近期（RECENT）。
     *
     * ★ 刻意不新增 `pinned` 字段：SAVED 的产品语义本来就是"不会忘"，
     *   多一个布尔只会让"长期 / 置顶"两套状态互相打架。
     */
    fun setPinned(id: String, pinned: Boolean): MemoryEntry? {
        val entry = store.all().firstOrNull { it.id == id } ?: return null
        val updated = entry.copy(
            kind = if (pinned) MemoryKind.SAVED else MemoryKind.RECENT,
        )
        store.put(updated)
        return updated
    }

    /**
     * 找出"疑似重复"的组（每组 ≥2 条），用于面板的一键合并。
     *
     * ★ 已经有不同 key 的两条**不会被判重**：它们占的是两个槽位，
     *   合并会丢掉其中一个槽位的语义。
     */
    fun findDuplicateGroups(threshold: Double = config.nearDuplicateThreshold): List<List<MemoryEntry>> {
        val alive = store.all().filterNot { it.deleted }
        val parent = IntArray(alive.size) { it }

        fun find(x: Int): Int {
            var i = x
            while (parent[i] != i) {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in alive.indices) {
            for (j in i + 1 until alive.size) {
                val a = alive[i]
                val b = alive[j]
                if (a.key != null && b.key != null && a.key != b.key) continue
                if (Dedupe.isSameTopic(a.content, b.content, threshold)) union(i, j)
            }
        }

        return alive.indices
            .groupBy { find(it) }
            .values
            .filter { it.size >= 2 }
            .map { idxs -> idxs.map { alive[it] } }
    }

    /**
     * 合并一组重复记忆：保留最长的一条（同等长度取最早的），
     * 其余进墓碑、命中次数累加。返回保留下来的那条。
     */
    fun mergeGroup(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
        /** 合并后的新文本（记忆管理员给的）；null = 保留最长那条的原文 */
        content: String? = null,
    ): MemoryEntry? {
        val entries = store.all().filter { it.id in ids && !it.deleted }
        if (entries.size < 2) return entries.firstOrNull()

        val keeper = entries.sortedWith(
            compareByDescending<MemoryEntry> { it.content.length }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        ).first()
        val others = entries.filter { it.id != keeper.id }

        val mergedContent = content?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it.length > config.maxEntryChars) it.take(config.maxEntryChars) else it
        } ?: keeper.content

        val merged = keeper.copy(
            content = mergedContent,
            hitCount = entries.sumOf { it.hitCount },
            lastUsedAt = now,
            kind = if (entries.any { it.kind == MemoryKind.SAVED }) MemoryKind.SAVED else keeper.kind,
        )
        store.put(merged)
        others.forEach { store.put(it.copy(deleted = true)) }
        return merged
    }

    /** 清空回收站（物理删除所有墓碑）。返回删除条数。 */
    fun purgeDeleted(): Int {
        val tombstones = store.all().filter { it.deleted }
        tombstones.forEach { store.remove(it.id) }
        return tombstones.size
    }

    // ---------------------------------------------------------------
    // 召回
    // ---------------------------------------------------------------

    /**
     * 组装本轮要注入的记忆文本。
     * 同时更新被用到记忆的 lastUsedAt / hitCount（用于衰减与提升判断）。
     */
    fun composeContext(
        totalContextTokens: Int,
        now: Long = System.currentTimeMillis(),
        touch: Boolean = true,
        /** 本轮的检索意图（通常是最后一条用户消息）。空 = 不做相关性筛选，全量按预算注入。 */
        query: String = "",
    ): Composition {
        // ★★★ 相关性筛选：记忆多到超过注入上限时，**按查询挑**而不是按时间挑。
        //   为什么必须有这一步：条数上限是 100，但库里可能刚过 100 条，
        //   只靠 createdAt 排序等于"最近的 100 条覆盖全部"，用户问起三个月前的事就永远找不到。
        //
        // ★ 只在「超过上限」时才筛：条数少的时候全量注入更准（模型自己会挑），
        //   任何筛选都可能漏掉它真正需要的那条。
        val all = store.all()
        val aliveCount = all.count { !it.deleted }
        val candidates = if (query.isNotBlank() && aliveCount > config.maxInjectedEntries) {
            val picked = retriever.search(query, all, config.maxInjectedEntries, now)
            // 检索可能只返回少量高相关条目；把未选中的**长期记忆**补回来 ——
            // SAVED 是用户显式要求记住的，不该因为一次查询没命中就消失。
            val pickedIds = picked.map { it.id }.toSet()
            val mustKeep = all.filter { !it.deleted && it.id !in pickedIds && it.kind == MemoryKind.SAVED }
            picked + mustKeep
        } else {
            all
        }

        val result = composer.compose(candidates, totalContextTokens, now)

        if (touch) {
            (result.usedSaved + result.usedRecent).forEach { used ->
                store.put(used.copy(lastUsedAt = now, hitCount = used.hitCount + 1))
            }
        }
        return result
    }

    /**
     * 按查询检索记忆（`search_memory` 工具的入口）。
     *
     * ★ 与 [composeContext] 共用同一个 [retriever] —— 这是"工具里能搜到"与
     *   "注入里能找到"不会分叉的唯一保证。
     */
    fun search(
        query: String,
        limit: Int = 20,
        now: Long = System.currentTimeMillis(),
    ): List<MemoryEntry> {
        if (query.isBlank()) return emptyList()
        val hits = retriever.search(query, store.all(), limit, now)
        // 命中即 touch：语义检索"用到了"也是用到，不更新的话衰减会把它算成"没人用"
        hits.forEach { store.put(it.copy(lastUsedAt = now, hitCount = it.hitCount + 1)) }
        return hits
    }

    // ---------------------------------------------------------------
    // 提升 / 删除 / 可解释性
    // ---------------------------------------------------------------

    /**
     * 把一条 recent 提升为 saved。
     *
     * 触发条件之一：同一内容被反复命中（hitCount >= promoteThreshold）。
     * 对应官方："ChatGPT is now better at keeping memory up to date"
     */
    fun promote(id: String): MemoryEntry? {
        val entry = store.all().firstOrNull { it.id == id } ?: return null
        if (entry.kind == MemoryKind.SAVED) return entry
        val promoted = entry.copy(kind = MemoryKind.SAVED)
        store.put(promoted)
        return promoted
    }

    /** 自动提升：扫描命中次数达标的 recent。返回被提升的条目。 */
    fun autoPromote(): List<MemoryEntry> {
        val candidates = store.all().filter {
            it.kind == MemoryKind.RECENT && !it.deleted && it.hitCount >= config.promoteThreshold
        }
        return candidates.mapNotNull { promote(it.id) }
    }

    /**
     * 删除记忆（墓碑，不是物理删除）。
     *
     * 对应官方 "Saved memory (deleted)" —— 保留痕迹，让用户能确认"确实删了"。
     */
    fun delete(id: String): Boolean {
        val entry = store.all().firstOrNull { it.id == id } ?: return false
        store.put(entry.copy(deleted = true))
        return true
    }

    /** 恢复被删除的记忆。 */
    fun restore(id: String): Boolean {
        val entry = store.all().firstOrNull { it.id == id } ?: return false
        if (!entry.deleted) return false
        store.put(entry.copy(deleted = false))
        return true
    }

    /**
     * 可解释性：这条记忆从哪来？
     *
     * 对应官方 "Why this memory?" —— 没有这个，用户不会信任记忆系统。
     */
    fun explain(id: String, now: Long = System.currentTimeMillis()): Explanation? {
        val entry = store.all().firstOrNull { it.id == id } ?: return null
        return Explanation(
            entry = entry,
            weight = Decay.effectiveWeight(entry, now, config.decayHalfLifeDays),
            ageDays = (now - entry.createdAt).toDouble() / 86_400_000.0,
            isPromoted = entry.kind == MemoryKind.SAVED && entry.hitCount > 0,
        )
    }

    /** 全部记忆（默认不含墓碑）。 */
    fun list(includeDeleted: Boolean = false): List<MemoryEntry> =
        store.all().filter { includeDeleted || !it.deleted }

    /** 统计，用于 "Memory almost full" 提示。 */    fun stats(): Stats {
        val alive = store.all().filterNot { it.deleted }
        return Stats(
            savedCount = alive.count { it.kind == MemoryKind.SAVED },
            recentCount = alive.count { it.kind == MemoryKind.RECENT },
            savedCapacity = config.savedCapacity,
        )
    }
}

data class Explanation(
    val entry: MemoryEntry,
    /** 当前权重（0,1] */
    val weight: Double,
    val ageDays: Double,
    val isPromoted: Boolean,
)

data class Stats(
    val savedCount: Int,
    val recentCount: Int,
    val savedCapacity: Int,
) {
    val isAlmostFull: Boolean get() = savedCount >= (savedCapacity * 0.9).toInt()
}
