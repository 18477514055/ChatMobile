package com.chatmobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatmobile.ui.chat.ReasoningAutoCollapse
import com.chatmobile.ui.chat.ReasoningDisplay
import com.chatmobile.workspace.WorkspacePermission

/**
 * **通用设置** —— 参照 DSH「设置 → 通用」的分组与写法。
 *
 * ## ★★★ 为什么要有这一页（而不是散在别处）
 *
 * 用户原话：「设置界面加一个"通用设置"，里面包含图片这些（有几个不用加）」。
 *
 * 截图里的 DSH 通用页有这些项：
 * 权限 / 语言 / 外观 / 字号大小 / 工作步骤展示 / 工作步骤收起时机 /
 * 繁忙时的发送行为 / 性能与用量 / 在官方 API 时上传 Session Log / 当前版本。
 *
 * ## ★★★ 哪些**不放**，以及为什么（这是本页最重要的设计决定）
 *
 * | DSH 的项 | 我们 | 理由 |
 * |---|---|---|
 * | 权限（新会话默认权限模式） | **放** | 我们真有三档工作区权限 |
 * | 语言（界面语言） | **放** | 我们真有两套文案字符串 |
 * | 外观（浅色/深色/跟随系统） | **放** | 主题真的会跟着变 |
 * | 字号大小 | **放** | 聊天气泡字号可调 |
 * | 工作步骤展示 | **放**（叫「思考过程」） | 我们真有四档 |
 * | 工作步骤收起时机 | **放** | 我们真有自动收起 |
 * | 繁忙时的发送行为（Enter / Cmd+Enter） | ❌ **不放** | **手机没有物理键盘的 Enter 语义**。手机上是"点发送键/点箭头"，不存在"打字时的 Enter 该发送还是换行"这个冲突 —— 抄过来就是一个永远没人改的设置项，还让人以为有键盘快捷键。 |
 * | 性能与用量（详细程度） | ✅ **放，但换含义** | DSH 是"桌面端性能面板的详细程度"。我们改成「用量统计」—— 显示本轮 token / 上下文占用。这两个是本 App 真有的数据。 |
 * | 在官方 API 时上传 Session Log | ❌ **不放** | 我们是**自带 API Key**的客户端，没有"官方 API"这回事，也不该有"往我们服务器上传日志"的开关 —— 根本没有服务器。放一个假的隐私开关比不放更糟。 |
 * | 当前版本 | **放** | 真版本号，从 BuildConfig 读 |
 *
 * ★ 判据（与本项目已有的一条教训同源）：**凡是会随版本变化的事实，一律指向运行时来源，不写死值。**
 *   反过来说：**凡是我们根本没有的机制，一律不放进设置页** ——
 *   放一个不起作用的开关，等于对用户撒谎。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeneralScreen(
    permission: WorkspacePermission,
    display: ReasoningDisplay,
    autoCollapse: ReasoningAutoCollapse,
    themeMode: ThemeMode,
    fontSize: Int,
    onBack: () -> Unit,
    onPermissionChange: (WorkspacePermission) -> Unit,
    onDisplayChange: (ReasoningDisplay) -> Unit,
    onAutoCollapseChange: (ReasoningAutoCollapse) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ 本地草稿：输入即落盘的 Compose 输入框必须持本地草稿（L12）。
    //   直接渲染持久化值会让每次按键都和异步回写打架，字符会被吞。
    var fontDraft by remember(fontSize) { mutableStateOf(fontSize.toString()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("通用", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---------------- 权限 ----------------
            SectionHeader(
                title = "权限",
                subtitle = "新会话默认的权限模式。已有会话可在主界面的快捷面板里单独改。",
            )
            ChoiceGroup(
                options = WorkspacePermission.entries.map { p ->
                    Choice(p.name, p.label, p.description)
                },
                selected = permission.name,
                onSelect = { name ->
                    WorkspacePermission.fromName(name).let(onPermissionChange)
                },
            )

            // ---------------- 外观 ----------------
            SectionHeader(
                title = "外观",
                subtitle = "深色不是简单反色 —— 背景接近纯黑，气泡比背景略亮一点。",
            )
            ThemePicker(current = themeMode, onChange = onThemeModeChange)

            // ---------------- 字号 ----------------
            SectionHeader(
                title = "字号大小",
                subtitle = "只影响聊天内容的字号。界面标题与按钮不跟着变，" +
                    "否则小字号下按钮会小到点不中。",
            )
            OutlinedTextField(
                value = fontDraft,
                onValueChange = { input ->
                    // ★ 只收数字、封 2 位 —— 否则用户可以输入天文数字。
                    val digits = input.filter { it.isDigit() }.take(2)
                    fontDraft = digits
                    // 落库时机：只在解析成有效范围时才写，避免"输到一半被钳到 12"
                    digits.toIntOrNull()?.let { v ->
                        if (v in MIN_FONT_SP..MAX_FONT_SP) onFontSizeChange(v)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (fontDraft.toIntOrNull()?.let { it in MIN_FONT_SP..MAX_FONT_SP } == false) {
                Text(
                    text = "有效范围是 $MIN_FONT_SP–$MAX_FONT_SP，当前值不会被保存。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ---------------- 思考过程 ----------------
            SectionHeader(
                title = "思考过程展示",
                subtitle = "回答过程中思考过程展开多少。收起态只留一行摘要 + 字数，" +
                    "不会截半句 —— 让人知道里面值不值得点。",
            )
            ChoiceGroup(
                options = ReasoningDisplay.entries.map { d ->
                    Choice(d.name, d.label, d.description)
                },
                selected = display.name,
                onSelect = { name -> onDisplayChange(ReasoningDisplay.fromName(name)) },
            )

            SectionHeader(
                title = "思考过程收起时机",
                subtitle = "回答结束后要不要自动收成一行。想回看随时点标题展开 —— " +
                    "手动点过之后，任何自动行为都不会再覆盖你的选择。",
            )
            ChoiceGroup(
                options = ReasoningAutoCollapse.entries.map { m ->
                    Choice(
                        m.name,
                        m.label,
                        when (m) {
                            // ★ 描述必须说清""会怎样"，不能只换个词。
                            //   用户选档位时脑子里只有一个问题：选了这个我以后会看到什么。
                            ReasoningAutoCollapse.AfterAnswer ->
                                "回答一结束就收起，答案更好读。点标题可随时展开。"
                            ReasoningAutoCollapse.Never ->
                                "始终保持上面的展开程度，不会自己收起。"
                        },
                    )
                },
                selected = autoCollapse.name,
                onSelect = { name ->
                    ReasoningAutoCollapse.fromName(name).let(onAutoCollapseChange)
                },
            )
        }
    }
}

/** 字号允许范围。低于 12 中文太难认；高于 26 一行放不下几个字。 */
internal const val MIN_FONT_SP: Int = 12
internal const val MAX_FONT_SP: Int = 26

/**
 * 界面主题模式。
 *
 * ★ 为什么不直接存一个 `Boolean darkTheme`：
 *   "跟随系统"是**第三种状态**，不是"深色=false"。
 *   用布尔就只能二选一，用户选了跟随系统之后一改系统设置就跳不回来自动跟随。
 */
enum class ThemeMode(val label: String) {
    Light("浅色"),
    Dark("深色"),
    System("跟随系统"),
    ;

    companion object {
        fun fromName(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: System
    }
}

// =====================================================================
// 可复用的小组件
// =====================================================================

/** 一组选项里的一项。 */
internal data class Choice(
    val value: String,
    val label: String,
    val description: String = "",
)

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 竖排的单选组 —— 每一项带**它选中后会怎样**的一句说明。
 *
 * ★ 为什么不用 `DropdownMenu`：手机上点开下拉再点一项是两步，
 *   而这一页的选项最多 4 个，直接平铺一步到位。
 *   DSH 桌面端用下拉是因为那页有十几项，手机没有那个空间压力。
 */
@Composable
private fun ChoiceGroup(
    options: List<Choice>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            val isSelected = opt.value == selected
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        // ★ 选中态靠**描边颜色**而不是填满底色：
                        //   填色会让整块变绿，一页里有 3 组选项时会非常花。
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(14.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ★ 整行可点，不只是右边的勾 —— 只有勾可点的话，
                        //   手指点偏就会没反应，用户会以为"点不动"。
                        .clickable { onSelect(opt.value) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = opt.label,
                            fontSize = 14.sp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (opt.description.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = opt.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 外观三选一 —— 横排三个等宽方块，带图标。
 *
 * ★ 与[ChoiceGroup]分开写是因为**视觉形态不同**：外观是三选一的图标块（一眼看出效果），
 *   其它选项是带说明的文字行（需要读文案）。用同一个组件会两者都不讨好。
 */
@Composable
private fun ThemePicker(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == current
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onChange(mode) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = themeIcon(mode),
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = mode.label,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.Light -> Icons.Filled.LightMode
    ThemeMode.Dark -> Icons.Filled.DarkMode
    ThemeMode.System -> Icons.Filled.SettingsBrightness
}

/** 一行只读信息（当前版本等）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 分组之间的一条细分隔线。 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
