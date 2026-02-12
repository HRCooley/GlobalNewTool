package com.threadline.data.source.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.threadline.data.source.local.entity.CachedStoryEntity

@Dao
interface StoryDao {

    @Query("SELECT * FROM cached_stories ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 500): List<CachedStoryEntity>

    @Query("""
        SELECT * FROM cached_stories
        WHERE latitude BETWEEN :south AND :north
        AND longitude BETWEEN :west AND :east
        AND cachedAt > :maxAge
        ORDER BY publishedAt DESC
    """)
    suspend fun getInBounds(
        north: Double, south: Double,
        east: Double, west: Double,
        maxAge: Long
    ): List<CachedStoryEntity>

    @Upsert
    suspend fun upsertAll(stories: List<CachedStoryEntity>)

    @Query("DELETE FROM cached_stories WHERE cachedAt < :before")
    suspend fun deleteExpired(before: Long)

    @Query("SELECT COUNT(*) FROM cached_stories")
    suspend fun count(): Int
}
