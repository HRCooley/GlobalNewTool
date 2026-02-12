package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_articles")
data class SavedArticleEntity(
    @PrimaryKey val id: String,
    val storyId: String?,
    val url: String,
    val title: String,
    val summary: String?,
    val content: String?,
    val sourceName: String,
    val savedAt: Long,
    val userNotes: String?,
    val threadId: String?,
    val boardId: String?
)
