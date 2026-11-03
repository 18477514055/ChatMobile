package com.chatmobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **思考过程折叠的纯逻辑**测试。
 *
 * ## ★ 这些测试配合 ChatHostContractTest 里那条 `思考折叠状态不能被流式文本重置`
 *
 * 那条测的是"`remember` 的 key 不能用流式文本"，这条测的是"裁切逻辑本身对不对"。
 * 两者缺一不可：
 * - 只有裁切对、key 错 ⇒ 用户点了收起，下一帧又被重置（**这正是用户报的 bug**）
 * - 只有 key 对、裁切错 ⇒ 收起后仍显示半截内容，或「还有 N 行」算错
 */
class ReasoningDisplayTest {

    private fun textOf(lines: Int): String =
        (1..lines).joinToString("\n") { "第 $it 行推理内容" }

    // ================================================================
    // 四档语义
    // ================================================================

    @Test
    fun `收起档不返回任何内容`() {
        val (shown, clipped) = clipReasoning(textOf(20), ReasoningDisplay.Collapsed)
        assertEquals("", shown)
        assertFalse(clipped)
    }

    @Test
    fun `简洁档只给前 N 行并标记被裁`() {
        val (shown, clipped) = clipReasoning(textOf(20), ReasoningDisplay.Compact)
        assertTrue("超过行数上限时必须标记被裁", clipped)
        assertEquals(
            ReasoningDisplay.COMPACT_LINES,
            shown.lines().size,
        )
    }

    @Test
    fun `简洁档内容不足上限时不标记被裁`() {
        val (shown, clipped) = clipReasoning(textOf(3), ReasoningDisplay.Compact)
        assertFalse("没超上限就不该说被裁", clipped)
        assertEquals(3, shown.lines().size)
    }

    @Test
    fun `标准和完全展开都给全文`() {
        val full = textOf(20)
        for (d in listOf(ReasoningDisplay.Standard, ReasoningDisplay.Expanded)) {
            val (shown, clipped) = clipReasoning(full, d)
            assertEquals("$d 应给全文", full, shown)
            assertFalse("$d 不该标记被裁", clipped)
        }
    }

    @Test
    fun `空内容不会崩也不会说被裁`() {
        for (d in ReasoningDisplay.entries) {
            val (shown, clipped) = clipReasoning("", d)
            assertEquals("", shown)
            assertFalse(clipped)
        }
    }

    // ================================================================
    // 「还有 N 行」必须算对
    // ================================================================

    @Test
    fun `剩余行数算对`() {
        assertEquals(
            14,
            remainingReasoningLines(textOf(20), ReasoningDisplay.Compact),
        )
    }

    @Test
    fun `未超上限时剩余为 0`() {
        assertEquals(0, remainingReasoningLines(textOf(3), ReasoningDisplay.Compact))
    }

    @Test
    fun `非简洁档不显示剩余行`() {
        for (d in listOf(
            ReasoningDisplay.Collapsed,
            ReasoningDisplay.Standard,
            ReasoningDisplay.Expanded,
        )) {
            assertEquals("$d 不该有剩余行提示", 0, remainingReasoningLines(textOf(50), d))
        }
    }

    // ================================================================
    // 持久化解析
    // ================================================================

    @Test
    fun `非法值回退到默认档`() {
        assertEquals(ReasoningDisplay.DEFAULT, ReasoningDisplay.fromName(null))
        assertEquals(ReasoningDisplay.DEFAULT, ReasoningDisplay.fromName(""))
        assertEquals(ReasoningDisplay.DEFAULT, ReasoningDisplay.fromName("不存在"))
    }

    @Test
    fun `每一档都能被解析回来`() {
        for (d in ReasoningDisplay.entries) {
            assertEquals(d, ReasoningDisplay.fromName(d.name))
        }
    }

    @Test
    fun `四档都有中文标签与说明`() {
        for (d in ReasoningDisplay.entries) {
            assertTrue("${d.name} 缺标签", d.label.isNotBlank())
            assertTrue("${d.name} 缺说明", d.description.isNotBlank())
        }
    }

    // ================================================================
    // ★★★ 档位合成（这是「收不回去」第三次报的真因所在）
    // ================================================================

    @Test
    fun `手动收起后流式追加不得把它改回展开`() {
        // 用户点了收起（override=false），此后无论来多少 token（isStreaming=true）、
        // 无论全局档位是什么，都必须保持收起。
        for (global in ReasoningDisplay.entries) {
            val eff = effectiveReasoningDisplay(
                display = global,
                autoCollapse = true,
                isStreaming = true,
                manualOverride = false,
            )
            assertEquals("手动收起被 $global 的全局档位覆盖了", ReasoningDisplay.Collapsed, eff)
        }
    }

    @Test
    fun `手动展开优先于自动收起`() {
        val eff = effectiveReasoningDisplay(
            display = ReasoningDisplay.Standard,
            autoCollapse = true,
            isStreaming = false,
            manualOverride = true,
        )
        assertEquals(
            "用户手动点开的，回答结束后不许自动收走",
            ReasoningDisplay.Expanded,
            eff,
        )
    }

    @Test
    fun `没手动点时按全局档位`() {
        for (global in ReasoningDisplay.entries) {
            assertEquals(
                "$global 在流式期间应原样生效",
                global,
                effectiveReasoningDisplay(
                    display = global,
                    autoCollapse = true,
                    isStreaming = true,
                    manualOverride = null,
                ),
            )
        }
    }

    @Test
    fun `回答结束后开启自动收起则收成一行`() {
        assertEquals(
            ReasoningDisplay.Collapsed,
            effectiveReasoningDisplay(
                display = ReasoningDisplay.Expanded,
                autoCollapse = true,
                isStreaming = false,
                manualOverride = null,
            ),
        )
    }

    @Test
    fun `回答结束后关掉自动收起则维持全局档位`() {
        for (global in ReasoningDisplay.entries) {
            assertEquals(
                global,
                effectiveReasoningDisplay(
                    display = global,
                    autoCollapse = false,
                    isStreaming = false,
                    manualOverride = null,
                ),
            )
        }
    }

    @Test
    fun `自动收起不得影响正在流式的那一条`() {
        val eff = effectiveReasoningDisplay(
            display = ReasoningDisplay.Standard,
            autoCollapse = true,
            isStreaming = true,
            manualOverride = null,
        )
        assertEquals(
            "还在生成时收起来，用户就看不到它正在想什么了",
            ReasoningDisplay.Standard,
            eff,
        )
    }

    // ================================================================
    // 自动收起时机
    // ================================================================

    @Test
    fun `收起时机的非法值回退到回答结束后`() {
        assertEquals(ReasoningAutoCollapse.AfterAnswer, ReasoningAutoCollapse.fromName(null))
        assertEquals(ReasoningAutoCollapse.AfterAnswer, ReasoningAutoCollapse.fromName(""))
        assertEquals(ReasoningAutoCollapse.AfterAnswer, ReasoningAutoCollapse.fromName("乱写"))
    }

    @Test
    fun `收起时机每一档都能解析回来且都有标签`() {
        for (m in ReasoningAutoCollapse.entries) {
            assertEquals(m, ReasoningAutoCollapse.fromName(m.name))
            assertTrue("${m.name} 缺标签", m.label.isNotBlank())
        }
    }
}
