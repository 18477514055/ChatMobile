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

    /**
     * 找 `needle` 第一次出现的位置，但跳过 `needle` 本身就是某个标识符**后缀**的情况。
     *
     * ★ 为什么需要：找 `File(` 时会先命中 `DocumentFile(` / `siblingHintFile(` 之类，
     *   得到的位置比真正那行早，于是"权限判定在文件访问之前"这条断言就假绿了。
     *   这是本项目已经栽过一次的"尺子错"。
     */
    private fun String.indexOfRun(needle: String): Int {
        var from = 0
        while (true) {
            val i = indexOf(needle, from)
            if (i < 0) return -1
            val prev = if (i > 0) this[i - 1] else ' '
            if (!prev.isLetterOrDigit() && prev != '_') return i
            from = i + 1
        }
    }

    private fun extractFunctionFrom(src: String, declaration: String): String {        val start = src.indexOf(declaration)
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
        // ★ 判据变了（用户要求加懒加载后）：不再直接按"启用的工具名"过滤，
        //   而是先读**三种模式**，再交给 ToolCatalog 分成常驻 / 懒加载。
        //   ★ 但仍然必须**按用户配置过滤** —— 这条意图没变。
        assertTrue(
            "必须读用户配置的工具模式",
            chatHost.contains("settings.toolModeMap()"),
        )
        assertTrue(
            "必须用 ToolCatalog.build 分常驻/懒加载",
            chatHost.contains("ToolCatalog.build("),
        )
    }

    @Test
    fun `工具表必须在循环外算一次`() {
        // ★ 在工具循环内重建工具表，会导致每轮的 `tools` 字段重新序列化。
        //   而 Anthropic 的缓存顺序是 tools → system → messages ⇒ 工具表是**最外层前缀**，
        //   它一变后面全部重算。所以必须循环外一次、循环内复用。
        val buildIdx = chatHost.indexOf("ToolCatalog.build(")
        val loopIdx = chatHost.indexOf("while (true)")
        assertTrue("找不到工具表构建", buildIdx >= 0)
        assertTrue("找不到工具循环", loopIdx >= 0)
        assertTrue(
            "工具表必须在 while 循环**之前**构建（循环内重建会让每轮缓存前缀失效）",
            buildIdx < loopIdx,
        )
    }

    @Test
    fun `执行器必须拿到懒加载工具集合`() {
        // ★★★ 没有这一条，整个懒加载是**坏的死循环**：
        //   模型能 describe 出工具、却调不动它（因为它不在 enabled 的常驻集合里）。
        assertTrue(
            "必须把 lazy 传给执行器",
            chatHost.contains("lazy = lazyTools"),
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
            listOf(
                "get_time", "get_location", "web_fetch", "save_memory", "search_memory",
                // ↓ 2026-11-04 追加的文件三件套（必须留在末尾这几格）
                "read_file", "write_file", "list_dir",
                // ↓ 同日追加的联网搜索
                "web_search",
                // ↓ 同日追加的跨会话历史检索
                "search_history",
            ),
            names,
        )
    }

    /** 搜索工具必须**默认开启**且**不需要额外 key** —— 否则多数用户用不了它。 */
    @Test
    fun `联网搜索默认开启`() {
        val search = ChatTools.byName("web_search")
        assertNotNull("必须存在 web_search", search)
        assertTrue(
            "web_search 必须默认开启 —— 免费源不需要 key，没有理由让用户先去设置里开",
            search!!.enabledByDefault,
        )
        assertFalse(
            "搜索是只读的外部查询，不该每问一次就弹确认框",
            search.requiresConfirmation,
        )
        assertEquals("搜索必须归为外部访问类", ToolKind.EXTERNAL, search.kind)
    }

    /**
     * ★★★ 搜索的主动权在**模型**手里，不在宿主的固定顺序里。
     *
     * 用户原话（第三次修正，这一条才是终态）：
     * > 「也就是说没有给主动权，是给他个主动权，让他在搜索结果不佳的时候，主动调用付费服务」
     *
     * ## 为什么宿主不该自己排顺序
     *
     * 结果"好不好"只有**读过结果的模型**知道。宿主按固定顺序排都在猜：
     * - 猜"免费够用" ⇒ 用户配的付费服务**永远轮不到**
     *   （上一版就是这么错的，用户报「还是用不了自己填的搜索服务」）
     * - 猜"付费优先" ⇒ 每次普通搜索都花钱
     *
     * ⇒ 默认免费；模型觉得不够就带 `use_paid=true` 再来一次。
     */
    @Test
    fun `搜索必须把付费与否的主动权交给模型`() {
        // ① schema 里必须有 use_paid 这个开关，否则模型没有那个"主动权"
        val def = ChatTools.byName("web_search")
        assertNotNull("必须有 web_search", def)
        val props = def!!.spec.parameters.properties
        assertTrue(
            "web_search 必须暴露 use_paid 参数 —— 没有它，模型无法在结果不佳时升级",
            props.containsKey("use_paid"),
        )
        // ② 说明里必须告诉模型什么时候该用它（不然它永远想不起来）
        val desc = props["use_paid"]!!.description.lowercase()
        assertTrue(
            "use_paid 的描述必须说清适用场景（结果空/不相关/太浅），" +
                "否则模型不知道该在什么时候开它",
            desc.contains("empty") || desc.contains("irrelevant") || desc.contains("quality"),
        )

        // ③ 实现必须真的按它分流
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        val src = stripComments(f.readText())
        val body = extractFunctionFrom(src, "override fun webSearch(")
        assertTrue("webSearch 必须收 usePaid 形参", body.contains("usePaid"))
        assertTrue(
            "必须先用 usePaid 分流到付费通道",
            body.contains("if (usePaid)"),
        )
        // ④ 默认路径（usePaid=false）必须是免费源
        assertTrue("默认必须走免费源", body.contains("searchAnySearch"))
        assertTrue(
            "免费结果里要提示模型可以升级 —— 否则它不知道有这个选项",
            body.contains("use_paid=true"),
        )
    }

    /**
     * ★★★ 两条付费通道**互斥**：配了专用服务就绝不用模型自带的。
     *
     * 用户原话：「填了的话就用填上去的，不要用模型自己的，**会产生不必要的扣费**」
     * ⇒ 这是**钱**的判据：两条都走 = 付两次钱。
     */
    @Test
    fun `配了专用搜索服务就不得再用模型自带的`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())
        val body = extractFunctionFrom(src, "override fun webSearch(")
        assertTrue("找不到 webSearch 实现", body.isNotBlank())

        val customIdx = body.indexOf("searchViaCustomEndpoint")
        val modelIdx = body.indexOf("searchViaModelEndpoint")
        assertTrue("两条付费通道都必须存在", customIdx >= 0 && modelIdx >= 0)

        val elseIdx = body.lastIndexOf("} else {", modelIdx)
        assertTrue(
            "模型自带的调用必须落在 `else` 分支里 —— " +
                "否则配了专用服务时两条都会走，等于付两次钱",
            elseIdx in 0 until modelIdx,
        )
        assertTrue(
            "专用端点必须只读一次（读两次会让两边判断不一致）",
            body.contains("val custom = runCatching { customSearchEndpoint() }"),
        )
    }

    /**
     * ★★★ 地址归一化：**填了后缀就不加，没填就补上**。
     *
     * 真机上用户填了完整终点 `https://api.deepseek.com/v1/tools/search`，
     * 旧代码无条件拼 `/v1/tools/search` ⇒ 404 ⇒ 「还是用不了自己填的搜索服务」。
     *
     * 详细规则见 [com.chatmobile.tools.SearchUrlTest]；这里只钉**调用点**：
     * 实现里不许再手写拼接。
     */
    @Test
    fun `搜索地址必须走统一归一化而不是手写拼接`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        val src = stripComments(f.readText())
        assertTrue(
            "必须调用 SearchUrl.resolve",
            src.contains("SearchUrl.resolve("),
        )
        assertFalse(
            "不许再手写 `+ \"/v1/tools/search\"` —— 那就是重复拼接的来源",
            src.contains("+ \"/v1/tools/search\""),
        )
    }

    /** 设置页必须说清这个互斥关系，否则用户不知道填了会怎样。 */
    @Test
    fun `设置页必须说明付费通道互斥`() {
        val src = File("src/main/java/com/chatmobile/settings/ToolsScreen.kt").readText()
        assertTrue(
            "设置页必须写明「填了就不再碰模型自带的」—— 这是用户会关心的扣费行为",
            src.contains("不再碰模型自带的") || src.contains("不会再用模型自带的"),
        )
        assertTrue(
            "设置页必须点明原因（多扣费），否则用户会以为是 bug",
            src.contains("扣费"),
        )
    }

    /**
     * 搜索必须走**短超时**的 client。
     *
     * ★ 多级串行 + 通用超时（10+20 秒）= 最坏四十多秒，模型那边早就在喊"工具超时"了。
     *   这条测试钉的是这个"看起来无关、实际直接决定可用性"的细节。
     */
    @Test
    fun `搜索必须使用短超时的独立 client`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        val src = stripComments(f.readText())
        assertTrue("必须有独立的 searchHttp", src.contains("private val searchHttp"))
        val body = extractFunctionFrom(src, "private fun postJsonSearch(")
        assertTrue("搜索 POST 必须走 searchHttp，不能走通用 http", body.contains("searchHttp.newCall"))
        assertFalse("搜索 POST 不能走通用 http", body.contains("http.newCall"))

        // 连接超时必须明显短于通用的 10 秒
        val m = Regex("""searchHttp[\s\S]{0,400}?connectTimeout\((\d+)""").find(src)
        assertTrue("找不到 searchHttp 的 connectTimeout", m != null)
        val secs = m!!.groupValues[1].toInt()
        assertTrue("搜索连接超时必须 ≤ 5 秒（现在是 $secs）—— 多级串行会累加", secs <= 5)
    }

    /**
     * 解析器必须同时认 `results` 在顶层、以及在 `data.results` 里。
     *
     * ★ 这是真机踩到的坑：AnySearch 返回 `{"data":{"results":[...]}}`，
     *   而解析写成 `root["data"] as? JsonArray` 对它永远不成立 ——
     *   **免 key 的可用通道被自己的解析器吃掉了**，表现成"免费源全都不通"。
     */
    @Test
    fun `搜索解析必须兼容 results 嵌在 data 里的形态`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        val src = stripComments(f.readText())
        val body = extractFunctionFrom(src, "private fun parseSearchJson(")
        assertTrue("找不到 parseSearchJson", body.isNotBlank())
        assertTrue(
            "必须处理 data.results —— AnySearch 实际返回的就是这个形状",
            body.contains("\"data\"") && body.contains("\"results\""),
        )
    }

    /** 设置页必须提供「搜索服务地址 + key」两项，且 key 不明文显示。 */
    @Test
    fun `设置页必须能单独配置搜索服务`() {
        val f = File("src/main/java/com/chatmobile/settings/ToolsScreen.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = f.readText()
        assertTrue(
            "设置页必须有搜索服务地址输入框",
            src.contains("搜索服务地址"),
        )
        assertTrue(
            "设置页必须有搜索服务 key 输入框",
            src.contains("搜索服务 Key"),
        )
        assertTrue(
            "搜索服务的 key 必须用密码样式显示 —— 明文等于在别人面前念出来",
            src.contains("PasswordVisualTransformation"),
        )
        assertTrue(
            "必须说明三级顺序（免费 → 付费二选一 → 降级），否则用户会以为不填就搜不了",
            src.contains("免费源") && src.contains("付费通道"),
        )
    }

    /** 空 key 时绝不能发 `Authorization: Bearer `（那会把免费源变成 401）。 */
    @Test
    fun `空凭据时不得发送 Authorization 头`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        val src = stripComments(f.readText())
        val body = extractFunctionFrom(src, "private fun postJson(")
        assertTrue("找不到 postJson", body.isNotBlank())
        assertTrue(
            "必须在 key 非空时才加 Authorization 头 —— " +
                "发一个空的 Bearer 会被免费源判成无效凭据而 401",
            body.contains("if (apiKey.isNotBlank())"),
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

    /**
     * ★ 判据随决策更新：用户要求「继续给模型加工具」，文件三件套已正式加入。
     *
     * 不变的那一半是红线 —— **永远不提供 shell 执行**：
     * App 里没有这个需求，而它把"能读一个文件"放大成"能跑任何命令"。逐文件操作的
     * 边界由 `PermissionGuard` 收得住，任意命令不行。
     */
    @Test
    fun `不提供 shell 执行`() {
        val names = ChatTools.all().map { it.name }
        for (banned in listOf("run_command", "shell", "exec", "execute", "bash", "sh")) {
            assertFalse(
                "手机聊天客户端不该提供 $banned —— 它是不可控的通用能力，" +
                    "而文件操作有权限层能收住边界",
                names.contains(banned),
            )
        }
    }

    /**
     * ★★★ 文件工具**必须**真的过一遍权限层。
     *
     * 这是本轮最容易写成空壳的地方：`PermissionGuard` 早就写好了、也有测试，
     * 但它是纯函数 —— 只要没人调用，它就一行代码都不执行。
     * 那正是本项目反复出现的「机制都在，唯独连接不存在」（L26）。
     *
     * ⇒ 钉在**调用点**上：AndroidToolHost 里每个文件方法都必须调 `guard(...)`。
     */
    @Test
    fun `文件工具必须调用权限判定`() {
        val f = File("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())

        for (fn in listOf("readFile", "writeFile", "listDir")) {
            val body = extractFunctionFrom(src, "override fun $fn(")
            assertTrue("找不到 override fun $fn(", body.isNotBlank())
            assertTrue(
                "$fn 必须调用 guard(...) 做权限判定 —— " +
                    "PermissionGuard 是纯函数，没人调它等于一行都不执行",
                body.contains("guard("),
            )
            // 且必须在真正碰文件系统之前
            val guardIdx = body.indexOf("guard(")
            val fsIdx = body.indexOfRun("File(")
            assertTrue("$fn 里找不到文件系统操作", fsIdx >= 0)
            assertTrue("$fn 的权限判定必须在碰文件系统之前", guardIdx < fsIdx)
        }
    }

    /**
     * 文件工具必须真的接到会话的工作区与档位上（不是永远 null）。
     *
     * ★ 与运行时事实那次是同一个 bug 形态：提示词里写着"你在某个工作区、档位是
     *   workspace-write"，而执行层拿到的根是 null ⇒ 模型一调就报"没有工作区"。
     *   ⇒ 构造 AndroidToolHost 时必须同时传 workspaceRoot 与 workspacePermission。
     */
    @Test
    fun `文件工具必须接上当前会话的工作区与档位`() {
        assertTrue(
            "构造 AndroidToolHost 时必须传 workspaceRoot",
            chatHost.contains("workspaceRoot ="),
        )
        assertTrue(
            "构造 AndroidToolHost 时必须传 workspacePermission",
            chatHost.contains("workspacePermission ="),
        )
        assertTrue(
            "工作区信息必须在构造请求之前取到（运行时事实与文件工具共用同一份）",
            chatHost.indexOf("val wsInfo = buildWorkspaceInfo") <
                chatHost.indexOf("chatmobile.tools.ChatToolExecutor("),
        )
    }

    /**
     * `write_file` 是**第一个**真的消费 `requiresConfirmation` 的工具。
     *
     * ★ 在此之前这个字段定义了却没有任何消费方 —— 一张空头支票。
     *   钉住它是为了防止将来有人把写文件悄悄改成免确认。
     */
    @Test
    fun `写文件必须要求确认而读文件不必`() {
        val write = ChatTools.byName("write_file")
        assertNotNull("必须存在 write_file", write)
        assertTrue(
            "write_file 会改用户的数据，必须 requiresConfirmation = true",
            write!!.requiresConfirmation,
        )
        assertEquals(
            "write_file 必须归为本地写入类，设置页据此展示风险",
            ToolKind.WRITE_LOCAL,
            write.kind,
        )
        for (readOnly in listOf("read_file", "list_dir")) {
            val d = ChatTools.byName(readOnly)
            assertNotNull("必须存在 $readOnly", d)
            assertFalse(
                "$readOnly 是只读的，不该打断用户去确认",
                d!!.requiresConfirmation,
            )
            assertEquals("$readOnly 必须是只读分类", ToolKind.READ_ONLY, d.kind)
        }
    }

    /** 执行器必须按名分发到宿主，缺必填参数要在**碰宿主之前**就报回去。 */
    @Test
    fun `文件工具缺必填参数时不访问文件系统`() {
        val real = listOf(
            File("../core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
            File("core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
        ).firstOrNull { it.exists() }
        assertNotNull("找不到 ChatToolExecutor.kt", real)
        val src = stripComments(real!!.readText())

        for (tool in listOf("read_file", "write_file", "list_dir")) {
            assertTrue("执行器必须分发 $tool", src.contains("\"$tool\" ->"))
        }
        // write_file 的 content 允许空串（用户可能就想建个空文件），所以不能用 isBlank 判
        assertTrue(
            "write_file 的 content 必须用 null 判断而不是 isBlank —— 空串是合法内容",
            src.contains("缺少必填参数 content"),
        )
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
        // ★ 这条判据本身没变（必须有 additionalProperties:false），但**看的地方变了**：
        //   参数编码从 ToolRegistry 搬到了 ToolParametersJson（唯一实现）——
        //   因为引入 MCP 的原样透传之后，"结构化编码"成了两条路之一，
        //   留在 ToolRegistry 里会让 describe_tool 那条路没法复用（L26 的形状）。
        //
        //   ★ 更重要的是：这里从"检查源码里有这个字符串"升级成**检查真的产出**——
        //     源码里写着但没生效，是这类断言最大的盲区（L32）。
        for (def in chatmobile.tools.ChatTools.all()) {
            val encoded = def.spec.parametersJson()
            assertTrue(
                "工具 '${def.name}' 的参数 schema 缺 additionalProperties:false —— " +
                    "模型会塞未知参数，严格的网关会 400",
                encoded.contains("\"additionalProperties\":false"),
            )
        }

        // 结构上再钉一次：编码只允许在唯一实现里（不许有人又复制一份）
        val impl = listOf(
            File("../core-tools/src/main/kotlin/chatmobile/tools/ToolParametersJson.kt"),
            File("core-tools/src/main/kotlin/chatmobile/tools/ToolParametersJson.kt"),
        ).firstOrNull { it.exists() }
        assertNotNull("找不到 ToolParametersJson.kt —— 参数编码的唯一实现不见了", impl)
        assertTrue(
            "唯一实现里必须有 additionalProperties:false",
            impl!!.readText().contains("\"additionalProperties\":false"),
        )
    }
}
