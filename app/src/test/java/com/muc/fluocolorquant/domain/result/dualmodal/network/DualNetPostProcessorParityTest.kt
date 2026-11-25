package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 端 DualNet 后处理与论文第四章 W3 参考实现（w3_analyze.py）的一致性检验。
 *
 * 夹具 dualnet/w3_postprocess_parity_cases.json 取自 W3 fold=none 在 W2 测试批次上的真实网络输出
 * （物理建模仿真），期望值由参考实现的 reading_features、recalibrate、net_decide 在双精度下给出；
 * 其中三组翻转了定位可信标记，用来覆盖融合头不可用时的退回分支。两边只差求和顺序，浓度与不确定度
 * 要求相对误差小于 1e-9，建议浓度（含"复检"）逐条相同。
 */
class DualNetPostProcessorParityTest {

    @Test
    fun `DualNet 读数、逐批重标定与建议在 W3 输出上与参考实现一致`() {
        val root = JsonParser.parseString(fixture()).asJsonObject
        val th = root.getAsJsonObject("thresholds")
        val thresholds = DualNetThresholds(
            colorimetricUncertainty = th["u_col"].asDouble,
            fluorescenceUncertainty = th["u_flu"].asDouble,
            fusedUncertainty = th["u_fuse"].asDouble,
            deltaPercent = th["delta"].asDouble
        )
        assertEquals(DualNetThresholds(), thresholds)
        val levels = root.getAsJsonArray("levels").map(JsonElement::getAsDouble)
        val recalibrationColumns = root.getAsJsonArray("recalibrationColumns").map(JsonElement::getAsInt)
        val decisions = mutableSetOf<DualNetDecision>()
        var compared = 0
        root.getAsJsonArray("cases").forEach { element ->
            val case = element.asJsonObject
            val key = case["key"].asString
            val trust = case.getAsJsonObject("trust")
            val calCol = trust["calibrationColorimetric"].asBoolean
            val calFlu = trust["calibrationFluorescence"].asBoolean
            val calibration = case.getAsJsonObject("calibration")
            val calibrationLevels = recalibrationColumns.map { column ->
                levels[column - 1] to DualNetPostProcessor.reading(calibration.outputs(column))
            }
            val usable = buildSet {
                if (calCol) add(DualNetHead.COLORIMETRIC)
                if (calFlu) add(DualNetHead.FLUORESCENCE)
                if (calCol && calFlu) add(DualNetHead.FUSED)
            }
            val recalibrations = DualNetPostProcessor.recalibrate(calibrationLevels, usable)
            val availability = DualNetHeadAvailability(
                colorimetric = trust["testColorimetric"].asBoolean,
                fluorescence = trust["testFluorescence"].asBoolean
            )
            val test = case.getAsJsonObject("test")
            case.getAsJsonArray("expected").forEach { item ->
                val expected = item.asJsonObject
                val column = expected["column"].asInt
                val reading = DualNetPostProcessor.decide(
                    analyteId = "cea",
                    sampleKey = "C$column",
                    raw = DualNetPostProcessor.reading(test.outputs(column)),
                    recalibrations = recalibrations,
                    availability = availability,
                    thresholds = thresholds
                )
                val label = "$key 第 $column 列"
                listOf(
                    Triple(reading.colorimetric, "col", availability.colorimetric),
                    Triple(reading.fluorescence, "flu", availability.fluorescence),
                    Triple(reading.fused, "fuse", availability.colorimetric && availability.fluorescence)
                ).forEach { (head, name, sideTrusted) ->
                    val expectedAvailable = expected["avail_$name"].asBoolean && sideTrusted
                    assertEquals("$label $name 可用性", expectedAvailable, head.available)
                    if (expectedAvailable) {
                        assertRelative("$label $name 浓度", expected["c_$name"].asDouble, head.concentration!!)
                        assertRelative("$label $name 不确定度", expected["u_$name"].asDouble, head.uncertainty!!)
                    }
                }
                if (reading.colorimetric.available && reading.fluorescence.available) {
                    assertRelative("$label δ", expected["delta"].asDouble, reading.deltaPercent!!)
                }
                val suggestion = expected["net_fuse_delta"].takeUnless(JsonElement::isJsonNull)?.asDouble
                if (suggestion == null) {
                    assertNull("$label 应建议复检", reading.suggestedConcentration)
                    assertEquals("$label 判定", DualNetDecision.RETEST, reading.decision)
                } else {
                    assertNotNull("$label 不应建议复检", reading.suggestedConcentration)
                    assertRelative("$label 建议浓度", suggestion, reading.suggestedConcentration!!)
                }
                decisions += reading.decision
                compared += 1
            }
        }
        assertEquals("夹具应包含 6 组共 72 个读数", 72, compared)
        assertTrue(
            "夹具应覆盖融合、退回单模态与复检",
            decisions.containsAll(setOf(DualNetDecision.ADOPT_FUSED, DualNetDecision.RETEST)) &&
                (DualNetDecision.ADOPT_COLORIMETRIC in decisions || DualNetDecision.ADOPT_FLUORESCENCE in decisions)
        )
    }

    /** 夹具中某一列 15 个重复孔的逐孔 6 维输出。 */
    private fun JsonObject.outputs(column: Int): List<FloatArray> =
        getAsJsonArray(column.toString()).map { row ->
            (row as JsonArray).map { it.asDouble.toFloat() }.toFloatArray()
        }

    private fun assertRelative(message: String, expected: Double, actual: Double) {
        assertTrue("$message：期望 $expected，实际 $actual", abs(actual - expected) <= 1e-9 * maxOf(1.0, abs(expected)))
    }

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResource("dualnet/w3_postprocess_parity_cases.json")) {
            "缺少 DualNet 后处理一致性夹具"
        }.readText(Charsets.UTF_8)
}
