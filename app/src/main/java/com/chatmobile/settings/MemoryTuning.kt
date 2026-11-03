package com.chatmobile.settings

import chatmobile.memory.MemoryConfig

/**
 * 记忆库可调参数的**用户覆盖层**。
 *
 * ## 为什么要单独一层，而不是直接把 `MemoryConfig` 存进 DataStore
 *
 * `MemoryConfig` 是纯逻辑模块里的数据类，字段会随算法演进而增删。
 * 直接序列化它的全部字段，等于把「实现细节」写进用户的持久化数据：
 * 以后加一个字段，老用户的存档里就没有它；删一个字段，旧值会读失败。
 *
 * 所以这里只存**用户真正改过的项**（稀疏覆盖），其余全部回落到
 * `MemoryConfig()` 的默认值 —— 加字段天然兼容，删字段也不会读崩。
 *
 * ## 编码
 *
 * `k=v` 用 `;` 连接，值为空或非法即忽略。
 * ★ 不用 `org.json`：它在本项目的 JVM 单测里是空 stub，用它写的编解码测试会静默失败。
 */
data class MemoryTuning(
    /** 触发整理的轮数（几条新消息） */
    val extractEveryTurns: Int? = null,
    /** 一次喂给管理员的消息条数 */
    val extractWindow: Int? = null,
    /** 单次最多几条 op */
    val extractMaxOps: Int? = null,
    /** 单条记忆最大字数 */
    val maxEntryChars: Int? = null,
    /** 注入条数上限 */
    val maxInjectedEntries: Int? = null,
    /** recent 容量 */
    val recentCapacity: Int? = null,
    /** saved 容量 */
    val savedCapacity: Int? = null,
    /** 提升阈值（命中几次升长期） */
    val promoteThreshold: Int? = null,
    /** 近义阈值 */
    val nearDuplicateThreshold: Double? = null,
    /** 衰减半衰期（天） */
    val decayHalfLifeDays: Double? = null,
    /** 长期注入占比 0..1 */
    val savedBudgetRatio: Double? = null,
    /** 近期注入占比 0..1 */
    val recentBudgetRatio: Double? = null,
    /** 向量召回绝对下限（余弦） */
    val retrievalMinScore: Double? = null,
    /** 向量召回相对下限（最高分的比例） */
    val retrievalRelativeRatio: Double? = null,
) {
    val isDefault: Boolean
        get() = this == MemoryTuning()

    /** 叠加到默认配置上；任何越界值都会被 `MemoryConfig` 的 `require` 挡住 ⇒ 退回默认。 */
    fun applyTo(base: MemoryConfig): MemoryConfig =
        // ★ 必须把**构造**也包进 runCatching —— `MemoryConfig` 的 require 在
        //   `copy()` 里就抛，等到 `return candidate` 时已经崩了（测试抓到过）。
        runCatching {
            base.copy(
                extractEveryTurns = extractEveryTurns ?: base.extractEveryTurns,
                extractWindow = extractWindow ?: base.extractWindow,
                extractMaxOps = extractMaxOps ?: base.extractMaxOps,
                maxEntryChars = maxEntryChars ?: base.maxEntryChars,
                maxInjectedEntries = maxInjectedEntries ?: base.maxInjectedEntries,
                recentCapacity = recentCapacity ?: base.recentCapacity,
                savedCapacity = savedCapacity ?: base.savedCapacity,
                promoteThreshold = promoteThreshold ?: base.promoteThreshold,
                nearDuplicateThreshold = nearDuplicateThreshold ?: base.nearDuplicateThreshold,
                decayHalfLifeDays = decayHalfLifeDays ?: base.decayHalfLifeDays,
                savedBudgetRatio = savedBudgetRatio ?: base.savedBudgetRatio,
                recentBudgetRatio = recentBudgetRatio ?: base.recentBudgetRatio,
                retrievalMinScore = retrievalMinScore ?: base.retrievalMinScore,
                retrievalRelativeRatio = retrievalRelativeRatio ?: base.retrievalRelativeRatio,
            )
        }.getOrElse { base }

    fun encode(): String = buildList {
        extractEveryTurns?.let { add("eet=$it") }
        extractWindow?.let { add("ew=$it") }
        extractMaxOps?.let { add("emo=$it") }
        maxEntryChars?.let { add("mec=$it") }
        maxInjectedEntries?.let { add("mie=$it") }
        recentCapacity?.let { add("rc=$it") }
        savedCapacity?.let { add("sc=$it") }
        promoteThreshold?.let { add("pt=$it") }
        nearDuplicateThreshold?.let { add("ndt=$it") }
        decayHalfLifeDays?.let { add("dhl=$it") }
        savedBudgetRatio?.let { add("sbr=$it") }
        recentBudgetRatio?.let { add("rbr=$it") }
        retrievalMinScore?.let { add("rms=$it") }
        retrievalRelativeRatio?.let { add("rrr=$it") }
    }.joinToString(";")

    companion object {
        fun decode(raw: String?): MemoryTuning {
            if (raw.isNullOrBlank()) return MemoryTuning()
            val map = raw.split(';')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
                }
                .toMap()
            fun int(k: String): Int? = map[k]?.trim()?.toIntOrNull()
            fun dbl(k: String): Double? = map[k]?.trim()?.toDoubleOrNull()
            return MemoryTuning(
                extractEveryTurns = int("eet"),
                extractWindow = int("ew"),
                extractMaxOps = int("emo"),
                maxEntryChars = int("mec"),
                maxInjectedEntries = int("mie"),
                recentCapacity = int("rc"),
                savedCapacity = int("sc"),
                promoteThreshold = int("pt"),
                nearDuplicateThreshold = dbl("ndt"),
                decayHalfLifeDays = dbl("dhl"),
                savedBudgetRatio = dbl("sbr"),
                recentBudgetRatio = dbl("rbr"),
                retrievalMinScore = dbl("rms"),
                retrievalRelativeRatio = dbl("rrr"),
            )
        }
    }
}
