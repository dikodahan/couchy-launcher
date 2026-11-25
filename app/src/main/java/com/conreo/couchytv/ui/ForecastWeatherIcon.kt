package com.conreo.couchytv.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.conreo.couchytv.data.WeatherIconKind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun ColoredForecastIcon(
    kind: WeatherIconKind,
    isDay: Boolean,
    phase: State<Float>?,
    modifier: Modifier,
    description: String?,
) {
    val semantics = if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
        role = Role.Image
    }
    Spacer(modifier.clipToBounds().then(semantics).drawWithCache {
        val edge = size.minDimension.roundToInt().coerceAtLeast(1)
        val rasterSize = IntSize(edge, edge)
        val bitmap = ImageBitmap(32, 32)
        val bitmapCanvas = Canvas(bitmap)
        val iconScope = CanvasDrawScope()
        val raster = bitmap.asAndroidBitmap()

        onDrawBehind {
            // Draw in the artwork's 32-pixel viewport. Scaling the finished
            // image keeps all the moving parts aligned on TV renderers.
            raster.eraseColor(android.graphics.Color.TRANSPARENT)
            val progress = phase?.value
            iconScope.draw(this, layoutDirection, bitmapCanvas, Size(32f, 32f)) {
                drawWeather(kind, isDay, progress)
            }
            val left = ((size.width - edge) / 2f).roundToInt()
            val top = ((size.height - edge) / 2f).roundToInt()
            drawImage(bitmap, dstOffset = IntOffset(left, top), dstSize = rasterSize)
        }
    })
}

private val sunFill = Brush.linearGradient(
    listOf(Color(0xFFFFF3A3), Color(0xFFFFC24F), Color(0xFFF5933D)),
    start = Offset(10f, 9f), end = Offset(23f, 24f),
)
private val moonFill = Brush.linearGradient(
    listOf(Color(0xFFFCF2E3), Color(0xFFCCC7FF), Color(0xFF928FF2)),
    start = Offset(8f, 6f), end = Offset(24f, 26f),
)
private val moonShape = Path().apply {
    moveTo(15.8f, 4.4f)
    cubicTo(11.3f, 14.6f, 19.5f, 22.5f, 27.1f, 19.0f)
    cubicTo(25.0f, 25.7f, 18.4f, 28.8f, 12.3f, 26.5f)
    cubicTo(1.8f, 22.9f, 3.9f, 7.6f, 15.8f, 4.4f)
    close()
}
private val cloudShape = Path().apply {
    moveTo(9.4f, 22f)
    cubicTo(2.3f, 22f, 1.4f, 12.3f, 8.8f, 11.4f)
    cubicTo(10.5f, 3.3f, 22.8f, 3f, 24.1f, 12f)
    cubicTo(31.3f, 11.1f, 32f, 22f, 24.6f, 22f)
    close()
}
private val brightCloud = Brush.verticalGradient(
    listOf(Color(0xFFFBFCFF), Color(0xFFC8DAF7), Color(0xFF8EA8D6)),
    startY = 6f, endY = 22f,
)
private val darkCloud = Brush.verticalGradient(
    listOf(Color(0xFFC4CCEE), Color(0xFF8A9BC9), Color(0xFF5C70A3)),
    startY = 6f, endY = 22f,
)
private val lightningShape = Path().apply {
    moveTo(17f, 14f)
    lineTo(23f, 14f)
    lineTo(19.6f, 20.9f)
    lineTo(24f, 20.9f)
    lineTo(15.8f, 30f)
    lineTo(17.4f, 23.1f)
    lineTo(13.1f, 23.1f)
    close()
}
private val lightningFill = Brush.verticalGradient(
    listOf(Color(0xFFFFF4C5), Color(0xFFFFCF59), Color(0xFFFFA83F)),
    startY = 14f, endY = 30f,
)
private val rainBlue = Color(0xFF62CFFF)
private val frostBlue = Color(0xFFCAF6FF)
private val center = Offset(16f, 16f)

private fun DrawScope.drawWeather(kind: WeatherIconKind, isDay: Boolean, phase: Float?) {
    val drift = phase?.let(::wave) ?: 0f
    when (kind) {
        WeatherIconKind.Clear -> drawSky(isDay, phase)
        WeatherIconKind.PartlyCloudy -> {
            translate(-6f, -5f) {
                scale(0.74f, pivot = center) { drawSky(isDay, phase) }
            }
            translate(4f + drift, 5.5f) {
                scale(0.76f, pivot = center) { drawCloud(brightCloud) }
            }
        }
        WeatherIconKind.Cloudy -> {
            translate(3.5f + drift * 1.6f, -4f) {
                scale(0.72f, pivot = center) { drawCloud(darkCloud) }
            }
            translate(-1f - drift * 0.7f, 3f) { drawCloud(brightCloud) }
        }
        WeatherIconKind.Fog -> {
            translate(0f, -3f) { drawCloud(brightCloud) }
            val haze = Color(0xFFB9D9EA)
            drawLine(haze, Offset(4f + drift * 2f, 21f), Offset(25f + drift * 2f, 21f), 1.8f, StrokeCap.Round)
            drawLine(haze, Offset(8f - drift * 2f, 25f), Offset(28f - drift * 2f, 25f), 1.8f, StrokeCap.Round, alpha = 0.82f)
            drawLine(haze, Offset(6f + drift, 29f), Offset(20f + drift, 29f), 1.55f, StrokeCap.Round, alpha = 0.60f)
        }
        WeatherIconKind.Rain -> {
            for (drop in 0..2) drawRain(9f + drop * 7f, phase, drop * 0.24f, 0.25f + drop * 0.2f)
            translate(0f, -3.5f) { drawCloud(darkCloud) }
        }
        WeatherIconKind.Snow -> {
            for (flake in 0..2) drawSnow(8f + flake * 7.5f, phase, flake * 0.27f, 0.26f + flake * 0.23f)
            translate(0f, -3.5f) { drawCloud(brightCloud) }
        }
        WeatherIconKind.Storm -> {
            drawRain(8f, phase, 0f, 0.52f)
            drawRain(28f, phase, 0.48f, 0.35f)
            translate(0f, -3.5f) { drawCloud(darkCloud) }
            val flash = phase?.let { (1f - 7f * kotlin.math.abs(it - 0.38f)).coerceIn(0f, 1f) } ?: 1f
            val opacity = 0.58f + flash * 0.42f
            drawPath(lightningShape, lightningFill, alpha = opacity)
        }
    }
}

private fun DrawScope.drawSky(isDay: Boolean, phase: Float?) {
    if (isDay) {
        rotate((phase ?: 0f) * 45f, pivot = center) {
            for (ray in 0..7) {
                rotate(ray * 45f, pivot = center) {
                    drawLine(Color(0xFFFFCE65), Offset(16f, 2.9f), Offset(16f, 6.2f), 1.8f, StrokeCap.Round)
                }
            }
        }
        drawCircle(sunFill, radius = 8f, center = center)
        drawOval(Color(0xFFFFF4BD), topLeft = Offset(10.8f, 10.7f), size = Size(7f, 3f), alpha = 0.42f)
    } else {
        drawPath(moonShape, moonFill)
        drawCircle(Color(0xFF938AE1), 1.8f, Offset(11f, 18.8f), alpha = 0.36f)
        drawCircle(Color(0xFF938AE1), 1.05f, Offset(17.5f, 23.4f), alpha = 0.30f)
        val twinkle = phase?.let(::wave) ?: 0f
        drawStar(23.5f, 8f, 2.3f + twinkle * 0.55f, 0.82f + twinkle * 0.18f)
        drawStar(28f, 14f, 1.3f - twinkle * 0.35f, 0.72f - twinkle * 0.24f)
    }
}

private fun DrawScope.drawStar(x: Float, y: Float, radius: Float, opacity: Float) {
    val star = Color(0xFFFFE7A7)
    drawLine(star, Offset(x - radius, y), Offset(x + radius, y), 1.05f, StrokeCap.Round, alpha = opacity)
    drawLine(star, Offset(x, y - radius), Offset(x, y + radius), 1.05f, StrokeCap.Round, alpha = opacity)
}

private fun DrawScope.drawCloud(fill: Brush) {
    translate(0f, 1.15f) { drawPath(cloudShape, Color(0xFF233B67), alpha = 0.38f) }
    drawPath(cloudShape, fill)
}

private fun DrawScope.drawRain(x: Float, phase: Float?, offset: Float, still: Float) {
    val fall = phase?.let { (it * 2f + offset) % 1f } ?: still
    val y = 17.2f + fall * 10.8f
    val startX = x - fall * 3.4f
    drawLine(
        rainBlue, Offset(startX, y), Offset(startX - 1.4f, y + 3.1f),
        strokeWidth = 1.85f, cap = StrokeCap.Round, alpha = fallOpacity(fall),
    )
}

private fun DrawScope.drawSnow(x: Float, phase: Float?, offset: Float, still: Float) {
    val fall = phase?.let { (it + offset) % 1f } ?: still
    val y = 18.8f + fall * 10.5f
    val flake = Offset(x + wave(fall) * 1.15f, y)
    val opacity = fallOpacity(fall)
    for (arm in 0..2) {
        rotate(arm * 60f + fall * 60f, pivot = flake) {
            drawLine(frostBlue, flake - Offset(1.75f, 0f), flake + Offset(1.75f, 0f), 1f, StrokeCap.Round, alpha = opacity)
        }
    }
}

private fun fallOpacity(fall: Float): Float =
    minOf(fall * 6f, (1f - fall) * 6f, 1f)

private fun wave(phase: Float): Float = sin(phase * 2.0 * PI).toFloat()
