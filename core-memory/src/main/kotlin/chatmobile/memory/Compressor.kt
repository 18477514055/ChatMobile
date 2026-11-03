package chatmobile.memory

/**
 * 压缩产物 —— 一次压缩之后，被折掉的那段历史变成了什么。
 *
 * ★★★ 这个类的存在本身就回答了一个问题：**凭什么相信压缩没丢东西？**
 *
 *   业界主流做法（Claude Code auto-compact、OpenHands condenser、Codex remote compact）
 *   是"用一段摘要**替换**原文"。官方文档逐字承认：
 *     *"It replaces the verbatim conversation: full tool outputs and
 *       intermediate reasoning are gone."*
 *   Claude Code 的示意实现里写的是 `tokens: Math.round(sumTokens * 0.12)`
 *   ⇒ **逐字保留率 12%**。被折掉的那 88% **没有副本**，事后想回读也没有。
 *
 *   这里的做法不同：**摘要 + 指针**。
 *   - [summary] 给模型看（省 token）
 *   - [fromMessageId] .. [toMessageId] 指向 Room 里**原封不动还在的那些消息**
 *   ⇒ 摘要写错了、或模型需要原文细节时，**按指针回库读原话**。
 *
 * ## 为什么指针用 messageId 而不是 seq / 行号
 *
 *   行号会因插入删除而失效；seq 是库内自增数，跨设备/重建库都不稳定。
 *   `messageId` 是 UUID，**永不变**，且 Room 里可以直接主键查。
 */
data class CompressedSpan(
    val id: String,
    /** 摘要文本，会被放进 system 消息给模型看 */
    val summary: String,
    /** 这段被折掉的历史从哪条消息开始（含） */
    val fromMessageId: String,
    /** 到哪条消息结束（含） */
    val toMessageId: String,
    /** 被折掉的消息条数，用于向用户交代"折了多少" */
    val messageCount: Int,
    /** 折叠时的估算 token，用于水位判断 */
    val approxTokens: Int,
    val createdAt: Long,
)

/**
 * 压缩策略参数。
 *
 * ★ 每个默认值的出处都写在注释里 —— 这些数字不该凭感觉定。
 */
data class CompressionConfig(
    /**
     * 触发压缩的 token 水位（占预算的比例）。
     *
     * 出处：billion-context 的 `EMERGENCY_NUDGE_ESCALATION_PCT = .7`（70% 紧急线）。
     * 参照项目调研文档 `02-参照物精读-billion-context.md`。
     * ★ 不用 80% 是因为要留出"压缩请求自己的输入"的空间 ——
     *   OpenClaw 社区把 `reserveTokensFloor` 从 20000 建议提到 40000，
     *   正是为了防 Summary Paradox（为防超窗要压缩，但压缩请求自己也要装下这些内容）。
     */
    val triggerRatio: Double = 0.70,

    /**
     * 压缩后**保留原文的最近消息条数**。
     *
     * 出处：各家的"逐字尾巴"——
     *   Claude Code `keep min 5 messages` + `~20 recent messages intact`；
     *   OpenClaw `keepRecentTokens = 20000`；SWE-agent `n = 5`；
     *   OpenHands 尾 `target_size - keep_first - 1`。
     * 手机端取 8：比 5 保守（聊天的"刚说过的话"参考价值更高），又比 20 省。
     */
    val keepRecentMessages: Int = 8,

    /**
     * 摘要请求自身允许吃掉多少 token。
     *
     * 出处：OpenClaw "单次摘要请求输入封顶 160,000 字符（≈40,000 token）"。
     * 手机端窗口小，压到 6000 —— 足够装下 8 条保留尾巴之外的中段。
     */
    val summarizerInputBudgetTokens: Int = 6_000,

    /** 摘要输出上限。太长的"摘要"等于没压。 */
    val summaryMaxTokens: Int = 400,
) {
    init {
        require(triggerRatio > 0 && triggerRatio < 1) { "触发水位必须在 (0,1)" }
        require(keepRecentMessages >= 0) { "保留条数不能为负" }
        require(summarizerInputBudgetTokens > 0) { "摘要输入预算必须为正" }
        require(summaryMaxTokens > 0) { "摘要输出上限必须为正" }
    }
}

/**
 * 压缩**提示词**。
 *
 * ★★★ 这是整个机制里最容易写坏的地方。参照项目调研里点名的三条硬约束：
 *
 *   1. **必留字段清单**（billion-context 的 `acpTags` 逐字）：
 *      路径+行号 / 签名 / 精确错误 / 命令 / 版本 / 阈值 / 带理由的决策 /
 *      当前状态 / 未完成 TODO。
 *      ⇒ 这些一旦被摘要抹成"讨论了错误处理"，就再也查不回来了。
 *
 *   2. **缺口必须显式计数，明令不要猜**（OpenClaw）：
 *      如果有内容没塞进来，要写"以下省略 N 条"，**不许编**。
 *
 *   3. **用户原话零容忍**：任何删词都破坏"这是他说过的话"这一逐字性。
 *      ⇒ 摘要里引用户的话必须**原样引用**。
 */
val COMPRESS_SYSTEM_PROMPT: String = """
你是一个对话压缩器。把下面这一段历史对话压缩成一段摘要，供后续对话参考。

必须保留（一条都不能丢）：
1. 用户说过的**原话**（引用时逐字照抄，一个字都不许改）
2. 具体的文件名、路径、代码标识符、命令、版本号、数字、阈值
3. 报错的**原文**
4. 做过的决定，以及**为什么**这么决定
5. 当前进行到哪一步、还没做完什么

可以省略：
- 寒暄、重复确认、"好的我明白了"这类过程性内容
- 已经失效的中间尝试（但如果它是"试过并且失败了"，要留一句"试过 X 失败"）
- 你自己长篇的解释性文字，只留结论

格式要求：
- 用「用户说了什么 / 做了什么决定 / 当前状态 / 未完成」四段
- 不要 markdown 代码块
- 不超过 400 字
- ★ 如果这段历史里确实没有某类信息，写「无」，**不要编**
""".trimIndent()

/**
 * 压缩器 —— 纯逻辑，不碰网络也不碰数据库。
 *
 * 它只做三件事：**判断该不该压** → **选出要压哪一段** → **生成指向那段历史的指针**。
 * 真正调 LLM 生成摘要由调用方做（这样这个类能脱离 Android 单测）。
 */
class Compressor(val config: CompressionConfig = CompressionConfig()) {

    /**
     * 判断要不要压缩。
     *
     * ★ 三条件（照 billion-context）：
     *   ① 水位过了阈值
     *   ② **确实有可压内容** —— 只剩尾巴时再压没意义，还会白烧一次 API
     *   ③ 有待压段
     *
     * @param usedTokens 估算已用 token
     * @param budgetTokens 预算
     * @param messageCount 当前消息总条数
     */
    fun shouldCompress(
        usedTokens: Int,
        budgetTokens: Int,
        messageCount: Int,
    ): Boolean {
        if (budgetTokens <= 0) return false
        val ratio = usedTokens.toDouble() / budgetTokens
        if (ratio < config.triggerRatio) return false
        // ★ 可压内容 = 总数 - 保留尾巴。<=1 条就不值得为它调一次 API。
        return (messageCount - config.keepRecentMessages) >= 1
    }

    /**
     * 选出要压的那一段：**从头开始**，一直折到"保留尾巴"之前。
     *
     * ★ 为什么从头折而不是从中间挖：
     *   挖中间会留下两段不连续的历史，模型读到会莫名其妙（"怎么突然跳到后面了"）。
     *   从头折 = 只留一条连续的"最近尾巴"，语义最干净。
     *
     * @return 要压的条数；0 表示不该压
     */
    fun spanToCompress(messageCount: Int): Int {
        val span = messageCount - config.keepRecentMessages
        return if (span > 0) span else 0
    }

    /**
     * 摘要请求的输入是否超预算 —— 超了必须**截断并显式计数**。
     *
     * ★ 照 OpenClaw 的做法："缺口标注被略过条数并明令不要猜"。
     *   静默截断是 Codex #13386 那个 bug 的形态：*"silently truncates… with no
     *   warning anywhere"* —— 我们不许重犯。
     */
    fun buildGapNotice(droppedCount: Int): String =
        if (droppedCount <= 0) "" else "\n\n【注意】上面省略了 $droppedCount 条较早的消息，未纳入本次压缩。不要猜测它们的内容。"
}
