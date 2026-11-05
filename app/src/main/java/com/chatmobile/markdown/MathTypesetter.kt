package com.chatmobile.markdown

/**
 * 把 [MathNode] 树**排版**成一组"视觉行"。
 *
 * ## ★★★ 为什么最终产物是「行」而不是 Compose 组件树
 *
 * 这是本方案最关键的一个决定。表面上看，分式应该画成分数线 + 上下的两个 Composable，
 * 但那样做会在 `LazyColumn` 里踩三个坑：
 *
 * 1. **无法与正文同一行** —— 行内公式 `$x^2$` 必须与汉字共用基线，
 *    独立组件做不到（只能整体抬升，中文会错位）
 * 2. **无法跨行折行** —— 长公式要跟着正文自动换行，组件树做不到
 * 3. **测量与回收开销** —— 每条消息里几十个嵌套 Row/Column，
 *    `LazyColumn` 滚动时的测量成本会翻倍（本项目在气泡上已踩过）
 *
 * 所以这里走**中间层**：把二维的数学结构压成若干"视觉行"
 * （分式 = 分子行 + 横线行 + 分母行；上下标 = 上下偏移的同行），
 * 再由调用方用一个 `Text` 显示。
 *
 * ★ 代价：**分式只能独占一块**（不能与文字混排在同一行内），
 *   因为它的高度是多行。这在聊天场景里是可接受的 —— 分式本来就需要呼吸空间。
 *
 * ## ★ 各行如何对齐
 *
 * 每个视觉行记录**基线在第几行**（[MathLine.baselineRow]）。
 * 分式的横线在"基线行"，分子在其上一行、分母在其下一行 ——
 * 这样外层再套一个分式时，嵌套关系自然正确。
 */
object MathTypesetter {

    /**
     * 排版结果。
     *
     * [lines] 是**自上而下**的视觉行；[baselineRow] 是整体基线的行号。
     */
    data class Layout(
        val lines: List<MathLine>,
        val baselineRow: Int,
        /** 整体高度（行数）—— 用于判断要不要额外留白。 */
        val height: Int = lines.size,
    )

    /**
     * 一个视觉行。
     *
     * [segments] 按 x 顺序排列；每段有自己的字号倍率与上下偏移。
     */
    data class MathLine(
        val segments: List<Segment>,
        /**
         * 这一行的基线相对整体基线的**行偏移**。
         *
         * 负 = 在基线上方（分子），正 = 下方（分母）。
         */
        val rowOffset: Int = 0,
        /** 是否是分式的横线行（需要绘制一条实线）。 */
        val isFractionBar: Boolean = false,
        /** 横线的横向范围（em 单位，相对本行起点）。 */
        val barStartEm: Float = 0f,
        val barWidthEm: Float = 0f,
    )

    /**
     * 一段文字。
     *
     * @param text 显示内容
     * @param scale 字号倍率（分子/分母/上下标都缩小）
     * @param baselineShiftEm 基线的**垂直**偏移（em，正 = 往下）
     */
    data class Segment(
        val text: String,
        val scale: Float,
        val baselineShiftEm: Float = 0f,
        val kind: SymKind = SymKind.Ordinary,
    )

    // --------------------------------------------------------------
    // 字号倍率（数学排版的通行比例）
    // --------------------------------------------------------------

    /** 分子/分母/上下标的字号倍率。0.72 是 LaTeX 的 scriptstyle 比例。 */
    private const val SCRIPT_SCALE = 0.72f

    /** 嵌套两层之后的最小倍率 —— 再小就看不清了。 */
    private const val MIN_SCALE = 0.55f

    /**
     * 排版一个节点。
     *
     * ★ 顶层用 [SCRIPT_SCALE] = 1（正文字号）。
     */
    fun layout(node: MathNode, baseScale: Float = 1f): Layout =
        when (node) {
            is MathNode.Row -> layoutRow(node.items, baseScale)
            else -> layoutRow(listOf(node), baseScale)
        }

    // --------------------------------------------------------------
    // Row：横向拼接，需要对齐基线
    // --------------------------------------------------------------

    private fun layoutRow(items: List<MathNode>, scale: Float): Layout {
        if (items.isEmpty()) {
            return Layout(listOf(MathLine(emptyList())), baselineRow = 0)
        }

        // 逐项排版，得到各自的 Layout
        val parts = items.map { it to layoutAny(it, scale) }

        // ---- 先确定整体向上/向下各需要多少行 ----
        var above = 0
        var below = 0
        for ((_, lay) in parts) {
            above = maxOf(above, lay.baselineRow)
            below = maxOf(below, lay.lines.size - 1 - lay.baselineRow)
        }
        val totalRows = above + below + 1
        val baseRow = above

        // ---- 每项按自己的基线对齐到 baseRow ----
        val rowSegs = Array(totalRows) { mutableListOf<SegEntry>() }
        for ((node, lay) in parts) {
            // 项内基线在 baseRow - (lay.baselineRow - r) 的位置
            val offset = baseRow - lay.baselineRow
            for ((r, line) in lay.lines.withIndex()) {
                val target = r + offset
                if (target !in 0 until totalRows) continue
                if (line.isFractionBar) {
                    // 横线行：单独标记，交给上层画线（这里先记成特殊段）
                    rowSegs[target] += SegEntry.bar(line.barWidthEm)
                } else {
                    for (seg in line.segments) rowSegs[target] += SegEntry.text(seg)
                }
            }
        }

        // ---- 组织成 MathLine ----
        val lines = (0 until totalRows).map { r ->
            val entries = rowSegs[r]
            val segs = mutableListOf<Segment>()
            var barStart = 0f
            var barWidth = 0f
            var hasBar = false
            for (e in entries) {
                if (e.isBar) {
                    hasBar = true
                    // 横线起点 = 当前已排内容的宽度
                    barStart = segs.sumOf { (it.text.length * it.scale * 0.5).toDouble() }.toFloat()
                    barWidth = maxOf(barWidth, e.barWidth)
                } else {
                    segs += e.seg!!
                }
            }
            MathLine(
                segments = segs,
                rowOffset = r - baseRow,
                isFractionBar = hasBar,
                barStartEm = barStart,
                barWidthEm = barWidth,
            )
        }
        return Layout(lines, baselineRow = baseRow)
    }

    /** 横线占位（Row 拼接时用）。 */
    private class SegEntry private constructor(
        val seg: Segment?,
        val isBar: Boolean,
        val barWidth: Float,
    ) {
        companion object {
            fun text(s: Segment) = SegEntry(s, false, 0f)
            fun bar(w: Float) = SegEntry(null, true, w)
        }
    }

    // --------------------------------------------------------------
    // 各节点的排版
    // --------------------------------------------------------------

    private fun layoutAny(node: MathNode, scale: Float): Layout = when (node) {
        is MathNode.Sym -> Layout(
            listOf(MathLine(listOf(Segment(node.text, scale, 0f, node.kind)))),
            baselineRow = 0,
        )

        is MathNode.Row -> layoutRow(node.items, scale)

        /**
         * 分式：分子 + 横线 + 分母。
         *
         * ★ 横线那一行**本身不占字高**（只有一条线），
         *   所以 [baselineRow] 指向横线行 —— 这样 `a + \frac{b}{c} + d`
         *   里的 `+` 会与分数线对齐，正是数学排版里"运算符对齐分数线"的惯例。
         */
        is MathNode.Frac -> {
            val inner = maxOf(scale * SCRIPT_SCALE, MIN_SCALE)
            val numLay = layoutAny(node.num, inner)
            val denLay = layoutAny(node.den, inner)

            val numRows = numLay.lines.size
            val denRows = denLay.lines.size

            // 横线宽度取分子分母中较宽的估算值
            val numW = estimateWidth(node.num, inner)
            val denW = estimateWidth(node.den, inner)
            val barW = maxOf(numW, denW) + 0.3f

            val lines = mutableListOf<MathLine>()
            // 分子
            lines += numLay.lines
            // 横线（基线所在行）
            val barRow = numRows
            lines += MathLine(
                segments = emptyList(),
                isFractionBar = true,
                barWidthEm = barW,
            )
            // 分母
            lines += denLay.lines

            Layout(lines, baselineRow = barRow)
        }

        /**
         * 上下标。
         *
         * ★★★ 关键决定：上下标**不新增视觉行**，而是在**同一行内**用
         *   `baselineShift` + 缩小字号表达。
         *
         *   一开始我让上标独占一行（rowOffset = -1），结果整行高度翻倍，
         *   `x^2 + y^2` 这种常见公式变成三行高 —— 完全不像数学。
         *   真正的排版里上下标就是**贴在基线上方的同一个行盒**里。
         *
         *   所以这里把 base 与 sup/sub 全部压进同一行，靠
         *   `Segment.baselineShiftEm` 上下错开。
         *
         * ★ 若 base 是 BigOp，则由 [layoutBigOp] 处理（上下限位置不同）。
         */
        is MathNode.Script -> {
            val inner = maxOf(scale * SCRIPT_SCALE, MIN_SCALE)
            val baseSegs = flattenToSegments(node.base, scale).toMutableList()

            // ★ 下标在前、上标在后：`x_i^2` 读起来是 x_i 再平方，
            //   视觉上也是下标更靠左。
            node.sub?.let { sub ->
                baseSegs += flattenToSegments(sub, inner).map {
                    it.copy(baselineShiftEm = it.baselineShiftEm + 0.28f)
                }
            }
            node.sup?.let { sup ->
                baseSegs += flattenToSegments(sup, inner).map {
                    it.copy(baselineShiftEm = it.baselineShiftEm - 0.42f)
                }
            }
            Layout(listOf(MathLine(baseSegs)), baselineRow = 0, height = 1)
        }

        /**
         * 根号：`√` 加一条**上横线**。
         *
         * ★ 上横线用 U+0305（COMBINING OVERLINE）覆盖在内容上 ——
         *   这是纯文本里能做到的、最接近"根号罩住内容"的写法。
         *   真正的伸缩根号需要绘制路径，那会破坏"一个 Text 搞定"的前提。
         */
        is MathNode.Sqrt -> {
            val body = flattenToSegments(node.body, scale)
            val withBar = overlayBar(body)
            val prefix: List<Segment> = if (node.index != null) {
                flattenToSegments(node.index, maxOf(scale * 0.6f, MIN_SCALE)) +
                    listOf(Segment("√", scale, 0f, SymKind.Ordinary))
            } else {
                listOf(Segment("√", scale, 0f, SymKind.Ordinary))
            }
            Layout(listOf(MathLine(prefix + withBar)), baselineRow = 0)
        }

        /**
         * 大运算符。
         *
         * ★ 两种位置约定（见 [MathNode.BigOp.limitsOnSide]）：
         *   - `limitsOnSide = true`（`∫`）：上下限挤在**同一行**的右侧，
         *     用上下偏移区分 —— 保持整行高度不失控
         *   - `limitsOnSide = false`（`∑`）：上下限各占一行，堆在正上下
         */
        is MathNode.BigOp -> layoutBigOp(node, scale)

        is MathNode.Delim -> Layout(
            listOf(MathLine(listOf(Segment(node.text, scale, 0f, SymKind.Ordinary)))),
            baselineRow = 0,
        )

        is MathNode.Space -> Layout(
            listOf(MathLine(listOf(Segment(" ".repeat(maxOf(1, (node.widthEm * 2).toInt())), scale)))),
            baselineRow = 0,
        )
    }

    private fun layoutBigOp(node: MathNode.BigOp, scale: Float): Layout {
        val inner = maxOf(scale * SCRIPT_SCALE, MIN_SCALE)
        val sym = Segment(node.sym, scale * 1.25f, 0f, SymKind.Ordinary)

        return if (node.limitsOnSide) {
            // `∫`：上下限在右侧，用 baselineShift 区分（不新增行）
            val segs = mutableListOf(sym)
            node.sub?.let {
                segs += Segment(flattenText(it), inner * 0.9f, baselineShiftEm = 0.35f)
            }
            node.sup?.let {
                segs += Segment(flattenText(it), inner * 0.9f, baselineShiftEm = -0.45f)
            }
            Layout(listOf(MathLine(segs)), baselineRow = 0)
        } else {
            // `∑`：上下限堆在上下
            val lines = mutableListOf<MathLine>()
            var baseRow = 0
            if (node.sup != null) {
                lines += MathLine(flattenToSegments(node.sup, inner))
                baseRow++
            }
            lines += MathLine(listOf(sym))
            if (node.sub != null) {
                lines += MathLine(flattenToSegments(node.sub, inner))
            }
            Layout(lines, baselineRow = baseRow)
        }
    }

    // --------------------------------------------------------------
    // 工具
    // --------------------------------------------------------------

    /** 把任意节点压平成同一行的段落序列（用于上下标、根号内容）。 */
    private fun flattenToSegments(node: MathNode, scale: Float): List<Segment> =
        when (node) {
            is MathNode.Sym -> listOf(Segment(node.text, scale, 0f, node.kind))
            is MathNode.Row -> node.items.flatMap { flattenToSegments(it, scale) }
            is MathNode.Script -> {
                val out = flattenToSegments(node.base, scale).toMutableList()
                node.sub?.let { out += Segment(flattenText(it), scale * 0.85f, 0.2f) }
                node.sup?.let { out += Segment(flattenText(it), scale * 0.85f, -0.2f) }
                out
            }
            is MathNode.Frac -> {
                // 压平的分式用 `/` 表达（在上下标这种小空间里画不了横线）
                flattenToSegments(node.num, scale) +
                    listOf(Segment("/", scale, 0f, SymKind.BinaryOp)) +
                    flattenToSegments(node.den, scale)
            }
            is MathNode.Sqrt -> {
                val p = if (node.index != null) {
                    "√" + flattenText(node.index)
                } else {
                    "√"
                }
                listOf(Segment(p, scale, 0f, SymKind.Ordinary)) +
                    overlayBar(flattenToSegments(node.body, scale))
            }
            is MathNode.BigOp -> listOf(Segment(node.sym, scale))
            is MathNode.Delim -> listOf(Segment(node.text, scale))
            is MathNode.Space -> listOf(Segment(" ", scale))
        }

    /** 把节点压成纯文本（用于必须单串的场合，如上下标里再套上下标）。 */
    private fun flattenText(node: MathNode): String =
        flattenToSegments(node, 1f).joinToString("") { it.text }

    /** 估算宽度（em）。字符数 × 平均字宽，够用于分式横线长度。 */
    private fun estimateWidth(node: MathNode, scale: Float): Float =
        flattenText(node).length * scale * 0.55f

    /**
     * 给内容加"上横线"。
     *
     * ★ 用 U+0305（COMBINING OVERLINE）逐字叠加。
     *   这是纯文本里唯一能"罩住内容"的办法；换行处线会断，
     *   但公式很少在这里换行，可接受。
     */
    private fun overlayBar(segs: List<Segment>): List<Segment> =
        segs.map { seg ->
            seg.copy(text = seg.text.map { ch -> "$ch\u0305" }.joinToString(""))
        }
}
