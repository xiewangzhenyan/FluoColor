package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION

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

    private val colorimetricFeatures = linkedSetOf(
        AnalysisPrimaryFeature.DELTA_E_2000,
        AnalysisPrimaryFeature.OPTICAL_DENSITY,
        AnalysisPrimaryFeature.GRAY_LUMINOSITY,
        AnalysisPrimaryFeature.RED_INTENSITY,
        AnalysisPrimaryFeature.GREEN_INTENSITY,
        AnalysisPrimaryFeature.BLUE_INTENSITY,
        AnalysisPrimaryFeature.AVERAGE_RGB
    )

    private val fluorescenceFeatures = linkedSetOf(
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.FLUORESCENCE_SNR
    )

    /** 返回当前检测模式能够由生产处理器真实计算的主特征。 */
    fun allowedFeatures(modality: DetectionModality): Set<AnalysisPrimaryFeature> = when (modality) {
        DetectionModality.COLORIMETRIC -> colorimetricFeatures
        DetectionModality.FLUORESCENCE -> fluorescenceFeatures
        DetectionModality.SPECTRUM -> linkedSetOf(
            AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM,
            AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM
        )
    }

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
        AnalysisPrimaryFeature.AVERAGE_RGB -> AnalysisSignalRange(0.0, 255.0)
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY -> AnalysisSignalRange()
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY -> AnalysisSignalRange()
        AnalysisPrimaryFeature.FLUORESCENCE_SNR -> AnalysisSignalRange(0.0, 9999.0)
        AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM -> AnalysisSignalRange(minimum = 0.0)
        AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM -> AnalysisSignalRange()
    }
}
