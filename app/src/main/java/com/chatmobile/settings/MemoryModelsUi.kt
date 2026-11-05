package com.chatmobile.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
 * 「提示词」页：用户自己写的人设/规矩 —— **以模板形式管理**。
 *
 * ★ 与记忆库的分界（这是产品语义，不是实现细节）：
 *   - **记忆库** = 模型自己观察到的关于你的东西，会被自动抽取、合并、衰减；
 *   - **提示词** = 你主动要求它成为什么（人设、口吻、禁区），**只会被你改**。
 *   所以它不进记忆库（否则一次"整理记忆"就能把你写的人设合并掉），
 *   而是紧接着固定提示词注入，位置固定、内容稳定，对缓存友好。
 *
 * ## ★★★ 为什么从「一个大文本框」改成「模板列表 + 编辑器」
 *
 * 用户原话：「现在允许将提示词保存为一个模板（一个模板包含一个标题和一个提示词文本，
 * 可以随时切换）」。
 *
 * 单文本框的本质问题是**它没有留住任何东西** —— 换人设就得把旧的删掉重写，
 * 而那些写过的通常是要反复用的。
 *
 * ## 交互上的两个刻意的取舍
 *
 * 1. **切换是"点了立即生效"**，不需要再点保存 —— 切换是高频小动作，
 *    再要一次确认会让它变得笨重。
 * 2. **正文编辑仍然要显式保存** —— 它是低频的、且误改代价大。
 *    "切换立即、编辑保存"这个组合是有意为之，不是不一致。
 */
@Composable
internal fun PromptTab(
    /** 模板集合 + 当前选中。 */
    templates: com.chatmobile.prompt.PromptTemplateSet,
    onSaveTemplates: (com.chatmobile.prompt.PromptTemplateSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前正在编辑哪个模板（null = 没在编辑，显示列表）
    var editingId by remember { mutableStateOf<String?>(null) }
    // 编辑器草稿（标题 + 正文）
    var titleDraft by remember { mutableStateOf("") }
    var textDraft by remember { mutableStateOf("") }
    // 上一次的错误/提示
    var notice by remember { mutableStateOf("") }

    val editing = editingId?.let { id -> templates.templates.firstOrNull { it.id == id } }
    val dirty = editing == null || titleDraft != editing.title || textDraft != editing.text

    /** 进入编辑一个已有模板。 */
    fun beginEdit(t: com.chatmobile.prompt.PromptTemplate) {
        editingId = t.id
        titleDraft = t.title
        textDraft = t.text
        notice = ""
    }

    /** 进入"新建"。 */
    fun beginNew() {
        editingId = ""
        titleDraft = ""
        textDraft = ""
        notice = ""
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
    ) {
        Text(
            "提示词模板",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "一句话人设，或者一整套规矩。可以存多个，随时切换 —— 切换立刻生效。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        if (editingId == null) {
            // ---------------- 列表模式 ----------------

            // ★「不使用模板」是一个**明确可选的状态**，不是"没选"。
            //   用户可能就想要纯净的默认提示词。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSaveTemplates(templates.copy(selectedId = null)) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = templates.selectedId == null,
                    onClick = { onSaveTemplates(templates.copy(selectedId = null)) },
                )
                Text("不使用模板（只用内置提示词）", fontSize = 13.sp)
            }

            templates.templates.forEach { t ->
                val selected = templates.selectedId == t.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ★ 整行可点（L28）：只让 RadioButton 可点的话，
                        //   手指落在文字上没反应，长条形的行会显得"点不动"。
                        .clickable {
                            onSaveTemplates(
                                com.chatmobile.prompt.PromptTemplatePolicy.select(templates, t.id),
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = {
                            onSaveTemplates(
                                com.chatmobile.prompt.PromptTemplatePolicy.select(templates, t.id),
                            )
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            // 正文预览：让人一眼分辨这个模板讲的是什么人设
                            t.text.replace('\n', ' ').take(40).ifEmpty { "（正文为空）" },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { beginEdit(t) }) { Text("编辑", fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            // ★ 走 Policy.delete：它会**修复选中项**，
                            //   删掉的正好是当前选中时把它清成 null，
                            //   而不是偷偷跳到别的模板上（那会让用户以为选择被记住了）。
                            onSaveTemplates(
                                com.chatmobile.prompt.PromptTemplatePolicy.delete(templates, t.id),
                            )
                            notice = "已删除「${t.title}」"
                        },
                    ) {
                        Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (templates.templates.isEmpty()) {
                Text(
                    "还没有模板。点下面的「新建」写第一个 —— 比如「严谨技术助理」。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { beginNew() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("新建模板") }

            if (notice.isNotBlank()) {
                Text(
                    notice,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            // ---------------- 编辑模式 ----------------

            // ★ 操作行放**输入框上方**：输入时键盘会盖住下半屏，
            //   放下面的按钮点不到（真机实测过，这条注释在上一版就有）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${textDraft.length} 字" + if (dirty) " · 未保存" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    editingId = null
                    notice = ""
                }) { Text("返回", fontSize = 12.sp) }
                TextButton(
                    enabled = dirty,
                    onClick = {
                        val isNew = editingId == ""
                        val verdict = com.chatmobile.prompt.PromptTemplatePolicy.check(
                            title = titleDraft,
                            text = textDraft,
                            existing = templates.templates,
                            editingId = editingId?.takeIf { it.isNotEmpty() },
                        )
                        when (verdict) {
                            is com.chatmobile.prompt.PromptTemplatePolicy.Verdict.Rejected -> {
                                // ★ 判据在 Policy 里，这里只负责显示它说了什么
                                notice = verdict.reason
                            }
                            is com.chatmobile.prompt.PromptTemplatePolicy.Verdict.Ok -> {
                                if (isNew) {
                                    val id = "tpl-" + System.currentTimeMillis()
                                    val created = com.chatmobile.prompt.PromptTemplate(
                                        id = id,
                                        title = verdict.title,
                                        text = verdict.text,
                                    )
                                    onSaveTemplates(
                                        templates.copy(
                                            templates = templates.templates + created,
                                            // ★ 新建后**直接选上它** —— 用户点"新建"的意图
                                            //   就是要用它，还要再点一次选择很多余。
                                            selectedId = id,
                                        ),
                                    )
                                } else {
                                    val id = editingId!!
                                    onSaveTemplates(
                                        templates.copy(
                                            templates = templates.templates.map {
                                                if (it.id == id) {
                                                    it.copy(title = verdict.title, text = verdict.text)
                                                } else {
                                                    it
                                                }
                                            },
                                        ),
                                    )
                                }
                                editingId = null
                                notice = "已保存"
                            }
                        }
                    },
                ) {
                    // ★ 只有新模板可以在还没写标题时点保存 —— 让 Policy 去报原因，
                    //   比把按钮置灰更容易理解（置灰的用户不知道缺什么）。
                    Text("保存", fontSize = 13.sp)
                }
            }

            OutlinedTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it.take(com.chatmobile.prompt.PromptTemplatePolicy.MAX_TITLE + 8) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("标题", fontSize = 12.sp) },
                placeholder = { Text("例如：严谨技术助理", fontSize = 12.sp) },
            )
            Text(
                "切换模板时你就是靠标题来认的，所以起个一看就懂的名字。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            OutlinedTextField(
                value = textDraft,
                onValueChange = { textDraft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                minLines = 7,
                maxLines = 20,
                label = { Text("提示词正文", fontSize = 12.sp) },
                placeholder = {
                    Text(
                        "例如：\n你是一位严谨的中文技术助理，回答先给结论再给依据。\n不要使用「作为 AI」这类说法。",
                        fontSize = 12.sp,
                    )
                },
            )
            Text(
                "这一段会被紧接着内置提示词注入。它和记忆是两回事：记忆是模型观察到的你，这里是你的指令。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (notice.isNotBlank()) {
                Text(
                    notice,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
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
                    // ★★★ 对话框里的输入框**同样会被键盘盖住**，而 Dialog 是独立窗口，
                    //   外层页面的 imePadding **管不到它**（那是另一个 WindowInsets 消费方）。
                    //   真机上表现为：点「API 地址」弹键盘，输入框连同「确定」一起被压在下面。
                    //   ⇒ 对话框内部必须自己消费一次 ime 内边距。
                    .imePadding()
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
