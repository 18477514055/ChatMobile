package com.chatmobile.attachments

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.zip.Inflater

/**
 * 从 PDF 里提取内容。
 *
 * ## ★ 为什么 PDF 要走"两条路"
 *
 * PDF 的正文是**压缩过的内容流**（`stream ... endstream` + Flate 压缩），
 * 解出来是一串 `Tj` / `TJ` 操作符加字符。有两条路：
 *
 * | 路线 | 得到 | 代价 |
 * |---|---|---|
 * | 解析文本流 | 真正的文字 | 字体子集 / 编码表 / 字符映射，中文 PDF 经常只给 glyph id |
 * | **渲染成图** | 模型"看见"页面 | 一顿一页，token 贵，但**几乎不会失败** |
 *
 * ## ★★★ 所以这里**两条都走**，且顺序是有意的
 *
 * 1. **先试文本流**：成功的话又准又便宜。
 * 2. **文本流失败就渲染成图**：pdfium（Android 自带 `PdfRenderer`）几乎什么 PDF 都能出图，
 *    模型本身支持看图，所以这条路是可靠兜底。
 *
 * ★ 只做文本提取是不够的：中文 PDF 用子集字体时，文本流里是 `(1)` `(2)` 这种
 *   glyph 索引，直接发出去等于发垃圾。而"渲染成图"对任何 PDF 都成立 ——
 *   这正是"宁可多花点 token，也不要给模型一堆看不懂的东西"。
 *
 * ★ 渲染有上限（页数 / 分辨率）：一个 300 页的 PDF 全渲染会吃光内存和 token。
 *   超出的部分**明确告知**用户"只发了前 N 页"。
 */
class PdfExtractor(
    /** 最多渲染多少页。超过就截断并在正文里说明。 */
    private val maxPages: Int = 8,
    /** 渲染 DPI。150 足够模型读清正文，再高只是浪费。 */
    private val renderDpi: Int = 150,
) {

    /** 提取结果：要么是文本，要么是若干页图片，也可能两者都有。 */
    data class Result(
        /** 文本路线提取到的正文；可能为空。 */
        val text: String,
        /** 渲染出来的页面（PNG 字节），按页序。 */
        val pageImages: List<ByteArray>,
        /** 这份 PDF 一共多少页（用于告诉用户截断了没有）。 */
        val totalPages: Int,
    ) {
        val isEmpty: Boolean get() = text.isBlank() && pageImages.isEmpty()
    }

    /**
     * 提取。
     *
     * ★ **永不抛异常**：损坏的 PDF 返回 null，由调用方如实报"读不出来"。
     *
     * ★★★ 这里的判据是这一版最重要的修正（真机踩出来的）：
     *
     *   上一版是「文本长度 ≥ 80 就算成功，不渲染」。真机结果：
     *   模型收到的是**一堆原始压缩字节**，它如实回答"这份 PDF 我读不了，
     *   客户端发过来的不是文字而是压缩字节"。
     *
     *   根因是**文本提取根本不可信**：PDF 的内容流用 Flate 压缩，
     *   我自己写的解压/取串逻辑只能处理最简单的情况；稍微复杂一点的
     *   PDF（换行编码、多段流、对象流压缩）解出来就是二进制垃圾，
     *   而垃圾**长度远超 80** ⇒ 阈值判据把它当成功 ⇒ 渲染兜底永远不触发。
     *
     *   ⇒ 改成**两个条件同时满足才算文本可用**：
     *     ① 长度够（≥ 80）
     *     ② **可读字符占比够高**（不是控制字符、不是乱码）
     *   只要有一条不满足，就走渲染 —— 渲染是可靠的，模型能直接看图。
     */
    fun extract(file: File): Result? = runCatching {
        val rawText = runCatching { extractTextStreams(file.readBytes()) }.getOrDefault("")
        val text = cleanPdfText(rawText)
        val usable = text.length >= MIN_USABLE_TEXT && isMostlyReadable(text)

        // 文本不可信 ⇒ 渲染成图（这条路几乎不会失败）
        val pageImages = if (usable) emptyList() else renderPages(file)

        val total = pageCount(file)
        Result(text = if (usable) text else "", pageImages = pageImages, totalPages = total)
    }.getOrNull()

    /**
     * 文本是不是"人话"。
     *
     * ★ 判据比 [looksLikeText] 宽松（那是逐串判断），这里看整段的整体质量：
     *   - 可读字符（ASCII 可见 + 中日韩 + 常见标点）占比 ≥ 60%
     *   - 不出现大量 `%PDF` / `obj` / `stream` 这类**结构残留**——
     *     它们出现在正文里，说明我们取到的是文件结构而不是内容。
     */
    private fun isMostlyReadable(s: String): Boolean {
        if (s.isEmpty()) return false
        val readable = s.count { c ->
            c.code in 0x20..0x7E ||
                c.code in 0x4E00..0x9FFF ||   // CJK 统一汉字
                c.code in 0x3000..0x303F ||   // 中文标点
                c.code in 0xFF00..0xFFEF ||   // 全角
                c == '\n' || c == '\t'
        }
        if (readable.toDouble() / s.length < 0.6) return false
        // 结构残留检测：这些词出现在"正文"里就是取错了东西
        val structural = listOf("%PDF", "endobj", "endstream", "/Type", "/Length")
        val hits = structural.count { s.contains(it) }
        return hits == 0
    }

    /**
     * 从这个 PDF 抠出所有 `Tj` / `TJ` 文本。
     *
     * ★ 这是**尽力而为**的实现，不是完整 PDF 解析器：
     *   只处理 Flate 压缩流里的字面量字符串 `(...)`，不处理字体编码映射。
     *   正因为不完整，所以下面才有"渲染成图"的兜底 —— 两者是配套的。
     */
    private fun extractTextStreams(bytes: ByteArray): String {
        val out = StringBuilder()
        var idx = 0
        while (true) {
            val s = indexOf(bytes, "stream".toByteArray(), idx)
            if (s < 0) break
            var start = s + "stream".length
            // 跳过 stream 后的 CRLF / LF
            if (start < bytes.size && bytes[start] == 13.toByte()) start++
            if (start < bytes.size && bytes[start] == 10.toByte()) start++

            val e = indexOf(bytes, "endstream".toByteArray(), start)
            if (e < 0) break
            val chunk = bytes.copyOfRange(start, e)
            idx = e + "endstream".length

            // 尝试 Flate 解压；失败就用原始字节（有些 PDF 不压缩）
            val plain = runCatching {
                val inf = Inflater()
                inf.setInput(chunk)
                val buf = ByteArray(maxOf(chunk.size * 8, 8192))
                val n = inf.inflate(buf)
                inf.end()
                if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else null
            }.getOrNull() ?: String(chunk, Charsets.ISO_8859_1)

            extractLiteralStrings(plain, out)
        }
        return out.toString()
    }

    /** 从内容流里抠 `(text) Tj` 与 `[(a)(b)] TJ`。 */
    private fun extractLiteralStrings(plain: String, out: StringBuilder) {
        var i = 0
        while (i < plain.length) {
            if (plain[i] == '(') {
                var j = i + 1
                val sb = StringBuilder()
                while (j < plain.length && plain[j] != ')') {
                    // 处理转义
                    if (plain[j] == '\\' && j + 1 < plain.length) {
                        when (val nx = plain[j + 1]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b', 'f' -> Unit
                            '(', ')', '\\' -> sb.append(nx)
                            else -> sb.append(nx)
                        }
                        j += 2
                    } else {
                        sb.append(plain[j])
                        j++
                    }
                }
                // ★ 只收"看起来像文字"的串：纯字节噪声收进来只会污染上下文
                val t = sb.toString()
                if (t.isNotBlank() && looksLikeText(t)) out.append(t)
                i = j + 1
            } else if (plain.startsWith("Tj", i) || plain.startsWith("TJ", i)) {
                out.append('\n')
                i += 2
            } else {
                i++
            }
        }
    }

    /**
     * 是不是"像文字"。
     *
     * ★ 判据：可打印字符占比够高，且**不是**一堆控制字符。
     *   子集字体的 glyph 索引解出来是 `\u0001\u0002` 这类东西 ——
     *   发出去模型只会说"我看不懂"。宁可走渲染。
     */
    private fun looksLikeText(s: String): Boolean {
        if (s.length < 2) return false
        val printable = s.count { it.code in 0x20..0x7E || it.code > 0xA0 }
        return printable.toDouble() / s.length >= 0.8
    }

    /** 清掉 PDF 文本流里常见的排版垃圾。 */
    private fun cleanPdfText(raw: String): String =
        raw.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** 用系统的 PdfRenderer 把页面渲染成 PNG 字节。 */
    private fun renderPages(file: File): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val n = minOf(renderer.pageCount, maxPages)
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                try {
                    // A4 @150dpi ≈ 1240×1754，足够模型读正文
                    val ratio = renderDpi / 72.0
                    val w = (page.width * ratio).toInt().coerceAtLeast(1)
                    val h = (page.height * ratio).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // ★ 必须填白底：ARGB_8888 默认全透明，模型看到的会是黑底
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    bmp.recycle()
                    out += bos.toByteArray()
                } finally {
                    page.close()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "PDF 渲染失败", e)
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        return out
    }

    private fun pageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }.getOrDefault(0)

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val TAG = "PdfExtractor"

        /**
         * 文本至少要有这么多字，才算"真的抠到正文了"。
         *
         * ★ 80 是个经验下限：一页 A4 正文通常 500+ 字，
         *   而压缩垃圾动辄上万 —— 这条只挡"几乎没取到东西"的情况，
         *   真正的把关在 [isMostlyReadable]。
         */
        const val MIN_USABLE_TEXT = 80
    }
}
