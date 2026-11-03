package com.chatmobile.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 把一条 SSE 响应变成 `Flow<ChatEvent>`。**这一层唯一被允许「弄脏手」的地方。**
 *
 * ★ **坑 1（本类存在的核心理由）**：`readTimeout` 必须显式调大。
 * `RealEventSource.processResponse` 只执行 `call?.timeout()?.cancel()`
 * （源码注释 *"This is a long-lived response. Cancel full-call timeouts."*），
 * 它取消的是 **callTimeout**；而读超时挂在 **socket** 上
 * （`RealConnection`：`source.timeout().timeout(chain.readTimeoutMillis)`），
 * 库并没有动它。OkHttp 默认 `readTimeout = 10_000` ms，
 * 于是**推理模型思考超过 10 秒没有产出任何 token 就会掉线**。
 * 见 [defaultClientBuilder]：readTimeout 设 0（不限），
 * 改由 [StreamTimeouts.firstByteTimeoutMs] 兜底。
 *
 * @param apiKey 仅用于把异常文本里的密钥擦掉，绝不外传。
 */
internal class SseTransport(
    private val client: OkHttpClient,
    private val parser: OpenAiStreamParser,
    private val apiKey: String,
    private val timeouts: StreamTimeouts = StreamTimeouts(),
) {

    fun stream(request: Request): Flow<ChatEvent> = callbackFlow {
        val factory = EventSources.createFactory(client)

        /** 终端事件只允许发一次：[DONE] / onClosed / onFailure / 看门狗可能连着来。 */
        var terminated = false
        var sawFirstByte = false
        var sawFinish = false
        // 用数组持有，避免「闭包捕获 val 后无法赋值」；EventSource 在下方才创建。
        val sseRef = arrayOfNulls<EventSource>(1)

        // ★ 必须在 listener **之前**声明：Kotlin 的对象表达式捕获不到后文才声明的局部变量。
        //   不用 @Volatile：读写都发生在同一个 callbackFlow 协程的上下文里
        //   （listener 回调与 launch 都在该协程的调度器上串行），不存在跨线程可见性问题。
        var watchdog: Job? = null

        fun finishWith(event: ChatEvent) {
            if (terminated) return
            terminated = true
            trySend(event)
            close()
        }

        val listener = object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                // 只有 2xx 且 Content-Type 合法才会走到这里 —— 响应头已到，算首字节。
                sawFirstByte = true
                watchdog?.cancel()
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (terminated) return
                sawFirstByte = true
                watchdog?.cancel()

                for (ev in parser.parseData(data)) {
                    when (ev) {
                        is ChatEvent.Done -> {
                            finishWith(ChatEvent.Done)
                            return
                        }

                        is ChatEvent.Finish -> {
                            sawFinish = true
                            trySend(ev)
                        }

                        else -> trySend(ev)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // ★ 坑 5（调研报告 §7.9）：服务端「直接断开但没发 [DONE]」在库层面
                //   与正常结束完全一致（ServerSentEventReader 在 EOF 时只是退出循环）。
                //   靠是否见过 finish_reason 区分：见过 = 服务端已明确收尾；没见过 = 被截断。
                if (sawFinish) {
                    finishWith(ChatEvent.Done)
                } else {
                    finishWith(
                        ChatEvent.Error(
                            "连接在收到结束标记前中断（可能被网关或网络切断）",
                            retryable = true,
                        ),
                    )
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?,
            ) {
                if (terminated) return

                val event = if (response != null && !response.isSuccessful) {
                    // 非 2xx 时 RealEventSource 直接回调 onFailure(null, response)，
                    // body 尚未关闭，可以安全读出来给用户看诊断信息。
                    val body = runCatching { response.body.string() }.getOrNull()
                    ChatEvent.Error(
                        redact(ErrorMapper.describeHttp(response.code, body), apiKey),
                        retryable = ErrorMapper.isRetryableHttp(response.code),
                    )
                } else {
                    val cause = t ?: IOException("未知网络错误")
                    val msg = if (cause is IllegalStateException &&
                        cause.message?.contains("Invalid content-type") == true
                    ) {
                        // 调研报告 §7.7：Content-Type 必须严格是 text/event-stream，
                        // 否则库直接拒绝。自建网关 / 反向代理改头是最常见原因。
                        "服务端返回的 Content-Type 不是 text/event-stream，SSE 无法解析。" +
                            "常见于自建网关或反向代理改写了响应头。原始信息：${cause.message}"
                    } else {
                        ErrorMapper.describe(cause)
                    }
                    ChatEvent.Error(
                        redact(msg, apiKey),
                        retryable = ErrorMapper.isRetryable(cause),
                    )
                }
                finishWith(event)
            }
        }

        // ★ 首字节看门狗：readTimeout 被设成「不限」后，若服务端彻底不响应，
        //   连接会一直挂着。这里用应用层超时兜底，超时即放弃并报可重试错误。
        //   一旦开始吐 token 就取消看门狗 —— 推理模型的停顿是正常现象。
        if (timeouts.firstByteTimeoutMs > 0) {
            watchdog = launch {
                delay(timeouts.firstByteTimeoutMs)
                if (!sawFirstByte && !terminated) {
                    terminated = true
                    trySend(
                        ChatEvent.Error(
                            "首字节超时：${timeouts.firstByteTimeoutMs}ms 内没有收到任何响应",
                            retryable = true,
                        ),
                    )
                    close()
                    runCatching { sseRef[0]?.cancel() }
                }
            }
        }

        sseRef[0] = factory.newEventSource(request, listener)

        awaitClose {
            watchdog?.cancel()
            // ★ 坑 6：cancel() 是「immediately and violently」的 ——
            //   取消后不要再指望收到剩余事件，已收到的增量调用方需自行保存。
            runCatching { sseRef[0]?.cancel() }
        }
    }.buffer(Channel.UNLIMITED) // 不丢增量：正文逐 token 到达，丢一个就少一个字

    companion object {
        /**
         * ★ 建客户端**必须**用这个 builder，逐条理由：
         *
         * - `readTimeout(0, SECONDS)`：见类注释坑 1。0 表示**不限**（不要写 -1）。
         *   这是整个网络层唯一的硬性要求，少了它推理模型必然掉线。
         * - `callTimeout(0)`：整通请求不限时 —— 一次长回答可以跑很多分钟。
         * - `connectTimeout(10s)`：连接阶段仍需有界。
         * - `retryOnConnectionFailure(true)`：OkHttp 自带的换路由重试，
         *   与上层的指数退避是两回事，可以共存。
         */
        fun defaultClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }
}

/**
 * 流式请求的超时口径。
 *
 * readTimeout 被设成「不限」后，若服务端彻底不回任何东西，连接会一直挂着。
 * [firstByteTimeoutMs] 用来兜底：发出请求后迟迟连响应头都拿不到，就放弃并报可重试错误。
 * 一旦开始吐 token 就不再限制间隔 —— 推理模型思考几十秒是正常现象（这就是坑 1）。
 */
data class StreamTimeouts(
    val connectTimeoutMs: Long = 10_000L,
    val firstByteTimeoutMs: Long = 120_000L,
    /** 两次 token 之间的最大间隔；`null` = 不限（推荐，坑 1 的正解）。 */
    val interTokenTimeoutMs: Long? = null,
)
