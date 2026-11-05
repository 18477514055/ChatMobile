package com.chatmobile.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **一个提示词模板** —— 标题 + 正文。
 *
 * ## ★ 用户为什么需要它（原话）
 *
 * > 「现在允许将提示词保存为一个模板（一个模板包含一个标题和一个提示词文本，可以随时切换）」
 *
 * 之前只有一个 `customPrompt` 字符串：想换人设就得把旧的全删掉重写，
 * 而写过的那些通常是**要反复用**的（"严谨技术助理" / "温柔陪聊" / "翻译腔校对"）。
 * 单槽位的本质问题是：**它没有留住任何东西**。
 *
 * ## ★★★ 为什么标题是必填的
 *
 * 标题不是装饰，它是**切换时唯一的辨认依据**。用户在一个下拉列表里看到的是
 * "严谨技术助理 / 温柔陪聊 / 英文校对"，而不是三段正文的前 20 个字 ——
 * 后者在列表里会长得几乎一样。所以：
 * - 标题为空 ⇒ 视为非法，不允许保存（[PromptTemplatePolicy]）
 * - 标题要短（列表里一行放得下）
 *
 * @param id 稳定标识。**不能用标题当 id** —— 用户会改标题，改完引用就断了。
 * @param title 用户可见的名字，切换时靠它辨认
 * @param text 正文（真正注入给模型的那段）
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val title: String,
    val text: String,
)

/**
 * 模板集合 + 当前选中 —— **这是要持久化的整体**。
 *
 * ## ★★ 为什么把"当前选中"和"模板列表"放在同一个对象里
 *
 * 因为它俩必须**一起变**。分成两个键的话会出现一瞬间的不一致：
 * 删掉正在用的模板，但选中项还指着那个 id ⇒ 下一轮请求注入一个已不存在的模板。
 * 放在一个对象里、一次写，就不会有中间态。
 *
 * @param selectedId 当前选中的模板 id；null = **不使用任何模板**
 *   ★ null 是一个合法且有意义的取值：用户可能就想要"纯净的默认提示词"。
 *     不能把它当成"没选"而去自动选第一个。
 */
@Serializable
data class PromptTemplateSet(
    val templates: List<PromptTemplate> = emptyList(),
    val selectedId: String? = null,
) {
    /** 当前生效的模板（可能为 null = 没有选中 / 选中的已被删）。 */
    val selected: PromptTemplate?
        get() = selectedId?.let { id -> templates.firstOrNull { it.id == id } }

    /**
     * 真正要注入的那段文本。
     *
     * ★ **单一入口**：注入点只调这个，不去自己拼。
     *   否则"选中了一个已删除的模板"这类边界会在每个调用点各错一次。
     */
    val effectiveText: String
        get() = selected?.text?.trim().orEmpty()
}

/**
 * 模板的校验与操作 —— **纯逻辑，可完整单测**。
 *
 * ★ 这些判断不放 UI 里：UI 只能"显示错误"，真正决定能不能存的是这里。
 *   本项目已有教训（`L26`）：判据写在 UI 上、执行路径上不查 ⇒ 存了不等于生效。
 */
object PromptTemplatePolicy {

    /** 标题上限：列表里一行放得下，而且它只是个名字。 */
    const val MAX_TITLE = 24

    /** 正文上限：注入在 system 里，太长会挤掉历史与记忆预算。 */
    const val MAX_TEXT = 8000

    /** 模板数量上限。 */
    const val MAX_TEMPLATES = 50

    /** 校验结果。 */
    sealed interface Verdict {
        data class Ok(val title: String, val text: String) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 校验一次"保存/新建"。
     *
     * @param editingId 正在编辑哪个模板（新建时为 null）—— 用于查重名时排除自己
     */
    fun check(
        title: String,
        text: String,
        existing: List<PromptTemplate>,
        editingId: String? = null,
    ): Verdict {
        val t = title.trim()
        val body = text.trim()

        // ★ 标题必填：它是切换时唯一的辨认依据（见 PromptTemplate 注释）
        if (t.isEmpty()) {
            return Verdict.Rejected("标题不能为空 —— 切换模板时你就是靠它来认的")
        }
        if (t.length > MAX_TITLE) {
            return Verdict.Rejected("标题最多 $MAX_TITLE 个字，现在 ${t.length}")
        }
        // ★ 正文可以空吗？**可以**。空模板有真实用途：临时用来"清空当前人设"
        //   而不必删掉模板本身。但正文为空的模板签名要提醒（由 UI 做）。
        if (body.length > MAX_TEXT) {
            return Verdict.Rejected("正文最多 $MAX_TEXT 字，现在 ${body.length}")
        }
        if (editingId == null && existing.size >= MAX_TEMPLATES) {
            return Verdict.Rejected("最多 $MAX_TEMPLATES 个模板，先删掉一些")
        }
        // ★ 重名检查：两个都叫「翻译」的模板在切换列表里无法分辨。
        val dup = existing.firstOrNull { it.id != editingId && it.title.trim() == t }
        if (dup != null) {
            return Verdict.Rejected("已经有一个叫「$t」的模板了，换个名字")
        }
        return Verdict.Ok(title = t, text = body)
    }

    /**
     * 生成一个新模板（id 由调用方给的工厂决定，便于测试注入固定值）。
     */
    fun create(
        title: String,
        text: String,
        existing: List<PromptTemplate>,
        newId: String,
    ): PromptTemplate? {
        val v = check(title, text, existing)
        return (v as? Verdict.Ok)?.let {
            PromptTemplate(id = newId, title = it.title, text = it.text)
        }
    }

    /**
     * 删除一个模板，并**修复选中项**。
     *
     * ## ★★★ 这是本功能最容易出错的地方
     *
     * 删掉的正好是当前选中的那个时，`selectedId` 会变成一个**悬空引用**。
     * 若不处理：
     * - `selected` 返回 null ⇒ `effectiveText` 变空 ⇒ 用户发现"人设突然没了"，
     *   但他并没有切换过模板
     * - 更糟的是，如果他之后又新建一个**同名**模板（新 id），
     *   悬空的旧 id 依然指向不存在的东西 —— 表现为"我选了它却没生效"
     *
     * ⇒ 规则：**删掉选中的那个 ⇒ selectedId 回 null（明确表示"没有用模板"）**，
     *   而不是偷偷跳到别的模板上。偷偷切换会让用户以为自己的选择被记住了。
     */
    fun delete(set: PromptTemplateSet, id: String): PromptTemplateSet {
        val remaining = set.templates.filterNot { it.id == id }
        val stillValid = set.selectedId?.takeIf { sid -> remaining.any { it.id == sid } }
        return set.copy(templates = remaining, selectedId = stillValid)
    }

    /**
     * 选中一个模板。`null` = 不使用模板。
     *
     * ★ 允许选中 null 是刻意的：用户可能就想要"纯净的默认提示词"。
     *   传一个不存在的 id 会被忽略并保持原状（防御，不该发生）。
     */
    fun select(set: PromptTemplateSet, id: String?): PromptTemplateSet {
        if (id == null) return set.copy(selectedId = null)
        if (set.templates.none { it.id == id }) return set
        return set.copy(selectedId = id)
    }
}

/**
 * 模板集合的持久化编解码。
 *
 * ## ★★★ 兼容旧数据（这段是本文件里最不能省的部分）
 *
 * 升级前只有一个 `customPrompt: String`。升级后如果直接读新键，
 * 老用户会看到**空模板列表** —— 他写的人设凭空消失（数据还在，但界面上没有了）。
 *
 * ⇒ [migrateFromLegacy] 把老的那个字符串变成一个模板，只在
 *   "新键为空 且 老键非空" 时执行一次。老键**不删**（万一要回退版本）。
 */
object PromptTemplateCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    /** 老数据的迁移产物用的固定 id —— 保证"只迁一次"且可预期。 */
    const val LEGACY_ID = "legacy-custom-prompt"

    fun encode(set: PromptTemplateSet): String = json.encodeToString(set)

    /**
     * 解码。★ **永不抛异常**：配置损坏时从空集合开始，不能让 App 起不来。
     */
    fun decode(raw: String?): PromptTemplateSet {
        if (raw.isNullOrBlank()) return PromptTemplateSet()
        return runCatching { json.decodeFromString<PromptTemplateSet>(raw) }
            .getOrElse { PromptTemplateSet() }
    }

    /**
     * 从旧版单字符串迁移。
     *
     * @param current 新键解出来的集合（非空则**不动** —— 已经迁移过了）
     * @param legacyCustomPrompt 旧键 `custom_prompt` 的内容
     * @return 合并后的集合
     */
    fun migrateFromLegacy(current: PromptTemplateSet, legacyCustomPrompt: String): PromptTemplateSet {
        // ★ 已经有模板了 ⇒ 说明用户已经在用新版，绝不能再塞一个"旧提示词"进去
        if (current.templates.isNotEmpty()) return current
        val body = legacyCustomPrompt.trim()
        if (body.isEmpty()) return current
        val legacy = PromptTemplate(
            id = LEGACY_ID,
            title = "原来的自定义提示词",
            text = body,
        )
        return PromptTemplateSet(
            templates = listOf(legacy),
            // ★ 迁移后**默认选上它** —— 否则用户升级后人设就不生效了，
            //   那是最糟的观感："升级把我说的话弄没了"。
            selectedId = legacy.id,
        )
    }
}
