package com.globenews.data.mapper

import com.globenews.data.source.local.FallbackStory
import com.globenews.data.source.remote.gdelt.GdeltArticleWithLocation
import com.globenews.data.source.remote.gnews.GNewsArticle
import com.globenews.data.source.remote.googlenews.GoogleNewsDataSource
import com.globenews.data.source.remote.newsapi.NewsApiArticle
import com.globenews.data.source.remote.rss.RssDataSource
import com.globenews.domain.model.EditorialScope
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import com.globenews.domain.model.SourceAttribution
import com.globenews.domain.model.StoryLocation
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

object StoryMappers {

    private val random = java.util.Random(42)

    fun fromGdelt(item: GdeltArticleWithLocation): NewsStory? {
        val article = item.article
        if (article.url.isNullOrBlank() || article.title.isNullOrBlank()) return null

        // Add small random offset to avoid stacking
        val latOffset = (random.nextDouble() - 0.5) * 0.2
        val lonOffset = (random.nextDouble() - 0.5) * 0.2

        return NewsStory(
            id = "gdelt_${article.url.hashCode()}",
            title = article.title,
            summary = null,
            url = article.url,
            imageUrl = article.socialImage?.takeIf { it.isNotBlank() },
            publishedAt = parseGdeltDate(article.seenDate) ?: Instant.now(),
            location = StoryLocation(
                latitude = item.queryRegion.lat + latOffset,
                longitude = item.queryRegion.lon + lonOffset,
                placeName = item.queryRegion.name,
                countryCode = null,
                admin1 = null
            ),
            scope = EditorialScope.INTERNATIONAL,
            category = inferCategoryFromTitle(article.title),
            sources = listOf(
                SourceAttribution(
                    name = article.domain ?: "Unknown",
                    providerApi = "gdelt",
                    retrievedAt = Instant.now()
                )
            ),
            language = article.language ?: "unknown",
            sentiment = article.tone?.toFloatOrNull()
        )
    }

    fun fromGoogleNews(
        item: GoogleNewsDataSource.GoogleNewsItem,
        lat: Double,
        lon: Double,
        placeName: String?
    ): NewsStory {
        return NewsStory(
            id = "gnews_rss_${item.link.hashCode()}",
            title = item.title,
            summary = item.description?.take(300),
            url = item.link,
            imageUrl = null,
            publishedAt = item.pubDate ?: Instant.now(),
            location = StoryLocation(
                latitude = lat,
                longitude = lon,
                placeName = placeName,
                countryCode = null,
                admin1 = null
            ),
            scope = EditorialScope.LOCAL,
            category = inferCategoryFromTitle(item.title),
            sources = listOf(
                SourceAttribution(
                    name = "Google News",
                    providerApi = "google_rss",
                    retrievedAt = Instant.now()
                )
            ),
            language = "unknown",
            sentiment = null
        )
    }

    fun fromRss(item: RssDataSource.RssItem): NewsStory {
        val config = item.feedConfig
        return NewsStory(
            id = "rss_${item.link.hashCode()}",
            title = item.title,
            summary = item.description?.take(300),
            url = item.link,
            imageUrl = null,
            publishedAt = item.pubDate ?: Instant.now(),
            location = StoryLocation(
                latitude = config.lat,
                longitude = config.lon,
                placeName = config.name,
                countryCode = config.country,
                admin1 = null
            ),
            scope = try {
                EditorialScope.valueOf(config.scope)
            } catch (e: Exception) {
                EditorialScope.INTERNATIONAL
            },
            category = inferCategoryFromTitle(item.title),
            sources = listOf(
                SourceAttribution(
                    name = config.name,
                    providerApi = "rss",
                    retrievedAt = Instant.now()
                )
            ),
            language = config.language,
            sentiment = null
        )
    }

    fun fromNewsApi(article: NewsApiArticle): NewsStory? {
        if (article.url.isNullOrBlank() || article.title.isNullOrBlank()) return null
        return NewsStory(
            id = "newsapi_${article.url.hashCode()}",
            title = article.title,
            summary = article.description,
            url = article.url,
            imageUrl = article.urlToImage,
            publishedAt = article.publishedAt?.let { parseIsoDate(it) } ?: Instant.now(),
            location = StoryLocation(40.7, -74.0, "New York", "US", null),
            scope = EditorialScope.NATIONAL,
            category = inferCategoryFromTitle(article.title),
            sources = listOf(
                SourceAttribution(
                    name = article.source?.name ?: "NewsAPI",
                    providerApi = "newsapi",
                    retrievedAt = Instant.now()
                )
            ),
            language = "en",
            sentiment = null
        )
    }

    fun fromGNews(article: GNewsArticle): NewsStory? {
        if (article.url.isNullOrBlank() || article.title.isNullOrBlank()) return null
        return NewsStory(
            id = "gnews_${article.url.hashCode()}",
            title = article.title,
            summary = article.description,
            url = article.url,
            imageUrl = article.image,
            publishedAt = article.publishedAt?.let { parseIsoDate(it) } ?: Instant.now(),
            location = StoryLocation(51.5, -0.1, "London", "GB", null),
            scope = EditorialScope.INTERNATIONAL,
            category = inferCategoryFromTitle(article.title),
            sources = listOf(
                SourceAttribution(
                    name = article.source?.name ?: "GNews",
                    providerApi = "gnews",
                    retrievedAt = Instant.now()
                )
            ),
            language = "en",
            sentiment = null
        )
    }

    fun fromFallback(story: FallbackStory): NewsStory {
        return NewsStory(
            id = "fallback_${story.url.hashCode()}",
            title = story.title,
            summary = story.summary,
            url = story.url,
            imageUrl = story.imageUrl,
            publishedAt = Instant.now(),
            location = StoryLocation(
                latitude = story.lat,
                longitude = story.lon,
                placeName = story.placeName,
                countryCode = story.countryCode,
                admin1 = null
            ),
            scope = try {
                EditorialScope.valueOf(story.scope)
            } catch (e: Exception) {
                EditorialScope.INTERNATIONAL
            },
            category = try {
                NewsCategory.valueOf(story.category)
            } catch (e: Exception) {
                NewsCategory.ALL
            },
            sources = listOf(
                SourceAttribution(
                    name = story.sourceName,
                    providerApi = "fallback",
                    retrievedAt = Instant.now()
                )
            ),
            language = story.language ?: "en",
            sentiment = null
        )
    }

    private fun parseGdeltDate(dateStr: String?): Instant? {
        if (dateStr == null) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            LocalDateTime.parse(dateStr, formatter).toInstant(ZoneOffset.UTC)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIsoDate(dateStr: String): Instant? {
        return try {
            Instant.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }

    private val CATEGORY_KEYWORDS = mapOf(
        NewsCategory.CONFLICT to listOf("war", "military", "attack", "bomb", "kill", "soldier", "troops", "missile", "protest", "riot"),
        NewsCategory.POLITICS to listOf("election", "president", "minister", "parliament", "vote", "senate", "congress", "diplomat", "sanction", "treaty"),
        NewsCategory.ECONOMY to listOf("economy", "trade", "market", "stock", "inflation", "bank", "gdp", "recession", "tariff", "currency"),
        NewsCategory.ENVIRONMENT to listOf("climate", "earthquake", "flood", "wildfire", "hurricane", "drought", "pollution", "environment", "carbon"),
        NewsCategory.HEALTH to listOf("health", "pandemic", "vaccine", "hospital", "disease", "medical", "virus", "outbreak", "who"),
        NewsCategory.TECHNOLOGY to listOf("tech", "cyber", "hack", "ai ", "artificial intelligence", "digital", "software", "startup"),
        NewsCategory.CRIME to listOf("crime", "arrest", "murder", "fraud", "drug", "corruption", "theft", "prison", "cartel"),
        NewsCategory.ENERGY to listOf("energy", "oil", "gas", "nuclear", "renewable", "solar", "wind power", "pipeline", "opec")
    )

    fun inferCategoryFromTitle(title: String): NewsCategory {
        val lower = title.lowercase()
        var bestCategory = NewsCategory.ALL
        var bestScore = 0

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            val score = keywords.count { lower.contains(it) }
            if (score > bestScore) {
                bestScore = score
                bestCategory = category
            }
        }
        return bestCategory
    }
}
