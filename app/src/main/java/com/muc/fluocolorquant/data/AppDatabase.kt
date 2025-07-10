package com.muc.fluocolorquant.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.dao.ReagentDao
import com.muc.fluocolorquant.data.dao.CurveModelDao
import com.muc.fluocolorquant.data.dao.ProjectAnalyteJoinDao
import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.converters.Converters

/**
 * 应用数据库
 * 统一管理所有数据表和DAO
 */
@Database(
    entities = [
        User::class,
        Project::class,
        DetectionRun::class,
        WellResult::class,
        Analyte::class,
        Reagent::class,
        CurveModel::class,
        ProjectAnalyteJoin::class,
        ExperimentTemplate::class
    ],
    version = 7,  // 版本号从6升级到7，因为添加了新字段dlModelName
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun projectDao(): ProjectDao
    abstract fun detectionRunDao(): DetectionRunDao
    abstract fun wellResultDao(): WellResultDao
    abstract fun analyteDao(): AnalyteDao
    abstract fun reagentDao(): ReagentDao
    abstract fun curveModelDao(): CurveModelDao
    abstract fun projectAnalyteJoinDao(): ProjectAnalyteJoinDao
    abstract fun experimentTemplateDao(): ExperimentTemplateDao
} 