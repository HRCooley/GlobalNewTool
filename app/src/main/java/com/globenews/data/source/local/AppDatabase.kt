package com.globenews.data.source.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CustomFeedEntity::class, ManagedFeed::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customFeedDao(): CustomFeedDao
    abstract fun managedFeedDao(): ManagedFeedDao
}
