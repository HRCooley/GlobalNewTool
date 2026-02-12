package com.threadline.data.source.remote.gdelt

import retrofit2.http.GET
import retrofit2.http.Query

interface GdeltApi {
    @GET("doc")
    suspend fun search(
        @Query("query") query: String,
        @Query("mode") mode: String = "artlist",
        @Query("maxrecords") maxRecords: Int = 75,
        @Query("timespan") timespan: String = "24h",
        @Query("format") format: String = "json"
    ): GdeltResponse
}

data class GdeltResponse(val articles: List<GdeltArticle>?)

data class GdeltArticle(
    val url: String?,
    val title: String?,
    val seendate: String?,
    val socialimage: String?,
    val domain: String?,
    val language: String?,
    val sourcecountry: String?,
    val tone: String?
)
