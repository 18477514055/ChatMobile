package com.chatmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆管理员（curator）输出解析：ADD / UPDATE / MERGE / SKIP 与容错。 */
class MemoryOpsParseTest {

    @Test
    fun `解析 ADD 与 keys`() {
        val raw = """{"ops":[{"op":"ADD","key":"user.job","content":"用户是 Android 开发者"}]}"""
        val ops = parseMemoryOps(raw)
        assertEquals(1, ops.size)
        assertEquals("ADD", ops[0].op)
        assertEquals("user.job", ops[0].key)
        assertEquals("用户是 Android 开发者", ops[0].content)
    }

    @Test
    fun `解析 MERGE 的 ids`() {
        val raw = """{"ops":[{"op":"MERGE","ids":["a","b"],"content":"用户在做 ChatMobile"}]}"""
        val ops = parseMemoryOps(raw)
        assertEquals("MERGE", ops[0].op)
        assertEquals(listOf("a", "b"), ops[0].ids)
    }

    @Test
    fun `MERGE 少于两个 id 被丢弃`() {
        val raw = """{"ops":[{"op":"MERGE","ids":["a"],"content":"x"}]}"""
        assertTrue(parseMemoryOps(raw).isEmpty())
    }

    @Test
    fun `老格式 key value 没有 op 时当作 ADD`() {
        val raw = """[{"key":"pref.reply_style","value":"简洁"}]"""
        val ops = parseMemoryOps(raw)
        assertEquals(1, ops.size)
        assertEquals("ADD", ops[0].op)
        assertEquals("简洁", ops[0].content)
    }

    @Test
    fun `SKIP 被保留但无内容`() {
        val ops = parseMemoryOps("""{"ops":[{"op":"SKIP"}]}""")
        assertEquals(1, ops.size)
        assertEquals("SKIP", ops[0].op)
        assertNull(ops[0].content)
    }

    @Test
    fun `非法 key 降级为空`() {
        val ops = parseMemoryOps("""{"ops":[{"op":"ADD","key":"偏好.风格","content":"简洁"}]}""")
        assertNull(ops[0].key)
    }

    @Test
    fun `代码块包裹与前后废话都能容错`() {
        val raw = "结果：\n```json\n{\"ops\":[{\"op\":\"ADD\",\"key\":\"user.city\",\"content\":\"上海\"}]}\n```"
        assertEquals(1, parseMemoryOps(raw).size)
    }

    @Test
    fun `非 JSON 返回空`() {
        assertTrue(parseMemoryOps("我无法完成").isEmpty())
        assertTrue(parseMemoryOps("").isEmpty())
    }
}
