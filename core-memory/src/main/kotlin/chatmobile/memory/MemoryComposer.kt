package chatmobile.memory

/**
 * 上下文组装器 —— 记忆系统最终要交付的东西。
 *
 * 职责：把 saved / recent 两层记忆按预算切成一段可注入的文本。
 * 这是整个记忆系统的**唯一出口**：上层不需要知道内部怎么排序、怎么衰减。
 *
 * 设计原则（来自官方行为的逆向，见可行性报告 §3）：
 *   1. saved 优先 —— 用户明确要求记住的东西不能被 recent 挤掉
 *   2. recent 按权重降序，填满剩余预算
 *   3. 永远不超预算 —— 宁可少记，不能让请求失败
 */
class MemoryComposer(private val config: MemoryConfig = MemoryConfig()) {

    /**
     * 把记忆组装成注入 system prompt 的文本块。
     *
     * @param memories 全部记忆（含已删除的，本方法会过滤）
     * @param totalContextTokens 总上下文预算（token）
     * @param now 当前时间
     * @return 组装结果；没有可用记忆时 [Composition.text] 为空字符串
     */
    fun compose(
        memories: List<MemoryEntry>,
        totalContextTokens: Int,
        now: Long,
    ): Composition {
        require(totalContextTokens > 0) { "上下文预算必须为正" }

        val alive = memories.filterNot { it.deleted }
        // ★ 渲染期去重：库里可能**已经**存着历史遗留的重复条目（改写入逻辑之前进的库），
        //   在出口再拦一次，用户立刻看不到重复，不必等下次抽取把它们合并掉。
        val kept = mutableListOf<MemoryEntry>()

        // ---- 第一层：saved，全量优先 ----
        val savedBudget = (totalContextTokens * config.savedBudgetRatio).toInt()
        val savedAll = alive
            .filter { it.kind == MemoryKind.SAVED }
            .sortedByDescending { it.createdAt }   // 新的优先（容量满时保留新的）

        val savedKept = mutableListOf<MemoryEntry>()
        var savedTokens = 0
        for (m in savedAll) {
            if (savedKept.size >= config.maxInjectedEntries) break
            val text = clamp(m.content)
            if (isDuplicateOf(m, text, kept)) continue
            val t = estimateTokens(text, config)
            if (savedTokens + t > savedBudget) break
            savedKept += m.copy(content = text)
            kept += m.copy(content = text)
            savedTokens += t
        }

        // ---- 第二层：recent，按权重填剩余 ----
        val recentBudget = (totalContextTokens * config.recentBudgetRatio).toInt()
        val recentRanked = alive
            .filter { it.kind == MemoryKind.RECENT }
            .sortedWith(recentComparator(now))

        val recentKept = mutableListOf<MemoryEntry>()
        var recentTokens = 0
        for (m in recentRanked) {
            if (savedKept.size + recentKept.size >= config.maxInjectedEntries) break
            val text = clamp(m.content)
            if (isDuplicateOf(m, text, kept)) continue
            val t = estimateTokens(text, config)
            if (recentTokens + t > recentBudget) continue   // 这条放不下，试下一条更短的
            recentKept += m.copy(content = text)
            kept += m.copy(content = text)
            recentTokens += t
        }

        // ---- 渲染 ----
        val sb = StringBuilder()
        if (savedKept.isNotEmpty()) {
            sb.appendLine("以下是用户长期设定或明确要求你记住的信息：")
            savedKept.forEach { sb.appendLine("- ${MemoryKey.render(it)}") }
        }
        if (recentKept.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("以下是近期对话中可能相关的上下文（越靠前越相关）：")
            recentKept.forEach { sb.appendLine("- ${MemoryKey.render(it)}") }
        }

        return Composition(
            text = sb.toString().trimEnd(),
            usedSaved = savedKept,
            usedRecent = recentKept,
            savedTokens = savedTokens,
            recentTokens = recentTokens,
            totalBudget = totalContextTokens,
        )
    }

    /** 历史数据可能超过单条上限，渲染前截断（不写回库）。 */
    private fun clamp(text: String): String =
        if (text.length > config.maxEntryChars) text.take(config.maxEntryChars) else text

    /**
     * 与已保留的任意一条"说的是同一件事" ⇒ 跳过。
     *
     * ★ 两个**不同 key** 的条目占的是两个槽位（如 `pref.reply_style` 与
     *   `user.name`），即使文字相近也**不合并** —— 那是两条不同的设定。
     */
    private fun isDuplicateOf(entry: MemoryEntry, text: String, kept: List<MemoryEntry>): Boolean {
        if (Dedupe.blob(text).isEmpty()) return true
        return kept.any { other ->
            val sameSlot = entry.key != null && other.key != null && entry.key != other.key
            !sameSlot && Dedupe.isSameTopic(other.content, text, config.nearDuplicateThreshold)
        }
    }

    /**
     * recent 层的排序规则。
     *
     * ★ 这里修过一个真实的 bug：最初只按 [Decay.effectiveWeight] 排序，
     * 结果同一批记忆（createdAt 相同、都没被用过）权重完全相同，
     * 排序退化成「插入顺序」——「越相关越靠前」形同虚设。
     *
     * 现在用四级排序，每一级都是确定性的：
     *   1. 权重降序
     *   2. 命中次数降序（同样老，被用得多更重要）
     *   3. 最后使用时间降序（同样老、同样常用，最近用过的更重要）
     *   4. id 升序（兜底，保证输入顺序不影响输出）
     */
    private fun recentComparator(now: Long): Comparator<MemoryEntry> =
        compareByDescending<MemoryEntry> {
            Decay.effectiveWeight(it, now, config.decayHalfLifeDays)
        }
            .thenByDescending { it.hitCount }
            .thenByDescending { it.lastUsedAt ?: Long.MIN_VALUE }
            .thenBy { it.id }
}

/** 组装结果。保留明细，是为了能做"为什么它记得这个"的可解释性。 */
data class Composition(
    val text: String,
    val usedSaved: List<MemoryEntry>,
    val usedRecent: List<MemoryEntry>,
    val savedTokens: Int,
    val recentTokens: Int,
    val totalBudget: Int,
) {
    val usedTokens: Int get() = savedTokens + recentTokens
    val isEmpty: Boolean get() = usedSaved.isEmpty() && usedRecent.isEmpty()

    /** 本次实际用到预算的百分比，用于调参 */
    val budgetUsage: Double get() = usedTokens.toDouble() / totalBudget
}
