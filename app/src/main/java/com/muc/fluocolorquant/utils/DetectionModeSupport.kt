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

    /**
     * 当前旧96孔板流程共用的浓度预测模型。
     *
     * 用户已经明确确认：在专用比色模型训练完成前，比色和荧光都使用该模型，并且普通结果页
     * 不增加额外的实验性提示。后续模型上传能力应通过版本化 AnalysisModel 覆盖这里，而不是
     * 再次在旧兼容链中硬编码两个实际不存在的文件名。
     */
    const val SHARED_CONCENTRATION_MODEL_ASSET: String =
        "models/improved_concentration_model_lite.ptl"

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
     * 获取旧孔板兼容链可以尝试加载的浓度模型路径。
     *
     * 新模板链使用版本化 AnalysisModel；此函数仅保留给旧孔板流程。当前比色和荧光按照
     * 用户确认共用同一个 PTL，光谱检测继续走独立算法链，不使用该图像浓度模型。
     */
    fun concentrationModelCandidates(mode: DetectionModeKind): List<String> {
        return when (mode) {
            DetectionModeKind.FLUORESCENCE,
            DetectionModeKind.COLORIMETRIC -> listOf(SHARED_CONCENTRATION_MODEL_ASSET)

            DetectionModeKind.SPECTRUM -> emptyList()
        }
    }
}
