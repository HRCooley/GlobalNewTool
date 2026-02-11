package com.globenews.data.source.remote.rss

import android.content.Context
import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.data.source.local.ManagedFeed
import com.globenews.data.source.local.ManagedFeedDao
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

@Singleton
class RssDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val managedFeedDao: ManagedFeedDao
) {
    companion object {
        private const val TAG = "RssDataSource"
    }

    data class RssItem(
        val title: String,
        val link: String,
        val pubDate: Instant?,
        val description: String?,
        val feedConfig: RssFeedConfig
    )

    private var feedConfigs: List<RssFeedConfig>? = null

    // Dedicated client with tight timeouts for RSS feeds
    private val rssClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun loadFeedConfigs(): List<RssFeedConfig> {
        feedConfigs?.let { return it }
        return try {
            val json = context.assets.open("rss_feeds.json").bufferedReader().readText()
            val type = Types.newParameterizedType(List::class.java, RssFeedConfig::class.java)
            val adapter = moshi.adapter<List<RssFeedConfig>>(type)
            val configs = adapter.fromJson(json) ?: emptyList()
            feedConfigs = configs
            configs
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load rss_feeds.json: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAllFeeds(): List<RssItem> = coroutineScope {
        val configs = loadFeedConfigs()
        fetchFeeds(configs)
    }

    suspend fun fetchFeeds(configs: List<RssFeedConfig>): List<RssItem> = coroutineScope {
        val results = configs.map { config ->
            async {
                try {
                    fetchFeed(config)
                } catch (e: Exception) {
                    Log.w(TAG, "Feed ${config.name} failed: ${e.message}")
                    emptyList()
                }
            }
        }
        results.awaitAll().flatten()
    }

    /** Per-feed result for diagnostics */
    data class RssFeedResult(
        val feedName: String,
        val itemCount: Int,
        val succeeded: Boolean,
        val error: String? = null
    )

    /**
     * Fetch all enabled managed feeds from Room in batches of 10 with 200ms delay.
     * Records success/failure per feed for health tracking.
     * Skips feeds with 10+ consecutive failures.
     * @param onFeedResult called after each feed completes, for real-time diagnostics
     * @param onTotalKnown called once with total feed count before fetching starts
     */
    suspend fun fetchAllManagedFeeds(
        onFeedResult: ((RssFeedResult) -> Unit)? = null,
        onTotalKnown: ((Int) -> Unit)? = null
    ): List<RssItem> {
        var feeds = managedFeedDao.getEnabledFeeds()
            .filter { it.consecutiveFailures < Constants.RSS_SKIP_AFTER_FAILURES }

        // On first launch, feed import runs async and may not be done yet.
        // Wait up to 10s for feeds to appear in Room.
        if (feeds.isEmpty()) {
            Log.d("GlobeNews", "RSS: 0 feeds in DB, waiting for import...")
            for (attempt in 1..10) {
                delay(1000)
                feeds = managedFeedDao.getEnabledFeeds()
                    .filter { it.consecutiveFailures < Constants.RSS_SKIP_AFTER_FAILURES }
                if (feeds.isNotEmpty()) {
                    Log.d("GlobeNews", "RSS: import ready after ${attempt}s — ${feeds.size} feeds")
                    break
                }
            }
            if (feeds.isEmpty()) {
                Log.w("GlobeNews", "RSS: still 0 feeds after 10s wait, returning empty")
                return emptyList()
            }
        }

        onTotalKnown?.invoke(feeds.size)

        val brokenCount = managedFeedDao.getBrokenCount()
        Log.d("GlobeNews", "RSS: fetching ${feeds.size} enabled managed feeds ($brokenCount broken)")

        val allItems = Collections.synchronizedList(mutableListOf<RssItem>())
        var successCount = 0
        var failCount = 0

        val batches = feeds.chunked(Constants.RSS_BATCH_SIZE)
        batches.forEachIndexed { batchIdx, batch ->
            coroutineScope {
                batch.map { feed ->
                    async {
                        try {
                            val items = fetchManagedFeed(feed)
                                .take(Constants.RSS_MAX_ITEMS_PER_FEED)
                            managedFeedDao.recordSuccess(feed.id, System.currentTimeMillis())
                            allItems.addAll(items)
                            successCount++
                            onFeedResult?.invoke(
                                RssFeedResult(feed.name, items.size, succeeded = true)
                            )
                        } catch (e: Exception) {
                            managedFeedDao.recordFailure(
                                feed.id,
                                System.currentTimeMillis(),
                                e.message ?: "Unknown error"
                            )
                            failCount++
                            Log.w("GlobeNews", "RSS FAIL: ${feed.name} — ${e.message}")
                            onFeedResult?.invoke(
                                RssFeedResult(
                                    feed.name, 0, succeeded = false,
                                    error = e.message ?: "Unknown error"
                                )
                            )
                        }
                    }
                }.awaitAll()
            }
            if (batchIdx < batches.size - 1) {
                delay(Constants.RSS_BATCH_DELAY_MS)
            }
        }

        val capped = allItems.take(Constants.RSS_MAX_STORIES)
        if (capped.size < allItems.size) {
            Log.w("GlobeNews", "RSS: capped from ${allItems.size} to ${capped.size} items")
        }
        Log.d("GlobeNews", "RSS: Done. $successCount OK, $failCount failed, ${capped.size} items (from ${allItems.size}) from ${feeds.size} feeds")
        return capped
    }

    private suspend fun fetchManagedFeed(feed: ManagedFeed): List<RssItem> = withContext(Dispatchers.IO) {
        val config = RssFeedConfig(
            name = feed.name,
            url = feed.url,
            country = feed.country,
            language = feed.language,
            lat = feed.latitude,
            lon = feed.longitude,
            scope = feed.scope
        )

        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", "GlobeNews/3.2")
            .build()

        val response = rssClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code} for ${feed.name}")
        }
        val body = response.body?.string() ?: return@withContext emptyList()
        parseRss(body, config)
    }

    private suspend fun fetchFeed(config: RssFeedConfig): List<RssItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.url)
            .header("User-Agent", "GlobeNews/3.0")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code} for ${config.name}")
        }
        val body = response.body?.string() ?: return@withContext emptyList()
        parseRss(body, config)
    }

    private fun parseRss(xml: String, config: RssFeedConfig): List<RssItem> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))

            // Try <item> first (RSS), then <entry> (Atom)
            var items = doc.getElementsByTagName("item")
            val isAtom = items.length == 0
            if (isAtom) {
                items = doc.getElementsByTagName("entry")
            }
            val results = mutableListOf<RssItem>()

            for (i in 0 until minOf(items.length, Constants.RSS_MAX_ITEMS_PER_FEED)) {
                val node = items.item(i)
                val children = node.childNodes
                var title = ""
                var link = ""
                var pubDate: String? = null
                var description: String? = null

                for (j in 0 until children.length) {
                    val child = children.item(j)
                    when (child.nodeName) {
                        "title" -> title = child.textContent ?: ""
                        "link" -> {
                            if (isAtom) {
                                // Atom: <link href="..."/>
                                val href = child.attributes?.getNamedItem("href")?.nodeValue
                                if (!href.isNullOrBlank()) link = href
                                else if (link.isBlank()) link = child.textContent ?: ""
                            } else {
                                link = child.textContent ?: ""
                            }
                        }
                        "pubDate" -> pubDate = child.textContent
                        "published" -> if (pubDate == null) pubDate = child.textContent
                        "updated" -> if (pubDate == null) pubDate = child.textContent
                        "description", "summary", "content" ->
                            if (description == null) description = child.textContent
                    }
                }

                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(
                        RssItem(
                            title = title,
                            link = link,
                            pubDate = pubDate?.let { parsePubDate(it) },
                            description = description?.take(500),
                            feedConfig = config
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed for ${config.name}: ${e.message}")
            emptyList()
        }
    }

    private fun parsePubDate(dateStr: String): Instant? {
        return try {
            ZonedDateTime.parse(dateStr.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // Try ISO-8601 for Atom feeds
                Instant.parse(dateStr.trim())
            } catch (e2: Exception) {
                null
            }
        }
    }
}
