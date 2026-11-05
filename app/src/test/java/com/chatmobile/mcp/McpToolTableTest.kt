package com.chatmobile.mcp

import chatmobile.tools.ChatTools
import chatmobile.tools.McpTools
import chatmobile.tools.ToolCatalog
import chatmobile.tools.ToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **MCP 接入工具表**的契约（P2）。
 *
 * ## 这个文件防的是什么
 *
 * MCP 有两种失败方式，而且**第二种更隐蔽**：
 *
 * | 失败 | 表现 | 能不能发现 |
 * |---|---|---|
 * | 连不上服务器 | 有错误提示 | ✅ 一眼看到 |
 * | **接进去了，但它不在工具表里** | 什么都不报，模型永远用不到 | ❌ 没人会注意到 |
 *
 * 第二种就是台账里那条教训「**机制都在，唯独连接不存在**」。
 * 所以这里的断言全都是"**它真的出现在该出现的地方吗**"。
 */
class McpToolTableTest {

    private fun server(
        name: String = "Demo",
        url: String = "https://example.com/mcp",
        prefix: String = "",
        enabled: Boolean = true,
    ) = McpServerConfig(name = name, url = url, transport = "streamable-http", enabled = enabled, prefix = prefix)

    private fun toolInfo(name: String = "do_thing") = McpToolInfo(
        name = name,
        description = "Does a thing",
        inputSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""",
    )

    // ------------------------------------------------------------------
    // 一、从配置到工具定义
    // ------------------------------------------------------------------

    @Test
    fun `一个 MCP 工具会变成一条带前缀的定义`() {
        val reg = McpToolRegistry()
        val servers = listOf(server(prefix = "demo"))
        val defs = reg.buildDefinitions(servers) { McpProbeResult.Ok("Demo", "1.0", listOf(toolInfo()), "streamable-http") }

        assertEquals(1, defs.size)
        // ★ 前缀必须有：MCP 规范明确说工具名唯一性只到单服务器内，多服务器一定撞名
        assertEquals("demo__do_thing", defs[0].name)
    }

    @Test
    fun `MCP 工具一律默认按需 而不是常驻`() {
        val reg = McpToolRegistry()
        val defs = reg.buildDefinitions(listOf(server())) {
            McpProbeResult.Ok("Demo", null, listOf(toolInfo()), "streamable-http")
        }
        // ★ 一个 MCP 可能带来上百个工具。默认常驻会瞬间把上下文吃光，
        //   而用户刚加进来时还不知道哪些有用。
        assertEquals(ToolMode.Lazy, defs[0].preferredMode)
    }

    @Test
    fun `MCP 的 inputSchema 必须原样透传 不能被降级解析`() {
        val reg = McpToolRegistry()
        val tricky = ToolInfoWithUnion()
        val defs = reg.buildDefinitions(listOf(server(prefix = "d"))) {
            McpProbeResult.Ok("Demo", null, listOf(tricky), "streamable-http")
        }
        val raw = defs[0].spec.rawParametersJson
        assertNotNull("MCP 的 schema 必须走原样透传", raw)
        // ★ oneOf 是我们的简化模型表达不了的东西 —— 丢了就等于丢约束，模型必然传错
        assertTrue("oneOf 被弄丢了：$raw", raw!!.contains("oneOf"))
    }

    @Test
    fun `停用的服务器不贡献任何工具`() {
        val reg = McpToolRegistry()
        val defs = reg.buildDefinitions(listOf(server(enabled = false))) {
            McpProbeResult.Ok("Demo", null, listOf(toolInfo()), "streamable-http")
        }
        assertTrue("停用的服务器不该产生工具", defs.isEmpty())
    }

    @Test
    fun `探测失败时不产生工具 也不崩`() {
        val reg = McpToolRegistry()
        val defs = reg.buildDefinitions(listOf(server())) {
            McpProbeResult.Failed("连不上", "检查地址")
        }
        assertTrue(defs.isEmpty())
    }

    // ------------------------------------------------------------------
    // 二、★★★ 最关键：它真的进工具表了吗
    // ------------------------------------------------------------------

    @Test
    fun `MCP 工具必须真的出现在工具表里`() {
        val reg = McpToolRegistry()
        val servers = listOf(server(prefix = "demo"))
        val mcpDefs = reg.buildDefinitions(servers) {
            McpProbeResult.Ok("Demo", null, listOf(toolInfo()), "streamable-http")
        }
        val all = ChatTools.all() + McpTools.all() + mcpDefs
        val modes = all.associate { it.name to it.preferredMode }
        val json = ToolCatalog.build(all, modes).toolsJson!!

        // 按需工具不会出现在完整 schema 里，但**必须出现在目录里** ——
        // 否则模型根本不知道它存在，永远召不回来。
        assertTrue(
            "MCP 工具没进目录 —— 这正是「加进来却不存在」那种错：什么都正常，就是永远用不到",
            json.contains("demo__do_thing"),
        )
    }

    @Test
    fun `MCP 工具能被 describe_tool 召回`() {
        // ★ 目录里有它、召回却查不到它 = 模型照着点名却被告知"没有这个工具"。
        //   所以 dynamicToolDefinition 那条路必须通。
        val reg = McpToolRegistry()
        val defs = reg.buildDefinitions(listOf(server(prefix = "demo"))) {
            McpProbeResult.Ok("Demo", null, listOf(toolInfo()), "streamable-http")
        }
        val host = FakeHost(defs.associateBy { it.name })

        // 这里只能测到"Host 能提供它"，执行器那条路由 McpToolExecutorTest 覆盖
        val d = host.dynamicToolDefinition("demo__do_thing")
        assertNotNull("Host 必须能回查 MCP 工具的定义", d)
        assertEquals("demo__do_thing", d!!.name)
    }

    // ------------------------------------------------------------------
    // 三、命名与去重（多服务器一定会撞名）
    // ------------------------------------------------------------------

    @Test
    fun `两个服务器有同名工具时不撞名`() {
        val reg = McpToolRegistry()
        val a = reg.buildDefinitions(listOf(server(name = "A", url = "https://a.com/mcp", prefix = "a"))) {
            McpProbeResult.Ok("A", null, listOf(toolInfo("search")), "streamable-http")
        }
        val b = reg.buildDefinitions(listOf(server(name = "B", url = "https://b.com/mcp", prefix = "b"))) {
            McpProbeResult.Ok("B", null, listOf(toolInfo("search")), "streamable-http")
        }
        // ★ MCP 规范明确警告：两个 server 都有 `search` 是常态
        assertEquals("a__search", a[0].name)
        assertEquals("b__search", b[0].name)
        assertTrue(a[0].name != b[0].name)
    }

    @Test
    fun `中文服务器名也能推出合法前缀`() {
        // ★ 工具名必须匹配 ^[a-zA-Z0-9_-]{1,64}$，而服务器名可能是中文
        val prefix = McpConfigCodec.prefixFrom("我的服务器")
        assertTrue(
            "前缀 '$prefix' 不是合法的工具名字符集",
            prefix.isEmpty() || prefix.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")),
        )
        // 净化为空时必须兜底成 mcp，不能变成空串
        assertTrue("前缀不能是空的", prefix.isNotEmpty())
    }

    @Test
    fun `拼出来的工具名一定合法`() {
        val name = McpConfigCodec.toolName("demo", "some/weird name!!")
        assertTrue(
            "生成的工具名 '$name' 不合法 —— ToolRegistry.register 会直接 require 失败",
            name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")),
        )
    }

    // ------------------------------------------------------------------
    // 四、归属判定（isMcpTool）
    // ------------------------------------------------------------------

    @Test
    fun `能认出自己的工具`() {
        val reg = McpToolRegistry()
        val servers = listOf(server(prefix = "demo"))
        assertTrue(reg.owns("demo__do_thing", servers))
    }

    @Test
    fun `不会把内建工具认成自己的`() {
        val reg = McpToolRegistry()
        val servers = listOf(server(prefix = "demo"))
        // ★ 若这里误判成 true，内建工具会被转发给 MCP —— 直接全坏
        assertFalse(reg.owns("web_search", servers))
        assertFalse(reg.owns("describe_tool", servers))
    }

    @Test
    fun `停用服务器的工具不再被认领`() {
        val reg = McpToolRegistry()
        val servers = listOf(server(prefix = "demo", enabled = false))
        assertFalse(reg.owns("demo__do_thing", servers))
    }

    @Test
    fun `调不存在的服务器给出明确失败而不抛异常`() {
        val reg = McpToolRegistry()
        val out = reg.call("nobody__x", "{}", listOf(server(prefix = "demo")))
        assertTrue("必须是可读的失败文本，实际：$out", out.startsWith("ERROR:"))
    }

    // ------------------------------------------------------------------
    // 五、MCP 元工具（配置入口）
    // ------------------------------------------------------------------

    @Test
    fun `两个 MCP 元工具必须是常驻`() {
        // ★ 若它们自己还要先 describe，"配置 MCP"这条链在第一跳就卡住。
        assertEquals(ToolMode.Resident, McpTools.probe.preferredMode)
        assertEquals(ToolMode.Resident, McpTools.saveDraft.preferredMode)
    }

    @Test
    fun `mcp_save_draft 的参数里不许有服务器名`() {
        // ★★★ 服务器名必须来自探测（serverInfo.name），不能让模型填 ——
        //   否则它可以把一个恶意地址起名叫「GitHub 官方」，用户在确认框里看到的是假的。
        val props = McpTools.saveDraft.spec.parameters.properties.keys
        assertFalse(
            "mcp_save_draft 不该接受服务器名参数，实际参数：$props",
            props.any { it.contains("name", ignoreCase = true) && it != "url" },
        )
        assertTrue("必须收 url", props.contains("url"))
    }

    @Test
    fun `两个元工具的名字不与其他工具冲突`() {
        val builtin = ChatTools.all().map { it.name }.toSet()
        for (t in McpTools.all()) {
            assertFalse("元工具 '${t.name}' 与内建工具重名", t.name in builtin)
        }
    }

    // ------------------------------------------------------------------

    /** 一个带 oneOf 的 schema —— 用来验证原样透传。 */
    private fun ToolInfoWithUnion() = McpToolInfo(
        name = "unions",
        description = "has a union",
        inputSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}},""" +
            """"oneOf":[{"required":["q"]},{"required":["id"]}]}""",
    )

    /** 最小 Host —— 只实现这条测试需要的两个方法。 */
    private class FakeHost(
        private val defs: Map<String, chatmobile.tools.ToolDefinition>,
    ) : chatmobile.tools.ToolHost {
        override fun nowText(timezone: String?): String = ""
        override fun locationText(precise: Boolean): String = ""
        override fun fetchUrl(url: String, maxChars: Int): String = ""
        override fun saveMemory(content: String, category: String?): String = ""
        override fun searchMemory(query: String): String = ""
        override fun readFile(path: String, maxChars: Int): String = ""
        override fun writeFile(path: String, content: String, append: Boolean): String = ""
        override fun listDir(path: String?): String = ""
        override fun webSearch(query: String, count: Int, usePaid: Boolean): String = ""
        override fun currentConversationIdHint(): String? = null
        override fun searchHistory(
            query: String?,
            mode: String?,
            days: Int?,
            count: Int,
            excludeConversationId: String?,
        ): String = ""
        override fun workspaceRootHint(): String = ""
        override fun dynamicToolDefinition(toolName: String): chatmobile.tools.ToolDefinition? =
            defs[toolName]
    }
}
