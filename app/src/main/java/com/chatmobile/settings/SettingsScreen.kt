package com.chatmobile.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chatmobile.memory.MemoryKey
import com.chatmobile.net.Endpoint
import com.chatmobile.net.ModelCapability
import com.chatmobile.net.ProviderPreset
import com.chatmobile.net.Providers

/**
 * 「拉取模型」的返回：模型清单 + 失败原因 + **同一次响应里顺带解析出的能力档案**。
 *
 * ★ 为什么要把 capabilities 一起带回来：切换模型弹窗要显示「思考强度」和
 *   「上下文上限」，它们必须**来自上游的真实声明**（用户原话：「思考强度每个
 *   模型的是不一样的，所以说得看上游拉取的实际信息是什么样」）。
 *   拉到模型时顺手存下来，弹窗就不用再发一次网络请求。
 */
data class FetchOutcome(
    val ids: List<String>,
    val error: String?,
    val capabilities: Map<String, ModelCapability> = emptyMap(),
)

/**
 * 设置 —— 三层导航里的第 2、3 层。
 *
 * ## ★★★ 第 3 层是照 DSH 源码做的，不是照截图猜的
 *
 * 规格来源：本机 `app.asar` → `@deepseek-ai/dsh-client-ui-settings-models/lib/client.js`
 * （由 DSH 客户端 bundle 提取）。
 * 里面含 `ModelsSection.module.css` 全文与 `ModelsSection.js` 的结构注释。
 *
 * 照抄的关键数值（都能在 CSS 里逐条对上）：
 *
 * | 元素 | DSH 原值 |
 * |---|---|
 * | 卡片 | `.5px` 边框 · `radius-xl` · `padding 12px 14px` · 卡片间 `8px` |
 * | 密钥状态点 | `8×8` 圆点（绿=已配置 / 红=缺失） |
 * | 「添加模型提供商」 | **虚线边框** · `radius-lg` · `44px` 高 |
 * | 输入框 | `32px` 高 |
 * | 行内按钮（编辑/删除） | `28px` 高 · `12px` 字号 |
 * | 字号阶梯 | 标题 16/500 · 正文 14 · 辅助 12 · 标签 11 |
 *
 * ## 交互结构（也是照 DSH 的）
 *
 * - **一次只开一个编辑卡片**（DSH 注释原文："one editor card at a time"）
 * - 添加流程 = **一个按钮先选模式**：内置目录 / 自定义 API
 * - 删除需**二次确认**
 * - 面板首次显示后保持挂载（切模式不丢草稿）
 *
 * ## ★ 与 DSH 的唯一刻意差异
 *
 * DSH 的密钥框支持「留空使用环境变量 `DEEPSEEK_API_KEY`」的后备。
 * **手机 App 没有环境变量概念** ⇒ 这里改成**必填**，并把提示改成"留空则无法使用"。
 * 这不是简化，是平台差异 —— 照抄会让用户以为不填也能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onSaveProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
    modifier: Modifier = Modifier,
    onOpenModelConfig: (() -> Unit)? = null,
    focusModelConfig: Boolean = false,
    /** 记忆条数（0 = 尚未抽取到任何记忆） */
    memoryCount: Int = 0,
    /** 打开记忆管理页 */
    onOpenMemories: () -> Unit = {},
    /** 打开工具设置页 */
    onOpenTools: () -> Unit = {},
    /** 打开通用设置页 */
    onOpenGeneral: () -> Unit = {},
) {
    if (focusModelConfig) {
        ModelsScreen(
            state = state,
            onBack = onBack,
            onSaveProfile = onSaveProfile,
            onDeleteProfile = onDeleteProfile,
            onTest = onTest,
            onFetch = onFetch,
            modifier = modifier,
        )
    } else {
        SettingsHome(
            state = state,
            onBack = onBack,
            onOpenModelConfig = { onOpenModelConfig?.invoke() },
            memoryCount = memoryCount,
            onOpenMemories = onOpenMemories,
            onOpenTools = onOpenTools,
            onOpenGeneral = onOpenGeneral,
            modifier = modifier,
        )
    }
}

// =====================================================================
// 第 2 层：设置首页
//
// 用户明确：「只保留"模型"，后续加功能」⇒ 不做 ChatGPT 那种多分组卡片。
// 但**单张卡片的样式照 DSH**（`.5px` 边框 + radius-xl），视觉上是一张卡。
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    state: SettingsState,
    onBack: () -> Unit,
    onOpenModelConfig: () -> Unit,
    memoryCount: Int = 0,
    onOpenMemories: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenGeneral: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("设置", fontSize = 17.sp) },
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
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- 通用 ----
            //
            // ★ 放在第一位，与 DSH 的设置页顺序一致（通用 → 模型 → …）。
            //   用户原话：「设置界面加一个"通用设置"，里面包含图片这些」——
            //   截图里 DSH 的通用页就是第一项。
            //
            // ★ 内容全在 [GeneralScreen] 里：权限 / 外观 / 字号 /
            //   思考过程展示 / 思考过程收起时机。
            //   **刻意没抄** DSH 的「繁忙时发送行为」（手机没有物理 Enter 语义）、
            //   「上传 Session Log」（我们是 BYOK 客户端，没有服务器）——
            //   放一个不起作用的开关等于对用户撒谎，理由写在 GeneralScreen 的文档里。
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenGeneral)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("通用", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 一张卡片，一行
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModelConfig)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("模型", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    // ★ 用户要求：「设置界面的模型不用显示当前模型，会打乱UI」
                    //   ⇒ 这一行只留标签本身 + 箭头，右边不再放模型名。
                    //     当前用的是哪个模型，由主界面顶栏的模型胶囊负责显示。
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ---- 记忆 ----
            //
            // ★ 为什么必须有这个入口：记忆是**自动**写入的，用户看不见它写了什么。
            //   没有查看/删除入口，记忆系统就是个黑盒 —— 一旦它记错了（模型抽取跑偏
            //   很常见），用户完全无从纠正，只能卸载重装。
            //   官方 ChatGPT 也有 "Manage memories"，这不是我们发明的功能。
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenMemories)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("记忆", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    // ★ 用户要求：「记忆也不用写个暂无」
                    //   ⇒ 只有真有条数时才显示，0 条就什么都不写（右侧只剩箭头）。
                    if (memoryCount > 0) {
                        Text(
                            text = "$memoryCount 条",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ---- 工具 ----
            //
            // ★ 为什么要有这个入口：工具会读位置、抓网页、写记忆 ——
            //   用户有权知道有哪些、并逐条关掉。这是信任底线，不是可选功能。
            // ★ 另外它是**省钱开关**：工具 schema 每轮都要发，而且排在
            //   系统提示词之前（Anthropic 的 tools → system → messages），
            //   用不上的工具关掉是实打实的省 token。
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenTools)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("工具", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 记忆管理页。
 *
 * ★ 这是「记忆库能让用户看见」的唯一出口。设计上对齐官方 ChatGPT 的
 *   "Manage memories"：**能看到、能删、能看出它从哪来**。
 *
 * ★ 为什么必须给删除：记忆是**模型自动抽取**的，抽取跑偏很常见
 *   （把一次性的抱怨记成"用户的长期偏好"）。没有删除，用户就只能眼睁睁
 *   看着它越记越错。
 *
 * ★ 为什么分两栏（长期 / 近期）：这两层的语义完全不同 ——
 *   长期永不衰减、近期会随时间淡出。混在一起用户分不清"为什么这条还在、那条没了"。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MemoriesScreen(
    memories: List<MemoryUi>,
    memoryEnabled: Boolean,
    duplicateGroups: List<List<String>>,
    memoryBusy: Boolean = false,
    curatorModel: MemoryModelConfig = MemoryModelConfig(),
    embeddingModel: MemoryModelConfig = MemoryModelConfig(),
    curatorSummary: String = "",
    embeddingSummary: String = "",
    profiles: List<ModelProfile> = emptyList(),
    onSaveCurator: (MemoryModelConfig) -> Unit = {},
    onSaveEmbedding: (MemoryModelConfig) -> Unit = {},
    onFetchModels: suspend (String, String, String) -> List<String> = { _, _, _ -> emptyList() },
    packStatus: String = "",
    packProgress: Float? = null,
    onDownloadPack: (() -> Unit)? = null,
    onDeletePack: (() -> Unit)? = null,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onConsolidate: () -> Unit = {},
    onDelete: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEdit: (String, String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onAdd: (String, String) -> Unit,
    onMergeGroup: (List<String>) -> Unit,
    onPurgeDeleted: () -> Unit,
    promptTemplates: com.chatmobile.prompt.PromptTemplateSet = com.chatmobile.prompt.PromptTemplateSet(),
    onSavePromptTemplates: (com.chatmobile.prompt.PromptTemplateSet) -> Unit = {},
    memoryTuning: MemoryTuning = MemoryTuning(),
    onSaveTuning: (MemoryTuning) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var editing by remember { mutableStateOf<MemoryUi?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingCurator by remember { mutableStateOf(false) }
    var editingEmbedding by remember { mutableStateOf(false) }
    // 0 = 记忆库，1 = 提示词
    var tab by rememberSaveable { mutableStateOf(0) }

    val alive = memories.filter { !it.deleted }
    val deleted = memories.filter { it.deleted }

    fun matches(m: MemoryUi): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return m.content.contains(q, ignoreCase = true) ||
            (m.key?.contains(q, ignoreCase = true) == true)
    }

    val filtered = when (filter) {
        FILTER_DELETED -> deleted.filter(::matches)
        FILTER_ALL -> alive.filter(::matches)
        else -> alive.filter { MemoryKey.namespace(it.key) == filter && matches(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("记忆与提示词", fontSize = 17.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (tab == 0) {
                            Switch(
                                checked = memoryEnabled,
                                onCheckedChange = onToggleEnabled,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("记忆库", fontSize = 13.sp) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("提示词", fontSize = 13.sp) },
                    )
                    Tab(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        text = { Text("参数", fontSize = 13.sp) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    text = { Text("记一条") },
                    icon = { Text("+", fontSize = 18.sp) },
                )
            }
        },
    ) { padding ->
        if (tab == 1) {
            PromptTab(
                templates = promptTemplates,
                onSaveTemplates = onSavePromptTemplates,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else if (tab == 2) {
            MemoryTuningTab(
                tuning = memoryTuning,
                onSave = onSaveTuning,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
            ),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (memoryEnabled) {
                            "这些是模型记住的关于你的事。同类的记忆只会保留一条；点「编辑」可以直接改，改成你希望它记住的样子。"
                        } else {
                            "记忆已关闭：不会注入、也不会再抽取。已有记忆仍然保留，打开开关即可恢复。"
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (alive.size >= 2) {
                        TextButton(onClick = onConsolidate, enabled = !memoryBusy) {
                            Text(if (memoryBusy) "整理中…" else "整理", fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                MemoryModelsCard(
                    curator = curatorModel,
                    embedding = embeddingModel,
                    curatorSummary = curatorSummary,
                    embeddingSummary = embeddingSummary,
                    onEditCurator = { editingCurator = true },
                    onEditEmbedding = { editingEmbedding = true },
                    packStatus = packStatus,
                    packProgress = packProgress,
                    onDownloadPack = onDownloadPack,
                    onDeletePack = onDeletePack,
                )
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索记忆…", fontSize = 14.sp) },
                )
            }

            item {
                // ★ 用 FlowRow 换行，不用横向滚动：
                //   横滚会把「回收站」这种末尾 chip 藏到屏幕外，用户（和自动化）
                //   都得先横滑才能看到 —— 手机上不如直接折成两行。
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MemoryFilterChip("全部", FILTER_ALL, filter) { filter = it }
                    MemoryKey.CATEGORIES.forEach { (ns, label) ->
                        MemoryFilterChip(label, ns, filter) { filter = it }
                    }
                    MemoryFilterChip("回收站", FILTER_DELETED, filter) { filter = it }
                }
            }

            if (duplicateGroups.isNotEmpty() && filter != FILTER_DELETED) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "发现 ${duplicateGroups.size} 组疑似重复的记忆",
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            TextButton(onClick = {
                                duplicateGroups.forEach(onMergeGroup)
                            }) {
                                Text("全部合并", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (filter == FILTER_DELETED && deleted.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onPurgeDeleted) {
                            Text("清空回收站", fontSize = 13.sp)
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = when {
                            memories.isEmpty() -> "还没有记忆。多聊几轮，它会自动记住关于你的事；也可以点右下角自己记一条。"
                            filter == FILTER_DELETED -> "回收站是空的。"
                            else -> "没有匹配的记忆。"
                        },
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            if (filter == FILTER_ALL) {
                // 按分类分组展示：用户一眼能看出"它记了我哪些方面"
                MemoryKey.CATEGORIES.forEach { (ns, label) ->
                    val group = filtered.filter { MemoryKey.namespace(it.key) == ns }
                    if (group.isNotEmpty()) {
                        item(key = "header-$ns") {
                            MemorySectionTitle(label, "${group.size} 条")
                        }
                        items(group, key = { it.id }) { m ->
                            MemoryCard(
                                m = m,
                                onDelete = { onDelete(m.id) },
                                onEdit = { editing = m },
                                onPin = { onPin(m.id, m.kind != MemoryUiKind.Saved) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { m ->
                    MemoryCard(
                        m = m,
                        onDelete = if (m.deleted) null else ({ onDelete(m.id) }),
                        onRestore = if (m.deleted) ({ onRestore(m.id) }) else null,
                        onEdit = if (m.deleted) null else ({ editing = m }),
                        onPin = if (m.deleted) null else ({ onPin(m.id, m.kind != MemoryUiKind.Saved) }),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        }
    }

    if (editing != null) {
        MemoryEditorDialog(
            initial = editing,
            onDismiss = { editing = null },
            onSave = { content, key ->
                onEdit(editing!!.id, content, key)
                editing = null
            },
            onDelete = {
                onDelete(editing!!.id)
                editing = null
            },
        )
    }

    if (adding) {
        MemoryEditorDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { content, key ->
                onAdd(content, key)
                adding = false
            },
        )
    }

    if (editingCurator) {
        MemoryModelConfigDialog(
            title = "记忆管理员模型",
            initial = curatorModel,
            profiles = profiles,
            onFetch = onFetchModels,
            onDismiss = { editingCurator = false },
            onSave = {
                onSaveCurator(it)
                editingCurator = false
            },
        )
    }

    if (editingEmbedding) {
        MemoryModelConfigDialog(
            title = "向量模型",
            initial = embeddingModel,
            profiles = profiles,
            onFetch = onFetchModels,
            onDismiss = { editingEmbedding = false },
            onSave = {
                onSaveEmbedding(it)
                editingEmbedding = false
            },
            allowChat = false,
            allowBuiltin = true,
            allowPack = true,
        )
    }
}

private const val FILTER_ALL = "all"
private const val FILTER_DELETED = "deleted"

@Composable
private fun MemoryFilterChip(
    label: String,
    value: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label, fontSize = 12.sp) },
    )
}

@Composable
private fun MemorySectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryCard(
    m: MemoryUi,
    onDelete: (() -> Unit)?,
    onRestore: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, top = 10.dp, bottom = 6.dp, end = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MemoryKey.label(m.key),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (!m.key.isNullOrBlank()) {
                Text(
                    "  ${m.key}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (m.kind == MemoryUiKind.Saved) "长期" else "近期",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = m.content,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            color = if (m.deleted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (m.hitCount > 0) "被用到 ${m.hitCount} 次" else "还没被用过",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onPin != null) {
                TextButton(onClick = onPin, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (m.kind == MemoryUiKind.Saved) "取消置顶" else "置顶", fontSize = 11.sp)
                }
            }
            if (onEdit != null) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("编辑", fontSize = 11.sp)
                }
            }
            if (onRestore != null) {
                TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("恢复", fontSize = 11.sp)
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 编辑/新建记忆。
 *
 * ★ 输入框必须持**本地草稿**（L12）：如果直接渲染 DataStore/列表里的值、
 *   每次按键都异步落盘，回流会把用户刚敲的字覆盖回旧值。
 */
@Composable
private fun MemoryEditorDialog(
    initial: MemoryUi?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var key by remember(initial?.id) { mutableStateOf(initial?.key.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "记一条" else "编辑记忆", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("内容", fontSize = 12.sp) },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("槽位键（可留空）", fontSize = 12.sp) },
                    placeholder = { Text("pref.reply_style / user.city / event.2026-10-28", fontSize = 11.sp) },
                )
                Text(
                    "同一个键只会保留一个值。留空表示自由文本，不会自动覆盖。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content.trim(), key.trim()) },
                enabled = content.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 给设置页用的记忆视图模型 —— 不让 ui 层依赖 core-memory 的内部类型。 */
data class MemoryUi(
    val id: String,
    val content: String,
    val kind: MemoryUiKind,
    val deleted: Boolean,
    /** 结构化槽位键；null = 自由文本 */
    val key: String? = null,
    val hitCount: Int = 0,
    val createdAt: Long = 0L,
    val lastUsedAt: Long? = null,
    val sourceConversationId: String? = null,
)

enum class MemoryUiKind { Saved, Recent }


// =====================================================================
// 第 3 层：模型（照 DSH）
// =====================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onSaveProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
    modifier: Modifier = Modifier,
) {
    // ★ 一次只开一个编辑卡片（DSH 的结构约束）。
    //   值 = 正在编辑的 profile id；null = 全部收起。
    var editingId by remember { mutableStateOf<String?>(null) }

    // 添加流程：null = 没在添加；否则是当前模式
    var addMode by remember { mutableStateOf<AddMode?>(null) }

    // 删除二次确认
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }

    Scaffold(
        // ★★ imePadding 必须加在 **Scaffold 外层**，不能加在 LazyColumn 上。
        //
        //   真机踩到的 bug：加在 LazyColumn 上时，键盘弹起 → LazyColumn 高度被压缩 →
        //   但它的 contentPadding 与 item 高度是按"原高度"算的，结果下方内容（模型输入框、
        //   保存按钮）既进不了可视区、也滚不到（uiautomator dump 只剩 1 个 EditText）。
        //
        //   放在 Scaffold 外层：整个页面（含 TopAppBar）被键盘顶起，LazyColumn 再在
        //   剩下的空间里正常滚动 —— 这才是"键盘弹起时还能看到并点到下面字段"的正确结构。
        modifier = modifier.imePadding(),
        topBar = {
            // ★ TopAppBar 不加 statusBarsPadding 了：Scaffold 自己会处理状态栏 inset，
            //   两边都加会多出一条空白。
            TopAppBar(
                title = { Text("模型", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            // ★ 不在这里加 imePadding —— 它在 Scaffold 外层已经加过了，
            //   两边都加会把可用高度压缩两次（键盘高度 × 2），内容更看不见。
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
            ),
        ) {
            // ---- 说明（DSH: intro，14px tertiary）----
            item {
                Text(
                    text = "填入各提供商的 API 密钥即可使用其模型。",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // ---- 已配置的提供商（DSH: rows，卡片间 8px）----
            items(state.profiles, key = { it.id }) { profile ->
                Spacer(Modifier.height(8.dp))
                ProviderRow(
                    profile = profile,
                    expanded = editingId == profile.id,
                    onToggleEdit = {
                        // 一次只开一个：点开新的会自动收起旧的
                        editingId = if (editingId == profile.id) null else profile.id
                    },
                    onSave = { onSaveProfile(it) },
                    onDelete = { pendingDelete = profile },
                    onTest = onTest,
                    onFetch = onFetch,
                )
            }

            // ---- 添加区（DSH: addBlock）----
            item {
                Spacer(Modifier.height(12.dp))
                if (addMode == null) {
                    // 虚线边框的大按钮（DSH: addButton, dashed, 44px）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { addMode = AddMode.Catalog },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("添加模型提供商", fontSize = 14.sp)
                    }
                } else {
                    AddPanel(
                        mode = addMode!!,
                        onModeChange = { addMode = it },
                        onCancel = { addMode = null },
                        onSave = { onSaveProfile(it) },
                        onTest = onTest,
                        onFetch = onFetch,
                    )
                }
            }
        }
    }

    // 删除二次确认（DSH 也有：deleteTitle = 「删除 {provider}？」）
    pendingDelete?.let { p ->
        val name = Providers.byId(p.providerId)?.displayName ?: p.providerId
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 $name？") },
            text = { Text("删除 $name 会移除其配置和存储的 API 密钥。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(p.id)
                        if (editingId == p.id) editingId = null
                        pendingDelete = null
                    },
                ) {
                    Text("删除 $name", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

enum class AddMode { Catalog, Custom }

/**
 * 一行提供商（DSH `rowCard`）。
 *
 * 收起时：红/绿点 + 名称 + 「编辑」按钮（+ 删除图标）。
 * 展开时：下面接一个编辑器（`editor`，`bg-module-platform` 底色）。
 */
@Composable
private fun ProviderRow(
    profile: ModelProfile,
    expanded: Boolean,
    onToggleEdit: () -> Unit,
    onSave: (ModelProfile) -> Unit,
    onDelete: () -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
) {
    val providerName = Providers.byId(profile.providerId)?.displayName ?: profile.providerId

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // ---- 头部：状态点 + 名称 + 操作 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ★ 8×8 状态点（DSH credentialDot）
            //   绿 = 密钥已配置（这里用"配置完整"近似，因为手机没有独立凭据存储层）
            //   红 = 缺密钥
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (profile.isComplete) Color(0xFF2E9E5B)
                        else MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = providerName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )

            // 行内按钮：28px 高（DSH rowActions 里的按钮规格）
            RowButton(text = if (expanded) "收起" else "编辑", onClick = onToggleEdit)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 未配置完成的提示（DSH 用 keyFallbackMissing 那类文案）
        if (!profile.isComplete) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "API 密钥缺失 —— 点「编辑」填写后才能使用。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- 展开的编辑器 ----
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Spacer(Modifier.height(12.dp))
            CatalogEditor(
                profile = profile,
                onSave = onSave,
                onCancel = onToggleEdit,
                onTest = onTest,
                onFetch = onFetch,
            )
        }
    }
}

/** 28px 高的小按钮（DSH `secondaryButton` 在 rowActions 里的规格）。 */
@Composable
private fun RowButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp)
    }
}

/**
 * 编辑一条**已有**配置。
 *
 * 已有配置的厂商是固定的（列表本来就按厂商分行），所以这里不显示厂商下拉 ——
 * 只让用户改密钥 / 模型。想换厂商就在下面重新添加一条。
 *
 * 自定义厂商走另一套表单（要填 API 地址），所以这里转一手。
 */
@Composable
private fun CatalogEditor(
    profile: ModelProfile,
    onSave: (ModelProfile) -> Unit,
    onCancel: () -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    if (draft.providerId == Providers.Custom.id) {
        CustomFields(
            draft = draft,
            onChange = { draft = it },
            onCancel = onCancel,
            onSave = onSave,
            onTest = onTest,
            // ★★★ 这两个 `onFetch` 之前**漏传了** —— 展开一条**已保存**的配置时，
            //   两个表单收到的都是默认值 null，于是 ModelSection 里的
            //   `onFetch?.invoke() ?: FetchOutcome(..., "未配置拉取器")` 永远走后半截，
            //   用户点「拉取模型」必定报「未配置拉取器」。
            //   ★ 教训：新增一个可选回调参数时，**每一条转发链都要过一遍**，
            //     漏一层就是"功能完全不可用"，而且编译器不会报错（有默认值）。
            onFetch = onFetch,
        )
    } else {
        CatalogFields(
            draft = draft,
            onChange = { draft = it },
            onCancel = onCancel,
            onSave = onSave,
            onTest = onTest,
            onFetch = onFetch,
        )
    }
}

/**
 * 「第三方模型提供商」表单。
 *
 * 厂商由调用方在外面选（[ProviderPicker]），这里只管**跟这家厂商配套**的字段：
 * 接入点 / 密钥 / 模型。
 *
 * ★ 文案纪律（用户 22:53 的第 3 条）：「完全没有必要写那么多文本，就写官方一点，
 *   正式一点，让别人知道这个框里填什么就好了。」
 *   ⇒ 每个框只有一句标签 + 一句 placeholder。厂商的 `note` 只有在**是真信息**时
 *   才显示（比如「Key 与国内站不通用」），不是废话式的引导语。
 */
@Composable
private fun CatalogFields(
    draft: ModelProfile,
    onChange: (ModelProfile) -> Unit,
    onCancel: () -> Unit,
    onSave: (ModelProfile) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
) {
    val preset = Providers.byId(draft.providerId) ?: Providers.all.first()

    // 用统一入口解析，手填过地址的配置也能正确回显选中项
    val endpoint = Providers.resolveEndpoint(draft.providerId, draft.endpointUrl)
        ?: preset.defaultEndpoint

    var keyVisible by remember(draft.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- 接入点（只有多接入点的厂商才出现）----
        if (preset.endpoints.size > 1) {
            EditorField("接入点") {
                Column {
                    preset.endpoints.forEach { ep ->
                        EndpointRow(
                            endpoint = ep,
                            selected = ep.baseUrl == endpoint.baseUrl,
                            onClick = { onChange(draft.copy(endpointUrl = ep.baseUrl)) },
                        )
                    }
                }
            }
        }

        // ---- 该厂商的真信息（一行，只说 Key 能不能通用这类硬事实）----
        if (preset.note.isNotBlank()) {
            Text(
                text = preset.note,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- API 密钥 ----
        EditorField("API 密钥") {
            ApiKeyBox(
                value = draft.apiKey,
                onValueChange = { onChange(draft.copy(apiKey = it)) },
                visible = keyVisible,
                onToggleVisible = { keyVisible = !keyVisible },
            )
        }

        // ---- 模型 ----
        EditorField("模型") {
            ModelSection(
                models = draft.effectiveModels,
                current = draft.model,
                onModelsChange = { onChange(draft.copy(models = it)) },
                onCurrentChange = { onChange(draft.copy(model = it)) },
                // ★ ModelSection 只负责"点一下就拉"，拉谁由这里决定 ——
                //   把**当前草稿**传过去，用的是用户刚填的地址与密钥，不是已保存的旧值。
                onFetch = onFetch?.let { f -> { f(draft) } },
                hint = preset.commonModels.joinToString(" / "),
                // ★ 拉到的能力档案立刻并进草稿 —— 用户点「保存」时一起落库。
                //   不并的话，弹窗里那两个设置永远是空的（要再拉一次才出现）。
                onCapabilities = { caps ->
                    onChange(draft.copy(capabilities = draft.capabilities + caps))
                },
            )
        }

        EditorActions(
            draft = draft,
            canSave = draft.apiKey.isNotBlank() && draft.model.isNotBlank(),
            onCancel = onCancel,
            onSave = onSave,
            onTest = onTest,
        )
    }
}

/**
 * 「自定义模型 API」表单。
 *
 * ★★★ 这个表单里**没有厂商这个概念** —— 用户原话：
 *   「你自定义那里怎么也是选提供商？不应该是给一个窗口用来填写吗？你没看到我给你的截图吗？」
 *   ⇒ 四个框：名称（可选）/ API 地址 / 密钥 / 模型。填完就能用。
 */
@Composable
private fun CustomFields(
    draft: ModelProfile,
    onChange: (ModelProfile) -> Unit,
    onCancel: () -> Unit,
    onSave: (ModelProfile) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
) {
    var keyVisible by remember(draft.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- 名称（可选，只是列表里显示得好认）----
        EditorField("名称") {
            PlainBox(
                value = draft.label,
                onValueChange = { onChange(draft.copy(label = it)) },
                placeholder = "选填",
            )
        }

        // ---- API 地址 ----
        EditorField("API 地址") {
            PlainBox(
                value = draft.endpointUrl,
                onValueChange = { onChange(draft.copy(endpointUrl = it)) },
                placeholder = "https://example.com/v1",
            )
        }

        // ---- API 密钥 ----
        EditorField("API 密钥") {
            ApiKeyBox(
                value = draft.apiKey,
                onValueChange = { onChange(draft.copy(apiKey = it)) },
                visible = keyVisible,
                onToggleVisible = { keyVisible = !keyVisible },
            )
        }

        // ---- 模型 ----
        EditorField("模型") {
            ModelSection(
                models = draft.effectiveModels,
                current = draft.model,
                onModelsChange = { onChange(draft.copy(models = it)) },
                onCurrentChange = { onChange(draft.copy(model = it)) },
                onFetch = onFetch?.let { f -> { f(draft) } },
                hint = "",
                onCapabilities = { caps ->
                    onChange(draft.copy(capabilities = draft.capabilities + caps))
                },
            )
        }

        EditorActions(
            draft = draft,
            canSave = draft.endpointUrl.isNotBlank() &&
                draft.apiKey.isNotBlank() &&
                draft.model.isNotBlank(),
            onCancel = onCancel,
            onSave = onSave,
            onTest = onTest,
        )
    }
}

/** 普通单行输入框（32dp 高，DSH `input` 规格）。 */
@Composable
private fun PlainBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, fontSize = 14.sp) },
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** 密钥输入框：默认打码，右侧「显示 / 隐藏」。 */
@Composable
private fun ApiKeyBox(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("粘贴 API Key", fontSize = 14.sp) },
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        trailingIcon = {
            TextButton(onClick = onToggleVisible) {
                Text(if (visible) "隐藏" else "显示", fontSize = 12.sp)
            }
        },
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * 模型区：**已添加的模型清单** + 「添加模型」+「拉取模型」。
 *
 * ★ 用户要求（23:25）：
 *   「第一是添加模型，用来手动输入来自同一家提供商的多个模型，
 *     第二个就是拉取模型，用来一次性的拉取，可以从该端口访问到的所有模型，
 *     然后进行勾选」
 *
 * ★ 为什么做成"清单 + 点选当前"而不是只有一个输入框：
 *   同一家提供商下换模型是**高频动作**（便宜的用于闲聊、强的用于难题），
 *   每换一次都要重打一遍模型 ID 是没必要的。
 *   ⇒ 清单里点一下就是"当前使用的模型"。
 *
 * @param models 已添加的模型清单
 * @param current 当前选中的模型
 * @param onModelsChange 清单变化（增删）
 * @param onCurrentChange 切换当前模型
 * @param onFetch 触发「拉取模型」，返回可用 id 列表 + 错误原因 + 能力档案
 * @param onCapabilities 拉到能力档案后回传给上层（存进 profile，供切换弹窗用）
 */
@Composable
private fun ModelSection(
    models: List<String>,
    current: String,
    onModelsChange: (List<String>) -> Unit,
    onCurrentChange: (String) -> Unit,
    onFetch: (suspend () -> FetchOutcome)?,
    hint: String,
    onCapabilities: (Map<String, ModelCapability>) -> Unit = {},
) {
    // 正在手动输入的型号
    var draft by remember { mutableStateOf("") }
    // 「添加模型」输入框是否展开
    var adding by remember { mutableStateOf(false) }

    // 拉取过程的三个状态：进行中 / 结果 / 勾选面板
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fetchTick by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- 已添加的模型清单 ----
        if (models.isEmpty()) {
            Text(
                text = "还没有模型",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.forEach { m ->
                val selected = m == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (selected) 1.dp else 0.5.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onCurrentChange(m) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(m, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val next = models - m
                            onModelsChange(next)
                            // 删掉的正好是当前选的 ⇒ 顺延到第一个，别留空
                            if (selected) onCurrentChange(next.firstOrNull().orEmpty())
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "移除该模型",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 两个按钮 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton(text = "添加模型") { adding = !adding }
            RowButton(text = if (fetching) "拉取中…" else "拉取模型") {
                if (!fetching) {
                    fetching = true
                    fetchError = null
                    fetched = null
                    fetchTick++
                }
            }
        }

        // ---- 手动添加输入框 ----
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("模型 ID", fontSize = 13.sp) },
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                val canAdd = draft.isNotBlank() && draft.trim() !in models
                RowButton(text = "加入") {
                    if (canAdd) {
                        val v = draft.trim()
                        onModelsChange(models + v)
                        if (current.isBlank()) onCurrentChange(v)
                        draft = ""
                    }
                }
            }
        }

        // ---- 拉取结果：勾选面板 ----
        val list = fetched
        if (list != null) {
            if (list.isEmpty()) {
                Text(
                    text = fetchError ?: "没有拉到模型",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "拉到 " + list.size + " 个，勾选要加入的：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .verticalScroll(rememberScrollState()),
                ) {
                    list.forEach { m ->
                        val on = m in checked
                        val already = m in models
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !already) {
                                    checked = if (on) checked - m else checked + m
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (on || already) Icons.Filled.Check
                                else Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (on || already) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = m,
                                fontSize = 13.sp,
                                color = if (already) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (already) {
                                Text(
                                    "已添加",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    RowButton(text = "取消") {
                        fetched = null
                        checked = emptySet()
                    }
                    Spacer(Modifier.width(8.dp))
                    RowButton(text = "加入所选") {
                        val add = checked.filter { it !in models }
                        if (add.isNotEmpty()) {
                            val next = models + add
                            onModelsChange(next)
                            if (current.isBlank()) onCurrentChange(add.first())
                        }
                        fetched = null
                        checked = emptySet()
                    }
                }
            }
        }

        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 拉取动作（有副作用，放 LaunchedEffect 里，不写在 onClick 内）
    if (fetchTick > 0) {
        LaunchedEffect(fetchTick) {
            val outcome = try {
                onFetch?.invoke() ?: FetchOutcome(emptyList(), "未配置拉取器")
            } catch (e: Throwable) {
                FetchOutcome(emptyList(), e.message ?: e::class.simpleName.orEmpty())
            }
            fetching = false
            fetchError = outcome.error
            fetched = outcome.ids
            // ★ 能力档案就地回传 —— 它是"上游说的实话"，
            //   存下来切换模型弹窗才能显示强度档位与窗口大小。
            if (outcome.capabilities.isNotEmpty()) {
                onCapabilities(outcome.capabilities)
            }
        }
    }
}

/**
 * 底部操作行 + 测试状态（DSH `editorActions`，右对齐）。
 *
 * 两个表单共用 —— 测试连接是有副作用的动作，只该有一份实现。
 */
@Composable
private fun EditorActions(
    draft: ModelProfile,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: (ModelProfile) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
) {
    var testing by remember(draft.id) { mutableStateOf(false) }
    var testResult by remember(draft.id) { mutableStateOf<TestResult?>(null) }
    var testTick by remember(draft.id) { mutableStateOf(0) }
    var testSnapshot by remember(draft.id) { mutableStateOf<ModelProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = canSave && !testing && onTest != null,
                onClick = {
                    testing = true
                    testResult = null
                    testSnapshot = draft
                    testTick++
                },
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (testing) "测试中…" else "测试连接", fontSize = 12.sp)
            }
            TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp) }
            // 主按钮（DSH primaryButton: 36px 高）
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (canSave) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable(enabled = canSave) { onSave(draft) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "保存",
                    fontSize = 14.sp,
                    color = if (canSave) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        testResult?.let { r ->
            Text(
                text = (if (r.ok) "✓ " else "✗ ") + r.message,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (r.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
            )
        }
    }

    val snap = testSnapshot
    if (snap != null) {
        LaunchedEffect(testTick) {
            val r = try {
                onTest?.invoke(snap) ?: TestResult(false, "未配置测试器")
            } catch (e: Throwable) {
                TestResult(false, e.message ?: e::class.simpleName.orEmpty())
            }
            testResult = r
            testing = false
        }
    }
}

/** DSH `field`：label（12px/500）+ 控件，gap 6dp。 */
@Composable
private fun EditorField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

// =====================================================================
// 添加面板（DSH `addCard`：模式切换 + 目录/自定义两种）
// =====================================================================

@Composable
private fun AddPanel(
    mode: AddMode,
    onModeChange: (AddMode) -> Unit,
    onCancel: () -> Unit,
    onSave: (ModelProfile) -> Unit,
    onTest: (suspend (ModelProfile) -> TestResult)?,
    onFetch: (suspend (ModelProfile) -> FetchOutcome)? = null,
) {
    // ★★ 两个模式必须**各自持有草稿**，不能共用一个。
    //
    //   真机踩到的问题：之前在两种模式下都调同一个 ProviderEditor，
    //   结果「自定义模型 API」里第一项还是**厂商下拉** —— 用户原话
    //   「你自定义那里怎么也是选提供商？不应该是给一个窗口用来填写吗？」
    //
    //   两个模式填的东西本来就不同：
    //     · 目录模式：先选厂商 → 再填 Key / 模型（baseUrl 由厂商预置决定）
    //     · 自定义模式：**没有厂商概念** → 直接填名称 / API 地址 / Key / 模型
    //   ⇒ 后者要用独立表单，不能复用前者。
    var catalogDraft by remember { mutableStateOf(newCatalogDraft()) }
    var customDraft by remember { mutableStateOf(newCustomDraft()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 模式切换 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("第三方模型提供商", mode == AddMode.Catalog) {
                onModeChange(AddMode.Catalog)
            }
            ModeChip("自定义模型 API", mode == AddMode.Custom) {
                onModeChange(AddMode.Custom)
            }
        }

        when (mode) {
            // ==========================================================
            // 目录模式：先选厂商
            // ==========================================================
            AddMode.Catalog -> {
                EditorField("提供商") {
                    ProviderPicker(
                        selected = catalogDraft.providerId,
                        onSelect = { p ->
                            catalogDraft = catalogDraft.copy(
                                providerId = p.id,
                                // 切厂商必须清接入点与模型 —— 否则残留上一家的值，
                                // 发出去 404/400，用户看不出是切换导致的。
                                endpointUrl = "",
                                model = "",
                            )
                        },
                    )
                }
                CatalogFields(
                    draft = catalogDraft,
                    onChange = { catalogDraft = it },
                    onCancel = onCancel,
                    onSave = onSave,
                    onTest = onTest,
                    onFetch = onFetch,
                )
            }

            // ==========================================================
            // 自定义模式：**直接填，没有厂商下拉**
            // ==========================================================
            AddMode.Custom -> {
                CustomFields(
                    draft = customDraft,
                    onChange = { customDraft = it },
                    onCancel = onCancel,
                    onSave = onSave,
                    onTest = onTest,
                    onFetch = onFetch,
                )
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
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

/**
 * 目录模式的草稿。
 *
 * 厂商默认取列表第一个，但**不预填模型** —— 预填了用户很容易直接点保存，
 * 结果用的是他没确认过的型号。让他看到输入框是空的，去挑一个。
 */
private fun newCatalogDraft() = ModelProfile(
    id = SettingsStore.newId(),
    providerId = Providers.all.first().id,
    apiKey = "",
    model = "",
)

/**
 * 自定义模式的草稿。
 *
 * `providerId` 固定为 `"custom"` —— 它是**存进用户配置里的稳定标识**，
 * 改了会让已保存的自定义配置在列表里找不到厂商。
 */
private fun newCustomDraft() = ModelProfile(
    id = SettingsStore.newId(),
    providerId = Providers.Custom.id,
    endpointUrl = "",
    apiKey = "",
    model = "",
)

// =====================================================================
// 小部件
// =====================================================================

/** 测试结果。`ok=false` 时 [message] 必须能指导用户下一步动作，不能只说"失败"。 */
data class TestResult(val ok: Boolean, val message: String)

@Composable
private fun ProviderPicker(selected: String, onSelect: (ProviderPreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = Providers.byId(selected)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                )
                .background(MaterialTheme.colorScheme.surface)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.displayName ?: "请选择",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (current != null && current.endpoints.size > 1) {
                    Text(
                        text = current.endpoints.size.toString() + " 个接入点",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                // ★★ 这里**不能**用 LazyColumn。
                //
                //   真机上踩到的 bug：ProviderPicker 本身渲染在**外层 LazyColumn 的 item 里**，
                //   再套一个 LazyColumn 就成了「懒列表套懒列表」——
                //   内层在测量时拿到的高度约束是无限的，导致外层 item 被压扁到只剩第一屏，
                //   后面的「模型」输入框 / 「保存」按钮整块消失（uiautomator dump 只剩 1 个 EditText）。
                //
                //   用普通 Column + verticalScroll 就没这个问题：它按内容实际高度测量，
                //   再由外层约束裁切。
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Providers.all.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = false; onSelect(p) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = p.displayName,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (p.id == selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: Endpoint, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(endpoint.label, fontSize = 13.sp)
            Text(
                text = endpoint.baseUrl,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
