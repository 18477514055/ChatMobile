package com.chatmobile.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数学排版的结构契约。
 *
 * ## 为什么必须测「结构」而不是测「长得像不像」
 *
 * 用户的要求是「变成更加标准的样子」。而"标准"在像素层面无法断言
 * （截图没法比对），但在**结构**层面完全可测：
 *   - 分式必须产出「分子行 + 横线行 + 分母行」三行
 *   - 分式的基线必须落在**横线行**（这样 `a + b/c + d` 的 `+` 才对得上分数线）
 *   - `\int` 的上下限必须在**右侧**、`\sum` 必须在**上下**
 *   - 变量必须斜体、函数名必须正体
 *
 * 这些正是"像不像 KaTeX"的实质差别。测了它们，就不必靠肉眼比对截图。
 */
class MathTypesetterTest {

    private fun flat(layout: MathTypesetter.Layout): String =
        layout.lines.joinToString("\n") { line ->
            line.segments.joinToString("") { it.text }
        }

    private fun allText(node: MathNode): String =
        MathTypesetter.layout(node).lines
            .joinToString("") { line -> line.segments.joinToString("") { it.text } }

    // ------------------------------------------------------------------
    // 一、解析
    // ------------------------------------------------------------------

    @Test
    fun `普通公式解析成 Row`() {
        val n = LatexMathParser.parse("a + b")
        assertTrue("必须是 Row", n is MathNode.Row)
    }

    @Test
    fun `分式解析成 Frac 节点`() {
        val n = LatexMathParser.parse("\\frac{a}{b}")
        assertTrue("必须解析成分式而不是斜杠文本", n is MathNode.Frac)
    }

    @Test
    fun `嵌套分式解析正确`() {
        val n = LatexMathParser.parse("\\frac{\\frac{a}{b}}{c}")
        assertTrue(n is MathNode.Frac)
        assertTrue("分子里还应该有一个分式", n is MathNode.Frac && n.num is MathNode.Frac)
    }

    @Test
    fun `上下标解析成 Script`() {
        val n = LatexMathParser.parse("x^2")
        assertTrue("必须解析成上标结构", n is MathNode.Script)
        assertNotNull((n as MathNode.Script).sup)
    }

    @Test
    fun `同时有上下标`() {
        // x_i^2 —— 数学里常见
        val n = LatexMathParser.parse("x_i^2")
        assertTrue(n is MathNode.Script)
        val s = n as MathNode.Script
        assertNotNull("下标必须有", s.sub)
        assertNotNull("上标必须有", s.sup)
    }

    @Test
    fun `根号解析成 Sqrt`() {
        assertTrue(LatexMathParser.parse("\\sqrt{x}") is MathNode.Sqrt)
    }

    @Test
    fun `带次数的根号保留次数`() {
        val n = LatexMathParser.parse("\\sqrt[3]{x}")
        assertTrue(n is MathNode.Sqrt)
        assertNotNull("开方次数必须保留", (n as MathNode.Sqrt).index)
    }

    @Test
    fun `半截公式不抛异常`() {
        // ★ 流式输出时这是常态。抛一次异常 = 整条消息渲染失败。
        listOf("\\frac{a}{", "\\sqrt{", "x^", "\\", "{", "}", "\\left(").forEach { broken ->
            val n = runCatching { LatexMathParser.parse(broken) }.getOrNull()
            assertNotNull("「$broken」不该抛异常", n)
        }
    }

    @Test
    fun `空输入返回空节点`() {
        val n = LatexMathParser.parse("")
        assertTrue(n is MathNode.Row)
        assertTrue("空 Row", (n as MathNode.Row).items.isEmpty())
    }

    // ------------------------------------------------------------------
    // 二、分式的排版（最关键的一条）
    // ------------------------------------------------------------------

    @Test
    fun `分式必须产出三行 分子 横线 分母`() {
        val lay = MathTypesetter.layout(LatexMathParser.parse("\\frac{a}{b}"))
        assertEquals("分子 + 横线 + 分母 = 3 行", 3, lay.lines.size)
        assertTrue("中间那行必须是横线", lay.lines[1].isFractionBar)
        assertEquals("分子在第 0 行", "a", lay.lines[0].segments.joinToString("") { it.text })
        assertEquals("分母在第 2 行", "b", lay.lines[2].segments.joinToString("") { it.text })
    }

    @Test
    fun `分式的基线必须落在横线上`() {
        // ★ 这条决定了 `a + \frac{b}{c} + d` 里 `+` 是否与分数线对齐。
        //   落在分子或分母上都会让整行看起来"错位"。
        val lay = MathTypesetter.layout(LatexMathParser.parse("\\frac{a}{b}"))
        assertEquals("基线必须是横线那一行", 1, lay.baselineRow)
        assertTrue(lay.lines[lay.baselineRow].isFractionBar)
    }

    @Test
    fun `横线必须有一定宽度`() {
        val lay = MathTypesetter.layout(LatexMathParser.parse("\\frac{abcdef}{x}"))
        assertTrue("横线必须有宽度", lay.lines[1].barWidthEm > 0f)
    }

    @Test
    fun `分子分母字号必须比正文小`() {
        // ★ 这是数学排版的通行比例（LaTeX scriptstyle ≈ 0.72）。
        //   分子分母与正文同字号会让分式显得臃肿且不像数学。
        val lay = MathTypesetter.layout(LatexMathParser.parse("\\frac{a}{b}"))
        val numScale = lay.lines[0].segments.first().scale
        assertTrue("分子字号必须小于 1，实际 $numScale", numScale < 1f)
        assertTrue("但不能小到看不清，实际 $numScale", numScale >= 0.55f)
    }

    @Test
    fun `嵌套分式行数递增`() {
        val simple = MathTypesetter.layout(LatexMathParser.parse("\\frac{a}{b}"))
        val nested = MathTypesetter.layout(LatexMathParser.parse("\\frac{\\frac{a}{b}}{c}"))
        assertTrue(
            "嵌套分式必须比简单分式更高（简单 ${simple.lines.size} 行，嵌套 ${nested.lines.size} 行）",
            nested.lines.size > simple.lines.size,
        )
    }

    // ------------------------------------------------------------------
    // 三、上下标
    // ------------------------------------------------------------------

    @Test
    fun `上标产生偏上的段落`() {
        val lay = MathTypesetter.layout(LatexMathParser.parse("x^2"))
        val allSegs = lay.lines.flatMap { it.segments }
        val sup = allSegs.firstOrNull { it.text == "2" }
        assertNotNull("上标里的 2 必须在", sup)
        assertTrue("上标必须往上偏移（负值），实际 ${sup!!.baselineShiftEm}", sup.baselineShiftEm < 0f)
    }

    @Test
    fun `上标字号必须缩小`() {
        val lay = MathTypesetter.layout(LatexMathParser.parse("x^2"))
        val two = lay.lines.flatMap { it.segments }.firstOrNull { it.text == "2" }!!
        assertTrue("上标字号必须 < 1", two.scale < 1f)
    }

    @Test
    fun `下标必须往下偏移`() {
        val lay = MathTypesetter.layout(LatexMathParser.parse("x_i"))
        val i = lay.lines.flatMap { it.segments }.firstOrNull { it.text == "i" }!!
        assertTrue("下标必须往下偏移（正值），实际 ${i.baselineShiftEm}", i.baselineShiftEm > 0f)
    }

    // ------------------------------------------------------------------
    // 四、大运算符的上下限位置（数学惯例，做反了很业余）
    // ------------------------------------------------------------------

    @Test
    fun `积分上下限放右侧 不堆叠`() {
        // ★ \int 的上下限堆到正上下会让整行高度翻倍，视觉上完全不像公式
        val n = LatexMathParser.parse("\\int_a^b f")
        assertTrue(n is MathNode.Row)
        val bigOp = (n as MathNode.Row).items.filterIsInstance<MathNode.BigOp>().first()
        assertTrue("积分的上下限必须在右侧", bigOp.limitsOnSide)
        assertNotNull("积分必须有下标", bigOp.sub)
        assertNotNull("积分必须有上标", bigOp.sup)
    }

    @Test
    fun `求和上下限堆在上下`() {
        val n = LatexMathParser.parse("\\sum_{i=1}^{n} a_i")
        val bigOp = (n as MathNode.Row).items.filterIsInstance<MathNode.BigOp>().first()
        assertTrue("求和的上下限必须堆叠（不在右侧）", !bigOp.limitsOnSide)
    }

    @Test
    fun `积分保持单行高度`() {
        // ★ 这条是"上下限放右侧"的**可验证后果**
        val bySide = MathTypesetter.layout(LatexMathParser.parse("\\int_a^b"))
        val stacked = MathTypesetter.layout(LatexMathParser.parse("\\sum_a^b"))
        assertEquals("积分的上下限不占额外行", 1, bySide.lines.size)
        assertEquals("求和的上下限各占一行 = 3 行", 3, stacked.lines.size)
    }

    // ------------------------------------------------------------------
    // 五、字体类别（斜体规则）
    // ------------------------------------------------------------------

    @Test
    fun `变量必须是斜体`() {
        val n = LatexMathParser.parse("x")
        val sym = (n as? MathNode.Sym)
            ?: ((n as MathNode.Row).items.filterIsInstance<MathNode.Sym>().first())
        assertEquals("变量必须判为 Variable（渲染时斜体）", SymKind.Variable, sym.kind)
    }

    @Test
    fun `函数名必须是正体`() {
        // ★ sin 斜体就成了 s·i·n —— 这是最常见的"不专业"来源
        val n = LatexMathParser.parse("\\sin x")
        val items = (n as MathNode.Row).items
        val fn = items.filterIsInstance<MathNode.Sym>().first { it.text == "sin" }
        assertEquals("函数名必须判为 Function（渲染时正体）", SymKind.Function, fn.kind)
    }

    @Test
    fun `数字必须是正体`() {
        val n = LatexMathParser.parse("123")
        val sym = (n as? MathNode.Sym)
            ?: ((n as MathNode.Row).items.filterIsInstance<MathNode.Sym>().first())
        assertEquals("数字必须判为 Number", SymKind.Number, sym.kind)
    }

    @Test
    fun `连续数字合成一个节点`() {
        // 拆开会各自有间距判断，视觉上出现空隙
        val n = LatexMathParser.parse("1234")
        val sym = (n as? MathNode.Sym)
            ?: ((n as MathNode.Row).items.filterIsInstance<MathNode.Sym>().first())
        assertEquals("1234 必须是一个整体", "1234", sym.text)
    }

    @Test
    fun `关系符判为 Relation`() {
        val n = LatexMathParser.parse("a = b")
        val eq = (n as MathNode.Row).items.filterIsInstance<MathNode.Sym>().first { it.text == "=" }
        assertEquals(SymKind.Relation, eq.kind)
    }

    // ------------------------------------------------------------------
    // 六、符号表与内容保全
    // ------------------------------------------------------------------

    @Test
    fun `希腊字母转成 Unicode`() {
        assertEquals("α", allText(LatexMathParser.parse("\\alpha")))
        assertEquals("π", allText(LatexMathParser.parse("\\pi")))
        assertEquals("Σ", allText(LatexMathParser.parse("\\Sigma")))
    }

    @Test
    fun `text 里的内容原样保留`() {
        // 中文/英文说明不该被当成变量
        val t = allText(LatexMathParser.parse("\\text{当且仅当}"))
        assertEquals("当且仅当", t)
    }

    @Test
    fun `未知命令丢命令名但保留内容`() {
        // 宁可少一个修饰，也不要让用户看到一屏反斜杠
        val t = allText(LatexMathParser.parse("\\unknowncmd{abc}"))
        assertTrue("内容必须保留，实际：$t", t.contains("abc"))
        assertTrue("命令名不该裸露，实际：$t", !t.contains("unknowncmd"))
    }

    @Test
    fun `sqrt 保留根号与内容`() {
        val t = allText(LatexMathParser.parse("\\sqrt{x+1}"))
        assertTrue("必须有根号，实际：$t", t.contains("√"))
        assertTrue("内容必须都在，实际：$t", t.contains("x") && t.contains("1"))
    }

    @Test
    fun `left right 的括号保留`() {
        val t = allText(LatexMathParser.parse("\\left( x \\right)"))
        assertTrue("括号必须保留，实际：$t", t.contains("(") && t.contains(")"))
    }
}
