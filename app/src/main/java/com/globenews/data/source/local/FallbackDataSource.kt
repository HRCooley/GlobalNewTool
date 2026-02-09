package com.globenews.data.source.local

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FallbackDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "FallbackDataSource"
    }

    private var cachedStories: List<FallbackStory>? = null

    fun loadFallbackStories(): List<FallbackStory> {
        cachedStories?.let { return it }
        return try {
            val json = context.assets.open("fallback_news.json").bufferedReader().readText()
            val type = Types.newParameterizedType(List::class.java, FallbackStory::class.java)
            val adapter = moshi.adapter<List<FallbackStory>>(type)
            val stories = adapter.fromJson(json) ?: emptyList()
            cachedStories = stories
            stories
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load fallback_news.json: ${e.message}")
            emptyList()
        }
    }
}

@JsonClass(generateAdapter = true)
data class FallbackStory(
    @Json(name = "title") val title: String,
    @Json(name = "url") val url: String,
    @Json(name = "summary") val summary: String?,
    @Json(name = "imageUrl") val imageUrl: String?,
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double,
    @Json(name = "placeName") val placeName: String?,
    @Json(name = "countryCode") val countryCode: String?,
    @Json(name = "category") val category: String,
    @Json(name = "scope") val scope: String,
    @Json(name = "sourceName") val sourceName: String,
    @Json(name = "language") val language: String?
)
