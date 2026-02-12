package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val keywords: String,
    val gdeltThemes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val notifyMode: String,
    val linkedBoardId: String?,
    val color: String,
    val isArchived: Boolean = false
)

@Entity(tableName = "thread_stories")
data class ThreadStoryEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val storyId: String,
    val matchedAt: Long,
    val isPinned: Boolean = false,
    val userNote: String?
)
