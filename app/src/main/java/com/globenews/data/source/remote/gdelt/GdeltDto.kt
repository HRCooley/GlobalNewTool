package com.globenews.data.source.remote.gdelt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GdeltResponse(
    @Json(name = "articles") val articles: List<GdeltArticle>?
)

@JsonClass(generateAdapter = true)
data class GdeltArticle(
    @Json(name = "url") val url: String?,
    @Json(name = "title") val title: String?,
    @Json(name = "seendate") val seenDate: String?,
    @Json(name = "socialimage") val socialImage: String?,
    @Json(name = "domain") val domain: String?,
    @Json(name = "language") val language: String?,
    @Json(name = "sourcecountry") val sourceCountry: String?,
    @Json(name = "tone") val tone: String?
)
