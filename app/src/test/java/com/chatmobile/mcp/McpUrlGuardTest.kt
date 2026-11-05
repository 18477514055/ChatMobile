package com.chatmobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 配置的安全边界契约。
 *
 * ## 为什么这套测试最重要（比其他都重要）
 *
 * 「模型帮用户配置 MCP」是一个**把网络出口控制权交给模型**的功能。
 * 做错了不是"不好用"，是**安全漏洞**：
 *   - 允许内网地址 ⇒ App 变成内网扫描器
 *   - 允许明文 http ⇒ 内容全程裸奔
 *   - 跳过探测直接保存 ⇒ 用户看到的配置可能是模型编的
 *
 * 所以这些判据必须在**执行路径**上，而不是只在 UI 上拦（模型能绕过 UI）。
 */
class McpUrlGuardTest {

    // ------------------------------------------------------------------
    // 一、正常地址
    // ------------------------------------------------------------------

    @Test
    fun `正常的 https 地址通过`() {
        val v = McpUrlGuard.check("https://mcp.example.com/mcp")
        assertTrue("应该通过：$v", v is McpUrlGuard.Verdict.Ok)
        assertEquals("https://mcp.example.com/mcp", (v as McpUrlGuard.Verdict.Ok).normalized)
    }

    @Test
    fun `没写 scheme 时补 https 而不是 http`() {
        // ★ 补 http 会让用户以为"能用"，实际全程裸奔且无人告知
        val v = McpUrlGuard.check("mcp.example.com/sse")
        assertTrue(v is McpUrlGuard.Verdict.Ok)
        assertTrue(
            "必须补 https",
            (v as McpUrlGuard.Verdict.Ok).normalized.startsWith("https://"),
        )
    }

    @Test
    fun `带端口与路径的地址通过`() {
        assertTrue(
            McpUrlGuard.check("https://mcp.example.com:8443/v1/mcp")
                is McpUrlGuard.Verdict.Ok,
        )
    }

    // ------------------------------------------------------------------
    // 二、内网 —— 最重要的一条
    // ------------------------------------------------------------------

    @Test
    fun `192168 网段必须拒绝`() {
        // ★ 允许内网等于把 App 变成内网扫描器：
        //   模型被诱导填一个地址，就能探测用户家里的路由器
        val v = McpUrlGuard.check("https://192.168.1.1/mcp")
        assertTrue("必须拒绝：$v", v is McpUrlGuard.Verdict.Rejected)
    }

    @Test
    fun `127 环回必须拒绝`() {
        assertTrue(McpUrlGuard.check("https://127.0.0.1:8080/mcp") is McpUrlGuard.Verdict.Rejected)
    }

    @Test
    fun `10 网段必须拒绝`() {
        assertTrue(McpUrlGuard.check("https://10.0.0.5/mcp") is McpUrlGuard.Verdict.Rejected)
    }

    @Test
    fun `17216 到 31 必须拒绝`() {
        assertTrue(McpUrlGuard.check("https://172.16.0.1/mcp") is McpUrlGuard.Verdict.Rejected)
        assertTrue(McpUrlGuard.check("https://172.31.255.1/mcp") is McpUrlGuard.Verdict.Rejected)
        // 172.32 不是私有段
        assertFalse(McpUrlGuard.isPrivateHost("172.32.0.1"))
    }

    @Test
    fun `169254 link-local 必须拒绝`() {
        assertTrue(McpUrlGuard.isPrivateHost("169.254.1.1"))
    }

    @Test
    fun `内网主机名后缀必须拒绝`() {
        assertTrue(McpUrlGuard.isPrivateHost("myserver.local"))
        assertTrue(McpUrlGuard.isPrivateHost("nas.lan"))
        assertTrue(McpUrlGuard.isPrivateHost("api.internal"))
    }

    @Test
    fun `IPv6 ULA 与 link-local 必须拒绝`() {
        assertTrue(McpUrlGuard.isPrivateHost("fd00::1"))
        assertTrue(McpUrlGuard.isPrivateHost("fe80::1"))
    }

    // ------------------------------------------------------------------
    // 三、明文 http
    // ------------------------------------------------------------------

    @Test
    fun `公网明文 http 必须拒绝`() {
        val v = McpUrlGuard.check("http://mcp.example.com/mcp")
        assertTrue("必须拒绝明文：$v", v is McpUrlGuard.Verdict.Rejected)
    }

    @Test
    fun `ws 地址被明确拒绝并给出替代`() {
        val v = McpUrlGuard.check("ws://mcp.example.com/mcp")
        assertTrue(v is McpUrlGuard.Verdict.Rejected)
        assertTrue(
            "必须告诉用户换成什么",
            (v as McpUrlGuard.Verdict.Rejected).reason.contains("https"),
        )
    }

    // ------------------------------------------------------------------
    // 四、格式错误
    // ------------------------------------------------------------------

    @Test
    fun `空地址被拒绝`() {
        assertTrue(McpUrlGuard.check("") is McpUrlGuard.Verdict.Rejected)
        assertTrue(McpUrlGuard.check("   ") is McpUrlGuard.Verdict.Rejected)
    }

    @Test
    fun `没有点号的主机名被拒绝并解释原因`() {
        val v = McpUrlGuard.check("https://myserver/mcp")
        assertTrue(v is McpUrlGuard.Verdict.Rejected)
        val reason = (v as McpUrlGuard.Verdict.Rejected).reason
        assertTrue(
            "要说明是「缺少点号」，而不只是「不合法」：$reason",
            reason.contains("点号") || reason.contains("IP"),
        )
    }

    @Test
    fun `不支持的 scheme 被拒绝`() {
        assertTrue(McpUrlGuard.check("ftp://example.com/x") is McpUrlGuard.Verdict.Rejected)
        assertTrue(McpUrlGuard.check("file:///etc/passwd") is McpUrlGuard.Verdict.Rejected)
    }

    // ------------------------------------------------------------------
    // 五、地址脱敏（token 不能进确认框、更不能进日志）
    // ------------------------------------------------------------------

    @Test
    fun `查询串里的 key 必须脱敏`() {
        val out = McpUrlGuard.redactForDisplay("https://x.com/mcp?key=SECRET123&foo=bar")
        assertFalse("token 不能出现", out.contains("SECRET123"))
        assertTrue("要保留 key 名，用户才知道有这么个参数", out.contains("key=***"))
        assertTrue("非敏感参数保留原值", out.contains("foo=bar"))
    }

    @Test
    fun `各种敏感参数名都要认`() {
        for (k in listOf("key", "token", "secret", "password", "apikey", "api_key", "auth")) {
            assertTrue("$k 必须被视为敏感", McpUrlGuard.isSensitiveKey(k))
        }
        assertFalse(McpUrlGuard.isSensitiveKey("mode"))
    }

    @Test
    fun `没有查询串时原样返回`() {
        assertEquals("https://x.com/mcp", McpUrlGuard.redactForDisplay("https://x.com/mcp"))
    }
}
