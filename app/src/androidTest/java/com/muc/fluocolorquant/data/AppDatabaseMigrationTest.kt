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
    }
}
