package com.chatmobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置厂商目录的**结构契约**。
 *
 * ## ★★★ 这个测试是为了一个真实 bug 而写的
 *
 * `Providers.all` 曾经是 48 条预设但只有 **46 个唯一 id**：
 * `moonshotai` 与 `zai` 各出现两次。而 `byId()` 是 `firstOrNull`
 * ⇒ **第二条在运行期永远选不到**。用户点「Kimi 国际站」「Z.ai」，
 * 拿到的其实是撞号那条的地址与 Key 体系，对不上就 401，而界面上看着一切正常。
 *
 * 这类 bug 的特点：**编译能过、点了不报错、只有真花钱试才发现**。
 * 所以必须用一条结构性断言把它钉死 —— 以后谁再往 `all` 里加一条撞号的预设，
 * 测试立刻红，而不是等用户去撞。
 */
class ProvidersTest {

    @Test
    fun `所有预置 id 必须唯一`() {
        val ids = Providers.all.map { it.id }
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }

        assertEquals(
            "id 重复会让 byId() 只能解析到第一条，撞号的那条永远选不到。" +
                "重复的是：$dupes",
            emptyMap<String, Int>(),
            dupes,
        )
    }

    @Test
    fun `每一条都能被 byId 解析回自己`() {
        for (p in Providers.all) {
            val back = Providers.byId(p.id)
            assertEquals(
                "byId(${p.id}) 解析到了别的一条 —— 说明有撞号，或名字被覆盖",
                p.id,
                back?.id,
            )
            assertEquals(
                "byId(${p.id}) 的显示名对不上，说明撞号后取错了对象",
                p.displayName,
                back?.displayName,
            )
        }
    }

    @Test
    fun `自定义必须排在最后`() {
        // 预设列表最后一条是兜底的「自定义」，它自带一个空 baseUrl 的占位端点。
        // 排在中间会让 UI 的"最后一个是自定义"这类假设失效。
        assertEquals("custom", Providers.all.last().id)
    }

    @Test
    fun `每条预设至少有一个接入点`() {
        for (p in Providers.all) {
            assertTrue(
                "${p.id} 一个 endpoint 都没有 —— 选它之后没有任何地址可发请求",
                p.endpoints.isNotEmpty(),
            )
            assertTrue("${p.id} 的 displayName 不能为空", p.displayName.isNotBlank())
        }
    }

    @Test
    fun `id 都是小写短横线风格`() {
        // 不是为了美观：id 会被存进 DataStore 并参与文件名/查询，
        // 大小写混用会让"同一个厂商"看起来像两个。
        val bad = Providers.all.map { it.id }.filter { !Regex("^[a-z0-9-]+$").matches(it) }
        assertEquals("这些 id 不符合小写短横线风格：$bad", emptyList<String>(), bad)
    }

    /**
     * 思考形态的落点：**Qwen / SiliconFlow 必须不是 EFFORT_ONLY**。
     *
     * ★ 因为这两家不认 `reasoning_effort` —— 只发档位是**静默无效**：
     *   不报错、也没效果，用户会以为"调了强度但模型没变"。
     *   这类 bug 只能靠结构性断言提前拦住。
     */
    @Test
    fun `不认 reasoning_effort 的厂商必须用布尔开关形态`() {
        val mustUseEnableThinking = listOf(
            "qwen",
            "qwen-token-plan",
            "qwen-token-plan-cn",
            "qwen-token-plan-individual",
            "siliconflow",
        )
        for (id in mustUseEnableThinking) {
            val p = Providers.byId(id)
            assertTrue("找不到预设 $id", p != null)
            assertEquals(
                "$id 不认 reasoning_effort，必须走 enable_thinking",
                ThinkingShape.ENABLE_THINKING,
                p!!.thinkingShape,
            )
        }
    }

    @Test
    fun `开关型厂商必须标成开关加档位`() {
        for (id in listOf("deepseek", "zai")) {
            val p = Providers.byId(id)
            assertTrue("找不到预设 $id", p != null)
            assertEquals(
                "$id 的思考是「开关 + 强度」两件事，只发强度等于没调",
                ThinkingShape.EFFORT_PLUS_THINKING_SWITCH,
                p!!.thinkingShape,
            )
        }
    }

    @Test
    fun `输出上限键名只在需要的厂商上切换`() {
        // 只有 OpenAI 新模型与 Groq 认 max_completion_tokens；
        // 其它家发它会被忽略或 400 ⇒ 必须保持 false。
        assertEquals(true, Providers.byId("openai")!!.usesMaxCompletionTokens)
        assertEquals(true, Providers.byId("groq")!!.usesMaxCompletionTokens)
        assertEquals(false, Providers.byId("deepseek")!!.usesMaxCompletionTokens)
        assertEquals(false, Providers.byId("qwen")!!.usesMaxCompletionTokens)
    }

    @Test
    fun `思考形态会传进运行时配置`() {
        // ProviderPreset.toConfig() 必须把这两个新字段带下去，
        // 否则预设上写了也白写（建请求体时读的是 ProviderConfig）。
        val p = Providers.byId("qwen")!!
        val cfg = p.toConfig(apiKey = "k")
        assertEquals(ThinkingShape.ENABLE_THINKING, cfg.thinkingShape)

        val o = Providers.byId("openai")!!
        assertTrue(o.toConfig(apiKey = "k").usesMaxCompletionTokens)
    }
}
