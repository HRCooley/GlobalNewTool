package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_stories")
data class CachedStoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String?,
    val url: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val placeName: String?,
    val countryCode: String?,
    val scope: String,
    val category: String,
    val sourceName: String,
    val providerApi: String,
    val language: String,
    val sentiment: Float?,
    val cachedAt: Long,
    val clusterId: String?,
    val clusterSize: Int?,
    val coverageRegions: String?,
    val isSignal: Boolean = false
)
