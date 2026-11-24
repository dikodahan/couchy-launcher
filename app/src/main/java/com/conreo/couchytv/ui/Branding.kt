package com.conreo.couchytv.ui

import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.conreo.couchytv.R
import kotlinx.coroutines.delay

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.brand_logo),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(16)),
    )
}

/** Play the bundled, silent clip before setup. A playback failure still opens setup. */
@Composable
internal fun StartupIntro(onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val finish by rememberUpdatedState(onFinished)
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var firstFrameRendered by remember { mutableStateOf(false) }

    BackHandler { finish() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(9_000)
            Log.w("CouchyStartup", "Startup video timed out; continuing to setup")
            finish()
        }
    }

    DisposableEffect(surface, lifecycle) {
        val view = surface ?: return@DisposableEffect onDispose {}
        val player = try {
            ExoPlayer.Builder(context).build()
        } catch (error: RuntimeException) {
            Log.w("CouchyStartup", "Couldn't initialize startup video", error)
            finish()
            return@DisposableEffect onDispose {}
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("CouchyStartup", "Couldn't play startup video", error)
                finish()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.play()
                Lifecycle.Event.ON_PAUSE -> player.pause()
                else -> Unit
            }
        }
        try {
            player.addListener(listener)
            player.volume = 0f
            player.setVideoSurfaceView(view)
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.startup_intro}")
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            lifecycle.addObserver(observer)
        } catch (error: RuntimeException) {
            Log.w("CouchyStartup", "Couldn't prepare startup video", error)
            finish()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val videoWidth = minOf(maxWidth, maxHeight * (16f / 9f))
        Box(Modifier.size(videoWidth, videoWidth * (9f / 16f))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { SurfaceView(it).also { view -> surface = view } },
            )
            if (!firstFrameRendered) {
                Image(
                    painter = painterResource(R.drawable.brand_startup_poster),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
