package com.muc.fluocolorquant.data.model

import com.muc.fluocolorquant.ui.components.charts.ChartData

/**
 * 光谱导出数据封装类
 */
data class SpectrumExportData(
    val project: Project,
    val channels: List<SpectrumChannelExportModel>
)

/**
 * 单个通道的导出数据模型
 */
data class SpectrumChannelExportModel(
    val channelIndex: Int,
    val analyteName: String,
    val analyteId: String?,
    val peakWavelength: Float?,
    val peakIntensity: Double?,
    val dataPointCount: Int,
    val minWavelength: Double,
    val maxWavelength: Double,
    val wavelengths: List<Double>,
    val intensities: List<Double>,
    val chartData: ChartData,
    val croppedImagePath: String? = null // 裁切后的原始图片路径
)
