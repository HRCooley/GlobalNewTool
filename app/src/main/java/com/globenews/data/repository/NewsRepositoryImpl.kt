package com.globenews.data.repository

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.core.common.Result
import com.globenews.core.common.normalizeUrl
import com.globenews.core.common.titleWordOverlap
import com.globenews.data.mapper.StoryMappers
import com.globenews.data.source.local.FallbackDataSource
import com.globenews.data.source.remote.gdelt.GdeltDataSource
import com.globenews.data.source.remote.gnews.GNewsDataSource
import com.globenews.data.source.remote.googlenews.GoogleNewsDataSource
import com.globenews.data.source.remote.newsapi.NewsApiDataSource
import com.globenews.data.source.remote.rss.RssDataSource
import com.globenews.domain.model.GLOBAL_GRID
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import com.globenews.domain.model.QueryRegion
import com.globenews.domain.repository.NewsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val gdeltDataSource: GdeltDataSource,
    private val googleNewsDataSource: GoogleNewsDataSource,
    private val rssDataSource: RssDataSource,
    private val newsApiDataSource: NewsApiDataSource,
    private val gNewsDataSource: GNewsDataSource,
    private val fallbackDataSource: FallbackDataSource
) : NewsRepository {

    companion object {
        private const val TAG = "NewsRepository"
    }

    private val storiesFlow = MutableStateFlow<Result<List<NewsStory>>>(Result.Loading)
    private val bookmarks = mutableSetOf<String>()

    // Cache for global grid results
    private var globalGridCache: List<NewsStory> = emptyList()
    private var globalGridCacheTime: Instant = Instant.EPOCH
    private var globalGridCacheCategory: NewsCategory = NewsCategory.ALL

    override fun getStoriesForView(
        view: GlobeView,
        category: NewsCategory
    ): Flow<Result<List<NewsStory>>> {
        return storiesFlow.map { result ->
            when (result) {
                is Result.Success -> {
                    val filtered = if (category == NewsCategory.ALL) {
                        result.data
                    } else {
                        result.data.filter { it.category == category }
                    }
                    // Apply marker cap based on altitude
                    val cap = when {
                        view.altitude > Constants.HIGH_ALTITUDE_KM -> Constants.MAX_MARKERS_HIGH_ALT
                        view.altitude > Constants.LOW_ALTITUDE_KM -> Constants.MAX_MARKERS_MID_ALT
                        else -> Constants.MAX_MARKERS_LOW_ALT
                    }
                    val capped = filtered
                        .sortedByDescending { it.publishedAt }
                        .take(cap)
                    Result.Success(capped.map { story ->
                        story.copy(isBookmarked = bookmarks.contains(story.id))
                    })
                }
                else -> result
            }
        }
    }

    override suspend fun refreshStories(view: GlobeView, category: NewsCategory) {
        storiesFlow.value = Result.Loading
        try {
            val stories = mutableListOf<NewsStory>()

            if (view.altitude > Constants.HIGH_ALTITUDE_KM) {
                // Global view — use grid
                val cacheValid = Duration.between(globalGridCacheTime, Instant.now()).toMinutes() < Constants.GLOBAL_CACHE_MINUTES
                    && globalGridCacheCategory == category
                    && globalGridCache.isNotEmpty()

                if (cacheValid) {
                    stories.addAll(globalGridCache)
                } else {
                    val gdeltResults = gdeltDataSource.fetchForRegions(
                        regions = GLOBAL_GRID,
                        category = category,
                        maxRecords = 75,
                        timespan = "24h"
                    )
                    stories.addAll(gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) })

                    // Also fetch RSS
                    val rssItems = rssDataSource.fetchAllFeeds()
                    stories.addAll(rssItems.map { StoryMappers.fromRss(it) })

                    // Optional sources
                    if (newsApiDataSource.isAvailable) {
                        val newsApiItems = newsApiDataSource.fetchHeadlines()
                        stories.addAll(newsApiItems.mapNotNull { StoryMappers.fromNewsApi(it) })
                    }
                    if (gNewsDataSource.isAvailable) {
                        val gNewsItems = gNewsDataSource.fetchHeadlines()
                        stories.addAll(gNewsItems.mapNotNull { StoryMappers.fromGNews(it) })
                    }

                    globalGridCache = stories.toList()
                    globalGridCacheTime = Instant.now()
                    globalGridCacheCategory = category
                }
            } else if (view.altitude > Constants.LOW_ALTITUDE_KM) {
                // Continental view — sub-queries
                val subRegions = getSubRegions(view)
                val gdeltResults = gdeltDataSource.fetchForRegions(
                    regions = subRegions,
                    category = category,
                    maxRecords = 100,
                    timespan = "48h"
                )
                stories.addAll(gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) })

                val rssItems = rssDataSource.fetchAllFeeds()
                stories.addAll(rssItems.map { StoryMappers.fromRss(it) })
            } else {
                // City/region view — single query + Google News local
                val radiusKm = (view.altitude * 0.5).toInt().coerceIn(50, 2000)
                val gdeltResults = gdeltDataSource.fetchNearby(
                    lat = view.latitude,
                    lon = view.longitude,
                    radiusKm = radiusKm,
                    category = category,
                    maxRecords = 100,
                    timespan = "7d"
                )
                stories.addAll(gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) })

                // Google News local
                val localNews = googleNewsDataSource.fetchLocal(view.latitude, view.longitude)
                stories.addAll(localNews.map {
                    StoryMappers.fromGoogleNews(it, view.latitude, view.longitude, null)
                })
            }

            // Deduplicate
            val deduped = deduplicateStories(stories)

            // If no stories found, use fallback
            val finalStories = if (deduped.isEmpty()) {
                Log.d(TAG, "No stories fetched, using fallback")
                fallbackDataSource.loadFallbackStories().map { StoryMappers.fromFallback(it) }
            } else {
                deduped
            }

            storiesFlow.value = Result.Success(finalStories)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh stories", e)
            // Try fallback
            val fallback = fallbackDataSource.loadFallbackStories().map { StoryMappers.fromFallback(it) }
            if (fallback.isNotEmpty()) {
                storiesFlow.value = Result.Success(fallback)
            } else {
                storiesFlow.value = Result.Error("Failed to load news: ${e.message}", e)
            }
        }
    }

    override suspend fun searchStories(query: String): Result<List<NewsStory>> {
        return try {
            val region = QueryRegion("search", 0.0, 0.0, 20000)
            val gdeltResults = gdeltDataSource.fetchForRegions(
                regions = listOf(region),
                category = NewsCategory.ALL,
                maxRecords = 100,
                timespan = "7d"
            )
            val stories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
            Result.Success(stories)
        } catch (e: Exception) {
            Result.Error("Search failed: ${e.message}", e)
        }
    }

    override suspend fun bookmarkStory(storyId: String, bookmarked: Boolean) {
        if (bookmarked) bookmarks.add(storyId) else bookmarks.remove(storyId)
        // Trigger re-emission
        val current = storiesFlow.value
        if (current is Result.Success) {
            storiesFlow.value = Result.Success(current.data.map { story ->
                story.copy(isBookmarked = bookmarks.contains(story.id))
            })
        }
    }

    override fun getBookmarkedStories(): Flow<List<NewsStory>> {
        return storiesFlow.map { result ->
            when (result) {
                is Result.Success -> result.data.filter { it.isBookmarked }
                else -> emptyList()
            }
        }
    }

    private fun getSubRegions(view: GlobeView): List<QueryRegion> {
        val bounds = view.bounds ?: return listOf(
            QueryRegion("center", view.latitude, view.longitude, 2000)
        )
        val latStep = (bounds.north - bounds.south) / 2
        val lonStep = (bounds.east - bounds.west) / 2
        return listOf(
            QueryRegion("NW", bounds.north - latStep / 2, bounds.west + lonStep / 2, 1500),
            QueryRegion("NE", bounds.north - latStep / 2, bounds.east - lonStep / 2, 1500),
            QueryRegion("SW", bounds.south + latStep / 2, bounds.west + lonStep / 2, 1500),
            QueryRegion("SE", bounds.south + latStep / 2, bounds.east - lonStep / 2, 1500),
        )
    }

    private fun deduplicateStories(stories: List<NewsStory>): List<NewsStory> {
        val seen = mutableMapOf<String, NewsStory>()
        val result = mutableListOf<NewsStory>()

        for (story in stories) {
            val normalizedUrl = story.url.normalizeUrl()

            // URL-based dedup
            if (seen.containsKey(normalizedUrl)) {
                continue
            }

            // Title-based dedup
            var isDuplicate = false
            for ((_, existing) in seen) {
                val overlap = titleWordOverlap(story.title, existing.title)
                if (overlap > Constants.DEDUP_TITLE_OVERLAP_THRESHOLD) {
                    val timeDiff = Duration.between(story.publishedAt, existing.publishedAt).abs()
                    if (timeDiff.toHours() < Constants.DEDUP_TIME_WINDOW_HOURS) {
                        isDuplicate = true
                        break
                    }
                }
            }

            if (!isDuplicate) {
                seen[normalizedUrl] = story
                result.add(story)
            }
        }

        return result
    }
}
