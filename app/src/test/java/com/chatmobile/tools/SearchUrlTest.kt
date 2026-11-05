package com.chatmobile.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **搜索地址归一化**。
 *
 * ## ★★★ 用户原话
 *
 * > 「然后就是那个地址弄一个，用户填了后缀，后面那个后缀就不加，没填就补上去。」
 *
 * 这条规则看着简单，但它决定"用户配的搜索服务能不能用"。
 * 真机上用户填的是完整终点 `https://api.deepseek.com/v1/tools/search`，
 * 而旧代码无条件拼 `/v1/tools/search` ⇒ 拼出
 * `.../v1/tools/search/v1/tools/search` ⇒ 404 ⇒ 用户报「还是用不了自己填的搜索服务」。
 *
 * ⇒ 判据：**路径末尾已经以 `/tools/search` 结尾就不加**。
 */
class SearchUrlTest {

    private val HOST = "https://api.deepseek.com"
    private val FULL = "$HOST/v1/tools/search"

    // ---------------------------------------------------------------
    // 核心规则：填了后缀就不加
    // ---------------------------------------------------------------

    @Test
    fun `填了完整后缀必须原样使用`() {
        assertEquals(
            "用户填了完整终点，绝不能再补一遍 —— 那会拼出 /tools/search/v1/tools/search",
            FULL,
            SearchUrl.resolve(FULL),
        )
    }

    @Test
    fun `末尾多一个斜杠也认`() {
        assertEquals(FULL, SearchUrl.resolve("$FULL/"))
    }

    @Test
    fun `大小写不同也认`() {
        assertEquals(
            "用户从文档复制时大小写可能不同，不能因此重复拼接",
            "$HOST/v1/Tools/Search",
            SearchUrl.resolve("$HOST/v1/Tools/Search"),
        )
    }

    // ---------------------------------------------------------------
    // 没填后缀就补上
    // ---------------------------------------------------------------

    @Test
    fun `只填根地址要补全路径`() {
        assertEquals(FULL, SearchUrl.resolve(HOST))
        assertEquals(FULL, SearchUrl.resolve("$HOST/"))
    }

    @Test
    fun `只填到 v1 要补全`() {
        assertEquals(FULL, SearchUrl.resolve("$HOST/v1"))
        assertEquals(FULL, SearchUrl.resolve("$HOST/v1/"))
    }

    @Test
    fun `只填到 tools 要补最后一段`() {
        assertEquals(
            "填到 /tools 说明用户记得路径但少写最后一段，补 /search 而不是补全整条",
            FULL,
            SearchUrl.resolve("$HOST/v1/tools"),
        )
    }

    // ---------------------------------------------------------------
    // 手打产生的噪声
    // ---------------------------------------------------------------

    @Test
    fun `重复斜杠要合并`() {
        assertEquals(FULL, SearchUrl.resolve("$HOST//v1//tools//search"))
    }

    @Test
    fun `前后空白要忽略`() {
        assertEquals(FULL, SearchUrl.resolve("  $FULL  "))
    }

    @Test
    fun `query 必须原样保留`() {
        // ★ 自建网关可能要求 ?key=xxx。粗暴 trim 会把它吃掉，导致鉴权失败。
        assertEquals(
            "$FULL?key=abc",
            SearchUrl.resolve("$FULL?key=abc"),
        )
        // 根地址 + query 也要能拼对
        assertEquals(
            "$FULL?key=abc",
            SearchUrl.resolve("$HOST?key=abc"),
        )
    }

    @Test
    fun `空串返回空串而不是拼出一个假地址`() {
        assertEquals("", SearchUrl.resolve(""))
        assertEquals("", SearchUrl.resolve("   "))
    }

    // ---------------------------------------------------------------
    // 其它服务商
    // ---------------------------------------------------------------

    @Test
    fun `自建网关地址同样适用`() {
        assertEquals(
            "https://search.corp.example/api/v1/tools/search",
            SearchUrl.resolve("https://search.corp.example/api/v1/tools/search"),
        )
        assertEquals(
            "https://search.corp.example/api/v1/tools/search",
            SearchUrl.resolve("https://search.corp.example/api"),
        )
    }
}
