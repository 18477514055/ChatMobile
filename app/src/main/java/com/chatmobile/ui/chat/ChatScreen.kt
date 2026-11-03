package com.chatmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 聊天主界面。
 *
 * ## ★ reverseLayout 的关键决策（Lead 特别提醒过）
 *
 * 列表用 `reverseLayout = true`：新消息在 index 0，视觉在底部。
 * 好处是新内容追加时**不需要滚动**就能自然出现在底部，这也是官方形态。
 *
 * 但**所有"是否在底部"的判据都要反过来**：
 * ```
 * 视觉最底部   = firstVisibleItemIndex == 0
 * 用户往上翻   = index 变大
 * 滚回最新     = scrollToItem(0)
 * ```
 * 见 `AutoFollow.kt` 的 `isAtBottom`。
 *
 * ## 拖拽即停 / 滚停恢复（清单 #7）
 *
 * 这里用 `pointerInput` 在 **`PointerEventPass.Initial`** 监听 —— Initial 阶段
 * 早于列表自己的滚动处理，所以能在用户手指落下的瞬间就置 `autoFollow = false`，
 * 而不是等列表开始滚才反应。这对应官方 `onDragStart`（`TextInputWrapper.js:367759`）。
 *
 * 恢复挂在"滚动停止且回到底部"上，对应官方的 `onScrollEnd`（`367771`），
 * 并按报告 §4.2 的建议挂在 `DragEnding` 语义上（松手即判，不等惯性停）。
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "ChatMobile",
    /** 当前模型名（显示在顶栏右侧，点击切模型）。空则不显示该按钮。 */
    currentModelName: String = "",
    /** 点顶栏模型名 → 打开居中快速切换弹窗 */
    onOpenModelSwitch: () -> Unit = {},
    /** 思考过程的展开程度（全局档位，来自设置）。 */
    reasoningDisplay: ReasoningDisplay = ReasoningDisplay.DEFAULT,
    /** 回答结束后是否自动收起思考过程（来自设置）。 */
    reasoningAutoCollapse: Boolean = true,
    /** 正文与列表的正文字号（sp，来自设置）。 */
    bodyFontSize: Int = 16,
) {
    val listState = rememberLazyListState()
    val autoFollow = rememberAutoFollowState(listState)
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current

    // ★ 已播过入场动画的消息 id。
    //   放在这里而不是 MessageBubble 内部，是因为 LazyColumn 会回收滚出屏幕的
    //   item 组合实例 —— 状态存在 item 里就会随回收丢失，滚回来时重播动画。
    //   这个集合的生命周期跟 ChatScreen 走，跨 item 存活。
    //
    //   用普通 HashSet 而非 snapshot 集合是有意的：
    //   它只在 MessageBubble 的 `remember(message.id) { }` 初始化块里被读一次，
    //   不需要参与重组。用 snapshot 集合反而会在 add 时触发无谓重组。
    val hasAnimated = remember { HashSet<String>() }

    // ★★★ 每条消息的「思考过程手动覆盖」状态：`true` 展开 / `false` 收起 / 缺席 = 没点过。
    //
    //   为什么存在这里而不是 `MessageBubble` 内部（这是「收不回去」的真因之一）：
    //
    //   ```
    //   消息滚出屏幕 → LazyColumn 回收 item 组合实例 → 气泡里的 remember 丢失
    //   → manualOverride 回到 null → 又按全局档位（展开）渲染
    //   ```
    //
    //   于是"单条点收起"当时确实生效（真机截图验过），但一往上翻、
    //   或者新消息把它顶出屏幕，再回来它就自己弹开了。
    //
    //   ⇒ 状态必须存在**生命周期长于 item** 的地方。这里用普通 `mutableStateMapOf`
    //     按 message.id 索引 —— 与本文件上面 `hasAnimated` 是同一个思路。
    //
    //   ★ 用 `mutableStateMapOf` 而不是 `HashMap`：这个 Map 要被读进布局，
    //     必须参与重组；`hasAnimated` 那个只在 remember 初始化块里读一次，所以能省。
    val reasoningOverrides = remember { mutableStateMapOf<String, Boolean>() }

    // ★★ 流式跟随：在【合成期】就发出滚动请求，不等组合提交完成。
    //
    // 为什么是 SideEffect 而不是 LaunchedEffect —— 这是最后一环延迟：
    //
    //   | # | 环节 | LaunchedEffect | SideEffect |
    //   |---|---|---|---|
    //   | 1 | 文本增长 → 触发重组 | 本帧 | 本帧 |
    //   | 2 | 滚动请求发出时机 | **组合提交完成后** | **重组后、measure 前** |
    //   | 3 | 协程调度 | 要 | **不要** |
    //   | 4 | scrollToItem 是否 suspend | — | 已改 requestScrollToItem（非 suspend） |
    //
    // SideEffect 在每次**成功重组之后、measure 之前**同步执行，
    // 流式时每帧都在重组，所以它能把"请求滚动"提前一个阶段，
    // 与内容增长落在同一帧 —— 这正是消除"文字先长高、旧消息后上移"的关键。
    //
    // 安全性：`requestScrollToItem` 只写内部 MutableState，**幂等无副作用**，
    // 重复调用安全；且 `onContentGrew()` 内部有两个短路
    // （`!autoFollow` / `isAtBottom`），不会狂滚。
    SideEffect {
        if (isGenerating) autoFollow.onContentGrew()
    }

    // ★ 滚动位置变化 → 记录"用户是否主动离开了底部"（粘性）+
    //   滚停后兜底校正。对应官方 onScrollEnd。
    //
    // ★★★ 为什么必须盯**位置**而不只是盯 `isScrollInProgress`：
    //   用户报的 bug 是「向上滑之后页面依旧跟着向下滚」。
    //   只在滚停时判一次是不够的 —— 流式期间内容持续变长，
    //   `isScrollInProgress` 会在中途反复翻转；而"用户已经离开底部"这个**意图**
    //   必须立刻记住、并且**不因为内容增长而自动撤销**。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            // reverseLayout：index 0 + offset 0 = 底部
            val atBottom = idx == 0 && offset == 0
            autoFollow.noteScrollPosition(atBottom)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) autoFollow.onScrollStopped()
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 顶部栏 ----
        ChatTopBar(
            title = title,
            modelName = currentModelName,
            onOpenModelSwitch = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // 弹窗出现前收键盘：否则弹窗会被键盘顶上去
                keyboard?.hide()
                onOpenModelSwitch()
            },
            onOpenSidebar = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // ★ 打开侧边栏前先收键盘。
                //   原因：侧边栏是 overlay 浮在内容之上，但如果键盘还开着，
                //   面板底部会被 imePadding 顶起来，而且遮罩下方露出键盘很怪。
                //   官方抽屉打开时不会保留输入焦点在键盘上。
                keyboard?.hide()
                onOpenSidebar()
            },
        )

        // ---- 消息列表 ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                // ★★ index 0 = 视觉底部（新消息在 0）
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 拖拽即停：Initial 阶段最早拿到手指落下事件
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val down = event.changes.any { it.pressed }
                                if (down) {
                                    // 手指一落下就停止跟随 —— 不确定用户是要滚还是要看
                                    autoFollow.onDragStart()
                                }
                                // 全部抬起 = 松手（官方 onDragEnding 语义）
                                val anyStillPressed = event.changes.any { it.pressed }
                                if (!anyStillPressed && event.changes.isNotEmpty()) {
                                    autoFollow.onDragEnding()
                                }
                            }
                        }
                    },
            ) {
                // ★★ reverseLayout 只改变【绘制方向】（从底往上排），
                //    **不会反转数据顺序**。所以必须自己喂倒序数据：
                //      messages  = [msg1(最早), msg2, msg3(最新)]  ← 时间正序
                //      asReversed = [msg3, msg2, msg1]
                //    reverseLayout 从底部开始画 → msg3 落在 index 0 = 视觉最底部 ✓
                //
                //    ⚠️ 如果这里直接喂正序 messages，最早的消息会跑到最底部（曾出过这个 bug）。
                //    这样 index 0 = 最新 = 视觉底部，AutoFollow 的 isAtBottom 判断才能成立。
                items(items = messages.asReversed(), key = { it.id }) { message ->
                    // ★ 该消息是否已播过入场动画。
                    //   `remember(message.id)` 保证：同一 id 在本次组合存活期内只读一次集合，
                    //   不会被 onAppear 的 add 触发重组（也就不会中途把动画掐掉）。
                    val shouldAnimate = remember(message.id) { !hasAnimated.contains(message.id) }

                    MessageBubble(
                        message = message,
                        reasoningDisplay = reasoningDisplay,
                        reasoningAutoCollapse = reasoningAutoCollapse,
                        reasoningOverride = reasoningOverrides[message.id],
                        onToggleReasoning = { expand -> reasoningOverrides[message.id] = expand },
                        bodyFontSize = bodyFontSize,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ChatMotion.layoutEnterSpec(),
                            fadeOutSpec = ChatMotion.layoutExitSpec(),
                            // ★★★ 流式期间必须传 **null**，不是 tween(0)。
                            //
                            //   这是一个"看起来等价、实际走不同代码分支"的坑，
                            //   已用 javap 读 LazyLayoutItemAnimation 字节码确证：
                            //
                            //     public final void animatePlacementDelta-ar5cAso(long, boolean)
                            //       0: getfield  placementSpec
                            //       5: ifnonnull 10        ← 非空才继续
                            //       9: return              ← ★ null 直接返回，零开销
                            //      30: setPlacementAnimationInProgress(true)
                            //      62: invokestatic BuildersKt.launch$default  ← ★ 非空就 launch 协程
                            //
                            //   ⇒ tween(0) 不是 snap，它是「用一个协程去做 0 毫秒动画」。
                            //     协程调度本身至少一帧 ⇒ 旧消息的位移永远比内容增长晚 1 帧。
                            //     这就是用户看到的"行数先增加，然后在极短时间内用户消息再上升"。
                            //
                            //   ⇒ 之前试过的两刀（requestScrollToItem / SideEffect）修的都是**滚动**，
                            //     而延迟在**item 位移动画**里，够不着。这一刀才打到点上。
                            //
                            //   非流式仍用 220ms 的 [实测] 值 —— 历史消息插入/重排要柔和的过渡。
                            placementSpec = if (isGenerating) {
                                null
                            } else {
                                ChatMotion.layoutMoveSpec()
                            },
                        ),
                        animateEnter = shouldAnimate,
                        onAppear = { hasAnimated.add(message.id) },
                    )
                }
            }

            // ---- 回到底部悬浮键（用户翻上去时出现）----
            if (!autoFollow.autoFollow) {
                GoToBottomButton(
                    onClick = { autoFollow.animateToBottom() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }

        // ---- 输入区 ----
        ChatInputBar(
            value = input,
            onValueChange = onInputChange,
            isGenerating = isGenerating,
            onSend = onSend,
            onStop = onStop,
            modifier = Modifier
                // ★ 用 ime.union(navigationBars)，不是单加 navigationBars 也不是两个都加。
                //
                //   原因（`enableEdgeToEdge()` 之后这个坑必然出现）：
                //   - 只用 `imePadding()`：键盘收起时手势导航条会压住输入框下沿
                //   - 只用 `navigationBarsPadding()`：键盘弹起时手势条被 IME 顶掉，
                //     但 padding 仍按 nav bar 高度算 ⇒ **键盘上方多留一条空白**
                //   - 两个都加：同样的双重计算，空白叠加
                //
                //   `union` 取两者中**较大**的那个 insets：
                //   键盘在 = 用 IME 高度；键盘不在 = 用导航条高度。
                //   这正是"任何时刻都恰好避开底部遮挡"的语义。
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars),
                )
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    onOpenSidebar: () -> Unit,
    /** 当前模型名。空则不显示模型切换按钮。 */
    modelName: String = "",
    /** 点模型名 → 弹出居中快速切换弹窗 */
    onOpenModelSwitch: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .pressAndTap(hapticOnPress = true, onClick = onOpenSidebar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "打开侧边栏",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        // ★ 右侧：模型切换按钮。
        //
        //   放在顶栏而不是侧边栏（用户明确纠正过：「你别在侧边栏加呀，
        //   完全不方便啊，就在主界面加」）—— 换模型是**高频动作**，
        //   每次都要先滑出侧边栏就是多两步。
        //
        //   显示当前模型名，本身就是状态提示（不用点开才知道在用什么）。
        if (modelName.isNotBlank()) {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pressAndTap(hapticOnPress = true, onClick = onOpenModelSwitch)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = "切换模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            // 没有可用模型时保持右侧等宽，让标题视觉居中
            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * 「回到最新」按钮。
 *
 * 官方 Android 侧没有确认存在 scroll-to-bottom FAB（报告 §8「未查到」），
 * 但清单 #7 要求"滚停恢复"，没有这个入口用户就只能手动滑回去。
 * 所以这里做，但保持低对比、小尺寸，不喧宾夺主。
 */
@Composable
private fun GoToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pressAndTap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "回到最新",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
