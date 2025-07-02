package com.muc.fluocolorquant.di

import android.content.Context
import com.muc.fluocolorquant.data.SessionManager
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.repository.UserRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepositoryImpl
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.data.repository.ReagentRepositoryImpl
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.data.repository.CurveModelRepositoryImpl
import com.muc.fluocolorquant.data.repository.PlateLayoutRepository
import com.muc.fluocolorquant.data.repository.PlateLayoutRepositoryImpl
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库模块
 * 为各个仓库接口提供实现类
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    /**
     * 提供分析物仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideAnalyteRepository(
        analyteRepositoryImpl: AnalyteRepositoryImpl
    ): AnalyteRepository
    
    /**
     * 提供试剂仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideReagentRepository(
        reagentRepositoryImpl: ReagentRepositoryImpl
    ): ReagentRepository
    
    /**
     * 提供曲线模型仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideCurveModelRepository(
        curveModelRepositoryImpl: CurveModelRepositoryImpl
    ): CurveModelRepository
    
    /**
     * 提供孔板布局仓库实现
     */
    @Binds
    @Singleton
    abstract fun providePlateLayoutRepository(
        plateLayoutRepositoryImpl: PlateLayoutRepositoryImpl
    ): PlateLayoutRepository
    
    /**
     * 提供实验模板仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideExperimentTemplateRepository(
        experimentTemplateRepositoryImpl: ExperimentTemplateRepositoryImpl
    ): ExperimentTemplateRepository
    
    companion object {
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
        
        @Provides
        @Singleton
        fun provideSettingsRepository(
            @ApplicationContext context: Context
        ): SettingsRepository {
            return SettingsRepository(context)
        }
    }
} 