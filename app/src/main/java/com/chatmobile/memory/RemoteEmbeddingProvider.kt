package com.chatmobile.memory

import chatmobile.memory.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程向量模型（OpenAI 兼容 `/embeddings`）。
 *
 * ## 它为什么在 `app` 而不是 `core-memory`
 *
 * `core-memory` 是**纯 JVM、零依赖**（没有 OkHttp、没有 Android）——
 * 那是它能被完整单测的前提。网络实现必须放在 app 层，
 * 通过 [EmbeddingProvider] 这个接口注入回去。
 *
 * ## 失败语义
 *
 * 任何失败（网络/格式/维度不一致）都返回 `null`，由 `EmbeddingRetriever` 自动退回字面检索。
 * **向量永远不是前置条件** —— 用户没配额、断网、模型下线，记忆功能都不受影响。
 */
class RemoteEmbeddingProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val embeddingsPath: String = "embeddings",
    private val expectedDimensions: Int = 0,
) : EmbeddingProvider {

    override val id: String = "remote:$model@$baseUrl"

    override val dimensions: Int get() = expectedDimensions

    override val available: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun url(): String {
        val base = baseUrl.trimEnd('/')
        val path = embeddingsPath.trimStart('/')
        return if (base.endsWith("/v1") && path.startsWith("v1/")) {
            "$base/${path.removePrefix("v1/")}"
        } else {
            "$base/$path"
        }
    }

    override fun embed(texts: List<String>): List<FloatArray>? {
        if (!available || texts.isEmpty()) return null
        return runCatching {
            // ★ 接口是同步的（core-memory 不依赖协程），这里用阻塞调用包在 IO 线程外。
            val body = JSONObject()
                .put("model", model)
                .put("input", JSONArray(texts))
                .toString()

            val request = Request.Builder()
                .url(url())
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body.string())
                val data = json.optJSONArray("data") ?: return null
                val out = ArrayList<FloatArray>(data.length())
                for (i in 0 until data.length()) {
                    val arr = data.getJSONObject(i).optJSONArray("embedding") ?: return null
                    val v = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    if (v.isEmpty()) return null
                    out += v
                }
                if (out.size != texts.size) return null
                val dim = out.first().size
                if (out.any { it.size != dim }) return null
                if (expectedDimensions > 0 && dim != expectedDimensions) return null
                out
            }
        }.getOrNull()
    }

    /** 供面板做「测试」用：跑一次探针，返回维度；失败返回 null。 */
    suspend fun probe(): Int? = withContext(Dispatchers.IO) {
        embed(listOf("记忆向量探针"))?.firstOrNull()?.size
    }
}
