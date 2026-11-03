package com.chatmobile

import chatmobile.memory.CompressionConfig
import chatmobile.memory.Compressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩触发判据的契约测试。
 *
 * ★ 为什么这些纯函数值得测：压缩的**触发时机**错了，症状分别是：
 *   - 触发太早 → 每轮都压，多烧 API 调用，用户会觉得"变慢变贵"
 *   - 触发太晚 → 撞上下文上限，请求直接失败（**硬失败**，不是降级）
 *   这两种都不会崩，只会"用得别扭"，所以必须有测试钉死边界。
 */
class CompressorTest {

    private fun compressor() = Compressor(CompressionConfig(keepRecentMessages = 8))

    @Test
    fun `水位不到阈值不压`() {
        val c = compressor()
        // 69% —— 差一点点
        assertFalse(c.shouldCompress(usedTokens = 6_900, budgetTokens = 10_000, messageCount = 100))
    }

    @Test
    fun `水位到阈值且有内容就压`() {
        val c = compressor()
        // 70% 正好到线
        assertTrue(c.shouldCompress(usedTokens = 7_000, budgetTokens = 10_000, messageCount = 100))
    }

    @Test
    fun `没有可压内容时不压`() {
        val c = compressor()
        // 水位够了，但只有 8 条 = 全是保留尾巴，压了等于把自己唯一的内容也压了
        assertFalse(c.shouldCompress(usedTokens = 9_000, budgetTokens = 10_000, messageCount = 8))
        // 连尾巴都不够
        assertFalse(c.shouldCompress(usedTokens = 9_000, budgetTokens = 10_000, messageCount = 3))
    }

    @Test
    fun `预算为 0 时不崩也不压`() {
        // 防御：调用方算错预算不该让它抛异常，只是别压
        assertFalse(compressor().shouldCompress(1_000, 0, 100))
    }

    @Test
    fun `要压的条数等于总数减保留尾巴`() {
        val c = compressor()
        assertEquals(12, c.spanToCompress(20))   // 20 - 8
        assertEquals(0, c.spanToCompress(8))     // 正好等于尾巴
        assertEquals(0, c.spanToCompress(5))     // 比尾巴还少
    }

    @Test
    fun `缺口提示在有丢弃时才出现`() {
        val c = compressor()
        // ★ 没丢弃就不能有提示 —— 否则模型会以为自己漏看了什么
        assertEquals("", c.buildGapNotice(0))
        assertEquals("", c.buildGapNotice(-1))

        val notice = c.buildGapNotice(5)
        assertTrue(notice.contains("5"))
        // ★ 必须明令"不要猜"，这是照 OpenClaw 的做法：
        //   静默截断是 Codex #13386 那个 bug 的形态
        assertTrue(notice.contains("不要猜"))
    }

    @Test
    fun `非法配置在构造时就抛错`() {
        // 0 或 1 都是无意义的触发线
        assertThrows { CompressionConfig(triggerRatio = 0.0) }
        assertThrows { CompressionConfig(triggerRatio = 1.0) }
        assertThrows { CompressionConfig(keepRecentMessages = -1) }
        assertThrows { CompressionConfig(summarizerInputBudgetTokens = 0) }
        assertThrows { CompressionConfig(summaryMaxTokens = 0) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("期望抛 IllegalArgumentException，但没有")
    }
}
