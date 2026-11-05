package com.chatmobile.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * **工具确认框** —— `requiresConfirmation` 的 UI 出口。
 *
 * ## ★★★ 为什么必须真的存在这个东西
 *
 * `ChatTools.writeFile` 标了 `requiresConfirmation = true`。在写这个文件之前，
 * **全项目没有任何地方读过那个字段** —— 一张空头支票。而它守的是
 * 「覆盖用户已有文件」这种不可逆操作。
 *
 * 本项目已经栽过一次「设置项存了但没人读」（思考过程展示，L26），
 * 那次的表现是"设置了没反应"。这次如果也漏掉，表现是**静默覆盖用户数据** ——
 * 后果严重得多。
 *
 * ## ★ 三个设计选择
 *
 * 1. **默认按钮是「拒绝」且在左边**：误触的代价不对等。点错"允许"会改文件，
 *    点错"拒绝"只是模型白跑一次。
 * 2. **不可点外部关闭**（`dismissOnClickOutside = false`）：
 *    点到外面就关掉会让用户不知道发生了什么，而执行方在等一个明确答复。
 * 3. **危险操作用红色**：覆盖已有文件时按钮变红，与"新建"区分开。
 *    用户扫一眼颜色就知道这一下会不会动到他原来的东西。
 */
@Composable
fun ToolConfirmDialog(
    request: PendingToolConfirm,
    onDecision: (Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = {
            // ★ 点外部/返回键 = 拒绝。绝不能当成"允许"，
            //   也不应该什么都不做 —— 执行方在等，超时前给个明确的否更好。
            onDecision(false)
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = request.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = request.detail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ★ 拒绝在左、默认视觉权重低 —— 误触代价不对等（见类文档）
                    DialogButton(
                        label = "不允许",
                        emphasis = false,
                        onClick = { onDecision(false) },
                    )
                    Spacer(Modifier.width(8.dp))
                    DialogButton(
                        label = "允许",
                        emphasis = true,
                        danger = request.danger,
                        onClick = { onDecision(true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    emphasis: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        danger -> MaterialTheme.colorScheme.error
        emphasis -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .border(
                width = if (emphasis) 1.2.dp else 0.5.dp,
                color = if (emphasis) color else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            // ★ clickable 必须在 padding **之前**（L28：反了就是死区）
            .clickable { onClick() },
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
            color = color,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
