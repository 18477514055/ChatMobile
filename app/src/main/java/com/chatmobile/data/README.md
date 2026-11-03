# `com.chatmobile.data` —— Room 持久化层

> 状态：**已通过 `:app:testDebugUnitTest`**（测试结果见文末）
> 负责人：data-agent　｜　最后更新：2026-11-02

---

## 一、这个包有什么

```
app/src/main/java/com/chatmobile/data/
  ConversationEntity.kt      会话表
  MessageEntity.kt           消息表 + MessageRole / MessageStatus 枚举
  MemoryEntity.kt            记忆表（字段对齐 core-memory 的 MemoryEntry）

  ConversationDao.kt         会话 CRUD + 置顶/归档/重命名 + observe
  MessageDao.kt              插入(自动 seq) + 游标分页 + 偏移分页 + 流式写 + 崩溃恢复
  MemoryDao.kt               记忆读写 + 内容去重查 + 全量替换

  ChatDatabase.kt            数据库定义（v1）
  ChatRepository.kt          会话/消息门面（跨表约定的唯一守卫）
  StreamingMessageWriter.kt  ★ 流式写入缓冲器
  RoomMemoryStore.kt         ★ MemoryStore（同步接口）的 Room 实现
```

测试在 `app/src/test/java/com/chatmobile/data/`。

---

## 二、★ 流式写入缓冲（本题的核心）

### 2.1 为什么不能每条 delta 都写库

一次 SSE 流式回答的 delta 粒度很小 —— 常见是 **1~3 个字符**。
一段 500 字的回答大约产生 **300~1500 次** delta 回调。如果每次都执行一条 `UPDATE`：

| 代价 | 量级 |
|---|---|
| SQLite 语句编译 + B-tree 查找 | 每次数百微秒 |
| **WAL 提交**（未合批时每次都是独立事务） | 每次 **1~10 ms**，含 `fsync` |
| IO 线程与 UI 线程争用 | 每一帧都可能撞上 |

1500 × 5ms ≈ **7.5 秒的纯 IO**，而这段回答本来应该 15 秒顺畅输出 ——
一半的时间在等磁盘，表现为"**字一个一个卡着蹦**"。
更糟的是 `fsync` 是突发性的：它会周期性把整个 App 的掉帧推到 16ms 以上。

### 2.2 缓冲策略

三条触发条件，谁先到谁触发 flush：

| 条件 | 默认值 | 作用 |
|---|---|---|
| 累积 delta **条数** ≥ `maxPendingDeltas` | **32** | 高频短 delta 的兜底 |
| 累积 **字符数** ≥ `maxPendingChars` | **1200** | ★ **主力条件** |
| 距上次写入 ≥ `maxDelayMs` | **120 ms** | 空闲尾巴：网络抖一下也不让最后几个字干等 |

**为什么主力是字符数而不是条数**：
delta 的长度完全不可控 —— 有的 provider 一次给一个字，有的一次给一整句。
只数条数会让攒批行为**在不同厂商之间差一个数量级**，而本项目的卖点就是兼容各家。

**超时条件谁来驱动**：[`StreamingMessageWriter.run()`](StreamingMessageWriter.kt) 每
`pollIntervalMs`（默认 20ms）调一次 `tick()`。挂在收集流的同一作用域里即可，随它一起取消：

```kotlin
coroutineScope {
    launch { writer.run() }                   // 定时 flush
    provider.streamChat(req).collect { event ->
        when (event) {
            is ChatEvent.Delta -> writer.append(event.text)
            is ChatEvent.Reasoning -> writer.appendReasoning(event.text)
            else -> {}
        }
    }
}
```

### 2.3 ★ 关键取舍：写**绝对快照**，不写增量

每次 flush 写的是"到目前为止的完整正文"，而不是"这 32 条拼起来的增量"。

**代价**：第 k 次 flush 要写 k 个字节，总写入量是 **O(n²)**（n = 回答长度）。
一次 10 KB 的回答、每 120ms 一次，总计约 **400 KB** 的额外写入 ——
在手机的 UFS 上完全可以忽略（一次回答的语音数据是这个量级的几十倍）。

**收益（这三条才是决定性的）**：

1. **幂等**。写失败、进程被杀、重试都不会双写。
   增量追加一旦"其实写成功了但抛了异常"，重试就会**永久性地把正文写坏**（重复片段），
   而且用户看不出来是 bug。
2. **自愈**。库里的内容永远是"某个时刻的完整前缀"，不会出现中间缺口。
3. **可断言**。测试可以直接比"库里 == 内存里"，见 `重复 flush 是幂等的`。

> **如果哪天 O(n²) 真的成了瓶颈**（比如长篇小说生成），正确的优化**不是**换回增量写，
> 而是**分段**：把一条消息拆成多行存储。那是 schema 层面的决定，不是缓冲器能兜住的。

### 2.4 落库顺序（这条比看起来重要）

```kotlin
suspend fun finish(status, errorMessage, reasoningMs) {
    flush()          // ← 先写内容
    dao.setStatus(…) // ← 再改状态
}
```

反过来的话，如果 flush 失败，库里会留下一条 **"已完成"但正文被截断**的消息 ——
那是**看起来能用但错了**，比直接失败难查得多。

### 2.5 出错时的三条纪律

| 场景 | 做法 |
|---|---|
| 用户点"停止生成" | `stop()` → 内容保留，状态 `stopped`（不完整但它就是用户要的） |
| 网络/协议错误 | `fail(msg)` → **错误信息单独存 `errorMessage`，绝不用它覆盖正文** |
| 进程被杀 | 启动时 `recoverInterruptedStreams()` 把残留的 `streaming`/`pending` 标成 `stopped` |

第三条不做的话，下次进来会显示一条"正常"但实际被截断的回答，
或者更糟 —— 一个永远转圈的气泡。

---

## 三、★ `RoomMemoryStore`：同步接口 × 异步数据库

### 3.1 核心矛盾

| 侧 | 是什么 |
|---|---|
| `core-memory` 的 `MemoryStore` | `all()` / `put()` / `remove()` —— **同步、当场返回** |
| Room | 所有 DAO 方法都是 `suspend` |

而 `MemoryBank` 的每一处调用都是"读-改-写"的同步过程：

```kotlin
fun recordRecent(content: String): MemoryEntry {
    val existing = store.all().firstOrNull { ... }          // 读
    store.put(existing.copy(hitCount = existing.hitCount + 1))  // 写
}
```

### 3.2 如果把 `put` 做成"扔进队列就走"，会出两个真实的 bug

1. **`composeContext()` 读到陈旧数据**。它刚 `put` 了 `lastUsedAt`，紧接着在同一轮里
   又要 `all()` 来组装上下文。队列还没消费 ⇒ 读到旧值 ⇒ `hitCount` 永远加不上去
   ⇒ **`autoPromote()` 永远不触发**。不报错，功能静默失效。
2. **`MemoryBank.save()` 的容量降级连环算错**。它在 `all()` 之后就 `put`，
   读到落后的 `all()` 会让 `savedCapacity` 判定误判。

⇒ **`all()` 必须同步返回、且必须包含刚刚 `put` 的内容。**
这就是"缓存"不是为了性能，而是**语义正确性的必要条件**。

### 3.3 设计

```
  ┌─────────── 同步世界（MemoryStore 接口）────────────┐
  │  cache: ConcurrentHashMap<String, MemoryEntry>     │ ← 唯一读来源
  └──────────────────┬─────────────────────────────────┘
                     │ 变更时投递"整条 entry"的快照
                     ▼
  ┌──────── Channel(UNLIMITED) ────────┐
  │  单消费者协程（scope 上）           │
  │  → MemoryDao.upsert / physicalDelete│
  └─────────────────────────────────────┘
                     ▲
                     │ 启动时显式 load()
  ┌──────────────────┴──────────────────┐
  │  MemoryDao.all()                     │
  └──────────────────────────────────────┘
```

一致性由三件事保证：

1. **写入是"整条快照"而不是"命令"**。Channel 里传的是不可变的 `MemoryEntry` 副本，
   不是 `hitCount += 1` 这种命令。所以重复消费、乱序消费都不会算错 ——
   后写的必然覆盖先写的。
2. **单消费者 = 串行**。不会出现两条协程同时改同一行的交错。
3. **`flush()` 是汇合点**。用**屏障命令 + `CompletableDeferred`** 实现，
   不是 `delay(50)` 那种猜测。带 5 秒超时 —— worker 死了要报错，不能静默挂死。

### 3.4 冷启动：必须显式 `load()`

构造时不读库（构造函数里不能 `runBlocking` —— 阻塞主线程，Android 上直接 ANR）。
所以提供 `suspend fun load()`，**App 启动时在协程里调一次**。

`load()` 之前 `all()` 返回空列表 —— 这是**明确契约**，不是 bug。
如果某天出现"记忆凭空消失"，第一个该查的就是 `load()` 有没有被调用。

★ `load()` 用 `putIfAbsent`：**缓存里较新的那份赢**。
理由：`put` 是用户/上层刚做的明确操作，用库里的旧值覆盖等于**把用户刚才的写回滚了**。

### 3.5 关闭

- `close()` 只关闭队列，**不阻塞**。残余项仍会被 worker 消费完，数据不丢。
- 要"close 之后保证已落盘"，用 `awaitClosed()`（suspend，会等 worker 退出）。
- **绝不在 `close()` 里 `runBlocking`** —— 主线程上就是 ANR。

### 3.6 `weight` 为什么不落库

MVP 方案 §2.1 里写了 `val weight: Float = 1.0f`，**我没有实现它**，理由是：

> 权重是**时间的函数**（`Decay.effectiveWeight = 0.5^(age/halfLife) × 命中加成`）。
> 一旦落库，它在下一次被读到时就**已经是错的**了。

存下来的权重只有两种命运：要么被当成真值用（排序按陈旧的数走），
要么每次读都重算（那这字段纯属浪费）。
所以只存算权重需要的**原料**：`createdAt` / `lastUsedAt` / `hitCount` / `kind`，读时现算。

有一条**防回归测试**守着这个决定（`记忆表不存 weight 字段`）：谁把 `weight` 加回实体，测试就红。

---

## 四、对 MVP 方案的其它调整（都有理由）

### 4.1 加了 `MessageEntity.seq`（方案里没有）

**问题**：用户消息与助手占位消息常在同一次点击里连着插入，
`System.currentTimeMillis()` **经常给出相同的值**。`createdAt` 一相同，
排序就退化成磁盘顺序 ⇒ **`LIMIT/OFFSET` 分页出现重复条和漏条**。
这不是理论问题，是分页的经典陷阱，而且**小数据量下看不出来**。

**解法**：`seq` 由 `insert()` 在**同一事务里**取 `MAX(seq)+1`。
配唯一索引 `(conversationId, seq)` 当并发保险 —— 万一两条并发插入拿到同一个 seq，
第二条会撞唯一约束**立刻报错**，而不是静默产生一个顺序错乱的会话。

**分页因此改用游标**：`WHERE seq < :cursor ORDER BY seq DESC LIMIT n`。
它不随插入而位移，且能直接用唯一索引反向扫描。
`LIMIT/OFFSET` 仍然保留（`pageByOffset`），但**只给"跳转到第 N 页"和测试用**。

### 4.2 加了外键 CASCADE

删会话必须连带删消息，否则会留下**永久孤儿行**（越用越大，且永远不会被发现）。
靠应用层"记得手动删"是不可靠的，交给 SQLite。

⚠️ 有个容易漏的点：**SQLite 默认 `foreign_keys=OFF`**，
Room 的 `@ForeignKey` 只把约束写进建表 DDL，**运行时是否生效取决于这个 pragma**。
Room 的 `RoomDatabase` 在开启时自动 `PRAGMA foreign_keys = ON`，但这条已经用测试锁住
（`删除会话会连带删掉它的消息`）。

### 4.3 `role` / `status` 用 String 落库而不是 enum

用 TypeConverter 把 enum 落库要额外写转换器，且**枚举改名就会静默读错**。
这里用 String 落库、用 `MessageRole` / `MessageStatus` 在代码侧做类型约束，
边界转换只有 `parse()` 一处。

`STREAMING` 与 `STOPPED` 刻意分开：进程被杀时库里的 `streaming` 行说明
"这条是被中断的，内容不完整"，启动时标成 `stopped` 才能如实显示。

### 4.4 `ConversationEntity` 补了 `systemPromptOverride`

方案 §2.1 列了它（"本会话覆盖的指令"），但骨架模型里漏了。
自定义指令是官方行为的一部分，现在不留位置，将来加就是一次需要 migration 的破坏性改动。

### 4.5 没有 `fallbackToDestructiveMigration()`

那等于"以后每次改表都静默清空用户聊天记录"，而且**不会有人发现** ——
用户只会觉得"怎么记录都没了"。

正确做法：每次改 schema 就 `VERSION + 1` 并写 `Migration`，迁移本身要有测试。

---

## 五、分页设计

### 5.1 为什么不用 `LIMIT/OFFSET` 做主路径

1. **翻页期间来了新消息** ⇒ OFFSET 整体位移 ⇒ 用户看到重复或跳过的消息。
   聊天 App 里新消息是常态，不是边界情况。
2. **OFFSET 越大越慢** —— SQLite 要先扫过前面 m 行再丢掉。

### 5.2 游标规则

`nextBeforeSeq` = 本页**最靠前**那条的 seq（即降序结果的最后一条）。
下一页取 `seq < 它`，于是本页最靠前那条不会被重复取到。

**到顶的判定**：返回条数 `< limit` 就说明没了。这不是启发式 ——
`pageBefore` 是 `ORDER BY seq DESC LIMIT n`，返回不足 n 条只可能是"符合条件的行不够 n 条"。

### 5.3 返回值顺序

SQL 一律 `ORDER BY seq DESC`（走索引从尾部反向扫，最省）。
**`DAO` 如实返回降序**，正序化由调用方套 `toAscending()`。

★ 刻意**不**在 DAO 里藏这层反转：返回值顺序与 SQL 保持一致，
后人看 SQL 就知道拿到什么顺序，不会猜。

---

## 六、测试

### 6.1 怎么跑

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd <仓库目录>
& "C:\Users\example\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat" :app:testDebugUnitTest --console=plain
```

### 6.2 为什么用 Robolectric 而不是 `androidTest`

`Room.inMemoryDatabaseBuilder(context, …)` 需要真的 `Context`。
`app/src/test` 跑在 JVM 上，`android.jar` 里的方法体全是 `throw new RuntimeException("Stub!")`
—— 不套 Robolectric 一定撞 `Method ... not mocked`。

`@Config(sdk = [34])`：compileSdk 是 37，Robolectric 4.17 已注册 API 37，
但跑 37 要额外下载约 100 MB 的 android-all jar，而这份用例不碰任何 35+ 的 API。
被测的是 SQL 与 Room 生成代码，这部分在 API 26~37 上行为一致。

Context 用 `RuntimeEnvironment.getApplication()`（Robolectric 自带），
不引 `androidx.test:core`。

### 6.3 ★ 为什么全部用 `runBlocking` 而不是 `runTest`

**`runTest` 用虚拟时钟**。`withTimeoutOrNull` 在虚拟时间下会**瞬间触发超时**
（真实线程一毫秒都没走），于是 `RoomMemoryStore.flush()` 会假性超时。
而 Room 的 `InvalidationTracker` 是在**真实执行器**上异步推送的。

拿虚拟时间去等真实线程 = 必挂，而且挂得随机 —— 这就是 L1 那类"**尺子错**"。

`kotlinx-coroutines-test` 仍然在依赖里（给 net 层用），但**这个包不用它**。

### 6.4 覆盖了什么

| 文件 | 重点 |
|---|---|
| `ConversationDaoTest` | CRUD、**列表排序（置顶优先 + 时间倒序）**、`rename`/`pinned`/`archived` **不推** `updatedAt`、**外键 CASCADE 真的生效**、重复主键不静默覆盖 |
| `MessageDaoTest` | `seq` 分配（含**同毫秒碰撞**）、**游标分页边界**（整除 / 只剩一条 / 空会话 / **翻页期间插入新消息**）、偏移分页、`stopInFlight` |
| `StreamingMessageWriterTest` | **1000 条 delta 的写库次数 ≤ 40**、三条阈值都真触发、**重复 flush 幂等**、`fail` 不用错误覆盖正文、`finish` 先 flush 再改状态 |
| `RoomMemoryStoreTest` | **`put` 后 `all()` 立刻可见**、字段一个不丢（含 `hitCount`/`lastUsedAt`/`deleted`）、`load` 冷启动、**`MemoryBank.autoPromote` 真的能触发** |
| `MemorySemanticsTest` | ★ **同一组操作在 `InMemoryStore` 与 `RoomMemoryStore` 上各跑一遍，逐字段比对** |
| `ChatRepositoryTest` | 跨表约定：插消息推 `updatedAt`、空白标题兜底、完整翻页流程、崩溃恢复 |

`MemorySemanticsTest` 是这一组里最重要的：它把"语义一致"从
"我觉得一致"变成了**可执行的断言**，一旦 Room 实现在某个细节上和内存实现分叉
（`remove` 做成墓碑、`all()` 漏掉墓碑、`lastUsedAt` 没更新），
比对会立刻失败，并指出是**哪个字段**。

### 6.5 测试结果

**真实运行结果（2026-11-02 16:27，`gradle :app:testDebugUnitTest`）：**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 32s
```

```
逐类统计
com.chatmobile.data.ChatRepositoryTest              15 用例   0 失败  0 跳过
com.chatmobile.data.ConversationDaoTest             14 用例   0 失败  0 跳过
com.chatmobile.data.MemorySemanticsTest              3 用例   0 失败  0 跳过
com.chatmobile.data.MessageDaoTest                  19 用例   0 失败  0 跳过
com.chatmobile.data.RoomMemoryStoreTest             18 用例   0 失败  0 跳过
com.chatmobile.data.StreamingMessageWriterTest      20 用例   0 失败  0 跳过
─────────────────────────────────────────────────────────────────────
data 包小计                                         89 用例   0 失败  0 跳过

（同时运行的 net 包：ChatProviderStreamTest 23 / ModelsApiTest 11 /
  OpenAiStreamParserTest 26，共 60 个，也全绿。模块合计 149 用例，0 失败。）
```

### 6.6 ★ 测试真的抓到过 3 个 bug（不是摆设）

第一版跑出来 **6 个失败**，其中 **3 个是被测代码的真 bug**、3 个是测试自身的 bug。
记在这里，因为这三个都属于"不报错、只是结果错"的那一类：

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 9 条消息 / 每页 3 条时，第 3 页取完仍声称"还有下一页" | 用「返回条数 < limit ⇒ 到顶」判结尾。**正好整除时永远是错的** ⇒ 界面底部多一个永远转圈、永远加载不出的"加载更多" | 改成**多取一条**（`limit + 1`）判断是否有后继，并把游标计算收敛到 `MessagePaging.assemble` + `MessageDao.page()` 单一入口 |
| 2 | `RoomMemoryStore.all()` 两次调用顺序可能不同 | `ConcurrentHashMap.values` 是哈希顺序。两个实现跑同一组操作时，`store.all().first()` 取到了**不同条目** | `all()` 输出稳定排序（`createdAt` 降序 → `id` 升序），与 `InMemoryStore` 的 `LinkedHashMap` 插入序保持同一意图 |
| 3 | `maxDelayMs=121, pollInterval=20` 时比配置**早 1ms** flush | 整数除法 `121/20 = 6`（应向上取整为 7） | `idleTicksBeforeTimeout` 改为 `(maxDelay + pollInterval - 1) / pollInterval` |

第 1 条最值得记：它**只在大数据量整除时暴露**，开发时用 10 条数据、每页 3 条（10 = 3+3+3+1）
就会"看起来正常"。只有 `9 条 / 每页 3 条` 这种正好整除去打才现形。

第 2 条更隐蔽：它不报错、只是**顺序不确定**，而 `MemorySemanticsTest` 那种
"两个实现逐字段比对"的测试才抓得到 —— 单测各自的实现是测不出这种分叉的。

---

## 七、给上层的使用约定

```kotlin
// 1) 启动
val db = ChatDatabase.build(context)
val repo = ChatRepository(db)
repo.recoverInterruptedStreams()          // ★ 必须：修复上次被杀留下的半截消息

val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
val memoryStore = RoomMemoryStore(db.memoryDao(), memoryScope)
memoryStore.load()                        // ★ 必须：否则 all() 返回空
val memoryBank = MemoryBank(memoryStore)

// 2) 流式对话
val userMsg = repo.appendMessage(convId, MessageRole.USER, content = input)
val assistant = repo.appendMessage(convId, MessageRole.ASSISTANT, status = MessageStatus.STREAMING)
val writer = repo.streamingWriter(assistant.id)
coroutineScope {
    launch { writer.run() }
    try {
        provider.streamChat(req).collect { /* writer.append / appendReasoning */ }
        writer.finish()
    } catch (e: CancellationException) {
        writer.stop(); throw e
    } catch (e: Exception) {
        writer.fail(e.message)
    }
}

// 3) 退出
memoryStore.awaitClosed()
db.close()
```

---

## 八、已知未做（诚实清单）

| 项 | 说明 |
|---|---|
| **Migration** | 现在只有 v1，还没有任何迁移。改 schema 时必须 +1 并写迁移 + 迁移测试 |
| **`room.schemaLocation`** | `exportSchema = true` 需要这个 KSP 参数，目前没配（编译只有警告）。配好后 schema 会落在 `app/schemas/`，**要提交进仓库** |
| **全文搜索** | 会话/消息搜索需要 FTS4 虚表，按需加 |
| **`MemoryBank` 的 suspend 版本** | 现在靠"缓存 + 后台同步"桥接。若将来 `core-memory` 提供 suspend 接口，可直接去掉缓存（但**不能去掉 `load()` 契约**） |
| **消息分段存储** | 见 §2.3：超长回答（>100 KB）时 O(n²) 可能成为瓶颈，解法是分段而非增量写 |
