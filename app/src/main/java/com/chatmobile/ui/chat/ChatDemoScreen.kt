package com.chatmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chatmobile.ui.sidebar.SidebarLayout
import com.chatmobile.ui.sidebar.SidebarPanel
import com.chatmobile.ui.sidebar.rememberSidebarState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 演示界面 —— 用假数据把 UI 跑起来，等 net/data 交付后再替换。
 *
 * ## ★ 为什么必须模拟「逐字追加」而不是一次性塞整段
 *
 * 报告清单 #7 的三件事（自动跟随 / 拖拽即停 / 滚停恢复）**只有在文本持续增长时**
 * 才能观察到。一次性塞入整段，列表只跳一次，看不出跟随手感对不对。
 *
 * 所以这里每 **40ms** 追加一小段（3~6 个字），模拟真实 SSE 流式的节奏：
 * - 用户能看到内容持续"长出来"
 * - 往上翻之后能看到内容仍在底部增长、而视口不动
 * - 点停止按钮能中途打断
 *
 * 节奏参考：真实 LLM 输出大约 20~60 token/s，40ms/段 ≈ 15~25 段/s，接近慢速模型。
 */
@Composable
fun ChatDemoScreen(modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var selectedConversationId by remember { mutableStateOf(CONVERSATIONS.first().id) }

    val sidebarState = rememberSidebarState()
    val scope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

    // 首次进入放一条欢迎消息，避免空屏
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    id = "welcome",
                    role = ChatMessage.Role.Assistant,
                    text = "你好，我是 ChatMobile。从左边边缘往右拖试试侧边栏，" +
                        "发一条消息看看流式输出的跟随效果。",
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun startStreaming(userText: String) {
        val userId = UUID.randomUUID().toString()
        messages.add(
            ChatMessage(
                id = userId,
                role = ChatMessage.Role.User,
                text = userText,
                timestamp = System.currentTimeMillis(),
            ),
        )

        val aiId = UUID.randomUUID().toString()
        val fullReply = demoReplyFor(userText)
        messages.add(
            ChatMessage(
                id = aiId,
                role = ChatMessage.Role.Assistant,
                text = "",
                isStreaming = true,
                timestamp = System.currentTimeMillis(),
            ),
        )

        isGenerating = true
        streamJob = scope.launch {
            var cursor = 0
            while (cursor < fullReply.length) {
                delay(STREAM_INTERVAL_MS)
                // 每次追加 3~6 个字，模拟 token 粒度
                val step = (3..6).random()
                val next = (cursor + step).coerceAtMost(fullReply.length)
                cursor = next

                val idx = messages.indexOfFirst { it.id == aiId }
                if (idx < 0) break // 被清空了
                messages[idx] = messages[idx].copy(
                    text = fullReply.substring(0, cursor),
                    isStreaming = cursor < fullReply.length,
                )
            }
            // 收尾：清掉流式标记
            val idx = messages.indexOfFirst { it.id == aiId }
            if (idx >= 0) {
                messages[idx] = messages[idx].copy(
                    text = fullReply,
                    isStreaming = false,
                )
            }
            isGenerating = false
            streamJob = null
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        // 把当前流式消息标记为完成
        val idx = messages.indexOfFirst { it.isStreaming }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(isStreaming = false)
        }
        isGenerating = false
    }

    SidebarLayout(
        state = sidebarState,
        modifier = modifier,
        panel = {
            SidebarPanel(
                conversations = CONVERSATIONS,
                selectedId = selectedConversationId,
                onSelect = { id ->
                    selectedConversationId = id
                    sidebarState.close()
                },
                onNewChat = {
                    messages.clear()
                    sidebarState.close()
                },
            )
        },
    ) {
        ChatScreen(
            messages = messages,
            input = input,
            onInputChange = { input = it },
            isGenerating = isGenerating,
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty() && !isGenerating) {
                    input = ""
                    startStreaming(text)
                }
            },
            onStop = { stopStreaming() },
            onOpenSidebar = { sidebarState.open() },
            title = CONVERSATIONS.firstOrNull { it.id == selectedConversationId }?.title
                ?: "ChatMobile",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

/** 流式追加间隔。40ms ≈ 每秒 25 段，接近真实慢速模型的 token 节奏。 */
private const val STREAM_INTERVAL_MS = 40L

/**
 * 假回复。故意写长一点、带换行和标点，
 * 方便观察自动跟随在多行文本增长时的表现。
 */
private fun demoReplyFor(prompt: String): String {
    val base = "这是演示用的模拟回复（还没有接真实模型）。\n\n" +
        "你刚才说的是「${prompt.take(30)}」。\n\n" +
        "现在你可以做三件事来验证手感：\n" +
        "1. 在这段文字还在增长时，用手指往上拖 —— 视口应该停在原地，内容继续在底部生长。\n" +
        "2. 松手后如果还在底部附近，自动跟随会自己恢复。\n" +
        "3. 右上角（输入框右侧）那个按钮会从「发送」变成「停止」，位置不会跳。\n\n" +
        "侧边栏：从屏幕最左边往右拖，或者点左上角菜单键。拖到一半松手它会自己弹回去，" +
        "快速甩一下则会直接开到底 —— 这两种情况走的是不同的判据。\n\n" +
        "再补充一些文字，让这段内容足够长，长到需要滚动才能看完，" +
        "这样才能看出自动跟随在长内容下的表现究竟对不对。"
    return base
}

/** 假会话列表。 */
private val CONVERSATIONS = listOf(
    Conversation(id = "c1", title = "侧边栏跟手测试"),
    Conversation(id = "c2", title = "流式输出与自动跟随"),
    Conversation(id = "c3", title = "Kotlin 协程取消语义"),
    Conversation(id = "c4", title = "Compose 重组性能"),
    Conversation(id = "c5", title = "一个很长的会话标题用来测试省略号是否正常工作"),
)
