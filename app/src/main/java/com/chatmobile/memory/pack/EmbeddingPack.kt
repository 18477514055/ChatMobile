package com.chatmobile.memory.pack

import java.io.File
import java.security.MessageDigest

/**
 * 内置向量模型包的定义（下载式增强包）。
 *
 * ## 为什么是"下载"而不是"塞进 APK"
 *
 * 模型 22.8 MB + 运行时 ≈ 30 MB。塞进 APK 会让**所有**用户为少数开启向量的人付包体代价，
 * 而且应用商店对包体增长很敏感。下载式则：不装不占、装了才用、坏了能删。
 *
 * ## 完整性
 *
 * 模型是 int8 ONNX（HF `Xenova/bge-small-zh-v1.5` 转档），官方给了 LFS 的 sha256，
 * 直接校验。词表只有 109 KB（非 LFS，仓库 API 不给 sha256）⇒ 用 **大小 + 首行** 校验，
 * 不编一个假哈希。
 */
object EmbeddingPack {

    const val ID = "bge-small-zh-v1.5-int8"
    const val DISPLAY_NAME = "bge-small-zh-v1.5（512 维）"

    const val MODEL_FILE = "model_int8.onnx"
    const val VOCAB_FILE = "vocab.txt"

    const val MODEL_SIZE = 23_903_394L
    /** HF LFS sha256（不是 git blob id）。 */
    const val MODEL_SHA256 = "b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5"

    const val VOCAB_SIZE = 109_540L
    /** 词表首行；用来确认下到的不是错误页 / 空文件（没有官方 sha256 可用）。 */
    const val VOCAB_FIRST_LINE = "[PAD]"

    /**
     * 下载源，按顺序尝试。
     *
     * ★ 国内网络直连 huggingface.co 经常不通，`hf-mirror.com` 是社区镜像，
     *   两个都列着、失败自动换下一个 —— 比写死一个源然后在用户机器上 404 好。
     */
    private const val HF = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main"
    private const val MIRROR = "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main"

    val MODEL_URLS: List<String> = listOf("$MIRROR/onnx/$MODEL_FILE", "$HF/onnx/$MODEL_FILE")
    val VOCAB_URLS: List<String> = listOf("$MIRROR/$VOCAB_FILE", "$HF/$VOCAB_FILE")

    fun dir(filesDir: File): File = File(filesDir, "models/$ID")

    fun modelFile(filesDir: File): File = File(dir(filesDir), MODEL_FILE)

    fun vocabFile(filesDir: File): File = File(dir(filesDir), VOCAB_FILE)

    /** 两个文件都在、大小对、模型哈希对 ⇒ 才算装好。 */
    fun verify(filesDir: File): Verdict {
        val model = modelFile(filesDir)
        val vocab = vocabFile(filesDir)
        if (!model.isFile || !vocab.isFile) return Verdict.Missing
        if (model.length() != MODEL_SIZE) return Verdict.Corrupt("模型大小不符")
        if (vocab.length() != VOCAB_SIZE) return Verdict.Corrupt("词表大小不符")
        if (!vocab.useLines { it.firstOrNull() }.orEmpty().startsWith(VOCAB_FIRST_LINE)) {
            return Verdict.Corrupt("词表首行不是 [PAD]")
        }
        if (!sha256(model).equals(MODEL_SHA256, ignoreCase = true)) {
            return Verdict.Corrupt("模型哈希不符")
        }
        return Verdict.Ready
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface Verdict {
        data object Missing : Verdict
        data object Ready : Verdict
        data class Corrupt(val reason: String) : Verdict
    }
}
