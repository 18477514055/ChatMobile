package com.chatmobile.markdown

/**
 * Markdown 解析器 —— **纯函数、零依赖**，可完整单测。
 *
 * ## 设计原则：永不失败
 *
 * AI 的输出是**流式**到达的，任何时候都可能是半截：
 * - ` ``` ` 只写了两个反引号
 * - `**粗体` 打开没闭合
 * - 表格只写了表头，分隔行还没到
 * - 列表项写到一半
 *
 * 大多数 Markdown 库遇到不完整输入会"整段降级成纯文本"或"吞掉后面的内容" ——
 * 在聊天里表现为**流式期间样式疯狂闪烁**。
 * 我们的做法：**已闭合的照常渲染，没闭合的降级成字面量**，屏幕上永远不会跳。
 *
 * ## 只支持 AI 真的会写的语法
 *
 * 见 README §支持的语法。刻意**不支持**：
 * - 引用式链接 `[x][1]` + 定义区（AI 从不用）
 * - 内嵌 HTML（安全风险，且我们不做 WebView）
 * - 脚注
 * - 语法高亮的复杂子语言（只做围栏代码的等宽渲染）
 */
object MarkdownParser {

    /**
     * 入口：把整段 Markdown 文本解析成块列表。
     *
     * ★ 输入为空白时返回空列表（不是"一个空段落"）—— 渲染层据此完全不占高度。
     */
    fun parse(text: String): List<MdBlock> {
        if (text.isBlank()) return emptyList()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return BlockParser(lines).parseBlocks()
    }

    /**
     * 把一行文本解析成行内元素。
     *
     * 单独暴露出来，是因为**表格单元格、列表项、标题**都要用同一套行内解析，
     * 而它们的文本不经过块解析器。
     *
     * ## ★ 末尾那道"明文 LaTeX 清理"是干什么的
     *
     * 模型经常**不用 `$` 定界符**，直接在普通句子里写命令。用户截图里就有一行：
     * ```
     * Step 3. (\triangle ACH \sim \triangle ABC), because they share the angle at (A)...
     * ```
     * 不处理的话这行还是满屏反斜杠。
     *
     * ★ 但这一步必须带**路径护栏**（见 [LatexText.looksLikeMathInPlainText]）：
     *   我们的 App 里会出现 `D:\Users\example\...`，无脑替换会**把用户的数据改坏**。
     *   宁可漏转一段数学，也不能改坏一个路径。
     */
    fun parseInline(text: String): List<MdInline> {
        val cleaned = if (LatexText.looksLikeMathInPlainText(text)) {
            LatexText.cleanInlineCommands(text)
        } else {
            text
        }
        return InlineParser(cleaned).parse()
    }

    // ==============================================================
    // 块级
    // ==============================================================

    private class BlockParser(private val lines: List<String>) {
        private var i = 0

        fun parseBlocks(): List<MdBlock> {
            val out = mutableListOf<MdBlock>()
            while (i < lines.size) {
                val line = lines[i]

                // ---- 空行：只是分隔，不产生块 ----
                if (line.isBlank()) {
                    i++
                    continue
                }

                // ---- 围栏代码块 ----
                // ★ 必须**先**判代码围栏：代码里可能有 `#`（会被当成标题）、
                //   `- `（会被当成列表）、`|`（会被当成表格）。
                //   判在前面，这些内容就原样留在代码块里，不会被误解析。
                if (isFence(line)) {
                    out += parseFencedCode()
                    continue
                }

                // ---- 独立数学公式块 ----
                // ★ 必须在**所有**块级语法之前判 —— 公式里会有 `#`（矩阵换行注释）、
                //   `-`（减号开头会被当列表）、`|`（行列式写成 `|A|` 会被当表格）。
                //   判在前面这些才不会把公式拆碎。
                //   同时必须在围栏代码之后：``` 里的 `$$` 是代码不是公式。
                if (isMathFence(line)) {
                    out += parseMathBlock()
                    continue
                }

                // ---- 分隔线 ----
                // ★ 也在列表之前判：`----` 是分隔线不是列表项。
                if (isDivider(line)) {
                    out += MdBlock.Divider
                    i++
                    continue
                }

                // ---- 标题 ----
                // ★ 这里必须用 if + continue，**不能**写 `heading(line)?.let { ... return@let }`：
                //   `return@let` 只退出那个 lambda，循环会继续往下跑，
                //   于是同一行既是标题又会被当成段落/列表再解析一次（内容重复）。
                val headingBlock = heading(line)
                if (headingBlock != null) {
                    out += headingBlock
                    i++
                    continue
                }

                // ---- 引用 ----
                if (line.trimStart().startsWith(">")) {
                    out += parseQuote()
                    continue
                }

                // ---- 表格 ----
                // 判据：本行含 `|` 且**下一行是分隔行**（`|---|---|`）。
                // ★ 只有表头没有分隔行 ⇒ 不是表格，按普通段落处理（流式初期就是这样）。
                if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                    out += parseTable()
                    continue
                }

                // ---- 列表 ----
                if (listMarker(line) != null) {
                    out += parseListItem()
                    continue
                }

                // ---- 普通段落 ----
                out += parseParagraph()
            }
            return out
        }

        // ----------------------------------------------------------
        // 独立数学公式块
        // ----------------------------------------------------------

        /**
         * 这一行是否是公式块的**开头**。
         *
         * 支持三种写法（模型都用）：
         * - `$$...$$`（可以整段在一行，也可以跨多行）
         * - `\[...\]`
         * - 单独一行的 `$$`
         */
        private fun isMathFence(line: String): Boolean {
            val t = line.trim()
            return t.startsWith("$$") || t.startsWith("\\[")
        }

        /**
         * 吃掉一个公式块。
         *
         * ★ **流式容错**：没见到闭合定界符时（`closed = false`）照常当公式渲染，
         *   取到最后一个非空行。绝不能退回纯文本 —— 那会让已经写了几行的公式
         *   在收尾瞬间整体换样式，屏幕会跳。
         */
        private fun parseMathBlock(): MdBlock.MathBlock {
            val first = lines[i].trim()

            // ---- 情形 1：整段在一行内闭合（最常见的单行公式） ----
            if (first.startsWith("$$") && first.length > 4 && first.endsWith("$$")) {
                val tex = first.removePrefix("$$").removeSuffix("$$").trim()
                i++
                return MdBlock.MathBlock(
                    tex = tex,
                    rendered = LatexText.render(tex),
                    closed = true,
                )
            }
            if (first.startsWith("\\[") && first.endsWith("\\]") && first.length > 4) {
                val tex = first.removePrefix("\\[").removeSuffix("\\]").trim()
                i++
                return MdBlock.MathBlock(
                    tex = tex,
                    rendered = LatexText.render(tex),
                    closed = true,
                )
            }

            // ---- 情形 2：跨行。开定界符可能单独一行，也可能带内容 ----
            val openMark = if (first.startsWith("\\[")) "\\[" else "$$"
            val closeMark = if (openMark == "\\[") "\\]" else "$$"

            val body = StringBuilder()
            // 开定界符那一行本身可能就带了一段内容
            val afterOpen = first.removePrefix(openMark)
            if (afterOpen.isNotBlank()) body.append(afterOpen.trim())
            i++

            var closed = false
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.trim().endsWith(closeMark)) {
                    // 闭合那一行前面可能还有内容
                    val head = cur.trim().removeSuffix(closeMark).trim()
                    if (head.isNotEmpty()) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(head)
                    }
                    closed = true
                    i++
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(cur)
                i++
            }

            val tex = body.toString().trim()
            // ★ 多行公式的 `\\` 换行转成真正的换行，让可读文本也分行
            return MdBlock.MathBlock(
                tex = tex,
                rendered = LatexText.render(tex),
                closed = closed,
            )
        }

        // ----------------------------------------------------------
        // 围栏代码块
        // ----------------------------------------------------------

        private fun isFence(line: String): Boolean {
            val t = line.trimStart()
            return t.startsWith("```") || t.startsWith("~~~")
        }

        private fun parseFencedCode(): MdBlock.CodeBlock {
            val openLine = lines[i].trimStart()
            val marker = if (openLine.startsWith("~~~")) "~~~" else "```"
            // 语言标记 = 围栏后的第一个词；` ```kotlin title="x" ` 只取 kotlin
            val lang = openLine.removePrefix(marker).trim()
                .substringBefore(' ')
                .substringBefore('\t')
            i++

            val body = StringBuilder()
            var closed = false
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.trimStart().startsWith(marker)) {
                    closed = true
                    i++
                    break
                }
                // ★ 代码内容原样保留，含前导空格（缩进是代码语义的一部分）
                body.append(cur).append('\n')
                i++
            }
            // 去掉尾部多出来的那个换行（它来自最后一行代码，不是代码本身）
            val code = body.toString().removeSuffix("\n")
            return MdBlock.CodeBlock(language = lang, code = code, closed = closed)
        }

        // ----------------------------------------------------------
        // 分隔线
        // ----------------------------------------------------------

        private fun isDivider(line: String): Boolean {
            val t = line.trim()
            if (t.length < 3) return false
            val c = t[0]
            if (c != '-' && c != '*' && c != '_') return false
            // 必须全由同一个字符组成（允许中间有空格）
            return t.all { it == c || it == ' ' }
        }

        // ----------------------------------------------------------
        // 标题
        // ----------------------------------------------------------

        private fun heading(line: String): MdBlock.Heading? {
            val t = line.trimStart()
            if (!t.startsWith("#")) return null
            var level = 0
            while (level < t.length && level < 6 && t[level] == '#') level++
            // ★ `#` 后面必须是空格或直接结束：`#hashtag` 不是标题
            if (level == 0) return null
            if (level < t.length && t[level] != ' ' && t[level] != '\t') return null
            val content = t.substring(level).trim()
            return MdBlock.Heading(level, parseInline(content))
        }

        // ----------------------------------------------------------
        // 引用
        // ----------------------------------------------------------

        private fun parseQuote(): MdBlock.Quote {
            val inner = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trimStart()
                if (!t.startsWith(">")) break
                // 去掉 `>` 和紧随的一个空格
                inner += t.removePrefix(">").removePrefix(" ")
                i++
            }
            // ★ 递归解析：引用里可以有列表、代码块。
            //   这里用一个新的 BlockParser 处理内部行，天然支持嵌套。
            return MdBlock.Quote(BlockParser(inner).parseBlocks())
        }

        // ----------------------------------------------------------
        // 表格
        // ----------------------------------------------------------

        private fun isTableSeparator(line: String): Boolean {
            val t = line.trim()
            if (!t.contains('-')) return false
            // 去掉首尾的 `|`，剩下每个单元格必须是 `---` / `:--` / `--:` / `:-:`
            val cells = splitRow(t)
            if (cells.isEmpty()) return false
            return cells.all { cell ->
                val c = cell.trim()
                c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.count { it == '-' } >= 1
            }
        }

        private fun parseTable(): MdBlock.Table {
            val header = splitRow(lines[i]).map { parseInline(it.trim()) }
            val aligns = splitRow(lines[i + 1]).map { raw ->
                val c = raw.trim()
                when {
                    c.startsWith(":") && c.endsWith(":") -> MdAlign.Center
                    c.endsWith(":") -> MdAlign.Right
                    c.startsWith(":") -> MdAlign.Left
                    else -> null
                }
            }
            i += 2

            val rows = mutableListOf<List<List<MdInline>>>()
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank() || !line.contains('|')) break
                // 表格结束于下一个非表格行
                rows += splitRow(line).map { parseInline(it.trim()) }
                i++
            }
            return MdBlock.Table(header = header, aligns = aligns, rows = rows)
        }

        /**
         * 按 `|` 切一行表格。
         *
         * ★ 必须**跳过**代码里的 `|` 与转义的 `\|`，否则 `` `a|b` `` 会被切成两格。
         *   这是表格解析最常见的错。
         */
        private fun splitRow(line: String): List<String> {
            var t = line.trim()
            if (t.startsWith("|")) t = t.removePrefix("|")
            if (t.endsWith("|") && !t.endsWith("\\|")) t = t.removeSuffix("|")

            val cells = mutableListOf<String>()
            val cur = StringBuilder()
            var inCode = false
            var k = 0
            while (k < t.length) {
                val c = t[k]
                when {
                    c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> {
                        cur.append('|'); k++
                    }
                    c == '`' -> { inCode = !inCode; cur.append(c) }
                    c == '|' && !inCode -> { cells += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                k++
            }
            cells += cur.toString()
            return cells
        }

        // ----------------------------------------------------------
        // 列表
        // ----------------------------------------------------------

        /**
         * 识别列表标记。
         *
         * @return `(level, ordered, number)`；不是列表项返回 null
         */
        private fun listMarker(line: String): Triple<Int, Boolean, Int>? {
            val t = line.trimStart()
            if (t.isEmpty()) return null
            // 层级：每 2 个空格算一级（tab 算 2）
            val indent = line.takeWhile { it == ' ' || it == '\t' }
                .sumOf { if (it == '\t') 2 else 1 }
            val level = indent / 2

            // 无序：`- ` `* ` `+ `
            if (t.length >= 2 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && t[1] == ' ') {
                // ★ 排除分隔线（`- - -` 之类），那种已在 isDivider 处理
                return Triple(level, false, 0)
            }

            // 有序：`1. ` 或 `1) `
            var k = 0
            while (k < t.length && k < 4 && t[k].isDigit()) k++
            if (k in 1..3 && k + 1 < t.length) {
                val sep = t[k]
                if ((sep == '.' || sep == ')') && t[k + 1] == ' ') {
                    return Triple(level, true, t.substring(0, k).toIntOrNull() ?: 1)
                }
            }
            return null
        }

        private fun parseListItem(): MdBlock.ListItem {
            val line = lines[i]
            val (level, ordered, number) = listMarker(line)!!
            val t = line.trimStart()
            // 去掉标记本身
            val content = if (ordered) {
                t.substringAfter(" ").trim()
            } else {
                t.substring(1).trim()
            }
            i++

            // ★ 续行：紧跟着的非空、非新列表项的行，算这一项的内容。
            //   AI 经常把一个列表项写成两三行。
            val sb = StringBuilder(content)
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                if (listMarker(next) != null) break
                if (isFence(next) || isDivider(next) || heading(next) != null) break
                if (isMathFence(next)) break
                sb.append('\n').append(next.trim())
                i++
            }

            return MdBlock.ListItem(
                level = level,
                number = number,
                ordered = ordered,
                spans = parseInline(sb.toString()),
            )
        }

        // ----------------------------------------------------------
        // 段落
        // ----------------------------------------------------------

        private fun parseParagraph(): MdBlock.Paragraph {
            val sb = StringBuilder()
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) break
                // 遇到任何"块级语法"就结束本段
                if (isFence(line) || isDivider(line)) break
                // ★ 公式块也必须是段落边界，否则 `$$` 会被并进上一段的正文
                if (isMathFence(line)) break
                if (heading(line) != null) break
                if (line.trimStart().startsWith(">")) break
                if (listMarker(line) != null) break
                if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) break

                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line.trim())
                i++
            }
            // ★ 防御：如果一条都没吃到（理论上不该），也必须前进一格，
            //   否则外层 while 会死循环 —— 聊天界面卡死比渲染错更难解释。
            if (sb.isEmpty()) {
                val fallback = lines.getOrElse(i) { "" }
                i++
                return MdBlock.Paragraph(listOf(MdInline.Text(fallback)))
            }
            return MdBlock.Paragraph(parseInline(sb.toString()))
        }
    }

    // ==============================================================
    // 行内
    // ==============================================================

    /**
     * 行内解析器。
     *
     * ★★ 核心难点是**"没闭合怎么办"**。策略：
     *   找到一个开标记后，去**后面**找配对的闭标记；
     *   - 找到了 ⇒ 正常生成元素，继续往后解析
     *   - 没找到 ⇒ **把开标记当普通文本**，继续往后解析（而不是吞掉后面全部内容）
     *
     * 这样流式时 `**粗` 会显示成字面 `**粗`，等闭合符到了再变成粗体 ——
     * 屏幕上只是"多了两个星号然后消失"，不会整段变色。
     */
    private class InlineParser(private val src: String) {
        private var i = 0

        fun parse(): List<MdInline> {
            val out = mutableListOf<MdInline>()
            val buf = StringBuilder()

            fun flush() {
                if (buf.isNotEmpty()) {
                    out += MdInline.Text(buf.toString())
                    buf.clear()
                }
            }

            while (i < src.length) {
                val c = src[i]

                // ---- 行内代码：优先级最高，内部不解析任何标记 ----
                if (c == '`') {
                    val ticks = countRun(src, i, '`')
                    val fence = "`".repeat(ticks)
                    val close = src.indexOf(fence, i + ticks)
                    if (close >= 0) {
                        flush()
                        out += MdInline.Code(src.substring(i + ticks, close))
                        i = close + ticks
                        continue
                    }
                    // 没闭合 ⇒ 当字面量
                    buf.append(fence)
                    i += ticks
                    continue
                }

                // ---- 行内数学 `$...$` / `\(...\)` ----
                // ★ 必须在行内代码**之后**、其它标记**之前**判：
                //   - 代码之后：`` `$x$` `` 里的 `$` 是字面量，不该当公式
                //   - 其它标记之前：公式体里的 `_` `*` 是下标/乘号，
                //     先让公式吃掉整段，就不会被当成 Markdown 标记
                if (c == '$') {
                    val math = tryParseInlineMath(i)
                    if (math != null) {
                        flush()
                        out += math.first
                        i = math.second
                        continue
                    }
                    // 没闭合 ⇒ 当字面量（流式期间很常见）
                    buf.append(c)
                    i++
                    continue
                }

                // ★★★ `\(...\)` —— 与 `$...$` **同等重要**。
                //
                //   真机抓到的原始报文里，模型用的是 `\(...\)` 而不是 `$...$`：
                //   ```
                //   规定 \(\Delta=b^2-4ac\)，注意 \(\sqrt{4a^2}=2|a|\)
                //   一个重根 \(x=-\dfrac{b}{2a}\)，\(\Delta<0\) 无实根
                //   ```
                //   之前只认 `$`，于是这些全部漏掉、原样显示成反斜杠 ——
                //   这正是用户截图里 `(x=-\dfrac{b}{2a})` 仍然裸露的原因。
                //
                //   ★ 为什么 `\(` 要**单独**在这里判而不是交给转义逻辑：
                //     下面的转义分支会把 `\(` 变成字面 `(`（`(` 在 ESCAPABLE 里），
                //     那样开定界符就没了，闭合的 `\)` 也变成 `)`，
                //     结果内容虽然留下但永远不会被识别成公式。
                if (c == '\\' && i + 1 < src.length && src[i + 1] == '(') {
                    val math = tryParseParenMath(i)
                    if (math != null) {
                        flush()
                        out += math.first
                        i = math.second
                        continue
                    }
                }

                // ---- 转义 ----
                if (c == '\\' && i + 1 < src.length) {
                    val n = src[i + 1]
                    // 只对"有语法意义"的字符做转义，其它保留反斜杠（路径里全是反斜杠）
                    if (n in ESCAPABLE) {
                        buf.append(n)
                        i += 2
                        continue
                    }
                }

                // ---- 删除线 ----
                if (c == '~' && i + 1 < src.length && src[i + 1] == '~') {
                    val close = src.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        flush()
                        out += MdInline.Strike(InlineParser(src.substring(i + 2, close)).parse())
                        i = close + 2
                        continue
                    }
                }

                // ---- 粗体 ----
                if (c == '*' && i + 1 < src.length && src[i + 1] == '*') {
                    val close = src.indexOf("**", i + 2)
                    if (close > i + 2) {
                        flush()
                        out += MdInline.Bold(InlineParser(src.substring(i + 2, close)).parse())
                        i = close + 2
                        continue
                    }
                }

                // ---- 斜体（单星 / 单下划线）----
                if (c == '*' || c == '_') {
                    // `_` 只有在词边界时才是斜体，否则 `snake_case_name` 会被误解析
                    val boundaryOk = c == '*' || !isWordChar(prevChar()) 
                    if (boundaryOk) {
                        val close = findSingleClose(src, i + 1, c)
                        if (close > i + 1) {
                            flush()
                            out += MdInline.Italic(InlineParser(src.substring(i + 1, close)).parse())
                            i = close + 1
                            continue
                        }
                    }
                }

                // ---- 链接 ----
                if (c == '[') {
                    val link = tryParseLink(i)
                    if (link != null) {
                        flush()
                        out += link.first
                        i = link.second
                        continue
                    }
                }

                buf.append(c)
                i++
            }
            flush()
            return out
        }

        private fun prevChar(): Char? = if (i > 0) src[i - 1] else null

        /**
         * 找单个字符的闭合位置。
         *
         * ★ 要跳过转义的 `\*`，也要保证闭合符**后面不是同一个字符**
         *   （否则 `**bold**` 里的单个 `*` 会被误当斜体的开或闭）。
         */
        private fun findSingleClose(s: String, from: Int, mark: Char): Int {
            var k = from
            while (k < s.length) {
                if (s[k] == '\\') { k += 2; continue }
                if (s[k] == mark) {
                    // 后面还是同一个字符 ⇒ 那是 `**`，不是单星
                    if (k + 1 < s.length && s[k + 1] == mark) { k += 2; continue }
                    // 前面是同一个字符 ⇒ 那是 `**` 的第二个字符
                    if (k > 0 && s[k - 1] == mark) { k++; continue }
                    // 闭合符必须在非空格之后
                    if (k > from && !s[k - 1].isWhitespace()) return k
                }
                k++
            }
            return -1
        }

        /**
         * 解析行内数学 `\(...\)` —— **真机上模型最常用的行内公式写法**。
         *
         * 与 `$...$` 的判据差别：
         * - `\(` 是**明确**的数学定界符（LaTeX 原生写法），不像 `$` 会和货币冲突，
         *   所以不需要"闭合符后面不能跟数字"那道护栏
         * - 但仍然**不能跨空行** —— 跨空行说明这不是一个行内公式
         *
         * @return `(元素, 下一个位置)`；没找到闭合 `\)` 返回 null（调用方按字面量处理）
         */
        private fun tryParseParenMath(start: Int): Pair<MdInline.Math, Int>? {
            var k = start + 2
            while (k < src.length - 1) {
                // 闭合定界符 `\)`
                if (src[k] == '\\' && src[k + 1] == ')') {
                    val tex = src.substring(start + 2, k)
                    if (tex.isBlank()) return null
                    return MdInline.Math(tex = tex, rendered = LatexText.render(tex)) to k + 2
                }
                // 跨空行 ⇒ 不是行内公式
                if (src[k] == '\n' && k + 1 < src.length && src[k + 1] == '\n') return null
                k++
            }
            return null
        }

        /**
         * 解析行内数学 `$...$` 或 `\(...\)`。
         *
         * ## ★ 三条必须守住的边界
         *
         * 1. **`$$` 不是行内公式** —— 那是块级，若在行内被吃掉，
         *    一个多行公式会被压成一行。
         * 2. **闭合符后面不能紧跟数字** —— `$5 和 $10` 是货币不是公式。
         *    判据：闭合 `$` 后面若是数字或字母，就当字面量。
         * 3. **公式体不能跨空行** —— 跨空行说明这不是一个行内公式，
         *    而是两段话里的两个 `$`。否则一整段对话会被一个 `$` 吞掉。
         *
         * @return `(元素, 下一个位置)`；不是公式返回 null（调用方按字面量处理）
         */
        private fun tryParseInlineMath(start: Int): Pair<MdInline.Math, Int>? {
            // `$$` 交给块级解析，这里不碰
            if (start + 1 < src.length && src[start + 1] == '$') return null

            var k = start + 1
            while (k < src.length) {
                val ch = src[k]
                // 转义的 `\$` 不算闭合
                if (ch == '\\') { k += 2; continue }
                if (ch == '\n' && k + 1 < src.length && src[k + 1] == '\n') return null
                if (ch == '$') {
                    // 闭合符后面紧跟数字/字母 ⇒ 这是货币，不是公式
                    if (k + 1 < src.length) {
                        val n = src[k + 1]
                        if (n.isDigit() || n.isLetter()) return null
                    }
                    val tex = src.substring(start + 1, k)
                    // 空公式 `$$` / `$ $` 无意义，当字面量
                    if (tex.isBlank()) return null
                    return MdInline.Math(tex = tex, rendered = LatexText.render(tex)) to k + 1
                }
                k++
            }
            return null
        }

        /**
         * 解析 `[text](url)`。
         *
         * @return `(元素, 下一个位置)`；不是链接返回 null
         */
        private fun tryParseLink(start: Int): Pair<MdInline.Link, Int>? {
            val closeBracket = src.indexOf(']', start + 1)
            if (closeBracket < 0) return null
            // `]( ` 必须紧邻
            if (closeBracket + 1 >= src.length || src[closeBracket + 1] != '(') return null
            val closeParen = src.indexOf(')', closeBracket + 2)
            if (closeParen < 0) return null

            val label = src.substring(start + 1, closeBracket)
            val url = src.substring(closeBracket + 2, closeParen).trim()
            if (label.isEmpty() || url.isEmpty()) return null

            return MdInline.Link(
                children = InlineParser(label).parse(),
                url = url,
            ) to closeParen + 1
        }
    }

    private fun countRun(s: String, from: Int, ch: Char): Int {
        var n = 0
        while (from + n < s.length && s[from + n] == ch) n++
        return n
    }

    private fun isWordChar(c: Char?): Boolean =
        c != null && (c.isLetterOrDigit())

    /** 可以反斜杠转义的字符。刻意不含 `\` 本身与普通字母（路径里全是反斜杠）。 */
    private const val ESCAPABLE = "*_~`[]()#+-.!|>"
}
