package com.chatmobile.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用 MockWebServer 做**端到端**流式验证（真 HTTP、真 SSE 解析、真断连）。
 *
 * 与 [OpenAiStreamParserTest] 的分工：
 * - 那边测「一帧怎么解」（纯函数）
 * - 这边测「一整条流怎么走」（含 OkHttp / okhttp-sse 的真实行为、错误映射、重试）
 *
 * ★ 用的是 `mockwebserver3.*`（OkHttp 5 的新 API）。
 *   build.gradle.kts 里声明的是 `com.squareup.okhttp3:mockwebserver`，
 *   但那是**弃用桥接层**，它的 POM 传递依赖了 `mockwebserver3:5.5.1`，
 *   所以新 API 直接可用，不需要额外加依赖。
 */
class ChatProviderStreamTest {

    private lateinit var server: MockWebServer

    private val sseMediaType = "text/event-stream"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    /** 造一个指向 MockWebServer 的配置。 */
    private fun config(
        supportsStreamUsage: Boolean = true,
        supportsModelList: Boolean = true,
    ): ProviderConfig = ProviderConfig(
        id = "test",
        displayName = "Test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key-1234567890",
        supportsStreamUsage = supportsStreamUsage,
        supportsModelList = supportsModelList,
    )

    /** 测试用的客户端：超时调小，避免失败用例卡住整个测试。 */
    private fun testClient(): OkHttpClient = SseTransport.defaultClientBuilder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun provider(
        cfg: ProviderConfig = config(),
        retryPolicy: RetryPolicy = RetryPolicy.NONE,
    ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
        config = cfg,
        client = testClient(),
        retryPolicy = retryPolicy,
        // 首字节超时设短，超时用例能快速收敛
        timeouts = StreamTimeouts(firstByteTimeoutMs = 3_000L),
    )

    /** 把若干 SSE 帧编成响应体（每帧 `data: xxx` + 空行）。 */
    private fun sseBody(vararg frames: String): String =
        frames.joinToString("") { "data: $it\n\n" }

    private fun enqueueSse(
        body: String,
        code: Int = 200,
        contentType: String = sseMediaType,
    ) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", contentType)
                .body(body)
                .build(),
        )
    }

    private fun chatRequest() = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage.user("hi")),
    )

    // ---------------------------------------------------------------
    // 正常流式
    // ---------------------------------------------------------------

    @Test
    fun `正常流式：多帧 delta 按序拼接且以 Done 收尾`() = runBlocking {
        enqueueSse(
            sseBody(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"你"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"好"}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("你好", text)

        assertEquals(ChatEvent.Finish("stop"), events.filterIsInstance<ChatEvent.Finish>().single())
        assertEquals(ChatEvent.Done, events.last())
        assertTrue("不该出现 Error", events.none { it is ChatEvent.Error })
    }

    @Test
    fun `请求被正确发送：路径 鉴权头 请求体`() = runBlocking {
        enqueueSse(sseBody("[DONE]"))

        provider().stream(chatRequest()).toList()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer test-key-1234567890", recorded.headers["Authorization"])
        assertTrue(
            "要声明接受 SSE",
            recorded.headers["Accept"]?.contains("text/event-stream") == true,
        )

        val body = recorded.body!!.utf8()
        assertTrue("请求体要带 stream=true", body.contains("\"stream\":true"))
        assertTrue("请求体要带 model", body.contains("\"model\":\"test-model\""))
        assertTrue("请求体要带 messages", body.contains("\"messages\""))
    }

    @Test
    fun `DONE 终止后后继帧不再被处理`() = runBlocking {
        // [DONE] 之后故意再塞一帧有内容的：它不应该出现。
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"content":"A"}}]}""",
                "[DONE]",
                """{"choices":[{"delta":{"content":"SHOULD_NOT_APPEAR"}}]}""",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("A", text)
        assertFalse(
            "DONE 之后的内容不能被处理",
            events.filterIsInstance<ChatEvent.Delta>().any { it.text.contains("SHOULD_NOT_APPEAR") },
        )
    }

    // ---------------------------------------------------------------
    // 坑 3：空 choices 的 usage 帧
    // ---------------------------------------------------------------

    @Test
    fun `空 choices 的 usage 帧不崩且用量被读到`() = runBlocking {
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"content":"hi"}}]}""",
                """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":7}}""",
                "[DONE]",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        assertEquals(ChatEvent.Usage(5, 7), events.filterIsInstance<ChatEvent.Usage>().single())
        assertEquals(ChatEvent.Done, events.last())
    }

    // ---------------------------------------------------------------
    // 坑 4：DeepSeek usage 骑在内容帧上
    // ---------------------------------------------------------------

    @Test
    fun `DeepSeek 形态：usage 骑在最后一个内容帧上`() = runBlocking {
        // ★ 这一帧必须写成**单行**。
        //   按 SSE 规范，一个 `data:` 字段只覆盖同一行；多行 JSON 的第二行会被
        //   当成未知字段名丢弃，最后得到半截 JSON —— 那样测的就不是解析器，而是我的笔误。
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"content":"答"}}]}""",
                """{"choices":[{"delta":{"content":"案"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}}""",
                "[DONE]",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        assertEquals("答案", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
        assertEquals(
            ChatEvent.Usage(11, 22),
            events.filterIsInstance<ChatEvent.Usage>().single(),
        )
        assertNotNull(events.firstOrNull { it is ChatEvent.Finish })
    }

    // ---------------------------------------------------------------
    // 坑 5：推理字段归一化（走完整链路）
    // ---------------------------------------------------------------

    @Test
    fun `reasoning_content 经真实链路归一化成 Reasoning`() = runBlocking {
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"reasoning_content":"先想"}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"再想"}}]}""",
                """{"choices":[{"delta":{"content":"结论"}}]}""",
                "[DONE]",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        assertEquals(
            "先想再想",
            events.filterIsInstance<ChatEvent.Reasoning>().joinToString("") { it.text },
        )
        assertEquals(
            "结论",
            events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `OpenRouter 的 reasoning 字段同样被归一化`() = runBlocking {
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"reasoning":"thinking"}}]}""",
                """{"choices":[{"delta":{"content":"done"}}]}""",
                "[DONE]",
            ),
        )

        val events = provider().stream(chatRequest()).toList()

        assertEquals(
            ChatEvent.Reasoning("thinking"),
            events.filterIsInstance<ChatEvent.Reasoning>().single(),
        )
    }

    // ---------------------------------------------------------------
    // 坑 1：readTimeout 必须放得下长时间静默
    // ---------------------------------------------------------------

    @Test
    fun `帧间静默超过默认 10 秒也不掉线`() = runBlocking {
        // ★ 这条是坑 1 的回归测试，也是本层最重要的用例。
        //   默认 readTimeout 是 10 秒；这里让服务端发完响应头后整 12 秒才发 body。
        //   若客户端没把 readTimeout 调大，socket 读超时会在 10 秒时掐断连接，
        //   表现为「一个 token 都没收到 + Error」。
        //   （headersDelay 不延迟 → onOpen 正常触发、看门狗被取消；
        //    bodyDelay 延迟 12s → 正好越过默认读超时线。）
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", sseMediaType)
                .body(
                    sseBody(
                        """{"choices":[{"delta":{"content":"第一"}}]}""",
                        """{"choices":[{"delta":{"content":"第二"}}]}""",
                        "[DONE]",
                    ),
                )
                .bodyDelay(12, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )

        val events = provider().stream(chatRequest()).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("第一第二", text)
        assertTrue(
            "静默 12 秒不该导致错误，实际事件=$events",
            events.none { it is ChatEvent.Error },
        )
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun `默认客户端确实把 readTimeout 设成了不限`() {
        // ★ 直接断言 builder 的产物，防止有人「优化」掉这行。
        //   这是整个网络层唯一的硬性要求（见 SseTransport 类注释坑 1）。
        val client = SseTransport.defaultClientBuilder().build()

        assertEquals("readTimeout 必须是 0（不限）", 0, client.readTimeoutMillis)
        assertEquals("callTimeout 必须不限", 0, client.callTimeoutMillis)
        assertTrue("connectTimeout 仍要有界", client.connectTimeoutMillis > 0)
    }

    // ---------------------------------------------------------------
    // 错误路径：永远给 Error 事件，不抛异常
    // ---------------------------------------------------------------

    @Test
    fun `HTTP 401 返回不可重试的 Error`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"Invalid API key"}}""")
                .build(),
        )

        val events = provider().stream(chatRequest()).toList()

        val err = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue("应提到鉴权", err.message.contains("401") || err.message.contains("鉴权"))
        assertFalse("401 重试也没用", err.retryable)
        assertTrue(events.none { it is ChatEvent.Delta })
    }

    @Test
    fun `HTTP 500 返回可重试的 Error`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":"boom"}""")
                .build(),
        )

        val events = provider().stream(chatRequest()).toList()

        val err = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue("5xx 应当可重试", err.retryable)
    }

    @Test
    fun `连接被断开时返回 Error 而不是抛异常`() = runBlocking {
        // 服务端收到请求就断开 —— 模拟网络中断。
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", sseMediaType)
                .body(sseBody("""{"choices":[{"delta":{"content":"半"}}]}"""))
                .onResponseBody(mockwebserver3.SocketEffect.CloseSocket())
                .build(),
        )

        // ★ 关键：即使网络层内部抛了，对外也必须只有 ChatEvent。
        val events = provider().stream(chatRequest()).toList()

        assertTrue("必须有事件产出", events.isNotEmpty())
        // 要么是"被截断"的可重试错误，要么是 Error —— 都必须是事件，不是异常。
        assertTrue(
            "中断应表现为 Error 事件",
            events.any { it is ChatEvent.Error },
        )
    }

    @Test
    fun `Content-Type 不对时报出可诊断的错误`() = runBlocking {
        // 调研报告 §7.7：自建网关常把 Content-Type 改成 application/json，
        // okhttp-sse 会直接 onFailure。
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(sseBody("[DONE]"))
                .build(),
        )

        val events = provider().stream(chatRequest()).toList()

        val err = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue(
            "错误信息应提示 Content-Type 问题，实际=${err.message}",
            err.message.contains("Content-Type") || err.message.contains("text/event-stream"),
        )
    }

    @Test
    fun `首字节超时返回可重试的 Error`() = runBlocking {
        // 服务端收下请求但一直不响应。
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", sseMediaType)
                .body(sseBody("[DONE]"))
                .headersDelay(10, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )

        // 首字节超时设 1 秒，看门狗应先生效。
        val p = OpenAiCompatibleProvider(
            config = config(),
            client = testClient(),
            retryPolicy = RetryPolicy.NONE,
            timeouts = StreamTimeouts(firstByteTimeoutMs = 1_000L),
        )

        val events = p.stream(chatRequest()).toList()

        val err = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue("超时必须可重试", err.retryable)
        assertTrue(
            "错误信息应说明是超时，实际=${err.message}",
            err.message.contains("超时") || err.message.contains("timeout"),
        )
    }

    // ---------------------------------------------------------------
    // 坑 6：指数退避重试
    // ---------------------------------------------------------------

    @Test
    fun `首次失败且未吐内容时按策略重试并最终成功`() = runBlocking {
        // 第一次 500，第二次正常。
        server.enqueue(
            MockResponse.Builder().code(500).setHeader("Content-Type", "application/json").body("boom").build(),
        )
        enqueueSse(
            sseBody(
                """{"choices":[{"delta":{"content":"重试成功"}}]}""",
                "[DONE]",
            ),
        )

        val p = provider(
            // 关掉抖动，让重试立即发生（延迟设很小，避免测试变慢）
            retryPolicy = RetryPolicy(maxRetries = 2, baseDelayMs = 10L, jitter = false),
        )

        val events = p.stream(chatRequest()).toList()

        assertEquals(
            "重试成功",
            events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text },
        )
        assertEquals(ChatEvent.Done, events.last())
        assertEquals("应该发了两次请求", 2, server.requestCount)
        assertTrue("重试失败过程不该暴露给 UI", events.none { it is ChatEvent.Error })
    }

    @Test
    fun `已经吐出内容后失败不再重试`() = runBlocking {
        // 第一轮：先送出一帧真实内容，然后**不发 [DONE]、不带 finish_reason** 就让响应结束。
        // 这正是「服务端说到一半被切断」的形态（调研报告 §7.9）：
        //   ServerSentEventReader 在 EOF 时只是退出循环，与正常结束在库层面无法区分。
        // 我们的区分依据是「是否见过 finish_reason」——没见过即判定为截断。
        //
        // ★ 不用 onResponseBody(CloseSocket())：那个 effect 在写 body **之前**触发，
        //   一个字节都发不出去，测的就不是「已吐出内容后断流」。
        // ★ 也不用自定义 MockResponseBody 抛异常：那会把 MockWebServer 的
        //   dispatcher 线程搞崩，连累同批次其它用例。
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", sseMediaType)
                .body(sseBody("""{"choices":[{"delta":{"content":"已有的"}}]}"""))
                .build(),
        )
        // 第二轮：即使服务端准备好了一份完整的好数据，也不该再发第二次请求。
        enqueueSse(sseBody("""{"choices":[{"delta":{"content":"不该出现"}}]}""", "[DONE]"))

        val p = provider(
            retryPolicy = RetryPolicy(maxRetries = 3, baseDelayMs = 10L, jitter = false),
        )

        val events = p.stream(chatRequest()).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("断流前的内容应保留，且不重来", "已有的", text)
        assertTrue("必须报错让用户知道断了", events.any { it is ChatEvent.Error })
        assertEquals("不能发第二次请求", 1, server.requestCount)
    }

    @Test
    fun `不可重试的错误不会触发重试`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(401).setHeader("Content-Type", "application/json").body("nope").build(),
        )

        val p = provider(
            retryPolicy = RetryPolicy(maxRetries = 3, baseDelayMs = 10L, jitter = false),
        )

        val events = p.stream(chatRequest()).toList()

        assertTrue(events.single() is ChatEvent.Error)
        assertEquals("401 不该重试", 1, server.requestCount)
    }

    @Test
    fun `重试次数用尽后把 Error 交给调用方`() = runBlocking {
        repeat(3) {
            server.enqueue(
                MockResponse.Builder().code(500).setHeader("Content-Type", "application/json").body("boom").build(),
            )
        }

        val p = provider(
            retryPolicy = RetryPolicy(maxRetries = 1, baseDelayMs = 10L, jitter = false),
        )

        val events = p.stream(chatRequest()).toList()

        assertTrue(events.single() is ChatEvent.Error)
        assertEquals("1 次原始 + 1 次重试 = 2 次请求", 2, server.requestCount)
    }

    // ---------------------------------------------------------------
    // 请求体构造
    // ---------------------------------------------------------------

    @Test
    fun `include_usage 受配置开关控制`() = runBlocking {
        // 关掉后请求体里不该有 stream_options。
        enqueueSse(sseBody("[DONE]"))
        provider(cfg = config(supportsStreamUsage = false)).stream(chatRequest()).toList()

        val body = server.takeRequest().body!!.utf8()
        assertFalse(
            "supportsStreamUsage=false 时不应带 stream_options",
            body.contains("stream_options"),
        )
    }

    @Test
    fun `可选参数只在显式给出时才发送`() = runBlocking {
        enqueueSse(sseBody("[DONE]"))
        provider().stream(chatRequest()).toList()

        val body = server.takeRequest().body!!.utf8()
        assertFalse("没给 temperature 就不该发", body.contains("temperature"))
        assertFalse("没给 max_tokens 就不该发", body.contains("max_tokens"))
    }

    @Test
    fun `显式参数会被发送`() = runBlocking {
        enqueueSse(sseBody("[DONE]"))
        provider().stream(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage.user("hi")),
                temperature = 0.3,
                maxTokens = 128,
            ),
        ).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"temperature\":0.3"))
        assertTrue(body.contains("\"max_tokens\":128"))
    }

    @Test
    fun `API 密钥不会出现在请求体里`() = runBlocking {
        enqueueSse(sseBody("[DONE]"))
        val cfg = config()
        provider(cfg).stream(chatRequest()).toList()

        val recorded = server.takeRequest()
        // 密钥只该在 Authorization 头里，绝不能混进 body。
        assertFalse(
            "请求体里不能有密钥",
            recorded.body!!.utf8().contains(cfg.apiKey),
        )
    }

    @Test
    fun `ProviderConfig 的 toString 不泄漏密钥`() {
        val cfg = config()

        val s = cfg.toString()

        assertFalse("toString 绝不能含密钥", s.contains(cfg.apiKey))
        assertTrue(s.contains("***"))
    }
}
