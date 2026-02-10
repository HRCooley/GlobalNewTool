package com.globenews.presentation.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.globenews.data.source.local.FeedImporter
import com.globenews.data.source.local.ManagedFeed
import com.globenews.data.source.local.ManagedFeedDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class FeedManagementViewModel @Inject constructor(
    private val feedDao: ManagedFeedDao,
    private val feedImporter: FeedImporter
) : ViewModel() {

    val allFeeds: StateFlow<List<ManagedFeed>> = feedDao.getAllFeedsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val brokenCount: StateFlow<Int> = feedDao.getBrokenCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showBrokenOnly = MutableStateFlow(false)
    val showBrokenOnly: StateFlow<Boolean> = _showBrokenOnly.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleBrokenOnly() {
        _showBrokenOnly.value = !_showBrokenOnly.value
    }

    fun toggleFeed(id: String, enabled: Boolean) {
        viewModelScope.launch { feedDao.setEnabled(id, enabled) }
    }

    fun deleteFeed(feed: ManagedFeed) {
        viewModelScope.launch { feedDao.delete(feed) }
    }

    fun resetBroken() {
        viewModelScope.launch { feedDao.resetAllFailures() }
    }

    fun reimportBundled() {
        viewModelScope.launch { feedImporter.importIfNeeded() }
    }

    fun addCustomFeed(name: String, url: String, lat: Double, lon: Double, category: String) {
        viewModelScope.launch {
            feedDao.upsert(
                ManagedFeed(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    url = url,
                    country = "",
                    language = "en",
                    latitude = lat,
                    longitude = lon,
                    scope = "LOCAL",
                    category = category.ifBlank { "custom" },
                    enabled = true,
                    isBundled = false
                )
            )
        }
    }
}
