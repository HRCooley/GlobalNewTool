package com.globenews.data.source.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_feeds")
data class CustomFeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val latitude: Double,
    val longitude: Double,
    val enabled: Boolean = true
)
