package com.globenews.presentation.globe

import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.globenews.domain.model.NewsCategory
import com.globenews.presentation.settings.SettingsScreen
import com.globenews.presentation.storydetail.StoryDetailSheet
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@Composable
fun GlobeScreen(
    viewModel: GlobeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val moshi = remember { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }
    val bridge = remember { GlobeBridge(moshi) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    // Observe bridge events
    LaunchedEffect(bridge) {
        bridge.cameraMoves.collect { view ->
            viewModel.onCameraMove(view)
        }
    }

    LaunchedEffect(bridge) {
        bridge.markerTaps.collect { storyId ->
            viewModel.onMarkerTapped(storyId)
            webView?.highlightStoryMarker(storyId)
        }
    }

    // When map is ready, push current markers
    val isMapReady by bridge.isReady.collectAsState()
    LaunchedEffect(isMapReady, webView) {
        Log.d("GlobeNews", "SCREEN: LaunchedEffect(isMapReady=$isMapReady, webView=${webView != null})")
        if (isMapReady && webView != null) {
            val stories = viewModel.uiState.value.stories
            Log.d("GlobeNews", "SCREEN: map ready, pushing ${stories.size} stories to webview")
            if (stories.isNotEmpty()) {
                webView?.updateMarkers(stories)
            }
        }
    }

    // Update markers when stories change
    LaunchedEffect(uiState.stories) {
        Log.d("GlobeNews", "SCREEN: stories changed, count=${uiState.stories.size}, isMapReady=$isMapReady")
        if (isMapReady) {
            webView?.updateMarkers(uiState.stories)
        }
    }

    // Update base layer
    LaunchedEffect(uiState.baseLayer) {
        if (isMapReady) {
            webView?.setGlobeBaseLayer(uiState.baseLayer)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Map WebView
        GlobeWebView(
            modifier = Modifier.fillMaxSize(),
            bridge = bridge,
            stories = uiState.stories,
            baseLayer = uiState.baseLayer,
            onWebViewReady = { wv -> webView = wv }
        )

        // Top bar overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Refresh
            IconButton(onClick = { viewModel.loadStories() }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = Color.White
                )
            }

            // Story count badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Newspaper,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (uiState.storyCount > 0) " ${uiState.storyCount} stories"
                    else " No stories",
                    color = Color.White,
                    fontSize = 13.sp
                )
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp).padding(start = 4.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }

            // Settings
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            // Category filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NewsCategory.entries.forEach { category ->
                    FilterChip(
                        selected = uiState.selectedCategory == category,
                        onClick = { viewModel.onCategorySelected(category) },
                        label = {
                            Text(
                                text = category.displayName,
                                fontSize = 12.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.6f),
                            labelColor = Color.White,
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // Layer toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                val layers = listOf("dark", "satellite", "streets")
                val labels = listOf("Dark", "Sat", "Map")
                SingleChoiceSegmentedButtonRow {
                    layers.forEachIndexed { index, layer ->
                        SegmentedButton(
                            selected = uiState.baseLayer == layer,
                            onClick = { viewModel.onBaseLayerChanged(layer) },
                            shape = SegmentedButtonDefaults.itemShape(index, layers.size)
                        ) {
                            Text(labels[index], fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Story detail bottom sheet
        uiState.selectedStory?.let { story ->
            StoryDetailSheet(
                story = story,
                onDismiss = { viewModel.dismissStoryDetail() },
                onBookmark = { id, bookmarked ->
                    // TODO: implement bookmark
                }
            )
        }
    }
}
