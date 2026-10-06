package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetTestFixtures.LEVELS
import kotlin.math.ln
import kotlin.math.log10
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DualNet 判读编排：门控、标定板追溯、每次运行只裁切一次、重标定与建议，以及各类失败的稳定原因。
 *
 * 假裁切把（位点号，运行）编码进位点裁切的第一个像素，假模型据此返回预设输出：标定板上 μ 等于
 * log10(名义浓度)，测试板上等于 log10(样本"真值")。这样不用真模型也能检验整条编排的数值去向。
 */
class DualNetAssessorTest {

    private val testCol = DualNetTestFixtures.run("test-col", "COLORIMETRIC", calibrationRunIds = listOf("cal-col"))
    private val testFlu = DualNetTestFixtures.run("test-flu", "FLUORESCENCE", calibrationRunIds = listOf("cal-flu"))
    private val calCol = DualNetTestFixtures.run("cal-col", "COLORIMETRIC", standards = true)
    private val calFlu = DualNetTestFixtures.run("cal-flu", "FLUORESCENCE", standards = true)
    private val found: suspend (String) -> DualNetCalibrationLookup = { DualNetCalibrationLookup.Found(DualNetRunPair(calCol, calFlu)) }

    /** 样本 S01–S12 的"真值"，取在网络评价范围内外各有分布的浓度。 */
    private val truth = (1..12).associate { column -> column to 0.3 * Math.pow(1.9, column.toDouble()) }

    private class FakeCrops(private val failRunId: String? = null) : DualNetCropSource {
        val requests = mutableMapOf<String, Set<Int>>()
        override fun crops(run: ArrayResultSnapshot, siteIndices: Set<Int>): Map<Int, ByteArray> {
            check(run.runId !in requests) { "同一次运行被重复裁切：${run.runId}" }
            requests[run.runId] = siteIndices
            if (run.runId == failRunId) throw DualNetUnavailableException(DualNetUnavailableReason.EVIDENCE_UNAVAILABLE, "原图缺失")
            val marker = if (run.runId.startsWith("cal")) 1 else 2
            return siteIndices.associateWith { site ->
                ByteArray(DualNetSpec.CROP_BYTES).also { bytes ->
                    bytes[0] = site.toByte()           // 第 0 像素 R：位点号（0–224）
                    bytes[1] = marker.toByte()         // 第 0 像素 G：1 为标定板，2 为测试板
                }
            }
        }
    }

    private inner class FakeModel(private val failure: DualNetUnavailableReason? = null) : DualNetModelRunner {
        override fun predict(input: FloatArray, count: Int): FloatArray {
            failure?.let { throw DualNetUnavailableException(it, "模型不可用") }
            val plane = DualNetSpec.CROP_SIZE * DualNetSpec.CROP_SIZE
            return FloatArray(count * DualNetSpec.OUTPUT_SIZE).also { out ->
                for (n in 0 until count) {
                    val base = n * DualNetSpec.CHANNELS * plane
                    val site = Math.round(input[base] * 255f) and 0xFF
                    val calibration = Math.round(input[base + plane] * 255f) == 1
                    val column = site % 15
                    val mu = if (calibration) log10(LEVELS[column - 1]) else log10(truth.getValue(column))
                    val logVariance = ln(0.01 * 0.01)
                    for (head in 0 until 3) {
                        out[n * 6 + 2 * head] = mu.toFloat()
                        out[n * 6 + 2 * head + 1] = logVariance.toFloat()
                    }
                }
            }
        }
    }

    @Test
    fun `正常配对时逐批重标定并给出融合建议，每次运行只裁切一次`() = runBlocking {
        val crops = FakeCrops()
        val assessment = DualNetAssessor(crops, FakeModel()).assess(DualNetRunPair(testCol, testFlu), found)

        val analyte = assessment.analytes.single()
        assertEquals(DualNetStatus.COMPLETED, analyte.status)
        assertEquals("cal-col", analyte.calibrationColorimetricRunId)
        assertEquals("cal-flu", analyte.calibrationFluorescenceRunId)
        assertEquals(setOf("test-col", "test-flu", "cal-col", "cal-flu"), crops.requests.keys)
        // 测试板：12 个样本列加同行三只参照；标定板：7 个重标定水平加参照。
        assertEquals(15 * 15, crops.requests.getValue("test-col").size)
        assertEquals(15 * (7 + 3), crops.requests.getValue("cal-col").size)
        analyte.recalibrations.forEach { map ->
            assertEquals(1.0, map.slope, 1e-6)
            assertEquals(0.0, map.intercept, 1e-6)
            assertEquals(7, map.levelCount)
        }
        assertEquals(12, analyte.readings.size)
        analyte.readings.forEach { reading ->
            val column = reading.sampleKey.removePrefix("S").toInt()
            assertEquals(DualNetDecision.ADOPT_FUSED, reading.decision)
            assertEquals(truth.getValue(column), reading.suggestedConcentration!!, truth.getValue(column) * 1e-5)
            assertEquals(15, reading.siteCount)
            val inRange = truth.getValue(column) in DualNetSpec.EVALUATED_RANGE_MIN..DualNetSpec.EVALUATED_RANGE_MAX
            assertEquals(inRange, reading.withinEvaluatedRange)
        }
    }

    @Test
    fun `测试板荧光侧定位不可信时退回比色头`() = runBlocking {
        val assessment = DualNetAssessor(FakeCrops(), FakeModel())
            .assess(DualNetRunPair(testCol, testFlu.copy(frame = testFlu.frame.copy(geometry = testFlu.frame.geometry.copy(trusted = false)))), found)

        val reading = assessment.analytes.single().readings.first()
        assertEquals(DualNetDecision.ADOPT_COLORIMETRIC, reading.decision)
        assertEquals(DualNetDecisionReason.COLORIMETRIC_FALLBACK, reading.reason)
        assertTrue(!reading.fluorescence.available && !reading.fused.available)
        assertNull(reading.deltaPercent)
    }

    @Test
    fun `各类失败只让网络判读不可用并给出稳定原因`() = runBlocking {
        suspend fun reasonOf(
            col: ArrayResultSnapshot = testCol,
            crops: DualNetCropSource = FakeCrops(),
            model: DualNetModelRunner = FakeModel(),
            lookup: suspend (String) -> DualNetCalibrationLookup = found
        ): DualNetUnavailableReason? = DualNetAssessor(crops, model).assess(DualNetRunPair(col, testFlu), lookup)
            .analytes.single().also { assertEquals(DualNetStatus.UNAVAILABLE, it.status) }.unavailableReason

        assertEquals(
            DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND,
            reasonOf(lookup = { DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND) })
        )
        assertEquals(DualNetUnavailableReason.EVIDENCE_UNAVAILABLE, reasonOf(crops = FakeCrops(failRunId = "cal-flu")))
        assertEquals(DualNetUnavailableReason.MODEL_UNAVAILABLE, reasonOf(model = FakeModel(DualNetUnavailableReason.MODEL_UNAVAILABLE)))
        assertEquals(DualNetUnavailableReason.UNIT_NOT_SUPPORTED, reasonOf(col = DualNetTestFixtures.run("test-col", "COLORIMETRIC", unit = "pg/mL")))
        assertEquals(DualNetUnavailableReason.LAYOUT_NOT_SUPPORTED, reasonOf(col = DualNetTestFixtures.run("test-col", "COLORIMETRIC", size = 10)))
        val sparseCalibration = calCol.copy(sites = calCol.sites.map { site ->
            if (site.roleCode == "STANDARD" && site.columnIndex !in 5..6) site.copy(standardConcentration = 500.0) else site
        })
        assertEquals(
            DualNetUnavailableReason.CALIBRATION_LEVELS_INSUFFICIENT,
            reasonOf(lookup = { DualNetCalibrationLookup.Found(DualNetRunPair(sparseCalibration, calFlu)) })
        )
        val untrustedCalibration = DualNetRunPair(
            calCol.copy(frame = calCol.frame.copy(geometry = calCol.frame.geometry.copy(trusted = false))),
            calFlu.copy(frame = calFlu.frame.copy(geometry = calFlu.frame.geometry.copy(trusted = false)))
        )
        assertEquals(DualNetUnavailableReason.RECALIBRATION_FAILED, reasonOf(lookup = { DualNetCalibrationLookup.Found(untrustedCalibration) }))
    }
}
