package com.muc.fluocolorquant.utils

import com.muc.fluocolorquant.data.enums.PixelType

/**
 * 检测模式支持工具。
 * 统一封装检测模式解析、推荐像素特征以及模型候选路径，避免各层重复写分支。
 */
enum class DetectionModeKind {
    FLUORESCENCE,
    COLORIMETRIC,
    SPECTRUM
}

object DetectionModeSupport {

    private const val COMMON_CONCENTRATION_MODEL_ASSET =
        "models/improved_concentration_model_lite.ptl"
    private const val FLUORESCENCE_CONCENTRATION_MODEL_ASSET =
        "models/fluorescence_concentration_model_lite.ptl"
    private const val COLORIMETRIC_CONCENTRATION_MODEL_ASSET =
        "models/colorimetric_concentration_model_lite.ptl"

    /**
     * 将数据库中的检测模式字符串解析为统一枚举。
     */
    fun fromStorageValue(value: String?): DetectionModeKind {
        return when (value?.uppercase()) {
            "COLORIMETRIC" -> DetectionModeKind.COLORIMETRIC
            "SPECTRUM" -> DetectionModeKind.SPECTRUM
            else -> DetectionModeKind.FLUORESCENCE
        }
    }

    /**
     * 获取当前检测模式推荐的默认像素特征。
     */
    fun defaultPixelType(mode: DetectionModeKind): PixelType {
        return when (mode) {
            DetectionModeKind.FLUORESCENCE -> PixelType.GREEN
            DetectionModeKind.COLORIMETRIC -> PixelType.CIE_L
            DetectionModeKind.SPECTRUM -> PixelType.GRAY_LUMINOSITY
        }
    }

    /**
     * 获取当前检测模式推荐的候选像素特征集合。
     * 返回顺序有意义，前面的类型优先级更高。
     */
    fun recommendedPixelTypes(mode: DetectionModeKind): Set<PixelType> {
        return when (mode) {
            DetectionModeKind.FLUORESCENCE -> linkedSetOf(
                PixelType.GREEN,
                PixelType.EUCLIDEAN_NORM,
                PixelType.GRAY_LUMINOSITY,
                PixelType.RATIO_GB,
                PixelType.VALUE_HSV
            )

            DetectionModeKind.COLORIMETRIC -> linkedSetOf(
                PixelType.CIE_L,
                PixelType.GRAY_LUMINOSITY,
                PixelType.HUE,
                PixelType.SATURATION_HSV,
                PixelType.AVERAGE_RGB
            )

            DetectionModeKind.SPECTRUM -> linkedSetOf(PixelType.GRAY_LUMINOSITY)
        }
    }

    /**
     * 获取当前检测模式可尝试加载的浓度模型路径。
     * 若模式专用模型不存在，应由调用方回退到通用模型。
     */
    fun concentrationModelCandidates(mode: DetectionModeKind): List<String> {
        return when (mode) {
            DetectionModeKind.FLUORESCENCE -> listOf(
                FLUORESCENCE_CONCENTRATION_MODEL_ASSET,
                COMMON_CONCENTRATION_MODEL_ASSET
            )

            DetectionModeKind.COLORIMETRIC -> listOf(
                COLORIMETRIC_CONCENTRATION_MODEL_ASSET,
                COMMON_CONCENTRATION_MODEL_ASSET
            )

            DetectionModeKind.SPECTRUM -> listOf(COMMON_CONCENTRATION_MODEL_ASSET)
        }
    }
}
