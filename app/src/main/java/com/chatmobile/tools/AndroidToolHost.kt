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
import okhttp3.OkHttpClient
import okhttp3.Request
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
class AndroidToolHost(
    private val context: Context,
    private val memoryBank: MemoryBank,
    /**
     * 记忆是否启用。关闭时 `save_memory` / `search_memory` **如实拒绝**。
     * 用 lambda 而不是 Boolean：配置随时可能被用户在设置里改，不能缓存死。
     */
    private val memoryEnabled: () -> Boolean,
) : ToolHost {

    private val tag = "ChatToolHost"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
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
}
