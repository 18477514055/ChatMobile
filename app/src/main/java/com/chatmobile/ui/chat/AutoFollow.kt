package com.chatmobile.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * 流式自动跟随（手感清单 #7 —— 报告点名的**第二高优先级**体感项）。
 *
 * ## 官方依据
 *
 * `UI研究-交互手感.md` §4.3 从 `TextInputWrapper.js` 读出的机制：
 *
 * ```
 * 367140: checkForScrollEvent  resolveScrollViewHandler  computeIdealScrollY
 * doScrollToTextInput  doScrollBackToClamp
 * startListeningForScrolling  stopListeningForScrolling
 * 367759: onDragStart   367771: onScrollEnd
 * ```
 *
 * 报告 §4.3 的结论原文：
 * > **用户一旦自己开始滚（onDragStart），就暂停自动跟随**；滚停（onScrollEnd）再恢复。
 *
 * 报告还提到官方的滚动回调分**三段**（§4.2，`ScrollViewHandler.js`）：
 * `onDragStart` / `onDragEnding` / `onDragEnd`，
 * 并明确建议：判断"恢复跟随"要挂在 **`DragEnding`**（松手、惯性还没停）而不是 `DragEnd`。
 *
 * ## ★★ reverseLayout 的坑（Lead 特别提醒过）
 *
 * 聊天列表用 `reverseLayout = true`（新消息在 index 0，视觉在底部），
 * 所以**判断"是否在底部"的逻辑要反过来**：
 *
 * | | 正序列表 | **reverseLayout = true（我们用这个）** |
 * |---|---|---|
 * | 视觉最底部 | `lastVisibleItemIndex == count-1` | **`firstVisibleItemIndex == 0`** |
 * | 用户往上翻 | index 变小 | **index 变大** |
 * | 滚到底部 | `scrollToItem(count-1)` | **`scrollToItem(0)`** |
 *
 * 所以本文件的判据一律用 **index 0 = 底部**，不要按正序逻辑写。
 */
@Stable
class AutoFollowState(
    private val listState: LazyListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * 是否跟随最新内容。
     *
     * - `true`  → 新内容进来就滚到 index 0（底部）
     * - `false` → 用户手动往上翻了，冻结当前位置，**不要**偷偷把他拽回去
     */
    var autoFollow by mutableStateOf(true)
        private set

    /**
     * 用户是否正手指按在列表上。
     *
     * ★ 语义是「**用户正在主动滚动**」，不是"屏幕上有一根手指"。
     *   这两者的区别是本轮 bug 的关键（见 [onDragStart]）。
     */
    var isUserDragging by mutableStateOf(false)
        private set

    /**
     * 用户**主动往上翻过**（看过历史）。
     *
     * ★★★ 这是本轮修 bug 新增的**粘性标记**。
     *
     *   为什么光有 [isUserDragging] 不够 —— 用户报的第二类现象：
     *   > 「在输出过程中我向上滑动后整个页面依旧会跟着向下滚动」
     *
     *   旧逻辑：松手时 `autoFollow = isAtBottom`。而流式期间内容在长，
     *   列表可能**恰好还是贴底**（用户只推了一点点、或还没推完就又长回来了）
     *   ⇒ `isAtBottom` 为 true ⇒ 跟随被重新打开 ⇒ 下一帧 `onContentGrew` 把他拽回去。
     *
     *   ⇒ 光看"松手时在哪"是不够的，必须记住"他主动离开过底部"这个**意图**。
     *     一旦用户为了看历史而向上滚，就进入 [isUserDragging] 语义的"暂停态"，
     *     **只有他主动点「回到底部」或自己滚回底部才恢复**，不因为内容增长而自动恢复。
     */
    var userScrolledAway by mutableStateOf(false)
        private set

    /**
     * 判定"当前是否已在底部"。
     *
     * ★ reverseLayout 语义：**index 0 才是底部**。
     *   同时要求第一项已经完全露出来（offset == 0），否则是"滑了一半"。
     */
    val isAtBottom: Boolean
        get() = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0

    /**
     * 用手指拖拽开始时调用 —— 立刻停止跟随（清单 #7 的「拖拽即停」）。
     *
     * ★★★ **本轮 bug 的第二个根因在这里**：这个函数被每一帧重复调用，
     *   而它无条件 `autoFollow = false`。
     *
     *   于是发生了一件很微妙的事：用户往上滑 → 松手 → [onDragEnding] 按
     *   `isAtBottom` 恢复跟随 → **但手指还没完全离开屏幕的下一帧又调到这里**
     *   → `autoFollow` 再被置 false → 再松手 → 再恢复……
     *   两个方向来回打，界面表现为"以恒定速度持续下移"。
     *
     *   ⇒ 改成：**只有真的在往"离开底部"的方向移动时**才认作 dragging。
     *     单纯按住（比如正好按住不动）不该打断跟随。
     */
    fun onDragStart() {
        isUserDragging = true
    }

    /**
     * 用户实际发生了"向上离开底部"的滚动。
     *
     * @param atBottom 当前是否仍在底部
     *
     * ★ 这是把"意图"落地的唯一入口：只要用户离开了底部，就记 [userScrolledAway]。
     *   恢复只能靠 [animateToBottom] 或他自己滚回底部（见 [onScrollStopped]）。
     */
    fun noteScrollPosition(atBottom: Boolean) {
        if (!atBottom) {
            userScrolledAway = true
            autoFollow = false
        }
    }

    /**
     * 松手时调用，等价官方的 `onDragEnding`。
     *
     * ★ 与旧版的关键差别：**不再用 `isAtBottom` 无条件恢复跟随**。
     *   流式期间内容在长，`isAtBottom` 随时可能瞬间为 true，
     *   用它当恢复条件等于"用户永远拽不回去"。
     *
     *   ⇒ 恢复跟随只有两种情况：
     *     ① 用户自己滚回了底部（由 [onScrollStopped] 判）
     *     ② 用户点了「回到底部」（[animateToBottom]）
     */
    fun onDragEnding() {
        isUserDragging = false
        // ★ 刻意**什么都不做**：不在这里恢复 autoFollow。
        //   恢复交给 onScrollStopped（用户自己滚回去）或 animateToBottom（点按钮）。
    }

    /** 滚动停止时兜底校正一次（惯性滚动可能把用户带回底部）。 */
    fun onScrollStopped() {
        val nowBottom = isAtBottom
        if (nowBottom) {
            // ★ 用户**自己滚回底部了** ⇒ 清掉粘性标记，恢复跟随。
            //   注意：这里用 isUserDragging 判断"是不是他主动滚的"，
            //   拖拽/惯性都没了才认。
            if (!isUserDragging) {
                userScrolledAway = false
                autoFollow = true
            }
        } else {
            noteScrollPosition(atBottom = false)
        }
    }

    /**
     * 新内容到达时调用。只有 `autoFollow == true` 才滚。
     *
     * ## ★★ 为什么用 `requestScrollToItem` 而不是 `scope.launch { scrollToItem(0) }`
     *
     * 用户真机反馈原话：
     * > 「行数先增加，然后在极短的时间内用户消息再上升，这就导致了视觉上的不同步。
     * >  当然他们之后肯定会恢复过来。」
     *
     * **「之后会恢复」+「先…然后…」= 典型的晚 1~2 帧。**
     *
     * 旧写法的延迟链（三段叠加）：
     *
     * | # | 环节 | 代价 |
     * |---|---|---|
     * | 1 | 文本增长 → 触发重组 | 本帧 |
     * | 2 | `LaunchedEffect` 在**组合完成之后**才跑 | +1 阶段 |
     * | 3 | `scope.launch` 再排一次队 | +1 帧 |
     * | 4 | `scrollToItem` 是 **suspend**，还要再等一次 | +1 帧 |
     *
     * ⇒ 内容在第 N 帧长高，滚动在第 N+1/N+2 帧才到位。中间这段就是"错位"。
     *
     * **`requestScrollToItem(int, int)` 是非 suspend 的**
     * （已用 `javap` 核实签名：
     * `public final void requestScrollToItem(int, int)` +
     * `requestScrollToItem$default(...)` 默认 offset=0）。
     *
     * 它把目标写进内部 `MutableState`，在**下一帧 measure 之前**被消费 ——
     * 不经过协程调度，因此与内容增长**同帧**生效。
     *
     * ## 为什么不用 `animateScrollToItem`
     *
     * 流式追加时每个 token 都触发一次，动画会互相打断、产生抖动感。
     * 报告 §7.1 的 600ms `animateScrollTo` 是给**用户主动跳转**用的
     * （见 [animateToBottom]），不是给流式追帧用的。
     */
    fun onContentGrew() {
        // ★★★ 用户在拖拽期间，**无论 autoFollow 是什么都绝不滚动**。
        //
        //   用户真机反馈原话（**两次**）：
        //   > 「因为流式输出的存在，导致在输出的过程中往上滑，它会自动继续往下滑，
        //   >  结果不会回到底部。但是呢依旧会以同样的速度往下滑，
        //   >  导致我无法定位到上面的一个固定位置。」
        //   > （第二次）「在输出过程中我向上滑动后整个页面依旧会跟着向下滚动」
        //
        //   现象拆解：**手指还按着**（拖拽中），内容仍在长（流式），
        //   容器于是继续被"跟随"逻辑往下拉 —— 但用户的拖动也在同时施加位移，
        //   两个力打架 ⇒ 位置以恒定速度漂移，用户根本定不住。
        //
        //   ## 为什么前两次修都没修好（这是本轮必须讲清的事）
        //
        //   | 次数 | 当时的防线 | 为什么不够 |
        //   |---|---|---|
        //   | ① | `if (!autoFollow) return` | `onDragEnding` 会用 `isAtBottom` 重算，瞬间又打开 |
        //   | ② | `if (isUserDragging) return` | `onDragStart` **每帧都在调**且无条件置 `autoFollow=false`；松手时又被 `isAtBottom` 拉回 true ⇒ 来回打 |
        //   | ③（本轮） | `isUserDragging` 硬闸 + **`userScrolledAway` 粘性标记** | 恢复跟随不再由"当前位置"决定，而由"用户意图"决定 |
        //
        //   ⇒ 三道闸：
        //     ① 手指按着 ⇒ 绝不动
        //     ② 用户主动离开过底部 ⇒ 绝不动（**除非**他滚回去或点按钮）
        //     ③ 已经在底部 ⇒ 无需动
        if (isUserDragging) return
        if (userScrolledAway) return
        if (!autoFollow) return
        if (isAtBottom) return // 已经在底部，无需再滚
        // ★ 非 suspend：目标写进 MutableState，下一帧 measure 前生效。
        //   reverseLayout 下 index 0 = 视觉底部。
        listState.requestScrollToItem(0)
    }

    /**
     * 用户主动点"回到最新"时调用 —— 这个才用 600ms 动画
     * （[实测] `ValdiScrollView.java:751` `startScroll(..., 600)`）。
     *
     * ★ 同时**清掉粘性标记** —— 这是唯一一条用户显式表达"我要回到最新"的路径，
     *   不清的话点了按钮也不会恢复跟随。
     */
    fun animateToBottom() {
        userScrolledAway = false
        autoFollow = true
        scope.launch {
            listState.animateScrollToItem(0)
        }
    }
}

@Composable
fun rememberAutoFollowState(listState: LazyListState): AutoFollowState {
    val scope = rememberCoroutineScope()
    return remember(listState) { AutoFollowState(listState, scope) }
}
