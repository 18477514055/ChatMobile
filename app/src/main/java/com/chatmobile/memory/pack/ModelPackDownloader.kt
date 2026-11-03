package com.chatmobile.memory.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载式增强包：把模型与词表拉到应用私有目录，校验后可用。
 *
 * ## 设计取舍
 *
 * - **先写 `.part` 再改名**：中途失败不会留下一个"看着像装好了"的半截文件。
 * - **多源回退**：`hf-mirror.com` 优先，不行再 `huggingface.co`。国内直连经常不通，
 *   写死一个源会让用户以为"这功能坏了"。
 * - **失败即清理**：任何一步失败都把临时文件删掉，磁盘不残留 23MB 垃圾。
 * - **不做断点续传**：24MB 在手机上重下一次是可接受的；续传会引入 Range/ETag 的
 *   一整套边界情况，收益不值这个复杂度（真要做，`verify` 已经能识别残缺文件）。
 */
class ModelPackDownloader(
    private val filesDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * @param onProgress 0f..1f（按两个文件的总字节数加权）
     * @return 成功时 [EmbeddingPack.verify] 已通过
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = EmbeddingPack.dir(filesDir)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.failure(IllegalStateException("无法创建目录"))
        }

        val total = EmbeddingPack.MODEL_SIZE + EmbeddingPack.VOCAB_SIZE
        var doneSoFar = 0L

        runCatching {
            fetch(
                urls = EmbeddingPack.MODEL_URLS,
                target = EmbeddingPack.modelFile(filesDir),
                expectedSize = EmbeddingPack.MODEL_SIZE,
                onBytes = { n -> onProgress(((doneSoFar + n).toFloat() / total).coerceIn(0f, 1f)) },
            )
            doneSoFar = EmbeddingPack.MODEL_SIZE
            fetch(
                urls = EmbeddingPack.VOCAB_URLS,
                target = EmbeddingPack.vocabFile(filesDir),
                expectedSize = EmbeddingPack.VOCAB_SIZE,
                onBytes = { n -> onProgress(((doneSoFar + n).toFloat() / total).coerceIn(0f, 1f)) },
            )
        }.getOrElse { e ->
            cleanup(filesDir)
            return@withContext Result.failure(e)
        }

        when (val v = EmbeddingPack.verify(filesDir)) {
            is EmbeddingPack.Verdict.Ready -> Result.success(Unit)
            is EmbeddingPack.Verdict.Corrupt -> {
                cleanup(filesDir)
                Result.failure(IllegalStateException("校验失败：${v.reason}"))
            }
            EmbeddingPack.Verdict.Missing -> Result.failure(IllegalStateException("下载后文件缺失"))
        }
    }

    fun cleanup(filesDir: File) {
        EmbeddingPack.modelFile(filesDir).delete()
        EmbeddingPack.vocabFile(filesDir).delete()
        File(EmbeddingPack.dir(filesDir), EmbeddingPack.MODEL_FILE + ".part").delete()
        File(EmbeddingPack.dir(filesDir), EmbeddingPack.VOCAB_FILE + ".part").delete()
        EmbeddingPack.dir(filesDir).takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    }

    private fun fetch(
        urls: List<String>,
        target: File,
        expectedSize: Long,
        onBytes: (Long) -> Unit,
    ) {
        var lastError: Throwable? = null
        for (url in urls) {
            val part = File(target.parentFile, target.name + ".part")
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("空响应")
                    val length = body.contentLength()
                    if (length > 0 && length != expectedSize) {
                        error("大小不符：期望 $expectedSize，实际 $length")
                    }
                    var written = 0L
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                onBytes(written)
                            }
                        }
                    }
                    if (written != expectedSize) error("下载不完整：$written / $expectedSize")
                }
                if (!part.renameTo(target)) error("重命名失败")
                return
            } catch (e: Throwable) {
                lastError = e
                part.delete()
            }
        }
        throw lastError ?: IllegalStateException("所有下载源都失败")
    }
}
