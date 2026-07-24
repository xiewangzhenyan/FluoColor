package com.muc.fluocolorquant.domain.detection

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.evidence.AndroidGridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 真实图片 → 定位 → 荧光光度 → 模型兼容 → 持久化数据包的协调器集成测试。 */
@RunWith(AndroidJUnit4::class)
class GridDetectionCoordinatorIntegrationTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `重度模糊记录质量风险但继续保存测量与结果`() = runBlocking {
        val repository = RecordingGridRunRepository()
        val coordinator = GridDetectionCoordinator(
            locator = OpenCvPgGridLocator(),
            repository = repository
        )
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pg_grid/perturbation_v1/images/g10_blur_3p0_s20260722.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val snapshot = snapshot10x10()
        val project = projectForSnapshot(snapshot, "project-blurred-retake")

        val outcome = coordinator.execute(
            GridDetectionRequest(
                project = project,
                snapshot = snapshot,
                endpointBitmap = bitmap,
                endpointPath = project.imageUri,
                operatorId = project.userId,
                runId = "run-blurred-retake",
                capturedAt = Date(2_500L)
            )
        )

        assertTrue(outcome is GridDetectionOutcome.Completed)
        val completed = outcome as GridDetectionOutcome.Completed
        assertTrue(completed.frameQcIssueCount > 0)
        val saved = requireNotNull(repository.saved)
        assertEquals("Completed", saved.run.status)
        assertTrue(saved.measurements.isNotEmpty())
        assertEquals(saved.measurements.size, saved.run.wellsDetected)
        assertTrue(requireNotNull(saved.run.frameQcJson).contains("blurred"))
    }

    @Test
    fun `完成运行原子携带九张处理过程证据`() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = RecordingGridRunRepository()
        val coordinator = GridDetectionCoordinator(
            locator = OpenCvPgGridLocator(),
            repository = repository,
            evidenceWriter = AndroidGridProcessingEvidenceWriter(instrumentation.targetContext)
        )
        val bitmap = instrumentation.context.assets
            .open("pg_grid/synthetic_10x10_dark_squares.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val snapshot = snapshot10x10()
        val project = projectForSnapshot(snapshot, "project-processing-evidence")

        val outcome = coordinator.execute(
            GridDetectionRequest(
                project = project,
                snapshot = snapshot,
                endpointBitmap = bitmap,
                endpointPath = project.imageUri,
                operatorId = project.userId,
                runId = "run-processing-evidence-${System.nanoTime()}",
                capturedAt = Date(2_750L)
            )
        )

        assertTrue(outcome is GridDetectionOutcome.Completed)
        val saved = requireNotNull(repository.saved)
        assertEquals(9, saved.diagnosticArtifacts.size)
        assertTrue(saved.diagnosticArtifacts.all { artifact ->
            java.io.File(artifact.originalPath).isFile &&
                artifact.checksumSha256?.length == 64 &&
                artifact.actualMetadataJson?.contains("pg-processing-evidence-v1") == true
        })
    }

    @Test
    fun `十乘十荧光模板生成一百条新位点测量`() = runBlocking {
        val repository = RecordingGridRunRepository()
        val coordinator = GridDetectionCoordinator(
            locator = OpenCvPgGridLocator(),
            repository = repository
        )
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pg_grid/synthetic_10x10_dark_squares.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val snapshot = snapshot10x10()
        val project = Project(
            id = "project-10x10",
            name = "10x10 fluorescence",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            recognitionType = "AUTO",
            imageUri = "content://test/10x10.png",
            rows = 10,
            columns = 10,
            createTime = Date(1_000L),
            userId = "operator-1",
            lastRunTimestamp = null,
            analysisMethod = "TEMPLATE_MODEL",
            templateId = snapshot.template.id,
            templateVersion = snapshot.template.version,
            // 故意放入不可解析旧值，运行必须冻结实际执行的 request.snapshot，而不是照抄该字段。
            templateSnapshotJson = "frozen-snapshot",
            overrideJson = TemplateProjectOverrideCodec.encode(
                TemplateProjectOverrideSnapshot(
                    sampleSlotMapping = mapOf("R01C01" to "样本-A")
                )
            )
        )

        val outcome = coordinator.execute(
            GridDetectionRequest(
                project = project,
                snapshot = snapshot,
                endpointBitmap = bitmap,
                endpointPath = project.imageUri,
                operatorId = project.userId,
                runId = "run-10x10",
                capturedAt = Date(2_000L)
            )
        )

        assertTrue(outcome is GridDetectionOutcome.Completed)
        val saved = requireNotNull(repository.saved)
        assertEquals("run-10x10", saved.run.runId)
        val persistedSnapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(saved.run.effectiveConfigSnapshotJson)
        )
        // Gson 往返后的 Date/集合对象不使用整棵 data class equals；逐项验证会真正影响
        // 历史结果重建的模板、载体、设备、分析物和100个位点关系。
        assertEquals(snapshot.template.id, persistedSnapshot.template.id)
        assertEquals(snapshot.template.version, persistedSnapshot.template.version)
        assertEquals(snapshot.carrierProfile.id, persistedSnapshot.carrierProfile.id)
        assertEquals(snapshot.acquisitionProfile.id, persistedSnapshot.acquisitionProfile.id)
        assertEquals(snapshot.analytes.single().analysisModel.model.id,
            persistedSnapshot.analytes.single().analysisModel.model.id)
        assertEquals(100, persistedSnapshot.siteAssignments.size)
        assertEquals(project.overrideJson, saved.run.configurationDeviationJson)
        assertEquals(100, saved.measurements.size)
        assertEquals(100, saved.measurements.map { it.siteIndex }.distinct().size)
        assertTrue(saved.measurements.all { it.detectionMode == DetectionModality.FLUORESCENCE.code })
        assertTrue(saved.measurements.all { it.primaryFeatureName == AnalysisPrimaryFeature.FLUORESCENCE_SNR.code })
        assertTrue(saved.measurements.all { it.concentrationValue != null })
        assertTrue(saved.measurements.all { measurement ->
            kotlin.math.abs(
                requireNotNull(measurement.concentrationValue) -
                    requireNotNull(measurement.primaryFeatureValue)
            ) < 1e-6
        })
        assertTrue(saved.measurements.all { it.concentrationUnit == "ng/mL" })
        assertTrue(saved.measurements.all {
            it.modelSnapshotJson?.contains("model-fluorescence-v1") == true
        })
        // 荧光校正信号契约保持原样，不能因比色参考上下文升级而增加外层包装。
        val fluorescenceCorrected = JsonParser().parse(
            requireNotNull(saved.measurements.first().correctedSignalJson)
        ).asJsonObject
        assertTrue(fluorescenceCorrected.has("base"))
        assertFalse(fluorescenceCorrected.has("schemaVersion"))
        assertFalse(fluorescenceCorrected.has("calibrationContext"))
    }

    @Test
    fun `损坏深度快照均在定位器调用前被阻止`() = runBlocking {
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pg_grid/synthetic_10x10_dark_squares.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val valid = snapshot10x10()
        val sourceAnalyte = valid.analytes.single()
        val cases = listOf(
            GridDetectionBlockReason.EMPTY_ANALYTE_SNAPSHOT.name to valid.copy(
                analytes = emptyList(),
                siteAssignments = emptyList()
            ),
            GridDetectionBlockReason.INVALID_SITE_COORDINATE.name to valid.copy(
                siteAssignments = valid.siteAssignments + valid.siteAssignments.first().copy(
                    id = "invalid-coordinate",
                    rowIndex = -1
                )
            ),
            GridDetectionBlockReason.DUPLICATE_ENABLED_SITE.name to valid.copy(
                siteAssignments = valid.siteAssignments + valid.siteAssignments.first().copy(
                    id = "duplicate-coordinate"
                )
            ),
            GridDetectionBlockReason.ORPHAN_SITE_ANALYTE.name to valid.copy(
                siteAssignments = valid.siteAssignments.mapIndexed { index, assignment ->
                    if (index == 0) assignment.copy(analyteId = "missing-analyte") else assignment
                }
            ),
            "DUPLICATE_ANALYTE_SNAPSHOT" to valid.copy(
                analytes = valid.analytes + sourceAnalyte
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                analytes = listOf(
                    sourceAnalyte.copy(
                        templateConfig = sourceAnalyte.templateConfig.copy(
                            analyteId = "another-analyte"
                        )
                    )
                )
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                analytes = listOf(
                    sourceAnalyte.copy(
                        templateConfig = sourceAnalyte.templateConfig.copy(
                            analysisModelId = "another-model"
                        )
                    )
                )
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                analytes = listOf(
                    sourceAnalyte.copy(
                        analysisModel = sourceAnalyte.analysisModel.copy(
                            standardCurve = requireNotNull(sourceAnalyte.analysisModel.standardCurve).copy(
                                analysisModelId = "another-model"
                            )
                        )
                    )
                )
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                analytes = listOf(
                    sourceAnalyte.copy(
                        analysisModel = sourceAnalyte.analysisModel.copy(
                            calibrationPoints = listOf(
                                CalibrationPoint(
                                    id = "bad-point",
                                    analysisModelId = "another-model",
                                    concentration = 1.0,
                                    signalValue = 1.0,
                                    repeatIndex = 1
                                )
                            )
                        )
                    )
                )
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                template = valid.template.copy(carrierProfileId = "another-carrier")
            ),
            "INCONSISTENT_ANALYTE_SNAPSHOT" to valid.copy(
                siteAssignments = valid.siteAssignments.mapIndexed { index, assignment ->
                    if (index == 0) assignment.copy(templateId = "another-template") else assignment
                }
            )
        )

        cases.forEachIndexed { caseIndex, (expectedReasonName, snapshot) ->
            var locatorCalls = 0
            val repository = RecordingGridRunRepository()
            val coordinator = GridDetectionCoordinator(
                locator = { _, _ ->
                    locatorCalls += 1
                    error("损坏快照不应进入定位器")
                },
                repository = repository
            )

            val outcome = coordinator.execute(
                GridDetectionRequest(
                    project = projectForSnapshot(snapshot, "project-$caseIndex-$expectedReasonName"),
                    snapshot = snapshot,
                    endpointBitmap = bitmap,
                    endpointPath = "content://test/blocked.png",
                    operatorId = "operator-1",
                    runId = "run-$caseIndex-$expectedReasonName"
                )
            )

            assertTrue(outcome is GridDetectionOutcome.Blocked)
            assertTrue(
                expectedReasonName in (outcome as GridDetectionOutcome.Blocked).reasons.map(Enum<*>::name)
            )
            assertEquals(0, locatorCalls)
            assertNull(repository.saved)
        }
    }

    @Test
    fun `多分析物比色运行只保存一次全局参考并记录完整校正上下文`() = runBlocking {
        val repository = RecordingGridRunRepository()
        val coordinator = GridDetectionCoordinator(
            locator = OpenCvPgGridLocator(),
            repository = repository
        )
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pg_grid/synthetic_10x10_dark_squares.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val snapshot = colorimetricSnapshot10x10()
        val project = projectForSnapshot(snapshot, "project-colorimetric-reference")

        val outcome = coordinator.execute(
            GridDetectionRequest(
                project = project,
                snapshot = snapshot,
                endpointBitmap = bitmap,
                endpointPath = project.imageUri,
                operatorId = project.userId,
                runId = "run-colorimetric-reference",
                capturedAt = Date(3_000L)
            )
        )

        val completed = outcome as GridDetectionOutcome.Completed
        val saved = requireNotNull(repository.saved)
        assertTrue(completed.signalOnlyAnalyteIds.isEmpty())
        // 两个分析物样本行 + 一条全局参考证据行；measurementCount/wellsDetected 都是数据库行数。
        assertEquals(3, completed.measurementCount)
        assertEquals(3, saved.measurements.size)
        assertEquals(3, saved.run.wellsDetected)

        val referenceRows = saved.measurements.filter { it.analyteId == null }
        assertEquals(1, referenceRows.size)
        val reference = referenceRows.single()
        assertEquals(0, reference.siteIndex)
        assertEquals("COLORIMETRIC_REFERENCE_EVIDENCE", reference.primaryFeatureName)
        assertEquals("pg-quant", reference.processorName)
        assertEquals("pg-quant-android-v2-unit-mask", reference.processorVersion)
        val referenceRaw = JsonParser().parse(reference.rawSignalJson).asJsonObject
        assertEquals("colorimetric-reference-evidence-v1", referenceRaw["schemaVersion"].asString)
        assertTrue(referenceRaw.has("site"))
        assertTrue(requireNotNull(reference.qcJson).contains("geometrySource"))

        val analyteRows = saved.measurements.filter { it.analyteId != null }
        assertEquals(2, analyteRows.size)
        analyteRows.forEach { measurement ->
            val corrected = JsonParser().parse(requireNotNull(measurement.correctedSignalJson)).asJsonObject
            assertEquals("colorimetric-corrected-signal-v1", corrected["schemaVersion"].asString)
            assertTrue(corrected.has("site"))
            val context = corrected["calibrationContext"].asJsonObject
            assertEquals(listOf(0), context["referenceIndices"].asJsonArray.map { it.asInt })
            assertTrue(context.has("whiteBalanceGains"))
            assertTrue(context.has("referenceRgb"))
            assertTrue(context.has("referenceLab"))
        }

        val modelUsage = JsonParser().parse(saved.run.concentrationModelUsed).asJsonObject
        listOf("cea", "afp").forEach { analyteId ->
            val usage = modelUsage[analyteId].asJsonObject
            assertEquals(1, usage["total"].asInt)
            assertEquals(1, usage["quantifiedCount"].asInt)
        }
        val qcSummary = JsonParser().parse(saved.run.siteQcSummaryJson).asJsonObject
        assertEquals(3, qcSummary["total"].asInt)
        assertEquals(1, qcSummary["referenceEvidence"].asInt)
        val versions = JsonParser().parse(saved.run.processingVersionJson).asJsonObject
        assertTrue(versions.has("endpointQuantifier"))
    }

    private fun snapshot10x10(): TemplateProjectSnapshot {
        val templateId = "template-10x10"
        val analyte = Analyte(id = "cea", name = "CEA")
        val model = AnalysisModel(
            id = "model-fluorescence-v1",
            name = "CEA fluorescence",
            modelType = "STANDARD_CURVE",
            analyteId = analyte.id,
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-1\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10_000.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        val templateConfig = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = templateId,
            analyteId = analyte.id,
            analysisModelId = model.id,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10_000.0,
            displayConfigJson = ScientificDetectionConfigCodec.encodeFluorescenceDisplay(
                FluorescenceChannel.GREEN
            )
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_000L,
            template = ExperimentTemplate(
                id = templateId,
                templateName = "10x10 fluorescence",
                analyteId = null,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = 0.1,
                reliableRangeMax = 100.0,
                concentrationUnit = "ng/mL",
                defaultLayoutJson = null,
                version = 1,
                status = TemplateLifecycleStatus.PUBLISHED.code,
                carrierProfileId = "carrier-10x10",
                detectionMode = DetectionModality.FLUORESCENCE.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = "device-1",
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            ),
            carrierProfile = CarrierProfile(
                id = "carrier-10x10",
                name = "10x10 dark chip",
                carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
                rows = 10,
                columns = 10,
                siteShape = "SQUARE",
                locatorConfigJson = "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"
            ),
            acquisitionProfile = AcquisitionProfile(
                id = "device-1",
                name = "test device",
                supportedModesJson = "[\"FLUORESCENCE\"]",
                compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                cameraControlStrategy = "AUTO_LOCKED"
            ),
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = analyte,
                    templateConfig = templateConfig,
                    analysisModel = AnalysisModelBundle(
                        model = model,
                        standardCurve = StandardCurveDefinition(
                            analysisModelId = model.id,
                            fittingFunction = "linear",
                            parametersJson = """{"a":1.0,"b":0.0}""",
                            monotonicDirection = "INCREASING"
                        )
                    )
                )
            ),
            siteAssignments = List(100) { index ->
                TemplateSiteAssignment(
                    id = "site-$index",
                    templateId = templateId,
                    rowIndex = index / 10,
                    columnIndex = index % 10,
                    analyteId = analyte.id,
                    roleType = if (index == 0) TemplateSiteRole.BLANK.code else TemplateSiteRole.SAMPLE.code,
                    enabled = true
                )
            }
        )
    }

    private fun colorimetricSnapshot10x10(): TemplateProjectSnapshot {
        val templateId = "template-colorimetric"
        val analytes = listOf(
            Analyte(id = "cea", name = "CEA"),
            Analyte(id = "afp", name = "AFP")
        )
        val snapshots = analytes.map { analyte ->
            val model = AnalysisModel(
                id = "model-${analyte.id}",
                name = "${analyte.name} colorimetric",
                modelType = "STANDARD_CURVE",
                analyteId = analyte.id,
                detectionMode = DetectionModality.COLORIMETRIC.code,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
                processorName = "colorimetric-photometry",
                processorVersion = "v1",
                compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                compatibleAcquisitionProfileIdsJson = "[\"device-colorimetric\"]",
                concentrationUnit = "ng/mL",
                reliableRangeMin = 0.0,
                reliableRangeMax = 200.0,
                status = AnalysisModelLifecycleStatus.PUBLISHED.code
            )
            TemplateProjectAnalyteSnapshot(
                analyte = analyte,
                templateConfig = TemplateAnalyteConfig(
                    id = "config-${analyte.id}",
                    templateId = templateId,
                    analyteId = analyte.id,
                    analysisModelId = model.id,
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.0,
                    reliableRangeMax = 200.0
                ),
                analysisModel = AnalysisModelBundle(
                    model = model,
                    standardCurve = StandardCurveDefinition(
                        analysisModelId = model.id,
                        fittingFunction = "linear",
                        parametersJson = """{"a":1.0,"b":0.0}""",
                        monotonicDirection = "INCREASING"
                    )
                )
            )
        }
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_000L,
            template = ExperimentTemplate(
                id = templateId,
                templateName = "10x10 colorimetric",
                analyteId = null,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = 0.0,
                reliableRangeMax = 200.0,
                concentrationUnit = "ng/mL",
                defaultLayoutJson = null,
                version = 1,
                status = TemplateLifecycleStatus.PUBLISHED.code,
                carrierProfileId = "carrier-colorimetric",
                detectionMode = DetectionModality.COLORIMETRIC.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = "device-colorimetric",
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            ),
            carrierProfile = CarrierProfile(
                id = "carrier-colorimetric",
                name = "10x10 colorimetric chip",
                carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
                rows = 10,
                columns = 10,
                siteShape = "SQUARE",
                locatorConfigJson = "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"
            ),
            acquisitionProfile = AcquisitionProfile(
                id = "device-colorimetric",
                name = "colorimetric device",
                supportedModesJson = "[\"COLORIMETRIC\"]",
                compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                cameraControlStrategy = "AUTO_LOCKED"
            ),
            analytes = snapshots,
            siteAssignments = listOf(
                TemplateSiteAssignment(
                    id = "global-reference",
                    templateId = templateId,
                    rowIndex = 0,
                    columnIndex = 0,
                    analyteId = null,
                    roleType = TemplateSiteRole.REFERENCE.code,
                    enabled = true
                ),
                TemplateSiteAssignment(
                    id = "cea-sample",
                    templateId = templateId,
                    rowIndex = 0,
                    columnIndex = 1,
                    analyteId = "cea",
                    roleType = TemplateSiteRole.SAMPLE.code,
                    enabled = true
                ),
                TemplateSiteAssignment(
                    id = "afp-sample",
                    templateId = templateId,
                    rowIndex = 0,
                    columnIndex = 2,
                    analyteId = "afp",
                    roleType = TemplateSiteRole.SAMPLE.code,
                    enabled = true
                )
            )
        )
    }

    private fun projectForSnapshot(snapshot: TemplateProjectSnapshot, id: String): Project {
        return Project(
            id = id,
            name = id,
            detectionMode = requireNotNull(snapshot.template.detectionMode),
            recognitionType = "AUTO",
            imageUri = "content://test/$id.png",
            rows = snapshot.carrierProfile.rows,
            columns = snapshot.carrierProfile.columns,
            createTime = Date(1_000L),
            userId = "operator-1",
            lastRunTimestamp = null,
            analysisMethod = "TEMPLATE_MODEL",
            templateId = snapshot.template.id,
            templateVersion = snapshot.template.version,
            templateSnapshotJson = "frozen-snapshot"
        )
    }

    private class RecordingGridRunRepository : GridDetectionRunRepository {
        var saved: GridDetectionPersistenceBundle? = null

        override suspend fun save(bundle: GridDetectionPersistenceBundle) {
            saved = bundle.requireValid()
        }
    }
}
