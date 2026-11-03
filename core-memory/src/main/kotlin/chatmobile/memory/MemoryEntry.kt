package chatmobile.memory

/**
 * 记忆条目。
 *
 * 双层结构的核心：kind 决定这条记忆会不会衰减。
 * 依据 ChatGPT 官方设计（见 ChatMobile-可行性研究报告.md §3.1）：
 *   "ChatGPT tries to remember your recent chats, but it may forget things over time.
 *    Saved memories are never forgotten."（字符串 0x7f13180a）
 */
data class MemoryEntry(
    val id: String,
    val content: String,
    val kind: MemoryKind,
    /** 这条记忆从哪条会话来 —— 对应官方 "Why this memory?" */
    val sourceConversationId: String?,
    /** 具体哪条消息 */
    val sourceMessageId: String?,
    /** 创建时间（epoch millis） */
    val createdAt: Long,
    /** 最近一次被召回的时间；从未召回过为 null */
    val lastUsedAt: Long? = null,
    /** 被召回次数，用于判断"反复出现" */
    val hitCount: Int = 0,
    /** 已删除（墓碑），对应官方 "Saved memory (deleted)" */
    val deleted: Boolean = false,
    /**
     * 结构化的槽位键（如 `pref.reply_style` / `user.city` / `project.current` / `event.2026-10-28`）。
     *
     * ★★★ 这是"记忆去重"的结构性答案：
     *   - **非 event 键**：同 key 直接**覆盖**（新值替换旧值）⇒ 同一件事永远只有一条
     *   - **event 键**：允许追加（事件本来就是多条）
     *   - `null`：旧数据 / 模型没给 key 的自由文本，退回近义去重
     *
     * ★ key 的命名空间决定它在面板里归到哪一组（见 [MemoryKey]）。
     */
    val key: String? = null,
)


enum class MemoryKind {
    /** 永久层：永不衰减，只有用户能删 */
    SAVED,

    /** 近期层：随时间衰减，可能被遗忘 */
    RECENT,
}

/**
 * 记忆系统的可调参数。
 *
 * 全部给默认值，但**必须可配置** —— 这些数字需要拿真实对话调，
 * 写死在代码里就等于放弃了调优。
 */
data class MemoryConfig(
    /** saved 层最多占上下文预算的百分比 */
    val savedBudgetRatio: Double = 0.15,

    /** recent 层最多占上下文预算的百分比 */
    val recentBudgetRatio: Double = 0.10,

    /** recent 层衰减半衰期（天）。7 天 = 一周后权重减半 */
    val decayHalfLifeDays: Double = 7.0,

    /** saved 层容量上限（条），对应官方 "Memory almost full" */
    val savedCapacity: Int = 200,

    /** 同一主题出现多少次后，recent 可以提升为 saved */
    val promoteThreshold: Int = 3,

    /**
     * recent 层容量上限（条）。超出时**物理删掉权重最低的**（不是墓碑）。
     *
     * ★ 为什么 recent 可以真删：它本来就是"可能被遗忘"的那层；
     *   而 SAVED 层永远只降级不删除。容量上限的意义见
     *   `_research/同类产品记忆库-调研与去重方案.md`：LibreChat 用库容量强制"记忆要小"。
     */
    val recentCapacity: Int = 400,

    /** 单条记忆最大字符数，超出截断（防止一次抽取塞进整段对话） */
    val maxEntryChars: Int = 400,

    /** 每次注入最多几条（saved + recent 合计） */
    val maxInjectedEntries: Int = 100,

    /**
     * 判"同一件事"的 Jaccard 阈值（见 [Dedupe.isSameTopic]）。
     *
     * ★ 0.7 是起点，不是真理：
     *   - 「迭代固定提示词」/「固定提示词迭代」= 0.714 ⇒ 必须 ≤0.71 才能合并（同一件事换语序）
     *   - 「我喜欢猫」/「我喜欢狗」= 0.6 ⇒ 0.7 不会误并
     *   真实对话再调。
     */
    val nearDuplicateThreshold: Double = 0.7,

    /** 粗略的 token 估算：每字符算多少 token。中文约 1.0，英文约 0.25 */
    val tokensPerChar: Double = 0.6,

    // ---------------------------------------------------------------
    // 抽取/整理（写侧）—— 以前写死在 ChatHost 的常量里，现在搬进来以便面板统一调整
    // ---------------------------------------------------------------

    /**
     * 攒够多少条新消息才触发一次整理。
     *
     * ★ 越小越及时、越费 token：每次都要把「已有记忆快照 + 新对话」发给管理员模型。
     *   6 是保守值（一次对话通常 2~4 轮才有值得记的东西）。
     */
    val extractEveryTurns: Int = 6,

    /**
     * 一次最多把多少条新消息喂给管理员。
     *
     * ★ 上限存在的意义：一轮塞 50 条进去，模型会开始"总结对话"而不是"抽事实"。
     */
    val extractWindow: Int = 12,

    /**
     * 一次最多让管理员产出几条 op（ADD/UPDATE/MERGE）。
     *
     * ★ 这个数直接决定 maxTokens：`op 数 × ~60 token`。设太大等于纵容它编。
     */
    val extractMaxOps: Int = 5,

    /**
     * 向量召回的**绝对下限**（余弦相似度）。
     *
     * ★ 为什么必须有：`bge` 这类模型对**任意两句中文**都给出 0.3~0.45 的余弦
     *   （向量空间的各向异性），所以"cos > 0 就返回"等于**不过滤** ——
     *   真机上模型当场指出「查询 remote-call-test 不可能命中这 5 条，却返回了 5 条」。
     */
    val retrievalMinScore: Double = 0.45,

    /**
     * 相对下限：只保留「不低于最高分 × 这个比例」的条目。
     *
     * ★ 绝对阈值挡不住"全都中等相关"的情况；相对阈值保证结果**集中**在真正最像的几条，
     *   而不是把 top-K 填满。
     */
    val retrievalRelativeRatio: Double = 0.9,
) {
    init {
        require(savedBudgetRatio > 0 && savedBudgetRatio <= 1) {
            "savedBudgetRatio 必须在 (0, 1]，当前 $savedBudgetRatio"
        }
        require(recentBudgetRatio >= 0 && recentBudgetRatio <= 1) {
            "recentBudgetRatio 必须在 [0, 1]，当前 $recentBudgetRatio"
        }
        require(savedBudgetRatio + recentBudgetRatio <= 1) {
            "记忆预算之和不能超过 100%，当前 ${savedBudgetRatio + recentBudgetRatio}"
        }
        require(decayHalfLifeDays > 0) { "半衰期必须为正" }
        require(savedCapacity > 0) { "容量必须为正" }
        require(recentCapacity > 0) { "recent 容量必须为正" }
        require(maxEntryChars > 0) { "单条上限必须为正" }
        require(maxInjectedEntries > 0) { "注入条数上限必须为正" }
        require(nearDuplicateThreshold in 0.0..1.0) { "相似度阈值必须在 [0,1]" }
        require(tokensPerChar > 0) { "tokensPerChar 必须为正" }
        require(extractEveryTurns >= 1) { "触发轮数至少为 1" }
        require(extractWindow >= 1) { "抽取窗口至少为 1" }
        require(extractMaxOps in 1..20) { "单次 op 数必须在 1..20" }
        require(retrievalMinScore in 0.0..1.0) { "召回下限必须在 [0,1]" }
        require(retrievalRelativeRatio in 0.0..1.0) { "召回相对比例必须在 [0,1]" }
    }
}

/** 粗略 token 估算。不追求精确，只用于预算切分。 */
fun estimateTokens(text: String, config: MemoryConfig): Int =
    kotlin.math.ceil(text.length * config.tokensPerChar).toInt()
