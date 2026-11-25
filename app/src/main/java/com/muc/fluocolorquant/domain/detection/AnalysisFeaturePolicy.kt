package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.signal.SignalFeatureCatalog
import com.muc.fluocolorquant.domain.signal.SignalFeatureTier

/**
 * 用户输入校验能够安全使用的信号范围。
 *
 * 只有具有严格物理边界的特征才填写最大值；光密度、背景扣除荧光和积分强度没有跨设备
 * 通用上限，因此只声明已知下限或允许负值，界面不得伪造一个看似精确的固定范围。
 */
data class AnalysisSignalRange(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val allowsNegative: Boolean = minimum == null || minimum < 0.0
) {
    /** 对用户手工输入和 CSV 映射执行同一套硬边界校验。 */
    fun contains(value: Double): Boolean {
        if (!value.isFinite()) return false
        if (minimum != null && value < minimum) return false
        if (maximum != null && value > maximum) return false
        return true
    }
}

/**
 * 标准曲线、模板和检测运行共享的主特征策略。
 *
 * 该对象是“检测模式 → 允许特征 → 处理器版本”的唯一事实来源，避免页面允许一种特征，
 * 保存时写入另一种特征，而检测阶段又使用第三套默认值。
 */
object AnalysisFeaturePolicy {

    /**
     * 普通自动推荐池。
     *
     * 经典加权灰度放在首位并明确采用 0.299R + 0.587G + 0.114B。ΔE2000和相对光密度
     * 在存在参考位时参加比较；没有参考位时只跳过这两项，不得连带删除RGB、Lab等直接信号。
     */
    private val recommendedColorimetricFeatures = linkedSetOf(
        AnalysisPrimaryFeature.GRAY_LUMINOSITY,
        AnalysisPrimaryFeature.DELTA_E_2000,
        AnalysisPrimaryFeature.OPTICAL_DENSITY,
        AnalysisPrimaryFeature.RED_INTENSITY,
        AnalysisPrimaryFeature.GREEN_INTENSITY,
        AnalysisPrimaryFeature.BLUE_INTENSITY,
        AnalysisPrimaryFeature.AVERAGE_RGB,
        AnalysisPrimaryFeature.CIE_L_STAR,
        AnalysisPrimaryFeature.CIE_A_STAR,
        AnalysisPrimaryFeature.CIE_B_STAR
    )

    private val extendedColorimetricFeatures = linkedSetOf(
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM,
        AnalysisPrimaryFeature.RED_GREEN_RATIO,
        AnalysisPrimaryFeature.RED_BLUE_RATIO,
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO,
        AnalysisPrimaryFeature.HSV_HUE,
        AnalysisPrimaryFeature.HSV_SATURATION,
        AnalysisPrimaryFeature.HSV_VALUE,
        AnalysisPrimaryFeature.HSL_SATURATION,
        AnalysisPrimaryFeature.HSL_LIGHTNESS,
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY,
        AnalysisPrimaryFeature.YCBCR_Y,
        AnalysisPrimaryFeature.YCBCR_CB,
        AnalysisPrimaryFeature.YCBCR_CR
    )

    private val compatibilityColorimetricFeatures = linkedSetOf(
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE,
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE,
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS,
        AnalysisPrimaryFeature.CMYK_CYAN,
        AnalysisPrimaryFeature.CMYK_MAGENTA,
        AnalysisPrimaryFeature.CMYK_YELLOW,
        AnalysisPrimaryFeature.CMYK_BLACK
    )

    private val colorimetricFeatures = linkedSetOf<AnalysisPrimaryFeature>().apply {
        addAll(recommendedColorimetricFeatures)
        addAll(extendedColorimetricFeatures)
        addAll(compatibilityColorimetricFeatures)
    }

    /** 荧光原生强度特征继续位于首位，保持默认工作流和既有曲线语义不变。 */
    private val nativeFluorescenceFeatures = linkedSetOf(
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.FLUORESCENCE_SNR
    )

    /**
     * 荧光自动比较池同时开放可由校正 ROI RGB 真实计算的基础颜色描述量。
     *
     * ΔE2000 和光密度依赖明确的比色参考语义，不能直接搬到荧光；RGB、经典灰度和 Lab
     * 不依赖参考位，可与三项荧光原生信号一起参与 R² 排名。通道比率等仍放在扩展组，
     * 由用户主动选择，避免默认候选过多造成偶然高 R²。
     */
    private val recommendedFluorescenceFeatures = linkedSetOf<AnalysisPrimaryFeature>().apply {
        addAll(nativeFluorescenceFeatures)
        addAll(
            recommendedColorimetricFeatures.filterNot { feature ->
                feature == AnalysisPrimaryFeature.DELTA_E_2000 ||
                    feature == AnalysisPrimaryFeature.OPTICAL_DENSITY
            }
        )
    }

    private val fluorescenceFeatures = linkedSetOf<AnalysisPrimaryFeature>().apply {
        addAll(nativeFluorescenceFeatures)
        addAll(
            colorimetricFeatures.filterNot { feature ->
                feature == AnalysisPrimaryFeature.DELTA_E_2000 ||
                    feature == AnalysisPrimaryFeature.OPTICAL_DENSITY
            }
        )
    }

    /** 返回当前检测模式能够由生产处理器真实计算的主特征。 */
    fun allowedFeatures(modality: DetectionModality): Set<AnalysisPrimaryFeature> = when (modality) {
        DetectionModality.COLORIMETRIC -> colorimetricFeatures
        DetectionModality.FLUORESCENCE -> fluorescenceFeatures
        DetectionModality.SPECTRUM -> linkedSetOf(
            AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM,
            AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM
        )
    }

    /** 普通自动标定默认只比较推荐层，扩展和兼容信号由用户在高级设置中主动加入。 */
    fun recommendedFeatures(modality: DetectionModality): Set<AnalysisPrimaryFeature> = when (modality) {
        DetectionModality.COLORIMETRIC -> recommendedColorimetricFeatures
        DetectionModality.FLUORESCENCE -> recommendedFluorescenceFeatures
        DetectionModality.SPECTRUM -> linkedSetOf(AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM)
    }

    /** 供现场选择器和设置页分组显示，不把兼容经验公式混入默认推荐。 */
    fun featureTier(feature: AnalysisPrimaryFeature): SignalFeatureTier = when (feature) {
        AnalysisPrimaryFeature.DELTA_E_2000,
        AnalysisPrimaryFeature.OPTICAL_DENSITY -> SignalFeatureTier.RECOMMENDED
        else -> SignalFeatureCatalog.definitionForPrimaryFeature(feature)?.tier
            ?: SignalFeatureTier.RECOMMENDED
    }

    /** 只有必须与明确参考信号比较的特征才强制要求空白/参考位。 */
    fun requiresReference(feature: AnalysisPrimaryFeature): Boolean = feature in setOf(
        AnalysisPrimaryFeature.DELTA_E_2000,
        AnalysisPrimaryFeature.OPTICAL_DENSITY
    )

    /** 普通用户不选择时使用的科学默认特征。 */
    fun defaultFeature(modality: DetectionModality): AnalysisPrimaryFeature = when (modality) {
        DetectionModality.COLORIMETRIC -> AnalysisPrimaryFeature.DELTA_E_2000
        DetectionModality.FLUORESCENCE -> AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
        DetectionModality.SPECTRUM -> AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM
    }

    /** 标准曲线只能使用所属模态处理器能够真实输出的特征。 */
    fun isCompatible(
        modality: DetectionModality,
        feature: AnalysisPrimaryFeature
    ): Boolean = feature in allowedFeatures(modality)

    /** 返回生产检测链必须冻结的处理器名称和版本。 */
    fun processorIdentity(modality: DetectionModality): Pair<String, String> = when (modality) {
        DetectionModality.COLORIMETRIC ->
            COLORIMETRIC_PROCESSOR_NAME to COLORIMETRIC_PROCESSOR_VERSION
        DetectionModality.FLUORESCENCE ->
            FLUORESCENCE_PROCESSOR_NAME to FLUORESCENCE_PROCESSOR_VERSION
        DetectionModality.SPECTRUM -> "spectrum-photometry" to "v1"
    }

    /**
     * 返回能够用于硬校验的理论范围。
     *
     * ΔE2000、光密度和荧光强度只使用有限值及方向性约束；RGB/灰度来自 8 位通道，
     * 因而能够安全限制在 0～255。SNR 与生产处理器一致限制在 0～9999。
     */
    fun signalRange(feature: AnalysisPrimaryFeature): AnalysisSignalRange = when (feature) {
        AnalysisPrimaryFeature.DELTA_E_2000 -> AnalysisSignalRange(minimum = 0.0)
        AnalysisPrimaryFeature.OPTICAL_DENSITY -> AnalysisSignalRange()
        AnalysisPrimaryFeature.GRAY_LUMINOSITY,
        AnalysisPrimaryFeature.RED_INTENSITY,
        AnalysisPrimaryFeature.GREEN_INTENSITY,
        AnalysisPrimaryFeature.BLUE_INTENSITY,
        AnalysisPrimaryFeature.AVERAGE_RGB,
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM,
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE,
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE,
        AnalysisPrimaryFeature.RED_GREEN_RATIO,
        AnalysisPrimaryFeature.RED_BLUE_RATIO,
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO,
        AnalysisPrimaryFeature.HSV_HUE,
        AnalysisPrimaryFeature.HSV_SATURATION,
        AnalysisPrimaryFeature.HSV_VALUE,
        AnalysisPrimaryFeature.HSL_SATURATION,
        AnalysisPrimaryFeature.HSL_LIGHTNESS,
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_L_STAR,
        AnalysisPrimaryFeature.CIE_A_STAR,
        AnalysisPrimaryFeature.CIE_B_STAR,
        AnalysisPrimaryFeature.YCBCR_Y,
        AnalysisPrimaryFeature.YCBCR_CB,
        AnalysisPrimaryFeature.YCBCR_CR,
        AnalysisPrimaryFeature.CMYK_CYAN,
        AnalysisPrimaryFeature.CMYK_MAGENTA,
        AnalysisPrimaryFeature.CMYK_YELLOW,
        AnalysisPrimaryFeature.CMYK_BLACK -> {
            val range = SignalFeatureCatalog.definitionForPrimaryFeature(feature)?.valueRange
            AnalysisSignalRange(
                minimum = range?.minimum,
                maximum = range?.maximum
            )
        }
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY -> AnalysisSignalRange()
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY -> AnalysisSignalRange()
        AnalysisPrimaryFeature.FLUORESCENCE_SNR -> AnalysisSignalRange(0.0, 9999.0)
        AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM -> AnalysisSignalRange(minimum = 0.0)
        AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM -> AnalysisSignalRange()
    }
}
