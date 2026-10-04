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
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.domain.spectrum.ResolvedSpectrumProcessingConfig
import com.muc.fluocolorquant.domain.spectrum.SpectrumProcessingConfig
import com.muc.fluocolorquant.domain.spectrum.SpectrumProcessingConfigOrigin
import com.muc.fluocolorquant.domain.spectrum.SpectrumProcessingConfigSnapshot
import com.muc.fluocolorquant.domain.spectrum.SpectrumSignalPeak
import com.muc.fluocolorquant.domain.spectrum.SpectrumSignalProcessor
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartLine
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.ui.components.charts.ChartVerticalMarker
import com.muc.fluocolorquant.utils.UiText
import com.muc.fluocolorquant.utils.math.SpectrumCalibrationMath
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

/** 领域峰值只在进入 Compose 状态时映射为 UI 模型，算法层不依赖界面包。 */
private fun SpectrumSignalPeak.toUiModel(): SpectrumPeakUiModel = SpectrumPeakUiModel(
    rank = rank,
    wavelength = wavelength,
    intensity = intensity,
    prominence = prominence,
    area = area,
    fullWidthHalfMax = fullWidthHalfMax,
    signalToNoise = signalToNoise
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
    val smoothingLevel: Int = SpectrumProcessingConfig.LEGACY_DEFAULT.smoothingLevel,
    val sensitivity: String = SpectrumProcessingConfig.LEGACY_DEFAULT.sensitivity,
    val processingConfigOrigin: SpectrumProcessingConfigOrigin = SpectrumProcessingConfigOrigin.LEGACY_DEFAULT,
    val processorVersion: String = "legacy-unversioned",
    val curveMode: SpectrumCurveMode = SpectrumCurveMode.CLASSIC,
    val projectImageUri: String? = null,
    val comparisonChartData: ChartData? = null,
    val availableAnalytes: List<Analyte> = emptyList()
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

/**
 * 光谱结果展示页面的 ViewModel。
 */
@HiltViewModel
class SpectrumResultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spectrumRepository: SpectrumRepository,
    private val analyteRepository: AnalyteRepository,
    private val projectRepository: ProjectRepository,
    private val signalProcessor: SpectrumSignalProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpectrumResultUiState())
    val uiState: StateFlow<SpectrumResultUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    // 当前项目与导出缓存
    private var currentProject: Project? = null
    private var cachedLightSourceSnapshot: String? = null
    private val rawChannelData = mutableMapOf<Int, Pair<List<Double>, List<Double>>>()
    private val analyteIdMap = mutableMapOf<Int, String?>()
    private val channelImagePathMap = mutableMapOf<Int, String>()

    // 内存缓存，避免重复读取数据库和 JSON 反序列化
    private var cachedProjectId: String? = null
    private var cachedRawChannels: List<CachedSpectrumChannelRaw> = emptyList()
    private var cachedResolvedConfig: ResolvedSpectrumProcessingConfig? = null
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
                if (refreshSource || cachedProjectId != projectId || cachedRawChannels.isEmpty()) {
                    loadRawProjectData(projectId)
                }

                // 原始数据加载后才能读取随结果保存的配置快照；禁止从当前设置重建历史。
                val resolvedConfig = cachedResolvedConfig
                    ?: SpectrumProcessingConfigSnapshot.resolve(emptyList())
                val config = resolvedConfig.config

                val channelModels = buildOrReuseProcessedResults(config, forceRebuild = refreshSource)
                val comparisonChart = buildComparisonChartData(channelModels, config)
                val analytes = analyteRepository.getAllAnalytes().first()

                _uiState.value = SpectrumResultUiState(
                    isLoading = false,
                    results = channelModels,
                    smoothingLevel = config.smoothingLevel,
                    sensitivity = config.sensitivity,
                    processingConfigOrigin = resolvedConfig.origin,
                    processorVersion = resolvedConfig.processorVersion,
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
     *
     * 这是结果查看会话内的临时分析，不写 DataStore、也不覆盖历史快照。用户重新进入页面时
     * 会恢复到该次实验冻结的参数，从而避免一个项目的调整污染其他项目或全局默认值。
     */
    fun updateSmoothing(level: Int) {
        if (_uiState.value.smoothingLevel == level) return

        viewModelScope.launch(Dispatchers.IO) {
            rebuildResults(
                smoothingLevel = level,
                sensitivity = _uiState.value.sensitivity
            )
        }
    }

    /**
     * 更新灵敏度，并基于缓存即时重算结果；语义同 [updateSmoothing]，仅对当前会话生效。
     */
    fun updateSensitivity(sensitivity: String) {
        if (_uiState.value.sensitivity.equals(sensitivity, ignoreCase = true)) return

        viewModelScope.launch(Dispatchers.IO) {
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
            channels = exportChannels,
            lightSourceSnapshot = cachedLightSourceSnapshot
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
    private fun loadProcessingConfig(
        smoothingLevelOverride: Int? = null,
        sensitivityOverride: String? = null
    ): SpectrumProcessingConfig {
        val frozen = cachedResolvedConfig?.config ?: SpectrumProcessingConfig.LEGACY_DEFAULT
        return frozen.copy(
            smoothingLevel = (smoothingLevelOverride ?: frozen.smoothingLevel)
                .coerceIn(
                    SpectrumProcessingConfig.MIN_SMOOTHING_LEVEL,
                    SpectrumProcessingConfig.MAX_SMOOTHING_LEVEL
                ),
            sensitivity = sensitivityOverride
                ?.let(SpectrumProcessingConfig::normalizeSensitivity)
                ?: frozen.sensitivity
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

        // 同一批通道必须拥有完全一致的采集光源。若旧库为空或数据异常地混用了多个值，
        // 报告明确显示未知，不能挑第一条掩盖快照不一致。
        cachedLightSourceSnapshot = results.map { result ->
            result.lightSourceSnapshot?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().singleOrNull()

        cachedResolvedConfig = SpectrumProcessingConfigSnapshot.resolve(
            results.sortedBy { it.columnIndex }.map { result ->
                result.processingConfigJson to result.processorVersion
            }
        )

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

        val processed = signalProcessor.process(
            samples = filteredData,
            smoothingLevel = config.smoothingLevel,
            sensitivity = config.sensitivity
        )
        val wavelengths = processed.wavelengths
        val rawPeaks = processed.rawPeaks.map { it.toUiModel() }
        val rawChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = processed.rawIntensities,
            peaks = rawPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )
        val classicPeaks = processed.classicPeaks.map { it.toUiModel() }
        val classicChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = processed.classicIntensities,
            peaks = classicPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength
        )
        val enhancedPeaks = processed.enhancedPeaks.map { it.toUiModel() }
        val baselineChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = processed.correctedNormalized,
            peaks = enhancedPeaks,
            globalMinWavelength = config.minWavelength,
            globalMaxWavelength = config.maxWavelength,
            overlayLines = listOf(
                ChartLine(
                    label = context.getString(R.string.spectrum_curve_mode_raw),
                    points = wavelengths.zip(processed.rawNormalized),
                    color = Color(0xFF94A3B8),
                    strokeWidth = 2f
                ),
                ChartLine(
                    label = context.getString(R.string.spectrum_curve_mode_baseline_line),
                    points = wavelengths.zip(processed.baselineNormalized),
                    color = Color(0xFFF59E0B),
                    strokeWidth = 2f,
                    dashed = true
                )
            )
        )
        val enhancedChartData = generateChartData(
            wavelengths = wavelengths,
            intensities = processed.enhancedIntensities,
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
