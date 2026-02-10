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
                    // Apply marker cap based on zoom level
                    val cap = when {
                        view.zoom < Constants.ZOOM_WORLD_THRESHOLD -> Constants.MAX_MARKERS_WORLD
                        view.zoom < Constants.ZOOM_LOCAL_THRESHOLD -> Constants.MAX_MARKERS_REGION
                        else -> Constants.MAX_MARKERS_LOCAL
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
        Log.d("GlobeNews", "REPO: refreshStories called, zoom=${view.zoom}, category=$category")
        storiesFlow.value = Result.Loading
        try {
            val stories = mutableListOf<NewsStory>()

            if (view.zoom < Constants.ZOOM_WORLD_THRESHOLD) {
                Log.d("GlobeNews", "REPO: GLOBAL view path (zoom ${view.zoom} < ${Constants.ZOOM_WORLD_THRESHOLD})")
                // Global view — use grid
                val cacheValid = Duration.between(globalGridCacheTime, Instant.now()).toMinutes() < Constants.GLOBAL_CACHE_MINUTES
                    && globalGridCacheCategory == category
                    && globalGridCache.isNotEmpty()

                if (cacheValid) {
                    Log.d("GlobeNews", "REPO: using cached global grid (${globalGridCache.size} stories)")
                    stories.addAll(globalGridCache)
                } else {
                    Log.d("GlobeNews", "REPO: fetching GDELT for ${GLOBAL_GRID.size} global regions...")
                    val gdeltResults = gdeltDataSource.fetchForRegions(
                        regions = GLOBAL_GRID,
                        category = category,
                        maxRecords = 75,
                        timespan = "24h"
                    )
                    Log.d("GlobeNews", "REPO: GDELT returned ${gdeltResults.size} raw articles")
                    val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                    Log.d("GlobeNews", "REPO: GDELT mapped to ${gdeltStories.size} stories")
                    stories.addAll(gdeltStories)

                    // Also fetch RSS
                    Log.d("GlobeNews", "REPO: fetching RSS feeds...")
                    try {
                        val rssItems = rssDataSource.fetchAllFeeds()
                        Log.d("GlobeNews", "REPO: RSS returned ${rssItems.size} items")
                        stories.addAll(rssItems.map { StoryMappers.fromRss(it) })
                    } catch (e: Exception) {
                        Log.e("GlobeNews", "REPO: RSS fetch failed: ${e.message}")
                    }

                    // Optional sources
                    if (newsApiDataSource.isAvailable) {
                        try {
                            val newsApiItems = newsApiDataSource.fetchHeadlines()
                            Log.d("GlobeNews", "REPO: NewsAPI returned ${newsApiItems.size} items")
                            stories.addAll(newsApiItems.mapNotNull { StoryMappers.fromNewsApi(it) })
                        } catch (e: Exception) {
                            Log.e("GlobeNews", "REPO: NewsAPI failed: ${e.message}")
                        }
                    }
                    if (gNewsDataSource.isAvailable) {
                        try {
                            val gNewsItems = gNewsDataSource.fetchHeadlines()
                            Log.d("GlobeNews", "REPO: GNews returned ${gNewsItems.size} items")
                            stories.addAll(gNewsItems.mapNotNull { StoryMappers.fromGNews(it) })
                        } catch (e: Exception) {
                            Log.e("GlobeNews", "REPO: GNews failed: ${e.message}")
                        }
                    }

                    Log.d("GlobeNews", "REPO: total stories before dedup: ${stories.size}")
                    globalGridCache = stories.toList()
                    globalGridCacheTime = Instant.now()
                    globalGridCacheCategory = category
                }
            } else if (view.zoom < Constants.ZOOM_LOCAL_THRESHOLD) {
                Log.d("GlobeNews", "REPO: CONTINENTAL view path")
                // Continental view — sub-queries
                val subRegions = getSubRegions(view)
                val gdeltResults = gdeltDataSource.fetchForRegions(
                    regions = subRegions,
                    category = category,
                    maxRecords = 100,
                    timespan = "48h"
                )
                Log.d("GlobeNews", "REPO: GDELT continental returned ${gdeltResults.size} articles")
                stories.addAll(gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) })

                try {
                    val rssItems = rssDataSource.fetchAllFeeds()
                    Log.d("GlobeNews", "REPO: RSS returned ${rssItems.size} items")
                    stories.addAll(rssItems.map { StoryMappers.fromRss(it) })
                } catch (e: Exception) {
                    Log.e("GlobeNews", "REPO: RSS fetch failed: ${e.message}")
                }
            } else {
                Log.d("GlobeNews", "REPO: LOCAL view path")
                // City/region view — single query + Google News local
                // Approximate visible radius from zoom level
                // zoom 8 ~ 500km, zoom 10 ~ 150km, zoom 12 ~ 40km
                val radiusKm = (40000.0 / Math.pow(2.0, view.zoom)).toInt().coerceIn(50, 2000)
                Log.d("GlobeNews", "REPO: GDELT nearby lat=${view.latitude}, lon=${view.longitude}, radius=${radiusKm}km")
                val gdeltResults = gdeltDataSource.fetchNearby(
                    lat = view.latitude,
                    lon = view.longitude,
                    radiusKm = radiusKm,
                    category = category,
                    maxRecords = 100,
                    timespan = "7d"
                )
                Log.d("GlobeNews", "REPO: GDELT nearby returned ${gdeltResults.size} articles")
                stories.addAll(gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) })

                // Google News local
                try {
                    val localNews = googleNewsDataSource.fetchLocal(view.latitude, view.longitude)
                    Log.d("GlobeNews", "REPO: Google News local returned ${localNews.size} items")
                    stories.addAll(localNews.map {
                        StoryMappers.fromGoogleNews(it, view.latitude, view.longitude, null)
                    })
                } catch (e: Exception) {
                    Log.e("GlobeNews", "REPO: Google News fetch failed: ${e.message}")
                }
            }

            // Deduplicate
            val deduped = deduplicateStories(stories)
            Log.d("GlobeNews", "REPO: after dedup: ${deduped.size} stories (from ${stories.size})")

            // If no stories found, use fallback
            val finalStories = if (deduped.isEmpty()) {
                Log.d("GlobeNews", "REPO: no stories found, loading fallback...")
                val fallback = fallbackDataSource.loadFallbackStories()
                Log.d("GlobeNews", "REPO: fallback loaded ${fallback.size} raw stories")
                fallback.map { StoryMappers.fromFallback(it) }
            } else {
                deduped
            }

            Log.d("GlobeNews", "REPO: emitting ${finalStories.size} final stories to flow")
            storiesFlow.value = Result.Success(finalStories)
        } catch (e: Exception) {
            Log.e("GlobeNews", "REPO: EXCEPTION in refreshStories: ${e.message}", e)
            // Try fallback
            try {
                val fallback = fallbackDataSource.loadFallbackStories().map { StoryMappers.fromFallback(it) }
                Log.d("GlobeNews", "REPO: fallback after exception: ${fallback.size} stories")
                if (fallback.isNotEmpty()) {
                    storiesFlow.value = Result.Success(fallback)
                } else {
                    storiesFlow.value = Result.Error("Failed to load news: ${e.message}", e)
                }
            } catch (e2: Exception) {
                Log.e("GlobeNews", "REPO: even fallback failed: ${e2.message}", e2)
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
