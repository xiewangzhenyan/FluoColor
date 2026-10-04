package com.muc.fluocolorquant.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库迁移集合。
 *
 * 新迁移从 Hilt 模块中拆出，便于 MigrationTestHelper 直接引用和单独审查。
 */
object DatabaseMigrations {

    /**
     * 版本 9 → 10：建立多模态领域基础，同时完整保留旧项目和旧光谱数据。
     */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addProjectSnapshotColumns(db)
            addDetectionRunSnapshotColumns(db)
            addTemplateDomainColumns(db)
            createResourceProfileTables(db)
            createAnalysisModelTables(db)
            createTemplateChildTables(db)
            createCaptureAndMeasurementTables(db)
        }
    }

    /**
     * 版本 10 → 11：解除模板主档对单分析物和旧 CurveModel 的强制绑定。
     *
     * 新模板的多分析物、试剂和统一分析模型全部保存在模板子表；主档中的旧字段仅供
     * 历史孔板流程读取。迁移通过重建表改变 NOT NULL 和外键删除语义，不伪造占位数据。
     */
    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildExperimentTemplateMaster(db)
        }
    }

    /**
     * 版本 11 → 12：为新逐位点测量补充浓度反算和模型快照字段。
     *
     * 所有新增列均可空，因此旧的仅信号测量天然保持合法；迁移只追加列，不重建表、不
     * 复制数据，也不会改变现有主键、索引或外键，避免科研运行记录产生不必要风险。
     */
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `concentrationValue` REAL")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `concentrationUnit` TEXT")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `reliableRangeStatus` TEXT")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `modelSnapshotJson` TEXT")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `quantificationQcJson` TEXT")
        }
    }

    /**
     * 版本 12 → 13：增加标准曲线内容指纹和实验模板冻结定量绑定。
     *
     * 旧模板没有绑定记录时继续沿用原有 analysisModelId 兼容读取；新模板会同时保存资源
     * 关联和完整摘要，因此曲线资源删除后仍可复现实验方案。旧 CurveModel 同时增加两个
     * 可空字段，用于让新建 96 孔板曲线冻结 V2 信号编码，而历史曲线继续读取 Legacy 键。
     */
    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `analysis_models` ADD COLUMN `contentFingerprint` TEXT")
            db.execSQL("ALTER TABLE `curve_models` ADD COLUMN `signalFeatureCode` TEXT")
            db.execSQL("ALTER TABLE `curve_models` ADD COLUMN `processorVersion` TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_analysis_models_contentFingerprint` " +
                    "ON `analysis_models` (`contentFingerprint`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `template_quantitation_bindings` (
                    `id` TEXT NOT NULL,
                    `templateId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `method` TEXT NOT NULL,
                    `sourceResourceId` TEXT,
                    `resourceSnapshotJson` TEXT NOT NULL,
                    `contentFingerprint` TEXT NOT NULL,
                    `processorName` TEXT NOT NULL,
                    `processorVersion` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`templateId`) REFERENCES `experiment_templates`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`sourceResourceId`) REFERENCES `analysis_models`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_template_quantitation_bindings_templateId` " +
                    "ON `template_quantitation_bindings` (`templateId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_template_quantitation_bindings_analyteId` " +
                    "ON `template_quantitation_bindings` (`analyteId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_template_quantitation_bindings_sourceResourceId` " +
                    "ON `template_quantitation_bindings` (`sourceResourceId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_template_quantitation_bindings_templateId_analyteId` " +
                    "ON `template_quantitation_bindings` (`templateId`, `analyteId`)"
            )
        }
    }

    /**
     * 版本 13 → 14：新增运行后的预测精度验证修订表。
     *
     * 验证记录与检测运行级联关联，但不会回写 DetectionRun 或 SiteMeasurement；用户重新
     * 输入参考浓度时只追加新修订，从而保持原始科研结果不可变。
     */
    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `result_validation_records` (
                    `validationId` TEXT NOT NULL,
                    `runId` TEXT NOT NULL,
                    `analyteId` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `concentrationUnit` TEXT NOT NULL,
                    `validationPointsJson` TEXT NOT NULL,
                    `regressionResultJson` TEXT NOT NULL,
                    `blandAltmanResultJson` TEXT NOT NULL,
                    `processorVersion` TEXT NOT NULL,
                    `inputFingerprint` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`validationId`),
                    FOREIGN KEY(`runId`) REFERENCES `detection_runs`(`runId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_result_validation_records_runId` " +
                    "ON `result_validation_records` (`runId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_result_validation_records_runId_analyteId_revision` " +
                    "ON `result_validation_records` (`runId`, `analyteId`, `revision`)"
            )
        }
    }

    /**
     * 版本 14 → 15：为可信扩展估计、浓度区间和单侧删失增加结构化逐孔字段。
     *
     * 所有列均可空，旧运行无需推断不存在的区间或删失方向；迁移只追加列，不重建
     * `site_measurements`，从而保持既有科研运行、主键、外键和附件引用完全不变。
     */
    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `quantificationState` TEXT")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `concentrationLowerBound` REAL")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `concentrationUpperBound` REAL")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `intervalConfidenceLevel` REAL")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `censoringDirection` TEXT")
            db.execSQL("ALTER TABLE `site_measurements` ADD COLUMN `quantificationVersion` TEXT")
        }
    }

    /**
     * 版本 15 → 16：为每条光谱结果增加处理参数与算法版本快照。
     *
     * 旧记录保持 null，读取时由领域层明确映射为固定 Legacy 配置；迁移阶段不能拿安装设备
     * 当前的 DataStore 设置回填，否则同一数据库在不同设备上的历史解释会不一致。
     */
    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `spectrum_results` ADD COLUMN `processingConfigJson` TEXT")
            db.execSQL("ALTER TABLE `spectrum_results` ADD COLUMN `processorVersion` TEXT")
        }
    }

    /**
     * 版本 16 → 17：为光谱结果增加项目采集光源快照。
     *
     * 旧结果保持 null，不能拿 `projects.lightSource` 回填：项目元数据可能在结果生成后被修改，
     * 回填会把“当前项目值”伪装成“当时采集值”，破坏历史结果的可追溯性。
     */
    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `spectrum_results` ADD COLUMN `lightSourceSnapshot` TEXT")
        }
    }

    /**
     * 版本 17 → 18：开始“完整运行快照 + 定量 V2”科研数据代际。
     *
     * 用户已经明确选择重新发布后不保留旧项目、历史运行、曲线、模板和载体配置。这里
     * 采用显式表级清退而不是 destructive migration：账户、分析物、试剂以及数据库外的
     * 语言/主题等普通设置均被保留。删除顺序从子表到主表，既满足外键约束，也让迁移测试
     * 可以精确证明清理范围；默认载体和采集档案会在数据库打开回调中重新幂等播种。
     */
    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 运行与项目数据：先删除全部子资源，禁止依赖某台设备是否启用了外键级联。
            db.execSQL("DELETE FROM `result_validation_records`")
            db.execSQL("DELETE FROM `capture_artifacts`")
            db.execSQL("DELETE FROM `site_measurements`")
            db.execSQL("DELETE FROM `well_results`")
            db.execSQL("DELETE FROM `spectrum_results`")
            db.execSQL("DELETE FROM `spectrum_calibrations`")
            db.execSQL("DELETE FROM `detection_runs`")
            db.execSQL("DELETE FROM `project_analytes_join`")
            db.execSQL("DELETE FROM `projects`")

            // 模板与定量绑定必须整体清退，避免新项目继续引用缺少 V2 快照语义的旧方案。
            db.execSQL("DELETE FROM `template_quantitation_bindings`")
            db.execSQL("DELETE FROM `template_site_assignments`")
            db.execSQL("DELETE FROM `template_analyte_configs`")
            db.execSQL("DELETE FROM `experiment_templates`")

            // 旧曲线和模型没有完整量程/逐孔状态快照，不能进入新数据代际的自动匹配。
            db.execSQL("DELETE FROM `deep_learning_model_definitions`")
            db.execSQL("DELETE FROM `calibration_points`")
            db.execSQL("DELETE FROM `standard_curve_definitions`")
            db.execSQL("DELETE FROM `analysis_models`")
            db.execSQL("DELETE FROM `curve_models`")

            // 载体/采集档案可能携带旧路由和定位 JSON；清理后由 V2 默认资源重新播种。
            db.execSQL("DELETE FROM `carrier_profiles`")
            db.execSQL("DELETE FROM `acquisition_profiles`")
        }
    }

    /**
     * 版本 18 → 19：新增比色—荧光双模态判定修订表。
     *
     * 判定记录同时关联比色运行与荧光运行，任一侧删除即级联清理；记录只追加修订，
     * 不回写 DetectionRun 或 SiteMeasurement，两次运行冻结的浓度保持不变。
     */
    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dual_modal_adjudication_records` (
                    `adjudicationId` TEXT NOT NULL,
                    `colorimetricRunId` TEXT NOT NULL,
                    `fluorescenceRunId` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `revoked` INTEGER NOT NULL,
                    `ruleVersion` TEXT NOT NULL,
                    `thresholdsJson` TEXT NOT NULL,
                    `readingsJson` TEXT NOT NULL,
                    `inputFingerprint` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`adjudicationId`),
                    FOREIGN KEY(`colorimetricRunId`) REFERENCES `detection_runs`(`runId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`fluorescenceRunId`) REFERENCES `detection_runs`(`runId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dual_modal_adjudication_records_colorimetricRunId` " +
                    "ON `dual_modal_adjudication_records` (`colorimetricRunId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dual_modal_adjudication_records_fluorescenceRunId` " +
                    "ON `dual_modal_adjudication_records` (`fluorescenceRunId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_dual_modal_adjudication_records_colorimetricRunId_fluorescenceRunId_revision` " +
                    "ON `dual_modal_adjudication_records` (`colorimetricRunId`, `fluorescenceRunId`, `revision`)"
            )
        }
    }

    /**
     * 重建模板主表并保持所有外部引用仍指向 `experiment_templates`。
     *
     * `legacy_alter_table` 防止 SQLite 在旧表改名时把子表外键同步改到临时表名；新表
     * 建好并复制完成后才删除旧表，避免模板分析物、位点和项目关联被级联清理。
     */
    private fun rebuildExperimentTemplateMaster(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA legacy_alter_table = ON")
        db.execSQL(
            "ALTER TABLE `experiment_templates` RENAME TO `experiment_templates_v10_legacy`"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `experiment_templates` (
                `id` TEXT NOT NULL,
                `templateName` TEXT NOT NULL,
                `analyteId` TEXT,
                `reagentAntigenId` TEXT,
                `reagentAntibodyId` TEXT,
                `fkCurveModelId` TEXT,
                `reliableRangeMin` REAL NOT NULL,
                `reliableRangeMax` REAL NOT NULL,
                `concentrationUnit` TEXT NOT NULL,
                `defaultLayoutJson` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `version` INTEGER NOT NULL DEFAULT 1,
                `status` TEXT NOT NULL DEFAULT 'DRAFT',
                `carrierProfileId` TEXT,
                `detectionMode` TEXT,
                `readoutLayout` TEXT,
                `acquisitionProfileId` TEXT,
                `inputProtocol` TEXT NOT NULL DEFAULT 'ENDPOINT_ONLY',
                `qcProfileJson` TEXT,
                `publishedAt` INTEGER,
                `purpose` TEXT,
                `versionNote` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`reagentAntigenId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`reagentAntibodyId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`fkCurveModelId`) REFERENCES `curve_models`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `experiment_templates` (
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt, version, status,
                carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                inputProtocol, qcProfileJson, publishedAt, purpose, versionNote
            )
            SELECT
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt, version, status,
                carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                inputProtocol, qcProfileJson, publishedAt, NULL, NULL
            FROM `experiment_templates_v10_legacy`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `experiment_templates_v10_legacy`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_templates_analyteId` " +
                "ON `experiment_templates` (`analyteId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_templates_reagentAntigenId` " +
                "ON `experiment_templates` (`reagentAntigenId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_templates_reagentAntibodyId` " +
                "ON `experiment_templates` (`reagentAntibodyId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_templates_fkCurveModelId` " +
                "ON `experiment_templates` (`fkCurveModelId`)"
        )
        db.execSQL("PRAGMA legacy_alter_table = OFF")
    }

    /** 项目只增加可空快照字段，旧记录自然保持 null，不推断不存在的模板。 */
    private fun addProjectSnapshotColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN templateId TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN templateVersion INTEGER")
        db.execSQL("ALTER TABLE projects ADD COLUMN templateSnapshotJson TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN overrideJson TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN projectBatch TEXT")
        db.execSQL("ALTER TABLE projects ADD COLUMN sampleBatch TEXT")
    }

    /** 运行级配置和 QC 快照均为可空，避免伪造旧检测记录没有保存的信息。 */
    private fun addDetectionRunSnapshotColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN effectiveConfigSnapshotJson TEXT")
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN acquisitionMetadataJson TEXT")
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN processingVersionJson TEXT")
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN frameQcJson TEXT")
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN siteQcSummaryJson TEXT")
        db.execSQL("ALTER TABLE detection_runs ADD COLUMN configurationDeviationJson TEXT")
    }

    /**
     * 扩展旧模板主表。
     *
     * SQLite 增加非空列必须提供默认值；新增模板默认 DRAFT，但历史模板随后明确改为 LEGACY。
     */
    private fun addTemplateDomainColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT'")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN carrierProfileId TEXT")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN detectionMode TEXT")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN readoutLayout TEXT")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN acquisitionProfileId TEXT")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN inputProtocol TEXT NOT NULL DEFAULT 'ENDPOINT_ONLY'")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN qcProfileJson TEXT")
        db.execSQL("ALTER TABLE experiment_templates ADD COLUMN publishedAt INTEGER")
        db.execSQL("UPDATE experiment_templates SET status = 'LEGACY', version = 1, inputProtocol = 'ENDPOINT_ONLY'")
    }

    /** 创建载体和采集设备两类版本化资源表。 */
    private fun createResourceProfileTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `carrier_profiles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `carrierType` TEXT NOT NULL,
                `rows` INTEGER NOT NULL,
                `columns` INTEGER NOT NULL,
                `siteShape` TEXT NOT NULL,
                `orientationMarkerJson` TEXT,
                `roiConfigJson` TEXT,
                `locatorConfigJson` TEXT,
                `status` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_carrier_profiles_name_version` ON `carrier_profiles` (`name`, `version`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `acquisition_profiles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `supportedModesJson` TEXT NOT NULL,
                `compatibleCarrierTypesJson` TEXT NOT NULL,
                `deviceMatcherJson` TEXT,
                `opticalModuleName` TEXT,
                `fixtureId` TEXT,
                `cameraControlStrategy` TEXT NOT NULL,
                `cameraConstraintsJson` TEXT,
                `imageQcProfileJson` TEXT,
                `status` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_acquisition_profiles_name_version` ON `acquisition_profiles` (`name`, `version`)")
    }

    /** 创建通用分析模型、标准曲线原始点和深度学习文件定义。 */
    private fun createAnalysisModelTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `analysis_models` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `modelType` TEXT NOT NULL,
                `analyteId` TEXT NOT NULL,
                `detectionMode` TEXT NOT NULL,
                `inputProtocol` TEXT NOT NULL,
                `primaryFeature` TEXT NOT NULL,
                `processorName` TEXT NOT NULL,
                `processorVersion` TEXT NOT NULL,
                `compatibleCarrierTypesJson` TEXT,
                `compatibleAcquisitionProfileIdsJson` TEXT,
                `concentrationUnit` TEXT NOT NULL,
                `reliableRangeMin` REAL NOT NULL,
                `reliableRangeMax` REAL NOT NULL,
                `validationMetricsJson` TEXT,
                `status` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_models_analyteId` ON `analysis_models` (`analyteId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_analysis_models_name_version` ON `analysis_models` (`name`, `version`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_models_detectionMode_inputProtocol_primaryFeature` ON `analysis_models` (`detectionMode`, `inputProtocol`, `primaryFeature`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `standard_curve_definitions` (
                `analysisModelId` TEXT NOT NULL,
                `fittingFunction` TEXT NOT NULL,
                `parametersJson` TEXT NOT NULL,
                `monotonicDirection` TEXT NOT NULL,
                `inverseRuleJson` TEXT,
                `lod` REAL,
                `loq` REAL,
                PRIMARY KEY(`analysisModelId`),
                FOREIGN KEY(`analysisModelId`) REFERENCES `analysis_models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `calibration_points` (
                `id` TEXT NOT NULL,
                `analysisModelId` TEXT NOT NULL,
                `concentration` REAL NOT NULL,
                `signalValue` REAL NOT NULL,
                `repeatIndex` INTEGER NOT NULL,
                `runId` TEXT,
                `baselineMeasurementId` INTEGER,
                `endpointMeasurementId` INTEGER,
                `batchId` TEXT,
                `excluded` INTEGER NOT NULL,
                `exclusionReason` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`analysisModelId`) REFERENCES `analysis_models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calibration_points_analysisModelId` ON `calibration_points` (`analysisModelId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calibration_points_analysisModelId_concentration_repeatIndex` ON `calibration_points` (`analysisModelId`, `concentration`, `repeatIndex`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `deep_learning_model_definitions` (
                `analysisModelId` TEXT NOT NULL,
                `modelFileName` TEXT NOT NULL,
                `checksumSha256` TEXT NOT NULL,
                `inputWidth` INTEGER NOT NULL,
                `inputHeight` INTEGER NOT NULL,
                `normalizationJson` TEXT NOT NULL,
                `trainingDataVersion` TEXT NOT NULL,
                `metadataJson` TEXT,
                PRIMARY KEY(`analysisModelId`),
                FOREIGN KEY(`analysisModelId`) REFERENCES `analysis_models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    /** 创建模板多分析物和通用位点布局子表。 */
    private fun createTemplateChildTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `template_analyte_configs` (
                `id` TEXT NOT NULL,
                `templateId` TEXT NOT NULL,
                `analyteId` TEXT NOT NULL,
                `reagentAntigenId` TEXT,
                `reagentAntibodyId` TEXT,
                `analysisModelId` TEXT,
                `concentrationUnit` TEXT NOT NULL,
                `reliableRangeMin` REAL,
                `reliableRangeMax` REAL,
                `displayOrder` INTEGER NOT NULL,
                `displayConfigJson` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`templateId`) REFERENCES `experiment_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(`reagentAntigenId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`reagentAntibodyId`) REFERENCES `reagents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`analysisModelId`) REFERENCES `analysis_models`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_analyte_configs_templateId` ON `template_analyte_configs` (`templateId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_analyte_configs_analyteId` ON `template_analyte_configs` (`analyteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_analyte_configs_reagentAntigenId` ON `template_analyte_configs` (`reagentAntigenId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_analyte_configs_reagentAntibodyId` ON `template_analyte_configs` (`reagentAntibodyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_analyte_configs_analysisModelId` ON `template_analyte_configs` (`analysisModelId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_template_analyte_configs_templateId_analyteId` ON `template_analyte_configs` (`templateId`, `analyteId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `template_site_assignments` (
                `id` TEXT NOT NULL,
                `templateId` TEXT NOT NULL,
                `rowIndex` INTEGER NOT NULL,
                `columnIndex` INTEGER NOT NULL,
                `analyteId` TEXT,
                `roleType` TEXT NOT NULL,
                `standardConcentration` REAL,
                `repeatGroup` TEXT,
                `defaultSampleSlot` TEXT,
                `referenceScope` TEXT,
                `enabled` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`templateId`) REFERENCES `experiment_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_site_assignments_templateId` ON `template_site_assignments` (`templateId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_site_assignments_analyteId` ON `template_site_assignments` (`analyteId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_template_site_assignments_templateId_rowIndex_columnIndex` ON `template_site_assignments` (`templateId`, `rowIndex`, `columnIndex`)")
    }

    /** 创建通用采集附件和新逐位点科学测量表。 */
    private fun createCaptureAndMeasurementTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `capture_artifacts` (
                `id` TEXT NOT NULL,
                `runId` TEXT NOT NULL,
                `captureRole` TEXT NOT NULL,
                `originalPath` TEXT NOT NULL,
                `derivedPath` TEXT,
                `capturedAt` INTEGER NOT NULL,
                `operatorId` TEXT,
                `actualMetadataJson` TEXT,
                `profileSnapshotJson` TEXT,
                `imageQcJson` TEXT,
                `checksumSha256` TEXT,
                `pairingKey` TEXT,
                `locked` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `supersedesArtifactId` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`runId`) REFERENCES `detection_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_artifacts_runId` ON `capture_artifacts` (`runId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_artifacts_runId_captureRole_revision` ON `capture_artifacts` (`runId`, `captureRole`, `revision`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_artifacts_pairingKey` ON `capture_artifacts` (`pairingKey`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `site_measurements` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `runId` TEXT NOT NULL,
                `siteIndex` INTEGER NOT NULL,
                `analyteId` TEXT,
                `detectionMode` TEXT NOT NULL,
                `rawSignalJson` TEXT NOT NULL,
                `correctedSignalJson` TEXT,
                `primaryFeatureName` TEXT NOT NULL,
                `primaryFeatureValue` REAL,
                `backgroundValue` REAL,
                `signalToNoiseRatio` REAL,
                `confidence` REAL,
                `signalDetectable` INTEGER NOT NULL,
                `qualityReliable` INTEGER NOT NULL,
                `qcJson` TEXT,
                `processorName` TEXT NOT NULL,
                `processorVersion` TEXT NOT NULL,
                FOREIGN KEY(`runId`) REFERENCES `detection_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`analyteId`) REFERENCES `analytes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_site_measurements_runId` ON `site_measurements` (`runId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_site_measurements_analyteId` ON `site_measurements` (`analyteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_site_measurements_runId_siteIndex_analyteId` ON `site_measurements` (`runId`, `siteIndex`, `analyteId`)")
    }
}
