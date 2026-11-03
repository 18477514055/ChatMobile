package com.chatmobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModelCapabilities` 的解析测试。
 *
 * ★ 所有用例的 JSON **都照抄自真实上游响应**（2026-11-03 实测
 *   `https://opencode.ai/inference/go/v1/models` 的 28 个模型），
 *   不是我自己编的形状。这一点很重要 —— 上一版就是因为照着"想象中"的
 *   `reasoningEfforts` 键写解析，而真实字段叫 `capabilities.reasoning.effort`，
 *   结果界面上那两块永远不出现。
 */
class ModelCapabilityTest {

    // ------------------------------------------------------------------
    // 真实形态 1：deepseek-v4.1-flash（OpenCode Go 实测逐字摘录）
    //   ★ 注意 `effort_updates: false` 但**仍然有** effort 数组 ——
    //     这两个字段不是一回事。
    // ------------------------------------------------------------------
    private val realDeepseek = """
        {
          "id": "deepseek-v4.1-flash",
          "object": "model",
          "name": "DeepSeek V4.1 Flash",
          "capabilities": {
            "tools": { "supported": true },
            "reasoning": { "supported": true, "effort": ["low", "high", "max"] },
            "structured_output": true,
            "effort_updates": false
          },
          "limit": { "context": 1000000, "output": 384000 }
        }
    """.trimIndent()

    @Test
    fun `真实 OpenCode 形态能读出上下文与档位`() {
        val r = ModelCapabilities.parse("""{"data":[$realDeepseek]}""")
        val c = r["deepseek-v4.1-flash"]
        requireNotNull(c)

        assertEquals(1_000_000, c.contextLimit)
        assertEquals(listOf("low", "high", "max"), c.effortLevels)
        assertEquals(true, c.reasoningSupported)
        assertTrue(c.showsEffortPicker)
    }

    @Test
    fun `effort_updates 是 false 也不影响档位表`() {
        // 这条专门锁住一个易错点：effort_updates 说的是"运行中能不能改"，
        // 不是"有没有档位"。混用会让 21 个模型里的绝大多数隐藏掉选择器。
        val r = ModelCapabilities.parse("""{"data":[$realDeepseek]}""")
        val c = r.getValue("deepseek-v4.1-flash")
        assertFalse(c.effortLevels.isEmpty())
    }

    // ------------------------------------------------------------------
    // 真实形态 2：没有 effort 数组的模型（实测 7/28 是这样）
    // ------------------------------------------------------------------
    @Test
    fun `没有 effort 数组时不编造档位`() {
        val body = """
            {"data":[{
              "id": "minimax-m3",
              "capabilities": { "reasoning": { "supported": true } },
              "limit": { "context": 200000 }
            }]}
        """.trimIndent()
        val c = ModelCapabilities.parse(body).getValue("minimax-m3")

        assertTrue(c.effortLevels.isEmpty())
        // ★ 关键：不支持"选档位"不等于"没有上下文"，两者独立
        assertEquals(200_000, c.contextLimit)
        assertFalse(c.showsEffortPicker)
    }

    // ------------------------------------------------------------------
    // 真实形态 3：OpenRouter（451 个模型全都没有 limit.context）
    // ------------------------------------------------------------------
    @Test
    fun `OpenRouter 的 context_length 也认`() {
        val body = """
            {"data":[{
              "id": "anthropic/claude-sonnet-4.5",
              "context_length": 1000000,
              "supported_parameters": ["tools", "reasoning", "reasoning_effort"]
            }]}
        """.trimIndent()
        val c = ModelCapabilities.parse(body).getValue("anthropic/claude-sonnet-4.5")

        assertEquals(1_000_000, c.contextLimit)
        // ★ supported_parameters 只声明"能传这个参数"，不声明有哪些档位
        //   ⇒ 必须返回空，绝不能凭它编 low/medium/high
        assertTrue(c.effortLevels.isEmpty())
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------
    @Test
    fun `什么字段都没有时三者都为空而不是崩`() {
        val c = ModelCapabilities.parse("""{"data":[{"id":"x"}]}""").getValue("x")
        assertNull(c.contextLimit)
        assertNull(c.reasoningSupported)
        assertTrue(c.effortLevels.isEmpty())
    }

    @Test
    fun `空档位数组视为没有档位`() {
        val body = """
            {"data":[{"id":"x","capabilities":{"reasoning":{"supported":true,"effort":[]}}}]}
        """.trimIndent()
        assertTrue(ModelCapabilities.parse(body).getValue("x").effortLevels.isEmpty())
    }

    @Test
    fun `一条解析失败不影响其它条`() {
        val body = """
            {"data":[
              {"no_id_here": true},
              {"id": "good", "limit": {"context": 128000}}
            ]}
        """.trimIndent()
        val r = ModelCapabilities.parse(body)
        assertEquals(setOf("good"), r.keys)
        assertEquals(128_000, r.getValue("good").contextLimit)
    }

    @Test
    fun `非 JSON 或缺少 data 时返回空 map`() {
        assertTrue(ModelCapabilities.parse("not json").isEmpty())
        assertTrue(ModelCapabilities.parse("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun `显式档位键值表形态也认`() {
        // DSH 配置里的形态：{档位: 真实API值}
        val body = """
            {"data":[{"id":"x","reasoningEfforts":{"low":"low","high":"high"}}]}
        """.trimIndent()
        val c = ModelCapabilities.parse(body).getValue("x")
        assertEquals(setOf("low", "high"), c.effortLevels.toSet())
    }

    // ==================================================================
    // 读层补的 5 个字段名（2026-11-03 报告二 §4.1 逐条对应）
    // ==================================================================

    /**
     * `limit.input` —— OpenCode 的单次输入上限。
     *
     * ★★ 语义关键：它**只是兜底**。有 `limit.context` 时用它会把上限报小
     *   （OpenCode 实测 context=1000000 / input=160000），而用户被允许
     *   「任意调整到厂家真正的上限」—— 报小是直接的伤害。
     */
    @Test
    fun `limit_input 只在没有总窗口时兜底`() {
        val both = """
            {"data":[{"id":"a","limit":{"context":1000000,"input":160000,"output":384000}}]}
        """.trimIndent()
        assertEquals(
            "有 context 时必须用 context，不能用 input 把上限报小",
            1_000_000,
            ModelCapabilities.parse(both).getValue("a").contextLimit,
        )

        val onlyInput = """{"data":[{"id":"b","limit":{"input":160000}}]}""".trimIndent()
        assertEquals(
            "只有 input 时它就是唯一能拿到的信息，应当兜底",
            160_000,
            ModelCapabilities.parse(onlyInput).getValue("b").contextLimit,
        )
    }

    /** `top_provider.context_length` —— OpenRouter 的托管方窗口（可能与顶层不同）。 */
    @Test
    fun `OpenRouter 的 top_provider 也认`() {
        val body = """
            {"data":[{
              "id": "openrouter/x",
              "context_length": 1024000,
              "top_provider": { "context_length": 262144, "max_completion_tokens": 32000 }
            }]}
        """.trimIndent()
        val r = ModelCapabilities.parse(body)
        assertEquals("顶层优先于托管方", 1_024_000, r.getValue("openrouter/x").contextLimit)

        val onlyTop = """
            {"data":[{"id":"y","top_provider":{"context_length":262144}}]}
        """.trimIndent()
        assertEquals(262_144, ModelCapabilities.parse(onlyTop).getValue("y").contextLimit)
    }

    /** `model_info.context_window` —— DashScope / 阿里百炼。 */
    @Test
    fun `DashScope 的 model_info 也认`() {
        val body = """
            {"data":[{
              "id": "qwen3.8-max",
              "model_info": {
                "context_window": 262144,
                "max_input_tokens": 245760,
                "max_output_tokens": 32768
              }
            }]}
        """.trimIndent()
        assertEquals(262_144, ModelCapabilities.parse(body).getValue("qwen3.8-max").contextLimit)
    }

    /** `max_model_len` —— vLLM 系（SiliconFlow / Together / Fireworks 自建）。 */
    @Test
    fun `vLLM 的 max_model_len 也认`() {
        val body = """{"data":[{"id":"z","max_model_len":131072}]}""".trimIndent()
        assertEquals(131_072, ModelCapabilities.parse(body).getValue("z").contextLimit)
    }

    /**
     * `reasoning.supported_efforts` —— OpenRouter 的档位表。
     *
     * ★★★ 这条是**用户能直接感知到的 bug**：OpenRouter 的 451 个模型以前
     *   **一个档位都显示不出来**，因为代码只读 `capabilities.reasoning.effort`。
     */
    @Test
    fun `OpenRouter 的 reasoning_supported_efforts 能读出档位`() {
        val body = """
            {"data":[{
              "id": "openrouter/y",
              "context_length": 200000,
              "reasoning": {
                "supported_efforts": ["minimal", "low", "medium", "high"],
                "default_effort": "medium",
                "mandatory": false
              }
            }]}
        """.trimIndent()
        val c = ModelCapabilities.parse(body).getValue("openrouter/y")
        assertEquals(
            listOf("minimal", "low", "medium", "high"),
            c.effortLevels,
        )
        assertTrue(c.showsEffortPicker)
    }

    /** 两种 `reasoning` 嵌套位置都要能读到（顶层 / 在 capabilities 里）。 */
    @Test
    fun `supported_efforts 两种嵌套位置都认`() {
        val insideCaps = """
            {"data":[{"id":"p","capabilities":{"reasoning":{
              "supported":true,"supported_efforts":["low","high"]
            }}}]}
        """.trimIndent()
        assertEquals(
            listOf("low", "high"),
            ModelCapabilities.parse(insideCaps).getValue("p").effortLevels,
        )
    }

    /** `effort` 优先于 `supported_efforts` —— 同一个对象里两个都有时不能打架。 */
    @Test
    fun `effort 数组优先于 supported_efforts`() {
        val body = """
            {"data":[{"id":"q","capabilities":{"reasoning":{
              "effort":["low","high","max"],
              "supported_efforts":["a","b"]
            }}}]}
        """.trimIndent()
        assertEquals(
            listOf("low", "high", "max"),
            ModelCapabilities.parse(body).getValue("q").effortLevels,
        )
    }

    /** 5 个新字段**同时**出现时也不能串味（优先级必须稳定）。 */
    @Test
    fun `五种上下文字段同时出现时优先级稳定`() {
        val body = """
            {"data":[{
              "id": "all",
              "limit": {"context": 1000000, "input": 160000},
              "context_length": 500000,
              "top_provider": {"context_length": 262144},
              "model_info": {"context_window": 262144},
              "max_model_len": 131072
            }]}
        """.trimIndent()
        assertEquals(
            "limit.context 是 OpenCode 的权威来源，必须最优先",
            1_000_000,
            ModelCapabilities.parse(body).getValue("all").contextLimit,
        )
    }

    /** 0 / 负数不是有效窗口，必须当成"没读到"而不是"上限是 0"。 */
    @Test
    fun `非正的窗口值视为没读到`() {
        val body = """
            {"data":[
              {"id":"a","limit":{"context":0}},
              {"id":"b","context_length":-1},
              {"id":"c","model_info":{"context_window":0}}
            ]}
        """.trimIndent()
        val r = ModelCapabilities.parse(body)
        assertNull(r.getValue("a").contextLimit)
        assertNull(r.getValue("b").contextLimit)
        assertNull(r.getValue("c").contextLimit)
    }
}
