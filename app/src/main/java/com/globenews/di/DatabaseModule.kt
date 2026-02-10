package com.globenews.di

import android.content.Context
import androidx.room.Room
import com.globenews.data.source.local.AppDatabase
import com.globenews.data.source.local.CustomFeedDao
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
}
