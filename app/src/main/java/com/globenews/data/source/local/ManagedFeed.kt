package com.globenews.data.source.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "managed_feeds")
data class ManagedFeed(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val country: String,
    val language: String,
    val latitude: Double,
    val longitude: Double,
    val scope: String,
    val category: String,
    val enabled: Boolean = true,
    val isBundled: Boolean = true,
    val lastFetchAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val totalFetches: Int = 0,
    val totalSuccesses: Int = 0
)
