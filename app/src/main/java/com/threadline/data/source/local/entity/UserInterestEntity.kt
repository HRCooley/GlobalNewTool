package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_interests")
data class UserInterestEntity(
    @PrimaryKey val id: String,
    val keyword: String,
    val weight: Float,
    val source: String
)
