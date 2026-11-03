package com.chatmobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import chatmobile.memory.MemoryConfig

/**
 * 「记忆库参数」页：把 `MemoryConfig` 里所有能调的项暴露给用户。
 *
 * ## 两条设计原则
 *
 * 1. **每项都写清"调大/调小会怎样"** —— 只给数字不给人话，用户不敢动，
 *    动了也不知道自己在牺牲什么。
 * 2. **不改就不写盘**（稀疏覆盖，见 [MemoryTuning]）—— 默认值跟着代码走，
 *    用户只 override 他真正在意的那几项。
 */
@Composable
internal fun MemoryTuningTab(
    tuning: MemoryTuning,
    onSave: (MemoryTuning) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaults = remember { MemoryConfig() }
    // 本地草稿：拖动滑块时**不落盘**，点「保存」才写（L12 同类问题：逐帧落盘会抖）
    var draft by remember(tuning) { mutableStateOf(tuning) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "这里调的是记忆库自己的行为。改动只影响之后的读写，不会回头删你已有的记忆。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        SectionTitle("整理频率")
        IntSlider(
            label = "攒够几条新消息才整理一次",
            hint = "越小越及时，但每次都把「已有记忆 + 新对话」发给管理员模型，越费 token",
            value = draft.extractEveryTurns ?: defaults.extractEveryTurns,
            range = 1..20,
            defaultValue = defaults.extractEveryTurns,
            onChange = { draft = draft.copy(extractEveryTurns = it) },
        )
        IntSlider(
            label = "一次最多喂几条消息",
            hint = "太大模型会开始「总结对话」而不是「抽事实」",
            value = draft.extractWindow ?: defaults.extractWindow,
            range = 2..40,
            defaultValue = defaults.extractWindow,
            onChange = { draft = draft.copy(extractWindow = it) },
        )
        IntSlider(
            label = "一次最多写几条",
            hint = "直接决定管理员模型的最大输出长度；设太大等于纵容它编",
            value = draft.extractMaxOps ?: defaults.extractMaxOps,
            range = 1..15,
            defaultValue = defaults.extractMaxOps,
            onChange = { draft = draft.copy(extractMaxOps = it) },
        )

        SectionTitle("记忆长度与容量")
        IntSlider(
            label = "单条记忆最多多少字",
            hint = "超出的部分会被截断；记忆是「一句话事实」，不是聊天摘要",
            value = draft.maxEntryChars ?: defaults.maxEntryChars,
            range = 80..1000,
            defaultValue = defaults.maxEntryChars,
            step = 20,
            onChange = { draft = draft.copy(maxEntryChars = it) },
        )
        IntSlider(
            label = "每次最多注入几条",
            hint = "上限决定记忆能吃掉多少上下文；条数越多越可能盖住历史",
            value = draft.maxInjectedEntries ?: defaults.maxInjectedEntries,
            range = 10..300,
            defaultValue = defaults.maxInjectedEntries,
            step = 10,
            onChange = { draft = draft.copy(maxInjectedEntries = it) },
        )
        IntSlider(
            label = "近期记忆容量",
            hint = "超了就删权重最低的（长期记忆只降级、永不删）",
            value = draft.recentCapacity ?: defaults.recentCapacity,
            range = 50..2000,
            defaultValue = defaults.recentCapacity,
            step = 50,
            onChange = { draft = draft.copy(recentCapacity = it) },
        )
        IntSlider(
            label = "长期记忆容量",
            hint = "满了会把最旧的降级回近期，不会直接删",
            value = draft.savedCapacity ?: defaults.savedCapacity,
            range = 20..500,
            defaultValue = defaults.savedCapacity,
            step = 10,
            onChange = { draft = draft.copy(savedCapacity = it) },
        )

        SectionTitle("去重与衰减")
        DoubleSlider(
            label = "合并阈值",
            hint = "低 = 更激进地合并（可能把两件事并成一件）；高 = 更保守（可能出现近似重复）",
            value = draft.nearDuplicateThreshold ?: defaults.nearDuplicateThreshold,
            range = 0.4f..0.95f,
            defaultValue = defaults.nearDuplicateThreshold,
            onChange = { draft = draft.copy(nearDuplicateThreshold = it) },
        )
        IntSlider(
            label = "命中几次升为长期",
            hint = "被反复用到的记忆自动升级；设为 1 等于「用过就永久记住」",
            value = draft.promoteThreshold ?: defaults.promoteThreshold,
            range = 1..10,
            defaultValue = defaults.promoteThreshold,
            onChange = { draft = draft.copy(promoteThreshold = it) },
        )
        DoubleSlider(
            label = "近期记忆半衰期（天）",
            hint = "几天后权重减半；越短忘得越快",
            value = draft.decayHalfLifeDays ?: defaults.decayHalfLifeDays,
            range = 1f..60f,
            defaultValue = defaults.decayHalfLifeDays,
            onChange = { draft = draft.copy(decayHalfLifeDays = it) },
        )

        SectionTitle("上下文预算占比")
        DoubleSlider(
            label = "长期记忆占比",
            hint = "占模型上下文窗口的比例；与下面一项之和不能超过 100%",
            value = draft.savedBudgetRatio ?: defaults.savedBudgetRatio,
            range = 0.02f..0.5f,
            defaultValue = defaults.savedBudgetRatio,
            onChange = { draft = draft.copy(savedBudgetRatio = it) },
        )
        DoubleSlider(
            label = "近期记忆占比",
            hint = "同上；超出部分自动被截断",
            value = draft.recentBudgetRatio ?: defaults.recentBudgetRatio,
            range = 0.02f..0.5f,
            defaultValue = defaults.recentBudgetRatio,
            onChange = { draft = draft.copy(recentBudgetRatio = it) },
        )

        SectionTitle("向量召回")
        DoubleSlider(
            label = "相似度下限",
            hint = "余弦低于它的一律不返回。设 0 等于不过滤 —— 真机上因此出现过「问 zzzz 也返回 5 条」",
            value = draft.retrievalMinScore ?: defaults.retrievalMinScore,
            range = 0.0f..0.95f,
            defaultValue = defaults.retrievalMinScore,
            onChange = { draft = draft.copy(retrievalMinScore = it) },
        )
        DoubleSlider(
            label = "相对上限比例",
            hint = "只保留「不低于最高分 × 这个比例」的条目，避免把 top-K 填满",
            value = draft.retrievalRelativeRatio ?: defaults.retrievalRelativeRatio,
            range = 0.5f..1.0f,
            defaultValue = defaults.retrievalRelativeRatio,
            onChange = { draft = draft.copy(retrievalRelativeRatio = it) },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { draft = MemoryTuning() },
                enabled = !draft.isDefault,
                modifier = Modifier.weight(1f),
            ) { Text("全部恢复默认", fontSize = 12.sp) }

            androidx.compose.material3.Button(
                onClick = { onSave(draft) },
                enabled = draft != tuning,
                modifier = Modifier.weight(1f),
            ) { Text("保存", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun IntSlider(
    label: String,
    hint: String,
    value: Int,
    range: IntRange,
    defaultValue: Int,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (value == defaultValue) "$value（默认）" else "$value",
                fontSize = 12.sp,
                color = if (value == defaultValue) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v ->
                val snapped = (v / step).toInt() * step
                onChange(snapped.coerceIn(range.first, range.last))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DoubleSlider(
    label: String,
    hint: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Double,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (kotlin.math.abs(value - defaultValue) < 1e-6) {
                    "%.2f（默认）".format(value)
                } else {
                    "%.2f".format(value)
                },
                fontSize = 12.sp,
                color = if (kotlin.math.abs(value - defaultValue) < 1e-6) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v ->
                // 保留两位小数，避免存进 DataStore 的是一串浮点噪声
                onChange((kotlin.math.round(v * 100) / 100.0).toDouble())
            },
            valueRange = range,
        )
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
