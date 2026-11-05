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
 * **工具分组** —— OpenAI `namespace` 的等价物（调研报告 P1-2）。
 *
 * ## ★★★ 为什么要分组（官方原话）
 *
 * OpenAI 明确说：「对单个延迟函数，模型仍然能看到函数名和描述，
 * 所以**实际上主要只是延迟了参数 schema**」。要拿最大收益必须用 namespace，
 * 并建议**每组 <10 个函数**。
 *
 * 我们的 `describe_tool` 目录 = 名字 + 一句话全量罗列 = 单函数 defer 的水平。
 *
 * ## ★★★ 但我们**不做**"选组才能看到工具"那种分组
 *
 * 报告同时警告了那个失败模式：**选错组 = 死路**（模型看不到正确工具、无法自纠）。
 * 在 10 个内建工具、几十个 MCP 工具这个量级上，为了省几百 token 引入
 * "模型可能永远找不到工具"的风险，是**不划算的**。
 *
 * ⇒ 这里的做法是**保守版**：分组只用来**组织目录的显示结构**
 *   （每组一个小标题 + 该组工具），**不构成筛选**。
 *   模型仍然能看到全部工具名，检索也跨组进行 —— 只是读起来更有结构。
 *
 * ★ 真正的分层路由（先选组再选工具）要等工具真的上百之后再做，
 *   而且必须先解决"选错组"的自纠路径。
 */
enum class ToolGroup(val label: String) {
    /** 时间 / 位置 / 运行时事实。 */
    CONTEXT("Context"),

    /** 记忆读写。 */
    MEMORY("Memory"),

    /** 工作区文件。 */
    FILES("Files"),

    /** 外网访问。 */
    WEB("Web"),

    /** 本机历史对话。 */
    HISTORY("History"),

    /** MCP 服务器提供的工具。 */
    MCP("MCP"),

    /** 配置类元工具（探测 / 起草）。 */
    SETUP("Setup"),
    ;
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
    /**
     * **一句话**说明 —— 懒加载时写进工具目录的那一行。
     *
     * ## ★ 为什么单独一个字段，而不是截取 [description]
     *
     * [description] 是写给人看的长文（几十到上百字，讲清"什么时候该用/不该用"）。
     * 目录里塞不下那么多 —— 10 个工具 × 100 字就是 1000 字，等于没省。
     *
     * ⇒ 单独写一句**不超过 60 字**的，回答一个问题：**"它是干什么的"**。
     *   模型靠这一句判断"要不要花一次往返去 describe 它"。
     *
     * ★ 判据：这句话里**不能有"如何调用"**（那是 describe 之后才需要的），
     *   只能有**用途**。写调用方式等于把懒加载又变回常驻。
     */
    val oneLiner: String,
    /**
     * 默认模式下这个工具该常驻还是懒加载。
     *
     * ## ★ 分成两档，不是一刀切（调研报告 P1-3）
     *
     * 官方两家都建议「**3–5 个最常用的常驻，其余延迟**」，而不是全常驻或全延迟。
     * 本项目按这个规则分成两档：
     *
     * | 档 | 工具 | 为什么 |
     * |---|---|---|
     * | 常驻 | `get_time` / `web_search` / `search_memory` | 几乎每轮都可能要用，让模型多跳一次往返不值得 |
     * | 按需 | 其余 7 个 | 单个对话里通常只用一次或根本不用 |
     *
     * ## ★★ 这个权衡必须说清楚（别把它当成纯粹的好处）
     *
     * 按需加载**不是免费的**：每次要用一个按需工具，模型都要先花一次 `describe_tool`
     * 往返 —— 那是一次**完整的模型调用**。
     *
     * 在 10 个工具这个量级，省下的是每轮约 5000 字符（≈1250 token），
     * 代价是每个新用到的工具多一次往返。粗算下来：一轮对话省 1250 token × 10 轮
     * ≈ 12500 token，换 3 次额外往返 —— 大致打平偏正，**但并没有压倒性优势**。
     *
     * ⇒ 按需加载的真正价值不是现在省钱，是**为 MCP 铺路**（工具上百时它就压倒性有利）
     *   + **把选择权交给用户**。任何工具都可以在设置页单独改成「常驻」，改完就恢复
     *   零往返。
     *
     * ★ 内建工具默认 [ToolMode.Resident] 的旧行为已被这条替换；
     *   老用户**存过**的配置不受影响（`ToolMode.fromLegacyFlag` 保证），
     *   只有从未动过工具设置的用户会看到新默认。
     *
     * ★ 将来 MCP 进来的工具**一律**默认 [ToolMode.Lazy]（数量可能上百，规则不同）。
     */
    val preferredMode: ToolMode = ToolMode.Resident,
    /**
     * **分组** —— 只用于组织目录的显示结构（见 [ToolGroup]）。
     *
     * ★ 它**不构成筛选**：模型永远能看到全部工具名，检索也跨组进行。
     *   理由见 [ToolGroup] 的注释：分层路由的失败模式（选错组=死路）
     *   在几十个工具的量级上不值得冒。
     */
    val group: ToolGroup = ToolGroup.CONTEXT,
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
        oneLiner = "Current local date, time and time zone of the device.",
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
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.CONTEXT,
        oneLiner = "Approximate current location (city level; precise only if permitted).",
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
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.WEB,
        oneLiner = "Download one web page and return its readable text.",
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
        // ★ 常驻：用户说「记住…」是高频动作，而它一旦被延迟，
        //   模型得先 describe 再 save —— 存一条记忆多花一次完整往返，很不划算。
        preferredMode = ToolMode.Resident,
        group = ToolGroup.MEMORY,
        oneLiner = "Store a durable fact about the user for future conversations.",
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
        group = ToolGroup.MEMORY,
        oneLiner = "Look up stored facts about the user by keyword.",
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

    // ==============================================================
    // ⑥⑦⑧ 文件三件套 —— 只在**有工作区**时才有意义
    // ==============================================================

    /**
     * 读文件。
     *
     * ★ 为什么默认开启、却只在有工作区时才有用：
     *   没有工作区时工具会如实回「这轮没有工作区」，
     *   模型据此知道要说"我需要你先把工作区指给我"，而不是乱猜路径。
     *   若默认关闭，用户建了工作区还得再进设置开一遍 —— 那是多一道无谓的手续。
     */
    val readFile = ToolDefinition(
        name = "read_file",
        displayName = "读取文件",
        summary = "读取工作区里的某个文本文件（受当前权限档位约束）",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        requiresConfirmation = false,
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.FILES,
        oneLiner = "Read a text file from the workspace.",
        spec = ToolSpec(
            name = "read_file",
            description = "Read a text file from the current workspace and return its content. " +
                "Use when the user refers to a file you are supposed to have seen, or asks you " +
                "to work on an existing file. Pass an absolute path, or a path relative to the " +
                "workspace root. Access outside the workspace is refused unless the user granted " +
                "full access. If a file does not exist the tool says so; do not guess its content.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ParamSpec(
                        ParamType.STRING,
                        "Path to the file. Relative paths resolve against the workspace root.",
                    ),
                    "max_chars" to ParamSpec(
                        ParamType.INTEGER,
                        "Maximum characters to return, 200-20000. Defaults to 8000.",
                    ),
                ),
                required = listOf("path"),
            ),
        ),
    )

    /**
     * 写文件。
     *
     * ★ 归入 [ToolKind.WRITE_LOCAL]，`requiresConfirmation = true`。
     *   这是本项目**第一个真的消费这个字段**的工具 —— 在此之前它只是一张空头支票。
     */
    val writeFile = ToolDefinition(
        name = "write_file",
        displayName = "写入文件",
        summary = "在工作区里新建或覆盖一个文本文件（默认需确认）",
        kind = ToolKind.WRITE_LOCAL,
        enabledByDefault = true,
        requiresConfirmation = true,
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.FILES,
        oneLiner = "Create or overwrite a text file in the workspace.",
        spec = ToolSpec(
            name = "write_file",
            description = "Create or overwrite a text file in the current workspace. Set append " +
                "to true to add to the end instead of replacing. Use this when the user asks you " +
                "to save, export, or produce a file. Modifying files outside the workspace is " +
                "refused unless the user granted full access. Never claim a file was written " +
                "without calling this tool.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ParamSpec(
                        ParamType.STRING,
                        "Path to the file. Relative paths resolve against the workspace root.",
                    ),
                    "content" to ParamSpec(
                        ParamType.STRING,
                        "Full text content to write. May be empty to create an empty file.",
                    ),
                    "append" to ParamSpec(
                        ParamType.BOOLEAN,
                        "true = append to the end, false or omitted = overwrite the whole file.",
                    ),
                ),
                required = listOf("path", "content"),
            ),
            // ★ 为什么 write_file 需要示例：`append` 是最贵的坑。
            //   JSON Schema 只说得出"它是布尔"，说不出"省略即覆盖整个文件"。
            //   模型想「追加一行日志」却省了 append ⇒ 用户的文件被清空。
            examples = listOf(
                ToolExample(
                    whenTo = "overwrite or create a file with exactly this content",
                    args = """{"path":"notes/todo.md","content":"# TODO\n- buy milk\n"}""",
                ),
                ToolExample(
                    whenTo = "add lines to an existing file without touching what is already there",
                    args = """{"path":"notes/todo.md","content":"- call mom\n","append":true}""",
                ),
            ),
        ),
    )

    /** 列目录。 */
    val listDir = ToolDefinition(
        name = "list_dir",
        displayName = "列出目录",
        summary = "列出工作区（或其中某个子目录）里的文件",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        requiresConfirmation = false,
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.FILES,
        oneLiner = "List files and folders in the workspace.",
        spec = ToolSpec(
            name = "list_dir",
            description = "List the files and subdirectories of the current workspace, or of one " +
                "directory inside it. Use this before reading or writing when you do not know " +
                "what is already there. Omit path to list the workspace root.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ParamSpec(
                        ParamType.STRING,
                        "Directory to list. Omit for the workspace root.",
                    ),
                ),
                required = emptyList(),
            ),
        ),
    )

    // ==============================================================
    // ⑨ 联网搜索 —— 与「读网页」配对：先搜到地址，再抓正文
    // ==============================================================

    /**
     * 联网搜索。
     *
     * ## ★ 为什么它和 `web_fetch` 是两个工具，而不是一个
     *
     * 它们解决的是**不同的问题**：
     * - `web_fetch`：**你已经有地址了**，去把正文拿回来
     * - `web_search`：**你还不知道该看哪儿**，先找地址
     *
     * 合成一个工具只会让模型在"有地址"和"没地址"之间反复试错。
     *
     * ## ★ 免费优先、付费保底（用户要求）
     *
     * 实现侧（`AndroidToolHost.webSearch`）先走**不需要任何 key** 的免费源，
     * 全都不通才用用户自己配的付费 key。原因很直接：这个 App 的定位是
     * "自带 API Key"，如果搜索还得再让用户去买一个搜索 API，很多人会直接用不了。
     */
    val webSearch = ToolDefinition(
        name = "web_search",
        displayName = "联网搜索",
        summary = "搜网上的页面（默认用免费搜索源，不需要额外密钥）",
        kind = ToolKind.EXTERNAL,
        enabledByDefault = true,
        requiresConfirmation = false,
        group = ToolGroup.WEB,
        oneLiner = "Search the web and return result titles, snippets and URLs.",
        spec = ToolSpec(
            name = "web_search",
            description = "Search the web and return a ranked list of pages with titles, URLs " +
                "and short summaries. Use this when the answer depends on current information " +
                "you do not have, or when the user asks you to look something up. Follow up " +
                "with web_fetch to read the full text of a result. " +
                "By default this uses a free search source. If the free results come back " +
                "empty, irrelevant, or too shallow for the question, call it again with " +
                "use_paid=true to use the user's configured paid search service. " +
                "If the search fails the tool tells you which sources were tried; " +
                "do not invent results.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ParamSpec(
                        ParamType.STRING,
                        "The search query. Keep it short and specific, like a browser search box.",
                    ),
                    "count" to ParamSpec(
                        ParamType.INTEGER,
                        "How many results to return, 1-15. Defaults to 6.",
                    ),
                    // ★★★ 主动权的落点：把"换更好的搜索"做成模型可以自己拉的一个开关。
                    //
                    //   为什么不写死顺序：搜索结果好不好，**只有读到结果的模型知道**。
                    //   宿主按固定顺序排（免费优先 / 付费优先）都是猜 ——
                    //   猜"免费够用"会让用户配的服务永远不生效，
                    //   猜"付费优先"会让每一次普通搜索都花钱。
                    //   ⇒ 默认免费，**由模型在认为结果不够时自己升级**。
                    "use_paid" to ParamSpec(
                        ParamType.BOOLEAN,
                        "Set true to use the paid search service instead of the free one. " +
                            "Leave it false (default) for a normal search. Use it when free " +
                            "results were empty, irrelevant, or too low quality for the question.",
                    ),
                ),
                required = listOf("query"),
            ),
            // ★ 为什么 web_search 需要示例：`use_paid` 是「只有当模型自己判断结果不够时
            //   才该翻的开关」。schema 说得出"布尔，默认 false"，说不出
            //   "这一条只有在免费结果真的不行时才算用对" —— 而模型很容易
            //   一上来就 use_paid=true（对用户是白花钱），或者永远不敢开（付费通道白配）。
            examples = listOf(
                ToolExample(
                    whenTo = "a normal lookup (free source, default)",
                    args = """{"query":"Jetpack Compose LazyColumn key","count":6}""",
                ),
                ToolExample(
                    whenTo = "free results came back empty or irrelevant, so retry on the paid service",
                    args = """{"query":"Jetpack Compose LazyColumn key","use_paid":true}""",
                ),
            ),
        ),
    )

    // ==============================================================
    // ⑩ 历史检索 —— 「我在哪个对话干了什么」的按需入口
    // ==============================================================

    /**
     * 跨会话历史检索。
     *
     * ## ★★★ 为什么是**工具**而不是**注入**
     *
     * 用户原话：「我的想法是，现有的注入规则不变，当我提到时模型再去查且能查到」
     *
     * 这句话把设计定死了：
     * - **注入规则不变** ⇒ 不动 `memoryCuratorPrompt`、不动记忆注入预算，
     *   记忆库继续只装「关于用户的稳定画像」
     * - **提到时再查** ⇒ 历史是**按需拉取**的，不占常驻上下文
     *
     * 这个划分很关键：如果改成"把历史也注进去"，每次请求都要为
     * "用户可能问到的任何历史"付 token；而按需查询只在真的问到时付一次。
     *
     * ## 它能回答什么
     * - 「我上次让你修的那个 bug 后来怎么样了」→ 搜关键词找回那个对话
     * - 「上礼拜那个关于向量检索的对话结论是什么」→ 搜"向量"再按会话过滤
     * - 「我这周都在忙什么」→ 按时间范围列出会话标题与最近的用户诉求
     *
     * ## 它**不能**做什么（必须写进描述，否则模型会误以为万能）
     * - 不做语义检索（是关键词 `LIKE`），同义词要换词再搜
     * - 不返回整段对话（只给命中片段 + 所在会话标题），要细节就用出现的原话
     * - 不跨设备（只有本机这份库）
     */
    val searchHistory = ToolDefinition(
        name = "search_history",
        displayName = "查历史对话",
        summary = "按关键词或时间在你的历史对话里找「当时做了什么」",
        kind = ToolKind.READ_ONLY,
        enabledByDefault = true,
        requiresConfirmation = false,
        // ★ 按需：用户原话「当我提到时模型再去查」—— 它本来就是低频触发器。
        preferredMode = ToolMode.Lazy,
        group = ToolGroup.HISTORY,
        oneLiner = "Search this device's PAST conversations, or list recent ones.",
        spec = ToolSpec(
            name = "search_history",
            description = "Search the user's PAST conversations on this device. Use this when the " +
                "user refers to something done in an earlier chat that is not in your current " +
                "context — e.g. \"what did we decide about X\", \"the bug I asked you to fix\", " +
                "\"what was I working on last week\". " +
                "Matching is by KEYWORD substring, not semantic: if nothing is found, retry with " +
                "a different or shorter word rather than concluding nothing exists. " +
                "Returns matched snippets together with the conversation title and date. " +
                "It does NOT return whole conversations; ask the user to open that chat if they " +
                "need full detail. Set mode=recent to list recent conversations by time instead " +
                "of searching.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ParamSpec(
                        ParamType.STRING,
                        "Keyword or phrase to look for in past messages. Leave empty when " +
                            "mode=recent.",
                    ),
                    "mode" to ParamSpec(
                        ParamType.STRING,
                        "search = keyword search (default). recent = list recent conversations " +
                            "by time, ignoring query.",
                        enum = listOf("search", "recent"),
                    ),
                    "days" to ParamSpec(
                        ParamType.INTEGER,
                        "Only look at messages from the last N days (1-3650). Omit for all time.",
                    ),
                    "count" to ParamSpec(
                        ParamType.INTEGER,
                        "Maximum hits to return, 1-20. Defaults to 8.",
                    ),
                ),
                required = emptyList(),
            ),
            // ★ 为什么 search_history 需要示例：它是**唯一一个「必填参数可以为空」**的工具。
            //   `required = emptyList()` + 「mode=recent 时 query 可省」这层关系，
            //   JSON Schema 根本表达不出来。模型于是有两种典型错法：
            //   ① 想"看看最近干了啥"却硬编一个 query（搜出空结果，得出"没有历史"的错误结论）
            //   ② 忘了 mode=recent，只传 query，结果什么都没传
            examples = listOf(
                ToolExample(
                    whenTo = "looking up a topic by keyword",
                    args = """{"query":"向量检索","count":10}""",
                ),
                ToolExample(
                    whenTo = "listing what the user did recently — no query at all",
                    args = """{"mode":"recent","days":7}""",
                ),
            ),
        ),
    )

    /**
     * 全部工具（**顺序冻结**）。
     *
     * ★ 新增工具只能**追加在末尾**。在中间插入会让它后面每一个工具的 schema
     *   在缓存前缀里的位置都平移 ⇒ 全体用户一次全量重缓存。
     *
     * ★ 缓存代价的另一面：**默认关闭的工具不进这张表**（见调用点按 enabledTools
     *   过滤），所以加工具这件事本身不会一次性改变所有用户的缓存前缀 ——
     *   只有当用户真的打开了它，他的那一份工具表才变。
     */
    fun all(): List<ToolDefinition> = listOf(
        getTime,
        getLocation,
        webFetch,
        saveMemory,
        searchMemory,
        readFile,
        writeFile,
        listDir,
        webSearch,
        searchHistory,
    )

    /** 按名字查。 */
    fun byName(name: String): ToolDefinition? = all().firstOrNull { it.name == name }

    /** 默认配置：每个工具按 [ToolDefinition.enabledByDefault]。 */
    fun defaultEnabled(): Map<String, Boolean> = all().associate { it.name to it.enabledByDefault }
}
