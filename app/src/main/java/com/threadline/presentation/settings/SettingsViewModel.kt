package com.threadline.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.dao.StoryDao
import com.threadline.domain.repository.DiagnosticInfo
import com.threadline.domain.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedStats(
    val enabledCount: Int = 0,
    val brokenCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val storyDao: StoryDao,
    private val feedDao: FeedDao
) : ViewModel() {

    val diagnostics: StateFlow<DiagnosticInfo> = repository.diagnostics

    private val _feedStats = MutableStateFlow(FeedStats())
    val feedStats: StateFlow<FeedStats> = _feedStats

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                _feedStats.value = FeedStats(
                    enabledCount = feedDao.getEnabledCount(),
                    brokenCount = feedDao.getBrokenCount()
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            storyDao.deleteExpired(Long.MAX_VALUE)
            loadStats()
        }
    }

    fun resetFailures() {
        viewModelScope.launch {
            feedDao.resetAllFailures()
            loadStats()
        }
    }
}
