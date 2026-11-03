package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupeTest {

    @Test
    fun `归一化会处理全角标点与大小写`() {
        assertEquals("hello 世界", Dedupe.normalize("Ｈｅｌｌｏ，世界！"))
    }

    @Test
    fun `blob 去掉全部空白`() {
        assertEquals("helloworld", Dedupe.blob("Hello   World"))
    }

    @Test
    fun `中文按字符 bigram 切分`() {
        assertEquals(setOf("迭代", "代固", "固定"), Dedupe.bigrams("迭代固定"))
    }

    @Test
    fun `同样的内容换语序应判为同一件事`() {
        // 真机重复的典型形态：同一事实被抽成不同语序
        assertTrue(Dedupe.isSameTopic("迭代固定提示词", "固定提示词迭代"))
    }

    @Test
    fun `不同事实不应被合并`() {
        assertFalse(Dedupe.isSameTopic("我喜欢猫", "我喜欢狗"))
    }

    @Test
    fun `短句包含关系不合并`() {
        // "喜欢猫" 被 "喜欢猫粮" 包含，但短句太短 ⇒ 不并（避免丢精度）
        assertFalse(Dedupe.isSameTopic("喜欢猫", "喜欢猫粮"))
    }

    @Test
    fun `长句包含关系判为同一件事`() {
        assertTrue(Dedupe.isSameTopic("我住在上海", "我住在上海浦东"))
    }

    @Test
    fun `空串永远不相等`() {
        assertFalse(Dedupe.isSameTopic("", "任何东西"))
        assertEquals(0.0, Dedupe.jaccard("", "x"), 1e-9)
    }
}
