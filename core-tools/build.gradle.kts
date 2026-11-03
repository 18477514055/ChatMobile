// 纯 JVM 模块 —— 不依赖 Android，能独立单测。
plugins {
    id("org.jetbrains.kotlin.jvm")
    // ★ 用 kotlinx.serialization 解析工具参数（org.json 在 JVM 单测里是空 stub）
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    // ★ JSON 解析工具参数必须用 kotlinx.serialization，**不能用 org.json**：
    //   org.json 在 JVM 单测里是空 stub（方法体全是 throw），
    //   本项目在 core-memory 上已经栽过一次（见 LESSONS）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
