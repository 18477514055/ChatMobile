package chatmobile.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具执行需要的**平台能力** —— 由 App 层实现，`core-tools` 只认这个接口。
 *
 * ## ★ 为什么用接口而不是直接调 Android API
 *
 * 1. **可单测**：`core-tools` 是纯 JVM 模块，没有 Context、没有 `android.util.Log`。
 *    让 App 层把能力注进来，执行器本身就能用普通单元测试钉死。
 * 2. **职责清楚**：`core-tools` 决定"怎么把结果组织成模型能读的文本"，
 *    App 层决定"去哪里取值"（时钟、LocationManager、OkHttp、记忆库）。
 * 3. **不泄露 Android 依赖到 core**：`core-tools` 的 build.gradle 里没有 android 依赖，
 *    一旦有人在这里 `import android.*`，编译立刻失败 —— 这是**结构性的守卫**。
 *
 * ★ 每个方法都**必须可失败**且**不抛异常**：工具调用失败是一种正常结果，
 *   要作为数据喂回模型让它自己收场，而不是炸掉整条对话。
 */
interface ToolHost {

    /**
     * 当前本地时间。
     *
     * @param timezone IANA 时区名；空 = 用设备时区。
     * @return 给人/模型读的一行文本；时区名非法时返回带错误说明的文本（不抛异常）。
     */
    fun nowText(timezone: String?): String

    /**
     * 大致位置。
     *
     * @param precise true = 尝试精确定位；false = 只要城市级。
     * @return 一行文本；没有权限/没有定位时**必须如实说明**，不许编造。
     */
    fun locationText(precise: Boolean): String

    /**
     * 抓一个网页的正文。
     *
     * ★ 必须是**同步**调用（工具执行发生在 IO 调度器上）。
     * @return 去掉 HTML 标签后的文本；失败返回以 `ERROR:` 开头的一行说明。
     */
    fun fetchUrl(url: String, maxChars: Int): String

    /**
     * 写一条记忆，返回一句确认文本。
     *
     * @param category 记忆分类（preference / user / project / person / event / other）。
     */
    fun saveMemory(content: String, category: String?): String

    /** 在记忆库里按关键词检索，返回多行文本（无命中返回明确的空结果说明）。 */
    fun searchMemory(query: String): String

    // ---------------------------------------------------------------
    // 文件（三个）—— 每个都**必须**由宿主侧过一遍权限档位
    // ---------------------------------------------------------------

    /**
     * 读一个文本文件。
     *
     * ★ 越权时**必须**返回以 `ERROR:` 开头、且说清"当前档位是什么、该怎么办"的说明，
     *   不能只回 `denied` —— 模型看到 `denied` 会原样重试。
     */
    fun readFile(path: String, maxChars: Int): String

    /**
     * 写一个文本文件（覆盖或新建）。
     *
     * @param append true = 追加到末尾，false = 覆盖。默认覆盖。
     */
    fun writeFile(path: String, content: String, append: Boolean): String

    /** 列目录。路径空 = 工作区根目录。 */
    fun listDir(path: String?): String

    /**
     * 联网搜索。
     *
     * ★ **免费优先、付费按需**（用户要求，参照 `dsh-free-search` 的引擎分级）：
     * 默认走不需要 key 的免费源；**当模型判断结果不佳时，可以主动要求改用付费服务**
     * —— 选择权交给模型，而不是由宿主写死一个顺序。
     *
     * @param usePaid true = 这次走付费搜索服务（用户配的专用服务，或模型自己的接入点）。
     *   默认 false（免费）。
     * @return 给人/模型读的文本；全失败时**必须说清试过哪些源**，
     *   而不是一句"搜索失败"—— 用户需要判断是网络问题还是源不可用。
     */
    fun webSearch(query: String, count: Int, usePaid: Boolean): String

    /**
     * 当前会话 id —— 历史检索要排除它。
     *
     * ★ 为什么由 host 提供而不是让模型传：模型并不知道自己的会话 id，
     *   让它传只会得到 null 或瞎编一个。这是**宿主比模型更清楚**的信息。
     */
    fun currentConversationIdHint(): String?

    /**
     * 跨会话历史检索 —— 回答「我在哪个对话干了什么」。
     *
     * ## ★ 为什么是工具而不是注入（用户定的）
     *
     * 用户原话：「现有的注入规则不变，当我提到时模型再去查且能查到」
     * ⇒ 历史是**按需拉取**的，不占常驻上下文预算。
     *
     * @param mode `search` = 关键词搜；`recent` = 按时间列最近的会话
     * @param days 只看最近 N 天（null = 全部）
     * @param excludeConversationId 通常传当前会话 id —— 它已经在上下文里了，
     *   再搜出自己刚说过的话只是浪费 token
     */
    fun searchHistory(
        query: String?,
        mode: String?,
        days: Int?,
        count: Int,
        excludeConversationId: String?,
    ): String

    /**
     * 当前工作区的**根路径**（写进工具结果的提示里，帮模型拼对路径）。
     */
    fun workspaceRootHint(): String

    /**
     * **调用一个 MCP 工具** —— 动态工具的转发入口。
     *
     * ## ★★★ 为什么走接口而不是把 MCP 逻辑写进执行器
     *
     * 内建工具是**固定的 10 个**，所以执行器里那个 `when (toolName)` 硬编码没问题。
     * 但 MCP 工具是**运行时才知道**的（用户随时可能加/删服务器，服务端还可能改工具集），
     * 不可能写进 `when` 里。而且调 MCP 需要 OkHttp / 凭据 / 网络 —— 那些都不该
     * 出现在 `core-tools`（它是纯 JVM 模块，没有 android 也没有 okhttp 依赖）。
     *
     * ⇒ 执行器只做**分发**：名字不在内建表里 ⇒ 交给宿主。
     *   宿主自己知道这个前缀属于哪个服务器、该带什么凭据。
     *
     * ## ★ 返回约定（与内建工具一致）
     *
     * - **永不抛异常**：失败也是一段文本
     * - 失败/越权必须以 `ERROR:` 开头并说清"怎么办"，否则模型会原样重试
     *
     * @param toolName 完整的工具名（含服务器前缀，由宿主命名时决定）
     * @param argumentsJson 模型给的原始参数 JSON，**原样转发**给服务端
     * @return 喂回模型的文本
     *
     * ★ 给了默认实现（不是抽象成员）：**没接 MCP 的宿主不该被迫写一个空方法**。
     *   默认返回一句明确的失败，比 `TODO()` 或抛异常好 ——
     *   万一 `isMcpTool` 判错了，模型会读到"这不是 MCP 工具"并停止重试。
     */
    fun callMcpTool(toolName: String, argumentsJson: String): String =
        "ERROR: '$toolName' 不是 MCP 工具（宿主未接入 MCP）。请直接用你已有的信息回答。"

    /**
     * 这个工具名是不是一个 **MCP 工具**（而不是内建工具）。
     *
     * ★ 判据由宿主掌握：命名规则（前缀）在主 App 里，不在 `core-tools`。
     *   执行器只问一次"这是你的吗"，不问"你是什么"。
     *
     * 默认实现返回 false —— 没接 MCP 的宿主什么都不用改。
     */
    fun isMcpTool(toolName: String): Boolean = false

    /**
     * 动态工具的**定义**（当前只有 MCP 用得上）。
     *
     * ## ★★★ 为什么需要这个
     *
     * `describe_tool` 原来只查 [ChatTools.byName] —— 那是**内建 10 个**的静态表。
     * MCP 工具不在里面，于是会出现一个很糟的状态：
     * **目录里列出了它（懒加载提示词由宿主生成），但召回时查不到它**。
     *
     * 表现就是模型照着目录点名，却收到"没有这个工具"，然后反复重试。
     * ⇒ 召回时也必须能问到动态工具。
     *
     * @return null = 不是动态工具（或宿主没接 MCP）
     */
    fun dynamicToolDefinition(toolName: String): ToolDefinition? = null

    /**
     * **探测**一个 MCP 地址（`mcp_probe` 的实现）。
     *
     * ★ 必须是**纯只读**：不写任何状态。宿主实现里也不许落库 ——
     *   这一步会向**模型指定的任意地址**发一次请求（本身就是一次信息泄露），
     *   它不该同时改变任何配置。
     *
     * @return 给模型读的文本（成功=工具清单；失败=以 ERROR: 开头且说清怎么办）
     */
    fun probeMcpServer(url: String): String =
        "ERROR: MCP 未接入。直接告诉用户当前版本不支持配置 MCP 服务器。"

    /**
     * **起草**添加一个 MCP 服务器（`mcp_save_draft` 的实现）。
     *
     * ## ★★★ 这里的返回值语义很特殊
     *
     * 它**不保存**任何东西 —— 宿主应该弹一张确认卡，然后返回一句
     * "已经给用户看了确认卡，等他点添加"。
     *
     * 为什么不在这里返回"添加成功"：那等于模型一调用就把配置改了，
     * 用户没有拒绝的时机。**保存必须由人触发**。
     */
    fun draftMcpServer(url: String): String =
        "ERROR: MCP 未接入。直接告诉用户当前版本不支持配置 MCP 服务器。"
}

/**
 * 工具执行器 —— 把模型的 [ToolCall] 变成喂回给它的文本。
 *
 * ## ★★★ 三条实现纪律
 *
 * 1. **永不抛异常**：任何失败都返回 [ToolResult.Failure]，由上层回填给模型。
 *    模型看到错误才知道该怎么改；抛异常则整条对话直接断掉。
 * 2. **结果必须截断**：网页正文可能几十万字，一次塞进上下文会爆掉——
 *    而且它出现在**消息末尾**，会让后面每一轮的缓存都变。
 * 3. **参数解析失败要报清楚缺哪个**，而不是笼统说"参数错误"——
 *    模型据此能自我修正，否则它会原样重试。
 */
class ChatToolExecutor(
    private val host: ToolHost,
    /** 单个工具结果回填的最大字符数。 */
    private val maxResultChars: Int = 8000,
) {

    private companion object {
        /**
         * 一次 `describe_tool` 最多解释几个工具。
         *
         * ★ 这不是产品限制，是**防失控**：模型偶尔会一口气点名十几个名字。
         *   取 8 的理由：官方两家默认召回量级在 5 上下（Anthropic 默认 5、可调），
         *   留一点余量够用；再多，这一个 tool 结果就会长到把上下文挤掉。
         */
        const val MAX_DESCRIBE_BATCH = 8
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 执行一次调用。
     *
     * @param enabled 当前用户启用的工具名集合 —— **未启用的工具一律拒绝**，
     *   即使模型硬要调。这是"用户在设置里关掉它"能真正生效的地方
     *   （模型可能从历史里见过旧工具名）。
     * @param confirm 需要确认的工具走这里。参数是工具名与原始参数 JSON，
     *   返回 true 才继续执行。**默认实现拒绝** —— 没人接这个回调时必须拒绝，
     *   不能因为"调用方忘了传"就把破坏性操作放行。
     * @param needsConfirm 由**调用方**决定这次调用要不要问用户。
     *
     *   ## ★★★ 为什么确认与否由外部决定，而不是看工具的静态标记
     *
     *   工具定义里的 `requiresConfirmation` 只表达"这个工具**可能**有破坏性"
     *   （`write_file` 是）。但**到底要不要打断用户**取决于**权限档位与目标位置**：
     *
     *   | 情况 | 要不要问 |
     *   |---|---|
     *   | 任何读取 | **永远不问** |
     *   | 工作区内写 | **不问** |
     *   | 工作区外写 | 问（`workspace-write` 档下其实已被权限层先拒） |
     *   | `full-access` 档 | **不问** |
     *
     *   把这段判断放在 `core-tools` 里就要把工作区概念搬进来；
     *   而它属于 App 的权限模型 ⇒ 由调用方算好再传。
     *   `core-tools` 只负责"你说要问，我就问；你不说，我按工具标记保守地问"。
     */
    fun execute(
        call: ToolCall,
        enabled: Set<String>,
        confirm: (toolName: String, argumentsJson: String) -> Boolean = { _, _ -> false },
        needsConfirm: (toolName: String, argumentsJson: String) -> Boolean = { name, _ ->
            // 默认退化成工具自己的静态标记：调用方没给判据时，
            // 声明了要确认的工具仍然要确认（保守方向）。
            ChatTools.byName(name)?.requiresConfirmation == true
        },
        /**
         * 本轮**懒加载但可召回**的工具。
         *
         * ★ 它们不在 `enabled` 的"常驻"语义里，但对 `describe_tool` 是可查的，
         *   且**召回之后就能直接调用**（见下面的放行判据）。
         *
         * ★ 与 `enabled` 的关系：`enabled` 是"用户允许用"，`lazy` 是"暂不常驻"。
         *   `lazy` 必须是 `enabled` 的**子集** —— 用户关掉的东西不该能被召回。
         */
        lazy: Set<String> = emptySet(),
    ): ToolResult {
        // ★★★ `describe_tool` 是**元工具**，不属于用户可配置的工具 --
        //   它由目录机制自己提供，所以在 `enabled` 检查之前单独处理。
        //
        //   为什么放最前面：它的参数校验与其它工具不同（它查的是目录，不是执行副作用），
        //   而且它**永远不该弹确认框**（看一眼 schema 不是危险操作）。
        if (call.toolName == ToolCatalog.DESCRIBE_TOOL_NAME) {
            return describeTool(call, lazy)
        }

        // ★★★ MCP 的**配置工具**也是元工具，同样在 `enabled` 检查之前处理。
        //
        //   为什么它们是元工具而不是普通工具：
        //   - `mcp_probe` 纯读（只看那个地址上有什么），没有副作用
        //   - `mcp_save_draft` 只**起草**（弹一个确认框），不落库
        //   两者都不是"调用某个服务器上的功能"，所以不属于用户可配置的工具集。
        //
        //   ★ 它们**必须**存在，否则「模型帮用户配置 MCP」这条链断在第一步：
        //     模型没有任何办法知道一个地址上有什么工具。
        if (call.toolName == McpTools.PROBE_NAME) {
            return mcpProbe(call)
        }
        if (call.toolName == McpTools.DRAFT_NAME) {
            return mcpDraft(call)
        }

        // ★★ 放行判据要看**并集**：常驻的 + 可召回的。
        //
        //   为什么不只查 `enabled`：懒加载工具本来就不在"常驻启用集合"里，
        //   若只用 `enabled` 判，模型召回成功后再调一次就会被拒 ——
        //   那整个机制就是坏的死循环（描述得出来、调不动）。
        if (call.toolName !in enabled && call.toolName !in lazy) {
            return ToolResult.Failure(
                call.callId,
                "工具 '${call.toolName}' 已被用户关闭。请直接用你已有的信息回答，" +
                    "不要重试，也不要假装调用过它。",
            )
        }

        val args = parseArgs(call.argumentsJson)
            ?: return ToolResult.Failure(
                call.callId,
                "参数不是合法的 JSON 对象：${call.argumentsJson.take(200)}",
            )

        // ★★★ `requiresConfirmation` 的**唯一消费点**。
        //
        //   在此之前这个字段定义了却没有任何地方读它 —— 等于给了一张空头支票。
        //   现在的语义：要不要问由 `needsConfirm` 决定（档位 + 是否在工作区内），
        //   **用户不点头就绝不执行**。
        //
        //   为什么检查在参数解析之后：确认框要显示"写哪个文件"，那需要 path。
        //   参数都解析不出来的调用根本走不到确认这一步。
        val def = ChatTools.byName(call.toolName)
        if (needsConfirm(call.toolName, call.argumentsJson)) {
            if (!confirm(call.toolName, call.argumentsJson)) {
                val name = def?.displayName ?: call.toolName
                return ToolResult.Failure(
                    call.callId,
                    "用户没有允许这次「$name」操作。请**不要重试**，" +
                        "也不要假设文件已经写好了。可以用文字把内容直接回答给用户，" +
                        "或者先问清楚 ta 希望怎么处理。",
                )
            }
        }

        val started = System.currentTimeMillis()
        return try {
            val output = when (call.toolName) {
                "get_time" -> host.nowText(args.str("timezone"))
                "get_location" -> host.locationText(precise = args.str("accuracy") == "precise")
                "web_fetch" -> {
                    val url = args.str("url")
                    if (url.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 url")
                    }
                    host.fetchUrl(url, clampMaxChars(args.int("max_chars")))
                }
                "save_memory" -> {
                    val content = args.str("content")
                    if (content.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 content")
                    }
                    host.saveMemory(content, args.str("category"))
                }
                "search_memory" -> {
                    val q = args.str("query")
                    if (q.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 query")
                    }
                    host.searchMemory(q)
                }
                // -----------------------------------------------------
                // 文件三件套。★ 参数校验放在**调用宿主之前** ——
                // 缺必填参数是模型自己的错，报回去让它改，不要浪费一次文件系统访问。
                // -----------------------------------------------------
                "read_file" -> {
                    val p = args.str("path")
                    if (p.isNullOrBlank()) {
                        return ToolResult.Failure(
                            call.callId,
                            "缺少必填参数 path。当前工作区根目录：${host.workspaceRootHint()}",
                        )
                    }
                    host.readFile(p.trim(), clampReadChars(args.int("max_chars")))
                }
                "write_file" -> {
                    val p = args.str("path")
                    if (p.isNullOrBlank()) {
                        return ToolResult.Failure(
                            call.callId,
                            "缺少必填参数 path。当前工作区根目录：${host.workspaceRootHint()}",
                        )
                    }
                    // ★ content 允许是空字符串（用户可能就是想让模型建一个空文件），
                    //   所以用 null 判断而不是 isBlank。
                    val content = args.str("content")
                        ?: return ToolResult.Failure(call.callId, "缺少必填参数 content")
                    host.writeFile(p.trim(), content, append = args.bool("append") == true)
                }
                "list_dir" -> host.listDir(args.str("path")?.trim()?.takeIf { it.isNotEmpty() })
                "search_history" -> {
                    val mode = args.str("mode")?.trim()?.lowercase()
                    val q = args.str("query")?.trim()?.takeIf { it.isNotEmpty() }
                    // ★ mode=recent 时 query 可有可无；mode=search 时没 query 就直接报错，
                    //   不要静默变成"列出全部" —— 那会给模型一份没有针对性的长列表。
                    if (mode != "recent" && q == null) {
                        return ToolResult.Failure(
                            call.callId,
                            "缺少 query。若想看最近的对话，请传 mode=recent。",
                        )
                    }
                    host.searchHistory(
                        query = q,
                        mode = mode,
                        days = args.int("days")?.takeIf { it > 0 },
                        count = clampHistoryCount(args.int("count")),
                        excludeConversationId = host.currentConversationIdHint(),
                    )
                }
                "web_search" -> {
                    val q = args.str("query")
                    if (q.isNullOrBlank()) {
                        return ToolResult.Failure(call.callId, "缺少必填参数 query")
                    }
                    host.webSearch(
                        query = q.trim(),
                        count = clampSearchCount(args.int("count")),
                        usePaid = args.bool("use_paid") == true,
                    )
                }
                else -> {
                    // ★★★ MCP 工具走这条路 —— 动态工具的唯一入口。
                    //
                    //   为什么不在上面 `when` 里加分支：MCP 的工具集是**运行时**才知道的
                    //   （用户随时加/删服务器），而 `when` 是编译期固定的。
                    //   判据交给宿主：它知道命名规则（前缀），执行器只负责转发。
                    if (host.isMcpTool(call.toolName)) {
                        host.callMcpTool(call.toolName, call.argumentsJson)
                    } else {
                        return ToolResult.Failure(
                            call.callId,
                            "未知工具 '${call.toolName}'。可用：${enabled.joinToString(", ")}",
                        )
                    }
                }
            }

            // ★ 以 ERROR: 开头的输出是宿主侧的**受控失败**（网络失败、没权限等），
            //   不是异常。仍然算"成功执行"，因为模型需要读到那句说明才知道怎么办。
            val truncated = output.length > maxResultChars
            ToolResult.Success(
                callId = call.callId,
                output = if (truncated) {
                    output.take(maxResultChars) +
                        "\n\n…（结果过长已截断，原长 ${output.length} 字符）"
                } else {
                    output
                },
                durationMs = System.currentTimeMillis() - started,
                truncated = truncated,
            )
        } catch (e: Throwable) {
            // ★ 兜底：任何未预料的异常都变成失败结果，而不是往上抛。
            ToolResult.Failure(
                call.callId,
                "工具 '${call.toolName}' 执行失败：" + (e.message ?: e::class.simpleName),
                retryable = false,
            )
        }
    }

    /** `max_chars` 钳在 1000..20000：太小读不到内容，太大撑爆上下文。 */
    private fun clampMaxChars(raw: Int?): Int = (raw ?: 8000).coerceIn(1000, 20000)

    /**
     * 读文件的 `max_chars` 钳在 200..20000。
     *
     * ★ 下限比 [clampMaxChars] 更低（200）：读文件常常只是想看开头几行
     *   （比如确认某个文件长什么样），强行 1000 起步会让模型白读一大段。
     */
    private fun clampReadChars(raw: Int?): Int = (raw ?: 8000).coerceIn(200, 20000)

    /**
     * 搜索结果的条数，钳在 1..15。
     *
     * ★ 上限比 API 常见的 50 低很多：每条结果都有标题+摘要，
     *   15 条已经能吃掉两千多 token，而这个结果会**整段进上下文**，
     *   并且排在消息末尾 —— 太大等于每轮都在为一次搜索付钱。
     */
    private fun clampSearchCount(raw: Int?): Int = (raw ?: 6).coerceIn(1, 15)

    /**
     * 历史检索的条数，钳在 1..20。
     *
     * ★ 比搜索略高（20 vs 15）：历史命中通常是短片段（一句话），而搜索结果带摘要。
     *   但也不能无限 —— 一次吐 100 条历史会把上下文填满，而那通常是模型在乱试。
     */
    private fun clampHistoryCount(raw: Int?): Int = (raw ?: 8).coerceIn(1, 20)

    private fun parseArgs(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /**
     * `describe_tool` 的实现 —— **懒加载的闭环就在这个方法里**。
     *
     * ## ★★★ 为什么必须返回**完整 schema**而不是"我记住了，你可以调了"
     *
     * 因为模型**没有跨轮记忆**。它这一轮之所以不知道那个工具的参数，
     * 是因为我们没把它发在 `tools` 里。所以这一次返回值**就是它唯一的信息来源** ——
     * 必须把参数名、类型、是否必填、枚举值全部写清，否则它下一轮调用只能靠猜。
     *
     * ## ★★★ 为什么支持一次要多个（`names`）
     *
     * 一次只返回一个工具，等于模型要连查 N 个就得 N 次往返。官方两家都是多返回的
     * （Anthropic 默认 5 个、OpenAI 可整批加载 namespace）。移动网络下每次往返
     * 不只是几百毫秒延迟，还是**一次完整的模型调用**。
     *
     * 容错策略：**部分成功比全失败有用**。模型一次要三个、其中一个名字打错了，
     * 就把它要到的两个 + 一个"没找到 X"一起回给它 —— 它据此能自己改，
     * 而不是整个调用作废重来。
     */
    private fun describeTool(call: ToolCall, lazy: Set<String>): ToolResult {
        val started = System.currentTimeMillis()
        val args = parseArgs(call.argumentsJson)
            ?: return ToolResult.Failure(
                call.callId,
                "参数不是合法的 JSON 对象：${call.argumentsJson.take(200)}",
            )

        // ★ 合并两条来源：`names`（首选，数组）与 `name`（单值，兼容）。
        //   去重但**保持顺序** —— 输出顺序稳定对缓存和模型阅读都有好处。
        val explicit = buildList {
            args.strArray("names")?.forEach { add(it) }
            args.str("name")?.let { add(it) }
        }.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // ★★★ 名字想不起来时走检索（`query`）。
        //
        //   为什么必须有这条路：目录在 `describe_tool` 的 description 里是**全量罗列**，
        //   工具数一旦过 30–50（Anthropic 官方的准确率拐点），罗列就从"有用"变"噪音"。
        //   而 MCP 接进来后工具数轻易破百 ⇒ 精确点名等于考验模型的记忆力。
        //   ⇒ 给一条"用你自己的话描述要做什么"的检索路径。
        val viaQuery = if (explicit.isEmpty()) {
            val q = args.str("query")?.trim().orEmpty()
            if (q.isEmpty()) emptyList() else retrieveByQuery(q, lazy)
        } else {
            emptyList()
        }

        val requested = if (explicit.isNotEmpty()) explicit else viaQuery

        if (requested.isEmpty()) {
            return ToolResult.Failure(
                call.callId,
                "缺少 names 参数。要查哪个工具？请从目录里挑确切的名字" +
                    "（一次可以给多个），或者用 query 描述你想做什么。",
            )
        }

        // ★ 上限只防"模型一口气点名 200 个"这种失控 —— 不是产品限制。
        val wanted = requested.take(MAX_DESCRIBE_BATCH)

        val sb = StringBuilder()
        val missing = mutableListOf<String>()

        for (name in wanted) {
            // ★★★ 先查动态（MCP），再查内建。
            //
            //   顺序不能反：将来 MCP 工具可能正好与内建同名（虽然我们会加前缀避免），
            //   而更重要的是 —— **内建的 10 个永远在静态表里**，
            //   先查它在功能上等价，但语义上"这个名字是谁的"应该由**可能更长的那张表**
            //   先表态（动态表可能是上百个工具）。
            val def = host.dynamicToolDefinition(name) ?: ChatTools.byName(name)
            // ★★ 只能查**可召回**的工具。
            //   为什么不给查任意工具：那等于把"用户关掉的工具"也暴露出来 ——
            //   模型会反复尝试调用它，最后只能收到"已被用户关闭"。
            if (def == null || name !in lazy) {
                missing += name
                continue
            }
            if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
            appendToolDefinition(sb, def)
        }

        if (sb.isEmpty()) {
            // ★ 一个都没命中 ⇒ 明确失败，并把可召回清单再给一遍（模型据此能改对）。
            return ToolResult.Failure(
                call.callId,
                "没有可召回的工具叫 " + wanted.joinToString(", ") { "'$it'" } +
                    "。可召回的有：" + lazy.sorted().joinToString(", "),
            )
        }

        sb.append("\n\n")
        if (missing.isNotEmpty()) {
            sb.append("NOT FOUND: ").append(missing.joinToString(", "))
            sb.append(" — those names are not in the recallable list. Available: ")
            sb.append(lazy.sorted().joinToString(", "))
            sb.append('\n')
        }
        sb.append("You can now call ")
        sb.append(wanted.filter { it in lazy }.joinToString(", ") { "'$it'" })
        sb.append(" directly, using the parameters above.")

        return ToolResult.Success(
            callId = call.callId,
            output = sb.toString(),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * `mcp_probe` 的实现。
     *
     * ★ 只做两件事：拿 url、转给宿主。**不在这里做任何 URL 校验** ——
     *   安全判据放在宿主的执行路径上（`McpUrlGuard`），那是唯一真相；
     *   在这里再写一份只会出现"两处不一致"的老问题。
     */
    private fun mcpProbe(call: ToolCall): ToolResult {
        val started = System.currentTimeMillis()
        val args = parseArgs(call.argumentsJson)
            ?: return ToolResult.Failure(
                call.callId,
                "参数不是合法的 JSON 对象：${call.argumentsJson.take(200)}",
            )
        val url = args.str("url")?.trim()
        if (url.isNullOrBlank()) {
            return ToolResult.Failure(
                call.callId,
                "缺少必填参数 url。请让用户提供完整的 MCP 地址（通常以 /mcp 或 /sse 结尾）。",
            )
        }
        return ToolResult.Success(
            callId = call.callId,
            output = host.probeMcpServer(url),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * `mcp_save_draft` 的实现。
     *
     * ★ 返回值是"已经给用户看确认卡了"，**不是**"已保存" ——
     *   保存必须由人点。
     */
    private fun mcpDraft(call: ToolCall): ToolResult {
        val started = System.currentTimeMillis()
        val args = parseArgs(call.argumentsJson)
            ?: return ToolResult.Failure(
                call.callId,
                "参数不是合法的 JSON 对象：${call.argumentsJson.take(200)}",
            )
        val url = args.str("url")?.trim()
        if (url.isNullOrBlank()) {
            return ToolResult.Failure(
                call.callId,
                "缺少必填参数 url。要添加哪个地址？请先 mcp_probe 它。",
            )
        }
        return ToolResult.Success(
            callId = call.callId,
            output = host.draftMcpServer(url),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 按自然语言 `query` 检索可召回工具（BM25）。
     *
     * ## ★ 为什么用 BM25 而不是问模型"你觉得像哪个"
     *
     * 我们**就是**在处理模型发来的这一次调用，不能再套一次模型调用 ——
     * 那会让"查一个工具"变成两次模型往返，比精确点名还贵。
     *
     * 依据见 [ToolSearch] 的注释：ToolRet（ACL 2025）实测 BM25（36.46）
     * 不输 ColBERT（23.79）、优于各种小 embedding（25–31）。
     *
     * ## ★ 检索范围只包含 `lazy`
     *
     * 与精确点名同一条底线：**用户关掉的工具不能靠检索"捞"出来**。
     */
    private fun retrieveByQuery(query: String, lazy: Set<String>): List<String> {
        if (lazy.isEmpty()) return emptyList()
        val docs = lazy.mapNotNull { name ->
            // ★ 与精确召回同一条路：先问宿主（MCP），再回落到内建表。
            //   否则检索永远搜不到 MCP 工具 —— 而那正是工具数破百之后最需要检索的部分。
            val def = host.dynamicToolDefinition(name) ?: ChatTools.byName(name)
            def?.let {
                // description 也参与检索，但要**截断** —— 每条都几十上百词，
                // 全放进去会让长描述的工具靠"说得多"取胜，而不是靠"说得准"。
                ToolSearch.Doc(
                    name = it.name,
                    oneLiner = it.oneLiner,
                    description = it.spec.description.take(300),
                )
            }
        }
        if (docs.isEmpty()) return emptyList()
        val hits = ToolSearch.search(docs, query, limit = MAX_DESCRIBE_BATCH)
        return hits.map { it.name }
    }

    /** 渲染**一个**工具的完整定义（名字 / 用途 / 描述 / 参数表 / 示例）。 */    private fun appendToolDefinition(sb: StringBuilder, def: ToolDefinition) {
        sb.append("Tool: ").append(def.spec.name).append('\n')
        sb.append("What it does: ").append(def.oneLiner).append('\n')
        sb.append('\n')
        // ★ 走 fullDescription() —— 与常驻工具序列化**同一个入口**，
        //   否则懒加载工具永远拿不到使用示例（那就是 L26 的同一个形状）。
        sb.append("Full description:\n").append(def.spec.fullDescription()).append('\n')
        sb.append('\n')
        sb.append("Parameters:\n")

        // ★★★ MCP 工具：参数是服务端给的完整 JSON Schema，**原样贴出来**。
        //
        //   为什么不用下面的结构化渲染：MCP schema 里可能有 oneOf / $ref / pattern，
        //   而我们那个字段遍历只认 properties/required/enum/items ——
        //   它会**静默漏掉**其余约束，模型据此拼参数必然违规。
        //   贴原始 schema 比"翻译得像"更安全：模型本来就看懂 JSON Schema。
        val raw = def.spec.rawParametersJson
        if (!raw.isNullOrBlank()) {
            sb.append(raw).append('\n')
            sb.append(
                "\n(This schema comes from the MCP server verbatim — follow it exactly, " +
                    "including any oneOf / pattern / nested objects.)\n",
            )
            return
        }

        val props = def.spec.parameters.properties
        if (props.isEmpty()) {
            sb.append("  (none)\n")
        } else {
            for ((pname, p) in props) {
                val required = pname in def.spec.parameters.required
                sb.append("  - ").append(pname)
                sb.append(" (").append(p.type.jsonName)
                sb.append(if (required) ", required" else ", optional").append("): ")
                sb.append(p.description)
                if (p.enum != null) {
                    sb.append(" Allowed values: ").append(p.enum.joinToString(" | "))
                }
                if (p.items != null) {
                    sb.append(" (array of ").append(p.items.jsonName).append(")")
                }
                sb.append('\n')
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 读字符串数组参数（`names`）。
     *
     * ★ 三种写法都要认，因为模型对"数组"的写法很不统一：
     *   ① 正常数组 `["a","b"]`
     *   ② 单值被写成字符串 `"a"`（只查一个时模型常常这样省）
     *   ③ 逗号分隔 `"a,b"`（少数模型的习惯）
     * 认不出来就等于"没传"，而它会导致一次白跑的往返。
     */
    private fun JsonObject.strArray(key: String): List<String>? {
        val el = this[key] ?: return null
        (el as? JsonArray)?.let { arr ->
            return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }
        val single = (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return single.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    /**
     * 读布尔参数。
     *
     * ★ 不能只认 `isString`：模型经常把布尔写成字符串 `"true"`，
     *   也经常写成 JSON 布尔 `true`。两种都要认，否则 `append` 会静默失效 ——
     *   而"静默失效"在这里的表现是**覆盖了用户的文件**，代价极高。
     */
    private fun JsonObject.bool(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        return when {
            !p.isString -> p.content.trim().equals("true", ignoreCase = true)
            else -> p.content.trim().toBooleanStrictOrNull()
        }
    }
}
