package com.muc.fluocolorquant.utils.math

/**
 * 光谱图像质量问题类型。
 */
enum class SpectrumImageQualityIssueType {
    OVER_EXPOSED,
    UNDER_EXPOSED,
    BLURRED,
    TILTED,
    MERGED_CHANNELS,
    UNEVEN_BACKGROUND
}

/**
 * 图像质量决策结果，仅用于提示，不强制拦截用户流程。
 */
enum class SpectrumImageQualityDecision {
    PASS,
    REVIEW,
    RETAKE
}

/**
 * 光谱图像质量检查结果。
 */
data class SpectrumImageQualityReport(
    val score: Int,
    val issues: List<SpectrumImageQualityIssueType> = emptyList(),
    val overExposureRatio: Double = 0.0,
    val signalContrast: Double = 0.0,
    val blurVariance: Double = 0.0,
    val tiltDegrees: Double = 0.0,
    val minimumGapRatio: Double = 1.0,
    val backgroundStdDev: Double = 0.0
) {
    val hasIssues: Boolean
        get() = issues.isNotEmpty()

    val decision: SpectrumImageQualityDecision
        get() {
            val severeIssueCount = issues.count {
                it == SpectrumImageQualityIssueType.OVER_EXPOSED ||
                    it == SpectrumImageQualityIssueType.UNDER_EXPOSED ||
                    it == SpectrumImageQualityIssueType.BLURRED ||
                    it == SpectrumImageQualityIssueType.MERGED_CHANNELS
            }
            return when {
                score < 60 || severeIssueCount >= 2 -> SpectrumImageQualityDecision.RETAKE
                hasIssues || score < 80 -> SpectrumImageQualityDecision.REVIEW
                else -> SpectrumImageQualityDecision.PASS
            }
        }
}
