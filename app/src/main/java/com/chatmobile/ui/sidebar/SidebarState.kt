package com.chatmobile.ui.sidebar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 抽屉进度状态。
 *
 * ★ 核心设计（照搬 `UI研究-动效与转场.md` §1.2 + §4.2 逆向出来的机制）：
 *
 *   1. 位置用 **`progress ∈ [0,1]` 归一化**表达，不是像素位移。
 *   2. **拖动期直接改 snapshot state（非 suspend，1:1 直给，绝不插值）**，
 *      松手才起一个协程跑弹簧。
 *   3. `dragStartProgress` 在**起手那一刻快照**，之后
 *      `progress = dragStartProgress + 本次位移/宽度`，所以多次拖拽无缝续接。
 *
 * ## 为什么 progress 用 `mutableFloatStateOf` 而不是 `Animatable`
 *
 * `awaitEachGesture` 的 lambda 是 **`AwaitPointerEventScope`（restricted suspending scope）**，
 * 里面只能调它自己的挂起函数，**不能**调外部任意 suspend 函数 —— 所以
 * `Animatable.snapTo` / `animateTo` 在拖动回调里根本编译不过。
 *
 * 而这个限制恰好把我们推到了**正确**的写法上（报告 §7.4 原则 3）：
 *
 *   | 阶段 | Valdi 官方 | Compose 等价 |
 *   |---|---|---|
 *   | 拖动中 | `setState` | 直接赋值 `mutableFloatStateOf`（无协程、无插值） |
 *   | 松手后 | `setStateAnimated` | `Animatable.animateTo(spring)` 逐帧回写 |
 *
 * 拖动路径上一个 suspend 调用都没有，因此天然 1:1 跟手、零拖影。
 *
 * @param initiallyOpen 初始是否打开
 * @param scope 松手收敛用的协程作用域。由 `rememberSidebarState()` 绑定到组合生命周期。
 */
class SidebarState(
    initiallyOpen: Boolean = false,
    private val scope: CoroutineScope,
) {

    private val _progress = mutableFloatStateOf(if (initiallyOpen) 1f else 0f)

    /**
     * 当前进度 0f..1f。0 = 完全关闭，1 = 完全打开。
     *
     * ⚠️ 这个值是 snapshot state：**只在 `graphicsLayer {}` 里读**，
     *    不要在组合阶段直接读，否则每帧都会触发重组。详见 `SidebarLayout`。
     */
    val progress: Float
        get() = _progress.floatValue

    /**
     * 本次拖拽起手时的进度快照。
     * 报告 §1.2 第 2 条点名了这个字段 —— 它的存在就是为了「多次拖拽无缝续接」。
     */
    var dragStartProgress: Float = 0f
        private set

    /** 正在跑的收敛动画。拖动一旦开始就要停掉它，否则会跟手指打架。 */
    private var settleJob: Job? = null

    /** 起手：停掉在跑的动画 + 快照当前进度。必须在确认接管横向手势之后调用。 */
    fun beginDrag() {
        settleJob?.cancel()
        settleJob = null
        dragStartProgress = _progress.floatValue
    }

    /**
     * 拖动中：直接落值。
     *
     * **非 suspend** —— 这就是报告说的 `setState`，1:1 直给，天然零插值。
     */
    fun dragTo(progress: Float) {
        _progress.floatValue = progress.coerceIn(0f, 1f)
    }

    /**
     * 松手收敛。**非 suspend**：内部 `launch` 一个弹簧，逐帧回写 progress。
     *
     * 判据是**三段式**，照搬报告 §7.3 伪代码的结构（那五个变量名来自
     * `DragToDismissController` 的实测符号表）：
     *
     *   ① 位移 < slop                                    → 回原位（不判方向，防手抖）
     *   ② 位移 > distanceThreshold * width               → 拖够了，朝拖动方向走
     *   ③ |速度| > velocityThreshold 且 位移 > minVelDist  → 甩够了，朝速度方向走
     *   ④ 兜底                                           → 过半就翻
     *
     * ⚠️ 与报告伪代码的一处**有意的改写**：
     *    伪代码里的 `offset` 是从静止位置开始的绝对位移（DragToDismiss 只有一个方向）。
     *    侧边栏可开可关，`progress` 的绝对值分不清用户想开还是想关，
     *    所以用 `deltaProgress = progress - dragStartProgress`（本次拖拽产生的位移）。
     *    三段式的**结构**完全一致，只是把"位移"取成本次拖拽的增量。
     *
     * @param velocityX 松手速度（px/s，正 = 向右）
     */
    fun settle(velocityX: Float, cfg: SidebarSettleConfig) {
        settleJob?.cancel()

        val target = computeSettleTarget(velocityX, cfg)
        val from = _progress.floatValue
        val springSpec = springFor(target > 0.5f)

        // 把松手速度带进弹簧，让"甩出去"有惯性延续感。
        // Animatable 的速度单位是「进度/秒」，所以要除以面板宽度。
        val initialVelocityProgress =
            if (cfg.panelWidthPx > 0f) velocityX / cfg.panelWidthPx else 0f

        settleJob = scope.launch {
            val anim = Animatable(from)
            anim.animateTo(
                targetValue = target,
                animationSpec = springSpec,
                initialVelocity = initialVelocityProgress,
            ) {
                // 越界钳制：弹簧过冲时不让面板跑到屏幕外或越过展开位
                _progress.floatValue = value.coerceIn(0f, 1f)
            }
            // 收尾对齐到精确目标，避免浮点残差让面板差一像素
            _progress.floatValue = target
        }
    }

    /**
     * 三段式判据的纯函数形式（无副作用，便于单测）。
     * 见 [settle] 的文档注释。
     */
    fun computeSettleTarget(velocityX: Float, cfg: SidebarSettleConfig): Float {
        val width = cfg.panelWidthPx
        val current = _progress.floatValue
        val start = dragStartProgress

        // 还没测量到宽度（理论上不会发生），退化为"过半就翻"
        if (width <= 0f) return if (current > 0.5f) 1f else 0f

        val deltaProgress = current - start
        val deltaPx = deltaProgress * width

        return when {
            // ① 死区：这一下基本没动 → 回拖拽之前的状态
            abs(deltaPx) < cfg.slopPx -> if (start >= 0.5f) 1f else 0f

            // ② 距离够了 → 朝拖动方向
            abs(deltaPx) > cfg.distanceThresholdFraction * width ->
                if (deltaProgress > 0f) 1f else 0f

            // ③ 甩够了（且真的动了）→ 朝速度方向
            abs(velocityX) > cfg.velocityThresholdPxPerSec &&
                abs(deltaPx) > cfg.minVelocityDistancePx ->
                if (velocityX > 0f) 1f else 0f

            // ④ 兜底
            else -> if (current > 0.5f) 1f else 0f
        }
    }

    private fun springFor(open: Boolean) =
        if (open) SidebarMotion.openSpring() else SidebarMotion.closeSpring()

    /** 程序化打开（走弹簧，不是瞬变）。非 suspend。 */
    fun open() = animateProgrammatic(1f)

    /** 程序化关闭（走弹簧，比打开更快 —— 报告 §7.4 原则 1）。非 suspend。 */
    fun close() = animateProgrammatic(0f)

    fun toggle() {
        if (progress > 0.5f) close() else open()
    }

    private fun animateProgrammatic(target: Float) {
        settleJob?.cancel()
        val from = _progress.floatValue
        if (from == target) return
        settleJob = scope.launch {
            val anim = Animatable(from)
            anim.animateTo(target, springFor(target > 0.5f)) {
                _progress.floatValue = value.coerceIn(0f, 1f)
            }
            _progress.floatValue = target
        }
    }
}

/**
 * 松手判据需要的全部阈值，已换算成像素 / 每秒像素。
 *
 * 单独拎出来是因为 `Density` 换算必须发生在组合期，
 * 而判据本身是纯函数，拆开就能单测。
 */
@Immutable
data class SidebarSettleConfig(
    val panelWidthPx: Float,
    val slopPx: Float,
    val distanceThresholdFraction: Float,
    val velocityThresholdPxPerSec: Float,
    val minVelocityDistancePx: Float,
)

fun buildSettleConfig(panelWidthPx: Float, density: Density): SidebarSettleConfig =
    with(density) {
        SidebarSettleConfig(
            panelWidthPx = panelWidthPx,
            slopPx = SidebarMotion.DistanceSlop.toPx(),
            distanceThresholdFraction = SidebarMotion.DistanceThresholdFraction,
            velocityThresholdPxPerSec = SidebarMotion.VelocityThreshold.toPx(),
            minVelocityDistancePx = SidebarMotion.MinVelocityDistance.toPx(),
        )
    }

@Composable
fun rememberSidebarState(initiallyOpen: Boolean = false): SidebarState {
    val scope = rememberCoroutineScope()
    return remember { SidebarState(initiallyOpen, scope) }
}
