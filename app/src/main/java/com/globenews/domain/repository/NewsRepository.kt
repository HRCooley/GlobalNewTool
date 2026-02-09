package com.globenews.domain.repository

import com.globenews.core.common.Result
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import kotlinx.coroutines.flow.Flow

interface NewsRepository {
    fun getStoriesForView(view: GlobeView, category: NewsCategory): Flow<Result<List<NewsStory>>>
    suspend fun refreshStories(view: GlobeView, category: NewsCategory)
    suspend fun searchStories(query: String): Result<List<NewsStory>>
    suspend fun bookmarkStory(storyId: String, bookmarked: Boolean)
    fun getBookmarkedStories(): Flow<List<NewsStory>>
}
