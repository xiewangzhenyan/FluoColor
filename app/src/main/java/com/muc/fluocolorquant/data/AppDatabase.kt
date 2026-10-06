package com.muc.fluocolorquant.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.muc.fluocolorquant.data.converters.Converters
import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.dao.AcquisitionProfileDao
import com.muc.fluocolorquant.data.dao.AnalysisModelDao
import com.muc.fluocolorquant.data.dao.CaptureArtifactDao
import com.muc.fluocolorquant.data.dao.CarrierProfileDao
import com.muc.fluocolorquant.data.dao.CurveModelDao
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.DualModalAdjudicationDao
import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.dao.ProjectAnalyteJoinDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.ReagentDao
import com.muc.fluocolorquant.data.dao.ResultValidationDao
import com.muc.fluocolorquant.data.dao.SpectrumDao
import com.muc.fluocolorquant.data.dao.SiteMeasurementDao
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.DualModalAdjudicationRecord
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.model.ResultValidationRecord
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumResult
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.model.TemplateQuantitationBinding
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.model.WellResult

/**
 * 应用数据库：统一管理所有数据表与 DAO。
 */
@Database(
    entities = [
        User::class,
        Project::class,
        DetectionRun::class,
        WellResult::class,
        SpectrumCalibration::class,
        SpectrumResult::class,
        Analyte::class,
        Reagent::class,
        CurveModel::class,
        ProjectAnalyteJoin::class,
        ExperimentTemplate::class,
        CarrierProfile::class,
        AcquisitionProfile::class,
        TemplateAnalyteConfig::class,
        TemplateSiteAssignment::class,
        TemplateQuantitationBinding::class,
        AnalysisModel::class,
        StandardCurveDefinition::class,
        CalibrationPoint::class,
        DeepLearningModelDefinition::class,
        CaptureArtifact::class,
        SiteMeasurement::class,
        ResultValidationRecord::class,
        DualModalAdjudicationRecord::class
    ],
    version = 20,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun projectDao(): ProjectDao
    abstract fun detectionRunDao(): DetectionRunDao
    abstract fun wellResultDao(): WellResultDao
    abstract fun spectrumDao(): SpectrumDao
    abstract fun analyteDao(): AnalyteDao
    abstract fun reagentDao(): ReagentDao
    abstract fun curveModelDao(): CurveModelDao
    abstract fun projectAnalyteJoinDao(): ProjectAnalyteJoinDao
    abstract fun experimentTemplateDao(): ExperimentTemplateDao
    abstract fun carrierProfileDao(): CarrierProfileDao
    abstract fun acquisitionProfileDao(): AcquisitionProfileDao
    abstract fun analysisModelDao(): AnalysisModelDao
    abstract fun captureArtifactDao(): CaptureArtifactDao
    abstract fun siteMeasurementDao(): SiteMeasurementDao
    abstract fun resultValidationDao(): ResultValidationDao
    abstract fun dualModalAdjudicationDao(): DualModalAdjudicationDao
}
