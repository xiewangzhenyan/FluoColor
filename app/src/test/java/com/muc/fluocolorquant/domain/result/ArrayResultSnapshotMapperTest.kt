package com.muc.fluocolorquant.domain.result

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.LabPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.SitePhotometryQc
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用阵列结果快照的纯 JVM 契约测试。
 *
 * 测试刻意只把运行时冻结证据交给 Mapper，确保实现不会偷偷查询当前模板、当前模型
 * 或项目后来被修改的样本映射。
 */
class ArrayResultSnapshotMapperTest {

    @Test
    fun `十乘十快照重建全部物理位点和运行时样本映射`() {
        val fixture = fixture(rows = 10, columns = 10)

        val result = ArrayResultSnapshotMapper.map(fixture.source)

        assertTrue(result is ArrayResultLoadResult.Success)
        val snapshot = (result as ArrayResultLoadResult.Success).snapshot
        assertEquals(10, snapshot.rows)
        assertEquals(10, snapshot.columns)
        assertEquals(100, snapshot.sites.size)
        assertEquals("R01C01", snapshot.sites.first().siteKey)
        assertEquals("R10C10", snapshot.sites.last().siteKey)
        assertEquals("SAMPLE", snapshot.sites[1].roleCode)
        assertEquals(ANALYTE_ID, snapshot.sites[1].analyteId)
        assertEquals("运行时样本-01", snapshot.sites[1].sampleSlot)
        val analyte = snapshot.analytes.single()
        assertEquals("linear", analyte.fittingFunction)
        assertEquals(1.0, analyte.fittingParameters["a"] ?: Double.NaN, 1e-9)
        assertEquals(2, analyte.calibrationPoints.size)
        assertEquals(0.0, analyte.projectRangeMin ?: Double.NaN, 1e-9)
        assertEquals(100.0, analyte.projectRangeMax ?: Double.NaN, 1e-9)
        assertEquals(28.0, analyte.calibrationRangeMin ?: Double.NaN, 1e-9)
        assertEquals(34.0, analyte.calibrationRangeMax ?: Double.NaN, 1e-9)
    }

    @Test
    fun `十五乘十五快照固定输出二百二十五个物理位点`() {
        val fixture = fixture(rows = 15, columns = 15)

        val result = ArrayResultSnapshotMapper.map(fixture.source)

        assertTrue(result is ArrayResultLoadResult.Success)
        val snapshot = (result as ArrayResultLoadResult.Success).snapshot
        assertEquals(225, snapshot.sites.size)
        assertEquals(224, snapshot.sites.last().siteIndex)
        assertEquals(14, snapshot.sites.last().rowIndex)
        assertEquals(14, snapshot.sites.last().columnIndex)
    }

    @Test
    fun `缺失运行配置快照返回稳定错误而不是回读项目快照`() {
        val fixture = fixture(rows = 10, columns = 10)
        val broken = fixture.source.copy(
            run = fixture.source.run.copy(effectiveConfigSnapshotJson = null)
        )

        val result = ArrayResultSnapshotMapper.map(broken)

        assertEquals(
            ArrayResultErrorCode.MISSING_EFFECTIVE_CONFIG_SNAPSHOT,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `PGGrid行列与冻结载体不一致时拒绝重建`() {
        val fixture = fixture(rows = 10, columns = 10)
        val wrongGrid = grid(rows = 4, columns = 4)
        val broken = fixture.source.copy(
            run = fixture.source.run.copy(frameQcJson = frameQcJson(wrongGrid))
        )

        val result = ArrayResultSnapshotMapper.map(broken)

        assertEquals(
            ArrayResultErrorCode.INCONSISTENT_GEOMETRY,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `位点测量索引越界时返回稳定错误`() {
        val fixture = fixture(rows = 10, columns = 10)
        val brokenMeasurement = measurement(siteIndex = 100, correctedSignalJson = null)

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(brokenMeasurement))
        )

        assertEquals(
            ArrayResultErrorCode.INVALID_MEASUREMENT,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `测量模态与运行快照不一致时拒绝拼接`() {
        val fixture = fixture(rows = 10, columns = 10)
        val wrongMode = measurement(
            siteIndex = 1,
            correctedSignalJson = null,
            detectionMode = "FLUORESCENCE"
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(wrongMode))
        )

        assertEquals(
            ArrayResultErrorCode.INVALID_MEASUREMENT,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `标定范围外推测量允许携带有限浓度`() {
        val fixture = fixture(rows = 10, columns = 10)
        val extrapolated = measurement(
            siteIndex = 1,
            correctedSignalJson = null
        ).copy(
            concentrationValue = 123.0,
            concentrationUnit = "ng/mL",
            reliableRangeStatus = "ABOVE_RANGE"
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(extrapolated))
        )

        val mapped = (result as ArrayResultLoadResult.Success)
            .snapshot.sites[1].measurements.single()
        assertEquals(123.0, mapped.concentrationValue ?: Double.NaN, 0.0)
        assertEquals("ABOVE_RANGE", mapped.reliableRangeStatus)
    }

    @Test
    fun `超项目量程测量仍携带浓度时拒绝伪结果`() {
        val fixture = fixture(rows = 10, columns = 10)
        val inconsistent = measurement(
            siteIndex = 1,
            correctedSignalJson = null
        ).copy(
            concentrationValue = 123.0,
            concentrationUnit = "ng/mL",
            reliableRangeStatus = "ABOVE_PROJECT_RANGE"
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(inconsistent))
        )

        assertEquals(
            ArrayResultErrorCode.INVALID_MEASUREMENT,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `可信边界外的单侧界限允许重建且不得携带伪精确浓度`() {
        val fixture = fixture(rows = 10, columns = 10)
        val boundOnly = measurement(
            siteIndex = 1,
            correctedSignalJson = null
        ).copy(
            concentrationValue = null,
            concentrationUnit = "ng/mL",
            reliableRangeStatus = "BELOW_TRUSTED_RANGE",
            quantificationState = "BOUND_ONLY",
            concentrationUpperBound = 6.0,
            censoringDirection = "UPPER_BOUND"
        )

        val successful = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(boundOnly))
        )

        val mapped = (successful as ArrayResultLoadResult.Success)
            .snapshot.sites[1].measurements.single()
        assertEquals("BELOW_TRUSTED_RANGE", mapped.reliableRangeStatus)
        assertEquals("BOUND_ONLY", mapped.quantificationState)
        assertEquals(6.0, mapped.concentrationUpperBound ?: Double.NaN, 0.0)
        assertNull(mapped.concentrationValue)

        val inconsistent = ArrayResultSnapshotMapper.map(
            fixture.source.copy(
                measurements = listOf(boundOnly.copy(concentrationValue = 5.5))
            )
        )
        assertEquals(
            ArrayResultErrorCode.INVALID_MEASUREMENT,
            (inconsistent as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `声明版本的比色校正JSON损坏时不得降级为旧版猜测`() {
        val fixture = fixture(rows = 10, columns = 10)
        val brokenMeasurement = measurement(
            siteIndex = 1,
            correctedSignalJson = """{"schemaVersion":"colorimetric-corrected-signal-v1"}"""
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(measurements = listOf(brokenMeasurement))
        )

        assertEquals(
            ArrayResultErrorCode.CORRUPT_DECLARED_SIGNAL_SCHEMA,
            (result as ArrayResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `比色版本化校正信号重建参考索引和白平衡上下文`() {
        val fixture = fixture(rows = 10, columns = 10)
        val base = basePhotometry(siteIndex = 1, columns = 10)
        val site = ColorimetricSitePhotometry(
            base = base,
            whiteBalancedRgb = RgbPhotometry(120.0, 121.0, 122.0),
            lab = LabPhotometry(50.0, 1.0, 2.0),
            deltaE2000 = 3.5,
            opticalDensity = 0.12,
            primaryFeature = com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.DELTA_E_2000,
            primaryFeatureValue = 3.5,
            qc = base.qc
        )
        val corrected = gson.toJson(
            linkedMapOf(
                "schemaVersion" to "colorimetric-corrected-signal-v1",
                "site" to site,
                "calibrationContext" to linkedMapOf(
                    "referenceIndices" to listOf(0),
                    "whiteBalanceGains" to RgbPhotometry(1.0, 1.1, 0.9),
                    "referenceRgb" to RgbPhotometry(100.0, 100.0, 100.0),
                    "referenceLab" to LabPhotometry(45.0, 0.0, 0.0)
                )
            )
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(
                measurements = listOf(
                    measurement(
                        siteIndex = 1,
                        correctedSignalJson = corrected,
                        rawSignalJson = gson.toJson(base)
                    )
                )
            )
        ) as ArrayResultLoadResult.Success

        val detail = result.snapshot.sites[1].measurements.single().detail
            as ArrayMeasurementDetail.Colorimetric
        assertEquals(listOf(0), detail.calibrationContext.referenceIndices)
        assertEquals(3.5, requireNotNull(detail.site.deltaE2000), 0.0)
        assertEquals(1.1, detail.calibrationContext.whiteBalanceGains.green, 0.0)
    }

    @Test
    fun `荧光旧有位点结构解析为荧光专用详情`() {
        val fixture = fixture(rows = 10, columns = 10, detectionMode = "FLUORESCENCE")
        val base = basePhotometry(siteIndex = 1, columns = 10)
        val fluorescence = FluorescenceSitePhotometry(
            base = base,
            channel = FluorescenceChannel.GREEN,
            netIntensity = 20.0,
            integratedIntensity = 300.0,
            signalToNoiseRatio = 8.0,
            primaryFeature = com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.FLUORESCENCE_SNR,
            primaryFeatureValue = 8.0,
            qc = base.qc
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(
                measurements = listOf(
                    measurement(
                        siteIndex = 1,
                        correctedSignalJson = gson.toJson(fluorescence),
                        rawSignalJson = gson.toJson(base),
                        detectionMode = "FLUORESCENCE",
                        primaryFeatureName = "FLUORESCENCE_SNR"
                    )
                )
            )
        ) as ArrayResultLoadResult.Success

        val detail = result.snapshot.sites[1].measurements.single().detail
            as ArrayMeasurementDetail.Fluorescence
        assertEquals(FluorescenceChannel.GREEN, detail.site.channel)
        assertEquals(8.0, detail.site.signalToNoiseRatio, 0.0)
    }

    @Test
    fun `全局参考证据挂到物理位点且不伪装成分析物浓度`() {
        val fixture = fixture(rows = 10, columns = 10)
        val frozen = TemplateProjectSnapshotCodec.decode(
            requireNotNull(fixture.source.run.effectiveConfigSnapshotJson)
        )
        val referenceSnapshot = frozen.copy(
            siteAssignments = frozen.siteAssignments.mapIndexed { index, assignment ->
                if (index == 0) assignment.copy(
                    analyteId = null,
                    roleType = "REFERENCE"
                ) else assignment
            }
        )
        val base = basePhotometry(siteIndex = 0, columns = 10)
        val referenceJson = gson.toJson(
            linkedMapOf(
                "schemaVersion" to "colorimetric-reference-evidence-v1",
                "roleType" to "REFERENCE",
                "site" to base
            )
        )
        val referenceMeasurement = measurement(
            siteIndex = 0,
            correctedSignalJson = null,
            rawSignalJson = referenceJson,
            analyteId = null,
            primaryFeatureName = "COLORIMETRIC_REFERENCE_EVIDENCE"
        )

        val result = ArrayResultSnapshotMapper.map(
            fixture.source.copy(
                run = fixture.source.run.copy(
                    effectiveConfigSnapshotJson = TemplateProjectSnapshotCodec.encode(referenceSnapshot)
                ),
                measurements = listOf(referenceMeasurement)
            )
        ) as ArrayResultLoadResult.Success

        val mapped = result.snapshot.sites.first().measurements.single()
        assertEquals(null, mapped.analyteId)
        assertEquals(null, mapped.concentrationValue)
        assertTrue(mapped.detail is ArrayMeasurementDetail.ColorimetricReference)
    }

    private fun fixture(
        rows: Int,
        columns: Int,
        detectionMode: String = "COLORIMETRIC"
    ): Fixture {
        val snapshot = snapshot(rows, columns, detectionMode)
        val override = TemplateProjectOverrideSnapshot(
            sampleSlotMapping = mapOf("R01C02" to "运行时样本-01")
        )
        val project = Project(
            id = PROJECT_ID,
            name = "微流控结果测试",
            detectionMode = detectionMode,
            recognitionType = "AUTO",
            imageUri = "content://endpoint",
            rows = rows,
            columns = columns,
            createTime = Date(1_000L),
            userId = "user-id",
            lastRunTimestamp = Date(2_000L),
            analysisMethod = "CURVE_FIT",
            templateId = TEMPLATE_ID,
            templateVersion = 1,
            // 项目字段故意放入不同 JSON；Mapper 必须忽略它，只读取运行字段。
            templateSnapshotJson = "{\"mustNotBeRead\":true}",
            overrideJson = "{\"mustNotBeRead\":true}"
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
            wellsDetected = rows * columns,
            effectiveConfigSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot),
            processingVersionJson = "{\"geometry\":\"pg-grid-v2.1\"}",
            frameQcJson = frameQcJson(grid(rows, columns)),
            configurationDeviationJson = TemplateProjectOverrideCodec.encode(override)
        )
        return Fixture(
            source = ArrayResultSnapshotSource(
                run = run,
                project = project,
                artifacts = emptyList(),
                measurements = emptyList()
            )
        )
    }

    private fun snapshot(rows: Int, columns: Int, detectionMode: String): TemplateProjectSnapshot {
        val analyte = Analyte(ANALYTE_ID, "CEA")
        val carrier = CarrierProfile(
            id = CARRIER_ID,
            name = "${rows}×${columns}微流控芯片",
            carrierType = "MICROFLUIDIC_CHIP",
            rows = rows,
            columns = columns,
            siteShape = "CIRCLE",
            locatorConfigJson = "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"
        )
        val acquisition = AcquisitionProfile(
            id = ACQUISITION_ID,
            name = "固定拍摄设备",
            supportedModesJson = "[\"$detectionMode\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "AUTO_LOCKED"
        )
        val template = ExperimentTemplate(
            id = TEMPLATE_ID,
            templateName = "比色微流控模板",
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
            detectionMode = detectionMode,
            readoutLayout = "GRID_SITES",
            acquisitionProfileId = ACQUISITION_ID,
            inputProtocol = "ENDPOINT_ONLY"
        )
        val model = AnalysisModel(
            id = MODEL_ID,
            name = "CEA线性曲线",
            modelType = "STANDARD_CURVE",
            analyteId = ANALYTE_ID,
            detectionMode = detectionMode,
            inputProtocol = "ENDPOINT_ONLY",
            primaryFeature = if (detectionMode == "FLUORESCENCE") {
                "FLUORESCENCE_SNR"
            } else {
                "DELTA_E_2000"
            },
            processorName = if (detectionMode == "FLUORESCENCE") {
                "fluorescence-photometry"
            } else {
                "colorimetric-photometry"
            },
            processorVersion = "v1",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 28.0,
            reliableRangeMax = 34.0,
            status = "PUBLISHED",
            version = 2
        )
        val templateConfig = TemplateAnalyteConfig(
            id = "config-1",
            templateId = TEMPLATE_ID,
            analyteId = ANALYTE_ID,
            analysisModelId = MODEL_ID,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0
        )
        val analyteSnapshot = TemplateProjectAnalyteSnapshot(
            analyte = analyte,
            templateConfig = templateConfig,
            analysisModel = AnalysisModelBundle(
                model = model,
                standardCurve = StandardCurveDefinition(
                    analysisModelId = MODEL_ID,
                    fittingFunction = "LINEAR",
                    parametersJson = "{\"a\":1.0,\"b\":0.0}",
                    monotonicDirection = "INCREASING"
                ),
                calibrationPoints = listOf(
                    CalibrationPoint(
                        id = "curve-point-1",
                        analysisModelId = MODEL_ID,
                        concentration = 1.0,
                        signalValue = 1.0,
                        repeatIndex = 0
                    ),
                    CalibrationPoint(
                        id = "curve-point-2",
                        analysisModelId = MODEL_ID,
                        concentration = 10.0,
                        signalValue = 10.0,
                        repeatIndex = 1
                    )
                )
            )
        )
        val assignments = List(rows * columns) { index ->
            TemplateSiteAssignment(
                id = "site-$index",
                templateId = TEMPLATE_ID,
                rowIndex = index / columns,
                columnIndex = index % columns,
                analyteId = ANALYTE_ID,
                roleType = "SAMPLE",
                defaultSampleSlot = "默认样本-${index + 1}",
                enabled = true
            )
        }
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_500L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(analyteSnapshot),
            siteAssignments = assignments
        )
    }

    private fun grid(rows: Int, columns: Int): PgGridResult {
        val sites = List(rows * columns) { index ->
            val row = index / columns
            val column = index % columns
            GridLocalizedSite(
                key = GridSiteKey(row, column),
                siteIndex = index,
                rectified = GridPoint(column * 10.0 + 5.0, row * 10.0 + 5.0),
                original = GridPoint(column * 11.0 + 6.0, row * 11.0 + 6.0),
                confidence = 0.95,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = rows,
            columns = columns,
            rectifiedWidth = columns * 10,
            rectifiedHeight = rows * 10,
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "test",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint(columns * 10.0, 0.0),
                GridPoint(columns * 10.0, rows * 10.0),
                GridPoint(0.0, rows * 10.0)
            ),
            homography = GridHomography(
                forward = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                inverse = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 1.0,
                trusted = true,
                observedRatio = 1.0,
                geometryRmsePx = 0.2,
                inlierCount = sites.size,
                outlierCount = 0,
                meanConfidence = 0.95
            ),
            frameQc = emptyList(),
            locatorName = "test-pg-grid",
            locatorVersion = "2.1"
        ).requireValid()
    }

    private fun frameQcJson(grid: PgGridResult): String {
        val encodedGrid = PgGridJsonCodec.encode(grid)
        return gson.toJson(
            linkedMapOf(
                "frame" to linkedMapOf<String, Any>(
                    "geometry" to grid.geometry,
                    "issues" to grid.frameQc
                ),
                "pgGrid" to gson.fromJson(encodedGrid, JsonObject::class.java)
            )
        )
    }

    private fun measurement(
        siteIndex: Int,
        correctedSignalJson: String?,
        rawSignalJson: String = "{\"legacyRaw\":true}",
        detectionMode: String = "COLORIMETRIC",
        analyteId: String? = ANALYTE_ID,
        primaryFeatureName: String = "DELTA_E_2000"
    ): SiteMeasurement {
        return SiteMeasurement(
            runId = RUN_ID,
            siteIndex = siteIndex,
            analyteId = analyteId,
            detectionMode = detectionMode,
            rawSignalJson = rawSignalJson,
            correctedSignalJson = correctedSignalJson,
            primaryFeatureName = primaryFeatureName,
            primaryFeatureValue = 10.0,
            signalDetectable = true,
            qualityReliable = true,
            processorName = "colorimetric-photometry",
            processorVersion = "v1"
        )
    }

    private fun basePhotometry(siteIndex: Int, columns: Int): BaseSitePhotometry {
        val row = siteIndex / columns
        val column = siteIndex % columns
        val rgb = RgbPhotometry(100.0, 110.0, 120.0)
        val sigma = RgbPhotometry(2.0, 2.0, 2.0)
        val qc = SitePhotometryQc(
            flags = emptySet(),
            signalDetectable = true,
            qualityReliable = true
        )
        return BaseSitePhotometry(
            siteIndex = siteIndex,
            rowIndex = row,
            columnIndex = column,
            rectifiedCenter = GridPoint(column * 10.0 + 5.0, row * 10.0 + 5.0),
            originalCenter = GridPoint(column * 11.0 + 6.0, row * 11.0 + 6.0),
            roiMedianRgb = rgb,
            roiMedianGray = 108.0,
            backgroundMedianRgb = RgbPhotometry(10.0, 11.0, 12.0),
            backgroundMedianGray = 11.0,
            backgroundSigmaRgb = sigma,
            backgroundSigmaGray = 2.0,
            correctedMedianRgb = rgb,
            correctedMedianGray = 108.0,
            signalGray = 97.0,
            signalRatio = 9.8,
            correctedSignalGray = 97.0,
            integratedSignalRgb = RgbPhotometry(1_000.0, 1_100.0, 1_200.0),
            integratedSignalGray = 1_080.0,
            signalToNoiseRatio = 8.0,
            saturationRatio = 0.0,
            roiContaminationRatio = 0.0,
            hotPixelRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0,
            qc = qc
        )
    }

    private data class Fixture(val source: ArrayResultSnapshotSource)

    private companion object {
        val gson = Gson()
        const val PROJECT_ID = "project-result-test"
        const val RUN_ID = "run-result-test"
        const val TEMPLATE_ID = "template-result-test"
        const val CARRIER_ID = "carrier-result-test"
        const val ACQUISITION_ID = "acquisition-result-test"
        const val ANALYTE_ID = "analyte-cea"
        const val MODEL_ID = "model-cea-v2"
    }
}
