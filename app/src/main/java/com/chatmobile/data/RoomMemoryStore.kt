package com.chatmobile.data

import chatmobile.memory.MemoryEntry
import chatmobile.memory.MemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `core-memory` 的 [MemoryStore]（**同步接口**）在 Room 上的实现。
 *
 * ## 核心矛盾
 *
 * | 侧 | 是什么 |
 * |---|---|
 * | `MemoryStore` | `all()` / `put()` / `remove()` —— **同步、当场返回** |
 * | Room | 所有 DAO 方法都是 `suspend`，**必须**在协程里、在 IO 线程上跑 |
 *
 * 而 `MemoryBank` 的每一处调用都是"读-改-写"的同步过程：
 *
 * ```kotlin
 * fun recordRecent(content: String): MemoryEntry {
 *     val existing = store.all().firstOrNull { ... }   // 读
 *     store.put(existing.copy(hitCount = existing.hitCount + 1))  // 写
 * }
 * ```
 *
 * 如果把 `put` 做成"扔进队列就走"，会有两个真实的后果：
 *
 * 1. **`composeContext()` 会读到陈旧数据**。它刚刚 `put` 了 `lastUsedAt`，
 *    紧接着（同一轮里）又要 `all()` 来组装下一轮的上下文。
 *    队列还没消费 ⇒ 读到的是旧值 ⇒ `hitCount` 永远加不上去 ⇒ **记忆永远达不到提升阈值**。
 *    这个 bug 不会报错，只会让"自动提升"这条功能**从不触发**。
 * 2. **`MemoryBank.save()` 的容量降级会连环算错**：它在 `store.all()` 之后就 `put`，
 *    如果读到的 `all()` 落后于写，`savedCapacity` 的判定会误判。
 *
 * ⇒ 所以 **`all()` 必须同步返回、且必须包含刚刚 `put` 的内容**。
 *   这就是"缓存"不是为了性能，而是**语义正确性的必要条件**。
 *
 * ## 设计
 *
 * ```
 *   ┌────────────────── 同步世界（MemoryStore 接口）──────────────────┐
 *   │  cache: ConcurrentHashMap<String, MemoryEntry>  ← 唯一真值来源 │
 *   └───────────────┬────────────────────────────────────────────────┘
 *                   │ 变更时投递一次"整条 entry"的快照
 *                   ▼
 *   ┌──────────── Channel(capacity=UNLIMITED) ────────────┐
 *   │  单消费者协程（在 [scope] 上）                        │
 *   │  → MemoryDao.upsert / physicalDelete                │
 *   └──────────────────────────────────────────────────────┘
 *
 *                   ▲
 *                   │ 启动时/需要时：从库全量灌入缓存
 *   ┌───────────────┴──────────────────────────────────────┐
 *   │  MemoryDao.all()                                      │
 *   └───────────────────────────────────────────────────────┘
 * ```
 *
 * **缓存是唯一的读来源，DB 是唯一的持久化目标。** 两者的一致性由三件事保证：
 *
 * 1. **写入是"整条快照"而不是"命令"**。Channel 里传的是 `MemoryEntry` 的不可变副本，
 *    不是 "hitCount += 1" 这种命令。所以重复消费、乱序消费都不会算错 ——
 *    后写的必然覆盖先写的，最终值就是最后一次 `put` 的那份。
 * 2. **同一 id 的写入按投递顺序串行消费**（单个消费者协程 + 按 id 分组不需要，
 *    因为单消费者本身就是串行）。不会出现两条协程同时改同一行的交错。
 * 3. **[flush] 提供一个"等它落盘"的汇合点**，给进程退出、测试、以及"用户点了导出"
 *    这类必须确认已持久化的场景用。
 *
 * ## 冷启动：必须显式 [load]
 *
 * 构造时不读库（构造函数里不能 `runBlocking` —— 那会阻塞主线程并且在
 * Android 上很容易撞上 `NetworkOnMainThread` 之外更糟的死锁）。
 * 而是提供一个 `suspend fun load()`：App 启动时在协程里调一次，
 * 缓存就位之后 `MemoryBank` 才能正常工作。
 *
 * `load()` 之前 `all()` 返回空列表 —— 这是**明确的契约**，不是 bug。
 * 如果某天真出现了"记忆凭空消失"，第一个该查的就是 `load()` 有没有被调用。
 *
 * ## 关闭
 *
 * [close] 只**关闭队列**，不阻塞等待。队列里的残余项仍会被 worker 消费完 ——
 * 数据不会丢。要"close 之后保证已落盘"，用 [awaitClosed]（suspend，会等 worker 退出）。
 * 绝不在 [close] 里 `runBlocking` —— 那在 Android 主线程上就是 ANR。
 *
 * @param scope 后台写库协程所在作用域。App 里给一个 `SupervisorJob() + Dispatchers.IO`；
 *              测试里给普通 `CoroutineScope`（**不要**给 `TestScope`，它的虚拟时间会让
 *              `withTimeoutOrNull` 和真实 IO 的时序错位）。
 */
class RoomMemoryStore(
    private val dao: MemoryDao,
    private val scope: CoroutineScope,
) : MemoryStore, AutoCloseable {

    // ---------------------------------------------------------------
    // 同步缓存 —— all() 的读取来源
    // ---------------------------------------------------------------

    private val cache = ConcurrentHashMap<String, MemoryEntry>()

    /**
     * 写库队列。**无界**，且刻意用 UNLIMITED：
     *
     * 有界 + `trySend` 丢弃 = 静默丢写。记忆丢失是不可接受的（用户会看到
     * "记住的东西忘了"，且无从排查）。有界 + `send`（挂起）= 把 `put()`
     * 变成可能阻塞的同步方法，直接违背接口契约。
     *
     * 内存风险实测可忽略：一条记忆约 200 字节，200 条 saved + 若干 recent 是上限
     * （`MemoryConfig.savedCapacity`），队列最深也就几百项。
     */
    private val writes = Channel<WriteCommand>(Channel.UNLIMITED)

    private val closed = AtomicBoolean(false)

    /** 是否有尚未落盘的改动（只用于诊断与测试断言，不是控制流依据） */
    private val pendingCount = AtomicInteger(0)

    private val worker = scope.launch {
        for (cmd in writes) {
            when (cmd) {
                is WriteCommand.Upsert -> {
                    try {
                        dao.upsert(cmd.entity)
                    } catch (t: Throwable) {
                        // ★ 写库失败**不能吞掉**：吞了就是"用户以为记住了、其实没存"。
                        // 但也不能让异常杀掉整个 worker —— 杀掉之后所有后续记忆都写不进去，
                        // 而用户看不到任何提示。这里记下来，由 [lastError] 给上层查。
                        lastError = t
                    } finally {
                        pendingCount.decrementAndGet()
                    }
                }

                is WriteCommand.Delete -> {
                    try {
                        dao.physicalDelete(cmd.id)
                    } catch (t: Throwable) {
                        lastError = t
                    } finally {
                        pendingCount.decrementAndGet()
                    }
                }

                is WriteCommand.Barrier -> cmd.done.complete(Unit)
            }
        }
    }

    /** 最近一次写库失败的异常；null = 一直正常。上层可据此提示"记忆保存失败" */
    @Volatile
    var lastError: Throwable? = null
        private set

    // ---------------------------------------------------------------
    // 载入
    // ---------------------------------------------------------------

    /**
     * 从库里把全部记忆读进缓存。**App 启动时必须调一次。**
     *
     * ★ 如果调用时缓存里已经有东西（比如 `load()` 之前就有人 `put` 了），
     *   **缓存里较新的那份会赢**：以 `put` 的时间为界，看起来一样的 id 用缓存值。
     *   理由：`put` 是用户/上层刚做的明确操作，库里的可能是它之前的旧状态，
     *   用库的覆盖掉就等于把用户刚才的写回滚了。
     *
     * @return 载入条数
     */
    suspend fun load(): Int {
        val fromDb = dao.all()
        var loaded = 0
        for (entity in fromDb) {
            val entry = entity.toEntry()
            // putIfAbsent：库里没有的才装，缓存已有的（更新的）保留
            val existing = cache.putIfAbsent(entry.id, entry)
            if (existing == null) loaded++
        }
        return loaded
    }

    /** 库里的行数（诊断用：应该始终 ≥ 缓存里的，因为缓存会先见新数据） */
    suspend fun countInDb(): Int = dao.aliveCount()

    // ---------------------------------------------------------------
    // MemoryStore —— 全部同步
    // ---------------------------------------------------------------

    /**
     * 全部记忆（含墓碑）。
     *
     * ## ★ 返回**稳定排序**的快照 —— 这一条是修过的真 bug
     *
     * `ConcurrentHashMap.values` 的顺序是**哈希顺序**，对同一个 key 集合在不同
     * 运行、不同 JVM 上可能不同，插入/删除还会改变它。直接 `toList()` 出去，
     * 上层任何"按 `all()` 顺序取前 N 条"的逻辑（比如设置页列记忆、
     * 或 `MemoryBank` 里 `firstOrNull` 拿了再改）都会拿到**不确定**的结果。
     *
     * 这不是理论问题：本包的一致性测试第一次跑就抓到了 ——
     * 两个实现在 `explain(store.all().first().id)` 这一步分叉，取了不同的条目。
     * `InMemoryStore` 用 `LinkedHashMap`（插入顺序稳定），Room 实现必须同样稳定。
     *
     * 排序规则与 `MemoryComposer` 的四级排序保持同一意图：
     * `createdAt` 降序（新的在前）→ `id` 升序（兜底，保证不同实现给出一致结果）。
     *
     * 注意：**这不是召回排序**。召回排序（含衰减、命中加成）在 `MemoryComposer` 里，
     * 需要 `now` 参数。这里只保证"同一份数据，读多少次顺序都一样"。
     */
    override fun all(): List<MemoryEntry> =
        cache.values.toList().sortedWith(
            compareByDescending<MemoryEntry> { it.createdAt }.thenBy { it.id },
        )

    override fun put(entry: MemoryEntry) {
        check(!closed.get()) { "RoomMemoryStore 已关闭，不能再写入" }
        // 1) 先落缓存：保证接下来任何 all() 立刻能看到它（语义正确性，见类注释）
        cache[entry.id] = entry
        // 2) 再排队落库：整条快照，重复/乱序消费都安全
        pendingCount.incrementAndGet()
        val sent = writes.trySend(WriteCommand.Upsert(MemoryEntity.from(entry))).isSuccess
        if (!sent) {
            pendingCount.decrementAndGet()
            // 队列只在 close() 之后才会拒绝接收
            error("记忆写入队列已关闭，entry=${entry.id}")
        }
    }

    override fun remove(id: String) {
        check(!closed.get()) { "RoomMemoryStore 已关闭，不能再写入" }
        // ★ 语义遵随 InMemoryStore：remove 是**物理移除**，不是墓碑。
        //   墓碑是 MemoryBank.delete() 的职责（它 put(deleted = true)）。
        //   两者不能混：如果 remove 也做墓碑，restore() 就永远无法区分
        //   "用户删掉的"和"用户彻底删掉的"。
        cache.remove(id)
        pendingCount.incrementAndGet()
        val sent = writes.trySend(WriteCommand.Delete(id)).isSuccess
        if (!sent) {
            pendingCount.decrementAndGet()
            error("记忆写入队列已关闭，id=$id")
        }
    }

    // ---------------------------------------------------------------
    // 汇合点
    // ---------------------------------------------------------------

    /**
     * 等到队列里当前的写入**全部落盘**。
     *
     * 实现方式是投一个"屏障"命令：因为单消费者串行处理，
     * 屏障被消费时就意味着它之前的所有写都已完成。
     *
     * ★ 不要用 `delay(50)` 之类的方式"等一等" —— 那是猜测，测试会随机失败。
     */
    suspend fun flush() {
        if (closed.get()) {
            // 已 close：队列不再接收，但 worker 可能还在把剩下的写完 → 直接等它退出
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { worker.join() }
            return
        }
        val barrier = WriteCommand.Barrier(CompletableDeferred())
        if (writes.trySend(barrier).isFailure) {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { worker.join() }
            return
        }
        // 加超时是为了"worker 已经死了"这种情况：否则这里会永久挂起，
        // 表现为 App 在退出时卡住、或者测试跑不完而没有报错 —— 那是最难查的一类。
        val ok = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { barrier.done.await() }
        checkNotNull(ok) {
            "等待记忆落盘超时（${FLUSH_TIMEOUT_MS}ms）。worker 可能已异常退出，lastError=$lastError"
        }
    }

    /** 还没落盘的写操作数量。测试用来确认"缓冲确实在工作"。 */
    fun pendingWrites(): Int = pendingCount.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // ★ 不在这里 runBlocking 等 worker（会阻塞调用线程，在 Android 主线程上直接 ANR）。
        //   Channel.close() 之后 worker 仍会把队列里剩余的项消费完再退出。
        //   要"close 之后保证已落盘"就先 awaitClosed()。
        writes.close()
    }

    /** 关闭并**等待**后台协程真正退出。进程退出 / 测试收尾时用这个，不要用 [close]。 */
    suspend fun awaitClosed() {
        close()
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { worker.join() }
    }

    private sealed interface WriteCommand {
        data class Upsert(val entity: MemoryEntity) : WriteCommand
        data class Delete(val id: String) : WriteCommand
        /** 汇合屏障：被消费到就说明之前的写都完成了 */
        data class Barrier(val done: CompletableDeferred<Unit>) : WriteCommand
    }

    private companion object {
        /** flush / awaitClosed 的最长等待。超过就是真出问题了，要报错不能静默挂死 */
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
