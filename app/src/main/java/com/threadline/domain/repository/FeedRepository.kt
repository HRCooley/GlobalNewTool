package com.threadline.domain.repository

import com.threadline.core.common.Result
import com.threadline.domain.model.NewsStory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiagnosticInfo(
    val networkTest: String = "Not tested",
    val gdeltStatus: String = "Idle",
    val rssStatus: String = "Idle",
    val rssFeedSummary: String = "",
    val cacheCount: String = "0",
    val pipelineSummary: String = "Waiting..."
)

interface FeedRepository {
    fun getStories(): Flow<Result<List<NewsStory>>>
    suspend fun refreshStories()
    val diagnostics: StateFlow<DiagnosticInfo>
}
