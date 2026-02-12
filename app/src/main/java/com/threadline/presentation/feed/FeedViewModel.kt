package com.threadline.presentation.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadline.core.common.Result
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.domain.model.DiversityLevel
import com.threadline.domain.model.NewsStory
import com.threadline.domain.model.StoryCluster
import com.threadline.domain.repository.FeedRepository
import com.threadline.domain.usecase.DiversityScorer
import com.threadline.domain.usecase.StoryClusterer
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FeedUiState(
    val clusters: List<StoryCluster> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedCategory: String = "All",
    val selectedScope: String = "Global"
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val feedDao: FeedDao
) : ViewModel() {

    companion object {
        val CATEGORIES = listOf("All", "CONFLICT", "POLITICS", "ECONOMY", "TECHNOLOGY", "ENVIRONMENT", "HEALTH", "CRIME", "ENERGY")
        val SCOPES = listOf("Global", "US", "Signals Only")
    }

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

    private var allClusters: List<StoryCluster> = emptyList()

    init {
        observeStories()
        refresh()
    }

    private fun observeStories() {
        viewModelScope.launch {
            repository.getStories().collectLatest { result ->
                when (result) {
                    is Result.Loading -> {
                        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                    }
                    is Result.Success -> {
                        processStories(result.data)
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    private suspend fun processStories(stories: List<NewsStory>) {
        try {
            val scored = withContext(Dispatchers.Default) {
                val clusters = StoryClusterer.clusterStories(stories)
                clusters.map { cluster ->
                    val score = DiversityScorer.scoreDiversity(cluster, feedDao)
                    cluster.copy(diversityScore = score)
                }
            }
            allClusters = scored
            applyFilters()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("FeedViewModel", "processStories failed: ${e.message}", e)
            // Fallback: show stories as individual unclustered items
            val fallbackClusters = stories.map { story ->
                StoryCluster(
                    id = story.id,
                    representativeTitle = story.title,
                    representativeSummary = story.summary,
                    stories = listOf(story),
                    sourceCount = 1,
                    mostRecent = story.publishedAt,
                    categories = listOf(story.category)
                )
            }
            allClusters = fallbackClusters
            applyFilters()
        }
    }

    fun setCategory(category: String) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
        applyFilters()
    }

    fun setScope(scope: String) {
        _uiState.value = _uiState.value.copy(selectedScope = scope)
        applyFilters()
    }

    private fun applyFilters() {
        val category = _uiState.value.selectedCategory
        val scope = _uiState.value.selectedScope

        var filtered = allClusters

        // Category filter
        if (category != "All") {
            filtered = filtered.filter { cluster ->
                cluster.categories.any { it.equals(category, ignoreCase = true) }
            }
        }

        // Scope filter
        filtered = when (scope) {
            "US" -> filtered.filter { cluster ->
                cluster.stories.any { it.countryCode == "US" }
            }
            "Signals Only" -> filtered.filter { cluster ->
                cluster.diversityScore?.composite == DiversityLevel.SIGNAL
            }
            else -> filtered
        }

        _uiState.value = _uiState.value.copy(
            clusters = filtered,
            isLoading = false,
            isRefreshing = false,
            error = null
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            repository.refreshStories()
        }
    }
}
