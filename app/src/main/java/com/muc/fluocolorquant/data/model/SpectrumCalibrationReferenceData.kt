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
 * 手动标定点的轻量持久化模型。
 */
data class SpectrumCalibrationReferencePoint(
    val x: Float,
    val y: Float,
    val wavelength: Float
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
    val usedFallbackAlignment: Boolean = false
)
