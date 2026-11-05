package com.chatmobile.mcp

/**
 * MCP 服务器地址的**安全校验**。
 *
 * ## ★★★ 为什么这件事不能交给模型判断
 *
 * 用户要求：「模型可以帮用户配置 mcp」。
 * 但"连哪个地址"是**用户的隐私边界**，不是模型的自由：
 *
 * | 风险 | 后果 |
 * |---|---|
 * | 模型被诱导填内网地址（`192.168.*` / `127.*`） | App 变成内网扫描器 |
 * | 地址里带 token（`?key=xxx`） | token 进对话记录，**落盘不可撤销** |
 * | 明文 `http://` | 内容全程裸奔 |
 *
 * ⇒ 所以判据都在这里，**纯函数**、可完整单测。
 *   模型只能"起草"，能不能保存由这里的规则决定，且最终还要用户点确认。
 *
 * ## ★ 与"阻止模型"相比，更该做的是"让它没有能力"
 *
 * 一个只在 UI 层拦的实现是挡不住的 —— 模型可以直接构造 URL 传给工具。
 * 所以判据放在**执行路径**上：`probe()` 一开始就调用 [check]。
 */
object McpUrlGuard {

    /** 校验结果。 */
    sealed interface Verdict {
        /** 可用。[normalized] 是补全 scheme 之后的地址。 */
        data class Ok(val normalized: String) : Verdict

        /** 拒绝。`reason` 会原样给用户/模型看。 */
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 校验一个地址。
     *
     * @param raw 用户或模型给的地址
     * @param allowPrivate 是否允许内网地址（默认 **false**）
     */
    fun check(raw: String, allowPrivate: Boolean = false): Verdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("地址是空的")

        // ---- 1. scheme 必须能确定 ----
        //
        // ★ 没写 scheme 时**补 https**，不补 http：MCP 绝大多数在公网，
        //   而默认明文会让用户以为"能用"，实际全程裸奔且无人告知。
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) ||
                trimmed.startsWith("wss://", ignoreCase = true) ->
                return Verdict.Rejected(
                    "不支持 WebSocket 地址。MCP 用 HTTP 的 SSE 或 streamable-http，" +
                        "请把 ws:// 换成 https://",
                )
            trimmed.contains("://") ->
                return Verdict.Rejected("只支持 http/https 地址")
            else -> "https://$trimmed"
        }

        // ---- 2. 解析主机名 ----
        val noScheme = withScheme.substringAfter("://")
        val host = noScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')     // 去掉 userinfo
            .substringBefore(':')    // 去掉端口
            .lowercase()

        if (host.isEmpty()) return Verdict.Rejected("地址里没有主机名")
        if (!host.contains('.')) {
            return Verdict.Rejected(
                "「" + host + "」不像一个完整的域名（缺少点号）。" +
                    "如果确实是局域网主机，请直接填它的 IP。",
            )
        }

        // ---- 3. 拒绝内网 / 环回 ----
        //
        // ★ 这是最重要的一条。允许内网等于把这个 App 变成内网扫描器 ——
        //   模型只要被诱导填一个 192.168.1.1，就能探测你家路由器。
        if (!allowPrivate && isPrivateHost(host)) {
            return Verdict.Rejected(
                "「" + host + "」是本地/内网地址，出于安全不允许。",
            )
        }

        // ---- 4. 明文 http 只允许 localhost（自建测试） ----
        if (withScheme.startsWith("http://", ignoreCase = true) && !isLoopback(host)) {
            return Verdict.Rejected(
                "只支持 https://。明文 http 会让传输内容全程可见。" +
                    "如果服务商只给 http，说明那个地址可能不是公网服务。",
            )
        }

        return Verdict.Ok(withScheme)
    }

    /** 是否是环回地址。 */
    fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

    /** 是否是内网/保留地址。 */
    fun isPrivateHost(host: String): Boolean {
        if (isLoopback(host)) return true

        // IPv4 私有段 + link-local + CGNAT
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(host)
        if (v4 != null) {
            val a = v4.groupValues[1].toIntOrNull() ?: return true
            val b = v4.groupValues[2].toIntOrNull() ?: return true
            return when {
                a == 10 -> true                      // 10.0.0.0/8
                a == 172 && b in 16..31 -> true       // 172.16.0.0/12
                a == 192 && b == 168 -> true          // 192.168.0.0/16
                a == 127 -> true                      // loopback
                a == 169 && b == 254 -> true          // link-local
                a == 100 && b in 64..127 -> true      // CGNAT
                a == 0 -> true                        // 0.0.0.0/8
                else -> false
            }
        }

        // IPv6：`fc00::/7`（ULA）与 `fe80::/10`（link-local）
        val h = host.removePrefix("[")
        if (h.contains(':')) {
            val low = h.lowercase()
            if (low.startsWith("fc") || low.startsWith("fd")) return true
            if (low.startsWith("fe8") || low.startsWith("fe9") ||
                low.startsWith("fea") || low.startsWith("feb")
            ) {
                return true
            }
        }

        // 常见的内网主机名后缀
        return host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal")
    }

    /**
     * 从地址里剔除敏感查询参数后的**展示形态**。
     *
     * ★ 用途：确认框里给用户看的地址不能把 token 显示出来 ——
     *   那和人脸识别时把密码念出来一样。但也不能完全藏掉（用户要能核对地址）。
     *
     * ⇒ 保留主机与路径，`key` / `token` / `secret` 这类参数替换成 `***`。
     */
    fun redactForDisplay(url: String): String {
        val idx = url.indexOf('?')
        if (idx < 0) return url
        val base = url.substring(0, idx)
        val query = url.substring(idx + 1)
        val redacted = query.split('&').joinToString("&") { pair ->
            val key = pair.substringBefore('=')
            if (isSensitiveKey(key)) "$key=***" else pair
        }
        return "$base?$redacted"
    }

    /** 这个查询参数名是否敏感。 */
    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return listOf("key", "token", "secret", "password", "passwd", "auth", "apikey", "api_key")
            .any { k == it || k.contains(it) }
    }
}
