package musicunlock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * MusicUnlock 主题(基于 ui-ux-pro-max 的 Dark Mode(OLED) + 音乐流媒体配色):
 * - 深色: 深黑/午夜蓝背景 + 靛蓝主色 + 播放绿强调
 * - 浅色: 同色相的浅色方案
 * - 全部使用语义色板(token),组件内不写死颜色
 */
object MusicUnlockColors {
    // 深色(OLED)
    val DarkPrimary = Color(0xFF818CF8)        // indigo-400
    val DarkOnPrimary = Color(0xFF0F0F23)
    val DarkSecondary = Color(0xFF22C55E)      // 播放绿
    val DarkOnSecondary = Color(0xFF0F172A)
    val DarkTertiary = Color(0xFFF97316)       // 强调橙
    val DarkBackground = Color(0xFF0F0F23)     // 午夜蓝
    val DarkSurface = Color(0xFF1B1B30)
    val DarkSurfaceVariant = Color(0xFF27273B)
    val DarkOnSurface = Color(0xFFF8FAFC)
    val DarkOnSurfaceVariant = Color(0xFF94A3B8)
    val DarkOutline = Color(0xFF4338CA)
    val DarkError = Color(0xFFEF4444)
    val DarkOnError = Color(0xFFFFFFFF)

    // 浅色
    val LightPrimary = Color(0xFF4F46E5)       // indigo-600
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF16A34A)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFFEA580C)
    val LightBackground = Color(0xFFF8FAFC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEEF2F7)
    val LightOnSurface = Color(0xFF1E293B)
    val LightOnSurfaceVariant = Color(0xFF475569)
    val LightOutline = Color(0xFF94A3B8)
    val LightError = Color(0xFFDC2626)
    val LightOnError = Color(0xFFFFFFFF)
}

private val DarkColors = darkColorScheme(
    primary = MusicUnlockColors.DarkPrimary,
    onPrimary = MusicUnlockColors.DarkOnPrimary,
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = MusicUnlockColors.DarkSecondary,
    onSecondary = MusicUnlockColors.DarkOnSecondary,
    tertiary = MusicUnlockColors.DarkTertiary,
    background = MusicUnlockColors.DarkBackground,
    onBackground = MusicUnlockColors.DarkOnSurface,
    surface = MusicUnlockColors.DarkSurface,
    onSurface = MusicUnlockColors.DarkOnSurface,
    surfaceVariant = MusicUnlockColors.DarkSurfaceVariant,
    onSurfaceVariant = MusicUnlockColors.DarkOnSurfaceVariant,
    outline = MusicUnlockColors.DarkOutline,
    outlineVariant = Color(0xFF3F3F5E),
    error = MusicUnlockColors.DarkError,
    onError = MusicUnlockColors.DarkOnError,
)

private val LightColors = lightColorScheme(
    primary = MusicUnlockColors.LightPrimary,
    onPrimary = MusicUnlockColors.LightOnPrimary,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = MusicUnlockColors.LightSecondary,
    onSecondary = MusicUnlockColors.LightOnSecondary,
    tertiary = MusicUnlockColors.LightTertiary,
    background = MusicUnlockColors.LightBackground,
    onBackground = MusicUnlockColors.LightOnSurface,
    surface = MusicUnlockColors.LightSurface,
    onSurface = MusicUnlockColors.LightOnSurface,
    surfaceVariant = MusicUnlockColors.LightSurfaceVariant,
    onSurfaceVariant = MusicUnlockColors.LightOnSurfaceVariant,
    outline = MusicUnlockColors.LightOutline,
    outlineVariant = Color(0xFFCBD5E1),
    error = MusicUnlockColors.LightError,
    onError = MusicUnlockColors.LightOnError,
)

@Composable
fun MusicUnlockTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
