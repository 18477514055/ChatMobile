package com.chatmobile.net

/**
 * 厂商协议族。决定走哪套请求体 / 鉴权 / 流式解析。
 *
 * 新增一族时：加一个枚举值 + 一个 [StreamCodec] 实现 + [ProviderFactory] 一个分支，
 * 其余代码不动。
 */
enum class ProviderKind {
    /** OpenAI `/chat/completions` 协议族：DeepSeek / 通义 / 硅基流动 / OpenRouter / Kimi / 智谱。 */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API。**接口位置已预留，MVP 未实现**（见 README §P1）。 */
    ANTHROPIC,

    /** Google Gemini generateContent。**接口位置已预留，MVP 未实现**。 */
    GEMINI,
}

/**
 * **思考能力**在请求体里的形态。
 *
 * ★★★ 为什么必须单独建模（这是本轮调研最硬的结论）：
 *   > **「开关」和「强度」是两件事。** `thinking.type` / `enable_thinking` 控制**开不开**，
 *   > `reasoning_effort` 控制**开多大**。只传强度、不传开关，在部分模型上等于没调。
 *
 *   实测/文档确认的 6 类形态里，本项目（只做 OpenAI 兼容）用得上的有 4 类；
 *   Anthropic 的 `budget_tokens` 与 Gemini 的 `thinkingConfig` 属非 OpenAI 协议，本版不实现。
 *
 * ★ **值域一律不写死**：档位名从上游 `/models` 读（[ModelCapability.effortLevels]）；
 *   这里只决定**字段怎么摆**，不决定**能填什么**。
 */
enum class ThinkingShape {
    /** 只发档位：`reasoning_effort`。OpenAI 系 / DeepSeek / Kimi K3 / GLM-5.2+ / Mistral / Groq / Cerebras 等。 */
    EFFORT_ONLY,

    /** 开关 + 档位：`thinking: {type: "enabled"}` 与 `reasoning_effort` 并列。DeepSeek / GLM。 */
    EFFORT_PLUS_THINKING_SWITCH,

    /**
     * 布尔开关 + 推理预算：`enable_thinking: true`（+ 可选 `thinking_budget`）。
     * Qwen / DashScope / SiliconFlow(Qwen3 系)。
     *
     * ★ 这两家**不认 `reasoning_effort`** ⇒ 只发档位是**静默无效**，不报错、也没效果。
     */
    ENABLE_THINKING,

    /** 归一化对象：`reasoning: { effort: "high" }`。OpenRouter（`max_tokens` 是预算不是档位）。 */
    REASONING_OBJECT,

    /** 该家没有可用的思考开关 —— **一个字段都不发**。 */
    NONE,
}

/**
 * 一个厂商连接的全部配置（**运行时**对象，含密钥）。
 *
 * ★ [apiKey] 是敏感数据：
 * - [toString] 已覆写，**不会**把密钥带进日志 / 崩溃堆栈；
 * - 错误信息统一过 [redact]，防止密钥随异常文本泄漏到 UI。
 *
 * 密钥只能来自用户在设置页填写（本机加密存储），**不允许硬编码**。
 */
data class ProviderConfig(
    val id: String,
    val displayName: String,
    /** 形如 `https://api.deepseek.com/v1`，末尾有无 `/` 都可以（[joinUrl] 会归一）。 */
    val baseUrl: String,
    val apiKey: String,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    /** 相对 [baseUrl] 的对话路径。 */
    val chatPath: String = "chat/completions",
    /**
     * 模型列表的路径。
     *
     * ★★ **默认相对 [baseUrl]，但可以是绝对 URL** —— 这不是设计洁癖，
     *   而是真机上踩出来的硬需求。已经证实有一家（OpenCode）两个接口
     *   **前缀不同**：
     *     chat   → `/inference/openai/v1/chat/completions`
     *     models → `/inference/v1/models`          ← 少一段 `openai`
     *   实测 `/inference/openai/v1/models` 返回 **404**，
     *   而 `/inference/v1/models` 返回 **200**（78 个模型）。
     *
     * ⇒ 以 `http://` 或 `https://` 开头时**直接当绝对地址用**，不再拼 baseUrl。
     *   这比给每家加一个 `modelsBaseUrl` 字段简单，也不会影响其它 38 家。
     */
    val modelsPath: String = "models",
    /** **可选能力**：厂商是否支持 `GET /models`。false 时 [ModelsApi] 不发明细请求，直接返回 null。 */
    val supportsModelList: Boolean = true,
    /** 是否支持 `stream_options.include_usage`。个别网关会因此报 400，可关掉。 */
    val supportsStreamUsage: Boolean = true,
    /**
     * 是否把历史轮次的 reasoning 回传给服务端。
     * DeepSeek 仅在请求带 `tools` 时才需要；不带 tools 时传了也会被忽略。
     */
    val echoReasoning: Boolean = false,
    /** 额外请求头（如 OpenRouter 的 `HTTP-Referer` / `X-Title`）。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 预置的默认模型名，UI 可用来预填。 */
    val defaultModel: String? = null,
    /** 思考参数的**摆放形态**（见 [ThinkingShape]）。默认只发 `reasoning_effort`。 */
    val thinkingShape: ThinkingShape = ThinkingShape.EFFORT_ONLY,
    /**
     * 用 `max_completion_tokens` 而不是 `max_tokens` 表达输出上限。
     *
     * OpenAI 新模型（o 系列、GPT-5 之后）与 Groq 认前者；
     * 老模型与多数兼容网关只认 `max_tokens`（发了 `max_completion_tokens` 会被忽略或 400）。
     */
    val usesMaxCompletionTokens: Boolean = false,
) {
    /**
     * ★ 手写 toString：data class 自动生成的版本会把 apiKey 原样打出来，
     * 一旦有人 `log(config)` 或它出现在异常信息里，密钥就直接进了日志。
     */
    override fun toString(): String = buildString {
        append("ProviderConfig(id=").append(id)
        append(", kind=").append(kind)
        append(", baseUrl=").append(baseUrl)
        append(", apiKey=").append(if (apiKey.isEmpty()) "<empty>" else "***(len=${apiKey.length})")
        append(")")
    }
}

/**
 * 把 **相对路径** 拼到 baseUrl 上。两条都容忍多余的斜杠。
 *
 * 之所以不用 `HttpUrl.resolve()`：`resolve()` 在 baseUrl 没有结尾 `/` 时会把最后一段当文件名吃掉
 * （`https://x/v1` + `models` → `https://x/models`），是个经典坑。
 */
fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val rel = path.trim().trimStart('/')
    return when {
        base.isEmpty() -> if (rel.isEmpty()) "/" else "/$rel"
        rel.isEmpty() -> base
        else -> "$base/$rel"
    }
}

/**
 * 把文本里的密钥替换成 `***`。
 *
 * 长度 < 6 的串不做替换：太短容易误伤正常文本（而且真实密钥都远长于 6）。
 */
internal fun redact(text: String, secret: String): String =
    if (secret.length < 6 || !text.contains(secret)) text else text.replace(secret, "***")

// ================================================================
// ★ 厂商预置已迁到 Providers.kt（lead 维护）
//
// 这里曾有一份 5 个厂商的 ProviderPreset + object Providers，
// 与 Providers.kt 的 23 个厂商版本重名冲突（Redeclaration）。
//
// 裁决：**以 Providers.kt 为准**（数据来自 DSH 内置 provider 目录，43 个、
// 多接入点、含 Token Plan 与地域差异）。本文件只保留「运行时配置」这点职责。
//
// ProviderPreset → ProviderConfig 的转换见 Providers.kt 的 [toConfig]。
// ================================================================
