package com.chatmobile.ui.sidebar

import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * 侧边栏动效与手感参数。
 *
 * 每个参数都标了来源，方便以后真机调的时候知道哪些能动、哪些是实测值不能瞎改。
 * 依据文档：`UI研究-动效与转场.md` §1.2 / §1.4 / §7.1 / §7.3。
 *
 * 标记约定：
 *   [实测] = 从 ChatGPT APK 里直接读到的值
 *   [参考] = 报告建议的起步值，需真机调
 */
object SidebarMotion {

    /**
     * 抽屉面板宽度占屏幕比例。
     * [实测] `SidebarLayout` 的 `maxWidth: 88%`（`oai_ui.raw`）。
     * 另一个组件 `SideDrawer` 是 86%，但主界面用的是前者（见报告 §1.1 的三条理由）。
     */
    const val PanelWidthFraction: Float = 0.88f

    /**
     * 位移死区。手指移动没超过它，松手时不做方向判断（防手抖误触）。
     * [参考] 报告 §7.3 的 `distanceSlop = 8.dp`。
     */
    val DistanceSlop = 8.dp

    /**
     * 距离阈值（面板宽度的比例）。拖过这么多就判定"要走"，比"过半就开"更早触发。
     * [参考] 报告 §7.3 的 `distanceThreshold = 0.35f`。
     */
    const val DistanceThresholdFraction: Float = 0.35f

    /**
     * 速度阈值。"甩一下就走"用的门槛。
     * [参考] 报告 §7.3 的 `velocityThreshold = 800f`（dp/s）。
     */
    val VelocityThreshold = 800.dp

    /**
     * 速度判据生效所需的最小位移 —— 报告里点名的精髓参数。
     * 防"手指几乎没动但抖动速度很大"导致误开/误关。
     * [参考] 报告 §7.3 的 `minVelocityDistance = 24.dp`。
     */
    val MinVelocityDistance = 24.dp

    /**
     * 屏幕左边缘的可拖拽热区宽度。
     * [未查到] 官方 `sidebar-edge` 节点的具体 dp（写在 JS 样式里，编译后被剥掉，报告 §1.3 / §8）。
     * 这里取 20.dp 作为 [参考] 值 —— 太窄了不容易触发，太宽了会抢走列表左边缘的纵向滚动。
     */
    val EdgeWidth = 20.dp

    /**
     * 遮罩最深不透明度。
     *
     * ## ⚠️ 这个值是**按观感调的，不是逆向值** —— 不要当成有出处的数据
     *
     * 取证过程与结论（保留在此，防止后人误以为它有依据）：
     *
     * - **用法已确认**：`oai_ui.raw:270014`（`SidebarLayout` 段）里
     *   `OAIColor | BgScrim | backgroundColor | backdrop` ——
     *   即官方的遮罩就是 `BgScrim` 作为 backdrop 的 `backgroundColor`，
     *   配合 `opacity` 与 progress 线性联动。**是纯色层，不是模糊、不是渐变。**
     *   （与 `UI研究-动效与转场.md` §1.2 第 7 条一致）
     *
     * - **数值拿不到**：`oai_core.raw:23379` 的 packed 颜色表里，
     *   `oai-bg-scrim` 后继字符是 `.`（下一个键的分隔符）而非 `#`，
     *   **没有内联颜色值**。同表的 40 个颜色字面量逐个映射回 token 后，
     *   没有一个属于 `BgScrim`。（同表里 `oai-bg-secondary` = `#E8E8E8BF`、
     *   `oai-bg-code-block` = `#E3E3E380` 都是有值的，能作对照。）
     *
     * - **`.` 的两种解释无法区分**：
     *   ① 空值；② 继承前一个 `#E3E3E380`（alpha 50%）。
     *   判别需要 Valdi 运行时或 dex，而 `_re` 下**没有 dex/jar**
     *   （已全树查过 `*.dex` / `*.jar`，为空）⇒ **取证到此为止。**
     *
     * ## 那 0.12 是哪来的
     *
     * **用户看 ChatGPT 官方 App 的截图后拍的板**，原话是
     * 「按「ChatGPT 那样轻微压暗」去改」。依据是：右侧内容**仍然清晰可读**，
     * 只是轻微压暗 / 降饱和，**不是一层黑盖住**。
     *
     * 所以 0.12 的正当性来自**观感对照**，不来自逆向。真机不合适就调，
     * 调的依据也应该是"和官方截图比而不是和数值比"。
     *
     * ## 从 0.42 降到 0.12 的原因
     *
     * 0.42 在浅色背景下已相当重，而面板占 88% ⇒ 剩下那条窄边显得"糊死了"。
     * 更关键的是：**遮罩的作用是提示"不可交互"，不是"压暗"**。
     */
    const val ScrimMaxAlpha: Float = 0.12f

    // ---------------------------------------------------------------------
    // 松手收敛的弹簧
    //
    // [未查到] `SPRING_DEFAULT_STIFFNESS` / `SPRING_DEFAULT_DAMPING` 的具体数值
    //          （模块级常量，数值被编译剥离，报告 §8）。
    //
    // 这里用"打开稍慢、关闭更快"的两套参数，刻意落实报告 §7.4 原则 1
    // （出场快于入场，比例约 1 : 0.67）：
    //   打开 ≈ 入场 → 软一点、有重量感
    //   关闭 ≈ 出场 → 硬一点、干脆
    // ---------------------------------------------------------------------

    /** 打开的弹簧：略欠阻尼，末端有一点点回弹的"重量感"。 */
    const val OpenDampingRatio: Float = 0.85f
    const val OpenStiffness: Float = 380f

    /** 关闭的弹簧：更硬，收得更快。 */
    const val CloseDampingRatio: Float = 0.90f
    const val CloseStiffness: Float = 520f

    fun openSpring() = spring<Float>(
        dampingRatio = OpenDampingRatio,
        stiffness = OpenStiffness,
    )

    fun closeSpring() = spring<Float>(
        dampingRatio = CloseDampingRatio,
        stiffness = CloseStiffness,
    )
}
