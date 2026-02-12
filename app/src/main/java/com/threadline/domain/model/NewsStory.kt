package com.threadline.domain.model

import java.time.Instant

data class NewsStory(
    val id: String,
    val title: String,
    val summary: String?,
    val url: String,
    val imageUrl: String?,
    val publishedAt: Instant,
    val latitude: Double?,
    val longitude: Double?,
    val placeName: String?,
    val countryCode: String?,
    val scope: String,
    val category: String,
    val sourceName: String,
    val providerApi: String,
    val language: String,
    val sentiment: Float?
)
