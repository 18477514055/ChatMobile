package com.chatmobile.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **键盘避让的契约测试**。
 *
 * ## ★★★ 用户原话
 *
 * > 「你这个联网搜索服务放在最下面，输入东西的时候，整个界面会被键盘挡住，
 * >   为什么不能顶上去呢（其他需要输入东西的界面也是如此，只有主界面可以顶上去）」
 *
 * ## 根因
 *
 * 主界面用的是 `WindowInsets.ime.union(WindowInsets.navigationBars)`，所以它能顶上去；
 * 而设置下的几个子页面只加了 `navigationBarsPadding()` ——
 * **那只躲导航条，完全不躲键盘**。
 *
 * 更隐蔽的是：`navigationBarsPadding()` 看起来"已经处理了底部内边距"，
 * 所以写的时候很容易以为这页已经照顾过底部遮挡了。它不是。
 *
 * ## 为什么必须用测试钉
 *
 * 这个 bug **编译不报错、界面不报错、静态截图也看不出来** ——
 * 只有真的弹出键盘并把输入框滚到屏幕下方才会暴露。
 * 而每次新加一页设置、每次重排字段顺序，都可能重新踩进去。
 *
 * ## 判据
 *
 * 含输入框的页面 / 弹窗，必须消费 **IME** 内边距：
 * - 页面级：`Scaffold(modifier = modifier.imePadding())`（放外层，不放滚动容器上）
 * - 弹窗级：Dialog / AlertDialog 自己的 modifier 上 `.imePadding()`
 *   —— Dialog 是**独立窗口**，外层页面的 imePadding 管不到它
 */
class KeyboardInsetsTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ---------------------------------------------------------------
    // 页面级
    // ---------------------------------------------------------------

    @Test
    fun `工具页必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/settings/ToolsScreen.kt")
        assertTrue(
            "工具页有「搜索服务地址 / Key」两个输入框，必须 imePadding —— " +
                "只加 navigationBarsPadding 躲的是导航条，不躲键盘",
            s.contains("modifier = modifier.imePadding()"),
        )
    }

    @Test
    fun `通用设置页必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/settings/GeneralScreen.kt")
        assertTrue(
            "通用页有「字号」输入框，必须 imePadding",
            s.contains("modifier = modifier.imePadding()"),
        )
    }

    @Test
    fun `工作区页必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/settings/WorkspacesScreen.kt")
        assertTrue(
            "工作区页有「新建工作区名称」输入框，必须 imePadding",
            s.contains("modifier = modifier.imePadding()"),
        )
    }

    /**
     * 模型页是最早修好的一页 —— 钉住它，防止将来"统一风格"时被改回去。
     */
    @Test
    fun `模型页的键盘避让不能被改掉`() {
        val s = src("src/main/java/com/chatmobile/settings/SettingsScreen.kt")
        assertTrue(
            "模型页的 imePadding 必须留在 Scaffold **外层** —— " +
                "放到内部滚动容器上会导致下方字段既进不了可视区也滚不到（踩过）",
            s.contains("modifier = modifier.imePadding()"),
        )
    }

    // ---------------------------------------------------------------
    // 弹窗级（最容易漏：外层页面的 imePadding 管不到独立窗口）
    // ---------------------------------------------------------------

    @Test
    fun `重命名对话框必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/ui/sidebar/SidebarPanel.kt")
        assertTrue(
            "重命名框会自动聚焦弹键盘 —— 不给自己加 imePadding 的话，" +
                "输入框连同保存按钮都会被键盘压住",
            s.contains("modifier = Modifier.imePadding()"),
        )
    }

    @Test
    fun `模型添加对话框必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/settings/MemoryModelsUi.kt")
        assertTrue(
            "对话框内的 API 地址/Key 输入框需要对话框**自己**消费 ime 内边距 —— " +
                "Dialog 是独立窗口，外层页面的 imePadding 管不到",
            s.contains(".imePadding()"),
        )
    }

    /**
     * 快捷面板设了 `decorFitsSystemWindows = false`，代价是**不再自动避让 IME**。
     *
     * ★ 这是个反直觉的点：大家都以为"Dialog 会自动处理键盘"，
     *   而这个属性恰好把那个自动行为关掉了。里面有搜索框，必须显式补回来。
     */
    @Test
    fun `快捷面板必须有键盘避让`() {
        val s = src("src/main/java/com/chatmobile/ui/chat/ModelSwitchDialog.kt")
        assertTrue("必须设了 decorFitsSystemWindows = false", s.contains("decorFitsSystemWindows = false"))
        assertTrue(
            "设了 decorFitsSystemWindows = false 就必须自己 imePadding —— " +
                "那个属性同时关掉了自动避让 IME，而面板里有搜索框",
            s.contains(".imePadding()"),
        )
    }
}
