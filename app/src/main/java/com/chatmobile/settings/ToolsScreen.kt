package com.chatmobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chatmobile.tools.ChatTools
import chatmobile.tools.ToolDefinition
import chatmobile.tools.ToolKind

/**
 * **工具设置页** —— 用户自由配置「给模型哪些工具」。
 *
 * ## ★★★ 为什么必须有这一页（而不是默认全开）
 *
 * 1. **信任**：工具会读位置、读网页、写记忆。用户有权知道有哪些、并逐条关掉它。
 *    LibreChat / Claude 都把这当成底线，不是可选功能。
 * 2. **成本**：工具 schema 每轮都要发给模型，而且**排在系统提示词之前**
 *    （Anthropic 的 `tools` → `system` → `messages`）。多挂一个工具 = 每轮多花 token。
 *    用户用不上的工具，关掉是实打实的省钱。
 * 3. **能力错配**：给不支持 function calling 的模型挂工具，部分网关直接 400。
 *    能关掉就有退路。
 *
 * ## ★ 设计上的三条取舍
 *
 * - **按风险分级展示，不按字母排序**：用户关心的是"它会不会动我的东西"，
 *   所以分成「只读 / 写本机 / 访问网络」三组，而不是一个平铺列表。
 * - **每个工具都显示"它做了什么"**（[ToolDefinition.summary]），
 *   不显示 schema —— 那是给模型看的，给人看只会让人更糊。
 * - **结果长度可调**：1M 窗口的模型能多吃，8K 窗口的模型吃多了会爆。
 *   写死一个数字必然对某一半用户是错的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsScreen(
    modeMap: Map<String, chatmobile.tools.ToolMode>,
    resultChars: Int,
    onBack: () -> Unit,
    onModeChange: (String, chatmobile.tools.ToolMode) -> Unit,
    onSaveResultChars: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 专用搜索服务地址（空 = 跟随模型自己的接入点）。 */
    searchEndpoint: String = "",
    /** 专用搜索服务的 key。 */
    searchApiKey: String = "",
    onSaveSearchEndpoint: (String, String) -> Unit = { _, _ -> },
    /**
     * 已保存的 MCP 服务器。
     *
     * ★ 这一页必须能看到它们 —— 否则用户配好之后就再也找不到入口去
     *   启停 / 删除 / 改模式。而 MCP 工具默认是「按需」，想让它常驻
     *   只能在这里改。
     */
    mcpServers: List<com.chatmobile.mcp.McpServerConfig> = emptyList(),
    /** 这些服务器当轮探测到的工具（用于展示每个服务器带来多少工具）。 */
    mcpDefinitions: List<ToolDefinition> = emptyList(),
    onToggleMcp: (url: String, enabled: Boolean) -> Unit = { _, _ -> },
    onRemoveMcp: (url: String) -> Unit = {},
) {
    // ★ 本地草稿：输入即落盘的 Compose 输入框必须持本地草稿（L12）。
    //   直接渲染 DataStore 的值会让每次按键都和异步回写打架，字符会被吞。
    var charsDraft by remember(resultChars) { mutableStateOf(resultChars.toString()) }
    var epDraft by remember(searchEndpoint) { mutableStateOf(searchEndpoint) }
    var keyDraft by remember(searchApiKey) { mutableStateOf(searchApiKey) }

    Scaffold(
        // ★★★ imePadding 必须加在 **Scaffold 外层**（与「模型」页同一写法）。
        //
        //   用户报：「输入东西的时候，整个界面会被键盘挡住，为什么不能顶上去呢
        //   （其他需要输入东西的界面也是如此，只有主界面可以顶上去）」
        //
        //   根因：这几页只加了 `navigationBarsPadding()` —— 那只躲导航条，**不躲键盘**。
        //   而主界面用的是 `WindowInsets.ime.union(WindowInsets.navigationBars)`，所以它能顶上去。
        //
        //   为什么放在 Scaffold 外层而不是内部滚动容器上：
        //   内部滚动容器被压缩后，它的 contentPadding 仍按原高度算，
        //   下方字段既进不了可视区也滚不到（「模型」页踩过，注释还留着）。
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("工具", fontSize = 17.sp) },
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
                // ★ 保留导航条内边距：键盘收起时它负责避开手势条。
                //   键盘弹起时外层 imePadding 已经把它们并进去了，这里不会重复叠加
                //   （Scaffold 外层的 ime 内边距会把可用高度减掉，导航条内边距随之归零）。
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "开启后模型可以主动调用这些工具。关掉的工具即使模型想用也会被拒绝，",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "而且它的定义不会发给模型（省 token，也让缓存前缀更短）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 按风险分组 —— 顺序固定，与 ChatTools.all() 的展示顺序一致
            ToolGroup(
                title = "只读（不改任何东西）",
                hint = "默认全开。拿不到数据时会如实说明，不会编造。",
                items = ChatTools.all().filter { it.kind == ToolKind.READ_ONLY },
                modeMap = modeMap,
                onModeChange = onModeChange,
            )
            ToolGroup(
                title = "写入（会改你的记忆库）",
                hint = "只写记忆，不碰文件、不碰系统。写入后可在「记忆与提示词」里删除。",
                items = ChatTools.all().filter { it.kind == ToolKind.WRITE_LOCAL },
                modeMap = modeMap,
                onModeChange = onModeChange,
            )
            ToolGroup(
                title = "访问网络",
                hint = "会把要抓取的网址发出去。注意：查询内容对目标站点可见。",
                items = ChatTools.all().filter { it.kind == ToolKind.EXTERNAL },
                modeMap = modeMap,
                onModeChange = onModeChange,
            )

            // ---- MCP ----
            //
            // ★ 放在最后：它是"进阶功能"，新用户不该第一眼就被它拦住。
            //   但它必须**存在** —— 否则用户配好的 MCP 工具没有地方管理
            //   （加进来却改不了模式 = 半成品）。
            Spacer(Modifier.height(4.dp))
            McpSection(
                servers = mcpServers,
                definitions = mcpDefinitions,
                modeMap = modeMap,
                onModeChange = onModeChange,
                onToggle = onToggleMcp,
                onRemove = onRemoveMcp,
            )

            Spacer(Modifier.height(4.dp))

            // ---- 结果长度 ----
            Text("工具结果长度上限", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "单个工具返回给模型的字数上限（1000–20000）。窗口大的模型可以调高，" +
                    "小窗口模型调高会把上下文撑爆。默认 8000。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = charsDraft,
                onValueChange = { input ->
                    // ★ 只收数字，长度封 5 位 —— 否则用户可以输入天文数字。
                    val digits = input.filter { it.isDigit() }.take(5)
                    charsDraft = digits
                    // 落库时机：只在解析成有效范围时才写，避免"输到一半被钳成 1000"
                    digits.toIntOrNull()?.let { v ->
                        if (v in 1000..20000) onSaveResultChars(v)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (charsDraft.toIntOrNull()?.let { it in 1000..20000 } == false) {
                Text(
                    text = "有效范围是 1000–20000，当前值不会被保存。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- 搜索服务（三级里的第二级）----
            //
            // ★ 这一块必须**说清三级关系**，否则用户会以为"不填就搜不了"。
            //   实际默认行为是"复用当前模型自己的 key"。
            Text("联网搜索服务", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                // ★ 这里**不能写 Markdown 星号** —— 该组件是普通 Text，
                //   不是 MarkdownText，星号会被原样显示出来（真机截图确认过）。
                //   要强调就用措辞和换行，不要用标记。
                text = "搜索按这个顺序试：\n" +
                    "① 免费源（不需要 key，最快）\n" +
                    "② 付费通道 —— 二选一，不会同时用：\n" +
                    "    · 下面的地址与 key 没填 ⇒ 用当前模型自己的接入点\n" +
                    "    · 填了 ⇒ 只用你填的这个，不再碰模型自带的接口\n\n" +
                    "为什么互斥：填了之后还去试模型自带的，会多扣一次费。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = epDraft,
                onValueChange = { epDraft = it },
                singleLine = true,
                label = { Text("搜索服务地址", fontSize = 12.sp) },
                placeholder = { Text("https://api.deepseek.com", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                singleLine = true,
                // ★ 用密码样式：地址与 key 会被别人看到屏幕时泄露。
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                label = { Text("搜索服务 Key", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSaveSearchEndpoint(epDraft, keyDraft) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "保存搜索服务",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (epDraft.isBlank() || keyDraft.isBlank()) {
                    "现在没配专用服务 —— 搜索会用当前模型自己的接入点。"
                } else {
                    "保存后所有模型都只走这个地址搜索，不会再用模型自带的（避免多扣费）。"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun McpSection(
    servers: List<com.chatmobile.mcp.McpServerConfig>,
    definitions: List<ToolDefinition>,
    modeMap: Map<String, chatmobile.tools.ToolMode>,
    onModeChange: (String, chatmobile.tools.ToolMode) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MCP 服务器", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = if (servers.isEmpty()) {
                "还没有配置。你可以在对话里直接把一个 MCP 地址发给模型，让它帮你探测并添加 —— " +
                    "它会先展示地址和工具数，你点确认才会真的保存。"
            } else {
                "这些服务器提供的工具默认是「按需」：不占常驻上下文，模型要用时才查。" +
                    "常用的可以在这里改成「常驻」。"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        servers.forEach { server ->
            // ★ 按前缀把这个服务器的工具挑出来（定义里没存 server url，
            //   只存了带前缀的名字 —— 那是唯一稳定的身份）。
            val prefix = server.prefix.ifBlank {
                com.chatmobile.mcp.McpConfigCodec.prefixFrom(server.name)
            }
            val mine = definitions.filter { it.name.startsWith("${prefix}__") }

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
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            // ★ 地址**脱敏显示**：屏幕上出现完整 token 是真实风险
                            //   （截图分享、录屏、被旁人看到）。
                            Text(
                                com.chatmobile.mcp.McpUrlGuard.redactForDisplay(server.url),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${mine.size} 个工具 · ${server.transport}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { onToggle(server.url, it) },
                        )
                    }

                    TextButton(
                        onClick = { onRemove(server.url) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }

                    // ★ 只有启用且探测到工具时才逐个列出来改模式 ——
                    //   探测失败时列表为空，那是如实反映（不是隐藏）。
                    if (server.enabled && mine.isNotEmpty()) {
                        mine.forEach { def ->
                            ToolRow(
                                def = def,
                                mode = modeMap[def.name] ?: def.preferredMode,
                                onModeChange = { onModeChange(def.name, it) },
                            )
                        }
                    } else if (server.enabled) {
                        Text(
                            "暂时探测不到它的工具 —— 服务器可能离线了。工具本身不会因此被删掉。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolGroup(
    title: String,
    hint: String,
    items: List<ToolDefinition>,
    modeMap: Map<String, chatmobile.tools.ToolMode>,
    onModeChange: (String, chatmobile.tools.ToolMode) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { def ->
            ToolRow(
                def = def,
                mode = modeMap[def.name] ?: def.preferredMode,
                onModeChange = { onModeChange(def.name, it) },
            )
        }
    }
}

@Composable
private fun ToolRow(
    def: ToolDefinition,
    mode: chatmobile.tools.ToolMode,
    onModeChange: (chatmobile.tools.ToolMode) -> Unit,
) {
    val isOff = mode == chatmobile.tools.ToolMode.Disabled
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
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(def.displayName, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        // 工具名给用户看有用：模型在回答里可能提到"我用了 get_time"
                        Text(
                            text = def.name,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = def.summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ★★★ 三态选择器，不是开关。
            //
            //   用户原话：「注意用户可以选择哪些工具常驻上下文，哪些工具进行懒加载。」
            //
            //   为什么不能用开关 + 另一个"懒加载"开关：两个布尔量能表达 4 种组合，
            //   其中一种（关掉 + 懒加载）**没有意义**，用户会在一个不可能的状态里点来点去。
            //   三段式控件从结构上就排除了非法组合。
            ToolModeSelector(mode = mode, onChange = onModeChange)

            // ★ 只有在懒加载时才解释代价 —— 常驻是默认行为，不需要解释
            if (mode == chatmobile.tools.ToolMode.Lazy) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "只在目录里显示名字和一句话；模型要用时先花一次往返查它的参数。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (isOff) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "模型看不到这个工具，也不会尝试调用它。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 三态分段控件：常驻 / 按需 / 关闭。
 *
 * ★ 为什么自己拼而不是用 `SegmentedButton`：
 *   M3 的 `SingleChoiceSegmentedButtonRow` 需要 Material3 1.2+ 的稳定 API，
 *   而我们这套 Compose BOM 里它仍有实验注解 —— 引实验 API 会让后续升级变脆。
 *   自己拼三个 Box 反而更可控（也更容易做整块可点，见 L28）。
 */
@Composable
private fun ToolModeSelector(
    mode: chatmobile.tools.ToolMode,
    onChange: (chatmobile.tools.ToolMode) -> Unit,
) {
    val entries = listOf(
        chatmobile.tools.ToolMode.Resident to "常驻",
        chatmobile.tools.ToolMode.Lazy to "按需",
        chatmobile.tools.ToolMode.Disabled to "关闭",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        entries.forEach { (value, label) ->
            val selected = mode == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    // ★ 整块可点（L28）：只让 Text 可点的话热区只有文字那么大
                    .clickable { if (!selected) onChange(value) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
