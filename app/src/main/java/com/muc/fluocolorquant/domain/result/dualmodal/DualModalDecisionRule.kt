package com.muc.fluocolorquant.domain.result.dualmodal

import kotlin.math.abs

/**
 * 双模态判定规则本身，与论文第三章 3.5 节、Python 参考实现 `dual_rule.adjudicate` 逐条对应。
 *
 * 输入只有两侧浓度、定位可信度和阳控偏差，不依赖快照结构，便于与参考实现在同一批读数上
 * 逐条比对（见 DualModalRuleParityTest）：
 * - 某侧没有浓度，或定位不可信，该侧不可用；
 * - 某侧不可用，或任一阳控偏差超过阈值，该侧判为异常；
 * - 两侧都异常 → 复检；只有一侧异常 → 采信另一侧；
 * - 两侧都正常时看相对离散度 δ：超过阈值 → 复检，否则取两侧均值。
 */
object DualModalDecisionRule {

    data class SideInput(
        val concentration: Double?,
        val localizationTrusted: Boolean,
        /** 各阳控水平的相对偏差；缺少质控证据时为空列表或包含空值。 */
        val qcDeviations: List<Double?>
    )

    data class Outcome(
        val colorimetricUnusable: Boolean,
        val fluorescenceUnusable: Boolean,
        val colorimetricAbnormal: Boolean,
        val fluorescenceAbnormal: Boolean,
        val deltaPercent: Double?,
        val alarm: Boolean,
        val decision: DualModalDecision,
        val reason: DualModalDecisionReason,
        val suggestedConcentration: Double?
    )

    fun decide(colorimetric: SideInput, fluorescence: SideInput, thresholds: DualModalThresholds): Outcome {
        val colorimetricUnusable = colorimetric.isUnusable()
        val fluorescenceUnusable = fluorescence.isUnusable()
        val colorimetricAbnormal = colorimetricUnusable || colorimetric.qcExceeded(thresholds)
        val fluorescenceAbnormal = fluorescenceUnusable || fluorescence.qcExceeded(thresholds)
        val delta = if (colorimetricUnusable || fluorescenceUnusable) {
            null
        } else {
            relativeDiscrepancyPercent(colorimetric.concentration, fluorescence.concentration)
        }
        // 与参考实现一致：δ 无法计算时视同告警
        val alarm = delta == null || delta > thresholds.deltaPercent
        val (decision, reason, value) = when {
            colorimetricAbnormal && fluorescenceAbnormal ->
                Triple(DualModalDecision.RETEST, DualModalDecisionReason.BOTH_SIDES_UNUSABLE, null)
            colorimetricAbnormal ->
                Triple(
                    DualModalDecision.ADOPT_FLUORESCENCE,
                    DualModalDecisionReason.COLORIMETRIC_UNUSABLE,
                    fluorescence.concentration
                )
            fluorescenceAbnormal ->
                Triple(
                    DualModalDecision.ADOPT_COLORIMETRIC,
                    DualModalDecisionReason.FLUORESCENCE_UNUSABLE,
                    colorimetric.concentration
                )
            alarm ->
                Triple(DualModalDecision.RETEST, DualModalDecisionReason.DISCREPANCY_UNATTRIBUTED, null)
            else -> {
                val c = colorimetric.concentration!!
                val f = fluorescence.concentration!!
                Triple(DualModalDecision.FUSE, DualModalDecisionReason.CONSISTENT, (c + f) / 2.0)
            }
        }
        return Outcome(
            colorimetricUnusable = colorimetricUnusable,
            fluorescenceUnusable = fluorescenceUnusable,
            colorimetricAbnormal = colorimetricAbnormal,
            fluorescenceAbnormal = fluorescenceAbnormal,
            deltaPercent = delta,
            alarm = alarm,
            decision = decision,
            reason = reason,
            suggestedConcentration = value
        )
    }

    /** δ = |a − b| / ((a + b) / 2) × 100。 */
    fun relativeDiscrepancyPercent(a: Double?, b: Double?): Double? {
        if (a == null || b == null) return null
        return abs(a - b) / ((a + b) / 2.0) * 100.0
    }

    /** 参考实现中反算失败或浓度不为正时没有读数，这里同样只接受有限正值。 */
    private fun SideInput.isUnusable(): Boolean {
        val c = concentration
        return c == null || !c.isFinite() || c <= 0.0 || !localizationTrusted
    }

    private fun SideInput.qcExceeded(thresholds: DualModalThresholds): Boolean =
        qcDeviations.any { deviation -> deviation != null && deviation > thresholds.qcDeviation }
}
