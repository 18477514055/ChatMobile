package com.chatmobile.markdown

/**
 * **LaTeX → 可读文本**的转换器（零依赖）。
 *
 * ## ★★★ 这个文件为什么存在
 *
 * 用户发来截图：模型输出的数学公式**原样显示**成
 *
 * ```
 * $$c^2 + 4\cdot\tfrac12 ab = (a+b)^2$$
 * $$\frac{AC}{AB} = \frac{AH}{AC} \quad\Longrightarrow\quad \frac{b}{c} = \frac{p}{b}$$
 * Step 3. (\triangle ACH \sim \triangle ABC)
 * ```
 *
 * 反斜杠、花括号、`$$` 全部裸露。这不是"排版不够漂亮"，是**内容读不了** ——
 * 一屏数学推导变成一屏反斜杠。
 *
 * ## ★ 为什么不做真正的数学排版
 *
 * 真正的排版（KaTeX/MathJax）需要 WebView 或一个几十 MB 的排版引擎，代价：
 * - WebView 里渲染 → 每条消息一个 WebView，滚动卡死、深色模式要单独注入、无法复制
 * - 引一个 Kotlin 数学库 → 项目至今零排版依赖，为聊天场景引一个不值得
 *
 * 而聊天里的数学**绝大多数是高中生能读懂级别**的推导：分式、上下标、
 * 希腊字母、几个运算符。把这些映射成 Unicode **已经能读**，
 * 而且顺带保住了三件真正重要的事：可复制、可搜索、跟随主题字号。
 *
 * ## ★ 设计取舍：宁可"简化"也不要"裸露反斜杠"
 *
 * 未知命令的处理是**丢掉命令名、保留花括号里的内容**（`\unknown{x}` → `x`）。
 * 另一种选择是原样保留（`\unknown{x}` 还是 `\unknown{x}`）—— 那样一旦遇到
 * 我们不认识但模型爱用的命令，用户又会看到一屏反斜杠。
 *
 * 判据：**数学的语义主要在结构（分式、上下标）和符号，不在控制序列的字面。**
 * 丢掉命令名最坏只是少了一个修饰，保留反斜杠则整段不可读。
 */
object LatexText {

    /**
     * 把一段 LaTeX 源码转成可读文本。
     *
     * @param source 不含定界符（`$` / `$$` / `\(` / `\[`）的公式体
     */
    fun render(source: String): String {
        val out = convert(source)
        // 收尾清理：多余空白（\quad 之类被换成空格的残留）、首尾空白
        return out
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    // ==============================================================
    // 主转换
    // ==============================================================

    private fun convert(src: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]

            when {
                // ---- 控制序列 ----
                c == '\\' -> {
                    i = readCommand(src, i, sb)
                }

                // ---- 上标 ----
                c == '^' -> {
                    val (group, next) = readGroup(src, i + 1)
                    sb.append(toSuperscript(group))
                    i = next
                }

                // ---- 下标 ----
                c == '_' -> {
                    val (group, next) = readGroup(src, i + 1)
                    sb.append(toSubscript(group))
                    i = next
                }

                // ---- 花括号：只留内容（结构已由 char/text 处理） ----
                c == '{' || c == '}' -> i++

                // ---- 其余原样 ----
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 读一个控制序列，把结果写进 [sb]，返回新的扫描位置。
     *
     * 覆盖三类：
     * 1. **带花括号参数的**（`\frac{a}{b}`、`\sqrt[3]{x}`、`\text{...}`）
     * 2. **无参数、纯替换的**（`\alpha`、`\cdot`、`\triangle`）
     * 3. **纯排版控制、结果为空或空格**（`\left`、`\right`、`\quad`、`\,`）
     */
    private fun readCommand(src: String, start: Int, sb: StringBuilder): Int {
        // `\\` 是换行符（矩阵里常见）
        if (start + 1 >= src.length) {
            sb.append('\\')
            return start + 1
        }

        val next = src[start + 1]

        // ---- 反斜杠 + 非字母：符号类命令 ----
        if (!next.isLetter()) {
            val replacement = when (next) {
                '\\' -> "\n"          // 矩阵换行
                ',', ';', ':', '!' -> " "  // 各种间距 → 一个空格
                '(', ')' -> ""         // `\(` `\)` 定界符残留
                '[', ']' -> ""
                else -> next.toString()
            }
            sb.append(replacement)
            return start + 2
        }

        // ---- 反斜杠 + 字母：读出命令名 ----
        var j = start + 1
        while (j < src.length && src[j].isLetter()) j++
        val name = src.substring(start + 1, j)

        // 1) 带参数的
        when (name) {
            "frac", "tfrac", "dfrac", "cfrac" -> {
                val (num, afterNum) = readGroup(src, j)
                val (den, afterDen) = readGroup(src, afterNum)
                sb.append(wrapIfCompound(num)).append('/').append(wrapIfCompound(den))
                return afterDen
            }

            "sqrt" -> {
                // 可选的开方次数：\sqrt[3]{x}
                var k = j
                var index: String? = null
                if (k < src.length && src[k] == '[') {
                    val close = src.indexOf(']', k)
                    if (close > k) {
                        index = src.substring(k + 1, close)
                        k = close + 1
                    }
                }
                val (body, after) = readGroup(src, k)
                val sign = when (index?.trim()) {
                    "3" -> "∛"
                    "4" -> "∜"
                    null, "" -> "√"
                    // 非 2/3/4 次：写成 `√[n]`，比丢掉信息好
                    else -> "√[$index]"
                }
                sb.append(sign).append(wrapIfCompound(body))
                return after
            }

            "text", "mathrm", "operatorname", "textnormal" -> {
                // 这些的参数要**原样**保留（里面可能是中文或 `sin` 这种词）
                val (body, after) = readGroup(src, j)
                sb.append(body)
                return after
            }

            "mathbf", "textbf", "boldsymbol", "mathit", "mathsf", "mathtt" -> {
                // 粗体/斜体在纯文本里表达不出来，但内容必须保留
                val (body, after) = readGroup(src, j)
                sb.append(body)
                return after
            }

            "boxed", "overline", "underline", "hat", "bar", "vec", "tilde", "widehat" -> {
                val (body, after) = readGroup(src, j)
                sb.append(body)
                return after
            }
        }

        // 2) 纯替换
        SYMBOLS[name]?.let {
            sb.append(it)
            return j
        }

        // 3) 纯排版控制 → 空
        if (name in LAYOUT_ONLY) return j

        // 4) 未知命令：丢掉命令名。若紧跟 `{...}`，内容会被下一轮保留下来。
        return j
    }

    /**
     * 读一个"参数组"。
     *
     * ★ 三种形态都要支持，缺一种就会解析错位：
     * - `{...}` —— 正常形式，**必须做花括号配对**（`\frac{a^{2}}{b}` 里嵌套）
     * - 单个字符 —— `\tfrac12` 是合法的 LaTeX，两个参数各是一个字符
     * - 空 —— 结尾或直接跟着别的结构
     */
    private fun readGroup(src: String, from: Int): Pair<String, Int> {
        if (from >= src.length) return "" to from

        // 跳过前导空格（LaTeX 忽略命令与参数之间的空格）
        var k = from
        while (k < src.length && (src[k] == ' ' || src[k] == '\t')) k++
        if (k >= src.length) return "" to k

        if (src[k] == '{') {
            var depth = 0
            var m = k
            while (m < src.length) {
                when (src[m]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            // ★ 递归转换：参数里可能还有 `\frac` / 上下标
                            return convert(src.substring(k + 1, m)) to m + 1
                        }
                    }
                    // `\{` `\}` 不算结构括号
                    '\\' -> m++
                }
                m++
            }
            // 没闭合（流式中常见）⇒ 取到结尾
            return convert(src.substring(k + 1)) to src.length
        }

        // 无花括号：吃掉一个 token（`\tfrac12` 或 `\sqrt x`）
        if (src[k] == '\\') {
            var m = k + 1
            if (m < src.length && src[m].isLetter()) {
                while (m < src.length && src[m].isLetter()) m++
            } else {
                m = minOf(m + 1, src.length)
            }
            return convert(src.substring(k, m)) to m
        }
        return convert(src.substring(k, k + 1)) to k + 1
    }

    /**
     * 分式的分子/分母若是"复合表达式"就加括号。
     *
     * ★ 为什么需要：`\frac{a+b}{2}` 渲染成 `a+b/2` 会被读成 `a + (b/2)` ——
     *   **意思变了**，而且用户在数学里对优先级极度敏感。
     *   单字符或单个标识符不必加括号（`p/b` 比 `(p)/(b)` 好读得多）。
     */
    private fun wrapIfCompound(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        // 只有字母数字、且不是一眼看出含运算符的，就不加括号
        if (t.length <= 1) return t
        if (t.all { it.isLetterOrDigit() }) return t
        return "($t)"
    }

    // ==============================================================
    // 上下标
    // ==============================================================

    /**
     * 转成 Unicode 上标；**只要有一个字符没有上标形式就整体退化成 `^(...)`**。
     *
     * ★ 为什么必须"全有或全无"：`x^ab` 里的 `a` 有上标、`b` 没有，
     *   混排出来 `xᵃb` 会被误读成 `xᵃ · b`。宁可整体退化，也不要半对。
     */
    private fun toSuperscript(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        // ★ 先全部映射，任何一个缺映射就整体退化成 `^(...)`。
        val mapped = StringBuilder()
        for (ch in t) {
            val m = SUPERSCRIPT[ch] ?: return "^($t)"
            mapped.append(m)
        }
        return mapped.toString()
    }

    private fun toSubscript(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        val mapped = StringBuilder()
        for (ch in t) {
            val m = SUBSCRIPT[ch] ?: return "_($t)"
            mapped.append(m)
        }
        return mapped.toString()
    }

    private val SUPERSCRIPT: Map<Char, String> = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'n' to "ⁿ", 'i' to "ⁱ", 'x' to "ˣ",
    )

    private val SUBSCRIPT: Map<Char, String> = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ",
        'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ",
        'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ",
        'x' to "ₓ",
    )

    // ==============================================================
    // 符号表
    // ==============================================================

    /** 纯排版控制命令，转成空串（它们不影响内容，只影响间距）。 */
    private val LAYOUT_ONLY: Set<String> = setOf(
        "left", "right", "big", "Big", "bigg", "Bigg",
        "bigl", "bigr", "Bigl", "Bigr",
        "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
        "quad", "qquad", "hspace", "vspace", "phantom", "hphantom",
        "mathchoice", "ensuremath", "nonumber", "notag",
        "begin", "end", "hline", "cline", "color", "mathbbm",
    )

    /**
     * 命令名 → Unicode 符号。
     *
     * ★ 这张表只放**能用单个 Unicode 字符准确表达**的。放不下的（如
     *   矩阵、多行对齐）宁可走"丢命令名"的兜底，也不硬凑 —— 凑错了
     *   用户看到的是**错误的数学**，比看不到更糟。
     */
    private val SYMBOLS: Map<String, String> = buildMap {
        // ---- 希腊字母（小写） ----
        put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
        put("epsilon", "ε"); put("varepsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
        put("theta", "θ"); put("vartheta", "ϑ"); put("iota", "ι"); put("kappa", "κ")
        put("lambda", "λ"); put("mu", "μ"); put("nu", "ν"); put("xi", "ξ")
        put("omicron", "ο"); put("pi", "π"); put("varpi", "ϖ"); put("rho", "ρ")
        put("varrho", "ϱ"); put("sigma", "σ"); put("varsigma", "ς"); put("tau", "τ")
        put("upsilon", "υ"); put("phi", "φ"); put("varphi", "φ"); put("chi", "χ")
        put("psi", "ψ"); put("omega", "ω")

        // ---- 希腊字母（大写） ----
        put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
        put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Upsilon", "Υ")
        put("Phi", "Φ"); put("Psi", "Ψ"); put("Omega", "Ω")

        // ---- 运算符 ----
        put("cdot", "·"); put("times", "×"); put("div", "÷"); put("pm", "±")
        put("mp", "∓"); put("ast", "∗"); put("star", "⋆"); put("circ", "∘")
        put("bullet", "•"); put("oplus", "⊕"); put("ominus", "⊖"); put("otimes", "⊗")

        // ---- 关系 ----
        put("le", "≤"); put("leq", "≤"); put("ge", "≥"); put("geq", "≥")
        put("ne", "≠"); put("neq", "≠"); put("approx", "≈"); put("equiv", "≡")
        put("sim", "∼"); put("simeq", "≃"); put("cong", "≅"); put("propto", "∝")
        put("ll", "≪"); put("gg", "≫"); put("prec", "≺"); put("succ", "≻")
        put("in", "∈"); put("notin", "∉"); put("ni", "∋")
        put("subset", "⊂"); put("supset", "⊃"); put("subseteq", "⊆"); put("supseteq", "⊇")
        put("cup", "∪"); put("cap", "∩"); put("setminus", "∖")
        put("emptyset", "∅"); put("varnothing", "∅")

        // ---- 箭头 ----
        put("to", "→"); put("rightarrow", "→"); put("leftarrow", "←")
        put("gets", "←"); put("leftrightarrow", "↔"); put("Rightarrow", "⇒")
        put("Leftarrow", "⇐"); put("Leftrightarrow", "⇔")
        put("longrightarrow", "⟶"); put("Longrightarrow", "⟹")
        put("longleftarrow", "⟵"); put("Longleftarrow", "⟸")
        put("uparrow", "↑"); put("downarrow", "↓"); put("mapsto", "↦")
        put("implies", "⟹"); put("iff", "⟺")

        // ---- 大运算符 ----
        put("sum", "∑"); put("prod", "∏"); put("coprod", "∐")
        put("int", "∫"); put("iint", "∬"); put("iiint", "∭"); put("oint", "∮")
        put("bigcup", "⋃"); put("bigcap", "⋂")
        put("bigoplus", "⨁"); put("bigotimes", "⨂")

        // ---- 几何 / 杂项 ----
        put("triangle", "△"); put("square", "□"); put("Box", "□")
        put("angle", "∠"); put("perp", "⊥"); put("parallel", "∥")
        put("infty", "∞"); put("partial", "∂"); put("nabla", "∇")
        put("therefore", "∴"); put("because", "∵")
        put("ldots", "…"); put("cdots", "⋯"); put("dots", "…"); put("vdots", "⋮")
        put("prime", "′"); put("degree", "°")
        put("forall", "∀"); put("exists", "∃"); put("neg", "¬")
        put("land", "∧"); put("lor", "∨"); put("wedge", "∧"); put("vee", "∨")

        // ---- 函数名（不带反斜杠原样显示，且后面不带空格） ----
        put("sin", "sin"); put("cos", "cos"); put("tan", "tan")
        put("cot", "cot"); put("sec", "sec"); put("csc", "csc")
        put("arcsin", "arcsin"); put("arccos", "arccos"); put("arctan", "arctan")
        put("sinh", "sinh"); put("cosh", "cosh"); put("tanh", "tanh")
        put("log", "log"); put("ln", "ln"); put("lg", "lg")
        put("exp", "exp"); put("lim", "lim"); put("max", "max"); put("min", "min")
        put("sup", "sup"); put("inf", "inf"); put("det", "det"); put("dim", "dim")
        put("gcd", "gcd"); put("lcm", "lcm"); put("mod", "mod")
        put("abs", "abs"); put("Pr", "Pr")
    }

    // ==============================================================
    // 明文里出现的 LaTeX 命令（没有定界符的情况）
    // ==============================================================

    /**
     * 判断一段普通文本里是否**明显包含数学命令**（用于没有 `$` 定界符的场合）。
     *
     * ## ★ 为什么需要这个
     *
     * 用户截图里有一行：
     * ```
     * Step 3. (\triangle ACH \sim \triangle ABC), because they share the angle at (A)...
     * ```
     * 模型在**普通段落**里直接写了 LaTeX 命令，没有 `$`。不处理的话这行还是反斜杠。
     *
     * ## ★★★ 为什么必须有护栏（这条是本文件最容易出错的地方）
     *
     * 我们的 App 里会出现**Windows 路径**：`D:\Users\example\Documents`。
     * 无脑把 `\command` 换成符号会把它变成 `D:UsersexampleDocuments` ——
     * **把用户的数据改坏**，比显示反斜杠严重得多。
     *
     * 所以判据不是"有没有反斜杠"，而是：
     * 1. 文本里出现了**结构性数学命令**（`\frac` `\sqrt` `\triangle` `\cdot`
     *    `\sum` `\int` `\le` `\sim` `\times` …）—— 这些几乎不可能出现在路径里
     * 2. **且**文本里没有路径特征（`X:\` 形式的盘符，或两个连续反斜杠）
     *
     * 两条都满足才做替换。宁可漏转一段数学，也不能改坏一个路径。
     */
    fun looksLikeMathInPlainText(text: String): Boolean {
        // 护栏一：看起来像路径就不碰
        if (Regex("""[A-Za-z]:\\""").containsMatchIn(text)) return false
        if (text.contains("\\\\")) return false
        if (Regex("""\\[A-Za-z0-9_\-]+\\[A-Za-z0-9_\-]+""").containsMatchIn(text)) {
            // `\Users\example` 这种"命令后紧跟另一个命令"的形态更像路径
            // 但 `\frac{a}{b}` 里不会有"命令紧跟命令"，所以还要看是不是真数学命令
        }

        // 护栏二：必须命中结构性数学命令
        return STRUCTURAL_COMMANDS.any { text.contains("\\$it") }
    }

    /**
     * 明文里做**保守替换**：只换结构性命令与希腊字母，不做上下标与分式重建。
     *
     * ★ 为什么明文这里不重建分式：普通段落里 `_` 可能是下划线命名，
     *   `^` 可能是正则或 caret，重建会改乱正文。这里只做"去反斜杠"这一件事。
     */
    fun cleanInlineCommands(text: String): String {
        var out = text
        for ((name, sym) in SYMBOLS) {
            if (out.contains("\\$name")) {
                // ★ 命令名后面必须是非字母（否则 `\sin` 会误吃 `\sinh` 的前缀）
                out = out.replace(Regex("""\\$name(?![A-Za-z])"""), sym)
            }
        }
        for (name in LAYOUT_ONLY) {
            out = out.replace(Regex("""\\$name(?![A-Za-z])\s*"""), "")
        }
        return out
    }

    /** 结构性数学命令 —— 出现在路径里的概率极低，可安全作为"这是数学"的判据。 */
    private val STRUCTURAL_COMMANDS: List<String> = listOf(
        "frac", "tfrac", "dfrac", "sqrt", "sum", "prod", "int", "oint",
        "triangle", "cdot", "times", "div", "pm", "mp",
        "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv",
        "sim", "simeq", "cong", "propto", "infty", "partial", "nabla",
        "alpha", "beta", "gamma", "delta", "theta", "lambda", "mu", "pi",
        "sigma", "phi", "omega", "Sigma", "Omega", "Delta", "Theta", "Lambda", "Pi",
        "left", "right", "quad", "qquad", "Longrightarrow", "longrightarrow",
        "Rightarrow", "rightarrow", "leftarrow", "to", "boxed",
    )
}
