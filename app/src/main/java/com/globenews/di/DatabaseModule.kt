package com.globenews.di

import android.content.Context
import androidx.room.Room
import com.globenews.data.source.local.AppDatabase
import com.globenews.data.source.local.CustomFeedDao
import com.globenews.data.source.local.ManagedFeedDao
import com.globenews.data.source.local.StoryDao
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "globenews.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideCustomFeedDao(db: AppDatabase): CustomFeedDao {
        return db.customFeedDao()
    }

    @Provides
    fun provideManagedFeedDao(db: AppDatabase): ManagedFeedDao {
        return db.managedFeedDao()
    }

    @Provides
    fun provideStoryDao(db: AppDatabase): StoryDao {
        return db.storyDao()
    }
}
