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
                        Log.d(TAG, "GDELT query: ${Constants.GDELT_BASE_URL}doc?query=$query&mode=artlist&maxrecords=$maxRecords&timespan=$timespan&format=json")
                        val response = api.search(
                            query = query,
                            maxRecords = maxRecords,
                            timespan = timespan
                        )
                        response.articles?.map { article ->
                            GdeltArticleWithLocation(article, region)
                        } ?: emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch GDELT for ${region.name}: ${e.message}")
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
        val region = QueryRegion("nearby", lat, lon, radiusKm)
        val query = buildQuery(region, category)
        Log.d(TAG, "GDELT nearby query: ${Constants.GDELT_BASE_URL}doc?query=$query&mode=artlist&maxrecords=$maxRecords&timespan=$timespan&format=json")
        return try {
            val response = api.search(
                query = query,
                maxRecords = maxRecords,
                timespan = timespan
            )
            response.articles?.map { GdeltArticleWithLocation(it, region) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed GDELT nearby: ${e.message}")
            emptyList()
        }
    }

    private fun buildQuery(region: QueryRegion, category: NewsCategory): String {
        val geoClause = "near:${region.lat},${region.lon} ${region.radiusKm}km"
        return if (category == NewsCategory.ALL || category.gdeltThemes.isEmpty()) {
            geoClause
        } else {
            val themeClause = category.gdeltThemes.joinToString(" OR ") { "theme:$it" }
            "$geoClause AND ($themeClause)"
        }
    }
}

data class GdeltArticleWithLocation(
    val article: GdeltArticle,
    val queryRegion: QueryRegion
)
