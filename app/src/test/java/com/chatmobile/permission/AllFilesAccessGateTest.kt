package com.chatmobile.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「所有文件访问」入口的**可达性契约**。
 *
 * ## 为什么必须有这一套（真机踩过两次）
 *
 * ① 第一版只声明权限、不给入口 ⇒ 用户永远不知道要去哪开（"机制都在，连接不存在"）。
 * ② 第二版给了入口，但**硬跳一个本机不存在的设置页** ⇒ 用户点了，屏幕毫无反应，
 *    而且代码不报错、log 里也没有异常。用户的原话是：「没有这个访问所有文件的啊」。
 *
 * 第二次是最坏的一种 bug：**按钮在，路不通，还没有任何反馈**。
 * 它躲过了编译、躲过了单元测试、也躲过了静止截图 —— 只有真机点一下才暴露。
 *
 * ⇒ 所以这里用契约测试把「路由必须先探测」这条钉死，并且额外钉住
 *   「探测不到时不许给按钮」这一条**行为**（不是文案）。
 */
class AllFilesAccessGateTest {

    private val gateSrc: String by lazy {
        File("src/main/java/com/chatmobile/permission/AllFilesAccessGate.kt").readText()
    }

    // ------------------------------------------------------------------
    // 路由选择
    // ------------------------------------------------------------------

    @Test
    fun `专属页可用时优先走专属页`() {
        assertEquals(
            AllFilesAccessGate.Route.AppPage,
            AllFilesAccessGate.decide(hasAppPage = true, hasListPage = true),
        )
    }

    @Test
    fun `专属页缺失但列表页可用时退到列表页`() {
        assertEquals(
            AllFilesAccessGate.Route.ListPage,
            AllFilesAccessGate.decide(hasAppPage = false, hasListPage = true),
        )
    }

    @Test
    fun `两个页面都没有时判定为不可用`() {
        assertEquals(
            AllFilesAccessGate.Route.Unavailable,
            AllFilesAccessGate.decide(hasAppPage = false, hasListPage = false),
        )
    }

    @Test
    fun `专属页单独可用时不被列表页干扰`() {
        assertEquals(
            AllFilesAccessGate.Route.AppPage,
            AllFilesAccessGate.decide(hasAppPage = true, hasListPage = false),
        )
    }

    // ------------------------------------------------------------------
    // 文案：两条互斥的路，各自说清能干什么
    // ------------------------------------------------------------------

    @Test
    fun `不可用时的说明必须点明是机器没有 而不是用户没找到`() {
        val hint = AllFilesAccessGate.unavailableHint()
        assertTrue("必须说明是机器没有这个开关", hint.contains("没有「所有文件访问」这个开关"))
        assertTrue("必须排除「你没找到」这个误解", hint.contains("不是你没找到"))
        assertTrue("必须排除「App 装坏了」这个误解", hint.contains("App 装坏了"))
    }

    @Test
    fun `不可用时的说明必须给出还能干什么与什么不行`() {
        val hint = AllFilesAccessGate.unavailableHint()
        // 能干什么
        assertTrue("必须写明公共目录仍可用", hint.contains("Download"))
        assertTrue("必须写明 Documents 可用", hint.contains("Documents"))
        // 什么不行 —— 这条是真机实测：/sdcard/跨宇宙文明世界观/ 报 EPERM
        assertTrue("必须写明不能自建文件夹", hint.contains("不能在这些目录里自己新建文件夹"))
    }

    @Test
    fun `可用时的说明不许出现 没这个开关 的措辞`() {
        val hint = AllFilesAccessGate.availableHint()
        assertFalse("两条文案互斥，不能混", hint.contains("没有这个开关"))
        assertTrue("要说明不授予也能用", hint.contains("不授予也完全能用"))
    }

    // ------------------------------------------------------------------
    // 契约：调用点必须真的探测，不能硬跳
    // ------------------------------------------------------------------

    @Test
    fun `ChatHost 必须先 resolveActivity 探测再决定跳转`() {
        val chatHost = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        assertTrue(
            "必须用 resolveActivity 探测设置页是否存在 —— 硬跳在 MIUI 上是无声失败",
            chatHost.contains("resolveActivity"),
        )
        assertTrue(
            "探测结果必须走 AllFilesAccessGate.decide",
            chatHost.contains("AllFilesAccessGate.decide"),
        )
    }

    @Test
    fun `ChatHost 跳转处不许再散写 Settings 的 action 常量`() {
        val chatHost = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        assertFalse(
            "action 常量必须集中在 AllFilesAccessGate，散写就会重现「只修一半」",
            chatHost.contains("Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"),
        )
        assertFalse(
            "列表页同样不许散写",
            chatHost.contains("Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION"),
        )
    }

    @Test
    fun `Unavailable 时不许渲染去授予的按钮`() {
        val general = File("src/main/java/com/chatmobile/settings/GeneralScreen.kt").readText()
        assertTrue(
            "GeneralScreen 必须接收路由参数",
            general.contains("allFilesRoute"),
        )
        assertTrue(
            "按钮必须在 Unavailable 分支之外 —— 否则点了没反应",
            general.contains("route != com.chatmobile.permission.AllFilesAccessGate.Route.Unavailable"),
        )
    }

    // ------------------------------------------------------------------
    // action 常量本身
    // ------------------------------------------------------------------

    @Test
    fun `两个 action 常量必须是系统真值`() {
        assertEquals(
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            AllFilesAccessGate.ACTION_APP_PAGE,
        )
        assertEquals(
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION",
            AllFilesAccessGate.ACTION_LIST_PAGE,
        )
    }

    @Test
    fun `纯逻辑文件里不许出现 android 导入`() {
        // 它要能在 JVM 单测里跑 —— 一旦 import android.* 这些测试就全废
        assertFalse(
            "AllFilesAccessGate 必须是纯 Kotlin",
            Regex("""^import\s+android\.""", RegexOption.MULTILINE).containsMatchIn(gateSrc),
        )
    }
}
