package com.chatmobile.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 工作区管理页的一行。 */
data class WorkspaceUi(
    val id: String,
    val name: String,
    val isShared: Boolean,
    val fileCount: Int,
)

/**
 * **工作区管理页** —— 新建 / 删除 / 查看。
 *
 * ## ★ 两种工作区，界面上必须说清区别
 *
 * | | 私有 | 共享（手机文件目录） |
 * |---|---|---|
 * | 位置 | App 私有目录 | 用户用系统选择器挑的目录 |
 * | 用户能看见吗 | 只有 App 里 | 文件管理器里能看到 |
 * | 模型怎么用 | 直接读写 | 先写私有暂存区，再推过去 |
 *
 * 用户建工作区时的第一问题就是"东西会存到哪、我能不能找到"，
 * 所以类型必须在列表里直接标出来，不能藏在详情页。
 *
 * ## ★ 为什么"共享"要走系统选择器
 *
 * 不申请 `MANAGE_EXTERNAL_STORAGE`（全盘访问）—— 那在 Play 属高危权限、
 * 要专门审核，而且对一个聊天客户端是明显的过度授权。
 * SAF 让**用户自己选**一个目录，我们的权限范围正好等于他选的那个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacesScreen(
    workspaces: List<WorkspaceUi>,
    onBack: () -> Unit,
    onCreatePrivate: (String) -> Unit,
    onCreateShared: (String, Uri) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorkspaceUi?>(null) }
    // 新建对话框里的名字草稿
    var nameDraft by remember { mutableStateOf("") }

    /**
     * SAF 目录选择器。
     *
     * ★ 用 `OpenDocumentTree` 而不是 `OpenDocument`：前者选**目录**（树），
     *   后者选单个文件。工作区是目录。
     */
    val pickTree = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            onCreateShared(nameDraft, uri)
            showAdd = false
            nameDraft = ""
        }
    }

    Scaffold(
        // ★★★ 与「模型」「工具」页同一写法：imePadding 加在 Scaffold 外层。
        //   根因见 ToolsScreen 的注释 —— 只加 navigationBarsPadding() 躲的是导航条，
        //   不躲键盘，所以键盘一弹就把输入框和按钮压在下面。
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("工作区", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建工作区")
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "工作区是给模型读写文件用的目录。每个对话可以单独指定一个，" +
                    "在聊天界面点模型胶囊 → 工作区 随时切换。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (workspaces.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有工作区。点右上角 + 新建一个。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                workspaces.forEach { w ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(w.name, fontSize = 14.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = buildString {
                                        append(if (w.isShared) "手机文件目录" else "App 私有目录")
                                        append(" · ").append(w.fileCount).append(" 个文件")
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { pendingDelete = w }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除工作区",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "共享目录的文件会先在 App 私有目录里写好，再推送到你选的目录。" +
                    "同名文件会被覆盖。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---- 新建对话框 ----
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建工作区", fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        label = { Text("名字（可留空）", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "私有目录：模型直接读写，只有 App 里能看到。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "手机文件目录：你选一个目录，模型写完会推过去，" +
                            "文件管理器里能看到。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickTree.launch(null)
                }) {
                    Text("选手机目录", fontSize = 13.sp)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onCreatePrivate(nameDraft)
                        showAdd = false
                        nameDraft = ""
                    }) {
                        Text("建私有目录", fontSize = 13.sp)
                    }
                    TextButton(onClick = { showAdd = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            },
        )
    }

    // ---- 删除确认 ----
    pendingDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工作区", fontSize = 16.sp) },
            text = {
                Text(
                    text = "「${w.name}」里的 ${w.fileCount} 个文件会被删除。" +
                        (if (w.isShared) "手机目录里的原文件不受影响，只是不再往那里推。" else ""),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(w.id)
                    pendingDelete = null
                }) {
                    Text("删除", fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", fontSize = 13.sp)
                }
            },
        )
    }
}
