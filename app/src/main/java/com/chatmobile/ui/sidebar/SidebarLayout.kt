package com.chatmobile.ui.sidebar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.round

/**
 * 自定义侧边栏抽屉。
 *
 * ★ 为什么不用 `ModalNavigationDrawer`：报告 §0 / §1.1 实测 —— ChatGPT 全 dex 里
 *   `ModalNavigationDrawer` 命中 **0**，官方抽屉是自研的 `SidebarLayout`。
 *   Compose 的 `ModalNavigationDrawer` 走锚点 + `AnchoredDraggable`，跟手是锚点插值
 *   而非「1:1 归一化进度」，松手判据也是 Material 的固定策略，做不出报告 §7.3 的三段式。
 *
 * ## 对应报告 §1.2 的八条机制
 *
 * 1. **进度归一化**：位置 = `progress ∈ [0,1]`，不是像素位移。
 * 2. **整数像素对齐**：`translationX = round(progress * panelWidthPx) - panelWidthPx`。
 *    面板宽度本身也取整，保证差值一定是整数 —— 文字不落在亚像素位置发虚。
 * 3. **起手快照** `dragStartProgress`（在 `SidebarGestures.kt`）。
 * 4. **方向判定** `abs(dx) > abs(dy)` 才接管（同上）。
 * 5. **松手弹簧 + 读速度**：`SidebarState.settle()`。
 * 6. **跟手不插值**：拖动直接赋值，松手才弹簧。
 * 7. **遮罩不画底色**，只留「点空白关闭」命中区（见下方注释）。
 * 8. **面板 overlay 浮在内容之上，内容不缩放**（官方没有 parallax）。
 *
 * 无障碍 id 沿用官方命名：`sidebar-edge` / `sidebar-backdrop` / `sidebar-panel`。
 */
@Composable
fun SidebarLayout(
    state: SidebarState,
    panel: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // ★ derivedStateOf 的意义：progress 每帧都变，但派生的布尔只在跨过阈值时变，
    //   所以组合阶段读 isOpen 不会引起每帧重组。
    val isOpen by remember { derivedStateOf { state.progress > 0.01f } }

    // ★ 抽屉开始打开时主动收键盘。
    //   放在这一层而不是调用方，是因为抽屉有**两条打开路径**：
    //     ① 点菜单键 → 调用方 onOpenSidebar()
    //     ② 从左边缘往右拖 → 手势直接改 progress，根本不经过调用方
    //   只有放在这里，②这条路径才能覆盖到。
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(isOpen) {
        if (isOpen) keyboard?.hide()
    }

    // 系统返回键：只在抽屉开着时才拦截
    BackHandler(enabled = isOpen) { state.close() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current

        // 面板宽度取整到整数像素 —— 这是 round() 像素对齐的前提
        val panelWidthPx: Int = remember(maxWidth, density) {
            round(with(density) { (maxWidth * SidebarMotion.PanelWidthFraction).toPx() }).toInt()
        }
        val panelWidthDp = with(density) { panelWidthPx.toDp() }

        // ---- 1. 内容：铺满全屏、不缩放；**整屏都能右滑呼出** ----
        //
        // ★★★ 为什么拖拽挂在**整个内容**上，而不是左边缘一条窄带
        //
        //   用户原话：「在主界面向右滑的时候不能划开侧边栏」。
        //   原来只在**左边缘 20dp** 一条竖带上挂了手势，
        //   而人的手不可能每次都正好从屏幕最左边起手 ⇒ 大部分右滑都落在
        //   内容区、根本进不了那条带子。
        //
        //   对照 `06-DSH-mobile` 的 `installEdgeSwipe()`（`lib/client.js:2003`）：
        //     var inBand = (x >= 20 && x <= w - 20);
        //   它是**除了屏幕左右各 20px 之外，任何位置都算**。这里采用同一判据。
        //
        // ★★★ 这个 Modifier **必须无条件挂载**。
        //
        //   曾经写成 `if (isOpen) Modifier else Modifier.sidebarDrag(...)`，
        //   结果：手指一动 progress 从 0 变 >0.01 ⇒ isOpen 翻转 ⇒ 修饰符被摘掉
        //   ⇒ 手势处理器销毁 ⇒ 手指还在动 ⇒ 又挂回去 … 循环。
        //   用户看到的就是「卡在中间疯狂颤动 / 要么死都划不出来 / 要么直接跳开」。
        //   ⇒ 永远挂载，让手势内部按 `state.progress` 自己判断方向。
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .sidebarDrag(state = state, panelWidthPx = panelWidthPx),
            ) {
                content()
            }
        }

        // ---- 2. 遮罩：**不画任何底色**，只保留"点空白处关闭"的命中区 ----
        //
        // ★★★ 这里曾经有一个真 bug，记下来别再犯。
        //
        //   旧代码写的是：
        //       .background(scrimColor)
        //       .graphicsLayer { alpha = state.progress * ScrimMaxAlpha }
        //
        //   **Modifier 顺序决定了图层范围**：`graphicsLayer` 创建的是一个**只包裹
        //   它之后绘制内容**的离屏图层，而 `.background()` 在它**之前**，属于外层，
        //   压根不进那个图层 ⇒ alpha 从来没作用到遮罩上。
        //   结果就是不管 `ScrimMaxAlpha` 写 0.42 还是 0.12，用户看到的都是
        //   **100% 不透明的纯黑**。
        //
        //   用户要求：「那个遮障要不干脆你别交了，直接什么都别动就好了」。
        //   ⇒ 那就真的什么都不画。保留这个节点只为两件事：
        //     ① 点空白处收起抽屉；② 无障碍 id `sidebar-backdrop` 还在。
        if (isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "sidebar-backdrop" }
                    .pointerInput(Unit) { awaitScrimTap { state.close() } },
            )
        }

        // ---- 3. 面板：overlay 浮在内容上，靠 translationX 移入移出 ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(panelWidthDp)
                .fillMaxHeight()
                .semantics { contentDescription = "sidebar-panel" }
                .graphicsLayer {
                    // ★★ 整数像素对齐 —— 报告 §1.2 第 1 条点名的 round
                    translationX =
                        (round(state.progress * panelWidthPx).toInt() - panelWidthPx).toFloat()
                }
                // ★ 面板自身也挂手势（收起方向）—— 同样**无条件挂载**，
                //   由手势内部按 progress 判断方向。面板完全在屏幕外时
                //   progress=0，`wantsOpen=true` 只接受右滑，正好等价于"不响应"。
                .sidebarDrag(state = state, panelWidthPx = panelWidthPx),
        ) {
            // 面板内容声明成 ColumnScope 接收者，这样侧边栏内部能用 weight() 把
            // 会话列表撑开、把底部按钮压到底 —— 所以这里必须真的套一个 Column。
            Column(modifier = Modifier.fillMaxSize()) {
                panel()
            }
        }

        // ---- 4. 左边缘系统手势豁免 ----
        //
        //   Android 10+ 手势导航会把**屏幕最左边缘往右滑**吃成系统「返回」，
        //   事件根本到不了我们这里；而 Chat 层的 BackHandler 是关着的 ⇒
        //   这一下会**直接退出 App**（用户抱怨过"经常直接退回桌面"）。
        //
        //   宽度取 20.dp，**不是拍脑袋**：`06-DSH-mobile` 的 `installEdgeSwipe()`
        //   （`_plugin-src/lib/client.js:2003`）用的就是
        //       var inBand = (x >= 20 && x <= w - 20);
        //   即"左右各留 20px 不动，中间全算"。这里沿用同一个边界。
        //
        //   ★ 这个热区**自己不再挂手势**（上一版两边都挂 = 两个处理器抢同一串事件，
        //     也会造成抖动）。它只负责向系统声明"这块别吃我的滑动"，
        //     事件照常往下落到内容层那个唯一的处理器。
        if (!isOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SidebarMotion.EdgeWidth)
                    .fillMaxHeight()
                    .systemGestureExclusion()
                    .semantics { contentDescription = "sidebar-edge" },
            )
        }
    }
}

/**
 * 遮罩上「按下即吞事件、抬手算点击」。
 *
 * 不用 `Modifier.clickable` 的原因：clickable 会带 ripple，遮罩上出现水波很怪。
 * 这里只关心"吞掉 + 抬手关闭"。
 *
 * 整个函数体里**没有任何 suspend 调用** → 满足 `AwaitPointerEventScope` 的限制。
 */
private suspend fun PointerInputScope.awaitScrimTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        down.consume()
        var releasedInside = true
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            // 手指滑出遮罩左上边界就当作"不是点击"
            if (change.position.x < 0f || change.position.y < 0f) {
                releasedInside = false
            }
        }
        if (releasedInside) onTap()
    }
}
