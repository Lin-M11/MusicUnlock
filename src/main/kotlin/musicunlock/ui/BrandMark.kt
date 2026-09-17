package musicunlock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val TileTop = Color(0xFF342A26)
private val TileBottom = Color(0xFF15110F)
private val InkTop = Color(0xFFFFFDFC)
private val InkBottom = Color(0xFFF2E8E1)
private val AccentTop = Color(0xFFFF8A3D)
private val AccentBottom = Color(0xFFEA580C)

/** 品牌徽记：象牙白 M 字航道，橙色折点标记转换；右侧肩部斜切让轮廓具有开放感。 */
@Composable
internal fun LogoMark(side: Dp) {
    val shape = RoundedCornerShape(side * 208f / 1024f)
    Box(
        modifier = Modifier
            .size(side)
            .shadow(
                elevation = side * 0.10f,
                shape = shape,
                spotColor = Color.Black.copy(alpha = 0.18f),
                ambientColor = Color.Black.copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(TileTop, TileBottom)))
            .border(0.5.dp, Color.White.copy(alpha = 0.07f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / 1024f
            drawPath(
                path = appMPath(scale),
                brush = Brush.linearGradient(
                    colors = listOf(InkTop, InkBottom),
                    start = Offset(294f * scale, 322f * scale),
                    end = Offset(730f * scale, 714f * scale),
                ),
                style = Stroke(width = 96f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path = appAccentPath(scale),
                brush = Brush.linearGradient(
                    colors = listOf(AccentTop, AccentBottom),
                    start = Offset(438f * scale, 548f * scale),
                    end = Offset(586f * scale, 622f * scale),
                ),
                style = Stroke(width = 34f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun appMPath(scale: Float): Path {
    fun x(value: Float) = value * scale
    fun y(value: Float) = value * scale
    return Path().apply {
        moveTo(x(294f), y(714f))
        lineTo(x(294f), y(365f))
        cubicTo(x(294f), y(336f), x(331f), y(322f), x(351f), y(343f))
        lineTo(x(454f), y(446f))
        cubicTo(x(488f), y(480f), x(536f), y(480f), x(570f), y(446f))
        lineTo(x(673f), y(343f))
        cubicTo(x(693f), y(322f), x(730f), y(336f), x(730f), y(365f))
        lineTo(x(730f), y(714f))
    }
}

private fun appAccentPath(scale: Float): Path {
    fun x(value: Float) = value * scale
    fun y(value: Float) = value * scale
    return Path().apply {
        moveTo(x(438f), y(548f))
        lineTo(x(512f), y(622f))
        lineTo(x(586f), y(548f))
    }
}
