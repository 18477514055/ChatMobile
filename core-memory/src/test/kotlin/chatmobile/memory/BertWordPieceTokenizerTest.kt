package chatmobile.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BERT WordPiece：中文按字切、英文走子词、特殊 token 固定。 */
class BertWordPieceTokenizerTest {

    // 用一份最小词表模拟真实词表（真实是 21128 行）
    private val tokenizer = BertWordPieceTokenizer(
        listOf(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
            "chat", "##mobile", "hello", "皮", "革", "包", ",", "。",
        ),
    )

    @Test
    fun `特殊 token 的 id 来自词表`() {
        assertEquals(0, tokenizer.padId)
        assertEquals(1, tokenizer.unkId)
        assertEquals(2, tokenizer.clsId)
        assertEquals(3, tokenizer.sepId)
    }

    @Test
    fun `编码以 CLS 开头、以 SEP 结尾`() {
        val ids = tokenizer.encode("hello")
        assertEquals(tokenizer.clsId.toLong(), ids.first())
        assertEquals(tokenizer.sepId.toLong(), ids.last())
    }

    @Test
    fun `中文按单字切分`() {
        val ids = tokenizer.encode("皮革")
        // [CLS] 皮 革 [SEP]
        assertEquals(4, ids.size)
        assertEquals(8L, ids[1])   // 皮
        assertEquals(9L, ids[2])   // 革
    }

    @Test
    fun `英文词走 wordpiece 子词`() {
        val ids = tokenizer.encode("chatmobile")
        // chat + ##mobile
        assertEquals(4, ids.size)
        assertEquals(5L, ids[1])
        assertEquals(6L, ids[2])
    }

    @Test
    fun `词表里没有的片段退化为 UNK`() {
        val ids = tokenizer.encode("zzzz")
        assertEquals(tokenizer.unkId.toLong(), ids[1])
    }

    @Test
    fun `标点单独成 token`() {
        val ids = tokenizer.encode("皮革,包")
        // [CLS] 皮 革 , 包 [SEP]
        assertEquals(6, ids.size)
        assertEquals(11L, ids[3])  // ","
    }

    @Test
    fun `超长文本被截断到 maxLength 以内`() {
        val small = BertWordPieceTokenizer(
            listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "皮"),
            maxLength = 5,
        )
        val ids = small.encode("皮".repeat(50))
        assertTrue(ids.size <= 5, "size=${ids.size}")
        assertEquals(small.sepId.toLong(), ids.last())
    }

    @Test
    fun `批量编码补齐为矩形并给出 attention mask`() {
        val (ids, mask) = tokenizer.encodeBatch(listOf("hello", "皮革"))
        assertEquals(2, ids.size)
        assertTrue(ids[0].size == ids[1].size)
        assertEquals(ids[0].size, mask[0].size)
        // 短句的尾部必须补 0 且 mask 为 0
        val padCount = mask[1].count { it == 0L }
        assertEquals(ids[1].size - 4, padCount)
    }
}
