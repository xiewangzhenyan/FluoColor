package com.muc.fluocolorquant.data.model

/**
 * 光谱标定附加数据。
 *
 * 统一挂在 SpectrumCalibration.referencePoints 字段中，
 * 避免为了标定诊断信息单独升级表结构。
 */
data class SpectrumCalibrationReferenceData(
    val manualPoints: List<SpectrumCalibrationReferencePoint> = emptyList(),
    val autoDebug: SpectrumAutoCalibrationDebug? = null
)

/**
 * 自动标定质量等级。
 */
enum class SpectrumAutoCalibrationQualityLevel {
    EXCELLENT,
    USABLE,
    REVIEW
}

/**
 * 自动标定诊断原因。
 */
enum class SpectrumAutoCalibrationIssue {
    FIT_RMSE_HIGH,
    EFFECTIVE_HEIGHT_LOW,
    EFFECTIVE_HEIGHT_HIGH,
    FALLBACK_ALIGNMENT,
    IMAGE_QUALITY_WARNING
}

/**
 * 手动标定点的轻量持久化模型。
 */
data class SpectrumCalibrationReferencePoint(
    val x: Float,
    val y: Float,
    val wavelength: Float
)

/**
 * 自动标定残差点诊断数据。
 */
data class SpectrumCalibrationResidualPoint(
    val rank: Int,
    val normalizedY: Double,
    val referenceWavelength: Double,
    val fittedWavelength: Double,
    val residual: Double
)

/**
 * 自动标定诊断信息。
 */
data class SpectrumAutoCalibrationDebug(
    val calibrationCropPath: String? = null,
    val detectedPeakCount: Int = 0,
    val referencePeakCount: Int = 0,
    val fitRmse: Double? = null,
    val effectiveCoverage: Double? = null,
    val qualityScore: Int? = null,
    val qualityLevel: SpectrumAutoCalibrationQualityLevel? = null,
    val issues: List<SpectrumAutoCalibrationIssue> = emptyList(),
    val meanAbsoluteResidual: Double? = null,
    val maxResidual: Double? = null,
    val residualPoints: List<SpectrumCalibrationResidualPoint> = emptyList(),
    val equation: String? = null,
    val usedFallbackAlignment: Boolean = false
)
