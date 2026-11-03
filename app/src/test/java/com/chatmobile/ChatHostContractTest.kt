package com.chatmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ChatHost 集成层的**防回归测试**。
 *
 * 这里测的不是 UI 长什么样（那要真机/仪器测试），而是几条
 * 「改错了不报错、只在特定操作下悄悄坏掉」的约定 —— 全部是真实踩过的坑。
 *
 * ## ★★ 核心手法：先剥掉注释，再扫代码
 *
 * 第一版直接对全文做正则，结果 4 条里挂了 3 条 —— **全是误报**：
 * 源码注释里为了解释"为什么不能这么写"，**原样引用了被禁止的写法**
 * （`ChatDatabase.build(`、`remember { ... }`）。
 *
 * 这正是本项目 LESSONS 里记过的坑：**"拿字面串去 grep 代码，会把注释和文档一起扫进来"**。
 * 所以这里统一先走 [stripComments]，只在**真正的代码**上做断言。
 */
class ChatHostContractTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue(
            "找不到 ${f.absolutePath} —— 测试工作目录假设不成立（Gradle 默认 cwd 是模块目录）",
            f.exists(),
        )
        stripComments(f.readText())
    }

    /**
     * 剥掉 Kotlin 的 `//` 行注释与 `/* */` 块注释（含 KDoc），**保留字符串字面量**。
     *
     * 为什么必须保留字符串：断言里要查的 `ChatDatabase.build(` 可能出现在
     * 字符串里（比如错误信息），但那属于代码行为，不是注释噪音。
     * 反过来，注释里的引用必须被剥掉，否则注释本身会让测试红。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false

        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'

            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append(c) }
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i++ }
                }
                inString -> {
                    out.append(c)
                    if (c == '\\') { // 转义：连字符一起吞
                        if (i + 1 < src.length) { out.append(next); i++ }
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '/' && next == '/' -> { inLine = true; i++ }
                c == '/' && next == '*' -> { inBlock = true; i++ }
                c == '"' -> { inString = true; out.append(c) }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun count(pattern: String): Int =
        Regex(pattern).findAll(source).count()

    private fun assertPresent(pattern: String, why: String) =
        assertTrue("$why\n（没在代码里找到 /$pattern/）", count(pattern) > 0)

    private fun assertAbsent(pattern: String, why: String) =
        assertEquals(
            "$why\n（实际匹配 $pattern 的处数：${count(pattern)}）",
            0,
            count(pattern),
        )

    // ---------------------------------------------------------------
    // 约定 1：数据库必须是**进程级单例**
    // ---------------------------------------------------------------

    /**
     * 反例（曾经就是这么写的）：
     * ```kotlin
     * val repo = remember { ChatRepository(ChatDatabase.build(context)) }
     * ```
     *
     * `remember` 跟随**组合**。Activity 一重建（旋转 / 深色模式 / 分屏 /
     * 开发者选项"不保留活动"）组合被丢弃、`remember` 重跑，
     * 而 Room 的 `build()` 每次都**新开一个到同一文件的连接**，旧的没人 close。
     *
     * ⇒ 连接泄漏；更麻烦的是多实例对同一 SQLite 并发写，
     *   WAL 下数据看着还在，但偶尔读到旧快照 —— 最难查的那类 bug。
     *
     * 注意：`ChatDatabase.kt` 的**定义**当然会有 `fun build`，所以要断言的是
     * **ChatHost 里的调用点**只有 1 处。
     */
    @Test
    fun `数据库构建只能出现在进程级单例里`() {
        assertEquals(
            "ChatHost 里 ChatDatabase.build( 只允许出现 1 次（在 RepoHolder 内）。" +
                "多了说明有人在别处又建库实例 —— 会泄漏连接。",
            1,
            count("""ChatDatabase\.build\("""),
        )

        val holderIdx = source.indexOf("private object RepoHolder")
        assertTrue("RepoHolder 不见了 —— 单例被谁改掉了？", holderIdx >= 0)

        val buildIdx = source.indexOf("ChatDatabase.build(")
        assertTrue(
            "ChatDatabase.build( 出现在 RepoHolder 之前，说明它不是被单例调用的",
            buildIdx > holderIdx,
        )
    }

    /**
     * `remember { ... ChatDatabase.build ... }` 必须彻底消失。
     *
     * 单独一条，因为它太容易在重构时被"顺手写回来" ——
     * 写回来之后**一切都能正常编译、App 也能跑**，只在旋转屏幕时才出问题。
     */
    @Test
    fun `不能再用 remember 包裹数据库构建`() {
        assertAbsent(
            """remember\s*\{[^}]*ChatDatabase\.build""",
            "检测到 `remember { ... ChatDatabase.build ... }` —— " +
                "会在 Activity 重建时重复开库并泄漏连接",
        )
    }

    // ---------------------------------------------------------------
    // 约定 2：会丢失的用户状态必须用 rememberSaveable
    // ---------------------------------------------------------------

    /**
     * | 状态 | 用 remember 的用户可见后果 |
     * |---|---|
     * | `activeId` | 在会话 B 里切深色模式，回来跳到会话 A（看起来像"串会话"） |
     * | `input` | 打到一半的话直接消失（用户会当 bug 报上来） |
     */
    @Test
    fun `activeId 与 input 必须用 rememberSaveable`() {
        assertPresent(
            """var activeId by rememberSaveable""",
            "activeId 必须用 rememberSaveable —— 否则 Activity 重建后跳到别的会话",
        )
        assertPresent(
            """var input by rememberSaveable""",
            "input 必须用 rememberSaveable —— 否则重建后用户打了一半的字消失",
        )
    }

    /**
     * 消息列表**不能**进 Bundle：Bundle 有 1MB 事务上限，
     * 长对话塞进去会 `TransactionTooLargeException`。它们从 Room 重新加载。
     */
    @Test
    fun `消息列表不能进 Bundle`() {
        assertAbsent(
            """var messages by rememberSaveable""",
            "messages 不能用 rememberSaveable —— 长对话会 TransactionTooLargeException",
        )
        assertAbsent(
            """var conversations by rememberSaveable""",
            "conversations 不能用 rememberSaveable —— 同上",
        )
    }

    // ---------------------------------------------------------------
    // 约定 3：崩溃恢复只能在冷启动跑一次
    // ---------------------------------------------------------------

    /**
     * `recoverInterruptedStreams()` 的语义是"把上次**进程被杀**遗留的
     * streaming/pending 行标成 stopped"。
     *
     * 它**不该**在 Activity 重建时再跑：那会把「本次会话里正在流式输出」的那条
     * 也标成 stopped —— 用户旋转一下屏幕，正在生成的回答就被判定为"已中断"。
     */
    @Test
    fun `崩溃恢复必须被冷启动标记包住`() {
        assertPresent("""coldStarted""", "必须有 coldStarted 标记区分冷启动与 Activity 重建")

        val recoverIdx = source.indexOf("recoverInterruptedStreams()")
        val guardIdx = source.indexOf("if (!coldStarted.value)")

        assertTrue("recoverInterruptedStreams() 不见了", recoverIdx >= 0)
        assertTrue("冷启动判断不见了", guardIdx >= 0)
        assertTrue(
            "recoverInterruptedStreams() 必须在 coldStarted 判断**内部**，" +
                "否则每次旋转屏幕都会把正在生成的回答标成已中断",
            recoverIdx > guardIdx,
        )
    }

    // ---------------------------------------------------------------
    // 约定 4：收尾必须走 StreamingMessageWriter
    // ---------------------------------------------------------------

    /**
     * `ChatRepository.kt:176-179` 明确写了：不要绕过 writer 直接 setContent。
     * writer 内部维护 `dirty` 标记，绕过去写会让它以为"还没写过"，
     * 下一次 flush 再把旧快照盖回去 —— 表现为"内容突然回退"。
     *
     * 这条用源码扫描守住，因为它是**跨模块的约定**，编译器管不了。
     */
    @Test
    fun `流式收尾必须经过 streamingWriter`() {
        assertPresent(
            """repo\.streamingWriter\(""",
            "收尾必须用 repo.streamingWriter(...)，否则 writer 的 dirty 标记会失效",
        )
        assertAbsent(
            """messageDao\(\)\s*\.\s*setContent""",
            "不允许直接调 messageDao().setContent —— 绕过 writer 会让 dirty 标记失效",
        )
    }

    // ---------------------------------------------------------------
    // 约定 5：压缩产物必须写进记忆库（缺口 1 的防回归）
    // ---------------------------------------------------------------

    /**
     * ★★★ 用户原话：「……**同时也**利用它的压缩去把东西写进记忆库里面」。
     *
     * 这条曾经**一个字都没实现**：`maybeCompress` 只 upsert `compressed_spans`，
     * 完全不碰 `memoryBank`。摘要因此只在本次会话的 system 前缀里活着，
     * 换一个新对话就再也见不到 —— 而记忆库恰恰是跨会话的那一层。
     *
     * 这个 bug 的可怕之处：**编译通过、运行正常、界面上看不出任何异常**，
     * 只是用户要的那个能力根本不存在。所以必须用测试钉住。
     */
    @Test
    fun `压缩完成后必须把摘要写进记忆库`() {
        val fn = extractFunction("suspend fun maybeCompress(")
        assertTrue("maybeCompress 不见了", fn.isNotBlank())

        assertPresent(
            """memoryBank\.recordRecent\(""",
            "maybeCompress 必须把摘要写进记忆库（memoryBank.recordRecent）—— " +
                "不写的话摘要换会话就消失，用户要的「压缩写进记忆库」等于没做",
        )
        assertTrue(
            "memoryBank.recordRecent( 必须在 maybeCompress 函数体内",
            fn.contains("memoryBank.recordRecent("),
        )
    }

    // ---------------------------------------------------------------
    // 约定 6：记忆预算必须跟着上下文上限走（缺口 2 的防回归）
    // ---------------------------------------------------------------

    /**
     * 以前 `composeContext(MEMORY_CONTEXT_BUDGET_TOKENS)` 里那个常量写死 **32000**，
     * 于是用户把上下文上限调到 1M 时，压缩触发点跟到了 700K，
     * 而记忆那一块仍按 32K 的 25% 算（8000 token）—— 窗口涨 31 倍，记忆预算一点没涨。
     */
    @Test
    fun `记忆预算必须按真实窗口算而不是常量`() {
        // ★ 尺子修正（不是放宽约束）：调用改成了具名参数
        //   `composeContext(totalContextTokens = effectiveMemoryBudget(active), query = prompt)`，
        //   原正则只认「紧邻括号」那一种写法，会误报。
        //   被保护的**约束**没变：预算必须来自 effectiveMemoryBudget，不能是常量。
        assertPresent(
            """totalContextTokens = effectiveMemoryBudget\(""",
            "composeContext 的预算必须来自 effectiveMemoryBudget(...) —— " +
                "传常量会让大窗口模型的记忆预算永远停在 32000 派生值",
        )
        assertAbsent(
            """composeContext\(MEMORY_CONTEXT_BUDGET_TOKENS""",
            "不能再用写死的 MEMORY_CONTEXT_BUDGET_TOKENS 调 composeContext",
        )
        assertAbsent(
            """val MEMORY_CONTEXT_BUDGET_TOKENS""",
            "那个写死的常量本身也该没了（已由 MEMORY_BUDGET_RATIO + effectiveMemoryBudget 取代）",
        )
    }

    // ---------------------------------------------------------------
    // 约定 7：压缩判断必须把记忆 + 摘要自身的 token 算进去（缺口 3）
    // ---------------------------------------------------------------

    /**
     * 以前 `usedTokens` 只算 `allMessages` 的正文，**没算记忆、也没算摘要本身**，
     * 可这两块每一轮都真的发出去 ⇒ 极端情况实际已超窗，压缩却认为还没到线。
     */
    @Test
    fun `压缩判断必须计入 system 前缀开销`() {
        val fn = extractFunction("suspend fun maybeCompress(")
        assertTrue("maybeCompress 不见了", fn.isNotBlank())

        assertTrue(
            "maybeCompress 必须有 systemOverheadTokens 参数",
            fn.contains("systemOverheadTokens"),
        )
        assertTrue(
            "usedTokens 必须是 正文 + system 前缀（不能只算正文）",
            fn.contains("systemOverheadTokens"),
        )

        // 发送路径必须真的把记忆与摘要的代价传进去
        assertPresent(
            """systemOverheadTokens\s*=""",
            "调用 maybeCompress 时必须传入 systemOverheadTokens —— " +
                "不传的话压缩永远看不见记忆和摘要占了多少窗口",
        )
    }

    /**
     * 取一个函数的**函数体**（从声明到配对的右花括号）。
     *
     * ★ 为什么不能用 `Regex("\\.maybeCompress\\(")` 之类：会同时命中定义与调用，
     *   而且跨函数匹配时容易把相邻代码算进来。这里按花括号配对切，最可靠。
     */
    private fun extractFunction(declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) return ""
        var i = source.indexOf('{', start)
        if (i < 0) return ""
        val bodyStart = i
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        return ""
    }

    // ---------------------------------------------------------------
    // 约定 8：消息顺序必须对缓存友好（前缀稳定性）
    // ---------------------------------------------------------------

    /**
     * ★★★ 所有主流厂商的 prompt 缓存都是**从第一个 token 起逐字节比对最长公共前缀**。
     *
     * 硬规矩：**越靠前的内容必须越稳定。**
     *
     * 我们曾经把记忆文本放在第一位（`system(memoryText)` 在最前），
     * 而记忆**每轮必变**（`touch=true` 会改 `lastUsedAt`/`hitCount`，
     * 还有衰减权重、新抽取条目）⇒ 每轮整个 system 前缀失效，
     * **连带后面整段历史一起无法命中缓存**。这是最贵的排序错误。
     *
     * 正确顺序：固定提示词 → 压缩摘要（只追加）→ 历史（只追加）→ 记忆（每轮变）→ 最后一条。
     */
    @Test
    fun `固定系统提示词必须是第一条消息`() {
        val idx = source.indexOf("SystemPrompt.STATIC")
        assertTrue(
            "ChatHost 必须引用 SystemPrompt.STATIC 作为固定系统提示词",
            idx >= 0,
        )

        val addIdx = source.indexOf("add(NetChatMessage.system(com.chatmobile.prompt.SystemPrompt.STATIC))")
        assertTrue(
            "第一条消息必须是 system(SystemPrompt.STATIC) —— " +
                "固定提示词必须做整个前缀的基座，否则每轮都要重算全部 system",
            addIdx >= 0,
        )
    }

    /**
     * 记忆**不能**出现在固定提示词与历史之前。
     *
     * 判据：`system(memoryText)` 的位置必须**晚于** `addAll(historyForRequest.subList`。
     */
    @Test
    fun `记忆必须排在历史之后而不是最前面`() {
        val staticIdx = source.indexOf("SystemPrompt.STATIC")
        val historyIdx = source.indexOf("historyForRequest.subList")
        val memoryIdx = source.indexOf("add(NetChatMessage.system(memoryText))")

        assertTrue("找不到固定提示词", staticIdx >= 0)
        assertTrue("找不到历史插入点", historyIdx >= 0)
        assertTrue("找不到记忆插入点", memoryIdx >= 0)

        assertTrue(
            "记忆必须排在固定提示词之后（它每轮都变，放前面会让整个前缀失效）",
            memoryIdx > staticIdx,
        )
        assertTrue(
            "记忆必须排在历史之后 —— 曾经放最前，导致每轮整段历史都无法命中缓存",
            memoryIdx > historyIdx,
        )
    }

    /**
     * 固定提示词**不许**掺入每轮会变的内容。
     *
     * 最典型的错误是把当前时间拼进 system（"当前时间：2026-11-03 01:55"）——
     * 看着贴心，实际等于宣布缓存永久失效。
     */
    @Test
    fun `固定提示词里不能有动态内容`() {
        val promptFile = File("src/main/java/com/chatmobile/prompt/SystemPrompt.kt")
        assertTrue(
            "找不到 ${promptFile.absolutePath} —— 提示词文件被移动了？",
            promptFile.exists(),
        )
        val promptSource = stripComments(promptFile.readText())

        // 模板插值 / 时间来源都不许出现在常量里
        assertFalse(
            "固定提示词里不许有字符串模板插值（\${...}）—— 那是「每轮都会变」的信号",
            Regex("""\$\{""").containsMatchIn(promptSource),
        )
        assertFalse(
            "固定提示词里不许取当前时间 —— 会让整个 system 前缀每轮失效",
            Regex("""currentTimeMillis|LocalDateTime|Instant\.now|System\.nanoTime""")
                .containsMatchIn(promptSource),
        )
    }

    // ---------------------------------------------------------------
    // 约定 9：运行时事实必须是**最后一条**消息
    // ---------------------------------------------------------------

    /**
     * 用户原话：
     * > 「我给APP开权限让它可读时间地点等必要信息，你加一下，自动写在提示词最后」
     *
     * 为什么必须是**最后一条**（三条独立证据的交集）：
     * - OpenAI 官方最佳实践原话：「put dynamic content at the end rather than the beginning」；
     * - Gemini CLI 的 `renderFinalShell` 把动态内容拼在最末；
     * - DSH 把 runtime context 放在 order 10000+（最后）。
     *
     * ★ 而时间是**每轮必变**的东西（分钟级），比记忆变得还快。
     *   它一旦排到记忆前面，记忆那块也会跟着失去前缀 ⇒ 越靠后越好。
     */
    @Test
    fun `运行时事实必须排在最后一条`() {
        // ★ 锚点跟着结构走：接工具链路后它从 `buildList { }` 改成
        //   `mutableListOf<NetChatMessage>().apply { }`（工具循环要在循环内往里追加）。
        val body = extractFunctionFrom(source, "val requestMessages = mutableListOf<NetChatMessage>().apply {")
            .ifBlank { extractFunctionFrom(source, "val requestMessages = buildList {") }
        assertTrue("找不到 requestMessages 的组装块", body.isNotBlank())

        val factsIdx = body.indexOf("RuntimeFactsProvider.collect")
        assertTrue(
            "requestMessages 里必须注入运行时事实（RuntimeFactsProvider.collect）",
            factsIdx >= 0,
        )

        // 它必须晚于：固定提示词、压缩摘要、历史、记忆
        val staticIdx = body.indexOf("SystemPrompt.STATIC")
        val memoryIdx = body.indexOf("system(memoryText)")
        val lastMsgIdx = body.indexOf("historyForRequest[lastIdx]")

        assertTrue("固定提示词缺失", staticIdx >= 0)
        assertTrue("记忆注入点缺失", memoryIdx >= 0)
        assertTrue("最后一条历史缺失", lastMsgIdx >= 0)

        assertTrue(
            "运行时事实必须排在固定提示词之后",
            factsIdx > staticIdx,
        )
        assertTrue(
            "运行时事实必须排在记忆之后 —— 时间比记忆变得更频繁，它若在前，记忆也一起失效",
            factsIdx > memoryIdx,
        )
        assertTrue(
            "运行时事实必须排在最后一条真实消息之后（用户要的就是「写在提示词最后」）",
            factsIdx > lastMsgIdx,
        )
    }

    /**
     * 运行时事实**不许**混进固定提示词常量。
     *
     * 这是上一代踩过的同一个坑：把 `当前时间：...` 拼进 system 常量，
     * 等于每轮让整个前缀失效（真机实测过代价：缓存从 95% 掉到 0%）。
     */
    @Test
    fun `运行时事实不能写进固定提示词常量`() {
        val promptFile = File("src/main/java/com/chatmobile/prompt/SystemPrompt.kt")
        assertTrue("找不到 ${promptFile.absolutePath}", promptFile.exists())
        val statics = extractStatics(promptFile.readText())

        assertFalse(
            "固定提示词常量里不许出现具体时钟/时区取值 —— 那是每轮会变的内容，要放最后一条消息",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(statics),
        )
        assertFalse(
            "固定提示词常量里不许读取系统时区/语言",
            Regex("""ZoneId\.systemDefault|Locale\.getDefault""").containsMatchIn(statics),
        )
    }

    /**
     * 版本号必须写进**正文首行**，不能只做 Kotlin 常量。
     *
     * ★ 报告一的头号建议：常量不进提示词 ⇒ 模型看不到 ⇒ 无法自证「我收到的是 v2」。
     *   而用户曾因此误判「模型没收到提示词」（L17 那次）。
     */
    @Test
    fun `版本号必须写进提示词正文`() {
        val promptFile = File("src/main/java/com/chatmobile/prompt/SystemPrompt.kt")
        assertTrue("找不到 ${promptFile.absolutePath}", promptFile.exists())
        val raw = promptFile.readText()

        // 常量声明
        val versionDecl = Regex("""const val VERSION: String = "(v\d+)"""").find(raw)
        assertTrue("SystemPrompt 必须声明 VERSION 常量", versionDecl != null)
        val version = versionDecl!!.groupValues[1]

        val statics = extractStatics(raw)
        assertTrue(
            "提示词正文首行必须带上版本号 $version —— 否则模型看不到自己收到的是哪一版",
            statics.contains(version),
        )
    }

    // ---------------------------------------------------------------
    // 约定 9：流式期间用户拖拽必须绝对静默
    // ---------------------------------------------------------------

    /**
     * 用户原话（真机反馈）：
     * > 「因为流式输出的存在，导致在输出的过程中往上滑，它会自动继续往下滑，
     * >  结果不会回到底部。但是呢依旧会以同样的速度往下滑，
     * >  导致我无法定位到上面的一个固定位置。」
     *
     * 根因：手指按着时内容仍在增长，`onContentGrew` 把列表往下拽，
     * 用户的手指往上推 —— 两个力打架，位置以恒定速度漂移。
     *
     * ★ 只靠 `autoFollow` 短路**不够**：`onDragEnding()` 会在松手瞬间用
     *   `isAtBottom` 重算，而流式期间 `isAtBottom` 随时可能变 true，
     *   跟随就被重新打开了。所以必须有"手指是否按下"的硬闸。
     *
     * `AutoFollowState` 依赖 `LazyListState`，JVM 单测里构造不出来，
     * 所以用源码扫描守住这个约定。
     */
    @Test
    fun `拖拽期间必须拒绝自动滚动`() {
        val f = File("src/main/java/com/chatmobile/ui/chat/AutoFollow.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())

        val body = extractFunctionFrom(src, "fun onContentGrew()")
        assertTrue("找不到 onContentGrew()", body.isNotBlank())

        // 判据：函数体里必须出现 isUserDragging 的短路，且在第一处 requestScrollToItem 之前
        val dragIdx = body.indexOf("isUserDragging")
        val scrollIdx = body.indexOf("requestScrollToItem")
        assertTrue(
            "onContentGrew() 必须以 isUserDragging 短路 —— " +
                "否则流式期间用户拖拽时列表会被往下拽，导致定不住位置",
            dragIdx >= 0,
        )
        assertTrue(
            "isUserDragging 判断必须在 requestScrollToItem 之前",
            dragIdx < scrollIdx,
        )

        // ★★★ 本轮新增：粘性标记 userScrolledAway 也必须是硬闸。
        //
        //   用户第二次报同一个 bug：「在输出过程中我向上滑动后整个页面依旧会跟着向下滚动」。
        //   前两版分别靠 `!autoFollow` 和 `isUserDragging`，**都不够**：
        //   `onDragEnding` 会用 isAtBottom 重算，而流式期间内容在长、
        //   isAtBottom 随时可能瞬间为 true ⇒ 跟随被重新打开。
        //
        //   ⇒ 必须有"用户主动离开过底部"这个**不因内容增长而撤销**的粘性标记。
        assertTrue(
            "onContentGrew() 必须同时以 userScrolledAway 短路 —— " +
                "只靠 isUserDragging 挡不住「松手瞬间 isAtBottom 为 true」这条路",
            body.contains("userScrolledAway"),
        )
        assertTrue(
            "userScrolledAway 判断也必须在 requestScrollToItem 之前",
            body.indexOf("userScrolledAway") < scrollIdx,
        )
    }

    /**
     * 松手时**不能**用 `isAtBottom` 无条件恢复跟随。
     *
     * ★ 这是「向上滑之后依旧往下滚」的真正根因：
     *   流式期间内容在长，`isAtBottom` 随时可能瞬间为 true，
     *   用它当恢复条件 = 用户永远拽不回去。
     *
     *   恢复跟随只该有两种触发：用户自己滚回底部、或点「回到底部」。
     */
    @Test
    fun `松手不得用当前位置恢复跟随`() {
        val f = File("src/main/java/com/chatmobile/ui/chat/AutoFollow.kt")
        val src = stripComments(f.readText())
        val body = extractFunctionFrom(src, "fun onDragEnding()")
        assertTrue("找不到 onDragEnding()", body.isNotBlank())

        assertFalse(
            "onDragEnding() 里不能出现 `autoFollow = isAtBottom` —— " +
                "流式期间它随时为 true，会把用户刚推上去的视图又拽回来",
            Regex("""autoFollow\s*=\s*isAtBottom""").containsMatchIn(body),
        )
    }

    /**
     * 思考过程的「收起」必须真的能收起来。
     *
     * ★ 用户报：「我现在收起不了思考过程了」。
     *   根因：`remember(reasoning)` 把**推理文本本身**当 key，
     *   而流式期间每来一个 token 它就变 ⇒ `manualOverride` 每帧被重置回 null
     *   ⇒ 点了「收起」下一帧又回到全局档位（默认完全展开）。
     */
    @Test
    fun `思考折叠状态不能被流式文本重置`() {
        val f = File("src/main/java/com/chatmobile/ui/chat/MessageBubble.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())

        assertFalse(
            "manualOverride 的 remember **不能**带 reasoning 作 key —— " +
                "流式期间 reasoning 每帧都在变，会把用户的折叠操作每帧重置掉",
            Regex("""remember\s*\(\s*reasoning\s*\)""").containsMatchIn(src),
        )
        assertTrue(
            "必须存在 manualOverride 状态（否则没法对单条消息临时展开/收起）",
            src.contains("manualOverride"),
        )
    }

    /**
     * ★★★ **「思考过程的档位设置」必须真的送到界面** —— 这条是第三次报 bug 的真因。
     *
     * ## 为什么前两次修都没打中
     *
     * 用户报了三次「收不回去」。前两次我改的都是**点击处理**（`remember` 的 key、
     * 状态放哪儿），但真正的问题在**更上游**：
     *
     * ```
     * SettingsStore.reasoningDisplay()  ← suspend getter 写好了
     * SettingsStore.setReasoningDisplay()  ← setter 也写好了
     * ChatHost → ChatScreen(reasoningDisplay = ???)  ← ★ 从来没传过
     * ```
     *
     * 结果：`ChatScreen` 只能吃默认值 `ReasoningDisplay.DEFAULT`（Standard = 全展开）。
     * **设置项存在、能被写、但没有任何东西读它** ⇒ 用户在设置里怎么改都没反应。
     *
     * 这正是本项目反复踩的那类错：「机制都在，唯独连接不存在」。
     * 前两次的测试只盯了组件内部（`remember` 的 key），够不着"这条线有没有接上"。
     *
     * ⇒ 这条测试直接对**调用点**断言：`ChatHost` 里调 `ChatScreen` 时必须显式传
     *   `reasoningDisplay`，且值来自 `settingsState`（而不是硬编码常量）。
     */
    @Test
    fun `思考档位设置必须送到 ChatScreen（不能只存不读）`() {
        val host = File("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue("找不到 ${host.absolutePath}", host.exists())
        val src = stripComments(host.readText())

        // ★ 不能直接用 extractFunctionFrom(src, "ChatScreen(") —— 它按**第一个**
        //   出现的 `ChatScreen(` 切片，而文件顶部有
        //   `import com.chatmobile.ui.chat.ChatScreen`（带左括号的是 import 里的
        //   包名那一行不会有 `{`），命中的是 import 行 ⇒ 取到空串 ⇒ 测试假红。
        //   ⇒ 显式定位**调用点**：`ChatScreen(` 后面紧跟换行 + 具名实参。
        val callIdx = Regex("""\n\s*ChatScreen\(""").find(src)?.range?.first ?: -1
        assertTrue("ChatHost 里找不到 ChatScreen( 的调用点", callIdx >= 0)
        val callBody = extractBalancedParens(src, callIdx + src.substring(callIdx).indexOf('('))
        assertTrue("取不到 ChatScreen 的实参区", callBody.isNotEmpty())

        assertTrue(
            "ChatScreen 调用必须显式传 reasoningDisplay —— " +
                "不传就用默认值，设置页改了也毫无作用（这就是用户报的那条 bug）",
            "reasoningDisplay = settingsState." in callBody,
        )
        assertTrue(
            "ChatScreen 调用必须显式传 reasoningAutoCollapse（收起时机同一类问题）",
            "reasoningAutoCollapse =" in callBody,
        )
        assertTrue(
            "字号设置同样必须送到界面",
            "bodyFontSize = settingsState." in callBody,
        )
    }

    /** 从 `(` 的位置开始按圆括号配对，返回括号内的原文。 */
    private fun extractBalancedParens(src: String, openIdx: Int): String {
        if (openIdx !in src.indices || src[openIdx] != '(') return ""
        var depth = 0
        for (i in openIdx until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(openIdx, i + 1)
                }
            }
        }
        return ""
    }

    /**
     * ★★★ **主题设置必须真的驱动主题** —— 与上一条同源的另一处断线。
     *
     * `MainActivity` 原来写的是 `ChatMobileTheme { ... }`，走
     * `isSystemInDarkTheme()` 默认值 ⇒ 用户在「通用 → 外观」里选浅色/深色
     * **毫无作用**。设置存下来了，读取它的那条线不存在。
     */
    @Test
    fun `主题设置必须驱动 ChatMobileTheme`() {
        val f = File("src/main/java/com/chatmobile/MainActivity.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = stripComments(f.readText())

        assertTrue(
            "MainActivity 必须用 resolveDarkTheme(...) 把设置解析成深色与否 —— " +
                "直接写 ChatMobileTheme { } 会让「外观」设置形同虚设",
            "resolveDarkTheme(" in src,
        )
        assertTrue(
            "必须从 SettingsStore 的 stateFlow 取主题模式（否则改了不生效）",
            "themeMode" in src,
        )
    }

    /**
     * ★★★ **思考过程的覆盖状态必须存在 item 之外** —— 这是「收不回去」的第二层真因。
     *
     * ```
     * 消息滚出屏幕 → LazyColumn 回收 item 组合实例 → 气泡里的 remember 丢失
     * → manualOverride 回到 null → 又按全局档位（展开）渲染
     * ```
     *
     * 表现：单条点收起**当时**生效（真机截图验过），但往上一翻再回来就弹开。
     * ⇒ 状态必须由 `ChatScreen` 持有（跨 item 存活），通过参数传下去。
     */
    @Test
    fun `思考覆盖状态必须由 ChatScreen 持有而不是气泡内部 remember`() {
        val bubble = File("src/main/java/com/chatmobile/ui/chat/MessageBubble.kt")
        val screen = File("src/main/java/com/chatmobile/ui/chat/ChatScreen.kt")
        assertTrue(bubble.exists() && screen.exists())

        val bubbleSrc = stripComments(bubble.readText())
        assertFalse(
            "气泡内部**不能**自己 remember 覆盖状态 —— LazyColumn 回收 item 时会丢，" +
                "丢了就回到全局档位（展开），用户看到的就是「收不回去」",
            Regex("""remember\s*\{[^}]*mutableStateOf<Boolean\?""")
                .containsMatchIn(bubbleSrc),
        )
        assertTrue(
            "气泡必须通过参数接收覆盖状态",
            "reasoningOverride" in bubbleSrc,
        )

        val screenSrc = stripComments(screen.readText())
        assertTrue(
            "ChatScreen 必须持有跨 item 存活的覆盖状态 Map",
            "reasoningOverrides" in screenSrc,
        )
        assertTrue(
            "覆盖状态 Map 必须是 mutableStateMapOf（要参与重组）",
            "mutableStateMapOf" in screenSrc,
        )
    }

    /**
     * 只取 `SystemPrompt.kt` 里 **STATIC 那个字符串字面量**（含 trimIndent 之前的原文），
     * 不含 KDoc / 行注释。
     *
     * ★ 为什么要精细到这程度：前几版测试对**整个文件**做正则，
     *   而 KDoc 里为了解释「为什么不能写日期」原样引用了 `"当前时间：2026-11-03 01:55"`
     *   —— 于是注释让测试红，而代码其实是对的（这个坑在文件头注释里记过）。
     *   断言必须落在**真正会发给模型的那段文本**上。
     */
    private fun extractStatics(src: String): String {
        val code = stripComments(src)
        val marker = "val STATIC: String =\n"
        var start = code.indexOf(marker)
        if (start < 0) {
            // 兼容写成一行 / 分隔符不同的情况
            start = code.indexOf("val STATIC")
            if (start < 0) return ""
        }
        val triple = code.indexOf("\"\"\"", start)
        if (triple < 0) return ""
        val end = code.indexOf("\"\"\"", triple + 3)
        if (end < 0) return ""
        return code.substring(triple + 3, end)
    }

    /**
     * 从**任意源码字符串**里按花括号配对取函数体（[extractFunction] 的通用版）。
     */
    private fun extractFunctionFrom(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        if (start < 0) return ""
        var i = src.indexOf('{', start)
        if (i < 0) return ""
        val bodyStart = i
        var depth = 0
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        return ""
    }
}
