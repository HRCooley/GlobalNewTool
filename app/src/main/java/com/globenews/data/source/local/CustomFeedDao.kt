package com.globenews.data.source.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CustomFeedDao {
    @Query("SELECT * FROM custom_feeds WHERE enabled = 1")
    suspend fun getEnabledFeeds(): List<CustomFeedEntity>

    @Query("SELECT * FROM custom_feeds")
    suspend fun getAllFeeds(): List<CustomFeedEntity>

    @Insert
    suspend fun insert(feed: CustomFeedEntity)

    @Delete
    suspend fun delete(feed: CustomFeedEntity)
}
