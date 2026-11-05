package com.chatmobile.tools

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import chatmobile.memory.MemoryBank
import chatmobile.memory.MemoryKey
// ★ 注意包名：本文件是 `com.chatmobile.tools`，而工具定义在 `chatmobile.tools`（**没有 com. 前缀**，
//   与 core-memory 的 `chatmobile.memory` 一致）。两者只差一个 `com.`，
//   少了这行 import 会报 Unresolved reference 'ToolHost' —— 本项目已经在 core-memory 上踩过同一个坑。
import chatmobile.tools.ToolHost
import com.chatmobile.prompt.RuntimeFactsProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * [ToolHost] 的 Android 实现 —— 把平台能力接到 `core-tools` 的工具执行器上。
 *
 * ## ★★★ 贯穿全文件的一条纪律：**如实报告，不许编**
 *
 * 每个方法在拿不到东西时都必须**明说拿不到**，而不是返回一个看似合理的结果：
 * - 没有位置权限 ⇒ "权限未授予"，而不是用上一次的坐标凑一个
 * - 网页抓不到 ⇒ 以 `ERROR:` 开头的说明，而不是返回空串
 * - 记忆库没开 ⇒ "记忆功能已关闭"，而不是假装写进去了
 *
 * 原因很直接：工具结果是喂回**模型**的。一个编造的结果会让模型理直气壮地
 * 基于假数据回答用户 —— 那比报错难查得多（用户在真机上无法分辨）。
 *
 * ## ★ 为什么每个方法都不抛异常
 *
 * 工具执行的失败是**正常结果**，要作为数据喂回模型让 ta 自己收场。
 * 抛异常会直接断掉整条对话，用户看到的是"请求失败"，
 * 而不是"这个网站打不开，我可以换个方式帮你"。
 */
/**
 * 一个搜索服务接入点。
 *
 * ## ★★★ 为什么搜索要和"模型接入点"分开建模
 *
 * 用户要的是三级：
 * 1. **默认复用模型现在的 key** —— 已经付过钱的东西别让他再配一遍；
 * 2. 也可以**单独配一个**给所有模型共用 —— 比如模型用一个便宜的、搜索用官方 Search；
 * 3. 都没有时，免费源保底。
 *
 * 这三级都归约成同一个类型（地址 + key），差别只在**从哪来**。
 */
data class SearchEndpoint(
    /** 基础地址，如 `https://api.deepseek.com`（末尾斜杠会被去掉）。 */
    val base: String,
    val apiKey: String,
) {
    /** 给用户看的来源标签（只显示主机名，不回显 key）。 */
    fun hostLabel(): String = runCatching {
        java.net.URI(base).host ?: base
    }.getOrDefault(base)
}

class AndroidToolHost(
    private val context: Context,
    private val memoryBank: MemoryBank,
    /**
     * 记忆是否启用。关闭时 `save_memory` / `search_memory` **如实拒绝**。
     * 用 lambda 而不是 Boolean：配置随时可能被用户在设置里改，不能缓存死。
     */
    private val memoryEnabled: () -> Boolean,
    /**
     * 当前会话工作区的**绝对根路径**（模型能操作的那个目录）。`null` = 这轮没有工作区。
     *
     * ★ 为什么是 lambda：用户在弹窗里随时能换工作区，并且要求"下一轮生效"。
     *   缓存成 String 就会用到上一轮的值 —— 那种 bug 用户只会描述成"它读错目录了"。
     * ★ 共享工作区给的是**暂存区**路径，不是 SAF treeUri（模型的活文件在私有目录）。
     */
    private val workspaceRoot: () -> String? = { null },
    /**
     * 当前会话的权限档位。
     *
     * ★ 这个字段的用途不是"告诉模型能干什么"，而是**执行器这一侧的强制层**。
     *   提示词只是请求，模型完全可能不照做；真正的边界只能落在这里。
     */
    private val workspacePermission: () -> com.chatmobile.workspace.WorkspacePermission =
        { com.chatmobile.workspace.WorkspacePermission.DEFAULT },
    /**
     * **当前模型自己的**接入点与 key —— 用来「复用模型的 key 去搜索」。
     *
     * ★ 返回 null 表示当前模型没有可用接入点（比如本地 Ollama），
     *   那就跳到下一级（专用搜索服务 → 免费源）。
     */
    private val modelSearchEndpoint: () -> SearchEndpoint? = { null },
    /**
     * 用户在设置里**单独配置**的搜索服务 —— 所有模型共用这一套。
     *
     * ★ 返回 null = 没配 ⇒ 跳过这一级。
     */
    private val customSearchEndpoint: () -> SearchEndpoint? = { null },
    /**
     * 当前会话 id —— 历史检索要把它排除掉。
     *
     * ★ 用户在**当前**对话里问"我上次干了什么"，模型不该搜出他眼前这段对话。
     */
    private val conversationIdHint: () -> String? = { null },
    /**
     * 跨会话历史检索的数据源。
     *
     * ★ 为什么用回调而不是直接传 Dao：`AndroidToolHost` 已经依赖 Context，
     *   再依赖 Room 的 Dao 会让它的构造签名继续膨胀；而且检索要同时碰
     *   `message` 与 `conversation` 两张表，把"查什么"留在 ChatHost
     *   更容易保持一处真相。
     *
     * 参数：(query, mode, days, count, excludeConversationId) -> 给模型读的文本
     */
    private val historySearch: (
        query: String?,
        mode: String?,
        days: Int?,
        count: Int,
        excludeConversationId: String?,
    ) -> String = { _, _, _, _, _ -> "ERROR: 历史检索未接入。" },
    /**
     * **MCP 转发**（P2）。
     *
     * ## ★★★ 为什么这三个都是回调而不是直接依赖 `McpToolRegistry`
     *
     * `McpToolRegistry` 需要 OkHttp + 配置，而 `AndroidToolHost` 这一层已经很长了。
     * 更重要的是：**当轮有没有 MCP 工具、是哪几个，只有 `ChatHost` 知道**
     * （它刚构建完工具表）。把它焊进 Host 会让两边各存一份状态，然后不同步。
     *
     * @param isMcp 判据：这个工具名是不是 MCP 的
     * @param call 执行转发：完整工具名 + 原始参数 JSON -> 给模型读的文本
     * @param definition 动态定义回查（`describe_tool` 召回用）
     */
    private val mcpIsTool: (String) -> Boolean = { false },
    private val mcpCall: (String, String) -> String = { n, _ ->
        "ERROR: '$n' 不是 MCP 工具（未接入）。"
    },
    private val mcpDefinition: (String) -> chatmobile.tools.ToolDefinition? = { null },
    /**
     * MCP **配置**两个元工具的实现（`mcp_probe` / `mcp_save_draft`）。
     *
     * ★ 与上面三个（转发已配好的 MCP 工具）分开：那两个是"配置"，
     *   这三个是"使用"。混在一起会让参数表变得难以核对。
     */
    private val mcpProbeServer: (String) -> String = { _ ->
        "ERROR: MCP 未接入。"
    },
    private val mcpDraftServer: (String) -> String = { _ ->
        "ERROR: MCP 未接入。"
    },
) : ToolHost {

    private val tag = "ChatToolHost"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 当前版本的 UA 片段（日志与请求头共用）。 */
    private val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 搜索专用 client —— **超时必须比通用 client 短得多**。
     *
     * ## ★★★ 为什么必须分开
     *
     * 搜索是**多级串行**的：一个源超时了才轮到下一个。用通用的 10+20 秒，
     * 最坏情况四级的连接超时叠起来就是 **40 秒**（还不算读超时），
     * 而模型那边早就在喊"工具超时用不了"了。
     *
     * 而搜索这个场景对延迟极其敏感：用户问一句、要等结果。
     * 5 秒还连不上，那条通道基本就是不通（国内对 DDG / GitHub 的封锁表现为
     * TCP 直接挂起，不是慢慢变慢）。
     *
     * ⇒ 连接 4 秒、读 8 秒。四级最坏 ≈ 48 秒但实际很少全超时，
     *   因为 AnySearch 排第一且实测很快。
     */
    private val searchHttp = http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)")

    // ---------------------------------------------------------------
    // 时间
    // ---------------------------------------------------------------

    override fun nowText(timezone: String?): String {
        val zone = resolveZone(timezone)
            ?: return "ERROR: 无法识别的时区名 '$timezone'。请用 IANA 格式，例如 Asia/Shanghai。" +
                "本次可用设备时区结果如下。\n" + nowText(null)

        val now = ZonedDateTime.now(zone)
        val offset = formatOffset(now)
        return buildString {
            append("当前本地时间：").append(now.format(timeFormat)).append('\n')
            append("时区：").append(zone.id).append("（").append(offset).append("）\n")
            append("Unix 毫秒：").append(Instant.now().toEpochMilli())
        }
    }

    /** 空 / 非法时区 ⇒ 设备时区；**非法名要能区分出来**，所以返回 null 由调用方处理。 */
    private fun resolveZone(name: String?): ZoneId? {
        if (name.isNullOrBlank()) {
            return runCatching { ZoneId.systemDefault() }.getOrDefault(ZoneId.of("UTC"))
        }
        return runCatching { ZoneId.of(name.trim()) }.getOrNull()
    }

    private fun formatOffset(at: ZonedDateTime): String {
        val total = at.offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return "UTC$sign" + h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }

    // ---------------------------------------------------------------
    // 位置
    // ---------------------------------------------------------------

    override fun locationText(precise: Boolean): String {
        val permission = if (precise) {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        }

        val coarse = PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (!coarse) {
            return "ERROR: 位置权限未授予，无法获取位置。请直接告诉用户你没有这个权限，" +
                "不要猜测用户在哪。"
        }
        if (precise) {
            val fine = PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            )
            if (!fine) {
                // ★ 退化成粗定位并**明说**退化了 —— 用户要的是"精确"，
                //   给他一个粗的结果却不说，他可能据此做出错误判断。
                return "注意：精确位置权限未授予，以下为城市级结果。\n" +
                    (lastKnownLocationText() ?: "ERROR: 当前没有可用的定位结果。")
            }
        }
        return lastKnownLocationText()
            ?: "ERROR: 当前没有可用的定位结果（定位服务可能已关闭，或设备尚未定位成功）。" +
                "请告诉用户你拿不到位置，不要猜。"
    }

    /**
     * 取最后一次已知位置。
     *
     * ★ 用 `getLastKnownLocation` 而不是请求实时更新：聊天一问不需要新鲜定位，
     *   而唤醒 GNSS 要耗电且可能几秒无结果。取上一次的就够了，且**不阻塞**。
     * ★ 在多个 provider 里取 `time` 最新的那个 —— 五分钟前的网络定位
     *   比两小时前的 GPS 有用得多。
     */
    private fun lastKnownLocationText(): String? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        val loc = best ?: return null

        val lat = String.format(Locale.US, "%.4f°%s", kotlin.math.abs(loc.latitude),
            if (loc.latitude >= 0) "N" else "S")
        val lon = String.format(Locale.US, "%.4f°%s", kotlin.math.abs(loc.longitude),
            if (loc.longitude >= 0) "E" else "W")
        val acc = if (loc.hasAccuracy()) "，精度约 ±${loc.accuracy.toInt()} 米" else ""
        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
        return "大致位置：$lat, $lon$acc（来源 ${loc.provider}，${ageSec} 秒前定位）"
    }

    // ---------------------------------------------------------------
    // 网页
    // ---------------------------------------------------------------

    override fun fetchUrl(url: String, maxChars: Int): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "ERROR: 只支持 http/https 链接，收到的是 '$trimmed'。"
        }
        return runCatching {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) ChatMobile/1.0")
                .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.8")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return "ERROR: 抓取失败，HTTP ${resp.code}。请告诉用户这个链接打不开。"
                }
                val body = resp.body.string()
                val text = htmlToText(body)
                if (text.isBlank()) {
                    return "ERROR: 该页面没有可读的正文（可能全由脚本渲染）。"
                }
                val clipped = if (text.length > maxChars) text.take(maxChars) else text
                buildString {
                    append("来源：").append(trimmed).append('\n')
                    append("正文长度：").append(text.length).append(" 字符")
                    if (text.length > maxChars) append("（以下为前 ").append(maxChars).append(" 字符）")
                    append("\n\n").append(clipped)
                }
            }
        }.getOrElse { e ->
            Log.w(tag, "web_fetch 失败", e)
            "ERROR: 抓取失败：" + (e.message ?: e::class.simpleName) +
                "。请告诉用户这个链接打不开。"
        }
    }

    /**
     * 把 HTML 变成可读文本 —— **故意简单**。
     *
     * ★ 不引 Jsoup：那是一个只为这个功能服务的依赖（约 400KB），
     *   而我们只需要"去标签 + 压空白"。真正复杂的页面（全 JS 渲染）
     *   本来也抓不到正文，引了库也救不回来。
     * ★ 先剥 `<script>` / `<style>` 整块 —— 只去标签会把 JS 源码当成正文吐出来。
     * ★ 实体解码只做最常见的五个：`&amp; &lt; &gt; &quot; &#39;`。
     *   全量 2000+ HTML 实体表对聊天场景没有价值。
     */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        // 块级标签换行，行内标签只加空格 —— 否则整页会挤成一行。
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6])\\s*/?>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace("&quot;", "\"")
        s = s.replace("&#39;", "'")
        // 压缩空白：连续空格并一个，连续空行并一个。
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    // ---------------------------------------------------------------
    // 记忆
    // ---------------------------------------------------------------

    override fun saveMemory(content: String, category: String?): String {
        if (!memoryEnabled()) {
            return "ERROR: 记忆功能已被用户关闭，没有保存。请告诉用户去设置里打开记忆，" +
                "不要说你已经记住了。"
        }
        return runCatching {
            val key = MemoryKey.normalize(category)
            // ★ 用 `save()` 而不是 `upsert()`：后者是 private 的统一入口。
            //   模型主动要求记的东西属于「用户明确要记住」⇒ 进 SAVED 层，
            //   而不是像抽取那样先进 RECENT 等命中提升。
            val entry = memoryBank.save(
                content = content.trim(),
                key = key,
            )
            "已保存到记忆库：" + entry.content +
                (key?.let { "（分类：$it）" } ?: "")
        }.getOrElse { e ->
            Log.w(tag, "save_memory 失败", e)
            "ERROR: 保存失败：" + (e.message ?: e::class.simpleName)
        }
    }

    override fun searchMemory(query: String): String {
        if (!memoryEnabled()) {
            return "ERROR: 记忆功能已被用户关闭，无法检索。"
        }
        return runCatching {
            val q = query.trim()
            // ★★★ 走 MemoryBank 的检索器，**不再做子串匹配**。
            //   旧写法 `content.contains(q)` 是"机制都在、连接不存在"的典型：
            //   向量模型装好、探针也通了，但没有任何一条路径去问它，
            //   结果是同义查询（"远程调用" vs "通过客户端调用 API"）永远 0 条。
            val hits = memoryBank.search(q, limit = 20)
            if (hits.isEmpty()) {
                return "没有找到与「$q」相关的记忆条目。" +
                    "（检索后端：${memoryBank.retrieverId}；如果这与预期不符，" +
                    "说明当前后端没有召回能力，可以在设置 → 记忆与提示词 → 向量模型里换一个）"
            }
            buildString {
                append("找到 ").append(hits.size).append(" 条（后端：")
                append(memoryBank.retrieverId).append("）：\n")
                hits.forEach { m ->
                    append("- ")
                    m.key?.let { append("[").append(it).append("] ") }
                    append(m.content)
                    append('\n')
                }
            }.trimEnd()
        }.getOrElse { e ->
            Log.w(tag, "search_memory 失败", e)
            "ERROR: 检索失败：" + (e.message ?: e::class.simpleName)
        }
    }

    // ---------------------------------------------------------------
    // 文件三件套
    // ---------------------------------------------------------------

    /**
     * 把模型给的路径解析成绝对路径。
     *
     * ★ 相对路径一律相对**工作区根**解析 —— 模型尤其擅长写 `notes.md` 这种裸名字。
     *   若把它按"进程当前目录"解析，会落到 `/` 或 App 数据根之外，必然失败，
     *   而失败信息又指向一个模型从没提过的路径 ⇒ 它只会反复重试。
     *
     * ★ 绝对路径**原样返回**，不做"偷偷夹到工作区里"的修正 ——
     *   越权必须由 `PermissionGuard` 明确拒绝并说清原因，
     *   否则模型永远学不会"这个目录我碰不了"。
     */
    private fun resolvePath(raw: String, root: String?): String {
        val p = raw.trim()
        if (p.startsWith("/")) return p
        if (p.isEmpty()) return root ?: "/"
        return if (root.isNullOrBlank()) p else "$root/$p"
    }

    /**
     * 统一的越权/无工作区拒绝 —— 三条信息缺一不可：
     * ① 现在不行 ② 为什么 ③ 该怎么办（或让用户怎么做）。
     */
    private fun denyNoWorkspace(): String =
        "ERROR: 本轮没有工作区，无法进行文件操作。" +
            "请让用户在输入框上方的「工作区」里选一个目录，或者直接把内容写在回答里。" +
            "不要猜测任何路径。"

    /** 跑一次权限判定；通过返回 null，不通过返回要喂回模型的那段话。 */
    private fun guard(
        op: com.chatmobile.workspace.FileOperation,
        absolutePath: String,
        root: String?,
    ): String? {
        val perm = workspacePermission()
        if (root.isNullOrBlank()) {
            // 没有工作区时，只有"完全权限"档仍可操作 —— 但它此时连根都没有，
            // 语义上等同于没有授权，所以一律拒绝。
            return denyNoWorkspace()
        }
        return when (
            val v = com.chatmobile.workspace.PermissionGuard.check(
                operation = op,
                targetPath = absolutePath,
                workspaceRoot = root,
                permission = perm,
            )
        ) {
            is com.chatmobile.workspace.PermissionVerdict.Allowed -> null
            is com.chatmobile.workspace.PermissionVerdict.Denied -> "ERROR: ${v.reason}"
        }
    }

    override fun workspaceRootHint(): String = workspaceRoot() ?: "（本轮没有工作区）"

    // ------------------------------------------------------------------
    // MCP 转发（P2）—— 三个都是一行转发，逻辑都在 ChatHost / McpToolRegistry 里。
    //   ★ 这里刻意不放任何判断：判据只有一处真相。
    // ------------------------------------------------------------------

    override fun isMcpTool(toolName: String): Boolean = mcpIsTool(toolName)

    override fun callMcpTool(toolName: String, argumentsJson: String): String =
        mcpCall(toolName, argumentsJson)

    override fun dynamicToolDefinition(toolName: String): chatmobile.tools.ToolDefinition? =
        mcpDefinition(toolName)

    override fun probeMcpServer(url: String): String = mcpProbeServer(url)

    override fun draftMcpServer(url: String): String = mcpDraftServer(url)

    override fun currentConversationIdHint(): String? =
        runCatching { conversationIdHint() }.getOrNull()

    /**
     * 跨会话历史检索。
     *
     * ★ 真正的查询在 `ChatHost` 里（它手上有 repo），这里只做**兜底与措辞**：
     *   失败绝不能抛 —— 工具失败是正常结果，要作为数据喂回模型。
     */
    override fun searchHistory(
        query: String?,
        mode: String?,
        days: Int?,
        count: Int,
        excludeConversationId: String?,
    ): String = runCatching {
        historySearch(query, mode, days, count, excludeConversationId)
    }.getOrElse { e ->
        Log.w(tag, "search_history 失败", e)
        "ERROR: 历史检索失败：" + (e.message ?: e::class.simpleName) +
            "。请告诉用户这次没查到，不要凭印象编造他以前做过什么。"
    }

    override fun readFile(path: String, maxChars: Int): String {
        val root = workspaceRoot()
        val abs = resolvePath(path, root)
        guard(com.chatmobile.workspace.FileOperation.READ, abs, root)?.let { return it }

        return runCatching {
            val f = File(abs)
            if (!f.exists()) {
                // ★ 顺手把同级目录列出来 —— 模型拼错文件名时，看到正确的名字会立刻自纠，
                //   比回一句 "not found" 有用得多。
                return "ERROR: 文件不存在：$abs" + siblingHint(f.parentFile)
            }
            if (f.isDirectory) {
                return "ERROR: `$abs` 是一个目录，不是文件。请用 list_dir 查看它的内容。"
            }
            if (f.length() > MAX_FILE_BYTES) {
                return "ERROR: 文件过大（${f.length()} 字节），超过 " +
                    "${MAX_FILE_BYTES / 1024}KB 上限。请让用户改用别的方式处理这个文件。"
            }
            val text = runCatching { f.readText() }.getOrElse {
                return "ERROR: 这个文件无法按文本读取（可能不是 UTF-8 文本，或是二进制文件）。"
            }
            val truncated = text.length > maxChars
            buildString {
                append("文件：").append(abs).append('\n')
                append("大小：").append(f.length()).append(" 字节，总长 ")
                append(text.length).append(" 字符")
                if (truncated) append("（以下为前 ").append(maxChars).append(" 字符）")
                append("\n\n").append(if (truncated) text.take(maxChars) else text)
            }
        }.getOrElse { e ->
            Log.w(tag, "read_file 失败", e)
            "ERROR: 读取失败：" + (e.message ?: e::class.simpleName)
        }
    }

    override fun writeFile(path: String, content: String, append: Boolean): String {
        val root = workspaceRoot()
        val abs = resolvePath(path, root)
        guard(com.chatmobile.workspace.FileOperation.WRITE, abs, root)?.let { return it }

        return runCatching {
            val f = File(abs)
            if (f.isDirectory) {
                return "ERROR: `$abs` 是一个目录，不能当文件写。"
            }
            val existed = f.exists()
            val beforeBytes = if (existed) f.length() else 0L
            f.parentFile?.mkdirs()

            if (append) {
                f.appendText(content)
            } else {
                f.writeText(content)
            }

            val afterBytes = f.length()
            buildString {
                if (append) {
                    append("已追加到：").append(abs)
                } else if (existed) {
                    append("已覆盖：").append(abs)
                } else {
                    append("已新建：").append(abs)
                }
                append("\n大小：").append(beforeBytes).append(" → ").append(afterBytes).append(" 字节")
                if (existed && !append && afterBytes < beforeBytes) {
                    // ★ 覆盖会让文件变小 —— 这是最容易误伤用户数据的一种，
                    //   必须显式说出来，让模型知道它删掉了一部分内容。
                    append("\n注意：原文件更长，本次覆盖已丢失 ")
                        .append(beforeBytes - afterBytes).append(" 字节的旧内容。")
                }
            }
        }.getOrElse { e ->
            Log.w(tag, "write_file 失败", e)
            "ERROR: 写入失败：" + (e.message ?: e::class.simpleName)
        }
    }

    override fun listDir(path: String?): String {
        val root = workspaceRoot()
        val abs = resolvePath(path ?: "", root)
        guard(com.chatmobile.workspace.FileOperation.READ, abs, root)?.let { return it }

        return runCatching {
            val dir = File(abs)
            if (!dir.exists()) return "ERROR: 目录不存在：$abs"
            if (!dir.isDirectory) {
                return "ERROR: `$abs` 是一个文件，不是目录。请用 read_file 读取它。"
            }
            val entries = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
            ).orEmpty()

            if (entries.isEmpty()) return "目录 $abs 是空的。"

            buildString {
                append("目录：").append(abs).append("（")
                append(entries.count { it.isDirectory }).append(" 个目录，")
                append(entries.count { !it.isDirectory }).append(" 个文件）\n")
                for (e in entries.take(MAX_LIST_ENTRIES)) {
                    if (e.isDirectory) {
                        append("[目录] ").append(e.name).append('/')
                    } else {
                        append("       ").append(e.name).append("  (").append(e.length())
                            .append(" 字节)")
                    }
                    append('\n')
                }
                if (entries.size > MAX_LIST_ENTRIES) {
                    append("…（还有 ").append(entries.size - MAX_LIST_ENTRIES)
                        .append(" 项未列出）")
                }
            }.trimEnd()
        }.getOrElse { e ->
            Log.w(tag, "list_dir 失败", e)
            "ERROR: 列目录失败：" + (e.message ?: e::class.simpleName)
        }
    }

    /** 文件不存在时，把同级目录的名字列出来帮模型自纠。 */
    private fun siblingHint(parent: File?): String {
        val names = runCatching {
            parent?.listFiles()?.take(MAX_LIST_ENTRIES)?.map { it.name }
        }.getOrNull().orEmpty()
        if (names.isEmpty()) return ""
        return "\n该目录下现有：" + names.joinToString("、")
    }

    // ---------------------------------------------------------------
    // 联网搜索 —— 免费优先，付费保底
    // ---------------------------------------------------------------

    /**
     * 联网搜索。
     *
     * ## ★★★ 分级策略（用户要求：「免费的优先……付费的保底」）
     *
     * 参照本机 `dsh-free-search` 插件的引擎分级（它就是这套做法的现成参考实现）：
     * 里面把 `ddg / ddg-lite / bing / searxng / degoog / anysearch` 归为
     * **FREE_ENGINES**（不需要任何 key），付费引擎只作为 keyless 额度耗尽后的兜底。
     *
     * 这里照同一思路排：
     *
     * | 顺序 | 源 | 要不要 key | 说明 |
     * |---|---|---|---|
     * | ① | AnySearch | ❌ | 结构化 JSON，最好解析 |
     * | ② | DuckDuckGo HTML | ❌ | 结果稳定，但会限流 |
     * | ③ | Bing HTML | ❌ | 覆盖面最好，HTML 结构较脆 |
     *
     * ## ★ 为什么**不**接 DeepSeek 官方的 Search API（虽然它很便宜）
     *
     * 官方 Search 是 **$0.75 / 1000 次**，很便宜；但它要**DeepSeek 的 key**。
     * 本 App 的用户可能用的是 OpenAI / Kimi / 本地模型 —— 对他们来说这个工具
     * 就永远用不了。而免费源对**任何**用户都可用 ⇒ 免费源必须排前面。
     * 付费源只在用户自己配了 key 时启用（当前版本先不接，见类尾注释）。
     *
     * ## ★ 全失败时必须说清"试过哪些"
     *
     * 只说"搜索失败"，用户没法判断是自己网络的问题还是源站挂了。
     */
    override fun webSearch(query: String, count: Int, usePaid: Boolean): String {
        val failures = mutableListOf<String>()
        val q = query.trim()
        if (q.isEmpty()) return "ERROR: 搜索关键词是空的。"

        // ★★★ 核心设计：**主动权交给模型，不是宿主写死顺序**
        //
        //   用户原话（三次，逐步收敛到这一点）：
        //   ① 「免费的优先……付费的保底」
        //   ② 「默认复用模型现在的 key 去搜索，同时允许单独配一个 key 和地址」
        //      「填了的话就用填上去的，不要用模型自己的，会产生不必要的扣费」
        //   ③ 「也就是说没有给主动权，是给他个主动权，让他在搜索结果不佳的时候，
        //       主动调用付费服务」
        //
        //   ## 为什么宿主不该自己排顺序
        //
        //   搜索结果"好不好"，**只有读过结果的模型知道**。宿主按固定顺序排都是在猜：
        //   - 猜"免费够用" ⇒ 用户配的付费服务**永远轮不到**
        //     （这就是上一版的 bug，用户报「还是用不了自己填的搜索服务」）
        //   - 猜"付费优先" ⇒ 每一次普通搜索都花钱
        //
        //   ⇒ 默认走免费；模型读到结果后觉得不够（空、不相关、太浅），
        //     **自己带 `use_paid=true` 再来一次**。这才是"给主动权"。
        //
        //   ## 两条付费通道仍然互斥（扣费安全）
        //
        //   | 设置里填了？ | use_paid=true 时用哪条 |
        //   |---|---|
        //   | 填了 | **用户填的** |
        //   | 没填 | 模型自带 `/v1/tools/search` |
        //
        //   ★ 任何时候都**不会两条都走** —— 那是付两次钱。
        val custom = runCatching { customSearchEndpoint() }.getOrNull()

        if (usePaid) {
            // ===== 模型主动要求付费搜索 =====
            if (custom != null) {
                runCatching { searchViaCustomEndpoint(custom, q, count) }
                    .onSuccess { if (it != null) return renderHits(it.hits, it.label) }
                    .onFailure { failures += "专用搜索服务：${it.message ?: it::class.simpleName}" }
            } else {
                runCatching { searchViaModelEndpoint(q, count) }
                    .onSuccess { if (it != null) return renderHits(it.hits, it.label) }
                    .onFailure { failures += "模型自带接入点：${it.message ?: it::class.simpleName}" }
            }
            // ★ 付费挂了继续往免费源降级 —— 有结果总比没有好，
            //   而且这时不可能再产生第二次扣费（付费那条已经试过了）。
        }

        // ===== 默认路径 / 付费失败后的兜底：免费源 =====
        runCatching { searchAnySearch(q, count) }
            .onSuccess {
                if (it != null) {
                    val note = if (usePaid) {
                        "\n\n（注：你要求过付费搜索，但它没成功，这是免费源的结果）"
                    } else {
                        "\n\n（如果这些结果不够用，可以用 use_paid=true 再搜一次，换付费来源）"
                    }
                    return renderHits(it, "AnySearch（免费）") + note
                }
            }
            .onFailure { failures += "AnySearch：${it.message ?: it::class.simpleName}" }

        runCatching { searchDuckDuckGo(q, count) }
            .onSuccess { if (it != null) return renderHits(it, "DuckDuckGo") }
            .onFailure { failures += "DuckDuckGo：${it.message ?: it::class.simpleName}" }

        return buildString {
            append("ERROR: 搜索失败，试过的都列在下面：\n")
            failures.forEach { append("· ").append(it).append('\n') }
            append("\n可以告诉用户：现在搜不了。**不要凭记忆编造搜索结果。**")
            if (custom != null) {
                append("用户已经在设置里配了专用搜索服务，但它连不上 —— ")
                append("请把上面的错误原样告诉用户，方便 ta 检查地址或 key。")
            } else if (!usePaid) {
                append("你也可以用 use_paid=true 试试模型自带的搜索接口。")
            }
        }
    }

    /** 一次命中的结果集 + 来源标签（让模型与用户都知道数据是谁给的）。 */
    private data class SearchOutcome(val hits: List<Hit>, val label: String)

    /**
     * 用**当前模型自己的**接入点 + key 去搜索。
     *
     * ★ 只有该端点支持 `POST /v1/tools/search` 时才会成功（目前 DeepSeek 官方支持）。
     *   不支持的会返回 404/405，我们**如实记进 failures 再降级**，不静默跳过 ——
     *   否则用户会以为"这个模型不支持搜索"是 bug。
     */
    private fun searchViaModelEndpoint(query: String, count: Int): SearchOutcome? {
        val ep = modelSearchEndpoint() ?: return null
        val body = buildString {
            append("{\"query\":").append(JSON_STR(query))
            append(",\"count\":").append(count).append('}')
        }
        // ★ 走同一个归一化：模型自带的接入点也可能被配置成完整终点
        //   （比如用户在自定义厂商里把 endpointUrl 填成 `.../v1/tools/search`）。
        //   **同一个坑不能只修一半。**
        val url = SearchUrl.resolve(ep.base)
        val raw = postJsonSearch(url, ep.apiKey, body)
        val hits = parseSearchJson(raw)
            ?: throw IllegalStateException("返回内容不是预期的搜索结果格式")
        if (hits.isEmpty()) return null
        return SearchOutcome(hits.take(count), "模型自带接入点（${ep.hostLabel()}）")
    }


    /**
     * 用用户**单独配置**的搜索服务。
     *
     * ★ 端点由调用方传进来（不是内部再读一次）—— 因为调用方已经读过它、
     *   并且**靠它是不是 null 来决定要不要走模型自带通道**（互斥，避免扣费）。
     *   内部再读一次就可能读到不同结果，两边判断不一致。
     */
    private fun searchViaCustomEndpoint(
        ep: SearchEndpoint,
        query: String,
        count: Int,
    ): SearchOutcome? {
        val body = buildString {
            append("{\"query\":").append(JSON_STR(query))
            append(",\"count\":").append(count).append('}')
        }
        val url = SearchUrl.resolve(ep.base)
        val raw = postJsonSearch(url, ep.apiKey, body)
        val hits = parseSearchJson(raw)
            ?: throw IllegalStateException("返回内容不是预期的搜索结果格式")
        if (hits.isEmpty()) return null
        return SearchOutcome(hits.take(count), "专用搜索服务（${ep.hostLabel()}）")
    }

    /**
     * 统一解析搜索结果。
     *
     * ## ★★★ 这里踩过一个真坑：`data` 可能是**对象**而不是数组
     *
     * AnySearch 实际返回的是：
     * ```json
     * { "code": 0, "message": "success",
     *   "data": { "results": [ {title, url, snippet, content}, ... ] } }
     * ```
     * 而 DeepSeek 官方 Search 返回的是**顶层** `{"results": [...]}`。
     *
     * 之前只写了 `root["results"] ?: root["data"] as? JsonArray` ——
     * 第二个分支对 AnySearch 永远不成立（`data` 是 JsonObject），
     * 于是**免 key 的可用通道被自己的解析器吃掉了**，表现为"免费源全都不通"。
     * 这正是本项目反复出现的形态：**链路通了，最后一步解析失败**。
     *
     * ⇒ 现在两种形态都认：`results` 在顶层、`results` 在 `data` 里面。
     */
    private fun parseSearchJson(raw: String): List<Hit>? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val arr = root["results"] as? JsonArray
            ?: (root["data"] as? JsonObject)?.get("results") as? JsonArray
            ?: root["data"] as? JsonArray
            ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o.strOf("url") ?: o.strOf("link") ?: return@mapNotNull null
            Hit(
                title = o.strOf("title").orEmpty().ifBlank { url },
                url = url,
                // ★ summary 是给模型读的长摘要（600-800 字），snippet 是给人看的短摘要。
                //   模型读前者更有用；两者都没有就留空。
                snippet = o.strOf("summary")?.takeIf { it.isNotBlank() }
                    ?: o.strOf("snippet").orEmpty(),
            )
        }
    }

    /** 把结果渲染成模型能读的文本。 */
    private fun renderHits(hits: List<Hit>, source: String): String {
        if (hits.isEmpty()) {
            return "没有搜到结果（来源：$source）。可以换个说法再搜，或者让用户直接给网址。"
        }
        return buildString {
            append("搜索到 ").append(hits.size).append(" 条结果（来源：").append(source).append("）：\n\n")
            hits.forEachIndexed { i, h ->
                append(i + 1).append(". ").append(h.title).append('\n')
                append("   ").append(h.url).append('\n')
                if (h.snippet.isNotBlank()) append("   ").append(h.snippet.take(600)).append('\n')
                append('\n')
            }
            append("（要看正文就用 web_fetch 抓上面某个网址）")
        }
    }

    private fun postJson(url: String, apiKey: String, bodyJson: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "ChatMobile/$currentVersion WebSearch")
        // ★ 空 key 必须**完全不发** Authorization 头。
        //   发一个 `Bearer `（后面什么都没有）会被免费源判成"提供了无效凭据"而 401，
        //   而它本来匿名就能用 —— 那等于我们自己把免费通道关掉了。
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val req = builder
            .post(
                okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    bodyJson.toByteArray(Charsets.UTF_8),
                ),
            )
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    /** 搜索专用 POST —— 走 [searchHttp]（短超时）。 */
    private fun postJsonSearch(url: String, apiKey: String, bodyJson: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "ChatMobile/$currentVersion WebSearch")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val req = builder
            .post(
                okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    bodyJson.toByteArray(Charsets.UTF_8),
                ),
            )
            .build()
        searchHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    /**
     * AnySearch —— 免 key 的结构化搜索。
     *
     * ★ 解析用 `kotlinx.serialization`（本项目的固定选择，不用 `org.json`：它在
     *   JVM 单测里是空 stub，用它写的逻辑一行都测不了）。
     */
    private fun searchAnySearch(query: String, count: Int): List<Hit>? {
        val body = buildString {
            append("{\"query\":").append(JSON_STR(query))
            append(",\"count\":").append(count).append('}')
        }
        val raw = postJsonSearch("https://api.anysearch.com/v1/search", "", body)
        // ★ 复用统一解析 —— AnySearch 的 results 嵌在 `data` 对象里，
        //   这里再抄一份解析就是给自己留第二个出错点。
        val hits = parseSearchJson(raw) ?: return null
        return hits.take(count).takeIf { it.isNotEmpty() }
    }

    /** DuckDuckGo 的 HTML 端点 —— 免 key、无 JS。 */
    private fun searchDuckDuckGo(query: String, count: Int): List<Hit>? =
        searchHtml(
            url = "https://html.duckduckgo.com/html/?q=" + urlEncode(query),
            // DDG 的结果块：<a class="result__a" href="...">标题</a> ... <a class="result__snippet">摘要</a>
            linkRegex = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL),
            snippetRegex = Regex("""<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL),
            count = count,
        )

    /** Bing 的 HTML 结果页。 */
    private fun searchBing(query: String, count: Int): List<Hit>? =
        searchHtml(
            url = "https://www.bing.com/search?q=" + urlEncode(query) + "&count=" + count,
            // Bing：<h2><a href="...">标题</a></h2>，摘要在随后的 <p> 里
            linkRegex = Regex("""<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL),
            snippetRegex = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL),
            count = count,
        )

    /**
     * 通用 HTML 搜索抓取。
     *
     * ★ 这两家都没有给免 key 的 JSON API，只能抓 HTML 页面。所以：
     *   1. 必须带浏览器 UA，否则直接返回垃圾页；
     *   2. 只做**最粗**的抽取，抽不到就返回 null 让上层换下一个源 ——
     *      在 HTML 上堆复杂解析，页面一改版就全崩；
     *   3. **绝不**把整页 HTML 当结果喂给模型。
     */
    private fun searchHtml(
        url: String,
        linkRegex: Regex,
        snippetRegex: Regex,
        count: Int,
    ): List<Hit>? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        val html = searchHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body.string()
        }

        val snippets = snippetRegex.findAll(html)
            .map { stripTags(it.groupValues.getOrNull(1).orEmpty()) }
            .filter { it.length > 20 }
            .toList()

        val hits = linkRegex.findAll(html).mapIndexed { i, m ->
            val rawUrl = m.groupValues[1]
            Hit(
                title = stripTags(m.groupValues[2]).ifBlank { rawUrl },
                url = unwrapRedirect(rawUrl),
                snippet = snippets.getOrNull(i).orEmpty().take(220),
            )
        }.filter { it.url.startsWith("http") }
            .take(count)
            .toList()

        return hits.takeIf { it.isNotEmpty() }
    }

    /** DDG / Bing 的链接常被包在跳转里，取出真实地址。 */
    private fun unwrapRedirect(url: String): String {
        val u = url.replace("&amp;", "&")
        // DDG: //duckduckgo.com/l/?uddg=<encoded>
        Regex("""[?&]uddg=([^&]+)""").find(u)?.let { m ->
            return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
                .getOrDefault(u)
        }
        return if (u.startsWith("//")) "https:$u" else u
    }

    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** 一条搜索结果。 */
    private data class Hit(val title: String, val url: String, val snippet: String)

    /** 极简 JSON 字符串转义（只用于拼搜索请求体）。 */
    private fun JSON_STR(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        /**
         * 单个文件读取上限。
         *
         * ★ 8MB 远大于任何会被塞进上下文的文本（结果另有 maxChars 截断），
         *   这条闸挡的是"模型误读一个视频/数据库文件"—— 那种读法会直接 OOM 掉 App。
         */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_LIST_ENTRIES = 200

        /** 抓搜索页必须带浏览器 UA，否则拿到的是空壳或跳转页。 */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
