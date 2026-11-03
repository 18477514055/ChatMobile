package com.chatmobile.ui.chat

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

/**
 * 聊天界面的动效 token。
 *
 * ★ 全部数值来自 `UI研究-动效与转场.md` §3.2 / §7.1，是从 ChatGPT APK 的
 *   `dil_generated_components.raw` 里直接读出的**明文 animate 规格**（标 [实测]）。
 *   原文片段（§3.2 A 组）：
 *
 *   ```js
 *   initial:    { opacity: 0, translateY: -6 }
 *   enter:      { opacity: 1, translateY: 0, delay: 0, duration: 180 }
 *   exit:       { opacity: 0, translateY: -4, delay: 0, duration: 120 }
 *   layoutEnter:{ delay: 0, duration: 220 }
 *   layoutExit: { delay: 0, duration: 160 }
 *   layoutMove: { delay: 0, duration: 220 }
 *   ```
 *
 * ★ 三条原则（§7.4，比数值更值钱）：
 *   1. 出场快于入场 —— 进 180~200，出 120~150，比例约 1 : 0.67
 *   2. 位移极小（4~6 px 级）+ 透明度承担主要信息
 *   3. 跟手期间绝不插值 —— 拖动 1:1 直给，松手才弹簧
 */
object ChatMotion {

    /** 消息入场：180 ms（带位移）。[实测] */
    const val EnterMs = 180

    /** 消息退场：120 ms（带位移）。[实测] */
    const val ExitMs = 120

    /** 列表布局进入 / 移动：220 ms。[实测] */
    const val LayoutEnterMs = 220

    /** 列表布局退出：160 ms。[实测] */
    const val LayoutExitMs = 160

    /**
     * 列表布局移动（**非流式**场景）：220 ms。[实测]
     *
     * ⚠️ 只在**非流式**时使用。流式期间必须传 `placementSpec = null`。
     *
     * ★ 注意：是 **`null`**，不是 `tween(0)` —— 二者走**不同的代码分支**，
     *   见 [ChatScreen] 里 `animateItem` 处的字节码注释。`tween(0)` 仍然会
     *   `launch` 一个协程，而协程调度本身就至少一帧。
     */
    const val LayoutMoveMs = 220

    /**
     * 列表布局移动（**流式**场景）。
     *
     * ## ★★★ 这里刻意**不提供**任何 spec —— 流式期间应当传 `null`
     *
     * 历史教训（真机验证过，且用字节码证伪过一个中间方案）：
     *
     * 用户原话：
     * > 「向上的运动不是连贯的，而是每多一行向上挤一下，但是这个挤又没有同步」
     * > 「行数先增加，然后在极短的时间内用户消息再上升」
     *
     * 排查走了三步，前两步都修错了地方：
     *
     * | 尝试 | 做法 | 结果 |
     * |---|---|---|
     * | 1 | `placementSpec = tween(0)`（以为等于 snap） | ❌ 仍晚 1 帧 |
     * | 2 | 滚动改 `requestScrollToItem` + 调用点改 `SideEffect` | ❌ 治的是滚动，延迟在位移动画里 |
     * | 3 | **`placementSpec = null`** | ✅ 走 `animatePlacementDelta` 的**提前 return** 分支 |
     *
     * **证据**（`javap -c LazyLayoutItemAnimation`）：
     * ```
     * animatePlacementDelta-ar5cAso(long, boolean):
     *   0: getfield  placementSpec
     *   5: ifnonnull 10        ← 非空才继续
     *   9: return              ← ★ null 直接返回，零开销
     *  30: setPlacementAnimationInProgress(true)
     *  62: invokestatic BuildersKt.launch$default   ← ★ 非空就 launch 协程
     * ```
     *
     * ⇒ `tween(0)` 的语义是「用一个协程做 0 毫秒动画」，协程调度至少一帧；
     *   `null` 的语义是「不创建这个动画」。**这不是参数值不同，是代码路径不同。**
     *
     * ## 所以这里没有 `layoutMoveStreamingSpec()`
     *
     * 曾经有过一个 `const val LayoutMoveStreamingMs = 0` + `layoutMoveStreamingSpec()`，
     * 那是第 1 步的错误产物。**不要把它加回来** —— 加了会重新引入那一帧延迟，
     * 而且编译器不会报错、界面"看起来也在动"，属于最难查的那类回归。
     *
     * 与此无关、**仍然有效**的另一条原则（报告 §7.4 原则 3）：
     * *跟手期间绝不插值* —— 流式输出就是"内容在跟手"的状态。
     */

    /** 纯淡入（无位移）：200 ms。[实测] */
    const val FadeInMs = 200

    /** 纯淡出：150 ms。[实测] */
    const val FadeOutMs = 150

    /** 程序化滚动时长：600 ms。[实测] `ValdiScrollView.java:751` `startScroll(..., 600)` */
    const val ScrollMs = 600

    /**
     * 入场位移量。**[实测] 原值是 6（像素级）**。
     *
     * 报告 §7.4 原则 2 说得很明确：位移只有 4~6，不是 dp 级的"从远处飞来"。
     * 官方 bundle 里写的是裸数字 `translateY: -6`，Valdi 里长度单位是点，
     * 所以这里按 6.dp 用 —— 在高密度屏上视觉位移会略大于 6 物理像素，
     * 这与官方"轻轻浮现"的观感一致，真机上如果觉得晃眼就调小到 4.dp。
     */
    val EnterOffsetY = 6.dp

    /** 退场位移量。[实测] `translateY: -4` */
    val ExitOffsetY = (-4).dp

    // ------------------------------------------------------------------
    // easing
    //
    // 官方 bundle 里只给了 duration，没给曲线（Valdi 的 AnimationCurve 是引用）。
    // 这里按 "入场减速、退场加速" 的通常含义挑了两条标准曲线：
    //   入场 LinearOutSlowIn  → 起步快、落地慢，内容"落位"
    //   退场 FastOutLinearIn  → 起步快，不让人等
    // ------------------------------------------------------------------

    fun <T> enterSpec() = tween<T>(durationMillis = EnterMs, easing = LinearOutSlowInEasing)

    fun <T> exitSpec() = tween<T>(durationMillis = ExitMs, easing = FastOutLinearInEasing)

    fun <T> fadeInSpec() = tween<T>(durationMillis = FadeInMs, easing = FastOutSlowInEasing)

    fun <T> fadeOutSpec() = tween<T>(durationMillis = FadeOutMs, easing = FastOutLinearInEasing)

    fun <T> layoutEnterSpec() = tween<T>(durationMillis = LayoutEnterMs)

    fun <T> layoutExitSpec() = tween<T>(durationMillis = LayoutExitMs)

    /**
     * 非流式的布局移动：220 ms 正常缓动。
     * 用于历史消息、以及用户自己发消息那次插入后的整体位移。
     *
     * ★ 流式期间**不要**调用这个，也不要写任何"0 毫秒的版本" ——
     *   流式时应当直接传 `placementSpec = null`。理由见上面 `LayoutMoveMs`
     *   那段注释里的字节码证据：`tween(0)` 仍会 launch 协程，晚一帧；
     *   `null` 才是 `animatePlacementDelta` 的提前 return 分支。
     */
    fun <T> layoutMoveSpec() = tween<T>(durationMillis = LayoutMoveMs)
}
