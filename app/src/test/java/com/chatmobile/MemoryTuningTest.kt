package com.chatmobile

import chatmobile.memory.MemoryConfig
import com.chatmobile.settings.MemoryTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆库参数覆盖层：稀疏编码、默认回落、越界保护。 */
class MemoryTuningTest {

    @Test
    fun `空覆盖等于默认配置`() {
        val cfg = MemoryTuning().applyTo(MemoryConfig())
        assertEquals(MemoryConfig(), cfg)
    }

    @Test
    fun `只改一项时其余保持默认`() {
        val cfg = MemoryTuning(extractEveryTurns = 3).applyTo(MemoryConfig())
        assertEquals(3, cfg.extractEveryTurns)
        assertEquals(MemoryConfig().extractWindow, cfg.extractWindow)
        assertEquals(MemoryConfig().maxEntryChars, cfg.maxEntryChars)
    }

    @Test
    fun `编解码往返`() {
        val t = MemoryTuning(
            extractEveryTurns = 4,
            extractWindow = 20,
            extractMaxOps = 8,
            maxEntryChars = 300,
            maxInjectedEntries = 60,
            recentCapacity = 500,
            savedCapacity = 150,
            promoteThreshold = 5,
            nearDuplicateThreshold = 0.65,
            decayHalfLifeDays = 14.0,
            savedBudgetRatio = 0.2,
            recentBudgetRatio = 0.15,
        )
        assertEquals(t, MemoryTuning.decode(t.encode()))
    }

    @Test
    fun `垃圾输入退化为默认`() {
        assertTrue(MemoryTuning.decode(null).isDefault)
        assertTrue(MemoryTuning.decode("").isDefault)
        assertTrue(MemoryTuning.decode("eet=abc;ew=;;").isDefault)
    }

    @Test
    fun `越界组合不抛异常而是退回基线`() {
        // 两个占比之和 1.4 > 1 ⇒ MemoryConfig 的 require 会拒绝
        val bad = MemoryTuning(savedBudgetRatio = 0.9, recentBudgetRatio = 0.5)
        val base = MemoryConfig()
        val cfg = bad.applyTo(base)
        // ★ 用 delta 比较：JUnit 的 assertEquals(Double, Double) 不接受无容差比较
        assertEquals(base.savedBudgetRatio, cfg.savedBudgetRatio, 1e-9)
        assertEquals(base.recentBudgetRatio, cfg.recentBudgetRatio, 1e-9)
    }

    @Test
    fun `isDefault 只在全空时为真`() {
        assertTrue(MemoryTuning().isDefault)
        assertTrue(!MemoryTuning(promoteThreshold = 1).isDefault)
    }
}
