package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具**懒加载**的契约。
 *
 * ## 用户原话（这是本文件存在的理由）
 *
 * > 「我们是不是可以考虑先把社区的懒加载机制给做了，不能让大量的工具整天填充上下文。
 *   > 感觉可以维护一个列表，每个工具都只有一句话，简洁说明和一个名字，
 *   > 真正用到的时候，模型调用工具召回它的完整语义和参数」
 *   > 「然后注意用户可以选择哪些工具常驻上下文，哪些工具进行懒加载。」
 *
 * ## 为什么这件事值得做（有数字）
 *
 * 工具定义排在请求最前面且**每轮全量发送**：
 *
 * | 工具数 | 全量暴露 | 目录模式 |
 * |---|---|---|
 * | 10（现状） | ~2062 字符 ≈ 800 token | ~800 字符 ≈ 320 token |
 * | 50 | ~10000 字符 ≈ 4000 token | ~1000 字符 ≈ 400 token |
 * | 200（MCP 铺开） | ~40000 字符 ≈ 16000 token | ~1200 字符 ≈ 480 token |
 *
 * ⇒ 工具越多省得越狠。现在建接口正是时候。
 *
 * ★ 断言用 `kotlin.test` 风格：**断言值在前、说明在后**（与 junit 相反）。
 */
class ToolLazyLoadingTest {

    private fun defs(): List<ToolDefinition> = ChatTools.all()

    // ------------------------------------------------------------------
    // 一、三种模式（不是两种）
    // ------------------------------------------------------------------

    @Test
    fun `模式有常驻 懒加载 关闭 三种`() {
        // ★ 把「关闭」混进「懒加载」是常见错误：
        //   那样模型会"发现"一个用户明确关掉的工具，然后反复尝试调用。
        assertEquals(3, ToolMode.entries.size)
        assertNotNull(ToolMode.parse("Resident"))
        assertNotNull(ToolMode.parse("Lazy"))
        assertNotNull(ToolMode.parse("Disabled"))
    }

    @Test
    fun `模式解析不区分大小写`() {
        assertEquals(ToolMode.Lazy, ToolMode.parse("lazy"))
        assertEquals(ToolMode.Resident, ToolMode.parse("RESIDENT"))
    }

    @Test
    fun `旧配置的 1 和 0 必须向后兼容`() {
        // ★ 这条最关键：绝不能因为升级让老用户所有工具突然变成懒加载 ——
        //   那会让每次调用多一跳往返，而他根本没改过设置。
        assertEquals(ToolMode.Resident, ToolMode.fromLegacyFlag("1"))
        assertEquals(ToolMode.Disabled, ToolMode.fromLegacyFlag("0"))
        assertEquals(ToolMode.Lazy, ToolMode.fromLegacyFlag("2"))
        assertNull(ToolMode.fromLegacyFlag(""))
        assertNull(ToolMode.fromLegacyFlag("garbage"))
    }

    // ------------------------------------------------------------------
    // 二、目录构建
    // ------------------------------------------------------------------

    @Test
    fun `全部常驻时 目录里没有 describe_tool`() {
        val built = ToolCatalog.build(defs(), defs().associate { it.name to ToolMode.Resident })
        assertFalse(
            built.resident.contains(ToolCatalog.DESCRIBE_TOOL_NAME),
            "没有懒加载工具时 describe_tool 是纯浪费（它描述的是一张空表）",
        )
        assertTrue(built.lazy.isEmpty())
    }

    @Test
    fun `全部常驻时 工具数等于定义数`() {
        val built = ToolCatalog.build(defs(), defs().associate { it.name to ToolMode.Resident })
        assertEquals(defs().size, built.resident.size)
    }

    @Test
    fun `有懒加载工具时必须有 describe_tool`() {
        val modes = defs().associate { it.name to ToolMode.Resident } +
            (defs().first().name to ToolMode.Lazy)
        val built = ToolCatalog.build(defs(), modes)
        assertNotNull(built.toolsJson, "必须发工具表")
        assertTrue(
            built.toolsJson!!.contains(ToolCatalog.DESCRIBE_TOOL_NAME),
            "必须带 describe_tool，否则模型没法召回",
        )
    }

    @Test
    fun `懒加载工具不进完整 schema 而进目录`() {
        val target = "search_history"
        val modes = defs().associate { it.name to ToolMode.Resident } + (target to ToolMode.Lazy)
        val built = ToolCatalog.build(defs(), modes)

        assertEquals(listOf(target), built.lazy, "必须被识别为懒加载")
        assertFalse(built.resident.contains(target), "不能出现在常驻列表")

        // ★ 关键：它的 name 会出现（在 describe_tool 的目录里），
        //   但它的**参数定义**不该出现在 tools 里（否则等于没省）。
        val json = built.toolsJson!!
        assertTrue(json.contains(target), "名字要出现在目录里")
        assertFalse(
            json.contains("\"search_history\",\"description\""),
            "参数定义不该出现（否则省不下来）",
        )
    }

    @Test
    fun `关闭的工具完全不出现`() {
        val target = "web_fetch"
        val modes = defs().associate { it.name to ToolMode.Resident } + (target to ToolMode.Disabled)
        val built = ToolCatalog.build(defs(), modes)

        assertFalse(built.resident.contains(target))
        assertFalse(built.lazy.contains(target))
        // ★ 判据要精确：检查它作为**工具定义条目**是否出现（`"name":"web_fetch"`），
        //   而不是"这个名字的字符串在不在 JSON 里" ——
        //   别的工具的 description 里完全可能顺带提到它（例如"抓网页见 web_fetch"），
        //   那是正常文本，不是把一个已关闭的工具暴露给模型。
        assertFalse(
            built.toolsJson?.contains("\"name\":\"$target\"") == true,
            "关闭的工具不该有工具定义条目 —— 否则模型会尝试调用一个用户明确关掉的东西",
        )
    }

    @Test
    fun `全部关闭时不发 tools 字段`() {
        // ★ 空表 ⇒ null ⇒ 请求里不发 tools 字段（发空数组有些网关 400）
        val built = ToolCatalog.build(defs(), defs().associate { it.name to ToolMode.Disabled })
        assertNull(built.toolsJson, "全关时不该发 tools")
    }

    // ------------------------------------------------------------------
    // 三、describe_tool 的目录必须带「名字 + 一句话」
    // ------------------------------------------------------------------

    @Test
    fun `describe_tool 的描述里必须列出所有懒加载工具`() {
        val lazyNames = listOf("web_fetch", "search_history")
        val modes = defs().associate { it.name to ToolMode.Resident } +
            lazyNames.associateWith { ToolMode.Lazy }
        val json = ToolCatalog.build(defs(), modes).toolsJson!!

        for (n in lazyNames) {
            assertTrue(
                json.contains(n),
                "目录里必须有 '$n' —— 没有的话模型只能盲猜工具名，猜错两三次就放弃了",
            )
        }
    }

    @Test
    fun `describe_tool 的描述里必须带一句话说明`() {
        val modes = defs().associate { it.name to ToolMode.Resident } +
            ("web_fetch" to ToolMode.Lazy)
        val oneLiner = ChatTools.byName("web_fetch")!!.oneLiner
        val json = ToolCatalog.build(defs(), modes).toolsJson!!

        assertTrue(oneLiner.isNotBlank(), "一句话说明必须非空")
        assertTrue(
            json.contains(oneLiner),
            "目录里必须带上这句话，否则模型不知道这个工具值不值得召回",
        )
    }

    @Test
    fun `每个工具都必须有一句话说明`() {
        for (def in ChatTools.all()) {
            assertTrue(
                def.oneLiner.isNotBlank(),
                "工具 '${def.name}' 缺 oneLiner —— 懒加载时目录里会是空白",
            )
        }
    }

    @Test
    fun `一句话说明必须够短`() {
        // ★ 目录条目的总长度 = 所有 lazy 工具的 (名字 + 一句话)。
        //   一句话若写成 150 字，100 个工具就是 15000 字 —— 等于没省。
        for (def in ChatTools.all()) {
            assertTrue(
                def.oneLiner.length <= 90,
                "工具 '${def.name}' 的 oneLiner 太长（${def.oneLiner.length} 字符）：${def.oneLiner}",
            )
        }
    }

    @Test
    fun `一句话说明不该写调用方式`() {
        // ★ 只讲"能干什么"。写调用方式等于把懒加载又变回常驻。
        for (def in ChatTools.all()) {
            val s = def.oneLiner
            assertFalse(
                s.contains("parameter") || s.contains("argument"),
                "工具 '${def.name}' 的 oneLiner 提到了参数，应该只讲用途：$s",
            )
        }
    }

    // ------------------------------------------------------------------
    // 四、召回闭环（最容易做成死循环的地方）
    // ------------------------------------------------------------------

    @Test
    fun `describe_tool 返回完整参数定义`() {
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"name":"web_search"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )

        assertTrue(result is ToolResult.Success, "必须成功，实际：$result")
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("web_search"), "必须有工具名")
        assertTrue(out.contains("Search the web"), "必须说清用途")
        assertTrue(out.contains("query"), "必须列出参数")
        assertTrue(
            out.contains("call") || out.contains("now"),
            "必须告诉模型现在可以调了",
        )
    }

    @Test
    fun `describe_tool 不接受不在可召回列表里的工具`() {
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"name":"read_file"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `describe_tool 对未知工具名给出可选项`() {
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"name":"nope"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search", "web_fetch"),
        )
        assertTrue(result is ToolResult.Failure)
        val msg = (result as ToolResult.Failure).error
        assertTrue(msg.contains("web_search"), "必须列出可选项，否则模型只能一直猜")
        assertTrue(msg.contains("web_fetch"))
    }

    @Test
    fun `describe_tool 缺 name 参数时明确要求`() {
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, "{}"),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `召回之后必须真的能调用`() {
        // ★★★ 这是整个机制的关键：如果召回后调不动，它就是**坏的死循环**。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", "get_time", "{}"),
            enabled = emptySet(),          // 不在常驻集合
            lazy = setOf("get_time"),      // 但在可召回集合
        )
        assertTrue(result is ToolResult.Success, "懒加载工具必须可被调用，否则机制是死循环。实际：$result")
    }

    @Test
    fun `既不在常驻也不在可召回时仍然拒绝`() {
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", "get_time", "{}"),
            enabled = emptySet(),
            lazy = emptySet(),
        )
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("已被用户关闭"))
    }

    @Test
    fun `describe_tool 本身不需要出现在 enabled 里`() {
        // ★ 它是**元工具**，由目录机制自己提供，不属于用户可配置项。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"name":"web_search"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )
        assertTrue(result is ToolResult.Success, "元工具不该被 enabled 拦住")
    }

    // ------------------------------------------------------------------
    // 五、★ 批量召回（names[]）—— 对应调研报告 P0-1
    // ------------------------------------------------------------------

    @Test
    fun `describe_tool 支持一次召回多个工具`() {
        // ★ 为什么这条重要：一次只返回一个 = 模型查 3 个工具就多 2 次往返，
        //   而每次往返都是一次完整的模型调用（真金白银）。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":["web_search","read_file"]}"""),
            enabled = emptySet(),
            lazy = setOf("web_search", "read_file"),
        )
        assertTrue(result is ToolResult.Success, "批量召回必须成功，实际：$result")
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("Tool: web_search"), "必须包含第一个工具的定义")
        assertTrue(out.contains("Tool: read_file"), "必须包含第二个工具的定义")
    }

    @Test
    fun `批量召回时每个工具的参数表都要完整`() {
        // ★ 防退化：不能因为批量就只给第一个工具的参数。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":["web_search","read_file"]}"""),
            enabled = emptySet(),
            lazy = setOf("web_search", "read_file"),
        )
        val out = (result as ToolResult.Success).output
        // web_search 的 query / read_file 的 path 都必须出现
        assertTrue(out.contains("query"), "web_search 的参数 query 必须在")
        assertTrue(out.contains("path"), "read_file 的参数 path 必须在")
    }

    @Test
    fun `批量召回部分命中时 部分成功优于全失败`() {
        // ★ 容错策略：模型要 3 个、其中 1 个打错名字时，
        //   把命中的 2 个 + "没找到 X" 一起回给它，它据此能自己改 ——
        //   整个调用作废重来的代价是多一次往返。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":["web_search","nope","read_file"]}"""),
            enabled = emptySet(),
            lazy = setOf("web_search", "read_file"),
        )
        assertTrue(result is ToolResult.Success, "部分命中仍需成功返回，实际：$result")
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("web_search"), "命中的必须给出来")
        assertTrue(out.contains("read_file"), "命中的必须给出来")
        assertTrue(out.contains("NOT FOUND"), "没找到的要明确标出，否则模型以为全拿到了")
        assertTrue(out.contains("nope"), "要点出是哪个名字没找到")
    }

    @Test
    fun `单个 name 的旧写法必须继续可用`() {
        // ★ 向后兼容：模型从历史里学到的旧调用方式不能突然失效。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"name":"web_search"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )
        assertTrue(result is ToolResult.Success, "旧写法必须还能用，实际：$result")
    }

    @Test
    fun `names 被写成逗号分隔字符串时也要认`() {
        // ★ 模型对"数组"的写法很不统一。认不出就等于"没传"，
        //   而它会白白烧掉一次往返。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":"web_search,read_file"}"""),
            enabled = emptySet(),
            lazy = setOf("web_search", "read_file"),
        )
        assertTrue(result is ToolResult.Success, "逗号分隔写法要认，实际：$result")
        val out = (result as ToolResult.Success).output
        assertTrue(out.contains("web_search") && out.contains("read_file"))
    }

    @Test
    fun `names 里的关闭工具不能被召回`() {
        // ★ 与单值路径同一条底线：用户关掉的工具，批量点名也不给。
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":["web_search","secret_tool"]}"""),
            enabled = emptySet(),
            lazy = setOf("web_search"),
        )
        val out = (result as ToolResult.Success).output
        assertFalse(out.contains("Tool: secret_tool"), "不在可召回列表的工具不能被打包送出")
    }

    @Test
    fun `一次召回的数量有上限 防止把上下文撑爆`() {
        // ★ 模型偶尔会一口气点名十几个名字。上限只防失控，不是产品限制。
        val many = ChatTools.all().map { it.name }
        val result = ChatToolExecutor(FakeHost()).execute(
            ToolCall(
                "c1",
                ToolCatalog.DESCRIBE_TOOL_NAME,
                """{"names":[${many.joinToString(",") { "\"$it\"" }}]}""",
            ),
            enabled = emptySet(),
            lazy = many.toSet(),
        )
        assertTrue(result is ToolResult.Success)
        val out = (result as ToolResult.Success).output
        val blocks = Regex("Tool: ").findAll(out).count()
        assertTrue(blocks <= 8, "一次最多解释 8 个，实际 $blocks 个 —— 再多会把上下文挤掉")
    }

    // ------------------------------------------------------------------
    // 六、★ 工具使用示例 —— 对应调研报告 P0-2
    // ------------------------------------------------------------------

    @Test
    fun `三个参数有坑的工具必须带使用示例`() {
        // ★ 依据：Anthropic 实测加示例后复杂参数准确率 72% → 90%。
        //   只给真正会传错的工具加 —— 全加会把省下来的上下文又吐回去。
        for (name in listOf("write_file", "search_history", "web_search")) {
            val def = ChatTools.byName(name)!!
            assertTrue(
                def.spec.examples.isNotEmpty(),
                "工具 '$name' 必须带使用示例 —— 它的参数有 schema 表达不了的坑",
            )
        }
    }

    @Test
    fun `示例必须真的进到发给模型的描述里`() {
        // ★★★ 这条是 L26 的钉子：数据加了但消费点没读，等于没加。
        val out = ChatToolExecutor(FakeHost()).execute(
            ToolCall("c1", ToolCatalog.DESCRIBE_TOOL_NAME, """{"names":["write_file"]}"""),
            enabled = emptySet(),
            lazy = setOf("write_file"),
        )
        val out2 = (out as ToolResult.Success).output
        val example = ChatTools.byName("write_file")!!.spec.examples.first().args
        assertTrue(
            out2.contains(example),
            "懒加载召回的结果里必须能看到示例，否则那条路永远拿不到示例",
        )

        // 常驻那条路同样要能拿到（走 ToolRegistry 序列化）。
        // ★ 用 `whenTo` 而不是 `args` 来定位：`args` 里带 `\n` 这类 JSON 转义，
        //   序列化后会被再转义一层（`\` → `\\`），拿原文去 contains 必然匹配不上——
        //   那是**尺子错**，不是实现错。`whenTo` 是纯 ASCII 且不含引号/换行。
        val whenTo = ChatTools.byName("write_file")!!.spec.examples.first().whenTo
        val json = ToolCatalog.build(
            ChatTools.all(),
            ChatTools.all().associate { it.name to ToolMode.Resident },
        ).toolsJson!!
        assertTrue(
            json.contains(whenTo),
            "常驻工具的 tools JSON 里也必须带上示例，否则那条路永远拿不到示例",
        )
    }

    @Test
    fun `示例的 JSON 必须真的能解析`() {
        // ★ 示例是给模型照抄的。抄一个语法错的 JSON 出去，
        //   模型的第一次调用就是失败的 —— 比不给示例更糟。
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        for (def in ChatTools.all()) {
            for (e in def.spec.examples) {
                val parsed = runCatching {
                    json.parseToJsonElement(e.args)
                }
                assertTrue(
                    parsed.isSuccess,
                    "工具 '${def.name}' 的示例不是合法 JSON：${e.args}（${parsed.exceptionOrNull()?.message}）",
                )
                assertTrue(
                    parsed.getOrNull() is kotlinx.serialization.json.JsonObject,
                    "工具 '${def.name}' 的示例必须是 JSON 对象：${e.args}",
                )
                assertTrue(e.whenTo.isNotBlank(), "示例必须说明什么场景该这样传")
            }
        }
    }

    @Test
    fun `示例必须控制体积`() {
        // ★ 示例会永久占据每一轮的输入 token（工具定义在缓存前缀里）。
        //   全加会让工具表从 2062 字符涨到四五千 —— 省下的上下文又吐回去了。
        val withExamples = ChatTools.all().filter { it.spec.examples.isNotEmpty() }
        assertTrue(
            withExamples.size <= 4,
            "只给「参数有坑」的工具加示例，现在有 ${withExamples.size} 个：${withExamples.map { it.name }}",
        )
        val budget = ChatTools.all().sumOf { d ->
            d.spec.examples.sumOf { it.whenTo.length + it.args.length + 4 }
        }
        assertTrue(budget < 900, "示例总量 $budget 字符，太大会吃掉懒加载省下的收益")
    }

    @Test
    fun `没有示例的工具 fullDescription 与 description 完全一致`() {
        // ★ 不加示例的工具不该多出任何字符（包括换行）——
        //   否则等于给所有工具涨了 token，而收益只在三个工具上。
        for (def in ChatTools.all().filter { it.spec.examples.isEmpty() }) {
            assertEquals(def.spec.description, def.spec.fullDescription(), "工具 '${def.name}' 多出了内容")
        }
    }

    // ------------------------------------------------------------------
    // 八、★ 默认分级（调研报告 P1-3）
    // ------------------------------------------------------------------

    @Test
    fun `默认常驻的工具必须是 3 到 5 个`() {
        // ★ 依据：官方两家都建议「3–5 个最常用的常驻，其余延迟」，而不是一刀切。
        //   少于此：几乎每个工具都要先 describe，往返次数爆炸。
        //   多于此：常驻本来就占掉了省下来的空间，"按需加载"名存实亡。
        val resident = ChatTools.all().filter { it.preferredMode == ToolMode.Resident }
        assertTrue(
            resident.size in 3..5,
            "默认常驻 ${resident.size} 个（${resident.map { it.name }}），官方建议 3–5 个",
        )
    }

    @Test
    fun `高频工具必须常驻`() {
        // ★ 这三个是「几乎每轮都可能要用」的：时间 / 联网搜索 / 检索记忆。
        //   让它们按需 = 每次用都多一次完整模型调用往返，非常不划算。
        for (name in listOf("get_time", "web_search", "search_memory")) {
            assertEquals(
                ToolMode.Resident,
                ChatTools.byName(name)!!.preferredMode,
                "高频工具 '$name' 必须默认常驻 —— 让模型每次多跳一次往返不值得",
            )
        }
    }

    @Test
    fun `低频工具默认按需 但一个都不能是关闭`() {
        // ★ 按需 ≠ 关闭。Disable 是**用户**的决定，默认值不该替用户关掉任何能力。
        for (def in ChatTools.all()) {
            assertTrue(
                def.enabledByDefault,
                "工具 '${def.name}' 默认被关掉了 —— 默认值不该替用户做主",
            )
            assertTrue(
                def.preferredMode != ToolMode.Disabled,
                "工具 '${def.name}' 的 preferredMode 不能是 Disabled",
            )
        }
    }

    @Test
    fun `默认分级后 describe_tool 必须出现`() {
        // ★ 有按需工具 ⇒ 必须有 describe_tool，否则按需工具永远召回不到。
        val defaults = ChatTools.all().associate { it.name to it.preferredMode }
        val built = ToolCatalog.build(ChatTools.all(), defaults)
        assertTrue(built.lazy.isNotEmpty(), "默认配置下必须有按需工具")
        assertTrue(
            built.toolsJson!!.contains(ToolCatalog.DESCRIBE_TOOL_NAME),
            "有按需工具就必须带 describe_tool",
        )
    }

    @Test
    fun `默认分级能明显缩小工具表`() {
        // ★ 这条量化了"默认分级到底省了多少"—— 防止改了默认值却几乎没效果。
        val all = ChatTools.all()
        val allResident = ToolCatalog.build(all, all.associate { it.name to ToolMode.Resident })
            .toolsJson!!.length
        val defaults = ToolCatalog.build(all, all.associate { it.name to it.preferredMode })
            .toolsJson!!

        // 按需后剩不到七成（实测约六成），否则说明"默认常驻"根本没改到点子上
        assertTrue(
            defaults.length < allResident * 0.75,
            "默认分级后应明显变小：全常驻 $allResident → 默认 ${defaults.length} 字符",
        )
    }

    // ------------------------------------------------------------------
    // 十、★ 分组（调研报告 P1-2，保守版）
    // ------------------------------------------------------------------

    @Test
    fun `分组不能让任何工具从目录里消失`() {
        // ★★★ 这条是整个分组设计的**底线**。
        //
        //   报告明确警告过分层的失败模式：**选错组 = 死路** ——
        //   模型看不到正确工具、也没有自纠路径。
        //   我们的做法是"分组只用于显示结构，不构成筛选"。
        //   这条测试就是那个承诺的钉子：**每个懒加载工具的名字都必须仍然出现**。
        val lazyNames = ChatTools.all().map { it.name }
        val modes = ChatTools.all().associate { it.name to ToolMode.Lazy }
        val json = ToolCatalog.build(ChatTools.all(), modes).toolsJson!!

        for (n in lazyNames) {
            assertTrue(
                json.contains(n),
                "工具 '$n' 在分组后从目录里消失了 —— 分组退化成筛选了，" +
                    "模型将永远看不到它（报告警告的「选错组=死路」）",
            )
        }
    }

    @Test
    fun `每个工具都有明确的分组`() {
        // ★ 分组为空会让目录里出现一个没有标题的孤儿条目。
        for (def in ChatTools.all()) {
            assertTrue(
                def.group.label.isNotBlank(),
                "工具 '${def.name}' 的分组没有标签",
            )
        }
        for (def in McpTools.all()) {
            assertEquals(
                ToolGroup.SETUP,
                def.group,
                "MCP 配置元工具 '${def.name}' 应归入 Setup 组",
            )
        }
    }

    @Test
    fun `分组顺序必须固定 不能依赖 map 迭代顺序`() {
        // ★ 顺序变了缓存前缀就变了（Anthropic 逐字节比对前缀）。
        //   目录按 ToolGroup.entries 的**声明顺序**输出，这条钉住它。
        assertTrue(
            ToolGroup.entries.indexOf(ToolGroup.CONTEXT) <
                ToolGroup.entries.indexOf(ToolGroup.WEB),
            "分组枚举顺序必须是确定的声明顺序",
        )
        val a = ToolCatalog.build(
            ChatTools.all(),
            ChatTools.all().associate { it.name to ToolMode.Lazy },
        ).toolsJson
        val b = ToolCatalog.build(
            ChatTools.all(),
            ChatTools.all().associate { it.name to ToolMode.Lazy },
        ).toolsJson
        assertEquals(a, b, "同样的输入必须产出逐字节相同的工具表（缓存前缀）")
    }

    // ------------------------------------------------------------------
    // 九、省 token 的量级（防止实现退化成"没省"）
    // ------------------------------------------------------------------

    @Test
    fun `懒加载后工具表必须明显变小`() {
        val all = defs()
        val everythingResident = ToolCatalog.build(
            all,
            all.associate { it.name to ToolMode.Resident },
        ).toolsJson!!.length

        val mostlyLazy = ToolCatalog.build(
            all,
            all.associate {
                if (it.name == "get_time") it.name to ToolMode.Resident
                else it.name to ToolMode.Lazy
            },
        ).toolsJson!!.length

        assertTrue(
            mostlyLazy < everythingResident * 0.7,
            "懒加载后必须明显变小：全常驻 $everythingResident 字符 → 只留 1 个常驻 $mostlyLazy 字符",
        )
    }

    @Test
    fun `加了批量召回与示例之后 省 token 的结论仍然成立`() {
        // ★★★ 这条是给这一轮改动上的保险。
        //   批量召回（names[]）与使用示例都往**常驻**那一侧加了字符：
        //   - describe_tool 的 description 变长了（多了 names / query 的说明）
        //   - 三个工具多了示例
        //   ⇒ 如果加得太多，懒加载省下来的又会被吃掉，那这一轮的收益就是负的。
        val all = defs()
        val residentChars = ToolCatalog.build(
            all,
            all.associate { it.name to ToolMode.Resident },
        ).toolsJson!!.length

        // 默认策略（内建工具全部常驻）与「3 个高频常驻 + 其余懒加载」对比
        val lean = ToolCatalog.build(
            all,
            all.associate {
                if (it.name in setOf("get_time", "web_search", "search_memory")) {
                    it.name to ToolMode.Resident
                } else {
                    it.name to ToolMode.Lazy
                }
            },
        ).toolsJson!!.length

        assertTrue(
            lean < residentChars * 0.6,
            "3 常驻 + 其余懒加载必须省下 40% 以上，实际 $residentChars → $lean 字符",
        )
    }

    @Test
    fun `常驻工具表的总量要有天花板 防止悄悄膨胀`() {
        // ★★★ 这个上限是**实测**出来的，不是从报告抄的。
        //
        //   实测（2026-11-05，本机跑出来的真实值）：
        //   - 10 个工具全常驻，**改动前 = 7275 字符**
        //   - 本轮加了三个使用示例 + describe_tool 的 names/query schema ⇒ **8157 字符**（净增 882）
        //   - 其中**纯 JSON 结构**（所有 description 换成空串）就要 **3969 字符**
        //
        //   ⇒ ★ 调研报告里那个「10 工具 = 2062 字符 ≈ 800 token」是**错的**（实际 3.5 倍）。
        //     它可能算的是 5–6 个工具，或者只算了描述没算参数结构。
        //     **已回填进台账**，别再用那个数字做决策。
        //
        //   天花板取 9000：给未来两三个工具留位置，但堵住"悄悄给每个工具加长描述"。
        val all = defs()
        val json = ToolCatalog.build(
            all,
            all.associate { it.name to ToolMode.Resident },
        ).toolsJson!!
        assertTrue(
            json.length < 9000,
            "全常驻工具表 ${json.length} 字符，超过 9000 天花板 —— 检查是不是又给工具加了长描述/示例。" +
                "（实测基线：2026-11-05 为 8157，其中纯结构 3969）",
        )
    }

    @Test
    fun `纯 JSON 结构本身占用的比例要清楚 别把账算错`() {
        // ★ 这条是给"估算 token"的人上的保险：
        //   把 description 全清空后仍有近 4000 字符 —— 那是参数名、类型、required、
        //   additionalProperties 这些**结构性开销**，改描述一点也优化不掉。
        //   ⇒ 以后说"工具表多少 token"必须包含这一块，否则会低估 3 倍。
        val stripped = ChatTools.all().map { d ->
            d.copy(spec = d.spec.copy(description = "", examples = emptyList()))
        }
        val structural = ToolCatalog.build(
            stripped,
            stripped.associate { it.name to ToolMode.Resident },
        ).toolsJson!!.length

        assertTrue(
            structural > 3000,
            "纯结构应约 3969 字符（实测），现在 $structural —— 参数结构若被改小了要重新核一遍",
        )
    }

    /** 假的 ToolHost —— `core-tools` 不碰 Android，注入即可。 */
    private class FakeHost : ToolHost {
        override fun nowText(timezone: String?): String = "2026-11-05 00:30 (UTC+08:00)"
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
