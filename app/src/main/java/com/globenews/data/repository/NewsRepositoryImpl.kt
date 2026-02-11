package com.globenews.data.repository

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.core.common.Result
import com.globenews.core.common.normalizeUrl
import com.globenews.core.common.titleWordOverlap
import com.globenews.data.mapper.StoryMappers
import com.globenews.data.source.local.FallbackDataSource
import com.globenews.data.source.local.ManagedFeedDao
import com.globenews.data.source.local.StoryDao
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val storyDao: StoryDao,
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
    private var queriesThisSession = 0
    private val MAX_QUERIES = 40

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

        // Step 1: Always load Room cache for viewport (merge with existing)
        try {
            val bounds = view.bounds
            val maxAge = System.currentTimeMillis() - (2 * 60 * 60 * 1000L) // 2h TTL
            val cached = if (bounds != null) {
                storyDao.getInBounds(bounds.north, bounds.south, bounds.east, bounds.west, maxAge)
            } else {
                storyDao.getInBounds(90.0, -90.0, 180.0, -180.0, maxAge)
            }
            if (cached.isNotEmpty()) {
                val cachedStories = cached.map { StoryMappers.fromEntity(it) }
                val existing = (storiesFlow.value as? Result.Success)?.data ?: emptyList()
                val merged = deduplicateStories(existing + cachedStories)
                    .sortedByDescending { it.publishedAt }
                    .take(Constants.MAX_TOTAL_STORIES)
                storiesFlow.value = Result.Success(merged)
                Log.d(TAG, "REPO: ${cached.size} stories from Room cache (${merged.size} total)")
                _diagnostics.value = _diagnostics.value.copy(
                    cacheStatus = "${cached.size} cached stories loaded"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "REPO: Room cache read failed: ${e.message}")
        }

        // Fallback if still no data at all
        val afterCache = storiesFlow.value
        if (afterCache !is Result.Success || afterCache.data.isEmpty()) {
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
        }

        // Session query cap — serve from cache only when budget exhausted
        if (queriesThisSession >= MAX_QUERIES) {
            Log.d(TAG, "REPO: query cap reached ($queriesThisSession/$MAX_QUERIES), cache only")
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "CACHE ONLY — query cap ($MAX_QUERIES) reached"
            )
            try {
                val bounds = view.bounds
                val maxAge = System.currentTimeMillis() - (2 * 60 * 60 * 1000L) // 2h TTL
                val cached = if (bounds != null) {
                    storyDao.getInBounds(bounds.north, bounds.south, bounds.east, bounds.west, maxAge)
                } else {
                    storyDao.getInBounds(90.0, -90.0, 180.0, -180.0, maxAge)
                }
                if (cached.isNotEmpty()) {
                    val cachedStories = cached.map { StoryMappers.fromEntity(it) }
                    val existing = (storiesFlow.value as? Result.Success)?.data ?: emptyList()
                    val merged = deduplicateStories(existing + cachedStories)
                        .sortedByDescending { it.publishedAt }
                        .take(Constants.MAX_TOTAL_STORIES)
                    storiesFlow.value = Result.Success(merged)
                }
            } catch (e: Exception) {
                Log.e(TAG, "REPO: cache-only read failed: ${e.message}")
            }
            return
        }
        queriesThisSession++

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

        // Step 2-5: Viewport-driven incremental fetch
        try {
            val stories = mutableListOf<NewsStory>()
            var gdeltCount = 0
            var rssCount = 0

            // ── STEP 2: Fast viewport GDELT ──
            _diagnostics.value = _diagnostics.value.copy(
                gdeltStatus = "Fetching viewport...",
                gdeltRegionsTotal = 1,
                gdeltRegionsSucceeded = 0,
                gdeltRegionsFailed = 0,
                gdeltRegionsRateLimited = 0
            )
            try {
                val gdeltResults = if (view.zoom < 3.0) {
                    // World zoom: quick 5-region sample instead of all 27
                    Log.d(TAG, "REPO: GLOBAL quick-world fetch (5 regions)")
                    _diagnostics.value = _diagnostics.value.copy(
                        gdeltStatus = "Fetching 5 sample regions...",
                        gdeltRegionsTotal = 5
                    )
                    val quickRegions = listOf(
                        GLOBAL_GRID[0],  // West Africa
                        GLOBAL_GRID[4],  // Eastern US/Canada
                        GLOBAL_GRID[10], // Western Europe
                        GLOBAL_GRID[15], // Middle East
                        GLOBAL_GRID[19]  // Southeast Asia
                    )
                    var regionsOk = 0
                    var regionsFail = 0
                    gdeltDataSource.fetchForRegions(
                        regions = quickRegions,
                        category = category,
                        maxRecords = 75,
                        timespan = "24h",
                        onRegionResult = { result ->
                            if (result.succeeded) regionsOk++ else regionsFail++
                            val logResult = if (result.succeeded) "OK (${result.articleCount} articles)"
                                else result.error ?: "Failed"
                            _diagnostics.value = _diagnostics.value
                                .addLog("GDELT", result.regionName, logResult)
                                .copy(
                                    gdeltRegionsSucceeded = regionsOk,
                                    gdeltRegionsFailed = regionsFail,
                                    gdeltRegionsRateLimited = if (result.rateLimited) 1 else 0,
                                    gdeltStatus = "$regionsOk/5 quick regions done"
                                )
                        }
                    )
                } else if (view.zoom < Constants.ZOOM_WORLD_THRESHOLD) {
                    // Continental zoom: viewport query
                    Log.d(TAG, "REPO: viewport GDELT at zoom=${view.zoom}")
                    gdeltDataSource.fetchForViewport(
                        centerLat = view.latitude,
                        centerLon = view.longitude,
                        zoom = view.zoom,
                        category = category,
                        onRegionResult = { result ->
                            val logResult = if (result.succeeded) "OK (${result.articleCount} articles)"
                                else result.error ?: "Failed"
                            _diagnostics.value = _diagnostics.value
                                .addLog("GDELT", result.regionName, logResult)
                                .copy(
                                    gdeltRegionsSucceeded = if (result.succeeded) 1 else 0,
                                    gdeltRegionsFailed = if (!result.succeeded) 1 else 0,
                                    gdeltRegionsRateLimited = if (result.rateLimited) 1 else 0,
                                    gdeltStatus = if (result.succeeded) "OK — ${result.articleCount} articles" else "Failed: ${result.error}"
                                )
                        }
                    )
                } else if (view.zoom < Constants.ZOOM_LOCAL_THRESHOLD) {
                    // Continental: sub-region queries
                    Log.d(TAG, "REPO: CONTINENTAL sub-region GDELT")
                    val subRegions = getSubRegions(view)
                    _diagnostics.value = _diagnostics.value.copy(
                        gdeltRegionsTotal = subRegions.size,
                        gdeltStatus = "Fetching ${subRegions.size} sub-regions..."
                    )
                    gdeltDataSource.fetchForRegions(
                        regions = subRegions,
                        category = category,
                        maxRecords = 100,
                        timespan = "48h"
                    )
                } else {
                    // Local: nearby query
                    Log.d(TAG, "REPO: LOCAL nearby GDELT")
                    val radiusKm = (40000.0 / Math.pow(2.0, view.zoom)).toInt().coerceIn(50, 2000)
                    gdeltDataSource.fetchNearby(
                        lat = view.latitude,
                        lon = view.longitude,
                        radiusKm = radiusKm,
                        category = category,
                        maxRecords = 100,
                        timespan = "7d"
                    )
                }

                val gdeltStories = gdeltResults.mapNotNull { StoryMappers.fromGdelt(it) }
                gdeltCount = gdeltStories.size
                Log.d(TAG, "REPO: GDELT returned $gdeltCount stories")
                stories.addAll(gdeltStories)
                _diagnostics.value = _diagnostics.value.copy(
                    gdeltStatus = "$gdeltCount stories"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "REPO: GDELT fetch failed: ${e.message}")
                _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "FAILED: ${e.message}")
            }

            // ── STEP 3: Push GDELT interim to map (merge with existing) ──
            if (stories.isNotEmpty()) {
                val existing = (storiesFlow.value as? Result.Success)?.data ?: emptyList()
                val interim = deduplicateStories(existing + stories)
                    .sortedByDescending { it.publishedAt }
                    .take(Constants.MAX_TOTAL_STORIES)
                storiesFlow.value = Result.Success(interim)
                Log.d(TAG, "REPO: interim merge — ${interim.size} stories on map (${stories.size} new GDELT + ${existing.size} existing)")
                // Persist GDELT stories immediately so they survive cancellation
                try {
                    storyDao.upsertAll(stories.map { StoryMappers.toEntity(it) })
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: GDELT interim cache write failed: ${e.message}")
                }
            }

            // ── STEP 4: Viewport RSS (only feeds on screen + international) ──
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "Fetching viewport feeds...")
            try {
                val rssStories = getViewportRssStories(view)
                rssCount = rssStories.size
                Log.d(TAG, "REPO: RSS contributed $rssCount stories")
                stories.addAll(rssStories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "REPO: RSS fetch failed: ${e.message}")
                _diagnostics.value = _diagnostics.value.copy(rssStatus = "FAILED: ${e.message}")
            }

            // Google News local (for zoomed-in views)
            if (view.zoom >= Constants.ZOOM_LOCAL_THRESHOLD) {
                try {
                    val localNews = googleNewsDataSource.fetchLocal(view.latitude, view.longitude)
                    Log.d(TAG, "REPO: Google News local returned ${localNews.size} items")
                    stories.addAll(localNews.map {
                        StoryMappers.fromGoogleNews(it, view.latitude, view.longitude, null)
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: Google News fetch failed: ${e.message}")
                }
            }

            // Optional API sources
            if (newsApiDataSource.isAvailable) {
                try {
                    val items = newsApiDataSource.fetchHeadlines()
                    stories.addAll(items.mapNotNull { StoryMappers.fromNewsApi(it) })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "REPO: NewsAPI failed: ${e.message}") }
            }
            if (gNewsDataSource.isAvailable) {
                try {
                    val items = gNewsDataSource.fetchHeadlines()
                    stories.addAll(items.mapNotNull { StoryMappers.fromGNews(it) })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "REPO: GNews failed: ${e.message}") }
            }

            // ── STEP 5: Deduplicate, cap, emit final ──
            val dedupedRaw = deduplicateStories(stories)
            val deduped = if (dedupedRaw.size > Constants.MAX_TOTAL_STORIES) {
                Log.w("GlobeNews", "REPO: capping stories from ${dedupedRaw.size} to ${Constants.MAX_TOTAL_STORIES}")
                dedupedRaw.sortedByDescending { it.publishedAt }.take(Constants.MAX_TOTAL_STORIES)
            } else {
                dedupedRaw
            }
            Log.d("GlobeNews", "REPO: after dedup: ${deduped.size} stories (from ${stories.size})")

            if (deduped.isNotEmpty()) {
                // Merge new stories with any existing accumulated stories
                val existing = (storiesFlow.value as? Result.Success)?.data ?: emptyList()
                val finalMerged = deduplicateStories(existing + deduped)
                    .sortedByDescending { it.publishedAt }
                    .take(Constants.MAX_TOTAL_STORIES)
                Log.d("GlobeNews", ">>> LIVE DATA OK: ${finalMerged.size} stories (${deduped.size} new + ${existing.size} existing)")
                storiesFlow.value = Result.Success(finalMerged)
                _diagnostics.value = _diagnostics.value.copy(
                    liveTotal = "${finalMerged.size} live stories (GDELT=$gdeltCount, RSS=$rssCount)",
                    pipelineSummary = "LIVE — ${finalMerged.size} stories"
                )

                // Cache in memory + Room
                globalGridCache = finalMerged
                globalGridCacheTime = Instant.now()
                globalGridCacheCategory = category
                try {
                    storyDao.upsertAll(finalMerged.map { StoryMappers.toEntity(it) })
                    val cacheTotal = storyDao.count()
                    Log.d(TAG, "REPO: upserted ${finalMerged.size} stories into Room cache ($cacheTotal total)")
                    _diagnostics.value = _diagnostics.value.copy(
                        cacheStatus = "$cacheTotal stories cached",
                        queriesUsed = queriesThisSession,
                        queriesMax = MAX_QUERIES
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: Room cache write failed: ${e.message}")
                }
            } else {
                Log.e("GlobeNews", ">>> LIVE DATA FAILED: 0 network stories")
                _diagnostics.value = _diagnostics.value.copy(
                    liveTotal = "0 live stories",
                    pipelineSummary = "FALLBACK ONLY — all sources returned 0"
                )
            }

            // ── STEP 6: Background grid fill (world zoom only, non-blocking) ──
            if (view.zoom < Constants.ZOOM_WORLD_THRESHOLD && deduped.isNotEmpty()) {
                Log.d(TAG, "REPO: starting background grid fill")
                _diagnostics.value = _diagnostics.value.copy(
                    pipelineSummary = "LIVE — ${deduped.size} stories. Background fill running..."
                )
                try {
                    gdeltDataSource.backgroundFillGrid(category) { regionName, regionArticles ->
                        val regionStories = regionArticles.mapNotNull { StoryMappers.fromGdelt(it) }
                        val currentData = (storiesFlow.value as? Result.Success)?.data ?: emptyList()
                        val merged = deduplicateStories(currentData + regionStories)
                            .sortedByDescending { it.publishedAt }
                            .take(Constants.MAX_TOTAL_STORIES)
                        if (merged.size > currentData.size) {
                            storiesFlow.value = Result.Success(merged)
                            _diagnostics.value = _diagnostics.value
                                .addLog("GDELT", regionName, "bg +${merged.size - currentData.size}")
                                .copy(
                                    liveTotal = "${merged.size} live stories (filling...)",
                                    pipelineSummary = "LIVE — ${merged.size} stories. Background fill..."
                                )
                        }
                    }
                    val finalCount = (storiesFlow.value as? Result.Success)?.data?.size ?: 0
                    _diagnostics.value = _diagnostics.value.copy(
                        pipelineSummary = "LIVE — $finalCount stories (fill complete)"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "REPO: background fill failed: ${e.message}")
                }
            }

            logBrokenFeeds()
        } catch (e: CancellationException) {
            Log.d("GlobeNews", "REPO: refreshStories cancelled, rethrowing")
            throw e
        } catch (e: Exception) {
            Log.e("GlobeNews", ">>> LIVE DATA EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
            )
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

    /** Get RSS stories from cache or fetch fresh if stale, with real-time diagnostics */
    private suspend fun getCachedRssStories(): List<NewsStory> {
        val cacheValid = Duration.between(rssCacheTime, Instant.now()).toMinutes() < Constants.RSS_REFRESH_MINUTES
            && rssCache.isNotEmpty()

        if (cacheValid) {
            Log.d(TAG, "REPO: using cached RSS (${rssCache.size} stories, age=${Duration.between(rssCacheTime, Instant.now()).toMinutes()}m)")
            _diagnostics.value = _diagnostics.value.copy(
                rssStatus = "${rssCache.size} stories (cached)"
            )
            return rssCache
        }

        return try {
            var feedsOk = 0
            var feedsFail = 0
            var feedsTotal = 0
            val rssItems = rssDataSource.fetchAllManagedFeeds(
                onTotalKnown = { total ->
                    feedsTotal = total
                    _diagnostics.value = _diagnostics.value.copy(
                        rssFeedsTotal = total,
                        rssFeedsSucceeded = 0,
                        rssFeedsFailed = 0,
                        rssFeedsStillFetching = total,
                        rssStatus = "0 succeeded, 0 failed, $total still fetching"
                    )
                },
                onFeedResult = { result ->
                    if (result.succeeded) feedsOk++ else feedsFail++
                    val stillFetching = feedsTotal - feedsOk - feedsFail
                    val logResult = if (result.succeeded) "OK (${result.itemCount} items)"
                        else result.error ?: "Failed"
                    _diagnostics.value = _diagnostics.value
                        .addLog("RSS", result.feedName, logResult)
                        .copy(
                            rssFeedsSucceeded = feedsOk,
                            rssFeedsFailed = feedsFail,
                            rssFeedsStillFetching = stillFetching,
                            rssStatus = "$feedsOk succeeded, $feedsFail failed, $stillFetching still fetching"
                        )
                }
            )
            val rssStories = rssItems.map { StoryMappers.fromRss(it) }
            Log.d(TAG, "REPO: fresh RSS fetch returned ${rssStories.size} stories")
            if (rssStories.isNotEmpty()) {
                rssCache = rssStories
                rssCacheTime = Instant.now()
            }
            _diagnostics.value = _diagnostics.value.copy(
                rssStatus = "${rssStories.size} stories — $feedsOk/$feedsTotal feeds OK, $feedsFail failed",
                rssFeedsStillFetching = 0
            )
            rssStories
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "REPO: managed RSS fetch failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(
                rssStatus = "FAILED: ${e.message}"
            )
            rssCache // Return stale cache if available
        }
    }

    /** Get RSS stories for the current viewport — uses bounds query for zoomed-in views */
    private suspend fun getViewportRssStories(view: GlobeView): List<NewsStory> {
        // World zoom or no bounds: use full cached fetch
        if (view.zoom < Constants.ZOOM_WORLD_THRESHOLD || view.bounds == null) {
            return getCachedRssStories()
        }

        // Zoomed in: fetch only feeds within viewport bounds + international
        val bounds = view.bounds
        var feedsOk = 0
        var feedsFail = 0
        var feedsTotal = 0

        return try {
            val rssItems = rssDataSource.fetchForViewport(
                north = bounds.north,
                south = bounds.south,
                east = bounds.east,
                west = bounds.west,
                onTotalKnown = { total ->
                    feedsTotal = total
                    _diagnostics.value = _diagnostics.value.copy(
                        rssFeedsTotal = total,
                        rssFeedsSucceeded = 0,
                        rssFeedsFailed = 0,
                        rssFeedsStillFetching = total,
                        rssStatus = "0 succeeded, 0 failed, $total viewport feeds"
                    )
                },
                onFeedResult = { result ->
                    if (result.succeeded) feedsOk++ else feedsFail++
                    val stillFetching = feedsTotal - feedsOk - feedsFail
                    val logResult = if (result.succeeded) "OK (${result.itemCount} items)"
                        else result.error ?: "Failed"
                    _diagnostics.value = _diagnostics.value
                        .addLog("RSS", result.feedName, logResult)
                        .copy(
                            rssFeedsSucceeded = feedsOk,
                            rssFeedsFailed = feedsFail,
                            rssFeedsStillFetching = stillFetching,
                            rssStatus = "$feedsOk succeeded, $feedsFail failed, $stillFetching still fetching"
                        )
                }
            )
            val rssStories = rssItems.map { StoryMappers.fromRss(it) }
            Log.d(TAG, "REPO: viewport RSS returned ${rssStories.size} stories")
            _diagnostics.value = _diagnostics.value.copy(
                rssStatus = "${rssStories.size} stories — $feedsOk/$feedsTotal feeds OK",
                rssFeedsStillFetching = 0
            )
            rssStories
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: viewport RSS fetch failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "FAILED: ${e.message}")
            rssCache // Fall back to stale cache if available
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
