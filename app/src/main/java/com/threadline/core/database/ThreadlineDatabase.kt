package com.threadline.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.threadline.data.source.local.dao.BoardDao
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.dao.StoryDao
import com.threadline.data.source.local.dao.ThreadDao
import com.threadline.data.source.local.entity.BoardCardEntity
import com.threadline.data.source.local.entity.BoardConnectionEntity
import com.threadline.data.source.local.entity.BoardEntity
import com.threadline.data.source.local.entity.CachedStoryEntity
import com.threadline.data.source.local.entity.ManagedFeedEntity
import com.threadline.data.source.local.entity.SavedArticleEntity
import com.threadline.data.source.local.entity.ThreadEntity
import com.threadline.data.source.local.entity.ThreadStoryEntity
import com.threadline.data.source.local.entity.UserInterestEntity

@Database(
    entities = [
        CachedStoryEntity::class,
        ManagedFeedEntity::class,
        SavedArticleEntity::class,
        ThreadEntity::class,
        ThreadStoryEntity::class,
        BoardEntity::class,
        BoardCardEntity::class,
        BoardConnectionEntity::class,
        UserInterestEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ThreadlineDatabase : RoomDatabase() {
    abstract fun storyDao(): StoryDao
    abstract fun feedDao(): FeedDao
    abstract fun threadDao(): ThreadDao
    abstract fun boardDao(): BoardDao
}
