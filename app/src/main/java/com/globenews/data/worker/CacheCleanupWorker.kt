package com.globenews.data.worker

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.globenews.data.source.local.AppDatabase

class CacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "globenews.db"
            ).fallbackToDestructiveMigration().build()

            val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            val countBefore = db.storyDao().count()
            db.storyDao().deleteExpired(cutoff)
            val countAfter = db.storyDao().count()
            Log.d("CacheCleanup", "Cleaned up ${countBefore - countAfter} expired stories, $countAfter remaining")
            db.close()
            Result.success()
        } catch (e: Exception) {
            Log.e("CacheCleanup", "Cache cleanup failed: ${e.message}")
            Result.failure()
        }
    }
}
