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
import com.muc.fluocolorquant.data.repository.ProjectCreationPreferences
import com.muc.fluocolorquant.data.security.PasswordHasher
import com.muc.fluocolorquant.data.security.Pbkdf2PasswordHasher
import com.muc.fluocolorquant.data.storage.ProjectFileCleaner
import com.muc.fluocolorquant.data.repository.ConcentrationUnitPreferences
import com.muc.fluocolorquant.data.repository.CalibrationPolicyPreferences
import com.muc.fluocolorquant.data.repository.CalibrationSettingsRepository
import com.muc.fluocolorquant.data.repository.UserRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepositoryImpl
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.data.repository.ReagentRepositoryImpl
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.data.repository.CurveModelRepositoryImpl
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepositoryImpl
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepositoryImpl
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.data.repository.SpectrumRepositoryImpl
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelRepositoryImpl
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepositoryImpl
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.data.repository.ArrayResultRepositoryImpl
import com.muc.fluocolorquant.data.repository.LegacyPlateResultRepository
import com.muc.fluocolorquant.data.repository.LegacyPlateResultRepositoryImpl
import com.muc.fluocolorquant.data.repository.DualModalAdjudicationRepository
import com.muc.fluocolorquant.data.repository.DualModalAdjudicationRepositoryImpl
import com.muc.fluocolorquant.data.repository.DualNetAssessmentService
import com.muc.fluocolorquant.data.repository.DualNetAssessmentServiceImpl
import com.muc.fluocolorquant.data.repository.ResultValidationRepository
import com.muc.fluocolorquant.data.repository.ResultValidationRepositoryImpl
import com.muc.fluocolorquant.utils.camera.CameraEngine
import com.muc.fluocolorquant.utils.camera.CameraXCameraEngine
import com.muc.fluocolorquant.domain.project.TemplateProjectCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinator
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocator
import com.muc.fluocolorquant.domain.detection.evidence.AndroidGridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.AndroidPlate96ProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.Plate96ProcessingEvidenceWriter
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

    /** 密码摘要实现集中注入，注册、登录升级和修改密码必须使用同一版本化协议。 */
    @Binds
    @Singleton
    abstract fun providePasswordHasher(
        implementation: Pbkdf2PasswordHasher
    ): PasswordHasher

    /** PG-Grid 处理中间图写入应用私有目录，协调器只依赖可测试接口。 */
    @Binds
    @Singleton
    abstract fun provideGridProcessingEvidenceWriter(
        writer: AndroidGridProcessingEvidenceWriter
    ): GridProcessingEvidenceWriter

    /** 96孔板过程图保持圆孔与标准A1～H12语义，不复用微流控方格渲染。 */
    @Binds
    @Singleton
    abstract fun providePlate96ProcessingEvidenceWriter(
        writer: AndroidPlate96ProcessingEvidenceWriter
    ): Plate96ProcessingEvidenceWriter

    /** 模板与曲线编辑器读取和检测设置相同的浓度单位来源。 */
    @Binds
    @Singleton
    abstract fun provideConcentrationUnitPreferences(
        settingsRepository: SettingsRepository
    ): ConcentrationUnitPreferences

    /** 直接新建只读取确有生产作用的检测方式与浓度单位默认值。 */
    @Binds
    @Singleton
    abstract fun provideProjectCreationPreferences(
        settingsRepository: SettingsRepository
    ): ProjectCreationPreferences

    /** 曲线拟合默认策略使用独立 DataStore，检测入口只读取不可变策略快照。 */
    @Binds
    @Singleton
    abstract fun provideCalibrationPolicyPreferences(
        repository: CalibrationSettingsRepository
    ): CalibrationPolicyPreferences
    
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
     * 提供项目-分析物关联仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideProjectAnalyteJoinRepository(
        projectAnalyteJoinRepositoryImpl: ProjectAnalyteJoinRepositoryImpl
    ): ProjectAnalyteJoinRepository
    
    /**
     * 提供实验模板仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideExperimentTemplateRepository(
        experimentTemplateRepositoryImpl: ExperimentTemplateRepositoryImpl
    ): ExperimentTemplateRepository

    /** 提供载体档案仓库，所有载体编辑统一走版本化保存。 */
    @Binds
    @Singleton
    abstract fun provideCarrierProfileRepository(
        carrierProfileRepositoryImpl: CarrierProfileRepositoryImpl
    ): CarrierProfileRepository

    /** 提供采集设备档案仓库，禁止界面直接物理删除设备资源。 */
    @Binds
    @Singleton
    abstract fun provideAcquisitionProfileRepository(
        acquisitionProfileRepositoryImpl: AcquisitionProfileRepositoryImpl
    ): AcquisitionProfileRepository

    /** 提供统一分析模型仓库，集中执行草稿、发布、版本化和归档规则。 */
    @Binds
    @Singleton
    abstract fun provideAnalysisModelRepository(
        analysisModelRepositoryImpl: AnalysisModelRepositoryImpl
    ): AnalysisModelRepository

    /** 新规则阵列检测使用数据库级单事务保存运行、附件和逐位点测量。 */
    @Binds
    @Singleton
    abstract fun provideGridDetectionRunRepository(
        repository: GridDetectionRunRepositoryImpl
    ): GridDetectionRunRepository

    /** 新阵列结果统一从运行快照重建，禁止页面直接跨 DAO 拼接 JSON。 */
    @Binds
    @Singleton
    abstract fun provideArrayResultRepository(
        repository: ArrayResultRepositoryImpl
    ): ArrayResultRepository

    /** 旧96孔板只读适配，不向旧表暴露任何新的生产写入入口。 */
    @Binds
    @Singleton
    abstract fun provideLegacyPlateResultRepository(
        repository: LegacyPlateResultRepositoryImpl
    ): LegacyPlateResultRepository

    /** 结果验证跨孔板和微流控共享同一套修订式持久化。 */
    @Binds
    @Singleton
    abstract fun provideResultValidationRepository(
        repository: ResultValidationRepositoryImpl
    ): ResultValidationRepository

    /** 双模态判定只追加派生修订，读取两次运行的冻结快照，不写回任何检测结果。 */
    @Binds
    @Singleton
    abstract fun provideDualModalAdjudicationRepository(
        repository: DualModalAdjudicationRepositoryImpl
    ): DualModalAdjudicationRepository

    /** DualNet 网络判读：追溯标定板并在配对时冻结网络读数，规则判定不依赖它。 */
    @Binds
    @Singleton
    abstract fun provideDualNetAssessmentService(
        service: DualNetAssessmentServiceImpl
    ): DualNetAssessmentService

    /** 微流控主定位器；学习型定位器仅在离线 A/B 证明收益后替换此绑定。 */
    @Binds
    @Singleton
    abstract fun providePgGridLocator(
        locator: OpenCvPgGridLocator
    ): PgGridLocator

    /** 提供模板优先项目协调器，统一执行项目创建前检查和快照冻结。 */
    @Binds
    @Singleton
    abstract fun provideTemplateProjectCoordinator(
        coordinator: TemplateProjectCreationCoordinator
    ): TemplateProjectCoordinator

    /**
     * 提供光谱仓库实现
     */
    @Binds
    @Singleton
    abstract fun provideSpectrumRepository(
        spectrumRepositoryImpl: SpectrumRepositoryImpl
    ): SpectrumRepository

    @Binds
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    abstract fun provideCameraEngine(
        cameraXCameraEngine: CameraXCameraEngine
    ): CameraEngine
    
    companion object {
        @Provides
        @Singleton
        fun provideUserRepository(
            userDao: UserDao,
            passwordHasher: PasswordHasher
        ): UserRepository {
            return UserRepository(userDao, passwordHasher)
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
            projectDao: ProjectDao,
            projectFileCleaner: ProjectFileCleaner
        ): ProjectRepository {
            return ProjectRepositoryImpl(projectDao, projectFileCleaner)
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
