package com.chatmobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 一个模型的**能力档案** —— 从上游 `/models` 的响应里读出来的事实。
 *
 * ★★★ 为什么必须有这个东西（用户原话）：
 *   「思考强度每个模型的是不一样的，所以说得看上游拉取的实际信息是什么样。」
 *
 *   这正是行业现实：**同一个字段名，各家给的信息完全不同**。实测两家：
 *
 *   | 厂商 | 上下文上限 | 思考能力 | 支持强度档 |
 *   |---|---|---|---|
 *   | OpenCode | `limit.context` | `capabilities.reasoning.supported` | `capabilities.effort_updates` |
 *   | OpenRouter | `context_length` | `reasoning{...}` + `supported_parameters[]` | `supported_parameters` 含 `reasoning_effort` |
 *
 *   ⇒ 所以**不能写死一套档位**。这里做的是"读得到就用，读不到就返回 null 让上层隐藏该项"。
 *     **不知道比猜错好** —— 给用户一个厂商根本不认的档位，只会让他 400。
 */
data class ModelCapability(
    /** 上下文窗口（token）。null = 上游没说 */
    val contextLimit: Int?,
    /** 该模型是否支持思考/推理。null = 上游没说 */
    val reasoningSupported: Boolean?,
    /**
     * 支持的强度档位名（如 `["low","medium","high"]`）。
     *
     * ★ 空列表 = **上游没有声明档位**。这**不等于**"不支持思考"，
     *   而是"不知道怎么调"。上层应退化成"不显示强度选择"，
     *   而不是自己编一套 low/medium/high 糊上去。
     */
    val effortLevels: List<String>,
) {
    /**
     * 强度选择器是否该出现。
     *
     * ★ 判据是**上游明确声明过档位**，不是"支持思考"。
     *   很多模型支持思考但没有可调档位（`effort_updates: false`）——
     *   对这种显示一个滑杆是骗人的。
     */
    val showsEffortPicker: Boolean get() = effortLevels.isNotEmpty()
}

/**
 * 从 `/models` 响应里解析能力档案。
 *
 * 为**零依赖、纯函数**：不碰网络、不碰 Android，可以完整单测。
 * 每次 [ModelsApi] 拉到列表时顺带解析，不需要额外的请求。
 */
object ModelCapabilities {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 常见的占位符档位名 —— 很多厂商在 `supported_parameters` 里只写个笼统的名字。 */
    private val EFFORT_NAMES = setOf(
        "reasoning_effort", "reasoning", "effort", "thinking", "thinking_budget",
    )

    /**
     * 解析整份响应，返回 `id -> 能力` 的映射。
     *
     * ★ 解析失败的条目**跳过而不是整份失败**：一家 78 个模型，偶尔一条字段怪
     *   不该让其余 77 条都用不了。
     */
    fun parse(body: String): Map<String, ModelCapability> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val data = root["data"] as? JsonArray ?: return emptyMap()

        return buildMap {
            for (el in data) {
                val obj = el as? JsonObject ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNullSafe() ?: continue
                if (id.isBlank()) continue
                put(id, one(obj))
            }
        }
    }

    /** 解析单个模型条目。字段名各家不同，**两种都认**。 */
    fun one(obj: JsonObject): ModelCapability =
        ModelCapability(
            contextLimit = readContextLimit(obj),
            reasoningSupported = readReasoningSupported(obj),
            effortLevels = readEffortLevels(obj),
        )

    /**
     * 上下文上限 —— 各家写法实测有 6 类（按优先级，先命中的算）：
     *
     * | 来源 | 谁在用 |
     * |---|---|
     * | `limit.context` | OpenCode |
     * | `context_length` / `context_window` / `max_context_length` | OpenRouter、部分自建网关 |
     * | `top_provider.context_length` | OpenRouter（嵌套在 top_provider 里） |
     * | `model_info.context_window` | DashScope / 阿里百炼 |
     * | `max_model_len` | vLLM 系网关（SiliconFlow、Together 自建等） |
     * | `limit.input` | 仅当上面全都没有时的**兜底** |
     *
     * ★ `limit.input` 语义是「**单次输入**上限」，与总窗口**不是一回事**
     *   （如 OpenCode：context=200000 / input=160000 / output=32000）。
     *   所以它**只在没有总窗口时才顶上来** —— 有 `context` 时用它会把上限报小，
     *   而用户被允许「任意调整到厂家真正的上限」，报小是直接的伤害。
     */
    private fun readContextLimit(obj: JsonObject): Int? {
        (obj["limit"] as? JsonObject)?.get("context")?.let { v ->
            (v as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
        }
        for (key in listOf("context_length", "context_window", "max_context_length")) {
            (obj[key] as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
        }
        (obj["top_provider"] as? JsonObject)?.get("context_length")?.let { v ->
            (v as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
        }
        (obj["model_info"] as? JsonObject)?.let { mi ->
            for (key in listOf("context_window", "context_length")) {
                (mi[key] as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
            }
        }
        (obj["max_model_len"] as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
        // 兜底：只知道单次输入上限
        (obj["limit"] as? JsonObject)?.get("input")?.let { v ->
            (v as? JsonPrimitive)?.intOrNull?.let { if (it > 0) return it }
        }
        return null
    }

    /**
     * 是否支持思考。三种写法：
     *   `capabilities.reasoning.supported` = true   （OpenCode）
     *   `capabilities.reasoning` 是个对象/true      （其它网关）
     *   `reasoning` 字段存在且非 null               （OpenRouter）
     */
    private fun readReasoningSupported(obj: JsonObject): Boolean? {
        val cap = obj["capabilities"] as? JsonObject
        if (cap != null) {
            when (val r = cap["reasoning"]) {
                is JsonObject -> (r["supported"] as? JsonPrimitive)?.booleanOrNull?.let { return it }
                is JsonPrimitive -> r.booleanOrNull?.let { return it }
                else -> Unit
            }
        }
        if (obj.containsKey("reasoning")) {
            // OpenRouter：`reasoning` 非 null 即表示该模型有推理能力
            return obj["reasoning"] !is kotlinx.serialization.json.JsonNull
        }
        return null
    }

    /**
     * 可调的强度档位。
     *
     * ★★★ 这是**最不可能统一**的一项 —— 各家档位名与数量都不同。
     *   实测 OpenCode Go 的 28 个模型，档位表有 **11 种不同形态**：
     *   `["low","high","max"]` / `["high","max"]` / `["low","medium","high","xhigh"]` /
     *   `["minimal","low","medium","high","xhigh"]` / `["none","low","high"]` /
     *   `["max"]` … 还有 7 个模型**根本没有** `effort` 数组。
     *   这正好印证用户那句「思考强度每个模型的是不一样的，所以说得看上游
     *   拉取的实际信息是什么样」。
     *
     * 能读到的来源（按优先级）：
     *   1. **`capabilities.reasoning.effort`（数组）** ← OpenCode 实测就是这个
     *   1b. **`reasoning.supported_efforts`（数组）** ← OpenRouter 实测形态
     *      （OpenRouter 的 451 个模型以前**一个档位都显示不出来**，就是因为漏了这一条）
     *   2. `reasoningEfforts` / `reasoning_efforts` —— 显式声明的 `{档位: API值}` 表
     *      （DSH 自己配置文件里的形态，见 DSH 客户端 bundle 里的 `readEfforts`）
     *   3. 都没有 ⇒ 空
     *
     * ★ 刻意**不从 `supported_parameters` 里编出档位**：它只声明参数名，
     *   编 low/medium/high 上去就是在猜，而猜错会让用户 400。
     *
     * ★ 注意 `capabilities.reasoning.effort_updates` **不是**档位表 ——
     *   它是"运行中能否改档位"的布尔量。实测 `effort_updates: false` 的模型
     *   依然有 `effort` 数组（deepseek-v4.1-flash 就是这样），所以这两个字段
     *   **不能混为一谈**。
     */
    private fun readEffortLevels(obj: JsonObject): List<String> {
        // 来源 1：capabilities.reasoning.effort（OpenCode 实测形态）
        (obj["capabilities"] as? JsonObject)
            ?.get("reasoning")?.let { r ->
                if (r is JsonObject) {
                    val fromEffort = readStringArray(r["effort"])
                    if (fromEffort.isNotEmpty()) return fromEffort
                    // 来源 1b：capabilities.reasoning.supported_efforts
                    val fromSupported = readStringArray(r["supported_efforts"])
                    if (fromSupported.isNotEmpty()) return fromSupported
                }
            }

        // 来源 1b：顶层 reasoning.supported_efforts（OpenRouter 实测形态）
        (obj["reasoning"] as? JsonObject)?.let { r ->
            for (key in listOf("supported_efforts", "efforts", "effort")) {
                val levels = readStringArray(r[key])
                if (levels.isNotEmpty()) return levels
            }
        }

        // 来源 2：显式声明的键值表 / 数组
        for (key in listOf("reasoningEfforts", "reasoning_efforts", "effort_levels", "efforts")) {
            val v = obj[key]
            if (v is JsonObject) {
                val levels = v.keys.filter { it.isNotBlank() }
                if (levels.isNotEmpty()) return levels
            }
            val levels = readStringArray(v)
            if (levels.isNotEmpty()) return levels
        }
        return emptyList()
    }

    /** 把一个 JSON 节点当字符串数组读；不是数组或全空 ⇒ 空列表。 */
    private fun readStringArray(v: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = v as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            .filter { it.isNotBlank() }
    }

    /** 判断 `supported_parameters` 里是否声明了强度参数（供上层决定是否提示"可调"）。 */
    fun declaresEffortParameter(obj: JsonObject): Boolean {
        val sp = obj["supported_parameters"] as? JsonArray ?: return false
        return sp.any { el ->
            val name = (el as? JsonPrimitive)?.contentOrNullSafe().orEmpty()
            name in EFFORT_NAMES
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
