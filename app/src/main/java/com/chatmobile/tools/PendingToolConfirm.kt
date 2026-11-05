package com.chatmobile.tools

import kotlinx.coroutines.CompletableDeferred

/**
 * 一次「等用户点允许/拒绝」的工具调用。
 *
 * ## ★★★ 为什么需要这个类型（而不是直接在 ChatHost 里放个 Boolean）
 *
 * `chatmobile.tools.ChatTools` 里 `requiresConfirmation = true` 存在已久，
 * 但**从来没有任何代码读过它** —— 一张空头支票。真正接上它需要三件事同时成立：
 *
 * 1. 执行器知道"这一步要问人"（→ `ChatToolExecutor.execute` 里查表）；
 * 2. 界面能显示一个说人话的确认框（→ 本类带着**已经拼好的**标题与正文）；
 * 3. 执行线程能**停下来等**用户点（→ [decision] 是个 CompletableDeferred）。
 *
 * 把这三件事塞进一个不可变对象，是为了让「问什么」与「怎么显示」解耦：
 * UI 不需要知道是哪个工具、参数长什么样，它只渲染 [title]/[detail]。
 *
 * ## ★ 超时必须是「拒绝」
 *
 * 用户没看屏幕时如果默认放行，确认框就等于"3 秒后自动执行"，
 * 那比没有确认框更糟 —— 用户会以为有保护。⇒ 见 ChatHost 的 `awaitToolConfirm`。
 */
data class PendingToolConfirm(
    /** 工具名（诊断用，也用于日志）。 */
    val toolName: String,
    /** 一句话标题，例如「模型想写入文件」。 */
    val title: String,
    /** 明细（目标路径、大小等），多行。 */
    val detail: String,
    /** 危险操作（覆盖、删除）用红色确认按钮。 */
    val danger: Boolean,
    /** 用户的选择；执行方 `await()` 它。 */
    val decision: CompletableDeferred<Boolean>,
) {
    companion object {
        /**
         * 按工具名 + 参数拼出要显示给用户的那一段。
         *
         * ★ 这里**替用户把关键信息提炼出来**（写哪个文件、多少字符、是不是覆盖），
         *   而不是把原始 JSON 甩给用户 —— 用户看 `{"path":"/a/b.md"}` 无法判断风险。
         */
        fun build(
            toolName: String,
            argumentsJson: String,
            targetPath: String?,
            willOverwrite: Boolean?,
            contentChars: Int?,
        ): PendingToolConfirm {
            val title = when (toolName) {
                "write_file" -> if (willOverwrite == true) "模型想覆盖一个文件" else "模型想写入文件"
                else -> "模型想执行「$toolName」"
            }
            val detail = buildString {
                if (targetPath != null) {
                    append("目标：").append(targetPath)
                } else {
                    append("参数：").append(argumentsJson.take(300))
                }
                if (contentChars != null) {
                    append("\n内容：").append(contentChars).append(" 字符")
                }
                if (willOverwrite == true) {
                    append("\n\n⚠️ 该文件已存在，允许后会**覆盖**原有内容。")
                }
                append("\n\n只对本次生效；下次调用会再问一次。")
            }
            return PendingToolConfirm(
                toolName = toolName,
                title = title,
                detail = detail,
                // 覆盖是破坏性的，用红色按钮；新建只是多一个文件。
                danger = willOverwrite == true,
                decision = CompletableDeferred(),
            )
        }

        /**
         * **MCP 添加确认** —— 与文件那条路分开，因为要显示的东西完全不同。
         *
         * ## ★★★ 为什么不复用 [build]
         *
         * [build] 的语义是"模型要写某个文件"，它把 targetPath / 覆盖与否 / 字符数
         * 提炼成三行。而 MCP 的确认框要让用户看到的是另外三样：
         *
         * | 要显示 | 为什么是决定性的 |
         * |---|---|
         * | **地址**（脱敏后） | 他要能核对是不是他想连的地方 —— 这是唯一的信息泄漏点 |
         * | **工具数** | 决定他该给这个服务器多少信任 |
         * | **"这是模型提议的"** | 让他知道不是系统自动连的，是模型建议的 |
         *
         * 硬塞进 [build] 会让两边都变差：那个函数会长出一堆 null 参数，
         * 而这里真正需要的信息却表达不出来。
         *
         * @param detail 由 `McpDraftPolicy.confirmMessage()` 拼好（那段话有单测钉住）
         */
        fun forMcpDraft(detail: String): PendingToolConfirm = PendingToolConfirm(
            toolName = "mcp_save_draft",
            title = "模型想添加一个 MCP 服务器",
            detail = detail,
            // 不是破坏性操作（它只增不减），但用普通按钮会让人以为无害地自动过去了。
            // 这里给 danger = false：确实是可撤销的（设置页能删）。
            danger = false,
            decision = CompletableDeferred(),
        )
    }
}
