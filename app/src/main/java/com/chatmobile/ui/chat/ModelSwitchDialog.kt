package com.chatmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chatmobile.workspace.WorkspacePermission

/**
 * 一个可切换的模型条目（弹窗里的一行）。
 *
 * ★ 刻意只带界面需要的字段，不引用 `settings.ModelProfile` ——
 *   弹窗是纯展示组件，不该知道配置是怎么存的。
 */
data class ModelOption(
    val profileId: String,
    /** 显示名，如「DeepSeek · deepseek-v4-pro」 */
    val title: String,
    /** 提供商显示名，如「DeepSeek」，用于分组 */
    val providerId: String,
    val providerName: String,
    /** 当前是否正在使用 */
    val active: Boolean,
    /**
     * 该模型支持的思考强度档位。
     *
     * ★ **来自上游 `/models` 的声明**，不是我们编的。
     *   空 = 上游没说 ⇒ 不显示强度选择（**不知道比猜错好**：
     *   给一个厂商不认的档位只会让用户 400）。
     */
    val effortLevels: List<String> = emptyList(),
    /**
     * 上游声明的上下文窗口（token）。null = 上游没说。
     *
     * ★★★ **这只是厂家的默认声明值，不是真正的上限。**
     *   用户明确要求：「上下文上限应该允许用户任意调整，调整到厂家允许的真正上限。
     *   不要按照默认上限，那个只是厂家默认，不是真正的上限。」
     *   ⇒ 它只用作**输入框的提示**（"上游声明 200000"），
     *     真正的取值来自用户自己填的 [contextLimitInput]，**不做任何硬性钳制**。
     */
    val contextLimitHint: Int? = null,
    /** 用户实际设置的上下文上限（空 = 还没设，回退到默认预算） */
    val contextLimitInput: String = "",
)

/**
 * 弹窗的**一级三个入口**。
 *
 * ## ★★★ 为什么把「模型 / 权限 / 工作区」放进同一个弹窗
 *
 * 用户原话：
 * > 「把整个当前内容统一收入『模型』，然后该弹窗第一层的第二个是『权限』，
 * >  第三个是『工作区』，支持每个对话随时切换工作区并在下一轮对话生效」
 *
 * 这三者**都是"这一轮对话怎么跑"的参数**，而不是"App 怎么配"：
 * 换模型、放开权限、换个目录 —— 用户都在**同一个心智上下文**里做决定。
 * 把它们拆到设置页意味着：想换个模型还要退出对话走三层导航，回来时已经忘了要问什么。
 *
 * ★ 而且这三个都有"随时切换、下一轮生效"的共同语义：
 * 改完**不需要重开对话**，下一条消息就按新设置走。
 */
enum class QuickSheetTab(val label: String) {
    Model("模型"),
    Permission("权限"),
    Workspace("工作区"),
}

/**
 * 居中快捷面板 —— **一级三项**：模型 / 权限 / 工作区。
 *
 * ## 用户要求（逐字，两条）
 * 1. 「点开之后出一个弹窗，要出现在屏幕正中间，然后列出所有的已经添加的模型，
 *    按提供方进行分类筛选（添加这个分类标签），弹窗上面也加一个搜索框，
 *    但是**搜索框不要抢焦点，呼出键盘，用户不点就不用弹键盘**。」
 * 2. 「把整个当前内容统一收入『模型』，然后该弹窗第一层的第二个是『权限』，
 *    第三个是『工作区』，支持每个对话随时切换工作区并在下一轮对话生效」
 *
 * ## 结构
 * ```
 * ┌─ 模型 ─┬─ 权限 ─┬─ 工作区 ─┐   ← 一级：三个 Tab，默认落在「模型」
 * │  搜索 / 分类 / 列表          │
 * │  思考强度 / 上下文上限        │   ← 原来是平铺的，现在收进「模型」页
 * └──────────────────────────┘
 * ```
 *
 * ## 四个关键实现点
 *
 * **① 居中**：用 `Dialog` 而不是 `ModalBottomSheet`。`Dialog` 默认居中，
 *    而且自动处理返回键与点击外部关闭。
 *
 * **② 搜索框不抢焦点** —— 最容易做错的一点。常规 `TextField` 一进组合就自动获得焦点，
 *    键盘立刻弹起，把弹窗顶得只剩半屏。做法：
 *      - 用 `BasicTextField`（比 `OutlinedTextField` 更不容易抢焦点）
 *      - **不调** `.focusRequester().requestFocus()`
 *      - Dialog 加 `DialogProperties(decorFitsSystemWindows = false)`
 *
 * **③ 切 Tab 不重置搜索词**：用户搜到一半去看权限再回来，搜索词还在。
 *    这是"顺手"的细节 —— 每次切 Tab 都清空会让人恼火。
 *
 * **④ 权限与工作区页都有"当前值"**：面板必须一眼看出**现在是什么状态**，
 *    否则用户看到一排选项不知道点了会变成什么。
 */
@Composable
fun QuickSwitchSheet(
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 当前生效的思考强度（空 = 不传） */
    currentEffort: String = "",
    onEffortChange: (String) -> Unit = {},
    onContextLimitChange: (String) -> Unit = {},
    // ---- 权限 ----
    /** 当前对话的权限档位 */
    currentPermission: WorkspacePermission = WorkspacePermission.DEFAULT,
    onPermissionChange: (WorkspacePermission) -> Unit = {},
    // ---- 工作区 ----
    /** 可选的工作区列表 */
    workspaces: List<WorkspaceOption> = emptyList(),
    /** 当前对话绑定的工作区 id；null = 没有工作区 */
    currentWorkspaceId: String? = null,
    onWorkspaceChange: (String?) -> Unit = {},
    /** 新建工作区（打开 SAF 选择器等） */
    onCreateWorkspace: () -> Unit = {},
    /** 打开完整的工作区管理页 */
    onManageWorkspaces: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(QuickSheetTab.Model) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            // ★★★ 不能用 tonalElevation（哪怕 3~6dp）：
            //   Material3 的 tonal elevation 会往 surface 叠加 primary 色，
            //   浅色主题下把弹窗底色染成淡绿，配浅灰说明文字就看不清了。
            //   实测用户反馈：「那个弹窗新增的文字怎么是浅色的啊，看不清」。
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                // ★★★ 弹窗里有搜索框，键盘弹起时必须把整个弹窗顶上去。
                //
                //   这个 Dialog 设了 `decorFitsSystemWindows = false`（为了让内容
                //   铺到状态栏下方、居中的观感才对），代价是**它不再自动避让 IME**——
                //   而 Material 的 ModalBottomSheet 会自动避让，所以这算是个反向陷阱。
                //   不加这一行，真机上点搜索框 → 弹窗下半部分被键盘盖住，
                //   连"上下文上限"那个输入框都点不到。
                .imePadding()
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ---- 一级三 Tab ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    QuickSheetTab.entries.forEach { t ->
                        val selected = tab == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.surface
                                    else Color.Transparent,
                                )
                                .clickable { tab = t },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = t.label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (tab) {
                    QuickSheetTab.Model -> ModelPane(
                        options = options,
                        onSelect = onSelect,
                        currentEffort = currentEffort,
                        onEffortChange = onEffortChange,
                        onContextLimitChange = onContextLimitChange,
                    )

                    QuickSheetTab.Permission -> PermissionPane(
                        current = currentPermission,
                        onPick = onPermissionChange,
                    )

                    QuickSheetTab.Workspace -> WorkspacePane(
                        workspaces = workspaces,
                        currentId = currentWorkspaceId,
                        onPick = onWorkspaceChange,
                        onCreate = onCreateWorkspace,
                        onManage = onManageWorkspaces,
                    )
                }
            }
        }
    }
}

// =====================================================================
// 模型页（原弹窗的全部内容，原样搬过来）
// =====================================================================

@Composable
private fun ModelPane(
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    currentEffort: String,
    onEffortChange: (String) -> Unit,
    onContextLimitChange: (String) -> Unit,
) {
    // 搜索关键词
    var query by remember { mutableStateOf("") }
    // 当前筛选的提供方；null = 全部
    var filterProvider by remember { mutableStateOf<String?>(null) }

    // 按提供方分组（保持首次出现顺序，不排序 —— 直觉上"常用的在前面"）
    val providers = remember(options) {
        options.map { it.providerId to it.providerName }.distinct()
    }

    val visible = remember(options, query, filterProvider) {
        options.filter { o ->
            val byProvider = filterProvider == null || o.providerId == filterProvider
            val byQuery = query.isBlank() ||
                o.title.contains(query, ignoreCase = true) ||
                o.providerName.contains(query, ignoreCase = true)
            byProvider && byQuery
        }
    }

    Column {
        // ---- 搜索框（不抢焦点）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索模型",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ★ BasicTextField 不会自动请求焦点 —— 这是不弹键盘的关键
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 提供方分类 chip ----
        if (providers.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderChip(
                    text = "全部",
                    selected = filterProvider == null,
                    onClick = { filterProvider = null },
                )
                providers.forEach { (id, name) ->
                    ProviderChip(
                        text = name,
                        selected = filterProvider == id,
                        onClick = {
                            // 再点一次同一个 = 取消筛选
                            filterProvider = if (filterProvider == id) null else id
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 模型列表 ----
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (options.isEmpty()) "还没有配置模型" else "没有匹配的模型",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visible, key = { it.profileId }) { o ->
                    ModelOptionRow(o) { onSelect(o.profileId) }
                }
            }
        }

        // ---- 思考强度 / 上下文上限 ----
        val current = options.firstOrNull { it.active }
        if (current != null) {
            var ctxDraft by remember(current.profileId) {
                mutableStateOf(current.contextLimitInput)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            val levels = current.effortLevels
            if (levels.isNotEmpty()) {
                Text(
                    text = "思考强度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 「默认」= 不传该字段，让服务端自己决定
                    ProviderChip(
                        text = "默认",
                        selected = currentEffort.isBlank(),
                        onClick = { onEffortChange("") },
                    )
                    levels.forEach { lv ->
                        ProviderChip(
                            text = lv,
                            selected = currentEffort == lv,
                            onClick = { onEffortChange(lv) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "档位由该模型的上游声明提供",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 上下文上限（**可自由编辑**）----
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "上下文上限",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (ctxDraft.isEmpty()) {
                        Text(
                            text = current.contextLimitHint?.let(::formatContext) ?: "未设置",
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ★ 不 requestFocus ⇒ 不弹键盘；用户点一下才聚焦
                    BasicTextField(
                        value = ctxDraft,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }
                            ctxDraft = clean
                            onContextLimitChange(clean)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = current.contextLimitHint?.let {
                    "上游声明 ${formatContext(it)}；可自行改到厂家真正允许的上限"
                } ?: "该模型未声明窗口大小，可按需填写",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// =====================================================================
// 权限页
// =====================================================================

/**
 * 三档权限选择。
 *
 * ## ★★★ 为什么每一档都要写清"能干什么 / 不能干什么"
 *
 * 只写档位名（「仅可查看」「工作区内修改」「完全权限」）用户没法判断该选哪个 ——
 * 尤其"完全权限"，听起来像是让模型能随便搞，实际它**只放开工作区边界**，
 * 不给新工具、不给 shell、也不跳过确认框。
 * 不说清这一点，用户要么不敢用，要么误以为放开了就什么都能干。
 */
@Composable
private fun PermissionPane(
    current: WorkspacePermission,
    onPick: (WorkspacePermission) -> Unit,
) {
    Column {
        Text(
            text = "限制模型在这个对话里能碰什么。改完下一轮生效。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        WorkspacePermission.entries.forEach { p ->
            val selected = p == current
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .border(
                        width = if (selected) 1.dp else 0.5.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onPick(p) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = p.label,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = p.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "当前档位",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            // ★ 这条必须留着：否则用户会以为"完全权限"= 模型什么都能干
            text = "注意：「完全权限」只放开工作区边界。它不会给模型新的工具、" +
                "不会让它执行命令，也不会跳过确认。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =====================================================================
// 工作区页
// =====================================================================

/** 弹窗里的一个工作区条目。 */
data class WorkspaceOption(
    val id: String,
    val name: String,
    /** 私有 / 共享（SAF） */
    val isShared: Boolean,
    /** 目录里已有几个文件（可空 = 未知） */
    val fileCount: Int? = null,
)

/**
 * 工作区选择。
 *
 * ★ **「没有工作区」是一个正常选项**，不是"未设置"。
 *   大多数闲聊根本不需要目录；把"没有"显示成缺失状态会让用户以为必须选一个。
 *
 * ★ 切换**不重开会话**：改完下一轮生效（用户明确要求「支持每个对话随时切换
 *   工作区并在下一轮对话生效」）。所以这里不做任何"需要重启"的提示。
 */
@Composable
private fun WorkspacePane(
    workspaces: List<WorkspaceOption>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
) {
    Column {
        Text(
            text = "给这个对话指定一个目录，模型可以在里面读写文件。改完下一轮生效。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // 「没有工作区」——正常选项
        WorkspaceRow(
            title = "没有工作区",
            subtitle = "纯聊天，模型不碰任何文件",
            selected = currentId == null,
            onClick = { onPick(null) },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(workspaces, key = { it.id }) { w ->
                WorkspaceRow(
                    title = w.name,
                    subtitle = buildString {
                        append(if (w.isShared) "手机文件目录（写完推送过去）" else "App 私有目录")
                        w.fileCount?.let { append(" · ").append(it).append(" 个文件") }
                    },
                    selected = currentId == w.id,
                    onClick = { onPick(w.id) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderChip(text = "新建工作区", selected = false, onClick = onCreate)
            ProviderChip(text = "管理工作区", selected = false, onClick = onManage)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "共享目录的文件会先在 App 私有目录里写好，再推送到你选的目录；" +
                "同名文件会被覆盖。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "当前工作区",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 把 token 数写成人类可读的「200K」「1M」。
 *
 * ★ 只用于**显示**（提示语），不参与任何换算 —— 用户填的永远是原始数字。
 */
private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000f
        if (m == m.toInt().toFloat()) "${m.toInt()}M" else String.format("%.1fM", m)
    }
    tokens >= 1_000 -> {
        val k = tokens / 1_000f
        if (k == k.toInt().toFloat()) "${k.toInt()}K" else String.format("%.1fK", k)
    }
    else -> tokens.toString()
}

@Composable
private fun ProviderChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModelOptionRow(option: ModelOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (option.active) 1.dp else 0.5.dp,
                color = if (option.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = option.providerName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (option.active) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前使用",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
