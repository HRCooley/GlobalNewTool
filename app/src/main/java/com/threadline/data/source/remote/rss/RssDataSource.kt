package com.threadline.data.source.remote.rss

import android.util.Log
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.entity.ManagedFeedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RssDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val feedDao: FeedDao
) {
    companion object {
        private const val TAG = "RssDataSource"
        val PRIORITY_FEEDS = listOf(
            "Al Jazeera", "BBC World", "The Guardian World", "France 24",
            "DW News", "Reuters", "Associated Press", "NPR",
            "The Conversation", "Ars Technica", "BleepingComputer"
        )
    }

    private val rssClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchAllManagedFeeds(
        onBatchReady: (suspend (List<RssItem>) -> Unit)? = null
    ): List<RssItem> {
        var feeds = feedDao.getEnabled().filter { it.consecutiveFailures < 10 }

        if (feeds.isEmpty()) {
            Log.d(TAG, "RSS: 0 feeds in DB, waiting for import...")
            for (attempt in 1..10) {
                delay(1000)
                feeds = feedDao.getEnabled().filter { it.consecutiveFailures < 10 }
                if (feeds.isNotEmpty()) {
                    Log.d(TAG, "RSS: import ready after ${attempt}s — ${feeds.size} feeds")
                    break
                }
            }
            if (feeds.isEmpty()) {
                Log.w(TAG, "RSS: still 0 feeds after 10s, returning empty")
                return emptyList()
            }
        }

        // Sort: priority feeds first, then the rest
        val priorityNames = PRIORITY_FEEDS.map { it.lowercase() }.toSet()
        val sorted = feeds.sortedByDescending { it.name.lowercase() in priorityNames }

        // Cap at 30 feeds per fetch
        val cappedFeeds = sorted.take(30)
        Log.d(TAG, "RSS: fetching ${cappedFeeds.size} feeds (of ${feeds.size} enabled)")

        val allItems = Collections.synchronizedList(mutableListOf<RssItem>())
        var successCount = 0
        var failCount = 0

        val batches = cappedFeeds.chunked(5)
        batches.forEachIndexed { batchIdx, batch ->
            val batchItems = Collections.synchronizedList(mutableListOf<RssItem>())
            coroutineScope {
                batch.map { feed ->
                    async {
                        try {
                            val items = fetchSingleFeed(feed)
                            feedDao.recordSuccess(feed.id, System.currentTimeMillis())
                            allItems.addAll(items)
                            batchItems.addAll(items)
                            successCount++
                            Log.d(TAG, "RSS RESULT: ${feed.name} - OK: ${items.size} stories")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            feedDao.recordFailure(
                                feed.id,
                                System.currentTimeMillis(),
                                e.message ?: "Unknown error"
                            )
                            failCount++
                            Log.d(TAG, "RSS RESULT: ${feed.name} - FAIL: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
            // Emit batch incrementally
            if (batchItems.isNotEmpty() && onBatchReady != null) {
                onBatchReady(batchItems.toList())
            }
            if (batchIdx < batches.size - 1) {
                delay(500)
            }
        }

        Log.d(TAG, "RSS SUMMARY: $successCount/${cappedFeeds.size} feeds succeeded, ${allItems.size} stories")
        return allItems
    }

    private suspend fun fetchSingleFeed(feed: ManagedFeedEntity): List<RssItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", "Threadline/0.1")
            .build()

        val response = rssClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code} for ${feed.name}")
        }
        val body = response.body?.string() ?: return@withContext emptyList()
        RssFeedParser.parse(
            xml = body,
            feedName = feed.name,
            country = feed.country,
            language = feed.language,
            lat = feed.latitude,
            lon = feed.longitude,
            scope = feed.scope
        )
    }
}
