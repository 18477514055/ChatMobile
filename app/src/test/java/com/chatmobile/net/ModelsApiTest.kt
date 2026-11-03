package com.chatmobile.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [ModelsApi] 的测试。
 *
 * 这个类的**契约核心是「失败返回 null，绝不抛异常」** ——
 * 因为 `/v1/models` 被定位成可选能力（调研报告 §3）：
 * 智谱没有这个端点、阿里的 compatible-mode 未获官方背书。
 * UI 拿到 null 就退回手动填模型名，功能不受影响。
 */
class ModelsApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(supportsModelList: Boolean = true): ProviderConfig = ProviderConfig(
        id = "test",
        displayName = "Test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key-1234567890",
        supportsModelList = supportsModelList,
    )

    @Test
    fun `正常响应解析出模型 id 列表`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(
                    """{"object":"list","data":[
                        {"id":"deepseek-chat","object":"model","owned_by":"deepseek"},
                        {"id":"deepseek-reasoner","object":"model","owned_by":"deepseek"}
                    ]}""",
                )
                .build(),
        )

        val models = ModelsApi(config(), shortClient()).listModels()

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models)
    }

    @Test
    fun `请求路径与鉴权头正确`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"data":[{"id":"m1"}]}""").build(),
        )

        ModelsApi(config(), shortClient()).listModels()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.target)
        assertEquals("Bearer test-key-1234567890", recorded.headers["Authorization"])
    }

    @Test
    fun `忽略多余字段只取 id`() = runBlocking {
        // OpenRouter 的响应是超集：pricing / architecture / top_provider ...
        // DeepSeek 也有 context_window 等。强依赖任何可选字段都会在某家上崩。
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(
                """{"data":[
                    {"id":"m1","canonical_slug":"x","pricing":{"prompt":"0.1"},
                     "architecture":{"input_modalities":["text"]},"context_length":8192},
                    {"id":"m2","context_window":128000,"max_output_tokens":4096}
                ]}""",
            ).build(),
        )

        assertEquals(listOf("m1", "m2"), ModelsApi(config(), shortClient()).listModels())
    }

    @Test
    fun `非 id 条目被跳过`() {
        // 防御：data 里混进非对象 / 缺 id / id 为空的条目，不该让整份列表失效。
        val api = ModelsApi(config(), shortClient())
        val body = """{"data":[{"id":"ok"},{"no_id":1},{"id":""},"garbage"]}"""

        assertEquals(listOf("ok"), api.parseModelIds(body))
    }

    @Test
    fun `重复 id 被去重`() {
        val api = ModelsApi(config(), shortClient())
        val body = """{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}"""

        assertEquals(listOf("a", "b"), api.parseModelIds(body))
    }

    @Test
    fun `HTTP 401 返回 null 而不抛异常`() = runBlocking {
        // 智谱这类厂商对任意路径都先回 401 —— 不能让 UI 崩掉。
        server.enqueue(
            MockResponse.Builder().code(401).setHeader("Content-Type", "application/json")
                .body("""{"error":"unauthorized"}""").build(),
        )

        assertNull(ModelsApi(config(), shortClient()).listModels())
    }

    @Test
    fun `HTTP 404 返回 null`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())

        assertNull(ModelsApi(config(), shortClient()).listModels())
    }

    @Test
    fun `厂商声明不支持时不发请求直接返回 null`() = runBlocking {
        // supportsModelList=false 时应当连请求都不发（省一次 401/404）。
        val result = ModelsApi(config(supportsModelList = false), shortClient()).listModels()

        assertNull(result)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `响应不是合法 JSON 时返回 null`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("<html>not json</html>").build(),
        )

        assertNull(ModelsApi(config(), shortClient()).listModels())
    }

    @Test
    fun `响应用 data 缺失时返回 null`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"object":"list"}""").build(),
        )

        assertNull(ModelsApi(config(), shortClient()).listModels())
    }

    @Test
    fun `连接失败返回 null 而不抛异常`() = runBlocking {
        // 指向一个必定连不上的地址。
        val deadConfig = config().copy(baseUrl = "http://127.0.0.1:1/v1")

        assertNull(ModelsApi(deadConfig, shortClient()).listModels())
    }

    private fun shortClient(): okhttp3.OkHttpClient = SseTransport.defaultClientBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
}
