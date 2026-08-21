package musicunlock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * MusicUnlock 主题 —— 新拟态(Neumorphism) + 极简(工具类产品)。
 * 依据 ui-ux-pro-max 对 "Calculator & Unit Converter" 产品的推荐:
 * 柔和单色底(浅灰/中性炭灰, 非蓝调) + 组件同底色 + 左上高光/右下暗影双阴影,
 * 主操作色用橙色, 成功=绿, 失败=红, 全程语义 token、8dp 间距节奏。
 */
object MusicUnlockColors {
    // ---- 浅色(新拟态经典浅灰) ----
    val LightBg = Color(0xFFF0F0F2)            // 近白底(组件同色)
    val LightText = Color(0xFF3F3F46)          // 主文字
    val LightTextSecondary = Color(0xFF71717A) // 次要文字
    val LightHighlight = Color(0xCCFFFFFF)     // 左上高光(白)
    val LightShadow = Color(0x40000000)        // 右下暗影(黑, 近白底上稍加深)
    val LightInsetTop = Color(0x24000000)      // 内凹顶暗
    val LightInsetBottom = Color(0x99FFFFFF)   // 内凹底亮
    val LightPrimary = Color(0xFFEA580C)       // 橙色主操作
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSuccess = Color(0xFF16A34A)
    val LightError = Color(0xFFDC2626)

    // ---- 深色(中性炭灰, 非蓝调) ----
    val DarkBg = Color(0xFF2A2A2E)
    val DarkText = Color(0xFFF4F4F5)
    val DarkTextSecondary = Color(0xFFA1A1AA)
    val DarkHighlight = Color(0x1FFFFFFF)
    val DarkShadow = Color(0x66000000)
    val DarkInsetTop = Color(0x59000000)
    val DarkInsetBottom = Color(0x12FFFFFF)
    val DarkPrimary = Color(0xFFF97316)
    val DarkOnPrimary = Color(0xFF1C0A00)
    val DarkSuccess = Color(0xFF22C55E)
    val DarkError = Color(0xFFEF4444)
}

/** 由背景亮度判断当前深浅主题, 返回对应新拟态阴影/内凹 token。 */
internal data class NeumorphicTokens(
    val highlight: Color,
    val shadow: Color,
    val insetTop: Color,
    val insetBottom: Color,
)

@Composable
internal fun neumorphicTokens(): NeumorphicTokens {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (isLight) {
        NeumorphicTokens(
            highlight = MusicUnlockColors.LightHighlight,
            shadow = MusicUnlockColors.LightShadow,
            insetTop = MusicUnlockColors.LightInsetTop,
            insetBottom = MusicUnlockColors.LightInsetBottom,
        )
    } else {
        NeumorphicTokens(
            highlight = MusicUnlockColors.DarkHighlight,
            shadow = MusicUnlockColors.DarkShadow,
            insetTop = MusicUnlockColors.DarkInsetTop,
            insetBottom = MusicUnlockColors.DarkInsetBottom,
        )
    }
}

private val LightColors = lightColorScheme(
    primary = MusicUnlockColors.LightPrimary,
    onPrimary = MusicUnlockColors.LightOnPrimary,
    primaryContainer = Color(0xFFFFE4D6),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = MusicUnlockColors.LightSuccess,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = MusicUnlockColors.LightPrimary,
    background = MusicUnlockColors.LightBg,
    onBackground = MusicUnlockColors.LightText,
    surface = MusicUnlockColors.LightBg,
    onSurface = MusicUnlockColors.LightText,
    surfaceVariant = Color(0xFFE6E6E9),
    onSurfaceVariant = MusicUnlockColors.LightTextSecondary,
    outline = Color(0xFFB4B4BC),
    outlineVariant = Color(0xFFD4D4D8),
    error = MusicUnlockColors.LightError,
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = MusicUnlockColors.DarkPrimary,
    onPrimary = MusicUnlockColors.DarkOnPrimary,
    primaryContainer = Color(0xFF5C2A0D),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = MusicUnlockColors.DarkSuccess,
    onSecondary = Color(0xFF00280F),
    tertiary = MusicUnlockColors.DarkPrimary,
    background = MusicUnlockColors.DarkBg,
    onBackground = MusicUnlockColors.DarkText,
    surface = MusicUnlockColors.DarkBg,
    onSurface = MusicUnlockColors.DarkText,
    surfaceVariant = Color(0xFF323236),
    onSurfaceVariant = MusicUnlockColors.DarkTextSecondary,
    outline = Color(0xFF4A4A50),
    outlineVariant = Color(0xFF3A3A3E),
    error = MusicUnlockColors.DarkError,
    onError = Color(0xFFFFFFFF),
)

@Composable
fun MusicUnlockTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
