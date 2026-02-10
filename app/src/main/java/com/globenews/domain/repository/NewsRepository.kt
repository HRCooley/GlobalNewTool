package com.globenews.domain.repository

import com.globenews.core.common.Result
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiagnosticInfo(
    val networkTest: String = "Not run yet",
    val gdeltStatus: String = "Waiting...",
    val rssStatus: String = "Waiting...",
    val liveTotal: String = "Waiting...",
    val pipelineSummary: String = "Fetch not started"
)

interface NewsRepository {
    fun getStoriesForView(view: GlobeView, category: NewsCategory): Flow<Result<List<NewsStory>>>
    suspend fun refreshStories(view: GlobeView, category: NewsCategory)
    suspend fun searchStories(query: String): Result<List<NewsStory>>
    suspend fun bookmarkStory(storyId: String, bookmarked: Boolean)
    fun getBookmarkedStories(): Flow<List<NewsStory>>
    val diagnostics: StateFlow<DiagnosticInfo>
}
