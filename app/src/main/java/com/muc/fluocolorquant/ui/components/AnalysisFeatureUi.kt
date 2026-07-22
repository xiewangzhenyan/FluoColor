package com.muc.fluocolorquant.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature

/** 将稳定主特征机器码转换为当前语言下的短名称。 */
@Composable
fun analysisFeatureLabel(feature: AnalysisPrimaryFeature): String = stringResource(
    when (feature) {
        AnalysisPrimaryFeature.DELTA_E_2000 -> R.string.analysis_model_feature_delta_e
        AnalysisPrimaryFeature.OPTICAL_DENSITY -> R.string.analysis_model_feature_optical_density
        AnalysisPrimaryFeature.GRAY_LUMINOSITY -> R.string.analysis_model_feature_gray_luminosity
        AnalysisPrimaryFeature.RED_INTENSITY -> R.string.analysis_model_feature_red_intensity
        AnalysisPrimaryFeature.GREEN_INTENSITY -> R.string.analysis_model_feature_green_intensity
        AnalysisPrimaryFeature.BLUE_INTENSITY -> R.string.analysis_model_feature_blue_intensity
        AnalysisPrimaryFeature.AVERAGE_RGB -> R.string.analysis_model_feature_average_rgb
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY ->
            R.string.analysis_model_feature_net_fluorescence
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY ->
            R.string.analysis_model_feature_integrated_fluorescence
        AnalysisPrimaryFeature.FLUORESCENCE_SNR -> R.string.analysis_model_feature_fluorescence_snr
        AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM ->
            R.string.analysis_model_feature_peak_wavelength
        AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM ->
            R.string.analysis_model_feature_delta_peak
    }
)

/** 普通用户需要理解的信号来源说明；不显示处理器内部机器名。 */
@Composable
fun analysisFeatureDescription(feature: AnalysisPrimaryFeature): String = stringResource(
    when (feature) {
        AnalysisPrimaryFeature.DELTA_E_2000 -> R.string.standard_curve_feature_delta_e_desc
        AnalysisPrimaryFeature.OPTICAL_DENSITY ->
            R.string.standard_curve_feature_optical_density_desc
        AnalysisPrimaryFeature.GRAY_LUMINOSITY -> R.string.standard_curve_feature_gray_desc
        AnalysisPrimaryFeature.RED_INTENSITY -> R.string.standard_curve_feature_red_desc
        AnalysisPrimaryFeature.GREEN_INTENSITY -> R.string.standard_curve_feature_green_desc
        AnalysisPrimaryFeature.BLUE_INTENSITY -> R.string.standard_curve_feature_blue_desc
        AnalysisPrimaryFeature.AVERAGE_RGB -> R.string.standard_curve_feature_average_rgb_desc
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY ->
            R.string.standard_curve_feature_net_fluorescence_desc
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY ->
            R.string.standard_curve_feature_integrated_fluorescence_desc
        AnalysisPrimaryFeature.FLUORESCENCE_SNR -> R.string.standard_curve_feature_snr_desc
        AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM,
        AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM ->
            R.string.analysis_model_protocol_single_spectrum
    }
)

/** 信号输入框与 CSV 映射共同使用的范围说明。 */
@Composable
fun analysisFeatureRangeLabel(feature: AnalysisPrimaryFeature): String {
    @StringRes val rangeResource = when (feature) {
        AnalysisPrimaryFeature.GRAY_LUMINOSITY,
        AnalysisPrimaryFeature.RED_INTENSITY,
        AnalysisPrimaryFeature.GREEN_INTENSITY,
        AnalysisPrimaryFeature.BLUE_INTENSITY,
        AnalysisPrimaryFeature.AVERAGE_RGB -> R.string.standard_curve_signal_range_0_255
        AnalysisPrimaryFeature.DELTA_E_2000,
        AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM ->
            R.string.standard_curve_signal_range_non_negative
        AnalysisPrimaryFeature.FLUORESCENCE_SNR -> R.string.standard_curve_signal_range_0_9999
        AnalysisPrimaryFeature.OPTICAL_DENSITY,
        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
        AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM ->
            R.string.standard_curve_signal_range_unbounded
    }
    return stringResource(
        R.string.standard_curve_signal_range_format,
        stringResource(rangeResource)
    )
}
