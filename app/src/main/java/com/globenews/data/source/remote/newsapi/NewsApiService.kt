package com.globenews.data.source.remote.newsapi

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface NewsApiService {
    @GET("top-headlines")
    suspend fun topHeadlines(
        @Query("country") country: String = "us",
        @Query("pageSize") pageSize: Int = 50,
        @Query("apiKey") apiKey: String
    ): NewsApiResponse
}

@JsonClass(generateAdapter = true)
data class NewsApiResponse(
    @Json(name = "status") val status: String?,
    @Json(name = "articles") val articles: List<NewsApiArticle>?
)

@JsonClass(generateAdapter = true)
data class NewsApiArticle(
    @Json(name = "title") val title: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "url") val url: String?,
    @Json(name = "urlToImage") val urlToImage: String?,
    @Json(name = "publishedAt") val publishedAt: String?,
    @Json(name = "source") val source: NewsApiSource?
)

@JsonClass(generateAdapter = true)
data class NewsApiSource(
    @Json(name = "name") val name: String?
)
