package chatmobile.tools

/**
 * 工具定义 —— 直接对齐 OpenAI function calling 的 tools 格式。
 *
 * 为什么要对齐：模型返回的 tool_call 是 `{"name": "...", "arguments": "{...}"}`，
 * 我们只需要按 name 找到实现、把 JSON 参数喂进去。不做中间层，少一层就少一处 bug。
 */
data class ToolSpec(
    /** 工具名，模型用它来调用。必须符合 [a-zA-Z0-9_-]{1,64} */
    val name: String,
    /** 给模型看的说明。写清楚「什么时候该用」比「它能做什么」更重要 */
    val description: String,
    /** JSON Schema 形式的参数定义 */
    val parameters: ToolParameters,
    /** 是否需要用户确认后才执行（危险操作） */
    val requiresConfirmation: Boolean = false,
)

/**
 * 极简 JSON Schema 子集。
 *
 * 刻意只支持 OpenAI function calling 实际会用到的部分：
 * 对象 + 字符串/数字/布尔/数组类型的属性。
 * 不做完整 JSON Schema 实现 —— 那是无底洞，且模型也用不到。
 */
data class ToolParameters(
    val properties: Map<String, ParamSpec>,
    val required: List<String> = emptyList(),
)

data class ParamSpec(
    val type: ParamType,
    val description: String,
    val enum: List<String>? = null,
    /** 数组元素类型，仅 type == ARRAY 时有意义 */
    val items: ParamType? = null,
)

enum class ParamType(val jsonName: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    ARRAY("array"),
}

/**
 * 一次工具调用的请求。
 *
 * [callId] 来自模型的 tool_call.id，回传结果时必须原样带上，
 * 否则 OpenAI 会拒绝（"tool_call_id not found"）。
 */
data class ToolCall(
    val callId: String,
    val toolName: String,
    /** 模型给的原始 JSON 字符串，不要在这里解析 —— 让执行器解析，便于报错定位 */
    val argumentsJson: String,
)

/**
 * 工具执行结果。
 *
 * 永远不抛异常给上层：失败也是一种结果，要能喂回模型让它自己处理。
 * 这是 agent 类应用的基本要求 —— 模型看到错误才知道该怎么改。
 */
sealed interface ToolResult {
    val callId: String

    data class Success(
        override val callId: String,
        val output: String,
        /** 执行耗时，用于 UI 展示「思考中」 */
        val durationMs: Long,
        /** 输出是否被截断（超长时截断，避免撑爆上下文） */
        val truncated: Boolean = false,
    ) : ToolResult

    data class Failure(
        override val callId: String,
        val error: String,
        /** 是否可重试（网络类可重试，参数错误不可重试） */
        val retryable: Boolean = false,
    ) : ToolResult
}

/** 工具执行器的统一接口。 */
interface ToolExecutor {
    fun execute(call: ToolCall, workingDir: String? = null): ToolResult
}
