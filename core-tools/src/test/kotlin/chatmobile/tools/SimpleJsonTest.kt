package chatmobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleJsonTest {

    @Test
    fun `解析简单对象`() {
        val r = SimpleJson.parseObjectOfStrings("""{"command":"sed","args":"s/a/b/"}""")
        assertEquals("sed", r["command"])
        assertEquals("s/a/b/", r["args"])
    }

    @Test
    fun `解析带转义的字符串`() {
        val r = SimpleJson.parseObjectOfStrings("""{"command":"grep","args":"a\u0000b"}""")
        assertEquals("a\u0000b", r["args"])
    }

    @Test
    fun `解析换行转义`() {
        val r = SimpleJson.parseObjectOfStrings("""{"text":"line1\nline2"}""")
        assertEquals("line1\nline2", r["text"])
    }

    @Test
    fun `解析引号转义`() {
        val r = SimpleJson.parseObjectOfStrings("""{"text":"say \"hi\""}""")
        assertEquals("say \"hi\"", r["text"])
    }

    @Test
    fun `解析数字和布尔`() {
        val r = SimpleJson.parseObjectOfStrings("""{"n":42,"b":true}""")
        assertEquals("42", r["n"])
        assertEquals("true", r["b"])
    }

    @Test
    fun `拒绝非对象`() {
        var threw = false
        try {
            SimpleJson.parseObjectOfStrings("""["a","b"]""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "数组不应被接受")
    }

    @Test
    fun `拒绝未闭合的字符串`() {
        var threw = false
        try {
            SimpleJson.parseObjectOfStrings("""{"a":"unclosed}""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "未闭合字符串应报错")
    }

    @Test
    fun `空对象返回空 map`() {
        assertTrue(SimpleJson.parseObjectOfStrings("{}").isEmpty())
    }

    @Test
    fun `解析 unicode 转义`() {
        val r = SimpleJson.parseObjectOfStrings("""{"c":"\u4e2d"}""")
        assertEquals("中", r["c"])
    }
}
