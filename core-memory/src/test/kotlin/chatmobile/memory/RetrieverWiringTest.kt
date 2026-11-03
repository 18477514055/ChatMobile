package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 检索后端必须**真的被调用** —— 这一组测的是"连接存在"，不是"算法正确"。
 *
 * 背景：真机上用户实测到「远程调用」查不到「通过客户端调用 API」，
 * 根因不是向量不准，而是 `search_memory` 写着 `content.contains(q)`、
 * 注入路径根本不过检索器。所以这里钉住三件事：
 *   1. `MemoryBank.search` 走 `retriever`（而不是字面 contains）
 *   2. `setEmbeddingProvider` 换后端后行为立刻变
 *   3. `updateConfig` **不能把向量后端弄丢**
 */
class RetrieverWiringTest {

    private class StubProvider(
        override val id: String = "stub",
        override val dimensions: Int = 4,
    ) : EmbeddingProvider {
        var calls = 0
        override val available: Boolean = true
        override fun embed(texts: List<String>): List<FloatArray> {
            calls += texts.size
            // 故意返回与字面无关的固定向量：只要被调用，命中结果就会和字面检索不同
            return texts.map { FloatArray(4) { 1f } }
        }
    }

    private fun bank(): MemoryBank = MemoryBank(store = InMemoryStore(), config = MemoryConfig())

    private fun entry(id: String, text: String) = MemoryEntry(
        id = id,
        content = text,
        kind = MemoryKind.RECENT,
        sourceConversationId = null,
        sourceMessageId = null,
        createdAt = 0L,
    )

    @Test
    fun `search 走 retriever 而不是字面 contains`() {
        val store = InMemoryStore(
            listOf(
                entry("a", "用户的手机端 Agent 通过手机客户端调用 API"),
                entry("b", "完全无关的天气"),
            ),
        )
        val bank = MemoryBank(store = store, config = MemoryConfig())

        // ★ 关键事实（真机上栽的那一刀）：旧代码是 `content.contains(q)` ——
        //   子串匹配，"远程调用" 不是原句的子串 ⇒ 0 条。
        //   而 LexicalRetriever 用 bigram Jaccard，"调用" 这一对是共享的 ⇒ 能命中。
        //   所以「换成 retriever」这一步本身就已经修好了用户报的那个现象。
        val lexical = bank.search("远程调用", limit = 10)
        assertTrue(lexical.isNotEmpty(), "LexicalRetriever 应该能靠共享 bigram 命中")
        assertEquals("a", lexical.first().id)

        // 再换一个"只认向量"的场景：查询与原文**没有任何共享字符**
        val noOverlap = bank.search("zzzz", limit = 10)
        assertTrue(noOverlap.isEmpty(), "字面后端对零共享字符的查询应当无命中")

        // 换向量后端后，同一条查询能召回（证明 retriever 真的被调用了）
        val stub = StubProvider()
        bank.setEmbeddingProvider(stub)
        val viaEmbedding = bank.search("zzzz", limit = 10)
        assertTrue(viaEmbedding.isNotEmpty(), "换了后端仍然搜不到 ⇒ retriever 没被调用")
        assertTrue(stub.calls >= 2, "embed 至少被调用一次（条目 + 查询）")
        assertEquals("embedding:stub", bank.retrieverId)
    }

    @Test
    fun `updateConfig 不会丢掉向量后端`() {
        val bank = bank()
        bank.setEmbeddingProvider(StubProvider())
        assertEquals("embedding:stub", bank.retrieverId)

        bank.updateConfig(MemoryConfig(decayHalfLifeDays = 14.0))
        // ★ 这一条是回归防线：改滑块把向量弄丢过一次
        assertEquals("embedding:stub", bank.retrieverId)
    }

    @Test
    fun `setEmbeddingProvider(null) 退回字面`() {
        val bank = bank()
        bank.setEmbeddingProvider(StubProvider())
        bank.setEmbeddingProvider(null)
        assertEquals("lexical", bank.retrieverId)
    }

    @Test
    fun `后端不可用时 retrieverId 如实报告降级`() {
        val bank = bank()
        val dead = object : EmbeddingProvider {
            override val id = "dead"
            override val dimensions = 4
            override val available = false
            override fun embed(texts: List<String>): List<FloatArray>? = null
        }
        bank.setEmbeddingProvider(dead)
        assertEquals("lexical(fallback)", bank.retrieverId)
    }

    @Test
    fun `注入路径会用到查询词（超过上限时按相关性筛）`() {
        val many = (1..150).map { entry("id$it", "第 $it 条：用户喜欢喝手冲咖啡") } +
            listOf(entry("target", "用户的手机端 Agent 通过手机客户端调用 API"))
        val bank = MemoryBank(store = InMemoryStore(many), config = MemoryConfig())

        // 上限 100，库里有 151 条 ⇒ 会走检索分支
        val text = bank.composeContext(100_000, query = "通过客户端调用 API").text
        assertTrue(text.contains("通过手机客户端调用 API"), "检索没生效：目标条目没进注入")
    }
}
