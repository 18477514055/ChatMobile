package com.chatmobile

import com.chatmobile.settings.MemoryModelConfig
import com.chatmobile.settings.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆专用模型配置：编解码与三种来源的解析。 */
class MemoryModelConfigTest {

    private fun profile(id: String, provider: String = "opencode", model: String = "m") =
        ModelProfile(id = id, providerId = provider, model = model)

    @Test
    fun `编解码往返不丢字段`() {
        val cfg = MemoryModelConfig(
            source = MemoryModelConfig.SOURCE_CUSTOM,
            profileId = "p1",
            providerId = "openai",
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "gpt-x",
        )
        assertEquals(cfg, MemoryModelConfig.decode(cfg.encode()))
    }

    @Test
    fun `空串解码为默认配置`() {
        assertEquals(MemoryModelConfig(), MemoryModelConfig.decode(null))
        assertEquals(MemoryModelConfig(), MemoryModelConfig.decode(""))
        assertEquals(MemoryModelConfig(), MemoryModelConfig.decode("not json"))
    }

    @Test
    fun `跟随对话时解析成当前对话模型`() {
        val active = profile("a", model = "chat-model")
        val r = resolveMemoryProfile(MemoryModelConfig(), listOf(active), active)
        assertEquals("chat-model", r?.model)
    }

    @Test
    fun `复用已配置模型时解析成那一份`() {
        val p1 = profile("p1", model = "cheap")
        val r = resolveMemoryProfile(
            MemoryModelConfig(source = MemoryModelConfig.SOURCE_PROFILE, profileId = "p1"),
            listOf(profile("p0", model = "big"), p1),
            profile("p0"),
        )
        assertEquals("cheap", r?.model)
    }

    @Test
    fun `独立配置时用自己填的地址与模型`() {
        val r = resolveMemoryProfile(
            MemoryModelConfig(
                source = MemoryModelConfig.SOURCE_CUSTOM,
                baseUrl = "https://x/v1",
                apiKey = "k",
                model = "mini",
            ),
            emptyList(),
            null,
        )
        assertNotNull(r)
        assertEquals("mini", r?.model)
        assertEquals("https://x/v1", r?.endpointUrl)
    }

    @Test
    fun `独立配置不完整时返回 null 以便回退`() {
        assertNull(
            resolveMemoryProfile(
                MemoryModelConfig(source = MemoryModelConfig.SOURCE_CUSTOM, model = "mini"),
                emptyList(),
                null,
            ),
        )
        assertNull(
            resolveMemoryProfile(
                MemoryModelConfig(source = MemoryModelConfig.SOURCE_PROFILE, profileId = "nope"),
                emptyList(),
                null,
            ),
        )
    }

    @Test
    fun `内置轻量向量始终可用`() {
        val cfg = MemoryModelConfig(source = MemoryModelConfig.SOURCE_BUILTIN)
        assertTrue(cfg.isConfigured)
        assertEquals(cfg, MemoryModelConfig.sanitizeEmbeddingConfig(cfg, emptyList()))
    }

    @Test
    fun `向量配置指向聊天模型时归一成未配置`() {
        val deepseek = ModelProfile(id = "p", providerId = "deepseek", model = "deepseek-v4.1-flash")
        val cfg = MemoryModelConfig(source = MemoryModelConfig.SOURCE_PROFILE, profileId = "p")
        val sanitized = MemoryModelConfig.sanitizeEmbeddingConfig(cfg, listOf(deepseek))
        assertEquals(MemoryModelConfig.SOURCE_PROFILE, sanitized.source)
        assertTrue(sanitized.profileId.isBlank())
        assertTrue(!sanitized.isConfigured)
    }

    @Test
    fun `支持 embeddings 的厂商配置会被保留`() {
        val openai = ModelProfile(id = "o", providerId = "openai", model = "text-embedding-3-small")
        val cfg = MemoryModelConfig(source = MemoryModelConfig.SOURCE_PROFILE, profileId = "o")
        assertEquals(cfg, MemoryModelConfig.sanitizeEmbeddingConfig(cfg, listOf(openai)))
    }
}
