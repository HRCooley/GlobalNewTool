package com.globenews.data.repository

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.core.common.Result
import com.globenews.core.common.normalizeUrl
import com.globenews.core.common.titleWordOverlap
import com.globenews.data.mapper.StoryMappers
import com.globenews.data.source.local.FallbackDataSource
import com.globenews.data.source.local.ManagedFeedDao
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
import com.globenews.domain.repository.DiagnosticInfo
import com.globenews.domain.repository.NewsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val managedFeedDao: ManagedFeedDao,
    private val okHttpClient: OkHttpClient
) : NewsRepository {

    companion object {
        private const val TAG = "NewsRepository"
    }

    private val storiesFlow = MutableStateFlow<Result<List<NewsStory>>>(Result.Loading)
    private val bookmarks = mutableSetOf<String>()
    private val _diagnostics = MutableStateFlow(DiagnosticInfo())
    override val diagnostics: StateFlow<DiagnosticInfo> = _diagnostics

    // Cache for global grid results
    private var globalGridCache: List<NewsStory> = emptyList()
    private var globalGridCacheTime: Instant = Instant.EPOCH
    private var globalGridCacheCategory: NewsCategory = NewsCategory.ALL

    // Cache for RSS feed results (shared across all view paths)
    private var rssCache: List<NewsStory> = emptyList()
    private var networkTestDone = false
    private var rssCacheTime: Instant = Instant.EPOCH

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

        // Only emit fallback if we don't already have real data
        val current = storiesFlow.value
        val hasRealData = current is Result.Success && current.data.isNotEmpty()
        if (!hasRealData) {
            try {
                val fallback = fallbackDataSource.loadFallbackStories().map { StoryMappers.fromFallback(it) }
                Log.d(TAG, "REPO: emitting ${fallback.size} fallback stories (no existing data)")
                if (fallback.isNotEmpty()) {
                    storiesFlow.value = Result.Success(fallback)
                } else {
                    storiesFlow.value = Result.Loading
                }
            } catch (e: Exception) {
                Log.e(TAG, "REPO: fallback load failed: ${e.message}")
                storiesFlow.value = Result.Loading
            }
        } else {
            Log.d(TAG, "REPO: skipping fallback, already have ${(current as Result.Success).data.size} stories")
        }

        // One-time network connectivity test
        if (!networkTestDone) {
            networkTestDone = true
            _diagnostics.value = _diagnostics.value.copy(networkTest = "Testing...", pipelineSummary = "Fetching live data...")
            try {
                withContext(Dispatchers.IO) {
                    val testUrl = "https://api.gdeltproject.org/api/v2/doc/doc?query=news&mode=artlist&maxrecords=1&format=json"
                    val request = Request.Builder().url(testUrl).build()
                    val response = okHttpClient.newCall(request).execute()
                    val bodySnippet = response.body?.string()?.take(200) ?: "(empty body)"
                    Log.e("GlobeNews", ">>> NETWORK TEST: HTTP ${response.code} — $bodySnippet")
                    _diagnostics.value = _diagnostics.value.copy(networkTest = "OK — HTTP ${response.code}")
                    response.close()
                }
            } catch (e: Exception) {
                Log.e("GlobeNews", ">>> NETWORK TEST FAILED: ${e.javaClass.simpleName}: ${e.message}")
                _diagnostics.value = _diagnostics.value.copy(networkTest = "FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
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
                    var googleCount = 0

                    // GDELT — wrapped so RSS still runs if GDELT fails
                    _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "Fetching ${GLOBAL_GRID.size} regions...")
                    try {
                        Log.d(TAG, "REPO: fetching GDELT for ${GLOBAL_GRID.size} global regions...")
                        val gdeltResults = gdeltDataSource.fetchForRegions(
                            regions = GLOBAL_GRID,
                            category = category,
                            maxRecords = 75,
                            timespan = "24h"
                        )
                        Log.d(TAG, "REPO: GDELT returned ${gdeltResults.size} raw articles")
                        val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                        gdeltCount = gdeltStories.size
                        Log.d(TAG, "REPO: GDELT mapped to $gdeltCount stories")
                        stories.addAll(gdeltStories)
                        _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "$gdeltCount stories from ${gdeltResults.size} articles")
                    } catch (e: Exception) {
                        Log.e(TAG, "REPO: GDELT global fetch failed: ${e.message}")
                        _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "FAILED: ${e.message}")
                    }

                    // Managed RSS feeds (297 bundled + user custom) — cached
                    _diagnostics.value = _diagnostics.value.copy(rssStatus = "Fetching feeds...")
                    val rssStories = getCachedRssStories()
                    rssCount = rssStories.size
                    Log.d(TAG, "REPO: RSS contributed $rssCount stories")
                    stories.addAll(rssStories)
                    _diagnostics.value = _diagnostics.value.copy(rssStatus = "$rssCount stories")

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
                    Log.d("GlobeNews", "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=$googleCount, RSS=$rssCount, Total=$totalDeduped ===")
                    logBrokenFeeds()
                }
            } else if (view.zoom < Constants.ZOOM_LOCAL_THRESHOLD) {
                Log.d("GlobeNews", "REPO: CONTINENTAL view path")
                var gdeltCount = 0
                var rssCount = 0

                // Continental view — sub-queries
                try {
                    val subRegions = getSubRegions(view)
                    val gdeltResults = gdeltDataSource.fetchForRegions(
                        regions = subRegions,
                        category = category,
                        maxRecords = 100,
                        timespan = "48h"
                    )
                    val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                    gdeltCount = gdeltStories.size
                    Log.d(TAG, "REPO: GDELT continental returned ${gdeltResults.size} articles, mapped to $gdeltCount stories")
                    stories.addAll(gdeltStories)
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: GDELT continental fetch failed: ${e.message}")
                }

                // Managed RSS feeds — cached
                val rssStories = getCachedRssStories()
                rssCount = rssStories.size
                stories.addAll(rssStories)

                val totalDeduped = deduplicateStories(stories).size
                Log.d(TAG, "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=0, RSS=$rssCount, Total=$totalDeduped ===")
                logBrokenFeeds()
            } else {
                Log.d(TAG, "REPO: LOCAL view path")
                var gdeltCount = 0
                var googleCount = 0
                var rssCount = 0

                // City/region view — single query + Google News local + cached RSS
                try {
                    val radiusKm = (40000.0 / Math.pow(2.0, view.zoom)).toInt().coerceIn(50, 2000)
                    Log.d(TAG, "REPO: GDELT nearby lat=${view.latitude}, lon=${view.longitude}, radius=${radiusKm}km")
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
                    Log.d(TAG, "REPO: GDELT nearby returned ${gdeltResults.size} articles, mapped to $gdeltCount stories")
                    stories.addAll(gdeltStories)
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: GDELT local fetch failed: ${e.message}")
                }

                // Google News local
                try {
                    val localNews = googleNewsDataSource.fetchLocal(view.latitude, view.longitude)
                    googleCount = localNews.size
                    Log.d(TAG, "REPO: Google News local returned $googleCount items")
                    stories.addAll(localNews.map {
                        StoryMappers.fromGoogleNews(it, view.latitude, view.longitude, null)
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: Google News fetch failed: ${e.message}")
                }

                // Managed RSS feeds — cached (adds global context to local view)
                val rssStories = getCachedRssStories()
                rssCount = rssStories.size
                stories.addAll(rssStories)

                val totalDeduped = deduplicateStories(stories).size
                Log.d(TAG, "=== COVERAGE: GDELT=$gdeltCount, GoogleRSS=$googleCount, RSS=$rssCount, Total=$totalDeduped ===")
                logBrokenFeeds()
            }

            // Deduplicate
            val deduped = deduplicateStories(stories)
            Log.d("GlobeNews", "REPO: after dedup: ${deduped.size} stories (from ${stories.size})")

            // Only replace fallback if we got real network results
            if (deduped.isNotEmpty()) {
                Log.e("GlobeNews", ">>> LIVE DATA OK: ${deduped.size} stories replacing fallback")
                storiesFlow.value = Result.Success(deduped)
                _diagnostics.value = _diagnostics.value.copy(
                    liveTotal = "${deduped.size} live stories (from ${stories.size} pre-dedup)",
                    pipelineSummary = "LIVE — ${deduped.size} stories from network sources"
                )
            } else {
                Log.e("GlobeNews", ">>> LIVE DATA FAILED: 0 network stories. ALL sources returned empty. App is showing FALLBACK ONLY. Check GDELT/RSS errors above.")
                _diagnostics.value = _diagnostics.value.copy(
                    liveTotal = "0 live stories",
                    pipelineSummary = "FALLBACK ONLY — all network sources returned 0. Check GDELT and RSS status above."
                )
            }
        } catch (e: Exception) {
            Log.e("GlobeNews", ">>> LIVE DATA EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
            )
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

    /** Get RSS stories from cache or fetch fresh if stale */
    private suspend fun getCachedRssStories(): List<NewsStory> {
        val cacheValid = Duration.between(rssCacheTime, Instant.now()).toMinutes() < Constants.RSS_REFRESH_MINUTES
            && rssCache.isNotEmpty()

        if (cacheValid) {
            Log.d(TAG, "REPO: using cached RSS (${rssCache.size} stories, age=${Duration.between(rssCacheTime, Instant.now()).toMinutes()}m)")
            return rssCache
        }

        return try {
            val rssItems = rssDataSource.fetchAllManagedFeeds()
            val rssStories = rssItems.map { StoryMappers.fromRss(it) }
            Log.d(TAG, "REPO: fresh RSS fetch returned ${rssStories.size} stories")
            if (rssStories.isNotEmpty()) {
                rssCache = rssStories
                rssCacheTime = Instant.now()
            }
            rssStories
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "REPO: managed RSS fetch failed: ${e.message}")
            rssCache // Return stale cache if available
        }
    }

    /** Log broken feeds at WARN level after each fetch cycle */
    private suspend fun logBrokenFeeds() {
        try {
            val broken = managedFeedDao.getBrokenFeeds()
            if (broken.isNotEmpty()) {
                Log.w("GlobeNews", "=== BROKEN FEEDS (${broken.size}) ===")
                broken.forEach {
                    Log.w("GlobeNews", "  ${it.name}: ${it.lastError} (${it.consecutiveFailures} failures)")
                }
            }
        } catch (e: Exception) {
            Log.e("GlobeNews", "REPO: failed to query broken feeds: ${e.message}")
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
