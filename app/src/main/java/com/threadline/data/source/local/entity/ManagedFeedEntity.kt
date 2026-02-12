package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "managed_feeds")
data class ManagedFeedEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val country: String?,
    val language: String?,
    val latitude: Double?,
    val longitude: Double?,
    val scope: String,
    val category: String?,
    val region: String?,
    val politicalLean: String?,
    val ownership: String?,
    val ownerName: String?,
    val bundledRegion: String?,
    val bundledPoliticalLean: String?,
    val bundledOwnership: String?,
    val bundledOwnerName: String?,
    val tagsModifiedByUser: Boolean = false,
    val enabled: Boolean = true,
    val isBundled: Boolean = true,
    val lastFetchAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val consecutiveFailures: Int = 0,
    val totalFetches: Int = 0,
    val totalSuccesses: Int = 0
)
