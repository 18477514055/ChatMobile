package chatmobile.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具执行需要的**平台能力** —— 由 App 层实现，`core-tools` 只认这个接口。
 *
 * ## ★ 为什么用接口而不是直接调 Android API
 *
 * 1. **可单测**：`core-tools` 是纯 JVM 模块，没有 Context、没有 `android.util.Log`。
 *    让 App 层把能力注进来，执行器本身就能用普通单元测试钉死。
 * 2. **职责清楚**：`core-tools` 决定"怎么把结果组织成模型能读的文本"，
 *    App 层决定"去哪里取值"（时钟、LocationManager、OkHttp、记忆库）。
 * 3. **不泄露 Android 依赖到 core**：`core-tools` 的 build.gradle 里没有 android 依赖，
 *    一旦有人在这里 `import android.*`，编译立刻失败 —— 这是**结构性的守卫**。
 *
 * ★ 每个方法都**必须可失败**且**不抛异常**：工具调用失败是一种正常结果，
 *   要作为数据喂回模型让它自己收场，而不是炸掉整条对话。
 */
interface ToolHost {

    /**
     * 当前本地时间。
     *
     * @param timezone IANA 时区名；空 = 用设备时区。
     * @return 给人/模型读的一行文本；时区名非法时返回带错误说明的文本（不抛异常）。
     */
    fun nowText(timezone: String?): String

    /**
     * 大致位置。
     *
     * @param precise true = 尝试精确定位；false = 只要城市级。
     * @return 一行文本；没有权限/没有定位时**必须如实说明**，不许编造。
     */
    fun locationText(precise: Boolean): String

    /**
     * 抓一个网页的正文。
     *
     * ★ 必须是**同步**调用（工具执行发生在 IO 调度器上）。
     * @return 去掉 HTML 标签后的文本；失败返回以 `ERROR:` 开头的一行说明。
     */
    fun fetchUrl(url: String, maxChars: Int): String

    /**
     * 写一条记忆，返回一句确认文本。
     *
     * @param category 记忆分类（preference / user / project / person / event / other）。
     */
    fun saveMemory(content: String, category: String?): String

    /** 在记忆库里按关键词检索，返回多行文本（无命中返回明确的空结果说明）。 */
    fun searchMemory(query: String): String
}

/**
 * 工具执行器 —— 把模型的 [ToolCall] 变成喂回给它的文本。
 *
 * ## ★★★ 三条实现纪律
 *
 * 1. **永不抛异常**：任何失败都返回 [ToolResult.Failure]，由上层回填给模型。
 *    模型看到错误才知道该怎么改；抛异常则整条对话直接断掉。
 * 2. **结果必须截断**：网页正文可能几十万字，一次塞进上下文会爆掉——
 *    而且它出现在**消息末尾**，会让后面每一轮的缓存都变。
 * 3. **参数解析失败要报清楚缺哪个**，而不是笼统说"参数错误"——
 *    模型据此能自我修正，否则它会原样重试。
 */
class ChatToolExecutor(
    private val host: ToolHost,
    /** 单个工具结果回填的最大字符数。 */
    private val maxResultChars: Int = 8000,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 执行一次调用。
     *
     * @param enabled 当前用户启用的工具名集合 —— **未启用的工具一律拒绝**，
     *   即使模型硬要调。这是"用户在设置里关掉它"能真正生效的地方
     *   （模型可能从历史里见过旧工具名）。
     */
    fun execute(call: ToolCall, enabled: Set<String>): ToolResult {
        if (call.toolName !in enabled) {
            return ToolResult.Failure(
                call.callId,
                "工具 '${call.toolName}' 已被用户关闭。请直接用你已有的信息回答，" +
                    "不要重试，也不要假装调用过它。",
            )
        }

        val args = parseArgs(call.argumentsJson)
            ?: return ToolResult.Failure(
                call.callId,
                "参数不是合法的 JSON 对象：${call.argumentsJson.take(200)}",
            )

        val started = System.currentTimeMillis()
        return try {
            val output = when (call.toolName) {
                "get_time" -> host.nowText(args.str("timezone"))
                "get_location" -> host.locationText(precise = args.str("accuracy") == "precise")
                "web_fetch" -> {
                    val url = args.str("url")
                    if (url.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 url")
                    }
                    host.fetchUrl(url, clampMaxChars(args.int("max_chars")))
                }
                "save_memory" -> {
                    val content = args.str("content")
                    if (content.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 content")
                    }
                    host.saveMemory(content, args.str("category"))
                }
                "search_memory" -> {
                    val q = args.str("query")
                    if (q.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 query")
                    }
                    host.searchMemory(q)
                }
                else -> return ToolResult.Failure(
                    call.callId,
                    "未知工具 '${call.toolName}'。可用：${enabled.joinToString(", ")}",
                )
            }

            // ★ 以 ERROR: 开头的输出是宿主侧的**受控失败**（网络失败、没权限等），
            //   不是异常。仍然算"成功执行"，因为模型需要读到那句说明才知道怎么办。
            val truncated = output.length > maxResultChars
            ToolResult.Success(
                callId = call.callId,
                output = if (truncated) {
                    output.take(maxResultChars) +
                        "\n\n…（结果过长已截断，原长 ${output.length} 字符）"
                } else {
                    output
                },
                durationMs = System.currentTimeMillis() - started,
                truncated = truncated,
            )
        } catch (e: Throwable) {
            // ★ 兜底：任何未预料的异常都变成失败结果，而不是往上抛。
            ToolResult.Failure(
                call.callId,
                "工具 '${call.toolName}' 执行失败：" + (e.message ?: e::class.simpleName),
                retryable = false,
            )
        }
    }

    /** `max_chars` 钳在 1000..20000：太小读不到内容，太大撑爆上下文。 */
    private fun clampMaxChars(raw: Int?): Int = (raw ?: 8000).coerceIn(1000, 20000)

    private fun parseArgs(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
}
