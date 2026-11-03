package com.chatmobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatmobile.markdown.MdAlign
import com.chatmobile.markdown.MdBlock
import com.chatmobile.markdown.MdInline

/**
 * Markdown 渲染器。
 *
 * ## ★ 为什么行内元素用 `AnnotatedString` 而不是嵌套 `Text`
 *
 * 嵌套 `Text` 会让**换行与断词交给 Compose 的 Row/Column 布局**，
 * 结果是「粗体那一段的换行位置和其它段落不一致」—— 中文尤其明显（字距会跳）。
 * `AnnotatedString` 把整段交给**一个** `Text` 排版，换行断词完全一致，
 * 只是给不同区间套样式。代价是不能给片段单独设点击，但我们只有链接需要点击，
 * 而链接在聊天里让用户长按复制就够了（见下）。
 *
 * ## ★ 代码块横向滚动
 *
 * 代码块用 `horizontalScroll` 而不是自动折行 —— 代码折行会破坏缩进语义
 * （Python 尤甚）。这也和所有编辑器的行为一致。
 */
@Composable
fun MarkdownText(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 正文字号（sp）。`null` = 用主题默认（`bodyLarge`）。
     *
     * ★ 用户在「通用 → 字号大小」里调的就是这个值。
     *   只影响**正文与列表**，标题按比例小幅跟随，代码块固定 13sp ——
     *   代码行太长，跟着正文放大只会更容易横向滚动。
     */
    bodySize: Int? = null,
) {
    if (blocks.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            MdBlockView(block, textColor, bodySize)
            // 块间距：最后一块不加，避免气泡底部多一截空白
            if (index != blocks.lastIndex) {
                Spacer(Modifier.height(blockGapAfter(block)))
            }
        }
    }
}

/** 不同块之后该留多少空 —— 标题后面多一点，列表项之间少一点。 */
private fun blockGapAfter(block: MdBlock): androidx.compose.ui.unit.Dp = when (block) {
    is MdBlock.Heading -> 8.dp
    is MdBlock.Divider -> 10.dp
    is MdBlock.ListItem -> 4.dp
    is MdBlock.CodeBlock -> 10.dp
    else -> 8.dp
}

@Composable
private fun MdBlockView(block: MdBlock, textColor: Color, bodySize: Int? = null) {
    // ★ 正文样式：有自定义字号就用它，没有就回到主题默认。
    //   做成一个 val 而不是每处 copy 一次 —— 五处分支各写一遍必然有一处漏掉，
    //   而漏掉的表现是"字号改了但某一种块没变"（用户会当成 bug）。
    val body = bodySize?.let {
        MaterialTheme.typography.bodyLarge.copy(fontSize = it.sp, lineHeight = (it * 1.45f).sp)
    } ?: MaterialTheme.typography.bodyLarge

    when (block) {
        is MdBlock.Paragraph -> Text(
            text = annotate(block.spans),
            style = body,
            color = textColor,
        )

        is MdBlock.Heading -> Text(
            text = annotate(block.spans),
            // 标题字号按级别递减；不放大到夸张，聊天里过大的标题很破坏节奏。
            // ★ 有自定义字号时标题跟着正文**等比**缩放（基准 16sp），
            //   否则把正文调到 24 而标题还是 19，标题会比正文小 —— 视觉上完全错乱。
            fontSize = when (block.level) {
                1 -> 21.sp
                2 -> 19.sp
                3 -> 17.sp
                else -> 16.sp
            } * ((bodySize ?: 16).toFloat() / 16f),
            fontWeight = FontWeight.SemiBold,
            lineHeight = when (block.level) {
                1 -> 28.sp
                2 -> 26.sp
                else -> 24.sp
            } * ((bodySize ?: 16).toFloat() / 16f),
            color = textColor,
            modifier = Modifier.padding(top = 2.dp),
        )

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.Quote -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 左侧竖线（引用标记）。
                // ★ 用 `fillMaxHeight` 而不是固定高度：竖线必须随内容自动拉伸，
                //   写死高度的话长引用只有很短一段线。
                //   `Row` 的子项在 `Alignment.Top` 下 fillMaxHeight 会取 Row 的高度，
                //   而 Row 的高度由最高的子项（右边的文本列）决定 —— 正是要的效果。
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    block.blocks.forEachIndexed { i, inner ->
                        MdBlockView(inner, MaterialTheme.colorScheme.onSurfaceVariant, bodySize)
                        if (i != block.blocks.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        is MdBlock.ListItem -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (block.level * 16).dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                // 有序列表显示真实序号，无序用小圆点
                text = if (block.ordered) "${block.number}. " else "• ",
                style = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = annotate(block.spans),
                style = body,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
        }

        is MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        is MdBlock.Table -> TableView(block, textColor, bodySize)

        is MdBlock.Raw -> Text(
            text = block.text,
            style = body,
            color = textColor,
        )

        // ★★★ 独立数学公式块。
        //
        //   用户报的渲染 bug：模型输出的公式**原样显示**成
        //   `$$c^2 + 4\cdot\tfrac12 ab = (a+b)^2$$`，一屏数学变成一屏反斜杠。
        //
        //   渲染选择：
        //   - **等宽字体** —— 公式里的对齐（矩阵、多行推导）靠空格与换行表达，
        //     比例字体会把列对不齐
        //   - **淡底 + 圆角** —— 与正文区分，一眼看出这是公式而不是段落
        //   - **横向可滚** —— 长公式不能被折行（折了就没法读），
        //     与代码块同一个理由
        //   - 字号跟正文一样（**不缩小**）：公式里全是符号，本来就比中文难认
        is MdBlock.MathBlock -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = block.rendered.ifEmpty { block.tex },
                        fontFamily = FontFamily.Monospace,
                        fontSize = (bodySize ?: 16).sp,
                        lineHeight = ((bodySize ?: 16) * 1.6f).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // ★ 流式中没闭合要说明 —— 否则用户以为公式写到这就没了
                if (!block.closed) {
                    Text(
                        text = "…",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 让引用竖线随内容拉伸的工具不需要了 —— 见上面 Quote 分支的注释。 */

/**
 * 围栏代码块：等宽字体 + 淡底 + 横滚。
 *
 * ★ `closed == false` 时不改变样式 —— 只在右上角标一个"写…"，
 *   否则流式收尾的瞬间整块代码会换外观（闪烁）。
 */
@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // 语言标记（有才显示）
        if (block.language.isNotBlank()) {
            Text(
                text = block.language,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                // 等宽字体的行高要比正文紧一点，否则代码块会异常高
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!block.closed) {
            Text(
                text = "…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 表格。
 *
 * ★ 用固定列宽（按权重均分）+ 横滚，而不是 `IntrinsicSize` 自适应：
 *   自适应在列内容很长时会把表格撑得极宽，且 `IntrinsicSize` 在
 *   `LazyColumn` 的 item 里测量代价高。
 *   横滚能保证「宽表格不会把整条消息撑爆」。
 */
@Composable
private fun TableView(block: MdBlock.Table, textColor: Color, bodySize: Int? = null) {
    val cols = maxOf(
        block.header.size,
        block.aligns.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (cols == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
    ) {
        // ---- 表头 ----
        TableRow(
            cells = List(cols) { idx -> block.header.getOrNull(idx).orEmpty() },
            aligns = block.aligns,
            cols = cols,
            textColor = textColor,
            header = true,
            bodySize = bodySize,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 数据行 ----
        block.rows.forEach { row ->
            TableRow(
                cells = List(cols) { idx -> row.getOrNull(idx).orEmpty() },
                aligns = block.aligns,
                cols = cols,
                textColor = textColor,
                header = false,
                bodySize = bodySize,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MdInline>>,
    aligns: List<MdAlign?>,
    cols: Int,
    textColor: Color,
    header: Boolean,
    bodySize: Int? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEachIndexed { idx, spans ->
            val align = aligns.getOrNull(idx)
            val ta = when (align) {
                MdAlign.Center -> androidx.compose.ui.text.style.TextAlign.Center
                MdAlign.Right -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
            Text(
                text = annotate(spans),
                style = MaterialTheme.typography.bodyMedium,
                // ★ 表格单元格比正文再小一档（默认 13 vs 正文 16），
                //   跟着正文字号联动，否则调大字号后表格会挤成一团。
                fontSize = ((bodySize ?: 16) - 3).coerceAtLeast(10).sp,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) textColor else textColor.copy(alpha = 0.92f),
                textAlign = ta,
                modifier = Modifier
                    .weight(1f / cols)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 把行内元素压成 `AnnotatedString`。
 *
 * ★ 递归实现：粗体里可以有斜体、链接里可以有代码。
 *   所有样式**叠加**在同一段字符区间上，因此不需要嵌套 `Text`。
 */
private fun annotate(spans: List<MdInline>, base: SpanStyle = SpanStyle()): AnnotatedString =
    buildAnnotatedString {
        appendSpans(spans, base)
    }

/**
 * 用给定样式追加一段文本，然后**恢复**到追加前的样式。
 *
 * ★ 为什么不用 `withStyle`（标准库那个）：它是 `AnnotatedString.Builder` 的
 *   **成员**函数，而我们这里是**声明在 builder 外部的扩展函数**
 *   （为了递归调用自己）。在扩展函数体里，receiver 的成员可以调，
 *   但 `withStyle` 的 lambda 参数会改变接收者作用域，Kotlin 解析不到
 *   （实测报 `Unresolved reference 'withStyle'`）。
 *
 *   自己实现只需两行、语义完全一致，也避免了对标准库内部作用域的依赖。
 */
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.styled(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    if (length > start) addStyle(style, start, length)
}

/**
 * 递归把行内元素写进 builder。
 *
 * ★ 必须写成 `AnnotatedString.Builder` 的**成员扩展**并在 `styled {}` 内部调用：
 *   样式作用域要靠我们自己的实现来管（见 [styled]）。
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendSpans(
    spans: List<MdInline>,
    style: SpanStyle,
) {
    spans.forEach { inline ->
        when (inline) {
            is MdInline.Text -> styled(style) { append(inline.text) }

            is MdInline.Code -> styled(
                // 行内代码：等宽 + 淡底。底色用 SpanStyle 的 background，
                // 这样它能跟着文字一起换行（用 Box 包就不能换行）。
                style.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = CodeBg,
                        fontSize = 14.sp,
                    ),
                ),
            ) { append(inline.text) }

            is MdInline.Bold -> appendSpans(
                inline.children,
                style.merge(SpanStyle(fontWeight = FontWeight.Bold)),
            )

            is MdInline.Italic -> appendSpans(
                inline.children,
                style.merge(SpanStyle(fontStyle = FontStyle.Italic)),
            )

            is MdInline.Strike -> appendSpans(
                inline.children,
                style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)),
            )

            // ★ 链接：只加下划线 + 主色，**不做可点击**。
            //   聊天里点击链接会离开 App，而用户往往只是想复制它 ——
            //   长按消息即可选中复制。做成可点击反而容易误触。
            is MdInline.Link -> appendSpans(
                inline.children,
                style.merge(
                    SpanStyle(
                        color = LinkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            )

            // ★★★ 行内数学。
            //
            //   ★ **不递归 children** —— 这里没有 children：公式体里的 `_` `*`
            //     是下标和乘号，不是 Markdown 标记。转好的纯文本直接追加。
            //   ★ 用主色 + 等宽：让人一眼看出"这是个公式"，与普通文字区分开，
            //     但**不加底色** —— 行内公式通常只有几个字符，
            //     加底色会让一句话被切成好几块色斑。
            is MdInline.Math -> styled(
                style.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = MathInlineColor,
                    ),
                ),
            ) { append(inline.rendered.ifEmpty { inline.tex }) }
        }
    }
}

/**
 * 行内代码底色 / 链接色。
 *
 * ★ 这两个不走 `MaterialTheme` 是因为 `SpanStyle` 在非 @Composable 的
 *   `annotate()` 里被构造 —— 拿不到 CompositionLocal。
 *   取值对 Theme 的亮/暗两套都兼容（半透明黑在两种底色上都读得清）。
 */
private val CodeBg = Color(0x1A000000)
private val LinkColor = Color(0xFF1F8A5F)

/**
 * 行内公式的颜色。
 *
 * ★ 与 `LinkColor` 区分开：链接是"能点的"，公式是"读的"，
 *   两者同色会让用户以为公式也能点。
 */
private val MathInlineColor = Color(0xFF0B6E4F)
