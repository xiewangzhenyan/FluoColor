package com.muc.fluocolorquant.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.dao.ReagentDao
import com.muc.fluocolorquant.data.dao.CurveModelDao
import com.muc.fluocolorquant.data.dao.ProjectAnalyteJoinDao
import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.dao.SpectrumDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

/**
 * 数据库模块
 * 提供数据库实例和各个DAO
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * 提供数据库实例
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "fluocolor_database"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        .addCallback(prepopulateCallback)  // 添加预填充回调
        .fallbackToDestructiveMigration() // 版本更新时，如果没有提供迁移路径，则重建数据库
        .build()
    }
    
    // 版本6到版本7的迁移策略
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 1. 创建临时表，包含新字段 dlModelName
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_analytes_join_temp` (
                    `projectId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `maxConcentration` REAL,
                    `concentrationUnit` TEXT,
                    `fkTemplateId` TEXT,
                    `dlModelName` TEXT,
                    PRIMARY KEY(`projectId`, `analyteId`),
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`fkTemplateId`) REFERENCES `experiment_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            
            // 2. 从原表复制数据到临时表，新字段设为NULL
            database.execSQL(
                """
                INSERT INTO project_analytes_join_temp (projectId, analyteId, maxConcentration, concentrationUnit, fkTemplateId, dlModelName)
                SELECT projectId, analyteId, maxConcentration, concentrationUnit, fkTemplateId, NULL FROM project_analytes_join
                """
            )
            
            // 3. 删除原表并重命名临时表
            database.execSQL("DROP TABLE project_analytes_join")
            database.execSQL("ALTER TABLE project_analytes_join_temp RENAME TO project_analytes_join")
            
            // 4. 重建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_projectId` ON `project_analytes_join` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_analyteId` ON `project_analytes_join` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_fkTemplateId` ON `project_analytes_join` (`fkTemplateId`)")
        }
    }

    // 版本5到版本6的迁移策略
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 1. 首先修改 project_analytes_join 表，添加新字段
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_analytes_join_temp` (
                    `projectId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `maxConcentration` REAL,
                    `concentrationUnit` TEXT,
                    `fkTemplateId` TEXT,
                    PRIMARY KEY(`projectId`, `analyteId`),
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`fkTemplateId`) REFERENCES `experiment_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            
            // 2. 复制已有的数据到新表，新增字段设为NULL
            database.execSQL(
                """
                INSERT INTO project_analytes_join_temp (projectId, analyteId, maxConcentration, concentrationUnit, fkTemplateId)
                SELECT projectId, analyteId, NULL, NULL, NULL FROM project_analytes_join
                """
            )
            
            // 3. 删除旧表，重命名新表
            database.execSQL("DROP TABLE project_analytes_join")
            database.execSQL("ALTER TABLE project_analytes_join_temp RENAME TO project_analytes_join")
            
            // 4. 创建新索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_projectId` ON `project_analytes_join` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_analyteId` ON `project_analytes_join` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_fkTemplateId` ON `project_analytes_join` (`fkTemplateId`)")
            
            // 5. 将 projects 表中的 maxConcentration 和 concentrationUnit 数据迁移到关联表中
            database.execSQL(
                """
                UPDATE project_analytes_join
                SET maxConcentration = (
                    SELECT maxConcentration FROM projects 
                    WHERE projects.id = project_analytes_join.projectId
                ),
                concentrationUnit = (
                    SELECT concentrationUnit FROM projects 
                    WHERE projects.id = project_analytes_join.projectId
                )
                """
            )
            
            // 6. 创建新的 projects 表临时表，不包含已移除的字段
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `projects_temp` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `detectionMode` TEXT NOT NULL,
                    `recognitionType` TEXT NOT NULL,
                    `imageUri` TEXT NOT NULL,
                    `rows` INTEGER NOT NULL,
                    `columns` INTEGER NOT NULL,
                    `createTime` INTEGER NOT NULL,
                    `userId` TEXT NOT NULL,
                    `lastRunTimestamp` INTEGER,
                    `analysisMethod` TEXT NOT NULL,
                    `fkCurveModelId` TEXT,
                    `finalCurveModelJson` TEXT,
                    PRIMARY KEY(`id`)
                )
                """
            )
            
            // 7. 复制 projects 表数据到临时表，排除已移除的字段
            database.execSQL(
                """
                INSERT INTO projects_temp (
                    id, name, detectionMode, recognitionType, imageUri, 
                    rows, columns, createTime, userId, lastRunTimestamp, 
                    analysisMethod, fkCurveModelId, finalCurveModelJson
                )
                SELECT 
                    id, name, detectionMode, recognitionType, imageUri, 
                    rows, columns, createTime, userId, lastRunTimestamp, 
                    analysisMethod, fkCurveModelId, finalCurveModelJson
                FROM projects
                """
            )
            
            // 8. 删除旧表，重命名新表
            database.execSQL("DROP TABLE projects")
            database.execSQL("ALTER TABLE projects_temp RENAME TO projects")
        }
    }
    
    // 版本4到版本5的迁移策略
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 1. 删除 plate_layouts 表
            database.execSQL("DROP TABLE IF EXISTS plate_layouts")

            // 2. 创建 project_analytes_join 表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_analytes_join` (
                    `projectId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    PRIMARY KEY(`projectId`, `analyteId`),
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )

            // 3. 创建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_projectId` ON `project_analytes_join` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_analyteId` ON `project_analytes_join` (`analyteId`)")

            // 4. 修改 well_results 表结构
            // 删除孔位手动裁剪相关字段
            database.execSQL("CREATE TABLE IF NOT EXISTS `well_results_temp` ("+
                "`resultId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "+
                "`runId` TEXT, `projectId` TEXT NOT NULL, `wellIndex` INTEGER NOT NULL, "+
                "`predictedConcentration` REAL, `trueConcentration` REAL, "+
                "`isStandard` INTEGER NOT NULL DEFAULT 0, "+
                "`detectedRectLeft` REAL, `detectedRectTop` REAL, "+
                "`detectedRectRight` REAL, `detectedRectBottom` REAL, "+
                "`detectionConfidence` REAL, `croppedImageIdentifier` TEXT, "+
                "`pixelValueJson` TEXT, `fkAnalyteId` TEXT, "+
                "`isOutOfRange` INTEGER NOT NULL DEFAULT 0, `roleType` TEXT, "+
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, "+
                "FOREIGN KEY(`runId`) REFERENCES `detection_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE, "+
                "FOREIGN KEY(`fkAnalyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")

            // 拷贝数据
            database.execSQL(
                """
                INSERT INTO well_results_temp (
                    resultId, runId, projectId, wellIndex, 
                    predictedConcentration, trueConcentration, 
                    isStandard, detectedRectLeft, detectedRectTop, 
                    detectedRectRight, detectedRectBottom, 
                    detectionConfidence, croppedImageIdentifier, 
                    pixelValueJson, fkAnalyteId, isOutOfRange
                ) 
                SELECT 
                    resultId, runId, projectId, wellIndex, 
                    predictedConcentration, trueConcentration, 
                    isStandard, detectedRectLeft, detectedRectTop, 
                    detectedRectRight, detectedRectBottom, 
                    detectionConfidence, croppedImageIdentifier, 
                    pixelValueJson, fkAnalyteId, isOutOfRange
                FROM well_results
                """
            )

            // 删除旧表并重命名新表
            database.execSQL("DROP TABLE well_results")
            database.execSQL("ALTER TABLE well_results_temp RENAME TO well_results")

            // 重建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_well_results_projectId` ON `well_results` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_well_results_runId` ON `well_results` (`runId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_well_results_fkAnalyteId` ON `well_results` (`fkAnalyteId`)")
        }
    }
    
    // 版本1到版本2的迁移策略
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 创建 curve_models 表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `curve_models` (
                    `id` TEXT NOT NULL, 
                    `modelName` TEXT NOT NULL, 
                    `analyteId` TEXT NOT NULL, 
                    `reagentId` TEXT,
                    `functionType` TEXT NOT NULL, 
                    `pixelType` TEXT NOT NULL, 
                    `parametersJson` TEXT NOT NULL, 
                    `rSquared` REAL NOT NULL, 
                    `reliableRangeMin` REAL NOT NULL, 
                    `reliableRangeMax` REAL NOT NULL,
                    `createTime` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`reagentId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            
            // 创建 plate_layouts 表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `plate_layouts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                    `projectId` TEXT NOT NULL, 
                    `wellIndex` INTEGER NOT NULL, 
                    `analyteId` TEXT, 
                    `roleType` TEXT NOT NULL,
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            
            // 创建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_curve_models_analyteId` ON `curve_models` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_curve_models_reagentId` ON `curve_models` (`reagentId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_plate_layouts_projectId` ON `plate_layouts` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_plate_layouts_analyteId` ON `plate_layouts` (`analyteId`)")
        }
    }
    
    // 版本2到版本3的迁移策略 - 无结构变化，仅用于触发预填充
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 结构没有变化，只是版本号变更以触发重新创建
        }
    }
    
    // 版本3到版本4的迁移策略 - 添加实验模板表
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 创建实验模板表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `experiment_templates` (
                    `id` TEXT NOT NULL,
                    `templateName` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `reagentAntigenId` TEXT,
                    `reagentAntibodyId` TEXT,
                    `fkCurveModelId` TEXT NOT NULL,
                    `reliableRangeMin` REAL NOT NULL,
                    `reliableRangeMax` REAL NOT NULL,
                    `concentrationUnit` TEXT NOT NULL,
                    `defaultLayoutJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`reagentAntigenId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`reagentAntibodyId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`fkCurveModelId`) REFERENCES `curve_models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )
            
            // 创建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_templates_analyteId` ON `experiment_templates` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_templates_reagentAntigenId` ON `experiment_templates` (`reagentAntigenId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_templates_reagentAntibodyId` ON `experiment_templates` (`reagentAntibodyId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_templates_fkCurveModelId` ON `experiment_templates` (`fkCurveModelId`)")
        }
    }
    
    // 版本8到版本9的迁移策略
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 1. 添加项目的光源和光谱列配置
            database.execSQL("ALTER TABLE projects ADD COLUMN lightSource TEXT")
            database.execSQL("ALTER TABLE projects ADD COLUMN spectrumColumnCount INTEGER NOT NULL DEFAULT 1")
            database.execSQL("ALTER TABLE projects ADD COLUMN spectrumColumnMappingJson TEXT")

            // 2. 创建光谱标定表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `spectrum_calibrations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `columnIndex` INTEGER NOT NULL,
                    `roiRect` TEXT NOT NULL,
                    `calibrationType` TEXT NOT NULL,
                    `coefficients` TEXT NOT NULL,
                    `referencePoints` TEXT,
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_spectrum_calibrations_projectId` ON `spectrum_calibrations` (`projectId`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spectrum_calibrations_projectId_columnIndex` ON `spectrum_calibrations` (`projectId`, `columnIndex`)")

            // 3. 创建光谱结果表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `spectrum_results` (
                    `resultId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `projectId` TEXT NOT NULL,
                    `columnIndex` INTEGER NOT NULL,
                    `analyteId` TEXT,
                    `imagePath` TEXT NOT NULL,
                    `wavelengths` TEXT NOT NULL,
                    `intensities` TEXT NOT NULL,
                    `peakWavelength` REAL,
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_spectrum_results_projectId` ON `spectrum_results` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_spectrum_results_analyteId` ON `spectrum_results` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_spectrum_results_projectId_columnIndex` ON `spectrum_results` (`projectId`, `columnIndex`)")
        }
    }

    // 版本7到版本8的迁移策略
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 1. 创建临时表，包含新字段 fkCurveModelId
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `project_analytes_join_temp` (
                    `projectId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `maxConcentration` REAL,
                    `concentrationUnit` TEXT,
                    `fkTemplateId` TEXT,
                    `dlModelName` TEXT,
                    `fkCurveModelId` TEXT,
                    PRIMARY KEY(`projectId`, `analyteId`),
                    FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`fkTemplateId`) REFERENCES `experiment_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`fkCurveModelId`) REFERENCES `curve_models`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """
            )
            
            // 2. 从原表复制数据到临时表，新字段设为NULL
            database.execSQL(
                """
                INSERT INTO project_analytes_join_temp (
                    projectId, analyteId, maxConcentration, concentrationUnit, 
                    fkTemplateId, dlModelName, fkCurveModelId
                )
                SELECT 
                    projectId, analyteId, maxConcentration, concentrationUnit, 
                    fkTemplateId, dlModelName, NULL 
                FROM project_analytes_join
                """
            )
            
            // 3. 删除原表并重命名临时表
            database.execSQL("DROP TABLE project_analytes_join")
            database.execSQL("ALTER TABLE project_analytes_join_temp RENAME TO project_analytes_join")
            
            // 4. 重建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_projectId` ON `project_analytes_join` (`projectId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_analyteId` ON `project_analytes_join` (`analyteId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_fkTemplateId` ON `project_analytes_join` (`fkTemplateId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_project_analytes_join_fkCurveModelId` ON `project_analytes_join` (`fkCurveModelId`)")
            
            // 5. 从projects表中迁移fkCurveModelId到project_analytes_join表
            // 对于每个项目，查找其所有的关联分析物，并更新它们的fkCurveModelId
            database.execSQL(
                """
                UPDATE project_analytes_join
                SET fkCurveModelId = (
                    SELECT fkCurveModelId FROM projects 
                    WHERE projects.id = project_analytes_join.projectId
                )
                WHERE EXISTS (
                    SELECT 1 FROM projects 
                    WHERE projects.id = project_analytes_join.projectId AND projects.fkCurveModelId IS NOT NULL
                )
                """
            )
            
            // 6. 创建projects表临时表，移除字段
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `projects_temp` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `detectionMode` TEXT NOT NULL,
                    `recognitionType` TEXT NOT NULL,
                    `imageUri` TEXT NOT NULL,
                    `rows` INTEGER NOT NULL,
                    `columns` INTEGER NOT NULL,
                    `createTime` INTEGER NOT NULL,
                    `userId` TEXT NOT NULL,
                    `lastRunTimestamp` INTEGER,
                    `analysisMethod` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """
            )
            
            // 7. 复制projects表数据到临时表，排除已移除的字段
            database.execSQL(
                """
                INSERT INTO projects_temp (
                    id, name, detectionMode, recognitionType, imageUri, 
                    rows, columns, createTime, userId, lastRunTimestamp, 
                    analysisMethod
                )
                SELECT 
                    id, name, detectionMode, recognitionType, imageUri, 
                    rows, columns, createTime, userId, lastRunTimestamp, 
                    analysisMethod
                FROM projects
                """
            )
            
            // 8. 删除旧表，重命名新表
            database.execSQL("DROP TABLE projects")
            database.execSQL("ALTER TABLE projects_temp RENAME TO projects")
        }
    }
    
    /**
     * 提供分析物DAO
     */
    @Provides
    fun provideAnalyteDao(appDatabase: AppDatabase): AnalyteDao {
        return appDatabase.analyteDao()
    }
    
    /**
     * 提供试剂DAO
     */
    @Provides
    fun provideReagentDao(appDatabase: AppDatabase): ReagentDao {
        return appDatabase.reagentDao()
    }
    
    // 数据库预填充回调
    private val prepopulateCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            
            // 预填充分析物数据
            val analyteIds = mutableMapOf<String, String>()
            
            // 生成10个分析物的UUID并插入数据
            val analyteNames = listOf("CEA", "NSE", "CYFRA21-1", "ProGRP", "SCCA", "CA125", "GAGE7", "TSGF", "MAGE A1", "p53")
            
            analyteNames.forEachIndexed { index, name ->
                val analyteId = UUID.randomUUID().toString()
                val placeholder = "uuid_analyte_${String.format("%02d", index + 1)}"
                analyteIds[placeholder] = analyteId
                
                val sql = "INSERT INTO analytes (id, name) VALUES ('$analyteId', '$name')"
                db.execSQL(sql)
            }
            
            // 预填充试剂数据
            val reagentData = listOf(
                // Reagents for CEA (analyte_id: 'uuid_analyte_01')
                Triple("uuid_analyte_01", "1_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_01", "1_antigen_linc-bio", "antigen"),
                Triple("uuid_analyte_01", "1_antibody_Bioss", "antibody"),
                Triple("uuid_analyte_01", "1_antigen_Bioss", "antigen"),
                
                // Reagents for NSE (analyte_id: 'uuid_analyte_02')
                Triple("uuid_analyte_02", "2_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_02", "2_antigen_linc-bio", "antigen"),
                
                // Reagents for CYFRA21-1 (analyte_id: 'uuid_analyte_03')
                Triple("uuid_analyte_03", "3_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_03", "3_antigen_linc-bio", "antigen"),
                
                // Reagents for ProGRP (analyte_id: 'uuid_analyte_04')
                Triple("uuid_analyte_04", "4_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_04", "4_antigen_linc-bio", "antigen"),
                
                // Reagents for SCCA (analyte_id: 'uuid_analyte_05')
                Triple("uuid_analyte_05", "5_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_05", "5_antigen_linc-bio", "antigen"),
                
                // Reagents for CA125 (analyte_id: 'uuid_analyte_06')
                Triple("uuid_analyte_06", "6_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_06", "6_antigen_linc-bio", "antigen"),
                Triple("uuid_analyte_06", "6_antibody_Bioss", "antibody"),
                Triple("uuid_analyte_06", "6_antigen_Bioss", "antigen"),
                
                // Reagents for GAGE7 (analyte_id: 'uuid_analyte_07')
                Triple("uuid_analyte_07", "7_antibody_BIORBYT", "antibody"),
                Triple("uuid_analyte_07", "7_antigen_BIORBYT", "antigen"),
                
                // Reagents for TSGF (analyte_id: 'uuid_analyte_08')
                Triple("uuid_analyte_08", "8_antibody_Abbiotec", "antibody"),
                Triple("uuid_analyte_08", "8_antigen_Abbiotec", "antigen"),
                
                // Reagents for MAGE A1 (analyte_id: 'uuid_analyte_09')
                Triple("uuid_analyte_09", "9_antibody_BIORBYT", "antibody"),
                Triple("uuid_analyte_09", "9_antigen_BIORBYT", "antigen"),
                
                // Reagents for p53 (analyte_id: 'uuid_analyte_10')
                Triple("uuid_analyte_10", "10_antibody_linc-bio", "antibody"),
                Triple("uuid_analyte_10", "10_antigen_linc-bio", "antigen")
            )
            
            // 制造商和分子量数据
            val manufacturerData = mapOf(
                "1_antibody_linc-bio" to Pair("linc-bio", null),
                "1_antigen_linc-bio" to Pair("linc-bio", 200.0),
                "1_antibody_Bioss" to Pair("Bioss", null),
                "1_antigen_Bioss" to Pair("Bioss", 200.0),
                "2_antibody_linc-bio" to Pair("linc-bio", null),
                "2_antigen_linc-bio" to Pair("linc-bio", 78.0),
                "3_antibody_linc-bio" to Pair("linc-bio", null),
                "3_antigen_linc-bio" to Pair("linc-bio", 40.0),
                "4_antibody_linc-bio" to Pair("linc-bio", null),
                "4_antigen_linc-bio" to Pair("linc-bio", 14.0),
                "5_antibody_linc-bio" to Pair("linc-bio", null),
                "5_antigen_linc-bio" to Pair("linc-bio", 45.0),
                "6_antibody_linc-bio" to Pair("linc-bio", null),
                "6_antigen_linc-bio" to Pair("linc-bio", 200.0),
                "6_antibody_Bioss" to Pair("Bioss", null),
                "6_antigen_Bioss" to Pair("Bioss", 200.0),
                "7_antibody_BIORBYT" to Pair("BIORBYT", null),
                "7_antigen_BIORBYT" to Pair("BIORBYT", 13.0),
                "8_antibody_Abbiotec" to Pair("Abbiotec", null),
                "8_antigen_Abbiotec" to Pair("Abbiotec", 40.0),
                "9_antibody_BIORBYT" to Pair("BIORBYT", null),
                "9_antigen_BIORBYT" to Pair("BIORBYT", 44.0),
                "10_antibody_linc-bio" to Pair("linc-bio", null),
                "10_antigen_linc-bio" to Pair("linc-bio", 44.0)
            )
            
            // 单位数据 - 只有抗原类型有单位
            val unitData = mapOf(
                "1_antigen_linc-bio" to "ng/ml",
                "1_antigen_Bioss" to "μg/ml",
                "2_antigen_linc-bio" to "μg/ml",
                "3_antigen_linc-bio" to "μg/ml",
                "4_antigen_linc-bio" to "μg/ml",
                "5_antigen_linc-bio" to "μg/ml",
                "6_antigen_linc-bio" to "μg/ml",
                "6_antigen_Bioss" to "μg/ml",
                "7_antigen_BIORBYT" to "μg/ml",
                "9_antigen_BIORBYT" to "μg/ml",
                "10_antigen_linc-bio" to "μg/ml"
            )
            
            // 插入试剂数据
            reagentData.forEachIndexed { index, (analytePlaceholder, reagentName, reagentType) ->
                val reagentId = UUID.randomUUID().toString()
                val analyteId = analyteIds[analytePlaceholder] ?: return@forEachIndexed
                
                val manufacturerInfo = manufacturerData[reagentName] ?: Pair(null, null)
                val manufacturer = manufacturerInfo.first
                val molecularWeight = manufacturerInfo.second
                val unit = unitData[reagentName]
                
                val molecularWeightStr = if (molecularWeight != null) "$molecularWeight" else "NULL"
                val manufacturerStr = if (manufacturer != null) "'$manufacturer'" else "NULL"
                val unitStr = if (unit != null) "'$unit'" else "NULL"
                
                val sql = "INSERT INTO reagents (id, analyteId, reagentName, reagentType, manufacturer, unit, molecularWeight) " +
                          "VALUES ('$reagentId', '$analyteId', '$reagentName', '$reagentType', $manufacturerStr, $unitStr, $molecularWeightStr)"
                db.execSQL(sql)
            }
        }
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

    @Provides
    fun provideSpectrumDao(appDatabase: AppDatabase): SpectrumDao {
        return appDatabase.spectrumDao()
    }
    
    @Provides
    fun provideCurveModelDao(appDatabase: AppDatabase): CurveModelDao {
        return appDatabase.curveModelDao()
    }
    
    @Provides
    fun provideProjectAnalyteJoinDao(appDatabase: AppDatabase): ProjectAnalyteJoinDao {
        return appDatabase.projectAnalyteJoinDao()
    }
    
    @Provides
    fun provideExperimentTemplateDao(appDatabase: AppDatabase): ExperimentTemplateDao {
        return appDatabase.experimentTemplateDao()
    }
} 
