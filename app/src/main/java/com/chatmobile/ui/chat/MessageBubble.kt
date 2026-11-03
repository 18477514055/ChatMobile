package com.chatmobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.chatmobile.markdown.MarkdownParser
import com.chatmobile.ui.markdown.MarkdownText
import kotlinx.coroutines.delay

/**
 * 单条消息。
 *
 * ## 视觉区分（对应 Theme.kt 的设计意图）
 *
 * 用户消息**有淡色气泡**（`surfaceVariant`），AI 消息**无底色**，靠排版与留白区分。
 * 官方是"低对比度克制风"，不是强色块。
 *
 * ## 入场动效（报告 §7.1 [实测]）
 *
 * `tween(180)` + `translateY 6 → 0` + `alpha 0 → 1`
 *
 * ★ 位移只有 6dp —— 报告 §7.4 原则 2：「位移极小 + 透明度承担主要信息」。
 * ★ 退场 `tween(120)` + `translateY 0 → -4` —— 原则 1：「出场快于入场」（1 : 0.67）。
 *
 * ## ★★ 为什么需要 `animateEnter` 参数（滚动重播动画的坑）
 *
 * 入场动画的本能写法是：
 * ```kotlin
 * var visible by remember(message.id) { mutableStateOf(false) }
 * LaunchedEffect(message.id) { visible = true }
 * ```
 * **但这个写法是错的** —— 因为 `LazyColumn` 会**回收滚出屏幕的 item 的组合实例**。
 *
 * 用户往上滚看历史消息、再滚回来时：
 * ```
 * item 被回收 → remember 丢失 → visible 重新变成 false
 * → LaunchedEffect 再跑一次 → AnimatedVisibility 再播一遍入场动画
 * ```
 * 结果是**每次滚动都会重播所有消息的入场动画**，非常刺眼。
 *
 * 所以入场动画的「是否播过」必须存在**比 item 生命周期更长**的地方 ——
 * 由调用方（`ChatScreen`）用一个跨 item 存活的 id 集合来记录，通过
 * [animateEnter] / [onAppear] 传进来。
 *
 * > 与 `README §4.4` 的 `reverseLayout` 坑是同一类错误：
 * > **把状态绑在了一个会被回收的生命周期上。**
 *
 * @param animateEnter 是否播放入场动画。`false` = 直接以最终状态出现（滚动回看时）。
 * @param onAppear 首次组合时回调，供调用方把该 id 记为"已播过"。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    animateEnter: Boolean = true,
    onAppear: () -> Unit = {},
    /** 思考过程的展开程度（全局档位，来自设置）。 */
    reasoningDisplay: ReasoningDisplay = ReasoningDisplay.DEFAULT,
    /** 回答结束后是否自动收起思考过程（来自设置）。 */
    reasoningAutoCollapse: Boolean = true,
    /**
     * 用户对**这一条**思考过程的手动覆盖：`true` 展开 / `false` 收起 / `null` 没点过。
     *
     * ★★★ 为什么这个状态必须由调用方持有，而不能 `remember` 在气泡里：
     *
     *   用户报过三次「思考过程收不回去」。前两次我一直改点击处理，
     *   但真因之一是**状态被 LazyColumn 回收掉了**：
     *
     *   ```
     *   消息滚出屏幕 → item 组合实例被销毁 → remember 丢失
     *   → manualOverride 回到 null → 又按全局档位（展开）渲染
     *   ```
     *
     *   表现：单条点收起**当时**是生效的（真机截图确认过标题从
     *   「收起思考过程」变成「思考过程」），但往上一翻、再翻回来，
     *   或者新消息把这条顶出屏幕，它就自己弹开了 —— 用户看到的就是
     *   「收不回去」。
     *
     *   ⇒ 覆盖状态必须存在**生命周期长于 item** 的地方（`ChatScreen` 的 Map，
     *     按 message.id 索引），与本文件顶部 `hasAnimated` 那个坑是同一类错误。
     */
    reasoningOverride: Boolean? = null,
    onToggleReasoning: (Boolean) -> Unit = {},
    /** 正文与列表的正文字号（sp，来自设置）。 */
    bodyFontSize: Int = 16,
) {
    val isUser = message.role == ChatMessage.Role.User
    val density = LocalDensity.current

    // 位移在组合期换算成像素 —— AnimatedVisibility 的 offset lambda 拿不到 Density
    val enterOffsetPx = with(density) { ChatMotion.EnterOffsetY.roundToPx() }
    val exitOffsetPx = with(density) { ChatMotion.ExitOffsetY.roundToPx() }

    // 首次出现：隐藏 → 置为可见，触发一次入场过渡。
    // ★ 注意这里**不能**用 early-return 跳过后面（@Composable 里 early return 会让
    //   条件分支各自成为一个独立的组合节点，状态与重组行为都不可预测）。
    //   正确做法是把"要不要包 AnimatedVisibility"作为唯一的分支点。
    var visible by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        visible = true
        onAppear()
    }

    if (!animateEnter) {
        // ★ 滚动回看：直接出最终态，不包 AnimatedVisibility，从根上避免重播
        Box(modifier = modifier) {
            if (isUser) {
                UserBubble(message, bodyFontSize)
            } else {
                AssistantMessage(
                    message = message,
                    reasoningDisplay = reasoningDisplay,
                    reasoningAutoCollapse = reasoningAutoCollapse,
                    reasoningOverride = reasoningOverride,
                    onToggleReasoning = onToggleReasoning,
                    bodyFontSize = bodyFontSize,
                )
            }
        }
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(ChatMotion.fadeInSpec()) +
                slideInVertically(
                    animationSpec = ChatMotion.enterSpec(),
                    initialOffsetY = { enterOffsetPx },
                ),
            exit = fadeOut(ChatMotion.fadeOutSpec()) +
                slideOutVertically(
                    animationSpec = ChatMotion.exitSpec(),
                    targetOffsetY = { exitOffsetPx },
                ),
            modifier = modifier,
        ) {
            if (isUser) {
                UserBubble(message, bodyFontSize)
            } else {
                AssistantMessage(
                    message = message,
                    reasoningDisplay = reasoningDisplay,
                    reasoningAutoCollapse = reasoningAutoCollapse,
                    reasoningOverride = reasoningOverride,
                    onToggleReasoning = onToggleReasoning,
                    bodyFontSize = bodyFontSize,
                )
            }
        }
    }
}

/**
 * 用户气泡：右对齐、淡灰底、圆角 20dp。
 *
 * 底色用 `surfaceVariant`（Theme.kt：亮色 #F4F4F4 / 暗色 #2A2A2A），
 * 与背景的明度差极小 —— 官方"克制"的做法。
 */
@Composable
private fun UserBubble(message: ChatMessage, bodyFontSize: Int = 16) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        // ★ 用户消息也走 Markdown 渲染 —— 用户经常粘贴代码 / 列表 / 链接。
        //   但**不渲染标题**那种大字号：用户气泡里的 `#` 更可能是手滑，
        //   保持和 AI 消息同一套解析即可（视觉上标题会被字号差区分开）。
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MarkdownText(
                blocks = remember(message.text) { MarkdownParser.parse(message.text) },
                textColor = MaterialTheme.colorScheme.onSurface,
                bodySize = bodyFontSize,
            )
        }
    }
}

/**
 * AI 消息：左对齐、**无底色**、靠排版区分。
 *
 * 流式中在文本末尾挂独立闪烁光标 —— 官方 `streamingCursor` + `rule:"streaming-cursor-"`
 * （报告 §3.3）。光标必须是**独立元素**：往文本里塞字符会改变测量，
 * 导致整段文字每帧重排、抖动。
 */
@Composable
private fun AssistantMessage(
    message: ChatMessage,
    reasoningDisplay: ReasoningDisplay = ReasoningDisplay.DEFAULT,
    reasoningAutoCollapse: Boolean = true,
    reasoningOverride: Boolean? = null,
    onToggleReasoning: (Boolean) -> Unit = {},
    bodyFontSize: Int = 16,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        // 思维链：默认折叠，不抢主线内容
        if (!message.reasoning.isNullOrBlank()) {
            ReasoningBlock(
                reasoning = message.reasoning,
                display = reasoningDisplay,
                autoCollapse = reasoningAutoCollapse,
                isStreaming = message.isStreaming,
                manualOverride = reasoningOverride,
                onToggle = onToggleReasoning,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            // ★★★ AI 消息走 Markdown 渲染。
            //
            //   用户原话：「为我们的客户端添加 markdown 等格式的渲染——
            //   这些费纯文本格式 ai 经常会输出」。
            //
            //   ★ 关键：**流式期间也走同一个解析器**。
            //     解析器对不完整语法（只开没闭的 `**`、只写了两撇的 ```）
            //     一律降级成字面量，所以流式过程中样式不会乱跳 ——
            //     只是"多了两个星号然后消失"。
            //
            //   ★ `remember(message.text)`：每来一个 token 都会重组，
            //     文本一变就要重新解析（无法避免），但文本没变时不能重复解析
            //     （比如滚动导致的重组）。
            Box(modifier = Modifier.weight(1f, fill = false)) {
                MarkdownText(
                    blocks = remember(message.text) { MarkdownParser.parse(message.text) },
                    textColor = MaterialTheme.colorScheme.onSurface,
                    bodySize = bodyFontSize,
                )
            }
            if (message.isStreaming) {
                StreamingCursor()
            }
        }
    }
}

/**
 * 折叠的思维链块 —— 支持 DSH 式的四档展开程度（见 [ReasoningDisplay]）。
 *
 * ## ★ 三处与旧版的差别（都是用户报的问题）
 *
 * 1. **档位是全局的，不是每条各自记**
 *    旧版每条一个 `expanded` 布尔 ⇒ 一屏里半开半合，翻历史要逐条点。
 *    现在档位来自设置，整屏一致。
 * 2. **收起态只给一行摘要，不截半句**
 *    旧版收起时连内容都不显示但标题写"思考过程"，用户不知道里面有多少。
 *    现在收起态写明「思考过程 · N 字」，让人知道值不值得点。
 * 3. **「简洁」档显示行数 + 「还有 N 行」**
 *    截断必须**说清被截了**，否则用户会以为那就是全部内容。
 *
 * ★ 触感：选择变更给 selection 反馈（清单 #3），与官方 CoreToggle 一致。
 */
@Composable
private fun ReasoningBlock(
    reasoning: String,
    display: ReasoningDisplay = ReasoningDisplay.DEFAULT,
    autoCollapse: Boolean = true,
    isStreaming: Boolean = false,
    manualOverride: Boolean? = null,
    onToggle: (Boolean) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current

    // ★★★ 档位由**纯函数**合成，不再在这里散着判。
    //   三份输入（全局档位 / 自动收起 / 手动覆盖）任何一份缺失，
    //   表现都一模一样（点了没反应）—— 所以合成逻辑必须能离线单测。
    val effective: ReasoningDisplay = effectiveReasoningDisplay(
        display = display,
        autoCollapse = autoCollapse,
        isStreaming = isStreaming,
        manualOverride = manualOverride,
    )

    val (shown, clipped) = clipReasoning(reasoning, effective)
    val remaining = remainingReasoningLines(reasoning, effective)
    val totalChars = reasoning.length

    // 收起态：只有一行标题，不带内容
    val isCollapsed = effective == ReasoningDisplay.Collapsed

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        // ★★★ 点击区域做成**整行**，不是只有那几个字。
        //
        //   用户报了三次「收起不了 / 点不动」。除了状态被回收那两层原因，
        //   还有一个纯几何的原因：这一行只有 labelMedium 那么高（约 20dp），
        //   而可点区域原来就是 `Text` 自己的排版框 —— 手指点偏 2dp 就落空。
        //
        //   ⇒ 用一个撑满宽度的 Row 承载点击，纵向留 8dp 内边距：
        //     实际热区约 36dp 高、通栏宽，单手拇指点得中。
        //
        //   ★ 顺带解决另一个问题：`uiautomator dump` 里这个标题**根本没出现**，
        //     因为原来的点击挂在 Text 上、没有语义节点 ⇒ 自动化取不到 bounds。
        //     现在包一层 clickable + onClickLabel，无障碍与自动化都能看见它。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    // ★ onClickLabel 让无障碍读屏知道"点了会发生什么"
                    onClickLabel = if (isCollapsed) "展开思考过程" else "收起思考过程",
                    onClick = {
                        // 把"接下来该是什么状态"交给调用方 —— 覆盖状态存在
                        // ChatScreen 的 Map 里，滚出屏幕也不会丢。
                        onToggle(isCollapsed)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(if (isCollapsed) "思考过程" else "收起思考过程")
                    append(" · ")
                    append(totalChars)
                    append(" 字")
                    if (clipped) {
                        append("（显示前 ")
                        append(ReasoningDisplay.COMPACT_LINES)
                        append(" 行）")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ★ 收起态给一个明确的展开提示 —— 只有文字没有箭头时，
            //   用户不知道这行能点（这也是"以为收不起来"的一部分原因）。
            if (isCollapsed) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = fadeIn(ChatMotion.fadeInSpec()),
            exit = fadeOut(ChatMotion.fadeOutSpec()),
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                Text(
                    text = shown,
                    // ★ 「完全展开」用等宽字体：推理里常有缩进/列表/代码片段，
                    //   比例字体下会错位。DSH 的"完全展开"也是这个意图。
                    style = if (effective == ReasoningDisplay.Expanded) {
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remaining > 0) {
                    Text(
                        text = "…还有 $remaining 行，点标题展开全部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 流式光标：缓慢呼吸的竖条。 */
@Composable
private fun StreamingCursor() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            on = !on
        }
    }
    val cursorAlpha by animateFloatAsState(
        targetValue = if (on) 1f else 0.15f,
        animationSpec = tween(530),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(cursorAlpha),
    )
}
