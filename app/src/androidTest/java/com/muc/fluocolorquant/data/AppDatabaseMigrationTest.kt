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
    }
}
