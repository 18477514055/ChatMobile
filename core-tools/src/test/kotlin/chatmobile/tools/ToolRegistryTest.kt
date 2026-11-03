package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {

    private val dummyExecutor = object : ToolExecutor {
        override fun execute(call: ToolCall, workingDir: String?): ToolResult =
            ToolResult.Success(call.callId, "ok", 0)
    }

    @Test
    fun `注册后能查到`() {
        val reg = ToolRegistry().register(BuiltinTools.shell, dummyExecutor)
        assertTrue(reg.has("run_command"))
        assertEquals(1, reg.specs().size)
    }

    @Test
    fun `重名注册报错`() {
        val reg = ToolRegistry().register(BuiltinTools.shell, dummyExecutor)
        var threw = false
        try {
            reg.register(BuiltinTools.shell, dummyExecutor)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "重名应报错")
    }

    @Test
    fun `非法工具名报错`() {
        val bad = BuiltinTools.shell.copy(name = "有中文的名字")
        var threw = false
        try {
            ToolRegistry().register(bad, dummyExecutor)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "非法名字应报错")
    }

    @Test
    fun `执行未知工具返回 Failure 而不是抛异常`() {
        val reg = ToolRegistry().register(BuiltinTools.shell, dummyExecutor)
        val r = reg.execute(ToolCall("c1", "nonexistent", "{}"))
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).error.contains("未知工具"))
    }

    @Test
    fun `执行已知工具`() {
        val reg = ToolRegistry().register(BuiltinTools.shell, dummyExecutor)
        val r = reg.execute(ToolCall("c1", "run_command", """{"command":"sed","args":"x"}"""))
        assertTrue(r is ToolResult.Success)
        assertEquals("ok", (r as ToolResult.Success).output)
    }

    @Test
    fun `生成的 OpenAI tools JSON 是合法结构`() {
        val reg = ToolRegistry().register(BuiltinTools.shell, dummyExecutor)
        val json = reg.toOpenAiToolsJson()

        assertTrue(json.startsWith("["), "应以数组开头")
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains(""""type":"function""""))
        assertTrue(json.contains(""""name":"run_command""""))
        assertTrue(json.contains(""""parameters":{"type":"object""""))
        assertTrue(json.contains(""""required":["command","args"]"""))
    }

    @Test
    fun `多个工具用逗号分隔`() {
        val reg = ToolRegistry()
            .register(BuiltinTools.shell, dummyExecutor)
            .register(BuiltinTools.readFile, dummyExecutor)
        val json = reg.toOpenAiToolsJson()
        assertEquals(2, Regex(""""type":"function"""").findAll(json).count())
    }

    @Test
    fun `JSON 字符串转义正确`() {
        assertEquals("\"a\\\"b\"", ToolRegistry.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", ToolRegistry.jsonString("a\\b"))
        assertEquals("\"a\\nb\"", ToolRegistry.jsonString("a\nb"))
    }

    @Test
    fun `内置工具数量合理`() {
        // 工具太多模型反而不会用（见 BuiltinTools 注释）
        assertTrue(BuiltinTools.all().size in 3..8, "内置工具数量应在 3-8 之间")
    }

    @Test
    fun `内置工具名字都合法`() {
        BuiltinTools.all().forEach {
            assertTrue(
                it.name.matches(ToolRegistry.NAME_REGEX),
                "工具名不合法: ${it.name}",
            )
            assertTrue(it.description.isNotBlank(), "工具 ${it.name} 缺描述")
        }
    }
}
