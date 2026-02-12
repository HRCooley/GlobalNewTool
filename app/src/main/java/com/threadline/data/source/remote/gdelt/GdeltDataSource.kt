package com.threadline.data.source.remote.gdelt

import android.util.Log
import com.threadline.core.common.Constants
import com.threadline.domain.model.GLOBAL_GRID
import com.threadline.domain.model.QueryRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

data class GdeltArticleWithRegion(
    val article: GdeltArticle,
    val queryRegion: QueryRegion
)

@Singleton
class GdeltDataSource @Inject constructor(
    private val api: GdeltApi
) {
    companion object {
        private const val TAG = "GdeltDataSource"
    }

    suspend fun fetchForRegions(
        regions: List<QueryRegion>,
        maxRecords: Int = 75,
        timespan: String = "24h"
    ): List<GdeltArticleWithRegion> = coroutineScope {
        val results = mutableListOf<GdeltArticleWithRegion>()
        val batches = regions.chunked(Constants.GDELT_BATCH_SIZE)

        for (batch in batches) {
            val deferred = batch.map { region ->
                async {
                    try {
                        val query = buildQuery(region)
                        val response = api.search(
                            query = query,
                            maxRecords = maxRecords,
                            timespan = timespan
                        )
                        val articles = response.articles ?: emptyList()
                        Log.d(TAG, "GDELT: ${region.name} returned ${articles.size} articles")
                        articles.map { GdeltArticleWithRegion(it, region) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "GDELT: ERROR for ${region.name}: ${e.message}")
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
        lat: Double, lon: Double, radiusKm: Int,
        maxRecords: Int = 100, timespan: String = "7d"
    ): List<GdeltArticleWithRegion> {
        val closestRegion = GLOBAL_GRID.minByOrNull { region ->
            val dLat = region.lat - lat
            val dLon = region.lon - lon
            dLat * dLat + dLon * dLon
        }
        val region = QueryRegion("nearby", lat, lon, radiusKm, closestRegion?.countryCodes ?: emptyList())
        return try {
            val response = api.search(
                query = buildQuery(region),
                maxRecords = maxRecords,
                timespan = timespan
            )
            (response.articles ?: emptyList()).map { GdeltArticleWithRegion(it, region) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GDELT nearby error: ${e.message}")
            emptyList()
        }
    }

    private fun buildQuery(region: QueryRegion): String {
        val geoClause = if (region.countryCodes.isNotEmpty()) {
            if (region.countryCodes.size == 1) {
                "sourcecountry:${region.countryCodes.first()}"
            } else {
                region.countryCodes.joinToString(" OR ") { "sourcecountry:$it" }
            }
        } else {
            region.name.replace("/", " OR ")
        }
        return "($geoClause)"
    }
}
