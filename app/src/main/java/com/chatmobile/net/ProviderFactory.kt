package com.chatmobile.net

import okhttp3.OkHttpClient

/**
 * 按配置造 [ChatProvider]。**唯一的厂商分发点** —— 调用方永远拿接口。
 *
 * 新增一家厂商的实现步骤（接口不写死的具体落点）：
 * 1. 实现一个 [ChatProvider]（例如 `AnthropicProvider`）；
 * 2. 在 [create] 的 `when` 里加一个分支；
 * 3. 调用方代码 **一行都不用改**。
 */
object ProviderFactory {

    /**
     * @param client 允许注入（测试用 MockWebServer 时传入指向假服务器的客户端）。
     * @throws UnsupportedOperationException 当厂商族尚未实现时。
     */
    fun create(
        config: ProviderConfig,
        client: OkHttpClient = SseTransport.defaultClientBuilder().build(),
        retryPolicy: RetryPolicy = RetryPolicy(),
        timeouts: StreamTimeouts = StreamTimeouts(),
    ): ChatProvider = when (config.kind) {
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            config = config,
            client = client,
            retryPolicy = retryPolicy,
            timeouts = timeouts,
        )

        // ── 以下为预留位（MVP 不实现）。抛明确的异常而不是静默降级，
        //    避免 UI 以为在用 Anthropic 实际走了 OpenAI 协议。
        ProviderKind.ANTHROPIC -> throw UnsupportedOperationException(
            "Anthropic Messages API 尚未实现。" +
                "实现时需要一个独立的 StreamCodec（请求体/事件类型/鉴权头都与 OpenAI 不同），" +
                "见 README「P1 适配层」。",
        )

        ProviderKind.GEMINI -> throw UnsupportedOperationException(
            "Gemini generateContent 尚未实现。" +
                "它的流式是 NDJSON 而非 SSE，且鉴权用 query 参数 key=，需要独立实现。",
        )
    }

    /**
     * 该厂商族是否已经可用。UI 可据此把未实现的选项置灰，而不是等用户点了才报错。
     */
    fun isImplemented(kind: ProviderKind): Boolean = kind == ProviderKind.OPENAI_COMPATIBLE

    /**
     * 便捷重载：从预置厂商 + 用户填的 key 直接造 provider。
     *
     * @param endpoint 厂商可能有多个接入点（直连 / Token Plan / 国际站），默认取
     *   [ProviderPreset.defaultEndpoint]。UI 让用户选过的话把它传进来。
     */
    fun createFromPreset(
        preset: ProviderPreset,
        apiKey: String,
        endpoint: Endpoint = preset.defaultEndpoint,
        model: String? = null,
        /**
         * 该对话的**稳定** ID，用于 `preset.sessionHeaderName` 指定的头。
         *
         * ★ 必须跨轮次固定不变：服务端用它做路由与 prompt 缓存。
         *   每次随机的话每轮都是新会话 —— 缓存全失效，还可能被判成异常流量。
         */
        sessionId: String = "",
        client: OkHttpClient = SseTransport.defaultClientBuilder().build(),
        retryPolicy: RetryPolicy = RetryPolicy(),
    ): ChatProvider = create(
        config = preset.toConfig(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            sessionId = sessionId,
        ),
        client = client,
        retryPolicy = retryPolicy,
    )
}
