package com.globenews.data.source.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagedFeedDao {

    @Query("SELECT * FROM managed_feeds WHERE enabled = 1 ORDER BY name")
    suspend fun getEnabledFeeds(): List<ManagedFeed>

    @Query("SELECT * FROM managed_feeds ORDER BY category, name")
    suspend fun getAllFeeds(): List<ManagedFeed>

    @Query("SELECT * FROM managed_feeds ORDER BY category, name")
    fun getAllFeedsFlow(): Flow<List<ManagedFeed>>

    @Query("SELECT * FROM managed_feeds WHERE consecutiveFailures >= 3")
    suspend fun getBrokenFeeds(): List<ManagedFeed>

    @Query("SELECT COUNT(*) FROM managed_feeds WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM managed_feeds WHERE consecutiveFailures >= 3")
    suspend fun getBrokenCount(): Int

    @Query("SELECT COUNT(*) FROM managed_feeds WHERE consecutiveFailures >= 3")
    fun getBrokenCountFlow(): Flow<Int>

    @Upsert
    suspend fun upsert(feed: ManagedFeed)

    @Upsert
    suspend fun upsertAll(feeds: List<ManagedFeed>)

    @Delete
    suspend fun delete(feed: ManagedFeed)

    @Query("UPDATE managed_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE managed_feeds SET lastFetchAt = :time, lastSuccessAt = :time, consecutiveFailures = 0, totalFetches = totalFetches + 1, totalSuccesses = totalSuccesses + 1 WHERE id = :id")
    suspend fun recordSuccess(id: String, time: Long)

    @Query("UPDATE managed_feeds SET lastFetchAt = :time, lastError = :error, consecutiveFailures = consecutiveFailures + 1, totalFetches = totalFetches + 1 WHERE id = :id")
    suspend fun recordFailure(id: String, time: Long, error: String)

    @Query("SELECT * FROM managed_feeds WHERE enabled = 1 AND consecutiveFailures < 5 AND latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east")
    suspend fun getFeedsInBounds(north: Double, south: Double, east: Double, west: Double): List<ManagedFeed>

    @Query("SELECT * FROM managed_feeds WHERE enabled = 1 AND consecutiveFailures < 5 AND scope = 'INTERNATIONAL'")
    suspend fun getInternationalFeeds(): List<ManagedFeed>

    @Query("UPDATE managed_feeds SET consecutiveFailures = 0, lastError = null")
    suspend fun resetAllFailures()
}
