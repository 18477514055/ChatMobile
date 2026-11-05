package com.chatmobile.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.chatmobile.data.MessageAttachmentMeta
import com.chatmobile.net.AttachmentPolicy
import com.chatmobile.net.ChatAttachment
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把用户选中的附件**真的读出来**，变成能进请求的 [ChatAttachment]。
 *
 * ## ★★★ 这一环之前根本不存在（用户报的 bug）
 *
 * > 「现在无法正常上传照片和文件」
 *
 * 上一版 `onAttachResult` 只是往输入框里写了一行「已选图片：xxx.jpg」，
 * 然后就**再没有任何代码去读那个文件**。看着像"选好了"，实际什么都没发出去。
 * 这是本项目最典型的失败形态：**UI 上有反馈，链路上是断的**。
 *
 * ## 三个必须由这一层做的脏活
 *
 * 1. **图片要压缩**。手机原图 4000×3000 的 JPEG 是 3–12MB，
 *    base64 后 ×1.37 ⇒ 单张 4–16MB。不压缩的话：请求体巨大、超时、
 *    而且主流视觉模型本来就会把长边降到 ~2000px，多传的字节纯粹浪费。
 * 2. **相册的图片要复制进私有目录**。`content://` URI 的读权限是**临时的**
 *    （除非显式 takePersistableUriPermission），下次冷启动就失效；
 *    而我们的设计是"落库只存元数据、发送时才读字节" ——
 *    存一个下次读不到的 URI 等于把图片弄丢。
 * 3. **文本文件要判编码并截断**。GBK 强行按 UTF-8 读会得到一堆替换字符，
 *    而模型会认真地去解读那些乱码。
 */
class AttachmentReader(private val context: Context) {

    /** PDF 提取器。渲染页数 / DPI 见 [PdfExtractor] 的注释。 */
    private val pdfExtractor = PdfExtractor()

    /**
     * 读出所有附件。
     *
     * ★ **永不抛异常**：任何一张图失败都只是"这一张没发出去"，
     *   不该让整条消息发不出去。失败项会进 [ReadResult.failures]，
     *   由调用方拼成一句进正文，让模型和用户都知道少了什么。
     */
    fun read(metas: List<MessageAttachmentMeta>): ReadResult {
        val images = mutableListOf<ChatAttachment>()
        val texts = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()

        for (meta in metas) {
            try {
                val ext = AttachmentPolicy.extensionOf(meta.name)

                // ★★★ PDF 单独一条路：它可能同时产出正文与页面图。
                //   判据放在最前面 —— PDF 的 MIME 是 application/pdf，
                //   既不是 image/* 也不是文本，走下面两个分支都会漏掉。
                if (ext in AttachmentPolicy.PDF_EXTENSIONS) {
                    val pdf = readPdf(meta)
                    if (pdf == null) {
                        failures += "「" + meta.name + "」读不出来（可能是加密或损坏的 PDF）"
                        continue
                    }
                    if (pdf.text.isNotBlank()) texts += meta.name to pdf.text
                    // ★ 渲染出来的页面按「第 N 页」命名，模型才知道哪张是哪页
                    pdf.pageImages.forEachIndexed { i, bytes ->
                        images += ChatAttachment(
                            kind = ChatAttachment.Kind.Image,
                            name = meta.name + " 第" + (i + 1) + "页",
                            mimeType = "image/jpeg",
                            // 页面图已经是 PNG 字节，直接 base64（不再二次压缩，
                            // 文字边缘重编码成 JPEG 会糊）
                            imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            sizeBytes = bytes.size.toLong(),
                        )
                    }
                    // ★★★ 两条路都没产出内容时必须**如实说**，不能静默发空。
                    //   上一版真机就栽在这：文本提取吐了一堆压缩垃圾（看着非空），
                    //   渲染兜底没触发，模型收到乱码后回"我读不了这份 PDF"。
                    if (pdf.text.isBlank() && pdf.pageImages.isEmpty()) {
                        failures += "「" + meta.name + "」没能提取出内容" +
                            (if (pdf.totalPages > 0) "（共 " + pdf.totalPages + " 页）" else "") +
                            "，建议用能选中文字的阅读器另存为纯文本再发"
                    }
                    if (pdf.totalPages > pdf.pageImages.size && pdf.pageImages.isNotEmpty()) {
                        failures += "「" + meta.name + "」共 " + pdf.totalPages +
                            " 页，只发了前 " + pdf.pageImages.size + " 页"
                    }
                    continue
                }

                when (meta.kind) {
                    MessageAttachmentMeta.KIND_IMAGE -> {
                        val b64 = readImageAsBase64(meta)
                        if (b64 == null) {
                            failures += "「" + meta.name + "」读不到（文件可能已被删除）"
                        } else {
                            images += ChatAttachment(
                                kind = ChatAttachment.Kind.Image,
                                name = meta.name,
                                mimeType = b64.second,
                                imageBase64 = b64.first,
                                sizeBytes = meta.sizeBytes,
                            )
                        }
                    }
                    else -> {
                        val text = readTextFile(meta)
                        if (text == null) {
                            failures += AttachmentPolicy.unsupportedBinaryHint(meta.name)
                        } else {
                            texts += meta.name to text
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "读取附件失败：" + meta.name, e)
                failures += "「" + meta.name + "」读取失败：" + (e.message ?: e::class.simpleName)
            }
        }
        return ReadResult(images, texts, failures)
    }

    /**
     * 读图片 → (裸 base64, 实际 MIME)。
     *
     * ★ 返回的实际 MIME 是 **JPEG**（因为我们重编码了），不是原文件的 MIME。
     *   声明成 `image/png` 却发 JPEG 字节，某些服务端会解码失败。
     */
    private fun readImageAsBase64(meta: MessageAttachmentMeta): Pair<String, String>? {
        val bytes = openBytes(meta) ?: return null

        // ① 先只读**尺寸**，不解码整张图 —— 一张 8000px 的全景图全解码会 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // ② 按最长边算采样率（inSampleSize 只能是 2 的幂，先粗降到目标附近）
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= AttachmentPolicy.MAX_IMAGE_DIMENSION) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        // ③ 再精确缩放到最长边 = MAX_IMAGE_DIMENSION（粗采样后可能还偏大）
        val scaled = scaleToMax(decoded, AttachmentPolicy.MAX_IMAGE_DIMENSION)
        if (scaled !== decoded) decoded.recycle()

        // ④ 统一重编码成 JPEG（质量 85）：PNG 的截图重编成 JPEG 体积能小一个数量级，
        //    而文字截图在 85 的质量下肉眼无损。
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, AttachmentPolicy.JPEG_QUALITY, out)
        scaled.recycle()

        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return b64 to "image/jpeg"
    }

    /** 等比缩到最长边 = [max]；本来就不超则**原样返回同一个对象**（调用方据此判断要不要 recycle）。 */
    private fun scaleToMax(src: Bitmap, max: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= max) return src
        val ratio = max.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 读文本文件；不是文本类就返回 null。
     *
     * ★ 编码判定顺序：**UTF-8（严格）→ GBK → 放弃**。
     *   - 用 `CharsetDecoder` 的 REPORT 模式做**严格** UTF-8 校验：
     *     `String(bytes, UTF_8)` 遇到非法字节会静默塞 U+FFFD，
     *     于是"这段到底是不是 UTF-8"就永远查不出来了。
     *   - GBK 兜底是因为国内用户从 Windows 记事本存出来的 `.txt` 大量是 GBK。
     */
    private fun readTextFile(meta: MessageAttachmentMeta): String? {
        val ext = AttachmentPolicy.extensionOf(meta.name)
        val bytes = openBytes(meta) ?: return null

        // ★★★ 第一步：Office 三件套 —— 本地解 ZIP 取文字（零依赖）
        //
        //   用户原话：「我经常上传其他格式的，比如 word、PPT、PDF 之类的」。
        //   这条路完全本地、不联网、不需要 POI。
        if (OfficeTextExtractor.supports(ext)) {
            val got = OfficeTextExtractor.extract(ext, bytes)
            if (got != null) return AttachmentPolicy.truncateText(got.text)
            // 解不开（损坏的包 / 加密的 Office 文档）⇒ 返回 null，由调用方如实报错
            return null
        }

        // ★★★ 第二步：纯文本
        if (!AttachmentPolicy.isTextLike(meta.name, meta.mimeType)) return null
        // 太大就不必整份读进来 —— 反正后面要截断，多读只是白占内存
        if (bytes.size > MAX_TEXT_BYTES) {
            // ★ 解码失败（既不是 UTF-8 也不是 GBK）就当作不可读 ——
            //   硬塞一堆 U+FFFD 进去，模型会认真地去"解读"那些乱码。
            val head = decodeBestEffort(bytes.copyOf(MAX_TEXT_BYTES)) ?: return null
            return AttachmentPolicy.truncateText(head)
        }
        val raw = decodeBestEffort(bytes) ?: return null
        return AttachmentPolicy.truncateText(raw)
    }

    /**
     * 读 PDF。
     *
     * ★ 与普通文件不同，PDF 可能产出**正文 + 若干页图片**两样东西：
     *   文本流抠不出来时（中文子集字体的常见情况）就把页面渲染成图，
     *   让模型直接"看" —— 这条路几乎不会失败。
     */
    private fun readPdf(meta: MessageAttachmentMeta): PdfReadResult? {
        val src = meta.source
        if (src.isBlank()) return null
        // PdfRenderer 需要真实的文件描述符 ⇒ content:// 要先落到临时文件
        val file = materialize(meta) ?: return null
        return try {
            val r = pdfExtractor.extract(file) ?: return null
            if (r.isEmpty) return null
            PdfReadResult(text = r.text, pageImages = r.pageImages, totalPages = r.totalPages)
        } finally {
            // ★ 只删我们**自己复制出来的**临时文件；用户私有目录里的原文件不能动
            if (file.absolutePath != src) runCatching { file.delete() }
        }
    }

    /** 把 `content://` 落到缓存目录，得到一个真实文件（PdfRenderer 只认 fd）。 */
    private fun materialize(meta: MessageAttachmentMeta): File? = runCatching {
        val src = meta.source
        if (!src.startsWith("content://")) {
            val f = File(src)
            return@runCatching if (f.exists()) f else null
        }
        val tmp = File(context.cacheDir, "pdf_" + System.currentTimeMillis() + ".pdf")
        context.contentResolver.openInputStream(Uri.parse(src))?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        } ?: return@runCatching null
        tmp
    }.getOrNull()

    private data class PdfReadResult(
        val text: String,
        val pageImages: List<ByteArray>,
        val totalPages: Int,
    )

    private fun decodeBestEffort(bytes: ByteArray): String? {
        strictUtf8(bytes)?.let { return it }
        return runCatching { String(bytes, charset("GBK")) }.getOrNull()
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = charset("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /**
     * 按元数据里记的来源打开字节。
     *
     * ★ 两种来源形态都要认：
     *   - `content://…` ⇒ 走 ContentResolver（SAF / 相册给的）
     *   - 绝对路径 ⇒ 直接读文件（我们自己私有目录里的）
     */
    private fun openBytes(meta: MessageAttachmentMeta): ByteArray? {
        val src = meta.source
        if (src.isBlank()) return null
        return runCatching {
            if (src.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(src))?.use { it.readBytes() }
            } else {
                val f = File(src)
                if (!f.exists() || !f.isFile) null else f.readBytes()
            }
        }.getOrNull()
    }

    /**
     * 一次读取的结果。
     *
     * ★ [imageTextHeader] 是把附件信息拼进正文的唯一入口 ——
     *   文本文件必须**进正文**（协议里没有 file 类型），
     *   而"有哪些图片"也要让模型知道文件名（否则它只会说"我看到一张图"）。
     */
    data class ReadResult(
        val images: List<ChatAttachment>,
        val textFiles: List<Pair<String, String>>,
        val failures: List<String>,
    ) {
        /** 有没有任何东西真的读出来了。 */
        val isEmpty: Boolean get() = images.isEmpty() && textFiles.isEmpty()

        /**
         * 拼进 user 消息正文的附件段。
         *
         * 形态：
         * ```
         * <attached_files>
         * [图片] a.jpg（模型可以直接看到它）
         * [文件] notes.md：
         * ```
         * …正文…
         * ```
         * </attached_files>
         * ```
         * ★ 用 XML 风格的包裹而不是纯文字：模型对「这块是附件内容」的边界
         *   感知明显更好，不会把文件正文当成用户说的话。
         */
        fun toPromptSection(): String {
            if (isEmpty && failures.isEmpty()) return ""
            return buildString {
                append("\n\n<attached_files>")
                for ((name, body) in textFiles) {
                    append("\n[文件] ").append(name).append("：\n```\n").append(body).append("\n```")
                }
                for (img in images) {
                    append("\n[图片] ").append(img.name).append("（已随本条消息发送，你可以直接看到它）")
                }
                for (f in failures) {
                    append("\n[未发送] ").append(f)
                }
                append("\n</attached_files>")
            }
        }
    }

    private companion object {
        const val TAG = "AttachmentReader"

        /**
         * 读文本文件时的内存上限。
         *
         * ★ 1MB 足够覆盖绝大多数代码/笔记/日志文件，
         *   而 [AttachmentPolicy.MAX_TEXT_CHARS] 只有 4 万字符（约 120KB UTF-8）——
         *   所以这个上限留了足够余量，又挡住"误选了个 500MB 日志"。
         */
        const val MAX_TEXT_BYTES = 1 * 1024 * 1024
    }
}
