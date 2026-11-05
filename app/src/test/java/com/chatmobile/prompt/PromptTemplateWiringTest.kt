package com.chatmobile.prompt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **提示词模板的接线契约** —— 防「机制都在，唯独连接不存在」。
 *
 * ## 为什么这个文件必要
 *
 * 模板的纯逻辑（Policy / Codec）已经有 24 条测试，它们全绿也说明不了
 * "用户真的用得上" —— 真正会出问题的是这三处**接线**：
 *
 * | 接线 | 断了会怎样 |
 * |---|---|
 * | 发送时读的是模板 | 模板选得再对也不生效（还是注入旧字段） |
 * | 启动时读一次（触发迁移） | 老用户的提示词永远迁不过来，看到空列表 |
 * | 设置页真的拿到了模板集合 | 界面显示不出来，等于功能不存在 |
 *
 * ⇒ 这个文件检查**调用点**，不检查组件内部。
 */
class PromptTemplateWiringTest {

    private fun src(rel: String): File {
        val candidates = listOf(File("../$rel"), File(rel))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到源码：$rel（工作目录 ${File(".").absolutePath}）")
    }

    private fun hostText(): String = src("app/src/main/java/com/chatmobile/ChatHost.kt").readText()

    // ------------------------------------------------------------------
    // 一、★★★ 发送时真的用了模板
    // ------------------------------------------------------------------

    @Test
    fun `发送时必须走 effectivePromptText 这个单一入口`() {
        val t = hostText()
        assertTrue(
            "请求组装里没有调用 effectivePromptText() —— 模板选了也不生效",
            t.contains("effectivePromptText()"),
        )
    }

    @Test
    fun `发送时不能再读老的 customPrompt 字段`() {
        val t = hostText()
        // ★★★ 这条是本轮最关键的回归钉子：
        //   旧代码是 `settings.load().customPrompt`。若它还在，
        //   说明有人把注入点改回去了 —— 那模板功能整个失效，且不会报错。
        assertFalse(
            "还在读 settings.load().customPrompt —— 那是模板化之前的老字段，" +
                "留着它就会绕过模板（用户切换模板不会生效）",
            t.contains("settings.load().customPrompt"),
        )
    }

    @Test
    fun `注入位置必须紧跟固定提示词`() {
        // ★ 位置决定缓存：模板必须在 STATIC 之后、压缩摘要之前。
        //   放到历史后面会让每轮前缀不同，缓存全废。
        //
        // ⚠️ 判据必须定位**代码行**，不能在整个文件里 indexOf 任意文本 ——
        //    注释里也提到过 `SystemPrompt.STATIC` 和 `effectivePromptText()`，
        //    第一次写这条时就被注释骗了（报 static=76819 / prompt=7994，
        //    其实那两个位置都是注释）。这是"尺子错"，不是实现错。
        val t = hostText()
        val staticLine = t.lines().indexOfFirst { it.contains("add(NetChatMessage.system(com.chatmobile.prompt.SystemPrompt.STATIC))") }
        val promptLine = t.lines().indexOfFirst {
            it.trimStart().startsWith("val customPrompt = settings.effectivePromptText()")
        }
        val compressionLine = t.lines().indexOfFirst {
            it.contains("if (compression.text.isNotBlank()) add(NetChatMessage.system(compression.text))")
        }
        assertTrue("找不到 STATIC 的注入行", staticLine >= 0)
        assertTrue("找不到 effectivePromptText() 的注入行（只在注释里出现过不算）", promptLine >= 0)
        assertTrue("找不到压缩摘要的注入行", compressionLine >= 0)
        assertTrue(
            "模板必须注入在固定提示词之后 —— 实际 static=$staticLine prompt=$promptLine",
            promptLine > staticLine,
        )
        assertTrue(
            "模板必须在压缩摘要之前（否则它在历史后面，每轮前缀都不同，缓存全废）—— " +
                "实际 prompt=$promptLine compression=$compressionLine",
            promptLine < compressionLine,
        )
    }

    // ------------------------------------------------------------------
    // 二、★★★ 启动时必须读一次（否则老数据永远迁不过来）
    // ------------------------------------------------------------------

    @Test
    fun `启动时必须调用 promptTemplates 触发迁移`() {
        val t = hostText()
        // ★ 迁移逻辑在 `promptTemplates()` 里（新键为空 + 老键非空 ⇒ 迁）。
        //   不读它就永远不会执行 —— 老用户升级后看到空模板列表。
        assertTrue(
            "启动加载里没有调用 settings.promptTemplates() —— 老用户的提示词永远迁不过来",
            t.contains("settings.promptTemplates()"),
        )
    }

    @Test
    fun `设置存储里必须有迁移逻辑`() {
        val t = src("app/src/main/java/com/chatmobile/settings/SettingsStore.kt").readText()
        assertTrue(
            "SettingsStore 没有调用 migrateFromLegacy —— 旧数据会丢",
            t.contains("migrateFromLegacy"),
        )
        assertTrue("没有新键 PROMPT_TEMPLATES", t.contains("PROMPT_TEMPLATES"))
        assertTrue(
            "effectivePromptText() 缺失 —— 注入点就没有单一入口可用",
            t.contains("suspend fun effectivePromptText()"),
        )
    }

    @Test
    fun `迁移结果必须落盘`() {
        val t = src("app/src/main/java/com/chatmobile/settings/SettingsStore.kt").readText()
        // ★ 不落盘的话每次读都要重算；而且用户改名后会下次读又被"迁移"覆盖回去。
        val fn = t.substringAfter("suspend fun promptTemplates()", "")
            .substringBefore("suspend fun setPromptTemplates")
        assertTrue(
            "promptTemplates() 里迁移后没有写回 —— 每次读都重算，且改名会被覆盖",
            fn.contains("promptTemplates] =") || fn.contains("PROMPT_TEMPLATES] ="),
        )
    }

    // ------------------------------------------------------------------
    // 三、设置页真的接上了
    // ------------------------------------------------------------------

    @Test
    fun `设置页必须拿到模板集合并能回存`() {
        val screen = src("app/src/main/java/com/chatmobile/settings/SettingsScreen.kt").readText()
        assertTrue("SettingsScreen 没有 promptTemplates 参数", screen.contains("promptTemplates"))
        assertTrue(
            "SettingsScreen 没有 onSavePromptTemplates 回调",
            screen.contains("onSavePromptTemplates"),
        )
        val ui = src("app/src/main/java/com/chatmobile/settings/MemoryModelsUi.kt").readText()
        assertTrue(
            "PromptTab 还在用老的 customPrompt 参数 —— 界面没改成模板",
            !ui.contains("customPrompt: String"),
        )
        assertTrue("PromptTab 没有接 templates", ui.contains("templates: com.chatmobile.prompt.PromptTemplateSet"))
    }

    @Test
    fun `设置页必须能新建 编辑 删除 切换`() {
        val ui = src("app/src/main/java/com/chatmobile/settings/MemoryModelsUi.kt").readText()
        // 四个动作缺一个，这个功能就是残的
        assertTrue("没有新建", ui.contains("新建模板"))
        assertTrue("没有编辑入口", ui.contains("beginEdit"))
        assertTrue("没有删除", ui.contains("PromptTemplatePolicy.delete"))
        assertTrue("没有切换（select）", ui.contains("PromptTemplatePolicy.select"))
        assertTrue(
            "没有『不使用模板』这个明确选项 —— 用户就没法回到纯净默认提示词",
            ui.contains("不使用模板"),
        )
    }

    @Test
    fun `删除必须走 Policy 而不是自己 filter`() {
        val ui = src("app/src/main/java/com/chatmobile/settings/MemoryModelsUi.kt").readText()
        // ★ 自己 filter 不会修复选中项 ⇒ 删掉正在用的模板会留下悬空引用。
        assertTrue(
            "删除没有走 PromptTemplatePolicy.delete —— 选中项会变成悬空引用",
            ui.contains("PromptTemplatePolicy.delete"),
        )
    }

    @Test
    fun `ChatHost 必须把模板传给设置页并回读`() {
        val t = hostText()
        assertTrue("没把 promptTemplates 传给设置页", t.contains("promptTemplates = promptTemplatesState"))
        assertTrue("没接保存回调", t.contains("onSavePromptTemplates ="))
        // ★ 保存后必须回读：删除会连带修复选中项，那是 Policy 算的，
        //   只改本地 state 会让界面与真实状态不一致。
        val block = t.substringAfter("onSavePromptTemplates =", "").take(600)
        assertTrue(
            "保存后没有回读 promptTemplates() —— 界面会与落库值不一致",
            block.contains("promptTemplatesState = settings.promptTemplates()"),
        )
    }

    @Test
    fun `模板文件的纯逻辑不许 import android`() {
        // ★ 这样它才能完整单测（本项目一贯做法：纯逻辑不留在 Android 类里）
        val t = src("app/src/main/java/com/chatmobile/prompt/PromptTemplate.kt").readText()
        val androidImports = t.lines().filter { it.trimStart().startsWith("import android.") }
        assertTrue(
            "PromptTemplate.kt 引入了 android 依赖（$androidImports）—— 那样就跑不了纯 JVM 单测",
            androidImports.isEmpty(),
        )
    }
}
