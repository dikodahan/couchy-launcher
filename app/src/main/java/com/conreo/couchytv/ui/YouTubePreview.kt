package com.conreo.couchytv.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import com.conreo.couchytv.data.youtubeIdPattern
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled") // The document is bundled markup with a validated YouTube ID.
@Composable
internal fun YouTubePreview(
    videoId: String,
    muted: Boolean,
    subtitleLanguage: String?,
    artwork: ImageBitmap,
    coverBrush: Brush,
    onPlaying: () -> Unit,
    onFinished: (Boolean) -> Unit,
) {
    val reportPlaying by rememberUpdatedState(onPlaying)
    val finish by rememberUpdatedState(onFinished)
    var playing by remember { mutableStateOf(false) }
    var readyPlayer by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(readyPlayer) {
        val view = readyPlayer ?: return@LaunchedEffect
        // Wait for the embed to be uncovered before asking YouTube to autoplay.
        withFrameNanos { }
        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (view.isAttachedToWindow && readyPlayer === view) {
                    view.evaluateJavascript("couchyPlay()", null)
                }
            }
        })
    }
    LaunchedEffect(playing) {
        if (!playing) {
            delay(12_000)
            Log.w("CouchyPreview", "YouTube start timed out")
            finish(false)
        }
    }

    if (readyPlayer == null) AppPreviewArtwork(artwork, coverBrush, Modifier.fillMaxSize())
    val launcherView = LocalView.current
    DisposableEffect(launcherView, videoId, muted, subtitleLanguage) {
        // Give Chromium a regular Android view in the launcher window. This
        // avoids routing the video layer through Compose's interop drawing pass.
        val host = launcherView.rootView.findViewById<FrameLayout>(android.R.id.content)
        val webView = host?.let { runCatching { WebView(it.context) }.getOrNull() }
        if (host == null || webView == null) {
            finish(false)
            return@DisposableEffect onDispose { }
        }
        webView.apply {
            setBackgroundColor(AndroidColor.BLACK)
            alpha = 0f
            isFocusable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish(false)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    finish(false)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    when (title) {
                        "couchy-ready" -> {
                            view.alpha = 1f
                            view.invalidate()
                            readyPlayer = view
                            reportPlaying()
                        }
                        "couchy-playing" -> {
                            playing = true
                            Log.d("CouchyPreview", "YouTube trailer playing")
                        }
                        "couchy-ended" -> finish(playing)
                        "couchy-autoplay-blocked", "couchy-api-error", "couchy-buffering" -> {
                            Log.w("CouchyPreview", "YouTube playback unavailable: $title")
                            finish(false)
                        }
                        else -> if (title.orEmpty().startsWith("couchy-error:")) {
                            val code = title?.substringAfter(':')?.toIntOrNull()
                            Log.w("CouchyPreview", "YouTube playback error: $code")
                            finish(false)
                        }
                    }
                }
            }
            val origin = "https://${context.packageName}"
            loadDataWithBaseURL("$origin/", embedPage(origin, videoId, muted, subtitleLanguage), "text/html", "UTF-8", null)
        }
        host.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        onDispose {
            host.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
}

private fun embedPage(origin: String, videoId: String, muted: Boolean, subtitleLanguage: String?): String {
    require(youtubeIdPattern.matches(videoId))
    val captions = subtitleLanguage.takeIf { it != null && Regex("[A-Za-z0-9-]{2,35}").matches(it) }
        ?.let { "&amp;cc_load_policy=1&amp;cc_lang_pref=$it" }.orEmpty()
    val audio = if (muted) "player.mute();" else ""
    return """
        <!doctype html>
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body,#player{width:100%;height:100%;margin:0;border:0}#player{display:block}</style>
        <iframe id="player" src="https://www.youtube.com/embed/$videoId?enablejsapi=1&amp;playsinline=1&amp;controls=0&amp;disablekb=1&amp;fs=0&amp;rel=0&amp;origin=$origin$captions"
          referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media" title="YouTube trailer"></iframe>
        <script>
          let progress, stall, player;
          function couchyPlay() {
            if (!player) return;
            $audio
            player.playVideo();
          }
          function onYouTubeIframeAPIReady() {
            new YT.Player('player', {
              events: {
                onReady: function(event) {
                  player = event.target;
                  document.title = 'couchy-ready';
                },
                onStateChange: function(event) {
                  clearInterval(progress);
                  clearTimeout(stall);
                  if (event.data === YT.PlayerState.PLAYING) {
                    const start = event.target.getCurrentTime();
                    progress = setInterval(function() {
                      if (event.target.getCurrentTime() > start) {
                        clearInterval(progress);
                        document.title = 'couchy-playing';
                      }
                    }, 150);
                  }
                  if (event.data === YT.PlayerState.BUFFERING) {
                    stall = setTimeout(function() { document.title = 'couchy-buffering'; }, 8000);
                  }
                  if (event.data === YT.PlayerState.ENDED) document.title = 'couchy-ended';
                },
                onError: function(event) { document.title = 'couchy-error:' + event.data; },
                onAutoplayBlocked: function() { document.title = 'couchy-autoplay-blocked'; }
              }
            });
          }
        </script>
        <script src="https://www.youtube.com/iframe_api" onerror="document.title='couchy-api-error'"></script>
    """.trimIndent()
}
