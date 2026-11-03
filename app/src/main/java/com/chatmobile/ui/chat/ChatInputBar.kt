package com.chatmobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box as LayoutBox

/**
 * 聊天输入区。
 *
 * ## 多行自动增高（清单 #9 的简化落地）
 *
 * 报告 §5.1 说官方 `textInputHeight` 是 `setAttributeFunction`（每帧由内容推导），
 * 比 `onValueChange -> 改 maxHeight` 少一次重组。
 *
 * Compose 里对应的做法是：**不要用 state 存高度**，而是给 `BasicTextField` 一个
 * `heightIn(min, max)`，高度由内部文本测量自然推导。这样高度是"计算属性"，
 * 我们没有在任何 `onValueChange` 里写高度逻辑 —— 符合清单 #9 的精神。
 *
 * `heightIn(max = 160.dp)` 是增长上限，超过就内部滚动。
 *
 * ## 发送 / 停止同位置切换（清单 #8）
 *
 * 报告 §7 清单第 8 条：
 * > 生成中把发送键换成停止键（**同 slot**）；空文本时 `disabled` 且 disabledStyle 明显。
 *
 * 实现要点：**必须是同一个 Box 里的同一个位置**，只是内容 Crossfade 切换。
 * 如果写成两个并排的 Composable 各占一半，按钮就会跳位 —— 那正是清单要避免的。
 * 「按钮跳位会让人找不到」是官方说明的理由。
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = value.isNotBlank() && !isGenerating

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        // ★ 底部对齐：多行输入时输入框会向上长高，按钮跟着往下贴住最后一行。
        //   人眼在打字时盯着最后一行，按钮贴底比居中更自然。
        verticalAlignment = Alignment.Bottom,
    ) {
        // ---- 输入框 ----
        LayoutBox(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(InputCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = InputVerticalPadding),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "发消息…",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = InputTextSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = InputTextSize,
                    // ★ 行高固定：这是「单行高度可预测」的前提。
                    //   不锁行高时，不同字体/不同缩放下 measured 高度会飘，
                    //   按钮就算写死 48dp 也可能对不齐。
                    lineHeight = InputLineHeight,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // 高度仍由内容推导（不存 state，见清单 #9），但 min 锁到单行高度
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SingleLineHeight, max = 160.dp),
                // ★ 回车上屏即发送（ChatGPT 手感）。
                //
                //   之前是 ImeAction.Default ⇒ 真机按回车只插入换行、不发送，
                //   而用户在 ChatGPT 里的肌肉记忆是"打完回车发出去"。
                //
                //   ImeAction.Send + KeyboardActions(onSend) 把 IME 的发送键接到同一个
                //   onSend 回调 —— 和右侧按钮走**完全相同的提交路径**，
                //   避免"键盘能发、按钮不能发"这类分叉。
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        // 只有能发的时候才发（空文本 / 生成中都不发）
                        if (value.isNotBlank() && !isGenerating) onSend()
                    },
                ),
            )
        }

        Spacer(Modifier.size(ButtonGap))

        // ★★ 发送 / 停止：同一个 slot，只 Crossfade 内容，位置绝不动
        SendStopButton(
            isGenerating = isGenerating,
            canSend = canSend,
            onSend = onSend,
            onStop = onStop,
        )
    }
}

// ---------------------------------------------------------------------------
// 输入区尺寸
//
// ★ 这几个常量是**一个整体**，不能单独调其中一个 —— 它们必须满足：
//
//      SingleLineHeight + InputVerticalPadding * 2 = ButtonSize
//
//   即「输入框单行总高 == 按钮直径」。改任何一个都要同步改另一个，
//   否则按钮又会"比输入框小一圈"（这是本次优化的起因）。
// ---------------------------------------------------------------------------

/** 输入文字字号。24sp 是 ChatGPT 输入区的观感量级。 */
private val InputTextSize = 17.sp

/** 输入行高。锁死它，单行高度才可预测。 */
private val InputLineHeight = 24.sp

/** 单行文本高度（= 行高）。与内边距相加得到输入框单行总高。 */
private val SingleLineHeight = 24.dp

/** 输入框上下内边距。 */
private val InputVerticalPadding = 12.dp

/**
 * 发送按钮直径 = 单行高度 + 上下内边距 × 2 = 24 + 12 + 12 = **48dp**。
 *
 * ★ 与输入框单行总高**完全相等** —— 这是本次"按钮比输入框小一圈"的修法。
 *   之前按钮 40dp vs 输入框 48dp，所以视觉上陷进去一块。
 */
private val ButtonSize = 48.dp

/**
 * 按钮图标尺寸。24dp。
 *
 * ★ 关键：图标必须**跟着按钮一起放大**（20 → 24）。
 *   如果只放大按钮不放大图标，会变成"中间空、外圈厚"的甜甜圈感 ——
 *   这是比原来更难看的结果。
 *   24 / 48 = 0.5，与常见的「图标占容器一半」比例一致。
 */
private val ButtonIconSize = 24.dp

/** 输入框与按钮间距。保持原值。 */
private val ButtonGap = 8.dp

/** 输入框圆角。与按钮完全同高时，用按钮半径（24）看起来最整。 */
private val InputCornerRadius = 24.dp

/**
 * 发送 ⇄ 停止 同位置切换按钮（清单 #8）。
 *
 * 直径固定 [ButtonSize]（48dp，等于输入框单行总高）、位置固定 ——
 * 只切换图标与语义，**不允许跳位**。
 *
 * ★ 两个易错点：
 *   1. **图标要跟着按钮放大**（[ButtonIconSize] = 24dp）。
 *      只放大按钮不放大图标 ⇒ 中间空、外圈厚的甜甜圈感。
 *   2. **多行输入时按钮不能跟着长高**。这里用固定 `.size()`，
 *      Row 的 `Alignment.Bottom` 让它在输入框长高时贴底而不拉伸。
 */
@Composable
private fun SendStopButton(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    // 生成中 = 停止键：总是可用（必须能停下来）
    // 非生成中 = 发送键：空文本禁用
    val enabled = if (isGenerating) true else canSend

    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.background
    }

    Box(
        modifier = Modifier
            // ★ 固定尺寸，**不随输入框长高**。
            //   如果这里写成 fillMaxHeight() 或让 Row 的 alignItems 拉伸它，
            //   多行输入时按钮会变成长条 —— 那比"小一圈"更难看。
            //   Row 用的是 Alignment.Bottom，所以多行时按钮仍然贴底、保持圆形。
            .size(ButtonSize)
            .clip(CircleShape)
            .background(bg)
            .pressAndTap(
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (isGenerating) onStop() else onSend()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = isGenerating,
            animationSpec = androidx.compose.animation.core.tween(ChatMotion.FadeOutMs),
            label = "sendStop",
        ) { generating ->
            if (generating) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "停止生成",
                    tint = fg,
                    // ★ 图标跟着按钮一起放大（20 → 24），否则中间空、外圈厚
                    modifier = Modifier.size(ButtonIconSize),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "发送",
                    tint = fg,
                    modifier = Modifier.size(ButtonIconSize),
                )
            }
        }
    }
}
