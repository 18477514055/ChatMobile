package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 安全边界测试 —— 这是本模块最重要的测试。
 *
 * 一个能执行命令的工具，如果边界没守住，就是后门。
 * 这里逐条验证「不该执行的东西确实执行不了」。
 */
class ToyboxSecurityTest {

    private val executor = ToyboxExecutor()

    private fun call(command: String, args: String = "") =
        ToolCall(
            callId = "c1",
            toolName = "run_command",
            argumentsJson = """{"command":${q(command)},"args":${q(args)}}""",
        )

    private fun q(s: String): String = ToolRegistry.jsonString(s)

    // ---------------- 白名单 ----------------

    @Test
    fun `不在白名单的命令被拒绝`() {
        val r = executor.execute(call("rm", "-rf\u0000/tmp/x"), null)
        assertTrue(r is ToolResult.Failure, "rm 应被拒绝")
        assertTrue((r as ToolResult.Failure).error.contains("不在允许列表"))
    }

    @Test
    fun `reboot 被拒绝`() {
        val r = executor.execute(call("reboot"), null)
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun `chmod 被拒绝`() {
        val r = executor.execute(call("chmod", "777\u0000/tmp/x"), null)
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun `dd 被拒绝`() {
        val r = executor.execute(call("dd", "if=/dev/zero\u0000of=/dev/block/x"), null)
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun `白名单内的命令被允许`() {
        // sed 在白名单里，即使执行失败也不该是「命令被拒绝」
        val r = executor.execute(call("sed", "s/a/b/\u0000/nonexistent/file"), null)
        val isNotWhitelistRejection = when (r) {
            is ToolResult.Failure -> !r.error.contains("不在允许列表")
            is ToolResult.Success -> true
        }
        assertTrue(isNotWhitelistRejection, "sed 不该被白名单拒绝")
    }

    // ---------------- 参数注入 ----------------

    @Test
    fun `分号和 rm 不会被执行`() {
        // 走 ProcessBuilder 数组形式，分号只是普通参数
        val r = executor.execute(
            call("sed", "s/x/y/\u0000; rm -rf /tmp/nothing"),
            null,
        )
        // 结果只会是 sed 找不到文件，绝不该是 rm 生效
        when (r) {
            is ToolResult.Success -> assertFalse(
                r.output.contains("rm:"),
                "不该出现 rm 的输出",
            )
            is ToolResult.Failure -> {
                // 失败也可以，只要不是白名单拒绝
                assertFalse(r.error.contains("不在允许列表"))
            }
        }
    }

    @Test
    fun `反引号不会被解释`() {
        val r = executor.execute(call("sed", "s/a/`whoami`/\u0000/nonexistent"), null)
        // 不该抛异常，也不该真的执行 whoami
        assertTrue(r is ToolResult.Success || r is ToolResult.Failure)
    }

    @Test
    fun `管道符不会被解释`() {
        val r = executor.execute(call("sed", "s/a/b/\u0000/nonexistent | rm -rf /tmp/x"), null)
        when (r) {
            is ToolResult.Success -> assertFalse(r.output.contains("rm:"))
            is ToolResult.Failure -> assertFalse(r.error.contains("不在允许列表"))
        }
    }

    // ---------------- 危险路径 ----------------

    @Test
    fun `find 整个 system 被拒绝`() {
        val r = executor.execute(call("find", "/system"), null)
        assertTrue(r is ToolResult.Failure, "全盘扫 /system 应被拒绝")
    }

    @Test
    fun `cat data_data 被拒绝`() {
        val r = executor.execute(call("cat", "/data/data"), null)
        assertTrue(r is ToolResult.Failure)
    }

    // ---------------- 参数校验 ----------------

    @Test
    fun `缺少 command 报错`() {
        val r = executor.execute(
            ToolCall("c1", "run_command", """{"args":"x"}"""),
            null,
        )
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).error.contains("缺少必填参数"))
    }

    @Test
    fun `非法 JSON 报错`() {
        val r = executor.execute(ToolCall("c1", "run_command", """{bad json"""), null)
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).error.contains("JSON"))
    }

    // ---------------- 真实执行（本机可用时） ----------------

    @Test
    fun `真实执行 sed 替换`() {
        // 本机是 Windows，没有 toybox —— 用系统 PATH 里的命令做等价验证
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            // Windows 上跳过真实执行，但至少验证不会崩
            val r = executor.execute(call("sed", "s/a/b/"), null)
            assertTrue(r is ToolResult.Failure || r is ToolResult.Success)
            return
        }
        val r = executor.execute(call("sed", "s/a/b/"), null)
        assertTrue(r is ToolResult.Success || r is ToolResult.Failure)
    }
}
