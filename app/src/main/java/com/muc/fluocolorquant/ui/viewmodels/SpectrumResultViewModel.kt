package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单个通道的光谱数据模型
 */
data class SpectrumChannelUiModel(
    val channelIndex: Int,
    val analyteName: String,
    val chartData: ChartData,
    val peakWavelength: Float?,
    val peakIntensity: Double?,
    val dataPointCount: Int,
    val minWavelength: Double,
    val maxWavelength: Double
)

/**
 * 光谱结果页面的 UI 状态
 */
data class SpectrumResultUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val results: List<SpectrumChannelUiModel> = emptyList()
)

/**
 * 光谱结果展示页面的 ViewModel
 */
@HiltViewModel
class SpectrumResultViewModel @Inject constructor(
    private val spectrumRepository: SpectrumRepository,
    private val analyteRepository: AnalyteRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SpectrumResultUiState())
    val uiState: StateFlow<SpectrumResultUiState> = _uiState.asStateFlow()
    
    private val gson = Gson()
    
    /**
     * 加载项目的所有通道光谱结果
     */
    fun loadProjectResults(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            runCatching {
                // 获取全局设置
                val minWavelength = settingsRepository.spectrumMinWavelengthFlow.first().toDouble()
                val maxWavelength = settingsRepository.spectrumMaxWavelengthFlow.first().toDouble()
                val smoothingLevel = settingsRepository.spectrumSmoothingFlow.first()
                val sensitivity = settingsRepository.spectrumSensitivityFlow.first()
                
                // 获取项目的所有光谱结果
                val results = spectrumRepository.getResultsByProject(projectId).first()
                
                // 转换为 UI 模型
                val channelModels = results.sortedBy { it.columnIndex }.map { result ->
                    // 获取分析物名称
                    val analyteName = result.analyteId?.let {
                        analyteRepository.getAnalyteById(it)?.name
                    } ?: "未绑定"
                    
                    // 解析数据
                    val wavelengthsType = object : TypeToken<List<Double>>() {}.type
                    val intensitiesType = object : TypeToken<List<Double>>() {}.type
                    
                    val rawWavelengths: List<Double> = gson.fromJson(result.wavelengths, wavelengthsType)
                    val rawIntensities: List<Double> = gson.fromJson(result.intensities, intensitiesType)
                    
                    // 1. 数据裁剪 - 只保留在波长范围内的数据点
                    val filteredData = rawWavelengths.zip(rawIntensities)
                        .filter { (wavelength, _) -> 
                            wavelength >= minWavelength && wavelength <= maxWavelength 
                        }
                    
                    if (filteredData.isEmpty()) {
                        // 如果没有数据在范围内,使用原始数据
                        return@map SpectrumChannelUiModel(
                            channelIndex = result.columnIndex + 1,
                            analyteName = analyteName,
                            chartData = ChartData(
                                title = "光谱曲线",
                                xAxisLabel = "Wavelength (nm)",
                                yAxisLabel = "Normalized Intensity (a.u.)",
                                xRange = Pair(minWavelength, maxWavelength),
                                yRange = Pair(-0.05, 1.05),
                                curvePoints = emptyList(),
                                scatterPoints = emptyList(),
                                showGrid = true
                            ),
                            peakWavelength = null,
                            peakIntensity = null,
                            dataPointCount = 0,
                            minWavelength = minWavelength,
                            maxWavelength = maxWavelength
                        )
                    }
                    
                    val wavelengths = filteredData.map { it.first }
                    var intensities = filteredData.map { it.second }
                    
                    // 2. 数据平滑 - 移动平均算法
                    if (smoothingLevel > 0) {
                        intensities = applyMovingAverage(intensities, smoothingLevel)
                    }
                    
                    // 3. 寻峰 - 根据灵敏度调整
                    val peakInfo = findPeak(wavelengths, intensities, sensitivity)
                    
                    // 生成图表数据
                    val chartData = generateChartData(
                        wavelengths = wavelengths,
                        intensities = intensities,
                        peakIndex = peakInfo.first,
                        globalMinWavelength = minWavelength,
                        globalMaxWavelength = maxWavelength
                    )
                    
                    SpectrumChannelUiModel(
                        channelIndex = result.columnIndex + 1,
                        analyteName = analyteName,
                        chartData = chartData,
                        peakWavelength = peakInfo.second?.toFloat(),
                        peakIntensity = peakInfo.third,
                        dataPointCount = wavelengths.size,
                        minWavelength = wavelengths.minOrNull() ?: minWavelength,
                        maxWavelength = wavelengths.maxOrNull() ?: maxWavelength
                    )
                }
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    results = channelModels
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "加载数据失败"
                )
            }
        }
    }
    
    /**
     * 移动平均平滑算法
     */
    private fun applyMovingAverage(data: List<Double>, level: Int): List<Double> {
        if (level <= 0 || data.size < 3) return data
        
        val windowSize = level * 2 + 1
        val result = mutableListOf<Double>()
        
        for (i in data.indices) {
            val start = maxOf(0, i - level)
            val end = minOf(data.size - 1, i + level)
            val window = data.subList(start, end + 1)
            result.add(window.average())
        }
        
        return result
    }
    
    /**
     * 寻峰算法 - 根据灵敏度调整
     * 返回: Triple(峰值索引, 峰值波长, 峰值强度)
     */
    private fun findPeak(
        wavelengths: List<Double>,
        intensities: List<Double>,
        sensitivity: String
    ): Triple<Int, Double?, Double?> {
        if (intensities.isEmpty()) return Triple(-1, null, null)
        
        // 根据灵敏度设置阈值
        val threshold = when (sensitivity) {
            "High" -> 0.3  // 高灵敏度 - 低阈值
            "Low" -> 0.7   // 低灵敏度 - 高阈值
            else -> 0.5    // 中等灵敏度
        }
        
        // 找到最大强度
        var maxIntensity = 0.0
        var peakIndex = 0
        
        intensities.forEachIndexed { idx, intensity ->
            if (intensity > maxIntensity) {
                maxIntensity = intensity
                peakIndex = idx
            }
        }
        
        // 检查峰值是否高于阈值
        if (maxIntensity < threshold) {
            return Triple(-1, null, null)
        }
        
        val peakWavelength = wavelengths.getOrNull(peakIndex)
        return Triple(peakIndex, peakWavelength, maxIntensity)
    }
    
    /**
     * 生成图表数据
     */
    private fun generateChartData(
        wavelengths: List<Double>,
        intensities: List<Double>,
        peakIndex: Int,
        globalMinWavelength: Double,
        globalMaxWavelength: Double
    ): ChartData {
        // 生成曲线点
        val curvePoints = wavelengths.zip(intensities).map { (w, i) -> Pair(w, i) }
        
        // 使用全局设置的波长范围,不添加 padding
        val yMin = 0.0
        val yMax = 1.0
        val yPadding = 0.05
        
        // 峰值点高亮
        val peakPoint = if (peakIndex >= 0 && peakIndex in wavelengths.indices) {
            listOf(ChartPoint(wavelengths[peakIndex], intensities[peakIndex]))
        } else {
            emptyList()
        }
        
        return ChartData(
            title = "光谱曲线",
            xAxisLabel = "Wavelength (nm)",
            yAxisLabel = "Normalized Intensity (a.u.)",
            xRange = Pair(globalMinWavelength, globalMaxWavelength),  // 严格使用全局设置
            yRange = Pair(yMin - yPadding, yMax + yPadding),
            curvePoints = curvePoints,
            scatterPoints = peakPoint,
            showGrid = true
        )
    }
}
