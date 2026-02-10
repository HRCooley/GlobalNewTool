package com.globenews.data.source.remote.gdelt

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.QueryRegion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GdeltDataSource @Inject constructor(
    private val api: GdeltApi
) {
    companion object {
        private const val TAG = "GdeltDataSource"
    }

    suspend fun fetchForRegions(
        regions: List<QueryRegion>,
        category: NewsCategory,
        maxRecords: Int = 75,
        timespan: String = "24h"
    ): List<GdeltArticleWithLocation> = coroutineScope {
        val results = mutableListOf<GdeltArticleWithLocation>()
        val batches = regions.chunked(Constants.GDELT_BATCH_SIZE)

        for (batch in batches) {
            val deferred = batch.map { region ->
                async {
                    try {
                        val query = buildQuery(region, category)
                        Log.d("GlobeNews", "GDELT: query URL = ${Constants.GDELT_BASE_URL}doc?query=$query&mode=artlist&maxrecords=$maxRecords&timespan=$timespan&format=json")
                        val response = api.search(
                            query = query,
                            maxRecords = maxRecords,
                            timespan = timespan
                        )
                        val articles = response.articles ?: emptyList()
                        Log.d("GlobeNews", "GDELT: region ${region.name} returned ${articles.size} articles")
                        articles.map { article ->
                            GdeltArticleWithLocation(article, region)
                        }
                    } catch (e: Exception) {
                        Log.e("GlobeNews", "GDELT: ERROR for ${region.name}: ${e.javaClass.simpleName}: ${e.message}")
                        emptyList()
                    }
                }
            }
            results.addAll(deferred.awaitAll().flatten())
            if (batches.indexOf(batch) < batches.lastIndex) {
                delay(Constants.GDELT_BATCH_DELAY_MS)
            }
        }
        results
    }

    suspend fun fetchNearby(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        category: NewsCategory,
        maxRecords: Int = 100,
        timespan: String = "7d"
    ): List<GdeltArticleWithLocation> {
        // Find the closest region's country codes for the given coordinates
        val closestRegion = findClosestRegion(lat, lon)
        val region = QueryRegion("nearby", lat, lon, radiusKm, closestRegion?.countryCodes ?: emptyList())
        val query = buildQuery(region, category)
        Log.d("GlobeNews", "GDELT: nearby query: $query")
        return try {
            val response = api.search(
                query = query,
                maxRecords = maxRecords,
                timespan = timespan
            )
            val articles = response.articles ?: emptyList()
            Log.d("GlobeNews", "GDELT: nearby returned ${articles.size} articles")
            articles.map { GdeltArticleWithLocation(it, region) }
        } catch (e: Exception) {
            Log.e("GlobeNews", "GDELT: nearby ERROR: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun findClosestRegion(lat: Double, lon: Double): QueryRegion? {
        return com.globenews.domain.model.GLOBAL_GRID.minByOrNull { region ->
            val dLat = region.lat - lat
            val dLon = region.lon - lon
            dLat * dLat + dLon * dLon
        }
    }

    private fun buildQuery(region: QueryRegion, category: NewsCategory): String {
        // GDELT DOC 2.0 uses sourcecountry: with FIPS codes for geographic filtering
        // No sourcelang filter — get all languages
        val geoClause = if (region.countryCodes.isNotEmpty()) {
            if (region.countryCodes.size == 1) {
                "sourcecountry:${region.countryCodes.first()}"
            } else {
                region.countryCodes.joinToString(" OR ") { "sourcecountry:$it" }
            }
        } else {
            // Fallback for regions without country codes — broad search
            region.name.replace("/", " OR ")
        }
        return if (category == NewsCategory.ALL || category.gdeltThemes.isEmpty()) {
            "($geoClause)"
        } else {
            val themeClause = category.gdeltThemes.joinToString(" OR ") { "theme:$it" }
            "($geoClause) ($themeClause)"
        }
    }
}

data class GdeltArticleWithLocation(
    val article: GdeltArticle,
    val queryRegion: QueryRegion
)
