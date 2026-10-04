package com.muc.fluocolorquant.domain.detection.quantification

import kotlin.math.abs
import kotlin.math.max

/** 动态量程复核算法版本；必须随运行快照冻结，后续升级不能改变历史判定。 */
const val RANGE_RECOVERY_ALGORITHM_VERSION: String = "range-review-v1"

/** 多数样品越界后的批次级处理状态。 */
enum class RangeRecoveryStatus {
    /** 样品不足或越界比例没有达到触发门槛。 */
    NOT_TRIGGERED,

    /** 已触发动态复核，但缺少独立质控锚点，因此只诊断、不改写浓度。 */
    TRIGGERED_REVIEW_REQUIRED,

    /** 独立质控锚点通过稳健仿射校正验收；调用方可按冻结参数重建校正结果。 */
    CORRECTION_APPLIED,

    /** 提供了独立锚点，但校正参数或复算误差未通过安全门槛。 */
    CORRECTION_REJECTED
}

/** 动态量程复核给用户的主要方向，不携带界面文案。 */
enum class RangeRecoveryDirection {
    NONE,
    MOSTLY_BELOW,
    MOSTLY_ABOVE,
    BOTH_DIRECTIONS
}

/** 稳定机器原因；UI、报告和导出各自映射本地化文案。 */
enum class RangeRecoveryReason {
    INSUFFICIENT_VALID_SAMPLES,
    OUT_OF_RANGE_NOT_DOMINANT,
    INDEPENDENT_CONTROL_REQUIRED,
    INVALID_CONTROL_ANCHORS,
    CONTROL_CORRECTION_OUT_OF_BOUNDS,
    CONTROL_VALIDATION_FAILED,
    CONTROL_CORRECTION_ACCEPTED
}

/**
 * 一个已完成普通定量的位点输入。
 *
 * [isSample] 由冻结模板角色产生；标准孔、空白孔、参考孔和质控孔不得混入样品覆盖率。
 * 浓度、界限和状态都必须来自当前运行的冻结结果，算法不会重新读取图片或重新拟合曲线。
 */
data class RangeRecoveryObservation(
    val siteIndex: Int,
    val isSample: Boolean,
    val concentration: Double?,
    val lowerBound: Double?,
    val upperBound: Double?,
    val rangeStatus: ReliableRangeStatus?,
    val quantificationState: QuantificationState?
)

/**
 * 独立质控锚点。
 *
 * expectedConcentration 必须来自与未知样品分离的已知质控材料；不能把未知样品期望落入
 * 用户量程的愿望当成锚点，否则会形成数据泄漏并人为提高“区间内比例”。
 */
data class RangeCorrectionAnchor(
    val expectedConcentration: Double,
    val observedConcentration: Double,
    val reliable: Boolean = true
)

/** 一次批次动态量程复核的完整、可序列化决策。 */
data class RangeRecoveryDecision(
    val status: RangeRecoveryStatus,
    val reason: RangeRecoveryReason,
    val direction: RangeRecoveryDirection,
    val validSampleCount: Int,
    val withinRangeCount: Int,
    val belowRangeCount: Int,
    val aboveRangeCount: Int,
    val outOfRangeRatio: Double,
    val triggerMinimumSampleCount: Int,
    val triggerOutOfRangeRatio: Double,
    val correctionScale: Double? = null,
    val correctionOffset: Double? = null,
    val controlMedianErrorPercent: Double? = null,
    val controlMaximumErrorPercent: Double? = null,
    val algorithmVersion: String = RANGE_RECOVERY_ALGORITHM_VERSION
) {
    /** 只有经过独立质控验收的决策才能对浓度执行变换。 */
    fun correct(concentration: Double): Double? {
        if (status != RangeRecoveryStatus.CORRECTION_APPLIED || !concentration.isFinite()) {
            return null
        }
        val scale = correctionScale ?: return null
        val offset = correctionOffset ?: return null
        return (scale * concentration + offset).takeIf { it.isFinite() && it >= 0.0 }
    }

    /** 转为稳定快照 Map，避免把 Kotlin 枚举的显示名称或空字段写入历史 JSON。 */
    fun toSnapshot(): Map<String, Any> = buildMap {
        put("schemaVersion", 1)
        put("algorithmVersion", algorithmVersion)
        put("status", status.name)
        put("reason", reason.name)
        put("direction", direction.name)
        put("validSampleCount", validSampleCount)
        put("withinRangeCount", withinRangeCount)
        put("belowRangeCount", belowRangeCount)
        put("aboveRangeCount", aboveRangeCount)
        put("outOfRangeRatio", outOfRangeRatio)
        put("triggerMinimumSampleCount", triggerMinimumSampleCount)
        put("triggerOutOfRangeRatio", triggerOutOfRangeRatio)
        correctionScale?.takeIf(Double::isFinite)?.let { put("correctionScale", it) }
        correctionOffset?.takeIf(Double::isFinite)?.let { put("correctionOffset", it) }
        controlMedianErrorPercent?.takeIf(Double::isFinite)?.let {
            put("controlMedianErrorPercent", it)
        }
        controlMaximumErrorPercent?.takeIf(Double::isFinite)?.let {
            put("controlMaximumErrorPercent", it)
        }
    }
}

/**
 * 多数样品越界时的动态处理引擎。
 *
 * 该算法分两层：第一层始终可以做动态诊断；第二层只有两个以上独立质控锚点通过稳健
 * 验收时才产生校正参数。未知样品分布本身永远不能决定增益或偏移，因此“多数越界”不
 * 会自动把数值压回用户区间。这样既响应批次异常，又保留结果的科学真实性和可追溯性。
 */
object RangeRecoveryEngine {
    const val DEFAULT_MINIMUM_SAMPLE_COUNT: Int = 6
    const val DEFAULT_TRIGGER_RATIO: Double = 0.50

    fun evaluate(
        observations: List<RangeRecoveryObservation>,
        projectMinimum: Double,
        projectMaximum: Double,
        anchors: List<RangeCorrectionAnchor> = emptyList(),
        minimumSampleCount: Int = DEFAULT_MINIMUM_SAMPLE_COUNT,
        triggerRatio: Double = DEFAULT_TRIGGER_RATIO
    ): RangeRecoveryDecision {
        require(projectMinimum.isFinite() && projectMaximum.isFinite()) {
            "项目量程必须为有限数值"
        }
        require(projectMaximum > projectMinimum) { "项目量程上限必须大于下限" }
        require(minimumSampleCount >= 1) { "动态复核最小样品数必须大于0" }
        require(triggerRatio in 0.0..1.0) { "动态复核比例必须位于0到1之间" }

        val classifications = observations.asSequence()
            .filter(RangeRecoveryObservation::isSample)
            .mapNotNull { observation ->
                classify(observation, projectMinimum, projectMaximum)
            }
            .toList()
        val validCount = classifications.size
        val belowCount = classifications.count { it == SampleRangeClass.BELOW }
        val aboveCount = classifications.count { it == SampleRangeClass.ABOVE }
        val withinCount = classifications.count { it == SampleRangeClass.WITHIN }
        val outsideCount = belowCount + aboveCount
        val outsideRatio = if (validCount == 0) 0.0 else outsideCount.toDouble() / validCount
        val direction = when {
            outsideCount == 0 -> RangeRecoveryDirection.NONE
            belowCount > 0 && aboveCount > 0 -> RangeRecoveryDirection.BOTH_DIRECTIONS
            belowCount > 0 -> RangeRecoveryDirection.MOSTLY_BELOW
            else -> RangeRecoveryDirection.MOSTLY_ABOVE
        }

        fun decision(
            status: RangeRecoveryStatus,
            reason: RangeRecoveryReason,
            correction: ValidatedCorrection? = null
        ): RangeRecoveryDecision = RangeRecoveryDecision(
            status = status,
            reason = reason,
            direction = direction,
            validSampleCount = validCount,
            withinRangeCount = withinCount,
            belowRangeCount = belowCount,
            aboveRangeCount = aboveCount,
            outOfRangeRatio = outsideRatio,
            triggerMinimumSampleCount = minimumSampleCount,
            triggerOutOfRangeRatio = triggerRatio,
            correctionScale = correction?.scale,
            correctionOffset = correction?.offset,
            controlMedianErrorPercent = correction?.medianErrorPercent,
            controlMaximumErrorPercent = correction?.maximumErrorPercent
        )

        if (validCount < minimumSampleCount) {
            return decision(
                RangeRecoveryStatus.NOT_TRIGGERED,
                RangeRecoveryReason.INSUFFICIENT_VALID_SAMPLES
            )
        }
        if (outsideRatio < triggerRatio) {
            return decision(
                RangeRecoveryStatus.NOT_TRIGGERED,
                RangeRecoveryReason.OUT_OF_RANGE_NOT_DOMINANT
            )
        }
        if (anchors.isEmpty()) {
            return decision(
                RangeRecoveryStatus.TRIGGERED_REVIEW_REQUIRED,
                RangeRecoveryReason.INDEPENDENT_CONTROL_REQUIRED
            )
        }

        val correction = fitAndValidateCorrection(
            anchors = anchors,
            projectMinimum = projectMinimum,
            projectMaximum = projectMaximum
        )
        return when (correction) {
            is CorrectionAttempt.Accepted -> decision(
                RangeRecoveryStatus.CORRECTION_APPLIED,
                RangeRecoveryReason.CONTROL_CORRECTION_ACCEPTED,
                correction.value
            )
            is CorrectionAttempt.Rejected -> decision(
                RangeRecoveryStatus.CORRECTION_REJECTED,
                correction.reason
            )
        }
    }

    /**
     * 有有限点估计时直接与项目量程比较；只有没有点估计时才读取单侧范围状态。
     * 这样标准曲线在“标定范围外但项目量程内”的可信外推不会被误计为项目越界，而
     * 深度模型返回的单侧 <下限 / >上限 仍会进入动态诊断。
     */
    private fun classify(
        observation: RangeRecoveryObservation,
        projectMinimum: Double,
        projectMaximum: Double
    ): SampleRangeClass? {
        if (observation.quantificationState == QuantificationState.UNAVAILABLE) return null
        observation.concentration?.takeIf(Double::isFinite)?.let { concentration ->
            return when {
                concentration < projectMinimum -> SampleRangeClass.BELOW
                concentration > projectMaximum -> SampleRangeClass.ABOVE
                else -> SampleRangeClass.WITHIN
            }
        }
        return when (observation.rangeStatus) {
            ReliableRangeStatus.BELOW_RANGE,
            ReliableRangeStatus.BELOW_TRUSTED_RANGE,
            ReliableRangeStatus.BELOW_PROJECT_RANGE -> SampleRangeClass.BELOW
            ReliableRangeStatus.ABOVE_RANGE,
            ReliableRangeStatus.ABOVE_TRUSTED_RANGE,
            ReliableRangeStatus.ABOVE_PROJECT_RANGE -> SampleRangeClass.ABOVE
            ReliableRangeStatus.WITHIN_RANGE -> SampleRangeClass.WITHIN
            null -> null
        }
    }

    /**
     * 使用 Theil-Sen 斜率中位数拟合 expected = scale × observed + offset。
     * 相比普通最小二乘，它不会被单个异常质控孔轻易拖动；随后仍需逐锚点复算验收。
     */
    private fun fitAndValidateCorrection(
        anchors: List<RangeCorrectionAnchor>,
        projectMinimum: Double,
        projectMaximum: Double
    ): CorrectionAttempt {
        val valid = anchors.filter { anchor ->
            anchor.reliable &&
                anchor.expectedConcentration.isFinite() &&
                anchor.observedConcentration.isFinite() &&
                anchor.expectedConcentration >= 0.0 &&
                anchor.observedConcentration >= 0.0
        }
        if (valid.size < MINIMUM_CONTROL_ANCHOR_COUNT ||
            valid.map(RangeCorrectionAnchor::observedConcentration).distinct().size < 2
        ) {
            return CorrectionAttempt.Rejected(RangeRecoveryReason.INVALID_CONTROL_ANCHORS)
        }
        val slopes = buildList {
            valid.indices.forEach { leftIndex ->
                ((leftIndex + 1) until valid.size).forEach { rightIndex ->
                    val left = valid[leftIndex]
                    val right = valid[rightIndex]
                    val observedDelta = right.observedConcentration - left.observedConcentration
                    if (abs(observedDelta) > CONTROL_EPSILON) {
                        add(
                            (right.expectedConcentration - left.expectedConcentration) /
                                observedDelta
                        )
                    }
                }
            }
        }.filter(Double::isFinite)
        val scale = slopes.medianOrNull()
            ?: return CorrectionAttempt.Rejected(RangeRecoveryReason.INVALID_CONTROL_ANCHORS)
        val offset = valid.map { anchor ->
            anchor.expectedConcentration - scale * anchor.observedConcentration
        }.medianOrNull()
            ?: return CorrectionAttempt.Rejected(RangeRecoveryReason.INVALID_CONTROL_ANCHORS)
        val projectSpan = projectMaximum - projectMinimum
        if (
            scale !in MINIMUM_CORRECTION_SCALE..MAXIMUM_CORRECTION_SCALE ||
            abs(offset) > projectSpan * MAXIMUM_OFFSET_TO_PROJECT_SPAN_RATIO
        ) {
            return CorrectionAttempt.Rejected(
                RangeRecoveryReason.CONTROL_CORRECTION_OUT_OF_BOUNDS
            )
        }
        val denominatorFloor = max(projectSpan * CONTROL_RELATIVE_ERROR_FLOOR_RATIO, CONTROL_EPSILON)
        val errors = valid.map { anchor ->
            val corrected = scale * anchor.observedConcentration + offset
            abs(corrected - anchor.expectedConcentration) /
                max(abs(anchor.expectedConcentration), denominatorFloor) * 100.0
        }
        val medianError = errors.medianOrNull()
            ?: return CorrectionAttempt.Rejected(RangeRecoveryReason.CONTROL_VALIDATION_FAILED)
        val maximumError = errors.maxOrNull()
            ?: return CorrectionAttempt.Rejected(RangeRecoveryReason.CONTROL_VALIDATION_FAILED)
        if (
            medianError > MAXIMUM_CONTROL_MEDIAN_ERROR_PERCENT ||
            maximumError > MAXIMUM_CONTROL_ERROR_PERCENT
        ) {
            return CorrectionAttempt.Rejected(RangeRecoveryReason.CONTROL_VALIDATION_FAILED)
        }
        return CorrectionAttempt.Accepted(
            ValidatedCorrection(
                scale = scale,
                offset = offset,
                medianErrorPercent = medianError,
                maximumErrorPercent = maximumError
            )
        )
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private enum class SampleRangeClass { WITHIN, BELOW, ABOVE }

    private sealed interface CorrectionAttempt {
        data class Accepted(val value: ValidatedCorrection) : CorrectionAttempt
        data class Rejected(val reason: RangeRecoveryReason) : CorrectionAttempt
    }

    private data class ValidatedCorrection(
        val scale: Double,
        val offset: Double,
        val medianErrorPercent: Double,
        val maximumErrorPercent: Double
    )

    private const val MINIMUM_CONTROL_ANCHOR_COUNT: Int = 2
    private const val MINIMUM_CORRECTION_SCALE: Double = 0.50
    private const val MAXIMUM_CORRECTION_SCALE: Double = 2.00
    private const val MAXIMUM_OFFSET_TO_PROJECT_SPAN_RATIO: Double = 0.50
    private const val CONTROL_RELATIVE_ERROR_FLOOR_RATIO: Double = 0.01
    private const val MAXIMUM_CONTROL_MEDIAN_ERROR_PERCENT: Double = 15.0
    private const val MAXIMUM_CONTROL_ERROR_PERCENT: Double = 25.0
    private const val CONTROL_EPSILON: Double = 1e-12
}
