package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

class DecayTest {

    @Test
    fun `刚创建的 recent 权重接近 1`() {
        val w = Decay.weight(createdAt = 1000L, now = 1000L, halfLifeDays = 7.0)
        assertEquals(1.0, w, 0.001)
    }

    @Test
    fun `经过一个半衰期后权重减半`() {
        val created = 0L
        val w = Decay.weight(createdAt = created, now = 7 * DAY, halfLifeDays = 7.0)
        assertEquals(0.5, w, 0.001)
    }

    @Test
    fun `经过两个半衰期后权重四分之一`() {
        val w = Decay.weight(createdAt = 0L, now = 14 * DAY, halfLifeDays = 7.0)
        assertEquals(0.25, w, 0.001)
    }

    @Test
    fun `永不归零`() {
        val w = Decay.weight(createdAt = 0L, now = 3650 * DAY, halfLifeDays = 7.0)
        assertTrue(w > 0.0, "衰减不应归零，实际 $w")
    }

    @Test
    fun `时钟回拨不产生大于1的权重`() {
        // now 早于 createdAt（设备时间被改回）
        val w = Decay.weight(createdAt = 10_000L, now = 5_000L, halfLifeDays = 7.0)
        assertEquals(1.0, w, 0.001)
    }

    @Test
    fun `saved 层永不衰减`() {
        val old = MemoryEntry(
            id = "1", content = "用户住在上海", kind = MemoryKind.SAVED,
            sourceConversationId = null, sourceMessageId = null,
            createdAt = 0L,
        )
        val w = Decay.effectiveWeight(old, now = 1000 * DAY, halfLifeDays = 7.0)
        assertEquals(1.0, w, 0.001, "saved 层不应随时间衰减")
    }

    @Test
    fun `经常被召回的 recent 权重更高`() {
        val base = MemoryEntry(
            id = "a", content = "喜欢喝咖啡", kind = MemoryKind.RECENT,
            sourceConversationId = null, sourceMessageId = null,
            createdAt = 0L, hitCount = 0,
        )
        val popular = base.copy(id = "b", hitCount = 10)

        val wBase = Decay.effectiveWeight(base, now = 7 * DAY, halfLifeDays = 7.0)
        val wPopular = Decay.effectiveWeight(popular, now = 7 * DAY, halfLifeDays = 7.0)

        assertTrue(wPopular > wBase, "高召回记忆应更重：$wPopular vs $wBase")
    }

    @Test
    fun `最近使用过的记忆比同样老但未使用的权重高`() {
        val stale = MemoryEntry(
            id = "s", content = "上周讨论过项目A", kind = MemoryKind.RECENT,
            sourceConversationId = null, sourceMessageId = null,
            createdAt = 0L,
        )
        val refreshed = stale.copy(id = "r", lastUsedAt = 6 * DAY)

        val wStale = Decay.effectiveWeight(stale, now = 7 * DAY, halfLifeDays = 7.0)
        val wRefreshed = Decay.effectiveWeight(refreshed, now = 7 * DAY, halfLifeDays = 7.0)

        assertTrue(wRefreshed > wStale, "最近用过的应更重：$wRefreshed vs $wStale")
    }
}
