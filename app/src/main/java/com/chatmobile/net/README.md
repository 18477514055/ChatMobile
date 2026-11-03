# 网络层（`com.chatmobile.net`）设计说明

> 流式对话 + 多厂商 API 适配。**厂商差异全部在本层吃掉**，UI 只认识 `ChatEvent`。

---

## 1. 文件地图

| 文件 | 职责 | 关键点 |
|---|---|---|
| `ChatModels.kt` | 领域模型 + `ChatProvider` 接口 | UI 唯一需要 import 的文件 |
| `ProviderConfig.kt` | 运行时配置（含密钥） | `toString` 防泄漏、`joinUrl`、`redact` |
| `Providers.kt` | 厂商静态目录（`lead` 维护） | 不含密钥，随包分发 |
| `OpenAiStreamParser.kt` | **单帧解析（纯函数）** | 6 个协议坑中的 4 个在这 |
| `OpenAiRequestBuilder.kt` | 请求体构造 | 只发显式给了值的参数 |
| `SseTransport.kt` | SSE → Flow，超时与错误映射 | **坑 1（readTimeout）** |
| `OpenAiCompatibleProvider.kt` | 发请求 + 指数退避 | **坑 6（不自动重连）** |
| `ProviderFactory.kt` | 唯一厂商分发点 | Anthropic / Gemini 预留位 |
| `ModelsApi.kt` | `GET /models`（可选能力） | 失败一律返回 `null` |

**分层原则**：解析是纯函数、传输才碰网络。所以 6 个坑里 4 个能用普通单测钉死，
不需要为了测「一帧怎么解」去起 HTTP 服务器。

---

## 2. 六个协议坑：分别落在哪、怎么防回归

| # | 坑 | 落点 | 防回归测试 |
|---|---|---|---|
| 1 | `readTimeout` 默认 10s 会掐断长连接 | `SseTransport.defaultClientBuilder()` | `默认客户端确实把 readTimeout 设成了不限`、`帧间静默超过默认 10 秒也不掉线` |
| 2 | `[DONE]` 不是 JSON | `OpenAiStreamParser.parseData` 首行 | `DONE 哨兵被识别为 Done 而不是解析异常` |
| 3 | `choices` 可能是空数组 | 解析器里 `choices` 与 `usage` **互相独立**读取 | `空 choices 的 usage 帧不崩且能读出用量` |
| 4 | DeepSeek 的 usage 骑在内容帧上 | 同上（不能"choices 非空就跳过 usage"） | `DeepSeek 形态：usage 骑在最后一个内容帧上`、`DeepSeek usage 骑在内容帧上时两者都要出来` |
| 5 | 推理字段各家不同 | 解析器按存在性读 `reasoning_content` → `reasoning` | `OpenRouter 的 reasoning 字段同样归一化` 等 5 条 |
| 6 | okhttp-sse 不自动重连 | `OpenAiCompatibleProvider.stream` 外层循环 | `首次失败且未吐内容时按策略重试并最终成功` 等 4 条 |

### 坑 1 的机制（值得单独记住）

`RealEventSource.processResponse` 只做这一件事：

```kotlin
// This is a long-lived response. Cancel full-call timeouts.
call?.timeout()?.cancel()
```

它取消的是 **callTimeout**。而读超时挂在 **socket** 上
（`RealConnection`：`source.timeout().timeout(chain.readTimeoutMillis)`），
**库没有动它**。OkHttp 默认 `readTimeout = 10_000` ms
⇒ 推理模型思考超过 10 秒不吐 token 就掉线。

**因此 `readTimeout(0, SECONDS)` 是本层唯一的硬性要求**（0 = 不限，**不是** -1）。
有人"优化"掉这行，`帧间静默超过默认 10 秒也不掉线` 会立刻红。

readTimeout 设成不限之后，若服务端彻底不响，连接会一直挂着 ⇒
用 `StreamTimeouts.firstByteTimeoutMs`（应用层看门狗）兜底。
**一旦开始吐 token 就取消看门狗** —— 推理模型的停顿是正常现象，不能当超时。

---

## 3. 与需求书的三处偏差（都加了东西，说清理由）

需求书给的 `ChatEvent` 有 5 个成员，实现加了 2 项 / 扩展了 1 项：

### 3.1 多了 `ChatEvent.Finish(reason)`

`finish_reason` 的枚举里 **`length` 表示被 max_tokens 截断**，
UI 必须区别于正常 `stop`（否则用户看到半句话以为模型就这水平）。
只靠 `Done` 表达不了这个区别。

### 3.2 `Usage` 多了两个可空字段

`reasoningTokens`（OpenAI `completion_tokens_details.reasoning_tokens`）与
`cachedInput`（OpenAI `cached_tokens` / DeepSeek `prompt_cache_hit_tokens`）。
两家都提供了，扔掉等于白丢信息。**都是可空的**，读不到就是 null。

### 3.3 `ChatRequest` 多了 `extraBody`

厂商私有参数（`thinking`、`enable_search` 等）走这个逃生舱，并入请求体顶层。
**新增一个厂商不需要改 `ChatRequest`**。
受保护键（`model` / `messages` / `stream` / `stream_options`）会被跳过，
防止误覆盖导致流式解析失效。

---

## 4. 重试：为什么「吐过内容就不重试」

`RetryPolicy` 默认 3 次、指数退避、带抖动。

**关键决策**：只在**整通请求还没吐出任何有内容的事件**时才重试。

理由：已经吐过正文再重试，UI 上会出现「半句话 + 从头再来」的重复内容，
比直接报错更糟 —— 宁可让用户手动重发。

连 `Usage` / `Finish` 也算「已产出」，避免重试后 UI 收到两份用量、两次"已结束"。

---

## 5. 错误处理：不抛异常

**`ChatProvider.stream` 永不向调用方抛异常**，所有失败都是 `ChatEvent.Error`。
好处是 UI 的状态机只处理一种形状，不需要 `try/catch` 包 collect。

`retryable` 的判定：

| 情形 | retryable |
|---|---|
| socket / 连接超时、连接重置 | ✅ |
| HTTP 408 / 409 / 425 / 429 / 5xx | ✅ |
| HTTP 400 / 401 / 403 / 404 / 422 | ❌（密钥或参数错了，重试一万次也一样） |
| 证书 / 域名解析失败 | ❌ |

---

## 6. 密钥安全

- `ProviderConfig.toString()` **手写**并遮蔽 `apiKey`
  —— data class 自动生成的版本会把它原样打出来，一次 `log(config)` 就泄漏。
- 所有给用户的错误文本过 `redact(secret)`，防止网关回显请求头时把密钥带进 UI。
- 密钥**只在 `Authorization` 头里**，绝不进请求体
  （有测试 `API 密钥不会出现在请求体里` 守着）。
- 本层**不负责存储**密钥 —— 由设置层加密保存后以参数传入。

---

## 7. `/v1/models`：为什么做成"可选能力"

调研报告 §3 的结论：**把 `/v1/models` 当可选能力，而非必有能力**。

- 智谱：OpenAPI 规范里**根本没有**列举模型的端点
- 阿里百炼：官方只承诺原生 `/api/v1/models`，`compatible-mode/v1/models` 未获背书
- 部分网关对**任意路径**都先返 401，导致"路由存在性探测"失效

所以 `ModelsApi.listModels()` 的契约是：**任何失败都返回 `null`，绝不抛异常**。
`config.supportsModelList == false` 时连请求都不发。
UI 收到 null 就退回「手动填模型名 + 内置常用清单」。

解析只取 `data[].id`，其余字段一律忽略（各家响应是超集）。

---

## 8. 并发：`@Volatile` 为什么没加

`SseTransport` 里的 `terminated` / `sawFirstByte` / `watchdog` 确实会被
**不同线程**访问（OkHttp 的 SSE 回调线程 vs `callbackFlow` 的协程）。

**安全性来自「幂等」，而非「无竞态」**：

- `watchdog?.cancel()` —— 重复调用无害
- `terminated` 双写 —— 只会让终端事件被 `finishWith()` 的 guard 挡掉一次
- 真正的互斥是 `finishWith()` 里的 `if (terminated) return` + `trySend` 的原子性

**不加 `@Volatile` 是刻意的**：加了会让读者以为"这里的安全性重点是可见性"，
而实际重点在幂等设计。加注解反而误导。

> 如果将来有人把 `terminated` 改成"只在某一侧写、另一侧读后做非幂等动作"，
> 那时**必须**加 `@Volatile` 并重新审视。

---

## 9. 怎么用

```kotlin
// 1. 从预置厂商 + 用户填的 key 造 provider
val provider = ProviderFactory.createFromPreset(
    preset = Providers.DeepSeek,
    apiKey = userApiKey,
    model  = userModel,      // 可空，默认用 preset.commonModels.first()
)

// 2. 收集事件
provider.stream(
    ChatRequest(
        model = "deepseek-chat",
        messages = listOf(ChatMessage.system("你是助手"), ChatMessage.user("你好")),
    )
).collect { event ->
    when (event) {
        is ChatEvent.Delta     -> appendToBubble(event.text)
        is ChatEvent.Reasoning -> appendToThinkingBubble(event.text)
        is ChatEvent.Usage     -> recordTokens(event.input, event.output)
        is ChatEvent.Finish    -> markTruncatedIf(event.reason == "length")
        is ChatEvent.Done      -> finishBubble()
        is ChatEvent.Error     -> showError(event.message, canRetry = event.retryable)
    }
}
```

取消：`collect` 所在的协程一取消，`awaitClose` 会调 `EventSource.cancel()`，
底层连接立刻断开。**注意 `cancel()` 是"immediately and violently"的**，
取消后不要再期待剩余增量 —— 已收到的内容调用方需自行保存。

---

## 10. 新增一个厂商要改哪里

**OpenAI 兼容的厂商**：什么都不用改，加一条 `ProviderPreset` 即可
（`Providers.kt`）。

**非 OpenAI 协议（Anthropic / Gemini）**：
1. 写一个 `ChatProvider` 实现（它们请求体、鉴权头、事件结构都不同）；
2. 在 `ProviderFactory.create` 的 `when` 里加一个分支；
3. `ProviderFactory.isImplemented()` 会自动让 UI 把选项置灰。

调用方代码**一行都不用改** —— 这就是"接口别写死"的落点。

---

## 11. 测试

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd <仓库目录>
& "<gradle>" :app:testDebugUnitTest --tests "com.chatmobile.net.*" --no-daemon
```

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `OpenAiStreamParserTest` | 26 | 6 个坑的纯函数层面 + 容错 + 脱敏 |
| `ChatProviderStreamTest` | 23 | MockWebServer 真 HTTP 端到端 + 重试 + 超时 |
| `ModelsApiTest` | 11 | 可选能力的"失败返回 null"契约 |

**共 60 条，全部通过。**

用 `mockwebserver3.*`（OkHttp 5 新 API）。
`build.gradle.kts` 里声明的是 `com.squareup.okhttp3:mockwebserver`，
那是**弃用桥接层**，其 POM 传递依赖了 `mockwebserver3:5.5.1`，
所以新 API 直接可用，**不需要额外加依赖**。

---

## 12. 已知限制

1. **Anthropic / Gemini 未实现**。`ProviderKind` 有枚举值，
   `ProviderFactory.create` 会抛 `UnsupportedOperationException` 并给出实现指引。
   **故意抛异常而不是静默降级** —— 否则 UI 以为在用 Anthropic，实际走了 OpenAI 协议。
2. **不做自动续写**。断流后不尝试"从半句话接着生成"，
   因为多数 OpenAI 兼容接口不支持这种续写语义。
3. **不解析 `tool_calls`**。`finish_reason == "tool_calls"` 会被透传，
   但增量里的工具调用结构没有建模（MVP 无工具）。
4. **`interTokenTimeoutMs` 未实现**。`StreamTimeouts` 里留了字段但当前未生效 ——
   默认就是"不限"，而这正是坑 1 的正解。要加需要更细的读超时控制。
5. **多模态 `image_url` 未建模**。`ChatMessage.content` 是 `String`，
   发图片需要改成内容块数组，属于后续迭代。

---

## 13. 踩过的坑（给后续维护者）

**SSE 的 `data:` 只覆盖同一行。** 写测试时如果把多行 JSON 塞进一个 `data:` 帧，
第二行会被当成未知字段名丢弃，你得到的是半截 JSON
—— 报错会看起来像"解析器有 bug"，实际是测试数据写错了。

**`onResponseBody(CloseSocket())` 在写 body 之前触发。**
用它模拟"说到一半断流"是错的：一个字节都发不出去。
正确做法是让响应正常结束但**不发 `[DONE]`、不带 `finish_reason`**
（我们的 `sawFinish` 判据正好把这判成截断）。
