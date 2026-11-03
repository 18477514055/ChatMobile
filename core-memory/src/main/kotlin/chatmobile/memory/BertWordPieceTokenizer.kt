package chatmobile.memory

/**
 * BERT WordPiece 分词器（够 `bge-small-zh-v1.5` 用）。
 *
 * ## 为什么自己写，而不是带 `tokenizer.json`
 *
 * 官方的 `tokenizer.json` 是 439 KB 的通用格式（含 normalizer / pre_tokenizer /
 * post_processor 全套规则），在 Android 上要么引 HuggingFace tokenizers 的 native 库，
 * 要么写一个大而全的解释器 —— 两者都远超"把中文句子变成 id"这件事的实际需要。
 *
 * 这个模型是 **BertTokenizer**：`do_lower_case=false`、`tokenize_chinese_chars=true`
 * （见 HF `tokenizer_config.json`），规则就三条：
 *   1. 中文（CJK）按**单字**切；
 *   2. 其余字符按"字母数字连续段 / 单个标点"切；
 *   3. 过长的词用 WordPiece 贪心最长匹配，匹配不到退 `[UNK]`。
 *
 * ★ 只实现**编码**，不实现解码 —— 我们只把文本送进模型，从不把 id 还原成文本。
 */
class BertWordPieceTokenizer(
    vocab: List<String>,
    private val maxLength: Int = 512,
) {
    private val idOf: Map<String, Int> = vocab.withIndex().associate { (i, t) -> t to i }

    val padId: Int = idOf["[PAD]"] ?: 0
    val unkId: Int = idOf["[UNK]"] ?: 100
    val clsId: Int = idOf["[CLS]"] ?: 101
    val sepId: Int = idOf["[SEP]"] ?: 102

    /** 最长 20 个字符的 WordPiece 子词（BERT 默认值）。 */
    private val maxCharsPerWord = 20

    fun encode(text: String): LongArray {
        val pieces = mutableListOf<Int>()
        pieces += clsId
        for (token in basicTokens(text)) {
            pieces += wordPiece(token)
            if (pieces.size >= maxLength - 1) break
        }
        pieces += sepId
        return LongArray(pieces.size) { pieces[it].toLong() }
    }

    /** 批量编码时按最长序列补齐（模型需要矩形输入）。 */
    fun encodeBatch(texts: List<String>): Pair<Array<LongArray>, Array<LongArray>> {
        val encoded = texts.map { encode(it) }
        val maxLen = encoded.maxOfOrNull { it.size } ?: 2
        val ids = Array(texts.size) { i ->
            LongArray(maxLen) { j -> if (j < encoded[i].size) encoded[i][j] else padId.toLong() }
        }
        val mask = Array(texts.size) { i ->
            LongArray(maxLen) { j -> if (j < encoded[i].size) 1L else 0L }
        }
        return ids to mask
    }

    // ---------------------------------------------------------------
    // 基础分词
    // ---------------------------------------------------------------

    private fun basicTokens(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out += buf.toString()
                buf.clear()
            }
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flush()
                    out += ch.toString()
                }
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> {
                    flush()
                    out += ch.toString()
                }
                else -> buf.append(ch)
            }
        }
        flush()
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF ||
            c in 0xF900..0xFAFF || c in 0x3040..0x30FF ||
            c in 0xAC00..0xD7AF
    }

    private fun isPunctuation(ch: Char): Boolean {
        val c = ch.code
        return c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126 ||
            c in 0x3000..0x303F || c in 0xFF00..0xFF65
    }

    /**
     * WordPiece 贪心最长匹配。
     *
     * 例：`ChatMobile` 不在词表 ⇒ 拆成 `chat` + `##mobile`（若两者在表里）。
     */
    private fun wordPiece(token: String): List<Int> {
        val direct = idOf[token]
        if (direct != null) return listOf(direct)

        val ids = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            var end = minOf(token.length, start + maxCharsPerWord)
            var matched = -1
            while (end > start) {
                val piece = if (start == 0) token.substring(start, end)
                else "##" + token.substring(start, end)
                val id = idOf[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched < 0) return listOf(unkId)
            ids += matched
            start = end
        }
        return ids
    }
}
