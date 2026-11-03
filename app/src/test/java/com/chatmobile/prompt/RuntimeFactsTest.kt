package com.chatmobile.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * 运行时事实的**渲染契约**。
 *
 * ★ 为什么值得单测：这段文本每轮都会被拼进请求，它是**纯函数**（不碰 Context、
 *   不自己取时钟），所以可以精确断言。真机上要发现"少了一行时区"只能靠肉眼读日志。
 *
 * ★ 最要紧的一条：**没有位置时必须写 unavailable，而不是省略这一行。**
 *   省略会让模型拿时区反推城市（"Asia/Shanghai ⇒ 用户在北京"）——
 *   那是编造，而提示词恰恰要求它别编。
 */
class RuntimeFactsTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-02 18:35 UTC == 2026-11-03 02:35 +08:00 */
    private val epoch = 1_793_644_500_000L

    @Test
    fun `渲染出时间时区语言三行`() {
        val text = RuntimeFacts.render(epoch, shanghai, Locale.SIMPLIFIED_CHINESE, null)
        assertTrue(text, text.contains("2026-11-03 02:35"))
        assertTrue(text, text.contains("Asia/Shanghai"))
        assertTrue(text, text.contains("UTC+08:00"))
        assertTrue(text, text.contains("zh-CN"))
    }

    @Test
    fun `没有位置时必须明说不确定`() {
        val text = RuntimeFacts.render(epoch, shanghai, Locale.SIMPLIFIED_CHINESE, null)
        assertTrue(
            "★ 省略这一行会让模型拿时区反推城市 —— 那是编造",
            text.contains("Location: unavailable"),
        )
        assertTrue("必须明令禁止猜", text.contains("do not guess it"))
    }

    @Test
    fun `有位置时渲染经纬度与精度`() {
        val text = RuntimeFacts.render(
            epoch,
            shanghai,
            Locale.SIMPLIFIED_CHINESE,
            RuntimeFacts.Location(39.9042, 116.4074, 1200f),
        )
        assertTrue(text, text.contains("39.9042°N"))
        assertTrue(text, text.contains("116.4074°E"))
        assertTrue(text, text.contains("±1200 m"))
        assertFalse(text, text.contains("unavailable"))
    }

    @Test
    fun `南半球西经的符号是对的`() {
        val text = RuntimeFacts.render(
            epoch,
            ZoneId.of("America/Sao_Paulo"),
            Locale.US,
            RuntimeFacts.Location(-23.5505, -46.6333, null),
        )
        assertTrue(text, text.contains("23.5505°S"))
        assertTrue(text, text.contains("46.6333°W"))
    }

    @Test
    fun `负时区偏移渲染正确`() {
        val text = RuntimeFacts.render(epoch, ZoneId.of("America/New_York"), Locale.US, null)
        assertTrue(text, text.contains("UTC-05:00"))
    }

    @Test
    fun `整小时偏移补两位`() {
        val text = RuntimeFacts.render(epoch, ZoneId.of("UTC"), Locale.US, null)
        assertTrue(text, text.contains("UTC+00:00"))
    }

    @Test
    fun `半小时时区也能表达`() {
        val text = RuntimeFacts.render(epoch, ZoneId.of("Asia/Kolkata"), Locale.US, null)
        assertTrue(text, text.contains("UTC+05:30"))
    }

    @Test
    fun `时间随传入的时刻变化而不是取当前时钟`() {
        // 同一函数、不同入参 ⇒ 不同输出。若它内部取了系统时钟，这条会失败。
        val a = RuntimeFacts.render(epoch, shanghai, Locale.US, null)
        val b = RuntimeFacts.render(epoch + 3_600_000L, shanghai, Locale.US, null)
        assertTrue("渲染必须是纯函数：时间来自入参，不是系统时钟", a != b)
        assertTrue(a, a.contains("02:35"))
        assertTrue(b, b.contains("03:35"))
    }

    @Test
    fun `首行说明这些事实是权威的`() {
        val text = RuntimeFacts.render(epoch, shanghai, Locale.US, null)
        val firstLine = text.lineSequence().first()
        assertTrue(firstLine, firstLine.contains("Runtime facts"))
        assertTrue(
            "必须告诉模型这些是客户端给的事实，否则它会把它们当成用户随口一说",
            firstLine.contains("authoritative"),
        )
    }

    @Test
    fun `渲染结果不含模板残留`() {
        val text = RuntimeFacts.render(epoch, shanghai, Locale.US, null)
        assertFalse("不许残留 Kotlin 模板符号", text.contains("\${"))
        assertEquals("不该有空行结尾", text.trimEnd(), text)
    }
}
