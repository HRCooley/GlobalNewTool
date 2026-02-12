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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val okHttpClient: OkHttpClient,
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
    private var networkTestDone = false
    private var queryCount = 0

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
                // Try fallback
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

        // One-time network test
        if (!networkTestDone) {
            networkTestDone = true
            _diagnostics.value = _diagnostics.value.copy(networkTest = "Testing...")
            try {
                withContext(Dispatchers.IO) {
                    val testUrl = "https://api.gdeltproject.org/api/v2/doc/doc?query=news&mode=artlist&maxrecords=1&format=json"
                    val request = Request.Builder().url(testUrl).build()
                    val response = okHttpClient.newCall(request).execute()
                    _diagnostics.value = _diagnostics.value.copy(networkTest = "OK — HTTP ${response.code}")
                    response.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(networkTest = "FAILED: ${e.message}")
            }
        }

        // Session query cap
        if (queryCount >= Constants.MAX_QUERIES_PER_SESSION) {
            Log.w(TAG, "REPO: session query cap reached ($queryCount)")
            return
        }
        queryCount++

        // 2. Fetch GDELT → merge
        val allStories = mutableListOf<NewsStory>()
        try {
            _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "Fetching ${GLOBAL_GRID.size} regions...")
            val gdeltResults = gdeltDataSource.fetchForRegions(GLOBAL_GRID)
            val gdeltStories = gdeltResults.mapNotNull { EntityMappers.gdeltToStory(it) }
                .take(Constants.MAX_STORIES_PER_SOURCE)
            allStories.addAll(gdeltStories)
            Log.d(TAG, "REPO: GDELT returned ${gdeltStories.size} stories")
            _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "${gdeltStories.size} stories")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: GDELT failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(gdeltStatus = "FAILED: ${e.message}")
        }

        // 3. Fetch RSS → merge
        try {
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "Fetching feeds...")
            val rssStories = getCachedRssStories()
            allStories.addAll(rssStories)
            Log.d(TAG, "REPO: RSS contributed ${rssStories.size} stories")
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "${rssStories.size} stories")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "REPO: RSS failed: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(rssStatus = "FAILED: ${e.message}")
        }

        // Deduplicate and cap
        val deduped = deduplicateStories(allStories).take(Constants.MAX_STORIES_TOTAL)
        Log.d(TAG, "REPO: after dedup: ${deduped.size} (from ${allStories.size})")

        if (deduped.isNotEmpty()) {
            // Merge with existing
            val existing = when (val current = storiesFlow.value) {
                is Result.Success -> current.data
                else -> emptyList()
            }
            val merged = mergeStories(existing, deduped).take(Constants.MAX_STORIES_TOTAL)
            storiesFlow.value = Result.Success(merged)
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "LIVE — ${merged.size} stories"
            )

            // Cache to Room
            try {
                val entities = deduped.map { EntityMappers.storyToEntity(it) }
                storyDao.upsertAll(entities)
                _diagnostics.value = _diagnostics.value.copy(cacheCount = "${storyDao.count()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "REPO: cache write failed: ${e.message}")
            }
        } else {
            _diagnostics.value = _diagnostics.value.copy(
                pipelineSummary = "No new stories from network"
            )
        }
    }

    private suspend fun getCachedRssStories(): List<NewsStory> {
        val cacheValid = Duration.between(rssCacheTime, Instant.now()).toMinutes() < Constants.RSS_REFRESH_MINUTES
            && rssCache.isNotEmpty()
        if (cacheValid) return rssCache

        return try {
            val rssItems = rssDataSource.fetchAllManagedFeeds()
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
