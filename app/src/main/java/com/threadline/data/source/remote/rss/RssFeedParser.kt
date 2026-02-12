package com.threadline.data.source.remote.rss

import android.util.Log
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class RssItem(
    val title: String,
    val link: String,
    val pubDate: Instant?,
    val description: String?,
    val feedName: String,
    val feedCountry: String?,
    val feedLanguage: String?,
    val feedLat: Double?,
    val feedLon: Double?,
    val feedScope: String
)

object RssFeedParser {

    private const val TAG = "RssFeedParser"

    fun parse(xml: String, feedName: String, country: String?, language: String?,
              lat: Double?, lon: Double?, scope: String): List<RssItem> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))

            var items = doc.getElementsByTagName("item")
            val isAtom = items.length == 0
            if (isAtom) {
                items = doc.getElementsByTagName("entry")
            }
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
                        "link" -> {
                            if (isAtom) {
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
                            feedName = feedName,
                            feedCountry = country,
                            feedLanguage = language,
                            feedLat = lat,
                            feedLon = lon,
                            feedScope = scope
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed for $feedName: ${e.message}")
            emptyList()
        }
    }

    private fun parsePubDate(dateStr: String): Instant? {
        return try {
            ZonedDateTime.parse(dateStr.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                Instant.parse(dateStr.trim())
            } catch (e2: Exception) {
                null
            }
        }
    }
}
