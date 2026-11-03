package com.chatmobile.settings

/**
 * 记忆专用模型的配置（**记忆管理员** 与 **向量模型** 各存一份）。
 *
 * ## 为什么要独立配置，而不是永远跟着对话模型
 *
 * 对话模型是"聊天用的"，记忆整理是"后台批处理"：
 * - 聊天可能挂着贵的大模型，但整理记忆只需要能读懂中文的小模型；
 * - 整理是**长上下文 + 低频**，跟对话的取舍完全不同；
 * - 用户可能想让记忆永远用便宜模型，而聊天用最好的那个。
 *
 * ## 三种取用方式（用户 23:2x 明确要求）
 *
 * 1. [SOURCE_CHAT]    跟随对话模型 —— 零配置，开箱即用（默认）
 * 2. [SOURCE_PROFILE] 复用「模型配置」界面里已经配好的某一份（含 Key/地址/模型）
 * 3. [SOURCE_CUSTOM]  就在这里独立配：地址 + Key + 模型（可「拉取模型」）
 *
 * ★ 三者最终都会被 `resolveMemoryProfile` 解析成同一个 `ModelProfile` ——
 *   这样"记忆模型"不会成为第二套协议实现。
 *
 * ★ 编解码**不用 org.json**：它在本项目的 JVM 单测里是空 stub（方法体全抛 `Stub!`），
 *   用它写的编解码在测试里会静默失败。这里用最朴素的分隔符编码，零依赖、可完整单测。
 */
data class MemoryModelConfig(
    val source: String = SOURCE_CHAT,
    val profileId: String = "",
    val providerId: String = "custom",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = when (source) {
            SOURCE_CHAT -> true
            SOURCE_PROFILE -> profileId.isNotBlank()
            SOURCE_CUSTOM -> baseUrl.isNotBlank() && model.isNotBlank()
            SOURCE_BUILTIN -> true
            SOURCE_ONNX -> true
            else -> false
        }

    /** 字段用 RS 分隔；值里不会出现 RS（API Key / URL / 模型名都是可见字符）。 */
    fun encode(): String = listOf(source, profileId, providerId, baseUrl, apiKey, model)
        .joinToString(SEP)

    companion object {
        const val SOURCE_CHAT = "chat"
        const val SOURCE_PROFILE = "profile"
        const val SOURCE_CUSTOM = "custom"

        /** 内置轻量向量（零下载，见 `chatmobile.memory.HashEmbeddingProvider`）。 */
        const val SOURCE_BUILTIN = "builtin"

        /**
         * 内置**真模型**（下载式增强包：bge-small-zh int8 + ONNX Runtime）。
         *
         * ★ 与 [SOURCE_BUILTIN] 的区别：那个是零下载的哈希向量（能立刻用、语义弱），
         *   这个是 22.8MB 的神经模型（语义强、需下载、失败要能降级）。
         */
        const val SOURCE_ONNX = "onnx"

        /**
         * 已知**支持 `/embeddings`** 的提供商。
         *
         * ★ 为什么必须白名单：用户上次在向量模型里选中了 `deepseek-v4.1-flash`，
         *   而 DeepSeek 的聊天端点**根本没有 embeddings** —— 面板显示"已配置"、
         *   一到检索就全是失败。**用聊天模型冒充向量模型是可运行性的 bug**，
         *   不是配置自由。所以这里显式排除，并在读取时把无效配置归一成"未配置"。
         */
        val EMBEDDING_PROVIDERS = setOf(
            "openai", "siliconflow", "mistral", "together", "fireworks", "nvidia",
            "qwen", "zai", "google", "google-vertex", "amazon-bedrock", "azure",
            "ollama", "lmstudio", "cloudflare-workers-ai", "huggingface",
        )

        fun isEmbeddingCapable(providerId: String): Boolean =
            providerId in EMBEDDING_PROVIDERS

        /**
         * 读取时净化向量配置：聊天模型 / 找不到的 profile / 不支持的厂商
         * ⇒ 一律当成"未配置"，而不是留一个看着像能用、其实必然失败的配置。
         */
        fun sanitizeEmbeddingConfig(
            cfg: MemoryModelConfig,
            profiles: List<ModelProfile>,
        ): MemoryModelConfig {
            if (cfg.source == SOURCE_BUILTIN) return cfg
            if (cfg.source == SOURCE_ONNX) return cfg
            if (cfg.source == SOURCE_CUSTOM) {
                return if (cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank()) cfg
                else MemoryModelConfig(SOURCE_PROFILE)
            }
            if (cfg.source != SOURCE_PROFILE) return MemoryModelConfig(SOURCE_PROFILE)
            val p = profiles.firstOrNull { it.id == cfg.profileId }
                ?: return MemoryModelConfig(SOURCE_PROFILE)
            return if (isEmbeddingCapable(p.providerId)) cfg else MemoryModelConfig(SOURCE_PROFILE)
        }

        private const val SEP = "\u001E"

        fun decode(raw: String?): MemoryModelConfig {
            if (raw.isNullOrBlank()) return MemoryModelConfig()
            val parts = raw.split(SEP)
            if (parts.size < 2) return MemoryModelConfig()
            fun at(i: Int, def: String) = parts.getOrNull(i)?.takeIf { it.isNotBlank() } ?: def
            return MemoryModelConfig(
                source = at(0, SOURCE_CHAT),
                profileId = parts.getOrNull(1).orEmpty(),
                providerId = at(2, "custom"),
                baseUrl = parts.getOrNull(3).orEmpty(),
                apiKey = parts.getOrNull(4).orEmpty(),
                model = parts.getOrNull(5).orEmpty(),
            )
        }
    }
}
