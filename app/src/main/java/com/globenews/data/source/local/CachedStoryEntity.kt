package com.globenews.data.source.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "cached_stories")
data class CachedStoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String?,
    val url: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val placeName: String?,
    val countryCode: String?,
    val scope: String,
    val category: String,
    val sourceName: String,
    val providerApi: String,
    val language: String,
    val cachedAt: Long = System.currentTimeMillis()
)

@Dao
interface StoryDao {
    @Query("""
        SELECT * FROM cached_stories
        WHERE latitude BETWEEN :south AND :north
        AND longitude BETWEEN :west AND :east
        AND cachedAt > :maxAge
        ORDER BY publishedAt DESC LIMIT 500
    """)
    suspend fun getInBounds(
        north: Double, south: Double, east: Double, west: Double, maxAge: Long
    ): List<CachedStoryEntity>

    @Upsert
    suspend fun upsertAll(stories: List<CachedStoryEntity>)

    @Query("DELETE FROM cached_stories WHERE cachedAt < :cutoff")
    suspend fun deleteExpired(cutoff: Long)

    @Query("SELECT COUNT(*) FROM cached_stories")
    suspend fun count(): Int
}
