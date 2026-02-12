package com.threadline.data.mapper

import com.threadline.data.source.local.entity.CachedStoryEntity
import com.threadline.data.source.local.entity.ManagedFeedEntity
import com.threadline.data.source.remote.gdelt.GdeltArticleWithRegion
import com.threadline.data.source.remote.rss.RssItem
import com.threadline.domain.model.ManagedFeed
import com.threadline.domain.model.NewsStory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object EntityMappers {

    private val random = java.util.Random(42)

    private val CATEGORY_KEYWORDS = mapOf(
        "CONFLICT" to listOf("war", "military", "attack", "bomb", "kill", "soldier", "troops", "missile", "protest", "riot"),
        "POLITICS" to listOf("election", "president", "minister", "parliament", "vote", "senate", "congress", "diplomat", "sanction", "treaty"),
        "ECONOMY" to listOf("economy", "trade", "market", "stock", "inflation", "bank", "gdp", "recession", "tariff", "currency"),
        "ENVIRONMENT" to listOf("climate", "earthquake", "flood", "wildfire", "hurricane", "drought", "pollution", "environment", "carbon"),
        "HEALTH" to listOf("health", "pandemic", "vaccine", "hospital", "disease", "medical", "virus", "outbreak"),
        "TECHNOLOGY" to listOf("tech", "cyber", "hack", "ai ", "artificial intelligence", "digital", "software", "startup"),
        "CRIME" to listOf("crime", "arrest", "murder", "fraud", "drug", "corruption", "theft", "prison", "cartel"),
        "ENERGY" to listOf("energy", "oil", "gas", "nuclear", "renewable", "solar", "wind power", "pipeline", "opec")
    )

    fun inferCategory(title: String): String {
        val lower = title.lowercase()
        var best = "ALL"
        var bestScore = 0
        for ((cat, keywords) in CATEGORY_KEYWORDS) {
            val score = keywords.count { lower.contains(it) }
            if (score > bestScore) {
                bestScore = score
                best = cat
            }
        }
        return best
    }

    fun gdeltToStory(item: GdeltArticleWithRegion): NewsStory? {
        val article = item.article
        if (article.url.isNullOrBlank() || article.title.isNullOrBlank()) return null

        val latOffset = (random.nextDouble() - 0.5) * 0.2
        val lonOffset = (random.nextDouble() - 0.5) * 0.2

        return NewsStory(
            id = "gdelt_${article.url.hashCode()}",
            title = article.title,
            summary = null,
            url = article.url,
            imageUrl = article.socialimage?.takeIf { it.isNotBlank() },
            publishedAt = parseGdeltDate(article.seendate) ?: Instant.now(),
            latitude = item.queryRegion.lat + latOffset,
            longitude = item.queryRegion.lon + lonOffset,
            placeName = item.queryRegion.name,
            countryCode = null,
            scope = "INTERNATIONAL",
            category = inferCategory(article.title),
            sourceName = article.domain ?: "Unknown",
            providerApi = "gdelt",
            language = article.language ?: "unknown",
            sentiment = article.tone?.split(",")?.firstOrNull()?.trim()?.toFloatOrNull()
        )
    }

    fun rssToStory(item: RssItem): NewsStory {
        return NewsStory(
            id = "rss_${item.link.hashCode()}",
            title = item.title,
            summary = item.description?.take(300),
            url = item.link,
            imageUrl = null,
            publishedAt = item.pubDate ?: Instant.now(),
            latitude = item.feedLat,
            longitude = item.feedLon,
            placeName = item.feedName,
            countryCode = item.feedCountry,
            scope = item.feedScope,
            category = inferCategory(item.title),
            sourceName = item.feedName,
            providerApi = "rss",
            language = item.feedLanguage ?: "unknown",
            sentiment = null
        )
    }

    fun storyToEntity(story: NewsStory): CachedStoryEntity {
        return CachedStoryEntity(
            id = story.id,
            title = story.title,
            summary = story.summary,
            url = story.url,
            imageUrl = story.imageUrl,
            publishedAt = story.publishedAt.toEpochMilli(),
            latitude = story.latitude,
            longitude = story.longitude,
            placeName = story.placeName,
            countryCode = story.countryCode,
            scope = story.scope,
            category = story.category,
            sourceName = story.sourceName,
            providerApi = story.providerApi,
            language = story.language,
            sentiment = story.sentiment,
            cachedAt = System.currentTimeMillis(),
            clusterId = null,
            clusterSize = null,
            coverageRegions = null,
            isSignal = false
        )
    }

    fun entityToStory(entity: CachedStoryEntity): NewsStory {
        return NewsStory(
            id = entity.id,
            title = entity.title,
            summary = entity.summary,
            url = entity.url,
            imageUrl = entity.imageUrl,
            publishedAt = Instant.ofEpochMilli(entity.publishedAt),
            latitude = entity.latitude,
            longitude = entity.longitude,
            placeName = entity.placeName,
            countryCode = entity.countryCode,
            scope = entity.scope,
            category = entity.category,
            sourceName = entity.sourceName,
            providerApi = entity.providerApi,
            language = entity.language,
            sentiment = entity.sentiment
        )
    }

    fun fallbackToStory(
        title: String, url: String, summary: String?, imageUrl: String?,
        lat: Double, lon: Double, placeName: String?, countryCode: String?,
        category: String, scope: String, sourceName: String, language: String?
    ): NewsStory {
        return NewsStory(
            id = "fallback_${url.hashCode()}",
            title = title,
            summary = summary,
            url = url,
            imageUrl = imageUrl,
            publishedAt = Instant.now(),
            latitude = lat,
            longitude = lon,
            placeName = placeName,
            countryCode = countryCode,
            scope = scope,
            category = category,
            sourceName = sourceName,
            providerApi = "fallback",
            language = language ?: "en",
            sentiment = null
        )
    }

    fun feedEntityToDomain(entity: ManagedFeedEntity): ManagedFeed {
        return ManagedFeed(
            id = entity.id,
            name = entity.name,
            url = entity.url,
            country = entity.country,
            language = entity.language,
            latitude = entity.latitude,
            longitude = entity.longitude,
            scope = entity.scope,
            category = entity.category,
            enabled = entity.enabled,
            consecutiveFailures = entity.consecutiveFailures,
            lastError = entity.lastError
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
}
