package com.threadline.di

import android.content.Context
import androidx.room.Room
import com.threadline.core.database.ThreadlineDatabase
import com.threadline.data.source.local.dao.BoardDao
import com.threadline.data.source.local.dao.FeedDao
import com.threadline.data.source.local.dao.StoryDao
import com.threadline.data.source.local.dao.ThreadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThreadlineDatabase {
        return Room.databaseBuilder(
            context,
            ThreadlineDatabase::class.java,
            "threadline.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideStoryDao(db: ThreadlineDatabase): StoryDao = db.storyDao()

    @Provides
    fun provideFeedDao(db: ThreadlineDatabase): FeedDao = db.feedDao()

    @Provides
    fun provideThreadDao(db: ThreadlineDatabase): ThreadDao = db.threadDao()

    @Provides
    fun provideBoardDao(db: ThreadlineDatabase): BoardDao = db.boardDao()
}
