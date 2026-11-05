package com.chatmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **会话标题清洗**的纯逻辑测试。
 *
 * ## ★ 为什么这个也要测
 *
 * 模型给标题时有固定的几类跑偏，每一条在真机上都会直接显示给用户：
 * - `标题：「如何实现向量检索」` —— 前缀 + 中文引号
 * - `"Kotlin Coroutines"` —— 英文引号
 * - `**协程调度**` —— markdown 粗体
 * - `标题：xxx\n\n（这个标题概括了……）` —— 标题后附解释
 *
 * 这些都不是"偶尔出现"，是**高频**。清洗错一格的代价是侧边栏里
 * 一排带引号的标题，看着就像没做完。
 */
class TitleSanitizeTest {

    @Test
    fun `剥掉中文前缀与引号`() {
        assertEquals("如何实现向量检索", sanitizeTitle("标题：「如何实现向量检索」"))
    }

    @Test
    fun `剥掉英文前缀与双引号`() {
        assertEquals("Kotlin Coroutines", sanitizeTitle("\"Kotlin Coroutines\""))
        assertEquals("Kotlin Coroutines", sanitizeTitle("Title: Kotlin Coroutines"))
    }

    @Test
    fun `前缀大小写不敏感`() {
        // ★ 这里必须过：只处理小写 Title: 是不够的，模型常写 TITLE:
        assertEquals("协程调度", sanitizeTitle("TITLE: 协程调度"))
        assertEquals("协程调度", sanitizeTitle("title：协程调度"))
    }

    @Test
    fun `剥掉 markdown 粗体`() {
        assertEquals("协程调度", sanitizeTitle("**协程调度**"))
    }

    @Test
    fun `叠加的包裹也能剥掉`() {
        // `**「标题」**` —— 两层
        assertEquals("协程调度", sanitizeTitle("**「协程调度」**"))
    }

    @Test
    fun `只取第一行`() {
        // 模型常在标题后补一段解释
        assertEquals(
            "向量检索",
            sanitizeTitle("向量检索\n\n（这个标题概括了对话主题）"),
        )
    }

    @Test
    fun `前导空行不影响取第一行`() {
        assertEquals("向量检索", sanitizeTitle("\n\n  向量检索  \n更多说明"))
    }

    @Test
    fun `内部多余空白压成一个`() {
        assertEquals("协程 调度 器", sanitizeTitle("协程   调度\u3000器"))
    }

    @Test
    fun `超长标题被截到 20 字`() {
        val long = "这是一段特别特别长的标题用来测试截断行为是否正确生效"
        val out = sanitizeTitle(long)
        assertEquals(20, out.length)
        assertTrue(long.startsWith(out))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", sanitizeTitle(""))
        assertEquals("", sanitizeTitle("   \n\t  "))
    }

    @Test
    fun `正常标题原样通过`() {
        assertEquals("手机端聊天客户端", sanitizeTitle("手机端聊天客户端"))
    }

    @Test
    fun `只有前缀没有内容时返回空串`() {
        // 模型偶尔只吐一个前缀 —— 不能把 "标题：" 当标题存进去
        assertEquals("", sanitizeTitle("标题："))
        assertEquals("", sanitizeTitle("Title:"))
    }
}
