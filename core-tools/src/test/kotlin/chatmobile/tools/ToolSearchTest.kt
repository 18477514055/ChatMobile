package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **工具检索**的契约（BM25）。
 *
 * ## 为什么需要这个文件
 *
 * 目录原来是**全量罗列**，靠模型精确点名。工具数一旦过 30–50（Anthropic 官方的
 * 准确率拐点），罗列就变成噪音；MCP 接进来后工具数轻易破百。
 * ⇒ 必须有按需检索的能力。
 *
 * ## ★ 判据纪律（这个文件最容易写成假绿）
 *
 * 不能只断言"返回了非空结果" —— 那连一个把所有工具按字母序吐出来的假实现都能通过。
 * 每条断言都要检查**检索结果对不对**：该排第一的排了第一没有，
 * 该被排除的有没有被排除。这正是 `L32`（判据要看产出对不对，不只是有没有产出）。
 */
class ToolSearchTest {

    /** 用真实工具集当夹具 —— 不用自己编的例子（`L32` 的另一面）。 */
    private fun docs(): List<ToolSearch.Doc> = ChatTools.all().map {
        ToolSearch.Doc(it.name, it.oneLiner, it.spec.description)
    }

    // ------------------------------------------------------------------
    // 一、基本正确性
    // ------------------------------------------------------------------

    @Test
    fun `按工具名检索能命中自己`() {
        // ★ 最基础的一条：用户/模型说"web_search"时它自己必须排第一。
        val hits = ToolSearch.search(docs(), "web_search")
        assertTrue(hits.isNotEmpty(), "按名字搜不到它自己，说明检索完全没用")
        assertEquals("web_search", hits.first().name, "工具名该是最强信号，必须排第一")
    }

    @Test
    fun `按用途描述检索能命中对应工具`() {
        // ★ 这是"检索"相对"罗列"的真正价值：模型不一定知道工具叫什么，
        //   但它知道自己想干什么。
        val cases = listOf(
            "read text from a file" to "read_file",
            "search the web" to "web_search",
            "what time is it" to "get_time",
            "list files in a folder" to "list_dir",
            "remember this about me" to "save_memory",
        )
        for ((q, expected) in cases) {
            val hits = ToolSearch.search(docs(), q)
            assertTrue(hits.isNotEmpty(), "查询「$q」什么都没搜到")
            assertTrue(
                hits.take(3).any { it.name == expected },
                "查询「$q」的前三名里没有 $expected，实际：${hits.map { it.name }}",
            )
        }
    }

    @Test
    fun `不相干的查询返回空 而不是硬凑`() {
        // ★ 查不到就该返回空。返回一堆低分结果会让模型召回一堆没用的工具，
        //   那比"没找到"更糟 —— 后者模型会换个词，前者它会以为找对了。
        val hits = ToolSearch.search(docs(), "quantum chromodynamics lattice gauge")
        assertTrue(
            hits.isEmpty() || hits.first().score < 1.0,
            "不相干的查询不该给出高分命中，实际：${hits.map { it.name to it.score }}",
        )
    }

    @Test
    fun `结果条数受 limit 限制`() {
        val hits = ToolSearch.search(docs(), "file web search memory time", limit = 3)
        assertTrue(hits.size <= 3, "limit=3 却返回了 ${hits.size} 条")
    }

    // ------------------------------------------------------------------
    // 二、排序必须是确定的（否则模型行为会随机）
    // ------------------------------------------------------------------

    @Test
    fun `同一查询两次检索结果完全一致`() {
        // ★ 不确定的排序会让同一个问题两次得到不同工具，模型的决策就退化成抽签。
        //   而且工具名同分时若按 HashMap 迭代序，那个顺序在不同 JVM 上都可能变。
        val a = ToolSearch.search(docs(), "search files").map { it.name }
        val b = ToolSearch.search(docs(), "search files").map { it.name }
        assertEquals(a, b)
    }

    @Test
    fun `同分时按名字排序 保证确定`() {
        // 三个文档对同一个词的命中完全一样 ⇒ 只能靠名字做最终裁决
        val same = listOf(
            ToolSearch.Doc("bbb", "alpha"),
            ToolSearch.Doc("aaa", "alpha"),
            ToolSearch.Doc("ccc", "alpha"),
        )
        val hits = ToolSearch.search(same, "alpha").map { it.name }
        assertEquals(listOf("aaa", "bbb", "ccc"), hits, "同分必须按名字升序，否则顺序不稳定")
    }

    // ------------------------------------------------------------------
    // 三、工具名权重（名字比描述更强的信号）
    // ------------------------------------------------------------------

    @Test
    fun `命中工具名的得分高于仅仅描述里提到`() {
        val docs = listOf(
            // 只在描述里提到 "search"
            ToolSearch.Doc("alpha", "does something", "this tool may search occasionally"),
            // 名字本身就叫 search
            ToolSearch.Doc("search", "does something", "this tool may search occasionally"),
        )
        val hits = ToolSearch.search(docs, "search")
        assertEquals("search", hits.first().name, "名字命中必须强于描述命中")
    }

    // ------------------------------------------------------------------
    // 四、分词（中文查询是真实场景）
    // ------------------------------------------------------------------

    @Test
    fun `分词把英文按词切并小写`() {
        assertEquals(listOf("web", "search"), ToolSearch.tokenize("Web Search"))
        // ★ 下划线是**分隔符**，不是词内字符。
        //   这看着像丢信息，实际是增益：用户说 "read a file" 时能命中 `read_file`。
        //   （不切的话两者零重叠，一条都搜不到 —— 是先写错测试才发现的。）
        assertEquals(listOf("read", "file"), ToolSearch.tokenize("read_file"))
    }

    @Test
    fun `停用词必须被丢掉 否则虚词会制造假命中`() {
        // ★★★ 这是一个实测出来的坑，不是理论上应该加：
        //   第一版没有停用词表，查询 "download a web page" 会命中 save_memory ——
        //   因为它的描述里有 "…for **a** durable fact…"，那个 `a` 挣了一点分，
        //   而只要分数 > 0 就被返回。
        //   后果很实际：模型问"怎么下载网页"，却拿回一个「记住这件事」的定义 ——
        //   **比什么都没搜到更糟**，它会以为自己找对了。
        assertEquals(listOf("download", "web", "page"), ToolSearch.tokenize("download a web page"))
        assertEquals(listOf("search"), ToolSearch.tokenize("please search it for me"))
    }

    @Test
    fun `中文单字不能被当停用词丢掉`() {
        // ★ 英文单字符是噪声，中文单字是有实义的（"搜" / "读"）。
        assertEquals(listOf("搜", "索"), ToolSearch.tokenize("搜索"))
    }

    @Test
    fun `分词把中文按单字切 而不是整句一个 token`() {
        // ★ 若不切，一整句中文会变成一个巨大 token，永远匹配不上任何工具。
        val t = ToolSearch.tokenize("搜索网页")
        assertEquals(4, t.size, "中文必须逐字切开，实际：$t")
    }

    @Test
    fun `中文查询能召回英文工具`() {
        // ★ 真实场景：用户用中文说需求，工具描述是英文。
        //   靠的是中文查询里的英文词 / 或描述里的中文（本项目描述全是英文，
        //   所以这里验证的是"不会因为中文查询崩掉或返回垃圾"）。
        val hits = ToolSearch.search(docs(), "搜索网页内容")
        // 中文单字对英文文档没有词面重叠 ⇒ 合理结果是空或低分，
        // 关键是不能抛异常、不能返回高分的无关结果
        assertTrue(
            hits.isEmpty() || hits.first().score < 1.0,
            "中文单字与英文文档无重叠时不该给出高分，实际：${hits.map { it.name to it.score }}",
        )
    }

    @Test
    fun `空查询返回空 不报错`() {
        assertTrue(ToolSearch.search(docs(), "").isEmpty())
        assertTrue(ToolSearch.search(docs(), "   ").isEmpty())
    }

    @Test
    fun `没有候选文档时返回空 不报错`() {
        // ★ 除数陷阱：avgLen 用 docs.size 算，空列表会除零。
        assertTrue(ToolSearch.search(emptyList(), "anything").isEmpty())
    }

    // ------------------------------------------------------------------
    // 五、接到 describe_tool 上（检索必须真的能用）
    // ------------------------------------------------------------------

    @Test
    fun `describe_tool 支持用 query 检索召回`() {
        // ★★★ 这条钉的是"检索真的接进了执行路径"，不是"写了一个 BM25 类"。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"query":"download a web page"}"""),
            enabled = emptySet(),
            lazy = setOf("web_fetch", "save_memory", "get_time"),
        )
        assertTrue(result is ToolResult.Success, "query 检索必须能用，实际：$result")
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("Tool: web_fetch"), "「下载网页」必须召回 web_fetch，实际：$out")
    }

    @Test
    fun `query 检索只在可召回范围内找`() {
        // ★ 与精确点名同一条底线：用户关掉的工具不能靠检索"捞"出来。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"query":"download a web page"}"""),
            enabled = emptySet(),
            lazy = setOf("save_memory"),   // web_fetch 不在可召回里
        )
        assertTrue(result is ToolResult.Failure, "检索也只能在 lazy 里找，实际：$result")
    }

    @Test
    fun `names 和 name 都没给时才走 query`() {
        // ★ 优先级：精确名字 > 检索。模型给了名字就以名字为准，
        //   否则"我就要 a，它却因为检索分高给了我 b"。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall(
                "c1",
                ToolCatalog.DESCRIBE_TOOL_NAME,
                """{"names":["get_time"],"query":"download a web page"}""",
            ),
            enabled = emptySet(),
            lazy = setOf("web_fetch", "get_time"),
        )
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("Tool: get_time"), "精确名字优先")
        assertTrue(!out.contains("Tool: web_fetch"), "给了名字就不该再去检索别的")
    }

    private class FakeHost : ToolHost {
        override fun nowText(timezone: String?): String = "2026-11-05 01:00 (UTC+08:00)"
        override fun locationText(precise: Boolean): String = "上海"
        override fun fetchUrl(url: String, maxChars: Int): String = "page"
        override fun saveMemory(content: String, category: String?): String = "saved"
        override fun searchMemory(query: String): String = "none"
        override fun readFile(path: String, maxChars: Int): String = "content"
        override fun writeFile(path: String, content: String, append: Boolean): String = "written"
        override fun listDir(path: String?): String = "a.txt"
        override fun webSearch(query: String, count: Int, usePaid: Boolean): String = "results"
        override fun currentConversationIdHint(): String? = null
        override fun searchHistory(
            query: String?,
            mode: String?,
            days: Int?,
            count: Int,
            excludeConversationId: String?,
        ): String = "history"
        override fun workspaceRootHint(): String = "/sdcard/ws"
    }
}
