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
 * ## ★★★ 第四次报同一个 bug 后换的思路（必读）
 *
 * 前三版都在讨论「**要不要滚**」—— 加 `autoFollow` 判断、加 `isUserDragging`、
 * 加 `userScrolledAway` 粘性标记。用户第四次仍然报「向上滑动还跟滚」。
 *
 * 结论：**问题不在判断条件，在判断的时机与载体。**
 *
 * 用户的真实操作是：
 * ```
 * 流式输出中 → 手指按住往上拖 → 松手 → 惯性继续滚 → 想停在某处看内容
 * ```
 * 而前三版的恢复条件都挂在「位置」上（`isAtBottom`）或「是否还按着」上。
 * 这两个量在**流式输出期间都是不可靠的**：
 * - 内容每帧在长 ⇒ `firstVisibleItemIndex` 会自己变回 0（因为 index 0 = 底部，
 *   而新内容不断插入 index 0 位置）⇒ `isAtBottom` 频繁瞬间为 true
 * - 惯性滚动期间手指已经抬起 ⇒ `isUserDragging` 已经是 false
 *
 * ⇒ 新策略：**只要用户在流式期间碰过这张列表，就彻底停止自动跟随**，
 *   直到他明确表示"我要回到底部"（点按钮 或 自己滚到底部并松手）。
 *   不再试图从位置反推意图 —— 位置在流式期间根本说明不了意图。
 *
 * 代价与取舍（明确写出来，免得以后有人觉得"太保守"又改回去）：
 * - 用户可能只是轻轻碰了一下屏幕（没滚）也被判为"停止跟随"
 *   ⇒ 但他再点一下「回到底部」就恢复了。**这个代价远小于"拽不回去"**。
 * - 流式期间新内容不再自动出现在视野里
 *   ⇒ 那正是用户要的效果：他在看历史时不想被拽走。
 *
 * ## 官方依据
 *
 * `UI研究-交互手感.md` §4.3 从 `TextInputWrapper.js` 读出：
 * `onDragStart`（367759）暂停跟随，`onScrollEnd`（367771）恢复。
 * ★ 官方那套是**网页输入框**的语义（内容不会每秒长几十行）；
 * 聊天流式的场景比它苛刻得多，所以这里的策略比官方更保守。
 *
 * ## ★★ reverseLayout 的坑
 *
 * | | 正序列表 | **reverseLayout = true（我们用这个）** |
 * |---|---|---|
 * | 视觉最底部 | `lastVisibleItemIndex == count-1` | **`firstVisibleItemIndex == 0`** |
 * | 用户往上翻 | index 变小 | **index 变大** |
 *
 * 所以本文件的判据一律用 **index 0 = 底部**。
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
     * - `false` → 冻结当前位置，**不要**偷偷把用户拽回去
     */
    var autoFollow by mutableStateOf(true)
        private set

    /** 用户是否正手指按在列表上。 */
    var isUserDragging by mutableStateOf(false)
        private set

    /**
     * ★★★ **用户碰过这张列表** —— 本轮的真正修复点。
     *
     * 语义比 [userScrolledAway] 更宽松也更可靠：
     * **只要手指在流式期间落下过一次，就置 true**，不看他滚了多少、滚到哪。
     *
     * 为什么这样更可靠：
     * - 不需要读 `firstVisibleItemIndex`（流式期间它自己会变，不可信）
     * - 不需要判断"用户是不是真的在滚"（按住不动也算 —— 他想停住看）
     * - 一旦置 true，**只有** [animateToBottom] 或 [onScrollStopped] 里
     *   「他确实滚到了底部且手指已抬起」才清
     *
     * ★ 与 [userScrolledAway] 同时保留：后者表达"他确实离开过底部"（用于 UI
     *   决定要不要显示「回到底部」按钮），前者表达"他碰过，别再动"（用于硬闸）。
     */
    var userTouchedList by mutableStateOf(false)
        private set

    /** 用户**主动往上翻过**（看过历史）。用于决定是否显示「回到底部」按钮。 */
    var userScrolledAway by mutableStateOf(false)
        private set

    /**
     * 本次"手指按下 → 抬起"之间**是否真的发生过滚动**。
     *
     * ★ 用来把「真的在翻」与「只是碰了一下屏幕」区分开：
     *   前者必须在抬起后继续冻结，后者应当立刻恢复跟随 ——
     *   否则用户随手点一下气泡（想复制文字）就会莫名其妙失去跟随，
     *   那会被当成新 bug 报回来。
     *
     * ★ 判据用 `isScrollInProgress`（**事件**），不用 `firstVisibleItemIndex`（**位置**）——
     *   位置在流式期间不可信，事件不会因为内容变长而自己翻转。
     */
    private var scrolledThisTouch by mutableStateOf(false)

    /** 判定"当前是否已在底部"。reverseLayout：index 0 + offset 0。 */
    val isAtBottom: Boolean
        get() = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0

    /**
     * 手指落到列表上 —— **立即、永久**停止自动跟随（直到用户明确要求恢复）。
     *
     * ★ 这就是本轮的核心改动：不再"判断用户是不是在滚"，
     *   而是"他碰了，就别动了"。**宁可少滚，也不要拽他。**
     */
    fun onTouchDown() {
        isUserDragging = true
        userTouchedList = true
        scrolledThisTouch = false
        autoFollow = false
        android.util.Log.i(TAG, "手指落下 ⇒ 冻结跟随")
    }

    /**
     * 手指抬起。
     *
     * ★ 判据是「**这次按下期间到底有没有真的滚过**」（事件），不是「停在哪」（位置）：
     * - 真的滚过 ⇒ 他要去读历史 ⇒ 保持冻结
     * - 只是点了一下气泡（想复制文字）⇒ 立刻恢复跟随
     *
     * 不加这一条，用户随手点一下消息就会永久失去跟随，
     * 那会被当成一个新 bug 报回来。
     */
    fun onTouchUp() {
        isUserDragging = false
        if (!scrolledThisTouch) {
            // 只是碰了一下，没挪动 ⇒ 撤销这次冻结
            userTouchedList = false
            if (!userScrolledAway) autoFollow = true
            android.util.Log.i(TAG, "手指抬起（未滚动）⇒ 恢复跟随")
        } else {
            android.util.Log.i(TAG, "手指抬起（滚动过）⇒ 保持冻结")
        }
        scrolledThisTouch = false
    }

    /**
     * 观察滚动位置（由 `snapshotFlow` 喂进来）。
     *
     * ★★★ 只在**手指确实按着**的时候才据此判定"用户要离开底部"。
     * 为什么：流式输出时内容每帧变长，`firstVisibleItemIndex` 会在
     * 「内容已长高、滚动还没跟上」的那一帧短暂不为 0 —— 旧写法在这里
     * 就 `userScrolledAway = true; autoFollow = false`，等于**内容自己在把跟随关掉**。
     * 那种误判不会表现为"不跟了"，而会在下一轮把状态搅乱。
     *
     * 手指没按着时的位置变化一律**不解释**：那可能是内容增长、可能是动画残余。
     */
    fun noteScrollPosition(atBottom: Boolean) {
        if (atBottom) {
            // 回到最底部 —— 清掉"离开过"的标记（是否恢复跟随由 [onTouchUp] / [onScrollStopped] 决定）
            userScrolledAway = false
            return
        }
        // ★★ 这里放宽回"只要不是自己滚出来的位移就算数"，但加了一个前提：
        //   `autoFollow` 已经关掉，或者手指正按着。
        //
        //   上一版只在 `isUserDragging` 时才记 —— 那削弱了一道兜底：
        //   万一 `onTouchDown` 因为任何原因没被调用（手势没进来、事件被别的层吃掉），
        //   整条冻结链路就完全失效，而表现恰好就是"滑动之后还跟滚"。
        //   加上 `!autoFollow` 这个条件后：
        //   - 内容增长造成的瞬时抖动：此时 autoFollow 仍是 true、手指也没按 ⇒ **不算**（不误判）
        //   - 用户真的滚了：`onTouchDown` 已把 autoFollow 置 false ⇒ **一定算**（兜底生效）
        if (!isUserDragging && autoFollow) return
        userScrolledAway = true
        scrolledThisTouch = true
        autoFollow = false
    }

    /**
     * 滚动停止时调用。
     *
     * ★★★ **不再按位置自动恢复跟随**（这是第五次报同一个 bug 后的改动）。
     *
     * 为什么删掉"在底部就恢复"：流式期间内容是**连续增长**的，
     * 只要某一帧位置恰好回到 0/0（布局重新测量、动画残余、甚至一次误触的
     * 极小位移），`autoFollow` 就被悄悄打开，下一帧 `onContentGrew` 立刻把用户拽回去。
     * 这条路**用户完全看不见**，却足以让"我明明滑上去了"反复发生。
     *
     * ⇒ 恢复跟随只留**两条用户显式意图**：
     *   ① 发新消息（[onNewUserMessage]）
     *   ② 点「回到底部」（[animateToBottom]）
     *   这两条都是"用户明确说了要看最新"。
     */
    fun onScrollStopped() {
        if (isUserDragging) return // 还在拖，别下结论
        // ★ 刻意什么都不做：位置不参与"要不要恢复跟随"的决策。
        //   保留这个函数是为了让调用点稳定（它是 `snapshotFlow { isScrollInProgress }` 的出口）。
    }

    /**
     * 用户**发出新消息**时调用 —— 这是一次显式的"我要看回答"。
     *
     * ★ 必须存在：否则用户在上一轮读历史时冻住了跟随，
     *   下一轮回答了却永远不跟，看起来就像"发送后不自动滚到新消息"。
     *   **每一条新消息都是重新表达一次意图**，不需要用户去点「回到底部」。
     */
    fun onNewUserMessage() {
        userTouchedList = false
        userScrolledAway = false
        scrolledThisTouch = false
        autoFollow = true
    }

    /**
     * 新内容到达时调用。
     *
     * ## ★★★ 第五次之后，用户直接给了判据 —— 照做，不再自作聪明
     *
     * 用户原话：
     * > 「滚动问题还在，反正我的想法是能不能这样，**除非在最底部，否则绝对不随之滚动**」
     *
     * 这句话把判据压到**一条**：`isAtBottom`。
     *
     * ## 为什么前面四版都绕远了（值得记下来，免得再绕）
     *
     * | 版本 | 我加的判据 | 用户看到的 |
     * |---|---|---|
     * | ① | `!autoFollow` | 还跟滚 |
     * | ② | `isUserDragging` | 还跟滚 |
     * | ③ | `userScrolledAway` 粘性 | 还跟滚 |
     * | ④ | `userTouchedList`（碰过就停） | 还跟滚（且轻点一下就永久不跟） |
     * | **⑤** | **`isAtBottom`：不在底部就**绝不**滚** | —— |
     *
     * 我一直在加"额外的粘性标记"，而用户要的是**最朴素的那条**：
     * 只要现在不在底部，就别动。
     *
     * 之前我不敢只用 `isAtBottom`，理由是"流式期间它会自己变回 true"。
     * 那个担心**只在"我主动滚到底"时成立** —— 而现在改成：
     * 不主动滚，只检查。检查时的 `isAtBottom` 反映的是**用户此刻的真实位置**，
     * 这恰恰就是用户要的语义。
     *
     * ## 明确的代价（写出来，免得以后又有人"优化"）
     *
     * 用户往上翻、但尚未离开到底部那一小段（offset 1~几 px）时，仍会被跟随。
     * 这是 `isAtBottom` 的定义决定的，也正是用户说的"在最底部"。
     * 一旦离开底部（哪怕只差一行），就再也不动 —— 直到他自己回去或点按钮。
     */
    fun onContentGrew() {
        // ★ 唯一判据：在底部才滚，不在底部绝对不滚。
        if (!isAtBottom) return
        // 手指按着时也不滚（他自己在拖，插一脚只会打架）
        if (isUserDragging) return
        listState.requestScrollToItem(0)
        // ★ 诊断：这行的次数 = 流式期间真的滚了几次。
        //   用户再报"还跟滚"时先看它：还在刷 ⇒ 另有滚动源；没刷 ⇒ 动的是位移动画。
        android.util.Log.i(TAG, "自动滚到底（在底部 + 内容增长）")
    }

    private companion object {
        const val TAG = "AutoFollow"
    }

    /**
     * 用户主动点"回到最新"时调用 —— **唯一**一条会无条件恢复跟随的路径。
     *
     * ★ 三个标记一起清：这是用户显式表达"我要回到最新"。
     */
    fun animateToBottom() {
        userTouchedList = false
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
