package com.chatmobile.data

import chatmobile.memory.InMemoryStore
import chatmobile.memory.MemoryBank
import chatmobile.memory.MemoryEntry
import chatmobile.memory.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomMemoryStore]。
 *
 * ## 这组测试要证明的核心
 *
 * `MemoryStore` 是**同步**接口，Room 是**异步**的。把两者接起来的桥必须满足：
 *
 * 1. **`all()` 立刻能看到刚 `put` 的内容** —— 否则 `MemoryBank` 的
 *    "读-改-写"（比如 `hitCount + 1`）会一直读到旧值，`promoteThreshold`
 *    永远达不到，自动提升这条功能**从不触发**，而且不报错。
 * 2. **持久化最终一致** —— 同步返回之后，数据必须真的进了库。
 * 3. **语义与 `InMemoryStore` 完全一致** —— 这是最重要的那条：
 *    同一套 `MemoryBank` 操作，换成 Room 实现后结果必须逐字段相同。
 *
 * 第 3 条用"MVP 对照"的方式验证：[MemorySemanticsTest] 里同一组操作分别在
 * [InMemoryStore] 和 [RoomMemoryStore] 上跑一遍，比较结果。
 */
class RoomMemoryStoreTest : RoomTestBase() {

    private lateinit var scope: CoroutineScope
    private lateinit var store: RoomMemoryStore

    private fun newStore(): RoomMemoryStore {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return RoomMemoryStore(memories, scope).also { store = it }
    }

    @After
    fun closeStore() {
        if (this::store.isInitialized) store.close()
        if (this::scope.isInitialized) scope.cancel()
    }

    private fun entry(
        id: String,
        content: String = "内容-$id",
        kind: MemoryKind = MemoryKind.RECENT,
        createdAt: Long = now,
        lastUsedAt: Long? = null,
        hitCount: Int = 0,
        deleted: Boolean = false,
        sourceConversationId: String? = "c-1",
        sourceMessageId: String? = "m-1",
    ) = MemoryEntry(
        id = id,
        content = content,
        kind = kind,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        hitCount = hitCount,
        deleted = deleted,
    )

    // ---------------------------------------------------------------
    // 同步可见性（最关键的一条）
    // ---------------------------------------------------------------

    @Test
    fun `put 之后 all 立刻能看到，不需要等落盘`() = runBlocking {
        val store = newStore()

        store.put(entry("m1", content = "用户喜欢简洁回答"))

        // ★ 同步接口的硬要求
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("用户喜欢简洁回答", all.single().content)
    }

    @Test
    fun `同一 id 连续 put 会被最后一次覆盖`() = runBlocking {
        val store = newStore()

        store.put(entry("m1", hitCount = 1))
        store.put(entry("m1", hitCount = 2))
        store.put(entry("m1", hitCount = 3))

        assertEquals(1, store.all().size)
        assertEquals(3, store.all().single().hitCount)
    }

    @Test
    fun `remove 之后 all 立刻看不到`() = runBlocking {
        val store = newStore()
        store.put(entry("m1"))

        store.remove("m1")

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `all 返回快照，改它不影响库里的状态`() = runBlocking {
        val store = newStore()
        store.put(entry("m1"))

        val snapshot = store.all().toMutableList()
        snapshot.clear()

        assertEquals("all() 必须返回快照，不能是活视图", 1, store.all().size)
    }

    // ---------------------------------------------------------------
    // 持久化
    // ---------------------------------------------------------------

    @Test
    fun `flush 之后数据真的在库里，字段一个不丢`() = runBlocking {
        val store = newStore()
        val original = entry(
            id = "m1",
            content = "用户是后端工程师",
            kind = MemoryKind.SAVED,
            createdAt = 1_700_000_000_000L,
            lastUsedAt = 1_700_000_111_000L,
            hitCount = 7,
            deleted = false,
            sourceConversationId = "conv-42",
            sourceMessageId = "msg-99",
        )

        store.put(original)
        store.flush()

        val rows = memories.all()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("m1", row.id)
        assertEquals("用户是后端工程师", row.content)
        assertEquals("saved", row.kind)
        assertEquals(1_700_000_000_000L, row.createdAt)
        // ★ 这三个字段是用户特别点名不能丢的
        assertEquals(1_700_000_111_000L, row.lastUsedAt)
        assertEquals(7, row.hitCount)
        assertFalse(row.deleted)
        // 可解释性字段
        assertEquals("conv-42", row.sourceConversationId)
        assertEquals("msg-99", row.sourceMessageId)
    }

    @Test
    fun `记忆表不存 weight 字段，理由见 MemoryEntity 注释`() {
        // 这条是"防回归"测试：如果有人把 weight 加回实体，这里会失败，
        // 强迫他重新读一遍 MemoryEntity 的类注释再决定。
        val fields = MemoryEntity::class.java.declaredFields.map { it.name }
        assertFalse("weight 不该落库：它是时间的函数，存下来立刻就过期了", "weight" in fields)
        // 但算权重需要的原料必须在
        listOf("createdAt", "lastUsedAt", "hitCount", "kind").forEach {
            assertTrue("算权重需要的原料 $it 必须在实体里", it in fields)
        }
    }

    @Test
    fun `重新构造 store 后能 load 回之前的记忆`() = runBlocking {
        val first = newStore()
        first.put(entry("m1", content = "记忆一", hitCount = 3))
        first.put(entry("m2", content = "记忆二", kind = MemoryKind.SAVED))
        first.flush()
        first.awaitClosed()

        // 模拟进程重启：新 store，空缓存，从库载入
        val second = newStore()
        assertEquals("load 之前缓存是空的（这是明确契约）", 0, second.all().size)

        val loaded = second.load()

        assertEquals(2, loaded)
        assertEquals(2, second.all().size)
        assertEquals("记忆一", second.all().first { it.id == "m1" }.content)
        assertEquals(3, second.all().first { it.id == "m1" }.hitCount)
        assertEquals(MemoryKind.SAVED, second.all().first { it.id == "m2" }.kind)
    }

    @Test
    fun `墓碑（deleted）也会被持久化，restore 才可能工作`() = runBlocking {
        val store = newStore()
        store.put(entry("m1"))
        store.put(entry("m1", deleted = true))
        store.flush()

        assertTrue(memories.all().single().deleted)

        // restore
        store.put(store.all().single().copy(deleted = false))
        store.flush()

        assertFalse(memories.all().single().deleted)
    }

    @Test
    fun `remove 是物理删除，不是墓碑`() = runBlocking {
        val store = newStore()
        store.put(entry("m1"))
        store.flush()
        assertEquals(1, memories.all().size)

        store.remove("m1")
        store.flush()

        // ★ 物理删掉了 —— 库里一行都不剩。
        //   墓碑是 MemoryBank.delete() 的职责（它 put(deleted=true)），两者不能混。
        assertEquals(0, memories.all().size)
    }

    @Test
    fun `putIfAbsent 语义：load 不会覆盖缓存里更新的数据`() = runBlocking {
        // 先往库里放一份旧数据
        memories.upsert(MemoryEntity.from(entry("m1", content = "旧内容", hitCount = 1)))

        val store = newStore()
        // load 之前先做一次更新（模拟"启动时有个写先到了"）
        store.put(entry("m1", content = "新内容", hitCount = 99))

        store.load()

        // 缓存里较新的那份必须赢 —— 否则等于把用户刚才的写回滚了
        val got = store.all().single()
        assertEquals("新内容", got.content)
        assertEquals(99, got.hitCount)
    }

    // ---------------------------------------------------------------
    // 与 MemoryBank 串起来
    // ---------------------------------------------------------------

    @Test
    fun `MemoryBank + RoomMemoryStore：recordRecent 累加 hitCount 并落盘`() = runBlocking {
        val store = newStore()
        val bank = MemoryBank(store, idGenerator = { "id-${System.nanoTime()}" })

        bank.recordRecent("用户问过 OkHttp 超时")
        bank.recordRecent("用户问过 OkHttp 超时")
        val third = bank.recordRecent("用户问过 OkHttp 超时")

        assertEquals("同内容应合并而不是新增", 1, store.all().size)
        assertEquals(3, third.hitCount)
        assertNotNull(third.lastUsedAt)

        store.flush()
        val row = memories.all().single()
        assertEquals(3, row.hitCount)
        assertNotNull("lastUsedAt 必须落库，否则衰减会从 createdAt 重算", row.lastUsedAt)
    }

    @Test
    fun `MemoryBank + RoomMemoryStore：命中 3 次后 autoPromote 真的能触发`() = runBlocking {
        // ★ 这条是整个"同步接口 + 异步库"设计是否正确的**判决性测试**。
        //   如果 put 只投队列不更新缓存，composeContext 的 touch 会读到旧 hitCount，
        //   autoPromote 就永远不触发 —— 不报错，功能静默失效。
        val store = newStore()
        val bank = MemoryBank(store, idGenerator = { "id-${System.nanoTime()}" })

        bank.recordRecent("用户在学日语")
        // 反复召回，让 hitCount 涨到 promoteThreshold(3)
        repeat(3) { bank.composeContext(totalContextTokens = 100_000) }

        val beforeCount = store.all().single().hitCount
        assertTrue("hitCount 应已 >= 3，实际 $beforeCount", beforeCount >= 3)

        val promoted = bank.autoPromote()

        assertEquals(1, promoted.size)
        assertEquals(MemoryKind.SAVED, promoted.single().kind)

        store.flush()
        assertEquals("saved", memories.all().single().kind)
    }

    @Test
    fun `MemoryBank + RoomMemoryStore：delete 是墓碑，list 默认不含它`() = runBlocking {
        val store = newStore()
        val bank = MemoryBank(store, idGenerator = { "id-${System.nanoTime()}" })

        val saved = bank.save("用户是后端工程师")
        assertTrue(bank.delete(saved.id))

        assertEquals(0, bank.list().size)
        assertEquals(1, bank.list(includeDeleted = true).size)

        store.flush()
        // 墓碑落库，restore 才有可能
        assertTrue(memories.all().single().deleted)
        assertTrue(bank.restore(saved.id))
    }

    // ---------------------------------------------------------------
    // 边界
    // ---------------------------------------------------------------

    @Test
    fun `close 之后 put 会明确报错，而不是静默丢写`() = runBlocking {
        val store = newStore()
        store.close()

        val failed = runCatching { store.put(entry("m1")) }.isFailure
        assertTrue("关闭后写入必须显式失败，静默丢弃是最坏的", failed)
    }

    @Test
    fun `flush 在空队列上是安全的空操作`() = runBlocking {
        val store = newStore()
        store.flush()   // 不应挂起、不应抛异常
        assertEquals(0, store.pendingWrites())
    }

    @Test
    fun `pendingWrites 在 flush 后归零`() = runBlocking {
        val store = newStore()
        repeat(5) { i -> store.put(entry("m$i")) }

        store.flush()

        assertEquals(0, store.pendingWrites())
        assertNull(store.lastError)
    }

    @Test
    fun `空内容与超长内容都能正确往返`() = runBlocking {
        val store = newStore()
        val long = "长".repeat(10_000)
        store.put(entry("short", content = ""))
        store.put(entry("long", content = long))
        store.flush()

        val rows = memories.all().associateBy { it.id }
        assertEquals("", rows.getValue("short").content)
        assertEquals(long, rows.getValue("long").content)
    }

    @Test
    fun `中文与 emoji 不会乱码`() = runBlocking {
        val store = newStore()
        val text = "用户喜欢用中文，也会用 emoji 😀🎯，还有标点：。「」"
        store.put(entry("m1", content = text))
        store.flush()

        assertEquals(text, memories.all().single().content)
    }
}

/**
 * **语义一致性对照测试** —— 本项目里最重要的一组。
 *
 * 任务要求："`RoomMemoryStore` 读写与 core-memory 的语义一致"。
 * 光靠"我觉得一致"是不算的，所以这里做成 **同一组操作跑两遍、逐字段比对**：
 *
 * ```
 * ops(MemoryBank(InMemoryStore))        → 结果 A
 * ops(MemoryBank(RoomMemoryStore))      → 结果 B
 * 断言 A == B（逐字段）
 * ```
 *
 * 这样一旦 Room 实现在某个细节上和内存实现分叉（比如 `remove` 做成墓碑、
 * `all()` 漏掉墓碑、`lastUsedAt` 没更新），比对会立刻失败，而且能指出是哪个字段。
 */
class MemorySemanticsTest : RoomTestBase() {

    private lateinit var scope: CoroutineScope
    private lateinit var roomStore: RoomMemoryStore

    private fun newRoomStore(): RoomMemoryStore {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return RoomMemoryStore(memories, scope).also { roomStore = it }
    }

    @After
    fun closeStore() {
        if (this::roomStore.isInitialized) roomStore.close()
        if (this::scope.isInitialized) scope.cancel()
    }

    /**
     * 一组有代表性的操作，覆盖 MemoryBank 的每一条公开路径。
     *
     * ★ 全程使用**固定时间戳**与**确定性 id**，否则两个实现的结果没法直接比
     *   （`System.currentTimeMillis()` 与 `UUID.randomUUID()` 每次都不一样）。
     */
    private fun runOps(store: chatmobile.memory.MemoryStore): List<String> {
        var idSeq = 0
        var clock = 1_700_000_000_000L
        val bank = MemoryBank(
            store = store,
            idGenerator = { "id-${idSeq++}" },
        )

        val log = mutableListOf<String>()

        fun snap(tag: String) {
            // 逐字段 dump，包含墓碑 —— 任何字段不一致都会体现在字符串里
            val lines = store.all()
                .sortedBy { it.id }
                .map { e ->
                    "${e.id}|${e.content}|${e.kind}|${e.sourceConversationId}|${e.sourceMessageId}|" +
                        "${e.createdAt}|${e.lastUsedAt}|${e.hitCount}|${e.deleted}"
                }
            log += "$tag -> ${lines.joinToString(" ; ")}"
        }

        // 1. 显式保存
        bank.save("用户是后端工程师", now = clock)
        snap("after save")
        clock += 1_000

        // 2. 记录近期，重复内容应合并
        bank.recordRecent("用户问过 OkHttp 超时", now = clock)
        snap("after recordRecent 1")
        clock += 1_000
        bank.recordRecent("用户问过 OkHttp 超时", now = clock)
        snap("after recordRecent 2 (merge)")
        clock += 1_000

        // 3. 大小写不同的同内容也应合并（MemoryBank 用 equals(ignoreCase=true)）
        bank.recordRecent("用户问过 okhttp 超时", now = clock)
        snap("after recordRecent case-insensitive merge")
        clock += 1_000

        // 4. 组装上下文（会 touch：写 lastUsedAt 与 hitCount）
        val composition = bank.composeContext(totalContextTokens = 50_000, now = clock)
        log += "compose.isEmpty=${composition.isEmpty} saved=${composition.usedSaved.size} " +
            "recent=${composition.usedRecent.size}"
        snap("after composeContext")
        clock += 1_000

        // 5. 不 touch 的组装不该改任何东西
        bank.composeContext(totalContextTokens = 50_000, now = clock, touch = false)
        snap("after composeContext without touch")

        // 6. 提升（手动）
        // ★ 显式按 kind 过滤再取 —— 不依赖 all() 的迭代顺序（见 RoomMemoryStore.all() 注释）
        val recentId = store.all().filter { it.kind == MemoryKind.RECENT }.minByOrNull { it.id }!!.id
        bank.promote(recentId)
        snap("after promote")

        // 7. 墓碑删除 + 恢复
        bank.delete(recentId)
        snap("after delete (tombstone)")
        bank.restore(recentId)
        snap("after restore")

        // 8. 列表与统计
        log += "list(default)=${bank.list().map { it.id }.sorted()}"
        log += "list(includeDeleted)=${bank.list(includeDeleted = true).map { it.id }.sorted()}"
        val stats = bank.stats()
        log += "stats saved=${stats.savedCount} recent=${stats.recentCount} cap=${stats.savedCapacity}"

        // 9. 可解释性
        // ★ 这里必须**显式定位**，不能靠 `store.all().first()`：
        //   `all()` 的顺序是两个实现各自的事，用它选"被试"会让对照测试
        //   测到不同的条目 —— 第一次跑就是这么挂的（见 RoomMemoryStore.all() 注释）。
        val explainTarget = store.all().first { it.id == "id-0" }
        val expl = bank.explain(explainTarget.id, now = clock)
        log += "explain entryId=${expl?.entry?.id} isPromoted=${expl?.isPromoted}"

        // 10. 物理移除（remove，与墓碑不同）
        // 同样显式指定，理由同上
        store.remove("id-0")
        snap("after remove (physical)")

        return log
    }

    @Test
    fun `RoomMemoryStore 与 InMemoryStore 的语义逐字段一致`() = runBlocking {
        val memoryLog = runOps(InMemoryStore())

        val store = newRoomStore()
        val roomLog = runOps(store)

        // 先确认两边都没跑出空结果（防止"两个空列表相等"的假绿）
        assertTrue("对照日志不该为空", memoryLog.size >= 10)
        assertEquals("操作条数应一致", memoryLog.size, roomLog.size)

        memoryLog.zip(roomLog).forEachIndexed { index, (expected, actual) ->
            assertEquals(
                "第 ${index + 1} 步语义分叉：\n  InMemoryStore = $expected\n  RoomMemoryStore = $actual",
                expected,
                actual,
            )
        }
    }

    @Test
    fun `RoomMemoryStore 落盘后的内容与内存实现完全一致`() = runBlocking {
        val memoryStore = InMemoryStore()
        runOps(memoryStore)

        val store = newRoomStore()
        runOps(store)
        store.flush()

        fun MemoryEntry.asRow() = listOf(
            id, content, kind.name, createdAt.toString(),
            lastUsedAt?.toString() ?: "null", hitCount.toString(), deleted.toString(),
        )

        // 内存实现里还剩什么（按 id 排序，两边都排，消除顺序差异）
        val expected = memoryStore.all().sortedBy { it.id }.map { it.asRow() }

        // 库里还剩什么
        val actual = memories.all().sortedBy { it.id }.map { it.toEntry().asRow() }

        assertEquals("落盘内容与内存实现必须逐字段一致", expected, actual)
    }

    @Test
    fun `note_ load 不是语义的一部分，两边都要显式调用才对等`() = runBlocking {
        // InMemoryStore 可以带初始数据构造；RoomMemoryStore 必须先 load。
        // 这条测试把这个差异**写成可执行的说明**，避免后人以为这是 bug。
        val initial = listOf(
            MemoryEntry(
                id = "seed-1",
                content = "已有记忆",
                kind = MemoryKind.SAVED,
                sourceConversationId = null,
                sourceMessageId = null,
                createdAt = 1_700_000_000_000L,
            ),
        )

        val memoryStore = InMemoryStore(initial)
        assertEquals(1, memoryStore.all().size)

        // 同样的数据先落库，再让 Room 实现 load
        memories.upsertAll(initial.map { MemoryEntity.from(it) })
        val store = newRoomStore()
        assertEquals("load 之前应为空（契约）", 0, store.all().size)
        store.load()
        assertEquals(1, store.all().size)

        assertEquals(memoryStore.all().single().id, store.all().single().id)
    }
}
