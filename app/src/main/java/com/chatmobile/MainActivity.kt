package com.chatmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatmobile.settings.SettingsStore
import com.chatmobile.ui.theme.ChatMobileTheme
import com.chatmobile.ui.theme.resolveDarkTheme

/**
 * 唯一的 Activity。
 *
 * 单 Activity + Compose 导航：避免多 Activity 转场带来的闪烁，
 * 也让侧边栏、底部弹层的状态管理集中在一处。
 *
 * ★ 接的是 [ChatHost]（真实持久化），**不是** `ChatDemoScreen`。
 *   `ChatDemoScreen` 用 `remember { mutableStateListOf() }` 存消息，纯内存 ——
 *   用户实测反馈过"切到桌面再回来历史消息全没了"，根因就在这里。
 *   验证手感时如果想用假数据，把下面换成 `ChatDemoScreen()` 即可，
 *   但**不要**把它当正式入口。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge 是 Android 15+ 的强制要求，早点适配省得后面返工
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // ★★★ 主题必须由**设置**驱动，不能直接用 `isSystemInDarkTheme()`。
            //
            //   以前是 `ChatMobileTheme { ... }`，走的是 isSystemInDarkTheme() 默认值
            //   ⇒ 用户在「通用 → 外观」里选浅色/深色**毫无作用**，
            //     因为没有任何地方把那个设置读出来。
            //   这跟「思考过程收不起来」是同一类错误：
            //   **设置项存下来了，但读取它的那条线不存在。**
            //
            //   ★ 这里直接把 DataStore 的 stateFlow 收成 Compose 状态：
            //     设置一改，`themeMode` 变，重组，整个 App 立刻换主题。
            val store = remember { SettingsStore(applicationContext) }
            val state by store.stateFlow.collectAsStateWithLifecycle(
                initialValue = com.chatmobile.settings.SettingsState(),
            )
            val dark = resolveDarkTheme(
                modeName = state.themeMode.name,
                systemDark = isSystemInDarkTheme(),
            )
            ChatMobileTheme(darkTheme = dark) {
                // ★ 调试专用入口：`adb shell am start ... --ez open_memory true`
                //   直接落到「记忆」面板。
                //   为什么需要它：这台机器的手势注入完全无效（`input swipe` /
                //   `motionevent` 都进不去事件），侧边栏只能靠手势打开 ⇒ 自动化
                //   验证够不着设置页。只在 debug 构建生效，release 行为不变。
                val debuggable =
                    (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val openMemory = debuggable &&
                    intent?.getBooleanExtra("open_memory", false) == true
                ChatHost(
                    initialLayer = if (openMemory) NavLayer.Memories else NavLayer.Chat,
                )
            }
        }
    }
}
