package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.domain.result.dualmodal.DualModalDecisionRule
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 一组重复位点在三个输出头上的读数统计（重标定之前）。
 *
 * [mu] 为各孔 log10 浓度均值的中位数，[u] 为 sqrt(mean(σ²) + var(μ))：单孔预测方差加孔间离散，
 * 下标为 [DualNetHead.ordinal]。与 W3 `w3_analyze.reading_features` 逐项对应。
 */
data class DualNetRawReading(
    val mu: List<Double>,
    val u: List<Double>,
    val siteCount: Int
)

/** 一个输出头在本批是否可用；只有可用的头才参与建议。 */
data class DualNetHeadAvailability(
    val colorimetric: Boolean,
    val fluorescence: Boolean
)

/**
 * DualNet 的后处理：读数汇总、逐批重标定与建议。
 *
 * 逻辑逐条移植自 W3 参考实现（`w3_analyze.py` 的 reading_features、recalibrate、net_decide），
 * 与之在同一组输入上的一致性由 DualNetPostProcessorParityTest 检验。建议取 W3 的"融合头＋一致性
 * 检查"：融合头不确定度合格、且两个单模态头的相对离散度 δ 不超过阈值才建议采用；某一侧不可用时
 * 退回另一侧的单模态头。
 */
object DualNetPostProcessor {

    /** outputs 为逐孔网络输出，每行 6 个数：比色 (μ, s)、荧光 (μ, s)、融合 (μ, s)。 */
    fun reading(outputs: List<FloatArray>): DualNetRawReading {
        require(outputs.isNotEmpty() && outputs.all { it.size == DualNetSpec.OUTPUT_SIZE }) { "网络输出为空或维度不符" }
        val mu = DualNetHead.entries.map { head -> median(outputs.map { it[2 * head.ordinal].toDouble() }) }
        val u = DualNetHead.entries.map { head ->
            val means = outputs.map { it[2 * head.ordinal].toDouble() }
            val variances = outputs.map { row ->
                exp(row[2 * head.ordinal + 1].toDouble().coerceIn(DualNetSpec.LOG_VARIANCE_MIN, DualNetSpec.LOG_VARIANCE_MAX))
            }
            sqrt(variances.average() + populationVariance(means))
        }
        return DualNetRawReading(mu = mu, u = u, siteCount = outputs.size)
    }

    /**
     * 以 log10 名义浓度对各头 μ 做最小二乘直线拟合（W3：np.polyfit(μ, log10 c, 1)）。
     *
     * 斜率不为正时该头本批不可用；[headsUsable] 中为 false 的头（标定板对应侧定位不可信）直接不可用。
     * 返回成功拟合的头，至少需要 [DualNetSpec.MIN_RECALIBRATION_LEVELS] 个水平。
     */
    fun recalibrate(
        levels: List<Pair<Double, DualNetRawReading>>,
        headsUsable: Set<DualNetHead>
    ): List<DualNetRecalibration> {
        if (levels.size < DualNetSpec.MIN_RECALIBRATION_LEVELS) return emptyList()
        val y = levels.map { (level, _) -> kotlin.math.log10(level) }
        return DualNetHead.entries.filter { it in headsUsable }.mapNotNull { head ->
            val x = levels.map { (_, reading) -> reading.mu[head.ordinal] }
            val fit = fitLine(x, y) ?: return@mapNotNull null
            fit.takeIf { (_, slope) -> slope > 0.0 && slope.isFinite() }?.let { (intercept, slope) ->
                DualNetRecalibration(head = head, intercept = intercept, slope = slope, levelCount = levels.size)
            }
        }
    }

    /**
     * 对一个样本给出网络读数与建议。
     *
     * [availability] 只描述测试板两侧定位是否可信；重标定缺失的头同样不可用（W3 avail_*）。
     */
    fun decide(
        analyteId: String,
        sampleKey: String,
        raw: DualNetRawReading,
        recalibrations: List<DualNetRecalibration>,
        availability: DualNetHeadAvailability,
        thresholds: DualNetThresholds
    ): DualNetReading {
        val maps = recalibrations.associateBy(DualNetRecalibration::head)
        fun headReading(head: DualNetHead, sideTrusted: Boolean, threshold: Double): DualNetHeadReading {
            val map = maps[head]
            if (!sideTrusted || map == null) {
                return DualNetHeadReading(head, available = false, concentration = null, uncertainty = null, withinThreshold = false)
            }
            val concentration = 10.0.pow(map.intercept + map.slope * raw.mu[head.ordinal])
            val uncertainty = abs(map.slope) * raw.u[head.ordinal]
            return DualNetHeadReading(
                head = head,
                available = true,
                concentration = concentration,
                uncertainty = uncertainty,
                withinThreshold = uncertainty <= threshold
            )
        }
        val colorimetric = headReading(DualNetHead.COLORIMETRIC, availability.colorimetric, thresholds.colorimetricUncertainty)
        val fluorescence = headReading(DualNetHead.FLUORESCENCE, availability.fluorescence, thresholds.fluorescenceUncertainty)
        val fused = headReading(
            DualNetHead.FUSED,
            availability.colorimetric && availability.fluorescence,
            thresholds.fusedUncertainty
        )
        val delta = if (colorimetric.available && fluorescence.available) {
            DualModalDecisionRule.relativeDiscrepancyPercent(colorimetric.concentration, fluorescence.concentration)
        } else {
            null
        }
        val (decision, reason, value) = when {
            colorimetric.available && fluorescence.available && fused.available -> when {
                !fused.withinThreshold -> Triple(DualNetDecision.RETEST, DualNetDecisionReason.FUSED_UNCERTAIN, null)
                delta == null || delta > thresholds.deltaPercent ->
                    Triple(DualNetDecision.RETEST, DualNetDecisionReason.HEADS_DISAGREE, null)
                else -> Triple(DualNetDecision.ADOPT_FUSED, DualNetDecisionReason.CONSISTENT, fused.concentration)
            }
            // W3 net_decide：一侧不可用时融合头不可用，退回另一侧的单模态头（比色优先，与参考实现相同）。
            colorimetric.available -> if (colorimetric.withinThreshold) {
                Triple(DualNetDecision.ADOPT_COLORIMETRIC, DualNetDecisionReason.COLORIMETRIC_FALLBACK, colorimetric.concentration)
            } else {
                Triple(DualNetDecision.RETEST, DualNetDecisionReason.FALLBACK_UNCERTAIN, null)
            }
            fluorescence.available -> if (fluorescence.withinThreshold) {
                Triple(DualNetDecision.ADOPT_FLUORESCENCE, DualNetDecisionReason.FLUORESCENCE_FALLBACK, fluorescence.concentration)
            } else {
                Triple(DualNetDecision.RETEST, DualNetDecisionReason.FALLBACK_UNCERTAIN, null)
            }
            else -> Triple(DualNetDecision.RETEST, DualNetDecisionReason.NO_USABLE_HEAD, null)
        }
        return DualNetReading(
            analyteId = analyteId,
            sampleKey = sampleKey,
            siteCount = raw.siteCount,
            colorimetric = colorimetric,
            fluorescence = fluorescence,
            fused = fused,
            deltaPercent = delta,
            decision = decision,
            reason = reason,
            suggestedConcentration = value,
            withinEvaluatedRange = value?.let { it >= DualNetSpec.EVALUATED_RANGE_MIN && it <= DualNetSpec.EVALUATED_RANGE_MAX }
        )
    }

    /** 普通最小二乘直线，返回 (截距, 斜率)；自变量没有离散度时为空。 */
    private fun fitLine(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val meanX = x.average()
        val meanY = y.average()
        val sxx = x.sumOf { (it - meanX) * (it - meanX) }
        if (!sxx.isFinite() || sxx <= 0.0) return null
        val sxy = x.indices.sumOf { (x[it] - meanX) * (y[it] - meanY) }
        val slope = sxy / sxx
        return (meanY - slope * meanX) to slope
    }

    /** 与 numpy.median 相同：偶数个时取中间两数的均值。 */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** 与 numpy.var 相同的总体方差（ddof = 0）。 */
    private fun populationVariance(values: List<Double>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
