package com.conreo.couchytv.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Stable
internal class LauncherBackdrop(val photo: ImageBitmap?, val preset: Brush) {
    var frame by mutableStateOf<ImageBitmap?>(null)
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
                0f to Color(0x29E6F3FF),
                0.30f to Color(0x09D9E7FF),
                0.70f to Color.Transparent,
                1f to Color(0x1CBDD9F5),
            )
            val rim = Brush.linearGradient(
                0f to Color(0xA9F4FAFF),
                0.36f to Color(0x32E8F4FF),
                0.62f to Color(0x20B6D1ED),
                1f to Color(0x83D5E9FF),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.68f, size.height),
            )
            val rimWidth = 1.5.dp.toPx()
            onDrawBehind {
                val location = position
                val image = backdrop?.frame ?: backdrop?.photo
                if (location != null && backdrop != null) {
                    val (origin, viewport) = location
                    withTransform({
                        // Slightly magnify the blurred backdrop around this panel's center.
                        scale(1.08f, 1.08f, center)
                        translate(-origin.x, -origin.y)
                    }) {
                        if (image == null) {
                            drawRect(brush = backdrop.preset, size = viewport)
                        } else {
                            val scale = maxOf(viewport.width / image.width, viewport.height / image.height)
                            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
                            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
                            drawImage(
                                image = image,
                                dstOffset = IntOffset(
                                    ((viewport.width - width) / 2f).roundToInt(),
                                    ((viewport.height - height) / 2f).roundToInt(),
                                ),
                                dstSize = IntSize(width, height),
                                filterQuality = FilterQuality.Medium,
                                alpha = 0.88f,
                            )
                        }
                    }
                }
                drawRect(Color(0x86101825))
                drawRect(faceLight)
                drawOutline(outline, rim, style = Stroke(rimWidth))
            }
        }
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
            val sample = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
            while (true) {
                if (view.holder.surface.isValid && copyFrame(view, sample, handler)) {
                    backdrop.frame = withContext(Dispatchers.Default) {
                        // A tiny filtered texture is sufficient for the two narrow glass panels.
                        Bitmap.createScaledBitmap(sample, 32, 18, true).asImageBitmap()
                    }
                }
                delay(330)
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
