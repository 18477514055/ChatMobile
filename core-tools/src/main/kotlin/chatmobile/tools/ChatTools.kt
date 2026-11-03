package chatmobile.tools

/**
 * **面向手机聊天客户端**的工具集。
 *
 * ## ★★★ 为什么不是原来那 5 个
 *
 * 原有的 `run_command` / `read_file` / `list_dir` / `text_stats` / `regex_replace`
 * 解决的是「在一台有 shell、有文件系统的桌面上批处理文本」。
 * 但 ChatMobile 是**手机聊天客户端**：
 *
 * | 原有工具 | 在手机上的现实 |
 * |---|---|
 * | `run_command` | toybox 能跑，但**用户根本不需要**；而且把 50+ 命令的语法塞进提示词，每轮都在烧钱 |
 * | `read_file` / `list_dir` | App 私有目录里**没有用户文件**；用户文件在 SAF，必须用户主动选 |
 * | `text_stats` / `regex_replace` | 同上，都是文件取向 |
 *
 * ⇒ 把这 5 个默认挂上去，模型会频繁尝试读不存在的路径，然后反复道歉。
 *   **"能跑"不等于"该给"**。
 *
 * ## ★★★ 真正值钱的是「模型自己拿不到的信息」
 *
 * 模型的三个硬伤：不知道现在几点、不知道用户在哪、训练数据有截止日期。
 * 这三样恰好是**手机能提供**的。所以工具集围绕它们组织。
 *
 * ## 分类：按副作用分级，而不是按功能
 *
 * 分级决定「用户配置界面怎么呈现」和「执行前要不要确认」：
 * - [ToolKind.READ_ONLY] —— 只读，无副作用，默认全开
 * - [ToolKind.WRITE_LOCAL] —— 写本机（笔记等），需用户确认
 * - [ToolKind.EXTERNAL] —— 访问外部网络，需用户确认（且可能花钱）
 *
 * **刻意不做**：shell 执行、任意文件遍历、读通讯录/短信、自动发消息。
 * 前两个是隐私雷区且走不通，后两个是不可逆外部副作用。
 */
enum class ToolKind {
    /** 只读：不改任何状态。默认开启。 */
    READ_ONLY,
    /** 写本机：可撤销的本地副作用。默认关闭，执行前确认。 */
    WRITE_LOCAL,
    /** 访问外部网络：可能产生费用或泄露查询内容。默认关闭，执行前确认。 */
    EXTERNAL,
}

/**
 * 一个**可提供给模型**的工具的完整定义。
 *
 * ★ 与 [ToolSpec] 的区别：`ToolSpec` 只管「发给模型的 schema」，
 *   这里还带着**本地配置元信息**（分类、默认开关、设置页文案），
 *   因为设置页需要展示「这个工具是干什么的、有没有风险」。
 */
data class ToolDefinition(
    /** 模型用来调用的名字，也是本地配置的持久化键。**不要改**。 */
    val name: String,
    /** 设置页显示的名字（中文）。 */
    val displayName: String,
    /** 设置页显示的一句话说明（中文，给人看）。 */
    val summary: String,
    val kind: ToolKind,
    /** 首次安装时是否默认开启。 */
    val enabledByDefault: Boolean,
    /**
     * 执行前是否必须用户确认。
     *
     * ★ 这条**必须真的被消费**。原 `ToolSpec.requiresConfirmation` 定义了却没人用，
     *   等于给了一张空头支票。
     */
    val requiresConfirmation: Boolean,
    /** 发给模型的 schema（英文，与提示词语言一致）。 */
    val spec: ToolSpec,
)

/**
 * 内置工具目录 —— **唯一真源**。
 *
 * ★ 顺序即 UI 显示顺序，也**是工具表序列化顺序**。
 *   所有厂商的缓存都逐字节比对前缀，所以这个顺序一旦上线就**冻结**，
 *   新增只能追加在末尾（Anthropic 的 `tools` 排在 `system` 之前，改它最贵）。
 */
object ChatTools {

    // ==============================================================
    // ① 时间 —— 模型最常缺、也最便宜的一个
    // ==============================================================

    val getTime = ToolDefinition(
        name = "get_time",
        displayName = "读取时间",
        summary = "让模型知道当前的日期、时间与时区",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        // 只读且无副作用，本地就能算出来，不值得打断用户
        requiresConfirmation = false,
        spec = ToolSpec(
            name = "get_time",
            description = "Get the current local date and time on the user's device, " +
                "including the time zone and UTC offset. Use this whenever the answer " +
                "depends on the present moment: today, tomorrow, deadlines, elapsed time, " +
                "or anything relative to now.",
            parameters = ToolParameters(
                properties = mapOf(
                    "timezone" to ParamSpec(
                        ParamType.STRING,
                        "IANA time zone name such as Asia/Shanghai. Omit to use the device time zone.",
                    ),
                ),
                required = emptyList(),
            ),
        ),
    )

    // ==============================================================
    // ② 地点 —— 需要权限，没有权限就如实说没有
    // ==============================================================

    val getLocation = ToolDefinition(
        name = "get_location",
        displayName = "读取大致位置",
        summary = "让模型知道你所在的城市（需位置权限，只取粗略位置）",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        requiresConfirmation = false,
        spec = ToolSpec(
            name = "get_location",
            description = "Get the user's approximate location. Returns a city-level " +
                "position by default. Use only when the answer genuinely depends on where " +
                "the user is, such as weather, nearby places, or local time differences. " +
                "If the permission is unavailable the tool says so; do not guess a location.",
            parameters = ToolParameters(
                properties = mapOf(
                    "accuracy" to ParamSpec(
                        ParamType.STRING,
                        "city = coarse position, precise = device GPS. Defaults to city.",
                        enum = listOf("city", "precise"),
                    ),
                ),
                required = emptyList(),
            ),
        ),
    )

    // ==============================================================
    // ③ 读网页 —— 用户贴链接时最常用
    // ==============================================================

    val webFetch = ToolDefinition(
        name = "web_fetch",
        displayName = "读取网页",
        summary = "抓取一个网址的正文并让模型阅读（用户贴链接时用）",
        kind = ToolKind.EXTERNAL,
        enabledByDefault = true,
        requiresConfirmation = false,
        spec = ToolSpec(
            name = "web_fetch",
            description = "Fetch a web page and return its readable text content. Use when the " +
                "user pastes a link, or when you need the full text of a page you already have " +
                "the URL for. HTML markup is stripped. Long pages are truncated.",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ParamSpec(ParamType.STRING, "Absolute http/https URL to fetch."),
                    "max_chars" to ParamSpec(
                        ParamType.INTEGER,
                        "Maximum characters of text to return, 1000-20000. Defaults to 8000.",
                    ),
                ),
                required = listOf("url"),
            ),
        ),
    )

    // ==============================================================
    // ④ 记住一件事 —— 写进记忆库，与"记忆"功能天然一体
    // ==============================================================

    val saveMemory = ToolDefinition(
        name = "save_memory",
        displayName = "记住一件事",
        summary = "让模型把你说的事实/偏好存进记忆库（跨对话）",
        kind = ToolKind.WRITE_LOCAL,
        enabledByDefault = true,
        requiresConfirmation = false,
        spec = ToolSpec(
            name = "save_memory",
            description = "Save a durable fact about the user into the long-term memory store, " +
                "so it is available in future conversations. Use this only when the user asks " +
                "you to remember something, or states a stable preference. Do not use it for " +
                "one-off questions or transient context.",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ParamSpec(
                        ParamType.STRING,
                        "One sentence, written in third person, e.g. " +
                            "\"The user prefers concise answers without pleasantries.\"",
                    ),
                    "category" to ParamSpec(
                        ParamType.STRING,
                        "Which bucket this belongs to.",
                        enum = listOf("preference", "user", "project", "person", "event", "other"),
                    ),
                ),
                required = listOf("content"),
            ),
        ),
    )

    // ==============================================================
    // ⑤ 搜索记忆 —— 记忆条数变多后，"注入全量"不够用
    // ==============================================================

    val searchMemory = ToolDefinition(
        name = "search_memory",
        displayName = "检索记忆",
        summary = "让模型按关键词在你已有的记忆里查找",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        requiresConfirmation = false,
        spec = ToolSpec(
            name = "search_memory",
            description = "Search the user's long-term memory store by keyword and return the " +
                "matching entries. Use this when the user refers to something from an earlier " +
                "conversation that is not already in your context.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ParamSpec(ParamType.STRING, "Keyword or phrase to look for."),
                ),
                required = listOf("query"),
            ),
        ),
    )

    /**
     * 全部工具（**顺序冻结**）。
     *
     * ★ 新增工具只能**追加在末尾**。在中间插入会让它后面每一个工具的 schema
     *   在缓存前缀里的位置都平移 ⇒ 全体用户一次全量重缓存。
     */
    fun all(): List<ToolDefinition> = listOf(
        getTime,
        getLocation,
        webFetch,
        saveMemory,
        searchMemory,
    )

    /** 按名字查。 */
    fun byName(name: String): ToolDefinition? = all().firstOrNull { it.name == name }

    /** 默认配置：每个工具按 [ToolDefinition.enabledByDefault]。 */
    fun defaultEnabled(): Map<String, Boolean> = all().associate { it.name to it.enabledByDefault }
}
