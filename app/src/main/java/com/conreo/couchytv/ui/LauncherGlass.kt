package com.conreo.couchytv.ui

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Stable
internal class LauncherBackdrop(
    val photo: ImageBitmap?,
    val preset: Brush,
    val scrim: Brush?,
    val isVideo: Boolean,
    val overlayAlpha: State<Float>,
) {
    var frame by mutableStateOf<ImageBitmap?>(null)
    var frameVisibility by mutableStateOf<(() -> Float)?>(null)
}

internal val LocalLauncherBackdrop = compositionLocalOf<LauncherBackdrop?> { null }

private data class GlassPosition(val origin: Offset, val viewport: Size)

@Composable
internal fun Modifier.launcherGlass(radius: Dp): Modifier {
    val backdrop = LocalLauncherBackdrop.current
    val shape = remember(radius) { SmoothCornerShape(radius) }
    var position by remember { mutableStateOf<GlassPosition?>(null) }
    return this
        .onGloballyPositioned { coordinates ->
            val root = coordinates.findRootCoordinates().size
            position = GlassPosition(coordinates.positionInRoot(), Size(root.width.toFloat(), root.height.toFloat()))
        }
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val faceLight = Brush.verticalGradient(
                0f to Color(0x18F4FAFF),
                0.24f to Color(0x04F4FAFF),
                0.70f to Color.Transparent,
                1f to Color(0x14071421),
            )
            val rim = Brush.linearGradient(
                0f to Color(0xAAF4FAFF),
                0.36f to Color(0x3CE8F4FF),
                0.62f to Color(0x267D9AB5),
                1f to Color(0x8BD5E9FF),
                start = Offset.Zero,
                end = Offset(size.width * 0.68f, size.height),
            )
            val rimWidth = 2.dp.toPx()
            val displacement = minOf(6.dp.toPx(), size.minDimension * 0.12f)
            val falloff = minOf(26.dp.toPx(), size.minDimension / 2f)
            val bitmapPaint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)
            val layerPaint = Paint()
            val scrimScope = CanvasDrawScope()
            val bounds = Rect(Offset.Zero, size)
            var mesh: GlassMesh? = null
            var presetViewport = Size.Zero
            var presetImage: ImageBitmap? = null

            onDrawBehind {
                val location = position
                if (backdrop != null && location != null && location.viewport.minDimension > 0f && falloff > 0f) {
                    val (origin, viewport) = location
                    val frame = backdrop.frame
                    val visibility = backdrop.frameVisibility?.invoke() ?: 1f
                    val opacity = (1f - backdrop.overlayAlpha.value) * when {
                        backdrop.isVideo -> if (frame == null) 0f else visibility * 0.50f
                        frame != null -> visibility
                        else -> 1f
                    }
                    if (opacity > 0.01f) {
                        val image = frame ?: backdrop.photo ?: run {
                            if (presetViewport != viewport) {
                                val width = 320
                                val height = (width * viewport.height / viewport.width).roundToInt().coerceAtLeast(1)
                                val texture = ImageBitmap(width, height)
                                CanvasDrawScope().draw(this, layoutDirection, Canvas(texture), Size(width.toFloat(), height.toFloat())) {
                                    drawRect(backdrop.preset)
                                }
                                presetViewport = viewport
                                presetImage = texture
                            }
                            checkNotNull(presetImage)
                        }
                        val imageAspect = image.width.toFloat() / image.height
                        val existing = mesh
                        val current = if (existing?.position == location && existing.imageAspect == imageAspect) existing
                            else buildGlassMesh(location, imageAspect, size, falloff, displacement).also { mesh = it }
                        drawIntoCanvas { canvas ->
                            if (opacity < 0.999f) {
                                layerPaint.alpha = opacity
                                canvas.saveLayer(bounds, layerPaint)
                            }
                            canvas.nativeCanvas.drawBitmapMesh(
                                image.asAndroidBitmap(), current.columns, current.rows, current.vertices, 0, null, 0, bitmapPaint,
                            )
                            backdrop.scrim?.let { scrim ->
                                // Resolve the gradient against the wallpaper bounds, not the panel.
                                canvas.save()
                                canvas.translate(-origin.x, -origin.y)
                                scrimScope.draw(this, layoutDirection, canvas, viewport) {
                                    drawRect(scrim)
                                }
                                canvas.restore()
                            }
                            if (opacity < 0.999f) canvas.restore()
                        }
                    }
                }
                drawRect(faceLight)
                drawOutline(outline, rim, style = Stroke(rimWidth))
            }
        }
}

private class GlassMesh(
    val position: GlassPosition,
    val imageAspect: Float,
    val columns: Int,
    val rows: Int,
    val vertices: FloatArray,
)

private fun buildGlassMesh(
    position: GlassPosition,
    imageAspect: Float,
    panel: Size,
    falloff: Float,
    displacement: Float,
): GlassMesh {
    val (origin, viewport) = position
    // Match the wallpaper's center crop. The mesh moves only around the rim,
    // so objects in the flat center stay aligned with the surrounding image.
    val width = maxOf(viewport.width, viewport.height * imageAspect)
    val height = maxOf(viewport.height, viewport.width / imageAspect)
    val left = (viewport.width - width) / 2f - origin.x
    val top = (viewport.height - height) / 2f - origin.y
    val columns = ceil(viewport.width / (falloff * 0.65f)).toInt().coerceIn(24, 100)
    val rows = ceil(viewport.height / (falloff * 0.65f)).toInt().coerceIn(18, 64)
    val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
    val horizontal = FloatArray(columns + 1) { column ->
        bendGlassEdge(left + width * column / columns, panel.width, falloff, displacement)
    }
    var vertex = 0
    for (row in 0..rows) {
        val y = bendGlassEdge(top + height * row / rows, panel.height, falloff, displacement)
        for (x in horizontal) {
            vertices[vertex++] = x
            vertices[vertex++] = y
        }
    }
    return GlassMesh(position, imageAspect, columns, rows, vertices)
}

private fun bendGlassEdge(point: Float, length: Float, falloff: Float, displacement: Float): Float {
    fun bevel(distance: Float): Float {
        val t = (1f - abs(distance) / falloff).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    return point + displacement * (bevel(point) - bevel(point - length))
}

@Composable
internal fun SampleGlassVideo(
    view: SurfaceView?,
    backdrop: LauncherBackdrop?,
    enabled: Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(view, backdrop, enabled, lifecycle) {
        if (!enabled || view == null || backdrop == null) return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val sample = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888)
            try {
                while (true) {
                    if (view.holder.surface.isValid && copyFrame(view, sample, handler)) {
                        backdrop.frame = withContext(Dispatchers.Default) {
                            sample.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
                        }
                    }
                    delay(125)
                }
            } finally {
                backdrop.frame = null
            }
        }
    }
}

private suspend fun copyFrame(view: SurfaceView, bitmap: Bitmap, handler: Handler): Boolean =
    suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(view, bitmap, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, handler)
        } catch (_: IllegalArgumentException) {
            continuation.resume(false) // The surface can disappear between the validity check and the copy.
        }
    }
