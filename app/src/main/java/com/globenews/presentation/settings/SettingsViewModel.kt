package com.globenews.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.globenews.data.source.local.CustomFeedDao
import com.globenews.data.source.local.CustomFeedEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val customFeedDao: CustomFeedDao
) : ViewModel() {

    private val _customFeeds = MutableStateFlow<List<CustomFeedEntity>>(emptyList())
    val customFeeds: StateFlow<List<CustomFeedEntity>> = _customFeeds.asStateFlow()

    init {
        loadFeeds()
    }

    private fun loadFeeds() {
        viewModelScope.launch {
            _customFeeds.value = customFeedDao.getAllFeeds()
        }
    }

    fun addFeed(name: String, url: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            customFeedDao.insert(
                CustomFeedEntity(
                    name = name,
                    url = url,
                    latitude = latitude,
                    longitude = longitude
                )
            )
            loadFeeds()
        }
    }

    fun deleteFeed(feed: CustomFeedEntity) {
        viewModelScope.launch {
            customFeedDao.delete(feed)
            loadFeeds()
        }
    }
}
