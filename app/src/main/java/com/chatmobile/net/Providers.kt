package com.chatmobile.net

/**
 * 内置的模型提供商预设。
 *
 * ★ 数据来源：**DSH 内置的 provider 目录**
 *   （`@deepseek-ai/dsh-llm-pi-ai` 的 `PROVIDER_DATA`，共 42 个 provider）。
 *   这是实际在用的配置，比各厂商官网更准确 —— 尤其体现在「同一厂商多接入点」上。
 *
 * ★ 关于「多接入点」：这不是重复配置，而是**真实存在的不同途径**。
 *   同一个厂商可能同时有：
 *     - API 直连（按量计费）
 *     - Token Plan（包月/包年套餐，**独立域名**）
 *     - 不同地域（国内 / 国际 / 新加坡 / 阿姆斯特丹）
 *     - 不同协议（OpenAI 兼容 / Anthropic 兼容）
 *   最典型的是**小米（4 个）**和**通义（3 个）**。
 *
 * ★ 本项目只做 **OpenAI 兼容**协议。所以：
 *   - Anthropic 原生端点（如 `minimax` 的 `/anthropic`）**暂不收录**
 *   - Gemini 原生端点（`google` 的 `/v1beta`）**暂不收录**
 *   等以后做多协议支持时再补。
 */
data class ProviderPreset(
    /** 稳定标识，存进设置里。**不要改**，改了就丢用户配置 */
    val id: String,
    val displayName: String,
    val endpoints: List<Endpoint>,
    val commonModels: List<String> = emptyList(),
    /** 该厂商是否支持 GET /v1/models（实测/文档确认） */
    val supportsModelList: Boolean = true,
    /**
     * 模型列表路径的**覆盖值**（绝对 URL 或相对路径）。
     *
     * ★ 空 = 用默认的 `models`（拼在 baseUrl 后）。
     * ★ 非空 = 覆盖。**必须是绝对 URL** 当 chat 与 models 前缀不同时。
     *
     * 已证实的案例（实测 HTTP 码）：
     *   OpenCode   chat `/inference/openai/v1/...`，models `/inference/v1/models`
     *              ⇒ 用 chat 的前缀去拉模型会 **404**，用这个才对 **200**
     *   OpenCode Go 同理，多一段 `/go`
     * ⇒ 这不是"某个厂商特殊"，而是 OpenAI 兼容网关的常见形态：
     *   chat 端点按协议分家（/openai/v1、/anthropic/v1），模型目录只有一个。
     */
    val modelsPathOverride: String = "",
    /**
     * **静态**默认请求头（不含密钥，可硬编码）。
     *
     * ★ 与用户填的 key 分开：key 走 `Authorization`，这里的都是"声明我是谁"的元信息。
     */
    val defaultHeaders: Map<String, String> = emptyMap(),
    /**
     * **必须带稳定会话 ID 的头名**。空 = 这家不需要。
     *
     * ★★★ 这是真机上撞出来的：OpenCode Go 对不带 `x-opencode-session` 的请求
     *   直接 **400 MissingSessionID**，原文：
     *   > "Request is missing x-opencode-session and cannot be routed efficiently."
     *
     *   官方文档（`/v2/docs/console/go/`，逐字）：
     *   > "Send a stable session ID in `x-opencode-session` for each conversation
     *      so we can optimize routing and prompt caching."
     *
     *   ⇒ 关键词是 **stable**（稳定）：**必须按对话固定不变**，不能每次请求随机。
     *     随机的话每一轮都是新会话 ⇒ 路由与缓存全失效，而且照样被当成异常流量。
     *     所以我们的 `sessionId` 传的是 **conversationId**。
     */
    val sessionHeaderName: String = "",
    val docUrl: String = "",
    val note: String = "",
    /**
     * 思考参数的**摆放形态** —— 决定请求体里发 `reasoning_effort` / `thinking{}` /
     * `enable_thinking` / `reasoning{}` 中的哪几样。
     *
     * ★ 不写值域：档位名来自上游 `/models`（[ModelCapability.effortLevels]）。
     * ★ 依据见 `_research/提供方-思考强度与上下文上限-字段总表.md` §3 逐家总表。
     */
    val thinkingShape: ThinkingShape = ThinkingShape.EFFORT_ONLY,
    /** 用 `max_completion_tokens` 而不是 `max_tokens`（OpenAI 新模型 / Groq）。 */
    val usesMaxCompletionTokens: Boolean = false,
) {
    val defaultEndpoint: Endpoint get() = endpoints.first()

    /**
     * 转成运行时配置。
     *
     * 两个模型的职责分工：
     * - [ProviderPreset] / [Endpoint] 是**静态目录**（不含密钥，可硬编码、可随包分发）
     * - [ProviderConfig] 是**运行时对象**（含密钥，有防泄漏 toString）
     *
     * @param sessionId 该对话的**稳定** ID，用于 [sessionHeaderName] 指定的头。
     *   空 = 本对话还没建立 ID，此时**不伪造**一个随机值（那比不带更糟）。
     */
    fun toConfig(
        endpoint: Endpoint = defaultEndpoint,
        apiKey: String,
        model: String? = null,
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        extraHeaders: Map<String, String> = emptyMap(),
        sessionId: String = "",
    ): ProviderConfig = ProviderConfig(
        id = id,
        displayName = displayName,
        baseUrl = endpoint.baseUrl.trimEnd('/'),
        apiKey = apiKey,
        kind = kind,
        // ★ 空字符串 → 用默认 `models`；非空 → 覆盖（可能是绝对 URL）
        modelsPath = modelsPathOverride.ifBlank { "models" },
        supportsModelList = supportsModelList,
        extraHeaders = buildMap {
            // 顺序：静态默认头 → 会话头 → 调用方显式传入（后者覆盖前者）
            putAll(defaultHeaders)
            if (sessionHeaderName.isNotBlank() && sessionId.isNotBlank()) {
                put(sessionHeaderName, sessionId)
            }
            putAll(extraHeaders)
        },
        defaultModel = model ?: commonModels.firstOrNull(),
        thinkingShape = thinkingShape,
        usesMaxCompletionTokens = usesMaxCompletionTokens,
    )
}

data class Endpoint(
    /** 用来说明「为什么有多个」，如「Token Plan」「国际站」 */
    val label: String,
    /** OpenAI 兼容 base URL，**不含** /chat/completions */
    val baseUrl: String,
    val note: String = "",
    val recommended: Boolean = false,
    /** base URL 中是否含需要用户填写的占位符 */
    val requiresSubstitution: Boolean = false,
)

object Providers {

    /**
     * 我们自报的 User-Agent。
     *
     * ★ 为什么要有：OpenCode 的文档明确要求 ——
     *   > "Identify itself with its own user agent, such as `my-coding-agent/1.0`,
     *      rather than a generic SDK or HTTP-library name."
     *   它会按 UA 判断"这是不是编程代理流量"。用 OkHttp 默认的 `okhttp/x.y.z`
     *   属于它点名不想要的那种，容易被当成异常流量。
     *
     * ★ 带版本号：出问题时要能从服务端日志里认出是哪一版。
     */
    const val USER_AGENT = "ChatMobile/1.0"

    // ==============================================================
    // 国内厂商
    // ==============================================================

    val DeepSeek = ProviderPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        endpoints = listOf(
            Endpoint("官方", "https://api.deepseek.com", recommended = true),
        ),
        commonModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        docUrl = "https://platform.deepseek.com/api_keys",
        note = "另有 Anthropic 兼容端点 /anthropic（本项目不用）",
        // DeepSeek 的思考是「开关 + 强度」两件事：thinking.type=enabled/disabled，
        // 强度走 reasoning_effort（low/high/max，默认 high）。
        thinkingShape = ThinkingShape.EFFORT_PLUS_THINKING_SWITCH,
    )

    /**
     * 月之暗面 Kimi —— **两个站，Key 不通用**
     */
    val Moonshot = ProviderPreset(
        id = "moonshotai",
        displayName = "Kimi（月之暗面）",
        endpoints = listOf(
            Endpoint("国内站", "https://api.moonshot.cn/v1", recommended = true),
            Endpoint("国际站", "https://api.moonshot.ai/v1", note = "Key 与国内站不通用"),
        ),
        commonModels = listOf("kimi-k3.1-pro", "moonshot-v1-auto"),
        docUrl = "https://platform.kimi.com/docs/api/chat",
        // ★ 刻意用默认的 EFFORT_ONLY：Kimi **按模型分家** ——
        //   k3 系只认顶层 reasoning_effort、且**明确不接受 thinking**；
        //   k2.6 / k2.7 走 thinking.type。预设是「整家」粒度，选不出中间态，
        //   所以选那个**对所有模型都安全**的：只发档位。
        //   K2.6 的思考默认 enabled ⇒ 不发开关也不丢能力。
    )

    /** Kimi 编程套餐 —— 独立域名 */
    val KimiCoding = ProviderPreset(
        id = "kimi-coding",
        displayName = "Kimi 编程套餐",
        endpoints = listOf(
            Endpoint("Coding", "https://api.kimi.com/coding/v1", recommended = true),
        ),
        docUrl = "https://platform.kimi.com/",
        note = "套餐专用端点，与按量计费的 Key 不通用",
        // ★★ 实测：目录在 /coding/v1/models（401 = 路径存在、缺鉴权）。
        //    之前 baseUrl 少了一段 /v1 ⇒ 拉模型会 404。
    )

    /**
     * ★ 通义千问 —— **3 个 Token Plan + 2 个普通 API**
     *
     * Token Plan 是套餐制，走独立的 `token-plan*.maas.aliyuncs.com` 域名；
     * 普通 API 走 `dashscope.aliyuncs.com`。两者**完全不同**。
     */
    val Qwen = ProviderPreset(
        id = "qwen",
        displayName = "通义千问（阿里云百炼）",
        endpoints = listOf(
            Endpoint(
                "Token Plan（新加坡）",
                "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
                note = "套餐制，用套餐 Key",
                recommended = true,
            ),
            Endpoint(
                "Token Plan（北京）",
                "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                note = "套餐制，用套餐 Key",
            ),
            Endpoint(
                "API 直连（北京，业务空间）",
                "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                note = "按量计费；{WorkspaceId} 换成你的业务空间 ID",
                requiresSubstitution = true,
            ),
            Endpoint(
                "API 直连（北京，旧域名）",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                note = "官方说仍可用，建议迁到专属域名",
            ),
            Endpoint(
                "API 直连（新加坡）",
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                note = "Key 与北京地域不通用",
            ),
        ),
        commonModels = listOf("qwen3.8-max", "qwen-plus", "qwen-max"),
        docUrl = "https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope",
        note = "★ Key 按地域绑定，跨地域调用会 401；套餐 Key 与按量 Key 不通用",
        // ★★ 阿里云百炼 / DashScope **不认 reasoning_effort** —— 只发档位是静默无效。
        //    它要的是 enable_thinking（布尔）+ 可选 thinking_budget（整数 token）。
        thinkingShape = ThinkingShape.ENABLE_THINKING,
    )

    /**
     * 智谱 —— **两个体系**
     * `zai` 是国际站（z.ai），`zai-coding-cn` 是国内编程套餐（bigmodel.cn）
     */
    val Zhipu = ProviderPreset(
        id = "zai",
        displayName = "智谱 GLM",
        endpoints = listOf(
            Endpoint(
                "国内编程套餐",
                "https://open.bigmodel.cn/api/coding/paas/v4",
                recommended = true,
                note = "bigmodel.cn，编程套餐专用路径",
            ),
            Endpoint(
                "国际站",
                "https://api.z.ai/api/coding/paas/v4",
                note = "z.ai，国际站 Key",
            ),
            Endpoint(
                "标准 API（国内）",
                "https://open.bigmodel.cn/api/paas/v4",
                note = "按量计费，非编程套餐",
            ),
        ),
        commonModels = listOf("glm-5.3", "glm-5.3-flash", "glm-4-plus"),
        supportsModelList = false,
        docUrl = "https://docs.bigmodel.cn/cn/guide/start/introduction",
        note = "/v1/models 未确认，建议手动填模型名",
        // 智谱 = thinking.type（开关）+ reasoning_effort（强度，默认 max）。
        thinkingShape = ThinkingShape.EFFORT_PLUS_THINKING_SWITCH,
    )

    /**
     * ★ 小米 —— **4 个接入点，最典型的「一厂多接口」**
     */
    val Xiaomi = ProviderPreset(
        id = "xiaomi",
        displayName = "小米 MiMo",
        endpoints = listOf(
            Endpoint("API 直连", "https://api.xiaomimimo.com/v1", recommended = true),
            Endpoint(
                "Token Plan（中国）",
                "https://token-plan-cn.xiaomimimo.com/v1",
                note = "套餐制",
            ),
            Endpoint(
                "Token Plan（新加坡）",
                "https://token-plan-sgp.xiaomimimo.com/v1",
                note = "套餐制",
            ),
            Endpoint(
                "Token Plan（阿姆斯特丹）",
                "https://token-plan-ams.xiaomimimo.com/v1",
                note = "套餐制",
            ),
        ),
        docUrl = "https://platform.xiaomimimo.com/",
        note = "4 个接入点：直连 + 3 个地域套餐",
    )

    /** MiniMax —— 注意：官方主推 Anthropic 端点，OpenAI 兼容端点待确认 */
    val MiniMax = ProviderPreset(
        id = "minimax",
        displayName = "MiniMax",
        endpoints = listOf(
            Endpoint(
                "国际站",
                "https://api.minimax.io/anthropic",
                note = "★ DSH 里记的是 Anthropic 端点，OpenAI 兼容请自行确认",
            ),
            Endpoint(
                "国内站",
                "https://api.minimaxi.com/anthropic",
                note = "同上",
            ),
        ),
        supportsModelList = false,
        note = "⚠️ 官方主推 Anthropic 协议，本项目暂只做 OpenAI 兼容，可能不可用",
    )

    val SiliconFlow = ProviderPreset(
        id = "siliconflow",
        displayName = "硅基流动",
        endpoints = listOf(
            Endpoint("官方", "https://api.siliconflow.cn/v1", recommended = true),
        ),
        commonModels = listOf(
            "deepseek-ai/DeepSeek-V4-Flash",
            "moonshotai/Kimi-K2.7-Code",
            "Qwen/Qwen3-235B",
        ),
        docUrl = "https://docs.siliconflow.cn/docs/api/chat-completions-post",
        note = "聚合平台，模型名带组织前缀",
        // ★★ 硅基流动的 Qwen3 系同样**不认 reasoning_effort**，要 enable_thinking + thinking_budget。
        //    （它托管的 DeepSeek / Kimi 实际走各自上游的字段，日志里会明说"非请求参数"。）
        thinkingShape = ThinkingShape.ENABLE_THINKING,
    )

    // ==============================================================
    // 国际厂商
    // ==============================================================

    val OpenAI = ProviderPreset(
        id = "openai",
        displayName = "OpenAI",
        endpoints = listOf(
            Endpoint("官方", "https://api.openai.com/v1", recommended = true),
        ),
        commonModels = listOf("gpt-4o", "gpt-4o-mini", "o3"),
        docUrl = "https://platform.openai.com/api-keys",
        note = "★ Chat Completions 不返回推理内容；推理走 Responses API（未接）",
        // OpenAI 认 reasoning_effort（逐模型值域不同），且新模型只认 max_completion_tokens。
        usesMaxCompletionTokens = true,
    )

    val OpenRouter = ProviderPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        endpoints = listOf(
            Endpoint("官方", "https://openrouter.ai/api/v1", recommended = true),
        ),
        commonModels = listOf(
            "openai/gpt-4o",
            "anthropic/claude-sonnet-4",
            "google/gemini-2.5-pro",
            "deepseek/deepseek-chat",
        ),
        docUrl = "https://openrouter.ai/docs/quickstart",
        note = "聚合平台；推理字段归一化成 reasoning + reasoning_details",
        // OpenRouter 用归一化的 `reasoning: { effort, max_tokens }` 对象。
        thinkingShape = ThinkingShape.REASONING_OBJECT,
    )

    val Groq = ProviderPreset(
        id = "groq",
        displayName = "Groq",
        endpoints = listOf(
            Endpoint("官方", "https://api.groq.com/openai/v1", recommended = true),
        ),
        docUrl = "https://console.groq.com/docs/openai",
        note = "以推理速度著称",
        // Groq 与 OpenAI 新模型一致：输出上限用 max_completion_tokens。
        usesMaxCompletionTokens = true,
    )

    val XAI = ProviderPreset(
        id = "xai",
        displayName = "xAI Grok",
        endpoints = listOf(
            Endpoint("官方", "https://api.x.ai/v1", recommended = true),
        ),
        commonModels = listOf("grok-4", "grok-3"),
        docUrl = "https://docs.x.ai/",
    )

    val Mistral = ProviderPreset(
        id = "mistral",
        displayName = "Mistral",
        endpoints = listOf(
            Endpoint("官方", "https://api.mistral.ai/v1", recommended = true),
        ),
        docUrl = "https://docs.mistral.ai/",
    )

    val Together = ProviderPreset(
        id = "together",
        displayName = "Together AI",
        endpoints = listOf(
            Endpoint("官方", "https://api.together.ai/v1", recommended = true),
        ),
        docUrl = "https://docs.together.ai/",
    )

    val Fireworks = ProviderPreset(
        id = "fireworks",
        displayName = "Fireworks AI",
        endpoints = listOf(
            Endpoint("官方", "https://api.fireworks.ai/inference/v1", recommended = true),
        ),
        docUrl = "https://docs.fireworks.ai/",
    )

    val Cerebras = ProviderPreset(
        id = "cerebras",
        displayName = "Cerebras",
        endpoints = listOf(
            Endpoint("官方", "https://api.cerebras.ai/v1", recommended = true),
        ),
        docUrl = "https://inference-docs.cerebras.ai/",
    )

    val HuggingFace = ProviderPreset(
        id = "huggingface",
        displayName = "Hugging Face",
        endpoints = listOf(
            Endpoint("Router", "https://router.huggingface.co/v1", recommended = true),
        ),
        docUrl = "https://huggingface.co/docs/inference-providers",
    )

    val VercelAIGateway = ProviderPreset(
        id = "vercel-ai-gateway",
        displayName = "Vercel AI Gateway",
        endpoints = listOf(
            Endpoint("官方", "https://ai-gateway.vercel.sh/v1", recommended = true),
        ),
        docUrl = "https://vercel.com/docs/ai-gateway",
    )

    val Baseten = ProviderPreset(
        id = "baseten",
        displayName = "Baseten",
        endpoints = listOf(
            Endpoint("官方", "https://inference.baseten.co/v1", recommended = true),
        ),
    )

    val Nvidia = ProviderPreset(
        id = "nvidia",
        displayName = "NVIDIA NIM",
        endpoints = listOf(
            Endpoint("官方", "https://integrate.api.nvidia.com/v1", recommended = true),
        ),
        docUrl = "https://build.nvidia.com/",
    )

    // ==============================================================
    // 本地 / 自建
    // ==============================================================

    val Ollama = ProviderPreset(
        id = "ollama",
        displayName = "Ollama（本地）",
        endpoints = listOf(
            Endpoint(
                "本机",
                "http://127.0.0.1:11434/v1",
                note = "需在 network_security_config 放行明文",
                recommended = true,
            ),
            Endpoint(
                "局域网另一台机器",
                "http://192.168.1.x:11434/v1",
                note = "换成实际 IP；NSC 需显式加该 IP",
                requiresSubstitution = true,
            ),
        ),
        commonModels = listOf("llama3.2", "qwen2.5", "deepseek-r1"),
        docUrl = "https://github.com/ollama/ollama/blob/main/docs/openai.md",
        note = "本地模型，API Key 可留空",
    )

    val LMStudio = ProviderPreset(
        id = "lmstudio",
        displayName = "LM Studio（本地）",
        endpoints = listOf(
            Endpoint("本机", "http://127.0.0.1:1234/v1", note = "需放行明文"),
        ),
        docUrl = "https://lmstudio.ai/docs/api/openai-api",
        note = "本地模型，API Key 可留空",
    )

    // ==================================================================
    // ★ 下面这批是补齐 DSH 全量目录时加的（原版只收了 23 家，漏了这些）
    //
    // 数据来源：从 DSH 客户端 bundle 提取的 46 家目录（app.asar 内的 provider 定义）。
    // **只收有 baseUrl 的** —— DSH 里 amazon-bedrock / azure / cloudflare-* /
    // google-vertex / opencode / typesafe 这些的 baseUrl 是 null
    // （它们走各自的 SDK 或需要账号级端点拼接），手机端填不出来，硬收进来
    // 用户点了只会得到"没地址"的困惑，所以**故意不列**。
    // ==================================================================

    val AntLing = ProviderPreset(
        id = "ant-ling",
        displayName = "蚂蚁百灵",
        endpoints = listOf(Endpoint("官方", "https://api.ant-ling.com/v1", recommended = true)),
        note = "OpenAI 兼容",
    )

    val Anthropic = ProviderPreset(
        id = "anthropic",
        displayName = "Anthropic（Claude）",
        endpoints = listOf(Endpoint("官方", "https://api.anthropic.com", recommended = true)),
        note = "★ 原生 Messages 协议，本版未实现，会提示不支持；如需可用请走自定义 API 填兼容网关",
        supportsModelList = false,
    )

    val GithubCopilot = ProviderPreset(
        id = "github-copilot",
        displayName = "GitHub Copilot",
        endpoints = listOf(Endpoint("个人版", "https://api.individual.githubcopilot.com", recommended = true)),
        note = "需要 Copilot 订阅签发的 token，不是普通 API Key",
        // ★ 实测：不带鉴权头时返回 **400 "missing required Authorization header"**
        //   （不是 404 —— 说明 /models 路径存在）。
        //   但它要的还有一个额外的 `Copilot-Integration-Id` 头，我们没带 ⇒
        //   拉取会失败。如实标 false，让用户手动填模型名。
        supportsModelList = false,
    )

    val Google = ProviderPreset(
        id = "google",
        displayName = "Google（Gemini）",
        endpoints = listOf(Endpoint("官方", "https://generativelanguage.googleapis.com/v1beta", recommended = true)),
        note = "★ 原生 generateContent 协议，本版未实现；如需可用请走自定义 API 填兼容网关",
        supportsModelList = false,
    )

    val Meta = ProviderPreset(
        id = "meta",
        displayName = "Meta",
        endpoints = listOf(Endpoint("官方", "https://api.meta.ai/v1", recommended = true)),
        note = "OpenAI 兼容",
    )

    val MiniMaxCN = ProviderPreset(
        id = "minimax-cn",
        displayName = "MiniMax（国内站）",
        endpoints = listOf(
            Endpoint("Anthropic 兼容", "https://api.minimaxi.com/anthropic", note = "需兼容网关或客户端支持"),
        ),
        note = "★ 该端点是 Anthropic 协议，本版未实现。国内站与海外站的 Key 不通用",
        supportsModelList = false,
    )

    // ★ 原来这里还有一条 `MoonshotIntl`，id 也是 "moonshotai" ⇒ 与上面的 `Moonshot` 撞号。
    //   `byId()` 是 firstOrNull ⇒ 注册表里第二条永远解析不到，用户点「国际站」拿到的其实是国内站配置。
    //   已删除：`Moonshot` 本身就把 international 站作为第二个 endpoint 列着，能力没有损失；
    //   而 id 保留在 `Moonshot` 上 ⇒ 已存配置仍然解析得到（不会失联）。

    val MoonshotCN = ProviderPreset(
        id = "moonshotai-cn",
        displayName = "Kimi（国内站 .cn）",
        endpoints = listOf(Endpoint("国内站", "https://api.moonshot.cn/v1", recommended = true)),
        commonModels = listOf("kimi-k2-0905-preview", "moonshot-v1-128k"),
        note = "★ 与 .ai 站的 Key 不通用",
    )

    val OpenAICodex = ProviderPreset(
        id = "openai-codex",
        displayName = "OpenAI Codex",
        endpoints = listOf(Endpoint("ChatGPT 后端", "https://chatgpt.com/backend-api")),
        note = "★ 走 ChatGPT 订阅登录态，不是 API Key，本版未实现",
        supportsModelList = false,
    )

    val QwenTokenPlanSG = ProviderPreset(
        id = "qwen-token-plan",
        displayName = "通义 Token Plan（新加坡）",
        endpoints = listOf(
            Endpoint("新加坡", "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1", recommended = true),
        ),
        commonModels = listOf("qwen3-max", "qwen-plus"),
        note = "★ 套餐 Key 与按量计费 Key 不通用；区域绑错会 401",
        // 同一套 DashScope 协议 ⇒ 同样不认 reasoning_effort
        thinkingShape = ThinkingShape.ENABLE_THINKING,
    )

    val QwenTokenPlanCN = ProviderPreset(
        id = "qwen-token-plan-cn",
        displayName = "通义 Token Plan（北京）",
        endpoints = listOf(
            Endpoint("北京", "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", recommended = true),
        ),
        commonModels = listOf("qwen3-max", "qwen-plus"),
        note = "★ 套餐 Key 与按量计费 Key 不通用；区域绑错会 401",
        thinkingShape = ThinkingShape.ENABLE_THINKING,
    )

    val Radius = ProviderPreset(
        id = "radius",
        displayName = "Radius",
        endpoints = listOf(Endpoint("官方", "https://radius.pi.dev/v1", recommended = true)),
        note = "OpenAI 兼容",
        // ★ 实测：chat 返回 401（路径对）、`/v1/models` 返回 **404 not_found**
        //   ⇒ 该网关**没有模型目录**，只能手动填。
        supportsModelList = false,
    )

    val XiaomiTokenPlanAMS = ProviderPreset(
        id = "xiaomi-token-plan-ams",
        displayName = "小米 Token Plan（阿姆斯特丹）",
        endpoints = listOf(
            Endpoint("阿姆斯特丹", "https://token-plan-ams.xiaomimimo.com/v1", recommended = true),
        ),
        note = "★ Token Plan 走独立域名，套餐 Key 与按量 Key 不通用",
    )

    val XiaomiTokenPlanCN = ProviderPreset(
        id = "xiaomi-token-plan-cn",
        displayName = "小米 Token Plan（国内）",
        endpoints = listOf(
            Endpoint("国内", "https://token-plan-cn.xiaomimimo.com/v1", recommended = true),
        ),
        note = "★ Token Plan 走独立域名，套餐 Key 与按量 Key 不通用",
    )

    val XiaomiTokenPlanSGP = ProviderPreset(
        id = "xiaomi-token-plan-sgp",
        displayName = "小米 Token Plan（新加坡）",
        endpoints = listOf(
            Endpoint("新加坡", "https://token-plan-sgp.xiaomimimo.com/v1", recommended = true),
        ),
        note = "★ Token Plan 走独立域名，套餐 Key 与按量 Key 不通用",
    )

    // ★ 原来这里还有一条 `Zai`，id 也是 "zai" ⇒ 与上面的 `Zhipu` 撞号，同上第二条永远解析不到。
    //   已删除：`Zhipu` 的三个 endpoint 已经把「国内编程套餐 / 国际站 z.ai / 标准 API」都列全了。

    val ZaiCodingCN = ProviderPreset(
        id = "zai-coding-cn",
        displayName = "智谱 Coding Plan（国内）",
        endpoints = listOf(Endpoint("国内", "https://open.bigmodel.cn/api/coding/paas/v4", recommended = true)),
        note = "★ Coding Plan 与普通 API Key 不通用",
    )

    /**
     * 自定义端点。
     *
     * ★ `id` 保持 `"custom"` 不变 —— 它是**存进用户配置里的稳定标识**，
     *   改了会让已保存的配置找不到厂商。
     */
    val Custom = ProviderPreset(
        id = "custom",
        displayName = "自定义",
        endpoints = listOf(Endpoint("手动填写", "", note = "任何 OpenAI 兼容的 base URL")),
        docUrl = "",
        note = "自建网关、中转站等",
    )

    // ==============================================================
    // ★ 补全：DSH 里 baseUrl 为 null 的厂商
    //
    //   DSH 是桌面端，这几家它走**专用 SDK** 或**账号级端点拼接**，所以目录里
    //   baseUrl 存的是 null。但它们在 OpenAI 兼容协议下**都有真实地址**，
    //   手机端完全能填 —— 之前以「填不出来」为由跳掉它们是错的。
    //   下面每条地址都标了出处，不是猜的。
    // ==============================================================

    /**
     * OpenCode Zen（按量计费）。
     *
     * 出处：https://opencode.ai/v2/docs/console/harnesses -> Base URLs
     *   Pay-as-you-go : https://opencode.ai/inference
     *   Go（订阅）    : https://opencode.ai/inference/go
     * 再接 OpenAI Chat Completions 路径 /openai/v1/chat/completions。
     * 所以本项目的 baseUrl 就是 .../inference/openai/v1（chatPath 补后半段）。
     */
    val OpenCode = ProviderPreset(
        id = "opencode",
        displayName = "OpenCode Zen（按量计费）",
        endpoints = listOf(
            Endpoint(
                label = "按量计费",
                baseUrl = "https://opencode.ai/inference/openai/v1",
                recommended = true,
            ),
        ),
        commonModels = listOf("deepseek-v4.1-flash", "glm-5.3", "kimi-k3", "qwen3.8-max"),
        note = "★ 套餐（Go）与按量计费的 Key 不通用，别选错接入点",
        // ★★ 实测：chat 在 /inference/openai/v1，但模型目录在 /inference/v1/models。
        //    用 chat 前缀去拉会 404（真机上就是这么失败的）。
        modelsPathOverride = "https://opencode.ai/inference/v1/models",
        // ★ 与 Go 同一套要求：必须自报身份 + 带稳定会话 ID，否则 400
        defaultHeaders = mapOf("User-Agent" to USER_AGENT),
        sessionHeaderName = "x-opencode-session",
    )

    /**
     * OpenCode Go（订阅制）。
     *
     * 同上出处，Go 的 base URL 多一段 /go：
     *   https://opencode.ai/inference/go/openai/v1/chat/completions
     */
    val OpenCodeGo = ProviderPreset(
        id = "opencode-go",
        displayName = "OpenCode Go（订阅）",
        endpoints = listOf(
            Endpoint(
                label = "Go 订阅",
                baseUrl = "https://opencode.ai/inference/go/openai/v1",
                recommended = true,
            ),
        ),
        commonModels = listOf("deepseek-v4.1-flash", "glm-5.3", "kimi-k3"),
        note = "★ 需要 Go 订阅；与按量计费的 Key 不通用",
        // ★★ 同上：Go 的模型目录在 /inference/go/v1/models（少一段 openai）
        modelsPathOverride = "https://opencode.ai/inference/go/v1/models",
        // ★★★ 真机实测：不带这两个头会 **400 MissingSessionID**，原文
        //   「Request is missing x-opencode-session and cannot be routed efficiently.」
        //   官方文档要求：① 用自己的 user-agent 标识客户端（不要用通用 SDK 名）
        //                ② 每个对话带**稳定的** `x-opencode-session`
        defaultHeaders = mapOf("User-Agent" to USER_AGENT),
        sessionHeaderName = "x-opencode-session",
    )

    /**
     * Cloudflare Workers AI。
     *
     * 出处：developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/
     *   baseURL: https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1
     * 地址里含 Account ID 占位符，必须让用户替换。
     */
    val CloudflareWorkersAI = ProviderPreset(
        id = "cloudflare-workers-ai",
        displayName = "Cloudflare Workers AI",
        endpoints = listOf(
            Endpoint(
                label = "账户级",
                baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
                recommended = true,
                requiresSubstitution = true,
            ),
        ),
        commonModels = listOf("@cf/meta/llama-3.1-8b-instruct"),
        note = "★ 把 {account_id} 换成你的 Cloudflare 账户 ID",
    )

    /**
     * Cloudflare AI Gateway。
     *
     * 出处：developers.cloudflare.com/ai-gateway/usage/providers/
     *   https://gateway.ai.cloudflare.com/v1/{account_id}/{gateway_id}/{provider}
     * 地址含两个占位符。
     */
    val CloudflareAIGateway = ProviderPreset(
        id = "cloudflare-ai-gateway",
        displayName = "Cloudflare AI Gateway",
        endpoints = listOf(
            Endpoint(
                label = "网关",
                baseUrl = "https://gateway.ai.cloudflare.com/v1/{account_id}/{gateway_id}/compat",
                recommended = true,
                requiresSubstitution = true,
            ),
        ),
        note = "★ 把 {account_id} / {gateway_id} 换成你自己的；网关需先在 CF 后台建好",
    )

    /**
     * Google Vertex AI（OpenAI 兼容层）。
     *
     * 出处：Google Cloud 官方「使用 OpenAI 库访问 Gemini 模型」文档。
     * 路径形如
     *   https://{region}-aiplatform.googleapis.com/v1/projects/{project}
     *     /locations/{region}/endpoints/openapi
     * 区域与项目 ID 都要替换。
     */
    val GoogleVertex = ProviderPreset(
        id = "google-vertex",
        displayName = "Google Vertex AI",
        endpoints = listOf(
            Endpoint(
                label = "OpenAI 兼容",
                baseUrl = "https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/endpoints/openapi",
                recommended = true,
                requiresSubstitution = true,
            ),
        ),
        note = "★ 替换 {region} 与 {project}；鉴权走 Google 凭据，不是普通 API Key",
        supportsModelList = false,
    )

    /**
     * Amazon Bedrock（OpenAI 兼容层）。
     *
     * 路径形如 https://bedrock-runtime.{region}.amazonaws.com/openai/v1
     * 只需替换区域。鉴权是 AWS SigV4，**不是** Bearer —— 这里如实写明，
     * 免得用户填了 IAM Key 却一直 401 不知道为什么。
     */
    val AmazonBedrock = ProviderPreset(
        id = "amazon-bedrock",
        displayName = "Amazon Bedrock",
        endpoints = listOf(
            Endpoint(
                label = "OpenAI 兼容",
                baseUrl = "https://bedrock-runtime.{region}.amazonaws.com/openai/v1",
                recommended = true,
                requiresSubstitution = true,
            ),
        ),
        note = "★ 替换 {region}；鉴权用 AWS SigV4（不是 Bearer Key），本版不支持签名",
        supportsModelList = false,
    )

    /**
     * Azure OpenAI。
     *
     * 它**不是**标准 OpenAI 兼容：路径里必须带 deployments/{部署名} 与
     * api-version 查询参数，鉴权头是 api-key 而不是 Authorization: Bearer。
     * 本项目只实现标准协议，如实标注未实现，别让用户白填。
     */
    val Azure = ProviderPreset(
        id = "azure",
        displayName = "Azure OpenAI",
        endpoints = listOf(
            Endpoint(
                label = "资源级",
                baseUrl = "https://{resource}.openai.azure.com/openai/deployments/{deployment}",
                requiresSubstitution = true,
            ),
        ),
        note = "★ 路径与鉴权头都非标准（api-key + api-version），本版未实现；建议走自定义网关",
        supportsModelList = false,
    )

    /**
     * 通义 Token Plan（个人版）。
     *
     * DSH 里它是独立的一条（qwen-token-plan-individual），地址与新加坡套餐相同，
     * 但**套餐与 Key 体系不同**，所以必须单独列一条，不能合并。
     */
    val QwenTokenPlanIndividual = ProviderPreset(
        id = "qwen-token-plan-individual",
        displayName = "通义 Token Plan（个人版）",
        endpoints = listOf(
            Endpoint(
                label = "新加坡",
                baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
                recommended = true,
            ),
        ),
        commonModels = listOf("qwen3-max", "qwen-plus"),
        note = "★ 个人版套餐 Key，与按量计费 / 企业版都不通用",
        thinkingShape = ThinkingShape.ENABLE_THINKING,
    )

    /** 全部预设，顺序即 UI 显示顺序 */
    val all: List<ProviderPreset> = listOf(
        // ---- 国内主力 ----
        DeepSeek,
        Qwen,
        QwenTokenPlanCN,
        QwenTokenPlanSG,
        QwenTokenPlanIndividual,
        Zhipu,
        ZaiCodingCN,
        Moonshot,
        MoonshotCN,
        KimiCoding,
        Xiaomi,
        XiaomiTokenPlanCN,
        XiaomiTokenPlanSGP,
        XiaomiTokenPlanAMS,
        MiniMax,
        MiniMaxCN,
        SiliconFlow,
        AntLing,
        // ---- 代码订阅 / 网关 ----
        OpenCode,
        OpenCodeGo,
        // ---- 国际 ----
        OpenAI,
        Anthropic,
        Google,
        GoogleVertex,
        OpenRouter,
        Groq,
        XAI,
        Mistral,
        Together,
        Fireworks,
        Cerebras,
        HuggingFace,
        VercelAIGateway,
        Baseten,
        Nvidia,
        Meta,
        Radius,
        GithubCopilot,
        OpenAICodex,
        // ---- 云平台 ----
        AmazonBedrock,
        Azure,
        CloudflareWorkersAI,
        CloudflareAIGateway,
        // ---- 本地 ----
        Ollama,
        LMStudio,
        // ---- 兜底（必须在最后）----
        Custom,
        )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }

    /**
     * 把「用户存下来的配置」解析成**实际要用的接入点**。
     *
     * ★ 为什么需要这一步（真机上会静默出错）：
     *   调用方原来写的是
     *       preset.endpoints.firstOrNull { it.baseUrl == profile.endpointUrl }
     *           ?: preset.defaultEndpoint
     *   这对预置厂商没问题，但对**自定义**是错的 ——
     *   `Custom` 只有一个占位的空 `baseUrl`，用户手填的地址永远匹配不上，
     *   会静默 fallback 到空字符串，最后请求发到 `""`。
     *   ⇒ 这里把手填地址**当成它自己的一个接入点**。
     *
     * @return `null` 表示这条配置根本无法使用（厂商不认识 / 地址为空）。
     *   调用方必须据此报错，**不能**继续拿空 baseUrl 发请求。
     */
    fun resolveEndpoint(providerId: String, endpointUrl: String): Endpoint? {
        val preset = byId(providerId) ?: return null
        val resolved = if (endpointUrl.isBlank()) {
            preset.defaultEndpoint
        } else {
            preset.endpoints.firstOrNull { it.baseUrl == endpointUrl }
                ?: Endpoint(label = "手动填写", baseUrl = endpointUrl)
        }
        return resolved.takeIf { it.baseUrl.isNotBlank() }
    }

    /** 有多个接入点的厂商 —— UI 上要展开让用户选 */
    fun multiEndpoint(): List<ProviderPreset> = all.filter { it.endpoints.size > 1 }
}
