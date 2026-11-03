package com.chatmobile.memory.pack

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import chatmobile.memory.BertWordPieceTokenizer
import chatmobile.memory.EmbeddingProvider
import java.io.File

/**
 * 本地向量推理：`bge-small-zh-v1.5` int8 ONNX + WordPiece 分词。
 *
 * ## 关键实现约束
 *
 * - **懒加载**：只有在真正用到向量时才创建 `OrtSession`（它会占几十 MB 并且有加载耗时）。
 * - **失败一次即封存**：加载/推理抛错后 `available=false`，不再重试——
 *   否则每次召回都去撞同一个错，界面卡、日志刷屏（这正是"坏模型拖垮好功能"的典型）。
 * - **CLS pooling + L2 归一化**：与模型训练时的用法一致（bge 的 `1_Pooling/config.json`
 *   是 `pooling_mode_cls_token=true`）。少做这一步，余弦会系统性偏高但排序仍错。
 * - **输入名以 session 实际声明为准**：不同导出可能只有 `input_ids`/`attention_mask`，
 *   也可能带 `token_type_ids`。写死三个会直接报错。
 */
class BgeSmallZhEmbeddingProvider(
    private val filesDir: File,
    private val maxTokens: Int = 256,
) : EmbeddingProvider {

    override val id: String = "onnx:${EmbeddingPack.ID}"
    override val dimensions: Int = 512

    @Volatile
    private var ready = false

    @Volatile
    private var failed = false

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: BertWordPieceTokenizer? = null
    private var inputNames: Set<String> = emptySet()

    override val available: Boolean
        get() = ready || (!failed && EmbeddingPack.verify(filesDir) is EmbeddingPack.Verdict.Ready)

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (ready) return true
        if (failed) return false

        val verdict = EmbeddingPack.verify(filesDir)
        if (verdict !is EmbeddingPack.Verdict.Ready) {
            failed = true
            return false
        }

        return runCatching {
            val vocab = EmbeddingPack.vocabFile(filesDir).readLines()
            val tk = BertWordPieceTokenizer(vocab, maxLength = maxTokens)
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(EmbeddingPack.modelFile(filesDir).absolutePath, opts)

            tokenizer = tk
            env = e
            session = s
            inputNames = s.inputNames
            ready = true
            true
        }.getOrElse { e ->
            android.util.Log.w("BgeEmbedding", "加载失败", e)
            failed = true
            false
        }
    }

    override fun embed(texts: List<String>): List<FloatArray>? {
        if (!ensureLoaded()) return null
        val tk = tokenizer ?: return null
        val e = env ?: return null
        val s = session ?: return null

        return runCatching {
            val (ids, mask) = tk.encodeBatch(texts)
            val idsTensor = OnnxTensor.createTensor(e, ids)
            val maskTensor = OnnxTensor.createTensor(e, mask)
            val typeTensor = if (inputNames.contains("token_type_ids")) {
                OnnxTensor.createTensor(e, Array(ids.size) { LongArray(ids[0].size) })
            } else {
                null
            }

            val feeds = HashMap<String, OnnxTensor>()
            feeds["input_ids"] = idsTensor
            feeds["attention_mask"] = maskTensor
            typeTensor?.let { feeds["token_type_ids"] = it }

            val vectors = s.run(feeds).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value
                when (raw) {
                    // [batch, seq, hidden] ⇒ 取每个样本的 CLS（位置 0）
                    is Array<*> -> raw.map { sample ->
                        when (sample) {
                            is Array<*> -> {
                                val cls = sample[0]
                                if (cls is FloatArray) cls.copyOf() else error("输出维度不认识")
                            }
                            is FloatArray -> sample.copyOf()
                            else -> error("输出维度不认识")
                        }
                    }
                    else -> error("输出不是数组")
                }
            }

            idsTensor.close()
            maskTensor.close()
            typeTensor?.close()

            vectors.map { normalize(it) }
        }.getOrElse { e ->
            android.util.Log.w("BgeEmbedding", "推理失败", e)
            failed = true
            null
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        if (norm <= 0.0) return v
        val inv = (1.0 / kotlin.math.sqrt(norm)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    fun close() {
        runCatching { session?.close() }
        ready = false
        session = null
    }
}
