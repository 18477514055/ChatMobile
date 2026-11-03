package com.chatmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **思考过程必须落库**的契约测试。
 *
 * ## ★★★ 这条测试是为了一个真实的用户投诉而写的
 *
 * 用户原话：
 * > 「现在虽然可以打开思考过程，但是只能看到最近的几条对话的思考，
 * >  还会迅速失效导致无法查看以前对话的思考过程。」
 *
 * 「迅速失效」听起来像缓存/LRU 过期，**实际是从来没存下来**。读码定位到两处：
 *
 * 1. `ChatEvent.Reasoning` 分支**只改内存里的 messages**，从没调过
 *    `writer.appendReasoning()` ⇒ 落库时 reasoning 永远是 null。
 * 2. 收尾时 `writer.resetContent(finalReply)` 的 `newReasoning` 默认 null，
 *    而 `StreamingMessageWriter:209` 会执行 `reasoning.setLength(0)`
 *    ⇒ 即使第 1 处修好了，收尾那一刻仍会把自己刚存的思考清空。
 *
 * 表现正好对应用户所见：
 * - 本轮进行中 —— 内存里有，看得到
 * - 切会话 / 重启 —— 从库读回来是 null，没了
 * - "最近几条还能看" —— 那些还在当前进程的内存列表里
 *
 * ⇒ 这类 bug **编译能过、运行不报错、只有真机用一阵子才发现**，
 *   所以必须用源码扫描钉死两个调用点。
 */
class ReasoningPersistenceContractTest {

    private val chatHost: String by lazy {
        val f = File("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        stripComments(f.readText())
    }

    /** 剥注释再断言 —— 否则注释里为了解释而引用的代码会让测试红（本项目踩过）。 */
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
                inLine -> { if (c == '\n') { inLine = false; out.append(c) } }
                inBlock -> { if (c == '*' && next == '/') { inBlock = false; i++ } }
                inString -> {
                    out.append(c)
                    if (c == '\\') { if (i + 1 < src.length) { out.append(next); i++ } }
                    else if (c == '"') inString = false
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> inBlock = true
                c == '"' -> { inString = true; out.append(c) }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

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

    @Test
    fun `Reasoning 事件必须同时写进 writer`() {
        val body = extractFunctionFrom(chatHost, "suspend fun runStreaming(")
        assertTrue("找不到 runStreaming", body.isNotBlank())

        // 找到 Reasoning 那一支
        val reasoningBranch = body.indexOf("is ChatEvent.Reasoning ->")
        assertTrue("找不到 Reasoning 分支", reasoningBranch >= 0)

        // ★ 取到**下一个 when 分支**为止，而不是固定字符数。
        //   第一版用了 600 字符窗口，被中间那段解释"为什么之前漏了"的注释挤爆了 ——
        //   测试红了但代码是对的。**这是尺子错，不是实现错**（LESSONS 里记过同类坑）。
        val nextBranch = listOf(
            "is ChatEvent.ToolCalls ->",
            "is ChatEvent.Error ->",
            "is ChatEvent.Finish",
        ).map { body.indexOf(it, reasoningBranch) }
            .filter { it > reasoningBranch }
            .minOrNull() ?: body.length

        val slice = body.substring(reasoningBranch, nextBranch)
        assertTrue("分支切片为空", slice.isNotBlank())

        assertTrue(
            "★ Reasoning 分支必须调用 writer.appendReasoning(ev.text) —— " +
                "只改内存 messages 的话，落库时 reasoning 永远是 null，" +
                "切会话/重启就看不到思考过程了（这正是用户报的 bug）",
            slice.contains("writer.appendReasoning"),
        )
    }

    @Test
    fun `收尾 resetContent 必须把 reasoning 一起传进去`() {
        val body = extractFunctionFrom(chatHost, "suspend fun runStreaming(")
        assertTrue("找不到 runStreaming", body.isNotBlank())

        // 找收尾那次 resetContent（带 finalReply 的那一行）
        val idx = body.indexOf("writer.resetContent(finalReply")
        assertTrue("找不到收尾的 writer.resetContent(finalReply...)", idx >= 0)

        val line = body.substring(idx, minOf(idx + 120, body.length)).substringBefore('\n')

        assertTrue(
            "★ 收尾 resetContent 必须传第二个参数（完整 reasoning）。" +
                "resetContent(content, newReasoning = null) 会执行 reasoning.setLength(0)，" +
                "把刚存下的思考整段抹掉 —— 这是「迅速失效」的第二个原因。当前行：$line",
            line.contains(",") && (line.contains("Reasoning") || line.contains("reasoning")),
        )
    }

    @Test
    fun `不能只在内存里累积推理而不落库`() {
        // 反证：如果整个文件里只有 appendReasoning 的定义、没有调用点，说明链路断了
        val calls = Regex("""writer\.appendReasoning\(""").findAll(chatHost).count()
        assertTrue(
            "ChatHost 里必须有 writer.appendReasoning(...) 的调用点（当前 $calls 处）",
            calls >= 1,
        )
    }

    @Test
    fun `思考过程的展开档位是全局设置而不是每条各自记`() {
        // ★ 用户要求「按 DSH 设计思考过程的展开程度」：
        //   DSH 是**一个下拉档位**作用于所有消息，不是每条各有开关。
        //   每条各自记 ⇒ 一屏半开半合，翻历史要逐条点。
        val f = File("src/main/java/com/chatmobile/ui/chat/ReasoningDisplay.kt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        val src = f.readText()

        assertFalse(
            "档位不能只有两态（展开/收起）—— 用户要的是 DSH 那四档",
            Regex("""^\s*(OPEN|CLOSED)\s*,?\s*$""", RegexOption.MULTILINE).containsMatchIn(src),
        )
        // 四档必须都在
        for (name in listOf("Collapsed", "Compact", "Standard", "Expanded")) {
            assertTrue("缺少档位 $name", src.contains(name))
        }
    }
}
