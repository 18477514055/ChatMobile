package com.chatmobile.mcp

/**
 * 模型起草的 MCP 配置 —— **待用户确认才能保存**。
 *
 * ## ★★★ 这条流程是整个功能的骨架
 *
 * 用户要求：「模型可以帮用户配置 mcp」。
 *
 * 最容易做错的做法：让模型调一个 `add_mcp_server(url)` 然后**直接写进配置**。
 * 那等于把"你的聊天 App 曾向哪些地址发过请求"的控制权交给模型 ——
 * 一旦它被历史消息、网页内容或某个工具返回值诱导，用户完全没有察觉的机会。
 *
 * ⇒ 采用**起草 → 确认 → 保存**三段式：
 *
 * ```
 * 用户: "帮我把这个 MCP 加上 https://example.com/mcp"
 *   模型: 调用 mcp_probe(url)              ← 只读，不落库
 *   宿主: 返回服务器名 + 工具清单
 *   模型: 调 mcp_save_draft(url, name)     ← 仍然只**起草**
 *   宿主: 弹确认框，列地址（脱敏）+ 工具数 ← **用户点确认才真的存**
 *   宿主: 保存并刷新工具表
 * ```
 *
 * ## ★ 为什么"起草"和"保存"必须是两个工具
 *
 * 不是洁癖：`mcp_probe` 是**纯读**（不改变任何状态），`mcp_save_draft` 产生一个
 * **可见待办**（UI 上出现一张确认卡）。若合成一个工具，模型一次调用就把事情办了，
 * 用户没有拒绝的时机。
 */
data class McpDraft(
    /** 服务端给的显示名（探测得到，模型不能自造）。 */
    val serverName: String,
    /** 规范化后的地址（已经过 [McpUrlGuard]）。 */
    val url: String,
    /** 服务端版本，可为空。 */
    val serverVersion: String? = null,
    /** 探测到的工具数 —— 确认框上要显示，让用户知道这东西有多大。 */
    val toolCount: Int,
    /** 传输方式（sse / streamable-http）。 */
    val transport: String,
)

/**
 * 起草的合法性判据 —— 纯逻辑，可完整单测。
 *
 * ★ 这些校验**不信任模型给的一切**：
 *   `serverName` 可能是模型瞎编的、`url` 可能没经过探测就直接报上来。
 */
object McpDraftPolicy {

    /** 校验结果。 */
    sealed interface Verdict {
        data class Ok(val draft: McpDraft) : Verdict
        data class Rejected(val reason: String, val hint: String) : Verdict
    }

    /**
     * 校验一次起草请求。
     *
     * @param probed 是否**真的探测过**这个地址（宿主记录，不是模型说了算）
     * @param toolCount 探测到的工具数（来自宿主，不是模型给的）
     */
    fun check(
        serverName: String,
        url: String,
        serverVersion: String?,
        probed: Boolean,
        toolCount: Int,
        transport: String,
        existingUrls: Set<String>,
        allowPrivate: Boolean = false,
    ): Verdict {
        val verdict = McpUrlGuard.check(url, allowPrivate)
        val normalized = when (verdict) {
            is McpUrlGuard.Verdict.Rejected -> return Verdict.Rejected(
                verdict.reason,
                "地址不合规。请让用户提供一个公网的 https MCP 端点。",
            )
            is McpUrlGuard.Verdict.Ok -> verdict.normalized
        }

        // ★★ 没探测过就不许起草。
        //   模型完全可能跳过 probe 直接编一个"服务器名 + 工具数"——
        //   而那会是纯虚构的配置，用户在确认框里看到的全是假的。
        if (!probed) {
            return Verdict.Rejected(
                "这个地址还没有探测过",
                "请先调用 mcp_probe 确认它真的能连上、真的暴露了工具，再来起草。",
            )
        }

        if (toolCount <= 0) {
            return Verdict.Rejected(
                "这个服务器没有暴露任何工具",
                "一个没有工具的 MCP 加进来只是凭空多一次网络请求，建议跳过。",
            )
        }

        if (normalized in existingUrls) {
            return Verdict.Rejected(
                "这个地址已经配置过了",
                "不需要重复添加。如果想改它的模式，去工具设置页把它的模式从" +
                    "「按需」改成「常驻」。",
            )
        }

        val name = serverName.trim().ifEmpty { "MCP 服务器" }

        return Verdict.Ok(
            McpDraft(
                serverName = name,
                url = normalized,
                serverVersion = serverVersion,
                toolCount = toolCount,
                transport = transport,
            ),
        )
    }

    /**
     * 确认框上给用户看的那段话。
     *
     * ★ 三件事缺一不可（这是用户做决定的全部依据）：
     *   ① **地址**（脱敏后）—— 他要能核对连的是不是他想连的地方
     *   ② **会带来多少工具** —— 决定他该设常驻还是按需
     *   ③ **这是模型提议的** —— 让他知道"不是我要连，是模型建议的"
     */
    fun confirmMessage(draft: McpDraft): String = buildString {
        append("模型想添加一个 MCP 服务器：\n\n")
        append("名称：").append(draft.serverName).append('\n')
        append("地址：").append(McpUrlGuard.redactForDisplay(draft.url)).append('\n')
        append("工具数：").append(draft.toolCount).append(" 个\n")
        append("传输：").append(draft.transport)
        append("\n\n添加后这些工具会出现在工具设置页，默认是「按需」——")
        append("不占常驻上下文，模型要用时才查。\n")
        append("只有你点「添加」，它才会真的保存。")
    }

    /**
     * 新加进来的 MCP 工具默认是什么模式。
     *
     * ★ **一律默认按需（懒加载）**。理由：
     *   一个 MCP 可能带来几十上百个工具，默认常驻会瞬间把上下文吃光 ——
     *   而用户刚加进来时还不知道哪些有用。他可以在设置页把常用的改成常驻。
     */
    val defaultModeForMcp: String = "Lazy"
}
