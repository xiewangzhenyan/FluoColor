package com.muc.fluocolorquant.di

import android.content.Context
import androidx.room.Room
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.WellResultDao
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "fluocolorquant_db"
        )
        .fallbackToDestructiveMigration() // 仅在开发阶段使用，生产环境应该提供正确的迁移策略
        .build()
    }
    
    @Provides
    fun provideUserDao(appDatabase: AppDatabase): UserDao {
        return appDatabase.userDao()
    }

    @Provides
    fun provideProjectDao(appDatabase: AppDatabase): ProjectDao {
        return appDatabase.projectDao()
    }

    @Provides
    fun provideDetectionRunDao(appDatabase: AppDatabase): DetectionRunDao {
        return appDatabase.detectionRunDao()
    }
    
    @Provides
    fun provideWellResultDao(appDatabase: AppDatabase): WellResultDao {
        return appDatabase.wellResultDao()
    }
} 