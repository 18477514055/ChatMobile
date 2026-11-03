package com.chatmobile.ui.chat

/**
 * **思考过程的展开程度** —— 对齐 DSH 的「工作步骤展示」四档。
 *
 * ## ★ 为什么需要一个全局档位，而不是每个气泡自己一个开关
 *
 * 用户的原话诉求是「按照 DSH 设计一下思考过程的展开程度」。DSH 那个下拉
 * （简洁 / 标准 / 详细 / 完全展开）本质上是**同一份信息的四种呈现密度**，
 * 不是"每条消息各自展开/收起"。两者差别很大：
 *
 * - 每条各自开关 ⇒ 一屏里一半展开一半收起，视觉上极乱，而且翻上去看历史时
 *   你还得逐条点开。
 * - 全局档位 ⇒ 一次设定，整屏一致。**这是"读起来舒服"的关键。**
 *
 * ## 四档各自该给什么（不是简单截断字数）
 *
 * | 档位 | 思考气泡里显示 | 适用 |
 * |---|---|---|
 * | [Collapsed] | 只有一行「思考过程 · N 字」，不显示内容 | 只想看答案 |
 * | [Compact] | 前 [COMPACT_LINES] 行，超出显示「…还有 N 行」 | 扫一眼它在想什么 |
 * | [Standard] | 全部内容，但用更小字号、更低对比度 | 常规 |
 * | [Expanded] | 全部内容 + 等宽字体 + 保留原始换行/缩进 | 要逐字看推理 |
 *
 * ★ 关键区别在**折叠态**：DSH 的"简洁"不是把长文本截一半糊上去，
 *   而是**只留一行摘要**。截一半会让人误以为那就是全部。
 */
enum class ReasoningDisplay(val label: String, val description: String) {

    /** 只有一行摘要，不展开内容。 */
    Collapsed(
        label = "收起",
        description = "只显示「思考过程 · N 字」，点开才看内容",
    ),

    /** 前几行 + 省略提示。 */
    Compact(
        label = "简洁",
        description = "只显示开头几行，其余折叠",
    ),

    /** 全文，常规字号。 */
    Standard(
        label = "标准",
        description = "显示全部内容，小字号低对比",
    ),

    /** 全文 + 等宽 + 保留缩进。 */
    Expanded(
        label = "完全展开",
        description = "全部内容，等宽字体，保留原始换行与缩进",
    ),
    ;

    companion object {
        /** 「简洁」档显示的行数。 */
        const val COMPACT_LINES: Int = 6

        /** 从持久化值解析；非法/空 ⇒ 默认 [Standard]。 */
        fun fromName(name: String?): ReasoningDisplay =
            entries.firstOrNull { it.name == name } ?: Standard

        val DEFAULT: ReasoningDisplay = Standard
    }
}

/**
 * **思考过程何时自动收起** —— 对应设置页「收起时机」。
 *
 * ★ DSH 里这一项叫「工作步骤收起时机」，默认「回答结束后」。
 *   它和 [ReasoningDisplay] 是**两件事**：
 *   - [ReasoningDisplay] 回答**过程中**展开多少
 *   - [ReasoningAutoCollapse] 回答**结束后**要不要自动收成一行
 *
 * ★ 为什么默认 [AfterAnswer] 而不是 [Never]：
 *   思考过程是过程，不是结论。回答完了还占着半屏，会把真正的答案推下去。
 *   想回看的人点一下标题即可 —— 这比"默认全摊着、每次手动收"省事得多。
 */
enum class ReasoningAutoCollapse(val label: String) {

    /** 回答结束就收成一行。 */
    AfterAnswer("回答结束后"),

    /** 永不自动收起，始终保持全局档位。 */
    Never("从不收起"),
    ;

    companion object {
        fun fromName(raw: String?): ReasoningAutoCollapse =
            entries.firstOrNull { it.name == raw } ?: AfterAnswer

        val DEFAULT: ReasoningAutoCollapse = AfterAnswer
    }
}

/**
 * 把思考过程按档位裁成**要显示的文本**。
 *
 * ★ 做成纯函数（不碰 Compose）是为了可单测 —— 裁切逻辑错一格，
 *   用户看到的就是"少了半句"，而这种错误在真机上极难发现。
 *
 * @return Pair(要显示的文本, 是否被裁掉了)
 */
fun clipReasoning(text: String, display: ReasoningDisplay): Pair<String, Boolean> {
    if (text.isBlank()) return "" to false
    return when (display) {
        // 收起态不显示内容 —— 由调用方决定不渲染正文
        ReasoningDisplay.Collapsed -> "" to false

        ReasoningDisplay.Compact -> {
            val lines = text.lines()
            if (lines.size <= ReasoningDisplay.COMPACT_LINES) {
                text to false
            } else {
                lines.take(ReasoningDisplay.COMPACT_LINES).joinToString("\n") to true
            }
        }

        ReasoningDisplay.Standard,
        ReasoningDisplay.Expanded,
        -> text to false
    }
}

/** 「简洁」档被裁掉时，剩余行数（用于「…还有 N 行」提示）。 */
fun remainingReasoningLines(text: String, display: ReasoningDisplay): Int {
    if (display != ReasoningDisplay.Compact) return 0
    val total = text.lines().size
    return (total - ReasoningDisplay.COMPACT_LINES).coerceAtLeast(0)
}

/**
 * **这条消息此刻实际该用哪一档** —— 纯函数，把三件事合成一个结果。
 *
 * ## ★★★ 为什么必须做成纯函数并单测
 *
 * 用户第三次报「思考过程还是收不回去」。前两次我都改的是**点击处理**，
 * 但真正的问题是**决定"该展开多少"的这条链有三份输入**，任何一份没接上，
 * 表现都一模一样（点了没反应）。三份输入是：
 *
 * | 输入 | 来源 | 没接上的后果 |
 * |---|---|---|
 * | 全局档位 | 设置页「工作步骤展示」 | 永远停在 [ReasoningDisplay.DEFAULT] |
 * | 自动收起 | 设置页「收起时机」 | 回答完了还整段摊着 |
 * | 手动覆盖 | 用户点标题 | 点了下一帧被重置 |
 *
 * 做成纯函数之后，"三份输入合成什么结果"可以脱离 Compose 直接验，
 * 不用靠真机点击去试 —— 这正是前两次漏掉的那一环。
 *
 * @param display 全局档位（设置页选的）
 * @param autoCollapse 回答结束后是否自动收起（设置页「收起时机」）
 * @param isStreaming 这条消息是否还在流式接收
 * @param manualOverride 用户手动点过标题：`true` = 展开，`false` = 收起，`null` = 没点过
 */
fun effectiveReasoningDisplay(
    display: ReasoningDisplay,
    autoCollapse: Boolean,
    isStreaming: Boolean,
    manualOverride: Boolean?,
): ReasoningDisplay {
    // ① 用户手动点过 ⇒ 以他为准，任何自动行为都不得覆盖
    manualOverride?.let { return if (it) ReasoningDisplay.Expanded else ReasoningDisplay.Collapsed }

    // ② 还在流式接收 ⇒ 正想看他思考什么，按全局档位来
    if (isStreaming) return display

    // ③ 回答已结束 + 开了自动收起 ⇒ 收成一行（用户想看得自己点开）
    if (autoCollapse) return ReasoningDisplay.Collapsed

    // ④ 关掉自动收起 ⇒ 维持全局档位
    return display
}
