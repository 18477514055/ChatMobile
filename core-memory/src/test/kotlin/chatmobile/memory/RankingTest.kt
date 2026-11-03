package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY_MS = 86_400_000L

/**
 * 排序健壮性测试。
 *
 * 起因：第一次跑演示时，所有 recent 记忆权重完全相同（0.058），
 * 排序退化成了插入顺序 —— 这意味着「越相关越靠前」实际上没有生效。
 *
 * 根因：同一批记忆 createdAt 相同，而权重公式只看时间。
 * 修复：给权重加"使用新鲜度"和"命中次数"的稳定 tiebreaker。
 */
class RankingTest {

    private val config = MemoryConfig(
        savedBudgetRatio = 0.3,
        recentBudgetRatio = 0.7,
        decayHalfLifeDays = 7.0,
        tokensPerChar = 1.0,
    )
    private val composer = MemoryComposer(config)

    private fun recent(
        id: String,
        createdAt: Long,
        lastUsedAt: Long? = null,
        hitCount: Int = 0,
    ) = MemoryEntry(
        id = id, content = "内容-$id", kind = MemoryKind.RECENT,
        sourceConversationId = null, sourceMessageId = null,
        createdAt = createdAt, lastUsedAt = lastUsedAt, hitCount = hitCount,
    )

    @Test
    fun `相同创建时间下 按命中次数排序`() {
        val now = 0L
        val memories = listOf(
            recent("low", createdAt = 0L, hitCount = 1),
            recent("high", createdAt = 0L, hitCount = 10),
            recent("mid", createdAt = 0L, hitCount = 5),
        )

        val r = composer.compose(memories, totalContextTokens = 10_000, now = now)
        val order = r.usedRecent.map { it.id }

        assertEquals(listOf("high", "mid", "low"), order,
            "命中次数高的应排在前面，实际顺序 $order")
    }

    @Test
    fun `相同创建时间与命中次数下 按最后使用时间排序`() {
        val now = 10 * DAY_MS
        val memories = listOf(
            recent("old-use", createdAt = 0L, lastUsedAt = 0L, hitCount = 1),
            recent("new-use", createdAt = 0L, lastUsedAt = 9 * DAY_MS, hitCount = 1),
            recent("mid-use", createdAt = 0L, lastUsedAt = 5 * DAY_MS, hitCount = 1),
        )

        val r = composer.compose(memories, totalContextTokens = 10_000, now = now)
        val order = r.usedRecent.map { it.id }

        assertEquals(listOf("new-use", "mid-use", "old-use"), order,
            "最近使用过的应排在前面，实际顺序 $order")
    }

    @Test
    fun `完全相同的记忆也有稳定顺序`() {
        // 这是之前的 bug：三者权重完全相同，顺序不确定
        val now = 0L
        val memories = listOf(
            recent("a", createdAt = 0L),
            recent("b", createdAt = 0L),
            recent("c", createdAt = 0L),
        )

        val r1 = composer.compose(memories, 10_000, now).usedRecent.map { it.id }
        val r2 = composer.compose(memories.reversed(), 10_000, now).usedRecent.map { it.id }

        assertEquals(r1, r2, "输入顺序不同不应影响输出顺序（需要确定性）")
    }

    @Test
    fun `权重不应该全都一样`() {
        // 复现演示里的场景
        val now = 30 * DAY_MS
        val memories = listOf(
            recent("a", createdAt = 0L, hitCount = 1),
            recent("b", createdAt = 0L, hitCount = 5),
            recent("c", createdAt = 0L, hitCount = 10),
        )
        val weights = memories.map {
            Decay.effectiveWeight(it, now, config.decayHalfLifeDays)
        }
        assertTrue(weights.toSet().size > 1,
            "权重应能区分不同记忆，实际全是 $weights")
    }
}
