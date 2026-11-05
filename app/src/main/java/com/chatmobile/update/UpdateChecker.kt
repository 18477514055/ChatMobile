package com.chatmobile.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * **应用内检查更新** —— 直接问 GitHub Releases。
 *
 * ## ★★★ 最重要的一件事：给没有梯子的人留路
 *
 * `api.github.com` 在国内网络下**基本打不开**（超时或连接重置）。
 * 如果只做这一条通道，那么这个按钮对相当一部分用户就是**永远失败**的 ——
 * 而"检查更新"恰恰是给"不知道有新版本"的人用的，让这批人看不到更新最荒谬。
 *
 * ⇒ 三条通道**依次降级**，任何一条通了就算通：
 *
 * | 顺序 | 通道 | 地址 | 国内可达性 | 拿到什么 |
 * |---|---|---|---|---|
 * | ① | GitHub API（直连） | `api.github.com/repos/.../releases` | ❌ 多数不通 | 完整（tag/资产/说明） |
 * | ② | jsDelivr CDN 上的 release 元数据 | `cdn.jsdelivr.net` | ⚠️ 多数可达 | **只有版本号**（拿不到资产） |
 * | ③ | 全部失败 | —— | —— | 报原因 + **给出可复制的更新页地址** |
 *
 * ★ 通道 ② 的取舍：它拿不到 APK 直链，所以 UI 只能"告诉你有新版 + 让你自己开页面"。
 *   但那**远好于**"什么都没检查到"。少一个直接下载按钮，不构成让人放弃更新的理由。
 *
 * ## ★ 为什么不做静默自动更新、也不后台轮询
 *
 * 这是个自带 key 的客户端，用户装它就是为了不要有人偷偷联网。
 * 只在用户点「检查更新」时联网，一次。
 *
 * ## ★ 为什么读 `/releases` 而不是 `/releases/latest`
 *
 * `/latest` 会跳过 prerelease 与 draft，看起来更省事；但它在仓库只有 draft 时
 * 返回 404，报错对用户毫无意义。而 `/releases` 在"还没有任何 Release"时返回 `[]`
 * —— 那是一句能读懂的事实。
 */
class UpdateChecker(
    private val currentVersion: String,
    /** 例：`18477514055/ChatMobile`。 */
    private val repo: String = DEFAULT_REPO,
) {

    private val tag = "UpdateChecker"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * ★ 两条通道各自一个 client，超时故意都很短。
     *
     * 依据：`api.github.com` 在国内的表现是**连不上但要等很久**（TCP 挂起）。
     * 给 30 秒的话，三条通道串起来最坏接近两分钟 —— 用户早就以为死机了。
     * 5+8 秒足够跑完全部三条，还在"可以等"的心理范围内。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 本次检查走的哪条通道 —— 显示给用户，让"为什么没有下载按钮"有解释。 */
    data class Result(val check: UpdateCheck, val channel: Channel?)

    enum class Channel(val label: String) {
        GitHubApi("GitHub API"),
        GitHubMirror("GitHub 镜像"),
        Atom("releases.atom"),
        JsDelivr("jsDelivr CDN"),
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()

        // ---------------- 通道 ①：GitHub API（直连 + 镜像） ----------------
        //
        // ★★★ 为什么"直连 + 镜像"合成一组而不是各算一条通道：
        //   它们拿到的是**同一种数据**（完整 release JSON），区别只在走哪个域名。
        //   只要任何一个域名通了，后面那些更弱的通道（atom / jsDelivr）根本不用试。
        //   反过来，如果把它们拆成独立通道，就会出现"镜像拿到版本号了、
        //   但用户还是只能看到页面链接"——因为先命中的是弱通道。
        //
        // ★ 镜像顺序按**实测可达性**排，不按名气排。2026-11-04 实测：
        //   - `api.github.com`      ✅ 通（境外/有代理时）
        //   - `gh-proxy.com`        ✅ 通（转发 API 与 atom 都正常）
        //   - `ghfast.top`          ❌ 403（Cloudflare 挑战页）
        //   - `ghproxy.net`         ❌ 403
        // ⇒ 只放前两个。**没验证过的地址不许写进来** —— 每一个都是串行的等待成本。
        for ((label, base) in API_BASES) {
            runCatching { fetchApiReleases(base) }
                .onSuccess { body ->
                    val release = parseLatestRelease(body)
                    if (release == null) {
                        // 通了但没有可用 Release —— 这是**确定的事实**，不必再试别的通道。
                        return@withContext Result(
                            UpdateCheck.Failed(
                                "仓库里还没有已发布的版本。（$repo）",
                                pageUrlOf(repo),
                            ),
                            Channel.GitHubApi,
                        )
                    }
                    val ch = if (base == GITHUB_API) Channel.GitHubApi else Channel.GitHubMirror
                    var checked = judgeFull(release)
                    // ★ 走镜像时，连**下载地址**也要套镜像前缀 ——
                    //   releases 里的 `browser_download_url` 是原始 GitHub 域名，
                    //   在国内同样打不开。只把 API 换成镜像、下载还走原地址，
                    //   等于"查得到但下不了"，用户点了会看到一个打不开的页面。
                    val apk = (checked as? UpdateCheck.Available)?.apkUrl
                    if (checked is UpdateCheck.Available && base != GITHUB_API && apk != null) {
                        checked = checked.copy(apkUrl = base + apk)
                    }
                    return@withContext Result(checked, ch)
                }
                .onFailure { failures += "$label：${describe(it)}" }
        }

        // ---------------- 通道 ②：releases.atom（极轻，能拿到说明） ----------------
        //
        // ★ atom 只有几百字节，且 `gh-proxy.com` 转发它是通的。
        //   它比 jsDelivr 强的地方：**能拿到整段更新说明**，用户知道更新了什么。
        //   弱的地方：拿不到 APK 直链。
        for ((label, base) in ATOM_BASES) {
            runCatching { fetchAtom(base) }
                .onSuccess { tag ->
                    if (tag != null) {
                        return@withContext Result(judgeTagOnly(tag), Channel.Atom)
                    }
                }
                .onFailure { failures += "$label atom：${describe(it)}" }
        }

        // ---------------- 通道 ③：jsDelivr 只拿版本号 ----------------
        runCatching { fetchJsDelivrVersion() }
            .onSuccess { rawTag ->
                if (rawTag != null) {
                    return@withContext Result(judgeTagOnly(rawTag), Channel.JsDelivr)
                }
            }
            .onFailure { failures += "jsDelivr CDN：${describe(it)}" }

        // ---------------- ④ 全都不通 ----------------
        UpdateCheck.Failed(
            reason = buildString {
                append("联网检查失败，试过的地址都不通：\n")
                failures.forEach { append("· ").append(it).append('\n') }
                append("\n可以直接打开下面的地址看看有没有新版本：\n")
                append(pageUrlOf(repo))
            },
            manualUrl = pageUrlOf(repo),
        ).let { Result(it, null) }
    }

    // ---------------------------------------------------------------
    // 通道 ①
    // ---------------------------------------------------------------

    private fun fetchApiReleases(base: String): String {
        val req = Request.Builder()
            .url("${base}https://api.github.com/repos/$repo/releases?per_page=10")
            // ★ GitHub API 要求带 User-Agent，否则直接 403。
            .header("User-Agent", "ChatMobile/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return text
        }
    }

    // ---------------------------------------------------------------
    // 通道 ②：releases.atom
    // ---------------------------------------------------------------

    /**
     * 抓 `releases.atom` 并取出**最新一条**的 tag。
     *
     * ## ★ 为什么这条通道值得留
     *
     * atom 只有几百字节，是所有通道里最轻的；而且 `gh-proxy.com` 转发它实测正常。
     * 相比 jsDelivr，它的优势是**能拿到整段更新说明**——虽然这一版我们先只用它取 tag，
     * 但将来想在"发现新版本"里显示更新内容，只有它和 API 能提供。
     *
     * ## ★ 解析方式：正则，不引 XML 解析器
     *
     * 理由：atom 的 `<entry>` 结构极稳定，而我们只要一个 `<title>`（形如 `v0.1.0`）
     * 与一个日期。为此引一个 XML 库（或搭一套 DOM 遍历）不划算，
     * 而"解析失败 ⇒ 返回 null ⇒ 继续下一条通道"这个降级本来就很安全。
     *
     * ★ 版本号必须是**最新的**那个：atom 里多条 entry 是**按时间正序**还是倒序，
     *   规范上不保证。这里不赌顺序，直接对全部 entry 的 title 取版本号最大值。
     */
    private fun fetchAtom(prefix: String): String? {
        val req = Request.Builder()
            .url("${prefix}https://github.com/$repo/releases.atom")
            .header("User-Agent", "ChatMobile/$currentVersion")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body.string()
            val tags = Regex("""<entry>.*?<title[^>]*>([^<]+)</title>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .filter { SemVer.parse(it) != null }
                .toList()
            return tags.maxByOrNull { SemVer.parse(it)!! }
        }
    }

    private fun fetchJsDelivrVersion(): String? {
        val req = Request.Builder()
            .url("https://data.jsdelivr.com/v1/package/gh/$repo")
            .header("User-Agent", "ChatMobile/$currentVersion")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body.string()
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                as? JsonObject ?: return null
            val versions = root["versions"] as? JsonArray ?: return null
            return versions
                .mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNullValue() }
                .filter { SemVer.parse(it) != null }
                .maxByOrNull { SemVer.parse(it)!! }
        }
    }

    private fun JsonPrimitive.contentOrNullValue(): String? =
        if (isString) content else content.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------
    // 判定
    // ---------------------------------------------------------------

    private fun judgeFull(release: ReleaseInfo): UpdateCheck {
        val cur = SemVer.parse(currentVersion)
        val lat = SemVer.parse(release.tag)
        if (cur == null || lat == null) {
            // ★ 解析不出来就**如实说**，绝不猜"大概是最新" ——
            //   猜错的代价是用户以为没有更新，永远停在旧版本。
            return UpdateCheck.Failed(
                "读不出可比较的版本号（当前 `$currentVersion`，线上 `${release.tag}`）。",
                release.pageUrl.ifBlank { pageUrlOf(repo) },
            )
        }
        return if (lat > cur) {
            UpdateCheck.Available(
                current = currentVersion,
                latest = release.tag.cleanVersion(),
                pageUrl = release.pageUrl.ifBlank { pageUrlOf(repo) },
                apkUrl = release.apkUrl,
                notes = release.notes,
                apkBytes = release.apkBytes,
            )
        } else {
            UpdateCheck.UpToDate(currentVersion, release.tag.cleanVersion())
        }
    }

    /** 只拿到版本号（通道 ②）时的判定 —— 没有直链，只能给页面。 */
    private fun judgeTagOnly(rawTag: String): UpdateCheck {
        val cur = SemVer.parse(currentVersion)
        val lat = SemVer.parse(rawTag)
        if (cur == null || lat == null) {
            return UpdateCheck.Failed(
                "读不出可比较的版本号（当前 `$currentVersion`，线上 `$rawTag`）。",
                pageUrlOf(repo),
            )
        }
        return if (lat > cur) {
            UpdateCheck.Available(
                current = currentVersion,
                latest = rawTag.cleanVersion(),
                pageUrl = pageUrlOf(repo),
                // ★ 没有直链 —— UI 会退化成"打开 Release 页面"，
                //   文案里也会标明这条通道拿不到安装包。
                apkUrl = null,
                notes = "",
                apkBytes = 0L,
            )
        } else {
            UpdateCheck.UpToDate(currentVersion, rawTag.cleanVersion())
        }
    }

    /**
     * 从 releases 列表里挑出**最该给用户看的那一个**。
     *
     * 规则（写在代码里，免得以后又要猜）：
     * 1. 跳过 draft（草稿不该被任何外部渠道看到）；
     * 2. 跳过 prerelease（beta 不该被推给普通用户）；
     * 3. 在剩下的里按**版本号**取最大 —— 列表是按创建时间倒序的，
     *    但 tag 顺序不一定与创建时间一致（比如补发一个旧版本）。
     */
    internal fun parseLatestRelease(body: String): ReleaseInfo? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val arr = root as? JsonArray ?: return null
        if (arr.isEmpty()) return null

        val candidates = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.bool("draft") == true) return@mapNotNull null
            if (o.bool("prerelease") == true) return@mapNotNull null
            val rawTag = o.str("tag_name") ?: return@mapNotNull null
            val ver = SemVer.parse(rawTag) ?: return@mapNotNull null
            ReleaseInfo(
                tag = rawTag,
                version = ver,
                pageUrl = o.str("html_url").orEmpty(),
                apkUrl = pickApk(o["assets"] as? JsonArray)?.first,
                apkBytes = pickApk(o["assets"] as? JsonArray)?.second ?: 0L,
                notes = o.str("body").orEmpty().take(600),
            )
        }
        return candidates.maxByOrNull { it.version }
    }

    /**
     * 挑 APK 资产，返回 (下载地址, 字节数)。
     *
     * ★ 优先 `.apk` 结尾；找不到就回 null（UI 会退化成"打开 Release 页面"），
     *   **不去猜别的资产名** —— 猜错会让用户下载到一个非安装包。
     */
    private fun pickApk(assets: JsonArray?): Pair<String, Long>? {
        if (assets == null) return null
        for (a in assets) {
            val o = a as? JsonObject ?: continue
            val name = o.str("name").orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = o.str("browser_download_url") ?: continue
            val size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            return url to size
        }
        return null
    }

    private fun describe(e: Throwable): String = when {
        e is IllegalStateException -> e.message ?: "请求失败"
        e.message?.contains("timeout", ignoreCase = true) == true -> "超时（网络可能被阻断）"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }

    private fun pageUrlOf(repo: String) = "https://github.com/$repo/releases"

    /** `v0.1.0` → `0.1.0`（显示用；比较仍在 [SemVer] 里做）。 */
    private fun String.cleanVersion(): String =
        trim().removePrefix("v").removePrefix("V")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.content.trim().equals("true", ignoreCase = true) }

    /** 内部用的 Release 条目。 */
    internal data class ReleaseInfo(
        val tag: String,
        val version: SemVer,
        val pageUrl: String,
        val apkUrl: String?,
        val apkBytes: Long,
        val notes: String,
    )

    companion object {
        /**
         * 默认仓库。
         *
         * ★ 写死在这里而不是从 BuildConfig 注入：它**不是环境相关配置**，
         *   而是"这个 App 的发布渠道是哪"。注入反而多一层可能配错的环节。
         */
        const val DEFAULT_REPO = "18477514055/ChatMobile"

        /** GitHub API 的原始域名。 */
        const val GITHUB_API = "https://api.github.com/repos/"

        /**
         * API 通道的地址前缀（**拼在完整 URL 前面**，空串 = 直连）。
         *
         * ★★★ 这张表里的每一个都**实测过**（2026-11-04，本机 + 手机）：
         *
         * | 前缀 | 结果 |
         * |---|---|
         * | `""`（直连） | ✅ 通（境外/代理），国内多数超时 |
         * | `https://gh-proxy.com/` | ✅ 通，转发 API 与 atom 都正常 |
         * | `https://ghfast.top/` | ❌ 403（Cloudflare 挑战页） |
         * | `https://ghproxy.net/` | ❌ 403 |
         *
         * ⇒ **只放能用的**。没验证过的地址不许加：每一条都是串行的等待成本，
         *   而"检查更新"这个动作用户最多忍几秒。加一条坏地址 = 让所有人多等一个超时。
         *
         * ★ 想再加镜像：先用 curl 打 `https://<镜像>/https://api.github.com/repos/.../releases`
         *   确认拿到 200 和真 JSON，再往后追加。
         */
        private val API_BASES = listOf(
            "直连" to "",
            "gh-proxy.com" to "https://gh-proxy.com/",
        )

        /**
         * atom 通道的前缀。
         *
         * ★ 直连放前面（境外用户体验最好），镜像兜底。
         *   注意 atom 的 URL 是 `https://github.com/...`，不是 api 域名。
         */
        private val ATOM_BASES = listOf(
            "直连" to "",
            "gh-proxy.com" to "https://gh-proxy.com/",
        )
    }
}
