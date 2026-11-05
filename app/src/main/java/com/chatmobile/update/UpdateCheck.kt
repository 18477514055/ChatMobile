package com.chatmobile.update

/**
 * 版本号 —— 只用于**比较**，不用于显示。
 *
 * ## ★★★ 为什么不能直接比字符串
 *
 * `"0.10.0" > "0.9.0"` 在字符串比较里是 **false**（`'1' < '9'`），
 * 而语义上 0.10.0 明显更新。发到第 10 个版本时这个 bug 才会暴露，
 * 那时候用户已经点过"检查更新"看到"已是最新"了。
 *
 * ⇒ 按 `.` 切成数字逐段比，段数不同时缺失段补 0（`1.2` == `1.2.0`）。
 */
data class SemVer(val parts: List<Int>) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /**
         * 解析一个版本串。
         *
         * ★ 刻意**宽松**：`v0.1.0`、`0.1.0`、`0.1`、`1.2.3-beta` 都要能解析出数字段。
         *   原因是 tag 的写法由我们（或将来别人）决定，而"检查更新"只要数字，
         *   因为词形不对就整条链路失败，代价远大于收益。
         * ★ 一个数字都取不到 ⇒ 返回 null（调用方据此说"读不到版本号"，不许猜）。
         */
        fun parse(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            // 取第一段连续"数字与点"的子串；`v0.1.0` / `release-0.1.0` 都会命中 `0.1.0`
            val m = Regex("""\d+(?:\.\d+)*""").find(raw.trim()) ?: return null
            val parts = m.value.split('.').mapNotNull { it.toIntOrNull() }
            return if (parts.isEmpty()) null else SemVer(parts)
        }
    }
}

/**
 * 一次「检查更新」的结论。
 *
 * ★ 用 sealed 而不是 `Pair<Boolean, String>`：这四种情况**要显示的 UI 完全不同**
 *   （可更新 → 给下载按钮；已最新 → 只报一句；失败 → 给重试按钮；
 *   渠道不支持 → 说明原因）。用一对值会把它们压成同一个"失败提示"。
 */
sealed interface UpdateCheck {

    /** 有比当前更新的版本。 */
    data class Available(
        val current: String,
        val latest: String,
        /** Release 页面地址（给「去下载」用）。 */
        val pageUrl: String,
        /** 直接下载 APK 的地址（优先）。 */
        val apkUrl: String?,
        /** Release 说明正文（可能为空）。 */
        val notes: String,
        /** 资产大小（字节），可能为 0（未知）。 */
        val apkBytes: Long,
    ) : UpdateCheck

    /** 已经是最新（或本地比线上还新，例如开发版）。 */
    data class UpToDate(val current: String, val latest: String) : UpdateCheck

    /**
     * 检查本身失败了。
     *
     * ★ 必须把**原因**带到 UI 上：网络不通、被墙、仓库还没有 Release，
     *   用户需要能自己判断"是我网络的问题，还是这个 App 的问题"。
     *
     * ★★★ [manualUrl] 是这个失败态最重要的一部分：**没有梯子的人**在这里
     *   仍然要有一条能走的路。放一个固定的更新页地址，让用户自己去复制打开，
     *   比只显示"检查失败"有用得多 —— 后者等于告诉他"你没梯子就别想更新了"。
     */
    data class Failed(
        val reason: String,
        val manualUrl: String = DEFAULT_PAGE_URL,
    ) : UpdateCheck

    companion object {
        /** 人手打开用的更新页（浏览器里能不能开取决于用户网络，但至少地址是给全的）。 */
        const val DEFAULT_PAGE_URL = "https://github.com/18477514055/ChatMobile/releases"
    }
}
