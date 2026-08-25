package musicunlock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * MusicUnlock 主题 —— 纯净白 · 精密工具。
 *
 * 近白底 + 白卡片 + 发丝分隔线, 品牌橙单点强调, 语义状态色,
 * 格式徽章按平台家族着色 (NCM 橙 / QMC 靛蓝 / KGM 青绿 / KWM 紫)。
 * 深色模式按同一世界重新设计(非反色): 中性炭灰 + 更亮的橙。
 */
object MusicUnlockColors {
    // ---- 浅色 ----
    val LightBg = Color(0xFFF7F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceSoft = Color(0xFFF4F4F7)
    val LightBorder = Color(0xFFE8E8EE)
    val LightText = Color(0xFF18181B)
    val LightTextSecondary = Color(0xFF71717A)
    val LightTextMuted = Color(0xFF8B8B96)
    val LightPrimary = Color(0xFFEA580C)
    val LightPrimarySoft = Color(0xFFFFF3E8)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightSuccess = Color(0xFF15803D)
    val LightSuccessSoft = Color(0xFFE9F9EF)
    val LightError = Color(0xFFDC2626)
    val LightErrorSoft = Color(0xFFFDEEEE)
    val LightWaitFg = Color(0xFF71717A)
    val LightWaitBg = Color(0xFFF4F4F7)
    val LightDupFg = Color(0xFF8B8B96)
    val LightDupBg = Color(0xFFF4F4F7)

    // 格式徽章
    val LightNcmBg = Color(0xFFFEF0E6); val LightNcmFg = Color(0xFFC2410C)
    val LightQmcBg = Color(0xFFEEF2FF); val LightQmcFg = Color(0xFF4F46E5)
    val LightKgmBg = Color(0xFFECFDF5); val LightKgmFg = Color(0xFF0F766E)
    val LightKwmBg = Color(0xFFF5F3FF); val LightKwmFg = Color(0xFF6D28D9)
    val LightExtBg = Color(0xFFF4F4F7); val LightExtFg = Color(0xFF52525B)
    val LightDropBorder = Color(0xFFD9D9E2)
    val LightCardBorder = Color(0xFFF0F0F4)
    val LightRowDivider = Color(0xFFF5F5F8)

    // ---- 深色(中性炭灰, 非蓝调) ----
    val DarkBg = Color(0xFF18181B)
    val DarkSurface = Color(0xFF202024)
    val DarkSurfaceSoft = Color(0xFF26262B)
    val DarkBorder = Color(0xFF2E2E34)
    val DarkText = Color(0xFFF4F4F5)
    val DarkTextSecondary = Color(0xFFA1A1AA)
    val DarkTextMuted = Color(0xFF71717A)
    val DarkPrimary = Color(0xFFFB923C)
    val DarkPrimarySoft = Color(0xFF3A2415)
    val DarkOnPrimary = Color(0xFF1C0A00)
    val DarkSuccess = Color(0xFF4ADE80)
    val DarkSuccessSoft = Color(0xFF123524)
    val DarkError = Color(0xFFF87171)
    val DarkErrorSoft = Color(0xFF3A1717)
    val DarkWaitFg = Color(0xFFA1A1AA)
    val DarkWaitBg = Color(0xFF26262B)
    val DarkDupFg = Color(0xFF71717A)
    val DarkDupBg = Color(0xFF26262B)

    val DarkNcmBg = Color(0xFF3A2415); val DarkNcmFg = Color(0xFFFDBA74)
    val DarkQmcBg = Color(0xFF22204A); val DarkQmcFg = Color(0xFFA5B4FC)
    val DarkKgmBg = Color(0xFF10352E); val DarkKgmFg = Color(0xFF5EEAD4)
    val DarkKwmBg = Color(0xFF2E1A4E); val DarkKwmFg = Color(0xFFC4B5FD)
    val DarkExtBg = Color(0xFF26262B); val DarkExtFg = Color(0xFFB8B8C0)
    val DarkDropBorder = Color(0xFF3A3A42)
    val DarkCardBorder = Color(0xFF2A2A30)
    val DarkRowDivider = Color(0xFF26262B)
}

/** 当前主题下的设计 token(按背景亮度判定深浅)。 */
internal data class CleanTokens(
    val bg: Color,
    val surface: Color,
    val surfaceSoft: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primary: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val success: Color,
    val successSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val waitFg: Color,
    val waitBg: Color,
    val dupFg: Color,
    val dupBg: Color,
    val ncmBg: Color,
    val ncmFg: Color,
    val qmcBg: Color,
    val qmcFg: Color,
    val kgmBg: Color,
    val kgmFg: Color,
    val kwmBg: Color,
    val kwmFg: Color,
    val extBg: Color,
    val extFg: Color,
    val dropBorder: Color,
    val cardBorder: Color,
    val rowDivider: Color,
)

@Composable
internal fun cleanTokens(): CleanTokens {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (isLight) {
        CleanTokens(
            bg = MusicUnlockColors.LightBg,
            surface = MusicUnlockColors.LightSurface,
            surfaceSoft = MusicUnlockColors.LightSurfaceSoft,
            border = MusicUnlockColors.LightBorder,
            text = MusicUnlockColors.LightText,
            textSecondary = MusicUnlockColors.LightTextSecondary,
            textMuted = MusicUnlockColors.LightTextMuted,
            primary = MusicUnlockColors.LightPrimary,
            primarySoft = MusicUnlockColors.LightPrimarySoft,
            onPrimary = MusicUnlockColors.LightOnPrimary,
            success = MusicUnlockColors.LightSuccess,
            successSoft = MusicUnlockColors.LightSuccessSoft,
            error = MusicUnlockColors.LightError,
            errorSoft = MusicUnlockColors.LightErrorSoft,
            waitFg = MusicUnlockColors.LightWaitFg,
            waitBg = MusicUnlockColors.LightWaitBg,
            dupFg = MusicUnlockColors.LightDupFg,
            dupBg = MusicUnlockColors.LightDupBg,
            ncmBg = MusicUnlockColors.LightNcmBg,
            ncmFg = MusicUnlockColors.LightNcmFg,
            qmcBg = MusicUnlockColors.LightQmcBg,
            qmcFg = MusicUnlockColors.LightQmcFg,
            kgmBg = MusicUnlockColors.LightKgmBg,
            kgmFg = MusicUnlockColors.LightKgmFg,
            kwmBg = MusicUnlockColors.LightKwmBg,
            kwmFg = MusicUnlockColors.LightKwmFg,
            extBg = MusicUnlockColors.LightExtBg,
            extFg = MusicUnlockColors.LightExtFg,
            dropBorder = MusicUnlockColors.LightDropBorder,
            cardBorder = MusicUnlockColors.LightCardBorder,
            rowDivider = MusicUnlockColors.LightRowDivider,
        )
    } else {
        CleanTokens(
            bg = MusicUnlockColors.DarkBg,
            surface = MusicUnlockColors.DarkSurface,
            surfaceSoft = MusicUnlockColors.DarkSurfaceSoft,
            border = MusicUnlockColors.DarkBorder,
            text = MusicUnlockColors.DarkText,
            textSecondary = MusicUnlockColors.DarkTextSecondary,
            textMuted = MusicUnlockColors.DarkTextMuted,
            primary = MusicUnlockColors.DarkPrimary,
            primarySoft = MusicUnlockColors.DarkPrimarySoft,
            onPrimary = MusicUnlockColors.DarkOnPrimary,
            success = MusicUnlockColors.DarkSuccess,
            successSoft = MusicUnlockColors.DarkSuccessSoft,
            error = MusicUnlockColors.DarkError,
            errorSoft = MusicUnlockColors.DarkErrorSoft,
            waitFg = MusicUnlockColors.DarkWaitFg,
            waitBg = MusicUnlockColors.DarkWaitBg,
            dupFg = MusicUnlockColors.DarkDupFg,
            dupBg = MusicUnlockColors.DarkDupBg,
            ncmBg = MusicUnlockColors.DarkNcmBg,
            ncmFg = MusicUnlockColors.DarkNcmFg,
            qmcBg = MusicUnlockColors.DarkQmcBg,
            qmcFg = MusicUnlockColors.DarkQmcFg,
            kgmBg = MusicUnlockColors.DarkKgmBg,
            kgmFg = MusicUnlockColors.DarkKgmFg,
            kwmBg = MusicUnlockColors.DarkKwmBg,
            kwmFg = MusicUnlockColors.DarkKwmFg,
            extBg = MusicUnlockColors.DarkExtBg,
            extFg = MusicUnlockColors.DarkExtFg,
            dropBorder = MusicUnlockColors.DarkDropBorder,
            cardBorder = MusicUnlockColors.DarkCardBorder,
            rowDivider = MusicUnlockColors.DarkRowDivider,
        )
    }
}

private val LightColors = lightColorScheme(
    primary = MusicUnlockColors.LightPrimary,
    onPrimary = MusicUnlockColors.LightOnPrimary,
    primaryContainer = MusicUnlockColors.LightPrimarySoft,
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = MusicUnlockColors.LightSuccess,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = MusicUnlockColors.LightPrimary,
    background = MusicUnlockColors.LightBg,
    onBackground = MusicUnlockColors.LightText,
    surface = MusicUnlockColors.LightSurface,
    onSurface = MusicUnlockColors.LightText,
    surfaceVariant = MusicUnlockColors.LightSurfaceSoft,
    onSurfaceVariant = MusicUnlockColors.LightTextSecondary,
    outline = MusicUnlockColors.LightBorder,
    outlineVariant = Color(0xFFE0E0E6),
    error = MusicUnlockColors.LightError,
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = MusicUnlockColors.DarkPrimary,
    onPrimary = MusicUnlockColors.DarkOnPrimary,
    primaryContainer = MusicUnlockColors.DarkPrimarySoft,
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = MusicUnlockColors.DarkSuccess,
    onSecondary = Color(0xFF00280F),
    tertiary = MusicUnlockColors.DarkPrimary,
    background = MusicUnlockColors.DarkBg,
    onBackground = MusicUnlockColors.DarkText,
    surface = MusicUnlockColors.DarkSurface,
    onSurface = MusicUnlockColors.DarkText,
    surfaceVariant = MusicUnlockColors.DarkSurfaceSoft,
    onSurfaceVariant = MusicUnlockColors.DarkTextSecondary,
    outline = MusicUnlockColors.DarkBorder,
    outlineVariant = Color(0xFF33333A),
    error = MusicUnlockColors.DarkError,
    onError = Color(0xFF2B0A0A),
)

@Composable
fun MusicUnlockTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
