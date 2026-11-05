package com.chatmobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条消息所携带附件的**元数据**。
 *
 * ★ 这是**落库**用的形态：只有"能显示的描述"，没有字节。
 *   真正的图片 base64 / 文本正文在发送那一刻从私有目录读出来
 *   （`net.ChatAttachment`）。分开的理由见 `MessageEntity.attachments` 的注释。
 *
 * ★ 为什么还要 [sizeBytes]：消息气泡上要显示"1.2MB"这种信息，
 *   而那时文件可能已经被用户删掉了 —— 落库的元数据是**唯一还能显示**的来源。
 */
@Serializable
data class MessageAttachmentMeta(
    /** `image` / `file`。落库用小写字符串，枚举改名也不会读错（同 MessageRole 的做法）。 */
    val kind: String,
    val name: String,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    /**
     * 文件在哪。
     *
     * - `image` / `photo`：我们私有目录里的绝对路径（相册图会先复制进来）
     * - `file`：SAF 的 `content://` URI 字符串 —— 那个 URI 是**系统给的持久读权限**，
     *   下次冷启动还能读（我们在挑选时已经 takePersistableUriPermission）
     *
     * ★ 空串表示"没有来源可读"（例如文件已被删除）—— 此时发送会跳过它并告知用户，
     *   而不是发一条空消息。
     */
    val source: String = "",
) {
    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_FILE = "file"
    }
}

/**
 * `message.attachments` 列的编解码。
 *
 * ★ 刻意选 JSON 而不是用某个分隔符拼接：文件名里可以有 `|` / `;` / 换行，
 *   任何自造格式都会在某天被一个奇怪的文件名打破，而 JSON 不会。
 *
 * ★ 用 `kotlinx.serialization` 而**不是** `org.json`：
 *   Android 的 `org.json` 在 JVM 单测里是**空 stub**（方法体全是抛异常/返回 null），
 *   用它写的解析在单测里永远返回空 —— 而真机正常。这种假象最难查（L17 同源）。
 */
object MessageAttachmentCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 编码成数据库列值。
     *
     * ★ 空列表 ⇒ **返回 null 而不是 `"[]"`**：
     *   老消息该列就是 NULL，保持"没有附件 = NULL"这一条单一语义，
     *   查询里就不用同时判 `NULL` 与 `"[]"` 两种"空"。
     */
    fun encode(list: List<MessageAttachmentMeta>): String? =
        if (list.isEmpty()) null else json.encodeToString(list)

    /**
     * 解码。
     *
     * ★ **任何异常都退化成空列表**，绝不抛：
     *   这一列是"锦上添花"的信息，解析失败不该让整条消息打不开
     *   （那会让用户以为聊天记录坏了）。
     */
    fun decode(raw: String?): List<MessageAttachmentMeta> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MessageAttachmentMeta>>(raw)
        }.getOrDefault(emptyList())
    }
}
