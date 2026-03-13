package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import com.muc.fluocolorquant.data.model.SpectrumExportData
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartLine
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.ui.components.charts.ChartVerticalMarker
import com.muc.fluocolorquant.utils.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * 单个峰值的 UI 模型。
 */
data class SpectrumPeakUiModel(
    val rank: Int,
    val wavelength: Double,
    val intensity: Double
)

/**
 * 单个通道的光谱数据模型。
 */
data class SpectrumChannelUiModel(
    val resultId: Long,
    val channelIndex: Int,
    val analyteId: String?,
    val analyteName: String,
    val chartData: ChartData,
    val peaks: List<SpectrumPeakUiModel>,
    val dataPointCount: Int,
    val minWavelength: Double,
    val maxWavelength: Double,
    val croppedImagePath: String?
) {
    val peakWavelength: Float?
        get() = peaks.firstOrNull()?.wavelength?.toFloat()

    val peakIntensity: Double?
        get() = peaks.firstOrNull()?.intensity
}

/**
 * 光谱结果页面的 UI 状态。
 */
data class SpectrumResultUiState(
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val results: List<SpectrumChannelUiModel> = emptyList(),
    val smoothingLevel: Int = SettingsRepository.DEFAULT_SPECTRUM_SMOOTHING,
    val sensitivity: String = SettingsRepository.DEFAULT_SPECTRUM_SENSITIVITY,
    val projectImageUri: String? = null,
    val comparisonChartData: ChartData? = null,
    val availableAnalytes: List<Analyte> = emptyList()
)

/**
 * 光谱结果处理参数。
 */
private data class SpectrumProcessingConfig(
    val minWavelength: Double,
    val maxWavelength: Double,
    val smoothingLevel: Int,
    val sensitivity: String
)

/**
 * 缓存的原始通道数据，避免重复反序列化。
 */
private data class CachedSpectrumChannelRaw(
    val resultId: Long,
    val columnIndex: Int,
    val analyteId: String?,
    val analyteName: String,
    val rawWavelengths: List<Double>,
    val rawIntensities: List<Double>,
    val imagePath: String
)

/**
 * 光谱结果展示页面的 ViewModel。
 */
@HiltViewModel
class SpectrumResultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spectrumRepository: SpectrumRepository,
    private val analyteRepository: AnalyteRepository,
    private val settingsRepository: SettingsRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpectrumResultUiState())
    val uiState: StateFlow<SpectrumResultUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    // 当前项目与导出缓存
    private var currentProject: Project? = null
    private val rawChannelData = mutableMapOf<Int, Pair<List<Double>, List<Double>>>()
    private val analyteIdMap = mutableMapOf<Int, String?>()
    private val channelImagePathMap = mutableMapOf<Int, String>()

    // 内存缓存，避免重复读取数据库和 JSON 反序列化
    private var cachedProjectId: String? = null
    private var cachedRawChannels: List<CachedSpectrumChannelRaw> = emptyList()
    private var cachedProcessingConfig: SpectrumProcessingConfig? = null
    private var cachedProcessedResults: List<SpectrumChannelUiModel> = emptyList()

    private val peakColors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFF4ECDC4),
        Color(0xFFFFA94D),
        Color(0xFF845EF7)
    )

    /**
     * 加载项目的所有通道光谱结果。
     */
    fun loadProjectResults(projectId: String, refreshSource: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            runCatching {
                val config = loadProcessingConfig()

                if (refreshSource || cachedProjectId != projectId || cachedRawChannels.isEmpty()) {
                    loadRawProjectData(projectId)
                }

                val channelModels = buildOrReuseProcessedResults(config, forceRebuild = refreshSource)
                val comparisonChart = buildComparisonChartData(channelModels, config)
                val analytes = analyteRepository.getAllAnalytes().first()

                _uiState.value = SpectrumResultUiState(
                    isLoading = false,
                    results = channelModels,
                    smoothingLevel = config.smoothingLevel,
                    sensitivity = config.sensitivity,
                    projectImageUri = currentProject?.imageUri,
                    comparisonChartData = comparisonChart,
                    availableAnalytes = analytes
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = UiText.DynamicString(
                        throwable.message ?: context.getString(R.string.spectrum_result_load_failed)
                    )
                )
            }
        }
    }

    /**
     * 更新平滑等级，并基于缓存即时重算结果。
     */
    fun updateSmoothing(level: Int) {
        if (_uiState.value.smoothingLevel == level) return

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setSpectrumSmoothing(level)
            rebuildResults(
                smoothingLevel = level,
                sensitivity = _uiState.value.sensitivity
            )
        }
    }

    /**
     * 更新灵敏度，并基于缓存即时重算结果。
     */
    fun updateSensitivity(sensitivity: String) {
        if (_uiState.value.sensitivity.equals(sensitivity, ignoreCase = true)) return

        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setSpectrumSensitivity(sensitivity)
            rebuildResults(
                smoothingLevel = _uiState.value.smoothingLevel,
                sensitivity = sensitivity
            )
        }
    }

    /**
     * 清除错误消息，避免重复提示。
     */
    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * 获取用于导出的光谱数据。
     */
    fun getExportData(): SpectrumExportData? {
        val project = currentProject ?: return null
        val uiResults = _uiState.value.results
        if (uiResults.isEmpty()) return null

        val exportChannels = uiResults.map { uiModel ->
            val channelIndex = uiModel.channelIndex - 1
            val rawData = rawChannelData[channelIndex] ?: Pair(emptyList(), emptyList())
            val croppedPath = channelImagePathMap[channelIndex]

            SpectrumChannelExportModel(
                channelIndex = uiModel.channelIndex,
                analyteName = uiModel.analyteName,
                analyteId = analyteIdMap[channelIndex],
                peakWavelength = uiModel.peakWavelength,
                peakIntensity = uiModel.peakIntensity,
                dataPointCount = uiModel.dataPointCount,
                minWavelength = uiModel.minWavelength,
                maxWavelength = uiModel.maxWavelength,
                wavelengths = rawData.first,
                intensities = rawData.second,
                chartData = uiModel.chartData,
                croppedImagePath = croppedPath
            )
        }

        return SpectrumExportData(
            project = project,
            channels = exportChannels
        )
    }

    /**
     * 重新基于缓存结果构建 UI 状态。
     */
    private suspend fun rebuildResults(smoothingLevel: Int, sensitivity: String) {
        val projectId = cachedProjectId ?: return
        val baseConfig = loadProcessingConfig(
            smoothingLevelOverride = smoothingLevel,
            sensitivityOverride = sensitivity
        )

        if (cachedRawChannels.isEmpty()) {
            loadProjectResults(projectId, refreshSource = true)
            return
        }

        val channelModels = buildOrReuseProcessedResults(baseConfig, forceRebuild = true)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = null,
            results = channelModels,
            smoothingLevel = baseConfig.smoothingLevel,
            sensitivity = baseConfig.sensitivity,
            comparisonChartData = buildComparisonChartData(channelModels, baseConfig)
        )
    }

    /**
     * 更新通道绑定的分析物，并刷新结果页。
     */
    fun updateChannelAnalyte(resultId: Long, channelIndex: Int, analyteId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val projectId = cachedProjectId ?: return@launch
            val existingResult = spectrumRepository.getResultById(resultId) ?: return@launch
            spectrumRepository.updateResult(existingResult.copy(analyteId = analyteId))
            analyteIdMap[channelIndex - 1] = analyteId
            loadProjectResults(projectId, refreshSource = true)
        }
    }

    /**
     * 读取当前结果处理配置。
     */
    private suspend fun loadProcessingConfig(
        smoothingLevelOverride: Int? = null,
        sensitivityOverride: String? = null
    ): SpectrumProcessingConfig {
        return SpectrumProcessingConfig(
            minWavelength = settingsRepository.spectrumMinWavelengthFlow.first().toDouble(),
            maxWavelength = settingsRepository.spectrumMaxWavelengthFlow.first().toDouble(),
            smoothingLevel = smoothingLevelOverride ?: settingsRepository.spectrumSmoothingFlow.first(),
            sensitivity = sensitivityOverride ?: settingsRepository.spectrumSensitivityFlow.first()
        )
    }

    /**
     * 加载项目原始数据并写入缓存。
     */
    private suspend fun loadRawProjectData(projectId: String) {
        val results = spectrumRepository.getResultsByProject(projectId).first()
        currentProject = projectRepository.getProjectById(projectId)
        cachedProjectId = projectId
        rawChannelData.clear()
        analyteIdMap.clear()
        channelImagePathMap.clear()

        val wavelengthsType = object : TypeToken<List<Double>>() {}.type
        val intensitiesType = object : TypeToken<List<Double>>() {}.type
        val unboundName = context.getString(R.string.spectrum_unbound)

        cachedRawChannels = results.sortedBy { it.columnIndex }.map { result ->
            val analyteName = result.analyteId?.let { analyteId ->
                analyteRepository.getAnalyteById(analyteId)?.name
            } ?: unboundName

            val rawWavelengths: List<Double> = gson.fromJson(result.wavelengths, wavelengthsType)
            val rawIntensities: List<Double> = gson.fromJson(result.intensities, intensitiesType)

            rawChannelData[result.columnIndex] = Pair(rawWavelengths, rawIntensities)
            analyteIdMap[result.columnIndex] = result.analyteId
            if (result.imagePath.isNotBlank()) {
                channelImagePathMap[result.columnIndex] = result.imagePath
            }

            CachedSpectrumChannelRaw(
                resultId = result.resultId,
                columnIndex = result.columnIndex,
                analyteId = result.analyteId,
                analyteName = analyteName,
                rawWavelengths = rawWavelengths,
                rawIntensities = rawIntensities,
                imagePath = result.imagePath
            )
        }
        cachedProcessingConfig = null
        cachedProcessedResults = emptyList()
    }

    /**
     * 使用缓存重建结果，参数不变时直接复用。
     */
    private fun buildOrReuseProcessedResults(
        config: SpectrumProcessingConfig,
        forceRebuild: Boolean
    ): List<SpectrumChannelUiModel> {
        if (!forceRebuild && cachedProcessingConfig == config && cachedProcessedResults.isNotEmpty()) {
            return cachedProcessedResults
        }

        val processedResults = cachedRawChannels.map { channel ->
            processChannel(channel, config)
        }

        cachedProcessingConfig = config
        cachedProcessedResults = processedResults
        return processedResults
    }

    /**
     * 单个通道的数据处理。
     */
    private fun processChannel(
        channel: CachedSpectrumChannelRaw,
        config: SpectrumProcessingConfig
    ): SpectrumChannelUiModel {
        val filteredData = channel.rawWavelengths.zip(channel.rawIntensities)
            .filter { (wavelength, _) ->
                wavelength in config.minWavelength..config.maxWavelength
            }

        if (filteredData.isEmpty()) {
            return SpectrumChannelUiModel(
                resultId = channel.resultId,
                channelIndex = channel.columnIndex + 1,
                analyteId = channel.analyteId,
                analyteName = channel.analyteName,
                chartData = ChartData(
                    title = context.getString(R.string.spectrum_curve_title),
                    xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
                    yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
                    xRange = Pair(config.minWavelength, config.maxWavelength),
                    yRange = Pair(-0.05, 1.05),
                    curvePoints = emptyList(),
                    scatterPoints = emptyList(),
                    showGrid = true
                ),
                peaks = emptyList(),
                dataPointCount = 0,
                minWavelength = config.minWavelength,
                maxWavelength = config.maxWavelength,
                croppedImagePath = channel.imagePath.ifBlank { null }
            )
        }

        val wavelengths = filteredData.map { it.first }
        val intensities = if (config.smoothingLevel > 0) {
            applyMovingAverage(filteredData.map { it.second }, config.smoothingLevel)
        } else {
            filteredData.map { it.second }
        }

        val peaks = findPeaks(wavelengths, intensities, config.sensitivity)
        val chartData = generateChartData(
            wavelengths = wavelengths,
            intensities = intensities,
            peaks = peaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )

        return SpectrumChannelUiModel(
            resultId = channel.resultId,
            channelIndex = channel.columnIndex + 1,
            analyteId = channel.analyteId,
            analyteName = channel.analyteName,
            chartData = chartData,
            peaks = peaks,
            dataPointCount = wavelengths.size,
            minWavelength = wavelengths.minOrNull() ?: config.minWavelength,
            maxWavelength = wavelengths.maxOrNull() ?: config.maxWavelength,
            croppedImagePath = channel.imagePath.ifBlank { null }
        )
    }

    /**
     * 移动平均平滑算法。
     */
    private fun applyMovingAverage(data: List<Double>, level: Int): List<Double> {
        if (level <= 0 || data.size < 3) return data

        val result = mutableListOf<Double>()
        for (index in data.indices) {
            val start = maxOf(0, index - level)
            val end = minOf(data.lastIndex, index + level)
            result += data.subList(start, end + 1).average()
        }
        return result
    }

    /**
     * 多峰检测。高灵敏度下保留更多候选峰，低灵敏度则更保守。
     */
    private fun findPeaks(
        wavelengths: List<Double>,
        intensities: List<Double>,
        sensitivity: String
    ): List<SpectrumPeakUiModel> {
        if (wavelengths.size < 3 || intensities.size < 3) return emptyList()

        val threshold = when {
            sensitivity.equals("High", ignoreCase = true) -> 0.20
            sensitivity.equals("Low", ignoreCase = true) -> 0.55
            else -> 0.35
        }
        val maxPeakCount = when {
            sensitivity.equals("High", ignoreCase = true) -> 4
            sensitivity.equals("Low", ignoreCase = true) -> 2
            else -> 3
        }
        val minPeakDistance = maxOf(4, intensities.size / 18)

        val candidates = mutableListOf<Pair<Int, Double>>()
        for (index in 1 until intensities.lastIndex) {
            val current = intensities[index]
            if (current < threshold) continue

            val previous = intensities[index - 1]
            val next = intensities[index + 1]
            if (current >= previous && current >= next) {
                candidates += index to current
            }
        }

        if (candidates.isEmpty()) {
            val peakIndex = intensities.indices.maxByOrNull { intensities[it] } ?: return emptyList()
            val peakIntensity = intensities[peakIndex]
            if (peakIntensity < threshold) return emptyList()
            return listOf(
                SpectrumPeakUiModel(
                    rank = 1,
                    wavelength = wavelengths[peakIndex],
                    intensity = peakIntensity
                )
            )
        }

        val mergedCandidates = mutableListOf<Pair<Int, Double>>()
        candidates.sortedByDescending { it.second }.forEach { candidate ->
            val tooClose = mergedCandidates.any { abs(it.first - candidate.first) < minPeakDistance }
            if (!tooClose) {
                mergedCandidates += candidate
            }
        }

        return mergedCandidates
            .sortedByDescending { it.second }
            .take(maxPeakCount)
            .mapIndexed { index, (peakIndex, peakIntensity) ->
                SpectrumPeakUiModel(
                    rank = index + 1,
                    wavelength = wavelengths[peakIndex],
                    intensity = peakIntensity
                )
            }
    }

    /**
     * 生成单通道图表数据。
     */
    private fun generateChartData(
        wavelengths: List<Double>,
        intensities: List<Double>,
        peaks: List<SpectrumPeakUiModel>,
        globalMinWavelength: Double,
        globalMaxWavelength: Double
    ): ChartData {
        val curvePoints = wavelengths.zip(intensities)
        val peakPoints = peaks.map { peak ->
            ChartPoint(
                x = peak.wavelength,
                y = peak.intensity,
                label = context.getString(R.string.spectrum_peak_rank_label, peak.rank)
            )
        }
        val markers = peaks.sortedBy { it.wavelength }.map { peak ->
            ChartVerticalMarker(
                x = peak.wavelength,
                label = String.format(Locale.US, "%.1f nm", peak.wavelength),
                color = peakColors[(peak.rank - 1) % peakColors.size]
            )
        }

        return ChartData(
            title = context.getString(R.string.spectrum_curve_title),
            xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
            yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
            xRange = Pair(globalMinWavelength, globalMaxWavelength),
            yRange = Pair(-0.05, 1.05),
            curvePoints = curvePoints,
            scatterPoints = peakPoints,
            pointColor = Color(0xFFFF6B6B),
            showGrid = true,
            verticalMarkers = markers
        )
    }

    /**
     * 生成多通道叠加对比图。
     */
    private fun buildComparisonChartData(
        results: List<SpectrumChannelUiModel>,
        config: SpectrumProcessingConfig
    ): ChartData? {
        if (results.isEmpty()) return null

        val lineColors = listOf(
            Color(0xFF2563EB),
            Color(0xFFDC2626),
            Color(0xFF059669),
            Color(0xFF7C3AED),
            Color(0xFFF59E0B),
            Color(0xFF0891B2),
            Color(0xFFDB2777),
            Color(0xFF65A30D),
            Color(0xFFEA580C),
            Color(0xFF4F46E5)
        )

        val primary = results.first()
        val overlayLines = results.drop(1).mapIndexed { index, channel ->
            ChartLine(
                label = context.getString(R.string.spectrum_compare_line_label, channel.channelIndex),
                points = channel.chartData.curvePoints,
                color = lineColors[(index + 1) % lineColors.size]
            )
        }

        return ChartData(
            title = context.getString(R.string.spectrum_compare_title),
            xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
            yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
            xRange = Pair(config.minWavelength, config.maxWavelength),
            yRange = Pair(-0.05, 1.05),
            curvePoints = primary.chartData.curvePoints,
            curveColor = lineColors.first(),
            scatterPoints = emptyList(),
            showGrid = true,
            chartType = "SPECTRUM_COMPARE",
            overlayLines = overlayLines
        )
    }
}
