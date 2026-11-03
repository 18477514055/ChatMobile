package com.chatmobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记忆抽取结果解析的契约测试。
 *
 * ★ 为什么这个纯函数值得单独测：它是**模型输出的唯一入口**，
 *   而模型**经常不听话**。解析失败的表现是"聊了很多轮，记忆库还是空的" ——
 *   这种失败**完全静默**，不打日志、不报错，只能靠人发现。
 *   所以边界必须钉死。
 */
class MemoryParseTest {

    @Test
    fun `裸 JSON 数组能直接解析`() {
        val raw = """["用户是 Android 开发者","用户偏好简洁回答"]"""
        val r = parseMemoryArray(raw)
        assertEquals(2, r.size)
        assertEquals("用户是 Android 开发者", r[0])
    }

    @Test
    fun `带 markdown 代码块也能解析`() {
        // 这是最常见的偏离 —— 模型几乎总会套一层 ```json
        val raw = """
            ```json
            ["用户喜欢 Kotlin", "用户在做 ChatMobile"]
            ```
        """.trimIndent()
        val r = parseMemoryArray(raw)
        assertEquals(2, r.size)
        assertEquals("用户喜欢 Kotlin", r[0])
    }

    @Test
    fun `前后有解释文字也能解析`() {
        // 模型经常先说一句"好的，以下是抽取的记忆："
        val raw = """好的，以下是抽取的记忆：["用户叫小明","用户住在杭州"]"""
        val r = parseMemoryArray(raw)
        assertEquals(2, r.size)
        assertEquals("用户叫小明", r[0])
    }

    @Test
    fun `空数组返回空列表`() {
        // ★ 提示词里明确要求"没有就返回空数组"。
        //   如果这里解析成 1 条空的，记忆库会被空字符串污染。
        assertEquals(0, parseMemoryArray("[]").size)
    }

    @Test
    fun `完全不是 JSON 时返回空列表而不是崩溃`() {
        // ★ 宁可不记，也不能把"我无法完成这个任务"记成记忆。
        assertEquals(0, parseMemoryArray("我无法完成这个任务").size)
        assertEquals(0, parseMemoryArray("").size)
        assertEquals(0, parseMemoryArray("   ").size)
    }

    @Test
    fun `超长条目被丢弃`() {
        // 超过 200 字的多半是模型把整段对话摘要塞进来了，
        // 那不是记忆，而且会吃掉有限的 token 预算。
        val long = "啊".repeat(300)
        val raw = """["正常的一条","$long"]"""
        val r = parseMemoryArray(raw)
        assertEquals(1, r.size)
        assertEquals("正常的一条", r[0])
    }

    @Test
    fun `空白条目被过滤`() {
        val raw = """["  ","有效的一条"]"""
        val r = parseMemoryArray(raw)
        assertEquals(1, r.size)
        assertEquals("有效的一条", r[0])
    }
}
