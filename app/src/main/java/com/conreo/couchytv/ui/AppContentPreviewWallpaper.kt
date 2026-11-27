package com.conreo.couchytv.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.conreo.couchytv.data.TopShelf
import com.conreo.couchytv.data.TrailerFallback
import com.conreo.couchytv.data.publisherYouTubeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LocalAppContentPreviewFocus = compositionLocalOf<((Any, String, Boolean) -> Unit)?> { null }

internal data class AppPreviewSlide(
    val artwork: ImageBitmap,
    val trailer: Uri? = null,
    val youtubeId: String? = null,
    val finished: CompletableDeferred<Boolean>? = null,
    val manualTrailerAvailable: Boolean = false,
    val manualPlayback: Boolean = false,
)

internal class AppPreviewDeck {
    private var requestId = 0
    internal data class Selection(val index: Int, val requestId: Int, val playTrailer: Boolean = false)

    internal val selection = MutableStateFlow(Selection(0, requestId))
    var slide by mutableStateOf<AppPreviewSlide?>(null)
        internal set
    var index by mutableIntStateOf(0)
        internal set
    var count by mutableIntStateOf(0)
        internal set

    fun move(delta: Int) {
        if (count < 2) return
        index = (index + delta).mod(count)
        // Dispose the active native or web player even if artwork is still loading.
        slide = slide?.let { AppPreviewSlide(it.artwork) }
        selection.value = Selection(index, ++requestId)
    }

    fun playCurrentTrailer() {
        val current = slide?.takeIf { it.manualTrailerAvailable } ?: return
        slide = current.copy(manualTrailerAvailable = false)
        selection.value = Selection(index, ++requestId, playTrailer = true)
    }

    fun stopManualTrailer() {
        if (!selection.value.playTrailer) return
        slide = slide?.let { AppPreviewSlide(it.artwork) }
        selection.value = Selection(index, ++requestId)
    }

    internal fun reset() {
        slide = null
        index = 0
        count = 0
        selection.value = Selection(0, ++requestId)
    }
}

@Composable
internal fun rememberAppContentPreview(
    packageName: String?,
    playTrailers: Boolean,
    fallbackCredential: String?,
): AppPreviewDeck? {
    val cache = remember {
        object : LruCache<Pair<String, Uri>, ImageBitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: Pair<String, Uri>, value: ImageBitmap) = value.width * value.height * 4
        }
    }
    if (packageName == null) return null
    return key(packageName) {
        val context = LocalContext.current.applicationContext
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val deck = remember(playTrailers, fallbackCredential) { AppPreviewDeck() }
        LaunchedEffect(packageName, lifecycle, playTrailers, fallbackCredential) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    // Fast D-pad movement should never query or download artwork.
                    delay(400)
                    val programs = withContext(Dispatchers.IO) {
                        readPreview {
                            TopShelf.programs(context, packageName, includeTrailers = true, includeFallback = fallbackCredential != null)
                        }
                    }
                    val slides = programs.orEmpty().toMutableList()
                    Log.d("CouchyPreview", "$packageName: ${slides.size} listings, " +
                        "${slides.count { it.trailer != null }} publisher trailers, " +
                        "${slides.count { it.fallback != null }} TMDB candidates")
                    coroutineScope {
                        // Keep artwork loads independent of page changes. A fast
                        // remote press can switch to an already prepared image.
                        val artwork = slides.associateWith { program ->
                            async(Dispatchers.IO) {
                                val key = packageName to program.images.first()
                                cache.get(key) ?: program.images.firstNotNullOfOrNull { loadPreview(context, it) }
                                    ?.also { cache.put(key, it) }
                            }
                        }
                        deck.count = slides.size
                        deck.selection.collectLatest { selection ->
                            var index = selection.index.coerceAtMost((slides.size - 1).coerceAtLeast(0))
                            while (slides.isNotEmpty()) {
                                val program = slides[index]
                                val image = artwork.getValue(program).await()
                                if (image == null) {
                                    slides.removeAt(index)
                                    deck.count = slides.size
                                    if (index == slides.size) index = 0
                                    continue
                                }
                                val trailer = program.trailer
                                val lookup = program.fallback
                                val hasTrailer = trailer != null || lookup != null
                                val manual = selection.playTrailer
                                val still = AppPreviewSlide(image, manualTrailerAvailable = !playTrailers && !manual && hasTrailer)
                                deck.index = index
                                deck.slide = still
                                suspend fun play(uri: Uri? = null, youtubeId: String? = null): Boolean {
                                    val finished = CompletableDeferred<Boolean>()
                                    deck.slide = still.copy(
                                        trailer = uri, youtubeId = youtubeId, finished = finished, manualPlayback = manual,
                                    )
                                    val completed = finished.await()
                                    deck.slide = still
                                    return completed
                                }
                                var played = false
                                if (hasTrailer && (playTrailers || manual)) {
                                    if (!manual) delay(2_500)
                                    val publisherId = trailer?.let { publisherYouTubeId(it.toString()) }
                                    played = when {
                                        publisherId != null -> play(youtubeId = publisherId)
                                        trailer != null -> play(uri = trailer)
                                        else -> false
                                    }
                                    if (!played && lookup != null && fallbackCredential != null) {
                                        val ids = readPreview { TrailerFallback.youtubeTrailers(lookup, fallbackCredential) }.orEmpty()
                                        Log.d("CouchyPreview", "$packageName: ${ids.size} TMDB trailer matches")
                                        for (id in ids) {
                                            if (id == publisherId) continue
                                            played = play(youtubeId = id)
                                            if (played) break
                                        }
                                    }
                                }
                                if (manual) {
                                    deck.slide = still.copy(manualTrailerAvailable = played)
                                    // Return to the same poster after a deliberate play.
                                    awaitCancellation()
                                }
                                if (slides.size == 1) break
                                delay(if (played) 1_000 else 7_000)
                                index = (index + 1) % slides.size
                            }
                            // Hold the last slide until navigation or lifecycle changes.
                            awaitCancellation()
                        }
                    }
                } finally {
                    deck.reset()
                }
            }
        }
        deck
    }
}

@Composable
internal fun AppContentPreviewWallpaper(
    slide: AppPreviewSlide,
    muteAutoplay: Boolean,
    coverBrush: Brush,
    subtitleLanguage: String?,
    onTrailerReady: () -> Unit,
) {
    val muted = muteAutoplay && !slide.manualPlayback
    val youtubeId = slide.youtubeId
    if (youtubeId != null) {
        key(slide.finished) {
            YouTubePreview(
                videoId = youtubeId,
                muted = muted,
                subtitleLanguage = subtitleLanguage,
                artwork = slide.artwork,
                coverBrush = coverBrush,
                onPlaying = onTrailerReady,
                onFinished = { slide.finished?.complete(it) },
            )
        }
    } else if (slide.trailer != null) {
        key(slide.finished) {
            VideoWallpaper(
                uri = slide.trailer.toString(),
                loop = false,
                coverBrush = coverBrush,
                coverArtwork = slide.artwork,
                glassActive = false,
                playOnce = true,
                muted = muted,
                subtitleLanguage = subtitleLanguage,
                onReady = { onTrailerReady() },
                onError = { slide.finished?.complete(false) },
                onEnded = { slide.finished?.complete(true) },
            )
        }
    } else {
        Crossfade(targetState = slide.artwork, animationSpec = tween(450), label = "appContentPreview") { artwork ->
            AppPreviewArtwork(artwork, coverBrush, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun AppPreviewArtwork(artwork: ImageBitmap, matte: Brush, modifier: Modifier) {
    Box(modifier.background(matte)) {
        Image(artwork, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
