package chatmobile.memory

/**
 * 结构化记忆的键空间。
 *
 * ## 为什么要有 key（这是"同一件事 9 遍"的结构性答案）
 *
 * 自由文本记忆只能靠**相似度**判断"是不是同一件事"，永远有漏网之鱼。
 * 结构化之后：**同一个 key 只有一个值** —— 「回答风格」这条记忆无论被抽到多少次，
 * 都只会覆盖同一个槽位。相似度去重退化成兜底，而不是主力。
 *
 * ## 键的形状
 *
 * ```
 * pref.reply_style      偏好：回答风格
 * pref.language         偏好：语言
 * user.name             关于你：称呼
 * user.city             关于你：城市
 * project.current       项目：当前项目
 * person.wang           人物：姓王的那位
 * event.2026-10-28_move 事件：允许追加，不覆盖
 * ```
 *
 * ★ 只有 `event.*` 允许"同 key 多条"；其余一律覆盖。
 * ★ 模型给不出 key 时传 null —— 退回自由文本 + 近义去重，不强行编一个。
 */
object MemoryKey {

    /** 允许的 key 形状：小写字母/数字/点/下划线/短横，最长 48。 */
    private val VALID = Regex("^[a-z][a-z0-9_.-]{0,47}$")

    /** 事件前缀 —— 唯一允许"同 key 多条"的命名空间。 */
    const val EVENT_PREFIX = "event."

    /** 面板分组标签（顺序即展示顺序）。 */
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "pref" to "偏好",
        "user" to "关于你",
        "project" to "项目",
        "person" to "人物",
        "event" to "事件",
        "other" to "其他",
    )

    /** 归一化 + 校验；非法返回 null（宁可不结构化，也不写脏 key）。 */
    fun normalize(key: String?): String? {
        val k = key?.trim()?.lowercase().orEmpty()
        if (k.isEmpty()) return null
        // 允许模型给 "偏好.回答风格" 这种中文命名空间？不允许 —— key 必须是稳定 ASCII。
        if (!VALID.matches(k)) return null
        return k
    }

    /** 命名空间（第一个点之前）。 */
    fun namespace(key: String?): String {
        val k = key?.trim()?.lowercase().orEmpty()
        if (k.isEmpty()) return "other"
        val ns = k.substringBefore('.')
        return if (CATEGORIES.any { it.first == ns }) ns else "other"
    }

    /** 面板上显示的分类名。 */
    fun label(key: String?): String =
        CATEGORIES.firstOrNull { it.first == namespace(key) }?.second ?: "其他"

    /** 允许同 key 多条？ */
    fun allowsDuplicates(key: String?): Boolean =
        key?.lowercase()?.startsWith(EVENT_PREFIX) == true

    /**
     * 注入到提示词里的一行文本。
     *
     * 带分类前缀是为了让模型知道"这条是偏好还是事件"，同时**不引入分组标题** ——
     * 标题会让分类的增删改变整段文本，破坏前缀缓存。
     */
    fun render(entry: MemoryEntry): String {
        val key = entry.key
        return if (key.isNullOrBlank()) entry.content else "[${label(key)}] ${entry.content}"
    }
}
