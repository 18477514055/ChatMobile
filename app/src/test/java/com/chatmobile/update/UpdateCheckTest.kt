package com.chatmobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **版本比较与更新通道的测试**。
 *
 * ## ★★★ 这批测试防的两件事
 *
 * 1. **版本比较按字符串做** —— `"0.10.0" < "0.9.0"`，第 10 个版本才暴露。
 *    那时候用户已经点过"检查更新"看到"已是最新"了。
 * 2. **只做 GitHub API 一条通道** —— `api.github.com` 在国内基本不通，
 *    等于把"检查更新"变成了对相当一部分用户永远失败的功能。
 *    给"没梯子的人"留的路必须被钉住，否则下一次重构很容易被"简化"掉。
 */
class UpdateCheckTest {

    // ---------------------------------------------------------------
    // 版本比较
    // ---------------------------------------------------------------

    @Test
    fun `版本必须按数字段比较而不是字符串`() {
        val a = SemVer.parse("0.10.0")!!
        val b = SemVer.parse("0.9.0")!!
        assertTrue(
            "0.10.0 必须大于 0.9.0 —— 按字符串比会得出相反结论，" +
                "而且要到第 10 个版本才暴露",
            a > b,
        )
    }

    @Test
    fun `版本段数不同时缺失段补零`() {
        assertEquals("1.2 与 1.2.0 必须相等", 0, SemVer.parse("1.2")!!.compareTo(SemVer.parse("1.2.0")!!))
        assertTrue("1.2.1 必须大于 1.2", SemVer.parse("1.2.1")!! > SemVer.parse("1.2")!!)
    }

    @Test
    fun `能解析各种 tag 写法`() {
        for (raw in listOf("0.1.0", "v0.1.0", "V0.1.0", "release-0.1.0", "  v0.2  ")) {
            assertTrue("必须能解析 `$raw`", SemVer.parse(raw) != null)
        }
        assertEquals(listOf(0, 1, 0), SemVer.parse("v0.1.0")!!.parts)
        assertEquals(listOf(0, 1, 0), SemVer.parse("release-0.1.0")!!.parts)
    }

    @Test
    fun `解析不出数字时必须返回 null 而不是 0`() {
        // ★ 返回 0 的话，任何"看起来像最新"的判断都会把它当成 v0.0.0，
        //   于是所有更新都会被判为"有新版本"。宁可报"读不出来"。
        for (raw in listOf(null, "", "   ", "latest", "release")) {
            assertNull("`$raw` 不该被解析成版本号", SemVer.parse(raw))
        }
    }

    // ---------------------------------------------------------------
    // Release 解析
    // ---------------------------------------------------------------

    private val checker = UpdateChecker(currentVersion = "0.1.0")

    @Test
    fun `跳过草稿与预发布`() {
        val body = """
        [
          {"tag_name":"v0.9.0","draft":true,"prerelease":false,"html_url":"x","assets":[]},
          {"tag_name":"v0.8.0","draft":false,"prerelease":true,"html_url":"x","assets":[]},
          {"tag_name":"v0.2.0","draft":false,"prerelease":false,"html_url":"u2","assets":[]}
        ]
        """.trimIndent()
        val r = checker.parseLatestRelease(body)
        assertEquals("必须跳过 draft 与 prerelease，只留正式版", "v0.2.0", r?.tag)
    }

    @Test
    fun `按版本号取最大而不是按列表顺序`() {
        // GitHub 返回的是按创建时间倒序 —— 补发一个旧版本时，第一条就不是最新的。
        val body = """
        [
          {"tag_name":"v0.2.0","html_url":"u2","assets":[]},
          {"tag_name":"v0.10.0","html_url":"u10","assets":[]},
          {"tag_name":"v0.9.0","html_url":"u9","assets":[]}
        ]
        """.trimIndent()
        assertEquals("必须取 0.10.0", "v0.10.0", checker.parseLatestRelease(body)?.tag)
    }

    @Test
    fun `挑出 apk 资产且忽略非安装包`() {
        val body = """
        [{"tag_name":"v0.2.0","html_url":"u","assets":[
          {"name":"checksums.txt","browser_download_url":"c","size":10},
          {"name":"chatmobile-v0.2.0.apk","browser_download_url":"https://x/a.apk","size":2048}
        ]}]
        """.trimIndent()
        val r = checker.parseLatestRelease(body)!!
        assertEquals("必须挑 .apk", "https://x/a.apk", r.apkUrl)
        assertEquals(2048L, r.apkBytes)
    }

    @Test
    fun `没有 apk 资产时直链为 null 而不是乱猜`() {
        val body = """[{"tag_name":"v0.2.0","html_url":"u","assets":[{"name":"a.zip","browser_download_url":"z","size":1}]}]"""
        val r = checker.parseLatestRelease(body)!!
        // ★ 猜一个非安装包给用户下载，比"没有直链、让用户去页面"糟得多。
        assertNull("没有 .apk 就必须回 null", r.apkUrl)
    }

    @Test
    fun `空列表与坏 JSON 都返回 null`() {
        assertNull(checker.parseLatestRelease("[]"))
        assertNull(checker.parseLatestRelease("not json"))
        assertNull(checker.parseLatestRelease("{}"))
    }

    // ---------------------------------------------------------------
    // ★★★ 「没有梯子的人」—— 通道降级必须真的存在
    // ---------------------------------------------------------------

    private val checkerSrc by lazy {
        File("src/main/java/com/chatmobile/update/UpdateChecker.kt").readText()
    }

    @Test
    fun `必须有 GitHub 之外的备用通道`() {
        assertTrue(
            "必须有 jsDelivr 或等价的可达通道 —— " +
                "api.github.com 在国内基本不通，只做一条等于对多数用户永远失败",
            checkerSrc.contains("jsdelivr", ignoreCase = true) ||
                checkerSrc.contains("ghproxy") ||
                checkerSrc.contains("mirror"),
        )
    }

    @Test
    fun `失败时必须给出可以手动的更新页地址`() {
        assertTrue(
            "Failed 必须带 manualUrl",
            checkerSrc.contains("manualUrl = pageUrlOf(repo)"),
        )
        // 且默认地址是真实存在的 releases 页
        assertTrue(
            "默认手动地址必须是 releases 页",
            UpdateCheck.DEFAULT_PAGE_URL.endsWith("/releases"),
        )
        assertTrue(
            "手动地址必须指向本项目仓库",
            UpdateCheck.DEFAULT_PAGE_URL.contains("ChatMobile"),
        )
    }

    @Test
    fun `超时必须足够短以便串行跑完所有通道`() {
        // ★ 三条通道串行，每条都给 30 秒的话最坏接近两分钟 —— 用户会以为死机。
        val connect = Regex("""connectTimeout\((\d+)""").find(checkerSrc)?.groupValues?.get(1)?.toInt()
        assertTrue("必须设 connectTimeout", connect != null)
        assertTrue("连接超时必须 ≤ 10 秒（现在是 $connect），否则三条通道串行太久", connect!! <= 10)
    }

    @Test
    fun `UI 必须提供手动打开更新页的入口`() {
        val f = File("src/main/java/com/chatmobile/settings/GeneralScreen.kt")
        val src = f.readText()
        assertTrue(
            "检查失败时必须有一个「手动打开更新页」的可点项 —— " +
                "否则没梯子的人只能看到一句错误，没有任何出路",
            src.contains("手动打开更新页"),
        )
        assertTrue(
            "必须把 manualUrl 交给外部打开器",
            src.contains("state.manualUrl"),
        )
    }

    // ---------------------------------------------------------------
    // ★★★ requiresConfirmation 必须真的被消费
    // ---------------------------------------------------------------

    @Test
    fun `确认字段必须有真正的消费方`() {
        val candidates = listOf(
            File("../core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
            File("core-tools/src/main/kotlin/chatmobile/tools/ChatToolExecutor.kt"),
        )
        val real = candidates.firstOrNull { it.exists() }
        assertTrue("找不到 ChatToolExecutor.kt", real != null)
        val src = real!!.readText()

        assertTrue(
            "执行器必须读 requiresConfirmation —— " +
                "在本轮之前它是一张空头支票，模型能静默覆盖用户文件",
            src.contains("requiresConfirmation == true"),
        )
        assertTrue(
            "确认不通过时必须拒绝执行并说明原因，不能静默继续",
            src.contains("用户没有允许这次"),
        )
        // ★ 默认回调必须是**拒绝**：调用方忘了传时绝不能放行
        assertTrue(
            "confirm 的默认实现必须返回 false —— 没人接这个回调时放行等于没有保护",
            src.contains("{ _, _ -> false }"),
        )
    }

    @Test
    fun `确认框必须有超时且超时等于拒绝`() {
        val f = File("src/main/java/com/chatmobile/ChatHost.kt")
        val src = f.readText()
        assertTrue("必须有确认超时常量", src.contains("TOOL_CONFIRM_TIMEOUT_MS"))
        assertTrue(
            "超时必须走拒绝分支（withTimeoutOrNull 返回 null ⇒ false）",
            src.contains("?: false"),
        )
    }
}
