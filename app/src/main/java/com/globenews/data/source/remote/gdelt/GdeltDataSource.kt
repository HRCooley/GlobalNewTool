package com.globenews.data.source.remote.gdelt

import android.util.Log
import com.globenews.core.common.Constants
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.QueryRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GdeltDataSource @Inject constructor(
    private val api: GdeltApi
) {
    companion object {
        private const val TAG = "GdeltDataSource"
    }

    /**
     * Fetch GDELT articles for all regions with per-region diagnostics.
     * @param onRegionResult called after each region completes, for real-time diagnostics
     */
    suspend fun fetchForRegions(
        regions: List<QueryRegion>,
        category: NewsCategory,
        maxRecords: Int = 75,
        timespan: String = "24h",
        onRegionResult: ((GdeltRegionResult) -> Unit)? = null
    ): List<GdeltArticleWithLocation> = coroutineScope {
        val results = mutableListOf<GdeltArticleWithLocation>()
        val batches = regions.chunked(Constants.GDELT_BATCH_SIZE)
        var rateLimited = false

        for (batch in batches) {
            if (rateLimited) {
                Log.w("GlobeNews", "GDELT: skipping remaining regions — rate limited")
                break
            }
            val deferred = batch.map { region ->
                async {
                    if (rateLimited) return@async emptyList()
                    try {
                        val query = buildQuery(region, category)
                        Log.d("GlobeNews", "GDELT: query for ${region.name}")
                        val response = api.search(
                            query = query,
                            maxRecords = maxRecords,
                            timespan = timespan
                        )
                        val articles = response.articles ?: emptyList()
                        Log.d("GlobeNews", "GDELT: region ${region.name} returned ${articles.size} articles")
                        onRegionResult?.invoke(
                            GdeltRegionResult(region.name, articles.size, succeeded = true)
                        )
                        articles.map { article ->
                            GdeltArticleWithLocation(article, region)
                        }
                    } catch (e: HttpException) {
                        val isRateLimit = e.code() == 429
                        if (isRateLimit) {
                            rateLimited = true
                            Log.w("GlobeNews", "GDELT: 429 rate limited at region ${region.name}, stopping")
                        } else {
                            Log.e("GlobeNews", "GDELT: ERROR for ${region.name}: HTTP ${e.code()}")
                        }
                        onRegionResult?.invoke(
                            GdeltRegionResult(
                                region.name, 0, succeeded = false,
                                rateLimited = isRateLimit,
                                error = "HTTP ${e.code()}"
                            )
                        )
                        emptyList()
                    } catch (e: Exception) {
                        Log.e("GlobeNews", "GDELT: ERROR for ${region.name}: ${e.javaClass.simpleName}: ${e.message}")
                        onRegionResult?.invoke(
                            GdeltRegionResult(
                                region.name, 0, succeeded = false,
                                error = "${e.javaClass.simpleName}: ${e.message}"
                            )
                        )
                        emptyList()
                    }
                }
            }
            results.addAll(deferred.awaitAll().flatten())
            if (!rateLimited && batches.indexOf(batch) < batches.lastIndex) {
                delay(Constants.GDELT_BATCH_DELAY_MS)
            }
        }
        val capped = results.take(Constants.GDELT_MAX_STORIES)
        if (capped.size < results.size) {
            Log.w("GlobeNews", "GDELT: capped from ${results.size} to ${capped.size} articles")
        }
        Log.d("GlobeNews", "GDELT: ${capped.size} articles from ${regions.size} regions (rateLimited=$rateLimited)")
        capped
    }

    /**
     * Fast viewport query — single GDELT call for what the user is looking at.
     * At world zoom (< 3.0) returns empty — caller should use quick-world or grid instead.
     */
    suspend fun fetchForViewport(
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        category: NewsCategory,
        onRegionResult: ((GdeltRegionResult) -> Unit)? = null
    ): List<GdeltArticleWithLocation> {
        if (zoom < 3.0) return emptyList() // Too wide for viewport query

        val maxRecords = when {
            zoom < 5.0 -> 150
            zoom < 8.0 -> 100
            else -> 75
        }
        val timespan = if (zoom < 8.0) "24h" else "7d"

        // Use country codes from nearest grid region for better GDELT results
        val closestRegion = findClosestRegion(centerLat, centerLon)
        val codes = closestRegion?.countryCodes ?: emptyList()
        val regionName = closestRegion?.name ?: "viewport"
        val region = QueryRegion(regionName, centerLat, centerLon, 3000, codes)

        return try {
            val query = buildQuery(region, category)
            Log.d("GlobeNews", "GDELT viewport: $query max=$maxRecords ts=$timespan")
            val response = api.search(query = query, maxRecords = maxRecords, timespan = timespan)
            val articles = response.articles ?: emptyList()
            Log.d("GlobeNews", "GDELT viewport: ${articles.size} articles")
            onRegionResult?.invoke(
                GdeltRegionResult(regionName, articles.size, succeeded = true)
            )
            articles.map { GdeltArticleWithLocation(it, region) }
                .take(Constants.GDELT_MAX_STORIES)
        } catch (e: HttpException) {
            Log.w("GlobeNews", "GDELT viewport: HTTP ${e.code()}")
            onRegionResult?.invoke(
                GdeltRegionResult(regionName, 0, succeeded = false,
                    rateLimited = e.code() == 429, error = "HTTP ${e.code()}")
            )
            emptyList()
        } catch (e: Exception) {
            Log.w("GlobeNews", "GDELT viewport failed: ${e.message}")
            onRegionResult?.invoke(
                GdeltRegionResult(regionName, 0, succeeded = false,
                    error = "${e.javaClass.simpleName}: ${e.message}")
            )
            emptyList()
        }
    }

    /**
     * Slow background fill: iterates all 27 grid regions one at a time with 3s delays.
     * Never rate-limits. Calls onRegionComplete per region so the caller can merge incrementally.
     */
    suspend fun backgroundFillGrid(
        category: NewsCategory,
        onRegionComplete: (String, List<GdeltArticleWithLocation>) -> Unit
    ) {
        val grid = com.globenews.domain.model.GLOBAL_GRID
        for (region in grid) {
            try {
                val query = buildQuery(region, category)
                val response = api.search(query = query, maxRecords = 50, timespan = "24h")
                val articles = response.articles ?: emptyList()
                if (articles.isNotEmpty()) {
                    val mapped = articles.map { GdeltArticleWithLocation(it, region) }
                    Log.d("GlobeNews", "GDELT bg: ${region.name} → ${articles.size} articles")
                    onRegionComplete(region.name, mapped)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GlobeNews", "GDELT bg: ${region.name} failed — ${e.message}")
            }
            delay(3000) // 3s between regions — never triggers rate limits
        }
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

/** Per-region fetch result for diagnostics */
data class GdeltRegionResult(
    val regionName: String,
    val articleCount: Int,
    val succeeded: Boolean,
    val rateLimited: Boolean = false,
    val error: String? = null
)
