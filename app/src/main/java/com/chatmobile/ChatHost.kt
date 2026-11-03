package com.chatmobile

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import com.chatmobile.data.ChatDatabase
import com.chatmobile.data.CompressedSpanEntity
import com.chatmobile.data.RoomMemoryStore
import chatmobile.memory.MemoryBank
import com.chatmobile.net.ChatEvent
import com.chatmobile.net.ChatProvider
import com.chatmobile.net.ChatRequest
import com.chatmobile.net.ChatMessage as NetChatMessage
import com.chatmobile.net.ModelsApi
import com.chatmobile.net.ProviderFactory
import com.chatmobile.net.Providers
import com.chatmobile.prompt.SystemPrompt
import com.chatmobile.settings.FetchOutcome
import com.chatmobile.settings.ModelProfile
import com.chatmobile.settings.MemoriesScreen
import com.chatmobile.settings.SettingsScreen
import com.chatmobile.settings.SettingsState
import com.chatmobile.settings.SettingsStore
import com.chatmobile.settings.TestResult
import com.chatmobile.data.ChatRepository
import com.chatmobile.data.MessageEntity
import com.chatmobile.data.MessageRole
import com.chatmobile.data.MessageStatus
import com.chatmobile.data.roleEnum
import com.chatmobile.ui.chat.ChatMessage
import com.chatmobile.ui.chat.ChatScreen
import com.chatmobile.ui.chat.ModelOption
import com.chatmobile.ui.chat.QuickSwitchSheet
import com.chatmobile.ui.chat.WorkspaceOption
import com.chatmobile.ui.sidebar.SidebarLayout
import com.chatmobile.ui.sidebar.SidebarPanel
import com.chatmobile.ui.sidebar.rememberSidebarState
import com.chatmobile.ui.chat.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 真正的宿主界面：**Room 持久化 + 侧边栏会话列表**。
 *
 * ## ★ 这个文件为什么存在（Lead 集成层）
 *
 * 之前 `MainActivity` 接的是 `ChatDemoScreen()`，它用
 * `remember { mutableStateListOf() }` 存消息 —— **纯内存**。
 * 后果是用户实测到的：**切到桌面再回来，历史消息全没了**。
 * 进程被系统回收是 Android 常态（后台内存吃紧就杀），
 * 用 `remember` 存聊天记录等于"关屏就丢"。
 *
 * 这里把它换成 `ChatRepository`（data-agent 交付，89 个用例覆盖），
 * 消息落 SQLite，冷启动从库里读回来。
 *
 * ## 文件归谁
 *
 * 这是 Lead 的集成文件，**不放在 `ui/` 下** —— `ui/` 目录归 ui-agent。
 * 它只**调用** ui / data / net 三方的公开 API，不修改它们的任何文件。
 * 三方各自的内部约定由各自的 README 负责，这里只做"接线"和"状态托管"。
 *
 * ## 当前仍在用模拟流式
 *
 * `net/` 已交付（60 用例），但**还没接**。现在 `streamReply()` 仍是本地逐字模拟，
 * 目的是先把"持久化 + 会话切换"这条链路验证通；接真实模型是下一步，
 * 届时只替换 `streamReply()` 内部，其余状态逻辑不动。
 */
@Composable
fun ChatHost(
    modifier: Modifier = Modifier,
    /** 起始层。默认聊天；仅调试用（见 MainActivity 的 `open_memory` extra）。 */
    initialLayer: NavLayer = NavLayer.Chat,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // ---- 数据库：**进程级单例**，不是 Composable 级 ----
    //
    // ★★ 为什么不能写 `remember { ChatDatabase.build(context) }`
    //
    //   `remember` 的生命周期跟随**组合**，不是进程。下面任一件事都会让 Activity 重建、
    //   组合被丢弃、`remember` 重跑：
    //     · 旋转屏幕 / 分屏
    //     · 切换深色模式（uiMode 变化）
    //     · 切换系统语言（locale 变化）
    //     · 开发者选项里的"不保留活动"
    //
    //   而 Room 的 `databaseBuilder(...).build()` **每次都会新开一个到同一文件的连接**。
    //   旧实例没人引用、更没人 `close()` ⇒ 连接泄漏，且多个实例对同一 SQLite 文件
    //   并发写时 WAL 会让问题更难察觉（数据看着还在，但偶尔读到旧快照）。
    //
    //   改成对象级单例：一个进程只可能有一个 ChatDatabase。
    //   放在伴生对象里而不是 Application 类，是为了不新增 Application
    //   （那要改 AndroidManifest，属于构建契约变更）。
    val repo = remember { ChatRepository(RepoHolder.get(context)) }

    // ★★ 记忆库 —— 门面 + 持久化。
    //
    //   分工（不要混）：
    //     · `MemoryBank`   = 纯逻辑（衰减 / 排序 / 预算 / 提升），无平台依赖
    //     · `RoomMemoryStore` = 同步接口 + 异步写库 + 内存缓存
    //   `MemoryBank` 只认 `MemoryStore` 接口，所以它能脱离 Android 单测（已有 31 条）。
    val memoryStore = remember { RepoHolder.memory(context) }
    // ★ 先按默认值构造；下面用 LaunchedEffect 把用户存档的参数叠加上去。
    //   不在 `remember` 里同步读 DataStore：`load()` 是 suspend，且首次组合就阻塞会掉帧。
    val memoryBank = remember { MemoryBank(memoryStore) }

    /** 压缩器 —— 纯逻辑，不碰网络/数据库，可脱离 Android 单测 */
    val compressor = remember { chatmobile.memory.Compressor() }

    // 记忆管理页的展示列表。读的是 `MemoryBank`（内存缓存），不是直接查库 ——
    // 库是异步写的，直接查会读到「刚删掉的还在」。
    val memoryUi = remember { mutableStateListOf<com.chatmobile.settings.MemoryUi>() }

    /** 疑似重复的分组（每项是一组记忆 id）。面板据此显示「全部合并」。 */
    val duplicateGroups = remember { mutableStateListOf<List<String>>() }

    /** 「整理」在跑：按钮显示「整理中…」并禁用，避免连点重复请求。 */
    var memoryBusy by remember { mutableStateOf(false) }

    /**
     * 工具开关状态（工具名 → 是否启用）。
     *
     * ★ 从 DataStore 读出来的**快照**，启动时载入一次、用户改动后回读。
     *   不每帧读：DataStore 是异步的，每帧读会让开关闪一下。
     */
    var toolEnabledMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    /** 工具结果回填上限（字符），同样来自 DataStore。 */
    var toolResultChars by remember { mutableStateOf(8000) }

    // 设置存储。DataStore 的实例是进程级（见 SettingsStore 文件末尾的 by preferencesDataStore），
    // 这里 remember 只是避免每帧重新包装一次。
    val settings = remember { SettingsStore(context) }

    /** 向量模型探针结果（未探测为 null）。 */
    var embeddingStatus by remember { mutableStateOf<String?>(null) }

    /** 增强包状态与下载进度。 */
    var packStatus by remember { mutableStateOf("") }
    var packProgress by remember { mutableStateOf<Float?>(null) }

    fun refreshPackStatus() {
        packStatus = when (val v = com.chatmobile.memory.pack.EmbeddingPack.verify(context.filesDir)) {
            com.chatmobile.memory.pack.EmbeddingPack.Verdict.Ready -> "已安装 · 离线可用"
            com.chatmobile.memory.pack.EmbeddingPack.Verdict.Missing -> "未安装（约 23MB）"
            is com.chatmobile.memory.pack.EmbeddingPack.Verdict.Corrupt -> "已损坏：${v.reason}"
        }
    }

    // 首次进入时算一次包状态（只读文件系统，很便宜）
    LaunchedEffect(Unit) { refreshPackStatus() }

    /** 给向量模型做一次真实探针：成功返回「已就绪 · N 维」，失败也要说清会退回字面检索。 */
    suspend fun probeEmbedding(cfg: com.chatmobile.settings.MemoryModelConfig): String {
        // 内置轻量向量：不需要网络，也不需要下载，直接报维度。
        if (cfg.source == com.chatmobile.settings.MemoryModelConfig.SOURCE_BUILTIN) {
            return "内置轻量 · ${chatmobile.memory.HashEmbeddingProvider().dimensions} 维（无需下载）"
        }
        // 内置真模型（下载式增强包）：先看包，再做一次真实推理。
        if (cfg.source == com.chatmobile.settings.MemoryModelConfig.SOURCE_ONNX) {
            when (val v = com.chatmobile.memory.pack.EmbeddingPack.verify(context.filesDir)) {
                com.chatmobile.memory.pack.EmbeddingPack.Verdict.Missing -> return "未下载（先点「下载」）"
                is com.chatmobile.memory.pack.EmbeddingPack.Verdict.Corrupt -> return "包已损坏：${v.reason}"
                com.chatmobile.memory.pack.EmbeddingPack.Verdict.Ready -> Unit
            }
            val provider = com.chatmobile.memory.pack.BgeSmallZhEmbeddingProvider(context.filesDir)
            val t0 = System.currentTimeMillis()
            val out = provider.embed(listOf("记忆向量探针"))
            val cost = System.currentTimeMillis() - t0
            // ★ 首次包含 session 加载 + 24MB 模型映射；第二次起才是纯推理耗时
            android.util.Log.i("ChatHost", "本地向量探针：${cost}ms，输出 ${out?.firstOrNull()?.size ?: 0} 维")
            return if (out != null && out.isNotEmpty()) {
                "已就绪 · ${out.first().size} 维（本地推理 · 首次 ${cost}ms）"
            } else {
                "本地推理失败（自动退回轻量/字面检索）"
            }
        }
        val st = settings.load()
        val p = resolveMemoryProfile(cfg, st.profiles, st.active) ?: return "未配置"
        val preset = Providers.byId(p.providerId)
        val base = Providers.resolveEndpoint(p.providerId, p.endpointUrl)?.baseUrl
            ?.takeIf { it.isNotBlank() }
            ?: preset?.defaultEndpoint?.baseUrl
            ?: p.endpointUrl
        if (base.isBlank() || p.model.isBlank()) return "未配置"
        val dim = com.chatmobile.memory.RemoteEmbeddingProvider(
            baseUrl = base,
            apiKey = p.apiKey,
            model = p.model,
        ).probe()
        return if (dim != null) "已就绪 · $dim 维" else "未连通（自动退回字面检索）"
    }

    /**
     * 把「向量模型」配置**真正装到** `MemoryBank` 上。
     *
     * ★★★ 这一步以前根本不存在 —— 面板能选、探针能通，但 `memoryBank` 从头到尾
     *   只持有 `LexicalRetriever`，所以 retrieval 永远是字面匹配（真机实测：
     *   同义查询「远程调用」对「通过客户端调用 API」返回 0 条）。
     *   现在配置改完/启动时都会走这里，装不上就装 null（退字面），并在日志里留痕。
     */
    suspend fun applyEmbeddingBackend(cfg: com.chatmobile.settings.MemoryModelConfig) {
        val provider: chatmobile.memory.EmbeddingProvider? = when (cfg.source) {
            com.chatmobile.settings.MemoryModelConfig.SOURCE_BUILTIN ->
                chatmobile.memory.HashEmbeddingProvider()

            com.chatmobile.settings.MemoryModelConfig.SOURCE_ONNX -> {
                val ok = com.chatmobile.memory.pack.EmbeddingPack.verify(context.filesDir) ==
                    com.chatmobile.memory.pack.EmbeddingPack.Verdict.Ready
                if (ok) com.chatmobile.memory.pack.BgeSmallZhEmbeddingProvider(context.filesDir) else null
            }

            com.chatmobile.settings.MemoryModelConfig.SOURCE_PROFILE,
            com.chatmobile.settings.MemoryModelConfig.SOURCE_CUSTOM -> {
                val st = settings.load()
                val p = resolveMemoryProfile(cfg, st.profiles, st.active)
                val preset = p?.let { Providers.byId(it.providerId) }
                val base = p?.let {
                    Providers.resolveEndpoint(it.providerId, it.endpointUrl)?.baseUrl
                        ?.takeIf { b -> b.isNotBlank() }
                        ?: preset?.defaultEndpoint?.baseUrl
                        ?: it.endpointUrl
                }.orEmpty()
                if (p != null && base.isNotBlank() && p.model.isNotBlank() &&
                    com.chatmobile.settings.MemoryModelConfig.isEmbeddingCapable(p.providerId)
                ) {
                    com.chatmobile.memory.RemoteEmbeddingProvider(
                        baseUrl = base,
                        apiKey = p.apiKey,
                        model = p.model,
                    )
                } else {
                    null
                }
            }

            else -> null // 跟随对话模型：聊天端点基本没有 /embeddings
        }

        memoryBank.setEmbeddingProvider(provider)
        Log.i("ChatHost", "记忆检索后端 = ${memoryBank.retrieverId}（配置来源 ${cfg.source}）")
    }

    // 启动时：把用户存档的记忆参数叠到默认配置上，并把选过的向量后端真正装上。
    // ★ 必须放在 applyEmbeddingBackend 定义**之后**（Kotlin 局部函数要先声明后使用）。
    LaunchedEffect(Unit) {
        runCatching {
            val savedState = settings.load()
            val saved = savedState.memoryTuning
            if (!saved.isDefault) {
                memoryBank.updateConfig(saved.applyTo(chatmobile.memory.MemoryConfig()))
            }
            // ★ 把用户选过的向量后端真正装上（否则选了也白选 —— 这正是那个 bug）
            applyEmbeddingBackend(savedState.embeddingModel)
        }
    }

    /**
     * 把 `MemoryBank` 的状态同步到界面列表。
     *     * ★ 每次增删改之后**必须**调一次：`memoryBank` 是同步内存对象，
     *   它变了不会自动通知 Compose（它不是 snapshot state）。
     *   不调的话用户点删除、界面纹丝不动，会以为没删掉。
     */
    fun refreshMemories() {
        val list = memoryBank.list(includeDeleted = true)
        memoryUi.clear()
        memoryUi.addAll(
            list.map { e ->
                com.chatmobile.settings.MemoryUi(
                    id = e.id,
                    content = e.content,
                    kind = if (e.kind == chatmobile.memory.MemoryKind.SAVED)
                        com.chatmobile.settings.MemoryUiKind.Saved
                    else com.chatmobile.settings.MemoryUiKind.Recent,
                    deleted = e.deleted,
                    key = e.key,
                    hitCount = e.hitCount,
                    createdAt = e.createdAt,
                    lastUsedAt = e.lastUsedAt,
                    sourceConversationId = e.sourceConversationId,
                )
            }
        )
        duplicateGroups.clear()
        duplicateGroups.addAll(memoryBank.findDuplicateGroups().map { g -> g.map { it.id } })
    }


    val messages = remember { mutableStateListOf<ChatMessage>() }
    val conversations = remember { mutableStateListOf<Conversation>() }

    // ---------------------------------------------------------------
    // ★ 三层导航（用户明确定义）
    //
    //   第 1 层：聊天主界面 + 侧边栏（侧边栏是主界面的**内嵌层**，不是独立页）
    //   第 2 层：设置页
    //   第 3 层：设置里的「模型配置」（用户要求放在设置第一项）
    //
    // 返回键规则（用户原话：「系统返回键回到上一层」）：
    //   第 3 层 → 回第 2 层设置页
    //   第 2 层 → 回第 1 层，**且侧边栏处于打开状态**
    //   第 1 层（侧边栏开着）→ 收起侧边栏
    //   第 1 层（侧边栏关着）→ 交给系统（退到桌面）
    //
    // ★ 用单个枚举而不是多级 Boolean：Boolean 组合会变出 2^n 个非法状态
    //   （比如"设置页开着但模型配置层也开着"），枚举天然互斥。
    // ---------------------------------------------------------------
    var layer by rememberSaveable { mutableStateOf(initialLayer) }
    // 从设置返回时要恢复侧边栏打开状态，所以它必须活过页面切换
    var sidebarOpenAfterBack by rememberSaveable { mutableStateOf(false) }

    /** 快速切换模型弹窗是否开着 */
    var showModelSwitch by rememberSaveable { mutableStateOf(false) }

    // ★★ 这三项用 rememberSaveable（走 Bundle），不是 remember。
    //
    //   `remember` 只在**组合存活期**内有效；Activity 一重建就清空：
    //     · activeId  → 用户从会话 B 切到深色模式，回来跳到会话 A（看起来像"串了"）
    //     · input     → 打到一半的话直接消失（用户会当成 bug 报上来）
    //     · isGenerating → 重建后流式状态丢失，停止按钮变回发送按钮
    //
    //   ★ 注意 messages / conversations 不在这里 —— 它们已经从 Room 重新加载，
    //     不需要也不应该塞进 Bundle（Bundle 有 1MB 事务上限，长对话会炸 TransactionTooLargeException）。
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    // ---- 工作区（按对话绑定）----
    //
    // ★ 这三个是**当前会话的元信息快照**，不是全局设置：
    //   工作区 id 与权限档位都存在 conversation 表上（每对话一套）。
    //   这里只在切换会话 / 改动后重新载入，不在每帧读库。
    // ★ 声明在 activeId **之后**：Kotlin 局部声明不提升，refresh 里要用 activeId。
    var workspaceOptions by remember {
        mutableStateOf<List<com.chatmobile.ui.chat.WorkspaceOption>>(emptyList())
    }
    var currentWorkspaceId by remember { mutableStateOf<String?>(null) }
    var currentPermissionValue by remember { mutableStateOf<String?>(null) }

    /** 工作区文件系统的唯一入口。 */
    val workspaceFs = remember { com.chatmobile.workspace.WorkspaceFs(context) }

    /** 当前会话的权限档位（null ⇒ 默认档）。 */
    fun currentPermission(): com.chatmobile.workspace.WorkspacePermission =
        com.chatmobile.workspace.WorkspacePermission.fromName(currentPermissionValue)

    /**
     * 重新载入「当前会话」的工作区元信息 + 可选工作区列表。
     *
     * ★ 为什么集中成一个函数：切换会话、改权限、改工作区、新建工作区后**都要刷**，
     *   散着写必然漏一处，而漏掉的表现是"设置看着没生效"—— 用户会以为功能坏了。
     */
    suspend fun refreshActiveConversationMeta(convId: String? = activeId) {
        runCatching {
            val rows = RepoHolder.get(context).workspaceDao().all()
            workspaceOptions = rows.map { w ->
                val kind = com.chatmobile.data.WorkspaceKind.fromPersisted(w.kind)
                val dir = workspaceFs.workingDir(w.id, kind)
                com.chatmobile.ui.chat.WorkspaceOption(
                    id = w.id,
                    name = w.name.ifBlank { w.id.take(8) },
                    isShared = kind == com.chatmobile.data.WorkspaceKind.SHARED,
                    fileCount = workspaceFs.listFiles(dir).size,
                )
            }
            val conv = convId?.let { repo.conversation(it) }
            currentWorkspaceId = conv?.workspaceId
            currentPermissionValue = conv?.workspacePermission
        }.onFailure {
            Log.w("ChatHost", "工作区元信息载入失败（弹窗会显示为空）", it)
        }
    }

    val sidebarState = rememberSidebarState()

    /**
     * 位置权限的申请器 —— 必须在 [requestLocationPermissionOnce] 之前声明
     * （Kotlin 局部函数不提升，同文件内的 `val` 也一样：先声明后使用）。
     *
     * 结果只有一个用途：**不处理**。用户给不给都行，
     * 给了提示词里就有位置，没给就写 unavailable。所以回调体是空的 ——
     * 这不是漏写，是刻意的：这里没有任何"被拒绝后要补救"的逻辑。
     */
    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* 给不给都不影响聊天，见 RuntimeFactsProvider */ }

    // ---------------------------------------------------------------
    // 局部工具函数
    //
    // ★ 必须声明在**使用之前**：Kotlin 的局部函数不参与提升，
    //   LaunchedEffect 里调它俩，就得先有定义。
    // ---------------------------------------------------------------

    suspend fun refreshConversations() {
        val list = repo.listConversations()
        conversations.clear()
        conversations.addAll(list.map { Conversation(id = it.id, title = it.title, updatedAt = it.updatedAt) })
    }

    /**
     * 新建工作区。
     *
     * @param treeUri 非空 = 共享目录（用户用 SAF 选的）；空 = App 私有目录。
     *
     * ★ SAF 的授权必须 `takePersistableUriPermission` —— 不 take 的话
     *   **进程重启后权限就没了**，而 `DocumentFile` 那时只会"看起来能打开但写不进去"，
     *   表现为**静默推送失败**。这是 SAF 最经典的坑。
     * ★ take 失败不阻断创建：私有目录那条路仍然可用，只是这个共享工作区会不可用，
     *   所以要在结果里如实体现（下次推送时会报"权限已失效，请重新选择"）。
     */
    suspend fun createWorkspace(name: String, treeUri: android.net.Uri?) {
        runCatching {
            val id = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val kind = if (treeUri == null) {
                com.chatmobile.data.WorkspaceKind.PRIVATE
            } else {
                com.chatmobile.data.WorkspaceKind.SHARED
            }

            if (treeUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure {
                    Log.w("ChatHost", "SAF 持久授权失败 —— 重启后这个工作区会不可用", it)
                }
            }

            // 先把目录建出来，这样设置页能立刻显示"0 个文件"而不是报错
            workspaceFs.workingDir(id, kind).mkdirs()

            RepoHolder.get(context).workspaceDao().upsert(
                com.chatmobile.data.WorkspaceEntity(
                    id = id,
                    name = name.ifBlank {
                        treeUri?.let { workspaceFs.guessDisplayName(it) } ?: "私有工作区"
                    },
                    kind = kind.persisted,
                    treeUri = treeUri?.toString(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.onFailure { Log.w("ChatHost", "新建工作区失败", it) }
        refreshActiveConversationMeta()
    }

    /**
     * 冷启动时申请一次「大致位置」权限。
     *
     * ★★ 三条设计决定：
     *
     * 1. **只问 COARSE（大致位置），不问精确位置。**
     *    Android 12+ 允许用户只给粗略位置，系统弹窗会直接提供"大致位置"这个选项。
     *    我们的用途只是"知道用户在哪个城市"，精确坐标毫无额外价值 —— 问了就是越权。
     *
     * 2. **只在冷启动问一次，重建不问。**
     *    由上面的 `coldStarted` 标记保证。旋转屏幕 / 切深色模式都算 Activity 重建，
     *    在那时候弹权限框会非常突兀。
     *
     * 3. **被拒绝也完全不拦功能。**
     *    没有这个权限时 `RuntimeFactsProvider` 会在提示词里写
     *    "Location: unavailable — do not guess it"，模型照常回答。
     *    所以这里不需要任何后续提示或重试逻辑。
     *
     * ★ 为什么用 `rememberLauncherForActivityResult` 而不是 `requestPermissions`：
     *   Compose 里前者把结果回调带回来，且能跟着组合生命周期自动清理；
     *   后者要自己处理 onRequestPermissionsResult。
     */
    fun requestLocationPermissionOnce() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
            .onFailure { Log.w("ChatHost", "位置权限申请失败（不影响聊天）", it) }
    }

    suspend fun loadMessages(conversationId: String) {
        withContext(Dispatchers.IO) {
            val rows = repo.latestMessages(conversationId, limit = 200)
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(rows.map { it.toUi() })
            }
        }
    }

    /**
     * 读出本会话已有的压缩记录，拼成 system 前缀。
     *
     * ★ 为什么可以有多条：长对话会**逐步**压缩（先折前 20 条，再折接下来 20 条），
     *   每条只覆盖一段。全部拼起来才是完整的历史摘要。
     */
    suspend fun buildCompressionPrefix(conversationId: String): CompressionPrefix {
        val spans = RepoHolder.get(context).compressedSpanDao().forConversation(conversationId)
        if (spans.isEmpty()) return CompressionPrefix("", emptySet())

        // ★ 已被覆盖的消息：查出每段对应的消息 id 范围。
        //   这里不用"范围比较"是因为消息 id 是 UUID（不保序），
        //   只能靠 DB 里 seq 来定区间。
        val covered = mutableSetOf<String>()
        for (s in spans) {
            runCatching {
                covered += repo.idsBetween(conversationId, s.fromMessageId, s.toMessageId)
            }
        }

        val text = buildString {
            appendLine("以下是本次对话较早部分的摘要（原文仍在，需要时可以让用户翻回历史）。")
            spans.forEachIndexed { i, s ->
                appendLine()
                appendLine("【第 ${i + 1} 段，共 ${s.messageCount} 条消息】")
                appendLine(s.summary)
            }
        }
        return CompressionPrefix(text.trimEnd(), covered)
    }

    /**
     * 判断并执行压缩。
     *
     * ★ 三条判据（照 billion-context 的三条件）：
     *   ① 估算 token 超过预算的 70%
     *   ② 确实有可压内容（条数 > 保留尾巴）
     *   ③ 尚未覆盖的部分还够压
     *
     * ★ 失败静默：压缩是**优化**，不是功能。它挂了对话必须照常能发。
     */
    suspend fun maybeCompress(
        conversationId: String,
        preset: com.chatmobile.net.ProviderPreset,
        endpoint: com.chatmobile.net.Endpoint,
        profile: ModelProfile,
        /**
         * 本轮**还要额外发出去**的 system 前缀（记忆 + 已有摘要）的估算 token。
         *
         * ★★★ 缺口 3 的修法。以前这里只算 `allMessages` 的正文，**没算记忆、
         *   也没算摘要本身**，可这两块每一轮都真的发出去。
         *   极端情况：记忆 8000 + 摘要累积几千 + 历史刚过 70% 线
         *   ⇒ **实际请求已经超窗，而压缩认为还没到**。
         *
         *   默认 0 是为了兼容旧调用（单测里不需要模拟前缀）。
         */
        systemOverheadTokens: Int = 0,
    ) {
        runCatching {
            val allMessages = repo.latestMessages(conversationId, limit = 500)
                .filter { it.content.isNotBlank() }
            if (allMessages.size <= compressor.config.keepRecentMessages) return@runCatching

            // ★ 正文 + 会一起发出去的 system 前缀 = 真正的已用量
            val bodyTokens = estimateTokens(allMessages.joinToString("") { it.content })
            val usedTokens = bodyTokens + systemOverheadTokens
            val should = compressor.shouldCompress(
                usedTokens = usedTokens,
                // ★ 用**这份配置实际生效的窗口**，不是写死的常量 ——
                //   用户把上限调大之后，压缩触发点必须跟着往后推，
                //   否则 200K 的模型还在 32K 就压缩，等于白买窗口。
                budgetTokens = effectiveContextBudget(profile),
                messageCount = allMessages.size,
            )
            if (!should) return@runCatching

            // 已覆盖的部分不再重复压（否则会压出越来越长的摘要堆）
            val already = buildCompressionPrefix(conversationId).coveredMessageIds
            val remaining = allMessages.filter { it.id !in already }
            if (remaining.size <= compressor.config.keepRecentMessages) return@runCatching

            val spanCount = compressor.spanToCompress(remaining.size)
            if (spanCount <= 0) return@runCatching
            val toFold = remaining.take(spanCount)
            val first = toFold.first()
            val last = toFold.last()

            // ---- 构造摘要请求 ----
            //
            // ★ 摘要器输入**自己要有预算**（OpenClaw 的做法：封顶 40K token）。
            //   不设预算的话，压缩请求自己就会超窗 —— 那正是 Summary Paradox。
            val sb = StringBuilder()
            var used = 0
            var included = 0
            for (m in toFold) {
                val who = if (m.roleEnum == MessageRole.USER) "用户" else "助手"
                val line = "$who：${m.content}\n"
                val cost = estimateTokens(line)
                if (used + cost > compressor.config.summarizerInputBudgetTokens) break
                sb.append(line)
                used += cost
                included++
            }
            // ★ 缺口显式计数，明令不要猜（照 OpenClaw）
            val dropped = toFold.size - included
            sb.append(compressor.buildGapNotice(dropped))

            val provider = ProviderFactory.createFromPreset(
                preset = preset,
                apiKey = profile.apiKey,
                endpoint = endpoint,
                model = profile.model,
            )

            val req = ChatRequest(
                model = profile.model,
                messages = listOf(
                    NetChatMessage.system(chatmobile.memory.COMPRESS_SYSTEM_PROMPT),
                    NetChatMessage.user(sb.toString()),
                ),
                temperature = 0.0,
                maxTokens = compressor.config.summaryMaxTokens,
            )

            val out = StringBuilder()
            provider.stream(req).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> out.append(ev.text)
                    is ChatEvent.Error -> { /* 放弃本轮压缩 */ }
                    else -> Unit
                }
            }

            val summary = out.toString().trim()
            if (summary.isBlank()) return@runCatching

            // ---- 落库 ----
            RepoHolder.get(context).compressedSpanDao().upsert(
                CompressedSpanEntity.from(
                    conversationId = conversationId,
                    span = chatmobile.memory.CompressedSpan(
                        id = java.util.UUID.randomUUID().toString(),
                        summary = summary,
                        fromMessageId = first.id,
                        toMessageId = last.id,
                        messageCount = toFold.size,
                        approxTokens = usedTokens - estimateTokens(
                            remaining.takeLast(compressor.config.keepRecentMessages)
                                .joinToString("") { it.content },
                        ),
                        createdAt = System.currentTimeMillis(),
                    ),
                ),
            )

            // ★★★ 缺口 1 的落点：**把压缩产物写进记忆库**。
            //
            //   用户原话：「……同时也利用它的压缩去把东西写进记忆库里面」。
            //   在这之前，摘要只在**本次会话**的 system 前缀里活着，
            //   开一个新对话就再也见不到了 —— 记忆库恰恰是跨会话的那一层，
            //   两者互不知道对方存在，那条连接根本不存在。
            //
            //   ## 为什么写 RECENT 而不是 SAVED
            //   - 摘要是"模型的转述"，不是用户明确要求记住的东西
            //     ⇒ 不该占 SAVED 那个"只有用户能删"的层
            //   - 写 RECENT 后它自动进衰减体系：反复被召回 ⇒ hitCount 涨
            //     ⇒ `autoPromote()` 升成 SAVED；没人用就慢慢淡出
            //   ★ 这是"让压缩产物自己证明自己有用"，而不是一次性硬塞进去。
            //
            //   ## 为什么前面加「本对话早前部分」前缀
            //   记忆库是**跨会话**读的。一条孤零零的摘要放进别的对话里，
            //   模型不知道它是"哪段对话的什么"。加前缀 = 给它上下文。
            val memoryOfSummary = "用户在一段较早的对话中聊到（已压缩成摘要）：" +
                summary.take(MEMORY_FROM_SUMMARY_MAX_CHARS)
            runCatching {
                memoryBank.recordRecent(
                    content = memoryOfSummary,
                    sourceConversationId = conversationId,
                    sourceMessageId = last.id,
                )
                // 摘要也算"出现过一次"，让它在同内容再次被抽到时能累加 hitCount
                memoryBank.autoPromote()
                refreshMemories()
            }.onFailure {
                // ★ 单独 catch：写记忆失败**不能**影响压缩本身
                Log.w("ChatHost", "摘要写进记忆库失败（压缩本身已完成）", it)
            }

            Log.i("ChatHost", "已压缩 ${toFold.size} 条消息（缺口 $dropped 条，摘要 ${summary.length} 字）")
        }.onFailure {
            Log.w("ChatHost", "压缩失败（不影响对话）", it)
        }
    }

    /**
     * 记忆库快照 —— 给"记忆管理员"看的已有内容。
     *
     * ★ 这是本轮的关键修复：以前抽取器**只看新对话**，不知道库里已经有什么，
     *   于是它只能逐条往外吐事实；重叠与否全靠字符串去重兜底。
     *   现在把快照喂进去，模型可以自己判断"这条已经被记过了""这两条该合并"。
     */
    fun memorySnapshotText(limit: Int = 60): String {
        val alive = memoryBank.list().take(limit)
        if (alive.isEmpty()) return "(空)"
        return alive.joinToString("\n") { e ->
            val key = e.key?.let { "($it)" } ?: ""
            "- [${e.id}] $key ${e.content}"
        }
    }

    /** 执行记忆管理员给的 op 列表。失败的单条不影响其余。 */
    fun applyMemoryOps(
        ops: List<MemoryOp>,
        conversationId: String?,
        sourceIdPrefix: String,
    ) {
        val now = System.currentTimeMillis()
        ops.forEachIndexed { index, op ->
            val src = "$sourceIdPrefix#$index"
            runCatching {
                when (op.op) {
                    "ADD" -> {
                        val text = op.content
                        if (!text.isNullOrBlank()) {
                            memoryBank.recordRecent(
                                content = text,
                                sourceConversationId = conversationId,
                                sourceMessageId = src,
                                now = now,
                                key = op.key,
                            )
                        }
                    }
                    "UPDATE" -> {
                        val text = op.content
                        if (!text.isNullOrBlank()) {
                            val key = op.key
                            val byId = op.ids.firstOrNull()
                            if (byId != null && memoryBank.update(byId, text, key) == null) {
                                // id 不存在（模型抄错）⇒ 退回按 key 写入
                                memoryBank.recordRecent(text, conversationId, src, now, key)
                            } else if (byId == null) {
                                memoryBank.recordRecent(text, conversationId, src, now, key)
                            }
                        }
                    }
                    "MERGE" -> {
                        val ids = op.ids.filter { id -> memoryBank.list().any { it.id == id } }
                        if (ids.size >= 2) memoryBank.mergeGroup(ids, now, op.content)
                    }
                    else -> Unit // SKIP / 未知 op
                }
            }.onFailure { Log.w("ChatHost", "单条记忆操作失败：${op.op}", it) }
        }
    }


    /**
     * 攒够轮数就抽一次记忆。
     *
     * ★ 为什么必须有抽取：`MemoryBank` 只是"存/取"的容器，
     *   它自己**不会**从对话里长出记忆。没有抽取，记忆库永远是空的 ——
     *   用户会觉得"这功能没做"。
     *
     * ★ 为什么这次要真的调 API：抽取是一次判断题（这句话值不值得记），
     *   正则/关键词做不到（"我叫小明"和"今天天气不错"没法用规则区分）。
     *
     * 失败静默：抽取是**尽力而为**，它挂了不能让用户看到任何报错。
     */
    /**
     * 整理整库（面板上的「整理」按钮）。
     *
     * 只喂快照、不喂新对话 —— 让模型专注"合并重复/改写成一条"。
     */
    suspend fun consolidateMemories(
        preset: com.chatmobile.net.ProviderPreset,
        endpoint: com.chatmobile.net.Endpoint,
        profile: ModelProfile,
    ) {
        if (memoryBank.list().size < 2) return
        runCatching {
            // ★ 整理用「记忆模型」里配的那一个，不跟对话模型绑定。
            val memState = settings.load()
            val curator = resolveMemoryProfile(memState.curatorModel, memState.profiles, memState.active)
                ?: profile
            val curatorPreset = Providers.byId(curator.providerId) ?: preset
            val curatorEndpoint = Providers.resolveEndpoint(curator.providerId, curator.endpointUrl)
                ?: curatorPreset.defaultEndpoint

            val provider = ProviderFactory.createFromPreset(
                preset = curatorPreset,
                apiKey = curator.apiKey,
                endpoint = curatorEndpoint,
                model = curator.model,
                sessionId = profile.id,
            )
            val req = ChatRequest(
                model = profile.model,
                messages = listOf(
                    NetChatMessage.system(memoryCuratorPrompt(memoryBank.config.extractMaxOps, memoryBank.config.maxEntryChars)),
                    NetChatMessage.user(
                        "【已有记忆】\n" + memorySnapshotText(80) +
                            "\n\n【新增对话】\n(无新对话。只整理已有记忆：把说同一件事的多条合并成一条，" +
                            "优先用 MERGE；必要时用 UPDATE 改写得更准确。不要凭空新增事实。)",
                    ),
                ),
                temperature = 0.0,
                maxTokens = (memoryBank.config.extractMaxOps + 4) * 60,
            )
            val sb = StringBuilder()
            provider.stream(req).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> sb.append(ev.text)
                    else -> Unit
                }
            }
            applyMemoryOps(parseMemoryOps(sb.toString()), conversationId = null, sourceIdPrefix = "consolidate")
            memoryBank.autoPromote()
            refreshMemories()
        }.onFailure {
            Log.w("ChatHost", "记忆整理失败（不影响使用）", it)
        }
    }

    suspend fun maybeExtractMemory(
        conversationId: String,
        preset: com.chatmobile.net.ProviderPreset,
        endpoint: com.chatmobile.net.Endpoint,
        profile: ModelProfile,
    ) {
        // 轮数门：本会话消息数不足就跳过。省一次请求。
        // ★ 总开关关闭时**连抽取都不做** —— 用户关的是"记忆"这件事，不只是注入。
        if (!runCatching { settings.isMemoryEnabled() }.getOrDefault(true)) return

        val eligible = messages.filter { !it.isStreaming && it.text.isNotBlank() }
        if (eligible.size < memoryBank.config.extractEveryTurns) return

        // ★★★ 抽取水位线（本轮修「同一件事重复 9 遍」的根因）
        //
        //   旧写法：每 6 条触发一次，窗口 `takeLast(12)` ⇒ **相邻两次窗口重叠一半**。
        //   重叠输入喂给 temperature=0 的抽取器，会产出措辞略有差异的同一批事实；
        //   而 MemoryBank 原来的去重只认字面相同 ⇒ 判不出来 ⇒ 同一件事进库 N 条。
        //
        //   现在：水位线 = 记忆库里**已经带过 sourceMessageId 的最后一条消息**，
        //   只抽它之后新增的消息。不新增任何状态 —— 水位天然随记忆一起持久化，
        //   进程被杀/重启也不会倒退。
        //
        //   ★ 老库里的条目 sourceMessageId 可能是 null（本轮之前写的），
        //     此时 coveredIndex = -1，会从头发一遍 —— 靠 MemoryBank 的近义去重兜住，
        //     顺便把历史重复条目合并掉。
        val coveredIds = memoryBank.list().mapNotNull { it.sourceMessageId }.toSet()
        val coveredIndex = eligible.indexOfLast { it.id in coveredIds }
        val fresh = eligible.drop(coveredIndex + 1)
        if (fresh.size < memoryBank.config.extractEveryTurns) return

        runCatching {
            // ★ 记忆管理员可以**不跟随**对话模型（用户要求独立配置）：
            //   这里按「记忆模型」配置解析，解析失败才退回当前对话模型。
            val memState = settings.load()
            val curator = resolveMemoryProfile(memState.curatorModel, memState.profiles, memState.active)
                ?: profile
            val curatorPreset = Providers.byId(curator.providerId) ?: preset
            val curatorEndpoint = Providers.resolveEndpoint(curator.providerId, curator.endpointUrl)
                ?: curatorPreset.defaultEndpoint

            val provider = ProviderFactory.createFromPreset(
                preset = curatorPreset,
                apiKey = curator.apiKey,
                endpoint = curatorEndpoint,
                model = curator.model,
                // ★ 抽取请求同样要带会话 ID（同一对话 → 同一 ID）
                sessionId = conversationId,
            )

            // 只喂**新增里最靠前的 12 条**（按时间顺序），不是最后 12 条：
            // 这样水位线每次只前进一个窗口，剩下的下一轮再抽，不会跳过中间的消息。
            val window = fresh.take(memoryBank.config.extractWindow)
            val recent = window.joinToString("\n") { m ->
                val who = if (m.role == ChatMessage.Role.User) "用户" else "助手"
                "$who：${m.text.take(500)}"
            }

            if (recent.isBlank()) return@runCatching

            // ★ 这一批消息的统一来源 id：同一次抽取重试时会命中 MemoryBank 的
            //   「同来源幂等」分支，不会因为重试多长出一条。
            val windowSourceId = window.last().id

            val req = ChatRequest(
                model = profile.model,
                messages = listOf(
                    NetChatMessage.system(memoryCuratorPrompt(memoryBank.config.extractMaxOps, memoryBank.config.maxEntryChars)),
                    NetChatMessage.user(
                        "【已有记忆】\n" + memorySnapshotText() + "\n\n【新增对话】\n" + recent,
                    ),
                ),
                temperature = 0.0,   // 抽取/整理要确定性，不要发挥
                // 输出预算跟着「一次最多几条 op」走：每条约 60 token，多留 2 条余量
                maxTokens = (memoryBank.config.extractMaxOps + 2) * 60,
            )

            val sb = StringBuilder()
            provider.stream(req).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> sb.append(ev.text)
                    is ChatEvent.Error -> { /* 失败就放弃这轮 */ }
                    else -> Unit
                }
            }

            applyMemoryOps(
                ops = parseMemoryOps(sb.toString()),
                conversationId = conversationId,
                sourceIdPrefix = windowSourceId,
            )
            // 抽完做一次自动提升（命中够次的 recent → saved）
            memoryBank.autoPromote()
            // ★ 刷新界面列表：用户可能正开着记忆页，抽到新条目要能立刻看到
            refreshMemories()
        }.onFailure {
            Log.w("ChatHost", "记忆抽取失败（不影响对话）", it)
        }
    }

    // ---------------------------------------------------------------
    // 启动：崩溃恢复 + 选一个会话
    // ---------------------------------------------------------------
    // ★★ `rememberSaveable` 而不是 `remember`，是为了区分「冷启动」和「Activity 重建」。
    //
    //   若用 `LaunchedEffect(Unit)`：
    //     · Activity 重建（旋转/深色模式）也会重跑它
    //     · 于是 activeId 被强制改回 list.first() —— 用户明明在会话 B，旋转一下跳到 A
    //     · 且 recoverInterruptedStreams() 会再扫一遍
    //
    //   加了这个标记后：重建时 activeId 从 Bundle 恢复，这里直接跳过，
    //   messages 由下面的 else 分支按恢复后的 activeId 重新加载。
    val coldStarted = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!coldStarted.value) {
            coldStarted.value = true
            // ★ 上次进程被杀时，库里会留下 status=streaming/pending 的行。
            //   不扫一遍的话，界面会把"说到一半的话"当成完整回答显示。
            //   data 层 README §7 明确要求 App 启动时调一次。
            repo.recoverInterruptedStreams()

            // ★★ 记忆必须在这里载入（`RoomMemoryStore.load()` 的契约是"启动时调一次"）。
            //
            //   不载入的后果很具体：`MemoryBank` 读的是 store 的**内存缓存**，
            //   缓存空 ⇒ 冷启动后模型一条记忆都看不到，用户会以为"它把我说的忘了"。
            //   而且这不会报错，只会**安静地失效** —— 只有真机用过几轮才发现。
            runCatching { memoryStore.load() }
                .onFailure { Log.w("ChatHost", "记忆载入失败（冷启动后记忆为空）", it) }
            // 载入完立刻同步一次界面列表，否则设置里的「记忆 N 条」永远是 0
            refreshMemories()

            // ★ 位置权限：冷启动时问一次（见权限申请段落）。重建不重复问。
            requestLocationPermissionOnce()

            // ★ 工具配置：冷启动载入一次快照。
            //   不载入的话，工具设置页会显示全关（map 为空 ⇒ 走默认值，
            //   而默认值只有 ChatTools 知道），用户会以为配置丢了。
            runCatching { settings.toolEnabledMap() }
                .onSuccess { toolEnabledMap = it }
                .onFailure { Log.w("ChatHost", "工具配置载入失败，按默认值显示", it) }
            runCatching { settings.toolResultChars() }
                .onSuccess { toolResultChars = it }

            val list = repo.listConversations()
            if (list.isEmpty()) {
                // 全新安装：建一个会话，再放一条欢迎消息（它也会落库）
                val conv = repo.createConversation(title = "新对话")
                repo.appendMessage(
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = WELCOME,
                    status = MessageStatus.DONE,
                )
                activeId = conv.id
            } else {
                activeId = list.first().id
            }
        }
        // 重建路径也走这里：activeId 已是 Bundle 里恢复的值
        refreshConversations()
        activeId?.let { loadMessages(it) }
    }

    /** 把一条助手消息落成 ERROR 状态，正文写错误原因（用户回头看时仍能看见为什么失败）。 */
    suspend fun finishWithError(assistantId: String, message: String) {
        val idx = messages.indexOfFirst { it.id == assistantId }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(text = message, isStreaming = false)
        }
        runCatching {
            repo.streamingWriter(assistantId).apply {
                resetContent(message)
                fail(message)
            }
        }
    }

    suspend fun runStreaming(conversationId: String, assistantId: String, prompt: String) {
        // ★ 多配置模型：从 SettingsState 里取**当前选中的那一份**。
        //   `null` = 一份都没配；`!isComplete` = 那份缺 Key 或模型名。
        //   两种都提示去配置，但文案要能区分 —— 用户照着做才找得到地方。
        val active = settings.load().active
        if (active == null) {
            finishWithError(
                assistantId,
                "还没有配置模型。\n\n点左上角菜单 → 设置 → 模型，添加一份配置（厂商 + API Key + 模型名）。",
            )
            return
        }
        if (!active.isComplete) {
            finishWithError(
                assistantId,
                "当前这份配置还没填完（缺 API Key 或模型名）。\n\n点左上角菜单 → 设置 → 模型，展开它补齐。",
            )
            return
        }

        val preset = Providers.byId(active.providerId)
        if (preset == null) {
            finishWithError(
                assistantId,
                "厂商 `" + active.providerId + "` 不在预置列表里，请到设置里重新选择。",
            )
            return
        }

        // ★ 用唯一入口解析接入点：自定义厂商的手填地址也要能通
        //   （旧写法 `firstOrNull { baseUrl == ... } ?: defaultEndpoint` 会把
        //    手填地址静默丢掉，最后请求发到空字符串 —— 见 Providers.resolveEndpoint）
        val endpoint = Providers.resolveEndpoint(active.providerId, active.endpointUrl)
        if (endpoint == null) {
            finishWithError(
                assistantId,
                "这份配置的 API 地址为空。\n\n点左上角菜单 → 设置 → 模型，展开它补齐。",
            )
            return
        }

        val provider = try {
            ProviderFactory.createFromPreset(
                preset = preset,
                apiKey = active.apiKey,
                endpoint = endpoint,
                model = active.model,
                // ★★★ 会话 ID 用 **conversationId** —— 这是全项目唯一"天然稳定"的 ID。
                //
                //   OpenCode（Zen 与 Go 都是）要求 `x-opencode-session`，
                //   原文：「Request is missing x-opencode-session and cannot be
                //   routed efficiently.」不带就是 **400**。
                //
                //   ★ 必须 stable：它拿这个 ID 做**路由**与 **prompt 缓存**。
                //     每轮随机 = 每轮都是新会话 ⇒ 缓存全失效，还可能被判成异常流量。
                //     conversationId 恰好一轮对话内不变、换对话才变 —— 正是要的语义。
                sessionId = conversationId,
            )
        } catch (e: Throwable) {
            finishWithError(assistantId, "无法创建该厂商的连接：" + (e.message ?: e::class.simpleName))
            return
        }

        // ★ 上下文：把本会话已有消息都带上（含刚插的用户那条）。
        val historyAll = messages.filter { !it.isStreaming }

        // ★★★ 记忆注入 —— 这是「记忆库接入」的落点
        //
        //   三个设计决定，每个都有理由：
        //
        //   ① **放进 system 消息，不塞进 history。**
        //      塞进 history 等于伪造对话记录：模型会以为用户真的说过那些话，
        //      而且它会进 Room 的上下文、被下一次压缩再摘一遍。
        //      system 是"本轮附加设定"，天然是临时的、不落库的。
        //
        //   ② **预算按 token 算，不按条数。**
        //      `composeContext` 已经做了预算切分（saved 15% / recent 10%）。
        //      ★ 缺口 2 的修法：这里传的是 `effectiveMemoryBudget(active)`，
        //        它按**这份配置的真实窗口**算（1M 窗口 ⇒ 25 万记忆预算），
        //        不再写死 32000。小窗口有 8000 的下限兜底。
        //
        //   ③ **`touch = true`**：被召回的记忆要更新 `lastUsedAt` / `hitCount`，
        //      否则衰减权重永远不会反映"这条最近被用过"。
        val memoryText = try {
            if (settings.isMemoryEnabled()) {
                // ★ 传入本轮用户输入作为检索意图：记忆条数超过上限时按相关性挑，
                //   而不是只按时间挑（见 MemoryBank.composeContext 注释）。
                //
                // ★★ 必须在后台线程算：向量后端是**同步**推理（本机实测首次 266ms），
                //    直接在 Main 上跑会让发送后卡住半秒以上。降级到字面检索时它也只是一次
                //    字符串比较，放 Default 上没有代价。
                withContext(Dispatchers.Default) {
                    memoryBank.composeContext(
                        totalContextTokens = effectiveMemoryBudget(active),
                        query = prompt,
                    ).text
                }
            } else {
                // ★ 总开关关闭：不注入、也不 touch（不更新命中数）
                ""
            }
        } catch (e: Throwable) {
            Log.w("ChatHost", "记忆组装失败，本轮不带记忆", e)
            ""
        }

        // ★★★ 压缩：把已被摘要代表的那段历史换成摘要，不再原样喂全文
        //
        //   ★ 关键：**原文没有删**。`compressed_spans` 只记"这段已被摘要代替"，
        //     messages 表里那些行原封不动。
        //
        //   ★ 顺序：**先把"本轮一定会发出去的 system 前缀"算出来**，
        //     再交给压缩判断（缺口 3）。以前是先压缩、后构建前缀，
        //     于是压缩永远看不见记忆与摘要占了多少 token。
        //
        //   ★ 但**压缩本身仍要先跑**（用旧的摘要做估算即可）——
        //     否则"这一轮用的是上一轮的压缩结果"，永远慢一轮。
        //     两次 buildCompressionPrefix 不贵（都是本地查库）。
        val compress = try {
            buildCompressionPrefix(conversationId)
        } catch (e: Throwable) {
            Log.w("ChatHost", "压缩前缀预读失败", e)
            CompressionPrefix("", emptySet())
        }

        maybeCompress(
            conversationId = conversationId,
            preset = preset,
            endpoint = endpoint,
            profile = active,
            // ★ 记忆 + 已有摘要 —— 这两块每轮都真的发出去，必须计入水位
            systemOverheadTokens =
                estimateTokens(memoryText) + estimateTokens(compress.text),
        )

        // 压缩可能刚刚新增了一段，重新读一次（这次可能比上面看到的多）
        val compression = try {
            buildCompressionPrefix(conversationId)
        } catch (e: Throwable) {
            Log.w("ChatHost", "压缩前缀构建失败，本轮退化为全文", e)
            CompressionPrefix("", emptySet())
        }

        // 已被摘要覆盖的消息不再作为独立消息发出（摘要里已经有它们）
        val historyForRequest = historyAll
            .filter { it.id !in compression.coveredMessageIds }
            .map { m ->
                when (m.role) {
                    ChatMessage.Role.User -> NetChatMessage.user(m.text)
                    ChatMessage.Role.Assistant -> NetChatMessage.assistant(m.text, m.reasoning)
                }
            }

        // ★★★ 消息顺序 = 缓存命中率。**这一段是整个缓存设计最关键的代码。**
        //
        //   所有主流厂商（DeepSeek context caching / OpenAI automatic prompt caching /
        //   Anthropic prompt caching）都是**从第一个 token 起逐字节比对最长公共前缀**，
        //   命中部分按缓存价计费。于是有一条硬规矩：
        //
        //   > **越靠前的内容必须越稳定。任何"每轮都变"的东西放在前面，
        //   >   它后面的所有内容都会失去缓存。**
        //
        //   按"稳定性从高到低"排：
        //
        //   | 位置 | 内容 | 变化频率 | 为什么放这 |
        //   |---|---|---|---|
        //   | ① | [SYSTEM_PROMPT_STATIC] | **永不变** | 常量，天然是整个前缀的基座 |
        //   | ② | 压缩摘要 | **只追加** | 新压缩只会往末尾加一段，前面不变 ⇒ 前缀仍命中 |
        //   | ③ | 历史消息 | **只追加** | 对话是 append-only 的 |
        //   | ④ | 记忆文本 | **每轮都变** | 衰减权重/命中数/新抽取条目都会变 ⇒ 必须排最后 |
        //   | ⑤ | 最后一条 user | 本轮新增 | 本来就不可能在缓存里 |
        //
        //   ★★★ 我们**曾经把记忆放在第 ① 位**（`system(memoryText)` 在最前）。
        //     那等于每轮都让整个 system 前缀失效 —— 而 memories 每轮必变
        //     （`touch=true` 会改 `lastUsedAt`/`hitCount`），
        //     连带后面**整段历史**一起无法命中缓存。这是最贵的排错位。
        //
        //   ★ 为什么记忆不塞进最后那条 user 消息（那样也能保住前缀）：
        //     那会**伪造用户发言** —— 模型会以为"我问过它我是谁"。
        //     记忆是"本轮附加设定"，必须是 system 角色。
        //
        //   ★ 为什么中间插 system 是安全的：OpenAI 兼容协议允许 system 出现在
        //     任意位置，DeepSeek / OpenRouter / OpenCode 实测都接受。
        val requestMessages = mutableListOf<NetChatMessage>().apply {
            // ① 固定系统提示词 —— 永远是第一条，且与上轮逐字相同
            add(NetChatMessage.system(com.chatmobile.prompt.SystemPrompt.STATIC))
            // ①.5 用户自写提示词（人设/规矩）—— 紧跟固定提示词：
            //     它只在用户编辑时变，位置固定 ⇒ 不打散后面的缓存前缀。
            //     ★ 与记忆严格分开：记忆是"观察到的你"，这里是"你的指令"。
            val customPrompt = settings.load().customPrompt.trim()
            if (customPrompt.isNotEmpty()) add(NetChatMessage.system(customPrompt))
            // ② 压缩摘要（append-only）
            if (compression.text.isNotBlank()) add(NetChatMessage.system(compression.text))
            // ③ 历史（append-only）
            val lastIdx = historyForRequest.lastIndex
            if (lastIdx >= 0) {
                addAll(historyForRequest.subList(0, lastIdx))
                // ④ 记忆 —— 插在最后一条消息**之前**，这样它前面的一切仍可命中
                if (memoryText.isNotBlank()) add(NetChatMessage.system(memoryText))
                // ⑤ 最后一条（通常是刚发的 user）
                add(historyForRequest[lastIdx])
            } else if (memoryText.isNotBlank()) {
                add(NetChatMessage.system(memoryText))
            }
            // ⑥ ★ 运行时事实（本地时间 / 时区 / 语言 / 大致位置）—— **必须是最后一条**。
            //
            //   为什么排在记忆**之后**：记忆每轮也可能变，但它变的是"内容集合"，
            //   而时间**每轮必变**（分钟级）。越变的东西越靠后 —— 时间排最末，
            //   它前面的一切（含记忆）都还能命中缓存。
            //
            //   为什么做成独立 system 而不是塞进最后那条 user：
            //   塞进 user 等于伪造用户发言（模型会以为用户刚说了"现在是11点"）。
            //
            //   为什么不能写进 SystemPrompt.STATIC：
            //   那会让整个 system 前缀每轮失效，连带摘要/历史全部无法命中
            //   （这条在 SystemPrompt 的注释里是红线，真机验证过代价）。
            //
            //   ★ 采集全部包在 runCatching 里（见 RuntimeFactsProvider）——
            //     拿不到位置权限时写 "unavailable"，绝不影响这次发送。
            val runtimeFacts = com.chatmobile.prompt.RuntimeFactsProvider.collect(context)
            if (runtimeFacts.isNotBlank()) add(NetChatMessage.system(runtimeFacts))
        }

        val writer = repo.streamingWriter(assistantId)
        val buf = StringBuilder()
        var lastFlushAt = 0L
        // ★ 诊断：本轮到底收到多少推理字符。0 说明模型没返回思考（换模型/关思考），
        //   >0 但库里为 0 才是我们的写入链路有问题。这两者排查方向完全相反。
        var reasoningChars = 0

        // ★★★ 打一条请求结构日志。
        //
        //   为什么需要：用户会问「模型说它没收到系统提示词，什么情况？」
        //   而**模型无法可靠地内省自己的 system prompt** —— 你问它「你的系统提示词是什么」，
        //   它多半回答"我没有什么系统提示词"。那是模型的自述，**不是事实**。
        //   唯一能证明"到底发了什么"的办法是看请求体本身。
        //
        //   ⇒ 每次发送都打一行：每条消息的角色 + 字符数。
        //     一眼就能看出 system 在不在、是几条、有多长。
        //     不打印正文（记忆里可能有隐私，且日志会落盘）。
        Log.i(
            "ChatHost",
            "请求结构（提示词 ${SystemPrompt.VERSION}）：" +
                requestMessages.mapIndexed { idx, m ->
                    "$idx:${m.role}(${m.content.length}字)"
                }.joinToString(" "),
        )

        // ★★★ 工具表：**这一份字符串在本轮内必须逐字节不变**。
        //
        //   Anthropic 的缓存顺序是 `tools` → `system` → `messages` —— 工具定义
        //   排在系统提示词**之前**。所以工具表一变，后面全部重算。
        //   ⇒ 在循环外算一次，循环内复用同一个 String 实例。
        //
        //   ★ 空表 ⇒ null ⇒ 请求里**不发 tools 字段**（发空数组有些网关 400）。
        val enabledTools = settings.enabledToolNames()
        // ★ 记忆开关每轮读一次就够 —— 发送是一个瞬时动作，
        //   用户不可能在这次请求的中途把它改掉；而且 lambda 里不能调 suspend。
        val memoryOn = runCatching { settings.isMemoryEnabled() }.getOrDefault(true)
        val toolsJson = if (enabledTools.isEmpty()) null else {
            val reg = chatmobile.tools.ToolRegistry()
            chatmobile.tools.ChatTools.all()
                .filter { it.name in enabledTools }
                .forEach { reg.register(it.spec, NOOP_EXECUTOR) }
            reg.toOpenAiToolsJson().takeIf { it != "[]" }
        }
        // ★ 打一行工具表诊断 —— 「模型没用工具」有两种完全不同的原因：
        //   ① 工具表压根没发出去（enabledTools 为空 / 序列化失败）
        //   ② 工具表发了，但模型选择不用
        //   这两者的排查方向相反，不打日志就只能猜。而"猜"正是本项目最贵的错误来源。
        Log.i(
            "ChatHost",
            "工具表：启用 ${enabledTools.size} 个 [${enabledTools.joinToString(",")}]；" +
                "序列化 ${toolsJson?.length ?: 0} 字符",
        )
        val toolHost = com.chatmobile.tools.AndroidToolHost(
            context = context,
            memoryBank = memoryBank,
            memoryEnabled = { memoryOn },
        )
        val toolExecutor = chatmobile.tools.ChatToolExecutor(
            host = toolHost,
            // ★ 用户可调：1M 窗口的模型能多吃，8K 窗口的模型吃多了会爆。
            maxResultChars = runCatching { settings.toolResultChars() }.getOrDefault(8000),
        )

        // ★ 工具循环的轮数上限。
        //   为什么必须有：模型可能反复调用同一个工具（尤其参数一直错的时候），
        //   不设上限会变成一个死循环，把用户的 token 烧光。
        //   8 轮足够覆盖「查时间 → 抓两个网页 → 查记忆 → 存一条 → 作答」这种真实链路。
        var toolRound = 0
        var finalReply = ""
        val enabledSet: Set<String> = enabledTools

        try {
            while (true) {
                val roundBuf = StringBuilder()
                var roundToolCalls: List<com.chatmobile.net.ToolCallRequest> = emptyList()

                provider.stream(
                    ChatRequest(
                        model = active.model,
                        messages = requestMessages,
                        // ★ 思考强度：空串 = **不传该字段**（用服务端默认）。
                        //   有些厂商不认这个字段会直接 400，所以"不传"才是安全默认。
                        reasoningEffort = active.reasoningEffort.takeIf { it.isNotBlank() },
                        tools = toolsJson,
                    ),
                ).collect { ev ->

                    when (ev) {
                        is ChatEvent.Delta -> {
                            roundBuf.append(ev.text)
                            buf.append(ev.text)
                            val idx = messages.indexOfFirst { it.id == assistantId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(
                                    text = buf.toString(),
                                    isStreaming = true,
                                )
                            }
                            // ★ 攒批落库：每个 token 都写库会让主线程外的 IO 被淹没，
                            //   但完全不写又会让"杀进程丢一大段"。120ms 是折中。
                            val now = System.currentTimeMillis()
                            if (now - lastFlushAt >= 120) {
                                lastFlushAt = now
                                writer.resetContent(buf.toString())
                            }
                        }

                        is ChatEvent.Reasoning -> {
                            val idx = messages.indexOfFirst { it.id == assistantId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(
                                    reasoning = (messages[idx].reasoning.orEmpty() + ev.text),
                                    isStreaming = true,
                                )
                            }
                            // ★★★ 必须**同时**写进 StreamingMessageWriter。
                            //
                            //   用户报的 bug 原话：「现在虽然可以打开思考过程，但是只能看到
                            //   最近几条对话的思考，还会迅速失效导致无法查看以前对话的思考过程。」
                            //
                            //   根因就在这里：这一支原来**只改内存里的 messages**，
                            //   从没调过 writer.appendReasoning()。而正文那一支一直有
                            //   writer.resetContent() ⇒ 落库时 reasoning 永远是 null。
                            //
                            //   表现正好对应用户描述：
                            //     · 本轮进行中 —— 内存里有，所以看得到
                            //     · 切会话 / 重启 —— 从库里读回来是 null，思考过程没了
                            //     · "最近几条"还能看 —— 那些还在当前进程的内存列表里
                            //   ⇒ 不是"过期"，是**从来没存下来**。
                            //
                            //   ★ 为什么不套在 resetContent(buf) 里一起写：
                            //     resetContent 会在正文分支被高频调用，而它把 reasoning
                            //     也整段重写一遍。推理内容只追加、不回退，走 appendReasoning
                            //     才是对的（而且两者共用同一次 flush，不额外增加 IO）。
                            writer.appendReasoning(ev.text)
                            reasoningChars += ev.text.length
                        }

                        // ★ 模型要求调工具。**这一轮先不结束** —— 执行完继续下一轮。
                        is ChatEvent.ToolCalls -> {
                            roundToolCalls = ev.calls
                        }

                        is ChatEvent.Error -> {
                            // 已经吐过正文的话，保留正文 + 附上错误原因；一个字都没吐就直接报错
                            val partial = buf.toString()
                            val detail = buildString {
                                if (partial.isNotEmpty()) append(partial).append("\n\n")
                                append("⚠️ 生成中断：").append(ev.message)
                                if (ev.retryable) append("\n（可重试：再点一次发送）")
                            }
                            finishWithError(assistantId, detail)
                            // ★ 不能用裸 return —— 它在 collect 的 lambda 里，只能返回到 lambda。
                            //   要退出外层 suspend 函数必须抛一个受控异常。
                            throw StreamAborted(detail)
                        }

                        is ChatEvent.Finish, ChatEvent.Done -> { }

                        // ★★★ 把缓存命中情况打进日志 —— 这是**唯一能证明前缀缓存真的生效**
                        //   的办法。不做这一步的话，"我改了顺序，缓存应该更好了"
                        //   永远只是一句猜测。
                        is ChatEvent.Usage -> {
                            val hit = ev.cachedInput
                            if (hit != null) {
                                val pct = if (ev.input > 0) hit * 100 / ev.input else 0
                                Log.i(
                                    "ChatHost",
                                    "缓存命中 $hit / ${ev.input} = $pct%" +
                                        "（提示词 ${SystemPrompt.VERSION}，共 ${requestMessages.size} 条消息）",
                                )
                            } else {
                                Log.i(
                                    "ChatHost",
                                    "本次用量 input=${ev.input} output=${ev.output}，" +
                                        "该厂商未上报缓存命中字段",
                                )
                            }
                        }
                    }
                }

                // ---- 这一轮结束了。判断是"要调工具"还是"说完了" ----
                if (roundToolCalls.isEmpty()) {
                    finalReply = buf.toString()
                    break
                }

                toolRound++
                if (toolRound > MAX_TOOL_ROUNDS) {
                    // ★ 宁可停下来告诉模型"轮数用完了"，也不要静默截断 ——
                    //   静默截断会让用户看到一个半截回答，以为模型就是这么答的。
                    Log.w("ChatHost", "工具轮数超限（$MAX_TOOL_ROUNDS），强制收尾")
                    finalReply = buf.toString() +
                        "\n\n（本轮的连续工具调用已达上限 $MAX_TOOL_ROUNDS 次，先停在这里。可以再问一次继续。）"
                    break
                }

                // ① 把「模型要调工具」这条 assistant 消息写进请求历史
                requestMessages += NetChatMessage.assistantToolCalls(roundToolCalls)
                // ★ 也让界面看到"正在用工具"，而不是干等着像卡死
                Log.i(
                    "ChatHost",
                    "工具调用（第 $toolRound 轮）：" +
                        roundToolCalls.joinToString(" ") { it.toolName },
                )

                // ② 逐个执行并回填结果
                for (call in roundToolCalls) {
                    val result = withContext(Dispatchers.IO) {
                        toolExecutor.execute(
                            chatmobile.tools.ToolCall(
                                callId = call.callId,
                                toolName = call.toolName,
                                argumentsJson = call.argumentsJson,
                            ),
                            enabled = enabledSet,
                        )
                    }
                    val text = when (result) {
                        is chatmobile.tools.ToolResult.Success -> result.output
                        is chatmobile.tools.ToolResult.Failure -> "ERROR: " + result.error
                    }
                    // ★ 结果必须以 role:"tool" + tool_call_id 回填，否则模型对不上账。
                    requestMessages += NetChatMessage.toolResult(call.callId, text)
                }
                // ★ 循环继续：带着工具结果再请求一次，让模型基于结果作答。
            }

            // 正常结束
            val idx = messages.indexOfFirst { it.id == assistantId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = finalReply.ifEmpty { buf.toString() },
                    isStreaming = false,
                )
            }
            // ★★★ 收尾时**必须把 reasoning 一起传进去**。
            //
            //   `resetContent(content, newReasoning = null)` 在 `newReasoning == null` 时
            //   会把内存里的 reasoning 清空（见 StreamingMessageWriter:209 `reasoning.setLength(0)`），
            //   下一次 flush 就把库里的 reasoning 写成 null。
            //   ⇒ 这正是用户说的「思考过程迅速失效」的**第二个**原因：
            //     流式期间刚存下的思考，在**收尾那一刻被自己抹掉了**。
            //
            //   所以这里取内存里累积的完整推理传回去 —— 它是这一轮全部 Reasoning 事件的拼接。
            val fullReasoning = messages.firstOrNull { it.id == assistantId }?.reasoning
            writer.resetContent(finalReply.ifEmpty { buf.toString() }, fullReasoning)
            writer.finish()
            Log.i(
                "ChatHost",
                "思考过程：本轮收到 ${reasoningChars} 字，内存累积 ${fullReasoning?.length ?: 0} 字" +
                    (if (reasoningChars == 0) "（模型本轮未返回推理内容 —— 检查是否选了支持思考的模型 / 思考是否被关闭）" else "（应已落库）"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户点了停止 —— 内容保留，标 stopped
            throw e
        } catch (e: StreamAborted) {
            // ★ 已经由 finishWithError 处理过了（含落库），这里只是把控制流带出 collect。
            //   不要再调一次 finishWithError，否则会把内容覆盖成"请求失败：<内部异常>"。
        } catch (e: Throwable) {
            finishWithError(assistantId, (buf.toString().takeIf { it.isNotEmpty() }?.plus("\n\n") ?: "") +
                "⚠️ 请求失败：" + (e.message ?: e::class.simpleName))
        } finally {
            refreshConversations()
            isGenerating = false
            streamJob = null
        }

        // ★★★ 记忆抽取 —— 在流式**结束之后**做，不打断输出。
        //
        //   为什么放这里而不是穿插在流式过程中：
        //     ① 抽取本身是一次**完整的额外请求**，中途插进去会让"停止"按钮的语义变糊
        //        （用户点了停止，到底停的是回答还是抽取？）
        //     ② 抽取失败完全不影响本轮回答，必须隔离
        //
        //   ★ 用 `runCatching` 包住：抽取是**尽力而为**，它挂了绝不能让界面报错。
        maybeExtractMemory(conversationId, preset, endpoint, active)
    }




    // ---------------------------------------------------------------
    // 发送
    // ---------------------------------------------------------------
    fun send(text: String) {
        val convId = activeId ?: return
        isGenerating = true
        streamJob = scope.launch {
            // ★ 两条消息先落库再上屏：这样"发出去一半就被杀进程"也不会丢用户那句。
            val userRow = repo.appendMessage(
                conversationId = convId,
                role = MessageRole.USER,
                content = text,
                status = MessageStatus.DONE,
            )
            val aiRow = repo.appendMessage(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
            )
            messages.add(userRow.toUi())
            messages.add(aiRow.toUi(isStreaming = true))

            // 会话标题：首条消息用它，让侧边栏能认出是哪个会话
            if (messages.count { it.role == ChatMessage.Role.User } == 1) {
                repo.renameConversation(convId, text.take(20))
                refreshConversations()
            }

            // ---- 真实模型调用 ----
            runStreaming(convId, aiRow.id, text)
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        val idx = messages.indexOfFirst { it.isStreaming }
        if (idx >= 0) {
            val partial = messages[idx].text
            val stoppedId = messages[idx].id
            messages[idx] = messages[idx].copy(isStreaming = false)
            // ★ 标成 stopped（不是 done）：界面要能如实区分"说完了"和"被打断"
            scope.launch {
                repo.streamingWriter(stoppedId).apply {
                    resetContent(partial)
                    stop()
                }
            }
        }
        isGenerating = false
    }

    // 全部模型配置 + 当前选中。设置的两层页都要读它，所以提到最外层。
    val settingsState by settings.stateFlow.collectAsStateWithLifecycle(
        initialValue = SettingsState(),
    )

    // =============================================================
    // 返回键：逐层回退（必须在 when **外面**）
    //
    //   第 3 层 模型配置 → 第 2 层 设置
    //   第 2 层 设置     → 第 1 层 主界面 + **侧边栏打开**
    //   第 1 层          → 不拦截，交给 SidebarLayout 自己的 BackHandler
    //                      （侧边栏开着就收它，关着就退到桌面）
    //
    // ★★★ 为什么必须放在 when 外面：
    //   第一版我把它写进了 `NavLayer.Chat ->` 分支里，结果设置页没有任何
    //   BackHandler 在注册 —— 按一次返回就直接退到桌面（真机实测：
    //   从模型配置按返回，直接跳到了别的 App）。
    //   `BackHandler` 的注册是**组合期副作用**，它必须在所有层都参与的
    //   那个层级上，而不是某个分支内部。
    //
    // ★ enabled 的边界：
    //   - 设置两层：true（要拦）
    //   - Chat 层：false（**故意不拦**）—— `SidebarLayout` 内部已有
    //     `BackHandler(enabled = isOpen) { close() }`。两个都拦会让"收侧边栏"
    //     和"退出 App"互相抢，行为取决于注册顺序，出现过"按返回没反应"。
    BackHandler(enabled = layer != NavLayer.Chat) {
        when (layer) {
            NavLayer.ModelConfig -> layer = NavLayer.Settings
            // ★ 记忆页是设置的下级，返回同样回设置
            NavLayer.Memories -> layer = NavLayer.Settings
            // ★ 工具页同样是设置的下级
            NavLayer.Tools -> layer = NavLayer.Settings
            // ★ 工作区页同样是设置的下级
            NavLayer.Workspaces -> layer = NavLayer.Settings
            // ★ 通用页同样是设置的下级
            NavLayer.General -> layer = NavLayer.Settings
            NavLayer.Settings -> {
                // ★ 回到第 1 层时必须恢复侧边栏（用户明确要求：
                //   「设置界面系统返回后应回到侧边栏打开状态的主界面」）。
                //   侧边栏是否该开，取决于**进设置前**它是不是开着的 ——
                //   记录在 sidebarOpenAfterBack 里，由 onOpenSettings 写入。
                layer = NavLayer.Chat
                if (sidebarOpenAfterBack) sidebarState.open()
            }
            NavLayer.Chat -> Unit
        }
    }

    // ★★★ 关键结构：`SidebarLayout` 只包**第 1 层**，不包设置层。
    //
    //   之前把 SettingsScreen 渲染在 SidebarLayout 的 content 槽里，真机上踩到：
    //   uiautomator dump 显示 `content-desc="sidebar-panel"` 的 bounds 是
    //   (0,0)-(1074,2712) —— 侧边栏面板**始终占满左侧**，把设置页的内容全挤到
    //   x>=1074（屏幕外）。表现就是"设置页看得见、但点什么都没反应"，
    //   连返回箭头都要等侧边栏弹簧动画跑完才可能生效。
    //
    //   第 2/3 层本来就是**盖住整个屏幕的独立页**（像 ChatGPT 的设置），
    //   它们不该和侧边栏共存于同一个布局槽。所以：Chat 层外面套 SidebarLayout，
    //   设置层直接铺满整个 modifier。
    when (layer) {
        // =============================================================
        // 第 1 层：聊天 + 侧边栏
        // =============================================================
        NavLayer.Chat -> SidebarLayout(
            state = sidebarState,
            modifier = modifier,
            panel = {
                SidebarPanel(
                    conversations = conversations,
                    selectedId = activeId ?: "",
                    onSelect = { id ->
                        activeId = id
                        sidebarState.close()
                        scope.launch { loadMessages(id) }
                    },
                    onNewChat = {
                        sidebarState.close()
                        scope.launch {
                            val conv = repo.createConversation(title = "新对话")
                            // ★ 新建的对话要套上「通用 → 权限」里选的**默认档位**。
                            //   不套的话，那一项就是个纯装饰 —— 用户改完新建一个，
                            //   表现仍然是代码里的常量 DEFAULT，看起来"设置没生效"。
                            //   （这正是本项目反复踩的那类错：设置存了、读取的那条线不存在。）
                            runCatching {
                                repo.setWorkspacePermission(
                                    conv.id,
                                    settingsState.defaultPermission.name,
                                )
                            }.onFailure { Log.w("ChatHost", "套用默认权限失败", it) }
                            activeId = conv.id
                            messages.clear()
                            refreshConversations()
                            refreshActiveConversationMeta(conv.id)
                        }
                    },
                    onOpenSettings = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // 记住"侧边栏本来是开着的"，返回时要恢复它（用户要求）
                        sidebarOpenAfterBack = true
                        // 瞬时收到底：进入设置后这一层整个不再组合，状态要干净。
                        sidebarState.dragTo(0f)
                        layer = NavLayer.Settings
                    },
                )
            },
        ) {
            ChatScreen(
                messages = messages,
                input = input,
                onInputChange = { input = it },
                isGenerating = isGenerating,
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() && !isGenerating) {
                        input = ""
                        send(text)
                    }
                },
                onStop = { stop() },
                onOpenSidebar = { sidebarState.open() },
                title = conversations.firstOrNull { it.id == activeId }?.title ?: "ChatMobile",
                // ★ 顶栏右侧显示当前模型名，点它弹居中切换弹窗
                currentModelName = settingsState.active?.let { p ->
                    val prov = Providers.byId(p.providerId)?.displayName ?: p.providerId
                    p.model.ifBlank { prov }
                } ?: "",
                onOpenModelSwitch = { showModelSwitch = true },
                // ★★★ 这两行就是「思考过程收不回去」的第一层真因 ——
                //   以前**根本没传**，`ChatScreen` 只能吃默认值
                //   （ReasoningDisplay.DEFAULT = Standard = 全展开），
                //   设置页里改了也毫无作用，因为这条链**断了**。
                //   `SettingsStore.reasoningDisplay()` 那个 suspend getter
                //   在整个项目里连一个调用点都没有。
                reasoningDisplay = settingsState.reasoningDisplay,
                reasoningAutoCollapse =
                    settingsState.reasoningAutoCollapse ==
                        com.chatmobile.ui.chat.ReasoningAutoCollapse.AfterAnswer,
                bodyFontSize = settingsState.chatFontSize,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // =============================================================
        // 第 2 层：设置首页（极简列表）
        // =============================================================
        NavLayer.Settings -> SettingsScreen(
            state = settingsState,
            onBack = {
                layer = NavLayer.Chat
                if (sidebarOpenAfterBack) sidebarState.open()
            },
            onSaveProfile = { profile ->
                scope.launch { settings.upsert(profile) }
            },
            onDeleteProfile = { id ->
                scope.launch { settings.remove(id) }
            },
            onTest = { profile -> testConnection(profile) },
            onOpenModelConfig = { layer = NavLayer.ModelConfig },
            memoryCount = memoryUi.count { !it.deleted },
            onOpenMemories = { layer = NavLayer.Memories },
            onOpenTools = { layer = NavLayer.Tools },
            onOpenGeneral = { layer = NavLayer.General },
            modifier = modifier.fillMaxSize(),
        )

        // =============================================================
        // 第 3 层：模型（照 DSH：提供商行 + 编辑卡片 + 添加流程）
        // =============================================================
        NavLayer.ModelConfig -> SettingsScreen(
            state = settingsState,
            onBack = { layer = NavLayer.Settings },
            onSaveProfile = { profile ->
                scope.launch { settings.upsert(profile) }
            },
            onDeleteProfile = { id ->
                scope.launch { settings.remove(id) }
            },
            onTest = { profile -> testConnection(profile) },
            onFetch = { profile -> fetchModels(profile) },
            focusModelConfig = true,
            modifier = modifier.fillMaxSize(),
        )

        // =============================================================
        // 记忆管理（设置的下级）
        // =============================================================
        NavLayer.Memories -> MemoriesScreen(
            memories = memoryUi,
            memoryEnabled = settingsState.memoryEnabled,
            duplicateGroups = duplicateGroups,
            memoryBusy = memoryBusy,
            curatorModel = settingsState.curatorModel,
            embeddingModel = settingsState.embeddingModel,
            curatorSummary = when (settingsState.curatorModel.source) {
                com.chatmobile.settings.MemoryModelConfig.SOURCE_CHAT -> "跟随对话模型"
                com.chatmobile.settings.MemoryModelConfig.SOURCE_PROFILE ->
                    settingsState.profiles.firstOrNull { it.id == settingsState.curatorModel.profileId }
                        ?.model?.ifBlank { "未选模型" } ?: "未选择"
                else -> settingsState.curatorModel.model.ifBlank { "未配置" }
            },
            embeddingSummary = embeddingStatus ?: when (settingsState.embeddingModel.source) {
                com.chatmobile.settings.MemoryModelConfig.SOURCE_CHAT -> "跟随对话模型"
                com.chatmobile.settings.MemoryModelConfig.SOURCE_BUILTIN -> "内置轻量 · 256 维"
                com.chatmobile.settings.MemoryModelConfig.SOURCE_ONNX -> "内置模型 · 512 维"
                com.chatmobile.settings.MemoryModelConfig.SOURCE_PROFILE ->
                    settingsState.profiles.firstOrNull { it.id == settingsState.embeddingModel.profileId }
                        ?.model?.ifBlank { "未选模型" } ?: "未配置（不影响记忆）"
                else -> settingsState.embeddingModel.model.ifBlank { "未配置（不影响记忆）" }
            },
            packStatus = packStatus,
            packProgress = packProgress,
            onDownloadPack = {
                if (packProgress == null) {
                    scope.launch {
                        packProgress = 0f
                        val result = com.chatmobile.memory.pack.ModelPackDownloader(context.filesDir)
                            .download { p -> packProgress = p }
                        packProgress = null
                        refreshPackStatus()
                        if (result.isFailure) {
                            packStatus = "下载失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
                        } else {
                            embeddingStatus = probeEmbedding(settingsState.embeddingModel)
                        }
                    }
                }
            },
            onDeletePack = {
                com.chatmobile.memory.pack.ModelPackDownloader(context.filesDir)
                    .cleanup(context.filesDir)
                refreshPackStatus()
                embeddingStatus = null
            },
            profiles = settingsState.profiles,
            onSaveCurator = { cfg ->
                scope.launch { settings.setCuratorModel(cfg) }
            },
            onSaveEmbedding = { cfg ->
                scope.launch {
                    settings.setEmbeddingModel(cfg)
                    // ★ 先装后端，再探活 —— 否则会出现"面板说已就绪、检索还在走字面"的分裂
                    applyEmbeddingBackend(cfg)
                    embeddingStatus = probeEmbedding(cfg)
                }
            },
            onFetchModels = { _, baseUrl, apiKey ->
                runCatching {
                    com.chatmobile.net.ModelsApi(
                        com.chatmobile.net.ProviderConfig(
                            id = "memory-custom",
                            displayName = "记忆模型",
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                        ),
                    ).fetchModels().ids
                }.getOrDefault(emptyList())
            },
            onConsolidate = {
                val p = settingsState.active
                val preset = p?.let { Providers.byId(it.providerId) }
                if (p != null && preset != null && !memoryBusy) {
                    val endpoint = Providers.resolveEndpoint(p.providerId, p.endpointUrl)
                        ?: preset.defaultEndpoint
                    scope.launch {
                        memoryBusy = true
                        try {
                            consolidateMemories(preset, endpoint, p)
                        } finally {
                            memoryBusy = false
                        }
                    }
                }
            },
            onBack = { layer = NavLayer.Settings },
            onToggleEnabled = { enabled ->
                scope.launch {
                    settings.setMemoryEnabled(enabled)
                    refreshMemories()
                }
            },
            onDelete = { id ->
                memoryBank.delete(id)
                refreshMemories()
            },
            onRestore = { id ->
                memoryBank.restore(id)
                refreshMemories()
            },
            onEdit = { id, content, key ->
                memoryBank.update(id, content, key)
                // ★ 编辑后立刻从库刷新（update 走的是内存对象，Room 写是异步的，
                //   但 MemoryBank 的 store 是同一个 RoomMemoryStore，下一次读会拿到）
                refreshMemories()
            },
            onPin = { id, pinned ->
                memoryBank.setPinned(id, pinned)
                refreshMemories()
            },
            onAdd = { content, key ->
                memoryBank.save(content = content, key = key)
                refreshMemories()
            },
            onMergeGroup = { ids ->
                memoryBank.mergeGroup(ids)
                refreshMemories()
            },
            onPurgeDeleted = {
                memoryBank.purgeDeleted()
                refreshMemories()
            },
            customPrompt = settingsState.customPrompt,
            onSavePrompt = { text ->
                scope.launch { settings.setCustomPrompt(text) }
            },
            memoryTuning = settingsState.memoryTuning,
            onSaveTuning = { tuning ->
                scope.launch {
                    settings.setMemoryTuning(tuning)
                    // ★ 立刻生效：不用重启 App。已落库条目不动（见 MemoryBank.updateConfig）。
                    memoryBank.updateConfig(tuning.applyTo(chatmobile.memory.MemoryConfig()))
                }
            },
            modifier = modifier.fillMaxSize(),
        )

        // =============================================================
        // 工具设置（设置的下级）
        // =============================================================
        NavLayer.Tools -> com.chatmobile.settings.ToolsScreen(
            enabledMap = toolEnabledMap,
            resultChars = toolResultChars,
            onBack = { layer = NavLayer.Settings },
            onToggle = { name, enabled ->
                scope.launch {
                    settings.setToolEnabled(name, enabled)
                    // ★ 立刻回读一次，让开关状态以**落库后的真实值**为准。
                    //   只改本地 state 会让"点了一下没反应"这类问题被掩盖。
                    toolEnabledMap = settings.toolEnabledMap()
                }
            },
            onSaveResultChars = { chars ->
                scope.launch {
                    settings.setToolResultChars(chars)
                    toolResultChars = settings.toolResultChars()
                }
            },
            modifier = modifier.fillMaxSize(),
        )

        // =============================================================
        // 通用设置（设置的下级）
        // =============================================================
        NavLayer.General -> com.chatmobile.settings.GeneralScreen(
            // ★ 这里的「权限」是**新会话的默认档位**，不是当前会话的。
            //   当前会话的档位在快捷面板第二 Tab 里改（按对话存）。
            //   两者混用会让用户以为"改这里就把现在这个对话也改了" ——
            //   而实际上已有对话的档位是存在 conversation 表上的。
            permission = settingsState.defaultPermission,
            display = settingsState.reasoningDisplay,
            autoCollapse = settingsState.reasoningAutoCollapse,
            themeMode = settingsState.themeMode,
            fontSize = settingsState.chatFontSize,
            onBack = { layer = NavLayer.Settings },
            onPermissionChange = { p ->
                scope.launch { settings.setDefaultPermission(p) }
            },
            onDisplayChange = { d ->
                scope.launch { settings.setReasoningDisplay(d) }
            },
            onAutoCollapseChange = { m ->
                scope.launch { settings.setReasoningAutoCollapse(m) }
            },
            onThemeModeChange = { m ->
                scope.launch { settings.setThemeMode(m) }
            },
            onFontSizeChange = { sp ->
                scope.launch { settings.setChatFontSize(sp) }
            },
            modifier = modifier.fillMaxSize(),
        )
        NavLayer.Workspaces -> com.chatmobile.settings.WorkspacesScreen(
            workspaces = workspaceOptions.map { o ->
                com.chatmobile.settings.WorkspaceUi(
                    id = o.id,
                    name = o.name,
                    isShared = o.isShared,
                    fileCount = o.fileCount ?: 0,
                )
            },
            onBack = { layer = NavLayer.Settings },
            onCreatePrivate = { name ->
                scope.launch { createWorkspace(name = name, treeUri = null) }
            },
            onCreateShared = { name, treeUri ->
                scope.launch { createWorkspace(name = name, treeUri = treeUri) }
            },
            onDelete = { id ->
                scope.launch {
                    runCatching {
                        RepoHolder.get(context).workspaceDao().delete(id)
                        workspaceFs.wipe(id)
                    }.onFailure { Log.w("ChatHost", "删除工作区失败", it) }
                    // ★ 删完必须重刷：否则弹窗里还留着一个已经不存在的工作区
                    refreshActiveConversationMeta()
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }

    // ★★★ 快速切换模型弹窗 —— 居中，必须放在 `when` **外面**
    //
    //   用户要求：「点开之后出一个弹窗，要出现在屏幕正中间，然后列出所有的
    //   已经添加的模型，按提供方进行分类筛选（添加这个分类标签），弹窗上面
    //   也加一个搜索框，但是搜索框不要抢焦点，呼出键盘，用户不点就不用弹键盘。」
    //
    //   ★ 为什么在 `when` 外面：放进去会被某个分支吞掉（第一次就写错了，
    //     导致 `when` 不穷尽 + `NavLayer.Settings ->` 变成语法错误）。
    //     Dialog 是独立窗口，与当前在哪一层无关 —— 但入口只在主界面。
    if (showModelSwitch) {
        QuickSwitchSheet(
            options = settingsState.profiles.map { p ->
                val prov = Providers.byId(p.providerId)?.displayName ?: p.providerId
                // ★ 能力档案按「这份配置当前选中的模型」取 —— 弹窗底部那两块
                //   （思考强度 / 上下文上限）说的是**正在用的那个模型**，
                //   不是这份配置里收藏的其它模型。
                val cap = p.capabilities[p.model]
                ModelOption(
                    profileId = p.id,
                    title = p.model.ifBlank { p.displayName(prov) },
                    providerId = p.providerId,
                    providerName = prov,
                    active = p.id == settingsState.activeId,
                    effortLevels = cap?.effortLevels.orEmpty(),
                    // 上游声明的窗口 —— **只当提示**，不覆盖用户填的值
                    contextLimitHint = cap?.contextLimit,
                    // 用户自己填的值；没填就显示空的（placeholder 才是上游值）
                    contextLimitInput = p.contextLimitOverride
                        .takeIf { it > 0 }?.toString().orEmpty(),
                )
            },
            onSelect = { profileId ->
                showModelSwitch = false
                scope.launch { settings.setActive(profileId) }
            },
            onDismiss = { showModelSwitch = false },
            currentEffort = settingsState.active?.reasoningEffort.orEmpty(),
            onEffortChange = { effort ->
                settingsState.activeId?.let { id ->
                    scope.launch { settings.setReasoningEffort(id, effort) }
                }
            },
            // ★★★ 用户填多少就存多少 —— **不钳制**。
            //   「上下文上限应该允许用户任意调整，调整到厂家允许的真正上限。
            //    不要按照默认上限，那个只是厂家默认，不是真正的上限。」
            onContextLimitChange = { raw ->
                settingsState.activeId?.let { id ->
                    scope.launch {
                        settings.setContextLimitOverride(id, raw.toIntOrNull() ?: 0)
                    }
                }
            },
            // ---- 权限：**按对话存**，改完下一轮生效 ----
            currentPermission = currentPermission(),
            onPermissionChange = { perm ->
                val convId = activeId
                if (convId != null) {
                    scope.launch {
                        repo.setWorkspacePermission(convId, perm.name)
                        refreshActiveConversationMeta(convId)
                    }
                }
            },
            // ---- 工作区：**按对话存**，改完下一轮生效 ----
            workspaces = workspaceOptions,
            currentWorkspaceId = currentWorkspaceId,
            onWorkspaceChange = { wsId ->
                val convId = activeId
                if (convId != null) {
                    scope.launch {
                        repo.setWorkspace(convId, wsId)
                        refreshActiveConversationMeta(convId)
                    }
                }
            },
            onCreateWorkspace = {
                showModelSwitch = false
                // ★ 新建工作区要走设置页（那里有 SAF 选择器）。
                //   在这里塞一个文件选择器会让弹窗承担两种完全不同的职责。
                layer = NavLayer.Workspaces
            },
            onManageWorkspaces = {
                showModelSwitch = false
                layer = NavLayer.Workspaces
            },
        )
    }
}

/**
 * 「拉取模型」：问该提供商的 `/models` 要一份清单。
 *
 * ★ 用户要求（23:25）：一次把所有能访问到的模型拉下来，然后勾选。
 *
 * ★ 这里的要点是**失败要说人话** —— 用户点了没反应是最糟的体验。
 *   所以返回 `(ids, error)` 两段：失败时 error 必须能指导下一步动作
 *   （"密钥无效" / "不支持 /models，请手动添加" / "网络错误：…"），
 *   而不是一个空列表让他猜。
 */
private suspend fun fetchModels(cfg: ModelProfile): FetchOutcome {
    val preset = Providers.byId(cfg.providerId)
        ?: return FetchOutcome(emptyList(), "厂商 " + cfg.providerId + " 不在预置列表里")

    val endpoint = Providers.resolveEndpoint(cfg.providerId, cfg.endpointUrl)
        ?: return FetchOutcome(emptyList(), "请先填写 API 地址")

    // 没填密钥就没必要发了 —— 一定会 401，还浪费一次网络往返。
    if (cfg.apiKey.isBlank()) {
        return FetchOutcome(emptyList(), "请先填写 API 密钥")
    }

    val config = try {
        preset.toConfig(
            endpoint = endpoint,
            apiKey = cfg.apiKey,
            model = cfg.model,
            // ★ 拉模型也带会话头 —— /models 实测不带也能过，
            //   但统一带上有两个好处：UA 一致、以后哪家收紧时不用再改一遍。
            sessionId = cfg.id,
        )
    } catch (e: Throwable) {
        return FetchOutcome(emptyList(), "无法创建连接：" + (e.message ?: e::class.simpleName))
    }

    val api = ModelsApi(config)
    val r = api.fetchModels()
    // ★ capabilities 一路带回去（同一次响应里解析好的）——
    //   SettingsScreen 会把它并进草稿，保存后切换弹窗就能显示强度档位与窗口大小。
    return FetchOutcome(r.ids, r.error, r.capabilities)
}

/**
 * 真实发一次请求验证 Key 能不能用。
 *
 * ★ 必须发真实请求：只校验"格式对不对"没有意义，用户要的是"这把 Key 现在能不能用"。
 *
 * 两条路径：
 * - 厂商支持 `GET /models` → 走它（便宜，不消耗 token）
 * - 不支持 → 发一条极短的对话（`max_tokens = 1`），至少能拿到 401/403/200 的判定
 */
private suspend fun testConnection(cfg: ModelProfile): TestResult {
    val preset = Providers.byId(cfg.providerId)
        ?: return TestResult(false, "厂商 " + cfg.providerId + " 不在预置列表里")
    // ★ 与发送路径同一个解析入口（见 Providers.resolveEndpoint）
    val endpoint = Providers.resolveEndpoint(cfg.providerId, cfg.endpointUrl)
        ?: return TestResult(false, "API 地址为空，请填写该厂商的接口地址")

    val provider: ChatProvider = try {
        ProviderFactory.createFromPreset(
            preset = preset,
            apiKey = cfg.apiKey,
            endpoint = endpoint,
            model = cfg.model,
            // ★ 测试连接也要带会话 ID，否则 OpenCode 直接 400，
            //   用户会以为"密钥不对"，实际是少一个头。
            sessionId = cfg.id,
        )
    } catch (e: Throwable) {
        return TestResult(false, "无法创建连接：" + (e.message ?: e::class.simpleName))
    }

    return try {
        // 发一条最小对话：这是最通用的判定 —— 能拿到第一帧就说明
        // 地址对、Key 有效、模型名存在。比单独测 /models 更接近真实使用。
        val req = ChatRequest(
            model = cfg.model,
            messages = listOf(NetChatMessage.user("hi")),
            maxTokens = 1,
        )
        var got = false
        var errText: String? = null
        provider.stream(req).collect { ev ->
            when (ev) {
                is ChatEvent.Delta, is ChatEvent.Reasoning, ChatEvent.Done -> got = true
                is ChatEvent.Error -> errText = ev.message
                is ChatEvent.Finish -> got = true
                // ★ 测连接发的是 `hi`、不带 tools ⇒ 正常不会出现工具调用。
                //   真出现了也算"服务端有响应"，仍然判定可用。
                is ChatEvent.ToolCalls -> got = true
                is ChatEvent.Usage -> Unit
            }
        }
        if (errText != null) {
            TestResult(false, errText!!)
        } else if (got) {
            TestResult(true, "模型 " + cfg.model + " 可用。")
        } else {
            TestResult(false, "服务端没有返回任何内容。请确认模型名是否正确。")
        }
    } catch (e: Throwable) {
        TestResult(false, "请求失败：" + (e.message ?: e::class.simpleName))
    }
}

/** 三层导航的层。用枚举而非多个 Boolean，见 ChatHost 里的说明。 */
enum class NavLayer { Chat, Settings, ModelConfig, Memories, Tools, Workspaces, General }

/**
 * 用于把控制流带出 `Flow.collect` 的 lambda。
 *
 * ★ 为什么要专门造一个类型：
 *   `collect { ... }` 的 lambda 里不能写裸 `return`（只能返回到 lambda 自己），
 *   而"收到 Error 事件后终止整个流式过程"必须跳出外层 suspend 函数。
 *   用一个**私有的、只在本文件流转**的异常，语义清晰且不会被误当成真错误。
 *
 * ★ `finishWithError` 已经在抛之前做完了 UI + 落库，所以捕获点**不要再处理一次**。
 */
private class StreamAborted(val reason: String) : Exception(reason)

/**
 * 工具循环的**轮数上限**。
 *
 * ★ 为什么必须有：模型可能反复调同一个工具（尤其参数一直错的时候）。
 *   不设上限会变成死循环，把用户的 token 烧光，而界面上只是"一直在转"。
 * ★ 为什么是 8：真实链路最长的是「查时间 → 抓两个网页 → 检索记忆 → 存一条 → 作答」，
 *   5 轮。留 3 轮余量。再多就说明模型在打转，那时停下比继续烧钱好。
 */
private const val MAX_TOOL_ROUNDS = 8

/**
 * 只为序列化工具 schema 而存在的空执行器。
 *
 * ★ 为什么用 `ToolRegistry` 序列化而不是直接拼 JSON：
 *   那份拼装逻辑（转义、`additionalProperties`、required 顺序）已经在
 *   `ToolRegistry` 里写好并有测试，再写一份必然分叉。
 * ★ 真正的执行走 `ChatToolExecutor`（它按名字分发到 `ToolHost`），
 *   所以这里的 executor 永远不会被调用 —— `error()` 是刻意留的断言。
 */
private object NOOP_EXECUTOR : chatmobile.tools.ToolExecutor {
    override fun execute(call: chatmobile.tools.ToolCall, workingDir: String?): chatmobile.tools.ToolResult =
        error("这只是占位执行器，真正的执行在 ChatToolExecutor 里")
}

// ------------------------------------------------------------------
// 进程级数据库单例
//
// ★ 用 @Volatile + synchronized 双检：ChatHost 可能在多线程组合环境里首次初始化，
//   而 Room 的 build() 不是线程安全的。
// ------------------------------------------------------------------
private object RepoHolder {
    @Volatile
    private var db: ChatDatabase? = null

    /**
     * 记忆存储 —— 进程级单例。
     *
     * ★ 为什么必须单例（和 db 同理）：`RoomMemoryStore` 内部维护一份
     *   `ConcurrentHashMap` 缓存 + 一个写库 worker。若每次组合都新建一个：
     *     · 每个实例只看得到自己那份缓存 ⇒ 记忆"时有时无"
     *     · 每个实例起一个 worker 协程 ⇒ 协程泄漏
     *     · `load()` 会被反复调 ⇒ 重复读库
     */
    @Volatile
    private var memoryStore: RoomMemoryStore? = null

    /** 专用 IO 作用域。用 SupervisorJob：一个写失败不该拖垮整个 store。 */
    private val memoryScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun get(context: Context): ChatDatabase =
        db ?: synchronized(this) {
            db ?: ChatDatabase.build(context.applicationContext).also { db = it }
        }

    fun memory(context: Context): RoomMemoryStore =
        memoryStore ?: synchronized(this) {
            memoryStore ?: RoomMemoryStore(get(context).memoryDao(), memoryScope)
                .also { memoryStore = it }
        }
}

// ------------------------------------------------------------------
// 映射：data 实体 → ui 展示模型
//
// ★ 只在这一个方向转换。ui 的 ChatMessage 是展示模型（带 isStreaming 这种
//   纯界面状态），data 的 MessageEntity 是持久化模型（带 seq 这种库内排序依据）。
//   二者职责不同，**不要合并**，也不要在 data 层反向 import ui。
// ------------------------------------------------------------------

private fun MessageEntity.toUi(isStreaming: Boolean = false): ChatMessage {
    val r = when (roleEnum) {
        MessageRole.USER -> ChatMessage.Role.User
        else -> ChatMessage.Role.Assistant
    }
    return ChatMessage(
        id = id,
        role = r,
        text = content,
        reasoning = reasoning,
        // ★ 库里残留的 streaming 状态不作为界面依据 —— 进程被杀留下的
        //   "未完成"不应该在冷启动时继续转圈。启动时已 recoverInterruptedStreams()
        //   把它们改成 stopped，所以这里只有"正在本次会话里流式输出"才为 true。
        isStreaming = isStreaming,
        timestamp = createdAt,
    )
}

/**
 * 从模型输出里抠出记忆数组。
 *
 * ★ 必须容错，因为模型**经常不听话**。实际见过的三种偏离：
 *   ① 套一层 markdown 代码块：```json [...] ```
 *   ② 前面加一句"好的，以下是抽取的记忆："
 *   ③ 返回对象 `{"memories":[...]}` 而不是裸数组
 *
 * ⇒ 策略：先找第一个 `[` 和最后一个 `]`，把中间抠出来当 JSON 解析。
 *   解析不出来就返回空 —— **宁可这轮不记，也不要记进垃圾**。
 *   记进垃圾比不记更糟：垃圾会进上下文，每一轮都占预算。
 *
 * ★ 用 `kotlinx.serialization` 而**不是** `org.json`：
 *   Android 的 `org.json` 在 JVM 单元测试里是**空 stub**（方法体全是
 *   `throw new RuntimeException("Stub!")` 或返回 null），所以用它写的解析
 *   在单测里永远返回空 —— 而真机正常。这种"单测绿、线上红"或反过来的
 *   假象最难查，直接用纯 JVM 实现规避。
 */
/** 抽取出来的一条记忆：可选的槽位键 + 值。 */
internal data class ExtractedMemory(val key: String?, val value: String)

/**
 * 解析抽取结果 —— 同时接受**对象**和**纯字符串**两种形态。
 *
 * ```json
 * [{"key":"pref.reply_style","value":"简洁，不要客套"}, "用户是 Android 开发者"]
 * ```
 *
 * ★ 为什么必须容忍字符串：模型不总是听话。只认对象的话，一次格式漂移就整批丢弃；
 *   退化成字符串仍能进库（走近义去重），只是少了结构化的好处。
 * ★ key 用 [chatmobile.memory.MemoryKey.normalize] 校验，非法即降级为 null。
 */
internal fun parseMemoryItems(raw: String): List<ExtractedMemory> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()

    return runCatching {
        val arr = kotlinx.serialization.json.Json
            .parseToJsonElement(text.substring(start, end + 1)) as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        arr.mapNotNull { el ->
            when (el) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    el.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it.length <= 200 }
                        ?.let { ExtractedMemory(null, it) }
                }
                is kotlinx.serialization.json.JsonObject -> {
                    val value = (el["value"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull?.trim()
                        ?.takeIf { it.isNotBlank() && it.length <= 200 }
                        ?: return@mapNotNull null
                    val key = (el["key"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull
                        ?.let { chatmobile.memory.MemoryKey.normalize(it) }
                    ExtractedMemory(key, value)
                }
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

/**
 * 旧接口：只要值（保留给既有测试与调用方）。
 *
 * ★ 新代码请用 [parseMemoryItems] —— 它带回 key，是结构化记忆的入口。
 */
internal fun parseMemoryArray(raw: String): List<String> =
    parseMemoryItems(raw).map { it.value }

// ---------------------------------------------------------------
// 记忆管理员（curator）：把"抽取"和"整理"合成一次 LLM 调用
// ---------------------------------------------------------------

/**
 * 把「记忆模型」配置解析成一份可用的 `ModelProfile`。
 *
 * 三种来源（跟随对话 / 复用已配置 / 独立配置）最终都归一到同一个类型
 * ⇒ 请求路径只有一套，不会出现"记忆模型走另一套协议"的分叉。
 */
internal fun resolveMemoryProfile(
    cfg: com.chatmobile.settings.MemoryModelConfig,
    profiles: List<ModelProfile>,
    active: ModelProfile?,
): ModelProfile? = when (cfg.source) {
    com.chatmobile.settings.MemoryModelConfig.SOURCE_CHAT -> active
    com.chatmobile.settings.MemoryModelConfig.SOURCE_PROFILE ->
        profiles.firstOrNull { it.id == cfg.profileId }
    else -> ModelProfile(
        id = "memory-custom",
        providerId = cfg.providerId.ifBlank { "custom" },
        endpointUrl = cfg.baseUrl,
        apiKey = cfg.apiKey,
        model = cfg.model,
    ).takeIf { it.endpointUrl.isNotBlank() && it.model.isNotBlank() }
}

/** 一条记忆操作。 */
internal data class MemoryOp(
    val op: String,
    val key: String?,
    val content: String?,
    val ids: List<String>,
)

/**
 * 解析记忆管理员输出。
 *
 * 期望形态：`{"ops":[{"op":"MERGE","ids":["a","b"],"content":"..."}]}`
 *
 * ★ 容错：
 *   - 裸数组（没有 `ops` 外层）
 *   - 老格式 `{"key":..,"value":..}`（没有 op）⇒ 当 ADD
 *   - 前后废话 / markdown 代码块
 */
internal fun parseMemoryOps(raw: String): List<MemoryOp> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()

    val objStart = text.indexOf('{')
    val arrStart = text.indexOf('[')
    val jsonText: String = when {
        objStart >= 0 && (arrStart < 0 || objStart < arrStart) -> {
            val end = text.lastIndexOf('}')
            if (end <= objStart) return emptyList()
            text.substring(objStart, end + 1)
        }
        arrStart >= 0 -> {
            val end = text.lastIndexOf(']')
            if (end <= arrStart) return emptyList()
            text.substring(arrStart, end + 1)
        }
        else -> return emptyList()
    }

    return runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonText)
        val arr = when (root) {
            is kotlinx.serialization.json.JsonObject ->
                root["ops"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            is kotlinx.serialization.json.JsonArray -> root
            else -> return emptyList()
        }
        arr.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            val ids = (o["ids"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val op = (str("op") ?: "ADD").uppercase()
            val content = (str("content") ?: str("value"))?.trim()?.takeIf { it.isNotBlank() }
            val key = chatmobile.memory.MemoryKey.normalize(str("key"))
            when (op) {
                "ADD", "UPDATE" -> if (content == null) null else MemoryOp(op, key, content, ids)
                "MERGE" -> if (ids.size < 2) null else MemoryOp(op, key, content, ids)
                "SKIP" -> MemoryOp("SKIP", null, null, emptyList())
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}



private const val STREAM_INTERVAL_MS = 40L

/**
 * 记忆预算**占上下文预算的比例**。
 *
 * ★ 这是缺口 2 的修法：以前记忆预算写死 32000，于是用户把上限调到 1M 时，
 *   压缩触发点跟到了 700K，但记忆那块仍按 32K 的 25% 算（8000 token）——
 *   窗口涨了 31 倍，记忆预算一点没涨。
 *
 * ⇒ 改成**比例**，由 [effectiveMemoryBudget] 乘上真实窗口。
 *   25% 来自 `MemoryConfig` 的 saved 15% + recent 10%（两者之和就是记忆能吃的上限）。
 *
 * ★ 为什么不直接用 25% 原样：`MemoryComposer` 内部已经按 15%/10% 自己切，
 *   这里只需把"总预算"给对。25% 与它的两个比例一致，改一处即同步。
 */
private const val MEMORY_BUDGET_RATIO = 0.25

/**
 * 记忆预算的**保守下限**。
 *
 * ★ 小窗口模型（比如 8K）如果严格按 25% 算只有 2000 token，记忆几乎装不下东西。
 *   但也不能反过来给它 32000 —— 那会让请求直接超窗。
 *
 * ⇒ 取 8000：这是"小窗口下仍能记住一点东西"与"绝不撑爆"的折中，
 *   在两个端点之间做 `coerceIn`，**不覆盖大窗口**。
 */
private const val MEMORY_BUDGET_FLOOR = 8_000

/**
 * 算出**这一轮给记忆的 token 预算**。
 *
 * 缺口 2 的落点：记忆预算**跟着上下文上限走**。
 * - 大窗口（1M）⇒ 250000，记忆能装得多
 * - 小窗口（8K）⇒ 2000 太小 ⇒ 抬到 [MEMORY_BUDGET_FLOOR]
 *
 * ★ 这是唯一一处把 `effectiveContextBudget` 与记忆预算连起来的地方，
 *   `composeContext` 的调用方必须用它，不许再传常量。
 */
private fun effectiveMemoryBudget(profile: ModelProfile): Int {
    val total = effectiveContextBudget(profile)
    val raw = (total * MEMORY_BUDGET_RATIO).toInt()
    // 大窗口不封顶（用户买了 1M 就该能记住更多），小窗口抬到下限
    return maxOf(raw, MEMORY_BUDGET_FLOOR)
}

/**
 * 压缩的**兜底**基准预算（token）—— 只在用户没设、上游也没声明时才用。
 *
 * ★ 真正生效的值由 [effectiveContextBudget] 决定：
 *   用户自己填的 > 上游声明的 > 这个兜底值。
 *   32000 只是一个"不知道也不能撑爆"的保守下限。
 */
private const val CONTEXT_BUDGET_TOKENS = 32_000

/**
 * 算出**这份配置实际生效的上下文预算**。
 *
 * ★★★ 用户原话：「上下文上限应该允许用户任意调整，调整到厂家允许的真正上限。
 *   不要按照默认上限，那个只是厂家默认，不是真正的上限。」
 *
 * 三级优先，**不做任何钳制**：
 *   1. 用户在切换模型弹窗里**自己填的**（[ModelProfile.contextLimitOverride]）
 *   2. 上游 `/models` 声明的（[ModelCapability.contextLimit]）
 *   3. 兜底的 [CONTEXT_BUDGET_TOKENS]
 */
private fun effectiveContextBudget(profile: ModelProfile): Int {
    profile.contextLimitOverride.takeIf { it > 0 }?.let { return it }
    profile.capabilities[profile.model]?.contextLimit?.takeIf { it > 0 }?.let { return it }
    return CONTEXT_BUDGET_TOKENS
}

/**
 * 压缩后的 system 前缀 + 已被它代表的消息 id 集合。
 *
 * [coveredMessageIds] 里的消息**不再单独发给模型**，因为摘要已经涵盖了它们。
 * 但它们在 Room 里一条都没删 —— 用户随时能翻回原文。
 */
private data class CompressionPrefix(val text: String, val coveredMessageIds: Set<String>)

/**
 * 粗略 token 估算。
 *
 * ★ 用它而不是真实 tokenizer：手机端装 tokenizer 要多几 MB 和一次加载，
 *   而我们只需要**量级对**（判断"是不是快到上限了"）。
 *   中文约 1 token/字，英文约 0.25 —— 取 0.6 是中英混排的经验值，
 *   与 `core-memory` 的 `MemoryConfig.tokensPerChar` 保持一致。
 */
private fun estimateTokens(text: String): Int =
    kotlin.math.ceil(text.length * 0.6).toInt()

/**
 * 记忆抽取的触发间隔（毫秒）。
 *
 * ★ 每轮都抽 = 每轮多一次完整 API 调用，手机上既慢又费钱。
 *   攒够 6 轮再抽一次，摊薄成本；同时保证"聊一会儿它就记住点东西"。
 *
 * ★ 不做成"按 token 数触发"是因为 token 数不可靠：各家 `usage` 字段
 *   有的给有的不给，而且估算误差大。**轮数是确定量**。
 */
private const val MEMORY_EXTRACT_EVERY_TURNS = 6

/**
 * 单次抽取最多喂多少条消息。
 *
 * ★ 与「每 6 条触发一次」配合：水位线保证不重叠，窗口只决定单次请求的大小。
 *   12 条 ≈ 一次像样的对话片段，再多模型也抓不住重点。
 */
private const val MEMORY_EXTRACT_WINDOW = 12

/**
 * 把压缩摘要写进记忆库时，摘要**最多保留多少字符**。
 *
 * ★ 摘要本身受 `CompressionConfig.summaryMaxTokens = 400` 约束，中英混排下
 *   大约 600~800 字符。这里取 600 作为硬上限 ——
 *   记忆条目的价值在于"浓缩"，照搬一整段摘要进记忆库就把 recent 预算吃光了。
 */
private const val MEMORY_FROM_SUMMARY_MAX_CHARS = 600

/**
 * 记忆抽取提示词。
 *
 * ★ 设计要点（都是踩过的坑）：
 *   1. **要求 JSON 数组**，不要让它自由发挥 —— 解析失败就只能整段丢弃
 *   2. **明确"没有就返回空数组"** —— 否则模型会为了凑数编记忆
 *   3. **禁止复述对话内容** —— 记忆是"关于用户的事实"，不是聊天摘要
 *   4. **限制条数与长度** —— 不限制它会一次吐 20 条，把预算吃光
 */
/** 把可调参数拼进提示词 —— 「最多 N 条 op」必须和配置一致，否则模型会超产被截断。 */
private fun memoryCuratorPrompt(maxOps: Int, maxChars: Int): String {
    // 单条上限取「配置里的字数上限」与 40 的较小者：40 字是经验值，
    // 用户把上限调大也不该让一条记忆变成一段话。
    val perEntry = minOf(40, maxChars)
    return """
你是用户的**记忆管理员**。你会看到【已有记忆】（每条带方括号 id）和【新增对话】。

你的任务有两件：
1. 从新增对话里认出**关于用户的、值得长期记住的事实**；
2. **顺手整理已有记忆** —— 已经记过的不要重复记，说同一件事的几条要合并。

只输出一个 JSON 对象：
{"ops":[ ... ]}

op 四种：
- {"op":"ADD","key":"user.job","content":"用户是 Android 开发者"}         新事实
- {"op":"UPDATE","key":"user.city","content":"用户现在住杭州","ids":["id"]} 同一个键的新值（覆盖旧值）
- {"op":"MERGE","ids":["id1","id2"],"content":"合并后的一句话"}            多条在说同一件事
- {"op":"SKIP"}                                                          没有值得记的

规则（严格遵守）：
1. 先看【已有记忆】。**已有同 key 或同义内容 ⇒ 用 UPDATE/MERGE，绝不要 ADD**。
2. 已有记忆里明显重复的（不同措辞、同一件事）⇒ MERGE，ids 必须来自方括号里的 id。
3. key 只能是 `pref.*` / `user.*` / `project.*` / `person.*` / `event.*`，小写 ASCII。
   同一个事实永远用同一个 key。
4. content 用第三人称（"用户…"），不超过 ${perEntry} 个汉字。
5. 最多 ${maxOps} 条 op。没有值得记的就输出 {"ops":[]}，不要为凑数编造。
6. 不要输出解释、不要 markdown 代码块。
""".trimIndent()
}

private const val WELCOME =
    "你好，我是 ChatMobile。这次消息会真的存进数据库，切到桌面再回来也不会丢。\n\n" +
        "左侧滑出侧边栏可以看到会话列表，新建的对话也会留下来。"

/**
 * 模拟回复。
 *
 * ★ 故意写长：要能超过一屏，才能验证 `isAtBottom` 在"最新一条消息高过视口"
 *   时是否仍然成立（这是 ui-agent 标为"未验证"的隐患，见 ui/README §4.7）。
 */
private fun demoReplyFor(prompt: String): String =
    "这是模拟回复（真实模型还没接上）。你说的是「${prompt.take(30)}」。\n\n" +
        "现在这条消息故意写得很长，为的是压到超过一屏，好验证一个具体的疑问：" +
        "当最新一条消息本身比视口还高时，「是否停在底部」的判断还成不成立？\n\n" +
        "如果它中途不再跟随、需要手动往下拖，那就说明判断条件需要修正；" +
        "如果它一直跟到底，说明当前写法在长回复下也是对的。\n\n" +
        "再补几行把它撑高：\n" +
        "1. 第一，消息要能持久化，切后台回来还在。\n" +
        "2. 第二，流式输出时旧消息的上移要和内容增长同步，不能错位。\n" +
        "3. 第三，侧边栏要跟手，拖到一半松手要弹回去。\n" +
        "4. 第四，回车要能发送。\n" +
        "5. 第五，新建对话之后旧对话还能在侧边栏找回来。\n\n" +
        "这一行是最后一行，看到它说明流式已经跑到结尾了。"
