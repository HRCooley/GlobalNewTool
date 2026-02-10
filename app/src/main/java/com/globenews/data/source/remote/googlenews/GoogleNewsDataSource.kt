package com.globenews.data.source.remote.googlenews

import android.util.Log
import com.globenews.data.source.geocoding.GeocodingDataSource
import kotlinx.coroutines.Dispatchers
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
class GoogleNewsDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val geocoding: GeocodingDataSource
) {
    companion object {
        private const val TAG = "GoogleNewsDS"

        val LOCALES = mapOf(
            "US" to Triple("en", "US", "US:en"),
            "GB" to Triple("en", "GB", "GB:en"),
            "FR" to Triple("fr", "FR", "FR:fr"),
            "DE" to Triple("de", "DE", "DE:de"),
            "JP" to Triple("ja", "JP", "JP:ja"),
            "BR" to Triple("pt-BR", "BR", "BR:pt-419"),
            "IN" to Triple("en", "IN", "IN:en"),
            "MX" to Triple("es", "MX", "MX:es-419"),
            "RU" to Triple("ru", "RU", "RU:ru"),
            "AU" to Triple("en", "AU", "AU:en"),
            "KR" to Triple("ko", "KR", "KR:ko"),
            "EG" to Triple("ar", "EG", "EG:ar"),
            "ZA" to Triple("en", "ZA", "ZA:en"),
            "KE" to Triple("en", "KE", "KE:en"),
            "NG" to Triple("en", "NG", "NG:en"),
        )

        private val DEFAULT_LOCALE = Triple("en", "US", "US:en")
    }

    data class GoogleNewsItem(
        val title: String,
        val link: String,
        val pubDate: Instant?,
        val description: String?
    )

    private var lastPlace: String? = null
    private var cachedItems: List<GoogleNewsItem> = emptyList()
    private var cacheTime: Instant = Instant.EPOCH

    suspend fun fetchLocal(
        lat: Double,
        lon: Double
    ): List<GoogleNewsItem> = withContext(Dispatchers.IO) {
        val placeInfo = geocoding.reverseGeocode(lat, lon) ?: return@withContext emptyList()
        val placeName = placeInfo.name

        // Return cached if same place and fresh
        if (placeName == lastPlace &&
            java.time.Duration.between(cacheTime, Instant.now()).toMinutes() < 30
        ) {
            return@withContext cachedItems
        }

        val locale = LOCALES[placeInfo.countryCode] ?: DEFAULT_LOCALE
        val url = "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(placeName, "UTF-8")}&hl=${locale.first}&gl=${locale.second}&ceid=${locale.third}"

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GlobeNews/3.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val items = parseRss(body).take(50) // Cap at 50 items
            Log.d("GlobeNews", "GoogleRSS: ${items.size} for '$placeName'")
            lastPlace = placeName
            cachedItems = items
            cacheTime = Instant.now()
            items
        } catch (e: Exception) {
            Log.w(TAG, "Google News fetch failed for $placeName: ${e.message}")
            emptyList()
        }
    }

    private fun parseRss(xml: String): List<GoogleNewsItem> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))

            val items = doc.getElementsByTagName("item")
            val results = mutableListOf<GoogleNewsItem>()

            for (i in 0 until items.length) {
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
                        GoogleNewsItem(
                            title = title,
                            link = link,
                            pubDate = pubDate?.let { parsePubDate(it) },
                            description = description
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "RSS parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun parsePubDate(dateStr: String): Instant? {
        return try {
            ZonedDateTime.parse(dateStr, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            null
        }
    }
}
