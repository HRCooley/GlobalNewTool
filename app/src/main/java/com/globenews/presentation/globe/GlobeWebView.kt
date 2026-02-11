package com.globenews.presentation.globe

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.globenews.domain.model.NewsStory
import com.squareup.moshi.Moshi
import java.time.Duration
import java.time.Instant

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlobeWebView(
    modifier: Modifier = Modifier,
    bridge: GlobeBridge,
    stories: List<NewsStory>,
    baseLayer: String,
    onWebViewReady: (WebView) -> Unit
) {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false

            addJavascriptInterface(bridge, "AndroidBridge")

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("GlobeNews", "WEBVIEW: onPageFinished url=$url")
                }
            }

            setBackgroundColor(android.graphics.Color.BLACK)
            Log.d("GlobeNews", "WEBVIEW: loading map.html")
            loadUrl("file:///android_asset/map.html")
        }
    }

    DisposableEffect(webView) {
        onWebViewReady(webView)
        onDispose {
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}

private var lastSentStoryIds: Set<String> = emptySet()

fun WebView.updateMarkers(stories: List<NewsStory>) {
    Log.d("GlobeNews", "WEBVIEW: updateMarkers called with ${stories.size} stories")

    // Skip if story IDs haven't changed — avoids expensive clear+redraw
    val currentIds = stories.map { it.id }.toSet()
    if (currentIds == lastSentStoryIds) {
        Log.d("GlobeNews", "WEBVIEW: skipping — story IDs unchanged (${currentIds.size})")
        return
    }
    lastSentStoryIds = currentIds

    if (stories.isEmpty()) {
        // Only clear when going to zero stories
        evaluateJavascript("clearMarkers()", null)
        Log.d("GlobeNews", "WEBVIEW: stories list is empty, cleared markers")
        return
    }

    val now = Instant.now()
    val markersJson = stories.map { story ->
        val hoursAgo = try {
            Duration.between(story.publishedAt, now).toHours()
        } catch (e: Exception) { 24L }
        val safeTitle = story.title.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "").take(80)
        val safeName = (story.sources.firstOrNull()?.name ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        """{"id":"${story.id.replace("\"", "")}","lat":${story.location.latitude},"lon":${story.location.longitude},"category":"${story.category.name}","title":"$safeTitle","sourceName":"$safeName","hoursAgo":$hoursAgo}"""
    }.joinToString(",", "[", "]")

    Log.d("GlobeNews", "WEBVIEW: calling addMarkers with JSON length=${markersJson.length}, first 200 chars: ${markersJson.take(200)}")
    evaluateJavascript("addMarkers('${markersJson.replace("'", "\\'")}')", null)
}

fun WebView.setGlobeBaseLayer(layer: String) {
    Log.d("GlobeNews", "WEBVIEW: setBaseLayer('$layer')")
    evaluateJavascript("setBaseLayer('$layer')", null)
}

fun WebView.flyToLocation(lat: Double, lon: Double, zoom: Double) {
    Log.d("GlobeNews", "WEBVIEW: flyToLocation($lat, $lon, $zoom)")
    evaluateJavascript("flyTo($lat, $lon, $zoom)", null)
}

fun WebView.highlightStoryMarker(id: String) {
    Log.d("GlobeNews", "WEBVIEW: highlightMarker('$id')")
    evaluateJavascript("highlightMarker('${id.replace("'", "\\'")}')", null)
}
