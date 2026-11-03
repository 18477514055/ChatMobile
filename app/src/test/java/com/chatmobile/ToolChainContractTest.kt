package com.chatmobile

import chatmobile.tools.ChatTools
import chatmobile.tools.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **工具链路的契约测试**。
 *
 * ## ★★★ 这批测试防的是什么
 *
 * 接工具链路要同时改四个地方：请求带 `tools`、解析 `tool_calls`、回填 `role:"tool"`、
 * 执行器按名分发。**漏掉任何一处，表现都是"模型说要用工具，然后什么都没发生"**
 * —— 编译能过、请求也能过，只是链路断了。
 *
 * 这正是本项目历史上最贵的一类 bug（「机制都在，唯独连接不存在」）。
 * 所以每一环都用源码扫描钉住。
 */
class ToolChainContractTest {

    private val chatHost: String by lazy {
        val f = File("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        stripComments(f.readText())
    }

    private val requestBuilder: String by lazy {
        File("src/main/java/com/chatmobile/net/OpenAiRequestBuilder.kt").readText()
    }

    /** 见 ChatHostContractTest 的同名函数 —— 剥注释再断言，否则注释里的引用会让测试红。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> { if (c == '\n') { inLine = false; out.append(c) } }
                inBlock -> { if (c == '*' && next == '/') { inBlock = false; i++ } }
                inString -> {
                    out.append(c)
                    if (c == '\\') { if (i + 1 < src.length) { out.append(next); i++ } }
                    else if (c == '"') inString = false
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> inBlock = true
                c == '"' -> { inString = true; out.append(c) }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun extractFunctionFrom(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        if (start < 0) return ""
        var i = src.indexOf('{', start)
        if (i < 0) return ""
        val bodyStart = i
        var depth = 0
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        return ""
    }

    // ================================================================
    // ① 请求必须带 tools
    // ================================================================

    @Test
    fun `请求必须把工具表传给 ChatRequest`() {
        assertTrue(
            "ChatRequest(...) 里必须传 tools = toolsJson —— 不传的话模型根本不知道有工具",
            chatHost.contains("tools = toolsJson"),
        )
        assertTrue(
            "必须按用户启用的工具名过滤后再序列化",
            chatHost.contains("settings.enabledToolNames()"),
        )
    }

    @Test
    fun `工具表必须在循环外算一次`() {
        // ★ 在工具循环内重建工具表，会导致每轮的 `tools` 字段重新序列化。
        //   而 Anthropic 的缓存顺序是 tools → system → messages ⇒ 工具表是**最外层前缀**，
        //   它一变后面全部重算。所以必须循环外一次、循环内复用。
        val enabledIdx = chatHost.indexOf("val enabledTools = settings.enabledToolNames()")
        val loopIdx = chatHost.indexOf("while (true)")
        assertTrue("找不到工具表构建", enabledIdx >= 0)
        assertTrue("找不到工具循环", loopIdx >= 0)
        assertTrue(
            "工具表必须在 while 循环**之前**构建（循环内重建会让每轮缓存前缀失效）",
            enabledIdx < loopIdx,
        )
    }

    @Test
    fun `空工具表不发 tools 字段`() {
        assertTrue(
            "启用工具为空时必须传 null，而不是空数组（有些网关对空数组 400）",
            requestBuilder.contains("takeIf { it.isNotBlank() }"),
        )
        // 解析失败要静默降级，而不是发坏数据
        assertTrue(
            "tools 解析失败必须降级为不传，不能把坏 JSON 发出去",
            requestBuilder.contains("parseToolsOrNull"),
        )
    }

    // ================================================================
    // ② 解析 tool_calls
    // ================================================================

    @Test
    fun `解析器必须按 index 累积分片`() {
        val f = File("src/main/java/com/chatmobile/net/OpenAiStreamParser.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())

        assertTrue(
            "必须按 index 累积（getOrPut(idx)）—— 每帧当独立调用会得到一堆参数残缺的调用",
            src.contains("partialCalls.getOrPut(idx)"),
        )
        assertTrue(
            "必须在收尾时统一 flush",
            src.contains("flushToolCalls()"),
        )
        // 累加器必须清空，否则同一个 parser 复用时会重复执行上一轮的工具
        assertTrue("累加器必须清空", src.contains("partialCalls.clear()"))
    }

    // ================================================================
    // ③ 回填 role:"tool"
    // ================================================================

    @Test
    fun `工具结果必须以 role tool 回填`() {
        assertTrue(
            "必须构造 role=\"tool\" 消息回填 —— 否则模型看不到工具结果",
            chatHost.contains("NetChatMessage.toolResult("),
        )
        assertTrue(
            "必须把「要调工具」的 assistant 消息也写进历史 —— 否则下一轮模型对不上账",
            chatHost.contains("NetChatMessage.assistantToolCalls("),
        )
    }

    @Test
    fun `请求体必须序列化 tool_calls 与 tool_call_id`() {
        assertTrue(
            "assistant 的 tool_calls 必须发回去",
            requestBuilder.contains("\"tool_calls\""),
        )
        assertTrue(
            "tool 消息必须带 tool_call_id",
            requestBuilder.contains("\"tool_call_id\""),
        )
        assertTrue(
            "arguments 必须是**字符串**不是对象（OpenAI 协议）",
            requestBuilder.contains("put(\"arguments\", c.argumentsJson)"),
        )
    }

    // ================================================================
    // ④ 执行器按名分发 + 用户关掉必须真生效
    // ================================================================

    @Test
    fun `关掉的工具必须被拒绝`() {
        val f = File("src/main/java/com/chatmobile/tools/../../../../core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt")
        // 路径在不同 cwd 下可能不同，直接按已知相对路径找
        val candidates = listOf(
            File("src/main/java/com/chatmobile/tools/ChatToolExecutor.kt"),
            File("../core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
        )
        val real = candidates.firstOrNull { it.exists() }
        assertNotNull("找不到 ChatToolExecutor.kt", real)
        val src = real!!.readText()
        assertTrue(
            "必须检查 enabled 集合 —— 否则用户关掉的工具模型仍能调用，设置页就成了摆设",
            src.contains("call.toolName !in enabled"),
        )
    }

    @Test
    fun `工具循环必须有轮数上限`() {
        assertTrue(
            "必须有轮数上限 —— 否则模型反复调同一个工具会变成死循环，把 token 烧光",
            chatHost.contains("MAX_TOOL_ROUNDS"),
        )
        assertTrue(
            "超过上限必须给出可见说明，而不是静默截断",
            chatHost.contains("连续工具调用已达上限"),
        )
    }

    // ================================================================
    // ⑤ 工具表本身的结构纪律
    // ================================================================

    @Test
    fun `工具顺序冻结：只能追加在末尾`() {
        // ★ 这条不是形式主义：工具表排在 system 之前，重排 = 全体用户一次全量重缓存。
        val names = ChatTools.all().map { it.name }
        assertEquals(
            "工具顺序被改动了。新增工具请**追加在末尾**，" +
                "在中间插入会让它后面每个工具的 schema 位置平移 ⇒ 全体用户缓存失效。",
            listOf("get_time", "get_location", "web_fetch", "save_memory", "search_memory"),
            names,
        )
    }

    @Test
    fun `每个工具的 schema 名字必须与目录名一致`() {
        for (def in ChatTools.all()) {
            assertEquals(
                "ToolDefinition.name 与 spec.name 必须相同，否则执行器按名字分发会找错",
                def.name,
                def.spec.name,
            )
        }
    }

    @Test
    fun `不做 shell 与任意文件遍历`() {
        val names = ChatTools.all().map { it.name }
        for (banned in listOf("run_command", "read_file", "list_dir", "shell", "exec")) {
            assertFalse(
                "手机聊天客户端不该提供 $banned —— 走不通（App 私有目录没有用户文件），" +
                    "而且是隐私雷区",
                names.contains(banned),
            )
        }
    }

    @Test
    fun `只读工具默认开启，写入与联网工具也要有明确分类`() {
        for (def in ChatTools.all()) {
            if (def.kind == ToolKind.READ_ONLY) {
                assertTrue(
                    "${def.name} 是只读工具，默认应当开启",
                    def.enabledByDefault,
                )
            }
        }
    }

    @Test
    fun `工具 schema 必须拒绝未声明参数`() {
        val f = File("../core-tools/src/main/kotlin/chatmobile/tools/ToolRegistry.kt")
        val alt = File("core-tools/src/main/kotlin/chatmobile/tools/ToolRegistry.kt")
        val real = listOf(f, alt).firstOrNull { it.exists() }
        assertNotNull("找不到 ToolRegistry.kt", real)
        assertTrue(
            "必须写 additionalProperties:false —— 少写它模型会塞未知参数，严格的网关会 400",
            real!!.readText().contains("\"additionalProperties\":false"),
        )
    }
}
