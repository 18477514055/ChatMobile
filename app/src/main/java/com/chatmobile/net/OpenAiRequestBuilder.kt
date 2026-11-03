package com.chatmobile.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把 [ChatRequest] 编成 OpenAI 兼容的 `/chat/completions` 请求体。
 *
 * 单独拆出来是为了**可测**：可以断言「只传了该传的字段」「null 不会被序列化进去」。
 */
internal object OpenAiRequestBuilder {

    /**
     * 这些键**不允许**被 [ChatRequest.extraBody] 覆盖：
     * 覆盖 `stream` 会让流式解析直接失效，覆盖 `model`/`messages` 属于误用。
     */
    private val PROTECTED_KEYS: Set<String> = setOf("model", "messages", "stream", "stream_options")

    fun build(
        request: ChatRequest,
        config: ProviderConfig,
    ): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)

        // include_usage：只有开了才拿得到 token 用量。个别网关不认这个字段会 400，
        // 所以由 config 的开关控制，而不是无条件加。
        if (request.includeUsage && config.supportsStreamUsage) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }

        putJsonArray("messages") {
            for (m in request.messages) {
                add(
                    buildJsonObject {
                        put("role", m.role)
                        // ★ 带 tool_calls 的 assistant 消息，content 可以是空串 ——
                        //   协议允许，但个别网关对「content 缺失」比「content 为空串」更挑剔，
                        //   所以一律显式写空串。
                        put("content", m.content)
                        // DeepSeek：带 tools 时才需要回传 reasoning，不带 tools 时传了也会被忽略。
                        // 默认不回传，避免给不认识的网关塞非标字段。
                        if (config.echoReasoning && m.reasoning != null) {
                            put("reasoning_content", m.reasoning)
                        }
                        // ★ assistant 要求调工具：必须原样把 id/name/arguments 发回去，
                        //   否则模型看不懂自己上一轮想干什么。arguments 是**字符串**，
                        //   不是对象 —— 这是 OpenAI 协议里最容易写错的一处。
                        if (m.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                for (c in m.toolCalls) {
                                    add(
                                        buildJsonObject {
                                            put("id", c.callId)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", c.toolName)
                                                put("arguments", c.argumentsJson)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // ★ 回填结果的 tool 消息必须带 tool_call_id 才能和上面的调用对上。
                        m.toolCallId?.let { put("tool_call_id", it) }
                    },
                )
            }
        }

        // 只写调用方**显式给了值**的参数。推理模型对这些很敏感
        // （DeepSeek 思考模式直接忽略 temperature，OpenAI o 系列会 400），
        // 不该由我们替用户填默认值。
        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        request.stop?.let { list -> putJsonArray("stop") { list.forEach { add(it) } } }

        // ★★★ 输出上限的键名**不是通用的**：
        //   `max_tokens`               —— 老模型 / 多数兼容网关
        //   `max_completion_tokens`    —— OpenAI o 系与 GPT-5 之后、Groq
        //   发错键不会报错，只会**被忽略**（看上去"我设了上限但没用"）。
        request.maxTokens?.let {
            put(if (config.usesMaxCompletionTokens) "max_completion_tokens" else "max_tokens", it)
        }

        // ★★★ 思考参数按**协议形态**分发，不是"一律发 reasoning_effort"。
        //   报告二的结论：只发 effort 时，Qwen / SiliconFlow / Kimi K2.x / GLM 的开关部分
        //   **要么静默无效、要么 400**。
        //   ★ 值域一律不写死 —— 档位名来自上游 /models，见 ModelCapability.effortLevels。
        applyThinking(request, config)

        // ★★★ 工具定义必须**逐字节稳定**才能命中缓存。
        //   它在 Anthropic 的缓存顺序里排在 system **之前**（tools → system → messages）
        //   ⇒ 只要工具表变一个字，后面全部重算。
        //   所以这里原样透传调用方给的字符串，**不做任何重新序列化**。
        //   ★ 空 / 解析失败 = **不发该字段**（发空数组或坏数据，有些网关会 400）。
        //     ★ 解析失败时静默降级为"本轮没有工具" —— 工具表语法错不该炸掉整条对话。
        val toolsEl = request.tools?.takeIf { it.isNotBlank() }?.let(::parseToolsOrNull)
        if (toolsEl != null) put("tools", toolsEl)

        // 逃生舱：厂商私有参数并入顶层。受保护键一律跳过。
        request.extraBody?.forEach { (k, v) ->
            if (k !in PROTECTED_KEYS) put(k, v)
        }
    }

    /**
     * 把调用方给的 tools 字符串解析成 JSON 树。
     *
     * ★ 为什么不用 `put("tools", string)`：那会把整段 JSON 当成**字符串**发出去
     *   （`"tools": "[{\"type\":...}]"`），服务端收到的是字符串而不是数组。
     * ★ 为什么解析是安全的：kotlinx 保留输入里的键顺序，所以「同一份工具表 ⇒ 同一个
     *   字节序列」这条缓存契约仍然成立。
     */
    private fun parseToolsOrNull(raw: String): kotlinx.serialization.json.JsonElement? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()

    /**
     * 按 [ProviderConfig.thinkingShape] 把思考参数摆进请求体。
     *
     * 四条实现纪律：
     * 1. **开关与强度分开** —— `enable_thinking` / `thinking.type` 管开不开，
     *    `reasoning_effort` 管开多大。
     * 2. **一个字段都不给时什么都不发** —— 不传才是安全默认（多数厂商不认会 400）。
     * 3. **预算不从 `maxTokens` 推导** —— 两者语义不同。
     * 4. **`thinking_budget` 只在真给了值时才发** —— 多数模型有服务端默认，
     *    我们凭空填一个数字等于替厂商决定思考深度。
     */
    private fun JsonObjectBuilder.applyThinking(
        request: ChatRequest,
        config: ProviderConfig,
    ) {
        val effort = request.reasoningEffort?.takeIf { it.isNotBlank() }
        val budget = request.thinkingBudget?.takeIf { it > 0 }

        when (config.thinkingShape) {
            ThinkingShape.NONE -> Unit

            ThinkingShape.EFFORT_ONLY -> {
                effort?.let { put("reasoning_effort", it) }
            }

            ThinkingShape.EFFORT_PLUS_THINKING_SWITCH -> {
                // DeepSeek / GLM：开关 + 强度并列。
                // ★ 只给了 budget（没给档位）也算"想开思考" ⇒ 开关照样给。
                if (effort != null || budget != null) {
                    putJsonObject("thinking") { put("type", "enabled") }
                }
                effort?.let { put("reasoning_effort", it) }
            }

            ThinkingShape.ENABLE_THINKING -> {
                // Qwen / DashScope / SiliconFlow：布尔开关 + 可选预算。
                // ★ 这两家**不认 reasoning_effort**，所以这里不发它。
                if (effort != null || budget != null) {
                    put("enable_thinking", true)
                }
                budget?.let { put("thinking_budget", it) }
            }

            ThinkingShape.REASONING_OBJECT -> {
                // OpenRouter 归一化形态：`reasoning: { effort: "high" }`
                if (effort != null || budget != null) {
                    putJsonObject("reasoning") {
                        effort?.let { put("effort", it) }
                        budget?.let { put("max_tokens", it) }
                    }
                }
            }
        }
    }
}
