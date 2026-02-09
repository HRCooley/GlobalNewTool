package com.globenews.data.source.remote.gnews

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface GNewsService {
    @GET("top-headlines")
    suspend fun topHeadlines(
        @Query("lang") lang: String = "en",
        @Query("max") max: Int = 50,
        @Query("token") token: String
    ): GNewsResponse
}

@JsonClass(generateAdapter = true)
data class GNewsResponse(
    @Json(name = "totalArticles") val totalArticles: Int?,
    @Json(name = "articles") val articles: List<GNewsArticle>?
)

@JsonClass(generateAdapter = true)
data class GNewsArticle(
    @Json(name = "title") val title: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "url") val url: String?,
    @Json(name = "image") val image: String?,
    @Json(name = "publishedAt") val publishedAt: String?,
    @Json(name = "source") val source: GNewsSource?
)

@JsonClass(generateAdapter = true)
data class GNewsSource(
    @Json(name = "name") val name: String?
)
