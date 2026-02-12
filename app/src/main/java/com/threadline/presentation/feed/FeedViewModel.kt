package com.threadline.presentation.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadline.core.common.Result
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.domain.model.NewsStory
import com.threadline.domain.model.StoryCluster
import com.threadline.domain.repository.FeedRepository
import com.threadline.domain.usecase.DiversityScorer
import com.threadline.domain.usecase.StoryClusterer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val clusters: List<StoryCluster> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val feedDao: FeedDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

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
        val clusters = StoryClusterer.clusterStories(stories)
        val scored = clusters.map { cluster ->
            val score = DiversityScorer.scoreDiversity(cluster, feedDao)
            cluster.copy(diversityScore = score)
        }
        _uiState.value = _uiState.value.copy(
            clusters = scored,
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
