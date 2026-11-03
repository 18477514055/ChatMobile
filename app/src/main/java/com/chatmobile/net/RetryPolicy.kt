package com.chatmobile.net

import kotlin.math.min
import kotlin.random.Random

/**
 * 指数退避策略。
 *
 * ★ **坑 6**：okhttp-sse 不自动重连（`onRetryChange` 是空实现，源码注释
 * *"Ignored. We do not auto-retry."*），所以重连必须应用层自己做。
 *
 * 策略：
 * - 只重试**还没吐出任何内容**的失败。已经吐出正文后再重试，会导致 UI 上出现
 *   「半句话 + 从头再来」的重复内容 —— 那比直接报错更糟。
 * - 延迟 = `base * 2^attempt`，封顶 [maxDelayMs]，再乘一个 0.5~1.0 的抖动
 *   （避免多个请求同时重试造成尖峰）。
 */
data class RetryPolicy(
    /** 最多重试几次（不含首次请求）。0 = 不重试。 */
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 8_000L,
    /** 是否加随机抖动。测试里关掉以便断言确定值。 */
    val jitter: Boolean = true,
) {
    /** 第 [attempt] 次重试前应等待的毫秒数（attempt 从 0 开始）。 */
    fun delayFor(attempt: Int): Long {
        val exp = baseDelayMs * (1L shl attempt.coerceIn(0, 20))
        val capped = min(exp, maxDelayMs)
        if (!jitter) return capped
        // 抖动到 [capped/2, capped]
        val half = capped / 2
        return half + Random.nextLong(half + 1)
    }

    companion object {
        /** 不重试。 */
        val NONE: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}

/**
 * 把 `Throwable` 归类成「是否值得重试」+ 一句人能看懂的话。
 *
 * 分类依据（协议调研报告 §7）：
 * - 连接/读超时 → 可重试（推理模型思考久了很常见）
 * - 5xx / 429 / 408 / 409 → 可重试
 * - 401 / 403 → **不可**重试（密钥错了，重试一万次也一样）
 * - 400 / 404 / 422 → 不可重试（请求或模型名错了）
 * - 证书 / 域名错误 → 不可重试
 */
internal object ErrorMapper {

    fun isRetryable(e: Throwable): Boolean = when (e) {
        is java.net.SocketTimeoutException -> true
        is java.net.ConnectException -> true
        is java.net.SocketException -> true
        is java.io.InterruptedIOException -> true
        is javax.net.ssl.SSLException -> false
        is java.net.UnknownHostException -> false
        else -> false
    }

    fun isRetryableHttp(code: Int): Boolean = when (code) {
        408, 409, 425, 429 -> true
        in 500..599 -> true
        else -> false
    }

    /**
     * 把网络异常翻译成给用户看的一句话。
     *
     * 刻意**不把原始堆栈/URL 直接甩给用户**，但保留可辨识的关键词，
     * 方便用户自己判断是网络问题还是配置问题。
     */
    fun describe(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException ->
            "请求超时：服务端长时间没有响应（推理模型思考较久时会这样）"
        is java.net.ConnectException ->
            "连接失败：无法连接到服务器，请检查网络或 baseUrl"
        is java.net.UnknownHostException ->
            "域名解析失败：请检查 baseUrl 是否写对"
        is javax.net.ssl.SSLException ->
            "TLS 握手失败：证书或 HTTPS 配置有问题"
        is java.io.InterruptedIOException ->
            "连接被中断"
        else -> {
            val m = e.message?.takeIf { it.isNotBlank() }
            buildString {
                append(e::class.simpleName ?: "网络错误")
                if (m != null) append("：").append(m)
            }
        }
    }

    /** HTTP 状态码 → 一句带诊断建议的话。 */
    fun describeHttp(code: Int, body: String?): String {
        val hint = when (code) {
            400 -> "请求被拒绝，可能是参数或模型名不被支持"
            401 -> "鉴权失败：API Key 无效或已过期"
            403 -> "无权限：这个 Key 不能访问该模型"
            404 -> "路径不存在：baseUrl 或 /chat/completions 路径可能写错"
            413 -> "请求体过大"
            422 -> "参数校验失败"
            429 -> "触发限流或额度不足，请稍后再试"
            in 500..599 -> "服务端错误，请稍后重试"
            else -> "HTTP 错误"
        }
        val snippet = body?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
        return if (snippet == null) "HTTP $code — $hint" else "HTTP $code — $hint（$snippet）"
    }
}
