package com.chatmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ChatMobile 配色。
 *
 * 设计依据（见 UI研究-交互手感.md §1、UI研究-动效与转场.md §7.4）：
 *   - 官方是「低对比度克制风」：背景与气泡的区分靠极小的明度差，不是强色块
 *   - 用户自己的消息用淡灰底，AI 的消息基本无底色 —— 靠排版区分而非色块
 *   - 深色模式不是简单反色，背景接近纯黑但气泡略微提亮
 *
 * ⚠️ 这些是 [参考] 值，需要在真机上对照调。
 *    官方真实色值没取到（Valdi bundle 里颜色是变量引用，不是字面量）。
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF10A37F),          // OpenAI 绿，用于强调与选中态
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F5F1),
    onPrimaryContainer = Color(0xFF0A3D31),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0D0D0D),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF4F4F4),   // 用户气泡底色
    onSurfaceVariant = Color(0xFF5D5D5D),

    outline = Color(0xFFE3E3E3),
    outlineVariant = Color(0xFFEFEFEF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF19C37D),
    onPrimary = Color(0xFF052E22),
    primaryContainer = Color(0xFF0E3A2C),
    onPrimaryContainer = Color(0xFFB8EBD9),

    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFECECEC),

    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2A),   // 用户气泡底色（比背景略亮）
    onSurfaceVariant = Color(0xFFB4B4B4),

    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF262626),
)

@Composable
fun ChatMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * 把用户在「通用 → 外观」里选的三档解析成**实际该不该用深色**。
 *
 * ★ 做成纯函数是为了可单测 —— 这个映射错一档，表现是「选了深色没反应」，
 *   而那种错误在真机上肉眼极难判断（环境光不同、屏幕色温不同）。
 *
 * ★ 「跟随系统」必须显式走 `systemDark`，**不能**写成「不是 Light 就当 Dark」：
 *   那样将来一加新档位，它会静默落进深色，而且没有任何编译错误。
 */
fun resolveDarkTheme(modeName: String?, systemDark: Boolean): Boolean =
    when (com.chatmobile.settings.ThemeMode.fromName(modeName)) {
        com.chatmobile.settings.ThemeMode.Light -> false
        com.chatmobile.settings.ThemeMode.Dark -> true
        com.chatmobile.settings.ThemeMode.System -> systemDark
    }
