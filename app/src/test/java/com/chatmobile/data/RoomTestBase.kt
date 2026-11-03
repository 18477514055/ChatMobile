package com.chatmobile.data

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room 单元测试的公共基座。
 *
 * ## 为什么必须 Robolectric
 *
 * `Room.inMemoryDatabaseBuilder(context, ...)` 需要一个真的 `Context`。
 * `app/src/test`（host-side unit test）跑在 JVM 上，`android.jar` 里的方法体全是
 * `throw new RuntimeException("Stub!")` —— 不套 Robolectric 就一定撞
 * `Method ... not mocked`。这是 Room 官方推荐的 host 侧测法。
 *
 * ## 为什么 `@Config(sdk = [34])`
 *
 * compileSdk 是 37。Robolectric 4.17 的 `DefaultSdkProvider` 已经注册了 API 37，
 * 但跑 37 要额外下载一份 android-all jar（约 100 MB），而这份用例根本不碰
 * 任何 35+ 才有的 API。固定在 34 有两个好处：
 *
 * 1. 新机器上第一次跑测试不用等大下载；
 * 2. 同一个 SDK 下结果稳定，不会因为环境里恰好有没有那份 jar 而时绿时红。
 *
 * ⚠️ 这不是"降级糊弄" —— 被测的是 SQL 与 Room 的生成代码，
 * 这部分在 API 26~37 上行为一致。真要测 35+ 的平台行为，那是 instrumented test 的活。
 *
 * ## 上下文从哪来
 *
 * 用 `RuntimeEnvironment.getApplication()`（Robolectric 自带），
 * 不用 `androidx.test:core` 的 `ApplicationProvider` —— 后者是**另一个依赖**，
 * 这里不需要为一行代码多引一个 artifact。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class RoomTestBase {

    protected lateinit var context: Context
    protected lateinit var db: ChatDatabase
    protected lateinit var conversations: ConversationDao
    protected lateinit var messages: MessageDao
    protected lateinit var memories: MemoryDao

    @Before
    fun setUpDatabase() {
        context = RuntimeEnvironment.getApplication()
        db = ChatDatabase.inMemory(context)
        conversations = db.conversationDao()
        messages = db.messageDao()
        memories = db.memoryDao()
    }

    @After
    fun tearDownDatabase() {
        if (this::db.isInitialized && db.isOpen) db.close()
    }

    // ---------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------

    /** 测试里的时间从固定点起跳，避免依赖真实时钟 */
    protected var now = 1_700_000_000_000L

    protected fun tick(ms: Long = 1000L): Long {
        now += ms
        return now
    }

    protected fun conversation(
        id: String = "c-1",
        title: String = "测试会话",
        createdAt: Long = now,
        updatedAt: Long = createdAt,
        pinned: Boolean = false,
        archived: Boolean = false,
        modelId: String? = null,
    ) = ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        archived = archived,
        modelId = modelId,
    )

    protected fun message(
        id: String,
        conversationId: String = "c-1",
        role: MessageRole = MessageRole.USER,
        content: String = "",
        status: MessageStatus = MessageStatus.DONE,
        createdAt: Long = now,
        reasoning: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.value,
        content = content,
        reasoning = reasoning,
        status = status.value,
        createdAt = createdAt,
    )
}
