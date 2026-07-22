package com.muc.fluocolorquant.domain.detection

import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
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
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocator
import com.muc.fluocolorquant.domain.detection.quantification.EndpointQuantificationReason
import com.muc.fluocolorquant.domain.detection.quantification.PreparedEndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.StandardCurveQuantifier
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检测协调器的路由、深度快照预检和批量量化契约测试。 */
class GridDetectionCoordinatorTest {

    @Test
    fun `微流控载体进入PGGrid主链`() {
        assertEquals(
            GridCarrierRoute.MICROFLUIDIC_PG_GRID,
            GridDetectionRouteResolver.resolve(CarrierType.MICROFLUIDIC_CHIP)
        )
    }

    @Test
    fun `孔板载体保留旧YOLO霍夫兼容链`() {
        assertEquals(
            GridCarrierRoute.LEGACY_PLATE,
            GridDetectionRouteResolver.resolve(CarrierType.PLATE)
        )
    }

    @Test
    fun `自定义载体在未声明定位协议时不静默套用PGGrid`() {
        assertEquals(
            GridCarrierRoute.UNSUPPORTED,
            GridDetectionRouteResolver.resolve(CarrierType.CUSTOM)
        )
    }

    @Test
    fun `空分析物快照在定位前被阻止`() {
        val valid = validSnapshot()
        val snapshot = valid.copy(
            analytes = emptyList(),
            siteAssignments = valid.siteAssignments.filter { it.analyteId == null }
        )

        val preflight = GridDetectionPreflightValidator.validate(projectFor(snapshot), snapshot)

        assertTrue(GridDetectionBlockReason.EMPTY_ANALYTE_SNAPSHOT in preflight.reasons)
    }

    @Test
    fun `负数和越界坐标在定位前被阻止`() {
        val valid = validSnapshot()
        val snapshot = valid.copy(
            siteAssignments = valid.siteAssignments + listOf(
                valid.siteAssignments.first().copy(id = "negative", rowIndex = -1),
                valid.siteAssignments.first().copy(
                    id = "overflow",
                    rowIndex = valid.carrierProfile.rows,
                    columnIndex = valid.carrierProfile.columns
                )
            )
        )

        val preflight = GridDetectionPreflightValidator.validate(projectFor(snapshot), snapshot)

        assertTrue(GridDetectionBlockReason.INVALID_SITE_COORDINATE in preflight.reasons)
    }

    @Test
    fun `重复启用坐标在定位前被阻止`() {
        val valid = validSnapshot()
        val duplicate = valid.siteAssignments.first().copy(id = "duplicate")
        val snapshot = valid.copy(siteAssignments = valid.siteAssignments + duplicate)

        val preflight = GridDetectionPreflightValidator.validate(projectFor(snapshot), snapshot)

        assertTrue(GridDetectionBlockReason.DUPLICATE_ENABLED_SITE in preflight.reasons)
    }

    @Test
    fun `悬空分析物引用在定位前被阻止`() {
        val valid = validSnapshot()
        val snapshot = valid.copy(
            siteAssignments = valid.siteAssignments + TemplateSiteAssignment(
                id = "orphan",
                templateId = valid.template.id,
                rowIndex = 1,
                columnIndex = 1,
                analyteId = "missing-analyte",
                roleType = TemplateSiteRole.SAMPLE.code,
                enabled = true
            )
        )

        val preflight = GridDetectionPreflightValidator.validate(projectFor(snapshot), snapshot)

        assertTrue(GridDetectionBlockReason.ORPHAN_SITE_ANALYTE in preflight.reasons)
    }

    @Test
    fun `重复分析物快照在定位前被阻止`() {
        val valid = validSnapshot()
        val snapshot = valid.copy(analytes = valid.analytes + valid.analytes.single())

        val reasonNames = GridDetectionPreflightValidator
            .validate(projectFor(snapshot), snapshot)
            .reasons
            .map(Enum<*>::name)

        assertTrue("DUPLICATE_ANALYTE_SNAPSHOT" in reasonNames)
    }

    @Test
    fun `分析物快照任一关系或类型专用定义错配都在定位前被阻止`() {
        val valid = validSnapshot()
        val source = valid.analytes.single()
        val model = source.analysisModel.model
        val deepLearning = DeepLearningModelDefinition(
            analysisModelId = model.id,
            modelFileName = "model.ptl",
            checksumSha256 = "checksum",
            inputWidth = 224,
            inputHeight = 224,
            normalizationJson = "{}",
            trainingDataVersion = "v1"
        )
        val invalidAnalytes = listOf(
            "模板ID错配" to source.copy(
                templateConfig = source.templateConfig.copy(templateId = "another-template")
            ),
            "分析物ID错配" to source.copy(
                templateConfig = source.templateConfig.copy(analyteId = "another-analyte")
            ),
            "模板模型ID错配" to source.copy(
                templateConfig = source.templateConfig.copy(analysisModelId = "another-model")
            ),
            "模型分析物ID错配" to source.copy(
                analysisModel = source.analysisModel.copy(
                    model = model.copy(analyteId = "another-analyte")
                )
            ),
            "标准曲线ID错配" to source.copy(
                analysisModel = source.analysisModel.copy(
                    standardCurve = requireNotNull(source.analysisModel.standardCurve).copy(
                        analysisModelId = "another-model"
                    )
                )
            ),
            "标定点ID错配" to source.copy(
                analysisModel = source.analysisModel.copy(
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
            ),
            "标准曲线模型混入深度学习定义" to source.copy(
                analysisModel = source.analysisModel.copy(deepLearning = deepLearning)
            )
        )

        invalidAnalytes.forEach { (caseName, invalidAnalyte) ->
            val snapshot = valid.copy(analytes = listOf(invalidAnalyte))
            val reasonNames = GridDetectionPreflightValidator
                .validate(projectFor(snapshot), snapshot)
                .reasons
                .map(Enum<*>::name)
            assertTrue("$caseName 应返回关系不一致原因，实际为 $reasonNames", "INCONSISTENT_ANALYTE_SNAPSHOT" in reasonNames)
        }
    }

    @Test
    fun `顶层快照任一外键关系错配都在定位前被阻止`() {
        val valid = validSnapshot()
        val cases = listOf(
            Triple(
                "项目模板版本错配",
                projectFor(valid).copy(templateVersion = valid.template.version + 1),
                valid
            ) to GridDetectionBlockReason.PROJECT_SNAPSHOT_MISMATCH,
            Triple(
                "载体档案ID错配",
                projectFor(valid),
                valid.copy(template = valid.template.copy(carrierProfileId = "another-carrier"))
            ) to GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT,
            Triple(
                "采集档案ID错配",
                projectFor(valid),
                valid.copy(template = valid.template.copy(acquisitionProfileId = "another-device"))
            ) to GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT,
            Triple(
                "位点模板ID错配",
                projectFor(valid),
                valid.copy(
                    siteAssignments = valid.siteAssignments.mapIndexed { index, assignment ->
                        if (index == 0) assignment.copy(templateId = "another-template") else assignment
                    }
                )
            ) to GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT
        )

        cases.forEach { (input, expectedReason) ->
            val (caseName, project, snapshot) = input
            val reasons = GridDetectionPreflightValidator.validate(project, snapshot).reasons
            assertTrue("$caseName 应阻断，实际原因：$reasons", expectedReason in reasons)
        }
    }

    @Test
    fun `同一分析物混合成功超范围和非有限信号时只准备一次且不整体降级`() = runBlocking {
        val snapshot = validSnapshot()
        val analyteSnapshot = snapshot.analytes.single()
        val coordinator = coordinator()
        var prepareCount = 0
        val measurements = listOf(
            measurement(siteIndex = 0, primaryFeatureValue = 10.0),
            measurement(siteIndex = 1, primaryFeatureValue = 200.0),
            measurement(siteIndex = 2, primaryFeatureValue = Double.NaN)
        )

        val batch = coordinator.applyQuantification(
            measurements = measurements,
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible,
            prepareQuantifier = { bundle ->
                prepareCount += 1
                StandardCurveQuantifier.prepare(bundle)
            }
        )

        assertEquals(1, prepareCount)
        assertTrue(batch.modelExecutable)
        assertEquals(3, batch.total)
        assertEquals(1, batch.quantifiedCount)
        assertEquals(1, batch.outOfRangeCount)
        assertEquals(1, batch.siteSignalOnlyCount)
        assertEquals("standard_curve_applied_with_warnings", batch.execution)

        val quantified = batch.measurements.first { it.siteIndex == 0 }
        val outOfRange = batch.measurements.first { it.siteIndex == 1 }
        val nonFinite = batch.measurements.first { it.siteIndex == 2 }
        assertEquals(10.0, requireNotNull(quantified.concentrationValue), 1e-9)
        assertNull(outOfRange.concentrationValue)
        assertEquals("ABOVE_RANGE", outOfRange.reliableRangeStatus)
        assertNull(nonFinite.concentrationValue)
        val nonFiniteQc = JsonParser().parse(nonFinite.quantificationQcJson).asJsonObject
        assertEquals("SIGNAL_ONLY", nonFiniteQc["status"].asString)
        assertEquals("SITE", nonFiniteQc["scope"].asString)
        assertEquals(EndpointQuantificationReason.NON_FINITE_SIGNAL.name, nonFiniteQc["reason"].asString)

        val modelUsage = coordinator.modelUsageEntry(
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible,
            batch = batch
        )
        assertEquals(3, modelUsage["total"])
        assertEquals(1, modelUsage["quantifiedCount"])
        assertEquals(1, modelUsage["outOfRangeCount"])
        assertEquals(1, modelUsage["siteSignalOnlyCount"])
        assertEquals("standard_curve_applied_with_warnings", modelUsage["execution"])
        assertEquals("Completed", coordinator.statusForSignalOnlyAnalytes(emptySet()))
    }

    @Test
    fun `模型级准备失败才将整个分析物标为仅信号`() {
        val snapshot = validSnapshot()
        val analyteSnapshot = snapshot.analytes.single().copy(
            analysisModel = snapshot.analytes.single().analysisModel.copy(standardCurve = null)
        )
        val coordinator = coordinator()

        val batch = coordinator.applyQuantification(
            measurements = listOf(measurement(0, 10.0), measurement(1, 20.0)),
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible
        )

        assertFalse(batch.modelExecutable)
        assertEquals("signal_only", batch.execution)
        assertTrue(batch.measurements.all { measurement ->
            val qc = JsonParser().parse(measurement.quantificationQcJson).asJsonObject
            qc["scope"].asString == "MODEL"
        })
        assertEquals("SignalOnlyCompleted", coordinator.statusForSignalOnlyAnalytes(setOf("cea")))
    }

    @Test
    fun `Ready运行时出现模型故障时撤销部分浓度并整体降级`() {
        val snapshot = validSnapshot()
        val analyteSnapshot = snapshot.analytes.single()
        val coordinator = coordinator()
        val injectedReady = PreparedStandardCurveQuantifier.Ready { signal ->
            if (signal == 20.0) {
                PreparedEndpointQuantificationResult.ModelFailure(
                    EndpointQuantificationReason.INVALID_MODEL_DEFINITION
                )
            } else {
                PreparedEndpointQuantificationResult.Quantified(
                    concentration = signal,
                    unit = "ng/mL",
                    modelSnapshotJson = "{\"model\":\"injected\"}"
                )
            }
        }

        val batch = coordinator.applyQuantification(
            measurements = listOf(measurement(0, 10.0), measurement(1, 20.0)),
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible,
            prepareQuantifier = { injectedReady }
        )

        // 第二个位点暴露的是 Ready 内部模型故障，不是样本信号问题；此前已计算的浓度
        // 也必须全部撤销，避免同一模型一部分定量、一部分模型级失败的不可审计状态。
        assertFalse(batch.modelExecutable)
        assertEquals(0, batch.quantifiedCount)
        assertEquals(0, batch.siteSignalOnlyCount)
        assertEquals("signal_only", batch.execution)
        assertTrue(batch.measurements.all { measurement ->
            measurement.concentrationValue == null &&
                JsonParser().parse(measurement.quantificationQcJson).asJsonObject["scope"].asString == "MODEL"
        })
        assertEquals("SignalOnlyCompleted", coordinator.statusForSignalOnlyAnalytes(setOf("cea")))
    }

    private fun coordinator(): GridDetectionCoordinator {
        val locator = PgGridLocator { _, _ -> error("纯量化单测不应调用定位器") }
        return GridDetectionCoordinator(locator, NoOpGridRunRepository())
    }

    private fun measurement(siteIndex: Int, primaryFeatureValue: Double): SiteMeasurement {
        return SiteMeasurement(
            runId = "run-test",
            siteIndex = siteIndex,
            analyteId = "cea",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            rawSignalJson = "{}",
            primaryFeatureName = AnalysisPrimaryFeature.FLUORESCENCE_SNR.code,
            primaryFeatureValue = primaryFeatureValue,
            signalDetectable = true,
            qualityReliable = true,
            processorName = "fluorescence-photometry",
            processorVersion = "v1"
        )
    }

    private fun validSnapshot(): TemplateProjectSnapshot {
        val templateId = "template-test"
        val analyte = Analyte(id = "cea", name = "CEA")
        val model = AnalysisModel(
            id = "model-cea",
            name = "CEA linear",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-test\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        val templateConfig = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = templateId,
            analyteId = analyte.id,
            analysisModelId = model.id,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            displayConfigJson = "{\"schemaVersion\":\"fluorescence-display-v1\",\"channel\":\"GREEN\"}"
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_000L,
            template = ExperimentTemplate(
                id = templateId,
                templateName = "test template",
                analyteId = null,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                concentrationUnit = "ng/mL",
                defaultLayoutJson = null,
                version = 1,
                status = TemplateLifecycleStatus.PUBLISHED.code,
                carrierProfileId = "carrier-test",
                detectionMode = DetectionModality.FLUORESCENCE.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = "device-test",
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            ),
            carrierProfile = CarrierProfile(
                id = "carrier-test",
                name = "2x2 chip",
                carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
                rows = 2,
                columns = 2,
                siteShape = "SQUARE",
                locatorConfigJson = "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"
            ),
            acquisitionProfile = AcquisitionProfile(
                id = "device-test",
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
            siteAssignments = listOf(
                TemplateSiteAssignment(
                    id = "sample",
                    templateId = templateId,
                    rowIndex = 0,
                    columnIndex = 0,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    enabled = true
                ),
                TemplateSiteAssignment(
                    id = "global-blank",
                    templateId = templateId,
                    rowIndex = 0,
                    columnIndex = 1,
                    analyteId = null,
                    roleType = TemplateSiteRole.BLANK.code,
                    enabled = true
                )
            )
        )
    }

    private fun projectFor(snapshot: TemplateProjectSnapshot): Project {
        return Project(
            id = "project-test",
            name = "project",
            detectionMode = requireNotNull(snapshot.template.detectionMode),
            recognitionType = "AUTO",
            imageUri = "content://test/image.png",
            rows = snapshot.carrierProfile.rows,
            columns = snapshot.carrierProfile.columns,
            createTime = Date(1_000L),
            userId = "operator",
            lastRunTimestamp = null,
            analysisMethod = "TEMPLATE_MODEL",
            templateId = snapshot.template.id,
            templateVersion = snapshot.template.version,
            templateSnapshotJson = "frozen"
        )
    }

    private class NoOpGridRunRepository : GridDetectionRunRepository {
        override suspend fun save(bundle: GridDetectionPersistenceBundle) = Unit
    }
}
