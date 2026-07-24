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
import com.muc.fluocolorquant.domain.calibration.CalibrationFailureReason
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
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantConfig
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.SitePhotometryQc
import com.muc.fluocolorquant.domain.detection.quantification.EndpointQuantificationReason
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningBatchResult
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningFailureReason
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningPrediction
import com.muc.fluocolorquant.domain.detection.quantification.PreparedEndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.ReliableRangeStatus
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
            GridCarrierRoute.PLATE96,
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
    fun `深度学习成功批次同时保存范围内浓度并抑制超范围数值`() {
        val coordinator = coordinator()
        val analyte = deepLearningAnalyteSnapshot()
        val measurements = listOf(
            measurement(siteIndex = 0, primaryFeatureValue = 10.0).copy(analyteId = "cea"),
            measurement(siteIndex = 1, primaryFeatureValue = 20.0).copy(analyteId = "cea")
        )

        val batch = coordinator.applyDeepLearningBatchResult(
            measurements = measurements,
            analyteSnapshot = analyte,
            compatibility = ModelCompatibilityResult.Compatible,
            execution = GridDeepLearningBatchResult.Success(
                predictions = mapOf(
                    0 to GridDeepLearningPrediction(
                        siteIndex = 0,
                        concentration = 42.0,
                        rangeStatus = ReliableRangeStatus.WITHIN_RANGE,
                        modelSnapshotJson = "{\"type\":\"dl\"}"
                    ),
                    1 to GridDeepLearningPrediction(
                        siteIndex = 1,
                        concentration = null,
                        rangeStatus = ReliableRangeStatus.ABOVE_RANGE,
                        modelSnapshotJson = "{\"type\":\"dl\"}"
                    )
                )
            )
        )

        assertTrue(batch.modelExecutable)
        assertEquals(1, batch.quantifiedCount)
        assertEquals(1, batch.outOfRangeCount)
        assertEquals(42.0, requireNotNull(batch.measurements[0].concentrationValue), 1e-6)
        assertEquals("ng/mL", batch.measurements[0].concentrationUnit)
        assertEquals("WITHIN_RANGE", batch.measurements[0].reliableRangeStatus)
        assertNull(batch.measurements[1].concentrationValue)
        assertEquals("ABOVE_RANGE", batch.measurements[1].reliableRangeStatus)
    }

    @Test
    fun `深度学习任一模型级失败或输出不完整都会撤销整批浓度`() {
        val coordinator = coordinator()
        val analyte = deepLearningAnalyteSnapshot()
        val measurements = listOf(
            measurement(siteIndex = 0, primaryFeatureValue = 10.0).copy(analyteId = "cea"),
            measurement(siteIndex = 1, primaryFeatureValue = 20.0).copy(analyteId = "cea")
        )

        val failed = coordinator.applyDeepLearningBatchResult(
            measurements = measurements,
            analyteSnapshot = analyte,
            compatibility = ModelCompatibilityResult.Compatible,
            execution = GridDeepLearningBatchResult.Failure(
                GridDeepLearningFailureReason.CHECKSUM_MISMATCH
            )
        )
        val incomplete = coordinator.applyDeepLearningBatchResult(
            measurements = measurements,
            analyteSnapshot = analyte,
            compatibility = ModelCompatibilityResult.Compatible,
            execution = GridDeepLearningBatchResult.Success(
                predictions = mapOf(
                    0 to GridDeepLearningPrediction(
                        siteIndex = 0,
                        concentration = 42.0,
                        rangeStatus = ReliableRangeStatus.WITHIN_RANGE,
                        modelSnapshotJson = "{}"
                    )
                )
            )
        )

        listOf(failed, incomplete).forEach { batch ->
            assertFalse(batch.modelExecutable)
            assertEquals(0, batch.quantifiedCount)
            assertTrue(batch.measurements.all { it.concentrationValue == null })
        }
    }

    @Test
    fun `多分析物中部分模型可执行时运行状态为部分定量`() {
        val coordinator = coordinator()
        val quantified = measurement(siteIndex = 0, primaryFeatureValue = 10.0).copy(
            concentrationValue = 2.5,
            concentrationUnit = "ng/mL"
        )

        val status = coordinator.statusForQuantification(
            signalOnlyAnalyteIds = setOf("cea"),
            measurements = listOf(quantified, measurement(1, 20.0))
        )

        assertEquals("PartiallyQuantified", status)
    }

    @Test
    fun `整帧几何仅提示时真实观测位点仍按自身光度质量判定`() {
        val coordinator = coordinator()

        assertTrue(
            coordinator.isSiteMeasurementReliable(
                photometryReliable = true,
                pointSource = GridPointSource.CANDIDATE_REFINED
            )
        )
        assertFalse(
            coordinator.isSiteMeasurementReliable(
                photometryReliable = true,
                pointSource = GridPointSource.MODEL_IMPUTED
            )
        )
        assertFalse(
            coordinator.isSiteMeasurementReliable(
                photometryReliable = false,
                pointSource = GridPointSource.CANDIDATE_REFINED
            )
        )
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

    @Test
    fun `现场标准点为每个分析物独立生成曲线单位并量化样本`() {
        val first = validSnapshot().analytes.single().copy(
            quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code
        )
        val secondAnalyte = Analyte(id = "afp", name = "AFP")
        val secondModelId = "model-afp"
        val second = first.copy(
            analyte = secondAnalyte,
            templateConfig = first.templateConfig.copy(
                id = "config-afp",
                analyteId = secondAnalyte.id,
                analysisModelId = secondModelId,
                concentrationUnit = "IU/mL",
                reliableRangeMin = 0.0,
                reliableRangeMax = 10.0
            ),
            analysisModel = first.analysisModel.copy(
                model = first.analysisModel.model.copy(
                    id = secondModelId,
                    analyteId = secondAnalyte.id,
                    concentrationUnit = "IU/mL",
                    reliableRangeMin = 0.0,
                    reliableRangeMax = 10.0
                ),
                standardCurve = requireNotNull(first.analysisModel.standardCurve).copy(
                    analysisModelId = secondModelId,
                    parametersJson = "{}"
                ),
                calibrationPoints = emptyList()
            ),
            quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code
        )
        val base = validSnapshot()
        val snapshot = base.copy(
            carrierProfile = base.carrierProfile.copy(
                name = "2x3 chip",
                rows = 2,
                columns = 3
            ),
            analytes = listOf(first, second),
            siteAssignments = listOf(
                standardAssignment(base.template.id, "cea-10", 0, 0, "cea", 10.0),
                standardAssignment(base.template.id, "cea-110", 0, 1, "cea", 110.0),
                sampleAssignment(base.template.id, "cea-sample", 0, 2, "cea"),
                standardAssignment(base.template.id, "afp-1", 1, 0, "afp", 1.0),
                standardAssignment(base.template.id, "afp-11", 1, 1, "afp", 11.0),
                sampleAssignment(base.template.id, "afp-sample", 1, 2, "afp")
            )
        )
        // CEA 的 10/110 标准对应信号 10/110，样本信号 60；AFP 的 1/11 标准对应
        // 信号 20/120，样本信号 70。两条曲线共享算法，但单位与可靠范围必须完全独立。
        val quant = quantResult(
            rows = 2,
            columns = 3,
            signals = listOf(10.0, 110.0, 60.0, 20.0, 120.0, 70.0)
        )
        val coordinator = coordinator()

        val ceaResult = coordinator.previewOnsiteCalibration(snapshot, quant, "cea")
        val ceaSelected = requireNotNull(ceaResult.recommendedCandidateId)
        val withCea = coordinator.applyOnsiteCalibrationSelection(
            snapshot = snapshot,
            resultSet = ceaResult,
            selectedCandidateId = ceaSelected,
            runId = "run-onsite"
        )
        val afpResult = coordinator.previewOnsiteCalibration(withCea, quant, "afp")
        val afpSelected = requireNotNull(afpResult.recommendedCandidateId)
        val calibrated = coordinator.applyOnsiteCalibrationSelection(
            snapshot = withCea,
            resultSet = afpResult,
            selectedCandidateId = afpSelected,
            runId = "run-onsite"
        )

        val cea = calibrated.analytes.first { it.analyte.id == "cea" }
        val afp = calibrated.analytes.first { it.analyte.id == "afp" }
        assertEquals(2, cea.analysisModel.calibrationPoints.size)
        assertEquals(2, afp.analysisModel.calibrationPoints.size)
        assertTrue(requireNotNull(cea.analysisModel.standardCurve).parametersJson != "{}")
        assertTrue(requireNotNull(afp.analysisModel.standardCurve).parametersJson != "{}")
        assertEquals("ng/mL", cea.analysisModel.model.concentrationUnit)
        assertEquals("IU/mL", afp.analysisModel.model.concentrationUnit)
        // 现场标准点只更新曲线模型的标定范围，不能覆盖新建项目时冻结的预期量程。
        assertEquals(0.0, requireNotNull(cea.templateConfig.reliableRangeMin), 0.0)
        assertEquals(100.0, requireNotNull(cea.templateConfig.reliableRangeMax), 0.0)
        assertEquals(10.0, cea.analysisModel.model.reliableRangeMin, 0.0)
        assertEquals(110.0, cea.analysisModel.model.reliableRangeMax, 0.0)
        assertEquals(0.0, requireNotNull(afp.templateConfig.reliableRangeMin), 0.0)
        assertEquals(10.0, requireNotNull(afp.templateConfig.reliableRangeMax), 0.0)
        assertEquals(1.0, afp.analysisModel.model.reliableRangeMin, 0.0)
        assertEquals(11.0, afp.analysisModel.model.reliableRangeMax, 0.0)

        val ceaBatch = coordinator.applyQuantification(
            measurements = listOf(measurement(2, 60.0).copy(analyteId = "cea")),
            analyteSnapshot = cea,
            compatibility = ModelCompatibilityResult.Compatible
        )
        val afpBatch = coordinator.applyQuantification(
            measurements = listOf(measurement(5, 70.0).copy(analyteId = "afp")),
            analyteSnapshot = afp,
            compatibility = ModelCompatibilityResult.Compatible
        )

        assertTrue(ceaBatch.modelExecutable)
        assertTrue(afpBatch.modelExecutable)
        assertEquals(60.0, requireNotNull(ceaBatch.measurements.single().concentrationValue), 1e-4)
        assertEquals("ng/mL", ceaBatch.measurements.single().concentrationUnit)
        assertEquals(6.0, requireNotNull(afpBatch.measurements.single().concentrationValue), 1e-4)
        assertEquals("IU/mL", afpBatch.measurements.single().concentrationUnit)
    }

    @Test
    fun `现场标准浓度不足两个水平时返回结构化失败而不是空结果`() {
        val base = validSnapshot()
        val signalOnlyAnalyte = base.analytes.single().let { source ->
            source.copy(
                analysisModel = source.analysisModel.copy(
                    standardCurve = requireNotNull(source.analysisModel.standardCurve).copy(
                        parametersJson = "{}"
                    ),
                    calibrationPoints = emptyList()
                )
            )
        }
        val snapshot = base.copy(
            analytes = listOf(signalOnlyAnalyte),
            siteAssignments = listOf(
                standardAssignment(base.template.id, "only-standard", 0, 0, "cea", 10.0),
                sampleAssignment(base.template.id, "sample", 0, 1, "cea")
            )
        )
        val coordinator = coordinator()

        val resultSet = coordinator.previewOnsiteCalibration(
            snapshot = snapshot,
            quant = quantResult(2, 2, listOf(20.0, 30.0, 40.0, 50.0)),
            analyteId = "cea"
        )

        assertNull(resultSet.recommendedCandidateId)
        assertTrue(resultSet.candidates.isEmpty())
        assertEquals(3, resultSet.functionResults.size)
        assertTrue(resultSet.functionResults.all { result ->
            CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS in result.failureReasons
        })
    }

    @Test
    fun `保存现场曲线资源后模型ID完整同步且预检继续通过`() {
        val base = validSnapshot()
        val source = base.analytes.single().copy(
            quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
            analyteQuantitationSnapshot = AnalyteQuantitationSnapshot(
                analyteId = base.analytes.single().analyte.id,
                method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
                concentrationUnit = base.analytes.single().templateConfig.concentrationUnit,
                processorVersion = base.analytes.single().analysisModel.model.processorVersion,
                inputFingerprint = "onsite-before-save"
            )
        )
        val savedModelId = "saved-onsite-curve"
        val savedModel = source.analysisModel.model.copy(
            id = savedModelId,
            name = "saved onsite curve",
            // 曲线资源只覆盖28～34，项目配置仍应保持用户创建项目时声明的0～100。
            reliableRangeMin = 28.0,
            reliableRangeMax = 34.0
        )
        val savedBundle = source.analysisModel.copy(
            model = savedModel,
            standardCurve = requireNotNull(source.analysisModel.standardCurve).copy(
                analysisModelId = savedModelId
            ),
            calibrationPoints = source.analysisModel.calibrationPoints.map { point ->
                point.copy(analysisModelId = savedModelId)
            }
        )

        val synchronized = source.withPersistedOnsiteCurveResource(savedBundle)
        val snapshot = base.copy(analytes = listOf(synchronized))
        val preflight = GridDetectionPreflightValidator.validate(projectFor(snapshot), snapshot)

        assertEquals(savedModelId, synchronized.templateConfig.analysisModelId)
        assertEquals(savedModelId, synchronized.analysisModel.model.id)
        assertEquals(savedModelId, synchronized.analysisModel.standardCurve?.analysisModelId)
        assertEquals(savedModelId, synchronized.analyteQuantitationSnapshot?.sourceResourceId)
        assertEquals(0.0, synchronized.templateConfig.reliableRangeMin ?: Double.NaN, 0.0)
        assertEquals(100.0, synchronized.templateConfig.reliableRangeMax ?: Double.NaN, 0.0)
        assertEquals(28.0, synchronized.analysisModel.model.reliableRangeMin, 0.0)
        assertEquals(34.0, synchronized.analysisModel.model.reliableRangeMax, 0.0)
        assertEquals(
            GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
            synchronized.quantitationMode
        )
        assertFalse(
            GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT in preflight.reasons
        )
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

    /** 构造布局中的现场标准品位点，浓度与分析物必须同时冻结。 */
    private fun standardAssignment(
        templateId: String,
        id: String,
        row: Int,
        column: Int,
        analyteId: String,
        concentration: Double
    ): TemplateSiteAssignment {
        return TemplateSiteAssignment(
            id = id,
            templateId = templateId,
            rowIndex = row,
            columnIndex = column,
            analyteId = analyteId,
            roleType = TemplateSiteRole.STANDARD.code,
            standardConcentration = concentration,
            enabled = true
        )
    }

    /** 构造一个等待现场曲线反算的样本位点。 */
    private fun sampleAssignment(
        templateId: String,
        id: String,
        row: Int,
        column: Int,
        analyteId: String
    ): TemplateSiteAssignment {
        return TemplateSiteAssignment(
            id = id,
            templateId = templateId,
            rowIndex = row,
            columnIndex = column,
            analyteId = analyteId,
            roleType = TemplateSiteRole.SAMPLE.code,
            defaultSampleSlot = id,
            enabled = true
        )
    }

    /**
     * 构造确定性的荧光基础光度：绿色通道背景固定为 5，背景噪声固定为 1，因而
     * `FLUORESCENCE_SNR` 与传入 signal 一致，便于精确验证现场线性曲线和浓度单位。
     */
    private fun quantResult(
        rows: Int,
        columns: Int,
        signals: List<Double>
    ): PgQuantResult {
        require(signals.size == rows * columns)
        val sites = signals.mapIndexed { index, signal ->
            val row = index / columns
            val column = index % columns
            val background = RgbPhotometry(5.0, 5.0, 5.0)
            val roi = RgbPhotometry(5.0 + signal, 5.0 + signal, 5.0 + signal)
            BaseSitePhotometry(
                siteIndex = index,
                rowIndex = row,
                columnIndex = column,
                rectifiedCenter = GridPoint(column * 10.0 + 5.0, row * 10.0 + 5.0),
                originalCenter = GridPoint(column * 10.0 + 5.0, row * 10.0 + 5.0),
                roiMedianRgb = roi,
                roiMedianGray = 5.0 + signal,
                backgroundMedianRgb = background,
                backgroundMedianGray = 5.0,
                backgroundSigmaRgb = RgbPhotometry(1.0, 1.0, 1.0),
                backgroundSigmaGray = 1.0,
                correctedMedianRgb = roi,
                correctedMedianGray = 5.0 + signal,
                signalGray = signal,
                signalRatio = signal / 5.0,
                correctedSignalGray = signal,
                integratedSignalRgb = RgbPhotometry(signal, signal, signal),
                integratedSignalGray = signal,
                signalToNoiseRatio = signal,
                saturationRatio = 0.0,
                roiContaminationRatio = 0.0,
                hotPixelRatio = 0.0,
                roiClipRatio = 0.0,
                annulusClipRatio = 0.0,
                qc = SitePhotometryQc(
                    flags = emptySet(),
                    signalDetectable = true,
                    qualityReliable = true
                )
            )
        }
        return PgQuantResult(
            rows = rows,
            columns = columns,
            pitchPx = 10.0,
            roiRadiusPx = 1.8,
            annulusInnerPx = 3.0,
            annulusOuterPx = 4.4,
            illuminationModel = "test-flat-field",
            illuminationUniformity = 1.0,
            config = PgQuantConfig(),
            sites = sites
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
            displayConfigJson = "{\"schemaVersion\":\"fluorescence-display-v1\",\"fluorescenceChannel\":\"GREEN\"}"
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

    /** 构造结构完整的深度学习分析物快照，供批次原子性测试使用。 */
    private fun deepLearningAnalyteSnapshot(): TemplateProjectAnalyteSnapshot {
        val source = validSnapshot().analytes.single()
        val model = source.analysisModel.model.copy(
            modelType = AnalysisModelType.DEEP_LEARNING.code
        )
        return source.copy(
            templateConfig = source.templateConfig.copy(analysisModelId = model.id),
            analysisModel = AnalysisModelBundle(
                model = model,
                deepLearning = DeepLearningModelDefinition(
                    analysisModelId = model.id,
                    modelFileName = "models/improved_concentration_model_lite.ptl",
                    checksumSha256 = "0".repeat(64),
                    inputWidth = 128,
                    inputHeight = 128,
                    normalizationJson = "{\"mean\":[0.485,0.456,0.406],\"std\":[0.229,0.224,0.225]}",
                    trainingDataVersion = "test"
                )
            ),
            quantitationMode = GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL.code
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
