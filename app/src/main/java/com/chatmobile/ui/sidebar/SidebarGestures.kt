package com.chatmobile.ui.sidebar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * 侧边栏跟手手势。
 *
 * ★ P0 的核心，严格照 `UI研究-动效与转场.md` §1.2 / §4.2 / §7.3 的机制实现。
 *
 * ## 为什么不用现成的 `draggable` / `detectHorizontalDragGestures`
 *
 *  1. **方向判定**（`abs(deltaX) > abs(deltaY)` 才接管）是必需的。
 *     报告 §1.2 第 3 条点名的 `acceptHorizontalDrag` 就是这个。
 *     `draggable(orientation = Horizontal)` 直接丢弃纵向信息，做不了这个判断。
 *  2. 需要在**接管的瞬间**快照 `dragStartProgress` 并重设锚点，
 *     否则 touchSlop 那一段位移会被算进去，面板会在接管时"跳"一下。
 *  3. 需要松手速度。`detectHorizontalDragGestures` 的 `onDragEnd` 不给速度。
 *
 * ## ★★★ 三个参数**必须是稳定值**，否则手势会被 Compose 当场掐断
 *
 * `pointerInput(vararg keys)` 的 key 一变，Compose 就 `resetPointerInputHandler()`，
 * **正在拖的那只手会被销毁重建**。表现就是"卡在中间疯狂颤动"。
 *
 * 所以：
 *  - `panelWidthPx` 传 **`Int`**，不是 `() -> Int`
 *    （lambda 每次重组都是新实例 ⇒ key 变化 ⇒ 手势中断）
 *  - **不传** `enabled` / `allowStart` 这类 lambda 参数
 *  - 方向门禁改在**手势内部**读 `state.progress` 判断（不在 Modifier 上做）
 *
 * ★ 这条坑极隐蔽：代码里看不到任何 suspend / cancel 的痕迹，
 *   但症状非常具体 —— 「要么死都划不出来，要么卡住狂颤，要么直接跳开」。
 *
 * ## ★★ 调用方也不要按状态增删这个 Modifier
 *
 * 曾经写过 `if (isOpen) Modifier else Modifier.sidebarDrag(...)`：
 * 手指一动 progress 就从 0 变成 >0.01 ⇒ isOpen 翻转 ⇒ 修饰符增删 ⇒ 循环。
 * **必须无条件挂载**，是否响应交给手势内部判断。
 *
 * ## ★ 关于 restricted suspension
 *
 * `awaitEachGesture { }` 的 lambda 是 `AwaitPointerEventScope` —— **受限挂起作用域**，
 * 只能调用它自己的挂起函数（`awaitPointerEvent` / `awaitFirstDown` …），
 * **不能**调用外部任意 suspend 函数（`Animatable.animateTo`、`delay` 等）。
 *
 * 所以本文件里拖动路径**一个 suspend 调用都没有**，全是普通函数调用：
 *
 *   - `state.dragTo(...)`      → 普通函数，直接改 snapshot state（1:1 直给）
 *   - `state.settle(...)`      → 普通函数，内部自己 launch 弹簧协程
 *
 * 这不是妥协 —— 报告 §7.4 原则 3「跟手期间绝不插值」要求的正是这个形态：
 * 拖动期 `setState`（无动画），松手才 `setStateAnimated`（弹簧）。限制与正确写法恰好一致。
 *
 * @param panelWidthPx 面板宽度（**整数像素**）。传值不传 lambda，见上方警告。
 */
fun Modifier.sidebarDrag(
    state: SidebarState,
    panelWidthPx: Int,
): Modifier = pointerInput(state, panelWidthPx) {
    val slop = viewConfiguration.touchSlop
    val width = panelWidthPx.toFloat()

    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Main,
        )

        // ★★★ 起手瞬间快照「这一下是往哪边拖」。
        //
        //   判据是**当前进度**，不是"上一次打开/关闭"：
        //     · progress 靠 0  ⇒ 抽屉基本是关的 ⇒ 只有向右 = 打开
        //     · progress 靠 1  ⇒ 抽屉基本是开的 ⇒ 只有向左 = 关闭
        //     · 停在中间（用户上次拖一半弹回途中又抓回来）⇒ 两个方向都收
        //
        //   ★ 读 `state.progress` 是**安全**的：它不在 pointerInput 的 key 里，
        //     所以读它不会重建手势处理器。这个区别是整份文件的关键。
        val startProgress = state.progress
        val wantsOpen: Boolean? = when {
            startProgress < 0.02f -> true     // 关着 → 只能往开拖
            startProgress > 0.98f -> false    // 开着 → 只能往关拖
            else -> null                      // 中间态 → 两边都收
        }

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        // null = 尚未判定方向；false = 已判定为纵向，本手势不再接管
        var horizontal: Boolean? = null
        var dragStarted = false
        var anchorX = down.position.x

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            // 手指抬起 / 被上层取消
            if (!change.pressed) break

            tracker.addPosition(change.uptimeMillis, change.position)

            if (horizontal == null) {
                val totalDx = change.position.x - down.position.x
                val totalDy = change.position.y - down.position.y

                // 还没动过 touchSlop，继续观察
                if (abs(totalDx) <= slop && abs(totalDy) <= slop) continue

                // ★ 方向判定：横向占优才接管；纵向让给列表滚动
                horizontal = abs(totalDx) > abs(totalDy)
                if (horizontal == false) {
                    // 纵向手势 —— 一个事件都不 consume，列表照常滚
                    return@awaitEachGesture
                }

                // ★ 方向门禁：关着时往左拖 / 开着时往右拖 = 不是这个手势
                if (wantsOpen != null && (totalDx > 0f) != wantsOpen) {
                    return@awaitEachGesture
                }
            }

            if (horizontal == true) {
                if (!dragStarted) {
                    // ★ 快照起始进度（报告 §1.2 第 2 条）—— 只快照一次。
                    //   之后 progress = dragStartProgress + 位移/宽度，
                    //   所以多次拖拽无缝续接，而不是每帧累加导致漂移。
                    state.beginDrag()
                    // 锚点取「接管这一刻」的手指位置，不是 down 的位置 ——
                    // 否则 touchSlop 那段位移会被重复计入，面板会在接管瞬间跳一下。
                    anchorX = change.position.x
                    dragStarted = true
                }

                // 消耗掉，避免同时触发下层手势
                change.consume()

                if (width > 0f) {
                    // ★ 进度归一化：progress = dragStartProgress + deltaX / panelWidthPx
                    // ★ dragTo 是非 suspend 的直接赋值 → 拖动期零插值
                    state.dragTo(state.dragStartProgress + (change.position.x - anchorX) / width)
                }
            }
        }

        // ---------------- 松手 ----------------
        if (!dragStarted) return@awaitEachGesture

        val velocityX = tracker.calculateVelocity().x
        val cfg = buildSettleConfig(width, this@pointerInput)
        // ★ 弹簧收敛，速度参与三段式判据（内部 launch，不在此作用域挂起）
        state.settle(velocityX, cfg)
    }
}
