package chatmobile.tools

/**
 * **工具目录**（catalog）—— 懒加载机制的纯逻辑核心。
 *
 * ## ★★★ 为什么要有这个东西（用户原话）
 *
 * > 「我们是不是可以考虑先把社区的懒加载机制给做了，不能让大量的工具整天填充上下文。
 *   > 感觉可以维护一个列表，每个工具都只有一句话，简洁说明和一个名字，
 *   > 真正用到的时候，模型调用工具召回它的完整语义和参数」
 *   > 「然后注意用户可以选择哪些工具常驻上下文，哪些工具进行懒加载。」
 *
 * 这就是 Anthropic「Tool Search Tool」/ MCP 动态工具发现那一套的同一件事。
 *
 * ## 为什么必须做（有数据）
 *
 * 工具定义在请求里排在**最前面**（Anthropic 的缓存顺序是 `tools → system → messages`），
 * 所以它每轮都要全量发送，且**每个 token 都按输入价计费**：
 *
 * | 工具数 | 全量暴露（实测/估算） | 目录模式 |
 * |---|---|---|
 * | 10（现状） | ~2062 字符 ≈ 800 token | ~800 字符 ≈ 320 token |
 * | 50 | ~10000 字符 ≈ 4000 token | ~1000 字符 ≈ 400 token |
 * | 200（MCP 铺开后） | ~40000 字符 ≈ 16000 token | ~1200 字符 ≈ 480 token |
 *
 * ⇒ **工具越多，省得越狠**。而现在正是建接口的时候：等 MCP 接进来再改，
 *   所有调用的位置都要动一遍。
 *
 * ## ★★★ 三种模式，不是两种
 *
 * 用户要求「可以选择哪些常驻、哪些懒加载」。但实际上有三种状态，
 * 因为**「关掉」和「懒加载」是两件事**：
 *
 * | 模式 | 在请求里 | 模型能看到 | 典型用途 |
 * |---|---|---|---|
 * | [ToolMode.Resident] | 完整 schema | 全部参数 | 常用工具（时间、记忆、读文件） |
 * | [ToolMode.Lazy] | 只有名字 + 一句话 | 要用时先 `describe_tool` | 大量低频工具 / MCP |
 * | [ToolMode.Disabled] | 完全不出现 | 看不到 | 用户不想给的能力 |
 *
 * ★ 把 Disabled 混进 Lazy 是常见错误 —— 那样模型会"发现"一个用户明确关掉的工具，
 *   然后反复尝试调用，最后只能报"该工具未启用"。
 *
 * ## ★ 默认策略
 *
 * `enabledByDefault = true` 且**内建**的工具默认 Resident（它们是高频的，
 * 让模型每次都多跳一次不值得）；将来 MCP 进来的默认 Lazy。
 * 这条写在 [ToolDefinition] 的 `preferredMode` 上，用户可覆盖。
 */
enum class ToolMode {
    /** 完整 schema 常驻上下文。 */
    Resident,

    /** 只暴露名字 + 一句话；用时先 describe。 */
    Lazy,

    /** 完全不出现 —— 模型看不到，也不会尝试。 */
    Disabled,
    ;

    companion object {
        fun parse(raw: String?): ToolMode? = entries.firstOrNull { it.name.equals(raw, true) }

        /**
         * 从配置串解析。
         *
         * ★ 兼容旧格式：老配置只有 `name:1 / name:0` 两个值（开关）。
         *   `1` ⇒ [Resident]（保持升级前的行为），`0` ⇒ [Disabled]。
         *   **不能让老用户升级后突然所有工具都变成 Lazy** —— 那会让每次调用多一跳。
         *
         * 串编码约定（`SettingsStore` 写入端必须一致）：
         *   `1` = 常驻 / `2` = 懒加载 / `0` = 关闭
         */
        fun fromLegacyFlag(raw: String?): ToolMode? = when (raw?.trim()) {
            "1" -> Resident
            "2" -> Lazy
            "0" -> Disabled
            // 也接受直接写名字（便于手工调试 / 将来迁移）
            else -> parse(raw)
        }
    }
}

/**
 * 构建「发给模型的那份工具表」。
 *
 * ★ 纯函数、零 Android 依赖 —— 因此可以完整单测，包括"哪些进了目录、哪些进了常驻"。
 */
object ToolCatalog {

    /**
     * `describe_tool` 这个元工具的名字。
     *
     * ★ 它**永远 Resident**，且**不占用户配置**：用户关掉了所有工具时它也不该出现
     *   （没有任何可描述的工具，留着它只是浪费 token）。
     */
    const val DESCRIBE_TOOL_NAME: String = "describe_tool"

    /**
     * 一次构建的结果。
     *
     * @param toolsJson 直接发给 `tools` 字段的 JSON 字符串（null = 不发该字段）
     * @param resident 常驻的工具名（完整 schema 已包含在 toolsJson 里）
     * @param lazy 懒加载的工具名（只在描述文字里出现）
     */
    data class Built(
        val toolsJson: String?,
        val resident: List<String>,
        val lazy: List<String>,
    ) {
        val hasAny: Boolean get() = toolsJson != null
    }

    /**
     * 构建。
     *
     * @param definitions 全部**已启用**的工具（调用方先按 [ToolMode] 过滤掉 Disabled）
     * @param modes 名字 → 模式。缺席的按 [ToolDefinition.preferredMode]
     * @param includeDescribeTool 是否加 `describe_tool`（只有存在 Lazy 工具时才需要）
     */
    fun build(
        definitions: List<ToolDefinition>,
        modes: Map<String, ToolMode>,
        includeDescribeTool: Boolean = true,
    ): Built {
        val resident = mutableListOf<ToolDefinition>()
        val lazy = mutableListOf<ToolDefinition>()

        for (def in definitions) {
            val mode = modes[def.name] ?: def.preferredMode
            when (mode) {
                ToolMode.Disabled -> Unit          // 调用方已过滤，这里再防一次
                ToolMode.Resident -> resident += def
                ToolMode.Lazy -> lazy += def
            }
        }

        // ★ 没有懒加载工具时，describe_tool 是纯浪费 —— 它描述的是一张空表。
        val needDescribe = includeDescribeTool && lazy.isNotEmpty()

        val specs = buildList {
            // ★★ 顺序纪律：`describe_tool` **放在最前**。
            //
            //   理由：模型的注意力会略微偏向靠前的工具；而这个元工具是"入口"，
            //   排后面时实测（本项目自己的 DSH 经验）模型更容易想不起来用它。
            //   ★ 它位置固定 ⇒ 前缀稳定，不影响缓存。
            if (needDescribe) add(describeToolSpec(lazy))
            // 常驻工具按**定义顺序**追加（顺序冻结 ⇒ 缓存前缀稳定）
            resident.forEach { add(it.spec) }
        }

        val json = if (specs.isEmpty()) null else serialize(specs)
        return Built(
            toolsJson = json,
            resident = resident.map { it.name },
            lazy = lazy.map { it.name },
        )
    }

    /**
     * 生成 `describe_tool` 的定义。
     *
     * ★★★ 这个工具的 **description 里必须带目录**，否则：
     *   模型只知道"有个 describe_tool"，却不知道**有哪些工具可以描述** ——
     *   它得先盲猜一个名字，猜错再试，往往两三次就放弃了。
     *   把「名字 + 一句话」直接写进 description，模型一次就能选对。
     *
     * ★ 这也是整个机制里**唯一的常驻开销**，所以每条都必须压到**一句话**。
     *
     * @param lazyTools 懒加载工具（顺序即展示顺序，须稳定）
     */
    private fun describeToolSpec(lazyTools: List<ToolDefinition>): ToolSpec {
        // ★ 分组显示（P1-2 的保守版）：**按组聚拢、加小标题，但不筛选**。
        //
        //   为什么不全量平铺：工具一多，一长串 `- name: what` 里模型很难扫。
        //   为什么不做"选组"：那会引入"选错组=死路"的失败模式（报告明确警告）。
        //   折中：结构清楚，但**每个工具名依然全部可见**。
        val catalog = buildString {
            // 按 ToolGroup 的声明顺序输出（顺序固定 ⇒ 缓存前缀稳定）
            val byGroup = lazyTools.groupBy { it.group }
            for (group in ToolGroup.entries) {
                val items = byGroup[group] ?: continue
                if (items.isEmpty()) continue
                append("[").append(group.label).append("]\n")
                for (t in items) {
                    append("- ").append(t.name).append(": ").append(t.oneLiner).append('\n')
                }
            }
        }.trimEnd()

        val desc = buildString {
            append("Look up the full definition (all parameters and usage rules) of tools that are ")
            append("listed but not yet loaded. ")
            append("IMPORTANT: calling this costs one round-trip, so only use it when you are ")
            append("actually going to call those tools. ")
            append("You may ask for SEVERAL tools at once via `names` — always do that when you ")
            append("already know you need more than one, instead of calling this repeatedly. ")
            append("If you do NOT know the exact name, pass `query` with what you want to do and ")
            append("the best matching tools are returned.\n\n")
            append("Available tools (grouped for readability; every group is always reachable):\n")
            append(catalog)
            append("\n\nAfter this call succeeds, the returned tools become callable with their ")
            append("full schemas.")
        }
        return ToolSpec(
            name = DESCRIBE_TOOL_NAME,
            description = desc,
            parameters = ToolParameters(
                properties = mapOf(
                    // ★★★ `names` 是**首选**，`name` 只为向后兼容保留。
                    //
                    //   为什么必须做批量：官方两家都默认一次返回多个（Anthropic 默认 5 个、
                    //   OpenAI 可整批加载 namespace），而我们原来一次只返回 1 个 ——
                    //   模型要查 3 个工具就多 2 次往返。移动网络下每次往返几百毫秒起，
                    //   而且每次都意味着一次完整的模型调用（真金白银）。
                    "names" to ParamSpec(
                        ParamType.ARRAY,
                        "One or more exact tool names from the list above. Prefer this over " +
                            "`name` when you need more than one tool.",
                        items = ParamType.STRING,
                    ),
                    "name" to ParamSpec(
                        ParamType.STRING,
                        "A single exact tool name from the list above. Kept for compatibility; " +
                            "use `names` when you need several.",
                    ),
                    // ★ 名字想不起来时的出路。工具上百后目录会变成噪音，
                    //   精确点名就成了对模型记忆力的考验 —— 检索比罗列可靠。
                    "query" to ParamSpec(
                        ParamType.STRING,
                        "What you want to do, in your own words. Returns the closest matching " +
                            "tools. Use this when you are not sure of the exact name.",
                    ),
                ),
                // ★ 都不设必填，由执行器校验「至少给一个」——
                //   设成 required 会逼模型在只用一个时也得传数组，白白多写一层。
                required = emptyList(),
            ),
        )
    }

    /**
     * 序列化成 OpenAI `tools` 数组。
     *
     * ★ 复用 [ToolRegistry.toOpenAiToolsJson] 的同一套写法（不另写一份）——
     *   本项目已经因为"两处各写一份"踩过一次（`L26`：设置存了≠生效）。
     */
    private fun serialize(specs: List<ToolSpec>): String {
        val reg = ToolRegistry()
        for (spec in specs) {
            // 执行器不参与序列化，给个占位实现
            reg.register(spec, NoopExecutor)
        }
        return reg.toOpenAiToolsJson()
    }

    /** 仅用于序列化的占位执行器。 */
    private object NoopExecutor : ToolExecutor {
        override fun execute(call: ToolCall, workingDir: String?): ToolResult =
            ToolResult.Failure(call.callId, "not executable")
    }
}
