package com.chatmobile.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式缓冲写入。
 *
 * ## 这组测试要证明的事
 *
 * 1. **内容正确**：不管攒批怎么攒，最后库里就是完整正文，一个字不差、不重复。
 * 2. **真的在攒批**：1000 条 delta 不能产生 1000 次写库（否则缓冲等于没做）。
 * 3. **三条阈值都真的会触发**：条数 / 字符 / 超时。
 * 4. **收尾顺序正确**：先 flush 再改状态（否则会留下"已完成但正文被截断"的行）。
 * 5. **写失败不丢内容**：内存里还在，重试是幂等的。
 */
class StreamingMessageWriterTest : RoomTestBase() {

    private suspend fun newStreamingMessage(id: String = "m1"): StreamingMessageWriter {
        conversations.insert(conversation(id = "c-1"))
        messages.insert(
            message(id = id, role = MessageRole.ASSISTANT, content = "", status = MessageStatus.STREAMING),
        )
        return StreamingMessageWriter(messages, id)
    }

    // ---------------------------------------------------------------
    // 内容正确性
    // ---------------------------------------------------------------

    @Test
    fun `少量 delta 不足阈值时不写库，flush 后才落盘`() = runBlocking {
        val writer = newStreamingMessage()

        writer.append("你")
        writer.append("好")

        // ★ 还没到 32 条 / 1200 字符，库里的 content 应该还是空的
        assertEquals("", messages.findById("m1")!!.content)
        // 但内存里已经有了（UI 可以直接拿 writer.text 渲染）
        assertEquals("你好", writer.text)

        writer.flush()
        assertEquals("你好", messages.findById("m1")!!.content)
        assertEquals(1, writer.flushCount)
    }

    @Test
    fun `攒满 32 条 delta 会自动触发一次写库`() = runBlocking {
        val writer = newStreamingMessage()

        repeat(31) { writer.append("a") }
        assertEquals("31 条还没到阈值，不该写库", 0, writer.flushCount)

        writer.append("a")   // 第 32 条
        assertEquals("第 32 条应触发写库", 1, writer.flushCount)
        assertEquals("a".repeat(32), messages.findById("m1")!!.content)
    }

    @Test
    fun `攒满 1200 字符会触发写库（不靠条数）`() = runBlocking {
        val writer = newStreamingMessage()

        // 一个 1200 字符的大 delta —— 条数只有 1，但字符阈值越过了
        writer.append("x".repeat(1_200))

        assertEquals("字符阈值应触发写库", 1, writer.flushCount)
        assertEquals("x".repeat(1_200), messages.findById("m1")!!.content)
    }

    @Test
    fun `字符阈值优先于条数：大 delta 少量次也能及时落盘`() = runBlocking {
        val writer = newStreamingMessage()

        // 每次 500 字符，3 次就 1500 > 1200，但条数才 3
        repeat(3) { writer.append("y".repeat(500)) }

        assertTrue("3 条大 delta 就该落盘了", writer.flushCount >= 1)
        assertEquals("y".repeat(1_500), writer.text)
    }

    @Test
    fun `1000 条 delta 的写库次数远小于 1000 —— 这就是缓冲的意义`() = runBlocking {
        val writer = newStreamingMessage()

        val deltas = 1_000
        repeat(deltas) { writer.append("字") }
        writer.flush()

        // 每次 32 条触发一次 → 约 31 次（最后再补一次 flush）
        assertTrue(
            "写库次数应与 delta 数脱钩：期望 <=40 次，实际 ${writer.flushCount}",
            writer.flushCount <= 40,
        )
        // 但一个字符都不能少
        assertEquals("字".repeat(deltas), messages.findById("m1")!!.content)
    }

    @Test
    fun `任意切分的 delta 序列最终内容都正确（模拟真实 token 流）`() = runBlocking {
        val writer = newStreamingMessage()
        val expected = StringBuilder()

        // 模拟真实流：delta 长度从 1 到 8 个字符不规则跳动
        var i = 0
        val sizes = intArrayOf(1, 3, 2, 8, 1, 1, 5, 2, 7, 1, 4, 2, 3)
        var round = 0
        while (i < 5_000) {
            val n = sizes[round % sizes.size]
            val piece = ("片段$round-").repeat(n).take(n * 6)
            writer.append(piece)
            expected.append(piece)
            i += piece.length
            round++
        }
        writer.flush()

        assertEquals(expected.toString(), messages.findById("m1")!!.content)
        assertEquals(expected.toString(), writer.text)
    }

    @Test
    fun `空 delta 被忽略，不占阈值也不产生写入`() = runBlocking {
        val writer = newStreamingMessage()

        repeat(100) { writer.append("") }
        writer.flush()

        assertEquals(0, writer.flushCount)
        assertEquals("", messages.findById("m1")!!.content)
    }

    // ---------------------------------------------------------------
    // 幂等性（绝对写的核心收益）
    // ---------------------------------------------------------------

    @Test
    fun `重复 flush 是幂等的，不会把内容写两遍`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("abc")

        writer.flush()
        writer.flush()
        writer.flush()

        // ★ 如果这里改成了"增量追加"，三次 flush 会得到 "abcabcabc"
        assertEquals("abc", messages.findById("m1")!!.content)
        // 后两次没有改动，应该是零成本空操作
        assertEquals(1, writer.flushCount)
    }

    @Test
    fun `flush 后继续 append，第二次 flush 写的是完整快照`() = runBlocking {
        val writer = newStreamingMessage()

        writer.append("第一段")
        writer.flush()
        assertEquals("第一段", messages.findById("m1")!!.content)

        writer.append("第二段")
        writer.flush()

        // 不是 "第二段"，是 "第一段第二段" —— 绝对写
        assertEquals("第一段第二段", messages.findById("m1")!!.content)
    }

    // ---------------------------------------------------------------
    // 推理内容
    // ---------------------------------------------------------------

    @Test
    fun `正文与推理各自累积，共用一次写库`() = runBlocking {
        val writer = newStreamingMessage()

        writer.appendReasoning("先想")
        writer.append("答案")
        writer.appendReasoning("再想")
        writer.flush()

        val m = messages.findById("m1")!!
        assertEquals("先想再想", m.reasoning)
        assertEquals("答案", m.content)
        // 四条 delta 只产生一次写库
        assertEquals(1, writer.flushCount)
    }

    @Test
    fun `没有推理时 reasoning 落库为 null 而不是空串`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("只有正文")
        writer.flush()

        assertNull("空推理必须存 null，否则界面上会冒出一个空的思考折叠块", messages.findById("m1")!!.reasoning)
        assertNull(writer.reasoningText)
    }

    @Test
    fun `resetContent 会替换而不是追加（重新生成用）`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("旧的回答")
        writer.flush()

        writer.resetContent("", null)
        writer.append("新的回答")
        writer.flush()

        assertEquals("新的回答", messages.findById("m1")!!.content)
    }

    // ---------------------------------------------------------------
    // 超时 flush
    // ---------------------------------------------------------------

    @Test
    fun `tick 在空闲超过 maxDelayMs 后触发 flush`() = runBlocking {
        val policy = StreamingMessageWriter.BufferPolicy(maxDelayMs = 100L, pollIntervalMs = 20L)
        conversations.insert(conversation(id = "c-1"))
        messages.insert(message(id = "m1", role = MessageRole.ASSISTANT, status = MessageStatus.STREAMING))
        val writer = StreamingMessageWriter(messages, "m1", policy)

        writer.append("差一点就写完")

        // 未到时间：前 4 次 tick 不该写（100ms / 20ms = 5 次才够）
        repeat(4) {
            val wrote = writer.tick()
            assertTrue("第 ${it + 1} 次 tick 不该写库", !wrote)
        }
        assertEquals(0, writer.flushCount)

        // 第 5 次达到 idleTicksBeforeTimeout
        assertTrue("第 5 次 tick 应触发写库", writer.tick())
        assertEquals("差一点就写完", messages.findById("m1")!!.content)
    }

    @Test
    fun `tick 在没有改动时是零成本空操作`() = runBlocking {
        val writer = newStreamingMessage()

        repeat(10) { assertTrue("没有 dirty 时 tick 不该写", !writer.tick()) }
        assertEquals(0, writer.flushCount)
    }

    // ---------------------------------------------------------------
    // 收尾
    // ---------------------------------------------------------------

    @Test
    fun `finish 先 flush 再标 done，正文完整`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("完整答案")
        // 注意：这里没有手动 flush

        writer.finish(reasoningMs = 1_234L)

        val m = messages.findById("m1")!!
        assertEquals(MessageStatus.DONE, m.statusEnum)
        assertEquals("完整答案", m.content)
        assertEquals(1_234L, m.reasoningMs)
    }

    @Test
    fun `stop 保留已收到的内容并标 stopped`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("说到一半")

        writer.stop()

        val m = messages.findById("m1")!!
        assertEquals(MessageStatus.STOPPED, m.statusEnum)
        assertEquals("说到一半", m.content)
        assertNull("正常停止不算错误", m.errorMessage)
    }

    @Test
    fun `fail 把错误信息单独存，绝不用它覆盖正文`() = runBlocking {
        val writer = newStreamingMessage()
        writer.append("已经收到的部分")

        writer.fail("HTTP 429: rate limited")

        val m = messages.findById("m1")!!
        assertEquals(MessageStatus.ERROR, m.statusEnum)
        assertEquals("HTTP 429: rate limited", m.errorMessage)
        // ★ 这条最关键：出错时正文必须保留，不能被错误信息顶掉
        assertEquals("已经收到的部分", m.content)
    }

    @Test
    fun `写到不存在的消息上不抛异常，只是影响 0 行`() = runBlocking {
        // 这条覆盖"进程恢复时消息已被删掉"的竞态
        val writer = StreamingMessageWriter(messages, "不存在的消息")
        writer.append("内容")

        val rows = writer.flush()

        assertEquals(0, rows)
        assertEquals(0, writer.flushCount)
    }

    // ---------------------------------------------------------------
    // 阈值策略
    // ---------------------------------------------------------------

    @Test
    fun `BufferPolicy 的非法值在构造时就失败，而不是运行时才炸`() {
        val cases = listOf<() -> Unit>(
            { StreamingMessageWriter.BufferPolicy(maxPendingDeltas = 0) },
            { StreamingMessageWriter.BufferPolicy(maxPendingChars = 0) },
            { StreamingMessageWriter.BufferPolicy(maxDelayMs = 0) },
            { StreamingMessageWriter.BufferPolicy(pollIntervalMs = 0) },
        )
        cases.forEach { build ->
            val failed = runCatching(build).isFailure
            assertTrue("非法策略应在构造时 require 失败", failed)
        }
    }

    @Test
    fun `idleTicksBeforeTimeout 由 maxDelay 与 pollInterval 推导`() {
        assertEquals(6L, StreamingMessageWriter.BufferPolicy(maxDelayMs = 120, pollIntervalMs = 20).idleTicksBeforeTimeout)
        // 除不尽时向上取整，保证不会早于 maxDelay
        assertEquals(7L, StreamingMessageWriter.BufferPolicy(maxDelayMs = 121, pollIntervalMs = 20).idleTicksBeforeTimeout)
        // 极端：pollInterval 比 maxDelay 还大 → 至少 1，不会变成 0（0 会让 tick 永远不触发）
        assertEquals(1L, StreamingMessageWriter.BufferPolicy(maxDelayMs = 10, pollIntervalMs = 100).idleTicksBeforeTimeout)
    }
}
