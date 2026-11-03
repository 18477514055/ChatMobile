package com.chatmobile

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 结构化抽取结果解析：对象 / 字符串混排、非法 key 降级。 */
class MemoryParseItemsTest {

    @Test
    fun `解析对象形态并带回键`() {
        val raw = """[{"key":"pref.reply_style","value":"简洁，不要客套"},
                     {"key":"user.city","value":"上海"}]"""
        val items = parseMemoryItems(raw)
        assertEquals(2, items.size)
        assertEquals("pref.reply_style", items[0].key)
        assertEquals("简洁，不要客套", items[0].value)
    }

    @Test
    fun `容忍模型只给字符串`() {
        val items = parseMemoryItems("""["用户是 Android 开发者"]""")
        assertEquals(1, items.size)
        assertNull(items.first().key)
        assertEquals("用户是 Android 开发者", items.first().value)
    }

    @Test
    fun `非法键降级为空`() {
        val items = parseMemoryItems("""[{"key":"偏好.回答风格","value":"简洁"}]""")
        assertEquals(1, items.size)
        assertNull(items.first().key)
    }

    @Test
    fun `字符串与对象混排`() {
        val items = parseMemoryItems("""["用户喜欢猫",{"key":"user.city","value":"上海"}]""")
        assertEquals(2, items.size)
        assertNull(items[0].key)
        assertEquals("user.city", items[1].key)
    }

    @Test
    fun `前后废话与 markdown 代码块都能容错`() {
        val raw = "好的，结果如下：\n```json\n[{\"key\":\"event.2026-10-28_move\",\"value\":\"用户搬家到上海\"}]\n```"
        val items = parseMemoryItems(raw)
        assertEquals(1, items.size)
        assertEquals("event.2026-10-28_move", items.first().key)
    }

    @Test
    fun `非 JSON 返回空而不是抛异常`() {
        assertTrue(parseMemoryItems("我无法完成这个任务").isEmpty())
        assertTrue(parseMemoryItems("").isEmpty())
    }
}
