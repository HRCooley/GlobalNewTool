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
    }

    private val rssClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchAllManagedFeeds(): List<RssItem> {
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

        // Cap at 30 feeds per fetch
        val cappedFeeds = feeds.take(30)
        Log.d(TAG, "RSS: fetching ${cappedFeeds.size} feeds (of ${feeds.size} enabled)")

        val allItems = Collections.synchronizedList(mutableListOf<RssItem>())
        var successCount = 0
        var failCount = 0

        val batches = cappedFeeds.chunked(5)
        batches.forEachIndexed { batchIdx, batch ->
            coroutineScope {
                batch.map { feed ->
                    async {
                        try {
                            val items = fetchSingleFeed(feed)
                            feedDao.recordSuccess(feed.id, System.currentTimeMillis())
                            allItems.addAll(items)
                            successCount++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            feedDao.recordFailure(
                                feed.id,
                                System.currentTimeMillis(),
                                e.message ?: "Unknown error"
                            )
                            failCount++
                            Log.w(TAG, "RSS FAIL: ${feed.name} — ${e.message}")
                        }
                    }
                }.awaitAll()
            }
            if (batchIdx < batches.size - 1) {
                delay(500)
            }
        }

        Log.d(TAG, "RSS: Done. $successCount OK, $failCount failed, ${allItems.size} items")
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
