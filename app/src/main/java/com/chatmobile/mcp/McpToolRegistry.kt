package com.chatmobile.mcp

import chatmobile.tools.ToolDefinition
import chatmobile.tools.ToolKind
import chatmobile.tools.ToolMode
import chatmobile.tools.ToolParameters
import chatmobile.tools.ToolSpec

/**
 * **MCP 工具注册表** —— 把「已保存的服务器配置 + 运行时探测到的工具清单」
 * 变成可以塞进工具表的 [ToolDefinition]。
 *
 * ## ★★★ 为什么必须有一个注册表，而不是每次现拼
 *
 * 工具表在一轮对话里会被**构建一次、执行多次**（模型可能连续调好几个工具）。
 * 若每次执行都去问服务端"你有哪些工具"，一轮对话就是 N 次网络往返 ——
 * 而答案在那一轮里根本不会变。
 *
 * ⇒ 一轮开始前**探测一次**，把结果缓存在这里；这一轮内所有地方都用缓存。
 *
 * ## ★★ 为什么不落库工具清单
 *
 * 服务端随时可能改（规范里就有 `tools/list_changed`）。落库就一定会过期，
 * 而过期的清单会让模型调用一个已经不存在的工具。
 * ⇒ **只缓存内存里的这一轮**，每轮重新探测（或者用 [cacheTtlMs] 控制频率）。
 *
 * ## ★ 懒加载默认值的差异
 *
 * 内建工具默认「常驻 3–5 个，其余按需」；而 **MCP 工具一律默认按需**。
 * 理由：一个 MCP 可能带来几十上百个工具，默认常驻会瞬间把上下文吃光，
 * 而用户刚加进来时还不知道哪些有用。
 */
class McpToolRegistry(
    private val callClient: McpCallClient = McpCallClient(),
) {

    /**
     * 每轮缓存：`服务器 url` → 它的工具清单。
     *
     * ★ 用 url 而不是 name 做键：name 是显示名，用户可能改；url 才是身份。
     */
    private val cache = LinkedHashMap<String, CachedTools>()

    /** 一条缓存。 */
    private data class CachedTools(
        val tools: List<McpToolInfo>,
        val fetchedAt: Long,
    )

    /**
     * 把一批配置变成工具定义。
     *
     * @param servers 已启用（`enabled=true`）的服务器
     * @param probe 探测函数（注入以便单测；真实实现走 [McpProbeClient]）
     * @return 全部 MCP 工具的 [ToolDefinition]（**一律 [ToolMode.Lazy]**）
     */
    fun buildDefinitions(
        servers: List<McpServerConfig>,
        probe: (McpServerConfig) -> McpProbeResult,
    ): List<ToolDefinition> {
        val out = mutableListOf<ToolDefinition>()
        for (server in servers) {
            if (!server.enabled) continue
            val tools = toolsOf(server, probe)
            for (info in tools) {
                out += toDefinition(server, info)
            }
        }
        return out
    }

    /** 取某个服务器的工具清单（优先用缓存）。 */
    private fun toolsOf(
        server: McpServerConfig,
        probe: (McpServerConfig) -> McpProbeResult,
    ): List<McpToolInfo> {
        val now = System.currentTimeMillis()
        val hit = cache[server.url]
        if (hit != null && now - hit.fetchedAt < CACHE_TTL_MS) return hit.tools

        val result = probe(server)
        val tools = (result as? McpProbeResult.Ok)?.tools.orEmpty()
        cache[server.url] = CachedTools(tools, now)
        return tools
    }

    /** 清空缓存（用户在设置页改动 MCP 配置后必须调用）。 */
    fun invalidate() = cache.clear()

    /**
     * 把一个 MCP 工具变成我们的 [ToolDefinition]。
     *
     * ★ 关键点全在注释里，每一条都是踩过或差点踩的坑。
     */
    private fun toDefinition(server: McpServerConfig, info: McpToolInfo): ToolDefinition {
        val fullName = McpConfigCodec.toolName(server.prefix.ifBlank {
            McpConfigCodec.prefixFrom(server.name)
        }, info.name)

        // ★★★ 一句话说明：把服务端的描述压到 90 字符以内。
        //   目录里每个工具只占一行，超长会吃掉懒加载省下的全部收益。
        val oneLiner = info.description
            .replace('\n', ' ')
            .trim()
            .take(90)
            .ifEmpty { "${server.name} 提供的工具" }

        return ToolDefinition(
            name = fullName,
            displayName = info.name,
            summary = oneLiner,
            kind = ToolKind.EXTERNAL,
            enabledByDefault = true,
            // ★★ MCP 工具的副作用**未知** —— 服务端声明的 annotations 规范明确说
            //   「客户端 MUST 视为不可信」。所以不主动声明要确认；
            //   要不要问由权限档位决定（那才是 App 的判据）。
            requiresConfirmation = false,
            // ★★★ 一律按需。一个 MCP 可能带来上百个工具，默认常驻会瞬间吃光上下文。
            preferredMode = ToolMode.Lazy,
            group = chatmobile.tools.ToolGroup.MCP,
            oneLiner = oneLiner,
            spec = ToolSpec(
                name = fullName,
                description = buildString {
                    append("MCP tool '").append(info.name).append("' from server '")
                    append(server.name).append("'.")
                    if (info.description.isNotBlank()) {
                        append(' ').append(info.description)
                    }
                },
                // ★ 结构化那份是空的 —— 真正的 schema 走 rawParametersJson 原样透传。
                parameters = ToolParameters(emptyMap()),
                rawParametersJson = info.inputSchemaJson,
            ),
        )
    }

    /**
     * 执行一个 MCP 工具调用。
     *
     * @param fullName 带前缀的完整工具名
     * @param servers 当前配置（用来反查是哪个服务器、原名是什么）
     * @return 给模型读的文本；**永不抛异常**
     */
    fun call(
        fullName: String,
        argumentsJson: String,
        servers: List<McpServerConfig>,
    ): String {
        // ★ 反查：谁的前缀能把自己的工具名映射成 fullName。
        //   为什么反查而不是把 server+原名塞进 Map：缓存会失效、配置会改，
        //   而 fullName 是唯一稳定的东西（它就在工具表里）。
        for (server in servers) {
            if (!server.enabled) continue
            val prefix = server.prefix.ifBlank { McpConfigCodec.prefixFrom(server.name) }
            if (!fullName.startsWith("${prefix}__")) continue

            val original = fullName.removePrefix("${prefix}__")
            return when (val r = callClient.call(server, original, argumentsJson)) {
                is McpCallResult.Ok -> r.text
                is McpCallResult.Failed ->
                    "ERROR: ${r.reason}。${r.hint}"
            }
        }
        return "ERROR: 找不到工具 '$fullName' 属于哪个 MCP 服务器 —— " +
            "配置可能刚被改过。告诉用户这个工具暂时不可用，不要重试。"
    }

    /** 这个工具名是不是当前配置里某个 MCP 服务器的。 */
    fun owns(fullName: String, servers: List<McpServerConfig>): Boolean {
        for (server in servers) {
            if (!server.enabled) continue
            val prefix = server.prefix.ifBlank { McpConfigCodec.prefixFrom(server.name) }
            if (fullName.startsWith("${prefix}__")) return true
        }
        return false
    }

    companion object {
        /**
         * 工具清单缓存多久。
         *
         * ★ 5 分钟：短到"服务端加了工具"能较快反映，长到一轮多轮对话不会反复探测。
         *   规范说客户端可自行决定；这里选一个不会让用户察觉的值。
         */
        const val CACHE_TTL_MS: Long = 5 * 60 * 1000L
    }
}
