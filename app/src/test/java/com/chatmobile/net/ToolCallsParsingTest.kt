package com.chatmobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **流式 `tool_calls` 拼接**的契约测试。
 *
 * ## ★★★ 为什么这个测试最重要
 *
 * 一个工具调用在 SSE 流里是**被切成很多片**送达的。这是 OpenAI 协议里
 * 最容易写错、且**错了不会报错**的地方 —— 表现为"模型说要搜索，然后什么都没发生"，
 * 而日志里一切正常。
 *
 * 真实分片形态（照抄自 OpenAI 官方流式文档）：
 * ```
 * {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function",
 *     "function":{"name":"web_search","arguments":""}}]}}]}
 * {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":"}}]}}]}
 * {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"天气\"}"}}]}}]}
 * {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
 * ```
 * ⇒ 正确结果是**一个**参数完整的调用，不是三个残缺的。
 */
class ToolCallsParsingTest {

    private fun parser() = OpenAiStreamParser()

    private fun frame(deltaJson: String, finish: String? = null): String {
        val finishPart = if (finish == null) "null" else "\"$finish\""
        return """{"choices":[{"delta":$deltaJson,"finish_reason":$finishPart}]}"""
    }

    // ---------------------------------------------------------------
    // 核心：分片必须拼起来
    // ---------------------------------------------------------------

    @Test
    fun `分片必须拼成一个完整调用而不是多个残缺调用`() {
        val p = parser()

        // 片 1：id / name 出现，arguments 为空
        val a = p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"web_fetch","arguments":""}}]}"""),
        )
        assertTrue("分片期间不该发出 ToolCalls 事件", a.none { it is ChatEvent.ToolCalls })

        // 片 2 + 片 3：只有 arguments 片段
        val b = p.parseData(
            frame("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"url\":\"https://ex"}}]}"""),
        )
        assertTrue(b.none { it is ChatEvent.ToolCalls })
        val c = p.parseData(
            frame("""{"tool_calls":[{"index":0,"function":{"arguments":"ample.com\"}"}}]}"""),
        )
        assertTrue(c.none { it is ChatEvent.ToolCalls })

        // 收尾帧：finish_reason 非空 ⇒ 这时才发完整调用
        val done = p.parseData(frame("""{}""", finish = "tool_calls"))
        val calls = done.filterIsInstance<ChatEvent.ToolCalls>()
        assertEquals("必须只发一个 ToolCalls 事件", 1, calls.size)
        assertEquals(1, calls[0].calls.size)

        val call = calls[0].calls[0]
        assertEquals("call_abc", call.callId)
        assertEquals("web_fetch", call.toolName)
        assertEquals(
            """{"url":"https://example.com"}""",
            call.argumentsJson,
            )
        // finish 事件也要跟着发出来
        assertTrue(done.any { it is ChatEvent.Finish })
        // ★ ToolCalls 必须在 Finish 之前 —— 调用方要先拿到"调什么"
        assertTrue(
            done.indexOfFirst { it is ChatEvent.ToolCalls } <
                done.indexOfFirst { it is ChatEvent.Finish },
        )
    }

    @Test
    fun `同一帧里两个并发工具按 index 分开`() {
        val p = parser()
        p.parseData(
            frame(
                """{"tool_calls":[{"index":0,"id":"a","function":{"name":"get_time","arguments":""}},{"index":1,"id":"b","function":{"name":"get_location","arguments":""}}]}""",
            ),
        )
        p.parseData(
            frame(
                """{"tool_calls":[{"index":0,"function":{"arguments":"{}"}},{"index":1,"function":{"arguments":"{\"accuracy\":\"city\"}"}}]}""",
            ),
        )
        val out = p.parseData(frame("""{}""", finish = "tool_calls"))
        val calls = out.filterIsInstance<ChatEvent.ToolCalls>().single().calls

        assertEquals(2, calls.size)
        assertEquals("get_time", calls[0].toolName)
        assertEquals("a", calls[0].callId)
        assertEquals("get_location", calls[1].toolName)
        assertEquals("""{"accuracy":"city"}""", calls[1].argumentsJson)
    }

    @Test
    fun `index 乱序到达也能按 index 归位`() {
        val p = parser()
        p.parseData(
            frame("""{"tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":""}}]}"""),
        )
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"first","arguments":""}}]}"""),
        )
        val calls = p.parseData(frame("""{}""", finish = "tool_calls"))
            .filterIsInstance<ChatEvent.ToolCalls>().single().calls
        assertEquals("first", calls[0].toolName)
        assertEquals("second", calls[1].toolName)
    }

    // ---------------------------------------------------------------
    // 边界：残缺数据必须丢弃而不是发出幽灵调用
    // ---------------------------------------------------------------

    @Test
    fun `缺 name 的分片不能变成调用`() {
        val p = parser()
        // 只有 id 和参数，没有 name（分片没收全就断了）
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"x","function":{"arguments":"{}"}}]}"""),
        )
        val out = p.parseData(frame("""{}""", finish = "tool_calls"))
        assertTrue(
            "缺 name 的调用必须丢弃 —— 否则模型会收到一个调不了工具的幽灵调用",
            out.none { it is ChatEvent.ToolCalls },
        )
    }

    @Test
    fun `缺 id 的分片不能变成调用`() {
        val p = parser()
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"function":{"name":"get_time","arguments":"{}"}}]}"""),
        )
        val out = p.parseData(frame("""{}""", finish = "tool_calls"))
        assertTrue("缺 id 时无法回填 tool_call_id，必须丢弃", out.none { it is ChatEvent.ToolCalls })
    }

    @Test
    fun `空 arguments 补成空对象`() {
        val p = parser()
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"get_time","arguments":""}}]}"""),
        )
        val calls = p.parseData(frame("""{}""", finish = "tool_calls"))
            .filterIsInstance<ChatEvent.ToolCalls>().single().calls
        assertEquals(
            "空参数必须补成 {}：很多网关拒绝空字符串作为 arguments",
            "{}",
            calls[0].argumentsJson,
        )
    }

    // ---------------------------------------------------------------
    // 累加器必须清空：同一 parser 实例会被工具循环复用
    // ---------------------------------------------------------------

    @Test
    fun `同一个 parser 连续两轮不能串味`() {
        val p = parser()
        // 第一轮
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"get_time","arguments":"{}"}}]}"""),
        )
        val first = p.parseData(frame("""{}""", finish = "tool_calls"))
            .filterIsInstance<ChatEvent.ToolCalls>().single().calls
        assertEquals(1, first.size)

        // 第二轮：纯文本回复，不该再冒出上一轮的调用
        val second = p.parseData(
            """{"choices":[{"delta":{"content":"好的"},"finish_reason":"stop"}]}""",
        )
        assertTrue(
            "累加器没清空 ⇒ 上一轮的工具会被**重复执行**",
            second.none { it is ChatEvent.ToolCalls },
        )
        assertTrue(second.any { it is ChatEvent.Delta })
    }

    @Test
    fun `DONE 哨兵也会冲出残留调用`() {
        val p = parser()
        p.parseData(
            frame("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"x","arguments":"{}"}}]}"""),
        )
        // 有些网关不发 finish_reason 直接 [DONE]
        val out = p.parseData("[DONE]")
        assertTrue(out.any { it is ChatEvent.ToolCalls })
        assertTrue(out.any { it is ChatEvent.Done })
    }

    // ---------------------------------------------------------------
    // 不该影响原有行为
    // ---------------------------------------------------------------

    @Test
    fun `普通文本回复不受工具解析影响`() {
        val p = parser()
        val out = p.parseData(
            """{"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("你好", (out[0] as ChatEvent.Delta).text)
    }

    @Test
    fun `没有工具调用时纯文本收尾不发 ToolCalls`() {
        val p = parser()
        val out = p.parseData(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        assertTrue(out.none { it is ChatEvent.ToolCalls })
        assertTrue(out.any { it is ChatEvent.Finish })
    }
}
