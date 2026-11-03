package com.chatmobile.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **LaTeX → 可读文本** 的转换测试。
 *
 * ## ★★★ 这些用例直接来自用户截图
 *
 * 用户报的渲染 bug 原话是「你有没有发现他的渲染出问题了」，
 * 截图里模型输出的公式**原样显示**成
 *
 * ```
 * $$c^2 + 4\cdot\tfrac12 ab = (a+b)^2$$
 * $$\frac{AC}{AB} = \frac{AH}{AC} \quad\Longrightarrow\quad \frac{b}{c} = \frac{p}{b}$$
 * Step 3. (\triangle ACH \sim \triangle ABC)
 * ```
 *
 * 所以这里的输入字符串**逐字取自那张截图**，不是我自己编的简单例子。
 * 拿真实数据当夹具，才能保证"修好了"是真的修好了。
 */
class LatexTextTest {

    // ================================================================
    // 用户截图里的真实公式
    // ================================================================

    @Test
    fun `截图的第一个公式必须变成可读文本`() {
        val out = LatexText.render("""c^2 + 4\cdot\tfrac12 ab = (a+b)^2""")
        // 不许有任何反斜杠、`$`、花括号残留
        assertFalse("公式里不该残留反斜杠：$out", out.contains('\\'))
        assertFalse("不该残留美元符：$out", out.contains('$'))
        assertFalse("不该残留花括号：$out", out.contains('{'))
        // 关键符号必须换对了
        assertTrue("`\\cdot` 应变成 ·，实际：$out", out.contains('·'))
        assertTrue("`^2` 应变成 ²，实际：$out", out.contains('²'))
    }

    @Test
    fun `截图的分式公式必须变成可读文本`() {
        val out = LatexText.render(
            """\frac{AC}{AB} = \frac{AH}{AC} \quad\Longrightarrow\quad \frac{b}{c} = \frac{p}{b}""",
        )
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        // 分式变成 a/b
        assertTrue("`\\frac{AC}{AB}` 应变成 AC/AB，实际：$out", out.contains("AC/AB"))
        assertTrue("`\\frac{AH}{AC}` 应变成 AH/AC，实际：$out", out.contains("AH/AC"))
        assertTrue("`\\Longrightarrow` 应变成 ⟹，实际：$out", out.contains('⟹'))
    }

    @Test
    fun `截图里段落中的三角形符号必须换对`() {
        val out = LatexText.render("""\triangle ACH \sim \triangle ABC""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue("`\\triangle` 应变成 △，实际：$out", out.contains('△'))
        assertTrue("`\\sim` 应变成 ∼，实际：$out", out.contains('∼'))
    }

    @Test
    fun `截图的第三行公式`() {
        val out = LatexText.render("""a^2 + b^2 = cq + cp = c(p+q) = c^2""")
        assertFalse("不该残留任何 LaTeX 标记：$out", out.contains('^'))
        assertTrue("上标 2 必须变成 ²：$out", out.contains('²'))
    }

    // ================================================================
    // 分式
    // ================================================================

    @Test
    fun `简单分式不加多余括号`() {
        assertEquals("p/b", LatexText.render("""\frac{p}{b}"""))
        assertEquals("AC/AB", LatexText.render("""\frac{AC}{AB}"""))
    }

    @Test
    fun `复合分式必须加括号否则意思会变`() {
        // `a+b/2` 会被读成 `a + (b/2)`，意思变了 —— 必须写成 `(a+b)/2`
        assertEquals("(a+b)/2", LatexText.render("""\frac{a+b}{2}"""))
        assertEquals("1/(x+1)", LatexText.render("""\frac{1}{x+1}"""))
    }

    @Test
    fun `嵌套分式也要正确`() {
        val out = LatexText.render("""\frac{\frac{a}{b}}{c}""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue("嵌套分式应保留两层 /：$out", out.count { it == '/' } >= 2)
    }

    // ================================================================
    // 上下标
    // ================================================================

    @Test
    fun `上下标转成 Unicode`() {
        assertEquals("x²", LatexText.render("""x^2"""))
        assertEquals("a₁", LatexText.render("""a_1"""))
        assertEquals("xⁿ", LatexText.render("""x^n"""))
    }

    @Test
    fun `多字符上标全部有映射时逐字转`() {
        assertEquals("x¹²", LatexText.render("""x^{12}"""))
    }

    @Test
    fun `上标里有无法映射的字符时整体退化为括号形式`() {
        // `ᵃb` 会被误读成 `xᵃ · b`，所以必须整体退化
        val out = LatexText.render("""x^{ab}""")
        assertEquals("x^(ab)", out)
    }

    @Test
    fun `中文下标不崩且保留内容`() {
        val out = LatexText.render("""x_{总}""")
        assertTrue("内容必须保留：$out", out.contains("总"))
    }

    // ================================================================
    // 根号 / 希腊字母 / 运算符
    // ================================================================

    @Test
    fun `根号带次数`() {
        assertEquals("√(x+1)", LatexText.render("""\sqrt{x+1}"""))
        assertEquals("∛x", LatexText.render("""\sqrt[3]{x}"""))
    }

    @Test
    fun `希腊字母与运算符`() {
        assertEquals("α", LatexText.render("""\alpha"""))
        assertEquals("Ω", LatexText.render("""\Omega"""))
        assertEquals("≤", LatexText.render("""\le"""))
        assertEquals("∞", LatexText.render("""\infty"""))
        assertEquals("∑", LatexText.render("""\sum"""))
    }

    @Test
    fun `函数名去掉反斜杠且不留多余空格`() {
        val out = LatexText.render("""\sin x""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue("应保留 sin：$out", out.contains("sin"))
    }

    // ================================================================
    // 纯排版命令
    // ================================================================

    @Test
    fun `排版命令不产生垃圾字符`() {
        val out = LatexText.render("""\left( x \right)""")
        assertEquals("( x )", out.replace(Regex("""\s+"""), " ").trim())
    }

    @Test
    fun `quad 之类间距命令变成空白而不是残留`() {
        val out = LatexText.render("""a \quad b""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue("a 和 b 都要在：$out", out.contains('a') && out.contains('b'))
    }

    @Test
    fun `空公式不崩`() {
        assertEquals("", LatexText.render(""))
        assertEquals("", LatexText.render("   "))
    }

    @Test
    fun `未知命令丢掉命令名但保留参数内容`() {
        // 宁可少一个修饰，也不能让用户看到 `\weirdcmd{内容}`
        val out = LatexText.render("""\weirdcmd{内容}""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue("参数内容必须保留：$out", out.contains("内容"))
    }

    // ================================================================
    // ★★★ 路径护栏（这一步一旦漏掉会改坏用户数据）
    // ================================================================

    @Test
    fun `Windows 路径不许被当成数学`() {
        val path = """D:\Users\example\Documents\project"""
        assertFalse(
            "路径里没有结构性数学命令，绝不能当作数学处理 —— " +
                "否则会把用户的数据改坏（这比显示反斜杠严重得多）",
            LatexText.looksLikeMathInPlainText(path),
        )
    }

    @Test
    fun `UNC 路径不许被当成数学`() {
        assertFalse(
            LatexText.looksLikeMathInPlainText("""\\server\share\file.txt"""),
        )
        assertFalse(
            LatexText.looksLikeMathInPlainText("""C:\\Users\\test"""),
        )
    }

    @Test
    fun `带盘符的路径即使含 math 命令名也不碰`() {
        // 极端情况：路径里恰好有个 `\sum` 目录名，也不能替换
        assertFalse(
            LatexText.looksLikeMathInPlainText("""D:\sum\data"""),
        )
    }

    @Test
    fun `明文里的三角形写法要识别为数学`() {
        assertTrue(
            LatexText.looksLikeMathInPlainText("""Step 3. (\triangle ACH \sim \triangle ABC)"""),
        )
    }

    @Test
    fun `普通中文段落不会被误判为数学`() {
        assertFalse(LatexText.looksLikeMathInPlainText("这是一段普通的中文，没有任何公式。"))
        assertFalse(LatexText.looksLikeMathInPlainText("Use the sum function."))
    }

    // ================================================================
    // 明文清理
    // ================================================================

    @Test
    fun `明文里的命令被换成符号`() {
        val out = LatexText.cleanInlineCommands("""(\triangle ACH \sim \triangle ABC)""")
        assertFalse("不该残留反斜杠：$out", out.contains('\\'))
        assertTrue(out.contains('△'))
        assertTrue(out.contains('∼'))
    }

    @Test
    fun `sinh 不会被 sin 的错误前缀吃掉`() {
        val out = LatexText.cleanInlineCommands("""\sinh x""")
        assertTrue("必须是 sinh 而不是 sin h：$out", out.contains("sinh"))
    }
}
