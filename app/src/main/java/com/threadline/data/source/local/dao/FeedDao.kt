package com.threadline.data.source.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.threadline.data.source.local.entity.ManagedFeedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {

    @Query("SELECT * FROM managed_feeds ORDER BY name")
    suspend fun getAll(): List<ManagedFeedEntity>

    @Query("SELECT * FROM managed_feeds ORDER BY name")
    fun getAllFlow(): Flow<List<ManagedFeedEntity>>

    @Query("SELECT * FROM managed_feeds WHERE enabled = 1 ORDER BY name")
    suspend fun getEnabled(): List<ManagedFeedEntity>

    @Query("""
        SELECT * FROM managed_feeds
        WHERE enabled = 1
        AND latitude BETWEEN :south AND :north
        AND longitude BETWEEN :west AND :east
        ORDER BY name
    """)
    suspend fun getFeedsInBounds(
        north: Double, south: Double,
        east: Double, west: Double
    ): List<ManagedFeedEntity>

    @Query("SELECT * FROM managed_feeds WHERE enabled = 1 AND scope = 'INTERNATIONAL' ORDER BY name")
    suspend fun getInternationalFeeds(): List<ManagedFeedEntity>

    @Upsert
    suspend fun upsertAll(feeds: List<ManagedFeedEntity>)

    @Query("UPDATE managed_feeds SET lastFetchAt = :timestamp, lastSuccessAt = :timestamp, consecutiveFailures = 0, totalFetches = totalFetches + 1, totalSuccesses = totalSuccesses + 1 WHERE id = :id")
    suspend fun recordSuccess(id: String, timestamp: Long)

    @Query("UPDATE managed_feeds SET lastFetchAt = :timestamp, lastError = :error, consecutiveFailures = consecutiveFailures + 1, totalFetches = totalFetches + 1 WHERE id = :id")
    suspend fun recordFailure(id: String, timestamp: Long, error: String)

    @Query("SELECT * FROM managed_feeds WHERE id = :id")
    suspend fun getById(id: String): ManagedFeedEntity?

    @Query("UPDATE managed_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM managed_feeds WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM managed_feeds WHERE consecutiveFailures >= 10")
    suspend fun getBrokenCount(): Int

    @Query("SELECT * FROM managed_feeds WHERE consecutiveFailures >= 10")
    suspend fun getBrokenFeeds(): List<ManagedFeedEntity>

    @Query("UPDATE managed_feeds SET consecutiveFailures = 0, lastError = null")
    suspend fun resetAllFailures()
}
