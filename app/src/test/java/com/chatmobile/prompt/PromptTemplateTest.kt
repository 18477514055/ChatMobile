package com.chatmobile.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **提示词模板**的契约。
 *
 * ## 用户原话
 *
 * > 「现在允许将提示词保存为一个模板（一个模板包含一个标题和一个提示词文本，可以随时切换）」
 *
 * ## 这个文件重点防三件事
 *
 * 1. **删掉选中的模板 ⇒ 不能留下悬空引用**（否则表现为"人设突然没了"）
 * 2. **升级不能把老用户写的提示词弄丢**（老数据是单个字符串，要迁成模板）
 * 3. **标题必须必填**（它是切换时唯一的辨认依据）
 *
 * ★ 断言顺序：app 模块用 JUnit `(message, actual)`。
 */
class PromptTemplateTest {

    private fun tpl(id: String, title: String, text: String) =
        PromptTemplate(id = id, title = title, text = text)

    // ------------------------------------------------------------------
    // 一、基本：一个模板 = 标题 + 正文
    // ------------------------------------------------------------------

    @Test
    fun `模板必须同时有标题和正文两个字段`() {
        val t = tpl("a", "严谨助理", "先给结论再给依据")
        assertEquals("严谨助理", t.title)
        assertEquals("先给结论再给依据", t.text)
    }

    @Test
    fun `选中的模板决定实际注入的正文`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "aaa"), tpl("b", "B", "bbb")),
            selectedId = "b",
        )
        assertEquals("bbb", set.effectiveText)
    }

    @Test
    fun `没有选中时注入空文本`() {
        // ★ 这是合法状态：用户可能就想要纯净的默认提示词
        val set = PromptTemplateSet(templates = listOf(tpl("a", "A", "aaa")), selectedId = null)
        assertNull(set.selected)
        assertEquals("", set.effectiveText)
    }

    @Test
    fun `选中的 id 不存在时注入空 而不是崩`() {
        val set = PromptTemplateSet(templates = listOf(tpl("a", "A", "aaa")), selectedId = "ghost")
        assertNull(set.selected)
        assertEquals("", set.effectiveText)
    }

    @Test
    fun `正文首尾空白会被修掉`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "  \n 人设 \n ")),
            selectedId = "a",
        )
        assertEquals("人设", set.effectiveText)
    }

    // ------------------------------------------------------------------
    // 二、校验（标题必填是最重要的一条）
    // ------------------------------------------------------------------

    @Test
    fun `标题为空时拒绝保存`() {
        // ★ 标题不是装饰，它是切换时唯一的辨认依据 —— 列表里靠它认模板
        val v = PromptTemplatePolicy.check("", "正文", emptyList())
        assertTrue("空标题必须被拒绝", v is PromptTemplatePolicy.Verdict.Rejected)
        assertTrue(
            "拒绝理由要说明为什么（不是只说不允许）",
            (v as PromptTemplatePolicy.Verdict.Rejected).reason.contains("标题"),
        )
    }

    @Test
    fun `只有空白的标题也算空`() {
        val v = PromptTemplatePolicy.check("   ", "正文", emptyList())
        assertTrue(v is PromptTemplatePolicy.Verdict.Rejected)
    }

    @Test
    fun `标题过长被拒绝`() {
        val long = "标".repeat(PromptTemplatePolicy.MAX_TITLE + 1)
        val v = PromptTemplatePolicy.check(long, "正文", emptyList())
        assertTrue(v is PromptTemplatePolicy.Verdict.Rejected)
    }

    @Test
    fun `正文可以为空`() {
        // ★ 空正文有真实用途：临时"清空当前人设"而不必删掉模板本身
        val v = PromptTemplatePolicy.check("空模板", "", emptyList())
        assertTrue("空正文应当允许", v is PromptTemplatePolicy.Verdict.Ok)
    }

    @Test
    fun `重名被拒绝`() {
        // 两个都叫「翻译」的模板在切换列表里无法分辨
        val existing = listOf(tpl("a", "翻译", "x"))
        val v = PromptTemplatePolicy.check("翻译", "y", existing)
        assertTrue("重名必须被拒绝", v is PromptTemplatePolicy.Verdict.Rejected)
    }

    @Test
    fun `编辑自己时可以保留原名`() {
        // ★ 编辑时不排自己就会变成"改个正文都存不了"
        val existing = listOf(tpl("a", "翻译", "x"))
        val v = PromptTemplatePolicy.check("翻译", "改过的正文", existing, editingId = "a")
        assertTrue("编辑自己不该被判重名", v is PromptTemplatePolicy.Verdict.Ok)
    }

    @Test
    fun `数量上限被拒绝`() {
        val many = (1..PromptTemplatePolicy.MAX_TEMPLATES).map { tpl("t$it", "T$it", "x") }
        val v = PromptTemplatePolicy.check("新的", "y", many)
        assertTrue(v is PromptTemplatePolicy.Verdict.Rejected)
    }

    // ------------------------------------------------------------------
    // 三、★★★ 删除必须修复选中项（本功能最容易错的地方）
    // ------------------------------------------------------------------

    @Test
    fun `删掉正在使用的模板时 选中项必须被清空`() {
        // ★★★ 若不处理：selectedId 变悬空 ⇒ effectiveText 变空
        //   ⇒ 用户发现"人设突然没了"，但他并没有切换过模板。
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "aaa"), tpl("b", "B", "bbb")),
            selectedId = "a",
        )
        val after = PromptTemplatePolicy.delete(set, "a")
        assertEquals(1, after.templates.size)
        assertNull(
            "删掉正在用的模板后，选中项必须回到 null（明确表示不用模板）",
            after.selectedId,
        )
        assertEquals("", after.effectiveText)
    }

    @Test
    fun `删掉别的模板时不动选中项`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "aaa"), tpl("b", "B", "bbb")),
            selectedId = "a",
        )
        val after = PromptTemplatePolicy.delete(set, "b")
        assertEquals("a", after.selectedId)
        assertEquals("aaa", after.effectiveText)
    }

    @Test
    fun `删掉正在用的模板时 不能偷偷切换到别的模板`() {
        // ★ 偷偷切换会让用户以为"我的选择被记住了"，
        //   而实际上他下一个问题会被人设完全不同的模板影响。
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "aaa"), tpl("b", "B", "bbb")),
            selectedId = "a",
        )
        val after = PromptTemplatePolicy.delete(set, "a")
        assertFalse("不能自动切到 b", after.selectedId == "b")
    }

    // ------------------------------------------------------------------
    // 四、切换
    // ------------------------------------------------------------------

    @Test
    fun `可以切到任意一个已有模板`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "A", "aaa"), tpl("b", "B", "bbb")),
            selectedId = "a",
        )
        val after = PromptTemplatePolicy.select(set, "b")
        assertEquals("b", after.selectedId)
        assertEquals("bbb", after.effectiveText)
    }

    @Test
    fun `可以明确切到不使用模板`() {
        val set = PromptTemplateSet(templates = listOf(tpl("a", "A", "aaa")), selectedId = "a")
        val after = PromptTemplatePolicy.select(set, null)
        assertNull(after.selectedId)
        assertEquals("", after.effectiveText)
    }

    @Test
    fun `切到不存在的 id 时保持原状`() {
        // 防御：不该发生，但发生了也不能把状态弄坏
        val set = PromptTemplateSet(templates = listOf(tpl("a", "A", "aaa")), selectedId = "a")
        val after = PromptTemplatePolicy.select(set, "ghost")
        assertEquals("a", after.selectedId)
    }

    // ------------------------------------------------------------------
    // 五、★★★ 旧数据迁移（升级不能把用户写的东西弄丢）
    // ------------------------------------------------------------------

    @Test
    fun `老的单个提示词会被迁移成一个模板`() {
        // ★★★ 这条是整个改版最重要的一条：
        //   升级前只有一个 custom_prompt 字符串；不迁移的话老用户
        //   会看到**空模板列表**，他写的人设凭空消失（数据还在，但界面上没有了）。
        val migrated = PromptTemplateCodec.migrateFromLegacy(
            current = PromptTemplateSet(),
            legacyCustomPrompt = "你是一位严谨的技术助理",
        )
        assertEquals(1, migrated.templates.size)
        assertEquals("你是一位严谨的技术助理", migrated.templates[0].text)
    }

    @Test
    fun `迁移后必须默认选中它 否则人设就不生效了`() {
        // ★ 不默认选上的话，用户升级后会以为"升级把我说的话弄没了"
        val migrated = PromptTemplateCodec.migrateFromLegacy(
            current = PromptTemplateSet(),
            legacyCustomPrompt = "人设内容",
        )
        assertNotNull("迁移出来的模板必须被选中", migrated.selectedId)
        assertEquals("人设内容", migrated.effectiveText)
    }

    @Test
    fun `已经有模板时不再迁移`() {
        // ★ 幂等：否则每次读配置都会塞一个"原来的自定义提示词"进去
        val current = PromptTemplateSet(
            templates = listOf(tpl("x", "已有", "已有正文")),
            selectedId = "x",
        )
        val after = PromptTemplateCodec.migrateFromLegacy(current, "老内容")
        assertEquals(1, after.templates.size)
        assertEquals("已有正文", after.effectiveText)
    }

    @Test
    fun `老提示词为空时不迁移`() {
        val after = PromptTemplateCodec.migrateFromLegacy(PromptTemplateSet(), "   ")
        assertTrue(after.templates.isEmpty())
        assertNull(after.selectedId)
    }

    // ------------------------------------------------------------------
    // 六、持久化编解码
    // ------------------------------------------------------------------

    @Test
    fun `编码解码一圈内容不变`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "标题一", "正文一\n第二行"), tpl("b", "标题二", "正文二")),
            selectedId = "b",
        )
        val back = PromptTemplateCodec.decode(PromptTemplateCodec.encode(set))
        assertEquals(2, back.templates.size)
        assertEquals("b", back.selectedId)
        assertEquals("正文二", back.effectiveText)
        // ★ 换行必须原样保留 —— 人设常常是多行的
        assertEquals("正文一\n第二行", back.templates[0].text)
    }

    @Test
    fun `坏数据解出空集合而不崩`() {
        // 配置是用户可编辑的东西，损坏概率不低 —— 绝不能让 App 起不来
        assertTrue(PromptTemplateCodec.decode("{不是合法 json").templates.isEmpty())
        assertTrue(PromptTemplateCodec.decode("").templates.isEmpty())
        assertTrue(PromptTemplateCodec.decode(null).templates.isEmpty())
    }

    @Test
    fun `中文与特殊字符能原样往返`() {
        val set = PromptTemplateSet(
            templates = listOf(tpl("a", "中文 · 标题", "含「引号」、emoji 🎯、制表符\t结束")),
            selectedId = "a",
        )
        val back = PromptTemplateCodec.decode(PromptTemplateCodec.encode(set))
        assertEquals("中文 · 标题", back.templates[0].title)
        assertEquals("含「引号」、emoji 🎯、制表符\t结束", back.templates[0].text)
    }

    // ------------------------------------------------------------------
    // 七、创建
    // ------------------------------------------------------------------

    @Test
    fun `创建时校验不通过返回 null`() {
        assertNull(PromptTemplatePolicy.create("", "正文", emptyList(), "new"))
        assertNull(
            PromptTemplatePolicy.create("重名", "x", listOf(tpl("a", "重名", "y")), "new"),
        )
    }

    @Test
    fun `创建成功后标题与正文都被 trim`() {
        val created = PromptTemplatePolicy.create("  名称  ", "  正文  ", emptyList(), "new")
        assertNotNull(created)
        assertEquals("名称", created!!.title)
        assertEquals("正文", created.text)
        assertEquals("new", created.id)
    }
}
