package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 命令来源区分测试。
 *
 * 起因（2026-11-02 真机实测）：
 *   在 Android 14 真机上跑 `toybox awk ...` 得到
 *   "toybox: Unknown command awk (see "toybox --help")"
 *
 *   根因：`/system/bin/awk` 是**独立二进制**，不是 `-> toybox` 软链。
 *   而之前的 `ls /system/bin/awk` 能看到文件存在，掩盖了这一点。
 *
 *   这就是「文件存在 ≠ 能用」的典型例子（见 LESSONS）。
 */
class StandaloneBinaryTest {

    @Test
    fun `awk 被标记为独立二进制`() {
        assertTrue(
            "awk" in ToyboxExecutor.STANDALONE_BINARIES,
            "awk 必须是独立二进制，否则 toybox awk 会报 Unknown command",
        )
    }

    @Test
    fun `sed 是 toybox applet 不是独立二进制`() {
        assertTrue("sed" !in ToyboxExecutor.STANDALONE_BINARIES)
        assertTrue("sed" in ToyboxExecutor.DEFAULT_ALLOWED)
    }

    @Test
    fun `独立二进制也必须在白名单里`() {
        // 否则会被白名单拦掉，永远执行不到
        ToyboxExecutor.STANDALONE_BINARIES.forEach {
            assertTrue(
                it in ToyboxExecutor.DEFAULT_ALLOWED,
                "$it 是独立二进制但不在白名单，会被误拒",
            )
        }
    }

    @Test
    fun `rev 和 join 不在白名单因为真机不存在`() {
        // Android 14 实测：/system/bin/rev 与 /system/bin/join 都不存在
        assertTrue("rev" !in ToyboxExecutor.DEFAULT_ALLOWED, "rev 在 Android 14 上不存在")
        assertTrue("join" !in ToyboxExecutor.DEFAULT_ALLOWED, "join 在 Android 14 上不存在")
    }

    @Test
    fun `白名单所有命令都是常见文本工具`() {
        val allowed = ToyboxExecutor.DEFAULT_ALLOWED
        // 破坏性命令必须不在白名单
        listOf("rm", "mv", "chmod", "chown", "dd", "mkfs", "mount", "reboot", "sh", "bash")
            .forEach {
                assertTrue(it !in allowed, "$it 是危险命令，不该在白名单")
            }
    }
}
