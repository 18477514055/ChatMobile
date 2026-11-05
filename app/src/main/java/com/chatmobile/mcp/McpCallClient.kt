package com.chatmobile.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 一个**已保存**的 MCP 服务器配置。
 *
 * ★ 只存探测确认过的东西：地址、显示名、传输方式。
 *   工具清单**不存** —— 它是服务端随时可能改的（`tools/list_changed`），
 *   存下来就一定会过期，而过期的工具清单会让模型调用一个已经不存在的工具。
 */
@Serializable
data class McpServerConfig(
    /** 显示名（探测时服务端报的，用户也可在设置页改名）。 */
    val name: String,
    /** 规范化后的地址。 */
    val url: String,
    /** `streamable-http` 或 `sse`。 */
    val transport: String = "streamable-http",
    /** 是否启用。关掉 = 它的工具完全不进工具表。 */
    val enabled: Boolean = true,
    /**
     * 工具名的前缀。
     *
     * ★★★ 为什么必须有前缀（MCP 规范明确警告过）：
     *   工具名的唯一性**只到单个服务器内**。聚合多个服务器时一定会撞名
     *   （两个 server 都有 `search`），而 `serverInfo.name` **不保证唯一、不可依赖**。
     *   ⇒ 规范的建议就是加前缀消歧。
     */
    val prefix: String = "",
)

/** MCP 配置的持久化编解码。 */
object McpConfigCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    fun encode(list: List<McpServerConfig>): String = json.encodeToString(list)

    /**
     * 解码。
     *
     * ★ **永不抛异常**：配置损坏时宁可从零开始，也不能让 App 起不来。
     *   （配置是"用户可编辑"的东西，损坏概率不低。）
     */
    fun decode(raw: String?): List<McpServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<McpServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    /**
     * 从服务器显示名推出一个**合法的工具名前缀**。
     *
     * ★ 工具名必须匹配 `^[a-zA-Z0-9_-]{1,64}$`（见 `ToolRegistry.NAME_REGEX`），
     *   而服务器名可能是中文、带空格、带斜杠 ⇒ 必须净化。
     */
    fun prefixFrom(serverName: String): String {
        val cleaned = serverName.map { c ->
            if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-') c else '_'
        }.joinToString("").trim('_').take(24)
        return cleaned.ifEmpty { "mcp" }.lowercase()
    }

    /** 拼出完整的工具名：`<前缀>__<原名>`。 */
    fun toolName(prefix: String, original: String): String {
        val p = prefix.ifBlank { "mcp" }
        // ★ 原名也可能超长或含非法字符 —— 一并净化，否则 ToolRegistry 会 require 失败。
        val o = original.map { c ->
            if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-') c else '_'
        }.joinToString("").take(40).ifEmpty { "tool" }
        return "${p}__$o".take(64)
    }
}

/** 一次 `tools/call` 的结果。 */
sealed interface McpCallResult {
    /** 成功。[text] 已经拼成一段给模型读的文本。 */
    data class Ok(val text: String) : McpCallResult

    /** 失败。[hint] 必须是可操作的。 */
    data class Failed(val reason: String, val hint: String) : McpCallResult
}

/**
 * MCP **调用**客户端 —— `tools/call`。
 *
 * ## ★★★ 为什么和 [McpProbeClient] 分开
 *
 * | | 探测 | 调用 |
 * |---|---|---|
 * | 何时 | 用户/模型在**配置阶段** | 对话中模型真的要用 |
 * | 超时 | 10 秒（用户正盯着界面） | 20 秒（工具可能真的在查东西） |
 * | 失败怎么办 | 给用户看，让他改地址 | 喂回模型，让它换个办法 |
 *
 * 合成一个类会让两边的超时与失败措辞互相污染。
 *
 * ## ★ 协议要点（少一个就连不上）
 *
 * 1. `Accept` 要同时声明 `application/json, text/event-stream`
 * 2. 响应可能是 SSE，`data:` 可能**多行**（要 `\n` 拼）
 * 3. 每次请求都要带 `initialize` 之后的那套头（本类无状态，每次自包含）
 *
 * ## ★★★ 断流不能续传（规范硬要求）
 *
 * 2026-07-28 版**删掉了 `Last-Event-ID`** 与 SSE 重投递。流断了就丢了，
 * 客户端 **MUST** 用新的 request id **整请求重发**。
 * ⇒ 这里不做任何"断点续传"逻辑 —— 做了就是错的。
 */
class McpCallClient(
    private val http: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 调一个工具。
     *
     * ★ **永不抛异常** —— 失败作为 [McpCallResult.Failed] 返回，由调用方喂回模型。
     *
     * @param argumentsJson 模型给的原始参数 JSON，**原样转发**
     */
    fun call(
        server: McpServerConfig,
        remoteToolName: String,
        argumentsJson: String,
    ): McpCallResult {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", remoteToolName)
                    // ★ 参数原样透传。解析再重建会把 `oneOf` 这类结构弄丢，
                    //   而服务端恰恰按原结构校验。
                    put(
                        "arguments",
                        runCatching { json.parseToJsonElement(argumentsJson) }
                            .getOrElse { JsonObject(emptyMap()) },
                    )
                },
            )
        }.toString()

        val req = Request.Builder()
            .url(server.url)
            .header("Content-Type", "application/json")
            // ★ 两个都要声明：只写 application/json 会被一些实现 406
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val body = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return McpCallResult.Failed(
                        reason = "服务器返回 HTTP ${resp.code}",
                        hint = "如果这个服务器需要鉴权，请在设置页检查它是否仍然可用。" +
                            "不要重复重试同一个调用。",
                    )
                }
                resp.body?.string().orEmpty()
            }
        }.getOrElse { e ->
            return McpCallResult.Failed(
                reason = "连不上这个 MCP 服务器（${e::class.simpleName}）",
                hint = "可能是网络问题或服务器已下线。先告诉用户这个工具暂时不可用，" +
                    "用你已有的信息回答；不要反复重试。",
            )
        }

        val obj = parseJsonRpc(body)
            ?: return McpCallResult.Failed(
                reason = "服务器返回的内容无法解析",
                hint = "这个地址可能不是有效的 MCP 端点，或者它返回了错误页。" +
                    "告诉用户这个工具暂时不可用。",
            )

        (obj["error"] as? JsonObject)?.let { err ->
            return McpCallResult.Failed(
                reason = "服务器拒绝了这个调用：" +
                    (err["message"]?.jsonPrimitive?.content ?: "未知原因"),
                hint = "检查参数是否符合它的 schema。不要用同样的参数重试。",
            )
        }

        val result = obj["result"] as? JsonObject
            ?: return McpCallResult.Failed(
                reason = "响应里没有 result",
                hint = "服务端行为异常。把这个情况告诉用户，不要重试。",
            )

        // ★ `isError: true` 表示**工具执行失败但协议成功** ——
        //   内容仍然是给模型看的错误说明，要原样转达，不能当成成功。
        val isError = (result["isError"] as? JsonPrimitive)
            ?.content?.trim()?.equals("true", ignoreCase = true) == true

        val text = extractContentText(result)

        return McpCallResult.Ok(
            if (isError) "工具执行失败：" + text.ifBlank { "（服务端没给原因）" } else text,
        )
    }

    /**
     * 从 `result.content` 里抠出所有文本。
     *
     * MCP 的 content 是一个数组，每项 `{type, text}`；也可能有
     * `image` / `resource` 类型（这里只取文本，其余给一句占位说明）。
     */
    private fun extractContentText(result: JsonObject): String {
        val arr = result["content"] as? JsonArray
        if (arr == null) {
            // 有些实现直接放 structuredContent 或纯字符串
            (result["structuredContent"] as? JsonObject)?.let { return it.toString() }
            return result.toString().take(4000)
        }
        val parts = mutableListOf<String>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            when (o["type"]?.jsonPrimitive?.content) {
                "text" -> o["text"]?.jsonPrimitive?.content?.let { parts += it }
                "image" -> parts += "（服务端返回了一张图片，当前版本不显示）"
                "resource" -> parts += "（服务端返回了一个资源引用，当前版本不展开）"
                else -> Unit
            }
        }
        return parts.joinToString("\n").ifBlank { "（工具执行成功，但没有返回文本）" }
    }

    /**
     * 解析响应体（与探测客户端同一套规则）。
     *
     * ★ 这两处不能各写一份 —— 但 [McpProbeClient] 里那份是 private 的。
     *   放在这里是因为调用路径才是热路径（每次工具调用都走它）。
     */
    private fun parseJsonRpc(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()?.let { return it }

        val dataLines = mutableListOf<String>()
        val current = StringBuilder()
        for (line in trimmed.lines()) {
            when {
                line.startsWith("data:") -> {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line.removePrefix("data:").trimStart())
                }
                line.isBlank() -> {
                    if (current.isNotEmpty()) {
                        dataLines += current.toString()
                        current.clear()
                    }
                }
                else -> Unit
            }
        }
        if (current.isNotEmpty()) dataLines += current.toString()

        for (data in dataLines) {
            val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
            if (obj != null && (obj.containsKey("result") || obj.containsKey("error"))) return obj
        }
        return null
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 调用用的客户端。
         *
         * ★ 超时比探测**长**（20s vs 10s）：探测只是握手，而工具可能真的在查数据库、
         *   调第三方 API。但也不能太长 —— 用户在界面上是在等的。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
