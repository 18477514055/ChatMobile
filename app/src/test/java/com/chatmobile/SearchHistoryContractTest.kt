package com.chatmobile

import chatmobile.tools.ChatTools
import chatmobile.tools.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **历史检索工具**（`search_history`）的契约测试。
 *
 * ## ★★★ 用户定的设计（这是判据，不是我的偏好）
 *
 * > 「我的想法是，现有的注入规则不变，当我提到时模型再去查且能查到」
 *
 * 拆成两条：
 * 1. **注入规则不变** —— 记忆抽取提示词、注入预算一律不动。
 *    历史**不进常驻上下文**。
 * 2. **提到时再查** —— 能力以工具形式暴露，模型按需调用。
 *
 * ## 为什么这个方向是对的（值得写下来）
 *
 * 把历史也注进去，等于每次请求都为"用户**可能**问到的任何历史"付 token；
 * 而按需查询只在**真的问到时**付一次。前者是常驻成本，后者是边际成本。
 *
 * 但按需查询有个反面：**模型不知道有这个工具就永远不会用**。
 * ⇒ 工具的描述必须把使用场景写具体（"上次那个 bug"、"上周的结论"），
 *   否则等于没加。这条用测试钉住。
 */
class SearchHistoryContractTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ---------------------------------------------------------------
    // 工具本身
    // ---------------------------------------------------------------

    @Test
    fun `历史检索必须存在且只读`() {
        val def = ChatTools.byName("search_history")
        assertNotNull("必须有 search_history 工具", def)
        assertEquals(
            "查历史不该改任何东西",
            ToolKind.READ_ONLY,
            def!!.kind,
        )
        assertFalse(
            "读历史不该弹确认框（用户原话：任何读取都不用审批）",
            def.requiresConfirmation,
        )
        assertTrue("应当默认开启 —— 这是能力的默认暴露", def.enabledByDefault)
    }

    @Test
    fun `工具顺序只能追加在末尾`() {
        val names = ChatTools.all().map { it.name }
        assertEquals(
            "search_history 必须追加在列表**末尾** —— " +
                "工具表排在 system 之前，中间插入会让后面每个工具的缓存位置平移",
            "search_history",
            names.last(),
        )
    }

    /**
     * 描述必须写出**具体场景**。
     *
     * ★ 模型不会凭空想到用一个它从没见过的工具。描述里如果不提
     *   「上次那个 bug」「上周的结论」这类原话，它永远不会主动调用 ——
     *   那这个工具等于没加。
     */
    @Test
    fun `描述必须写清什么时候该用它`() {
        val def = ChatTools.byName("search_history")!!
        val desc = def.spec.description.lowercase()
        assertTrue(
            "描述必须提到「过去的对话」这类场景",
            desc.contains("past conversation") || desc.contains("earlier chat"),
        )
        assertTrue(
            "描述必须给出具体例子（上次让你修的东西 / 上周在做什么）",
            desc.contains("bug") || desc.contains("last week"),
        )
        // ★ 必须说清是关键词而不是语义，否则模型搜不到同义词会断言"不存在"
        assertTrue(
            "必须说明匹配是**关键词**而非语义 —— " +
                "不然模型换词失败后会告诉用户「你没做过这件事」",
            desc.contains("keyword"),
        )
        assertTrue(
            "必须说明搜不到应该换词重试，而不是断定不存在",
            desc.contains("retry") || desc.contains("different"),
        )
    }

    // ---------------------------------------------------------------
    // 注入规则**不变**（这是用户明确要求的）
    // ---------------------------------------------------------------

    /**
     * 记忆抽取提示词里那条「禁止复述对话内容」必须**保留**。
     *
     * ★ 这是本设计的另一半：用户要的是"提到时再查"，
     *   而不是"把历史也塞进记忆库"。
     *   如果将来有人为了让模型"记住做过什么"而删掉这条规则，
     *   记忆库就会被对话摘要灌满、注入预算被吃掉、而模型依然答不准细节
     *   （摘要里的"做了什么"远不如原文精确）。
     *   ⇒ 这条规则与 search_history 是**互补**的，不是可以互相替代的。
     */
    @Test
    fun `记忆抽取仍然禁止复述对话内容`() {
        val s = src("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue(
            "必须保留「禁止复述对话内容」这条规则 —— " +
                "历史靠 search_history 按需查，不进记忆库",
            s.contains("禁止复述对话内容"),
        )
        assertTrue(
            "必须保留「记忆是关于用户的事实，不是聊天摘要」这句判据",
            s.contains("不是聊天摘要"),
        )
    }

    // ---------------------------------------------------------------
    // 实现纪律
    // ---------------------------------------------------------------

    @Test
    fun `检索必须排除当前会话`() {
        val host = src("src/main/java/com/chatmobile/tools/AndroidToolHost.kt")
        assertTrue(
            "AndroidToolHost 必须接 conversationIdHint",
            host.contains("conversationIdHint"),
        )
        val chatHost = src("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue(
            "ChatHost 必须把当前会话 id 传进去 —— " +
                "当前对话已经在上下文里，再搜出来只是浪费 token",
            chatHost.contains("conversationIdHint = { activeId }"),
        )
    }

    @Test
    fun `搜不到时必须说明这是关键词匹配`() {
        val s = src("src/main/java/com/chatmobile/ChatHost.kt")
        assertTrue(
            "空结果必须回一句「这是关键词匹配，换个词再试」—— " +
                "否则模型会把「搜不到」当成「不存在」告诉用户",
            s.contains("**关键词**匹配") || s.contains("关键词"),
        )
    }

    @Test
    fun `缺 query 且非 recent 模式必须报错而不是列出全部`() {
        val real = listOf(
            File("../core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
            File("core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
        ).firstOrNull { it.exists() }
        assertTrue("找不到 ChatToolExecutor.kt", real != null)
        val s = real!!.readText()
        assertTrue(
            "缺 query 时必须返回 Failure —— 静默变成「列出全部」会给模型一份没有针对性的长列表",
            s.contains("缺少 query"),
        )
        assertTrue(
            "必须提示可以用 mode=recent",
            s.contains("mode=recent"),
        )
    }
}
