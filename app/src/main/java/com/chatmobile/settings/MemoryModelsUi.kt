package com.chatmobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 「提示词」页：用户自己写的人设/规矩。
 *
 * ★ 与记忆库的分界（这是产品语义，不是实现细节）：
 *   - **记忆库** = 模型自己观察到的关于你的事实，会被自动抽取、合并、衰减；
 *   - **提示词** = 你主动要求它成为什么（人设、口吻、禁区），**只会被你改**。
 *   所以它不进记忆库（否则一次"整理记忆"就能把你写的人设合并掉），
 *   而是紧接着固定提示词注入，位置固定、内容稳定，对缓存友好。
 */
@Composable
internal fun PromptTab(
    customPrompt: String,
    onSavePrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ 本地草稿（L12）：不能直接渲染 DataStore 的值再按键落盘，
    //   异步回流会把用户刚敲的字覆盖回旧值。
    var draft by remember(customPrompt) { mutableStateOf(customPrompt) }
    val dirty = draft != customPrompt

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
    ) {
        // ★ 操作行放在**输入框上方**：输入时键盘会盖住下半屏，
        //   放在下面的「保存」会被键盘挡住（真机实测点不到）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${draft.length} 字" + if (dirty) " · 未保存" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { draft = customPrompt }, enabled = dirty) {
                Text("还原", fontSize = 12.sp)
            }
            TextButton(onClick = { onSavePrompt(draft.trim()) }, enabled = dirty) {
                Text("保存", fontSize = 13.sp)
            }
        }
        Text(
            "这里写的是「你要求模型成为什么」——人设、口吻、必须遵守的规矩。",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "它和记忆是两回事：记忆是模型观察到的你，这里是你的指令；记忆整理不会动它。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            minLines = 8,
            maxLines = 20,
            label = { Text("自定义提示词", fontSize = 12.sp) },
            placeholder = {
                Text(
                    "例如：\n你是一位严谨的中文技术助理，回答先给结论再给依据。\n不要使用「作为 AI」这类说法。",
                    fontSize = 12.sp,
                )
            },
        )
    }
}

/**
 * 记忆专用模型的两张入口（记忆管理员 / 向量模型）。
 *
 * ★ 放在记忆面板顶部：用户管理记忆时最常问的两件事是
 *   「谁在帮我整理」「它靠什么找」—— 入口就该在这里，而不是埋进设置深处。
 */
@Composable
internal fun MemoryModelsCard(
    curator: MemoryModelConfig,
    embedding: MemoryModelConfig,
    curatorSummary: String,
    embeddingSummary: String,
    onEditCurator: () -> Unit,
    onEditEmbedding: () -> Unit,
    /** 增强包状态文案（未安装 / 下载中 42% / 已安装 / 校验失败）。 */
    packStatus: String = "",
    /** 下载进度 0..1；null = 没在下载。 */
    packProgress: Float? = null,
    onDownloadPack: (() -> Unit)? = null,
    onDeletePack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        ModelEntryRow("记忆管理员", curatorSummary, onEditCurator)
        Spacer(Modifier.height(6.dp))
        ModelEntryRow("向量模型", embeddingSummary, onEditEmbedding)

        // ★ 增强包单独一行：它是"下载一个 23MB 的东西"，用户必须能看见进度、
        //   能中途反悔、能删掉腾空间 —— 藏在配置对话框里就等于没有。
        if (embedding.source == MemoryModelConfig.SOURCE_ONNX) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("模型包", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    packStatus,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                when {
                    packProgress != null -> Text(
                        "${(packProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    onDownloadPack != null -> TextButton(onClick = onDownloadPack) {
                        Text("下载", fontSize = 12.sp)
                    }
                    onDeletePack != null -> TextButton(onClick = onDeletePack) {
                        Text("删除", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelEntryRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            summary,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = onClick) { Text("配置", fontSize = 12.sp) }
    }
}

/**
 * 记忆模型配置对话框。
 *
 * 三种来源对应 [MemoryModelConfig] 的三个常量；`profile` 模式从已配置的
 * 模型清单里挑，`custom` 模式可以「拉取模型」再选。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryModelConfigDialog(
    title: String,
    initial: MemoryModelConfig,
    profiles: List<ModelProfile>,
    /** 拉取模型：providerId, baseUrl, apiKey → 模型 id 列表（失败返回空） */
    onFetch: suspend (String, String, String) -> List<String>,
    onDismiss: () -> Unit,
    onSave: (MemoryModelConfig) -> Unit,
    /** 记忆管理员可以「跟随对话」；向量模型不行（聊天模型不支持 /embeddings）。 */
    allowChat: Boolean = true,
    /** 向量模型可以选「内置轻量向量」。 */
    allowBuiltin: Boolean = false,
    /** 向量模型可以选「内置模型（下载增强包）」。 */
    allowPack: Boolean = false,
) {
    var source by remember { mutableStateOf(initial.source) }
    var profileId by remember { mutableStateOf(initial.profileId) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allowChat) {
                        FilterChip(
                            selected = source == MemoryModelConfig.SOURCE_CHAT,
                            onClick = { source = MemoryModelConfig.SOURCE_CHAT },
                            label = { Text("跟随对话", fontSize = 12.sp) },
                        )
                    }
                    if (allowBuiltin) {
                        FilterChip(
                            selected = source == MemoryModelConfig.SOURCE_BUILTIN,
                            onClick = { source = MemoryModelConfig.SOURCE_BUILTIN },
                            label = { Text("内置轻量", fontSize = 12.sp) },
                        )
                    }
                    if (allowPack) {
                        FilterChip(
                            selected = source == MemoryModelConfig.SOURCE_ONNX,
                            onClick = { source = MemoryModelConfig.SOURCE_ONNX },
                            label = { Text("内置模型", fontSize = 12.sp) },
                        )
                    }
                    FilterChip(
                        selected = source == MemoryModelConfig.SOURCE_PROFILE,
                        onClick = { source = MemoryModelConfig.SOURCE_PROFILE },
                        label = { Text("已配置模型", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = source == MemoryModelConfig.SOURCE_CUSTOM,
                        onClick = { source = MemoryModelConfig.SOURCE_CUSTOM },
                        label = { Text("独立配置", fontSize = 12.sp) },
                    )
                }

                when (source) {
                    MemoryModelConfig.SOURCE_ONNX -> {
                        Text(
                            "真正的神经网络向量（bge-small-zh-v1.5，512 维，约 23MB，下载后离线运行）。" +
                                "中文语义明显强于内置轻量；下载失败或运行失败都会自动退回轻量/字面检索。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    MemoryModelConfig.SOURCE_BUILTIN -> {
                        Text(
                            "内置哈希向量（256 维，零下载、零内存占用）。它只是把字面相近变成向量相近，" +
                                "不是神经网络模型；好处是不依赖任何服务，关掉也不影响记忆本身。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    MemoryModelConfig.SOURCE_CHAT -> {
                        Text(
                            "直接用聊天当前选中的模型。零配置，但记忆整理会占用同一个模型。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    MemoryModelConfig.SOURCE_PROFILE -> {
                        Text(
                            "复用「模型配置」里已经配好的某一份（共享它的地址与 Key）。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        if (profiles.isEmpty()) {
                            Text("还没有任何模型配置。", fontSize = 12.sp)
                        }
                        // ★ 向量模式只列**支持 /embeddings 的厂商**的配置；
                        //   把聊天模型（如 deepseek）列出来就是让人选一个必然失败的。
                        val pickable = if (allowChat) profiles
                        else profiles.filter { MemoryModelConfig.isEmbeddingCapable(it.providerId) }
                        if (pickable.isEmpty() && !allowChat) {
                            Text(
                                "现有配置里没有可用作向量的模型（聊天模型不支持 /embeddings）。" +
                                    "可以先用「内置轻量」，或在这里选「独立配置」填一个 embeddings 端点。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        pickable.forEach { p ->
                            FilterChip(
                                selected = profileId == p.id,
                                onClick = { profileId = p.id },
                                label = {
                                    Text(
                                        "${p.model.ifBlank { "未选模型" }}",
                                        fontSize = 12.sp,
                                    )
                                },
                                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            singleLine = true,
                            label = { Text("API 地址", fontSize = 12.sp) },
                            placeholder = { Text("https://api.example.com/v1", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            singleLine = true,
                            label = { Text("API Key", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                singleLine = true,
                                label = { Text("模型", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f).padding(top = 6.dp),
                            )
                            TextButton(
                                enabled = !fetching && baseUrl.isNotBlank(),
                                onClick = {
                                    fetching = true
                                    fetchError = null
                                    scope.launch {
                                        val ids = onFetch("custom", baseUrl, apiKey)
                                        fetched = ids
                                        fetching = false
                                        if (ids.isEmpty()) fetchError = "没拉到模型（检查地址/Key，或手动填写）"
                                    }
                                },
                            ) {
                                Text(if (fetching) "拉取中…" else "拉取", fontSize = 12.sp)
                            }
                        }
                        fetchError?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                        fetched.take(40).forEach { id ->
                            FilterChip(
                                selected = model == id,
                                onClick = { model = id },
                                label = { Text(id, fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = when (source) {
                    MemoryModelConfig.SOURCE_CHAT -> true
                    MemoryModelConfig.SOURCE_BUILTIN -> true
                    MemoryModelConfig.SOURCE_ONNX -> true
                    MemoryModelConfig.SOURCE_PROFILE -> profileId.isNotBlank()
                    else -> baseUrl.isNotBlank() && model.isNotBlank()
                },
                onClick = {
                    onSave(
                        MemoryModelConfig(
                            source = source,
                            profileId = profileId,
                            providerId = "custom",
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
