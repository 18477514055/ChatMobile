package com.chatmobile.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 按压反馈（手感清单 #1 + #2）。
 *
 * ## 官方依据
 *
 * `dil_core/src/DILPressFeedback.js` 的符号链（`UI研究-交互手感.md` §1.3 原文）：
 *
 * ```
 * 233381: getAttribute
 * 233394: scaleX   233401: pressedScaleX   233428: originalScaleX
 * 233443: scaleY   233450: pressedScaleY   233464: originalScaleY
 * 233504: isAndroid(onTouchDelayDuration   ← 平台分支：按压缩放只在 Android 走
 * 233221: Device.performHapticFeedback
 * 233250: selection                        ← 震动类型
 * ```
 *
 * ## #1 「先读原始值再乘」
 *
 * 官方先 `getAttribute` 读**当前** `scaleX` 存成 `originalScaleX`，再写 `pressedScaleX`
 * —— 缩放是**相对原始值的乘性动画**，不是"设成 0.96"。
 *
 * 在 Compose 里这个坑的等价形态是 `scale *= 0.96f` 这种累乘
 * （连续点击会一路缩到看不见），或每帧读上一帧动画中间值再乘（会漂移）。
 *
 * 正解：**目标值算成 `baseScale * PressedScale`，`baseScale` 是不可变常量基准**，
 * 绝不读当前动画值 —— 从结构上杜绝漂移。见 [pressScale] 的 `baseScale` 参数。
 *
 * ## #2 「按压反馈与点击回调解耦」
 *
 * 视觉反馈来自 `interactionSource` 的 press 状态（按下即产生），
 * 与 `onClick` 是两条独立路径。报告原文：「『点了没反应』90% 是因为反馈挂在 onClick 上」。
 *
 * ## #3 触感
 *
 * 官方按压缩放每下都伴随 `performHapticFeedback(.selection)`（`233221` + `233250`）。
 * 报告 §6.1：官方默认策略是「能震的控件默认震」，所以这里默认可开。
 */

/**
 * 按下时缩放到的比例。
 * [参考] 官方具体比例未查到（报告 §8 明确列为未查到），0.96 是保守取值。
 */
private const val PressedScale = 0.96f

/**
 * 按压缩放 modifier。
 *
 * @param baseScale 静止态基准缩放，**必须是常量**（通常 1f）。
 *   这个参数的存在就是把 #1 的坑堵死：目标值永远是 `baseScale * PressedScale`，
 *   不读任何上一帧的动画中间值，所以连续快速点击**不会**逐次缩水。
 * @param enabled 是否启用（禁用态不该有按压反馈）
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    baseScale: Float = 1f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()

    // 目标值由「不可变基准 × 常量比例」算出 —— 不依赖当前动画值，故无累乘漂移
    val target = if (pressed && enabled) baseScale * PressedScale else baseScale

    val currentScale by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            // 按下快、回弹稍软 —— 符合"按下即反馈"的即时感
            dampingRatio = if (pressed) Spring.DampingRatioNoBouncy else Spring.DampingRatioMediumBouncy,
            stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )

    this.scale(currentScale)
}

/**
 * 「按下即缩放 + 抬起才判定点击」的组合 modifier —— 手感清单 #1 + #2 的落地形态。
 *
 * ★ #2 的核心：视觉反馈来自 `interactionSource` 的 press 状态（按下那一刻就发生），
 *   `onClick` 是另一条独立路径（clickable 内部从 press 到 release 才触发）。
 *   两者结构上就解耦 —— 不可能出现"点了没反应"。
 *
 * ```kotlin
 * Modifier.pressAndTap { doSomething() }
 * ```
 *
 * @param indication 是否保留 ripple。默认 **null**（关掉）——
 *   缩放已经承担了按压反馈，再叠 ripple 就是双重反馈。
 *   但大块可点区域（如列表行）按官方 §1.6 的做法应该用透明度/ripple 而非缩放。
 */
fun Modifier.pressAndTap(
    enabled: Boolean = true,
    baseScale: Float = 1f,
    hapticOnPress: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    // 按下即触感（官方 DILPressFeedback 的行为），不等到 click
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed && hapticOnPress) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    this
        .pressScale(
            interactionSource = interactionSource,
            baseScale = baseScale,
            enabled = enabled,
        )
        .clickable(
            interactionSource = interactionSource,
            indication = null, // 缩放已承担反馈，不叠 ripple
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 创建一个带「按下即触感」的 interactionSource。
 *
 * 适合要和 `Button` / `IconButton` 等内置控件配合的场景
 * （那些控件的 ripple 由 Material 提供，我们只补触感 + 用 [pressScale] 加缩放）。
 *
 * 触感只在「变为按下」那一次响，按住不放不会重复震。
 */
@Composable
fun rememberPressInteraction(hapticOnPress: Boolean = true): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val pressed by source.collectIsPressedAsState()

    LaunchedEffect(pressed) {
        if (pressed && hapticOnPress) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    return source
}
