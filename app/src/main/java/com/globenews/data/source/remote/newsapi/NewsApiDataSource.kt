package com.globenews.data.source.remote.newsapi

import android.util.Log
import com.globenews.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsApiDataSource @Inject constructor(
    private val api: NewsApiService
) {
    companion object {
        private const val TAG = "NewsApiDS"
    }

    val isAvailable: Boolean
        get() = BuildConfig.NEWSAPI_KEY.isNotBlank()

    suspend fun fetchHeadlines(country: String = "us"): List<NewsApiArticle> {
        if (!isAvailable) return emptyList()
        return try {
            val response = api.topHeadlines(
                country = country,
                apiKey = BuildConfig.NEWSAPI_KEY
            )
            response.articles ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "NewsAPI fetch failed: ${e.message}")
            emptyList()
        }
    }
}
