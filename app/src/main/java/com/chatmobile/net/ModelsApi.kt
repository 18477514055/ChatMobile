package com.chatmobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * `GET /v1/models` —— **可选能力**。
 *
 * ★ 为什么叫「可选」：调研报告 §3 结论是「把 `/v1/models` 当可选能力而非必有能力」。
 * 智谱的 OpenAPI 规范里根本没有列举模型的端点，阿里百炼只承诺原生 `/api/v1/models`
 * 而不承诺 `compatible-mode/v1/models`。另外有些网关对**任意路径**都先返回 401，
 * 让「路由存在性探测」失效。
 *
 * 因此本类的契约是：**任何失败都返回 `null`，绝不抛异常**。
 * UI 收到 null 就退回「手动填模型名 + 内置常用清单」，功能不受影响。
 */
class ModelsApi(
    private val config: ProviderConfig,
    private val client: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 模型列表的**实际 URL**。
     *
     * ★ `modelsPath` 可以是绝对 URL —— 已经证实有厂商 chat 与 models
     *   前缀不同（OpenCode：chat 有 `/openai`，models 没有）。
     *   见 [ProviderConfig.modelsPath] 的注释。
     */
    private fun modelsUrl(): String {
        val p = config.modelsPath.trim()
        return if (p.startsWith("http://") || p.startsWith("https://")) {
            p
        } else {
            joinUrl(config.baseUrl, p)
        }
    }

    /**
     * 拉取模型 id 列表。
     *
     * @return 去重后的模型 id；**任何失败都返回 null**（含：厂商不支持、网络错、格式不认识）。
     */
    suspend fun listModels(): List<String>? {
        // 配置层已经明确说了不支持，就别发多余的请求（省一次 401/404）。
        if (!config.supportsModelList) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(modelsUrl())
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Accept", "application/json")
                    .apply { config.extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body.string()
                    parseModelIds(body)
                }
            }.getOrNull()
        }
    }

    /**
     * 拉取模型并**保留失败原因**给 UI 用。
     *
     * ★ 为什么要单独一个方法：用户点了「拉取模型」之后如果什么都没发生，
     *   他分不清是"这家里没有模型"还是"请求失败了"。
     *   上一版只有 `listModels(): List<String>?`，null 什么信息都不带。
     *
     * @return 成功时 `ids` 非空、`error` 为 null；
     *   失败时 `error` 是一句**能指导下一步动作**的话（不是"失败了"三个字），
     *   且 `ids` 为空。
     */
    suspend fun fetchModels(): Result = withContext(Dispatchers.IO) {
        if (!config.supportsModelList) {
            return@withContext Result(
                ids = emptyList(),
                error = "该提供商不支持列出模型，请用「添加模型」手动填写",
            )
        }
        if (config.baseUrl.isBlank()) {
            return@withContext Result(ids = emptyList(), error = "请先填写 API 地址")
        }

        runCatching {
            val request = Request.Builder()
                .url(modelsUrl())
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "application/json")
                .apply { config.extraHeaders.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.code == 401 || response.code == 403 ->
                        Result(emptyList(), "密钥无效或没有权限（HTTP " + response.code + "）")

                    response.code == 404 ->
                        Result(emptyList(), "该地址不支持 /models，请用「添加模型」手动填写")

                    !response.isSuccessful ->
                        Result(emptyList(), "请求失败（HTTP " + response.code + "）")

                    else -> {
                        val ids = parseModelIds(body)
                        if (ids.isNullOrEmpty()) {
                            Result(emptyList(), "返回内容里没有模型列表，请手动填写")
                        } else {
                            // ★ 顺手解析能力档案 —— 同一次响应里就有，不需要额外请求。
                            //   各家字段名不同（OpenCode 用 limit.context，
                            //   OpenRouter 用 context_length），由 ModelCapabilities 兜住。
                            Result(ids, null, ModelCapabilities.parse(body))
                        }
                    }
                }
            }
        }.getOrElse { e ->
            Result(emptyList(), "网络错误：" + (e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    /**
     * [fetchModels] 的返回。
     *
     * @param capabilities 模型 id → 能力档案。空 map 表示上游没给这些信息
     *   （绝大多数厂商只给 id 和 created），此时上层应隐藏"思考强度"与
     *   "上下文上限"这两个 UI —— **不知道比猜错好**。
     */
    data class Result(
        val ids: List<String>,
        val error: String?,
        val capabilities: Map<String, ModelCapability> = emptyMap(),
    )

    /**
     * 从响应体里抽 `data[].id`。
     *
     * 只认 `data[].id`，其余字段一律忽略 —— 各家的响应是超集
     * （DeepSeek 多 context_window、OpenRouter 多 pricing/architecture 等），
     * 强依赖任何一个可选字段都会在某家上崩。
     */
    fun parseModelIds(body: String): List<String>? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val data = root.arr("data") ?: return@runCatching null
        data.mapNotNull { entry ->
            (entry as? JsonObject)?.str("id")?.takeIf { it.isNotBlank() }
        }.distinct()
    }.getOrNull()

    private companion object {
        /**
         * 模型列表是**短请求**，可以放心用有限超时；
         * 不要复用 SSE 那个 readTimeout=0 的客户端，否则列表页可能一直转圈。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
