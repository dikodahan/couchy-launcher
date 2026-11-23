package com.conreo.couchytv.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.conreo.couchytv.data.TopShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LocalTopShelfFocus = compositionLocalOf<((Any, String, Boolean) -> Unit)?> { null }

@Composable
internal fun TopShelfWallpaper(packageName: String?) {
    val cache = remember {
        object : LruCache<Pair<String, Uri>, ImageBitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: Pair<String, Uri>, value: ImageBitmap) = value.width * value.height * 4
        }
    }
    if (packageName != null) key(packageName) {
        val context = LocalContext.current.applicationContext
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(packageName, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    // Fast D-pad movement should never query or download artwork.
                    delay(400)
                    val images = withContext(Dispatchers.IO) { readPreview { TopShelf.artwork(context, packageName) } }
                    val slides = images.orEmpty().toMutableList()
                    var index = 0
                    while (slides.isNotEmpty()) {
                        val candidates = slides[index]
                        val image = withContext(Dispatchers.IO) {
                            val key = packageName to candidates.first()
                            cache.get(key) ?: candidates.firstNotNullOfOrNull { loadPreview(context, it) }
                                ?.also { cache.put(key, it) }
                        }
                        if (image == null) {
                            slides.removeAt(index)
                            if (index == slides.size) index = 0
                            continue
                        }
                        artwork = image
                        if (slides.size == 1) break
                        delay(7_000)
                        index = (index + 1) % slides.size
                    }
                    // Keep the last successful slide until the app loses focus or Home pauses.
                    awaitCancellation()
                } finally {
                    artwork = null
                }
            }
        }
        Crossfade(targetState = artwork, animationSpec = tween(450), label = "topShelfArtwork") { slide ->
            if (slide != null) Image(slide, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        }
    }
}

private suspend fun loadPreview(context: Context, uri: Uri): ImageBitmap? =
    readPreview { TopShelf.load(context, uri)?.asImageBitmap() }

private suspend fun <T> readPreview(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null // A provider or an individual image may be unavailable on this device.
}
