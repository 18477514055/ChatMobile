package com.chatmobile.mcp

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **MCP 接线契约** —— 防止「机制都在，唯独连接不存在」。
 *
 * ## 这个文件为什么必须存在
 *
 * 前四轮里出现过好几次同一类错误：组件写完了、单测全绿、**却没有一个调用点**。
 * 那种状态下所有测试都会通过，因为测试直接调组件；而真实用户永远碰不到它。
 *
 * ⇒ 这个文件检查的不是"组件对不对"，而是**调用点存不存在**：
 * 源码里有没有真的把 A 接到 B 上。
 *
 * ★ 这类测试读源码字符串，看起来"脏"，但它挡住的正是本类项目最贵的错误。
 *   （`ToolChainContractTest` 里已经有同一个套路的成功先例。）
 */
class McpWiringContractTest {

    private fun src(rel: String): File {
        val candidates = listOf(File("../$rel"), File(rel))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到源码：$rel（工作目录 ${File(".").absolutePath}）")
    }

    private fun hostText(): String = src("app/src/main/java/com/chatmobile/ChatHost.kt").readText()

    // ------------------------------------------------------------------
    // 一、工具表真的接上了 MCP
    // ------------------------------------------------------------------

    @Test
    fun `构建工具表时必须把 MCP 工具加进去`() {
        val t = hostText()
        // ★ 只看这一句：`allDefinitions` 必须包含 mcpDefinitions。
        //   少了它 ⇒ MCP 工具永远不会出现在工具表里（本轮最容易犯的错）。
        assertTrue(
            "工具表构建里没有把 mcpDefinitions 加进去 —— MCP 工具会「加进来却不存在」",
            t.contains("ChatTools.all() +") &&
                t.contains("McpTools.all()") &&
                t.contains("mcpDefinitions"),
        )
    }

    @Test
    fun `工具模式必须把 MCP 定义算进去`() {
        val t = hostText()
        // ★ 若 toolModeMap 不知道这些工具，它们的模式就取不到 ⇒
        //   既不在常驻也不在懒加载 ⇒ 表里没有它们。
        assertTrue(
            "toolModeMap 调用没有传 MCP 定义 —— MCP 工具会拿不到模式",
            Regex("""toolModeMap\([^)]*McpTools\.all\(\)[^)]*mcpDefinitions""").containsMatchIn(t),
        )
    }

    @Test
    fun `执行器必须拿到 MCP 的三个回调`() {
        val t = hostText()
        for (key in listOf("mcpIsTool", "mcpCall", "mcpDefinition", "mcpProbeServer", "mcpDraftServer")) {
            assertTrue("AndroidToolHost 构造里缺 $key —— 那条链路是断的", t.contains("$key ="))
        }
    }

    @Test
    fun `AndroidToolHost 必须真的实现五个 MCP 方法`() {
        val t = src("app/src/main/java/com/chatmobile/tools/AndroidToolHost.kt").readText()
        for (fn in listOf(
            "override fun isMcpTool(",
            "override fun callMcpTool(",
            "override fun dynamicToolDefinition(",
            "override fun probeMcpServer(",
            "override fun draftMcpServer(",
        )) {
            assertTrue("AndroidToolHost 没有 $fn —— 接口方法没实现，链路是断的", t.contains(fn))
        }
    }

    // ------------------------------------------------------------------
    // 二、配置真的存得下、读得回
    // ------------------------------------------------------------------

    @Test
    fun `设置存储必须真的读写 MCP 配置`() {
        val t = src("app/src/main/java/com/chatmobile/settings/SettingsStore.kt").readText()
        assertTrue("没有 MCP 的存储键", t.contains("MCP_SERVERS"))
        assertTrue("没有 mcpServers() 读取", t.contains("suspend fun mcpServers()"))
        assertTrue("没有 setMcpServers() 写入", t.contains("suspend fun setMcpServers("))
        // ★ 写入端与读取端必须用**同一个键**（L26 的形状：两处各写一份就不一致）
        assertTrue(
            "读写没有都走 Keys.MCP_SERVERS",
            Regex("""prefs\[Keys\.MCP_SERVERS\]""").findAll(t).count() >= 2,
        )
    }

    @Test
    fun `设置页真的显示 MCP 服务器并能改模式`() {
        val t = src("app/src/main/java/com/chatmobile/settings/ToolsScreen.kt").readText()
        assertTrue("设置页没有 MCP 区块", t.contains("McpSection("))
        assertTrue("设置页没接服务器列表", t.contains("mcpServers"))
        assertTrue("设置页没接工具定义", t.contains("mcpDefinitions"))
        // ★ 没有删除/启停入口 = 用户配好之后再也管不了它
        assertTrue("没有启停回调", t.contains("onToggleMcp"))
        assertTrue("没有删除回调", t.contains("onRemoveMcp"))
    }

    @Test
    fun `ChatHost 必须把 MCP 状态传给设置页`() {
        val t = hostText()
        assertTrue("没把 mcpServers 传给设置页", t.contains("mcpServers = mcpServersForUi"))
        assertTrue("没把 mcpDefinitions 传给设置页", t.contains("mcpDefinitions = mcpDefsForUi"))
        assertTrue("没接启停", t.contains("onToggleMcp ="))
        assertTrue("没接删除", t.contains("onRemoveMcp ="))
    }

    // ------------------------------------------------------------------
    // 三、四段式安全边界不能塌
    // ------------------------------------------------------------------

    @Test
    fun `起草前必须核对真的探测过`() {
        val t = hostText()
        // ★★★ 这是整个 MCP 功能最关键的一行。
        //   少了它，模型可以跳过 probe 直接起草一个"服务器名 + 工具数"全是编的配置，
        //   而用户在确认框里看到的**全是假的** —— 那是他唯一做决定的依据。
        assertTrue(
            "draftMcp 没有核对 mcpProbedByUrl —— 模型可以编造探测结果",
            t.contains("mcpProbedByUrl[rawUrl]"),
        )
    }

    @Test
    fun `保存必须经过用户确认`() {
        val t = hostText()
        val draftFn = t.substringAfter("suspend fun draftMcp(", "")
        assertTrue(
            "draftMcp 没有调用 awaitToolConfirm —— 那等于模型一调用就保存了",
            draftFn.contains("awaitToolConfirm"),
        )
        // ★ 拒绝的分支必须存在：返回"用户没添加"而不是继续保存
        assertTrue(
            "没有处理用户拒绝的分支",
            draftFn.contains("if (!added)"),
        )
    }

    @Test
    fun `探测必须记进宿主侧 而不是信模型`() {
        val t = hostText()
        // ★ probeMcp 成功时必须写进 mcpProbedByUrl（那是 draftMcp 的判据来源）
        assertTrue(
            "probeMcp 没有记录探测结果 —— draftMcp 就无从核对",
            t.contains("mcpProbedByUrl[rawUrl] = result"),
        )
    }

    @Test
    fun `删除或启停后必须清缓存`() {
        val t = hostText()
        // ★ 不清缓存 ⇒ 注册表里还留着旧工具，而 owns() 已经认不出它们
        //   ⇒ 变成"看得见、调不了"的僵尸工具。
        val count = Regex("""mcpRegistry\.invalidate\(\)""").findAll(t).count()
        assertTrue(
            "只在 $count 处清了 MCP 缓存 —— 删除/启停/探测后都必须清",
            count >= 3,
        )
    }

    // ------------------------------------------------------------------
    // 四、元工具真的注册了
    // ------------------------------------------------------------------

    @Test
    fun `两个 MCP 元工具在执行器里有分支`() {
        val t = src("core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt").readText()
        // ★ 没有分支 ⇒ 模型调 mcp_probe 会被当成"未知工具"，整条链在第一跳就死。
        assertTrue("执行器没有 mcp_probe 分支", t.contains("McpTools.PROBE_NAME"))
        assertTrue("执行器没有 mcp_save_draft 分支", t.contains("McpTools.DRAFT_NAME"))
        // ★ 必须在 enabled 检查之前 —— 它们是元工具，不属于用户可配置的工具集
        val probeIdx = t.indexOf("McpTools.PROBE_NAME")
        val enabledIdx = t.indexOf("call.toolName !in enabled")
        assertTrue(
            "MCP 元工具的分支在 enabled 检查之后 —— 它们会被当成已关闭的工具拒掉",
            probeIdx in 0 until enabledIdx,
        )
    }
}
