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
import com.muc.fluocolorquant.domain.detection.array.ArrayBackgroundAnnulus
import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationDiagnostics
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunGeometryCodec
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunGeometrySnapshot
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunSiteGeometry
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
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

    @Test
    fun `新96孔板运行重建后保持方向圆孔索引和冻结浓度`() = runBlocking {
        val analyte = Analyte(PLATE_ANALYTE_ID, "CEA")
        val snapshot = plateSnapshot(analyte)
        val project = Project(
            id = PLATE_PROJECT_ID,
            name = "96孔板持久化闭环",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://plate96-source",
            rows = 8,
            columns = 12,
            createTime = Date(1_000L),
            userId = "user-id",
            lastRunTimestamp = Date(2_000L),
            analysisMethod = "CURVE_FIT",
            templateId = PLATE_TEMPLATE_ID,
            templateVersion = 1,
            templateSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot)
        )
        val geometry = plateGeometry()
        val frameQcJson = gson.toJson(
            linkedMapOf(
                "frame" to linkedMapOf<String, Any>(
                    "geometry" to geometry.diagnostics,
                    "issues" to emptyList<Any>()
                ),
                "plate96Geometry" to gson.fromJson(
                    Plate96RunGeometryCodec.encode(geometry),
                    JsonObject::class.java
                ),
                "geometrySchemaVersion" to geometry.schemaVersion
            )
        )
        val acquisitionMetadata = Plate96RunGeometryCodec.mergeAcquisitionMetadata(
            existingJson = "{\"camera\":\"rear\"}",
            geometry = geometry,
            exifRotationDegrees = 90,
            exifFlipped = false
        )
        val run = DetectionRun(
            runId = PLATE_RUN_ID,
            projectId = PLATE_PROJECT_ID,
            timestamp = Date(2_000L),
            detectionModelUsed = "Plate96 YOLO + circular-grid 1.0",
            concentrationModelUsed = "{}",
            status = "Completed",
            errorMessage = null,
            confThreshold = 0.25f,
            iouThreshold = 0.45f,
            wellsDetected = 96,
            effectiveConfigSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot),
            acquisitionMetadataJson = acquisitionMetadata,
            processingVersionJson = "{\"geometry\":\"plate96-run-geometry-v1\"}",
            frameQcJson = frameQcJson
        )
        val measurements = List(96) { index ->
            SiteMeasurement(
                runId = PLATE_RUN_ID,
                siteIndex = index,
                analyteId = PLATE_ANALYTE_ID,
                detectionMode = "COLORIMETRIC",
                rawSignalJson = "{\"siteIndex\":$index}",
                primaryFeatureName = "DELTA_E_2000",
                primaryFeatureValue = index.toDouble(),
                confidence = 0.96,
                signalDetectable = true,
                qualityReliable = true,
                processorName = "colorimetric-photometry",
                processorVersion = "v1",
                concentrationValue = index.toDouble(),
                concentrationUnit = "ng/mL",
                reliableRangeStatus = "WITHIN_RANGE"
            )
        }

        database.analyteDao().insertAnalyte(analyte)
        database.projectDao().insertProject(project)
        database.detectionRunDao().insertDetectionRun(run)
        database.captureArtifactDao().insert(
            CaptureArtifact(
                id = "plate96-endpoint",
                runId = PLATE_RUN_ID,
                captureRole = "ENDPOINT",
                originalPath = "content://plate96-source",
                capturedAt = Date(2_000L),
                locked = true
            )
        )
        database.siteMeasurementDao().insertAll(measurements)

        // 重新创建仓库模拟应用进程重启；结果必须只读冻结数据，不调用任何定位或拟合器。
        val reopened = ArrayResultRepositoryImpl(database).loadSnapshot(PLATE_RUN_ID)
        val plate = Plate96ResultSnapshotMapper.map(reopened)

        assertTrue(plate is Plate96ResultLoadResult.Success)
        val restored = (plate as Plate96ResultLoadResult.Success).snapshot
        assertEquals(96, restored.wells.size)
        assertEquals("A1", restored.wells.first().wellLabel)
        assertEquals("H12", restored.wells.last().wellLabel)
        assertEquals(95.0, restored.wells.last().site.measurements.single().concentrationValue)
        assertEquals(0, restored.orientation.quarterTurnsClockwise)
        assertTrue(restored.orientation.userConfirmed == true)
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

    private fun plateSnapshot(analyte: Analyte): TemplateProjectSnapshot {
        val carrier = CarrierProfile(
            id = PLATE_CARRIER_ID,
            name = "标准96孔板",
            carrierType = "PLATE",
            rows = 8,
            columns = 12,
            siteShape = "CIRCLE"
        )
        val acquisition = AcquisitionProfile(
            id = PLATE_ACQUISITION_ID,
            name = "96孔板相机",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"PLATE\"]",
            cameraControlStrategy = "AUTO_LOCKED"
        )
        val template = ExperimentTemplate(
            id = PLATE_TEMPLATE_ID,
            templateName = "96孔板模板",
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
            carrierProfileId = PLATE_CARRIER_ID,
            detectionMode = "COLORIMETRIC",
            readoutLayout = "GRID_SITES",
            acquisitionProfileId = PLATE_ACQUISITION_ID,
            inputProtocol = "ENDPOINT_ONLY"
        )
        val model = AnalysisModel(
            id = PLATE_MODEL_ID,
            name = "CEA现场曲线",
            modelType = "STANDARD_CURVE",
            analyteId = PLATE_ANALYTE_ID,
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
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_500L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = analyte,
                    templateConfig = TemplateAnalyteConfig(
                        id = "plate96-config",
                        templateId = PLATE_TEMPLATE_ID,
                        analyteId = PLATE_ANALYTE_ID,
                        analysisModelId = PLATE_MODEL_ID,
                        concentrationUnit = "ng/mL",
                        reliableRangeMin = 0.0,
                        reliableRangeMax = 100.0
                    ),
                    analysisModel = AnalysisModelBundle(
                        model = model,
                        standardCurve = StandardCurveDefinition(
                            analysisModelId = PLATE_MODEL_ID,
                            fittingFunction = "LINEAR",
                            parametersJson = "{\"a\":1.0,\"b\":0.0}",
                            monotonicDirection = "INCREASING"
                        )
                    )
                )
            ),
            siteAssignments = List(96) { index ->
                TemplateSiteAssignment(
                    id = "plate96-site-$index",
                    templateId = PLATE_TEMPLATE_ID,
                    rowIndex = index / 12,
                    columnIndex = index % 12,
                    analyteId = PLATE_ANALYTE_ID,
                    roleType = "SAMPLE",
                    enabled = true
                )
            }
        )
    }

    private fun plateGeometry(): Plate96RunGeometrySnapshot {
        val orientation = Plate96LayoutContract.orientation(
            originCorner = ArrayOriginCorner.TOP_LEFT,
            source = ArrayOrientationSource.USER_CONFIRMED,
            confidence = 1.0
        )
        val transform = ArrayCoordinateTransformer.createImageTransform(
            sourceWidth = 1200,
            sourceHeight = 800,
            rotation = orientation.rotation,
            mirrored = false
        )
        return Plate96RunGeometrySnapshot(
            locatorName = "plate96-test",
            locatorVersion = "1.0",
            orientation = orientation,
            imageTransform = transform,
            sites = List(96) { index ->
                val row = index / 12
                val column = index % 12
                val center = ArrayImagePoint(70.0 + column * 92.0, 70.0 + row * 92.0)
                val coordinate = ArrayGridCoordinate(row, column)
                Plate96RunSiteGeometry(
                    siteIndex = index,
                    displayLabel = Plate96LayoutContract.displayLabel(row, column),
                    canonicalCoordinate = coordinate,
                    sourceCoordinate = coordinate,
                    normalizedCenter = center,
                    sourceCenter = center,
                    normalizedBounds = ArrayImageBounds(
                        center.x - 28.0,
                        center.y - 28.0,
                        center.x + 28.0,
                        center.y + 28.0
                    ),
                    sourceBounds = ArrayImageBounds(
                        center.x - 28.0,
                        center.y - 28.0,
                        center.x + 28.0,
                        center.y + 28.0
                    ),
                    normalizedBackgroundAnnulus = ArrayBackgroundAnnulus(center, 34.0, 42.0),
                    sourceBackgroundAnnulus = ArrayBackgroundAnnulus(center, 34.0, 42.0),
                    normalizedCropBounds = ArrayUnitBounds(
                        (center.x - 28.0).toInt(),
                        (center.y - 28.0).toInt(),
                        (center.x + 28.0).toInt(),
                        (center.y + 28.0).toInt()
                    ),
                    sourceCropBounds = ArrayUnitBounds(
                        (center.x - 28.0).toInt(),
                        (center.y - 28.0).toInt(),
                        (center.x + 28.0).toInt(),
                        (center.y + 28.0).toInt()
                    ),
                    radiusPx = 28.0,
                    confidence = 0.96,
                    source = ArraySiteLocalizationSource.SHAPE_REFINED,
                    flags = emptySet()
                )
            },
            diagnostics = ArrayLocalizationDiagnostics(
                observedSiteCount = 96,
                shapeRefinedSiteCount = 96,
                imputedSiteCount = 0,
                orientationScore = 0.99,
                orientationAlternativeScore = 0.61,
                orientationAmbiguous = false,
                meanConfidence = 0.96
            )
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
        const val PLATE_PROJECT_ID = "project-room-plate96"
        const val PLATE_RUN_ID = "run-room-plate96"
        const val PLATE_TEMPLATE_ID = "template-room-plate96"
        const val PLATE_CARRIER_ID = "carrier-room-plate96"
        const val PLATE_ACQUISITION_ID = "acquisition-room-plate96"
        const val PLATE_ANALYTE_ID = "analyte-room-plate96-cea"
        const val PLATE_MODEL_ID = "model-room-plate96-cea"
    }
}
