package com.muc.fluocolorquant.domain.result.dualmodal

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 端双模态判定规则与论文第三章 Python 参考实现（dual_rule.adjudicate）的逐条一致性检验。
 *
 * 夹具 dualmodal/w4_rule_parity_cases.json 取自论文 W4 评价的测试批次读数（物理建模仿真），
 * 按判定 × 两侧状态分层抽样，覆盖四种判定与无浓度、定位不可信、无质控证据、质控超阈等情形；
 * 期望值由参考实现给出。两边做同样的浮点运算，所以判定、输出值与 δ 都要求逐位相等。
 */
class DualModalRuleParityTest {

    @Test
    fun `App 判定规则在 W4 测试读数上与参考实现逐条一致`() {
        val root = loadFixture()
        val thresholds = DualModalThresholds(
            deltaPercent = root["deltaPercent"].asDouble,
            qcDeviation = root["qcDeviation"].asDouble,
            qcDenominatorFloor = root["qcDenominatorFloor"].asDouble
        )
        val cases = root.getAsJsonArray("cases")
        assertTrue("夹具应覆盖足够多的读数", cases.size() > 1000)
        val seen = mutableSetOf<DualModalDecision>()
        cases.forEach { element ->
            val case = element.asJsonObject
            val key = case["key"].asString
            val outcome = DualModalDecisionRule.decide(
                case.getAsJsonObject("colorimetric").toSide(),
                case.getAsJsonObject("fluorescence").toSide(),
                thresholds
            )
            val expected = decisionOf(case["decision"].asString)
            assertEquals("判定不一致：$key", expected, outcome.decision)
            assertEquals("输出值不一致：$key", case["output"].doubleOrNull(), outcome.suggestedConcentration)
            assertEquals("δ 不一致：$key", case["delta"].doubleOrNull(), outcome.deltaPercent)
            seen += outcome.decision
        }
        assertEquals("夹具应覆盖全部四种判定", DualModalDecision.entries.toSet(), seen)
    }

    private fun JsonObject.toSide(): DualModalDecisionRule.SideInput {
        val deviations = this["qcDeviations"]
        return DualModalDecisionRule.SideInput(
            concentration = this["concentration"].doubleOrNull(),
            localizationTrusted = this["trusted"].asBoolean,
            qcDeviations = if (deviations == null || deviations.isJsonNull) {
                emptyList()
            } else {
                deviations.asJsonArray.map { it.doubleOrNull() }
            }
        )
    }

    private fun JsonElement?.doubleOrNull(): Double? =
        if (this == null || isJsonNull) null else asDouble

    private fun decisionOf(code: String): DualModalDecision = when (code) {
        "fuse" -> DualModalDecision.FUSE
        "adopt_col" -> DualModalDecision.ADOPT_COLORIMETRIC
        "adopt_flu" -> DualModalDecision.ADOPT_FLUORESCENCE
        "retest" -> DualModalDecision.RETEST
        else -> error("未知判定：$code")
    }

    private fun loadFixture(): JsonObject {
        val path = "dualmodal/w4_rule_parity_cases.json"
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "缺少测试夹具：$path" }
        return stream.reader(Charsets.UTF_8).use { reader -> JsonParser.parseReader(reader).asJsonObject }
    }
}
