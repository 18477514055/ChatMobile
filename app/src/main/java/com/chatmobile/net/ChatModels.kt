package com.chatmobile.net

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * 模型发起的一次工具调用。
 *
 * ★ [callId] 是模型给的 `tool_call.id`，回填结果时**必须原样带回**
 *   （`role:"tool"` 消息的 `tool_call_id`），否则 OpenAI 会 400。
 * ★ [argumentsJson] 保持**原始字符串**，不在这里解析 —— 让执行器解析便于报错定位。
 */
data class ToolCallRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
)

/**
 * 一条对话消息。
 *
 * [reasoning] 是**历史**推理内容（不是本轮流式产出）。DeepSeek 在「带 tools」时要求把历史
 * 轮次的 reasoning 原样回传，不带 tools 时会忽略。
 *
 * ## ★★★ 工具相关字段为什么必须挂在消息上
 *
 * OpenAI 的工具调用是**多轮往返**，不是一次请求：
 * ```
 * assistant(tool_calls=[{id, function:{name, arguments}}])   ← 模型说"我要调工具"
 * tool(tool_call_id, content="结果")                          ← 我们回填结果
 * assistant("根据结果，……")                                    ← 模型给最终答复
 * ```
 * 这三条都必须**留在历史里并发回去**，否则模型看不到自己刚才调过什么。
 * 所以 [ChatMessage] 必须能表达「带 tool_calls 的 assistant」和「带 tool_call_id 的 tool」。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    /** 仅 `role == assistant` 且模型要调工具时非空。 */
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** 仅 `role == "tool"` 时非空，对应发起调用的 id。 */
    val toolCallId: String? = null,
    /**
     * 随这条消息一起发出的**图片**（只对 `role == "user"` 有意义）。
     *
     * ★★★ 一旦非空，`content` 在 JSON 里就**不能是字符串**，必须变成数组：
     * ```json
     * "content": [
     *   {"type":"text","text":"这是什么"},
     *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}
     * ]
     * ```
     * 发成字符串 + 另加一个顶层字段是最常见的错误 —— 服务端会**静默忽略图片**，
     * 模型回你一句"我看不到图片"，而报文本身完全合法。
     *
     * ★ 文本类附件**不在这里**：它们已经被内联进 [content] 正文了
     *   （协议里没有 `{"type":"file"}` 这种类型）。
     */
    val images: List<ChatAttachment> = emptyList(),
) {
    companion object {
        const val ROLE_SYSTEM: String = "system"
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"
        const val ROLE_TOOL: String = "tool"

        fun system(content: String): ChatMessage = ChatMessage(ROLE_SYSTEM, content)

        fun user(content: String, images: List<ChatAttachment> = emptyList()): ChatMessage =
            ChatMessage(ROLE_USER, content, images = images)

        fun assistant(content: String, reasoning: String? = null): ChatMessage =
            ChatMessage(ROLE_ASSISTANT, content, reasoning)

        /** 模型要调工具的那一条（content 通常为空）。 */
        fun assistantToolCalls(toolCalls: List<ToolCallRequest>): ChatMessage =
            ChatMessage(ROLE_ASSISTANT, "", toolCalls = toolCalls)

        /** 回填工具执行结果。`content` 必须是字符串（协议要求）。 */
        fun toolResult(toolCallId: String, content: String): ChatMessage =
            ChatMessage(ROLE_TOOL, content, toolCallId = toolCallId)
    }
}

/**
 * 一次对话请求。
 *
 * 只放**跨厂商通用**的字段；厂商私有字段走 [extraBody]（会与生成出的 JSON 顶层合并）。
 * 这样新增一个厂商不需要改这个类。
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    /** 是否请求 `stream_options.include_usage`（拿到 token 用量）。 */
    val includeUsage: Boolean = true,
    /** DeepSeek 的 `reasoning_effort`：none / low / high / max。 */
    val reasoningEffort: String? = null,
    /**
     * 推理预算（token）。**null = 不发该字段**，用服务端默认。
     *
     * ★ 只有 [ThinkingShape.ENABLE_THINKING]（Qwen / SiliconFlow）用得着它
     *   （`thinking_budget`）。**绝不由 `maxTokens` 推导** —— 那是"回答长度"，
     *   这是"思考深度预算"，两者混用会静默改变语义。
     */
    val thinkingBudget: Int? = null,
    /**
     * 工具定义 —— **已序列化好的 OpenAI `tools` 数组字符串**。
     *
     * ★ 为什么传字符串而不是结构化对象：不是偷懒，是**缓存纪律**。
     *   所有厂商的 prompt 缓存都逐字节比对前缀，而 Anthropic 的顺序是
     *   `tools` → `system` → `messages` —— **工具定义比系统提示词更靠前**。
     *   只要这一次的字符串和上一次逐字节相同，前缀就命中。
     *   传字符串让「同一份工具表 = 同一个字节序列」成为显式契约。
     *
     * ★ 空 = **不发** `tools` 字段（不是发空数组）。发空数组有些网关会 400。
     */
    val tools: String? = null,
    /** 逃生舱：直接并入请求体顶层，用于厂商私有参数。 */
    val extraBody: JsonObject? = null,
)

/**
 * 流式事件。**这是 UI 唯一需要认识的网络层类型** —— 厂商差异全部在解析层吃掉。
 *
 * 比原始需求多了两类事件，理由见 README「与需求书的三处偏差」：
 * - [Finish]：`finish_reason` 非空时发出。`length` 表示被 max_tokens 截断，
 *   UI 应区别于正常结束（协议调研报告 §1.6）。
 * - [Usage] 多了两个可空字段（推理 token / 命中缓存的输入 token），DeepSeek 与 OpenAI
 *   都提供了，不带会白丢信息。
 */
sealed interface ChatEvent {

    /** 正文增量。 */
    data class Delta(val text: String) : ChatEvent

    /** 推理（思维链）增量，已归一化：无论厂商用的是哪个字段名。 */
    data class Reasoning(val text: String) : ChatEvent

    /** token 用量。可能来自独立帧，也可能骑在内容帧上（DeepSeek）。 */
    data class Usage(
        val input: Int,
        val output: Int,
        /** OpenAI: `completion_tokens_details.reasoning_tokens`。 */
        val reasoningTokens: Int? = null,
        /** OpenAI: `prompt_tokens_details.cached_tokens`；DeepSeek: `prompt_cache_hit_tokens`。 */
        val cachedInput: Int? = null,
    ) : ChatEvent

    /**
     * 模型要求调用工具。
     *
     * ★ 解析器**逐帧累积**后，在一轮结束时**一次性**发出这个事件，
     *   而不是每帧发一个 —— 因为 OpenAI 的 `tool_calls` 是增量分片：
     *   `id` / `function.name` 只在**第一帧**出现，`arguments` 会分成很多帧。
     *   把每帧当独立调用是最常见的实现错误（会得到一堆参数残缺的调用）。
     */
    data class ToolCalls(val calls: List<ToolCallRequest>) : ChatEvent

    /** 收到 `finish_reason`。可能是 `stop` / `length` / `tool_calls` / `content_filter`。 */
    data class Finish(val reason: String?) : ChatEvent

    /** 流正常结束（收到 `[DONE]`，或服务端干净关闭连接）。 */
    data object Done : ChatEvent

    /**
     * 失败。**永远以事件形式给出，不抛异常** —— 让 UI 的状态机只处理一种形状。
     *
     * @param retryable 是否值得重试（超时 / 5xx / 429 / 连接重置 = true；401 / 400 / 证书错误 = false）
     */
    data class Error(val message: String, val retryable: Boolean) : ChatEvent
}

/**
 * 厂商无关的对话接口。
 *
 * 新增厂商（Anthropic / Gemini）只需实现这个接口并在 [ProviderFactory] 注册，
 * 调用方代码不变 —— 这是「接口别写死」的落点。
 */
interface ChatProvider {

    /**
     * 发起流式对话。
     *
     * 冷流：每次 collect 都是一次新的请求。取消 collect 会立刻取消底层 HTTP 连接。
     */
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
