package com.chatmobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
    /** 点「＋」里的「添加图片」 */
    onPickImage: () -> Unit = {},
    /** 点「＋」里的「添加文件」 */
    onPickFile: () -> Unit = {},
    /** 点「＋」里的「拍照」 */
    onTakePhoto: () -> Unit = {},
    /** 已选中、待随后发出的附件（元数据）。 */
    pendingAttachments: List<com.chatmobile.data.MessageAttachmentMeta> = emptyList(),
    /** 移除第 [index] 个待发附件。 */
    onRemoveAttachment: (Int) -> Unit = {},
) {
    val canSend = value.isNotBlank() && !isGenerating
    var showAttachMenu by remember { mutableStateOf(false) }

    // ★★★ 整块输入区包在 Column 里：附件条在输入行**上方**。
    //   不能把附件条塞进 Row（Row 是横向的），也不该塞进 LazyColumn 的底部
    //   （那样它会随着消息一起被滚走）。
    Column(modifier = modifier.fillMaxWidth()) {
        if (pendingAttachments.isNotEmpty()) {
            PendingAttachmentStrip(
                items = pendingAttachments,
                onRemove = onRemoveAttachment,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            // ★ 底部对齐：多行输入时输入框会向上长高，按钮跟着往下贴住最后一行。
            //   人眼在打字时盯着最后一行，按钮贴底比居中更自然。
            verticalAlignment = Alignment.Bottom,
        ) {
        // ---- 「＋」附件入口（用户要求：输入框左侧）----
        //
        // ★ 位置在**输入框左边、与发送键同高**：一行里左右各一个圆形按钮，
        //   输入框在中间。这是 ChatGPT / 微信的一致形态，用户的肌肉记忆在这儿。
        AttachButton(
            expanded = showAttachMenu,
            onToggle = { showAttachMenu = !showAttachMenu },
            onDismiss = { showAttachMenu = false },
            onPickImage = {
                showAttachMenu = false
                onPickImage()
            },
            onPickFile = {
                showAttachMenu = false
                onPickFile()
            },
            onTakePhoto = {
                showAttachMenu = false
                onTakePhoto()
            },
        )

        Spacer(Modifier.size(ButtonGap))

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
}

/**
 * 待发附件条 —— 输入行**上方**的一排小卡片。
 *
 * ## ★★★ 为什么必须有它（用户报的 bug 的根因之一）
 *
 * 上一版选完附件只往输入框里塞一行「已选图片：xxx.jpg」纯文本。
 * 那行字既不能删、也看不出到底会不会发出去，而且**发送时它会被当成用户说的话**
 * 一起拼进正文 —— 用户看到的和实际发生的完全不是一回事。
 *
 * 现在改成独立的条目：看得见、能删、发送后消失，同时消息气泡上出现标签。
 * **"选好了"这件事必须有一个能被撤销的实体**，否则用户没法确认它存在。
 */
@Composable
private fun PendingAttachmentStrip(
    items: List<com.chatmobile.data.MessageAttachmentMeta>,
    onRemove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // ★ 横向可滚：一次贴五六个文件时不能把输入框挤没了
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, meta ->
            PendingAttachmentChip(meta = meta, onRemove = { onRemove(index) })
        }
    }
}

/** 单个待发附件条目。图片用「图」角标，文本文件用「文」。 */
@Composable
private fun PendingAttachmentChip(
    meta: com.chatmobile.data.MessageAttachmentMeta,
    onRemove: () -> Unit,
) {
    val isImage = meta.kind == com.chatmobile.data.MessageAttachmentMeta.KIND_IMAGE
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isImage) Icons.Filled.Image else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = meta.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                // 长文件名截断：条目宽度不该由文件名长度决定
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            // ★ 删除按钮整块 24dp 可点（L28：Text 的可点区只有排版框）
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除 " + meta.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 「＋」按钮 —— 打开附件菜单。
 *
 * ★ 展开时图标旋转 45° 变成「×」：用户不用读字就知道再点一下会收起。
 *   比"换个图标"好 —— 旋转是连续的动画，视觉上有因果感。
 *
 * ## ★★★ 菜单必须写在**这个 Box 里面**（真机踩过：菜单跑到屏幕中间）
 *
 * `DropdownMenu` 内部是 `Popup`，而 Popup 的锚点是**它被组合进去的那个父布局**的
 * 窗口坐标。上一版为了"不被横向 Row 裁掉"，把它写在了 `Row` 之后、`ChatInputBar`
 * 的最外层 —— 于是锚点变成了整个输入区的父容器 ⇒ 菜单弹在屏幕中部，
 * 跟「＋」按钮没有任何视觉关系。用户原话：「列表还在屏幕中间」。
 *
 * 正确做法：`DropdownMenu` 作为 `Box`（本按钮）的直接子节点。
 * Popup 不参与布局测量，所以它不会撑大按钮、也不会被 `Row` 裁掉 ——
 * 之前那个"会被裁掉"的担心是多余的（Popup 是独立窗口，不受父布局 clip 影响）。
 *
 * ★ 菜单默认向下弹；下方空间不够时 Compose 会自动翻到锚点上方 ——
 *   输入框贴着屏幕底部，所以实际就是要它往上弹，这个翻转是自动的。
 */
@Composable
private fun AttachButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = androidx.compose.animation.core.tween(ChatMotion.EnterMs),
        label = "attachRotate",
    )
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = if (expanded) "收起附件菜单" else "添加附件",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(ButtonIconSize)
                .rotate(rotation),
        )

        // ★★ 菜单是本 Box 的直接子节点 ⇒ 锚点就是「＋」按钮本身。
        //    绝不要把它挪到 Row 外面（那正是上一版弹在屏幕中间的原因）。
        AttachMenu(
            expanded = expanded,
            onDismiss = onDismiss,
            onPickImage = onPickImage,
            onPickFile = onPickFile,
            onTakePhoto = onTakePhoto,
        )
    }
}

/**
 * 附件菜单 —— 三项：添加图片 / 添加文件 / 拍照。
 *
 * ## ★ 为什么用 `DropdownMenu` 而不是底部弹窗
 *
 * 这个菜单跟「＋」按钮是**因果关系**（点它出现）。用 Dropdown 挂在按钮附近，
 * 用户一眼能看出"是这个按钮弹出来的"；换成底部弹窗会遮住半个屏幕，
 * 而里面只有三项 —— 那是杀鸡用牛刀。
 *
 * ★ 但它**必须由 [AttachButton] 在自己的 Box 里调用**，不能从外面挂 ——
 *   Popup 的锚点是直接父布局（见上方 AttachButton 的注释）。
 *
 * ## 三项各自的系统入口（用户指定）
 * | 项 | 入口 |
 * |---|---|
 * | 添加图片 | 相册（`ACTION_PICK_IMAGES`，13+ 用 Photo Picker，无需权限） |
 * | 添加文件 | 系统文件管理器（SAF `ACTION_OPEN_DOCUMENT`） |
 * | 拍照 | 相机（`ACTION_IMAGE_CAPTURE`，结果写我们自己的私有目录） |
 *
 * ★ 「添加文件」用户说要"文件管理器与我们 APP 的私有目录"——
 *   那是一个入口的两半：SAF 打开后用户既能浏览手机目录，
 *   我们的私有目录也在侧栏里（见 ChatHost 的实现）。
 */
@Composable
private fun AttachMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        AttachMenuItem(
            icon = Icons.Filled.Image,
            label = "添加图片",
            hint = "从相册选",
            onClick = onPickImage,
        )
        AttachMenuItem(
            icon = Icons.Filled.InsertDriveFile,
            label = "添加文件",
            hint = "文件管理器 / App 私有目录",
            onClick = onPickFile,
        )
        AttachMenuItem(
            icon = Icons.Filled.PhotoCamera,
            label = "拍照",
            hint = "用相机拍一张",
            onClick = onTakePhoto,
        )
    }
}

@Composable
private fun AttachMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Column {
                Text(label, fontSize = 14.sp)
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
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
