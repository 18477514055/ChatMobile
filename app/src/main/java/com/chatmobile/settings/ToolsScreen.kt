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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    enabledMap: Map<String, Boolean>,
    resultChars: Int,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSaveResultChars: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ★ 本地草稿：输入即落盘的 Compose 输入框必须持本地草稿（L12）。
    //   直接渲染 DataStore 的值会让每次按键都和异步回写打架，字符会被吞。
    var charsDraft by remember(resultChars) { mutableStateOf(resultChars.toString()) }

    Scaffold(
        modifier = modifier,
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
                enabledMap = enabledMap,
                onToggle = onToggle,
            )
            ToolGroup(
                title = "写入（会改你的记忆库）",
                hint = "只写记忆，不碰文件、不碰系统。写入后可在「记忆与提示词」里删除。",
                items = ChatTools.all().filter { it.kind == ToolKind.WRITE_LOCAL },
                enabledMap = enabledMap,
                onToggle = onToggle,
            )
            ToolGroup(
                title = "访问网络",
                hint = "会把要抓取的网址发出去。注意：查询内容对目标站点可见。",
                items = ChatTools.all().filter { it.kind == ToolKind.EXTERNAL },
                enabledMap = enabledMap,
                onToggle = onToggle,
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
        }
    }
}

@Composable
private fun ToolGroup(
    title: String,
    hint: String,
    items: List<ToolDefinition>,
    enabledMap: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { def ->
            ToolRow(
                def = def,
                enabled = enabledMap[def.name] ?: def.enabledByDefault,
                onToggle = { onToggle(def.name, it) },
            )
        }
    }
}

@Composable
private fun ToolRow(
    def: ToolDefinition,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
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
            modifier = Modifier
                .fillMaxWidth()
                // ★ 整行可点，不只是开关 —— 开关本身在 M3 里只有约 32dp 宽，
                //   手指点偏了就会没反应，用户会以为"点不动"。
                .clickable { onToggle(!enabled) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Spacer(Modifier.width(10.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
