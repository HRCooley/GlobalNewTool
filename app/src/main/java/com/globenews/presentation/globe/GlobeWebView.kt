package com.globenews.presentation.globe

import android.annotation.SuppressLint
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
                }
            }

            setBackgroundColor(android.graphics.Color.BLACK)
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

fun WebView.updateMarkers(stories: List<NewsStory>) {
    // Clear existing markers before adding new ones
    evaluateJavascript("clearMarkers()", null)

    if (stories.isEmpty()) return

    val now = Instant.now()
    val markersJson = stories.map { story ->
        val hoursAgo = try {
            Duration.between(story.publishedAt, now).toHours()
        } catch (e: Exception) { 24L }
        val safeTitle = story.title.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "").take(80)
        val safeName = (story.sources.firstOrNull()?.name ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        """{"id":"${story.id.replace("\"", "")}","lat":${story.location.latitude},"lon":${story.location.longitude},"category":"${story.category.name}","title":"$safeTitle","sourceName":"$safeName","hoursAgo":$hoursAgo}"""
    }.joinToString(",", "[", "]")

    evaluateJavascript("addMarkers('${markersJson.replace("'", "\\'")}')", null)
}

fun WebView.setGlobeBaseLayer(layer: String) {
    evaluateJavascript("setBaseLayer('$layer')", null)
}

fun WebView.flyToLocation(lat: Double, lon: Double, zoom: Double) {
    evaluateJavascript("flyTo($lat, $lon, $zoom)", null)
}

fun WebView.highlightStoryMarker(id: String) {
    evaluateJavascript("highlightMarker('${id.replace("'", "\\'")}')", null)
}
