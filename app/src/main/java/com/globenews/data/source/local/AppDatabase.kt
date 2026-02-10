package com.globenews.data.source.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CustomFeedEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customFeedDao(): CustomFeedDao
}
