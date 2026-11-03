package chatmobile.memory

/**
 * 记忆系统演示 —— 用真实数据跑一遍完整流程，直接看输出。
 *
 * 运行：gradle run
 */

private const val DAY = 86_400_000L

fun main() {
    println("=".repeat(70))
    println("ChatMobile 记忆系统演示")
    println("=".repeat(70))

    var seq = 0
    val bank = MemoryBank(
        store = InMemoryStore(),
        config = MemoryConfig(
            savedCapacity = 5,
            promoteThreshold = 3,
            tokensPerChar = 0.6,
        ),
        idGenerator = { "m${++seq}" },
    )

    val now = 0L   // 用 0 作起点，方便展示衰减

    // ---- 1. 用户显式要求记住的 ----
    println("\n【1】用户显式要求记住（saved 层，永不衰减）")
    bank.save("用户是程序员，主要写 Kotlin", sourceConversationId = "conv-1", now = now)
    bank.save("用户偏好简洁回答，不要客套话", sourceConversationId = "conv-1", now = now)
    bank.save("用户住在上海", sourceConversationId = "conv-2", now = now)
    bank.list().forEach { println("  · ${it.content}") }

    // ---- 2. 对话中自动抽取的近期记忆 ----
    println("\n【2】对话中自动抽取（recent 层，会衰减）")
    // ★ 用错开的时间戳 + 不同的命中次数，才能看出排序效果。
    //   （第一次演示时全用 now=0，结果权重全一样、排序退化 —— 已修，见 RankingTest）
    bank.recordRecent("昨天讨论了 Android 的 Compose 布局", sourceConversationId = "conv-3", now = now - 1 * DAY)
    bank.recordRecent("用户问过 OkHttp 的超时配置", sourceConversationId = "conv-4", now = now - 3 * DAY)
    bank.recordRecent("用户提到最近在学日语", sourceConversationId = "conv-3", now = now - 5 * DAY)
    // 让「OkHttp 超时」被反复命中，模拟它更相关
    repeat(4) { bank.recordRecent("用户问过 OkHttp 的超时配置", now = now - 1 * DAY) }
    bank.list().filter { it.kind == MemoryKind.RECENT }.forEach {
        println("  · 命中 ${it.hitCount} 次  ${it.content}")
    }

    // ---- 3. 组装上下文 ----
    println("\n【3】组装本轮上下文（预算 8000 token）")
    val composition = bank.composeContext(totalContextTokens = 8000, now = now)
    println("  用掉 ${composition.usedTokens} token " +
        "（saved ${composition.savedTokens} + recent ${composition.recentTokens}），" +
        "占预算 ${"%.1f".format(composition.budgetUsage * 100)}%")
    println("\n  ---- 实际注入 system prompt 的内容 ----")
    composition.text.lines().forEach { println("  | $it") }

    // ---- 4. 衰减演示 ----
    println("\n【4】时间流逝 30 天后，recent 层的权重变化（按权重排序）")
    val after30 = now + 30 * DAY
    bank.list()
        .filter { it.kind == MemoryKind.RECENT }
        .sortedByDescending { Decay.effectiveWeight(it, after30, halfLifeDays = 7.0) }
        .forEach { m ->
            val w = Decay.effectiveWeight(m, after30, halfLifeDays = 7.0)
            println("  · 权重 %.3f  命中 %2d 次  %s".format(w, m.hitCount, m.content))
        }
    println("  （saved 层权重恒为 1.000，不受时间影响）")

    // ---- 5. 重复出现 → 自动提升 ----
    println("\n【5】命中次数达到阈值(3) → 自动提升为 saved")
    val promoted = bank.autoPromote()
    if (promoted.isEmpty()) {
        println("  没有提升任何条目")
    } else {
        promoted.forEach { println("  ↑ 已提升：${it.content}（命中 ${it.hitCount} 次）") }
    }

    // ---- 6. 可解释性 ----
    println("\n【6】可解释性：这条记忆从哪来？（对应官方 \"Why this memory?\"）")
    val target = bank.list().first()
    bank.explain(target.id, now)?.let { exp ->
        println("  内容：${exp.entry.content}")
        println("  来源会话：${exp.entry.sourceConversationId}")
        println("  当前权重：%.3f".format(exp.weight))
        println("  已存在：%.1f 天".format(exp.ageDays))
        println("  被召回次数：${exp.entry.hitCount}")
    }

    // ---- 7. 删除与墓碑 ----
    println("\n【7】删除是墓碑，不是物理删除")
    val toDelete = bank.list().first()
    bank.delete(toDelete.id)
    println("  删除后默认列表可见数：${bank.list().size}")
    println("  含墓碑可见数：${bank.list(includeDeleted = true).size}")
    println("  能恢复吗：${bank.restore(toDelete.id)}")

    // ---- 8. 容量 ----
    println("\n【8】容量统计")
    val s = bank.stats()
    println("  saved ${s.savedCount}/${s.savedCapacity}，recent ${s.recentCount}")
    println("  是否接近上限：${s.isAlmostFull}")

    println("\n" + "=".repeat(70))
    println("演示结束")
    println("=".repeat(70))
}
