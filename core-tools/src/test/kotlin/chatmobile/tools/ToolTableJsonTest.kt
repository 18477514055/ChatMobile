package chatmobile.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **工具表 JSON 的结构契约**。
 *
 * ## ★★★ 为什么需要这个文件（而不是继续用 `contains("xxx")` 断言）
 *
 * 之前所有关于工具表的测试都是**字符串包含**断言：
 * `assertTrue(json.contains("\"name\":\"web_search\""))`。
 *
 * 这类断言有个致命盲区：**括号数量写错也照样通过**。
 * 本轮改造 `ToolRegistry` 时把结尾从 `}}}` 改成 `}}`（抽取参数编码后的必然结果），
 * 而所有 `contains` 断言**一条都不会报警** —— 但发出去的会是一个**语法非法的 JSON**，
 * 每家厂商都会 400，而且是全量用户全量失败。
 *
 * ⇒ 判据必须升级：**真的解析一遍**。
 *   这正是 `L32`（判据要看产出对不对，不只是有没有产出）在本项目最重要的一次应用。
 */
class ToolTableJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun built(): String = ToolCatalog.build(
        ChatTools.all(),
        ChatTools.all().associate { it.name to ToolMode.Resident },
    ).toolsJson!!

    @Test
    fun `常驻工具表必须是合法 JSON`() {
        // ★★ 一条能挡住"括号写错"的断言。
        val text = built()
        val parsed = runCatching { json.parseToJsonElement(text) }
        assertTrue(
            parsed.isSuccess,
            "工具表不是合法 JSON（${parsed.exceptionOrNull()?.message}）—— 这种错误会让所有厂商 400，" +
                "而且字符串包含断言发现不了。前 200 字符：${text.take(200)}",
        )
    }

    @Test
    fun `工具表必须是数组 且长度等于工具数`() {
        val arr = json.parseToJsonElement(built()) as JsonArray
        assertEquals(ChatTools.all().size, arr.size, "每个工具一条，不能多也不能少")
    }

    @Test
    fun `每个条目的结构必须是 OpenAI function 形态`() {
        val arr = json.parseToJsonElement(built()) as JsonArray
        for (el in arr) {
            val obj = el as JsonObject
            assertEquals("function", obj["type"].toString().trim('"'), "type 必须是 function")
            val fn = obj["function"] as? JsonObject
            assertNotNull(fn, "必须有 function 对象")
            // ★ function 里**只允许**这三个键。多塞字段（例如 Anthropic 专有的
            //   input_examples）会被严格网关 400 —— 这就是示例只能拼进 description 的原因。
            assertEquals(
                setOf("name", "description", "parameters"),
                fn.keys,
                "function 的键集合不对：${fn.keys}",
            )
        }
    }

    @Test
    fun `parameters 必须是合法 JSON 对象 且带 additionalProperties 禁令`() {
        val arr = json.parseToJsonElement(built()) as JsonArray
        for (el in arr) {
            val fn = (el as JsonObject)["function"] as JsonObject
            val name = fn["name"].toString().trim('"')
            val params = fn["parameters"] as? JsonObject
            assertNotNull(params, "工具 '$name' 的 parameters 必须是对象")
            assertEquals("object", params["type"].toString().trim('"'), "工具 '$name' 的 type 必须是 object")
            assertEquals(
                "false",
                params["additionalProperties"].toString(),
                "工具 '$name' 缺 additionalProperties:false —— 模型会塞未声明字段，严格网关 400",
            )
        }
    }

    @Test
    fun `工具名在表里不重复`() {
        val arr = json.parseToJsonElement(built()) as JsonArray
        val names = arr.map { ((it as JsonObject)["function"] as JsonObject)["name"].toString() }
        assertEquals(names.size, names.toSet().size, "工具名重复：$names")
    }

    @Test
    fun `数组参数必须正确序列化 items`() {
        // ★★★ 这条钉的是一个**真的发出去会导致全量 400 的 bug**（本轮实际踩到）。
        //
        //   手写 JSON 拼接时，`"items":{"type":` 里的引号很容易写错一半：
        //   - 少一个 ⇒ `{"type":string"}`  → 非法
        //   - 多一个 ⇒ `{"type":"string""}` → 非法
        //   两种错法都会让**整个工具表**变成非法 JSON。
        //
        //   ★ 危险之处：它会随「有没有工具带数组参数」而**时隐时现**。
        //     全常驻时可能碰不到（describe_tool 不出现），
        //     一开启按需加载（describe_tool 的 `names` 是数组）就立刻炸 ——
        //     而按需加载正是我们刚设成默认的。
        val def = ChatTools.byName("write_file")!!
        val spec = ToolSpec(
            name = "array_probe",
            description = "probe",
            parameters = ToolParameters(
                properties = mapOf(
                    "names" to ParamSpec(
                        ParamType.ARRAY,
                        "list of names",
                        items = ParamType.STRING,
                    ),
                ),
                required = listOf("names"),
            ),
        )
        val reg = ToolRegistry()
        reg.register(spec, object : ToolExecutor {
            override fun execute(call: ToolCall, workingDir: String?): ToolResult =
                ToolResult.Failure(call.callId, "n/a")
        })
        val out = reg.toOpenAiToolsJson()

        assertTrue(
            runCatching { json.parseToJsonElement(out) }.isSuccess,
            "带数组参数的工具表不是合法 JSON：$out",
        )
        // 结构化断言：items 必须真的是 {"type":"string"}
        val arr = json.parseToJsonElement(out) as JsonArray
        val params = ((arr[0] as JsonObject)["function"] as JsonObject)["parameters"] as JsonObject
        val namesProp = (params["properties"] as JsonObject)["names"] as JsonObject
        val items = namesProp["items"] as? JsonObject
        assertNotNull(items, "items 必须是对象，实际：${namesProp["items"]}")
        assertEquals("string", items["type"].toString().trim('"'), "items.type 必须是 string")
        assertTrue(def.name.isNotBlank())
    }

    @Test
    fun `默认分级下的工具表也必须可解析`() {
        // ★★★ 这条是上面那个 bug 的**真实触发场景**：默认分级会让 describe_tool 出现，
        //   而它的 `names` 参数是数组 ⇒ 一旦 items 写错，这里立刻炸。
        //   全常驻那条路（上面的 built()）反而碰不到 —— 所以必须单独测这一条。
        val all = ChatTools.all() + McpTools.all()
        val jsonText = ToolCatalog.build(
            all,
            all.associate { it.name to it.preferredMode },
        ).toolsJson!!
        val parsed = runCatching { json.parseToJsonElement(jsonText) }
        assertTrue(
            parsed.isSuccess,
            "默认分级（含按需工具）下工具表不是合法 JSON（${parsed.exceptionOrNull()?.message}）" +
                " —— 这会让**所有用户**的每一轮请求都被厂商 400",
        )
        assertTrue(jsonText.contains("describe_tool"), "默认分级下必须有 describe_tool")
    }

    @Test
    fun `每个工具单独序列化都必须是合法 JSON`() {
        // ★ 逐个测：这样出问题时报错能直接点名是哪个工具，
        //   而不是"整个表坏了"。
        for (def in ChatTools.all() + McpTools.all()) {
            val out = ToolCatalog.build(
                listOf(def),
                mapOf(def.name to ToolMode.Resident),
            ).toolsJson!!
            assertTrue(
                runCatching { json.parseToJsonElement(out) }.isSuccess,
                "工具 '${def.name}' 单独序列化不是合法 JSON：$out",
            )
        }
    }

    @Test
    fun `原样透传的参数必须被完整保留 一个字符都不能改`() {
        // ★★★ MCP 工具走这条路。它的 inputSchema 里可能有 oneOf / $ref / pattern，
        //   我们的简化模型表达不了 ⇒ 必须原样透传。
        //   若这里做"解析再重建"，约束会被**静默丢掉**，模型必然传错参数。
        val mcpSchema = """
            {"type":"object","properties":{"q":{"type":"string","pattern":"^[a-z]+$"}},
             "required":["q"],"additionalProperties":false,
             "oneOf":[{"required":["q"]},{"required":["id"]}]}
        """.trimIndent().replace("\n", "")

        val spec = ToolSpec(
            name = "mcp_demo",
            description = "demo",
            parameters = ToolParameters(emptyMap()),   // 结构化那份应被完全忽略
            rawParametersJson = mcpSchema,
        )
        val reg = ToolRegistry()
        reg.register(spec, object : ToolExecutor {
            override fun execute(call: ToolCall, workingDir: String?): ToolResult =
                ToolResult.Failure(call.callId, "n/a")
        })
        val out = reg.toOpenAiToolsJson()

        assertTrue(out.contains("oneOf"), "oneOf 丢了 —— 说明走了结构化那条路，约束被静默丢弃")
        assertTrue(out.contains("pattern"), "pattern 丢了")
        assertTrue(out.contains("^[a-z]+$"), "pattern 的具体值被改了")

        // 顺带验证整体仍是合法 JSON（透传的字符串嵌进去不能破坏外层）
        assertTrue(
            runCatching { json.parseToJsonElement(out) }.isSuccess,
            "带原样透传参数的工具表不是合法 JSON",
        )
    }

    @Test
    fun `没有原样透传时 parametersJson 等于结构化编码`() {
        // ★ 唯一入口的正确性：两条路不能不一致。
        for (def in ChatTools.all()) {
            null == def.spec.rawParametersJson
            assertEquals(
                ToolParametersJson.encode(def.spec.parameters),
                def.spec.parametersJson(),
                "工具 '${def.name}' 的参数编码与结构化编码不一致",
            )
        }
    }

    @Test
    fun `参数编码本身必须是合法 JSON 且保留 required`() {
        val def = ChatTools.byName("write_file")!!
        val encoded = def.spec.parametersJson()
        val obj = json.parseToJsonElement(encoded) as JsonObject
        assertEquals("object", obj["type"].toString().trim('"'))
        val required = obj["required"] as JsonArray
        assertTrue(
            required.map { it.toString().trim('"') }.containsAll(listOf("path", "content")),
            "required 丢了：$required",
        )
    }
}
