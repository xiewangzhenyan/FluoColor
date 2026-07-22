package com.muc.fluocolorquant.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.ArrayResultRepositoryImpl
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阵列结果仓库的真实 Room 事务测试。
 *
 * 项目中的 overrideJson 故意写成后来修改的值，断言结果仍使用 DetectionRun 冻结值，
 * 防止历史结果随着项目编辑而漂移。
 */
@RunWith(AndroidJUnit4::class)
class ArrayResultRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ArrayResultRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repository = ArrayResultRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `仓库在单事务中从运行快照重建结果且不读取当前项目覆盖`() = runBlocking {
        val fixture = fixture()
        database.analyteDao().insertAnalyte(fixture.analyte)
        database.projectDao().insertProject(fixture.project)
        database.detectionRunDao().insertDetectionRun(fixture.run)
        database.captureArtifactDao().insert(fixture.artifact)
        database.siteMeasurementDao().insertAll(listOf(fixture.measurement))

        assertTrue(repository.hasNewArrayResult(RUN_ID))
        val result = repository.loadSnapshot(RUN_ID)

        assertTrue(result is ArrayResultLoadResult.Success)
        val snapshot = (result as ArrayResultLoadResult.Success).snapshot
        assertEquals(4, snapshot.sites.size)
        assertEquals("冻结样本", snapshot.sites.first().sampleSlot)
        assertEquals("content://frozen-endpoint", snapshot.artifacts.single().originalPath)
        assertEquals(1, snapshot.sites.first().measurements.size)
        assertEquals(12.5, snapshot.sites.first().measurements.single().concentrationValue)
    }

    @Test
    fun `没有新位点测量时结果网关判断为旧结果`() = runBlocking {
        val fixture = fixture()
        database.projectDao().insertProject(fixture.project)
        database.detectionRunDao().insertDetectionRun(fixture.run)

        assertFalse(repository.hasNewArrayResult(RUN_ID))
    }

    @Test
    fun `运行不存在时返回稳定错误`() = runBlocking {
        val result = repository.loadSnapshot("missing-run")

        assertEquals(
            ArrayResultErrorCode.RUN_NOT_FOUND,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `项目运行历史按时间倒序且不会覆盖旧运行`() = runBlocking {
        val fixture = fixture()
        database.projectDao().insertProject(fixture.project)
        database.detectionRunDao().insertDetectionRun(
            fixture.run.copy(runId = "run-older", timestamp = Date(1_000L))
        )
        database.detectionRunDao().insertDetectionRun(
            fixture.run.copy(runId = "run-newer", timestamp = Date(3_000L))
        )

        val runs = repository.getProjectRuns(PROJECT_ID)

        assertEquals(listOf("run-newer", "run-older"), runs.map { it.runId })
    }

    private fun fixture(): Fixture {
        val analyte = Analyte(ANALYTE_ID, "CEA")
        val snapshot = snapshot(analyte)
        val project = Project(
            id = PROJECT_ID,
            name = "Room阵列结果",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://project-current",
            rows = 2,
            columns = 2,
            createTime = Date(1_000L),
            userId = "user-id",
            lastRunTimestamp = Date(2_000L),
            analysisMethod = "CURVE_FIT",
            templateId = TEMPLATE_ID,
            templateVersion = 1,
            templateSnapshotJson = "{\"currentProjectSnapshotMustNotBeRead\":true}",
            overrideJson = TemplateProjectOverrideCodec.encode(
                TemplateProjectOverrideSnapshot(
                    sampleSlotMapping = mapOf("R01C01" to "后来修改的样本")
                )
            )
        )
        val frozenOverride = TemplateProjectOverrideCodec.encode(
            TemplateProjectOverrideSnapshot(
                sampleSlotMapping = mapOf("R01C01" to "冻结样本")
            )
        )
        val run = DetectionRun(
            runId = RUN_ID,
            projectId = PROJECT_ID,
            timestamp = Date(2_000L),
            detectionModelUsed = "OpenCV PG-Grid 2.1.0",
            concentrationModelUsed = "{}",
            status = "Completed",
            errorMessage = null,
            confThreshold = null,
            iouThreshold = null,
            wellsDetected = 1,
            effectiveConfigSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot),
            processingVersionJson = "{\"geometry\":\"pg-grid-v2.1\"}",
            frameQcJson = frameQcJson(grid()),
            configurationDeviationJson = frozenOverride
        )
        val artifact = CaptureArtifact(
            id = "artifact-endpoint",
            runId = RUN_ID,
            captureRole = "ENDPOINT",
            originalPath = "content://frozen-endpoint",
            capturedAt = Date(2_000L),
            locked = true
        )
        val measurement = SiteMeasurement(
            runId = RUN_ID,
            siteIndex = 0,
            analyteId = ANALYTE_ID,
            detectionMode = "COLORIMETRIC",
            rawSignalJson = "{\"legacyRaw\":true}",
            correctedSignalJson = null,
            primaryFeatureName = "DELTA_E_2000",
            primaryFeatureValue = 12.5,
            confidence = 0.95,
            signalDetectable = true,
            qualityReliable = true,
            processorName = "colorimetric-photometry",
            processorVersion = "v1",
            concentrationValue = 12.5,
            concentrationUnit = "ng/mL",
            reliableRangeStatus = "WITHIN_RANGE"
        )
        return Fixture(analyte, project, run, artifact, measurement)
    }

    private fun snapshot(analyte: Analyte): TemplateProjectSnapshot {
        val carrier = CarrierProfile(
            id = CARRIER_ID,
            name = "2×2测试芯片",
            carrierType = "MICROFLUIDIC_CHIP",
            rows = 2,
            columns = 2,
            siteShape = "CIRCLE"
        )
        val acquisition = AcquisitionProfile(
            id = ACQUISITION_ID,
            name = "测试设备",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "AUTO_LOCKED"
        )
        val template = ExperimentTemplate(
            id = TEMPLATE_ID,
            templateName = "Room结果模板",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            concentrationUnit = "ng/mL",
            defaultLayoutJson = null,
            version = 1,
            status = "PUBLISHED",
            carrierProfileId = CARRIER_ID,
            detectionMode = "COLORIMETRIC",
            readoutLayout = "GRID_SITES",
            acquisitionProfileId = ACQUISITION_ID,
            inputProtocol = "ENDPOINT_ONLY"
        )
        val model = AnalysisModel(
            id = MODEL_ID,
            name = "CEA曲线",
            modelType = "STANDARD_CURVE",
            analyteId = ANALYTE_ID,
            detectionMode = "COLORIMETRIC",
            inputProtocol = "ENDPOINT_ONLY",
            primaryFeature = "DELTA_E_2000",
            processorName = "colorimetric-photometry",
            processorVersion = "v1",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            version = 1
        )
        val analyteSnapshot = TemplateProjectAnalyteSnapshot(
            analyte = analyte,
            templateConfig = TemplateAnalyteConfig(
                id = "config-room",
                templateId = TEMPLATE_ID,
                analyteId = ANALYTE_ID,
                analysisModelId = MODEL_ID,
                concentrationUnit = "ng/mL",
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0
            ),
            analysisModel = AnalysisModelBundle(
                model = model,
                standardCurve = StandardCurveDefinition(
                    analysisModelId = MODEL_ID,
                    fittingFunction = "LINEAR",
                    parametersJson = "{\"a\":1.0,\"b\":0.0}",
                    monotonicDirection = "INCREASING"
                )
            )
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_500L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(analyteSnapshot),
            siteAssignments = List(4) { index ->
                TemplateSiteAssignment(
                    id = "site-room-$index",
                    templateId = TEMPLATE_ID,
                    rowIndex = index / 2,
                    columnIndex = index % 2,
                    analyteId = ANALYTE_ID,
                    roleType = "SAMPLE",
                    enabled = true
                )
            }
        )
    }

    private fun grid(): PgGridResult {
        val sites = List(4) { index ->
            GridLocalizedSite(
                key = GridSiteKey(index / 2, index % 2),
                siteIndex = index,
                rectified = GridPoint((index % 2) * 10.0 + 5.0, (index / 2) * 10.0 + 5.0),
                original = GridPoint((index % 2) * 11.0 + 6.0, (index / 2) * 11.0 + 6.0),
                confidence = 0.95,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = 2,
            columns = 2,
            rectifiedWidth = 20,
            rectifiedHeight = 20,
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "test",
            chipCorners = listOf(
                GridPoint(0.0, 0.0), GridPoint(20.0, 0.0),
                GridPoint(20.0, 20.0), GridPoint(0.0, 20.0)
            ),
            homography = GridHomography(
                forward = IDENTITY,
                inverse = IDENTITY
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 1.0,
                trusted = true,
                observedRatio = 1.0,
                geometryRmsePx = 0.1,
                inlierCount = 4,
                outlierCount = 0,
                meanConfidence = 0.95
            ),
            frameQc = emptyList(),
            locatorName = "test-pg-grid",
            locatorVersion = "2.1"
        ).requireValid()
    }

    private fun frameQcJson(grid: PgGridResult): String {
        return gson.toJson(
            linkedMapOf(
                "frame" to linkedMapOf<String, Any>(
                    "geometry" to grid.geometry,
                    "issues" to grid.frameQc
                ),
                "pgGrid" to gson.fromJson(PgGridJsonCodec.encode(grid), JsonObject::class.java)
            )
        )
    }

    private data class Fixture(
        val analyte: Analyte,
        val project: Project,
        val run: DetectionRun,
        val artifact: CaptureArtifact,
        val measurement: SiteMeasurement
    )

    private companion object {
        val gson = Gson()
        val IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        const val PROJECT_ID = "project-room-array"
        const val RUN_ID = "run-room-array"
        const val TEMPLATE_ID = "template-room-array"
        const val CARRIER_ID = "carrier-room-array"
        const val ACQUISITION_ID = "acquisition-room-array"
        const val ANALYTE_ID = "analyte-room-cea"
        const val MODEL_ID = "model-room-cea"
    }
}
