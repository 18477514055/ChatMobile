// AGP 9.x 起，Kotlin 支持已内置到 android 插件里，
// 不能再 apply 'org.jetbrains.kotlin.android'（会报错）。
// Compose 插件与 KSP 仍需单独声明。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ★★★ 签名信息**不进版本库**，从 local.properties 或环境变量读。
//
//   为什么这样组织：
//   - local.properties 已被 .gitignore 挡掉，是 Android 生态里约定俗成的本机配置位
//   - 环境变量给 CI 用（GitHub Actions 里没有 local.properties）
//
//   ★ 缺失时的行为是**关键**：不配置签名 ⇒ release 构建产出的是**未签名包**，
//     而不是悄悄用 debug key 签一个"看起来能装"的包。
//     后者极其危险 —— 用 debug key 签的 release 包一旦发出去，
//     将来换真 key 时用户必须卸载重装（签名不一致，系统拒绝覆盖安装）。
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String): String? =
    (signingProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "com.chatmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chatmobile"
        minSdk = 26          // ReTerminal 用 26 也能跑 proot，将来加容器不用改
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // ★ 只出 arm64-v8a：向量增强包用 ONNX Runtime（native 库），
        //   四个 ABI 全打会让 APK 白涨一倍；验证机与绝大多数现役手机都是 arm64。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // ★ 只有四项齐全才创建这个 signingConfig。
        //   缺一项就整个跳过 ⇒ release 变未签名包，构建脚本不会假装成功。
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // ★ 包名加 .debug 后缀 ⇒ 正式版与开发版**可以同时安装**，数据互不干扰。
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Robolectric 跑 Room 需要 Android resources，否则单元测试拿不到 Context
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room schema 导出目录。
// 为什么需要：ChatDatabase 开了 exportSchema = true，没有 schemaLocation 就只是一条警告，
// 但**将来写 Migration 需要拿历史 schema 对照**，所以现在就配好、让 json 进仓库。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-memory"))
    implementation(project(":core-tools"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // ★★★ 显式声明，不要把 androidx.startup 当"传递依赖"。
    //
    // 真机崩过（点图标瞬间退回桌面，进程直接死）：
    //   java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/startup/R$string;
    //       at androidx.startup.AppInitializer.discoverAndInitialize(AppInitializer.java:217)
    //       at androidx.startup.InitializationProvider.onCreate(InitializationProvider.java:49)
    //
    // 它本来是 profileinstaller → startup-runtime 的传递依赖，
    // 在本项目的 android.nonTransitiveRClass=true 下，传递 AAR 自己的 R 类
    // 没有被可靠地打进 dex，于是 InitializationProvider 一启动就炸。
    //
    // 提成一等依赖后 R 类随库一起打包，问题消失（已真机验证）。
    // ★ 不要因为"看起来是多余的一行"就删掉它 —— 删了会立刻退回那个崩溃。
    implementation("androidx.startup:startup-runtime:1.2.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    // ★ 工作区的共享目录走 SAF（Storage Access Framework）。
    //   用户自己选一个目录 ⇒ 我们的权限范围正好等于他选的那个，
    //   而不是申请 MANAGE_EXTERNAL_STORAGE（全盘访问，Play 高危审核，对聊天 App 明显过度授权）。
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.1")
    // 向量增强包的本地推理。用 mobile 构建（AAR 6.9MB）而不是 android（27.2MB）：
    // 只要 CPU EP、只跑 BERT 编码，用不到额外算子。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // Room 的 host-side 测试：Robolectric 提供 Context，否则 Method ... not mocked
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("org.robolectric:robolectric:4.17")
    // ★ 不要加 androidx.test:core-ktx —— Robolectric 自带
    //   org.robolectric.RuntimeEnvironment.getApplication()，用不着额外依赖。
    //   （2026-11-02 教训：曾误写 core-ktx:1.7.1，该版本不存在，稳定版最高 1.7.0，
    //     导致 debugUnitTestRuntimeClasspath 解析失败、全项目测试跑不了。）
    // 网络层测试
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
