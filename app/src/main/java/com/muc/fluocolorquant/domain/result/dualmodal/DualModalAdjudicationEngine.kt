package com.muc.fluocolorquant.domain.result.dualmodal

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.quantification.QuantificationState
import com.muc.fluocolorquant.domain.project.DIRECT_CARRIER_ID_PREFIX
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlin.math.abs
import kotlin.math.max

/** 配对检查的结果：可配对时已按模式排好比色与荧光两侧。 */
sealed interface DualModalPairCheck {
    data class Compatible(
        val colorimetric: ArrayResultSnapshot,
        val fluorescence: ArrayResultSnapshot
    ) : DualModalPairCheck

    data class Incompatible(val reasons: Set<DualModalIncompatibility>) : DualModalPairCheck
}

/**
 * 从两次冻结运行的结果快照计算双模态判定。
 *
 * 读数口径与论文第三章一致：一个样本的读数是它全部重复位点有效浓度的中位数；质控证据
 * 是每个阳控水平的实测信号与本次标定曲线在名义浓度处的预测信号之间的相对偏差。
 * 引擎是纯 Kotlin，不访问数据库或界面，也不修改输入快照。
 */
object DualModalAdjudicationEngine {
    const val RULE_VERSION: String = "dual-modal-rule-v1"
    const val COLORIMETRIC: String = "COLORIMETRIC"
    const val FLUORESCENCE: String = "FLUORESCENCE"

    /** 有效浓度只取正式定量与可信扩展估计，不使用只有界限或不可用的位点。 */
    private val VALID_STATES = setOf(QuantificationState.QUANTIFIED.name, QuantificationState.ESTIMATED.name)

    fun check(first: ArrayResultSnapshot, second: ArrayResultSnapshot): DualModalPairCheck {
        val reasons = mutableSetOf<DualModalIncompatibility>()
        val modes = setOf(first.detectionMode.uppercase(), second.detectionMode.uppercase())
        when {
            modes.any { it != COLORIMETRIC && it != FLUORESCENCE } ->
                reasons += DualModalIncompatibility.UNSUPPORTED_DETECTION_MODE
            modes.size == 1 -> reasons += DualModalIncompatibility.SAME_DETECTION_MODE
        }
        if (first.rows != second.rows || first.columns != second.columns) {
            reasons += DualModalIncompatibility.GRID_SIZE_MISMATCH
        }
        if (!sameCarrier(first.carrier, second.carrier)) {
            reasons += DualModalIncompatibility.CARRIER_MISMATCH
        }
        if (layoutOf(first) != layoutOf(second)) {
            reasons += DualModalIncompatibility.SITE_LAYOUT_MISMATCH
        }
        val shared = sharedAnalytes(first, second)
        if (shared.isEmpty()) {
            reasons += DualModalIncompatibility.NO_SHARED_ANALYTE
        } else if (shared.any { (a, b) -> !a.concentrationUnit.equals(b.concentrationUnit, ignoreCase = true) }) {
            reasons += DualModalIncompatibility.UNIT_MISMATCH
        }
        if (reasons.isNotEmpty()) return DualModalPairCheck.Incompatible(reasons)
        return if (first.detectionMode.equals(COLORIMETRIC, ignoreCase = true)) {
            DualModalPairCheck.Compatible(colorimetric = first, fluorescence = second)
        } else {
            DualModalPairCheck.Compatible(colorimetric = second, fluorescence = first)
        }
    }

    fun adjudicate(
        colorimetric: ArrayResultSnapshot,
        fluorescence: ArrayResultSnapshot,
        thresholds: DualModalThresholds = DualModalThresholds()
    ): DualModalAdjudication {
        val readings = sharedAnalytes(colorimetric, fluorescence)
            .sortedWith(compareBy({ it.first.displayOrder }, { it.first.analyteId }))
            .flatMap { (colAnalyte, fluAnalyte) ->
                val colSamples = sampleConcentrations(colorimetric, colAnalyte.analyteId)
                val fluSamples = sampleConcentrations(fluorescence, fluAnalyte.analyteId)
                val colQc = qcEvidence(colorimetric, colAnalyte, thresholds)
                val fluQc = qcEvidence(fluorescence, fluAnalyte, thresholds)
                (colSamples.keys + fluSamples.keys).sortedWith(SAMPLE_KEY_ORDER).map { sampleKey ->
                    readingFor(
                        analyte = colAnalyte,
                        sampleKey = sampleKey,
                        col = side(colorimetric, colSamples[sampleKey], colQc),
                        flu = side(fluorescence, fluSamples[sampleKey], fluQc),
                        thresholds = thresholds
                    )
                }
            }
        return DualModalAdjudication(
            ruleVersion = RULE_VERSION,
            thresholds = thresholds,
            colorimetricRunId = colorimetric.runId,
            fluorescenceRunId = fluorescence.runId,
            readings = readings
        )
    }

    private fun readingFor(
        analyte: ArrayAnalyteResult,
        sampleKey: String,
        col: DualModalSideReading,
        flu: DualModalSideReading,
        thresholds: DualModalThresholds
    ): DualModalReading {
        val outcome = DualModalDecisionRule.decide(col.toRuleInput(), flu.toRuleInput(), thresholds)
        return DualModalReading(
            analyteId = analyte.analyteId,
            analyteName = analyte.name,
            sampleKey = sampleKey,
            concentrationUnit = analyte.concentrationUnit,
            colorimetric = col,
            fluorescence = flu,
            deltaPercent = outcome.deltaPercent,
            alarm = outcome.alarm,
            decision = outcome.decision,
            reason = outcome.reason,
            suggestedConcentration = outcome.suggestedConcentration
        )
    }

    private fun DualModalSideReading.toRuleInput() = DualModalDecisionRule.SideInput(
        concentration = concentration,
        localizationTrusted = localizationTrusted,
        qcDeviations = qcEvidence.map(DualModalQcEvidence::relativeDeviation)
    )

    private fun side(
        snapshot: ArrayResultSnapshot,
        sample: SampleConcentrations?,
        qc: List<DualModalQcEvidence>
    ): DualModalSideReading {
        val trusted = snapshot.frame.geometry.trusted
        val concentration = sample?.values?.takeIf { it.isNotEmpty() }?.let(::median)
        val status = when {
            concentration == null || concentration <= 0.0 -> DualModalSideStatus.NO_CONCENTRATION
            !trusted -> DualModalSideStatus.LOCALIZATION_UNTRUSTED
            qc.any { it.exceeded } -> DualModalSideStatus.QC_ABNORMAL
            else -> DualModalSideStatus.USABLE
        }
        return DualModalSideReading(
            runId = snapshot.runId,
            concentration = concentration,
            validSiteCount = sample?.values?.size ?: 0,
            totalSiteCount = sample?.total ?: 0,
            localizationTrusted = trusted,
            qcEvidence = qc,
            status = status
        )
    }

    private data class SampleConcentrations(val values: List<Double>, val total: Int)

    /** 样本位点按样本槽汇总；未分配样本槽时退回重复组，再退回位点自身。 */
    private fun sampleConcentrations(snapshot: ArrayResultSnapshot, analyteId: String): Map<String, SampleConcentrations> {
        return snapshot.sites
            .filter { site -> site.enabled && site.roleCode == TemplateSiteRole.SAMPLE.code && site.analyteId == analyteId }
            .groupBy(::sampleKeyOf)
            .mapValues { (_, sites) ->
                val measurements = sites.mapNotNull { site -> site.measurementFor(analyteId) }
                SampleConcentrations(
                    values = measurements.mapNotNull(::validConcentration),
                    total = sites.size
                )
            }
    }

    private fun validConcentration(measurement: ArraySiteMeasurementResult): Double? {
        val value = measurement.concentrationValue ?: return null
        if (!value.isFinite()) return null
        val state = measurement.quantificationState
        return value.takeIf { state == null || state.uppercase() in VALID_STATES }
    }

    /** 每个阳控水平取重复位点主信号的中位数，与本次标定曲线在名义浓度处的预测信号比较。 */
    private fun qcEvidence(
        snapshot: ArrayResultSnapshot,
        analyte: ArrayAnalyteResult,
        thresholds: DualModalThresholds
    ): List<DualModalQcEvidence> {
        val function = analyte.fittingFunction?.let { identifier -> FittingFunction.fromIdentifier(identifier) }
        return snapshot.sites
            .filter { site ->
                site.enabled && site.roleCode == TemplateSiteRole.POSITIVE_CONTROL.code &&
                    site.analyteId == analyte.analyteId && site.standardConcentration?.isFinite() == true
            }
            .groupBy { site -> site.standardConcentration!! }
            .toSortedMap()
            .map { (nominal, sites) ->
                val signals = sites.mapNotNull { site ->
                    site.measurementFor(analyte.analyteId)?.primaryFeatureValue?.takeIf(Double::isFinite)
                }
                val measured = signals.takeIf { it.isNotEmpty() }?.let(::median)
                val predicted = function?.let { fn ->
                    runCatching { FittingEngine.calculate(fn, analyte.fittingParameters, nominal) }
                        .getOrNull()?.takeIf(Double::isFinite)
                }
                val deviation = if (measured != null && predicted != null) {
                    abs(measured - predicted) / max(abs(predicted), thresholds.qcDenominatorFloor)
                } else {
                    null
                }
                DualModalQcEvidence(
                    nominalConcentration = nominal,
                    siteCount = sites.size,
                    measuredSignal = measured,
                    predictedSignal = predicted,
                    relativeDeviation = deviation,
                    exceeded = deviation != null && deviation > thresholds.qcDeviation
                )
            }
    }

    private fun ArrayPhysicalSiteResult.measurementFor(analyteId: String): ArraySiteMeasurementResult? =
        measurements.firstOrNull { it.analyteId == analyteId }
            ?: measurements.singleOrNull()?.takeIf { it.analyteId == null }

    /** 样本键：样本槽，其次重复组，最后是位点自身；双模态网络判读按同一口径分组。 */
    internal fun sampleKeyOf(site: ArrayPhysicalSiteResult): String =
        site.sampleSlot?.takeIf(String::isNotBlank)
            ?: site.repeatGroup?.takeIf(String::isNotBlank)
            ?: site.siteKey

    /** 两次运行必须对每个物理位点给出相同的角色、分析物、样本槽与阳控名义浓度。 */
    /**
     * 两次运行是否使用同一种物理载体。
     *
     * 模板库中的载体按 ID 与版本比较。直接新建的项目各有一份隐式载体（[DIRECT_CARRIER_ID_PREFIX]
     * 加项目 ID），同一块芯片的比色与荧光项目 ID 必然不同，此时按物理身份（载体类型与位点形状）
     * 比较；网格尺寸与逐位点版面另行核对。
     */
    private fun sameCarrier(first: ArrayCarrierResult, second: ArrayCarrierResult): Boolean {
        if (first.id == second.id) return first.version == second.version
        val implicit = first.id.startsWith(DIRECT_CARRIER_ID_PREFIX) ||
            second.id.startsWith(DIRECT_CARRIER_ID_PREFIX)
        return implicit &&
            first.carrierType == second.carrierType &&
            first.siteShape == second.siteShape
    }

    private fun layoutOf(snapshot: ArrayResultSnapshot): List<String> =
        snapshot.sites.sortedBy(ArrayPhysicalSiteResult::siteIndex).map { site ->
            listOf(
                site.siteIndex, site.enabled, site.roleCode, site.analyteId,
                site.sampleSlot, site.repeatGroup, site.standardConcentration
            ).joinToString("|")
        }

    private fun sharedAnalytes(
        first: ArrayResultSnapshot,
        second: ArrayResultSnapshot
    ): List<Pair<ArrayAnalyteResult, ArrayAnalyteResult>> {
        val other = second.analytes.associateBy(ArrayAnalyteResult::analyteId)
        return first.analytes.mapNotNull { analyte -> other[analyte.analyteId]?.let { analyte to it } }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** 样本键按"字母前缀 + 数字"的自然顺序排列，使 S2 排在 S10 之前。 */
    private val SAMPLE_KEY_ORDER: Comparator<String> = Comparator { a, b ->
        val pa = splitKey(a)
        val pb = splitKey(b)
        compareValuesBy(pa, pb, { it.first }, { it.second }, { it.third })
    }

    private fun splitKey(key: String): Triple<String, Long, String> {
        val match = Regex("^(\\D*)(\\d+)(.*)$").find(key) ?: return Triple(key, Long.MAX_VALUE, "")
        return Triple(match.groupValues[1], match.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE, match.groupValues[3])
    }
}
