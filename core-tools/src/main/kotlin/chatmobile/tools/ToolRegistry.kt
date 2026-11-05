package chatmobile.tools

/**
 * 工具注册表 —— 模型能看见哪些工具、怎么找到它们。
 *
 * 对应 OpenAI function calling 的 `tools` 数组：
 * 每次请求要把 [specs] 序列化后发给模型。
 */
class ToolRegistry {

    private val executors = LinkedHashMap<String, Pair<ToolSpec, ToolExecutor>>()

    fun register(spec: ToolSpec, executor: ToolExecutor): ToolRegistry {
        require(spec.name.matches(NAME_REGEX)) {
            "工具名必须匹配 $NAME_REGEX，当前 '${spec.name}'"
        }
        require(!executors.containsKey(spec.name)) { "工具名重复: ${spec.name}" }
        executors[spec.name] = spec to executor
        return this
    }

    fun specs(): List<ToolSpec> = executors.values.map { it.first }

    fun has(name: String): Boolean = executors.containsKey(name)

    /** 按模型给的 tool_call 执行。找不到工具时返回 Failure 而不是抛异常。 */
    fun execute(call: ToolCall, workingDir: String? = null): ToolResult {
        val entry = executors[call.toolName]
            ?: return ToolResult.Failure(
                call.callId,
                "未知工具 '${call.toolName}'。可用: ${executors.keys.joinToString(", ")}",
            )
        val (_, executor) = entry
        return executor.execute(call, workingDir)
    }

    /**
     * 把工具定义转成 OpenAI tools 数组的 JSON 字符串。
     *
     * 输出形态：
     * `[{"type":"function","function":{"name":"...","description":"...","parameters":{...}}}]`
     *
     * ## ★★★ 三条缓存纪律（这份字符串会被逐字节比对）
     *
     * 1. **顺序由 [specs] 决定，且只追加不重排** —— Anthropic 的缓存顺序是
     *    `tools` → `system` → `messages`，工具表**比系统提示词更靠前**。
     *    在中间插一个工具，后面每个工具的位置都平移 ⇒ 全体用户一次全量重缓存。
     * 2. **不输出任何动态内容**：不能有时间戳、不能有"当前启用了几个"这类每轮会变的字段。
     * 3. **`additionalProperties: false` 必须写** —— 现在补上了。少写它的后果：
     *    模型会往参数里塞 schema 没声明的字段，严格的网关直接 400。
     */
    fun toOpenAiToolsJson(): String {
        val sb = StringBuilder("[")
        specs().forEachIndexed { index, spec ->
            if (index > 0) sb.append(',')
            sb.append("""{"type":"function","function":{""")
            sb.append(""""name":""").append(jsonString(spec.name))
            // ★★★ 走 spec.fullDescription()，不是拼 spec.description ——
            //   后者会让「使用示例」永远到不了常驻工具（示例只加在 description 之外的字段里）。
            sb.append(""","description":""").append(jsonString(spec.fullDescription()))
            // ★★★ 参数走 spec.parametersJson() 这一个入口：
            //   结构化工具 → ToolParametersJson.encode()
            //   MCP 工具   → 服务端给的 inputSchema **原样透传**（含 oneOf/$ref 等，
            //                我们的简化模型表达不了，降级解析会静默丢约束）
            sb.append(""","parameters":""").append(spec.parametersJson())
            sb.append("}}")
        }
        sb.append(']')
        return sb.toString()
    }

    companion object {
        val NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

        /** JSON 字符串转义 */
        fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
