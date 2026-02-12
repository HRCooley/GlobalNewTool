package com.threadline.data.source.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.threadline.data.source.local.entity.ThreadEntity
import com.threadline.data.source.local.entity.ThreadStoryEntity

@Dao
interface ThreadDao {

    @Query("SELECT * FROM threads WHERE isArchived = 0 ORDER BY updatedAt DESC")
    suspend fun getActive(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun getById(id: String): ThreadEntity?

    @Upsert
    suspend fun upsert(thread: ThreadEntity)

    @Delete
    suspend fun delete(thread: ThreadEntity)

    @Upsert
    suspend fun upsertStory(story: ThreadStoryEntity)

    @Query("SELECT * FROM thread_stories WHERE threadId = :threadId ORDER BY matchedAt DESC")
    suspend fun getStoriesForThread(threadId: String): List<ThreadStoryEntity>
}
