package com.globenews.domain.repository

import com.globenews.core.common.Result
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiagnosticLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,  // "GDELT" or "RSS"
    val name: String,    // region or feed name
    val result: String   // "OK (42 articles)" or "FAILED: timeout" etc.
)

data class DiagnosticInfo(
    val networkTest: String = "Not run yet",
    val gdeltStatus: String = "Waiting...",
    val rssStatus: String = "Waiting...",
    val liveTotal: String = "Waiting...",
    val pipelineSummary: String = "Fetch not started",
    // GDELT detail
    val gdeltRegionsTotal: Int = 0,
    val gdeltRegionsSucceeded: Int = 0,
    val gdeltRegionsFailed: Int = 0,
    val gdeltRegionsRateLimited: Int = 0,
    // RSS detail
    val rssFeedsTotal: Int = 0,
    val rssFeedsSucceeded: Int = 0,
    val rssFeedsFailed: Int = 0,
    val rssFeedsStillFetching: Int = 0,
    // Cache
    val cacheStatus: String = "Not loaded",
    val queriesUsed: Int = 0,
    val queriesMax: Int = 150,
    // Scrollable log (last 50 entries)
    val logEntries: List<DiagnosticLogEntry> = emptyList()
) {
    fun addLog(source: String, name: String, result: String): DiagnosticInfo {
        val entry = DiagnosticLogEntry(source = source, name = name, result = result)
        val updated = (logEntries + entry).takeLast(50)
        return copy(logEntries = updated)
    }
}

interface NewsRepository {
    fun getStoriesForView(view: GlobeView, category: NewsCategory): Flow<Result<List<NewsStory>>>
    suspend fun refreshStories(view: GlobeView, category: NewsCategory)
    suspend fun backgroundFillGlobalGrid(category: NewsCategory)
    suspend fun searchStories(query: String): Result<List<NewsStory>>
    suspend fun bookmarkStory(storyId: String, bookmarked: Boolean)
    fun getBookmarkedStories(): Flow<List<NewsStory>>
    val diagnostics: StateFlow<DiagnosticInfo>
}
