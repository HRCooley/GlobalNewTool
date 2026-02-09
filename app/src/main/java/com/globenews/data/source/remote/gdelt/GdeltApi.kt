package com.globenews.data.source.remote.gdelt

import retrofit2.http.GET
import retrofit2.http.Query

interface GdeltApi {
    @GET("doc")
    suspend fun search(
        @Query("query", encoded = true) query: String,
        @Query("mode") mode: String = "artlist",
        @Query("maxrecords") maxRecords: Int = 75,
        @Query("timespan") timespan: String = "24h",
        @Query("format") format: String = "json"
    ): GdeltResponse
}
