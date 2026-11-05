package com.chatmobile.markdown

/**
 * 数学公式的**盒模型**（box model）—— 真正排版的中间表示。
 *
 * ## ★★★ 为什么要从「Latin→Unicode 字符串」改成树
 *
 * 旧实现（`LatexText`）把 `\frac{a}{b}` 变成 `a/b`、把 `\int_a^b` 变成 `∫ₐᵇ`。
 * 能读，但它们**没有真正排版**：
 *
 * | 旧 | 想要 |
 * |---|---|
 * | `a/b` | 分子在分母**上方**，中间一条横线 |
 * | `∫ₐᵇ` | 大积分号，上下限**堆叠**在它右侧（或上下） |
 * | `√(x+1)` | 根号**罩住**整个 `x+1`，上面有横线 |
 * | `x²` | 真正的抬升小字号，而不是换一个 Unicode 字符 |
 *
 * 后三件事**无法用字符串表达** —— 它们是二维布局。所以必须建树。
 *
 * ## ★ 为什么不引 KaTeX / MathJax / WebView
 *
 * - WebView：每条消息一个 WebView，`LazyColumn` 里滚动会卡死；深色模式要单独注入；
 *   而且公式**无法复制**（对聊天场景是硬伤）。
 * - 引一个 Kotlin 数学库：本项目至今零排版依赖，为聊天场景引一个几十 MB 的引擎不值。
 *
 * 而聊天里的公式类型高度集中（分式、上下标、根号、几个大运算符、希腊字母），
 * 自己实现一个**够用的盒模型**大约几百行，且完全可控、可单测。
 */
sealed interface MathNode {

    /**
     * 一个"符号"叶节点。
     *
     * ★ [kind] 不是装饰 —— 它决定 **斜体** 与 **字间距**：
     *   - 变量必须斜体（`x` 与 `×` 才分得清，这也是数学排版的核心惯例）
     *   - 数字、运算符、函数名必须正体（`sin` 斜体就成了 `s·i·n`）
     */
    data class Sym(val text: String, val kind: SymKind) : MathNode

    /** 横向排列。 */
    data class Row(val items: List<MathNode>) : MathNode

    /** 分式：分子在上、分母在下、中间一条横线。 */
    data class Frac(val num: MathNode, val den: MathNode) : MathNode

    /**
     * 上下标。
     *
     * ★ `sup` / `sub` 都可空 —— `x^2` 只有上标，`x_i` 只有下标。
     */
    data class Script(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode

    /** 根号。[index] 是开方次数（`\sqrt[3]{x}` 的 3），可空。 */
    data class Sqrt(val body: MathNode, val index: MathNode?) : MathNode

    /**
     * 大运算符（`\sum` `\int` `\prod` …）。
     *
     * ★★ [limitsOnSide] 是**排版惯例**，不是可有可无的开关：
     *   - `\int` 的上下限放**右侧**（下标右下、上标右上）—— 数学界通用写法
     *   - `\sum` `\prod` 的上下限**堆在正上、正下**（行间公式里）
     *   搞反了会显得很业余，而且 `∫` 上下堆叠会让公式高度失控。
     */
    data class BigOp(
        val sym: String,
        val sub: MathNode?,
        val sup: MathNode?,
        val limitsOnSide: Boolean,
    ) : MathNode

    /**
     * 可伸缩定界符（`\left( \right)` 里的括号）。
     *
     * ★ 它必须能**跟着内容长高**：`(\frac{a}{b})` 的括号要比 `(x)` 高一倍多，
     *   否则括号只包住中间一点，读起来完全错。
     *   所以它的度量在**父 Row 排好之后**才确定（见 MathTypesetter 的 Row 分支）。
     */
    data class Delim(val text: String) : MathNode

    /** 显式间距。`\quad` 这类换算出来的。 */
    data class Space(val widthEm: Float) : MathNode
}

/** 符号类别 —— 决定字形与间距。 */
enum class SymKind {
    /** 变量：**斜体**。 */
    Variable,

    /** 数字：正体。 */
    Number,

    /** 二元运算符（`+` `-` `·`）：两侧留薄空格。 */
    BinaryOp,

    /** 关系符（`=` `<` `≈`）：两侧留**较宽**空格（数学排版惯例）。 */
    Relation,

    /** 函数名（`sin` `log`）：正体，且不与后面括号贴太紧。 */
    Function,

    /** 标点（逗号、分号）：后面留空格。 */
    Punctuation,

    /** `\text{}` 里的普通文字：正体，按普通文字渲染。 */
    Text,

    /** 普通符号（希腊字母以外的杂项）。 */
    Ordinary,
}

/**
 * LaTeX → [MathNode] 的解析器。
 *
 * ## ★ 设计原则：**永不抛异常**
 *
 * 这个解析器吃的是**模型流式输出**里的半截公式（`\frac{a}{` 这种）。
 * 抛一次异常就等于整条消息渲染失败。所以所有不闭合、不认识的结构
 * 一律**降级为字面量或忽略**，能排多少算多少。
 *
 * 与旧 `LatexText` 的关系：本解析器**取代**它用于区块公式。
 * `LatexText` 保留给"没有定界符的正文内联命令"那条路（那里需要路径护栏，语义不同）。
 */
object LatexMathParser {

    /** 解析入口。任何输入都返回一个节点（最差是空 Row）。 */
    fun parse(source: String): MathNode {
        val r = parseSequence(source, 0, terminator = null)
        return normalize(r.first)
    }

    // ------------------------------------------------------------------
    // 序列
    // ------------------------------------------------------------------

    /**
     * 读一段序列，直到 [terminator] 或字符串结束。
     *
     * @return 节点 与 新的扫描位置
     */
    private fun parseSequence(src: String, from: Int, terminator: Char?): Pair<MathNode, Int> {
        val items = mutableListOf<MathNode>()
        var i = from

        while (i < src.length) {
            val c = src[i]

            if (terminator != null && c == terminator) {
                return MathNode.Row(items) to (i + 1)
            }

            when {
                // 空白：跳过（LaTeX 不把空格当内容；需要空格时用 `\quad` / `\ `）
                c.isWhitespace() -> i++

                // 显式分组：只影响优先级，不影响内容
                c == '{' -> {
                    val (inner, next) = parseSequence(src, i + 1, '}')
                    items += inner
                    i = next
                }

                c == '}' -> {
                    // 多余的右括号（流式中常见）⇒ 直接吞掉，不报错
                    i++
                }

                // 上下标：挂到**前一个**原子上
                c == '^' || c == '_' -> {
                    val (script, next) = readScriptArgument(src, i + 1)
                    val baseNode = items.removeLastOrNull() ?: MathNode.Sym("", SymKind.Ordinary)
                    val merged = attachScript(baseNode, isSup = c == '^', script)
                    items += merged
                    i = next
                }

                c == '\\' -> {
                    val (node, next) = readCommand(src, i)
                    if (node != null) items += node
                    i = next
                }

                // 普通字符
                else -> {
                    val (node, next) = readAtom(src, i)
                    items += node
                    i = next
                }
            }
        }
        return MathNode.Row(items) to i
    }

    // ------------------------------------------------------------------
    // 原子
    // ------------------------------------------------------------------

    /**
     * 读一个普通字符原子。
     *
     * ★ **连续数字合成一个 Number 节点**：`123` 若拆成三个 Sym，
     *   排版时会各自有斜体/间距判断，视觉上会出现微小空隙；
     *   合成一个节点后是一个完整的数字串，符合数学排版惯例。
     */
    private fun readAtom(src: String, at: Int): Pair<MathNode, Int> {
        val c = src[at]

        if (c.isDigit()) {
            var j = at
            while (j < src.length && src[j].isDigit()) j++
            // 小数：`1.5` 也算一个数字
            if (j < src.length && src[j] == '.' && j + 1 < src.length && src[j + 1].isDigit()) {
                j++
                while (j < src.length && src[j].isDigit()) j++
            }
            return MathNode.Sym(src.substring(at, j), SymKind.Number) to j
        }

        val kind = when {
            c.isLetter() -> SymKind.Variable
            c in "+-*/" -> SymKind.BinaryOp
            c in "=<>" || c == '≤' || c == '≥' || c == '≠' || c == '≈' -> SymKind.Relation
            c in ",;:" -> SymKind.Punctuation
            else -> SymKind.Ordinary
        }
        return MathNode.Sym(c.toString(), kind) to at + 1
    }

    /**
     * 读上下标的参数。
     *
     * ★ 三种形态（与 `\frac` 的参数一致）：
     *   `^{...}` 正常 / `^2` 单字符 / `^\alpha` 命令
     */
    private fun readScriptArgument(src: String, from: Int): Pair<MathNode, Int> {
        var k = from
        while (k < src.length && src[k].isWhitespace()) k++
        if (k >= src.length) return MathNode.Row(emptyList()) to k

        return when (src[k]) {
            '{' -> {
                val (inner, next) = parseSequence(src, k + 1, '}')
                inner to next
            }
            '\\' -> {
                val (node, next) = readCommand(src, k)
                (node ?: MathNode.Row(emptyList())) to next
            }
            else -> {
                // 单字符：`x^2`
                val (node, next) = readAtom(src, k)
                node to next
            }
        }
    }

    /** 把上下标挂到 base 上；base 若是 BigOp 则并进它的字段（便于上下限排版）。 */
    private fun attachScript(base: MathNode, isSup: Boolean, script: MathNode): MathNode =
        when (base) {
            is MathNode.BigOp -> if (isSup) {
                base.copy(sup = script)
            } else {
                base.copy(sub = script)
            }
            is MathNode.Script -> if (isSup) base.copy(sup = script) else base.copy(sub = script)
            else -> if (isSup) {
                MathNode.Script(base, sup = script, sub = null)
            } else {
                MathNode.Script(base, sup = null, sub = script)
            }
        }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    /**
     * 读一个 `\command`。
     *
     * @return 节点（null = 该命令不产出内容，例如 `\left`）与新的扫描位置
     */
    private fun readCommand(src: String, at: Int): Pair<MathNode?, Int> {
        if (at + 1 >= src.length) {
            return MathNode.Sym("\\", SymKind.Ordinary) to (at + 1)
        }

        val nxt = src[at + 1]

        // `\\` 换行（矩阵）—— 单行公式里当成一个空格
        if (nxt == '\\') return MathNode.Space(0.5f) to (at + 2)

        // `\,` `\;` `\quad` 这类间距
        if (!nxt.isLetter()) {
            val space = when (nxt) {
                ',', ':', ';', ' ' -> MathNode.Space(0.166f)
                '!' -> MathNode.Space(-0.166f)
                'q' -> MathNode.Space(1f)      // 不会到这里，保险
                else -> MathNode.Sym(nxt.toString(), SymKind.Ordinary)
            }
            return space to (at + 2)
        }

        var j = at + 1
        while (j < src.length && src[j].isLetter()) j++
        val name = src.substring(at + 1, j)

        // ---- 带参数的 ----
        when (name) {
            "frac", "tfrac", "dfrac", "cfrac" -> {
                val (num, p1) = readGroupArg(src, j)
                val (den, p2) = readGroupArg(src, p1)
                return MathNode.Frac(num, den) to p2
            }

            "sqrt" -> {
                var k = j
                var index: MathNode? = null
                // 可选开方次数
                val optOpen = skipWs(src, k)
                if (optOpen < src.length && src[optOpen] == '[') {
                    val close = src.indexOf(']', optOpen)
                    if (close > optOpen) {
                        index = parse(src.substring(optOpen + 1, close))
                        k = close + 1
                    }
                }
                val (body, p) = readGroupArg(src, k)
                return MathNode.Sqrt(body, index) to p
            }

            // 文本/字体：内容原样保留，按 Text 类渲染（**正体**）
            "text", "mathrm", "operatorname", "textnormal", "textrm", "mbox" -> {
                val (body, p) = readGroupArg(src, j)
                return toText(body) to p
            }

            "mathbf", "textbf", "boldsymbol", "bm", "mathit", "mathsf", "mathtt",
            "mathbb", "mathcal", "mathscr", "mathfrak",
            -> {
                // 字体样式不实现（数学里极少数才需要），但**内容必须保留**
                val (body, p) = readGroupArg(src, j)
                return body to p
            }

            "overline", "bar", "hat", "widehat", "vec", "tilde", "widetilde", "dot", "ddot" -> {
                // 重音符号这一版不画（画歪了比不画更糟），但内容保留
                val (body, p) = readGroupArg(src, j)
                return body to p
            }

            "boxed" -> {
                val (body, p) = readGroupArg(src, j)
                return body to p
            }
        }

        // ---- 大运算符（带上下限位置约定） ----
        BIG_OPS[name]?.let { (sym, sideLimits) ->
            return MathNode.BigOp(sym, sub = null, sup = null, limitsOnSide = sideLimits) to j
        }

        // ---- 可伸缩定界符 ----
        if (name == "left" || name == "right") {
            val k = skipWs(src, j)
            if (k >= src.length) return null to k
            val delim = when (src[k]) {
                '\\' -> {
                    // \left\{ \right\}
                    var m = k + 1
                    while (m < src.length && src[m].isLetter()) m++
                    when (src.substring(k + 1, m)) {
                        "lbrace" -> "{" to m
                        "rbrace" -> "}" to m
                        "langle" -> "⟨" to m
                        "rangle" -> "⟩" to m
                        "vert", "|" -> "|" to m
                        "Vert" -> "‖" to m
                        else -> "|" to m
                    }
                }
                else -> src[k].toString() to (k + 1)
            }
            // `.` 表示"这一侧不要括号"
            if (delim.first == ".") return null to delim.second
            return MathNode.Delim(delim.first) to delim.second
        }

        // ---- 纯排版控制：不产出 ----
        if (name in LAYOUT_ONLY) {
            return when (name) {
                "quad" -> MathNode.Space(1f) to j
                "qquad" -> MathNode.Space(2f) to j
                else -> null to j
            }
        }

        // ---- 纯符号替换 ----
        SYMBOLS[name]?.let { return MathNode.Sym(it, kindOf(name)) to j }

        // ---- 未知命令：丢掉命令名，但若紧跟 `{}`，内容会被主循环保留下来 ----
        return null to j
    }

    /** 读一个 `{...}` 参数；没有花括号就吃一个 token。 */
    private fun readGroupArg(src: String, from: Int): Pair<MathNode, Int> {
        val k = skipWs(src, from)
        if (k >= src.length) return MathNode.Row(emptyList()) to k

        return when (src[k]) {
            '{' -> parseSequence(src, k + 1, '}')
            '\\' -> {
                val (node, next) = readCommand(src, k)
                (node ?: MathNode.Row(emptyList())) to next
            }
            // 单字符参数：`\frac12`
            else -> readAtom(src, k)
        }
    }

    private fun skipWs(src: String, from: Int): Int {
        var k = from
        while (k < src.length && src[k].isWhitespace()) k++
        return k
    }

    /** 把一个节点树整体标成 Text（`\text{...}` 里的内容不按变量斜体）。 */
    private fun toText(node: MathNode): MathNode = when (node) {
        is MathNode.Sym -> node.copy(kind = SymKind.Text)
        is MathNode.Row -> MathNode.Row(node.items.map(::toText))
        else -> node
    }

    /**
     * 判断这个符号该按什么类别渲染。
     *
     * ★ 希腊字母这类是**变量**（要斜体）；`\sin` `\log` 是**函数名**（必须正体）。
     *   搞反了 `sin` 会变成 `s·i·n`，`π` 会变成正体像 `n`。
     */
    private fun kindOf(command: String): SymKind = when {
        command in FUNCTION_NAMES -> SymKind.Function
        command in RELATION_SYMBOLS -> SymKind.Relation
        command in BINARY_SYMBOLS -> SymKind.BinaryOp
        command in NUMBER_SYMBOLS -> SymKind.Number
        // 希腊字母、单字母符号：按变量（斜体）
        command.length <= 8 && SYMBOLS[command]?.length == 1 -> SymKind.Variable
        else -> SymKind.Ordinary
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /** 拍平嵌套空 Row、去掉空 Sym，让后续排版少走弯路。 */
    private fun normalize(node: MathNode): MathNode = when (node) {
        is MathNode.Row -> {
            val flat = mutableListOf<MathNode>()
            for (item in node.items) {
                val n = normalize(item)
                if (n is MathNode.Row) flat += n.items else flat += n
            }
            val cleaned = flat.filterNot {
                it is MathNode.Sym && it.text.isEmpty()
            }
            if (cleaned.size == 1) cleaned[0] else MathNode.Row(cleaned)
        }
        is MathNode.Frac -> MathNode.Frac(normalize(node.num), normalize(node.den))
        is MathNode.Script -> MathNode.Script(
            normalize(node.base),
            node.sup?.let(::normalize),
            node.sub?.let(::normalize),
        )
        is MathNode.Sqrt -> MathNode.Sqrt(normalize(node.body), node.index?.let(::normalize))
        is MathNode.BigOp -> node.copy(
            sub = node.sub?.let(::normalize),
            sup = node.sup?.let(::normalize),
        )
        else -> node
    }

    // ==================================================================
    // 数据表
    // ==================================================================

    /**
     * 大运算符 → (显示字符, 上下限是否放右侧)。
     *
     * ★ `\int` 放右侧、`\sum` 放上下 —— 这是数学排版惯例，不是偏好。
     *   把 `∫` 的上下限堆到正上下会让整行高度翻倍，视觉上完全不像公式。
     */
    private val BIG_OPS: Map<String, Pair<String, Boolean>> = mapOf(
        // 积分类：上下限在右侧
        "int" to ("∫" to true),
        "iint" to ("∬" to true),
        "iiint" to ("∭" to true),
        "iiiint" to ("⨌" to true),
        "oint" to ("∮" to true),
        // 求和/连乘类：上下限堆在上下
        "sum" to ("∑" to false),
        "prod" to ("∏" to false),
        "coprod" to ("∐" to false),
        "bigcup" to ("⋃" to false),
        "bigcap" to ("⋂" to false),
        "bigoplus" to ("⨁" to false),
        "bigotimes" to ("⨂" to false),
        "bigvee" to ("⋁" to false),
        "bigwedge" to ("⋀" to false),
    )

    private val LAYOUT_ONLY: Set<String> = setOf(
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
        "limits", "nolimits", "nonumber", "notag", "mathstrut",
        "rm", "bf", "it", "cal",
    )

    private val FUNCTION_NAMES: Set<String> = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth",
        "log", "ln", "lg", "exp",
        "lim", "max", "min", "sup", "inf",
        "det", "dim", "gcd", "lcm", "mod", "Pr",
        "arg", "deg", "ker", "hom",
    )

    private val RELATION_SYMBOLS: Set<String> = setOf(
        "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv",
        "sim", "simeq", "cong", "propto", "ll", "gg",
        "in", "notin", "ni", "subset", "supset", "subseteq", "supseteq",
        "to", "rightarrow", "leftarrow", "gets", "leftrightarrow",
        "Rightarrow", "Leftarrow", "Leftrightarrow",
        "longrightarrow", "Longrightarrow", "longleftarrow", "Longleftarrow",
        "mapsto", "implies", "iff", "perp", "parallel",
    )

    private val BINARY_SYMBOLS: Set<String> = setOf(
        "cdot", "times", "div", "pm", "mp", "ast", "star", "circ",
        "bullet", "oplus", "ominus", "otimes", "cup", "cap", "setminus",
        "land", "lor", "wedge", "vee",
    )

    private val NUMBER_SYMBOLS: Set<String> = setOf(
        "infty", "partial", "prime", "ldots", "cdots", "dots", "vdots",
    )

    /** 命令 → Unicode。只放能用单个字符准确表达的。 */
    private val SYMBOLS: Map<String, String> = buildMap {
        // 希腊字母（小写）
        put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
        put("epsilon", "ε"); put("varepsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
        put("theta", "θ"); put("vartheta", "ϑ"); put("iota", "ι"); put("kappa", "κ")
        put("lambda", "λ"); put("mu", "μ"); put("nu", "ν"); put("xi", "ξ")
        put("omicron", "ο"); put("pi", "π"); put("varpi", "ϖ"); put("rho", "ρ")
        put("varrho", "ϱ"); put("sigma", "σ"); put("varsigma", "ς"); put("tau", "τ")
        put("upsilon", "υ"); put("phi", "φ"); put("varphi", "φ"); put("chi", "χ")
        put("psi", "ψ"); put("omega", "ω")

        // 希腊字母（大写）
        put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
        put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Upsilon", "Υ")
        put("Phi", "Φ"); put("Psi", "Ψ"); put("Omega", "Ω")

        // 运算符
        put("cdot", "·"); put("times", "×"); put("div", "÷"); put("pm", "±")
        put("mp", "∓"); put("ast", "∗"); put("star", "⋆"); put("circ", "∘")
        put("bullet", "•"); put("oplus", "⊕"); put("ominus", "⊖"); put("otimes", "⊗")

        // 关系
        put("le", "≤"); put("leq", "≤"); put("ge", "≥"); put("geq", "≥")
        put("ne", "≠"); put("neq", "≠"); put("approx", "≈"); put("equiv", "≡")
        put("sim", "∼"); put("simeq", "≃"); put("cong", "≅"); put("propto", "∝")
        put("ll", "≪"); put("gg", "≫")
        put("in", "∈"); put("notin", "∉"); put("ni", "∋")
        put("subset", "⊂"); put("supset", "⊃"); put("subseteq", "⊆"); put("supseteq", "⊇")
        put("cup", "∪"); put("cap", "∩"); put("setminus", "∖")
        put("emptyset", "∅"); put("varnothing", "∅")

        // 箭头
        put("to", "→"); put("rightarrow", "→"); put("leftarrow", "←")
        put("gets", "←"); put("leftrightarrow", "↔"); put("Rightarrow", "⇒")
        put("Leftarrow", "⇐"); put("Leftrightarrow", "⇔")
        put("longrightarrow", "⟶"); put("Longrightarrow", "⟹")
        put("longleftarrow", "⟵"); put("Longleftarrow", "⟸")
        put("uparrow", "↑"); put("downarrow", "↓"); put("mapsto", "↦")
        put("implies", "⟹"); put("iff", "⟺")

        // 几何 / 杂项
        put("triangle", "△"); put("square", "□"); put("Box", "□")
        put("angle", "∠"); put("perp", "⊥"); put("parallel", "∥")
        put("infty", "∞"); put("partial", "∂"); put("nabla", "∇")
        put("therefore", "∴"); put("because", "∵")
        put("ldots", "…"); put("cdots", "⋯"); put("dots", "…"); put("vdots", "⋮")
        put("prime", "′"); put("degree", "°")
        put("forall", "∀"); put("exists", "∃"); put("neg", "¬")
        put("land", "∧"); put("lor", "∨"); put("wedge", "∧"); put("vee", "∨")
        put("hbar", "ℏ"); put("ell", "ℓ"); put("Re", "ℜ"); put("Im", "ℑ")

        // 函数名（正体显示，由 kindOf 判为 Function）
        for (f in setOf(
            "sin", "cos", "tan", "cot", "sec", "csc",
            "arcsin", "arccos", "arctan",
            "sinh", "cosh", "tanh", "coth",
            "log", "ln", "lg", "exp",
            "lim", "max", "min", "sup", "inf",
            "det", "dim", "gcd", "lcm", "mod", "Pr",
            "arg", "deg", "ker", "hom",
        )) {
            put(f, f)
        }
    }

    /** 空 Row（解析失败时的安全返回值）。 */
    val EMPTY: MathNode = MathNode.Row(emptyList())
}
