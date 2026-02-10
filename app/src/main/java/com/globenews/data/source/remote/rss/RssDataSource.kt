package com.globenews.data.source.remote.rss

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

@Singleton
class RssDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val moshi: Moshi
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

    private suspend fun fetchFeed(config: RssFeedConfig): List<RssItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.url)
            .header("User-Agent", "GlobeNews/3.0")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        parseRss(body, config)
    }

    private fun parseRss(xml: String, config: RssFeedConfig): List<RssItem> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))

            val items = doc.getElementsByTagName("item")
            val results = mutableListOf<RssItem>()

            for (i in 0 until minOf(items.length, 30)) {
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
                        "link" -> link = child.textContent ?: ""
                        "pubDate" -> pubDate = child.textContent
                        "description" -> description = child.textContent
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
            null
        }
    }
}
