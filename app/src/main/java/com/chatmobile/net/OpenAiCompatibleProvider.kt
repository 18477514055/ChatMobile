package com.chatmobile.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI `/chat/completions` 兼容提供方。
 *
 * 覆盖：DeepSeek / OpenAI / 通义（DashScope 兼容模式）/ 硅基流动 / OpenRouter / Kimi / 智谱。
 * 它们的差异（推理字段名、usage 位置、是否支持 include_usage）全部被
 * [OpenAiStreamParser] 与 [ProviderConfig] 吃掉，这里只负责「发请求 + 重试 + 拼 URL」。
 *
 * ★ **坑 6 的落点**：okhttp-sse 不自动重连，退避重试在本类的 [streamOnce] 外层实现。
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val client: OkHttpClient = SseTransport.defaultClientBuilder().build(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val timeouts: StreamTimeouts = StreamTimeouts(),
) : ChatProvider {

    private val parser = OpenAiStreamParser(apiKey = config.apiKey)

    private val transport = SseTransport(client, parser, config.apiKey, timeouts)

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var attempt = 0
        // 整通请求是否已经产出过任何「有内容的事件」。
        var emittedAnything = false

        while (true) {
            var retryableFailure: ChatEvent.Error? = null

            streamOnce(request).collect { event ->
                when (event) {
                    is ChatEvent.Error -> retryableFailure = event
                    else -> {
                        // Delta / Reasoning / Usage / Finish 都算「已经产出」。
                        // Done 不算（它只是收尾信号，没有任何内容）。
                        if (event !is ChatEvent.Done) emittedAnything = true
                        emit(event)
                    }
                }
            }

            val failure = retryableFailure ?: return@flow

            // ★ 只在「整通请求还没吐出任何有内容的事件」时重试。
            //   已经吐过正文再重试，UI 上会出现「半句话 + 从头再来」的重复内容，
            //   比直接报错更糟 —— 宁可让用户手动重发。
            //   连 Usage / Finish 也算产出，避免重试后 UI 收到两份用量、两次「已结束」。
            val canRetry = failure.retryable &&
                !emittedAnything &&
                attempt < retryPolicy.maxRetries

            if (!canRetry) {
                emit(failure)
                return@flow
            }

            delay(retryPolicy.delayFor(attempt))
            attempt++
            // 重试后继续循环；若这次成功，Done 会由内层 emit 出来。
        }
    }

    /** 发一次请求，把 SSE 事件原样透传。不做重试。 */
    private fun streamOnce(request: ChatRequest): Flow<ChatEvent> {
        val bodyJson = OpenAiRequestBuilder.build(request, config)
        val bodyText = bodyJson.toString()

        val httpRequest = Request.Builder()
            .url(joinUrl(config.baseUrl, config.chatPath))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            // 显式声明接受 SSE：EventSources.createFactory 只在**没有** Accept 头时才补，
            // 这里写上是为了让「到底要什么」在代码里可见。
            .header("Accept", "text/event-stream")
            .apply { config.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(bodyText.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return transport.stream(httpRequest)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
