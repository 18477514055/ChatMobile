package com.chatmobile.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器测试。
 *
 * ## ★ 一半以上的用例是**流式不完整输入**
 *
 * 因为那是这个解析器存在的核心理由：AI 边生成边显示，
 * 用户看到的每一帧都可能是半截 Markdown。
 * 纯文本编辑器不会遇到的问题，在这里是**常态**。
 *
 * 判据统一为：**不崩、不吞内容、样式不跳**（已闭合的照常渲染）。
 */
class MarkdownParserTest {

    private fun parse(s: String) = MarkdownParser.parse(s)

    /** 把行内元素拍平成纯文本，用来断言"内容没丢"。 */
    private fun flatten(spans: List<MdInline>): String = spans.joinToString("") { s ->
        when (s) {
            is MdInline.Text -> s.text
            is MdInline.Code -> s.text
            is MdInline.Bold -> flatten(s.children)
            is MdInline.Italic -> flatten(s.children)
            is MdInline.Strike -> flatten(s.children)
            is MdInline.Link -> flatten(s.children)
            // ★ 公式拍平成"转好的可读文本"，不是原始 LaTeX ——
            //   断言"内容没丢"时我们要的是用户实际看到的东西。
            is MdInline.Math -> s.rendered
        }
    }

    // ---------------------------------------------------------------
    // 围栏代码块
    // ---------------------------------------------------------------

    @Test
    fun `围栏代码块连语言标记一起识别`() {
        val blocks = parse(
            """
            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main()"))
        assertTrue(code.closed)
        // ★ 缩进必须原样保留 —— 它是代码语义的一部分
        assertTrue("缩进丢了：${code.code}", code.code.contains("    println"))
    }

    // ---------------------------------------------------------------
    // ★★★ 数学公式（用户报的渲染 bug）
    // ---------------------------------------------------------------

    @Test
    fun `单独一行的双美元公式识别成公式块`() {
        val blocks = parse("""$$\frac{a}{b} = c$$""")
        assertEquals(1, blocks.size)
        val math = blocks[0] as MdBlock.MathBlock
        assertTrue(math.closed)
        assertEquals("a/b = c", math.rendered)
    }

    @Test
    fun `跨行的双美元公式识别成一个块`() {
        val blocks = parse(
            """
            $$
            a^2 + b^2 = c^2
            $$
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val math = blocks[0] as MdBlock.MathBlock
        assertTrue(math.closed)
        assertTrue("上标必须转掉：${math.rendered}", math.rendered.contains('²'))
    }

    @Test
    fun `流式中未闭合的公式照常当公式渲染`() {
        // ★ 关键：不能因为没闭合就退回纯文本 —— 那会让已经写了几行的公式
        //   在收尾瞬间整体换样式，屏幕会跳。
        val blocks = parse("""$$\frac{a}{b}""")
        assertEquals(1, blocks.size)
        val math = blocks[0] as MdBlock.MathBlock
        assertFalse("流式中应该是未闭合", math.closed)
        assertFalse("不该残留反斜杠：${math.rendered}", math.rendered.contains('\\'))
    }

    @Test
    fun `公式块不会被当成列表或标题`() {
        // 公式里以减号开头的行不能被当列表项
        val blocks = parse(
            """
            $$
            -b \pm \sqrt{b^2-4ac}
            $$
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        assertTrue("应该是公式块而不是列表：${blocks[0]}", blocks[0] is MdBlock.MathBlock)
    }

    @Test
    fun `公式块前面有段落时正确切分`() {
        val blocks = parse(
            """
            Step 4. Both sides are equal:
            ${'$'}${'$'}c^2 + 2ab = a^2 + b^2 + 2ab${'$'}${'$'}
            Done.
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue("中间那段必须是公式：${blocks[1]}", blocks[1] is MdBlock.MathBlock)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `行内公式转成可读文本`() {
        val blocks = parse("""设 ${'$'}x^2 = 4${'$'}，求 x。""")
        val text = flatten((blocks[0] as MdBlock.Paragraph).spans)
        assertFalse("不该残留美元符：$text", text.contains('$'))
        assertFalse("不该残留 caret：$text", text.contains('^'))
        assertTrue("上标要转掉：$text", text.contains('²'))
        assertTrue("正文不能丢：$text", text.contains("求"))
    }

    // ---------------------------------------------------------------
    // ★★★ `\(...\)` 行内公式（真机上模型实际用的就是这种）
    // ---------------------------------------------------------------

    @Test
    fun `反斜杠括号行内公式必须被识别`() {
        // ★ 这条直接来自真机抓到的原始报文：
        //   规定 \(\Delta=b^2-4ac\)，注意 \(\sqrt{4a^2}=2|a|\)
        val text = flatten(
            (parse("""规定 \(\Delta=b^2-4ac\)，两边开方。""")[0] as MdBlock.Paragraph).spans,
        )
        assertFalse("不该残留反斜杠：$text", text.contains('\\'))
        assertFalse("不该残留 caret：$text", text.contains('^'))
        assertTrue("Δ 应转出来：$text", text.contains('Δ'))
        assertTrue("正文不能丢：$text", text.contains("两边开方"))
    }

    @Test
    fun `反斜杠括号里的分式要转`() {
        // 真机原句：一个重根 \(x=-\dfrac{b}{2a}\)
        val text = flatten(
            (parse("""一个重根 \(x=-\dfrac{b}{2a}\)，""")[0] as MdBlock.Paragraph).spans,
        )
        assertFalse("不该残留反斜杠：$text", text.contains('\\'))
        assertFalse("不该残留 dfrac：$text", text.contains("dfrac"))
        assertTrue("分式应变成 /：$text", text.contains('/'))
    }

    @Test
    fun `多个反斜杠括号公式互不干扰`() {
        val text = flatten(
            (parse("""\(\Delta>0\) 两个根，\(\Delta=0\) 一个重根""")[0] as MdBlock.Paragraph).spans,
        )
        assertFalse("不该残留反斜杠：$text", text.contains('\\'))
        assertEquals("两个 Δ 都要在：$text", 2, text.count { it == 'Δ' })
    }

    @Test
    fun `没闭合的反斜杠括号按字面量处理`() {
        // 流式中会出现只有开没有闭的情况，不能吞掉后面全部内容
        val text = flatten((parse("""规定 \(\Delta=b^2""")[0] as MdBlock.Paragraph).spans)
        assertTrue("内容不能丢：$text", text.contains("规定"))
        assertTrue("后面的文本必须保留：$text", text.contains("b"))
    }

    @Test
    fun `反斜杠方括号是块级公式`() {
        val blocks = parse(
            """
            \[
            x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}
            \]
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        assertTrue("应是公式块：${blocks[0]}", blocks[0] is MdBlock.MathBlock)
        val math = blocks[0] as MdBlock.MathBlock
        assertFalse("不该残留反斜杠：${math.rendered}", math.rendered.contains('\\'))
    }

    @Test
    fun `货币符号不会被当成公式`() {
        // `$5` 与 `$10` 之间不是公式，因为闭合 $ 后面紧跟数字
        val text = flatten((parse("""我有 $5 你有 $10""")[0] as MdBlock.Paragraph).spans)
        assertTrue("货币符号必须原样保留：$text", text.contains('$'))
        assertTrue(text.contains("5"))
        assertTrue(text.contains("10"))
    }

    @Test
    fun `代码块里的美元符不当公式`() {
        val blocks = parse(
            """
            ```bash
            echo ${'$'}HOME
            ```
            """.trimIndent(),
        )
        val code = blocks[0] as MdBlock.CodeBlock
        assertTrue("代码里的 \$ 必须原样：${code.code}", code.code.contains('$'))
    }

    @Test
    fun `段落里的明文 LaTeX 命令被清理`() {
        val text = flatten(
            (parse("""(\triangle ACH \sim \triangle ABC)""")[0] as MdBlock.Paragraph).spans,
        )
        assertFalse("不该残留反斜杠：$text", text.contains('\\'))
        assertTrue("应变成 △：$text", text.contains('△'))
    }

    @Test
    fun `段落里的 Windows 路径不会被改坏`() {
        val path = """D:\Users\example\Documents"""
        val text = flatten((parse(path)[0] as MdBlock.Paragraph).spans)
        assertTrue(
            "路径必须原样保留，不许被当数学清理掉：$text",
            text.contains("""D:\Users\example\Documents"""),
        )
    }

    @Test
    fun `围栏代码块里是公式也不当公式`() {
        // ``` 里的 $$ 是代码不是公式
        val blocks = parse(
            """
            ```latex
            ${'$'}${'$'}x^2${'$'}${'$'}
            ```
            """.trimIndent(),
        )
        assertTrue("应是代码块：${blocks[0]}", blocks[0] is MdBlock.CodeBlock)
    }

    @Test
    fun `流式中代码块没闭合也要当代码块渲染`() {
        // 用户正在看模型写代码的中间态
        val blocks = parse("```python\nprint(1)\nprint(2)")
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("python", code.language)
        assertFalse("没闭合必须标记为 closed=false", code.closed)
        // ★ 关键：内容一个字都不能丢
        assertTrue(code.code.contains("print(1)"))
        assertTrue(code.code.contains("print(2)"))
    }

    @Test
    fun `代码块里的井号和减号不被当成标题与列表`() {
        val blocks = parse("```\n# 这是注释\n- 这也是代码\n```")
        val code = blocks.single() as MdBlock.CodeBlock
        assertTrue(code.code.contains("# 这是注释"))
        assertTrue(code.code.contains("- 这也是代码"))
    }

    // ---------------------------------------------------------------
    // 基础块
    // ---------------------------------------------------------------

    @Test
    fun `各级标题`() {
        val blocks = parse("# 一\n## 二\n### 三")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
    }

    @Test
    fun `井号后没空格不是标题`() {
        // `#hashtag` 是话题标签，不是标题
        val blocks = parse("#hashtag 内容")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `分隔线`() {
        assertTrue(parse("---").single() is MdBlock.Divider)
        assertTrue(parse("***").single() is MdBlock.Divider)
        assertTrue(parse("___").single() is MdBlock.Divider)
    }

    @Test
    fun `无序列表与有序列表`() {
        val blocks = parse("- 甲\n- 乙")
        assertEquals(2, blocks.size)
        assertTrue((blocks[0] as MdBlock.ListItem).ordered.not())

        val ordered = parse("1. 第一\n2. 第二")
        val first = ordered[0] as MdBlock.ListItem
        assertEquals(1, first.number)
        assertEquals(2, (ordered[1] as MdBlock.ListItem).number)
    }

    @Test
    fun `列表项续行归到同一项`() {
        val blocks = parse("- 第一行\n  第二行")
        // ★ AI 经常把一项写成两三行，不能拆成两个 item
        assertEquals(1, blocks.size)
        val item = blocks[0] as MdBlock.ListItem
        assertTrue(flatten(item.spans).contains("第一行"))
        assertTrue(flatten(item.spans).contains("第二行"))
    }

    // ---------------------------------------------------------------
    // 表格
    // ---------------------------------------------------------------

    @Test
    fun `带分隔行的表格`() {
        val blocks = parse(
            """
            | 名称 | 数量 |
            | --- | ---: |
            | 苹果 | 3 |
            | 梨 | 5 |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(2, table.rows.size)
        assertEquals(MdAlign.Right, table.aligns[1])
    }

    @Test
    fun `只有表头没有分隔行时不是表格`() {
        // ★ 流式初期就是这样：表头到了、分隔行还没到。
        //   此时必须当普通段落，否则会渲染出一个残缺表格再跳变。
        val blocks = parse("| 名称 | 数量 |\n| 苹果 | 3 |")
        assertFalse(blocks.any { it is MdBlock.Table })
    }

    @Test
    fun `单元格里的竖线被代码包裹时不切分`() {
        val blocks = parse(
            """
            | 表达式 | 值 |
            | --- | --- |
            | `a|b` | 1 |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(1, table.rows.size)
        // 该行应该只有 2 个单元格（`a|b` 里的 | 不算分隔）
        assertEquals(2, table.rows[0].size)
    }

    // ---------------------------------------------------------------
    // 行内
    // ---------------------------------------------------------------

    @Test
    fun `粗体斜体删除线行内代码`() {
        val inlines = MarkdownParser.parseInline("**粗** *斜* ~~删~~ `码`")
        assertTrue(inlines.any { it is MdInline.Bold })
        assertTrue(inlines.any { it is MdInline.Italic })
        assertTrue(inlines.any { it is MdInline.Strike })
        assertTrue(inlines.any { it is MdInline.Code })
    }

    @Test
    fun `粗体里套斜体`() {
        val inlines = MarkdownParser.parseInline("**粗里有*斜*体**")
        val bold = inlines.filterIsInstance<MdInline.Bold>().single()
        assertTrue(bold.children.any { it is MdInline.Italic })
    }

    @Test
    fun `没闭合的粗体降级成字面量而不是吞掉后面`() {
        // ★ 这是流式最常见的形态
        val inlines = MarkdownParser.parseInline("**半截粗体")
        val text = flatten(inlines)
        assertTrue("内容丢了：$text", text.contains("半截粗体"))
        assertTrue("星号应保留：$text", text.contains("**"))
        assertFalse(inlines.any { it is MdInline.Bold })
    }

    @Test
    fun `下划线在单词内部不算斜体`() {
        // `snake_case_name` 不能变成 snake + 斜体 + name
        val inlines = MarkdownParser.parseInline("变量 snake_case_name 结束")
        assertFalse(inlines.any { it is MdInline.Italic })
        assertTrue(flatten(inlines).contains("snake_case_name"))
    }

    @Test
    fun `行内代码内部不再解析标记`() {
        val inlines = MarkdownParser.parseInline("`**不是粗体**`")
        val code = inlines.filterIsInstance<MdInline.Code>().single()
        assertEquals("**不是粗体**", code.text)
    }

    @Test
    fun `链接`() {
        val inlines = MarkdownParser.parseInline("见 [文档](https://example.com/x)")
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/x", link.url)
        assertTrue(flatten(link.children).contains("文档"))
    }

    @Test
    fun `没闭合的方括号当普通文本`() {
        val inlines = MarkdownParser.parseInline("见 [未闭合")
        assertFalse(inlines.any { it is MdInline.Link })
        assertTrue(flatten(inlines).contains("未闭合"))
    }

    // ---------------------------------------------------------------
    // 健壮性：这些是"不能崩"的底线
    // ---------------------------------------------------------------

    @Test
    fun `空输入返回空列表`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `纯空白行不会产出空段落`() {
        val blocks = parse("甲\n\n\n\n乙")
        assertEquals(2, blocks.size)
    }

    @Test
    fun `引用块内部的列表也被解析`() {
        val blocks = parse("> 引用里\n> - 有个列表")
        val quote = blocks.single() as MdBlock.Quote
        assertTrue(
            "引用内部应该解析出列表项，实际：${quote.blocks}",
            quote.blocks.any { it is MdBlock.ListItem },
        )
    }

    @Test
    fun `怪字符不会让解析器死循环或崩溃`() {
        // 这些输入没有任何合法 Markdown 结构，但绝不能让界面卡死
        val weird = listOf(
            "|", "|||", ">", "###", "```", "~~~", "- ", "1. ", "\\", "**", "`",
            "| a |\n| - |", "> > > 嵌套引用",
        )
        weird.forEach { input ->
            // 只要不抛异常、不死循环即可。返回的块本身都是合法对象
            //（Kotlin 的非空类型保证，无需断言 == null）。
            val blocks = parse(input)
            assertTrue("解析「$input」不该抛异常", true)
            assertEquals(
                "解析「$input」后块数不能为负",
                true,
                blocks.size >= 0,
            )
        }
    }

    @Test
    fun `代码块之后还能继续解析普通内容`() {
        val blocks = parse("```\ncode\n```\n\n之后的段落")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.CodeBlock)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }
}
