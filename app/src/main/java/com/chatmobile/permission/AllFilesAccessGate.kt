package com.chatmobile.permission

/**
 * 「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）授权入口的**可达性判据**。
 *
 * ## ★★★ 为什么需要这样一个东西（真机踩过）
 *
 * 这条权限**不是运行时权限** —— `requestPermissions()` 对它无效，必须跳到系统设置里
 * 那个「本 App 专属页」。上一版的实现是：先 `startActivity(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)`，
 * 失败再退到列表页，都失败就只写一行 log。
 *
 * 真机（Redmi / MIUI / Android 16）结果是：**用户点「去授予全盘访问」，什么都不发生**。
 * 因为 MIUI 把这一整组设置页裁掉了 —— 用户自己在系统权限页里也找不到
 * 「所有文件访问」这一项（他已截图确认：只有位置 / 相机 / 照片和视频 / 短信 / 其他权限）。
 *
 * 而这一切在编译期、在静态代码里**完全看不出来**：代码没错，`startActivity` 也不抛异常，
 * 只是那扇门不存在。
 *
 * ## 所以判据是：**先探测，再决定给不给入口**
 *
 * 宁可如实告诉用户「这台机器没有这个开关」，也不要留一个点了没反应的按钮 ——
 * 那比没有按钮更糟：用户会以为是自己点错了，反复点。
 *
 * ★ 纯逻辑放在这里（不碰 android.*），才能被 JVM 单测钉住；
 *   真正的 `resolveActivity` 探测在 `ChatHost` 里做，只把两个布尔喂进来。
 */
object AllFilesAccessGate {

    /** 本 App 专属页（按包名定位）。 */
    const val ACTION_APP_PAGE: String = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"

    /** 「所有文件访问」列表页（不指定 App）。 */
    const val ACTION_LIST_PAGE: String = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

    /** 系统里到底哪条路走得通。 */
    enum class Route {
        /** 专属页可用 —— 最理想，直接落到本 App 那一栏。 */
        AppPage,

        /** 专属页没有，但列表页在 —— 退一步，用户自己找本 App。 */
        ListPage,

        /** 两个都没有 —— 这台机器真的没有这个开关。**不给按钮**。 */
        Unavailable,
    }

    /**
     * 按探测结果选路。
     *
     * ★ 顺序不能反：专属页优先（少让用户找一次）。只有它探不到才退列表页。
     */
    fun decide(hasAppPage: Boolean, hasListPage: Boolean): Route = when {
        hasAppPage -> Route.AppPage
        hasListPage -> Route.ListPage
        else -> Route.Unavailable
    }

    /**
     * 系统里没有那个开关时，给用户看的说明。
     *
     * ★ 必须说清三件事，缺一件用户就会反复试：
     *   ① 不是你没找到 / 不是 App 装坏了 —— **是这台机器没这个开关**；
     *   ② 那你现在还能干什么（公共目录能读写）；
     *   ③ 什么不行（在公共目录里**自建**文件夹不行）—— 这条是真机实测出来的：
     *      模型往 `/sdcard/Download/` 写 19 字节成功，往 `/sdcard/跨宇宙文明世界观/` 写报 `EPERM`。
     */
    fun unavailableHint(): String =
        "这台设备的系统设置里没有「所有文件访问」这个开关（部分 MIUI / 定制系统会把它整个裁掉，" +
            "不是你没找到，也不是 App 装坏了）。" +
            "影响：模型仍然能读写手机公共目录（例如 /sdcard/Download、/sdcard/Documents），" +
            "但不能在这些目录里自己新建文件夹 —— 那一步需要这条权限。" +
            "如果你需要更大的范围，把工作区设到一个已存在的公共目录即可。"

    /**
     * 专属页 / 列表页可用时的引导文案。
     *
     * ★ 与 [unavailableHint] 是**互斥**的两条路，UI 上只出现一条 ——
     *   同时出现会让人搞不清到底要不要点。
     */
    fun availableHint(): String =
        "这一步会打开系统设置里的本 App 专属页。系统不允许我们替你打开。" +
            "不授予也完全能用，只是文件工具出不了工作区。"
}
