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
import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationIssue
import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationQualityLevel
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumCalibrationReferenceData
import com.muc.fluocolorquant.data.model.SpectrumCalibrationResidualPoint
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
import com.muc.fluocolorquant.utils.math.SpectrumCalibrationMath
import com.muc.fluocolorquant.utils.math.integrateSpectrumPeakArea
import com.muc.fluocolorquant.utils.math.interpolateSpectrumHalfMaxCrossing
import com.muc.fluocolorquant.utils.math.sortAndMergeSpectrumSamples
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
    val intensity: Double,
    val prominence: Double = 0.0,
    val area: Double = 0.0,
    val fullWidthHalfMax: Double = 0.0,
    val signalToNoise: Double = 0.0
)

enum class SpectrumCurveMode { RAW, CLASSIC, BASELINE, ENHANCED }

enum class SpectrumResultConfidenceLevel { HIGH, MEDIUM, REVIEW }

enum class SpectrumResultConfidenceIssue {
    CALIBRATION_REVIEW,
    CALIBRATION_FALLBACK,
    NO_PRIMARY_PEAK,
    LOW_SNR,
    LOW_PROMINENCE
}

data class SpectrumResultConfidenceUiModel(
    val score: Int,
    val level: SpectrumResultConfidenceLevel,
    val issues: List<SpectrumResultConfidenceIssue> = emptyList()
)

/**
 * 自动标定对照信息，用于在结果页展示标定图与光谱图的关系。
 */
data class SpectrumCalibrationComparisonUiModel(
    val calibrationImagePath: String?,
    val qualityScore: Int?,
    val qualityLevel: SpectrumAutoCalibrationQualityLevel?,
    val fitRmse: Double?,
    val effectiveCoverage: Double?,
    val detectedPeakCount: Int,
    val referencePeakCount: Int,
    val issues: List<SpectrumAutoCalibrationIssue> = emptyList(),
    val meanAbsoluteResidual: Double? = null,
    val maxResidual: Double? = null,
    val residualPoints: List<SpectrumCalibrationResidualPoint> = emptyList(),
    val equation: String? = null,
    val usedFallbackAlignment: Boolean
)

/**
 * 单个通道的光谱数据模型。
 */
data class SpectrumChannelUiModel(
    val resultId: Long,
    val channelIndex: Int,
    val analyteId: String?,
    val analyteName: String,
    val rawChartData: ChartData? = null,
    val rawPeaks: List<SpectrumPeakUiModel> = emptyList(),
    val chartData: ChartData,
    val peaks: List<SpectrumPeakUiModel>,
    val baselineChartData: ChartData? = null,
    val enhancedChartData: ChartData? = null,
    val enhancedPeaks: List<SpectrumPeakUiModel> = emptyList(),
    val dataPointCount: Int,
    val minWavelength: Double,
    val maxWavelength: Double,
    val croppedImagePath: String?,
    val calibrationComparison: SpectrumCalibrationComparisonUiModel? = null,
    val resultConfidence: SpectrumResultConfidenceUiModel? = null
) {
    val peakWavelength: Float?
        get() = peaks.firstOrNull()?.wavelength?.toFloat()

    val peakIntensity: Double?
        get() = peaks.firstOrNull()?.intensity

    fun resolveChartData(mode: SpectrumCurveMode): ChartData {
        return when (mode) {
            SpectrumCurveMode.RAW -> rawChartData ?: chartData
            SpectrumCurveMode.CLASSIC -> chartData
            SpectrumCurveMode.BASELINE -> baselineChartData ?: chartData
            SpectrumCurveMode.ENHANCED -> enhancedChartData ?: chartData
        }
    }

    fun resolvePeaks(mode: SpectrumCurveMode): List<SpectrumPeakUiModel> {
        return when (mode) {
            SpectrumCurveMode.RAW -> rawPeaks.ifEmpty { peaks }
            SpectrumCurveMode.CLASSIC -> peaks
            SpectrumCurveMode.BASELINE,
            SpectrumCurveMode.ENHANCED -> enhancedPeaks.ifEmpty { peaks }
        }
    }
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
    val curveMode: SpectrumCurveMode = SpectrumCurveMode.CLASSIC,
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
    val imagePath: String,
    val calibrationComparison: SpectrumCalibrationComparisonUiModel?
)

private data class BaselineCorrectionResult(
    val baseline: List<Double>,
    val corrected: List<Double>
)

private data class PeakCandidate(
    val index: Int,
    val wavelength: Double,
    val intensity: Double,
    val prominence: Double,
    val area: Double,
    val fullWidthHalfMax: Double,
    val signalToNoise: Double
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
                    curveMode = _uiState.value.curveMode,
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

    fun setCurveMode(mode: SpectrumCurveMode) {
        if (_uiState.value.curveMode == mode) return
        _uiState.value = _uiState.value.copy(curveMode = mode)
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
        val currentState = _uiState.value
        val uiResults = currentState.results
        if (uiResults.isEmpty()) return null

        val exportChannels = uiResults.map { uiModel ->
            val channelIndex = uiModel.channelIndex - 1
            val rawData = rawChannelData[channelIndex] ?: Pair(emptyList(), emptyList())
            val croppedPath = channelImagePathMap[channelIndex]
            val selectedPeaks = uiModel.resolvePeaks(currentState.curveMode)

            SpectrumChannelExportModel(
                channelIndex = uiModel.channelIndex,
                analyteName = uiModel.analyteName,
                analyteId = analyteIdMap[channelIndex],
                peakWavelength = selectedPeaks.firstOrNull()?.wavelength?.toFloat(),
                peakIntensity = selectedPeaks.firstOrNull()?.intensity,
                dataPointCount = uiModel.dataPointCount,
                minWavelength = uiModel.minWavelength,
                maxWavelength = uiModel.maxWavelength,
                wavelengths = rawData.first,
                intensities = rawData.second,
                chartData = uiModel.resolveChartData(currentState.curveMode),
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
            curveMode = _uiState.value.curveMode,
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
        val calibrations = spectrumRepository.getCalibrationsByProject(projectId).first()
            .associateBy { it.columnIndex }
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
                imagePath = result.imagePath,
                calibrationComparison = calibrations[result.columnIndex]
                    ?.let(::parseCalibrationComparison)
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
                rawChartData = ChartData(
                    title = context.getString(R.string.spectrum_curve_title),
                    xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
                    yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
                    xRange = Pair(config.minWavelength, config.maxWavelength),
                    yRange = Pair(-0.05, 1.05),
                    curvePoints = emptyList(),
                    scatterPoints = emptyList(),
                    showGrid = true,
                    chartType = "SPECTRUM"
                ),
                rawPeaks = emptyList(),
                chartData = ChartData(
                    title = context.getString(R.string.spectrum_curve_title),
                    xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
                    yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
                    xRange = Pair(config.minWavelength, config.maxWavelength),
                    yRange = Pair(-0.05, 1.05),
                    curvePoints = emptyList(),
                    scatterPoints = emptyList(),
                    showGrid = true,
                    chartType = "SPECTRUM"
                ),
                peaks = emptyList(),
                baselineChartData = null,
                enhancedChartData = null,
                enhancedPeaks = emptyList(),
                dataPointCount = 0,
                minWavelength = config.minWavelength,
                maxWavelength = config.maxWavelength,
                croppedImagePath = channel.imagePath.ifBlank { null },
                calibrationComparison = channel.calibrationComparison,
                resultConfidence = buildResultConfidence(
                    calibrationComparison = channel.calibrationComparison,
                    enhancedPeaks = emptyList()
                )
            )
        }

        val orderedData = sortAndMergeSpectrumSamples(filteredData)
        val wavelengths = orderedData.map { it.first }
        val rawIntensities = orderedData.map { it.second }
        val rawPeaks = findClassicPeaks(wavelengths, rawIntensities, config.sensitivity)
        val rawChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = rawIntensities,
            peaks = rawPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )
        val classicIntensities = if (config.smoothingLevel > 0) {
            applyMovingAverage(rawIntensities, config.smoothingLevel)
        } else {
            rawIntensities
        }
        val classicPeaks = findClassicPeaks(wavelengths, classicIntensities, config.sensitivity)
        val classicChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = classicIntensities,
            peaks = classicPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )

        val baselineCorrection = applyBaselineCorrection(
            data = rawIntensities,
            smoothingLevel = config.smoothingLevel
        )
        val rawNormalized = normalizeSeries(rawIntensities)
        val baselineNormalized = normalizeSeries(baselineCorrection.baseline)
        val correctedNormalized = normalizeSeries(baselineCorrection.corrected)
        val correctedIntensities = if (config.smoothingLevel > 0) {
            applyMovingAverage(baselineCorrection.corrected, config.smoothingLevel)
        } else {
            baselineCorrection.corrected
        }
        val normalizedIntensities = normalizeSeries(correctedIntensities)

        val enhancedPeaks = findEnhancedPeaks(wavelengths, normalizedIntensities, config.sensitivity)
        val baselineChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = correctedNormalized,
            peaks = enhancedPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength,
            overlayLines = listOf(
                ChartLine(
                    label = context.getString(R.string.spectrum_curve_mode_raw),
                    points = wavelengths.zip(rawNormalized),
                    color = Color(0xFF94A3B8),
                    strokeWidth = 2f
                ),
                ChartLine(
                    label = context.getString(R.string.spectrum_curve_mode_baseline_line),
                    points = wavelengths.zip(baselineNormalized),
                    color = Color(0xFFF59E0B),
                    strokeWidth = 2f,
                    dashed = true
                )
            )
        )
        val enhancedChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = normalizedIntensities,
            peaks = enhancedPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )
        val resultConfidence = buildResultConfidence(
            calibrationComparison = channel.calibrationComparison,
            enhancedPeaks = enhancedPeaks
        )

        return SpectrumChannelUiModel(
            resultId = channel.resultId,
            channelIndex = channel.columnIndex + 1,
            analyteId = channel.analyteId,
            analyteName = channel.analyteName,
            rawChartData = rawChartData,
            rawPeaks = rawPeaks,
            chartData = classicChartData,
            peaks = classicPeaks,
            baselineChartData = baselineChartData,
            enhancedChartData = enhancedChartData,
            enhancedPeaks = enhancedPeaks,
            dataPointCount = wavelengths.size,
            minWavelength = wavelengths.minOrNull() ?: config.minWavelength,
            maxWavelength = wavelengths.maxOrNull() ?: config.maxWavelength,
            croppedImagePath = channel.imagePath.ifBlank { null },
            calibrationComparison = channel.calibrationComparison,
            resultConfidence = resultConfidence
        )
    }

    /**
     * 解析标定附加信息，兼容旧版本仅保存手动点位的 JSON 结构。
     */
    private fun parseCalibrationComparison(
        calibration: SpectrumCalibration
    ): SpectrumCalibrationComparisonUiModel? {
        val referenceData = parseReferenceData(calibration.referencePoints)
        val autoDebug = referenceData.autoDebug ?: return null
        val qualityLevel = autoDebug.qualityLevel
            ?: SpectrumCalibrationMath.resolveAutoCalibrationQualityLevel(autoDebug.qualityScore)
        val issues = autoDebug.issues.ifEmpty {
            SpectrumCalibrationMath.collectAutoCalibrationIssues(
                fitRmse = autoDebug.fitRmse,
                effectiveCoverage = autoDebug.effectiveCoverage,
                usedFallbackAlignment = autoDebug.usedFallbackAlignment,
                hasImageQualityWarning = false
            )
        }
        val equation = autoDebug.equation?.takeIf { it.isNotBlank() }
            ?: parseCoefficients(calibration.coefficients)?.let(SpectrumCalibrationMath::formatNormalizedCalibrationEquation)
        return SpectrumCalibrationComparisonUiModel(
            calibrationImagePath = autoDebug.calibrationCropPath?.takeIf { it.isNotBlank() },
            qualityScore = autoDebug.qualityScore,
            qualityLevel = qualityLevel,
            fitRmse = autoDebug.fitRmse,
            effectiveCoverage = autoDebug.effectiveCoverage,
            detectedPeakCount = autoDebug.detectedPeakCount,
            referencePeakCount = autoDebug.referencePeakCount,
            issues = issues,
            meanAbsoluteResidual = autoDebug.meanAbsoluteResidual,
            maxResidual = autoDebug.maxResidual,
            residualPoints = autoDebug.residualPoints,
            equation = equation,
            usedFallbackAlignment = autoDebug.usedFallbackAlignment
        )
    }

    private fun parseCoefficients(coefficients: String?): DoubleArray? {
        if (coefficients.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(coefficients, DoubleArray::class.java)
        }.getOrNull()
    }

    private fun parseReferenceData(referencePoints: String?): SpectrumCalibrationReferenceData {
        if (referencePoints.isNullOrBlank()) {
            return SpectrumCalibrationReferenceData()
        }

        return runCatching {
            gson.fromJson(referencePoints, SpectrumCalibrationReferenceData::class.java)
        }.getOrNull()?.takeIf {
            it.autoDebug != null || it.manualPoints.isNotEmpty()
        } ?: SpectrumCalibrationReferenceData()
    }

    private fun buildResultConfidence(
        calibrationComparison: SpectrumCalibrationComparisonUiModel?,
        enhancedPeaks: List<SpectrumPeakUiModel>
    ): SpectrumResultConfidenceUiModel {
        var score = (calibrationComparison?.qualityScore ?: 78).toDouble()
        val issues = mutableListOf<SpectrumResultConfidenceIssue>()
        val primaryPeak = enhancedPeaks.firstOrNull()

        if (calibrationComparison?.qualityLevel == SpectrumAutoCalibrationQualityLevel.REVIEW) {
            score -= 8.0
            issues += SpectrumResultConfidenceIssue.CALIBRATION_REVIEW
        }
        if (calibrationComparison?.usedFallbackAlignment == true) {
            score -= 6.0
            issues += SpectrumResultConfidenceIssue.CALIBRATION_FALLBACK
        }

        if (primaryPeak == null) {
            score -= 28.0
            issues += SpectrumResultConfidenceIssue.NO_PRIMARY_PEAK
        } else {
            score += when {
                primaryPeak.signalToNoise >= 5.0 -> 8.0
                primaryPeak.signalToNoise >= 3.0 -> 4.0
                else -> {
                    issues += SpectrumResultConfidenceIssue.LOW_SNR
                    -12.0
                }
            }
            score += when {
                primaryPeak.prominence >= 0.25 -> 8.0
                primaryPeak.prominence >= 0.12 -> 4.0
                else -> {
                    issues += SpectrumResultConfidenceIssue.LOW_PROMINENCE
                    -10.0
                }
            }
        }

        val normalizedScore = score.toInt().coerceIn(0, 100)
        val level = when {
            normalizedScore >= 85 -> SpectrumResultConfidenceLevel.HIGH
            normalizedScore >= 70 -> SpectrumResultConfidenceLevel.MEDIUM
            else -> SpectrumResultConfidenceLevel.REVIEW
        }
        return SpectrumResultConfidenceUiModel(
            score = normalizedScore,
            level = level,
            issues = issues.distinct()
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
     * 使用滚动最小值近似基线，再做轻度平滑，减少背景抬升对寻峰的干扰。
     */
    private fun applyBaselineCorrection(
        data: List<Double>,
        smoothingLevel: Int
    ): BaselineCorrectionResult {
        if (data.isEmpty()) {
            return BaselineCorrectionResult(emptyList(), emptyList())
        }

        val baselineRadius = maxOf(6, smoothingLevel * 3, data.size / 28)
        val roughBaseline = data.indices.map { index ->
            val start = maxOf(0, index - baselineRadius)
            val end = minOf(data.lastIndex, index + baselineRadius)
            data.subList(start, end + 1).minOrNull() ?: data[index]
        }
        val baseline = applyMovingAverage(roughBaseline, maxOf(2, baselineRadius / 4))
        val corrected = data.indices.map { index ->
            (data[index] - baseline[index]).coerceAtLeast(0.0)
        }
        return BaselineCorrectionResult(
            baseline = baseline,
            corrected = corrected
        )
    }

    private fun normalizeSeries(data: List<Double>): List<Double> {
        if (data.isEmpty()) return emptyList()
        val minValue = data.minOrNull() ?: 0.0
        val maxValue = data.maxOrNull() ?: minValue
        val range = (maxValue - minValue).coerceAtLeast(1e-9)
        return data.map { value -> ((value - minValue) / range).coerceIn(0.0, 1.0) }
    }

    /**
     * 多峰检测。高灵敏度下保留更多候选峰，低灵敏度则更保守。
     */
    private fun findClassicPeaks(
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

    private fun findEnhancedPeaks(
        wavelengths: List<Double>,
        intensities: List<Double>,
        sensitivity: String
    ): List<SpectrumPeakUiModel> {
        if (wavelengths.size < 3 || intensities.size < 3) return emptyList()

        val noiseStd = estimateNoiseStdDev(intensities)
        val prominenceThreshold = when {
            sensitivity.equals("High", ignoreCase = true) -> maxOf(0.045, noiseStd * 1.6)
            sensitivity.equals("Low", ignoreCase = true) -> maxOf(0.085, noiseStd * 2.7)
            else -> maxOf(0.06, noiseStd * 2.1)
        }
        val minSignalToNoise = when {
            sensitivity.equals("High", ignoreCase = true) -> 1.6
            sensitivity.equals("Low", ignoreCase = true) -> 2.6
            else -> 2.0
        }
        val maxPeakCount = when {
            sensitivity.equals("High", ignoreCase = true) -> 4
            sensitivity.equals("Low", ignoreCase = true) -> 2
            else -> 3
        }
        val minPeakDistance = maxOf(4, intensities.size / 18)

        val candidates = mutableListOf<PeakCandidate>()
        for (index in 1 until intensities.lastIndex) {
            val current = intensities[index]
            val previous = intensities[index - 1]
            val next = intensities[index + 1]
            if (current >= previous && current >= next && current > 0.0) {
                val candidate = buildPeakCandidate(
                    wavelengths = wavelengths,
                    intensities = intensities,
                    peakIndex = index,
                    noiseStd = noiseStd
                )
                if (candidate.prominence >= prominenceThreshold && candidate.signalToNoise >= minSignalToNoise) {
                    candidates += candidate
                }
            }
        }

        if (candidates.isEmpty()) {
            val peakIndex = intensities.indices.maxByOrNull { intensities[it] } ?: return emptyList()
            val fallbackPeak = buildPeakCandidate(
                wavelengths = wavelengths,
                intensities = intensities,
                peakIndex = peakIndex,
                noiseStd = noiseStd
            )
            if (fallbackPeak.prominence < prominenceThreshold) return emptyList()
            return listOf(
                SpectrumPeakUiModel(
                    rank = 1,
                    wavelength = fallbackPeak.wavelength,
                    intensity = fallbackPeak.intensity,
                    prominence = fallbackPeak.prominence,
                    area = fallbackPeak.area,
                    fullWidthHalfMax = fallbackPeak.fullWidthHalfMax,
                    signalToNoise = fallbackPeak.signalToNoise
                )
            )
        }

        val mergedCandidates = mutableListOf<PeakCandidate>()
        candidates.sortedByDescending { it.prominence + it.intensity * 0.35 }.forEach { candidate ->
            val tooClose = mergedCandidates.any { abs(it.index - candidate.index) < minPeakDistance }
            if (!tooClose) {
                mergedCandidates += candidate
            }
        }

        return mergedCandidates
            .sortedByDescending { it.prominence + it.intensity * 0.35 }
            .take(maxPeakCount)
            .mapIndexed { index, candidate ->
                SpectrumPeakUiModel(
                    rank = index + 1,
                    wavelength = candidate.wavelength,
                    intensity = candidate.intensity,
                    prominence = candidate.prominence,
                    area = candidate.area,
                    fullWidthHalfMax = candidate.fullWidthHalfMax,
                    signalToNoise = candidate.signalToNoise
                )
            }
    }

    private fun estimateNoiseStdDev(intensities: List<Double>): Double {
        if (intensities.size < 3) return 0.01
        val deltas = intensities.zipWithNext { left, right -> right - left }
        if (deltas.isEmpty()) return 0.01
        val mean = deltas.average()
        val variance = deltas.sumOf { delta ->
            val diff = delta - mean
            diff * diff
        } / deltas.size.toDouble()
        return kotlin.math.sqrt(variance).coerceAtLeast(0.01)
    }

    private fun buildPeakCandidate(
        wavelengths: List<Double>,
        intensities: List<Double>,
        peakIndex: Int,
        noiseStd: Double
    ): PeakCandidate {
        val leftBaseIndex = findBaseIndex(
            intensities = intensities,
            startIndex = peakIndex,
            direction = -1
        )
        val rightBaseIndex = findBaseIndex(
            intensities = intensities,
            startIndex = peakIndex,
            direction = 1
        )
        val baseLevel = maxOf(intensities[leftBaseIndex], intensities[rightBaseIndex])
        val peakIntensity = intensities[peakIndex]
        val prominence = (peakIntensity - baseLevel).coerceAtLeast(0.0)
        val halfMax = baseLevel + prominence / 2.0
        val leftHalf = interpolateSpectrumHalfMaxCrossing(
            wavelengths = wavelengths,
            intensities = intensities,
            startIndex = peakIndex,
            boundaryIndex = leftBaseIndex,
            target = halfMax,
            direction = -1
        )
        val rightHalf = interpolateSpectrumHalfMaxCrossing(
            wavelengths = wavelengths,
            intensities = intensities,
            startIndex = peakIndex,
            boundaryIndex = rightBaseIndex,
            target = halfMax,
            direction = 1
        )
        val fwhm = (rightHalf - leftHalf).coerceAtLeast(0.0)
        val area = integrateSpectrumPeakArea(
            wavelengths = wavelengths,
            intensities = intensities,
            leftIndex = leftBaseIndex,
            rightIndex = rightBaseIndex,
            baseLevel = baseLevel
        )
        return PeakCandidate(
            index = peakIndex,
            wavelength = wavelengths[peakIndex],
            intensity = peakIntensity,
            prominence = prominence,
            area = area,
            fullWidthHalfMax = fwhm,
            signalToNoise = (prominence / noiseStd).coerceAtLeast(0.0)
        )
    }

    private fun findBaseIndex(
        intensities: List<Double>,
        startIndex: Int,
        direction: Int
    ): Int {
        var index = startIndex
        var candidateIndex = startIndex
        var candidateValue = intensities[startIndex]

        while (true) {
            val nextIndex = index + direction
            if (nextIndex !in intensities.indices) break
            val nextValue = intensities[nextIndex]
            if (nextValue <= candidateValue) {
                candidateValue = nextValue
                candidateIndex = nextIndex
            }
            if (nextValue > intensities[index] && candidateIndex != startIndex) {
                break
            }
            index = nextIndex
        }
        return candidateIndex
    }

    private fun interpolateHalfMaxCrossing(
        wavelengths: List<Double>,
        intensities: List<Double>,
        startIndex: Int,
        boundaryIndex: Int,
        target: Double,
        direction: Int
    ): Double {
        var index = startIndex
        while (index != boundaryIndex) {
            val nextIndex = index + direction
            if (nextIndex !in intensities.indices) break
            val current = intensities[index]
            val next = intensities[nextIndex]
            val crossed = if (direction < 0) next <= target else next <= target
            if (crossed) {
                val denominator = (current - next).takeIf { kotlin.math.abs(it) > 1e-9 } ?: return wavelengths[nextIndex]
                val ratio = ((current - target) / denominator).coerceIn(0.0, 1.0)
                return wavelengths[index] + (wavelengths[nextIndex] - wavelengths[index]) * ratio
            }
            index = nextIndex
        }
        return wavelengths[boundaryIndex]
    }

    private fun integratePeakArea(
        wavelengths: List<Double>,
        intensities: List<Double>,
        leftIndex: Int,
        rightIndex: Int,
        baseLevel: Double
    ): Double {
        if (rightIndex <= leftIndex) return 0.0
        var area = 0.0
        for (index in leftIndex until rightIndex) {
            val leftValue = (intensities[index] - baseLevel).coerceAtLeast(0.0)
            val rightValue = (intensities[index + 1] - baseLevel).coerceAtLeast(0.0)
            val deltaX = wavelengths[index + 1] - wavelengths[index]
            area += (leftValue + rightValue) * deltaX / 2.0
        }
        return area
    }

    /**
     * 生成单通道图表数据。
     */
    private fun generateChartData(
        wavelengths: List<Double>,
        intensities: List<Double>,
        peaks: List<SpectrumPeakUiModel>,
        globalMinWavelength: Double,
        globalMaxWavelength: Double,
        overlayLines: List<ChartLine> = emptyList()
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
        val span = (globalMaxWavelength - globalMinWavelength).coerceAtLeast(1.0)
        val xPadding = maxOf(6.0, span * 0.025)

        return ChartData(
            title = context.getString(R.string.spectrum_curve_title),
            xAxisLabel = context.getString(R.string.spectrum_axis_wavelength),
            yAxisLabel = context.getString(R.string.spectrum_axis_intensity),
            xRange = Pair(
                (globalMinWavelength - xPadding).coerceAtLeast(0.0),
                globalMaxWavelength + xPadding
            ),
            yRange = Pair(-0.05, 1.05),
            curvePoints = curvePoints,
            scatterPoints = peakPoints,
            pointColor = Color(0xFFFF6B6B),
            showGrid = true,
            chartType = "SPECTRUM",
            verticalMarkers = markers,
            overlayLines = overlayLines
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
            Color(0xFF0EA5E9),
            Color(0xFFEF4444),
            Color(0xFF22C55E),
            Color(0xFF8B5CF6),
            Color(0xFFF59E0B),
            Color(0xFF14B8A6),
            Color(0xFFEC4899),
            Color(0xFF84CC16),
            Color(0xFFF97316),
            Color(0xFF6366F1)
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
