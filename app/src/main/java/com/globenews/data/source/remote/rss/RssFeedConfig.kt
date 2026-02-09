package com.globenews.data.source.remote.rss

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RssFeedConfig(
    @Json(name = "name") val name: String,
    @Json(name = "url") val url: String,
    @Json(name = "country") val country: String,
    @Json(name = "language") val language: String,
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double,
    @Json(name = "scope") val scope: String
)
