package com.chatmobile.markdown

/**
 * Markdown 的**块级**模型。
 *
 * ★ 这是一个**纯数据**模型：不依赖 Android、不依赖 Compose，
 *   所以解析器可以完整单测（见 `MarkdownParserTest`）。
 *   渲染在 `ui/markdown/MarkdownText.kt`。
 *
 * ## 为什么自己写而不用现成库
 *
 * 三个理由，都是这个项目特有的：
 *
 * 1. **流式容错**。我们边收 token 边渲染，用户会看到**半截**的 Markdown：
 *    ` ``` ` 只写了两个反引号、`**` 只开没闭、表格只写了表头。
 *    大多数库对不完整输入的表现是"整段变纯文本"或"吞掉后面所有内容" ——
 *    聊天场景里这会让屏幕在流式期间疯狂闪烁。
 *    ⇒ 我们选择**永不失败**：不完整的语法一律降级成字面文本，已经闭合的部分照常渲染。
 *    这一点由 [closed] 字段显式建模（代码块是否闭合）。
 *
 * 2. **不引额外依赖**。项目已有 41 家厂商 + Room + OkHttp，
 *    再为排版引一个库会拉进它自己的 Compose/font 依赖，体积与版本冲突都不划算。
 *
 * 3. **可控的边界**。只支持 AI 真正会写的语法（见 README §支持的语法），
 *    不做完整 CommonMark —— 那些冷门语法在聊天里从没出现过，
 *    支持它们只会增加"渲染出来和用户想的不一样"的风险。
 */
sealed interface MdBlock {

    /**
     * 段落：连续的普通文本行合并成一段。
     *
     * ★ 为什么合并而不是每行一个段落：Markdown 的硬换行需要两个空格或反斜杠，
     *   而 AI 经常直接换行表示"同一段的第二行"。按 CommonMark 严格处理会
     *   把一段话拆成多个段落，行距变大、看起来松散。
     *   ⇒ 这里**保留软换行**（用 `\n` 拼），由渲染层决定行距。
     */
    data class Paragraph(val spans: List<MdInline>) : MdBlock

    /** 标题。`level` ∈ 1..6（`#` 的个数）。 */
    data class Heading(val level: Int, val spans: List<MdInline>) : MdBlock

    /**
     * 围栏代码块。
     *
     * @param language 开栏那行 ` ``` ` 后面的语言标记（可能为空串）
     * @param code 代码正文（**不含**围栏行本身）
     * @param closed 是否见到了闭合围栏
     *   ★ 流式时经常是 `false` —— 此时照常当代码块渲染（等宽、有底色），
     *     只是会显示一个"还在写"的视觉提示。**绝不能**因为没闭合就退回纯文本，
     *     那会让已经有几十行的代码块在收尾瞬间整体换样式。
     */
    data class CodeBlock(
        val language: String,
        val code: String,
        val closed: Boolean,
    ) : MdBlock

    /** 引用块。内部再放块，因为引用里可以有段落 / 列表 / 代码。 */
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /**
     * 列表（有序 / 无序混排时按**相邻同类项**切成多块）。
     *
     * @param level 缩进层级，0 = 顶层。每 2 个空格（或 1 个 tab）算一级。
     */
    data class ListItem(
        val level: Int,
        /** 有序列表的序号；无序时为 0 */
        val number: Int,
        /** 这一项是否是有序的（`1.` vs `-`） */
        val ordered: Boolean,
        val spans: List<MdInline>,
    ) : MdBlock

    /** 分隔线 `---` / `***` / `___`（三个及以上）。 */
    data object Divider : MdBlock

    /**
     * 表格。
     *
     * @param header 表头单元格（已解析成 inline）
     * @param aligns 每列对齐方式（`null` = 默认左对齐）
     * @param rows 数据行
     */
    data class Table(
        val header: List<List<MdInline>>,
        val aligns: List<MdAlign?>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock

    /**
     * ★ 解析不了 / 不该被解释的原始行，原样保留。
     *
     * 什么时候会产生：理论上不该有（解析器对任何输入都会降级到上面某种块），
     * 但留一个兜底类型，比让解析器抛异常好 —— 聊天界面**不能**因为
     * 一段怪文本就整屏白掉。
     */
    data class Raw(val text: String) : MdBlock

    /**
     * **独立成块的数学公式**（`$$...$$` 或 `\[...\]` 独占一段）。
     *
     * ## ★★★ 为什么必须有这个类型（用户报的渲染 bug）
     *
     * 用户截图里，模型输出的数学推导**原样显示**成：
     * ```
     * $$c^2 + 4\cdot\tfrac12 ab = (a+b)^2$$
     * $$\frac{AC}{AB} = \frac{AH}{AC} \quad\Longrightarrow\quad \frac{b}{c} = \frac{p}{b}$$
     * ```
     * 反斜杠与 `$$` 全部裸露 —— 一屏数学变成一屏反斜杠，**内容读不了**。
     *
     * ## ★ 为什么不并进 Paragraph
     *
     * 独立公式在视觉上应该**单独成块、小字号、居中或左对齐留白**，
     * 与正文区分开。并进段落就只能跟正文同样式，长公式会被断词得很碎。
     *
     * @param tex 公式**原文**（不含定界符）。保留原文是为了将来换真正的排版引擎时不用重解析。
     * @param rendered 已转好的可读文本（见 [LatexText]）。
     * @param closed 是否见到闭合定界符。流式时经常是 `false` ——
     *   此时照常按公式渲染（**绝不能**因为没闭合就退回纯文本，那会让
     *   已经写了几行的公式在收尾瞬间整体换样式）。
     */
    data class MathBlock(
        val tex: String,
        val rendered: String,
        val closed: Boolean,
    ) : MdBlock
}

/** 表格列对齐。 */
enum class MdAlign { Left, Center, Right }

/**
 * **行内**元素模型。
 *
 * ★ 与块级分开的原因：行内元素要能互相嵌套（粗体里有斜体、链接文字里有代码），
 *   而块级不能嵌套在行内里。用两个 sealed interface 表达这个约束，
 *   比用一个"什么都能装"的树要安全得多。
 */
sealed interface MdInline {

    /** 普通文本。 */
    data class Text(val text: String) : MdInline

    /** 行内代码 `` `x` ``。内部**不再解析**任何标记（原文照显）。 */
    data class Code(val text: String) : MdInline

    /** 粗体 `**x**`。 */
    data class Bold(val children: List<MdInline>) : MdInline

    /** 斜体 `*x*` 或 `_x_`。 */
    data class Italic(val children: List<MdInline>) : MdInline

    /** 删除线 `~~x~~`。 */
    data class Strike(val children: List<MdInline>) : MdInline

    /** 链接 `[text](url)`。 */
    data class Link(val children: List<MdInline>, val url: String) : MdInline

    /**
     * **行内数学**（`$...$` 或 `\(...\)`）。
     *
     * ★ 与前几种不同的地方：它**不解析子元素**（`_` `*` 在数学里是下标/乘号，
     *   当 Markdown 标记处理会把 `a_1 * b_2` 毁掉）。
     *   转好的文本直接交给渲染层，不再递归。
     *
     * @param tex 原文（不含定界符）
     * @param rendered 已转好的可读文本
     */
    data class Math(val tex: String, val rendered: String) : MdInline
}
