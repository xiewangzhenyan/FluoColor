package com.muc.fluocolorquant.domain.detection

import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.domain.calibration.CalibrationFailureReason
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
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
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantConfig
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.SitePhotometryQc
import com.muc.fluocolorquant.domain.detection.quantification.EndpointQuantificationReason
import com.muc.fluocolorquant.domain.detection.quantification.ENDPOINT_QUANTIFIER_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.DeepLearningOutputMode
import com.muc.fluocolorquant.domain.detection.quantification.DeepLearningOutputTransform
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningBatchResult
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningFailureReason
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningPrediction
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningSiteFailure
import com.muc.fluocolorquant.domain.detection.quantification.GRID_DEEP_LEARNING_QUANTIFIER_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.PreparedEndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.QuantificationState
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryStatus
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
        assertEquals(0, batch.estimatedCount)
        assertEquals(1, batch.boundOnlyCount)
        assertEquals(1, batch.unavailableCount)
        assertEquals(2, batch.retestCount)
        assertEquals(1, batch.outOfRangeCount)
        assertEquals(1, batch.siteSignalOnlyCount)
        assertEquals("standard_curve_applied_with_warnings", batch.execution)

        val quantified = batch.measurements.first { it.siteIndex == 0 }
        val outOfRange = batch.measurements.first { it.siteIndex == 1 }
        val nonFinite = batch.measurements.first { it.siteIndex == 2 }
        assertEquals(10.0, requireNotNull(quantified.concentrationValue), 1e-9)
        assertNull(outOfRange.concentrationValue)
        assertEquals("ABOVE_RANGE", outOfRange.reliableRangeStatus)
        assertEquals("BOUND_ONLY", outOfRange.quantificationState)
        assertNull(nonFinite.concentrationValue)
        assertEquals("UNAVAILABLE", nonFinite.quantificationState)
        val nonFiniteQc = JsonParser().parse(nonFinite.quantificationQcJson).asJsonObject
        assertEquals("UNAVAILABLE", nonFiniteQc["status"].asString)
        assertEquals("SITE", nonFiniteQc["scope"].asString)
        assertEquals(EndpointQuantificationReason.NON_FINITE_SIGNAL.name, nonFiniteQc["reason"].asString)

        val modelUsage = coordinator.modelUsageEntry(
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible,
            batch = batch
        )
        assertEquals(3, modelUsage["total"])
        assertEquals(1, modelUsage["quantifiedCount"])
        assertEquals(0, modelUsage["estimatedCount"])
        assertEquals(1, modelUsage["boundOnlyCount"])
        assertEquals(1, modelUsage["unavailableCount"])
        assertEquals(2, modelUsage["retestCount"])
        assertEquals(1, modelUsage["outOfRangeCount"])
        assertEquals(1, modelUsage["siteSignalOnlyCount"])
        assertEquals("standard_curve_applied_with_warnings", modelUsage["execution"])
        assertEquals("Completed", coordinator.statusForSignalOnlyAnalytes(emptySet()))
    }

    @Test
    fun `独立质控校正后逐孔状态与批次计数从冻结结果重新汇总`() {
        val base = validSnapshot()
        val baseAnalyte = base.analytes.single()
        val calibratedModel = baseAnalyte.analysisModel.model.copy(
            // 模型范围代表真实标准点覆盖，模板配置继续代表项目声明的 0～100 量程。
            reliableRangeMin = 20.0,
            reliableRangeMax = 80.0
        )
        val analyteSnapshot = baseAnalyte.copy(
            analysisModel = baseAnalyte.analysisModel.copy(model = calibratedModel)
        )
        val sampleConcentrations = listOf(120.0, 140.0, 160.0, 180.0, 200.0, 220.0)
        val assignments = sampleConcentrations.indices.map { index ->
            TemplateSiteAssignment(
                id = "sample-$index",
                templateId = base.template.id,
                rowIndex = index / 4,
                columnIndex = index % 4,
                analyteId = analyteSnapshot.analyte.id,
                roleType = TemplateSiteRole.SAMPLE.code,
                enabled = true
            )
        } + listOf(
            TemplateSiteAssignment(
                id = "negative-control",
                templateId = base.template.id,
                rowIndex = 1,
                columnIndex = 2,
                analyteId = analyteSnapshot.analyte.id,
                roleType = TemplateSiteRole.NEGATIVE_CONTROL.code,
                standardConcentration = 20.0,
                enabled = true
            ),
            TemplateSiteAssignment(
                id = "positive-control",
                templateId = base.template.id,
                rowIndex = 1,
                columnIndex = 3,
                analyteId = analyteSnapshot.analyte.id,
                roleType = TemplateSiteRole.POSITIVE_CONTROL.code,
                standardConcentration = 80.0,
                enabled = true
            )
        )
        val snapshot = base.copy(
            carrierProfile = base.carrierProfile.copy(rows = 2, columns = 4),
            analytes = listOf(analyteSnapshot),
            siteAssignments = assignments
        )
        val measurements = (sampleConcentrations + listOf(40.0, 160.0)).mapIndexed {
                index,
                concentration ->
            val isControl = index >= sampleConcentrations.size
            measurement(index, concentration).copy(
                concentrationValue = concentration,
                concentrationUnit = "ng/mL",
                reliableRangeStatus = if (isControl) {
                    ReliableRangeStatus.WITHIN_RANGE.name
                } else {
                    ReliableRangeStatus.ABOVE_RANGE.name
                },
                quantificationState = if (isControl) {
                    QuantificationState.QUANTIFIED.name
                } else {
                    QuantificationState.ESTIMATED.name
                },
                concentrationLowerBound = concentration,
                concentrationUpperBound = concentration,
                quantificationQcJson = "{}"
            )
        }
        val beforeReview = GridDetectionCoordinator.QuantificationBatch(
            measurements = measurements,
            modelExecutable = true,
            quantifiedCount = 2,
            estimatedCount = 6,
            boundOnlyCount = 0,
            unavailableCount = 0,
            outOfRangeCount = 0,
            extrapolatedCount = 6,
            siteSignalOnlyCount = 0,
            total = measurements.size
        )
        val coordinator = coordinator()

        val reviewed = with(coordinator) {
            beforeReview.withDynamicRangeReview(snapshot, analyteSnapshot)
        }

        assertEquals(RangeRecoveryStatus.CORRECTION_APPLIED, reviewed.rangeRecovery?.status)
        assertEquals(2, reviewed.quantifiedCount)
        assertEquals(5, reviewed.estimatedCount)
        assertEquals(1, reviewed.boundOnlyCount)
        assertEquals(0, reviewed.unavailableCount)
        assertEquals(1, reviewed.outOfRangeCount)
        assertEquals(5, reviewed.extrapolatedCount)
        assertEquals(8, reviewed.total)

        val correctedWithin = reviewed.measurements.first { it.siteIndex == 0 }
        assertEquals(60.0, requireNotNull(correctedWithin.concentrationValue), 1e-9)
        // 原结果属于估计，质控校正不能因为数值回到标定区间就把证据等级升级为精确定量。
        assertEquals(QuantificationState.ESTIMATED.name, correctedWithin.quantificationState)
        assertEquals(ReliableRangeStatus.WITHIN_RANGE.name, correctedWithin.reliableRangeStatus)

        val correctedOutside = reviewed.measurements.first { it.siteIndex == 5 }
        assertNull(correctedOutside.concentrationValue)
        assertEquals(100.0, requireNotNull(correctedOutside.concentrationLowerBound), 1e-9)
        assertNull(correctedOutside.concentrationUpperBound)
        assertEquals(QuantificationState.BOUND_ONLY.name, correctedOutside.quantificationState)
        assertEquals(ReliableRangeStatus.ABOVE_PROJECT_RANGE.name, correctedOutside.reliableRangeStatus)
        assertEquals("LOWER_BOUND", correctedOutside.censoringDirection)
        val siteRecovery = JsonParser.parseString(correctedOutside.quantificationQcJson)
            .asJsonObject["rangeRecovery"].asJsonObject
        assertEquals(220.0, siteRecovery["originalConcentration"].asDouble, 1e-9)
        assertEquals("BOUND_ONLY", siteRecovery["correctedQuantificationState"].asString)

        val modelUsage = coordinator.modelUsageEntry(
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible,
            batch = reviewed
        )
        assertEquals(1, modelUsage["outOfRangeCount"])
        assertEquals(5, modelUsage["estimatedCount"])
        assertEquals(1, modelUsage["boundOnlyCount"])
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
        assertEquals(2, batch.unavailableCount)
        assertEquals(2, batch.retestCount)
        assertTrue(batch.measurements.all { measurement ->
            val qc = JsonParser().parse(measurement.quantificationQcJson).asJsonObject
            qc["scope"].asString == "MODEL" &&
                measurement.quantificationState == "UNAVAILABLE" &&
                measurement.quantificationVersion == ENDPOINT_QUANTIFIER_VERSION
        })
        assertEquals("SignalOnlyCompleted", coordinator.statusForSignalOnlyAnalytes(setOf("cea")))
    }

    @Test
    fun `真实五参数现场曲线在零起点项目量程中不会整批降级`() {
        val source = validSnapshot().analytes.single()
        val model = source.analysisModel.model.copy(
            primaryFeature = AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY.code,
            concentrationUnit = "g/ml",
            reliableRangeMin = 26.0,
            reliableRangeMax = 85.0
        )
        val analyteSnapshot = source.copy(
            templateConfig = source.templateConfig.copy(
                concentrationUnit = "g/ml",
                // 项目量程来自用户新建项目时填写的0～100，不等同于现场标定点范围。
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0
            ),
            analysisModel = AnalysisModelBundle(
                model = model,
                standardCurve = StandardCurveDefinition(
                    analysisModelId = model.id,
                    fittingFunction = "logistic_5pl",
                    parametersJson = """{"a":361625.78152999684,"b":8.547083392401774,"c":34.921194891259134,"d":1113444.9059174429,"g":0.4861663820746118}""",
                    monotonicDirection = "AUTO"
                )
            )
        )
        val coordinator = coordinator()

        val batch = coordinator.applyQuantification(
            measurements = listOf(
                measurement(siteIndex = 0, primaryFeatureValue = 756000.0),
                measurement(siteIndex = 1, primaryFeatureValue = 8560.0),
                measurement(siteIndex = 2, primaryFeatureValue = 1175518.0)
            ),
            analyteSnapshot = analyteSnapshot,
            compatibility = ModelCompatibilityResult.Compatible
        )

        assertTrue(batch.modelExecutable)
        assertEquals(1, batch.quantifiedCount)
        assertEquals(2, batch.outOfRangeCount)
        assertEquals(40.58720368279272, requireNotNull(batch.measurements[0].concentrationValue), 1e-6)
        assertEquals("g/ml", batch.measurements[0].concentrationUnit)
        assertEquals("WITHIN_RANGE", batch.measurements[0].reliableRangeStatus)
        assertEquals("BELOW_PROJECT_RANGE", batch.measurements[1].reliableRangeStatus)
        assertEquals("ABOVE_PROJECT_RANGE", batch.measurements[2].reliableRangeStatus)
    }

    @Test
    fun `旧直接项目现场曲线可在新96孔板直接项目中继续定量`() {
        val source = validSnapshot().analytes.single()
        val model = source.analysisModel.model.copy(
            primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR.code,
            compatibleCarrierTypesJson = "[\"PLATE\"]",
            // 旧版把项目UUID拼入采集档案ID；它只代表一次直接采集会话，并不代表另一台设备。
            compatibleAcquisitionProfileIdsJson = "[\"direct-acquisition-old-project\"]",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0
        )
        val analyteSnapshot = source.copy(
            templateConfig = source.templateConfig.copy(
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0
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
        val compatibility = AnalysisModelCompatibilityChecker.check(
            model = model,
            request = ModelCompatibilityRequest(
                analyteId = source.analyte.id,
                modality = DetectionModality.FLUORESCENCE,
                inputProtocol = InputProtocol.ENDPOINT_ONLY,
                primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR,
                carrierType = CarrierType.PLATE,
                acquisitionProfileId = "direct-acquisition-new-project",
                processorName = model.processorName,
                processorVersion = model.processorVersion
            )
        )

        // 回归契约不只验证“兼容”枚举，还必须证明协调器最终真的写出了浓度。
        assertEquals(ModelCompatibilityResult.Compatible, compatibility)
        val batch = coordinator().applyQuantification(
            measurements = listOf(measurement(siteIndex = 0, primaryFeatureValue = 10.0)),
            analyteSnapshot = analyteSnapshot,
            compatibility = compatibility
        )

        assertTrue(batch.modelExecutable)
        assertEquals(1, batch.quantifiedCount)
        assertEquals(10.0, requireNotNull(batch.measurements.single().concentrationValue), 1e-9)
        assertEquals("WITHIN_RANGE", batch.measurements.single().reliableRangeStatus)
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
        val quantified = batch.measurements[0]
        assertEquals(42.0, requireNotNull(quantified.concentrationValue), 1e-6)
        assertEquals("ng/mL", quantified.concentrationUnit)
        assertEquals("WITHIN_RANGE", quantified.reliableRangeStatus)
        assertEquals("QUANTIFIED", quantified.quantificationState)
        assertEquals(42.0, quantified.concentrationLowerBound ?: Double.NaN, 0.0)
        assertEquals(42.0, quantified.concentrationUpperBound ?: Double.NaN, 0.0)
        assertEquals("NONE", quantified.censoringDirection)
        assertEquals(GRID_DEEP_LEARNING_QUANTIFIER_VERSION, quantified.quantificationVersion)

        val aboveRange = batch.measurements[1]
        assertNull(aboveRange.concentrationValue)
        assertEquals("ABOVE_RANGE", aboveRange.reliableRangeStatus)
        assertEquals("BOUND_ONLY", aboveRange.quantificationState)
        assertEquals(
            analyte.analysisModel.model.reliableRangeMax,
            aboveRange.concentrationLowerBound ?: Double.NaN,
            0.0
        )
        assertNull(aboveRange.concentrationUpperBound)
        assertEquals("LOWER_BOUND", aboveRange.censoringDirection)
        assertEquals(GRID_DEEP_LEARNING_QUANTIFIER_VERSION, aboveRange.quantificationVersion)
        assertEquals(
            "BOUND_ONLY",
            JsonParser.parseString(aboveRange.quantificationQcJson).asJsonObject["status"].asString
        )
    }

    @Test
    fun `百分比模型只在零到一百声明域内换算浓度`() {
        val transform = DeepLearningOutputTransform(
            mode = DeepLearningOutputMode.PERCENT_OF_RELIABLE_MAX,
            scale = 1.0,
            offset = 0.0
        )

        // 2.5% 应映射到完整可靠区间的 2.5% 位置；模型百分比不是 ng/mL 本身。
        assertEquals(
            12.5,
            requireNotNull(
                transform.toConcentrationOrNull(
                    rawOutput = 2.5,
                    reliableMinimum = 10.0,
                    reliableMaximum = 110.0
                )
            ),
            1e-9
        )
        assertEquals(
            110.0,
            requireNotNull(
                transform.toConcentrationOrNull(
                    rawOutput = 100.0 + 1e-5,
                    reliableMinimum = 10.0,
                    reliableMaximum = 110.0
                )
            ),
            1e-9
        )
        // 明显越界表示模型离开声明域。禁止 clamp 到端点，也不能据此伪造 >110 ng/mL。
        assertNull(
            transform.toConcentrationOrNull(
                rawOutput = -0.01,
                reliableMinimum = 10.0,
                reliableMaximum = 110.0
            )
        )
        assertNull(
            transform.toConcentrationOrNull(
                rawOutput = 100.01,
                reliableMinimum = 10.0,
                reliableMaximum = 110.0
            )
        )
    }

    @Test
    fun `深度学习批次级失败或输出不完整仍会撤销整批浓度`() {
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
            assertTrue(batch.measurements.all { measurement ->
                measurement.concentrationValue == null &&
                    measurement.quantificationState == "UNAVAILABLE" &&
                    measurement.concentrationLowerBound == null &&
                    measurement.concentrationUpperBound == null &&
                    measurement.censoringDirection == null
            })
        }
    }

    @Test
    fun `深度学习单孔输出离域时保留其他孔浓度并冻结原始输出`() {
        val coordinator = coordinator()
        val analyte = deepLearningAnalyteSnapshot()
        val measurements = listOf(
            measurement(siteIndex = 0, primaryFeatureValue = 10.0).copy(analyteId = "cea"),
            measurement(siteIndex = 1, primaryFeatureValue = 20.0).copy(analyteId = "cea")
        )
        val modelSnapshot = "{\"model\":\"shared\"}"

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
                        modelSnapshotJson = modelSnapshot
                    )
                ),
                siteFailures = mapOf(
                    1 to GridDeepLearningSiteFailure(
                        siteIndex = 1,
                        reason = GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE,
                        rawModelOutput = 127.5,
                        transformedModelOutput = 127.5,
                        declaredOutputMin = 0.0,
                        declaredOutputMax = 100.0,
                        modelSnapshotJson = modelSnapshot
                    )
                )
            )
        )

        assertTrue(batch.modelExecutable)
        assertFalse(batch.isSignalOnlyResult)
        assertEquals(1, batch.quantifiedCount)
        assertEquals(1, batch.unavailableCount)
        assertEquals(1, batch.siteSignalOnlyCount)
        assertEquals(42.0, requireNotNull(batch.measurements[0].concentrationValue), 0.0)
        val unavailable = batch.measurements[1]
        assertNull(unavailable.concentrationValue)
        assertEquals(QuantificationState.UNAVAILABLE.name, unavailable.quantificationState)
        assertEquals(GRID_DEEP_LEARNING_QUANTIFIER_VERSION, unavailable.quantificationVersion)
        assertEquals(modelSnapshot, unavailable.modelSnapshotJson)
        val qc = JsonParser.parseString(unavailable.quantificationQcJson).asJsonObject
        assertEquals("SITE", qc["scope"].asString)
        assertEquals(
            GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE.name,
            qc["reason"].asString
        )
        assertEquals(127.5, qc["rawModelOutput"].asDouble, 0.0)
        assertEquals(0.0, qc["declaredOutputMin"].asDouble, 0.0)
        assertEquals(100.0, qc["declaredOutputMax"].asDouble, 0.0)

        val usage = coordinator.modelUsageEntry(
            analyteSnapshot = analyte,
            compatibility = ModelCompatibilityResult.Compatible,
            batch = batch
        )
        assertEquals(1, usage["outOfDeclaredDomainCount"])
        assertEquals(listOf(1), usage["outOfDeclaredDomainSiteIndices"])
        assertEquals(
            listOf(GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE.name),
            usage["reasons"]
        )
    }

    @Test
    fun `深度学习全部孔输出离域时结果仍标记为仅信号`() {
        val coordinator = coordinator()
        val analyte = deepLearningAnalyteSnapshot()
        val measurement = measurement(siteIndex = 0, primaryFeatureValue = 10.0)
            .copy(analyteId = "cea")
        val failure = GridDeepLearningSiteFailure(
            siteIndex = 0,
            reason = GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE,
            rawModelOutput = -3.0,
            transformedModelOutput = -3.0,
            declaredOutputMin = 0.0,
            declaredOutputMax = 100.0,
            modelSnapshotJson = "{\"model\":\"shared\"}"
        )

        val batch = coordinator.applyDeepLearningBatchResult(
            measurements = listOf(measurement),
            analyteSnapshot = analyte,
            compatibility = ModelCompatibilityResult.Compatible,
            execution = GridDeepLearningBatchResult.Success(
                predictions = emptyMap(),
                siteFailures = mapOf(0 to failure)
            )
        )

        assertTrue(batch.modelExecutable)
        assertTrue(batch.isSignalOnlyResult)
        assertEquals("signal_only", batch.execution)
        assertEquals(0, batch.quantifiedCount)
        assertEquals(1, batch.unavailableCount)
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
        assertEquals(2, batch.unavailableCount)
        assertEquals(2, batch.retestCount)
        assertEquals("signal_only", batch.execution)
        assertTrue(batch.measurements.all { measurement ->
            measurement.concentrationValue == null &&
                measurement.quantificationState == "UNAVAILABLE" &&
                measurement.quantificationVersion == ENDPOINT_QUANTIFIER_VERSION &&
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
                standardAssignment(base.template.id, "cea-90", 0, 1, "cea", 90.0),
                sampleAssignment(base.template.id, "cea-sample", 0, 2, "cea"),
                standardAssignment(base.template.id, "afp-1", 1, 0, "afp", 1.0),
                standardAssignment(base.template.id, "afp-9", 1, 1, "afp", 9.0),
                sampleAssignment(base.template.id, "afp-sample", 1, 2, "afp")
            )
        )
        // CEA 的 10/90 标准对应信号 10/90，样本信号 60；AFP 的 1/9 标准对应
        // 信号 20/100，样本信号 70。标准浓度必须位于各自项目硬量程内，两条曲线
        // 共享算法，但单位与标定范围仍必须完全独立。
        val quant = quantResult(
            rows = 2,
            columns = 3,
            signals = listOf(10.0, 90.0, 60.0, 20.0, 100.0, 70.0)
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
        assertEquals(90.0, cea.analysisModel.model.reliableRangeMax, 0.0)
        assertEquals(0.0, requireNotNull(afp.templateConfig.reliableRangeMin), 0.0)
        assertEquals(10.0, requireNotNull(afp.templateConfig.reliableRangeMax), 0.0)
        assertEquals(1.0, afp.analysisModel.model.reliableRangeMin, 0.0)
        assertEquals(9.0, afp.analysisModel.model.reliableRangeMax, 0.0)

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
        assertEquals(CalibrationPolicy.DEFAULT_FUNCTIONS.size, resultSet.functionResults.size)
        assertTrue(resultSet.functionResults.all { result ->
            CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS in result.failureReasons
        })
    }

    @Test
    fun `无参考孔的比色现场标定保留经典灰度并只跳过DeltaE`() {
        val base = validSnapshot()
        val templateId = base.template.id
        val colorimetricAnalyte = base.analytes.single().let { source ->
            source.copy(
                templateConfig = source.templateConfig.copy(displayConfigJson = null),
                analysisModel = source.analysisModel.copy(
                    model = source.analysisModel.model.copy(
                        detectionMode = DetectionModality.COLORIMETRIC.code,
                        primaryFeature = AnalysisPrimaryFeature.GRAY_LUMINOSITY.code,
                        processorName = COLORIMETRIC_PROCESSOR_NAME,
                        processorVersion = COLORIMETRIC_PROCESSOR_VERSION
                    )
                ),
                onsiteSelectedFeatures = listOf(
                    AnalysisPrimaryFeature.DELTA_E_2000.code,
                    AnalysisPrimaryFeature.GRAY_LUMINOSITY.code
                ),
                onsiteSelectedFunctions = listOf(FittingFunction.LINEAR.identifier)
            )
        }
        val snapshot = base.copy(
            template = base.template.copy(detectionMode = DetectionModality.COLORIMETRIC.code),
            acquisitionProfile = base.acquisitionProfile.copy(
                supportedModesJson = "[\"COLORIMETRIC\"]"
            ),
            analytes = listOf(colorimetricAnalyte),
            // 故意不设置空白或参考位：三个标准孔足以验证经典灰度直接信号。
            siteAssignments = listOf(
                standardAssignment(templateId, "standard-0", 0, 0, "cea", 0.0),
                standardAssignment(templateId, "standard-1", 0, 1, "cea", 10.0),
                standardAssignment(templateId, "standard-2", 1, 0, "cea", 20.0),
                sampleAssignment(templateId, "sample", 1, 1, "cea")
            )
        )

        val resultSet = coordinator().previewOnsiteCalibration(
            snapshot = snapshot,
            quant = quantResult(2, 2, listOf(10.0, 20.0, 30.0, 40.0)),
            analyteId = "cea"
        )

        assertEquals(listOf(FittingFunction.LINEAR), resultSet.functionResults.map { it.function })
        assertTrue(resultSet.candidates.isNotEmpty())
        assertTrue(resultSet.candidates.all {
            it.primaryFeature == AnalysisPrimaryFeature.GRAY_LUMINOSITY
        })

        // 用户应用候选后，正式运行必须冻结同一主特征和同一处理器身份；否则兼容门控会在
        // “开始分析”阶段把刚刚拟合成功的曲线错误降级成仅信号。
        val selected = requireNotNull(resultSet.candidates.first())
        val frozen = coordinator().applyOnsiteCalibrationSelection(
            snapshot = snapshot,
            resultSet = resultSet,
            selectedCandidateId = selected.id,
            runId = "run-colorimetric-no-reference"
        ).analytes.single()
        assertEquals(AnalysisPrimaryFeature.GRAY_LUMINOSITY.code, frozen.analysisModel.model.primaryFeature)
        assertEquals(COLORIMETRIC_PROCESSOR_NAME, frozen.analysisModel.model.processorName)
        assertEquals(COLORIMETRIC_PROCESSOR_VERSION, frozen.analysisModel.model.processorVersion)
        assertEquals(
            ModelCompatibilityResult.Compatible,
            AnalysisModelCompatibilityChecker.check(
                model = frozen.analysisModel.model,
                request = ModelCompatibilityRequest(
                    analyteId = "cea",
                    modality = DetectionModality.COLORIMETRIC,
                    inputProtocol = InputProtocol.ENDPOINT_ONLY,
                    primaryFeature = AnalysisPrimaryFeature.GRAY_LUMINOSITY,
                    carrierType = CarrierType.MICROFLUIDIC_CHIP,
                    acquisitionProfileId = snapshot.acquisitionProfile.id,
                    processorName = COLORIMETRIC_PROCESSOR_NAME,
                    processorVersion = COLORIMETRIC_PROCESSOR_VERSION
                )
            )
        )
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
