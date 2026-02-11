package com.globenews.presentation.globe

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.globenews.core.common.Constants
import com.globenews.core.common.Result
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import com.globenews.data.source.local.ManagedFeedDao
import com.globenews.domain.usecase.BackgroundFillUseCase
import com.globenews.domain.usecase.GetStoriesForViewUseCase
import com.globenews.domain.usecase.RefreshStoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ZoomTier { WORLD, CONTINENTAL, LOCAL }

data class GlobeUiState(
    val stories: List<NewsStory> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val storyCount: Int = 0,
    val selectedCategory: NewsCategory = NewsCategory.ALL,
    val selectedStory: NewsStory? = null,
    val currentView: GlobeView = GlobeView(20.0, 0.0, 2.0, null),
    val baseLayer: String = "dark"
)

@HiltViewModel
class GlobeViewModel @Inject constructor(
    private val getStoriesForView: GetStoriesForViewUseCase,
    private val refreshStories: RefreshStoriesUseCase,
    private val backgroundFill: BackgroundFillUseCase,
    managedFeedDao: ManagedFeedDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlobeUiState())
    val uiState: StateFlow<GlobeUiState> = _uiState.asStateFlow()

    val brokenFeedCount: StateFlow<Int> = managedFeedDao.getBrokenCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var observeJob: Job? = null
    private var refreshJob: Job? = null
    private var fetchJob: Job? = null
    private var backgroundJob: Job? = null
    private var backgroundFillStarted = false
    private var currentZoomTier: ZoomTier = ZoomTier.WORLD

    init {
        Log.d("GlobeNews", "VIEWMODEL: init called, triggering initial fetch")
        observeStories()
        loadStories()
    }

    private fun observeStories() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            val state = _uiState.value
            Log.d("GlobeNews", "VIEWMODEL: observeStories started, zoom=${state.currentView.zoom}, category=${state.selectedCategory}")
            getStoriesForView(state.currentView, state.selectedCategory).collectLatest { result ->
                when (result) {
                    is Result.Loading -> {
                        Log.d("GlobeNews", "VIEWMODEL: received Result.Loading")
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    is Result.Success -> {
                        Log.d("GlobeNews", "VIEWMODEL: received Result.Success with ${result.data.size} stories")
                        _uiState.update {
                            it.copy(
                                stories = result.data,
                                storyCount = result.data.size,
                                isLoading = false,
                                error = null
                            )
                        }
                    }
                    is Result.Error -> {
                        Log.e("GlobeNews", "VIEWMODEL: received Result.Error: ${result.message}")
                        _uiState.update {
                            it.copy(isLoading = false, error = result.message)
                        }
                    }
                }
            }
        }
    }

    fun loadStories(force: Boolean = false) {
        // Don't cancel an active fetch unless forced (manual refresh)
        // The initial fetch populates caches that subsequent fetches depend on
        if (!force && fetchJob?.isActive == true) {
            Log.d("GlobeNews", "VIEWMODEL: fetch already in progress, skipping")
            return
        }
        fetchJob?.cancel()
        // Do NOT cancel backgroundJob — it runs independently
        fetchJob = viewModelScope.launch {
            val state = _uiState.value
            Log.d("GlobeNews", "VIEWMODEL: loadStories called, zoom=${state.currentView.zoom}, category=${state.selectedCategory}")
            refreshStories(state.currentView, state.selectedCategory)
            Log.d("GlobeNews", "VIEWMODEL: refreshStories completed")
            // Start background fill once after first successful fetch
            startBackgroundFillIfNeeded(state.selectedCategory)
        }
    }

    fun onCameraMove(view: GlobeView) {
        Log.d("GlobeNews", "VIEWMODEL: onCameraMove lat=${view.latitude}, lon=${view.longitude}, zoom=${view.zoom}")
        _uiState.update { it.copy(currentView = view) }

        // Zoom hysteresis: only change tier if zoom crosses threshold ± buffer
        val newTier = resolveZoomTier(view.zoom)
        val tierChanged = newTier != currentZoomTier
        if (tierChanged) {
            Log.d("GlobeNews", "VIEWMODEL: zoom tier changed $currentZoomTier -> $newTier")
            currentZoomTier = newTier
        }

        observeStories()

        // Debounce refresh: cancel previous, wait 800ms for camera to settle
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(800)
            loadStories(force = true)
        }
    }

    private fun resolveZoomTier(zoom: Double): ZoomTier {
        val h = Constants.ZOOM_HYSTERESIS
        return when (currentZoomTier) {
            ZoomTier.WORLD -> when {
                zoom > Constants.ZOOM_LOCAL_THRESHOLD + h -> ZoomTier.LOCAL
                zoom > Constants.ZOOM_WORLD_THRESHOLD + h -> ZoomTier.CONTINENTAL
                else -> ZoomTier.WORLD
            }
            ZoomTier.CONTINENTAL -> when {
                zoom > Constants.ZOOM_LOCAL_THRESHOLD + h -> ZoomTier.LOCAL
                zoom < Constants.ZOOM_WORLD_THRESHOLD - h -> ZoomTier.WORLD
                else -> ZoomTier.CONTINENTAL
            }
            ZoomTier.LOCAL -> when {
                zoom < Constants.ZOOM_WORLD_THRESHOLD - h -> ZoomTier.WORLD
                zoom < Constants.ZOOM_LOCAL_THRESHOLD - h -> ZoomTier.CONTINENTAL
                else -> ZoomTier.LOCAL
            }
        }
    }

    private fun startBackgroundFillIfNeeded(category: NewsCategory) {
        if (backgroundFillStarted) return
        backgroundFillStarted = true
        backgroundJob = viewModelScope.launch {
            Log.d("GlobeNews", "VIEWMODEL: starting background grid fill")
            backgroundFill(category)
            Log.d("GlobeNews", "VIEWMODEL: background grid fill completed")
        }
    }

    fun onCategorySelected(category: NewsCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
        observeStories()
        loadStories()
    }

    fun onMarkerTapped(storyId: String) {
        val story = _uiState.value.stories.find { it.id == storyId }
        _uiState.update { it.copy(selectedStory = story) }
    }

    fun dismissStoryDetail() {
        _uiState.update { it.copy(selectedStory = null) }
    }

    fun onBaseLayerChanged(layer: String) {
        _uiState.update { it.copy(baseLayer = layer) }
    }
}
