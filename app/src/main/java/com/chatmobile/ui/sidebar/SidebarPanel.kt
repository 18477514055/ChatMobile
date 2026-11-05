package com.chatmobile.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chatmobile.ui.chat.Conversation
import com.chatmobile.ui.chat.pressAndTap

/**
 * 侧边栏面板内容：新建对话 / 会话列表 / 设置入口。
 *
 * ## 手感说明
 *
 * 会话行是**大块可点区域** —— 按官方 §1.6 的取舍（`UI研究-交互手感.md`）：
 * 「大块可点区域用 opacity，小图标用 scale/ripple」。所以这里行内用 ripple
 * （`indication = null` + 自己叠加的选中底色），不用缩放，避免整行"晃"。
 *
 * 选中/新建/设置这类操作给触感（清单 #3：官方 `CoreToggle` 用 selection）。
 */
@Composable
fun SidebarPanel(
    conversations: List<Conversation>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    /**
     * 点"设置"。
     *
     * ★ 默认值是 `{}` 而不是必填：这样已有的调用点（Demo 页、测试）不用改，
     *   也不会因为漏传参数而编译失败。真正的接线在 ChatHost 里。
     */
    onOpenSettings: () -> Unit = {},
    /**
     * 长按菜单：置顶 / 取消置顶。参数是会话 id 与「目标状态」。
     *
     * ★ 传「目标状态」而不是「切换」：调用方拿到 false 就写 false，
     *   不需要再回读一次当前值。避免"快速连点两下"时状态翻不回来。
     */
    onTogglePin: (id: String, pinned: Boolean) -> Unit = { _, _ -> },
    /** 长按菜单：删除。★ 界面层只负责问用户，真正删由调用方做（要动库 + 刷列表）。 */
    onDeleteConversation: (id: String) -> Unit = {},
    /** 长按菜单：重命名。调用方负责写库与刷新。 */
    onRenameConversation: (id: String, newTitle: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    // ★ 长按菜单当前针对哪一条。null = 没开。
    //   放在这里而不是每一行内部：Popup 需要知道锚点在哪一行，
    //   而且同一时刻只该有一个菜单（每行各存一份会出现"两个菜单同时开着"）。
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    // 删除二次确认的目标
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    // 重命名对话框的目标（null = 没开）
    var renaming by remember { mutableStateOf<Conversation?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        // ---- 新建对话 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .pressAndTap(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNewChat()
                    },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "新建对话",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        // ---- 会话列表 ----
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == selectedId,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(conversation.id)
                    },
                    onLongClick = {
                        // ★ 长按给**不同**的触感（LongPress）而不是 selection：
                        //   用户要能凭手感区分"我点了一下"和"我按住触发了菜单"。
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuFor = conversation
                    },
                )
            }
        }

        // ---- 底部设置 ----
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .pressAndTap(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenSettings()
                    },
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    // ---- 长按菜单 ----
    //
    // ★ 用**居中 Dialog** 而不是 Popup 锚在行上：
    //   侧边栏宽度只有 88%，行又矮，Popup 在边缘行会被顶出屏幕
    //   （真机上表现是"菜单弹到屏幕外，只看到一条边"）。
    //   居中弹窗在屏幕任何位置都稳定可见，且与项目里其它菜单（快捷面板）形态一致。
    menuFor?.let { target ->
        ConversationActionDialog(
            title = target.title,
            pinned = target.pinned,
            onDismiss = { menuFor = null },
            onTogglePin = { wantPinned ->
                menuFor = null
                onTogglePin(target.id, wantPinned)
            },
            onRequestDelete = {
                menuFor = null
                pendingDelete = target
            },
            onRequestRename = {
                menuFor = null
                renaming = target
            },
        )
    }

    // ---- 重命名对话框 ----
    //
    // ★ 输入框持本地草稿（L12）：直接绑库里的值，每次按键都和异步回写打架，字符会被吞。
    // ★ 自动聚焦 + 全选：用户点重命名的意图就是"改掉它"，
    //   让他还得先手动删掉原文字是多余的。（全选用 TextFieldValue，见下）
    renaming?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renaming = null },
            onConfirm = { newTitle ->
                renaming = null
                val clean = newTitle.trim()
                // ★ 空标题直接忽略，不写库 —— 侧边栏不允许出现空白行，
                //   而且 repo.renameConversation 本身也会拒绝空白（返回 false）。
                if (clean.isNotEmpty() && clean != target.title) {
                    onRenameConversation(target.id, clean)
                }
            },
        )
    }

    // ---- 删除二次确认 ----
    //
    // ★ 删除**必须**二次确认，且要说清"消息也一起没了"。
    //   侧边栏一行那么小，长按菜单里的删除很容易误触；
    //   而会话删除是**不可撤销**的（消息随外键 CASCADE 一起删）。
    pendingDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除对话？") },
            text = {
                Text(
                    "「${target.title}」以及其中的全部消息会被永久删除，无法恢复。" +
                        "这个操作不会影响你的记忆库。",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteConversation(target.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 长按会话弹出的操作菜单。
 *
 * ★ 为什么把「置顶」的那一行文案做成动态的（置顶 / 取消置顶）：
 *   菜单只有两项，用户扫一眼就要知道会发生什么。写死"置顶"的话，
 *   已置顶的会话再点一次会变成取消置顶 —— 与菜单文字不符。
 */
@Composable
private fun ConversationActionDialog(
    title: String,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    onRequestRename: () -> Unit = {},
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ActionRow(
                    text = if (pinned) "取消置顶" else "置顶",
                    tint = MaterialTheme.colorScheme.onSurface,
                ) { onTogglePin(!pinned) }

                // ★ 重命名放在删除**之前**：删除是破坏性操作，按惯例放最后，
                //   而且两者相邻时更该让"安全的那一个"离手指更远。
                ActionRow(
                    text = "重命名",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRequestRename,
                )

                ActionRow(
                    text = "删除对话",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onRequestDelete,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = tint,
        // ★★★ 顺序：`padding` 必须在 `clickable` **之前**。
        //
        //   反过来写（先 clickable 再 padding）时，padding 落在点击区**外面** ——
        //   视觉上这一行有 14dp 上下留白，那 14dp 却是死区，点上去没反应。
        //   真机上就撞到了：点「置顶」菜单关了、库里的 `pinned` 仍是 0。
        //
        //   这是本项目**第三次**踩同一个几何坑（前两次：思考过程标题、
        //   会话行的点击区）。Compose 的规则是：`clickable` 只吃它**之前**
        //   已经生效的尺寸修饰符。
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .clickable(onClick = onClick),
    )
}

/**
 * 重命名对话框。
 *
 * ## ★ 三个细节都是为"少一步操作"服务的
 *
 * 1. **自动聚焦 + 自动全选** —— 用户点「重命名」的意图就是"改掉它"，
 *    还让他先长按删原文是多余的。全选用 [TextFieldValue]，因为
 *    `String` 版 `OutlinedTextField` 无法表达选区。
 * 2. **本地草稿** —— 输入即落盘的 Compose 输入框必须持本地草稿（L12）：
 *    直接渲染外部值会和异步回写打架，字符被吞。
 * 3. **空输入时禁用「保存」** —— 侧边栏不允许出现空白行。禁用比"点了没反应"好：
 *    后者让用户以为坏了。
 */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initial,
                // 全选：光标在末尾、选区覆盖全部
                selection = androidx.compose.ui.text.TextRange(0, initial.length),
            ),
        )
    }
    val canSave = value.text.isNotBlank()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // 对话框一出现就聚焦到输入框 —— 用户意图是"改它"，不该还要先点一下
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    androidx.compose.material3.AlertDialog(
        // ★★★ AlertDialog 是独立窗口，外层页面的 imePadding 管不到它。
        //   这个框还会**自动聚焦并弹出键盘**（见上面的 requestFocus），
        //   不给它 ime 内边距的话，输入框连同「保存/取消」会被键盘压在下面 ——
        //   用户只能盲打，然后看不见保存按钮。
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("重命名对话") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = {
                    // ★ 限长 60：侧边栏只显示一行，再长纯属浪费；
                    //   但别限太死（20）—— 用户可能想写"A 项目：XXX 的调试记录"。
                    if (it.text.length <= 60) value = it
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(value.text) },
                enabled = canSave,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 单条会话行。
 *
 * 大块可点区域 → 用选中底色作反馈，不做缩放（官方 §1.6）。
 */
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            // ★ 长按用 `combinedClickable`，不是再套一个 pointerInput。
            //   `pressAndTap` 内部是普通 `clickable`，两者叠在一起会抢事件
            //   （长按触发时点击也照发，于是"长按弹出菜单的同时顺手切了会话"）。
            //   combinedClickable 天生就是「点击 + 长按」二选一。
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                // 无障碍：读屏用户也需要知道这里能长按
                onLongClickLabel = "打开会话操作菜单",
                // ★ 大块可点区域按官方 §1.6 用透明度/ripple 作反馈，不做缩放。
                //   这里保留默认 indication（涟漪）—— 长按触发菜单时，
                //   有涟漪才看得出"我按到了"。
                onClickLabel = "打开这个对话",
            )
            // ★ padding 在 combinedClickable **之后**：这里是有意的 ——
            //   要让 12dp/11dp 的内边距也算进点击区（整行可点）。
            //   注意这与 ActionRow 那条相反，差别在于**要不要把 padding 算进热区**：
            //     · 会话行：要（整行可点，padding 属于可点范围）
            //     · 菜单项：也要，但那次写反了导致 padding 跑到热区外面
            //   正确的判断是看 `clickable` 前面有没有把尺寸撑起来。
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★ 置顶标记：一个小图钉 + 左侧固定宽度占位。
        //   不用"置顶的排到上面就够了"来省这一笔 —— 列表一长，用户滑到中间
        //   就分不清哪些是置顶的、哪些只是最近用过的。
        if (conversation.pinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "已置顶",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(13.dp)
                    .padding(end = 0.dp),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = conversation.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
