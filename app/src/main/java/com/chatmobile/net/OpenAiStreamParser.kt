package com.chatmobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenAI 兼容 `/chat/completions` 的**流式帧解析器**。
 *
 * 设计要点：**纯函数、无网络、无协程**。输入是一条 `data:` 后面的原始字符串，
 * 输出 0..N 个 [ChatEvent]。这样 6 个协议坑全部可以用普通单元测试钉死。
 *
 * 刻意**不使用 `@Serializable` 数据类**来建模响应：各家字段是超集且会变
 * （DeepSeek 多 `reasoning_content`、OpenRouter 多 `reasoning`/`reasoning_details`、
 * 网关会偷偷塞字段）。用强类型模型会因 `ignoreUnknownKeys` 之外的原因变脆，
 * 而手动遍历 JSON 树对「缺失 / null / 类型漂移」天然宽容。
 */
class OpenAiStreamParser(
    /** 用于把异常文本里的密钥擦掉，防止日志泄漏。 */
    private val apiKey: String = "",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * **流式 `tool_calls` 的拼接累加器** —— 按 OpenAI 的 `index` 分组。
     *
     * ★★★ 为什么不每帧直接发一个 [ChatEvent.ToolCalls]（这是最常见的实现错误）：
     *
     *   一个工具调用在流里是**被切成很多片**送达的：
     *   ```
     *   delta.tool_calls[0] = { index:0, id:"call_abc", type:"function",
     *                           function:{ name:"web_search", arguments:"" } }
     *   delta.tool_calls[0] = { index:0, function:{ arguments:"{\"query\"" } }
     *   delta.tool_calls[0] = { index:0, function:{ arguments:":\"天气\"}" } }
     *   ```
     *   `id` / `name` 只在**第一片**出现，`arguments` 会分成很多片。
     *
     *   ⇒ 把每片当独立调用，会得到「第一个调用 id 正常但参数为空」+「后面一堆
     *     id 为空的幽灵调用」。真机上表现为：模型说"我要搜索"，然后什么都没发生。
     *
     *   ⇒ 正确做法是**按 index 累积**，在本轮结束时一次性发出。
     */
    private data class PartialCall(
        var callId: String = "",
        var toolName: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    private val partialCalls = linkedMapOf<Int, PartialCall>()

    /**
     * 解析一条 `data:` 载荷。
     *
     * ★ **坑 2**：必须先判 `[DONE]` 再解析 JSON —— `[DONE]` 不是 JSON，
     * 直接 parse 会在流的最后一帧抛异常。
     *
     * @return 该帧产生的事件；空 [List] 表示这帧没有可展示内容（例如纯 role 帧）。
     */
    fun parseData(data: String): List<ChatEvent> {
        val payload = data.trim()
        if (payload.isEmpty()) return emptyList()

        if (payload == DONE_SENTINEL) {
            return buildList {
                flushToolCalls()?.let { add(it) }
                add(ChatEvent.Done)
            }
        }

        val root = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            // 解析失败不抛给调用方：一帧坏数据不应该炸掉整条流。
            return listOf(
                ChatEvent.Error(
                    "无法解析流式帧: ${redact(e.message ?: e::class.simpleName ?: "unknown", apiKey)}",
                    retryable = false,
                ),
            )
        }

        val obj = root as? JsonObject
            ?: return listOf(ChatEvent.Error("流式帧不是 JSON 对象", retryable = false))

        // 有的网关在 HTTP 200 的流里塞 error 对象（上游失败时常见）。
        obj.obj("error")?.let { err ->
            val msg = err.str("message") ?: err.str("code") ?: "上游返回未知错误"
            return listOf(
                ChatEvent.Error(
                    redact(msg, apiKey),
                    retryable = isRetryableCode(err.int("code") ?: err.int("status")),
                ),
            )
        }

        val out = mutableListOf<ChatEvent>()

        // ★ 坑 3 + 坑 4：choices 可能为空数组（include_usage 的独立 usage 帧），
        // 也可能非空但 usage 骑在这一帧上（DeepSeek）。所以两者**互相独立**地读，
        // 不能「先判 choices 非空再读 usage」，也不能「只认空 choices 的 usage 帧」。
        val choices = obj.arr("choices")

        if (choices != null && choices.isNotEmpty()) {
            val first = choices[0] as? JsonObject
            val delta = first?.obj("delta")
            val message = first?.obj("message") // 个别网关在流里混用 message 形状

            val carrier = delta ?: message
            if (carrier != null) {
                // ★ 坑 5：推理字段各家不同，这里**按存在性宽容读取**：
                //   DeepSeek / 智谱 / Kimi → reasoning_content
                //   OpenRouter              → reasoning（官方称 reasoning_content 是它的别名）
                //   OpenAI                  → 根本没有，读到 null 就直接跳过
                // 两者都取第一个非空的，避免同一帧被计入两次。
                val reasoning = carrier.str("reasoning_content")?.takeIf { it.isNotEmpty() }
                    ?: carrier.str("reasoning")?.takeIf { it.isNotEmpty() }
                if (reasoning != null) {
                    out += ChatEvent.Reasoning(reasoning)
                }

                // 正文增量。首帧通常 content 为空串，跳过以减少 UI 无效刷新。
                carrier.str("content")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out += ChatEvent.Delta(it) }

                // ★ 累积工具调用分片（不在这里发事件，见 [PartialCall] 的注释）。
                carrier.arr("tool_calls")?.forEach { el ->
                    val piece = el as? JsonObject ?: return@forEach
                    // ★ index 是**必需**的：同一帧可能同时带多个工具调用的分片。
                    //   个别网关不给 index，那就退化成"最后一个"——至少不会丢弃。
                    val idx = piece.int("index") ?: (partialCalls.keys.maxOrNull() ?: 0)
                    val slot = partialCalls.getOrPut(idx) { PartialCall() }
                    piece.str("id")?.takeIf { it.isNotEmpty() }?.let { slot.callId = it }
                    piece.obj("function")?.let { fn ->
                        fn.str("name")?.takeIf { it.isNotEmpty() }?.let { slot.toolName = it }
                        fn.str("arguments")?.let { slot.args.append(it) }
                    }
                }
            }

            // finish_reason 非空 = 本轮生成结束（可能是 stop / length / tool_calls / content_filter）
            val finish = first?.str("finish_reason")?.takeIf { it.isNotEmpty() }
            if (finish != null) {
                // ★ 收尾时才发工具调用 —— 此时 arguments 已经拼完整了。
                //   放在 Finish **之前**：调用方需要先拿到"要调什么"，再看到"这轮结束"。
                flushToolCalls()?.let { out += it }
                out += ChatEvent.Finish(finish)
            }
        }

        // usage 独立读取：DeepSeek 骑在内容帧上，OpenAI 在空 choices 帧上。两条都要覆盖。
        obj.obj("usage")?.let { u ->
            val input = u.int("prompt_tokens")
            val output = u.int("completion_tokens")
            if (input != null || output != null) {
                out += ChatEvent.Usage(
                    input = input ?: 0,
                    output = output ?: 0,
                    reasoningTokens = u.obj("completion_tokens_details")?.int("reasoning_tokens"),
                    cachedInput = u.obj("prompt_tokens_details")?.int("cached_tokens")
                        ?: u.int("prompt_cache_hit_tokens"),
                )
            }
        }

        return out
    }

    /**
     * 把累积的分片收成一个 [ChatEvent.ToolCalls]，并清空累加器。
     *
     * ★ 返回 `null` 的三种情况都必须**丢弃**而不是发空列表：
     *   - 没有累积到任何调用（本轮就是纯文本回复）；
     *   - `name` 为空（分片没收全就断了 ⇒ 参数残缺，调了也是错）；
     *   - `callId` 为空（同上）。
     *   宁可"这一轮没有工具可用"，也不要发出一个模型侧对不上的幽灵调用。
     *
     * ★ 清空是必须的：同一个 parser 实例会被**多次请求**复用（工具循环里连续几轮），
     *   不清空的话上一轮的调用会被重复执行。
     */
    private fun flushToolCalls(): ChatEvent.ToolCalls? {
        if (partialCalls.isEmpty()) return null
        val calls = partialCalls.entries
            .sortedBy { it.key }
            .mapNotNull { (_, p) ->
                if (p.toolName.isBlank() || p.callId.isBlank()) return@mapNotNull null
                ToolCallRequest(
                    callId = p.callId,
                    toolName = p.toolName,
                    // ★ 空参数要补成 "{}" 而不是 ""：很多网关会拒绝空字符串作为 arguments。
                    argumentsJson = p.args.toString().ifBlank { "{}" },
                )
            }
        partialCalls.clear()
        return if (calls.isEmpty()) null else ChatEvent.ToolCalls(calls)
    }

    companion object {
        /** SSE 终止哨兵，字面量 `[DONE]`。 */
        const val DONE_SENTINEL: String = "[DONE]"

        private fun isRetryableCode(code: Int?): Boolean = when (code) {
            null -> false
            408, 409, 429 -> true
            in 500..599 -> true
            else -> false
        }
    }

    /**
     * 这帧是否带了非空 `finish_reason`。
     *
     * 供 [SseTransport] 判断「服务端是否明确收过尾」—— 用来区分
     * 「正常结束但没发 `[DONE]`」与「被截断」。
     */
    fun peekHasFinishReason(data: String): Boolean {
        val payload = data.trim()
        if (payload.isEmpty() || payload == DONE_SENTINEL) return false
        return runCatching {
            val root = json.parseToJsonElement(payload) as? JsonObject ?: return false
            val first = root.arr("choices")?.firstOrNull() as? JsonObject ?: return false
            !first.str("finish_reason").isNullOrEmpty()
        }.getOrDefault(false)
    }
}

// ---------------------------------------------------------------------------
// JSON 宽容读取工具
//
// ★ JsonNull 在 kotlinx-serialization 里**是 JsonPrimitive 的子类型**，
//   所以 `as? JsonPrimitive` 会把 null 也接住（content == "null"）。
//   下面的 `when` 里 JsonNull 分支必须放在 JsonPrimitive 之前。
// ---------------------------------------------------------------------------

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 只接受真正的 JSON 字符串；数字/布尔/ null 一律返回 null。 */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonElement).asStringOrNull()

private fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (isString) content else null
    else -> null
}

/** 接受 JSON 数字，也接受被网关串化过的数字字符串（`"123"`）。 */
internal fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().toIntOrNull()
}
