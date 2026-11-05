package com.chatmobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 附件**真的能发出去**的契约。
 *
 * ## 为什么需要这一套（用户报的 bug）
 *
 * > 「现在无法正常上传照片和文件」
 *
 * 上一版的实现是：选完文件 → 往输入框写一行「已选图片：xxx.jpg」→ **结束**。
 * 既没有保存 URI、也没有保存路径，更没有把任何东西挂到消息上。
 * 用户看到"已选图片"四个字，以为成了，其实**一个字节都不会发出去**。
 *
 * 而这样的代码：
 *   · 编译通过 ✅
 *   · 界面正常 ✅
 *   · 没有任何异常 ✅
 *   · 单元测试（当时）也过 ✅
 *
 * ⇒ 只有真机点一次、并且**真的发一条消息**才会暴露。
 * 这正是 `L26`（设置项存了≠生效）的同类：**机制都在，唯独连接不存在**。
 *
 * 所以这里钉的不是"有没有这个函数"，而是**连接本身**：
 *   ① 请求体里 content 的形状由图片决定；
 *   ② 发送路径必须把附件读出来并挂到最后那条用户消息上；
 *   ③ `pendingAttachments` 必须真的被 `send` 消费。
 */
class AttachmentUploadContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildRequest(msgs: List<ChatMessage>): JsonObject {
        val req = ChatRequest(model = "m", messages = msgs)
        val cfg = ProviderConfig(id = "t", displayName = "t", baseUrl = "https://x", apiKey = "k")
        return OpenAiRequestBuilder.build(req, cfg)
    }

    private fun firstMessageContent(obj: JsonObject): kotlinx.serialization.json.JsonElement =
        ((obj["messages"] as JsonArray)[0] as JsonObject)["content"]!!

    // ------------------------------------------------------------------
    // ① content 的形状
    // ------------------------------------------------------------------

    @Test
    fun `纯文本消息的 content 必须仍然是字符串`() {
        // ★ 这条是**缓存契约**，不是洁癖：一旦纯文本也变成数组，
        //   整段历史前缀的字节序列就变了 ⇒ 上一轮的缓存全部失效。
        val content = firstMessageContent(buildRequest(listOf(ChatMessage.user("你好"))))
        assertTrue("纯文本必须是字符串，不能是数组", content is kotlinx.serialization.json.JsonPrimitive)
        assertEquals("你好", content.jsonPrimitive.content)
    }

    @Test
    fun `带图片时 content 必须变成数组`() {
        val img = ChatAttachment(
            kind = ChatAttachment.Kind.Image,
            name = "a.jpg",
            mimeType = "image/jpeg",
            imageBase64 = "AAAA",
        )
        val content = firstMessageContent(buildRequest(listOf(ChatMessage.user("这是什么", listOf(img)))))
        assertTrue("带图片时 content 必须是数组（发成字符串服务端会静默忽略图片）", content is JsonArray)
    }

    @Test
    fun `带图片时 text 片段在前 image_url 在后`() {
        val img = ChatAttachment(
            kind = ChatAttachment.Kind.Image,
            name = "a.jpg",
            mimeType = "image/jpeg",
            imageBase64 = "AAAA",
        )
        val arr = firstMessageContent(buildRequest(listOf(ChatMessage.user("看图", listOf(img))))) as JsonArray
        assertEquals("必须有两个片段：text + image_url", 2, arr.size)
        assertEquals("text", (arr[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", (arr[1] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `图片 URL 必须是 data URI 且带真实 MIME`() {
        val img = ChatAttachment(
            kind = ChatAttachment.Kind.Image,
            name = "a.png",
            mimeType = "image/png",
            imageBase64 = "QUJD",
        )
        val arr = firstMessageContent(buildRequest(listOf(ChatMessage.user("x", listOf(img))))) as JsonArray
        val url = ((arr[1] as JsonObject)["image_url"] as JsonObject)["url"]!!.jsonPrimitive.content
        assertEquals("data:image/png;base64,QUJD", url)
    }

    @Test
    fun `base64 为空的图片被跳过 而不是发出坏数据`() {
        val broken = ChatAttachment(
            kind = ChatAttachment.Kind.Image,
            name = "x.jpg",
            mimeType = "image/jpeg",
            imageBase64 = null,
        )
        val arr = firstMessageContent(buildRequest(listOf(ChatMessage.user("x", listOf(broken))))) as JsonArray
        assertEquals("读不出字节的图片不该进请求（只剩 text 片段）", 1, arr.size)
    }

    @Test
    fun `多张图片全部按序进入数组`() {
        val imgs = (1..3).map {
            ChatAttachment(
                kind = ChatAttachment.Kind.Image,
                name = "p" + it + ".jpg",
                mimeType = "image/jpeg",
                imageBase64 = "B64" + it,
            )
        }
        val arr = firstMessageContent(buildRequest(listOf(ChatMessage.user("三张", imgs)))) as JsonArray
        assertEquals("1 个 text + 3 个 image_url", 4, arr.size)
        val urls = (1..3).map {
            ((arr[it] as JsonObject)["image_url"] as JsonObject)["url"]!!.jsonPrimitive.content
        }
        assertEquals(
            listOf(
                "data:image/jpeg;base64,B641",
                "data:image/jpeg;base64,B642",
                "data:image/jpeg;base64,B643",
            ),
            urls,
        )
    }

    @Test
    fun `assistant 消息即使带图片列表也不该走数组分支`() {
        // assistant 的 content 本来是字符串；协议里给 assistant 塞 image_url 是非法的。
        // 我们的模型层不会构造这种消息，但契约上明确：只有 user 才会带 images。
        val c = firstMessageContent(buildRequest(listOf(ChatMessage.assistant("回答"))))
        assertTrue(c is kotlinx.serialization.json.JsonPrimitive)
    }

    // ------------------------------------------------------------------
    // ② 分类与策略
    // ------------------------------------------------------------------

    @Test
    fun `image 开头 MIME 判为图片`() {
        assertEquals(ChatAttachment.Kind.Image, AttachmentPolicy.classify("a.bin", "image/png"))
    }

    @Test
    fun `MIME 缺失时靠扩展名兜底`() {
        // 相册/相机偶尔不给 MIME，这时不能把 jpg 当文本发
        assertEquals(ChatAttachment.Kind.Image, AttachmentPolicy.classify("photo.jpg", ""))
        assertEquals(ChatAttachment.Kind.Image, AttachmentPolicy.classify("photo.HEIC", ""))
        assertEquals(ChatAttachment.Kind.Image, AttachmentPolicy.classify("x.jpeg", "application/octet-stream"))
    }

    @Test
    fun `octet-stream 的 md 文件仍按文本处理`() {
        // ★ 这条是真机踩出来的：SAF 对 Documents/ 下的文件经常给 octet-stream，
        //   只看 MIME 会把一堆 .md/.json 误判成二进制而拒收。
        assertTrue(AttachmentPolicy.isTextLike("notes.md", "application/octet-stream"))
        assertTrue(AttachmentPolicy.isTextLike("data.json", "application/octet-stream"))
        assertTrue(AttachmentPolicy.isTextLike("Main.kt", ""))
    }

    @Test
    fun `真正的二进制不是文本`() {
        assertFalse(AttachmentPolicy.isTextLike("a.apk", "application/vnd.android.package-archive"))
        assertFalse(AttachmentPolicy.isTextLike("a.pdf", "application/pdf"))
        assertFalse(AttachmentPolicy.isTextLike("a.zip", "application/zip"))
    }

    @Test
    fun `超长文本被截断且明确告知`() {
        val long = "a".repeat(AttachmentPolicy.MAX_TEXT_CHARS + 500)
        val out = AttachmentPolicy.truncateText(long)
        assertTrue("必须截断", out.length < long.length)
        assertTrue("必须写明被截断了", out.contains("已截断"))
        assertTrue("必须说清还有多少没给", out.contains("500"))
        assertTrue("必须给出后续办法", out.contains("拆小") || out.contains("关键部分"))
    }

    @Test
    fun `未超限文本原样返回`() {
        val short = "hello"
        assertEquals(short, AttachmentPolicy.truncateText(short))
    }

    @Test
    fun `读不出的文件要给出出路 不能只说不行`() {
        // ★ 文案变过：现在 Word/PPT/Excel/PDF 都能读了，
        //   只有老 Office、压缩包、音视频还走这条。仍然要求「是什么/为什么/怎么办」。
        val hint = AttachmentPolicy.unsupportedBinaryHint("影片.mp4")
        assertTrue("必须带上文件名", hint.contains("影片.mp4"))
        assertTrue("必须说明原因", hint.contains("读不出"))
        assertTrue("必须给出替代办法", hint.contains("导出") || hint.contains("截图"))
    }

    // ------------------------------------------------------------------
    // ③ 调用点契约 —— 这是抓「连接不存在」的那一层
    // ------------------------------------------------------------------

    @Test
    fun `send 必须消费 pendingAttachments`() {
        val src = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        val sendBlock = src.substringAfter("fun send(text: String)")
            .substringBefore("fun stop()")
        assertTrue(
            "send 必须真的取走待发附件 —— 上一版就是没有这一步，选了等于没选",
            sendBlock.contains("pendingAttachments"),
        )
        assertTrue(
            "附件必须写进落库调用（否则气泡上显示不出来、重开 App 也没了）",
            sendBlock.contains("attachments ="),
        )
        assertTrue(
            "必须把附件传给 runStreaming",
            sendBlock.contains("runStreaming(") && sendBlock.contains("attachSnapshot"),
        )
    }

    @Test
    fun `runStreaming 必须真的读附件并挂到最后一条用户消息`() {
        val src = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        val block = src.substringAfter("suspend fun runStreaming(")
            .substringBefore("// ★★★ 打一条请求结构日志")
        assertTrue("必须用 AttachmentReader 真的读字节", block.contains("AttachmentReader"))
        assertTrue("必须把读结果拼进正文", block.contains("toPromptSection"))
        assertTrue("必须把图片挂到请求消息上", block.contains("images = attachmentRead.images"))
    }

    @Test
    fun `onAttachResult 必须真的产出元数据 而不是只写一行字`() {
        val src = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        val block = src.substringAfter("fun onAttachResult(")
            .substringBefore("// ★ 三个入口共用一个 launcher")
        assertTrue("必须写进 pendingAttachments", block.contains("pendingAttachments ="))
        assertFalse(
            "不许再把「已选图片」写进输入框 —— 那会被当成用户说的话拼进正文",
            block.contains("已选图片") || block.contains("已选文件"),
        )
    }

    @Test
    fun `输入框上方的附件条必须接上真实列表`() {
        val chat = File("src/main/java/com/chatmobile/ChatHost.kt").readText()
        assertTrue(
            "ChatScreen 调用点必须传 pendingAttachments",
            chat.contains("pendingAttachments = pendingAttachments"),
        )
        val bar = File("src/main/java/com/chatmobile/ui/chat/ChatInputBar.kt").readText()
        assertTrue("输入条必须渲染待发附件", bar.contains("PendingAttachmentStrip"))
        assertTrue("必须能删", bar.contains("onRemoveAttachment"))
    }

    @Test
    fun `消息气泡必须显示已发送的附件`() {
        val bubble = File("src/main/java/com/chatmobile/ui/chat/MessageBubble.kt").readText()
        assertTrue("气泡必须渲染附件标签", bubble.contains("SentAttachmentTags"))
        assertTrue("必须读 message.attachments", bubble.contains("message.attachments"))
    }
}
