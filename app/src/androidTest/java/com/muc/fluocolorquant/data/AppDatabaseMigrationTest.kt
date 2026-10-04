package com.muc.fluocolorquant.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.data.migration.DatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 版本 9 到 10 的真实数据库迁移测试。
 *
 * 测试故意插入旧项目、检测运行、实验模板和单图光谱结果，确保新增多模态基础表时
 * 不会删除历史科研数据，也不会把旧单图光谱错误解释为 LSPR 配对结果。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate9To10_preservesLegacyDataAndCreatesDomainFoundation() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 9).apply {
            insertLegacyFixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            10,
            true,
            DatabaseMigrations.MIGRATION_9_10
        ).use { database ->
            assertLegacyProjectAndRun(database)
            assertLegacyTemplateDefaults(database)
            assertLegacySpectrumResultPreserved(database)
            assertNewTablesExist(database)
        }
    }

    @Test
    fun migrate10To11_preservesLegacyTemplateAndAllowsMultiAnalyteMasterRecord() {
        migrationHelper.createDatabase(TEMPLATE_DATABASE_NAME, 10).apply {
            insertVersion10TemplateFixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEMPLATE_DATABASE_NAME,
            11,
            true,
            DatabaseMigrations.MIGRATION_10_11
        ).use { database ->
            database.query(
                "SELECT templateName, analyteId, fkCurveModelId FROM experiment_templates " +
                    "WHERE id = 'template-v10'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("版本10旧模板", cursor.getString(0))
                assertEquals("analyte-v10", cursor.getString(1))
                assertEquals("curve-v10", cursor.getString(2))
            }

            assertTemplateCompatibilityColumnsAreNullable(database)

            // 新多分析物模板的主档不再伪造一个旧分析物和旧 CurveModel 外键。
            database.execSQL(
                """
                INSERT INTO experiment_templates (
                    id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                    fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                    defaultLayoutJson, createdAt, updatedAt, version, status,
                    carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                    inputProtocol, qcProfileJson, publishedAt
                ) VALUES (
                    'template-v11', '10×10双分析物模板', NULL, NULL, NULL,
                    NULL, 0.0, 0.0, '', NULL, 2, 2, 1, 'DRAFT',
                    NULL, 'COLORIMETRIC', 'GRID_SITES', NULL,
                    'ENDPOINT_ONLY', NULL, NULL
                )
                """.trimIndent()
            )
            database.query(
                "SELECT analyteId, fkCurveModelId FROM experiment_templates " +
                    "WHERE id = 'template-v11'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate11To12_preservesScientificSignalAndAddsQuantificationColumns() {
        migrationHelper.createDatabase(RESULT_DATABASE_NAME, 11).apply {
            insertVersion11SiteMeasurementFixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            RESULT_DATABASE_NAME,
            12,
            true,
            DatabaseMigrations.MIGRATION_11_12
        ).use { database ->
            database.query(
                """
                SELECT primaryFeatureName, primaryFeatureValue, backgroundValue,
                       signalToNoiseRatio, signalDetectable, qualityReliable,
                       concentrationValue, concentrationUnit, reliableRangeStatus,
                       modelSnapshotJson, quantificationQcJson
                FROM site_measurements WHERE id = 1
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("NET_FLUORESCENCE_INTENSITY", cursor.getString(0))
                assertEquals(42.5, cursor.getDouble(1), 0.0001)
                assertEquals(8.0, cursor.getDouble(2), 0.0001)
                assertEquals(12.0, cursor.getDouble(3), 0.0001)
                assertEquals(1, cursor.getInt(4))
                assertEquals(1, cursor.getInt(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.isNull(7))
                assertTrue(cursor.isNull(8))
                assertTrue(cursor.isNull(9))
                assertTrue(cursor.isNull(10))
            }
        }
    }

    @Test
    fun migrate12To13_addsTemplateBindingsCurveFingerprintAndSignalVersion() {
        migrationHelper.createDatabase(TEMPLATE_BINDING_DATABASE_NAME, 12).close()

        migrationHelper.runMigrationsAndValidate(
            TEMPLATE_BINDING_DATABASE_NAME,
            13,
            true,
            DatabaseMigrations.MIGRATION_12_13
        ).use { database ->
            val tables = mutableSetOf<String>()
            database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue("template_quantitation_bindings" in tables)

            val analysisColumns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`analysis_models`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) analysisColumns += cursor.getString(nameIndex)
            }
            assertTrue("contentFingerprint" in analysisColumns)

            val curveColumns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`curve_models`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) curveColumns += cursor.getString(nameIndex)
            }
            assertTrue("signalFeatureCode" in curveColumns)
            assertTrue("processorVersion" in curveColumns)
        }
    }

    @Test
    fun migrate13To14_addsAppendOnlyResultValidationRecords() {
        migrationHelper.createDatabase(VALIDATION_DATABASE_NAME, 13).close()

        migrationHelper.runMigrationsAndValidate(
            VALIDATION_DATABASE_NAME,
            14,
            true,
            DatabaseMigrations.MIGRATION_13_14
        ).use { database ->
            val columns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`result_validation_records`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(
                columns.containsAll(
                    setOf(
                        "validationId",
                        "runId",
                        "analyteId",
                        "revision",
                        "concentrationUnit",
                        "validationPointsJson",
                        "regressionResultJson",
                        "blandAltmanResultJson",
                        "processorVersion",
                        "inputFingerprint",
                        "createdAt"
                    )
                )
            )

            val indexes = mutableMapOf<String, Boolean>()
            database.query("PRAGMA index_list(`result_validation_records`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    indexes[cursor.getString(nameIndex)] = cursor.getInt(uniqueIndex) == 1
                }
            }
            assertEquals(false, indexes["index_result_validation_records_runId"])
            assertEquals(
                true,
                indexes["index_result_validation_records_runId_analyteId_revision"]
            )
        }
    }

    @Test
    fun migrate14To15_addsStructuredQuantificationIntervalColumns() {
        migrationHelper.createDatabase(VALIDATION_DATABASE_NAME, 14).close()

        migrationHelper.runMigrationsAndValidate(
            VALIDATION_DATABASE_NAME,
            15,
            true,
            DatabaseMigrations.MIGRATION_14_15
        ).use { database ->
            val columns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`site_measurements`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(
                columns.containsAll(
                    setOf(
                        "quantificationState",
                        "concentrationLowerBound",
                        "concentrationUpperBound",
                        "intervalConfidenceLevel",
                        "censoringDirection",
                        "quantificationVersion"
                    )
                )
            )
        }
    }

    @Test
    fun migrate15To16_preservesSpectrumResultAndLeavesLegacySnapshotUnknown() {
        migrationHelper.createDatabase(SPECTRUM_SNAPSHOT_DATABASE_NAME, 15).apply {
            insertVersion15SpectrumFixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            SPECTRUM_SNAPSHOT_DATABASE_NAME,
            16,
            true,
            DatabaseMigrations.MIGRATION_15_16
        ).use { database ->
            database.query(
                """
                SELECT wavelengths, intensities, processingConfigJson, processorVersion
                FROM spectrum_results WHERE projectId = 'project-v15'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[500.0,501.0]", cursor.getString(0))
                assertEquals("[0.1,0.8]", cursor.getString(1))
                // 迁移不能用设备当前设置伪造旧历史；null 会在领域层映射为固定 Legacy 配置。
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun migrate16To17_doesNotBackfillResultLightSourceFromMutableProject() {
        migrationHelper.createDatabase(SPECTRUM_LIGHT_SOURCE_DATABASE_NAME, 16).apply {
            insertVersion15SpectrumFixture()
            execSQL("UPDATE projects SET lightSource = 'HALOGEN' WHERE id = 'project-v15'")
            execSQL(
                """
                UPDATE spectrum_results
                SET processingConfigJson = '{"version":1}', processorVersion = 'test-v16'
                WHERE projectId = 'project-v15'
                """.trimIndent()
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            SPECTRUM_LIGHT_SOURCE_DATABASE_NAME,
            17,
            true,
            DatabaseMigrations.MIGRATION_16_17
        ).use { database ->
            database.query(
                """
                SELECT p.lightSource, r.processingConfigJson, r.processorVersion,
                       r.lightSourceSnapshot
                FROM projects p
                JOIN spectrum_results r ON r.projectId = p.id
                WHERE p.id = 'project-v15'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("HALOGEN", cursor.getString(0))
                assertEquals("{\"version\":1}", cursor.getString(1))
                assertEquals("test-v16", cursor.getString(2))
                // 项目字段是可变元数据，迁移不能把它伪装成旧结果生成时的采集快照。
                assertTrue(cursor.isNull(3))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun migrate17To18_startsNewScientificGenerationButPreservesIdentityCatalogs() {
        migrationHelper.createDatabase(SCIENTIFIC_GENERATION_DATABASE_NAME, 17).apply {
            insertVersion17ScientificGenerationFixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            SCIENTIFIC_GENERATION_DATABASE_NAME,
            18,
            true,
            DatabaseMigrations.MIGRATION_17_18
        ).use { database ->
            // 账户和基础目录是用户资产，不属于旧科研结果，升级后必须原样保留。
            assertEquals(1, database.rowCount("users"))
            assertEquals(1, database.rowCount("analytes"))
            assertEquals(1, database.rowCount("reagents"))

            // V2 从空的科研代际开始；默认载体/采集档案随后由生产打开回调重新播种。
            listOf(
                "projects",
                "detection_runs",
                "well_results",
                "spectrum_calibrations",
                "spectrum_results",
                "project_analytes_join",
                "experiment_templates",
                "template_analyte_configs",
                "template_site_assignments",
                "template_quantitation_bindings",
                "curve_models",
                "analysis_models",
                "standard_curve_definitions",
                "calibration_points",
                "deep_learning_model_definitions",
                "capture_artifacts",
                "site_measurements",
                "result_validation_records",
                "carrier_profiles",
                "acquisition_profiles"
            ).forEach { table ->
                assertEquals("迁移后 $table 应为空", 0, database.rowCount(table))
            }
        }
    }

    /** 构造同时包含用户资产与旧科研配置的 V17 数据，固定 V18 清退边界。 */
    private fun SupportSQLiteDatabase.insertVersion17ScientificGenerationFixture() {
        execSQL(
            "INSERT INTO users (id, username, password, email, profilePicUrl, createdAt) " +
                "VALUES (1, 'scientist', 'pbkdf2_sha256:test', NULL, NULL, 1)"
        )
        execSQL("INSERT INTO analytes (id, name) VALUES ('cea-v17', 'CEA-V17')")
        execSQL(
            "INSERT INTO reagents " +
                "(id, analyteId, reagentName, reagentType, manufacturer, molecularWeight, unit) " +
                "VALUES ('reagent-v17', 'cea-v17', 'Control', 'antigen', NULL, NULL, 'ng/mL')"
        )
        execSQL(
            """
            INSERT INTO projects (
                id, name, detectionMode, recognitionType, imageUri, rows, columns,
                lightSource, spectrumColumnCount, spectrumColumnMappingJson,
                createTime, userId, lastRunTimestamp, analysisMethod,
                templateId, templateVersion, templateSnapshotJson, overrideJson,
                projectBatch, sampleBatch
            ) VALUES (
                'project-v17', '旧科研项目', 'FLUORESCENCE', 'AUTO', '/private/old.png',
                10, 10, 'UV', 1, NULL, 1, '1', NULL, 'STANDARD_CURVE',
                NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO curve_models (
                id, name, function, pixelType, signalFeatureCode, processorVersion,
                parameters, metrics, dataPoints, createdAt, updatedAt
            ) VALUES (
                'curve-v17', '旧曲线', 'LINEAR', 'GRAY', NULL, NULL,
                '{}', NULL, NULL, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO experiment_templates (
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt, version, status,
                carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                inputProtocol, qcProfileJson, publishedAt, purpose, versionNote
            ) VALUES (
                'template-v17', '旧模板', 'cea-v17', 'reagent-v17', NULL,
                'curve-v17', 0.0, 100.0, 'ng/mL', NULL, 1, 1, 1, 'PUBLISHED',
                NULL, 'FLUORESCENCE', 'GRID_SITES', NULL,
                'ENDPOINT_ONLY', NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO analysis_models (
                id, name, modelType, analyteId, detectionMode, inputProtocol,
                primaryFeature, processorName, processorVersion,
                compatibleCarrierTypesJson, compatibleAcquisitionProfileIdsJson,
                concentrationUnit, reliableRangeMin, reliableRangeMax,
                validationMetricsJson, contentFingerprint, status, version, createdAt, updatedAt
            ) VALUES (
                'model-v17', '旧模型', 'STANDARD_CURVE', 'cea-v17', 'FLUORESCENCE',
                'ENDPOINT_ONLY', 'NET_FLUORESCENCE_INTENSITY', 'test', 'v1',
                NULL, NULL, 'ng/mL', 0.0, 100.0, NULL, NULL, 'PUBLISHED', 1, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO carrier_profiles (
                id, name, carrierType, rows, columns, siteShape,
                orientationMarkerJson, roiConfigJson, locatorConfigJson,
                status, version, createdAt, updatedAt
            ) VALUES (
                'carrier-v17', '旧载体', 'CUSTOM', 10, 10, 'CIRCLE',
                NULL, NULL, NULL, 'ACTIVE', 1, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO acquisition_profiles (
                id, name, supportedModesJson, compatibleCarrierTypesJson,
                deviceMatcherJson, opticalModuleName, fixtureId, cameraControlStrategy,
                cameraConstraintsJson, imageQcProfileJson, status, version, createdAt, updatedAt
            ) VALUES (
                'acquisition-v17', '旧采集', '[]', '[]', NULL, NULL, NULL, 'AUTO',
                NULL, NULL, 'ACTIVE', 1, 1, 1
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    /** 在版本 15 中构造真实的单通道历史结果，验证追加快照列不会改写原始科学数据。 */
    private fun SupportSQLiteDatabase.insertVersion15SpectrumFixture() {
        execSQL(
            """
            INSERT INTO projects (
                id, name, detectionMode, recognitionType, imageUri, rows, columns,
                lightSource, spectrumColumnCount, spectrumColumnMappingJson,
                createTime, userId, lastRunTimestamp, analysisMethod,
                templateId, templateVersion, templateSnapshotJson, overrideJson,
                projectBatch, sampleBatch
            ) VALUES (
                'project-v15', '版本15光谱项目', 'SPECTRUM', 'AUTO', '/spectrum.png',
                1, 1, NULL, 1, NULL, 1, 'user-v15', NULL, 'SIGNAL_ONLY',
                NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO spectrum_results (
                projectId, columnIndex, analyteId, imagePath,
                wavelengths, intensities, peakWavelength
            ) VALUES (
                'project-v15', 0, NULL, '/legacy/channel.png',
                '[500.0,501.0]', '[0.1,0.8]', 501.0
            )
            """.trimIndent()
        )
    }

    /** 在 Room 11 中写入一条新检测链的真实科学信号，验证迁移不会丢失已有数据。 */
    private fun SupportSQLiteDatabase.insertVersion11SiteMeasurementFixture() {
        execSQL("INSERT INTO analytes (id, name) VALUES ('analyte-v11', 'CEA')")
        execSQL(
            """
            INSERT INTO projects (
                id, name, detectionMode, recognitionType, imageUri, rows, columns,
                lightSource, spectrumColumnCount, spectrumColumnMappingJson,
                createTime, userId, lastRunTimestamp, analysisMethod,
                templateId, templateVersion, templateSnapshotJson, overrideJson,
                projectBatch, sampleBatch
            ) VALUES (
                'project-v11', 'Room11微流控项目', 'FLUORESCENCE', 'AUTO', '/input.png',
                10, 10, NULL, 1, NULL, 1, 'user-v11', NULL, 'STANDARD_CURVE',
                NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO detection_runs (
                runId, projectId, timestamp, detectionModelUsed, concentrationModelUsed,
                status, errorMessage, confThreshold, iouThreshold, wellsDetected,
                effectiveConfigSnapshotJson, acquisitionMetadataJson, processingVersionJson,
                frameQcJson, siteQcSummaryJson, configurationDeviationJson
            ) VALUES (
                'run-v11', 'project-v11', 2, 'PG-Grid', NULL,
                'SignalOnlyCompleted', NULL, NULL, NULL, 1,
                NULL, NULL, NULL, NULL, NULL, NULL
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO site_measurements (
                id, runId, siteIndex, analyteId, detectionMode,
                rawSignalJson, correctedSignalJson, primaryFeatureName, primaryFeatureValue,
                backgroundValue, signalToNoiseRatio, confidence, signalDetectable,
                qualityReliable, qcJson, processorName, processorVersion
            ) VALUES (
                1, 'run-v11', 0, 'analyte-v11', 'FLUORESCENCE',
                '{}', '{}', 'NET_FLUORESCENCE_INTENSITY', 42.5,
                8.0, 12.0, 0.98, 1, 1, '{}', 'fluorescence-photometry', 'v1'
            )
            """.trimIndent()
        )
    }

    /** 在版本 10 schema 中写入仍使用旧单分析物兼容字段的模板。 */
    private fun SupportSQLiteDatabase.insertVersion10TemplateFixture() {
        execSQL("INSERT INTO analytes (id, name) VALUES ('analyte-v10', 'CEA')")
        execSQL(
            """
            INSERT INTO curve_models (
                id, name, function, pixelType, parameters, metrics, dataPoints, createdAt, updatedAt
            ) VALUES (
                'curve-v10', '版本10曲线', 'linear', 'gray_luminosity',
                '{"a":1.0,"b":0.0}', NULL, NULL, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO experiment_templates (
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt, version, status,
                carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                inputProtocol, qcProfileJson, publishedAt
            ) VALUES (
                'template-v10', '版本10旧模板', 'analyte-v10', NULL, NULL,
                'curve-v10', 0.1, 100.0, 'ng/mL', NULL, 1, 1, 1, 'LEGACY',
                NULL, 'COLORIMETRIC', 'GRID_SITES', NULL,
                'ENDPOINT_ONLY', NULL, NULL
            )
            """.trimIndent()
        )
    }

    private fun assertTemplateCompatibilityColumnsAreNullable(
        database: SupportSQLiteDatabase
    ) {
        val notNullByColumn = mutableMapOf<String, Int>()
        database.query("PRAGMA table_info(experiment_templates)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                notNullByColumn[cursor.getString(nameIndex)] = cursor.getInt(notNullIndex)
            }
        }
        assertEquals(0, notNullByColumn["analyteId"])
        assertEquals(0, notNullByColumn["fkCurveModelId"])
        assertEquals(0, notNullByColumn["purpose"])
        assertEquals(0, notNullByColumn["versionNote"])
    }

    /** 在版本 9 schema 中写入一组满足外键约束的最小历史数据。 */
    private fun SupportSQLiteDatabase.insertLegacyFixture() {
        execSQL("INSERT INTO analytes (id, name) VALUES ('analyte-1', 'CEA')")
        execSQL(
            """
            INSERT INTO curve_models (
                id, name, function, pixelType, parameters, metrics, dataPoints, createdAt, updatedAt
            ) VALUES (
                'curve-1', '旧曲线', 'linear', 'gray_luminosity', '{"a":1.0,"b":0.0}',
                NULL, NULL, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO projects (
                id, name, detectionMode, recognitionType, imageUri, rows, columns,
                lightSource, spectrumColumnCount, spectrumColumnMappingJson,
                createTime, userId, lastRunTimestamp, analysisMethod
            ) VALUES (
                'project-1', '旧项目', 'SPECTRUM', 'AUTO', 'content://legacy', 1, 1,
                'WHITE_LED', 1, NULL, 1, 'user-1', NULL, 'CURVE_FIT'
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO detection_runs (
                runId, projectId, timestamp, detectionModelUsed, concentrationModelUsed,
                status, errorMessage, confThreshold, iouThreshold, wellsDetected
            ) VALUES (
                'run-1', 'project-1', 2, NULL, NULL, 'Completed', NULL, NULL, NULL, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO experiment_templates (
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt
            ) VALUES (
                'template-1', '旧模板', 'analyte-1', NULL, NULL,
                'curve-1', 0.0, 10.0, 'ng/mL', NULL, 1, 1
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO spectrum_results (
                projectId, columnIndex, analyteId, imagePath, wavelengths, intensities, peakWavelength
            ) VALUES (
                'project-1', 0, 'analyte-1', '/legacy/channel.png', '[500.0,501.0]', '[0.1,0.2]', 501.0
            )
            """.trimIndent()
        )
    }

    private fun assertLegacyProjectAndRun(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT templateSnapshotJson, overrideJson FROM projects WHERE id = 'project-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }

        database.query(
            "SELECT effectiveConfigSnapshotJson, frameQcJson FROM detection_runs WHERE runId = 'run-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
    }

    private fun assertLegacyTemplateDefaults(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT version, status, inputProtocol FROM experiment_templates WHERE id = 'template-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("LEGACY", cursor.getString(1))
            assertEquals("ENDPOINT_ONLY", cursor.getString(2))
        }
    }

    private fun assertLegacySpectrumResultPreserved(database: SupportSQLiteDatabase) {
        database.query("SELECT imagePath, peakWavelength FROM spectrum_results WHERE projectId = 'project-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("/legacy/channel.png", cursor.getString(0))
            assertEquals(501.0, cursor.getDouble(1), 0.0001)
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertNewTablesExist(database: SupportSQLiteDatabase) {
        val requiredTables = setOf(
            "carrier_profiles",
            "acquisition_profiles",
            "template_analyte_configs",
            "template_site_assignments",
            "analysis_models",
            "standard_curve_definitions",
            "calibration_points",
            "deep_learning_model_definitions",
            "capture_artifacts",
            "site_measurements"
        )
        val actualTables = mutableSetOf<String>()
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) actualTables += cursor.getString(0)
        }
        assertTrue(actualTables.containsAll(requiredTables))
    }

    private companion object {
        const val TEST_DATABASE_NAME = "multimodal-migration-test"
        const val TEMPLATE_DATABASE_NAME = "template-v11-migration-test"
        const val RESULT_DATABASE_NAME = "result-v12-migration-test"
        const val TEMPLATE_BINDING_DATABASE_NAME = "template-binding-v13-migration-test"
        const val VALIDATION_DATABASE_NAME = "result-validation-v14-migration-test"
        const val SPECTRUM_SNAPSHOT_DATABASE_NAME = "spectrum-snapshot-v16-migration-test"
        const val SPECTRUM_LIGHT_SOURCE_DATABASE_NAME =
            "spectrum-light-source-v17-migration-test"
        const val SCIENTIFIC_GENERATION_DATABASE_NAME =
            "scientific-generation-v18-migration-test"
    }
}
