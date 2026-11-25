package com.conreo.couchytv.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

internal enum class GlassPanel { Status, Dock }

internal data class GlassLens(val bounds: Rect, val radius: Float, val falloff: Float, val displacement: Float)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoGlassSurface(
    player: ExoPlayer?,
    useFilter: Boolean,
    filterActive: Boolean,
) {
    val host = LocalView.current
    if (Build.VERSION.SDK_INT >= 33 && useFilter && host.isHardwareAccelerated &&
        FilteredVideoSurface(player, filterActive)
    ) return

    AndroidView(
        factory = ::SurfaceView,
        modifier = Modifier.fillMaxSize(),
        onRelease = { player?.clearVideoSurfaceView(it) },
        update = { surface ->
            player?.let {
                it.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                it.setVideoSurfaceView(surface)
            }
        },
    )
}

@RequiresApi(33)
@Composable
private fun FilteredVideoSurface(player: ExoPlayer?, active: Boolean): Boolean {
    val shader = remember {
        try {
            RuntimeShader(VIDEO_GLASS_SHADER)
        } catch (failure: IllegalArgumentException) {
            Log.w("LiteTV", "Video glass shader is unavailable", failure)
            null
        }
    } ?: return false
    val backdrop = LocalLauncherBackdrop.current
    val showLens = active && (backdrop?.overlayAlpha?.value ?: 0f) < 0.98f
    val status = if (showLens) backdrop?.videoLens(GlassPanel.Status) else null
    val dock = if (showLens) backdrop?.videoLens(GlassPanel.Dock) else null
    AndroidView(
        factory = { FilteredVideoView(it, shader) },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.bind(null) },
        update = { view ->
            view.bind(player)
            view.updateLens(status, dock)
        },
    )
    return true
}

@RequiresApi(33)
private class FilteredVideoView(context: Context, private val shader: RuntimeShader) :
    FrameLayout(context), Player.Listener {
    // The RenderNode can sample a TextureView. A SurfaceView composes independently.
    private val texture = TextureView(context)
    private var player: ExoPlayer? = null
    private var status: GlassLens? = null
    private var dock: GlassLens? = null

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun bind(next: ExoPlayer?) {
        if (next === player) return
        player?.removeListener(this)
        player?.clearVideoTextureView(texture)
        player = next
        next?.let {
            it.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            it.setVideoTextureView(texture)
        }
        next?.addListener(this)
        updateCrop(next?.videoSize ?: VideoSize.UNKNOWN)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        updateCrop(videoSize)
    }

    fun updateLens(status: GlassLens?, dock: GlassLens?) {
        if (this.status == status && this.dock == dock) return
        this.status = status
        this.dock = dock
        applyFilter()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCrop(player?.videoSize ?: VideoSize.UNKNOWN)
        applyFilter()
    }

    private fun updateCrop(videoSize: VideoSize) {
        val transform = Matrix()
        if (width > 0 && height > 0 && videoSize.width > 0 && videoSize.height > 0) {
            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) / (width.toFloat() / height)
            if (ratio.isFinite() && ratio > 0f) {
                transform.setScale(maxOf(1f, ratio), maxOf(1f, 1f / ratio), width / 2f, height / 2f)
            }
        }
        texture.setTransform(transform)
    }

    private fun applyFilter() {
        if (width <= 0 || height <= 0 || (status == null && dock == null)) {
            setRenderEffect(null)
            return
        }
        shader.setFloatUniform("viewport", width.toFloat(), height.toFloat())
        setLens("status", status)
        setLens("dock", dock)
        setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "video"))
    }

    private fun setLens(name: String, lens: GlassLens?) {
        val bounds = lens?.bounds ?: Rect.Zero
        shader.setFloatUniform("${name}Bounds", bounds.left, bounds.top, bounds.right, bounds.bottom)
        shader.setFloatUniform("${name}Optics", lens?.radius ?: 0f, lens?.falloff ?: 1f, lens?.displacement ?: 0f)
    }
}

// Refract the GPU video texture near the two bevels. The original frame is sampled
// once per output pixel; there are no CPU reads, bitmap copies or per-frame Compose updates.
private const val VIDEO_GLASS_SHADER = """
uniform shader video;
uniform float2 viewport;
uniform float4 statusBounds;
uniform float3 statusOptics;
uniform float4 dockBounds;
uniform float3 dockOptics;

float2 refractAt(float2 point, float4 bounds, float3 optics) {
    if (optics.z <= 0.0 || point.x < bounds.x || point.y < bounds.y ||
        point.x > bounds.z || point.y > bounds.w) {
        return point;
    }
    float2 center = (bounds.xy + bounds.zw) * 0.5;
    float2 corner = abs(point - center) - (bounds.zw - bounds.xy) * 0.5 + optics.x;
    float edge = length(max(corner, float2(0.0))) + min(max(corner.x, corner.y), 0.0) - optics.x;
    float strength = (1.0 - smoothstep(-2.0, 0.0, edge)) * optics.z;
    float2 leading = 1.0 - smoothstep(float2(0.0), float2(optics.y), point - bounds.xy);
    float2 trailing = 1.0 - smoothstep(float2(0.0), float2(optics.y), bounds.zw - point);
    return point - strength * (leading - trailing);
}

half4 main(float2 point) {
    float2 source = refractAt(point, statusBounds, statusOptics);
    source = refractAt(source, dockBounds, dockOptics);
    return video.eval(clamp(source, float2(0.5), viewport - 0.5));
}
"""
