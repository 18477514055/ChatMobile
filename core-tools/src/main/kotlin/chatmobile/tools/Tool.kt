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
    /**
     * **工具使用示例** —— 只给「参数有坑」的工具加，默认空。
     *
     * ## ★ 为什么这是个高性价比字段（有出处）
     *
     * Anthropic 官方实测：加上工具使用示例后，**复杂参数的准确率从 72% 提升到 90%**
     * （`tool use examples` / `input_examples`，见 `_ledger` 里那份调研报告的量化基线）。
     * 原因很直接 —— **JSON Schema 表达不了「怎么用」**：
     *
     * - 某个参数在什么情况下该省、什么情况下该显式给反值；
     * - 两个参数的组合约定（`mode=recent` 时 `query` 可以为空）；
     * - 某个开关什么时候该翻成 true（`use_paid`）。
     *
     * 这些在自然语言描述里要写一长段，模型还未必照做；给一条能照抄的 JSON 又快又准。
     *
     * ## ★★ 为什么只给少数工具加，不是全加
     *
     * 示例会**永久占据每一轮的输入 token**（工具定义在缓存前缀里）。10 个工具全加示例
     * 会让工具表从 2062 字符涨到四五千 —— 省下来的那点上下文又吐回去了。
     * ⇒ 纪律：**只给"模型真的会传错"的工具加**。判断依据是有没有真实的踩坑场景，
     *   不是"这个工具重不重要"。
     *
     * ## ★★★ 为什么拼进 description，而不是当成 tools JSON 的新字段
     *
     * OpenAI function calling 的 `function` 对象只有 `name` / `description` /
     * `parameters` / `strict`；Anthropic 的 `input_examples` 是它**自己 API 专有**的字段。
     * 我们走的是 OpenAI 兼容协议、接 46 家厂商 —— 多塞一个它们不认识的字段，
     * 严格的网关会直接 400（和 `additionalProperties` 缺失是同一类事故）。
     * ⇒ **只能写进 description 文本里**，这是唯一在所有厂商都安全的位置。
     */
    val examples: List<ToolExample> = emptyList(),
    /**
     * **原样透传的参数 schema** —— 给 MCP 工具用。非空时**完全取代** [parameters]。
     *
     * ## ★★★ 为什么必须有这个字段（MCP 接入的硬需求）
     *
     * MCP 服务端给的 `inputSchema` 是一份**完整标准 JSON Schema**：可能有
     * `oneOf` / `anyOf` / `$ref` / `default` / `pattern` / 嵌套对象 / 数组的元组写法……
     *
     * 我们的 [ToolParameters] 是个**刻意简化**的子集（对象 + 基本类型 + enum + 数组），
     * 表达不了这些。两条路：
     *
     * | 做法 | 后果 |
     * |---|---|
     * | 把 MCP schema 降级解析成 [ToolParameters] | **静默丢掉约束** ⇒ 模型不知道 `oneOf` 的分支，参数必然传错 |
     * | **原样透传**（本字段） | 模型看到的与服务端声明**完全一致**，零翻译损失 |
     *
     * ⇒ 选第二条。翻译不但是体力活，而且**翻译得越像就越容易翻译错** ——
     *   这类"中间层自己引入 bug"的事这个项目已经踩过（见 L26 的同源教训）。
     *
     * ## ★ 纪律
     *
     * - 非空时 `parameters` **不被使用**（序列化走 [ToolRegistry] 的分支）。
     * - 必须是**合法 JSON 对象字面量**；有契约测试钉住。
     * - 只给 MCP 工具用；内建工具继续用结构化的 [parameters]（可读性好、能单测）。
     */
    val rawParametersJson: String? = null,
) {

    /**
     * 实际要发给模型的参数 schema JSON。
     *
     * ★ 唯一入口 —— 序列化与 `describe_tool` 都走这里，避免两处各写一份（L26 的形状）。
     */
    fun parametersJson(): String =
        rawParametersJson?.takeIf { it.isNotBlank() } ?: ToolParametersJson.encode(parameters)

    /**
     * **发给模型的完整描述** —— description + 使用示例。
     *
     * ★★★ 两个消费点都**必须**走这个函数，不许各自拼：
     *   - `ToolRegistry.toOpenAiToolsJson()`（常驻工具进 `tools` 数组）
     *   - `ChatToolExecutor.describeTool()`（懒加载工具被召回时）
     *
     * 这正是 `L26`（设置项存了 ≠ 生效）的同一个形状 —— 数据加在一个地方、
     * 消费点在另一个地方各写一份，于是懒加载的工具永远拿不到示例。
     * 收成一个函数 + 一条契约测试，就没有第二条路可走。
     */
    fun fullDescription(): String {
        if (examples.isEmpty()) return description
        val sb = StringBuilder(description)
        sb.append("\n\nExamples (each line is a complete arguments object you can copy):")
        for (e in examples) {
            sb.append("\n- ").append(e.whenTo).append(": ").append(e.args)
        }
        return sb.toString()
    }
}

/**
 * 一条**工具使用示例**。
 *
 * @param whenTo 什么时候该这样传参数（一句话，写场景不写语法）
 * @param args 完整、可直接复制的参数 JSON 对象（**必须能真的解析**，有契约测试钉住）
 */
data class ToolExample(
    val whenTo: String,
    val args: String,
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
