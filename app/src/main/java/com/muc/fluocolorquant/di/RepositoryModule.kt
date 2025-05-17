package com.muc.fluocolorquant.di

import android.content.Context
import com.muc.fluocolorquant.data.SessionManager
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import com.muc.fluocolorquant.data.repository.UserRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    
    @Provides
    @Singleton
    fun provideUserRepository(
        userDao: UserDao
    ): UserRepository {
        return UserRepository(userDao)
    }
    
    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        userRepository: UserRepository
    ): SessionManager {
        return SessionManager(context, userRepository)
    }
    
    @Provides
    @Singleton
    fun provideProjectRepository(
        projectDao: ProjectDao
    ): ProjectRepository {
        return ProjectRepositoryImpl(projectDao)
    }
    
    @Provides
    @Singleton
    fun provideWellResultRepository(
        wellResultDao: WellResultDao,
        detectionRunDao: DetectionRunDao,
        projectDao: ProjectDao,
        @ApplicationContext context: Context
    ): WellResultRepository {
        return WellResultRepository(wellResultDao, detectionRunDao, projectDao, context)
    }
} 