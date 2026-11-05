package com.chatmobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型起草 MCP 配置」的流程契约。
 *
 * ## 核心：起草 ≠ 保存
 *
 * 最容易做错的是让模型一次调用就把配置写进去 —— 那等于把
 * "你的 App 向哪些地址发过请求"的控制权交给模型，用户没有察觉的机会。
 *
 * ⇒ 三段式：`probe`（只读）→ `draft`（起草，产生可见待办）→ **用户确认** → 保存。
 *
 * 本测试钉的是中间那一环：**起草的合法性判据，且不信任模型给的一切**。
 */
class McpDraftPolicyTest {

    private fun check(
        name: String = "示例服务器",
        url: String = "https://mcp.example.com/sse",
        probed: Boolean = true,
        toolCount: Int = 3,
        existingUrls: Set<String> = emptySet(),
    ) = McpDraftPolicy.check(
        serverName = name,
        url = url,
        serverVersion = "1.0",
        probed = probed,
        toolCount = toolCount,
        transport = "sse",
        existingUrls = existingUrls,
    )

    // ------------------------------------------------------------------
    // 一、正常起草
    // ------------------------------------------------------------------

    @Test
    fun `探测过的地址可以起草`() {
        val v = check()
        assertTrue("应该通过：$v", v is McpDraftPolicy.Verdict.Ok)
        val d = (v as McpDraftPolicy.Verdict.Ok).draft
        assertEquals("示例服务器", d.serverName)
        assertEquals(3, d.toolCount)
        assertEquals("sse", d.transport)
    }

    @Test
    fun `地址会被规范化`() {
        val v = McpDraftPolicy.check(
            serverName = "x",
            url = "mcp.example.com/sse",     // 没写 scheme
            serverVersion = null,
            probed = true,
            toolCount = 2,
            transport = "sse",
            existingUrls = emptySet(),
        )
        assertTrue(v is McpDraftPolicy.Verdict.Ok)
        assertTrue(
            "必须补成 https",
            (v as McpDraftPolicy.Verdict.Ok).draft.url.startsWith("https://"),
        )
    }

    @Test
    fun `空服务器名用占位而不是失败`() {
        val v = check(name = "   ")
        assertTrue(v is McpDraftPolicy.Verdict.Ok)
        assertEquals(
            "MCP 服务器",
            (v as McpDraftPolicy.Verdict.Ok).draft.serverName,
        )
    }

    // ------------------------------------------------------------------
    // 二、必须探测过（防模型凭空编配置）
    // ------------------------------------------------------------------

    @Test
    fun `没探测过不许起草`() {
        // ★★ 模型完全可能跳过 probe，直接编一个"服务器名 + 工具数"。
        //   那样用户在确认框里看到的全是虚构的。
        val v = check(probed = false)
        assertTrue("必须拒绝：$v", v is McpDraftPolicy.Verdict.Rejected)
        val hint = (v as McpDraftPolicy.Verdict.Rejected).hint
        assertTrue("必须告诉它先 probe：$hint", hint.contains("mcp_probe"))
    }

    @Test
    fun `工具数为 0 时不许起草`() {
        // 一个没有工具的 MCP 加进来只是凭空多一次网络请求
        val v = check(toolCount = 0)
        assertTrue(v is McpDraftPolicy.Verdict.Rejected)
    }

    // ------------------------------------------------------------------
    // 三、不重复
    // ------------------------------------------------------------------

    @Test
    fun `已配置的地址不许重复添加`() {
        val v = check(existingUrls = setOf("https://mcp.example.com/sse"))
        assertTrue("必须拒绝重复：$v", v is McpDraftPolicy.Verdict.Rejected)
        val hint = (v as McpDraftPolicy.Verdict.Rejected).hint
        assertTrue(
            "要告诉用户该去哪儿改模式，而不是只说重复：$hint",
            hint.contains("模式") || hint.contains("常驻"),
        )
    }

    @Test
    fun `去重是按规范化后的地址比`() {
        // 用户已经配了带 scheme 的，模型又报了个不带的 —— 必须认出来是同一个
        val v = check(
            url = "mcp.example.com/sse",
            existingUrls = setOf("https://mcp.example.com/sse"),
        )
        assertTrue("必须识别为重复：$v", v is McpDraftPolicy.Verdict.Rejected)
    }

    // ------------------------------------------------------------------
    // 四、地址安全性复用 UrlGuard（不能有两套判据）
    // ------------------------------------------------------------------

    @Test
    fun `内网地址起草时同样被拒`() {
        val v = check(url = "https://192.168.1.1/mcp")
        assertTrue(v is McpDraftPolicy.Verdict.Rejected)
    }

    @Test
    fun `明文 http 起草时同样被拒`() {
        val v = check(url = "http://mcp.example.com/mcp")
        assertTrue(v is McpDraftPolicy.Verdict.Rejected)
    }

    // ------------------------------------------------------------------
    // 五、确认框文案（用户做决定的全部依据）
    // ------------------------------------------------------------------

    @Test
    fun `确认文案必须包含地址 工具数 和这是模型提议的`() {
        val draft = McpDraft(
            serverName = "示例",
            url = "https://mcp.example.com/sse",
            serverVersion = "1.0",
            toolCount = 7,
            transport = "sse",
        )
        val msg = McpDraftPolicy.confirmMessage(draft)

        assertTrue("必须显示地址", msg.contains("mcp.example.com"))
        assertTrue("必须显示工具数", msg.contains("7"))
        assertTrue(
            "必须说明是模型提议的 —— 让用户知道不是 App 自己要连",
            msg.contains("模型"),
        )
        assertTrue(
            "必须说明默认是按需，否则用户会担心上下文被吃光",
            msg.contains("按需") || msg.contains("常驻"),
        )
    }

    @Test
    fun `确认文案里的 token 必须脱敏`() {
        val draft = McpDraft(
            serverName = "示例",
            url = "https://mcp.example.com/sse?key=TOPSECRET",
            toolCount = 1,
            transport = "sse",
        )
        val msg = McpDraftPolicy.confirmMessage(draft)
        assertFalse("token 不能出现在确认框：$msg", msg.contains("TOPSECRET"))
        assertTrue("但要保留参数名", msg.contains("key=***"))
    }

    @Test
    fun `确认文案必须写明只有点添加才真的保存`() {
        val draft = McpDraft("x", "https://mcp.example.com/sse", null, 1, "sse")
        val msg = McpDraftPolicy.confirmMessage(draft)
        assertTrue(
            "必须让用户知道还有拒绝的机会：$msg",
            msg.contains("只有你点") || msg.contains("才会真的保存"),
        )
    }

    // ------------------------------------------------------------------
    // 六、新加的 MCP 默认懒加载
    // ------------------------------------------------------------------

    @Test
    fun `MCP 工具默认按需 不默认常驻`() {
        // ★ 一个 MCP 可能带来几十上百个工具，默认常驻会瞬间把上下文吃光
        assertEquals("Lazy", McpDraftPolicy.defaultModeForMcp)
    }
}
