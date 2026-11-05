package com.chatmobile.markdown

/**
 * 把 [MathTypesetter.Layout] 变成一段**可放进 Text 的带样式文本**。
 *
 * ## ★ 为什么最终落点是字符串而不是 Composable
 *
 * 见 [MathTypesetter] 的注释：为了能在 `LazyColumn` 里与正文共用同一次文本测量、
 * 能跟随正文字号与主题色、能被复制。
 *
 * 本类负责把"多行 + 每段有字号/偏移"的结构编成一个 `AnnotatedString`：
 * - 行与行之间用 `\n`
 * - 每段的字号倍率 → `SpanStyle(fontSize = base.sp * scale)`
 * - 每段的垂直偏移 → `baselineShift`（Compose 的 `SpanStyle` 支持）
 * - 分式横线 → 用一串 `─`（BOX DRAWINGS LIGHT HORIZONTAL）铺出来
 *
 * ## ★★ 横线为什么用 `─` 而不是画一条真实的线
 *
 * 真实线条要 `drawBehind` + 自定义测量，那就必须回到 Composable，
 * 重新引入测量/回收问题。而 `─` 是**等宽字体里宽度恒定的字符**，
 * 我们在等宽字体下渲染公式（见 MarkdownText 的 MathBlock 分支），
 * 所以它能铺出一条视觉上连续的横线，且跟随字号缩放。
 *
 * ★ 前提：**公式必须用等宽字体渲染**。比例字体下 `─` 宽度不一致，线会断成虚线。
 *   这个约束由调用方保证（MathBlock 已用 `FontFamily.Monospace`）。
 */
object MathAnnotator {

    /**
     * 排版并生成带样式的字符串。
     *
     * @param source LaTeX 源码（不含定界符）
     * @param baseFontSizeSp 正文字号（sp）—— 所有倍率都相对它
     * @return 可直接喂给 `Text(AnnotatedString)` 的结果
     */
    fun render(
        source: String,
        baseFontSizeSp: Float,
    ): Result = renderParsed(LatexMathParser.parse(source), baseFontSizeSp)

    /** 已经解析过的节点（避免重复解析）。 */
    fun renderParsed(node: MathNode, baseFontSizeSp: Float): Result {
        val layout = MathTypesetter.layout(node)
        val builder = androidx.compose.ui.text.AnnotatedString.Builder()

        for ((index, line) in layout.lines.withIndex()) {
            if (index > 0) builder.append("\n")

            if (line.isFractionBar) {
                // ★ 横线：宽度按 barWidthEm 折算成字符数。
                //   等宽字体下 1 个 `─` ≈ 1 个字符宽（约 0.6em），
                //   所以字符数 = em 宽度 / 0.6，至少 3 个（短横线看不清）。
                val count = maxOf(3, (line.barWidthEm / 0.6f).toInt())
                builder.pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            baseFontSizeSp,
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                        color = androidx.compose.ui.graphics.Color.Unspecified,
                    ),
                )
                builder.append("─".repeat(count))
                builder.pop()
                continue
            }

            for (seg in line.segments) {
                if (seg.text.isEmpty()) continue
                val size = (baseFontSizeSp * seg.scale).coerceAtLeast(6f)
                builder.pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            size,
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                        // ★ baselineShift 的类型是 BaselineShift，不是 TextUnit。
                        //   正 = 往下。上标为负、下标为正。
                        //   单位按 em × 字号折算成 sp —— 嵌套时随字号一起缩。
                        baselineShift = androidx.compose.ui.text.style.BaselineShift(
                            seg.baselineShiftEm * baseFontSizeSp,
                        ),
                        fontStyle = if (seg.kind == SymKind.Variable) {
                            // ★ 变量斜体是数学排版惯例：`x` 与 `×` 才分得清。
                            //   函数名（sin/log）必须正体 —— 斜体就成了 s·i·n。
                            androidx.compose.ui.text.font.FontStyle.Italic
                        } else {
                            androidx.compose.ui.text.font.FontStyle.Normal
                        },
                    ),
                )
                builder.append(seg.text)
                builder.pop()
            }
        }

        return Result(
            text = builder.toAnnotatedString(),
            heightRows = layout.height,
            hasFraction = layout.lines.any { it.isFractionBar },
        )
    }

    /**
     * 渲染结果。
     *
     * [hasFraction] 用来决定要不要给公式块**多留一点上下内边距** ——
     * 带分式的公式上下更挤，需要呼吸空间。
     */
    data class Result(
        val text: androidx.compose.ui.text.AnnotatedString,
        val heightRows: Int,
        val hasFraction: Boolean,
    )
}
