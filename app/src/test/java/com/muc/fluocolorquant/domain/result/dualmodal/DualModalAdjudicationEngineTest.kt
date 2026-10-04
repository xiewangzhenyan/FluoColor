package com.muc.fluocolorquant.domain.result.dualmodal

import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从两次运行快照汇总读数、计算阳控证据并给出判定的引擎行为测试。 */
class DualModalAdjudicationEngineTest {

    @Test
    fun `两侧可用且一致时取两侧中位数的均值`() {
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.5, 9.5))
        val flu = run("flu", "FLUORESCENCE", sample = listOf(10.6, 11.0, 10.2))

        val reading = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single()

        assertEquals(10.0, reading.colorimetric.concentration!!, 1e-12)
        assertEquals(10.6, reading.fluorescence.concentration!!, 1e-12)
        assertEquals(0.6 / 10.3 * 100.0, reading.deltaPercent!!, 1e-9)
        assertEquals(DualModalDecision.FUSE, reading.decision)
        assertEquals(10.3, reading.suggestedConcentration!!, 1e-12)
        assertEquals(DualModalSideStatus.USABLE, reading.colorimetric.status)
        assertEquals(3, reading.colorimetric.validSiteCount)
        assertEquals(0.0, reading.colorimetric.qcEvidence.single().relativeDeviation!!, 1e-12)
    }

    @Test
    fun `比色阳控偏差超阈时采信荧光`() {
        // 预测信号 = 2 × 25 + 1 = 51；实测 66.3，偏差 0.30 > 0.15
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.0, 10.0), qcSignal = 66.3)
        val flu = run("flu", "FLUORESCENCE", sample = listOf(14.0, 14.0, 14.0))

        val reading = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single()

        val evidence = reading.colorimetric.qcEvidence.single()
        assertEquals(51.0, evidence.predictedSignal!!, 1e-12)
        assertEquals(0.3, evidence.relativeDeviation!!, 1e-12)
        assertTrue(evidence.exceeded)
        assertEquals(DualModalSideStatus.QC_ABNORMAL, reading.colorimetric.status)
        assertEquals(DualModalDecision.ADOPT_FLUORESCENCE, reading.decision)
        assertEquals(DualModalDecisionReason.COLORIMETRIC_UNUSABLE, reading.reason)
        assertEquals(14.0, reading.suggestedConcentration!!, 1e-12)
    }

    @Test
    fun `荧光定位不可信时采信比色`() {
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.0, 10.0))
        val flu = run("flu", "FLUORESCENCE", sample = listOf(30.0, 30.0, 30.0), trusted = false)

        val reading = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single()

        assertEquals(DualModalSideStatus.LOCALIZATION_UNTRUSTED, reading.fluorescence.status)
        assertNull("一侧不可用时不计算 δ", reading.deltaPercent)
        assertEquals(DualModalDecision.ADOPT_COLORIMETRIC, reading.decision)
        assertEquals(10.0, reading.suggestedConcentration!!, 1e-12)
    }

    @Test
    fun `两侧都正常但不一致时建议复检`() {
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.0, 10.0))
        val flu = run("flu", "FLUORESCENCE", sample = listOf(20.0, 20.0, 20.0))

        val reading = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single()

        assertTrue(reading.alarm)
        assertEquals(DualModalDecision.RETEST, reading.decision)
        assertEquals(DualModalDecisionReason.DISCREPANCY_UNATTRIBUTED, reading.reason)
        assertNull(reading.suggestedConcentration)
    }

    @Test
    fun `只有界限而没有有效浓度的一侧视为无浓度`() {
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.0, 10.0), state = "BOUND_ONLY")
        val flu = run("flu", "FLUORESCENCE", sample = listOf(12.0, 12.0, 12.0))

        val reading = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single()

        assertNull(reading.colorimetric.concentration)
        assertEquals(0, reading.colorimetric.validSiteCount)
        assertEquals(3, reading.colorimetric.totalSiteCount)
        assertEquals(DualModalSideStatus.NO_CONCENTRATION, reading.colorimetric.status)
        assertEquals(DualModalDecision.ADOPT_FLUORESCENCE, reading.decision)
    }

    @Test
    fun `预测信号接近零时偏差分母取下限`() {
        // 截距 −49.98 使预测信号 = 2 × 25 − 49.98 = 0.02，低于分母下限 0.05
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0, 10.0, 10.0), intercept = -49.98, qcSignal = 0.03)
        val flu = run("flu", "FLUORESCENCE", sample = listOf(10.0, 10.0, 10.0))

        val evidence = DualModalAdjudicationEngine.adjudicate(col, flu).readings.single().colorimetric.qcEvidence.single()

        assertEquals(0.01 / 0.05, evidence.relativeDeviation!!, 1e-9)
        assertTrue(evidence.exceeded)
    }

    @Test
    fun `配对检查按模式排好两侧并拒绝不兼容的运行`() {
        val col = run("col", "COLORIMETRIC", sample = listOf(10.0))
        val flu = run("flu", "FLUORESCENCE", sample = listOf(10.0))

        val ordered = DualModalAdjudicationEngine.check(flu, col) as DualModalPairCheck.Compatible
        assertEquals("col", ordered.colorimetric.runId)
        assertEquals("flu", ordered.fluorescence.runId)

        val sameMode = DualModalAdjudicationEngine.check(col, col.copy(runId = "col2"))
        assertTrue(DualModalIncompatibility.SAME_DETECTION_MODE in (sameMode as DualModalPairCheck.Incompatible).reasons)

        val otherGrid = DualModalAdjudicationEngine.check(col, flu.copy(rows = 5))
        assertTrue(DualModalIncompatibility.GRID_SIZE_MISMATCH in (otherGrid as DualModalPairCheck.Incompatible).reasons)

        val moved = flu.copy(sites = flu.sites.map { site ->
            if (site.roleCode == "SAMPLE") site.copy(sampleSlot = "S9") else site
        })
        val layout = DualModalAdjudicationEngine.check(col, moved)
        assertTrue(DualModalIncompatibility.SITE_LAYOUT_MISMATCH in (layout as DualModalPairCheck.Incompatible).reasons)

        val spectrum = DualModalAdjudicationEngine.check(col, flu.copy(detectionMode = "SPECTRUM"))
        assertTrue(DualModalIncompatibility.UNSUPPORTED_DETECTION_MODE in (spectrum as DualModalPairCheck.Incompatible).reasons)
        assertFalse(DualModalIncompatibility.SAME_DETECTION_MODE in spectrum.reasons)
    }

    // ---------------------------------------------------------------- 夹具

    /** 一个分析物、一个样本（若干重复位点）与一个阳控水平（25，两个重复位点）的最小阵列。 */
    private fun run(
        runId: String,
        mode: String,
        sample: List<Double>,
        trusted: Boolean = true,
        state: String = "QUANTIFIED",
        slope: Double = 2.0,
        intercept: Double = 1.0,
        qcSignal: Double = slope * 25.0 + intercept
    ): ArrayResultSnapshot {
        val sites = sample.mapIndexed { index, concentration ->
            site(index, "SAMPLE", sampleSlot = "S1", standard = null, measurement(index, mode, 0.0, concentration, state))
        } + listOf(0, 1).map { k ->
            val index = sample.size + k
            site(index, "POSITIVE_CONTROL", sampleSlot = null, standard = 25.0, measurement(index, mode, qcSignal, 25.0, "QUANTIFIED"))
        }
        return ArrayResultSnapshot(
            runId = runId,
            projectId = "project-$runId",
            projectName = "项目 $runId",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = mode,
            carrier = ArrayCarrierResult("chip", "测试芯片", "MICROFLUIDIC", 1, "SQUARE", null),
            rows = 1,
            columns = sites.size,
            analytes = listOf(
                ArrayAnalyteResult(
                    analyteId = "afp",
                    name = "AFP",
                    displayOrder = 0,
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.1,
                    reliableRangeMax = 500.0,
                    modelId = "curve",
                    modelName = "曲线",
                    modelType = "CURVE_FIT",
                    modelVersion = 1,
                    primaryFeature = "signal",
                    processorName = "test",
                    processorVersion = "1",
                    fittingFunction = "linear",
                    fittingParameters = mapOf("a" to slope, "b" to intercept)
                )
            ),
            sites = sites,
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 100,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = trusted,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = sites.size,
                    outlierCount = 0,
                    meanConfidence = 0.95
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun site(
        index: Int,
        role: String,
        sampleSlot: String?,
        standard: Double?,
        measurement: ArraySiteMeasurementResult
    ) = ArrayPhysicalSiteResult(
        siteIndex = index,
        rowIndex = 0,
        columnIndex = index,
        siteKey = "R1C${index + 1}",
        enabled = true,
        roleCode = role,
        analyteId = "afp",
        defaultSampleSlot = sampleSlot,
        sampleSlot = sampleSlot,
        overrideReason = null,
        standardConcentration = standard,
        repeatGroup = null,
        referenceScope = null,
        geometry = ArraySiteGeometry(
            rectified = GridPoint(index * 10.0, 0.0),
            original = GridPoint(index * 10.0, 0.0),
            confidence = 0.95,
            source = GridPointSource.CANDIDATE_REFINED,
            flags = emptySet()
        ),
        measurements = listOf(measurement)
    )

    private fun measurement(
        index: Int,
        mode: String,
        signal: Double,
        concentration: Double,
        state: String
    ) = ArraySiteMeasurementResult(
        measurementId = index.toLong(),
        analyteId = "afp",
        detectionMode = mode,
        primaryFeatureName = "signal",
        primaryFeatureValue = signal,
        concentrationValue = concentration,
        concentrationUnit = "ng/mL",
        reliableRangeStatus = null,
        quantificationState = state,
        backgroundValue = null,
        signalToNoiseRatio = null,
        confidence = null,
        signalDetectable = true,
        qualityReliable = true,
        processorName = "test",
        processorVersion = "1",
        modelSnapshotJson = null,
        rawSignalJson = "{}",
        correctedSignalJson = null,
        qcJson = null,
        quantificationQcJson = null,
        qc = ArrayMeasurementQc(
            geometrySourceCode = null,
            geometryFlags = emptySet(),
            photometryFlags = emptySet(),
            quantificationStatus = null,
            quantificationScope = null,
            quantificationReason = null
        ),
        detail = ArrayMeasurementDetail.LegacyUnparsed(rawSignalJson = "{}", correctedSignalJson = null)
    )
}
