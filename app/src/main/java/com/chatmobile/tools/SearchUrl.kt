package com.chatmobile.tools

/**
 * **搜索地址的归一化** —— 纯函数，可完整单测。
 *
 * ## ★★★ 为什么必须有它（真机踩到的坑）
 *
 * 用户在设置里填的是 `https://api.deepseek.com/v1/tools/search`（**完整终点**），
 * 而代码里写死的是 `base + "/v1/tools/search"`，于是拼出
 * `.../v1/tools/search/v1/tools/search` ⇒ 必然 404。
 *
 * 用户没有任何办法知道"这个输入框到底该填根地址还是完整地址"：
 * placeholder 写的是 `https://api.deepseek.com`，但他从官方文档抄来的是完整地址。
 * **两种都合理**，所以必须两种都认，而不是要求用户猜对。
 *
 * ## 判据只有一条
 *
 * **路径末尾是不是已经以 `/tools/search` 结尾** —— 是就不加，不是就补全。
 *
 * | 用户填的 | 实际请求 |
 * |---|---|
 * | `https://api.deepseek.com` | `.../v1/tools/search` |
 * | `https://api.deepseek.com/` | 同上 |
 * | `https://api.deepseek.com/v1` | `.../v1/tools/search` |
 * | `https://api.deepseek.com/v1/` | 同上 |
 * | `https://api.deepseek.com/v1/tools` | `.../v1/tools/search` |
 * | `.../v1/tools/search` | **原样使用** |
 * | `.../v1/tools/search/` | 同上（去尾斜杠） |
 * | `.../v1/Tools/Search` | 同上（大小写也认） |
 * | `.../v1//tools/search` | 同上（合并重复斜杠） |
 * | `.../v1/tools/search?k=1` | 同上（**query 原样保留**） |
 */
object SearchUrl {

    private const val SUFFIX = "/tools/search"

    fun resolve(base: String): String {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return ""
        // ① 拆出 query / fragment，只对路径做判定，拼完再原样接回去 ——
        //    自建网关可能带 `?key=xxx`，粗暴 trim 会把它吃掉。
        val (pathPart, tail) = splitTail(trimmed)
        // ② 去尾斜杠 + 合并重复斜杠（手打时很常见），但**不能动协议里的 `//`**
        val b = pathPart.trimEnd('/').replace(Regex("(?<!:)//+"), "/")
        val lower = b.lowercase()

        val full = when {
            // ③ 用户填了后缀 ⇒ **不再重复加**
            lower.endsWith(SUFFIX) -> b
            // ④ 写到 /tools（少了 /search）⇒ 补最后一段
            lower.endsWith("/tools") -> "$b/search"
            // ⑤ 只写到 /v1 ⇒ 补 /tools/search
            lower.endsWith("/v1") -> "$b$SUFFIX"
            // ⑥ 根地址或其它 ⇒ 补全路径
            else -> "$b/v1$SUFFIX"
        }
        return full + tail
    }

    /** 把 URL 拆成 (路径, `?...`或`#...` 起的尾巴)。没有则尾巴为空串。 */
    private fun splitTail(url: String): Pair<String, String> {
        val idx = url.indexOfFirst { it == '?' || it == '#' }
        return if (idx < 0) url to "" else url.substring(0, idx) to url.substring(idx)
    }
}
