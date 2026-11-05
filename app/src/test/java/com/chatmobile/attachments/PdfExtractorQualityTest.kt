package com.chatmobile.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PDF 提取的**质量判据**契约。
 *
 * ## 真机踩出来的那个坑（必须钉住）
 *
 * 第一版：`文本长度 ≥ 80` 就算文本提取成功、不渲染。
 *
 * 真机结果 —— 模型回答：
 * > 「这份 PDF 我读不了喵。客户端发过来的不是文字，而是文件的原始压缩字节，
 * >  所以在我眼里就是一堆乱码。」
 *
 * 根因：PDF 内容流是 Flate 压缩的，我自己写的取串逻辑只能应付最简单的情况。
 * 稍微复杂点的 PDF **解出来就是二进制垃圾，而且垃圾长度远超 80** ⇒
 * 阈值判据把它当成功 ⇒ 渲染兜底（`PdfRenderer`，几乎不会失败）**永远不触发**。
 *
 * ⇒ 修正为「长度够 **且** 可读字符占比够高 **且** 不含结构残留」三条同时满足。
 *   任一条不满足就走渲染。
 *
 * ★ 这个 bug 的形状值得记住：**判据只看"有没有产出"，没看"产出是不是对的东西"**。
 *   编译过、界面正常、日志显示"文本 1 个 / 失败 0 项" —— 全都看着成功。
 */
class PdfExtractorQualityTest {

    /**
     * 复制一份判定逻辑来测。
     *
     * ★ 为什么不直接调 `PdfExtractor` 的私有方法：它依赖 `android.graphics.pdf`，
     *   在 JVM 单测里是空 stub（L17 同源）。这里测的是**判据本身**，
     *   而判据是纯逻辑 —— 把它复制出来测是合理的，并在生产代码里用同样的常量。
     */
    private fun isMostlyReadable(s: String): Boolean {
        if (s.isEmpty()) return false
        val readable = s.count { c ->
            c.code in 0x20..0x7E ||
                c.code in 0x4E00..0x9FFF ||
                c.code in 0x3000..0x303F ||
                c.code in 0xFF00..0xFFEF ||
                c == '\n' || c == '\t'
        }
        if (readable.toDouble() / s.length < 0.6) return false
        val structural = listOf("%PDF", "endobj", "endstream", "/Type", "/Length")
        return structural.none { s.contains(it) }
    }

    private fun usable(text: String): Boolean =
        text.length >= 80 && isMostlyReadable(text)

    // ------------------------------------------------------------------
    // 一、垃圾必须被判为不可用（这是真机翻车的那一类）
    // ------------------------------------------------------------------

    @Test
    fun `压缩流垃圾即使很长也必须判为不可用`() {
        // 真机收到的就是这种东西：长度够长，但全是控制字符
        val garbage = (1..500).map { (it * 7 % 256).toChar() }.joinToString("")
        assertTrue("长度确实超过 80", garbage.length >= 80)
        assertFalse("但不能当可用文本", usable(garbage))
    }

    @Test
    fun `含结构残留的文本必须判为不可用`() {
        // 这些词出现在"正文"里，说明我们取到的是文件结构而不是内容
        val withStructural = "%PDF-1.7\n" + "正文内容".repeat(30) + "\nendobj"
        assertFalse(usable(withStructural))
    }

    @Test
    fun `endstream 残留也算结构`() {
        assertFalse(isMostlyReadable("一些正常文字".repeat(10) + "endstream"))
    }

    @Test
    fun `slash Type 残留也算结构`() {
        assertFalse(isMostlyReadable("一些正常文字".repeat(10) + "/Type /Page"))
    }

    // ------------------------------------------------------------------
    // 二、真实文本要判为可用
    // ------------------------------------------------------------------

    @Test
    fun `正常中文正文可用`() {
        val t = "这是一份作业的正文内容，讲了质谱分析的基本原理。" +
            "质谱分析是一种测量离子质荷比的分析方法，广泛应用于化学和生物学研究。" +
            "它通过电离样品分子，使其成为带电离子，然后利用电磁场分离不同质荷比的离子。"
        assertTrue("长度=" + t.length, usable(t))
    }

    @Test
    fun `正常英文正文可用`() {
        val t = "This assignment covers the basic principles of mass spectrometry. " +
            "Mass spectrometry measures the mass-to-charge ratio of ions. " +
            "It is widely used in chemistry and biology for analyzing samples."
        assertTrue(usable(t))
    }

    @Test
    fun `中英混排可用`() {
        val t = "质谱分析 Mass Spectrometry 是化学分析的核心工具。它的基本原理是 " +
            "ionize the sample then separate by m/z ratio。"
        assertTrue(usable(t))
    }

    // ------------------------------------------------------------------
    // 三、长度门槛
    // ------------------------------------------------------------------

    @Test
    fun `太短的文本即便干净也走渲染`() {
        // 一页 A4 正文通常 500+ 字；只有几十个字说明几乎没取到东西
        assertFalse("只有几个字不能算成功", usable("第一页"))
    }

    @Test
    fun `空文本不可用`() {
        assertFalse(usable(""))
    }

    // ------------------------------------------------------------------
    // 四、调用点契约
    // ------------------------------------------------------------------

    @Test
    fun `PdfExtractor 必须同时检查长度与可读性`() {
        val src = File("src/main/java/com/chatmobile/attachments/PdfExtractor.kt").readText()
        assertTrue("必须有 MIN_USABLE_TEXT 长度门槛", src.contains("MIN_USABLE_TEXT"))
        assertTrue("必须检查可读性（不能只看长度）", src.contains("isMostlyReadable"))
        assertTrue("必须检测结构残留", src.contains("endobj"))
        assertTrue(
            "长度与可读性必须**同时**满足才不发图（&& 而不是 ||）",
            src.contains("text.length >= MIN_USABLE_TEXT && isMostlyReadable(text)"),
        )
    }

    @Test
    fun `两条路都没产出时必须如实报错 不能静默发空`() {
        val src = File("src/main/java/com/chatmobile/attachments/AttachmentReader.kt").readText()
        assertTrue(
            "必须检查 text 与 pageImages 都为空的情况",
            src.contains("pdf.text.isBlank() && pdf.pageImages.isEmpty()"),
        )
        assertTrue("必须给出替代办法", src.contains("另存为纯文本"))
    }
}
