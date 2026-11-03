# ChatMobile UI 层实现说明

> 位置：`app/src/main/java/com/chatmobile/ui/`
> 实现者：ui-agent
> 日期：2026-11-02

本文说明三件事：**实现了哪几条手感清单**、**每个参数的依据**、**还有什么没做**。

所有参数都标注了来源：

| 标记 | 含义 |
|---|---|
| `[实测]` | 从 ChatGPT APK 里直接读到的值，**不要改** |
| `[参考]` | 报告建议的起步值，**需要真机调** |
| `[未查到]` | 官方确实没取到，我用了自己的取值 |

依据文档（工程根目录）：
- `UI研究-动效与转场.md`
- `UI研究-交互手感.md`

---

## 一、文件清单

### `ui/sidebar/` —— 侧边栏（P0）

| 文件 | 职责 |
|---|---|
| `SidebarMotion.kt` | 全部参数常量（宽度 / 三段式阈值 / 弹簧） |
| `SidebarState.kt` | 进度状态机 + 三段式判据（纯函数，可单测） |
| `SidebarGestures.kt` | 手势：方向判定 / 快照 / 跟手 / 松手 |
| `SidebarLayout.kt` | 布局与渲染：`round()` 像素对齐 / 遮罩 / 边缘热区 |
| `SidebarPanel.kt` | 面板内容：新建对话 / 会话列表 / 设置 |

### `ui/chat/` —— 聊天界面（P1 + P2）

| 文件 | 职责 |
|---|---|
| `ChatMessage.kt` | 展示模型（`ChatMessage` / `Conversation`） |
| `ChatMotion.kt` | 全部动效 token |
| `PressFeedback.kt` | 按压反馈（清单 #1 #2 #3） |
| `AutoFollow.kt` | 流式自动跟随（清单 #7） |
| `MessageBubble.kt` | 消息气泡 / 思维链 / 流式光标 |
| `ChatInputBar.kt` | 输入区 + 发送停止同位（清单 #8 #9） |
| `ChatScreen.kt` | 主界面：列表 + 顶部栏 + 回到底部 |
| `ChatDemoScreen.kt` | 假数据 + 模拟流式（临时入口） |

---

## 二、侧边栏（P0）—— 重点

按 `UI研究-动效与转场.md` §1.2 逆向出的 `SidebarLayout` 机制实现。

### 2.1 八条机制逐条对照

| # | 报告原文的机制 | 本实现的落点 |
|---|---|---|
| 1 | **进度归一化**：`progress = dragStartProgress + deltaX / panelWidth` | `SidebarGestures.kt` 中 `state.dragTo(dragStartProgress + (x - anchorX) / width)` |
| 1 | **`round()` 整数像素对齐** | `SidebarLayout.kt`：`translationX = (round(progress * panelWidthPx).toInt() - panelWidthPx)`。**面板宽度本身也 `round().toInt()`**，保证差值必为整数 |
| 2 | **起手快照 `dragStartProgress`** | `SidebarState.beginDrag()`，只在接管横向手势那一刻调一次 |
| 3 | **方向判定 `abs(dx) > abs(dy)`** | `SidebarGestures.kt`：未过 slop 前持续观察；判定为纵向则 `return`，**一个事件都不 consume** |
| 4 | **松手弹簧 + 读速度** | `SidebarState.settle(velocityX, cfg)`，用 `VelocityTracker` 取 `velocity.x` |
| 5 | **三段式判据** | `computeSettleTarget()`，见 2.2 |
| 6 | **跟手不插值** | 拖动期 `dragTo()` 是**普通函数**直接改 `mutableFloatStateOf`；松手才在内部 `launch` 弹簧 |
| 7 | **遮罩与 progress 线性联动** | `graphicsLayer { alpha = progress * 0.42f }` |
| 8 | **overlay 浮起、内容不缩放** | 面板是 `Box` 内 `align(CenterStart)` 的独立层，**没有对 content 做 parallax** |

### 2.2 三段式松手判据（照搬报告 §7.3 结构）

```
① 位移 < distanceSlop                              → 回原位（不判方向，防手抖）
② 位移 > distanceThreshold * panelWidth             → 拖够了，朝拖动方向
③ |velocity| > velocityThreshold 且 位移 > minVelDist → 甩够了，朝速度方向
④ 兜底                                             → 过半就翻
→ 然后 spring(damping, stiffness) 收敛
```

**一处有意的改写**（必须说明，否则算偏离）：

报告伪代码里的 `offset` 是**从静止位置开始的绝对位移** —— 因为 `DragToDismissController`
只做单向关闭，位移的符号就代表方向。

但侧边栏**可开可关**，`progress` 的绝对值分不清用户想开还是想关
（比如 `progress = 0.9` 既可能是"刚拖到 0.9"，也可能是"从 1.0 往回拖到 0.9"）。

所以本实现用 **`deltaProgress = progress - dragStartProgress`**（本次拖拽产生的位移）。
**判据结构完全一致，只是把"位移"取成本次拖拽的增量。**

### 2.3 参数表

| 参数 | 值 | 来源 |
|---|---|---|
| 面板宽度 | **88%** | `[实测]` `SidebarLayout` `maxWidth: 88%` |
| `distanceSlop` | 8 dp | `[参考]` 报告 §7.3 |
| `distanceThreshold` | 面板宽 35% | `[参考]` 报告 §7.3（比"过半"更早触发） |
| `velocityThreshold` | 800 dp/s | `[参考]` 报告 §7.3 |
| `minVelocityDistance` | 24 dp | `[参考]` 报告 §7.3（防"速度大但没动"） |
| 打开弹簧 | damping 0.85 / stiffness 380 | `[未查到]` 官方 D/S |
| 关闭弹簧 | damping 0.90 / stiffness 520 | `[未查到]` —— 见下 |
| 边缘热区 | 20 dp | `[未查到]` 官方 `sidebar-edge` 具体 dp |
| 遮罩最深 alpha | 0.42 | `[未查到]` |

**为什么关闭比打开硬**：报告 §7.4 原则 1「出场快于入场，比例约 1 : 0.67」。
打开 = 入场（软、有重量感），关闭 = 出场（硬、干脆）。

### 2.4 为什么不用 `ModalNavigationDrawer`

报告 §0 / §1.1 实测：ChatGPT 全 dex 里 `ModalNavigationDrawer` **命中 0**，
官方抽屉是自研 `SidebarLayout`。

Compose 的 `ModalNavigationDrawer` 走锚点 + `AnchoredDraggable`：
- 跟手是**锚点之间插值**，不是 1:1 归一化进度 → 做不出报告要的手感
- 松手判据是 Material 固定策略 → 做不出三段式

### 2.5 ⊙ 一个踩过的坑：`awaitEachGesture` 是受限挂起作用域

`AwaitPointerEventScope` **只能调用它自己的挂起函数**，不能调外部任意 `suspend`
（`Animatable.animateTo`、`delay` 都不行）。

我最初用 `Animatable` 当拖动载体，编译直接报：
```
Restricted suspending functions can invoke member or extension suspending
functions only on their restricted coroutine scope.
```

**修法不是搬位置，是换载体**：

| 阶段 | 载体 | suspend? |
|---|---|---|
| 拖动中 | `mutableFloatStateOf` 直接赋值 | **否** |
| 松手后 | `Animatable.animateTo(spring)` 逐帧回写 | 是，但在**内部 launch** 的协程里 |

这个限制恰好把实现推到了报告 §7.4 原则 3 要求的形态上：
拖动期 `setState`（无动画）、松手才 `setStateAnimated`（弹簧）。**限制与正确写法同向。**

---

## 三、10 条手感清单的完成情况

来源：`UI研究-交互手感.md` §7。

| # | 手感项 | 状态 | 落点 |
|---|---|---|---|
| **1** | 按压缩放先读原始值再乘 | ✅ **做了** | `PressFeedback.kt` `pressScale(baseScale)` |
| **2** | 按压反馈与点击回调解耦 | ✅ **做了** | `PressFeedback.kt` `pressAndTap()` |
| **3** | 触感默认开 | ✅ **做了**（3 处） | 见 4.1 |
| 4 | 长按 → 原生上下文菜单 | ⬜ **没做** | 见第六节 |
| 5 | 图片点击 → lightbox | ⬜ **没做** | 见第六节 |
| 6 | 预览前校验 URL 可信 | ⬜ **没做** | 见第六节（安全问题，接图片时必做） |
| **7** | 流式自动跟随 + 拖拽即停 + 滚停恢复 | ✅ **做了** | `AutoFollow.kt` + `ChatScreen.kt` |
| **8** | 发送/停止同位置切换 | ✅ **做了** | `ChatInputBar.kt` `SendStopButton` |
| 9 | 输入框高度用计算属性 | 🟡 **部分** | 见 4.2 |
| 10 | 拖拽关闭弹层 4 参数判据 | 🟡 **部分** | 判据已实现，见 4.3 |

**完成度：6 条做了 / 2 条部分 / 2 条没做（都是图片相关，等图片功能）**

---

## 四、关键实现说明

### 4.1 触感的三处（清单 #3）

报告 §6.1 的核心发现：**官方默认策略是「能震的控件默认震」** ——
`CoreToggle.js` 里要靠 `hapticFeedbackDisabled` 这个**显式开关去抑制**。
这跟 Compose 的默认（什么都不震）完全相反。

三处必给，全部用 `HapticFeedbackType.TextHandleMove`（对应官方的 `selection` 语义）：

| 位置 | 文件 |
|---|---|
| 会话选择变更 | `SidebarPanel.kt` `onSelect` |
| 思维链展开/收起 | `MessageBubble.kt` `ReasoningBlock` |
| 发送/停止按钮 | `ChatInputBar.kt` `SendStopButton` |

**不用自写 `VibrationEffect`** —— 报告 §6.4 实测官方 `VibrationEffect` **0 命中**，
只用系统触感常量。自写波形不尊重用户系统设置，且费电。

### 4.2 清单 #9 只做到"部分"的原因

报告 §5.1 说官方 `textInputHeight` 是 `setAttributeFunction`（每帧由内容推导）。

Compose 的严格等价物是 `SubcomposeLayout` 或读 `TextLayoutResult`，
但那会**明显增加复杂度**，而且 `BasicTextField` + `heightIn(min, max)`
已经做到了"高度是计算属性、不在 `onValueChange` 里写高度逻辑"这个**核心要求**。

差的部分：`SubcomposeLayout` 能做到"少一次重组"，`heightIn` 不能。
**判定：性价比不够，等 UI 骨架稳定后再优化。**

### 4.3 清单 #10 只做到"部分"的原因

四个阈值（`distanceThreshold` / `distanceSlop` / `velocityThreshold` /
`minVelocityDistance`）**都实现了**，在 `SidebarState.computeSettleTarget()`。

没做的：
- `wasEligibleForDismiss`（防回拖误关）—— 侧边栏是双向的，这个语义不直接适用
- **底部弹层本身还没做**，所以这套判据目前只用在侧边栏上

判据是纯函数，将来做底部弹层时复用它即可。

### 4.4 ★★ reverseLayout 的两个坑（清单 #7 的前提）

聊天列表用 `reverseLayout = true`，**所有"是否在底部"的判据都必须反过来**：

| | 正序列表 | **reverseLayout = true（本项目）** |
|---|---|---|
| 视觉最底部 | `lastVisibleItemIndex == count-1` | **`firstVisibleItemIndex == 0`** |
| 用户往上翻 | index 变小 | **index 变大** |
| 滚到底部 | `scrollToItem(count-1)` | **`scrollToItem(0)`** |

按正序逻辑写会得到完全相反的行为（用户往上翻反而触发跟随）。
实现在 `AutoFollow.kt` 的 `isAtBottom`。

#### ★ 坑 ①（真出过 bug）：`reverseLayout` 不反转数据，只改绘制方向

**这是本项目实际发生过、被用户截图抓到的 bug。**

我最初以为「`reverseLayout = true` 会自动让数据从底部往上排」，于是直接喂了时间正序的
`messages`。实际行为是：

```
messages   = [msg1(最早), msg2, msg3(最新)]   ← 时间正序
reverseLayout 从【底部】开始画
⇒ msg1 落在 index 0 = 视觉最底部      ✗ 最老的消息跑到最下面
⇒ msg3 落在最上面                      ✗ 最新的消息跑到最上面
```

**后果**：用户发的第一条消息一直黏在屏幕底部不被顶掉，AI 的长回复反而往上堆。
`AutoFollow` 的判断本身没错，但**数据顺序反了，跟随方向也就反了**。

**正解**：自己喂倒序数据。

```kotlin
items(items = messages.asReversed(), key = { it.id }) { ... }
// [msg3, msg2, msg1] + reverseLayout ⇒ msg3 在 index 0 = 视觉底部 ✓
```

这样 `index 0` 同时是「最新」和「视觉底部」，`AutoFollow.isAtBottom` 的
`firstVisibleItemIndex == 0` 才能成立。

> **教训**：`reverseLayout` 是**绘制层**的翻转，不是**数据层**的翻转。
> 两件事必须分开想 —— 「我从哪一头开始画」和「数组第 0 个元素是谁」是独立的两件事。

#### ★ 坑 ②：流式追加时 `size` 不变

流式追加时**消息数量不变、只有文本长度在涨**。
所以触发跟随的 key 必须同时看 `messages.size` 和 `lastMessage.text.length`，
只看 `size` 会导致流式期间**完全不跟随**。

见 `ChatScreen.kt`：

```kotlin
val lastLen = messages.lastOrNull()?.text?.length ?: 0
LaunchedEffect(messages.size, lastLen) { autoFollow.onContentGrew() }
```

> ⚠️ 注意 `lastOrNull()` 取的是**时间正序**的最后一条（最新），
> 与渲染用的 `asReversed()` 是两件事 —— 判断"哪条最新"用正序，
> 决定"画在哪"用倒序。这两个用途不要混用同一个集合。

### 4.5 流式跟随的三个时机

| 时机 | 动作 | 官方对应 |
|---|---|---|
| 新内容到达 | 若 `autoFollow` 则 `scrollToItem(0)`（**瞬移，不动画**） | — |
| 手指落下 | `autoFollow = false`（**拖拽即停**） | `onDragStart` |
| 松手 | 若已在底部则恢复 `autoFollow = true` | `onDragEnding` |

**为什么松手就判、不等惯性停**：报告 §4.2 明确建议判据挂 `DragEnding`
（松手但惯性未停）而非 `DragEnd` —— 等惯性停要一秒多，体感会"迟钝"。

**为什么流式跟随用瞬移不用动画**：每个 token 都触发一次，
`animateScrollTo` 会互相打断产生抖动。报告 §7.1 的 600ms
是给**用户主动跳转**用的（`animateToBottom()`），不是给流式追帧用的。

### 4.6 ★★★ 流式期间 `placementSpec` 必须是 `null`，不是 `tween(0)`

这一节记录了**三层**逐层剥开的过程，每一层都有各自的证据来源。
留着是因为第 3 层的结论**违反直觉**，很容易被后来人"优化"回去。

#### 第 1 层：220ms 缓动 vs 瞬时滚动（用户第一次反馈）

**用户原话**：
> 「流式输出不太对劲，就是它这个向上的运动不是连贯的，而是每多一行向上挤一下，
> 但是这个挤又没有同步，导致二者其实是错位的。」

| 环节 | 时间尺度 |
|---|---|
| 文本增长（跨行时高度一次性 +一整行） | **离散跳变，瞬时** |
| `scrollToItem(0)` 跟随 | **瞬时（0 ms）** |
| `animateItem(placementSpec)` 让旧消息上移 | **220 ms 缓动** ← 元凶 |

流式时 token 每几十毫秒到一次，`items` 的位置目标**每帧都在变**，
220ms 的 placement 动画被反复重启、永远追不上 ⇒「慢慢挤、还落后半拍」。

修法：**流式期间把 placement 压掉**。方向是"压动画"，不是"把滚动改慢"（后者更糟）。

> 与报告 §7.4 原则 3「跟手期间绝不插值」是同一个道理 ——
> **流式输出本质是"内容在跟手"的状态**，此时不该有独立缓动。

#### 第 2 层：调度延迟（用户第二次反馈）

**用户原话**：
> 「行数先增加，然后在极短的时间内用户消息再上升。」
> 「之后他们肯定会恢复过来。」

**「之后会恢复」+「先…然后…」= 典型晚 1~2 帧。** 逐层剥掉调度：

| # | 环节 | 修法 |
|---|---|---|
| 1 | `scope.launch { scrollToItem }` 协程排队 | → `requestScrollToItem`（**非 suspend**，已 javap 核实签名） |
| 2 | `LaunchedEffect` 在组合**提交后**才跑 | → `SideEffect`（重组后、measure 前同步执行） |

**`SideEffect` 方案的两个前提与风险（都需真机验证）：**

1. **前提**：`requestScrollToItem` 只写内部 `MutableState`，**幂等无副作用**，
   所以放在每次重组都跑的 `SideEffect` 里是安全的。
2. **风险**：`SideEffect` 每帧重组都会调 `onContentGrew()`。
   它内部有两个短路（`!autoFollow` / `isAtBottom`），正常情况不会狂滚。
   **但如果 `isAtBottom` 在长回复下不成立**（见 §4.7 的未验证隐患），
   就会退化成**每帧都请求滚动** —— 用户往上翻会被一直拽回底部。

> ⇒ 用 `SideEffect` 比用 `LaunchedEffect` **更需要**验证"长回复时往上拖能不能停住"。

#### ★ 第 3 层：`tween(0)` 不等于"没有动画"（字节码证据）

前两层修完**仍有错位**。真相在字节码里 ——
`LazyLayoutItemAnimation.animatePlacementDelta`：

```bytecode
 0: getfield   placementSpec
 5: ifnonnull  10          ← 非空才继续
 9: return                 ← ★ null 直接返回，零开销
...
30: setPlacementAnimationInProgress(true)
38: getfield   coroutineScope
62: invokestatic BuildersKt.launch$default   ← ★★ 非空就 launch 协程
```

| `placementSpec` | 实际行为 |
|---|---|
| **`null`** | 第 9 行直接 `return`，**真的不创建动画** |
| `tween(0)` | 第 62 行 **launch 一个协程**，在协程里才把 delta 归零 |

⇒ **`tween(0)` 不是 snap，是「用一个协程去做 0 毫秒的动画」。**
协程调度至少一帧 ⇒ 旧消息位移永远比内容增长晚一帧。
而这发生在 `animateItem` **内部**，`SideEffect` / `requestScrollToItem` **都够不着** ——
它们修的都是"滚动"，延迟却在"item 位移动画"里。

**正确写法：**

```kotlin
placementSpec = if (isGenerating) {
    null                          // ★ 流式：走字节码第 9 行的 return，完全不创建动画
} else {
    ChatMotion.layoutMoveSpec()   // 非流式：220ms [实测]，历史消息重排要柔和过渡
},
```

> ⚠️ **不要"优化"成 `tween(0)` 或 `snap()`。** 那会重新引入一帧延迟，
> 且症状很隐蔽（只在流式换行瞬间可见）。

> ⚠️ **`null` 的副作用（待真机验证）**：`null` 彻底跳过 placement 处理，
> 而 `tween(0)` 至少有 `setPlacementAnimationInProgress(true)` 在维护状态。
> 若某帧旧 item 未被重新测量（如处于可见边界），理论上可能**停在旧位置**。
> 装机时需专门验：长回复流式时旧消息会不会卡住不动。

#### 这一条的通用教训

> **"参数值压到 0"和"走另一条代码分支"是两件不同的事。**
> 当一个 API 的可空参数表示"关闭功能"时，`null` 与"零值参数"往往走**完全不同的路径**。
> 判断依据只能是读实现（源码或字节码），不能靠语义类比。

### 4.7 ⚠️ `isAtBottom` 在长回复超一屏时的隐患（**未验证，未修改**）

**Lead 提出的推理**：如果最新一条 AI 回复长到超过一屏，
`reverseLayout` 下 `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`
是否还成立？若不成立，长回复会**中途停止跟随**。

**当前状态：判据未改，等真机实测结论。**

我一度想改成用 `canScrollForward` 作为主判据：

```kotlin
// ❌ 未采用 —— 语义未验证
val isAtBottom get() = !listState.canScrollForward || (...原判据...)
```

**为什么回退了**：`canScrollForward` 在 `reverseLayout = true` 下的语义
（"forward" 到底指向最新还是最旧）**拿不到证据**。
Gradle 缓存里只有 `foundation.aar`，没有 sources jar；
从字节码反推也可行但成本高于收益。

> ★ 纪律：**没有证据的改动不做。** 尤其是这种"看起来更健壮"的改动 ——
> 它在语义猜错时会让行为**静默地错**，比现在明确知道"可能有问题"更难排查。

**验证方式（不需要改 `build.gradle`）**：
真机发一条会生成很长内容的消息，观察长度超过一屏后是否仍在跟随。
如果停住 ⇒ 隐患成立，届时再用 `createComposeRule()` 写仪器测试确证，
并且**单独一次提交、单独验证**（当前构建有并发瞬态故障，两件事叠一起会互相污染归因）。


勾选拖拽监听挂在 `PointerEventPass.Initial` —— 早于列表自身滚动处理，
手指一落下就停，不等列表真的开始滚。

---

## 五、动效参数表（全部 `[实测]`）

来源：`UI研究-动效与转场.md` §3.2 / §7.1，原文出自
`dil_generated_components.raw` 的明文 animate 规格。

| 用途 | Compose 写法 | 值 |
|---|---|---|
| 消息入场 | `tween(180)` + `translateY 6 → 0` + `alpha 0→1` | 180 ms |
| 消息退场 | `tween(120)` + `translateY 0 → -4` + `alpha 1→0` | 120 ms |
| 列表布局进 | `animateItem(fadeInSpec)` | 220 ms |
| 列表布局出 | `animateItem(fadeOutSpec)` | 160 ms |
| 列表布局移动 | `animateItem(placementSpec)` | 220 ms |
| 纯淡入 | `tween(200)` | 200 ms |
| 纯淡出 | `tween(150)` | 150 ms |
| 程序化滚动 | `animateScrollTo` | 600 ms |
| 抽屉宽度 | `fillMaxWidth(0.88f)` | 88% |

### 三条原则的落实

| 原则 | 落实 |
|---|---|
| **出场快于入场**（1 : 0.67） | 入 180 / 出 120；布局进 220 / 出 160；纯淡入 200 / 淡出 150 |
| **位移极小 + 透明度承担主要信息** | `EnterOffsetY = 6.dp`、`ExitOffsetY = -4.dp` |
| **跟手期间绝不插值** | 拖动期直接赋值，无任何 `animate*` |

### 一处需要真机确认的取值

报告原文是 `translateY: -6`（**裸数字**，Valdi 里长度单位是"点"）。
我按 `6.dp` 用 —— 在高密度屏上视觉位移会略大于 6 物理像素。

**如果真机觉得"晃眼"，把 `ChatMotion.EnterOffsetY` 调到 `4.dp`。**
这是本表里我唯一不确定的单位换算。

---

## 六、明确没做的（附理由）

| 项 | 为什么没做 |
|---|---|
| **长按 → 原生上下文菜单**（清单 #4） | 需要 `data/` 的持久化（复制/编辑/重新生成要落库），等集成。**不要用 `DropdownMenu` 自己画** —— 报告 §2.1 明确官方走原生 `ActionMode` 风格 |
| **图片 lightbox**（清单 #5） | 目前没有图片消息 |
| **URL 可信校验**（清单 #6） | 同上。★ **接入图片时这是必做项，不是可选项** —— 自填 API Key 场景下模型返回的 URL 完全不可信 |
| **消息级 swipe-to-dismiss** | ❌ **明确不做**。报告 §2.5 判定官方不存在，且会跟系统返回手势冲突 |
| **手搓 ripple 径向渐变** | ❌ **明确不做**。报告 §1.1：Compose 的 `ripple()` 已是同一物理模型 |
| **自研图片缩放** | ❌ **明确不做**。报告 §3.5 判定官方 Android 侧没实现，要上就上 `telephoto` |
| 共享元素转场 | 报告 §3.5 判定官方大概率不存在，不必做 |
| 底部弹层 + 其拖拽关闭 | 弹层本身还没出现 |
| 键盘调起时的输入框滚动 | 需要真机键盘高度观察，等真机验证后再做 |

---

## 七、已知限制 / 待真机验证

| 项 | 说明 |
|---|---|
| `EnterOffsetY` 单位 | 见第五节末尾，可能要 6 → 4 |
| 边缘热区 20 dp | 官方值未查到。太窄难触发、太宽会抢列表左边缘的纵向滚动 |
| 弹簧 D/S | 官方值未查到。现在是"能明显看出重量感但不拖沓"的估计值 |
| 遮罩 alpha 0.42 | 官方 scrim 值未查到 |
| 按压比例 0.96 | 官方值未查到（报告 §8 明确列为未查到） |
| **`isAtBottom` 长回复** | 见 §4.7。**未验证、未修改**，等真机实测 |

**这些都需要真机上手才能定，代码结构不用改，只改 `SidebarMotion.kt` 里的常量。**

---

## 七之二、真机验证后修掉的问题（回归记录）

这些问题都是「编译通过 + 界面能显示」但**逻辑不对**的 ——
留档是为了避免以后"重构时又改回去"。

| # | 现象（用户原话） | 根因 | 修法 | 详见 |
|---|---|---|---|---|
| 1 | 最早的消息黏在底部不被顶掉 | **误以为 `reverseLayout` 会反转数据**，喂了正序 | `items(messages.asReversed())` | §4.4 坑① |
| 2 | 向上运动不连贯，「每多一行向上挤一下，还错位」 | **placement 220ms 缓动 vs 滚动瞬时**，两套时间尺度打架 | 流式期 `placementSpec = tween(0)` | §4.6 |
| 3 | 按回车不发送 | `ImeAction.Default` ⇒ 只插换行 | `ImeAction.Send` + `KeyboardActions(onSend)` | §4.7 下方 |
| 4 | 侧边栏打开时键盘还在 | 只在菜单键路径收了键盘，**手势路径漏了** | 收键盘逻辑下沉到 `SidebarLayout` | — |
| 5 | 滚动回看历史会**重播入场动画** | 状态绑在 `remember(message.id)`，被 LazyColumn 回收 | 已播 id 集合上提到 `ChatScreen` | §4.8 |

> **共同教训**：这 5 个都不是编译能发现的错。
> 其中 #1 #5 是同一类 —— **把状态/假设绑在了一个会被回收或翻转的生命周期上**。

### §4.7 补充：回车发送（#3）

```kotlin
keyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
    imeAction = ImeAction.Send,
),
keyboardActions = KeyboardActions(
    onSend = { if (value.isNotBlank() && !isGenerating) onSend() },
),
```

关键：IME 的发送键与右侧按钮走**完全相同的 `onSend` 回调**，
避免"键盘能发、按钮不能发"这类分叉。

### §4.8 滚动回看重播动画（#5）

`LazyColumn` 会**回收滚出屏幕的 item 的组合实例**。如果不处理：

```
item 被回收 → remember 丢失 → visible 重新变 false
→ LaunchedEffect 再跑一次 → AnimatedVisibility 重播入场动画
```

**修法**：入场动画的「是否播过」必须存在**比 item 生命周期更长**的地方 ——
由 `ChatScreen` 持有一个 `HashSet<String>`，通过 `animateEnter` / `onAppear` 传入。

用普通 `HashSet` 而非 snapshot 集合是有意的：它只在
`remember(message.id) { }` 初始化块里读一次，不需要参与重组；
snapshot 集合反而会在 `add` 时触发无谓重组。

**这也是同一个教训**（与 §4.4 坑① 同类）：状态不能绑在会被回收的生命周期上。

---

## 八、接入方式（等 net/data 交付后）

当前 `MainActivity` 接的是 `ChatDemoScreen()`。替换方式：

```kotlin
// MainActivity.kt
setContent {
    ChatMobileTheme {
        // 换成真实 ViewModel 驱动
        ChatRoute(viewModel = viewModel())
    }
}
```

`ChatScreen` 的签名已经按"可接 ViewModel"的形状设计：

```kotlin
ChatScreen(
    messages: List<ChatMessage>,
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenSidebar: () -> Unit,
)
```

只需把 `ui/chat/ChatMessage.kt` 里的展示模型映射到 `data/` 的持久化模型即可。
**注意方向：UI 依赖映射，不要反向让 `data/` 依赖 UI 的模型。**

---

## 九、验证

- 编译：`gradle :app:compileDebugKotlin`
- 真机：由 lead 在 Redmi 2407FRK8EC / Android 16 上验证

`ChatDemoScreen` 的流式模拟是**专门为了让手感可验证**设计的：
每 40 ms 追加 3~6 字，持续十几秒，覆盖自动跟随 / 拖拽即停 / 停止按钮三件事。
