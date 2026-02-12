package com.threadline.data.repository

import android.content.Context
import android.util.Log
import com.threadline.core.common.Constants
import com.threadline.core.common.Result
import com.threadline.core.common.normalizeUrl
import com.threadline.core.common.titleWordOverlap
import com.threadline.data.mapper.EntityMappers
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.dao.StoryDao
import com.threadline.data.source.remote.gdelt.GdeltDataSource
import com.threadline.data.source.remote.rss.RssDataSource
import com.threadline.domain.model.GLOBAL_GRID
import com.threadline.domain.model.NewsStory
import com.threadline.domain.repository.DiagnosticInfo
import com.threadline.domain.repository.FeedRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gdeltDataSource: GdeltDataSource,
    private val rssDataSource: RssDataSource,
    private val storyDao: StoryDao,
    private val feedDao: FeedDao,
    private val moshi: Moshi
) : FeedRepository {

    companion object {
        private const val TAG = "FeedRepository"
    }

    private val storiesFlow = MutableStateFlow<Result<List<NewsStory>>>(Result.Loading)
    private val _diagnostics = MutableStateFlow(DiagnosticInfo())
    override val diagnostics: StateFlow<DiagnosticInfo> = _diagnostics

    private var rssCache: List<NewsStory> = emptyList()
    private var rssCacheTime: Instant = Instant.EPOCH
    private var rssLoaded = false
    private val gdeltScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getStories(): Flow<Result<List<NewsStory>>> = storiesFlow

    override suspend fun refreshStories() {
        Log.d(TAG, "REPO: refreshStories called")

        // 1. Load cached stories from Room → show immediately
        try {
            val cached = storyDao.getAll(Constants.MAX_STORIES_TOTAL)
            if (cached.isNotEmpty()) {
                val stories = cached.map { EntityMappers.entityToStory(it) }
                Log.d(TAG, "REPO: loaded ${stories.size} cached stories from Room")
                storiesFlow.value = Result.Success(stories)
                _diagnostics.value = _diagnostics.value.copy(cacheCount = "${cached.size}")
            } else {
                val fallback = loadFallbackStories()
                if (fallback.isNotEmpty()) {
                    storiesFlow.value = Result.Success(fallback)
                } else {
                    storiesFlow.value = Result.Loading
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: cache load failed: ${e.message}")
            storiesFlow.value = Result.Loading
        }

        // 2. Fetch RSS FIRST — primary data source
        val rssStories = mutableListOf<NewsStory>()
        try {
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "Fetching feeds...")
            val stories = getCachedRssStories()
            rssStories.addAll(stories)
            Log.d(TAG, "REPO: RSS returned ${rssStories.size} stories")
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "${rssStories.size} stories")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: RSS failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "FAILED: ${e.message}")
        }

        // Emit RSS stories immediately
        if (rssStories.isNotEmpty()) {
            val deduped = deduplicateStories(rssStories).take(Constants.MAX_STORIES_TOTAL)
            val existing = when (val current = storiesFlow.value) {
                is Result.Success -> current.data
                else -> emptyList()
            }
            val merged = mergeStories(existing, deduped).take(Constants.MAX_STORIES_TOTAL)
            storiesFlow.value = Result.Success(merged)
            rssLoaded = true
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "RSS — ${merged.size} stories",
                networkTest = "RSS OK"
            )

            // Cache RSS to Room
            try {
                val entities = deduped.map { EntityMappers.storyToEntity(it) }
                storyDao.upsertAll(entities)
                _diagnostics.value = _diagnostics.value.copy(cacheCount = "${storyDao.count()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "REPO: RSS cache write failed: ${e.message}")
            }

            // DIAGNOSTIC: sample story source names vs feed DB
            try {
                val sampleStories = storyDao.getAll(5)
                sampleStories.forEach { story ->
                    val exactMatch = feedDao.getByName(story.sourceName)
                    val fuzzyMatch = if (exactMatch == null) feedDao.getByNameFuzzy(story.sourceName) else null
                    val match = exactMatch ?: fuzzyMatch
                    val matchType = when {
                        exactMatch != null -> "EXACT"
                        fuzzyMatch != null -> "FUZZY"
                        else -> "NO MATCH"
                    }
                    Log.d(TAG, "STORY SOURCE: '${story.sourceName}' | $matchType: ${match?.name ?: "—"} region=${match?.region}")
                }
                // DIAGNOSTIC: sample feed tags
                val sampleFeeds = feedDao.getAll().take(5)
                sampleFeeds.forEach { feed ->
                    Log.d(TAG, "FEED TAGS: '${feed.name}' region=${feed.region} lean=${feed.politicalLean} owner=${feed.ownerName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "REPO: diagnostic logging failed: ${e.message}")
            }
        } else {
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "RSS returned 0 stories"
            )
        }

        // 3. GDELT — non-blocking background enrichment, only after RSS loaded
        if (rssLoaded) {
            gdeltScope.launch { fetchGdeltBackground() }
        }
    }

    private suspend fun fetchGdeltBackground() {
        try {
            _diagnostics.value = _diagnostics.value.copy(
                gdeltStatus = "Fetching ${GLOBAL_GRID.size} regions..."
            )
            val gdeltResults = gdeltDataSource.fetchForRegions(GLOBAL_GRID)
            val gdeltStories = gdeltResults.mapNotNull { EntityMappers.gdeltToStory(it) }
                .take(Constants.MAX_STORIES_PER_SOURCE)
            Log.d(TAG, "REPO: GDELT returned ${gdeltStories.size} stories (background)")
            _diagnostics.value = _diagnostics.value.copy(
                gdeltStatus = "${gdeltStories.size} stories"
            )

            if (gdeltStories.isNotEmpty()) {
                val existing = when (val current = storiesFlow.value) {
                    is Result.Success -> current.data
                    else -> emptyList()
                }
                val allNew = deduplicateStories(gdeltStories)
                val merged = mergeStories(existing, allNew).take(Constants.MAX_STORIES_TOTAL)
                storiesFlow.value = Result.Success(merged)
                _diagnostics.value = _diagnostics.value.copy(
                    pipelineSummary = "LIVE — ${merged.size} stories (RSS+GDELT)"
                )

                try {
                    val entities = allNew.map { EntityMappers.storyToEntity(it) }
                    storyDao.upsertAll(entities)
                    _diagnostics.value = _diagnostics.value.copy(
                        cacheCount = "${storyDao.count()}"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "REPO: GDELT cache write failed: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: GDELT background failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(
                gdeltStatus = "FAILED: ${e.message}"
            )
        }
    }

    private suspend fun getCachedRssStories(): List<NewsStory> {
        val cacheValid = Duration.between(rssCacheTime, Instant.now()).toMinutes() < Constants.RSS_REFRESH_MINUTES
            && rssCache.isNotEmpty()
        if (cacheValid) return rssCache

        return try {
            val rssItems = rssDataSource.fetchAllManagedFeeds { batchItems ->
                // Incremental: emit each batch to the UI as it arrives
                val batchStories = batchItems.map { EntityMappers.rssToStory(it) }
                if (batchStories.isNotEmpty()) {
                    val existing = when (val current = storiesFlow.value) {
                        is Result.Success -> current.data
                        else -> emptyList()
                    }
                    val merged = mergeStories(existing, batchStories)
                        .take(Constants.MAX_STORIES_TOTAL)
                    storiesFlow.value = Result.Success(merged)
                    Log.d(TAG, "REPO: RSS batch +${batchStories.size} → ${merged.size} total")
                }
            }
            val rssStories = rssItems.map { EntityMappers.rssToStory(it) }
                .take(Constants.MAX_STORIES_PER_SOURCE)
            if (rssStories.isNotEmpty()) {
                rssCache = rssStories
                rssCacheTime = Instant.now()
            }
            rssStories
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: RSS fetch failed: ${e.message}")
            rssCache
        }
    }

    private fun mergeStories(existing: List<NewsStory>, fresh: List<NewsStory>): List<NewsStory> {
        val byId = LinkedHashMap<String, NewsStory>()
        existing.forEach { byId[it.id] = it }
        fresh.forEach { byId[it.id] = it }
        return byId.values.sortedByDescending { it.publishedAt }
    }

    private fun deduplicateStories(stories: List<NewsStory>): List<NewsStory> {
        val seen = mutableMapOf<String, NewsStory>()
        val result = mutableListOf<NewsStory>()

        for (story in stories) {
            val normalizedUrl = story.url.normalizeUrl()
            if (seen.containsKey(normalizedUrl)) continue

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

    private fun loadFallbackStories(): List<NewsStory> {
        return try {
            val json = context.assets.open("fallback_news.json").bufferedReader().readText()
            val type = Types.newParameterizedType(List::class.java, Map::class.java, String::class.java, Any::class.java)
            val adapter = moshi.adapter<List<Map<String, Any>>>(type)
            val list = adapter.fromJson(json) ?: return emptyList()
            list.mapNotNull { map ->
                try {
                    EntityMappers.fallbackToStory(
                        title = map["title"] as? String ?: return@mapNotNull null,
                        url = map["url"] as? String ?: return@mapNotNull null,
                        summary = map["summary"] as? String,
                        imageUrl = map["imageUrl"] as? String,
                        lat = (map["lat"] as? Number)?.toDouble() ?: 0.0,
                        lon = (map["lon"] as? Number)?.toDouble() ?: 0.0,
                        placeName = map["placeName"] as? String,
                        countryCode = map["countryCode"] as? String,
                        category = map["category"] as? String ?: "ALL",
                        scope = map["scope"] as? String ?: "INTERNATIONAL",
                        sourceName = map["sourceName"] as? String ?: "Unknown",
                        language = map["language"] as? String
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback load failed: ${e.message}")
            emptyList()
        }
    }
}
