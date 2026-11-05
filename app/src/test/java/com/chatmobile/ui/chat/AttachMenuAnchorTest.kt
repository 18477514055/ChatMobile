package com.chatmobile.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「＋」附件菜单的**锚点契约**。
 *
 * ## 真机踩过的那一版
 *
 * 用户原话：「点击加号后，键盘会收回，但是列表还在屏幕中间」。
 *
 * 根因不是"位置随机"，是**锚到了错的父布局**：`DropdownMenu` 内部是 `Popup`，
 * 而 Popup 的锚点是被组合进去的那个**直接父布局**的窗口坐标。
 * 上一版为了"不被横向 Row 裁掉"，把 `AttachMenu(...)` 写在了 `Row { ... }` 之后、
 * `ChatInputBar` 的最外层 —— 父布局于是变成了整个输入区，菜单就弹在屏幕中部。
 *
 * 而"会被 Row 裁掉"这个担心本身是**错的**：Popup 是独立窗口，不受父布局 clip 影响，
 * 也不参与父布局的测量。所以正确做法反而是把它放回按钮自己的 `Box` 里。
 *
 * ## 这里钉的是什么
 *
 * 不钉像素（截不到），钉**结构**：`AttachMenu` 的调用点必须在 `AttachButton` 的
 * `Box { ... }` 内部，且**不许**再出现在 `ChatInputBar` 的顶层。
 * 这是唯一能在 JVM 单测里表达的判据 —— 而它恰好就是这次 bug 的全部。
 */
class AttachMenuAnchorTest {

    private val src: String by lazy {
        File("src/main/java/com/chatmobile/ui/chat/ChatInputBar.kt").readText()
    }

    /**
     * 取某个函数**函数体**的内容。
     *
     * ★ 不能简单取「marker 之后的第一个 `{`」—— 参数列表里的默认值 `= {}`
     *   会先撞上（本项目的 `ChatInputBar` 正好有四个这样的默认值）。
     *   所以先配平参数列表的圆括号，再从它后面的第一个 `{` 开始配平花括号。
     */
    private fun blockAfter(marker: String): String {
        val start = src.indexOf(marker)
        require(start >= 0) { "源码里找不到：$marker" }

        // 1) 配平参数列表的圆括号（marker 之后第一个 '(' 到它的配对 ')')
        val parenOpen = src.indexOf('(', start)
        require(parenOpen >= 0) { "marker 后没有 (：$marker" }
        var parenDepth = 0
        var parenClose = -1
        for (i in parenOpen until src.length) {
            when (src[i]) {
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth == 0) { parenClose = i; break }
                }
            }
        }
        require(parenClose > 0) { "参数列表未配平：$marker" }

        // 2) 从参数列表之后找函数体，配平花括号
        val open = src.indexOf('{', parenClose)
        require(open >= 0) { "参数列表后没有函数体：$marker" }
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        error("花括号未配平：$marker")
    }

    // ------------------------------------------------------------------
    // 结构契约
    // ------------------------------------------------------------------

    @Test
    fun `AttachMenu 的调用必须写在 AttachButton 的 Box 里面`() {
        val button = blockAfter("private fun AttachButton(")
        assertTrue(
            "AttachMenu 必须在 AttachButton 内部被调用 —— 否则 Popup 锚到外层容器，" +
                "菜单会弹在屏幕中间（真机已复现）",
            button.contains("AttachMenu("),
        )
    }

    @Test
    fun `ChatInputBar 顶层不许再出现 AttachMenu 的调用`() {
        val bar = blockAfter("fun ChatInputBar(")
        // 去掉 AttachButton 的调用块后，剩下的顶层内容里不该再有 AttachMenu
        val withoutButtonCall = bar.replace(Regex("""AttachButton\([\s\S]*?\n        \)"""), "")
        assertFalse(
            "ChatInputBar 顶层不许挂 AttachMenu —— 上一版就是这样弹在屏幕中间的",
            withoutButtonCall.contains("AttachMenu("),
        )
    }

    @Test
    fun `AttachMenu 受 expanded 控制 而不是常驻 true`() {
        val menu = blockAfter("private fun AttachMenu(")
        assertTrue(
            "必须接 expanded 参数：写死 expanded = true 会让菜单在每次重组时都试着弹",
            menu.contains("expanded = expanded"),
        )
        assertFalse(
            "不许再写死 true",
            menu.contains("expanded = true"),
        )
    }

    @Test
    fun `AttachButton 必须拿到三个入口回调`() {
        val button = blockAfter("private fun AttachButton(")
        listOf("onPickImage", "onPickFile", "onTakePhoto", "onDismiss").forEach { p ->
            assertTrue("AttachButton 必须接收 $p（否则菜单点了没反应）", button.contains(p))
        }
    }

    // ------------------------------------------------------------------
    // 防退化：菜单三项必须都在
    // ------------------------------------------------------------------

    @Test
    fun `菜单三项齐全且顺序固定`() {
        val menu = blockAfter("private fun AttachMenu(")
        val image = menu.indexOf("添加图片")
        val file = menu.indexOf("添加文件")
        val photo = menu.indexOf("拍照")
        assertTrue("缺「添加图片」", image >= 0)
        assertTrue("缺「添加文件」", file >= 0)
        assertTrue("缺「拍照」", photo >= 0)
        assertTrue("顺序必须是 图片 → 文件 → 拍照（用户指定的顺序）", image < file && file < photo)
    }

    @Test
    fun `三项各自接对回调 不许串`() {
        val menu = blockAfter("private fun AttachMenu(")
        // 「添加图片」那一块的 onClick 必须是 onPickImage
        val imageBlock = menu.substring(menu.indexOf("添加图片"))
        assertTrue(
            "「添加图片」必须接 onPickImage",
            imageBlock.substringBefore(")").contains("onPickImage") ||
                imageBlock.contains("onClick = onPickImage"),
        )
        assertTrue("「添加文件」必须接 onPickFile", menu.contains("onClick = onPickFile"))
        assertTrue("「拍照」必须接 onTakePhoto", menu.contains("onClick = onTakePhoto"))
    }

    @Test
    fun `点任一项都必须先收起菜单`() {
        val bar = blockAfter("fun ChatInputBar(")
        // 三个回调的形态都是：showAttachMenu = false 然后调用真正的入口
        assertTrue(
            "点完必须收起菜单，否则拉起相册/相机回来菜单还挂着",
            bar.contains("showAttachMenu = false"),
        )
    }
}
