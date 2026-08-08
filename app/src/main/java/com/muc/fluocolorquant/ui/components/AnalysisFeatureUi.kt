package com.muc.fluocolorquant.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AreaChart
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM -> R.string.analysis_model_feature_euclidean_rgb_norm
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE -> R.string.analysis_model_feature_inverse_rb_average
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE -> R.string.analysis_model_feature_red_blue_difference
        AnalysisPrimaryFeature.RED_GREEN_RATIO -> R.string.analysis_model_feature_red_green_ratio
        AnalysisPrimaryFeature.RED_BLUE_RATIO -> R.string.analysis_model_feature_red_blue_ratio
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO -> R.string.analysis_model_feature_green_blue_ratio
        AnalysisPrimaryFeature.HSV_HUE -> R.string.analysis_model_feature_hsv_hue
        AnalysisPrimaryFeature.HSV_SATURATION -> R.string.analysis_model_feature_hsv_saturation
        AnalysisPrimaryFeature.HSV_VALUE -> R.string.analysis_model_feature_hsv_value
        AnalysisPrimaryFeature.HSL_SATURATION -> R.string.analysis_model_feature_hsl_saturation
        AnalysisPrimaryFeature.HSL_LIGHTNESS -> R.string.analysis_model_feature_hsl_lightness
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS -> R.string.analysis_model_feature_cie_x_tristimulus
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS -> R.string.analysis_model_feature_cie_y_tristimulus
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS -> R.string.analysis_model_feature_cie_z_tristimulus
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY -> R.string.analysis_model_feature_cie_x_chromaticity
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY -> R.string.analysis_model_feature_cie_y_chromaticity
        AnalysisPrimaryFeature.CIE_L_STAR -> R.string.analysis_model_feature_cie_l_star
        AnalysisPrimaryFeature.CIE_A_STAR -> R.string.analysis_model_feature_cie_a_star
        AnalysisPrimaryFeature.CIE_B_STAR -> R.string.analysis_model_feature_cie_b_star
        AnalysisPrimaryFeature.YCBCR_Y -> R.string.analysis_model_feature_ycbcr_y
        AnalysisPrimaryFeature.YCBCR_CB -> R.string.analysis_model_feature_ycbcr_cb
        AnalysisPrimaryFeature.YCBCR_CR -> R.string.analysis_model_feature_ycbcr_cr
        AnalysisPrimaryFeature.CMYK_CYAN -> R.string.analysis_model_feature_cmyk_cyan
        AnalysisPrimaryFeature.CMYK_MAGENTA -> R.string.analysis_model_feature_cmyk_magenta
        AnalysisPrimaryFeature.CMYK_YELLOW -> R.string.analysis_model_feature_cmyk_yellow
        AnalysisPrimaryFeature.CMYK_BLACK -> R.string.analysis_model_feature_cmyk_black
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

/**
 * 为信号选择器提供克制的科学类别图标。
 *
 * 图标只承担快速扫描用途，不替代信号名称与科学定义。这里按“公式、颜色、光学、
 * 比值、荧光、光谱”区分高频类别，让现场标定和系统设置中的长列表更容易浏览；
 * 统一使用单色小图标，避免专业界面因过度装饰而显得花哨。
 */
fun analysisFeatureIcon(feature: AnalysisPrimaryFeature): ImageVector = when (feature) {
    AnalysisPrimaryFeature.GRAY_LUMINOSITY -> Icons.Filled.Functions

    AnalysisPrimaryFeature.RED_INTENSITY,
    AnalysisPrimaryFeature.GREEN_INTENSITY,
    AnalysisPrimaryFeature.BLUE_INTENSITY -> Icons.Filled.ColorLens

    AnalysisPrimaryFeature.AVERAGE_RGB -> Icons.Filled.GridOn

    AnalysisPrimaryFeature.DELTA_E_2000 -> Icons.Filled.Palette
    AnalysisPrimaryFeature.OPTICAL_DENSITY -> Icons.Filled.Opacity

    AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM,
    AnalysisPrimaryFeature.RED_GREEN_RATIO,
    AnalysisPrimaryFeature.RED_BLUE_RATIO,
    AnalysisPrimaryFeature.GREEN_BLUE_RATIO -> Icons.AutoMirrored.Filled.CompareArrows

    AnalysisPrimaryFeature.INVERSE_RB_AVERAGE,
    AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE -> Icons.Outlined.Description

    AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY -> Icons.Filled.Flare
    AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY -> Icons.Filled.AreaChart
    AnalysisPrimaryFeature.FLUORESCENCE_SNR -> Icons.Filled.SignalCellularAlt
    AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM -> Icons.Filled.Timeline
    AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM -> Icons.AutoMirrored.Filled.CompareArrows

    AnalysisPrimaryFeature.CIE_X_TRISTIMULUS,
    AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS,
    AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS,
    AnalysisPrimaryFeature.CMYK_CYAN,
    AnalysisPrimaryFeature.CMYK_MAGENTA,
    AnalysisPrimaryFeature.CMYK_YELLOW,
    AnalysisPrimaryFeature.CMYK_BLACK -> Icons.Filled.Science

    AnalysisPrimaryFeature.HSV_HUE,
    AnalysisPrimaryFeature.HSV_SATURATION,
    AnalysisPrimaryFeature.HSV_VALUE,
    AnalysisPrimaryFeature.HSL_SATURATION,
    AnalysisPrimaryFeature.HSL_LIGHTNESS,
    AnalysisPrimaryFeature.CIE_X_CHROMATICITY,
    AnalysisPrimaryFeature.CIE_Y_CHROMATICITY,
    AnalysisPrimaryFeature.CIE_L_STAR,
    AnalysisPrimaryFeature.CIE_A_STAR,
    AnalysisPrimaryFeature.CIE_B_STAR,
    AnalysisPrimaryFeature.YCBCR_Y,
    AnalysisPrimaryFeature.YCBCR_CB,
    AnalysisPrimaryFeature.YCBCR_CR -> Icons.Filled.Palette
}

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
        AnalysisPrimaryFeature.RED_GREEN_RATIO,
        AnalysisPrimaryFeature.RED_BLUE_RATIO,
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO -> R.string.standard_curve_feature_ratio_desc
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE,
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE -> R.string.standard_curve_feature_legacy_desc
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS,
        AnalysisPrimaryFeature.CMYK_CYAN,
        AnalysisPrimaryFeature.CMYK_MAGENTA,
        AnalysisPrimaryFeature.CMYK_YELLOW,
        AnalysisPrimaryFeature.CMYK_BLACK -> R.string.standard_curve_feature_experimental_desc
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM,
        AnalysisPrimaryFeature.HSV_HUE,
        AnalysisPrimaryFeature.HSV_SATURATION,
        AnalysisPrimaryFeature.HSV_VALUE,
        AnalysisPrimaryFeature.HSL_SATURATION,
        AnalysisPrimaryFeature.HSL_LIGHTNESS,
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_L_STAR,
        AnalysisPrimaryFeature.CIE_A_STAR,
        AnalysisPrimaryFeature.CIE_B_STAR,
        AnalysisPrimaryFeature.YCBCR_Y,
        AnalysisPrimaryFeature.YCBCR_CB,
        AnalysisPrimaryFeature.YCBCR_CR -> R.string.standard_curve_feature_color_transform_desc
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
        AnalysisPrimaryFeature.AVERAGE_RGB,
        AnalysisPrimaryFeature.YCBCR_Y,
        AnalysisPrimaryFeature.YCBCR_CB,
        AnalysisPrimaryFeature.YCBCR_CR -> R.string.standard_curve_signal_range_0_255
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM,
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE,
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE ->
            R.string.standard_curve_signal_range_extended_rgb
        AnalysisPrimaryFeature.RED_GREEN_RATIO,
        AnalysisPrimaryFeature.RED_BLUE_RATIO,
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO -> R.string.standard_curve_signal_range_0_20
        AnalysisPrimaryFeature.HSV_HUE -> R.string.standard_curve_signal_range_0_360
        AnalysisPrimaryFeature.HSV_SATURATION,
        AnalysisPrimaryFeature.HSV_VALUE,
        AnalysisPrimaryFeature.HSL_SATURATION,
        AnalysisPrimaryFeature.HSL_LIGHTNESS,
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY,
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY,
        AnalysisPrimaryFeature.CMYK_CYAN,
        AnalysisPrimaryFeature.CMYK_MAGENTA,
        AnalysisPrimaryFeature.CMYK_YELLOW,
        AnalysisPrimaryFeature.CMYK_BLACK -> R.string.standard_curve_signal_range_0_1
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS,
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS -> R.string.standard_curve_signal_range_0_110
        AnalysisPrimaryFeature.CIE_L_STAR,
        AnalysisPrimaryFeature.CIE_A_STAR,
        AnalysisPrimaryFeature.CIE_B_STAR -> R.string.standard_curve_signal_range_lab
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
