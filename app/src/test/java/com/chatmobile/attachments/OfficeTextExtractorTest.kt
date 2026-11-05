package com.chatmobile.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Office / PDF 文本提取的契约。
 *
 * ## 为什么需要（用户原话）
 *
 * > 「这个文件只能选这两个格式的吗？不能选其他格式的吗？
 * >  我经常上传其他格式的，比如 word、PPT、PDF 之类的。」
 *
 * 上一版只放行纯文本，把 docx / pptx / xlsx / pdf 一律拒掉。
 * 那个限制**不是协议要求**，是我图省事 —— 这些格式的内容都能在本地取出来。
 *
 * ## 这里钉什么
 *
 * ① **能不能读**的判据（多了哪些格式、少了哪些）
 * ② **读得对不对**：用真实构造的 OOXML 包测，不是编例子
 * ③ **读不出时的措辞**：必须给出替代办法，不能只说"不支持"
 */
class OfficeTextExtractorTest {

    // ------------------------------------------------------------------
    // 构造真实的 OOXML 包（不是 mock）
    // ------------------------------------------------------------------

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 造一个最小可用的 docx（两个段落）。 */
    private fun docx(vararg paragraphs: String): ByteArray {
        val body = paragraphs.joinToString("") { p ->
            "<w:p><w:r><w:t xml:space=\"preserve\">" + p + "</w:t></w:r></w:p>"
        }
        val xml = "<?xml version=\"1.0\"?><w:document xmlns:w=\"x\"><w:body>" + body +
            "</w:body></w:document>"
        return zipOf(mapOf("word/document.xml" to xml))
    }

    /** 造一个最小可用的 pptx（指定页序，故意乱序传入）。 */
    private fun pptx(slides: Map<Int, String>): ByteArray {
        val entries = slides.map { (n, text) ->
            val xml = "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><p:cSld><p:spTree><p:sp><p:txBody>" +
                "<a:p><a:r><a:t>" + text + "</a:t></a:r></a:p>" +
                "</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"
            "ppt/slides/slide$n.xml" to xml
        }.toMap()
        return zipOf(entries)
    }

    /** 造一个最小可用的 xlsx（共享字符串 + 一张表）。 */
    private fun xlsx(rows: List<List<String>>): ByteArray {
        // 共享字符串池：按首次出现顺序去重
        val pool = mutableListOf<String>()
        val cells = rows.joinToString("") { row ->
            "<row>" + row.joinToString("") { v ->
                val idx = pool.indexOf(v).let { if (it >= 0) it else { pool += v; pool.size - 1 } }
                "<c t=\"s\"><v>" + idx + "</v></c>"
            } + "</row>"
        }
        val sharedXml = "<sst>" + pool.joinToString("") { "<si><t>" + it + "</t></si>" } + "</sst>"
        val sheetXml = "<worksheet><sheetData>" + cells + "</sheetData></worksheet>"
        return zipOf(
            mapOf(
                "xl/sharedStrings.xml" to sharedXml,
                "xl/worksheets/sheet1.xml" to sheetXml,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 一、能读哪些格式（用户问的那个问题）
    // ------------------------------------------------------------------

    @Test
    fun `word PPT Excel PDF 都必须能读`() {
        // ★ 这条直接对应用户的原话。少一个都是没回答他的问题。
        assertTrue("docx 必须能读", OfficeTextExtractor.supports("docx"))
        assertTrue("pptx 必须能读", OfficeTextExtractor.supports("pptx"))
        assertTrue("xlsx 必须能读", OfficeTextExtractor.supports("xlsx"))
        assertTrue(
            "pdf 必须能读",
            "pdf" in com.chatmobile.net.AttachmentPolicy.PDF_EXTENSIONS,
        )
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(OfficeTextExtractor.supports("DOCX"))
        assertTrue(OfficeTextExtractor.supports("Pptx"))
    }

    @Test
    fun `老 Office 格式明确不支持 但要给替代办法`() {
        // 它们不是 ZIP 而是 OLE 二进制，零依赖读不了
        assertFalse(OfficeTextExtractor.supports("doc"))
        assertFalse(OfficeTextExtractor.supports("ppt"))
        assertFalse(OfficeTextExtractor.supports("xls"))

        val hint = com.chatmobile.net.AttachmentPolicy.unsupportedBinaryHint("报告.doc")
        assertTrue("必须点出是老格式", hint.contains("老格式"))
        assertTrue("必须给出替代办法", hint.contains("docx"))
    }

    @Test
    fun `压缩包要提示先解压`() {
        val hint = com.chatmobile.net.AttachmentPolicy.unsupportedBinaryHint("打包.zip")
        assertTrue(hint.contains("解压"))
    }

    // ------------------------------------------------------------------
    // 二、docx
    // ------------------------------------------------------------------

    @Test
    fun `docx 能取出正文`() {
        val got = OfficeTextExtractor.extract("docx", docx("第一段", "第二段"))
        assertNotNull("必须能解析", got)
        assertEquals("第一段\n第二段", got!!.text)
    }

    @Test
    fun `docx 段落之间必须有换行`() {
        // 只把文本拼起来会得到一整坨，列表和标题全糊在一起，模型很难读
        val got = OfficeTextExtractor.extract("docx", docx("标题", "正文一", "正文二"))
        assertNotNull(got)
        assertEquals(3, got!!.text.split("\n").size)
    }

    @Test
    fun `docx 里的 XML 实体要正确反转义`() {
        val got = OfficeTextExtractor.extract("docx", docx("a &lt; b &amp;&amp; c &gt; d"))
        assertNotNull(got)
        assertTrue("小于号要还原", got!!.text.contains("a < b"))
        assertTrue("&amp; 要还原成 &", got.text.contains("&&"))
    }

    @Test
    fun `损坏的 docx 返回 null 而不是抛异常`() {
        // 用户传一个改了扩展名的文件是常事 —— 不能让整条消息发不出去
        assertNull(OfficeTextExtractor.extract("docx", "这不是一个 zip".toByteArray()))
    }

    @Test
    fun `空 docx 返回 null`() {
        val empty = zipOf(mapOf("word/document.xml" to "<w:document><w:body></w:body></w:document>"))
        assertNull(OfficeTextExtractor.extract("docx", empty))
    }

    // ------------------------------------------------------------------
    // 三、pptx
    // ------------------------------------------------------------------

    @Test
    fun `pptx 按页输出且标出页码`() {
        val got = OfficeTextExtractor.extract("pptx", pptx(mapOf(1 to "封面", 2 to "要点")))
        assertNotNull(got)
        assertTrue("必须标第 1 页", got!!.text.contains("第 1 页"))
        assertTrue("必须标第 2 页", got.text.contains("第 2 页"))
        assertTrue(got.text.contains("封面"))
        assertTrue(got.text.contains("要点"))
    }

    @Test
    fun `pptx 页序按数字排 不是按字符串`() {
        // ★ slide10 按字符串排会跑到 slide2 前面 —— 幻灯片的顺序全乱
        val got = OfficeTextExtractor.extract(
            "pptx",
            pptx(mapOf(10 to "第十页", 2 to "第二页")),
        )
        assertNotNull(got)
        val text = got!!.text
        assertTrue(
            "第 2 页必须排在第 10 页之前，实际：$text",
            text.indexOf("第 2 页") < text.indexOf("第 10 页"),
        )
    }

    @Test
    fun `pptx 报告实际用了多少页`() {
        val got = OfficeTextExtractor.extract("pptx", pptx(mapOf(1 to "A", 2 to "B", 3 to "C")))
        assertEquals(3, got!!.parts)
    }

    // ------------------------------------------------------------------
    // 四、xlsx —— 最容易做错的一个
    // ------------------------------------------------------------------

    @Test
    fun `xlsx 的共享字符串要替换成真实文本 不是索引`() {
        // ★ 这是最容易错的一点：sheet 里存的是索引不是文本。
        //   直接把 <v>0</v> 发出去，模型会煞有介事地分析"0"这个数字。
        val got = OfficeTextExtractor.extract("xlsx", xlsx(listOf(listOf("姓名", "年龄"), listOf("张三", "30"))))
        assertNotNull(got)
        val text = got!!.text
        assertTrue("必须出现真实文本「张三」，实际：$text", text.contains("张三"))
        assertTrue("必须出现「姓名」", text.contains("姓名"))
        assertTrue("必须出现「30」", text.contains("30"))
    }

    @Test
    fun `xlsx 行列结构要用制表符保留`() {
        val got = OfficeTextExtractor.extract("xlsx", xlsx(listOf(listOf("A", "B"), listOf("C", "D"))))
        assertNotNull(got)
        // ★ 跳过「--- 工作表 N ---」这类标题行，只看真正的内容行
        val lines = got!!.text.lines().filter { it.isNotBlank() && !it.startsWith("---") }
        assertTrue("必须保留两行数据，实际：${got.text}", lines.size >= 2)
        assertTrue("同行单元格要用 \\t 分隔，实际：${lines[0]}", lines[0].contains("\t"))
    }

    // ------------------------------------------------------------------
    // 五、可读性判据（调用点契约）
    // ------------------------------------------------------------------

    @Test
    fun `Office 与 PDF 不再被判为不可读`() {
        val p = com.chatmobile.net.AttachmentPolicy
        assertFalse("docx 不该被拒", p.isUnreadable("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse("pptx 不该被拒", p.isUnreadable("a.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertFalse("xlsx 不该被拒", p.isUnreadable("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertFalse("pdf 不该被拒", p.isUnreadable("a.pdf", "application/pdf"))
    }

    @Test
    fun `纯文本与图片仍然可读`() {
        val p = com.chatmobile.net.AttachmentPolicy
        assertFalse(p.isUnreadable("a.md", ""))
        assertFalse(p.isUnreadable("a.png", "image/png"))
    }

    @Test
    fun `真正读不了的仍然拒绝`() {
        val p = com.chatmobile.net.AttachmentPolicy
        assertTrue("老 doc 要拒", p.isUnreadable("a.doc", "application/msword"))
        assertTrue("zip 要拒", p.isUnreadable("a.zip", "application/zip"))
        assertTrue("mp4 要拒", p.isUnreadable("a.mp4", "video/mp4"))
    }

    @Test
    fun `ChatHost 的拒收判据必须用 isUnreadable 而不是 isTextLike`() {
        // ★ 上一版就是用 isTextLike 判的，于是 docx/pptx/pdf 全被拒。
        //   这条钉住调用点，防止回退。
        val src = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        assertTrue(
            "必须用 isUnreadable 判拒收",
            src.contains("AttachmentPolicy.isUnreadable"),
        )
    }

    @Test
    fun `AttachmentReader 必须真的接上两个提取器`() {
        val src = File("src/main/java/com/chatmobile/attachments/AttachmentReader.kt").readText()
        assertTrue("必须调用 OfficeTextExtractor", src.contains("OfficeTextExtractor.extract"))
        assertTrue("必须有 PDF 分支", src.contains("readPdf"))
        assertTrue("必须有 PDF 提取器实例", src.contains("PdfExtractor"))
    }
}
