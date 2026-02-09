package com.globenews.data.source.remote.gnews

import android.util.Log
import com.globenews.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GNewsDataSource @Inject constructor(
    private val api: GNewsService
) {
    companion object {
        private const val TAG = "GNewsDS"
    }

    val isAvailable: Boolean
        get() = BuildConfig.GNEWS_API_KEY.isNotBlank()

    suspend fun fetchHeadlines(): List<GNewsArticle> {
        if (!isAvailable) return emptyList()
        return try {
            val response = api.topHeadlines(token = BuildConfig.GNEWS_API_KEY)
            response.articles ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "GNews fetch failed: ${e.message}")
            emptyList()
        }
    }
}
