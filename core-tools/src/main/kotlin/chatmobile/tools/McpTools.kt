package chatmobile.tools

/**
 * **MCP 配置工具** —— 让模型能帮用户配置 MCP 服务器。
 *
 * ## ★★★ 用户原话与这里的设计
 *
 * > 「顺便再加一个功能，模型可以帮用户配置 mcp（这个困难吗？）」
 * > 「可以一起做完」
 *
 * 困难的不是代码，是**安全边界**。最容易做错的做法：让模型调一个
 * `add_mcp_server(url)` 然后直接写进配置 —— 那等于把"你的聊天 App 曾向哪些地址发过请求"
 * 的控制权交给模型。一旦它被历史消息、网页内容或某个工具返回值诱导，
 * 用户完全没有察觉的机会。
 *
 * ⇒ 拆成**四段**，每一段都不可合并：
 *
 * | 阶段 | 谁做 | 会改变什么 |
 * |---|---|---|
 * | ① `mcp_probe` | 模型调用，**纯只读** | 什么都不变，只是拿到"那地址上有什么" |
 * | ② 模型根据探测结果**起草** | 模型 | 什么都不变 |
 * | ③ `mcp_save_draft` | 模型调用 | **弹确认框**（用户第一次有机会拒绝） |
 * | ④ 用户点「添加」 | **人** | 才真的落库 |
 *
 * ## ★★ 为什么 `mcp_probe` 必须是纯只读
 *
 * 探测会向**模型指定的任意地址**发一次网络请求。这本身就是一次信息泄露
 * （对方知道这个 IP 在用什么 App）。所以：
 * - 它不写任何状态；失败也只是"读不到"
 * - 地址必须过 [McpUrlGuard] 的校验（拒内网/环回/明文 http）
 *
 * ## ★ 为什么参数里**没有** serverName
 *
 * 服务器名必须来自**探测响应**（`serverInfo.name`），不能让模型填 ——
 * 否则模型可以给一个恶意地址起名叫 "GitHub 官方"，用户在确认框里看到的是假的。
 * ⇒ `mcp_save_draft` 只收 `url`，服务器名由宿主从**自己的探测缓存**里取。
 */
object McpTools {

    const val PROBE_NAME = "mcp_probe"
    const val DRAFT_NAME = "mcp_save_draft"

    /** 探测一个 MCP 地址上有什么工具（**纯只读**）。 */
    val probe = ToolDefinition(
        name = PROBE_NAME,
        displayName = "探测 MCP 服务器",
        summary = "连接一个 MCP 地址，看它暴露了哪些工具（只读，不保存任何东西）",
        kind = ToolKind.EXTERNAL,
        enabledByDefault = true,
        requiresConfirmation = false,
        // ★ 常驻：它是"配置 MCP"这条链的入口。若它本身还要先 describe，
        //   模型在第一跳就会卡住（"我怎么知道怎么探测"）。
        preferredMode = ToolMode.Resident,
        group = ToolGroup.SETUP,
        oneLiner = "Inspect what tools an MCP server exposes (read-only).",
        spec = ToolSpec(
            name = PROBE_NAME,
            description = "Connect to an MCP server and list the tools it exposes. " +
                "This is READ-ONLY: nothing is saved, no configuration changes. " +
                "Use this first whenever the user gives you an MCP endpoint and wants it added — " +
                "you must call this before mcp_save_draft, because the draft is rejected if " +
                "the address was never probed. " +
                "The URL must be a public https endpoint; private/local addresses are refused.",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ParamSpec(
                        ParamType.STRING,
                        "The MCP endpoint URL, e.g. https://example.com/mcp",
                    ),
                ),
                required = listOf("url"),
            ),
            examples = listOf(
                ToolExample(
                    whenTo = "user pasted an MCP endpoint and wants to add it",
                    args = """{"url":"https://example.com/mcp"}""",
                ),
            ),
        ),
    )

    /**
     * 起草添加请求 —— **只起草，不保存**。
     *
     * ★ 参数只有 `url`（+可选 `alias`）。服务器名必须来自探测，不能让模型编。
     */
    val saveDraft = ToolDefinition(
        name = DRAFT_NAME,
        displayName = "起草添加 MCP",
        summary = "把探测过的 MCP 服务器做成一张待确认卡片（用户点添加才真的保存）",
        kind = ToolKind.EXTERNAL,
        enabledByDefault = true,
        // ★ 它本身不改变状态（只是弹卡），但**用户必须点确认**才真的保存。
        //   这个确认不在 requiresConfirmation 这条路上，而是走 MCP 自己的确认卡 ——
        //   因为那张卡要展示地址（脱敏）+ 工具数，比通用的"是否允许"信息量大得多。
        requiresConfirmation = false,
        preferredMode = ToolMode.Resident,
        group = ToolGroup.SETUP,
        oneLiner = "Draft adding a probed MCP server (user must confirm).",
        spec = ToolSpec(
            name = DRAFT_NAME,
            description = "Draft adding a previously probed MCP server. This does NOT save " +
                "anything: it shows the user a confirmation card with the redacted address and " +
                "the tool count, and only if they tap Add is it stored. " +
                "You MUST call mcp_probe on the same URL first — otherwise this is rejected. " +
                "Never claim the server was added; wait for the user to confirm.",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ParamSpec(
                        ParamType.STRING,
                        "The same URL you already probed with mcp_probe.",
                    ),
                ),
                required = listOf("url"),
            ),
            examples = listOf(
                ToolExample(
                    whenTo = "after mcp_probe succeeded and the user wants it added",
                    args = """{"url":"https://example.com/mcp"}""",
                ),
            ),
        ),
    )

    /** 两个元工具（顺序固定，便于序列化稳定）。 */
    fun all(): List<ToolDefinition> = listOf(probe, saveDraft)

    fun byName(name: String): ToolDefinition? = all().firstOrNull { it.name == name }
}
