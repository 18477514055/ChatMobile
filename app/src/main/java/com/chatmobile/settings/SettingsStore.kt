package com.chatmobile.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatmobile.net.ModelCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户配置 —— **多份模型配置 + 当前选中的那一份**（仿 DSH 的形态）。
 *
 * ## 为什么从"单份"改成"列表"
 *
 * 第一版只有一份配置（providerId / endpoint / key / model 四个 key）。
 * 用户实测后提出：*「模型配置内部应该展示已连接的模型，点击后展开详细配置，
 * 最下面用添加模型按键追加模型」*。
 *
 * 这是**数据结构变更**，不是界面改动 —— 必须连带做迁移，见 [SettingsStore.load]。
 *
 * ## ★ 密钥存在哪 —— 这是一个必须想清楚的问题
 *
 * 用 DataStore Preferences（明文，落在 App 私有目录
 * `data/data/com.chatmobile.debug/files/datastore/` 下的 `preferences_pb` 文件）。
 *
 * **它的安全边界（如实说明，不夸大）**：
 * - ✅ 其他 App 读不到（Android 沙箱 + 私有目录权限）
 * - ✅ 未 root 的设备上，普通用户也读不到
 * - ❌ **root 设备上可读**（用户另一台机器是服务器改的）
 * - ❌ **adb backup / 云备份** 如果开着，可能把文件带出去
 * - ❌ 设备被取证工具刷镜像时可读
 *
 * ⇒ **够用于"防同机其他 App 和路人"，不够用于"防物理接触的攻击者"。**
 *
 * 下一步该上的：Keystore 托管密钥 + AES-GCM。之所以先不做：security-crypto
 * 长期 alpha，且要处理"换机 / 恢复出厂后 Keystore 失效如何恢复" ——
 * 那是独立课题，不适合和"跑通模型"混在一刀里。
 *
 * ★ 无论存在哪，**这个类不允许把 key 打进日志**。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        /** 全部配置的 JSON 数组（见 [encodeProfiles]） */
        val PROFILES = stringPreferencesKey("profiles_json")

        /** 当前使用的配置 id */
        val ACTIVE_ID = stringPreferencesKey("active_id")

        /**
         * 记忆总开关（对应 LibreChat 的 "Use memory"）。
         *
         * ★ 关掉后：不注入记忆、不做抽取、不给记忆工具；**条目一条不删**，
         *   重新打开立刻恢复 —— 这是"信任"的最低成本实现。
         * ★ 默认 true（老用户升级上来自动开启），但用户随时能关。
         */
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")

        /**
         * 记忆专用模型的配置（JSON，见 [MemoryModelConfig]）。
         *
         * - `MEMORY_CURATOR`：记忆管理员（抽取 / 整理）用哪个模型
         * - `MEMORY_EMBEDDING`：向量模型（embedding）用哪个
         *
         * ★ 两者互相独立：用户可以"整理用贵模型、向量用便宜模型"，反之亦然。
         */
        val MEMORY_CURATOR = stringPreferencesKey("memory_curator_model")
        val MEMORY_EMBEDDING = stringPreferencesKey("memory_embedding_model")

        /**
         * 用户自己写的系统提示词（人设 / 规矩 / 口吻）。
         *
         * ★ 与记忆的**本质区别**：记忆是"模型自己观察到的你"，提示词是"你要求它成为什么"。
         *   所以两者分开存、分开注入、分开管理 —— 不能混进记忆库，否则一次"整理记忆"
         *   就可能把你写的人设合并掉。
         */
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")

        /**
         * 记忆库可调参数的**稀疏覆盖**（见 [MemoryTuning]）。
         *
         * ★ 只存用户改过的项；没改的回落 `MemoryConfig()` 默认值。
         *   这样以后增删参数不会读崩老存档。
         */
        val MEMORY_TUNING = stringPreferencesKey("memory_tuning")

        /**
         * 工具的启用状态 —— `name|0/1|name|0/1…` 的扁平串。
         *
         * ★ 为什么不用 JSON：`org.json` 在 JVM 单测里是空 stub（本项目已栽过），
         *   而这是个纯字符串映射，扁平串足够且编解码不会有歧义。
         * ★ 为什么不是「每工具一个 key」：工具会增加，每个都加一个 Preferences key
         *   意味着每加一个工具就要动一次存储层。扁 flat 串只需读一次。
         * ★ **未出现在串里的工具按默认值处理** —— 这样新增工具时老用户不用迁移。
         */
        val TOOLS_ENABLED = stringPreferencesKey("tools_enabled")

        /**
         * 工具结果回填的最大字符数。**用户可调**。
         *
         * ★ 为什么做成设置项而不是常量：上下文窗口大小因模型而异。
         *   1M 窗口的模型可以吃 20000 字的网页，8K 窗口的模型吃 8000 就会爆。
         *   写死一个数字必然对某一半用户是错的。
         */
        val TOOL_RESULT_CHARS = stringPreferencesKey("tool_result_chars")

        /**
         * 思考过程的展开程度（`ReasoningDisplay.name`）。
         *
         * ★ 为什么做成**全局**设置而不是每条消息各自记：
         *   每条各自开关 ⇒ 一屏里半开半合，翻历史要逐条点。
         *   全局档位一次设定、整屏一致 —— 这是"读起来舒服"的关键。
         */
        val REASONING_DISPLAY = stringPreferencesKey("reasoning_display")

        /**
         * 思考过程**何时自动收起**（`ReasoningAutoCollapse.name`）。
         *
         * ★ 与 [REASONING_DISPLAY] 是两件事：
         *   - REASONING_DISPLAY   回答**过程中**展开多少
         *   - REASONING_AUTO_COLLAPSE  回答**结束后**要不要自动收成一行
         */
        val REASONING_AUTO_COLLAPSE = stringPreferencesKey("reasoning_auto_collapse")

        /**
         * 界面主题模式（`ThemeMode.name`：Light / Dark / System）。
         *
         * ★ 存字符串而不是布尔：**"跟随系统"是第三种状态**，不是"深色=false"。
         *   布尔只能二选一，用户选了跟随系统、改一次系统设置之后再也回不到自动跟随。
         */
        val THEME_MODE = stringPreferencesKey("theme_mode")

        /**
         * 聊天内容字号（sp）。只影响气泡正文，不影响界面标题与按钮。
         *
         * ★ 为什么只影响聊天内容：界面标题/按钮跟着一起缩，小字号下按钮会小到点不中。
         */
        val CHAT_FONT_SIZE = intPreferencesKey("chat_font_size")

        /**
         * **新会话**默认的工作区权限档位（`WorkspacePermission.name`）。
         *
         * ★ 与 conversation 表上的 `workspacePermission` 是两件事：
         *   - 这里：新建对话时套用的**默认值**
         *   - 表上：每个对话**自己的**档位（可在快捷面板随时改）
         *   混成一个会让用户以为"在通用里改了，现有对话也跟着变" —— 并不会。
         */
        val DEFAULT_PERMISSION = stringPreferencesKey("default_workspace_permission")

        // ---- 旧格式（单份）。**只读不写**，用于一次性迁移 ----
        val LEGACY_PROVIDER_ID = stringPreferencesKey("provider_id")
        val LEGACY_ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val LEGACY_API_KEY = stringPreferencesKey("api_key")
        val LEGACY_MODEL = stringPreferencesKey("model")

        // ★ 生成参数（思考强度 / 上下文上限）**不在这里** ——
        //   它们挂在 `ModelProfile` 上（每份配置一套），随 profiles_json 一起存。
        //   之前这里放过一对全局 key，但全局值换模型必然越界
        //   （从 max 换到只支持 low/high 的模型），已删除。
    }

    /**
     * 当前状态：全部配置 + 当前选中 id。
     *
     * ★ [active] 为 `null` 表示**没有任何可用配置**（首次安装 / 全删了）。
     *   调用方据此区分"没配"和"配了但 key 错"，两者给用户的提示完全不同。
     */
    // ★ 注意：`dataStore` 这个委托就地声明在本文件末尾，是 `private` 的 ——
    //   本类的**所有**成员都必须在同一个文件里用它，不能拆到别处。
    //   （第一版把类挪出去过，报 Unresolved reference 'dataStore'，
    //     因为 private 顶层扩展属性只在本文件可见。）
    val stateFlow: Flow<SettingsState> = context.dataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[Keys.PROFILES])

        // ★ 迁移：旧版只有单份四个 key，没有 profiles_json。
        //   只在 profiles 为空时做，且**不清除旧 key**（万一迁移逻辑有 bug，
        //   用户的数据还在，可以再救一次；代价只是多占几百字节）。
        if (profiles.isEmpty()) {
            val legacyProvider = prefs[Keys.LEGACY_PROVIDER_ID]
            val legacyKey = prefs[Keys.LEGACY_API_KEY]
            if (!legacyProvider.isNullOrBlank() && !legacyKey.isNullOrBlank()) {
                val migrated = ModelProfile(
                    id = LEGACY_MIGRATED_ID,
                    providerId = legacyProvider,
                    endpointUrl = prefs[Keys.LEGACY_ENDPOINT_URL].orEmpty(),
                    apiKey = legacyKey,
                    model = prefs[Keys.LEGACY_MODEL].orEmpty(),
                    label = "从旧版迁移",
                )
                return@map SettingsState(listOf(migrated), migrated.id, migrated, prefs[Keys.MEMORY_ENABLED] ?: true)
            }
            return@map SettingsState(emptyList(), null, null, prefs[Keys.MEMORY_ENABLED] ?: true)
        }

        val activeId = prefs[Keys.ACTIVE_ID]
        val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
        SettingsState(
            profiles = profiles,
            activeId = active.id,
            active = active,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            curatorModel = MemoryModelConfig.decode(prefs[Keys.MEMORY_CURATOR]),
            // ★ 向量模型默认「未配置」而不是「跟随对话」：聊天模型绝大多数不支持 /embeddings，
            //   默认跟随会让面板一进来就显示一个用不了的配置。
            // ★ 读取时再净化一次：已经存进去的"聊天模型"（如上版误存的 deepseek）
            //   会被归一成未配置，不会继续显示成一个必然失败的向量模型。
            embeddingModel = MemoryModelConfig.sanitizeEmbeddingConfig(
                prefs[Keys.MEMORY_EMBEDDING]?.let { MemoryModelConfig.decode(it) }
                    ?: MemoryModelConfig(MemoryModelConfig.SOURCE_PROFILE),
                profiles,
            ),
            customPrompt = prefs[Keys.CUSTOM_PROMPT].orEmpty(),
            memoryTuning = MemoryTuning.decode(prefs[Keys.MEMORY_TUNING]),
            reasoningDisplay = com.chatmobile.ui.chat.ReasoningDisplay.fromName(
                prefs[Keys.REASONING_DISPLAY],
            ),
            reasoningAutoCollapse = com.chatmobile.ui.chat.ReasoningAutoCollapse.fromName(
                prefs[Keys.REASONING_AUTO_COLLAPSE],
            ),
            themeMode = ThemeMode.fromName(prefs[Keys.THEME_MODE]),
            chatFontSize = (prefs[Keys.CHAT_FONT_SIZE] ?: DEFAULT_CHAT_FONT_SP)
                .coerceIn(MIN_FONT_SP, MAX_FONT_SP),
            defaultPermission = com.chatmobile.workspace.WorkspacePermission.fromName(
                prefs[Keys.DEFAULT_PERMISSION],
            ),
        )
    }

    suspend fun load(): SettingsState = stateFlow.first()

    // ---------------------------------------------------------------
    // 记忆总开关（对应 LibreChat "Use memory"）
    // ---------------------------------------------------------------

    /** 记忆是否启用。默认 true；读 DataStore（命中缓存，微秒级）。 */
    val memoryEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MEMORY_ENABLED] ?: true
    }

    suspend fun isMemoryEnabled(): Boolean = memoryEnabledFlow.first()

    /**
     * 关掉后：不注入记忆、不抽取、不给记忆工具；**条目一条不删**。
     * 重新打开立刻恢复 —— 这是"用户信任"的最低成本实现。
     */
    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.MEMORY_ENABLED] = enabled }
    }

    // ---------------------------------------------------------------
    // 记忆专用模型（管理员 / 向量）
    // ---------------------------------------------------------------

    val curatorModelFlow: Flow<MemoryModelConfig> = context.dataStore.data.map { prefs ->
        MemoryModelConfig.decode(prefs[Keys.MEMORY_CURATOR])
    }

    val embeddingModelFlow: Flow<MemoryModelConfig> = context.dataStore.data.map { prefs ->
        MemoryModelConfig.decode(prefs[Keys.MEMORY_EMBEDDING])
    }

    suspend fun setCuratorModel(config: MemoryModelConfig) {
        context.dataStore.edit { prefs -> prefs[Keys.MEMORY_CURATOR] = config.encode() }
    }

    suspend fun setEmbeddingModel(config: MemoryModelConfig) {
        context.dataStore.edit { prefs -> prefs[Keys.MEMORY_EMBEDDING] = config.encode() }
    }

    /** 用户自写提示词（人设等）。空串 = 不注入。 */
    val customPromptFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_PROMPT].orEmpty()
    }

    suspend fun setCustomPrompt(text: String) {
        context.dataStore.edit { prefs -> prefs[Keys.CUSTOM_PROMPT] = text }
    }

    /** 记忆库参数。传 `MemoryTuning()` 即恢复全默认。 */
    val memoryTuningFlow: Flow<MemoryTuning> = context.dataStore.data.map { prefs ->
        MemoryTuning.decode(prefs[Keys.MEMORY_TUNING])
    }

    suspend fun setMemoryTuning(tuning: MemoryTuning) {
        context.dataStore.edit { prefs -> prefs[Keys.MEMORY_TUNING] = tuning.encode() }
    }

    // ---------------------------------------------------------------
    // 工具配置
    // ---------------------------------------------------------------

    /**
     * 当前**启用的工具名集合**。
     *
     * ★ 解码规则：串里出现过的以串为准；**没出现过的按 [ChatTools] 的默认值**。
     *   这条让"新增工具"不需要数据迁移 —— 老用户的串里没有新工具，
     *   于是新工具自动按它的 `enabledByDefault` 生效。
     * ★ 未知名字（已被删除的工具）会被忽略 —— 它们不在 `ChatTools.all()` 里，
     *   上层过滤时自然不会带上，不需要在这里清理。
     */
    suspend fun enabledToolNames(): Set<String> {
        val raw = context.dataStore.data.map { prefs -> prefs[Keys.TOOLS_ENABLED].orEmpty() }.first()
        val stored = parseToolFlags(raw)
        return chatmobile.tools.ChatTools.all()
            .filter { def -> stored[def.name] ?: def.enabledByDefault }
            .map { it.name }
            .toSet()
    }

    /** 设置页用：每个工具当前的开关状态（含默认值兜底）。 */
    suspend fun toolEnabledMap(): Map<String, Boolean> {
        val raw = context.dataStore.data.map { prefs -> prefs[Keys.TOOLS_ENABLED].orEmpty() }.first()
        val stored = parseToolFlags(raw)
        return chatmobile.tools.ChatTools.all()
            .associate { def -> def.name to (stored[def.name] ?: def.enabledByDefault) }
    }

    /** 只改一个工具的开关，其余保持不变（把当前完整状态写回去）。 */
    suspend fun setToolEnabled(name: String, enabled: Boolean) {
        val current = toolEnabledMap().toMutableMap()
        current[name] = enabled
        context.dataStore.edit { prefs ->
            prefs[Keys.TOOLS_ENABLED] = encodeToolFlags(current)
        }
    }

    /**
     * 工具结果回填上限（字符）。
     *
     * ★ 钳在 1000..20000：太小模型的工具等于没用（读半页），太大撑爆小窗口模型。
     * ★ 默认 8000 —— 与 `ChatToolExecutor` 的默认值一致。
     */
    suspend fun toolResultChars(): Int {
        val raw = context.dataStore.data.map { prefs -> prefs[Keys.TOOL_RESULT_CHARS].orEmpty() }.first()
        return raw.toIntOrNull()?.coerceIn(1000, 20000) ?: 8000
    }

    suspend fun setToolResultChars(chars: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOOL_RESULT_CHARS] = chars.coerceIn(1000, 20000).toString()
        }
    }

    /** `a|1|b|0` ⇒ `{a:true,b:false}`。坏数据（奇数段/非 0-1）一律跳过，不让它整串失效。 */
    private fun parseToolFlags(raw: String): Map<String, Boolean> {
        if (raw.isBlank()) return emptyMap()
        val parts = raw.split('|')
        val out = mutableMapOf<String, Boolean>()
        var i = 0
        while (i + 1 < parts.size) {
            val name = parts[i].trim()
            val flag = parts[i + 1].trim()
            if (name.isNotEmpty() && (flag == "1" || flag == "0")) {
                out[name] = flag == "1"
            }
            i += 2
        }
        return out
    }

    /** 按 [ChatTools.all] 的**固定顺序**编码 —— 让这一串也逐字节稳定。 */
    private fun encodeToolFlags(map: Map<String, Boolean>): String =
        chatmobile.tools.ChatTools.all()
            .joinToString("|") { def -> def.name + "|" + if (map[def.name] == true) "1" else "0" }

    // ---------------------------------------------------------------
    // 思考过程的展开程度
    // ---------------------------------------------------------------

    /** 当前档位。非法/未设置 ⇒ 默认（标准）。 */
    suspend fun reasoningDisplay(): com.chatmobile.ui.chat.ReasoningDisplay =
        com.chatmobile.ui.chat.ReasoningDisplay.fromName(
            context.dataStore.data
                .map { prefs -> prefs[Keys.REASONING_DISPLAY] }
                .first(),
        )

    suspend fun setReasoningDisplay(display: com.chatmobile.ui.chat.ReasoningDisplay) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REASONING_DISPLAY] = display.name
        }
    }

    // ---------------------------------------------------------------
    // 思考过程的自动收起时机
    // ---------------------------------------------------------------

    /** 当前「收起时机」。非法/未设置 ⇒ 默认（回答结束后）。 */
    suspend fun reasoningAutoCollapse(): com.chatmobile.ui.chat.ReasoningAutoCollapse =
        com.chatmobile.ui.chat.ReasoningAutoCollapse.fromName(
            context.dataStore.data
                .map { prefs -> prefs[Keys.REASONING_AUTO_COLLAPSE] }
                .first(),
        )

    suspend fun setReasoningAutoCollapse(
        mode: com.chatmobile.ui.chat.ReasoningAutoCollapse,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REASONING_AUTO_COLLAPSE] = mode.name
        }
    }

    // ---------------------------------------------------------------
    // 外观与字号
    // ---------------------------------------------------------------

    /** 界面主题模式。未设置 ⇒ 跟随系统。 */
    suspend fun themeMode(): ThemeMode =
        ThemeMode.fromName(context.dataStore.data.map { it[Keys.THEME_MODE] }.first())

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    /**
     * 聊天内容字号（sp）。未设置 / 越界 ⇒ [DEFAULT_CHAT_FONT_SP]。
     *
     * ★ 读的时候也钳一次：老存档里若是被人手改过超大值，
     *   直接拿来排版会让一条消息占满整屏。
     */
    suspend fun chatFontSize(): Int {
        val raw = context.dataStore.data.map { it[Keys.CHAT_FONT_SIZE] }.first()
            ?: DEFAULT_CHAT_FONT_SP
        return raw.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    }

    suspend fun setChatFontSize(sp: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CHAT_FONT_SIZE] = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        }
    }

    // ---------------------------------------------------------------
    // 新会话的默认权限档位
    // ---------------------------------------------------------------

    /** 新会话默认档位。未设置 ⇒ `WorkspacePermission.DEFAULT`。 */
    suspend fun defaultPermission(): com.chatmobile.workspace.WorkspacePermission =
        com.chatmobile.workspace.WorkspacePermission.fromName(
            context.dataStore.data.map { it[Keys.DEFAULT_PERMISSION] }.first(),
        )

    suspend fun setDefaultPermission(
        p: com.chatmobile.workspace.WorkspacePermission,
    ) {
        context.dataStore.edit { prefs -> prefs[Keys.DEFAULT_PERMISSION] = p.name }
    }

    suspend fun memoryModels(): Pair<MemoryModelConfig, MemoryModelConfig> =
        MemoryModelConfig.decode(context.dataStore.data.first()[Keys.MEMORY_CURATOR]) to
            MemoryModelConfig.decode(context.dataStore.data.first()[Keys.MEMORY_EMBEDDING])


    /** 新增或更新一份配置。 */
    suspend fun upsert(profile: ModelProfile, makeActive: Boolean = true) {
        context.dataStore.edit { prefs ->
            val current = decodeProfiles(prefs[Keys.PROFILES]).toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            prefs[Keys.PROFILES] = encodeProfiles(current)
            if (makeActive || prefs[Keys.ACTIVE_ID].isNullOrBlank()) {
                prefs[Keys.ACTIVE_ID] = profile.id
            }
        }
    }

    suspend fun setActive(id: String) {
        context.dataStore.edit { prefs ->
            val current = decodeProfiles(prefs[Keys.PROFILES])
            if (current.any { it.id == id }) prefs[Keys.ACTIVE_ID] = id
        }
    }

    /**
     * 改某份配置的思考强度。
     *
     * ★ 空串 = 不传该字段。**不校验档位合法性** —— 上游可能随时加档位，
     *   在本地写死白名单反而会把合法值挡掉；合法性由服务端裁决。
     */
    suspend fun setReasoningEffort(profileId: String, effort: String) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[Keys.PROFILES]).toMutableList()
            val idx = list.indexOfFirst { it.id == profileId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(reasoningEffort = effort)
                prefs[Keys.PROFILES] = encodeProfiles(list)
            }
        }
    }

    /**
     * 改某份配置的上下文上限（用户自己填的值）。
     *
     * ★★★ **不做任何钳制** —— 见 [ModelProfile.contextLimitOverride]。
     *   传 0 或不合法值 = 清空（回退到默认预算）。
     */
    suspend fun setContextLimitOverride(profileId: String, tokens: Int) {
        context.dataStore.edit { prefs ->
            val list = decodeProfiles(prefs[Keys.PROFILES]).toMutableList()
            val idx = list.indexOfFirst { it.id == profileId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(contextLimitOverride = tokens.coerceAtLeast(0))
                prefs[Keys.PROFILES] = encodeProfiles(list)
            }
        }
    }

    /**
     * 删除一份配置。
     *
     * ★ 如果删的是当前选中的那份，自动切到剩下的第一份；
     *   全删光后 `activeId` 置空（调用方据此显示"去配置"）。
     */
    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val left = decodeProfiles(prefs[Keys.PROFILES]).filterNot { it.id == id }
            prefs[Keys.PROFILES] = encodeProfiles(left)
            val active = prefs[Keys.ACTIVE_ID]
            if (active == id || active == null || left.none { it.id == active }) {
                if (left.isEmpty()) {
                    prefs.remove(Keys.ACTIVE_ID)
                } else {
                    prefs[Keys.ACTIVE_ID] = left.first().id
                }
            }
        }
    }

    /** 清空全部（调试用；UI 不暴露）。 */
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    // ------------------------------------------------------------------
    // JSON 编解码
    //
    // 用 org.json 而不是 kotlinx.serialization：本类只有一个很扁的结构，
    // 引入序列化插件（还要加 @Serializable 与 ksp 处理）收益不成比例。
    // org.json 是 Android 平台自带的，零额外依赖。
    // ------------------------------------------------------------------

    private fun decodeProfiles(raw: String?): List<ModelProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val providerId = o.optString("providerId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ModelProfile(
                    id = id,
                    providerId = providerId,
                    endpointUrl = o.optString("endpointUrl", ""),
                    apiKey = o.optString("apiKey", ""),
                    model = o.optString("model", ""),
                    models = o.optJSONArray("models")?.let { a ->
                        (0 until a.length()).mapNotNull { k ->
                            a.optString(k).takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList(),
                    capabilities = o.optJSONObject("capabilities")?.let { caps ->
                        buildMap {
                            caps.keys().forEach { key ->
                                val c = caps.optJSONObject(key) ?: return@forEach
                                put(
                                    key,
                                    ModelCapability(
                                        contextLimit = c.optInt("ctx", -1).takeIf { it > 0 },
                                        reasoningSupported = if (c.has("reason")) c.optBoolean("reason") else null,
                                        effortLevels = c.optJSONArray("efforts")?.let { e ->
                                            (0 until e.length()).mapNotNull { i ->
                                                e.optString(i).takeIf { it.isNotBlank() }
                                            }
                                        } ?: emptyList(),
                                    ),
                                )
                            }
                        }
                    } ?: emptyMap(),
                    reasoningEffort = o.optString("reasoningEffort", ""),
                    contextLimitOverride = o.optInt("ctxOverride", 0),
                    label = o.optString("label", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeProfiles(list: List<ModelProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("providerId", p.providerId)
                    put("endpointUrl", p.endpointUrl)
                    put("apiKey", p.apiKey)
                    put("model", p.model)
                    put("models", JSONArray(p.effectiveModels))
                    // 能力档案：字段名压缩成短名（ctx/reason/efforts），
                    // 省 DataStore 体积 —— 78 个模型各带一份，长字段名会明显变大
                    put(
                        "capabilities",
                        JSONObject().apply {
                            p.capabilities.forEach { (modelId, c) ->
                                put(
                                    modelId,
                                    JSONObject().apply {
                                        c.contextLimit?.let { put("ctx", it) }
                                        c.reasoningSupported?.let { put("reason", it) }
                                        if (c.effortLevels.isNotEmpty()) {
                                            put("efforts", JSONArray(c.effortLevels))
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put("reasoningEffort", p.reasoningEffort)
                    if (p.contextLimitOverride > 0) put("ctxOverride", p.contextLimitOverride)
                    put("label", p.label)
                },
            )
        }
        return arr.toString()
    }

    companion object {
        /** 旧版单份配置迁移后的固定 id —— 稳定，便于反复迁移不产生重复项。 */
        const val LEGACY_MIGRATED_ID = "legacy-migrated"

        /** 新建一份配置时用的 id。 */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 一份模型配置。
 *
 * ★ [toString] 手写并脱敏 —— data class 自动生成的版本会把 apiKey 原样打出来，
 *   一旦有人 `log(profile)` 或它进了异常堆栈，密钥就进了日志。
 */
data class ModelProfile(
    val id: String,
    val providerId: String,
    /** 空 = 用该厂商的默认接入点 */
    val endpointUrl: String = "",
    val apiKey: String = "",
    /**
     * **当前选中的模型**。
     *
     * ★ 与 [models] 的分工（用户 23:25 要求「同一家提供商的多个模型」）：
     *   - [models] = 这份配置下**收藏的模型清单**（0..N 个）
     *   - [model]  = 聊天时**实际用的那一个**，必须是 [models] 里的成员（或为空）
     *
     *   旧配置里 [models] 为空、[model] 有值 ⇒ 读取时自动把 [model] 并进清单，
     *   不需要迁移脚本，也不会丢数据。
     */
    val model: String = "",
    /** 该配置下可选的模型清单。空 = 尚未添加任何模型。 */
    val models: List<String> = emptyList(),
    /**
     * 从上游 `/models` 读到的能力档案（`模型id -> 档案`）。
     *
     * ★ 为什么要**存下来**而不是每次现拉：换模型弹窗要立刻显示
     *   "这个模型支持哪些思考档位 / 上下文多大"，不能等一次网络往返。
     *   拉取模型时顺带写入，之后一直可用。
     *
     * ★ 空 map = 该厂商没提供这些信息 ⇒ UI 隐藏这两项（**不猜**）。
     */
    val capabilities: Map<String, ModelCapability> = emptyMap(),
    /**
     * 当前模型使用的思考强度（`reasoning_effort` 的取值）。空 = 不传该字段。
     *
     * ★ 它是**每份配置一个**而不是全局：不同模型的档位不同，
     *   全局存一个值换模型后必然越界（比如从 `max` 换到只支持 `low/high` 的模型）。
     *   上游声明了哪些档位就只能在哪些里选（见 [ModelCapability.effortLevels]）。
     */
    val reasoningEffort: String = "",
    /**
     * 用户**自己设定**的上下文上限（token）。0 = 没设。
     *
     * ★★★ 用户原话：「上下文上限应该允许用户任意调整，调整到厂家允许的真正上限。
     *   不要按照默认上限，那个只是厂家默认，不是真正的上限。」
     *
     *   ⇒ **这不是"钳制后的值"，是用户的原始意图**。
     *     上游声明的 `limit.context`（见 [ModelCapability.contextLimit]）只是
     *     **输入框里的提示**，永远不覆盖用户填的数字。
     *
     * ★ 它按配置存（不是全局）：不同模型的窗口不同，全局存一个值换模型必然错。
     */
    val contextLimitOverride: Int = 0,
    /** 给用户看的自定义名字，空则显示"厂商 · 模型" */
    val label: String = "",
) {
    /** 合并后的实际可用清单：`models` 去重，且把 `model` 也包进来（兼容旧配置）。 */
    val effectiveModels: List<String>
        get() {
            val base = models.filter { it.isNotBlank() }
            return if (model.isNotBlank() && model !in base) base + model else base
        }

    val isComplete: Boolean
        get() = providerId.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 列表里显示的名字。 */
    fun displayName(providerName: String): String =
        label.ifBlank { providerName + " · " + model.ifBlank { "未选模型" } }

    override fun toString(): String = buildString {
        append("ModelProfile(id=").append(id)
        append(", providerId=").append(providerId)
        append(", endpoint=").append(endpointUrl.ifEmpty { "<default>" })
        append(", model=").append(model.ifEmpty { "<default>" })
        append(", apiKey=")
        append(if (apiKey.isEmpty()) "<empty>" else "***(len=${apiKey.length})")
        append(")")
    }
}

/**
 * 聊天内容字号的默认值（sp）。
 *
 * ★ 16 与本项目现有正文的观感一致（`bodyLarge` ≈ 16sp），
 *   所以首次进入「通用」时用户看到的就是"当前实际效果"，不是先被改了一档。
 */
const val DEFAULT_CHAT_FONT_SP: Int = 16

/** 设置页需要的完整状态。 */
data class SettingsState(
    val profiles: List<ModelProfile> = emptyList(),
    val activeId: String? = null,
    /** 当前选中的那份（等价于 `profiles.firstOrNull { it.id == activeId }`）。 */
    val active: ModelProfile? = null,
    /** 记忆总开关（关掉后不注入、不抽取；条目一条不删） */
    val memoryEnabled: Boolean = true,
    /** 记忆管理员用哪个模型（默认跟随对话模型） */
    val curatorModel: MemoryModelConfig = MemoryModelConfig(),
    /** 向量模型（默认未配置） */
    val embeddingModel: MemoryModelConfig = MemoryModelConfig(MemoryModelConfig.SOURCE_PROFILE),
    /** 用户自写提示词（人设等），紧跟固定提示词之后注入 */
    val customPrompt: String = "",
    /** 记忆库可调参数（稀疏覆盖；空 = 全用默认值） */
    val memoryTuning: MemoryTuning = MemoryTuning(),
    /**
     * 思考过程的展开程度（回答**过程中**）。
     *
     * ★ 放进 [SettingsState] 而不是每次单独 `suspend` 读：
     *   UI 要跟着设置变化立刻重组，走 StateFlow 才有这个效果。
     *   ★★★ 这正是「收不回去」的第一层真因 —— 之前 `reasoningDisplay()`
     *   这个 suspend getter **在整个项目里没有任何调用点**，
     *   所以 `ChatScreen` 永远收到默认值（Standard = 全展开）。
     */
    val reasoningDisplay: com.chatmobile.ui.chat.ReasoningDisplay =
        com.chatmobile.ui.chat.ReasoningDisplay.DEFAULT,
    /** 思考过程**何时自动收起**（回答结束后 / 从不）。 */
    val reasoningAutoCollapse: com.chatmobile.ui.chat.ReasoningAutoCollapse =
        com.chatmobile.ui.chat.ReasoningAutoCollapse.DEFAULT,
    /** 界面主题模式（浅色 / 深色 / 跟随系统）。 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 聊天内容字号（sp）。 */
    val chatFontSize: Int = DEFAULT_CHAT_FONT_SP,
    /** **新会话**默认的工作区权限档位（不影响已有会话）。 */
    val defaultPermission: com.chatmobile.workspace.WorkspacePermission =
        com.chatmobile.workspace.WorkspacePermission.DEFAULT,
)

/**
 * DataStore 委托 —— **必须是文件级 private**。
 *
 * ★ 两个约束，缺一都会出错：
 * 1. **一个进程只能有一个 `preferencesDataStore(name)` 委托**。
 *    同一个 name 声明两次会在运行时抛 `IllegalStateException:
 *    There are multiple DataStores active for the same file`。
 *    （这是 Android 官方文档明确的坑，只会在**第二次读**时才炸，编译期看不出来。）
 * 2. **它必须是 `private` 的**，否则别的文件也能拿到同一个委托的副本 ——
 *    那正是第 1 条被违反的典型路径。代价是：`SettingsStore` 必须和它同文件。
 */
private val Context.dataStore by preferencesDataStore(name = "chat_settings")

