package com.chatmobile.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写层契约：**思考参数按协议形态分发** + **输出上限的键名不是通用的**。
 *
 * ## ★★★ 这个测试存在的理由
 *
 * 报告二的核心结论是：思考强度**不是同一个字段名**，而是 6 类协议形态。
 * 当时代码只发 `reasoning_effort`，对 Qwen / SiliconFlow / Kimi K2.x / GLM 的开关部分
 * **要么静默无效、要么 400** —— 而"静默无效"这类 bug 编译能过、跑起来也不报错，
 * 只有真机花钱试出来。所以必须用测试把每种形态钉住。
 *
 * ★ 另一条容易忽略的：`max_tokens` 与 `max_completion_tokens` 发错键**不报错，只会被忽略**
 *   —— 用户会以为"我设了上限但没用"。
 */
class RequestBuilderTest {

    private fun base(
        reasoningEffort: String? = null,
        thinkingBudget: Int? = null,
        maxTokens: Int? = null,
    ) = ChatRequest(
        model = "m",
        messages = listOf(ChatMessage.user("hi")),
        reasoningEffort = reasoningEffort,
        thinkingBudget = thinkingBudget,
        maxTokens = maxTokens,
    )

    private fun config(
        shape: ThinkingShape = ThinkingShape.EFFORT_ONLY,
        maxCompletion: Boolean = false,
    ) = ProviderConfig(
        id = "t",
        displayName = "T",
        baseUrl = "https://x/v1",
        apiKey = "k",
        thinkingShape = shape,
        usesMaxCompletionTokens = maxCompletion,
    )

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

    // ---------------------------------------------------------------
    // 形态 1：只发档位
    // ---------------------------------------------------------------

    @Test
    fun `EFFORT_ONLY 只发 reasoning_effort`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "high"),
            config(ThinkingShape.EFFORT_ONLY),
        )
        assertEquals("high", body.str("reasoning_effort"))
        assertNull("EFFORT_ONLY 不该发 thinking 对象", body["thinking"])
        assertNull("EFFORT_ONLY 不该发 enable_thinking", body["enable_thinking"])
        assertNull("EFFORT_ONLY 不该发 reasoning 对象", body["reasoning"])
    }

    // ---------------------------------------------------------------
    // 形态 2：开关 + 档位
    // ---------------------------------------------------------------

    @Test
    fun `开关型必须同时发 thinking 启用与档位`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "high"),
            config(ThinkingShape.EFFORT_PLUS_THINKING_SWITCH),
        )
        val thinking = body["thinking"] as? JsonObject
        assertTrue("开关型必须发 thinking 对象", thinking != null)
        assertEquals("enabled", thinking!!.str("type"))
        assertEquals(
            "★ 开关与强度是两件事：只发了开关等于没调深度",
            "high",
            body.str("reasoning_effort"),
        )
    }

    @Test
    fun `开关型只给预算时也要打开开关`() {
        val body = OpenAiRequestBuilder.build(
            base(thinkingBudget = 2048),
            config(ThinkingShape.EFFORT_PLUS_THINKING_SWITCH),
        )
        assertTrue("给了预算说明用户想开思考，开关必须一起发", body["thinking"] != null)
        assertNull("没给档位就不该凭空编一个", body["reasoning_effort"])
    }

    // ---------------------------------------------------------------
    // 形态 3：布尔开关 + 预算（Qwen / SiliconFlow）
    // ---------------------------------------------------------------

    @Test
    fun `布尔开关型发 enable_thinking 而不是 reasoning_effort`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "high", thinkingBudget = 32768),
            config(ThinkingShape.ENABLE_THINKING),
        )
        assertEquals("Qwen / SiliconFlow 要的是布尔开关", true, body.bool("enable_thinking"))
        assertEquals(32768, body.int("thinking_budget"))
        assertNull(
            "★ 这两家不认 reasoning_effort —— 发了是静默无效，等于没调",
            body["reasoning_effort"],
        )
    }

    @Test
    fun `布尔开关型没给预算就不发 thinking_budget`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "high"),
            config(ThinkingShape.ENABLE_THINKING),
        )
        assertEquals(true, body.bool("enable_thinking"))
        assertNull(
            "★ 不凭空填预算：多数模型有服务端默认，替厂商决定思考深度是错的",
            body["thinking_budget"],
        )
    }

    // ---------------------------------------------------------------
    // 形态 4：归一化对象（OpenRouter）
    // ---------------------------------------------------------------

    @Test
    fun `归一化型发 reasoning 对象`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "max"),
            config(ThinkingShape.REASONING_OBJECT),
        )
        val reasoning = body["reasoning"] as? JsonObject
        assertTrue("OpenRouter 走 reasoning 对象", reasoning != null)
        assertEquals("max", reasoning!!.str("effort"))
        assertNull("归一化形态不该同时发顶层 effort", body["reasoning_effort"])
    }

    @Test
    fun `归一化型的预算是 max_tokens 不是档位`() {
        val body = OpenAiRequestBuilder.build(
            base(thinkingBudget = 4096),
            config(ThinkingShape.REASONING_OBJECT),
        )
        val reasoning = body["reasoning"] as? JsonObject
        assertEquals(4096, reasoning?.int("max_tokens"))
    }

    // ---------------------------------------------------------------
    // 形态 5：什么都没有
    // ---------------------------------------------------------------

    @Test
    fun `NONE 形态一个思考字段都不发`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "high", thinkingBudget = 1024),
            config(ThinkingShape.NONE),
        )
        assertNull(body["reasoning_effort"])
        assertNull(body["thinking"])
        assertNull(body["enable_thinking"])
        assertNull(body["reasoning"])
    }

    @Test
    fun `什么都没给时任何形态都不发思考字段`() {
        for (shape in ThinkingShape.entries) {
            val body = OpenAiRequestBuilder.build(base(), config(shape))
            for (k in listOf("reasoning_effort", "thinking", "enable_thinking", "reasoning")) {
                assertNull("$shape 在没给值时不该发 $k", body[k])
            }
        }
    }

    // ---------------------------------------------------------------
    // 输出上限的键名
    // ---------------------------------------------------------------

    @Test
    fun `输出上限键名按厂商切换`() {
        val old = OpenAiRequestBuilder.build(base(maxTokens = 100), config(maxCompletion = false))
        assertEquals(100, old.int("max_tokens"))
        assertNull(old["max_completion_tokens"])

        val new = OpenAiRequestBuilder.build(base(maxTokens = 100), config(maxCompletion = true))
        assertEquals(100, new.int("max_completion_tokens"))
        assertNull(
            "★ 两个键同时发会让部分网关 400 —— 必须二选一",
            new["max_tokens"],
        )
    }

    // ---------------------------------------------------------------
    // 值域纪律
    // ---------------------------------------------------------------

    @Test
    fun `档位值原样透传不做映射`() {
        // ★ 上游自己会做别名映射（DeepSeek minimal→low、GLM low→high 等）。
        //   客户端替它映射 = 把两套语义叠在一起，出问题无从排查。
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "xhigh"),
            config(ThinkingShape.EFFORT_ONLY),
        )
        assertEquals("xhigh", body.str("reasoning_effort"))
    }

    @Test
    fun `空白档位视为没给`() {
        val body = OpenAiRequestBuilder.build(
            base(reasoningEffort = "   "),
            config(ThinkingShape.EFFORT_ONLY),
        )
        assertNull("空白串不该变成空字符串发出去", body["reasoning_effort"])
    }

    @Test
    fun `非正的预算视为没给`() {
        for (bad in listOf(0, -1)) {
            val body = OpenAiRequestBuilder.build(
                base(thinkingBudget = bad),
                config(ThinkingShape.REASONING_OBJECT),
            )
            assertNull("预算 $bad 不合法，不该发 reasoning 对象", body["reasoning"])
        }
    }
}
