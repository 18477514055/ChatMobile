package com.chatmobile.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 一个 MCP 服务器暴露的工具。
 *
 * ★ 这里的字段**直接来自服务端**，我们只做类型转换，不重新解释：
 *   `inputSchema` 就是一份标准 JSON Schema，无需翻译成我们自己的 [ToolParameters] ——
 *   原样转给模型反而更准（我们的简化的 `ToolParameters` 表达不了 `oneOf` / `$ref`）。
 */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

/** 探测结果。 */
sealed interface McpProbeResult {
    data class Ok(
        val serverName: String,
        val serverVersion: String?,
        val tools: List<McpToolInfo>,
        /** 实际用的传输方式（诊断用）。 */
        val transport: String,
    ) : McpProbeResult

    /** 失败。[hint] 必须是**可操作**的建议，不是错误码。 */
    data class Failed(val reason: String, val hint: String) : McpProbeResult
}

/**
 * MCP 服务器的**只读探测**客户端。
 *
 * ## ★★★ 为什么先只做"探测"而不是完整客户端
 *
 * 用户要求：「模型可以帮用户配置 mcp」。
 * 而配置的前提是**先知道那个地址上有什么** —— 空着手让模型填配置，它只能瞎写。
 *
 * 所以这一步只做两件事：
 *   ① `initialize` —— 握手，确认这是个 MCP 服务
 *   ② `tools/list` —— 拿到它的工具清单
 *
 * 完整调用（`tools/call`）等配置保存后再接 —— 那时才有确定的服务器与凭据。
 *
 * ## ★ 三个协议细节（不做会一直失败）
 *
 * 1. **两个响应头都要声明**：新版客户端要求 `Accept: application/json, text/event-stream`，
 *    只写 `application/json` 会被一些实现直接 406。
 * 2. **响应可能是 SSE**：`text/event-stream` 里每行 `data: {...}`，
 *    解析要按 SSE 规则（多行 data 拼接、空行分隔）。
 * 3. **`initialize` 之后要发 `notifications/initialized`**，否则部分服务端不认后续请求。
 */
class McpProbeClient(
    private val http: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 探测一个地址。
     *
     * ★ **永不抛异常** —— 失败作为一种结果返回，带可操作的建议。
     */
    fun probe(rawUrl: String, allowPrivate: Boolean = false): McpProbeResult {
        val verdict = McpUrlGuard.check(rawUrl, allowPrivate)
        val url = when (verdict) {
            is McpUrlGuard.Verdict.Rejected ->
                return McpProbeResult.Failed(
                    reason = verdict.reason,
                    hint = "换一个公网的 https 地址试试。",
                )
            is McpUrlGuard.Verdict.Ok -> verdict.normalized
        }

        // ---- ① initialize ----
        val initResp = rpc(url, 1, "initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", "ChatMobile")
                    put("version", "0.1.0")
                },
            )
            put("capabilities", buildJsonObject { })
        }) ?: return McpProbeResult.Failed(
            reason = "连不上这个地址",
            hint = "检查地址是否写对、服务器是否在线。有的服务需要把完整路径写全" +
                "（例如 /mcp 或 /sse）。",
        )

        val initErr = initResp["error"] as? JsonObject
        if (initErr != null) {
            return McpProbeResult.Failed(
                reason = "握手被拒绝：" + (initErr["message"]?.jsonPrimitive?.content ?: "未知原因"),
                hint = "如果服务要求鉴权，请先在设置页把 key 填进去再探测。",
            )
        }

        val initResult = initResp["result"] as? JsonObject
            ?: return McpProbeResult.Failed(
                reason = "响应里没有 result —— 这个地址可能不是 MCP 服务",
                hint = "确认地址指向 MCP 端点（常见后缀 /mcp 或 /sse），而不是网站首页。",
            )

        val serverInfo = initResult["serverInfo"] as? JsonObject
        val serverName = serverInfo?.get("name")?.jsonPrimitive?.content ?: "（未命名）"
        val serverVersion = serverInfo?.get("version")?.jsonPrimitive?.content

        // ---- ② notifications/initialized（协议要求，不回应） ----
        rpc(url, 2, "notifications/initialized", null, expectResponse = false)

        // ---- ③ tools/list ----
        val listResp = rpc(url, 3, "tools/list", buildJsonObject { })
            ?: return McpProbeResult.Failed(
                reason = "握手成功，但拿不到工具清单",
                hint = "服务器可能不支持 tools/list。这样的 MCP 只能提供资源（resources），" +
                    "当前版本还不支持，可以先跳过。",
            )

        val listErr = listResp["error"] as? JsonObject
        if (listErr != null) {
            return McpProbeResult.Failed(
                reason = "工具清单被拒绝：" + (listErr["message"]?.jsonPrimitive?.content ?: ""),
                hint = "可能是凭据不足。",
            )
        }

        val tools = (listResp["result"] as? JsonObject)
            ?.get("tools") as? JsonArray
            ?: JsonArray(emptyList())

        val infos = tools.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                inputSchemaJson = obj["inputSchema"]?.toString() ?: """{"type":"object"}""",
            )
        }

        return McpProbeResult.Ok(
            serverName = serverName,
            serverVersion = serverVersion,
            tools = infos,
            transport = if (url.contains("/sse")) "sse" else "streamable-http",
        )
    }

    // ------------------------------------------------------------------

    /**
     * 发一次 JSON-RPC。
     *
     * @param expectResponse false = 通知（`notifications` 系列），服务端不回，也不该等超时
     * @return 解析出来的响应对象；网络失败返回 null
     */
    private fun rpc(
        url: String,
        id: Int,
        method: String,
        params: JsonObject?,
        expectResponse: Boolean = true,
    ): JsonObject? {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            if (expectResponse) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            // ★ 两个都要声明：只写 application/json 会被一些实现 406
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val body = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null

        if (!expectResponse) return JsonObject(emptyMap())

        return parseJsonRpc(body)
    }

    /**
     * 解析响应体。
     *
     * ★ 两种形态都要认：
     *   - `application/json`：一个完整的 JSON 对象
     *   - `text/event-stream`：`event: message\ndata: {...}\n\n`
     *
     * ★ SSE 的坑：`data:` 可能**多行**，按规范要用 `\n` 拼起来才是完整 JSON。
     *   只取第一行会让稍大的响应被截断 —— 表现是"解析失败"，很难查。
     */
    private fun parseJsonRpc(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        // 先按纯 JSON 试
        runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()?.let {
            return it
        }

        // 再按 SSE 试：找出所有 data: 段，每段内的多行用 \n 拼
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
                else -> Unit   // event: / id: 等忽略
            }
        }
        if (current.isNotEmpty()) dataLines += current.toString()

        for (data in dataLines) {
            val obj = runCatching {
                json.parseToJsonElement(data) as? JsonObject
            }.getOrNull()
            if (obj != null && obj.containsKey("result")) return obj
            if (obj != null && obj.containsKey("error")) return obj
        }
        return null
    }

    companion object {
        /** 我们声明的协议版本。服务端可能返回它支持的版本，那不冲突。 */
        const val PROTOCOL_VERSION = "2024-11-05"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 探测用的 OkHttp 客户端。
         *
         * ★ 超时必须短：探测是**同步交互**（用户正等在界面上），
         *   10 秒没回应就该如实说"别等了"，而不是让他盯着转圈。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
