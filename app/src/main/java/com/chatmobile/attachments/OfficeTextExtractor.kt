package com.chatmobile.attachments

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 从 Office 文档里**本地**提取纯文本。
 *
 * ## ★★★ 为什么要有这个（用户原话）
 *
 * > 「这个文件只能选这两个格式的吗？不能选其他格式的吗？
 * >  我经常上传其他格式的，比如 word、PPT、PDF 之类的。」
 *
 * 上一版只放行纯文本（.txt/.md/.json…），把 docx/pptx/xlsx/pdf 一律拒掉。
 * 那个限制是**偷懒**，不是协议要求 —— OpenAI 协议确实没有 `{"type":"file"}`，
 * 但"把内容取出来当文本发"完全可行，而这些格式恰恰是用户最常传的。
 *
 * ## ★ 为什么零依赖就能解析 docx / pptx / xlsx
 *
 * 它们**本质都是 ZIP**，里面装的是 XML（Office 2007 起就统一成 OOXML）：
 *
 * | 格式 | 要读的条目 | 正文标签 |
 * |---|---|---|
 * | `.docx` | `word/document.xml` | `<w:t>` |
 * | `.pptx` | `ppt/slides/slideN.xml` | `<a:t>` |
 * | `.xlsx` | `xl/sharedStrings.xml` + `xl/worksheets/sheetN.xml` | `<t>` / `<v>` |
 *
 * 所以 `java.util.zip` + 正则就够 —— **不需要 Apache POI**。
 * POI 在 Android 上有 10MB+ 且依赖一堆 javax 类，为了读个段落实在不值。
 *
 * ## ★ 为什么用正则而不是 DOM 解析
 *
 * ① Office 生成的 XML 结构极其稳定（不是任意 XML），正则够用；
 * ② `DocumentBuilder` 会把整棵树读进内存 —— 一个带 200 张图的 pptx 能吃掉几十 MB，
 *    而我们只需要文字；
 * ③ 我们**只读本地文件、不解析外部实体**，没有 XXE 面。
 */
object OfficeTextExtractor {

    /** 提取结果。 */
    data class Extracted(
        val text: String,
        /** 这份文档实际有几页/几节 —— 用于在正文里标注来源。 */
        val parts: Int,
    )

    /**
     * 按扩展名分发。不认识的格式返回 null（调用方继续尝试别的路）。
     *
     * ★ **永不抛异常**：解析失败返回 null，由调用方报"读不出来"。
     *   一个损坏的 docx 不该让整条消息发不出去。
     */
    fun extract(extension: String, bytes: ByteArray): Extracted? = runCatching {
        when (extension.lowercase()) {
            "docx" -> extractDocx(bytes)
            "pptx" -> extractPptx(bytes)
            "xlsx" -> extractXlsx(bytes)
            // 老格式是 OLE 复合文档，不是 ZIP —— 零依赖读不了，见下方 doc 的说明
            else -> null
        }
    }.getOrNull()

    /** 这个扩展名能不能本地提取。 */
    fun supports(extension: String): Boolean =
        extension.lowercase() in setOf("docx", "pptx", "xlsx")

    /**
     * 老格式（`.doc` / `.ppt` / `.xls`）的说明。
     *
     * ★ 它们不是 ZIP，是 OLE 复合二进制文档（格式规范 200 多页）。
     *   零依赖读不了，硬塞只会得到乱码 token。**如实说清并给出路**，
     *   比假装支持然后给一堆垃圾好。
     */
    fun legacyFormatHint(name: String): String =
        "「" + name + "」是 Office 的老格式（97-2003），不是 ZIP 包，本地拆不开。" +
            "用 Office / WPS 另存为 .docx / .pptx / .xlsx 就能发，或者导出成 PDF。"

    // ------------------------------------------------------------------
    // docx
    // ------------------------------------------------------------------

    /**
     * `.docx` = `word/document.xml` 里的 `<w:t>` 文本，`</w:p>` 换段。
     *
     * ★ 段落处理：Word 里**一个 `<w:p>` 就是一段**。只把文本拼起来会得到
     *   一整坨没有换行的东西，模型读起来很难受（而且列表、标题全糊在一起）。
     */
    private fun extractDocx(bytes: ByteArray): Extracted? {
        val xml = readZipEntry(bytes, "word/document.xml") ?: return null
        val text = extractTaggedText(xml, "t")
        return if (text.isBlank()) null else Extracted(text, 1)
    }

    // ------------------------------------------------------------------
    // pptx
    // ------------------------------------------------------------------

    /**
     * `.pptx` = 每个 `ppt/slides/slideN.xml` 里的 `<a:t>`，**按页分开**。
     *
     * ★ 必须按页分并标出「第 N 页」：
     *   PPT 的内容高度依赖版面 —— 同一段文字在"标题"里和在"脚注"里意思不同。
     *   丢掉页边界后，模型会把不同页的东西串成一段，结论就全歪了。
     *
     * ★ 页序**不能靠字符串排序**（`slide10` 会排在 `slide2` 前面），
     *   必须按文件名里的数字排序。
     */
    private fun extractPptx(bytes: ByteArray): Extracted? {
        val slides = readAllZipEntries(bytes)
            .filter { (name, _) ->
                name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
            }
            .sortedBy { (name, _) -> slideNumber(name) }

        if (slides.isEmpty()) return null

        val sb = StringBuilder()
        var used = 0
        for ((name, xml) in slides) {
            val body = extractTaggedText(xml, "t")
            if (body.isBlank()) continue
            used++
            sb.append("--- 第 ").append(slideNumber(name)).append(" 页 ---\n")
            sb.append(body.trim()).append("\n\n")
        }
        return if (sb.isBlank()) null else Extracted(sb.toString().trim(), used)
    }

    /**
     * 从路径尾部取编号：`slide12.xml` → 12、`sheet3.xml` → 3。
     *
     * ★ 上一版只匹配 `slide(\d+)`，于是 xlsx 的 `sheetN.xml` 全部返回 0，
     *   每张表都被标成「工作表 0」—— 多张表时无法区分。已由测试钉住。
     */
    private fun slideNumber(path: String): Int =
        Regex("""[a-zA-Z]+(\d+)\.xml$""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ------------------------------------------------------------------
    // xlsx
    // ------------------------------------------------------------------

    /**
     * `.xlsx` = 共享字符串表 + 各 sheet 的单元格引用。
     *
     * ★ 这是三者里最麻烦的：单元格里存的通常**不是文本，是索引**。
     *   `xl/sharedStrings.xml` 是一张全局字符串池，sheet 里的
     *   `<c t="s"><v>42</v></c>` 意思是"第 42 号共享字符串"。
     *   直接把 `<v>42</v>` 当内容发出去，模型会看到一堆莫名其妙的数字 ——
     *   那比拒绝更糟（它会煞有介事地分析这些数字）。
     *
     * ★ 行要按 `<row>` 聚合、列之间用制表符分隔 —— 否则表格结构全丢。
     */
    private fun extractXlsx(bytes: ByteArray): Extracted? {
        val entries = readAllZipEntries(bytes)
        val shared = entries.firstOrNull { it.first == "xl/sharedStrings.xml" }
            ?.second?.let(::parseSharedStrings) ?: emptyList()

        val sheets = entries
            .filter { (name, _) ->
                name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
            }
            .sortedBy { (name, _) -> slideNumber(name) }

        if (sheets.isEmpty()) return null

        val sb = StringBuilder()
        var usedSheets = 0
        for ((name, xml) in sheets) {
            val body = renderSheet(xml, shared)
            if (body.isBlank()) continue
            usedSheets++
            sb.append("--- 工作表 ").append(slideNumber(name)).append(" ---\n")
            sb.append(body.trim()).append("\n\n")
        }
        return if (sb.isBlank()) null else Extracted(sb.toString().trim(), usedSheets)
    }

    /** 共享字符串池：`<si>…<t>文本</t>…</si>` 按出现顺序就是索引。 */
    private fun parseSharedStrings(xml: String): List<String> {
        val out = mutableListOf<String>()
        // ★ 用非贪婪匹配每个 <si> 块，块内可能有多个 <t>（富文本分片）
        for (si in Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val inner = si.groupValues[1]
            val text = TAG_ANY_T.findAll(inner)
                .joinToString("") { unescapeXml(it.groupValues[1]) }
            out += text
        }
        return out
    }

    /** 把一张 sheet 渲染成"每行一行、单元格用 \t 分隔"。 */
    private fun renderSheet(xml: String, shared: List<String>): String {
        val sb = StringBuilder()
        for (rowMatch in Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val cells = mutableListOf<String>()
            for (c in Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.groupValues[1])) {
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                val isShared = attrs.contains("""t="s"""")
                val raw = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1)
                val value = when {
                    // 共享字符串：<v>42</v> 指向池里第 42 条
                    isShared -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    // 内联字符串：<is><t>…</t></is>
                    inner.contains("<is>") -> TAG_ANY_T.find(inner)?.groupValues?.get(1)
                        ?.let(::unescapeXml).orEmpty()
                    // 普通值（数字/布尔/日期序列号）
                    else -> raw?.let(::unescapeXml).orEmpty()
                }
                cells += value
            }
            if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString("\t").trimEnd()).append("\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    /**
     * 从一段 XML 里取出某个标签的所有文本，并**保留段落换行**。
     *
     * ★ 参数 [tag] 是**不带命名空间前缀**的标签名（`t`，不是 `w:t`）——
     *   前缀由正则里的 `(?:[a-zA-Z]+):` 通配掉。
     *   （上一版这里传了 `"w:t"`，正则于是变成 `<xxx:w:t>`，
     *    那个标签永远不存在 ⇒ **所有 docx/pptx 都解析失败**，
     *    而单测里用字符串拼的样例看着完全正常。已由测试钉住。）
     */
    private fun extractTaggedText(xml: String, tag: String): String {
        // 先按段落切分（docx 用 <w:p>，pptx 用 <a:p>）
        val paraPattern = Regex("""<(?:w|a):p\b[^>]*>(.*?)</(?:w|a):p>""", RegexOption.DOT_MATCHES_ALL)
        val paragraphs = paraPattern.findAll(xml).map { it.groupValues[1] }.toList()

        if (paragraphs.isEmpty()) {
            // 没有段落结构（例如 pptx 的某些文本框）⇒ 全部拼起来
            return tagTexts(xml, tag).joinToString("")
        }
        return paragraphs
            .map { para -> tagTexts(para, tag).joinToString("") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /**
     * 取 `<任意前缀:tag>` 里的文本（含 XML 反转义）。
     *
     * ★ tag **不含前缀**。`<w:t>` 与 `<a:t>` 都能被同一个模式命中。
     */
    private fun tagTexts(xml: String, tag: String): List<String> =
        Regex(
            "<(?:[a-zA-Z]+):" + Regex.escape(tag) + "\\b[^>]*>(.*?)</(?:[a-zA-Z]+):" +
                Regex.escape(tag) + ">",
            RegexOption.DOT_MATCHES_ALL,
        )
            .findAll(xml)
            .map { unescapeXml(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()

    /** 任意命名空间下名为 `t` 的标签（`.xlsx` 的共享字符串用）。 */
    private val TAG_ANY_T: Regex = Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * XML 实体反转义。
     *
     * ★ 顺序不能反：`&amp;` **必须最后**处理。
     *   否则 `&amp;lt;` 会先变成 `&lt;` 再变成 `<` —— 那是双重解码，
     *   把用户写的字面量 `<` 错误地变成了标签。
     */
    private fun unescapeXml(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /** 读单个 ZIP 条目；不存在返回 null。 */
    private fun readZipEntry(bytes: ByteArray, path: String): String? =
        readAllZipEntries(bytes).firstOrNull { it.first == path }?.second

    /**
     * 一次性读出所有 `.xml` 条目。
     *
     * ★ 只收 `.xml`、且**跳过超大条目**：Office 文件里图片/嵌入对象
     *   可能几十 MB，全读进内存会 OOM，而它们本来也不含我们要的文字。
     *
     * ★★★ 必须用**限长读取**而不是 `zip.readBytes()`：
     *   `ZipInputStream.readBytes()` 会一路读到**流的末尾**，
     *   把后面所有条目的字节都吞进当前条目里 —— 于是第一个 `.xml`
     *   拿到了整包的二进制垃圾，正则再也匹配不到任何标签。
     *   （真机表现是"所有 docx/pptx 都解析失败"，而单测里用字符串
     *     构造的样例却看不出问题 —— 因为那时根本没有 ZIP。）
     */
    private fun readAllZipEntries(bytes: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buf = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!name.endsWith(".xml")) continue
                    // ★ entry.size 在流式读取时可能是 -1（未写在本地头里），
                    //   所以用"读到 nextEntry 边界为止"的限长读法。
                    val bos = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        bos.write(buf, 0, n)
                        // 超限就丢弃这个条目（继续 nextEntry 仍能正常工作）
                        if (bos.size() > MAX_ENTRY_BYTES) {
                            bos.reset()
                            break
                        }
                    }
                    if (bos.size() == 0) continue
                    out += name to bos.toString("UTF-8")
                }
            }
        }
        return out
    }

    /**
     * 单个 XML 条目的读取上限。
     *
     * ★ 8MB：正常的 `document.xml`（几十万字的长文档）也就 1-3MB；
     *   超过这个量级基本是内嵌了巨大的二进制或 Base64，读进来也用不上。
     */
    private const val MAX_ENTRY_BYTES: Long = 8L * 1024 * 1024
}
