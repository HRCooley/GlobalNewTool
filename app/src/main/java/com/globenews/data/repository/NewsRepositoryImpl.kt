package com.globenews.data.repository

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.core.common.Result
import com.globenews.core.common.normalizeUrl
import com.globenews.core.common.titleWordOverlap
import com.globenews.data.mapper.StoryMappers
import com.globenews.data.source.local.CustomFeedDao
import com.globenews.data.source.local.FallbackDataSource
import com.globenews.data.source.remote.gdelt.GdeltDataSource
import com.globenews.data.source.remote.gnews.GNewsDataSource
import com.globenews.data.source.remote.googlenews.GoogleNewsDataSource
import com.globenews.data.source.remote.newsapi.NewsApiDataSource
import com.globenews.data.source.remote.rss.RssDataSource
import com.globenews.data.source.remote.rss.RssFeedConfig
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
    private val fallbackDataSource: FallbackDataSource,
    private val customFeedDao: CustomFeedDao
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

        // Immediately emit fallback so users see stories while network loads
        try {
            val fallback = fallbackDataSource.loadFallbackStories().map { StoryMappers.fromFallback(it) }
            Log.d("GlobeNews", "REPO: emitting ${fallback.size} fallback stories immediately")
            if (fallback.isNotEmpty()) {
                storiesFlow.value = Result.Success(fallback)
            } else {
                storiesFlow.value = Result.Loading
            }
        } catch (e: Exception) {
            Log.e("GlobeNews", "REPO: fallback load failed: ${e.message}")
            storiesFlow.value = Result.Loading
        }

        // Now try to fetch fresh data from network sources
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
                    var gdeltCount = 0
                    var rssCount = 0
                    var customCount = 0
                    var googleCount = 0

                    Log.d("GlobeNews", "REPO: fetching GDELT for ${GLOBAL_GRID.size} global regions...")
                    val gdeltResults = gdeltDataSource.fetchForRegions(
                        regions = GLOBAL_GRID,
                        category = category,
                        maxRecords = 75,
                        timespan = "24h"
                    )
                    Log.d("GlobeNews", "REPO: GDELT returned ${gdeltResults.size} raw articles")
                    val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                    gdeltCount = gdeltStories.size
                    Log.d("GlobeNews", "REPO: GDELT mapped to $gdeltCount stories")
                    stories.addAll(gdeltStories)

                    // Also fetch RSS (bundled + custom)
                    Log.d("GlobeNews", "REPO: fetching RSS feeds...")
                    try {
                        val rssItems = rssDataSource.fetchAllFeeds()
                        rssCount = rssItems.size
                        Log.d("GlobeNews", "REPO: RSS returned $rssCount items")
                        stories.addAll(rssItems.map { StoryMappers.fromRss(it) })
                    } catch (e: Exception) {
                        Log.e("GlobeNews", "REPO: RSS fetch failed: ${e.message}")
                    }

                    // Custom RSS feeds from user
                    val customStories = fetchCustomFeeds()
                    customCount = customStories.size
                    stories.addAll(customStories)

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

                    val totalDeduped = deduplicateStories(stories).size
                    Log.d("GlobeNews", "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=$googleCount, RSS=$rssCount, Custom=$customCount, Total=$totalDeduped ===")
                }
            } else if (view.zoom < Constants.ZOOM_LOCAL_THRESHOLD) {
                Log.d("GlobeNews", "REPO: CONTINENTAL view path")
                var gdeltCount = 0
                var rssCount = 0
                var customCount = 0

                // Continental view — sub-queries
                val subRegions = getSubRegions(view)
                val gdeltResults = gdeltDataSource.fetchForRegions(
                    regions = subRegions,
                    category = category,
                    maxRecords = 100,
                    timespan = "48h"
                )
                val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                gdeltCount = gdeltStories.size
                Log.d("GlobeNews", "REPO: GDELT continental returned ${gdeltResults.size} articles, mapped to $gdeltCount stories")
                stories.addAll(gdeltStories)

                try {
                    val rssItems = rssDataSource.fetchAllFeeds()
                    rssCount = rssItems.size
                    Log.d("GlobeNews", "REPO: RSS returned $rssCount items")
                    stories.addAll(rssItems.map { StoryMappers.fromRss(it) })
                } catch (e: Exception) {
                    Log.e("GlobeNews", "REPO: RSS fetch failed: ${e.message}")
                }

                // Custom RSS feeds from user
                val customStories = fetchCustomFeeds()
                customCount = customStories.size
                stories.addAll(customStories)

                val totalDeduped = deduplicateStories(stories).size
                Log.d("GlobeNews", "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=0, RSS=$rssCount, Custom=$customCount, Total=$totalDeduped ===")
            } else {
                Log.d("GlobeNews", "REPO: LOCAL view path")
                var gdeltCount = 0
                var googleCount = 0

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
                val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                gdeltCount = gdeltStories.size
                Log.d("GlobeNews", "REPO: GDELT nearby returned ${gdeltResults.size} articles, mapped to $gdeltCount stories")
                stories.addAll(gdeltStories)

                // Google News local
                try {
                    val localNews = googleNewsDataSource.fetchLocal(view.latitude, view.longitude)
                    googleCount = localNews.size
                    Log.d("GlobeNews", "REPO: Google News local returned $googleCount items")
                    stories.addAll(localNews.map {
                        StoryMappers.fromGoogleNews(it, view.latitude, view.longitude, null)
                    })
                } catch (e: Exception) {
                    Log.e("GlobeNews", "REPO: Google News fetch failed: ${e.message}")
                }

                val totalDeduped = deduplicateStories(stories).size
                Log.d("GlobeNews", "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=$googleCount, RSS=0, Custom=0, Total=$totalDeduped ===")
            }

            // Deduplicate
            val deduped = deduplicateStories(stories)
            Log.d("GlobeNews", "REPO: after dedup: ${deduped.size} stories (from ${stories.size})")

            // Only replace fallback if we got real network results
            if (deduped.isNotEmpty()) {
                Log.d("GlobeNews", "REPO: emitting ${deduped.size} network stories to flow")
                storiesFlow.value = Result.Success(deduped)
            } else {
                Log.d("GlobeNews", "REPO: no network stories, keeping fallback")
            }
        } catch (e: Exception) {
            Log.e("GlobeNews", "REPO: EXCEPTION in refreshStories: ${e.message}", e)
            // Fallback was already emitted at start, so just log the error
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

    private suspend fun fetchCustomFeeds(): List<NewsStory> {
        return try {
            val customFeeds = customFeedDao.getEnabledFeeds()
            if (customFeeds.isEmpty()) return emptyList()
            Log.d("GlobeNews", "REPO: fetching ${customFeeds.size} custom RSS feeds...")
            val configs = customFeeds.map { feed ->
                RssFeedConfig(
                    name = feed.name,
                    url = feed.url,
                    country = "XX",
                    language = "en",
                    lat = feed.latitude,
                    lon = feed.longitude,
                    scope = "LOCAL"
                )
            }
            val items = rssDataSource.fetchFeeds(configs)
            Log.d("GlobeNews", "REPO: custom RSS returned ${items.size} items")
            items.map { StoryMappers.fromRss(it) }
        } catch (e: Exception) {
            Log.e("GlobeNews", "REPO: custom RSS fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun getSubRegions(view: GlobeView): List<QueryRegion> {
        // Find the closest global grid region for country codes
        val closest = GLOBAL_GRID.minByOrNull { region ->
            val dLat = region.lat - view.latitude
            val dLon = region.lon - view.longitude
            dLat * dLat + dLon * dLon
        }
        val codes = closest?.countryCodes ?: emptyList()
        val bounds = view.bounds ?: return listOf(
            QueryRegion("center", view.latitude, view.longitude, 2000, codes)
        )
        val latStep = (bounds.north - bounds.south) / 2
        val lonStep = (bounds.east - bounds.west) / 2
        return listOf(
            QueryRegion("NW", bounds.north - latStep / 2, bounds.west + lonStep / 2, 1500, codes),
            QueryRegion("NE", bounds.north - latStep / 2, bounds.east - lonStep / 2, 1500, codes),
            QueryRegion("SW", bounds.south + latStep / 2, bounds.west + lonStep / 2, 1500, codes),
            QueryRegion("SE", bounds.south + latStep / 2, bounds.east - lonStep / 2, 1500, codes),
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
