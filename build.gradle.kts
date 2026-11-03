// 顶层构建文件 —— 只声明插件版本，不 apply。
// 各模块自己 apply，这样 core-* 这种纯 JVM 模块不必拖 Android 插件。
//
// ★ AGP 9.x 起 Kotlin 支持已内置，"org.jetbrains.kotlin.android" 不再需要
//   也不能再用（会直接报错）。纯 JVM 模块仍用 kotlin("jvm")。
//
// ★ KSP 2.3.13 与 Kotlin 2.4.21 是官方文档配法；KSP 2.3.1+ 已兼容 AGP 9 内置 Kotlin
//   （google/ksp issue #2615）。Room 必须靠 KSP 生成 _Impl。
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // 工具参数解析用（org.json 在 JVM 单测里是空 stub，不能用于 core-tools）
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
