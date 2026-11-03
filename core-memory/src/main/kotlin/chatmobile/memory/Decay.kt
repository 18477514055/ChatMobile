package chatmobile.memory

import kotlin.math.ln
import kotlin.math.pow

/**
 * recent 层的衰减计算。
 *
 * 设计依据：官方明确说近期记忆"may forget things over time"，
 * 但没说怎么衰减 —— 这里用**指数衰减**，因为它满足三条要求：
 *   1. 单调递减（越久越不重要）
 *   2. 永不归零（久远的记忆不会突然消失，只是权重低）
 *   3. 半衰期可解释（7 天后权重减半，用户能理解）
 *
 * 不用线性衰减：线性衰减到 0 之后，所有老记忆会突然一起被丢弃。
 */
object Decay {

    /**
     * 计算某条记忆在 [now] 时刻的权重。
     *
     * @return (0, 1] 区间的权重；刚创建时为 1.0
     */
    fun weight(
        createdAt: Long,
        now: Long,
        halfLifeDays: Double,
    ): Double {
        require(halfLifeDays > 0) { "半衰期必须为正，当前 $halfLifeDays" }

        // 未来时间（时钟回拨）当作刚创建，不产生 >1 的权重
        val ageMs = (now - createdAt).coerceAtLeast(0L)
        val ageDays = ageMs.toDouble() / MS_PER_DAY

        // w = 0.5 ^ (age / halfLife)
        return 0.5.pow(ageDays / halfLifeDays)
    }

    /**
     * 带"召回加成"的权重。
     *
     * 一条老记忆如果经常被用到，说明它重要 —— 应该比同样老但没人用的权重高。
     * 用对数加成，避免高频记忆垄断预算。
     */
    fun effectiveWeight(
        entry: MemoryEntry,
        now: Long,
        halfLifeDays: Double,
    ): Double {
        if (entry.kind == MemoryKind.SAVED) {
            // saved 层永不衰减
            return 1.0
        }

        val base = weight(entry.createdAt, now, halfLifeDays)

        // 最近被用过的话，从"最后使用时间"算衰减，而不是创建时间
        val freshness = entry.lastUsedAt?.let { weight(it, now, halfLifeDays) } ?: 0.0
        val recency = maxOf(base, freshness)

        // 召回次数加成：ln(1+n)/ln(1+10) 归一到 [0,1]，最多 +30%
        val boost = 1.0 + 0.3 * (ln(1.0 + entry.hitCount) / ln(11.0)).coerceIn(0.0, 1.0)

        return (recency * boost).coerceIn(0.0, 1.0)
    }

    private const val MS_PER_DAY = 86_400_000.0
}
