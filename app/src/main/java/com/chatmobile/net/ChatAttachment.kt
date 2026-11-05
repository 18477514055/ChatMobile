package com.chatmobile.net

/**
 * 一条随消息发出的附件。
 *
 * ## ★★★ 为什么图片走 base64 而文本文件走正文
 *
 * OpenAI 兼容协议里，多模态消息的 `content` 不是字符串，而是一个**数组**：
 * ```json
 * "content": [
 *   {"type": "text",      "text": "这张图里是什么"},
 *   {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,/9j/4AA…"}}
 * ]
 * ```
 * 而**纯文本文件没有对应类型** —— 协议里没有 `{"type":"file"}`（那是 Anthropic/Google 的形态）。
 * ⇒ 文本文件只能**内联成一段 text**，这是协议决定的，不是取舍。
 *
 * 二进制文件（PDF / zip / apk…）连文本都不算，塞进去只会得到一堆乱码 token
 * ⇒ 明确拒绝并告诉用户，比发一堆垃圾更负责。
 *
 * ## ★ 为什么这里**只存内容**、不存路径
 *
 * 落库的是**元数据**（见 `data.MessageEntity.attachments`），真正的字节在发送那一刻
 * 才从私有目录读出来。理由：
 *   ① 一张 4MB 的照片 base64 后是 5.3MB，塞进 message 表会让库迅速膨胀到 GB 级；
 *   ② 每次读历史都要把整条消息的 base64 一起读出来 ⇒ 翻页会越来越卡；
 *   ③ 附件是"发出去那一刻的快照"，进请求即可，不需要长期随消息携带。
 */
data class ChatAttachment(
    val kind: Kind,
    /** 显示用文件名（含扩展名）。 */
    val name: String,
    val mimeType: String,
    /**
     * 图片：**已压缩过的** base64（不含 `data:` 前缀，调用方自己拼）。
     *
     * ★ 前缀由 [AttachmentContent] 统一拼，这里存裸 base64 —— 因为不同厂商
     *   对 `data:image/xxx;base64,` 里的 MIME 类型容忍度不同，集中一处好改。
     */
    val imageBase64: String? = null,
    /** 文本文件：正文。 */
    val text: String? = null,
    val sizeBytes: Long = 0L,
) {
    enum class Kind {
        /** 相册/相机来的图片 —— 能真正让模型"看见"。 */
        Image,

        /** 文本类文件 —— 内联成一段 text。 */
        File,
    }
}

/**
 * 附件相关的**纯策略** —— 不碰 Android、不碰 IO，因此可以被 JVM 单测完整覆盖。
 *
 * ★ 把这些规则抽出来是必须的：下面每一条都对应一个**真机/协议**上的硬事实，
 *   散落进 `ChatHost` 就再也测不了（本项目 L26：设置项存了≠生效）。
 */
object AttachmentPolicy {

    /**
     * 图片压缩后的**最长边**上限。
     *
     * ★ 1568 不是随手写的：OpenAI 视觉模型对长边超过约 2000px 的图会做下采样，
     *   再大只是浪费带宽与 token 而不增加信息量。压到 1568 既保住细节又不至于
     *   让 base64 巨大（一张手机照 4000×3000 → 1568×1176，base64 约 200–400KB）。
     */
    const val MAX_IMAGE_DIMENSION: Int = 1568

    /** 图片 JPEG 重编码质量。85 是"肉眼看不出损失"与体积之间的常见折中。 */
    const val JPEG_QUALITY: Int = 85

    /**
     * 单张图片**原始字节**的硬上限。超了直接拒绝并说明，不做"悄悄截断"。
     *
     * 32MB：常见手机原图 HEIC/JPEG 在 3–12MB，给到 32MB 足以容纳绝大多数；
     * 再大基本是全景图或 RAW，压缩后也未必有意义。
     */
    const val MAX_IMAGE_SOURCE_BYTES: Long = 32L * 1024 * 1024

    /**
     * 单个文本文件内联进提示词的**字符**上限。
     *
     * ★ 40000 字符 ≈ 12000 token（按 3 字符/token 粗估）。选这个数的理由：
     *   一次问答里用户可能同时贴几个文件，单文件再大就会把整个上下文吃掉，
     *   而用户真正想要的往往只是"这个文件里写了什么"。
     *   ★ **超限不是拒绝，是截断 + 明确告知** —— 文件能被读一部分
     *     比"完全不给读"有用得多。
     */
    const val MAX_TEXT_CHARS: Int = 40_000

    /**
     * 这些扩展名/ MIME 按**文本**读。
     *
     * ★ 为什么不只看 MIME：SAF 返回的 MIME 经常是 `application/octet-stream`
     *   （尤其是 `Documents/` 里的文件和某些文件管理器），只看 MIME 会把
     *   一堆 `.md` / `.json` 误判成二进制。扩展名更可靠，所以**两者任一命中即可**。
     */
    private val TEXT_EXTENSIONS: Set<String> = setOf(
        "txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg",
        "csv", "tsv", "log", "xml", "html", "htm", "css", "scss",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "m", "sh", "bat", "ps1", "sql",
        "gradle", "properties", "gitignore", "env",
    )

    private val TEXT_MIME_PREFIXES: List<String> = listOf(
        "text/",
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/javascript",
        "application/x-sh",
    )

    /** 取小写扩展名；没有扩展名返回空串。 */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * 需要**本地拆包**才能读出文字的格式（OOXML 三件套）。
     *
     * ★ 用户原话：「我经常上传其他格式的，比如 word、PPT、PDF 之类的」。
     *   上一版只放行纯文本，把这些全拒了 —— 那是偷懒，不是协议要求。
     */
    val OFFICE_EXTENSIONS: Set<String> = setOf("docx", "pptx", "xlsx")

    /** Office 的老二进制格式（97-2003）—— 不是 ZIP，零依赖读不了。 */
    val LEGACY_OFFICE_EXTENSIONS: Set<String> = setOf("doc", "ppt", "xls")

    /** PDF。 */
    val PDF_EXTENSIONS: Set<String> = setOf("pdf")

    /**
     * 这个文件能不能**取出内容**来发给模型。
     *
     * ★ 判据从"是不是纯文本"扩成了"能不能读出来"：
     *   纯文本直接读；docx/pptx/xlsx 本地解 ZIP；pdf 先抠文本、不行就渲染成图。
     *   只有**真的读不出**的（老 Office、压缩包、音视频）才拒绝。
     */
    fun isReadable(name: String, mimeType: String): Boolean {
        if (isTextLike(name, mimeType)) return true
        val ext = extensionOf(name)
        return ext in OFFICE_EXTENSIONS || ext in PDF_EXTENSIONS
    }

    /**
     * 这个文件是不是"取不出内容"的那一类。
     *
     * ★ 只有这里返回 true 才拒绝。拒绝时必须给出**替代办法**，
     *   否则用户会以为 App 坏了（见 [unsupportedBinaryHint]）。
     */
    fun isUnreadable(name: String, mimeType: String): Boolean {
        if (isReadable(name, mimeType)) return false
        // 图片由调用方单独处理，不走这条
        if (classify(name, mimeType) == ChatAttachment.Kind.Image) return false
        return true
    }

    /**
     * 这个文件能不能当文本读。
     *
     * ★ **扩展名或 MIME 任一命中就算文本** —— 见 [TEXT_EXTENSIONS] 的注释。
     */
    fun isTextLike(name: String, mimeType: String): Boolean {
        if (extensionOf(name) in TEXT_EXTENSIONS) return true
        val mt = mimeType.lowercase().substringBefore(';').trim()
        return TEXT_MIME_PREFIXES.any { mt.startsWith(it) }
    }

    /**
     * 这个附件该按什么类型处理。
     *
     * ★ 判据顺序很重要：**先看是不是图片**（MIME 以 `image/` 开头），
     *   再看是不是文本。一张叫 `photo.png.txt` 的怪文件应该被当图片
     *   （它内容确实是 PNG 字节，MIME 会说 image/png）。
     */
    fun classify(name: String, mimeType: String): ChatAttachment.Kind {
        val mt = mimeType.lowercase().substringBefore(';').trim()
        if (mt.startsWith("image/")) return ChatAttachment.Kind.Image
        // MIME 缺失/不认时，靠扩展名兜底 —— 相册/相机偶尔不给 MIME
        val ext = extensionOf(name)
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")) {
            return ChatAttachment.Kind.Image
        }
        return ChatAttachment.Kind.File
    }

    /**
     * 文本超限时的截断 + 说明。
     *
     * ★ 必须**明确写进正文**，不能悄悄截掉：
     *   否则模型会基于半截文件下结论，而用户以为它看完了全文 ——
     *   这是最会误导人的失败方式。
     */
    fun truncateText(text: String): String {
        if (text.length <= MAX_TEXT_CHARS) return text
        val kept = text.substring(0, MAX_TEXT_CHARS)
        val dropped = text.length - MAX_TEXT_CHARS
        return kept +
            "\n\n[文件过长，已截断：只提供了前 $MAX_TEXT_CHARS 字，后面还有约 $dropped 字未提供。]" +
            "\n[如果你需要后面的内容，请让用户把文件拆小或直接贴出关键部分。]"
    }

    /**
     * 真的读不出内容的文件，给用户看的话。
     *
     * ★ 三件事缺一不可：**是什么 / 为什么 / 那还能怎么办**。
     *   只说"不支持该文件"会让人以为 App 坏了。
     *
     * ★ 现在这条路**只覆盖真正读不了的**（老 Office、压缩包、音视频）——
     *   Word/PPT/Excel/PDF 已经全部支持了，不再落到这里。
     */
    fun unsupportedBinaryHint(name: String): String {
        val ext = extensionOf(name)
        return when {
            ext in LEGACY_OFFICE_EXTENSIONS ->
                "「" + name + "」是 Office 的老格式（97-2003），不是 ZIP 包，本地拆不开。" +
                    "用 Office / WPS 另存为 .docx / .pptx / .xlsx 就能发。"
            ext in setOf("zip", "rar", "7z", "tar", "gz") ->
                "「" + name + "」是压缩包，得先解压。把里面的文件单独发我。"
            else ->
                "「" + name + "」这种格式读不出文字内容。" +
                    "可以把它导出成 PDF / Word，或者截图发我。"
        }
    }
}
