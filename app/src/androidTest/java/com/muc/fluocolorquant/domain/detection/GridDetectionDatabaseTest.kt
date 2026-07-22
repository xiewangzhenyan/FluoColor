package com.muc.fluocolorquant.domain.detection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepositoryImpl
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Room 外键和事务验证新检测运行不会留下半成品。 */
@RunWith(AndroidJUnit4::class)
class GridDetectionDatabaseTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: GridDetectionRunRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = GridDetectionRunRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `十乘十运行原子保存运行附件和一百个位点`() = runBlocking {
        database.projectDao().insertProject(project("project-success"))
        val bundle = persistenceBundle(
            projectId = "project-success",
            runId = "run-success",
            measurements = List(100) { index -> measurement("run-success", index) }
        )

        repository.save(bundle)

        assertNotNull(database.detectionRunDao().getDetectionRunById("run-success"))
        assertEquals(
            1,
            database.captureArtifactDao().getByRole("run-success", CaptureRole.ENDPOINT.code).size
        )
        assertEquals(100, database.siteMeasurementDao().getByRun("run-success").size)
    }

    @Test
    fun `终点图与九张处理证据在同一事务保存`() = runBlocking {
        database.projectDao().insertProject(project("project-evidence"))
        val diagnostics = CaptureRole.entries
            .filter(CaptureRole::isProcessingEvidence)
            .mapIndexed { index, role ->
                CaptureArtifact(
                    id = "evidence-$index",
                    runId = "run-evidence",
                    captureRole = role.code,
                    originalPath = "/data/evidence-$index.png",
                    capturedAt = Date(2_000L),
                    locked = true,
                    revision = index + 1
                )
            }

        repository.save(
            persistenceBundle(
                projectId = "project-evidence",
                runId = "run-evidence",
                measurements = emptyList(),
                diagnosticArtifacts = diagnostics
            )
        )

        val artifacts = database.captureArtifactDao().getByRun("run-evidence")
        assertEquals(10, artifacts.size)
        assertEquals(9, artifacts.count { CaptureRole.fromCode(it.captureRole)?.isProcessingEvidence == true })
    }

    @Test
    fun `位点外键失败时运行和附件一并回滚`() = runBlocking {
        database.projectDao().insertProject(project("project-failure"))
        val invalidMeasurements = List(10) { index ->
            measurement("run-failure", index).copy(
                analyteId = if (index == 9) "missing-analyte" else null
            )
        }

        val failure = runCatching {
            repository.save(
                persistenceBundle(
                    projectId = "project-failure",
                    runId = "run-failure",
                    measurements = invalidMeasurements
                )
            )
        }

        assertTrue(failure.isFailure)
        assertNull(database.detectionRunDao().getDetectionRunById("run-failure"))
        assertTrue(
            database.captureArtifactDao()
                .getByRole("run-failure", CaptureRole.ENDPOINT.code)
                .isEmpty()
        )
        assertTrue(database.siteMeasurementDao().getByRun("run-failure").isEmpty())
    }

    private fun project(id: String): Project {
        return Project(
            id = id,
            name = id,
            detectionMode = "FLUORESCENCE",
            recognitionType = "AUTO",
            imageUri = "content://test/endpoint.png",
            rows = 10,
            columns = 10,
            createTime = Date(1_000L),
            userId = "test-user",
            lastRunTimestamp = null,
            analysisMethod = "TEMPLATE_MODEL"
        )
    }

    private fun persistenceBundle(
        projectId: String,
        runId: String,
        measurements: List<SiteMeasurement>,
        diagnosticArtifacts: List<CaptureArtifact> = emptyList()
    ): GridDetectionPersistenceBundle {
        val run = DetectionRun(
            runId = runId,
            projectId = projectId,
            timestamp = Date(2_000L),
            detectionModelUsed = "OpenCV PG-Grid 2.1.0",
            concentrationModelUsed = null,
            status = "Completed",
            errorMessage = null,
            confThreshold = null,
            iouThreshold = null,
            wellsDetected = measurements.size,
            effectiveConfigSnapshotJson = "{}",
            processingVersionJson = "{}"
        )
        val artifact = CaptureArtifact(
            id = "$runId-endpoint",
            runId = runId,
            captureRole = CaptureRole.ENDPOINT.code,
            originalPath = "content://test/$runId.png",
            capturedAt = Date(2_000L),
            locked = true
        )
        return GridDetectionPersistenceBundle(
            run = run,
            endpointArtifact = artifact,
            measurements = measurements,
            diagnosticArtifacts = diagnosticArtifacts
        )
    }

    private fun measurement(runId: String, siteIndex: Int): SiteMeasurement {
        return SiteMeasurement(
            runId = runId,
            siteIndex = siteIndex,
            analyteId = null,
            detectionMode = "FLUORESCENCE",
            rawSignalJson = "{\"green\":100.0}",
            correctedSignalJson = "{\"net\":80.0}",
            primaryFeatureName = "NET_FLUORESCENCE_INTENSITY",
            primaryFeatureValue = 80.0,
            backgroundValue = 20.0,
            signalToNoiseRatio = 10.0,
            confidence = 0.95,
            signalDetectable = true,
            qualityReliable = true,
            qcJson = "{\"flags\":[]}",
            processorName = "fluorescence-photometry",
            processorVersion = "v1"
        )
    }
}
