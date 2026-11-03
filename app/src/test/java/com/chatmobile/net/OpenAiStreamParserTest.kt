package com.chatmobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenAiStreamParser] 的纯函数测试。
 *
 * 这组测试**不需要网络**，是钉死 6 个协议坑的第一道防线。
 * 每一条用例对应《协议层与密钥安全-调研报告》里的某一节，注释里标了出处。
 */
class OpenAiStreamParserTest {

    private val parser = OpenAiStreamParser()

    // ---------------------------------------------------------------
    // 坑 2：先判 [DONE] 再 JSON.parse
    // ---------------------------------------------------------------

    @Test
    fun `DONE 哨兵被识别为 Done 而不是解析异常`() {
        // [DONE] 不是 JSON。若实现先 parse 就会抛异常。
        val events = parser.parseData("[DONE]")

        assertEquals(listOf(ChatEvent.Done), events)
    }

    @Test
    fun `DONE 允许前后有空白`() {
        assertEquals(listOf(ChatEvent.Done), parser.parseData("  [DONE]  "))
    }

    // ---------------------------------------------------------------
    // 正常流式：多帧 delta
    // ---------------------------------------------------------------

    @Test
    fun `普通内容帧解析成 Delta`() {
        val frame = """{"id":"1","choices":[{"index":0,"delta":{"content":"Hello"}}]}"""

        assertEquals(listOf(ChatEvent.Delta("Hello")), parser.parseData(frame))
    }

    @Test
    fun `空 content 不产生 Delta 事件`() {
        // 首帧惯例：delta 里只有 role，content 是空串。不该让 UI 白刷一次。
        val frame = """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}"""

        assertEquals(emptyList<ChatEvent>(), parser.parseData(frame))
    }

    @Test
    fun `content 为 JSON null 不崩也不产生事件`() {
        // ★ JsonNull 在 kotlinx-serialization 里是 JsonPrimitive 的子类，
        //   如果读取时只判 JsonPrimitive 就会读出字符串 "null" —— 这条专门防这个。
        val frame = """{"choices":[{"index":0,"delta":{"content":null}}]}"""

        assertEquals(emptyList<ChatEvent>(), parser.parseData(frame))
    }

    @Test
    fun `多帧拼接后文本完整`() {
        val frames = listOf(
            """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"delta":{"content":"你"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
            """{"choices":[{"delta":{"content":"，世界"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )

        val text = frames
            .flatMap { parser.parseData(it) }
            .filterIsInstance<ChatEvent.Delta>()
            .joinToString("") { it.text }

        assertEquals("你好，世界", text)
    }

    // ---------------------------------------------------------------
    // 坑 3：include_usage 产生空 choices 的 usage 帧
    // ---------------------------------------------------------------

    @Test
    fun `空 choices 的 usage 帧不崩且能读出用量`() {
        // OpenAI 在 include_usage=true 时，[DONE] 前会多发一个 choices 为 [] 的帧。
        // 无条件读 choices[0] 会数组越界。
        val frame = """{"id":"1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}"""

        val events = parser.parseData(frame)

        assertEquals(listOf(ChatEvent.Usage(input = 12, output = 34)), events)
    }

    @Test
    fun `完全没有 choices 字段也不崩`() {
        val frame = """{"id":"1","usage":{"prompt_tokens":1,"completion_tokens":2}}"""

        assertEquals(listOf(ChatEvent.Usage(input = 1, output = 2)), parser.parseData(frame))
    }

    // ---------------------------------------------------------------
    // 坑 4：DeepSeek 的 usage 骑在最后一个内容帧上
    // ---------------------------------------------------------------

    @Test
    fun `DeepSeek usage 骑在内容帧上时两者都要出来`() {
        // 调研报告 §1.8：DeepSeek 的 usage 不是单独一帧，
        // 而是带在一个「choices 恰好一个元素、不带新内容、finish_reason 非空」的 chunk 上。
        // 所以不能「choices 非空就跳过 usage」。
        val frame = """
            {"id":"d1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
        """.trimIndent()

        val events = parser.parseData(frame)

        assertTrue("应含 Finish", events.any { it is ChatEvent.Finish })
        assertTrue("应含 Usage", events.any { it is ChatEvent.Usage })
        assertEquals(
            ChatEvent.Usage(input = 10, output = 20),
            events.filterIsInstance<ChatEvent.Usage>().single(),
        )
        // 空 content 不应产生 Delta
        assertTrue(events.none { it is ChatEvent.Delta })
    }

    @Test
    fun `usage 的推理与缓存字段被透传`() {
        val frame = """
            {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,
             "completion_tokens_details":{"reasoning_tokens":30},
             "prompt_tokens_details":{"cached_tokens":64}}}
        """.trimIndent()

        val usage = parser.parseData(frame).filterIsInstance<ChatEvent.Usage>().single()

        assertEquals(100, usage.input)
        assertEquals(50, usage.output)
        assertEquals(30, usage.reasoningTokens)
        assertEquals(64, usage.cachedInput)
    }

    @Test
    fun `DeepSeek 的 prompt_cache_hit_tokens 也能读到`() {
        val frame = """
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_cache_hit_tokens":8}}
        """.trimIndent()

        val usage = parser.parseData(frame).filterIsInstance<ChatEvent.Usage>().single()

        assertEquals(8, usage.cachedInput)
    }

    // ---------------------------------------------------------------
    // 坑 5：推理字段各家不同，必须归一化
    // ---------------------------------------------------------------

    @Test
    fun `DeepSeek 的 reasoning_content 归一化成 Reasoning`() {
        val frame = """{"choices":[{"delta":{"reasoning_content":"让我想想"}}]}"""

        val events = parser.parseData(frame)

        assertEquals(listOf(ChatEvent.Reasoning("让我想想")), events)
        assertTrue("不该混进 Delta", events.none { it is ChatEvent.Delta })
    }

    @Test
    fun `OpenRouter 的 reasoning 字段同样归一化`() {
        // 调研报告 §2.10：OpenRouter 用 message.reasoning，
        // 官方称 reasoning_content 是它的别名，行为完全相同。
        val frame = """{"choices":[{"delta":{"reasoning":"thinking..."}}]}"""

        assertEquals(listOf(ChatEvent.Reasoning("thinking...")), parser.parseData(frame))
    }

    @Test
    fun `同一帧里 content 与 reasoning 并存时都产出`() {
        // 智谱 GLM 的流式 delta 里可能同时有 content 与 reasoning_content。
        val frame = """{"choices":[{"delta":{"content":"答案","reasoning_content":"推理"}}]}"""

        val events = parser.parseData(frame)

        assertEquals(2, events.size)
        assertEquals(ChatEvent.Reasoning("推理"), events[0])
        assertEquals(ChatEvent.Delta("答案"), events[1])
    }

    @Test
    fun `两个推理字段同时存在时只取 reasoning_content 一次`() {
        val frame = """{"choices":[{"delta":{"reasoning_content":"A","reasoning":"B"}}]}"""

        val events = parser.parseData(frame)

        assertEquals(listOf(ChatEvent.Reasoning("A")), events)
    }

    @Test
    fun `OpenAI 没有推理字段时不产生 Reasoning 事件`() {
        // 调研报告 §2.4：OpenAI Chat Completions 根本没有 reasoning 字段。
        val frame = """{"choices":[{"delta":{"content":"普通回答"}}]}"""

        val events = parser.parseData(frame)

        assertEquals(listOf(ChatEvent.Delta("普通回答")), events)
    }

    // ---------------------------------------------------------------
    // finish_reason
    // ---------------------------------------------------------------

    @Test
    fun `finish_reason 被透传成 Finish 事件`() {
        val frame = """{"choices":[{"delta":{},"finish_reason":"length"}]}"""

        // length 意味着被 max_tokens 截断，UI 要区别于正常结束。
        assertEquals(listOf(ChatEvent.Finish("length")), parser.parseData(frame))
    }

    @Test
    fun `finish_reason 为 null 时不产生 Finish`() {
        val frame = """{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""

        assertTrue(parser.parseData(frame).none { it is ChatEvent.Finish })
    }

    // ---------------------------------------------------------------
    // 容错：坏数据不能让整条流崩掉
    // ---------------------------------------------------------------

    @Test
    fun `非法 JSON 返回 Error 而不抛异常`() {
        val events = parser.parseData("{this is not json")

        assertEquals(1, events.size)
        val err = events.single() as ChatEvent.Error
        assertFalse("坏帧重试也没用", err.retryable)
    }

    @Test
    fun `JSON 不是对象时返回 Error`() {
        val events = parser.parseData("[1,2,3]")

        assertTrue(events.single() is ChatEvent.Error)
    }

    @Test
    fun `空 data 返回空列表`() {
        assertEquals(emptyList<ChatEvent>(), parser.parseData(""))
        assertEquals(emptyList<ChatEvent>(), parser.parseData("   "))
    }

    @Test
    fun `流内嵌的 error 对象被识别`() {
        // 有的网关在 HTTP 200 的流里塞 error 对象（上游失败时常见）。
        val frame = """{"error":{"message":"rate limited","code":429}}"""

        val err = parser.parseData(frame).single() as ChatEvent.Error

        assertTrue(err.message.contains("rate limited"))
        assertTrue("429 应当可重试", err.retryable)
    }

    @Test
    fun `未知字段被忽略`() {
        // 各家响应是超集（DeepSeek 多 context_window、OpenRouter 多 pricing...），
        // 多出来的字段不该让解析失败。
        val frame = """
            {"id":"1","object":"chat.completion.chunk","created":1,"model":"m",
             "system_fingerprint":"fp_x","service_tier":"default",
             "choices":[{"index":0,"logprobs":null,"delta":{"content":"ok"}}]}
        """.trimIndent()

        assertEquals(listOf(ChatEvent.Delta("ok")), parser.parseData(frame))
    }

    @Test
    fun `usage 里的数字被串化成字符串也能读`() {
        // 个别网关会把数字序列化成字符串。
        val frame = """{"choices":[],"usage":{"prompt_tokens":"7","completion_tokens":"9"}}"""

        assertEquals(
            ChatEvent.Usage(input = 7, output = 9),
            parser.parseData(frame).single(),
        )
    }

    // ---------------------------------------------------------------
    // 密钥脱敏
    // ---------------------------------------------------------------

    @Test
    fun `错误信息里的密钥被擦掉`() {
        val secret = "sk-super-secret-key-123456"
        val p = OpenAiStreamParser(apiKey = secret)

        // 构造一个把密钥写进错误文本的坏帧（网关回显请求头时真会这样）。
        val err = p.parseData("""{"error":{"message":"invalid key $secret"}}""")
            .single() as ChatEvent.Error

        assertFalse("密钥绝不能出现在给用户的文本里", err.message.contains(secret))
        assertTrue(err.message.contains("***"))
    }

    // ---------------------------------------------------------------
    // peekHasFinishReason（供 SseTransport 判断是否「已明确收尾」）
    // ---------------------------------------------------------------

    @Test
    fun `peekHasFinishReason 正确识别`() {
        assertTrue(
            parser.peekHasFinishReason("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
        )
        assertFalse(
            parser.peekHasFinishReason("""{"choices":[{"delta":{"content":"x"}}]}"""),
        )
        assertFalse(
            parser.peekHasFinishReason("""{"choices":[]}"""),
        )
        assertFalse(parser.peekHasFinishReason("[DONE]"))
        assertFalse("坏帧不应抛异常", parser.peekHasFinishReason("{bad"))
    }
}
