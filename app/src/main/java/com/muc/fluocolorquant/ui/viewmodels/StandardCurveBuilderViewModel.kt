package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ConcentrationUnitPreferences
import com.muc.fluocolorquant.data.repository.CalibrationPolicyPreferences
import com.muc.fluocolorquant.domain.calibration.CalibrationRankingMetrics
import com.muc.fluocolorquant.domain.calibration.CalibrationRecommendationEngine
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.utils.math.CalibrationDataParser
import com.muc.fluocolorquant.utils.math.CalibrationTableParseResult
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 页面中允许暂时为空的单个手工标定点。 */
data class StandardCurvePointDraft(
    val concentration: String = "",
    val signal: String = ""
)

/** 宽表 CSV 的用户确认状态。 */
data class StandardCurveImportDraft(
    val table: CalibrationTableParseResult,
    val concentrationColumnIndex: Int,
    val selectedSignalColumnIndices: Set<Int>,
    val featureMappings: Map<Int, AnalysisPrimaryFeature>
) {
    /** 只有同时选择列并声明兼容主特征的列才会进入拟合。 */
    val readySignalColumnIndices: List<Int>
        get() = selectedSignalColumnIndices
            .filter(featureMappings::containsKey)
            .sorted()
}

/** 一列信号完成拟合后的独立候选；多列 CSV 不会提前丢弃未获推荐的结果。 */
data class StandardCurveFitCandidate(
    val id: String,
    val feature: AnalysisPrimaryFeature,
    val sourceLabel: String,
    val points: List<Pair<Double, Double>>,
    val result: FittingResult
)

/** 统一标准曲线创建页状态。 */
data class StandardCurveBuilderUiState(
    val availableModels: List<AnalysisModel> = emptyList(),
    val analytes: List<Analyte> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val modelName: String = "",
    val selectedAnalyteId: String? = null,
    val detectionModality: DetectionModality = DetectionModality.COLORIMETRIC,
    val concentrationUnit: String = "",
    val compatibleCarrierTypes: Set<CarrierType> = setOf(CarrierType.MICROFLUIDIC_CHIP),
    val selectedFeature: AnalysisPrimaryFeature = AnalysisPrimaryFeature.DELTA_E_2000,
    val selectedFunction: FittingFunction? = null,
    val manualPoints: List<StandardCurvePointDraft> = List(4) { StandardCurvePointDraft() },
    val importDraft: StandardCurveImportDraft? = null,
    val candidates: List<StandardCurveFitCandidate> = emptyList(),
    val selectedCandidateId: String? = null,
    val editingModelId: String? = null,
    val isLoading: Boolean = true,
    val isFitting: Boolean = false,
    val isSaving: Boolean = false
) {
    val allowedFeatures: List<AnalysisPrimaryFeature>
        get() = AnalysisFeaturePolicy.allowedFeatures(detectionModality).toList()

    val selectedCandidate: StandardCurveFitCandidate?
        get() = candidates.firstOrNull { it.id == selectedCandidateId }
}

/** 页面把稳定错误码映射到中英文资源，ViewModel 不持有 Android Context。 */
enum class StandardCurveBuilderError {
    MODEL_NAME_REQUIRED,
    MODEL_NAME_DUPLICATE,
    ANALYTE_REQUIRED,
    UNIT_REQUIRED,
    CARRIER_REQUIRED,
    POINTS_INVALID,
    SIGNAL_OUT_OF_RANGE,
    IMPORT_MAPPING_REQUIRED,
    FIT_FAILED,
    FIT_FIRST,
    SAVE_FAILED
}

/** 标准曲线创建页的一次性事件。 */
sealed interface StandardCurveBuilderEvent {
    data class ValidationFailed(val error: StandardCurveBuilderError) : StandardCurveBuilderEvent
    data class Saved(val modelId: String) : StandardCurveBuilderEvent
}

/**
 * 生成可以安全写入标准 JSON 的拟合指标快照。
 *
 * 自动择优内部允许 AICc 等指标在统计自由度不足时取正无穷，用于把该候选排在有限值之后；
 * 但 Gson 按标准 JSON 规则拒绝序列化 NaN 和无穷大。持久化时只移除这些“未定义”数值，
 * 其余诊断指标完整保留，不改变内存中的模型排序和科学裁决结果。
 */
internal fun finiteStandardCurveMetrics(
    metrics: Map<String, Double>
): Map<String, Double> = metrics.filterValues(Double::isFinite)

/**
 * 面向普通用户的统一标准曲线编排。
 *
 * 页面只收集可理解的实验信息；函数参数、单调方向、处理器名称和版本全部由程序计算并
 * 冻结。保存时直接写入统一 AnalysisModel，而不是继续制造无法关联新项目的旧 CurveModel。
 */
@HiltViewModel
class StandardCurveBuilderViewModel @Inject constructor(
    private val analysisModelRepository: AnalysisModelRepository,
    private val analyteRepository: AnalyteRepository,
    private val concentrationUnitPreferences: ConcentrationUnitPreferences,
    private val calibrationPolicyPreferences: CalibrationPolicyPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val requestedModelId = savedStateHandle.get<String>("modelId")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    private val _uiState = MutableStateFlow(StandardCurveBuilderUiState())
    val uiState: StateFlow<StandardCurveBuilderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<StandardCurveBuilderEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<StandardCurveBuilderEvent> = _events.asSharedFlow()

    init {
        observeModels()
        observeAnalytes()
        observeUnits()
        requestedModelId?.let(::loadForEditing)
    }

    private fun observeModels() {
        viewModelScope.launch {
            analysisModelRepository.observeAll().collect { models ->
                _uiState.update { it.copy(availableModels = models) }
            }
        }
    }

    private fun observeAnalytes() {
        viewModelScope.launch {
            analyteRepository.getAllAnalytes().collect { analytes ->
                _uiState.update { state ->
                    val selected = state.selectedAnalyteId
                        ?.takeIf { id -> analytes.any { it.id == id } }
                        ?: analytes.singleOrNull()?.id
                    state.copy(
                        analytes = analytes.sortedBy(Analyte::name),
                        selectedAnalyteId = selected,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeUnits() {
        viewModelScope.launch {
            concentrationUnitPreferences.concentrationUnitsFlow.collect { unitSet ->
                // 编辑历史曲线时，即使其单位已从系统偏好中移除，也必须继续显示原单位，
                // 避免页面加载后静默把标定浓度解释成另一种量纲。
                val currentUnit = _uiState.value.concentrationUnit
                val units = (unitSet + currentUnit)
                    .filter(String::isNotBlank)
                    .sorted()
                val defaultUnit = concentrationUnitPreferences.defaultConcentrationUnitFlow.first()
                _uiState.update { state ->
                    state.copy(
                        concentrationUnits = units,
                        concentrationUnit = state.concentrationUnit
                            .takeIf { it in units }
                            ?: defaultUnit.takeIf { it in units }
                            ?: units.firstOrNull().orEmpty()
                    )
                }
            }
        }
    }

    fun updateModelName(value: String) {
        _uiState.update { it.copy(modelName = value) }
    }

    fun updateAnalyte(id: String) {
        _uiState.update { it.copy(selectedAnalyteId = id) }
    }
    fun updateConcentrationUnit(unit: String) = resetFit { copy(concentrationUnit = unit) }

    fun updateDetectionModality(modality: DetectionModality) = resetFit {
        copy(
            detectionModality = modality,
            selectedFeature = AnalysisFeaturePolicy.defaultFeature(modality),
            importDraft = importDraft?.normalizedFor(modality)
        )
    }

    fun updateFeature(feature: AnalysisPrimaryFeature) = resetFit {
        if (!AnalysisFeaturePolicy.isCompatible(detectionModality, feature)) this
        else copy(selectedFeature = feature)
    }

    fun updateFunction(function: FittingFunction?) = resetFit { copy(selectedFunction = function) }

    fun toggleCarrier(carrierType: CarrierType) {
        _uiState.update { state ->
            val updated = state.compatibleCarrierTypes.toMutableSet().apply {
                if (!add(carrierType)) remove(carrierType)
            }
            state.copy(compatibleCarrierTypes = updated)
        }
    }

    /**
     * 把现有发布曲线还原成普通编辑表单，并使用原函数重新拟合一次以恢复图表和指标。
     *
     * 编辑不创建新版本；项目中的旧曲线早已冻结在项目快照中，因此这里直接更新资源库
     * 只会影响后续新建项目，不会改变历史结果。
     */
    private fun loadForEditing(modelId: String) {
        viewModelScope.launch {
            val bundle = runCatching { analysisModelRepository.getBundle(modelId) }.getOrNull()
                ?: return@launch
            if (AnalysisModelType.fromCode(bundle.model.modelType) !=
                AnalysisModelType.STANDARD_CURVE
            ) {
                return@launch
            }

            val modality = DetectionModality.fromCode(bundle.model.detectionMode)
                ?: return@launch
            val feature = AnalysisPrimaryFeature.fromCode(bundle.model.primaryFeature)
                ?.takeIf { AnalysisFeaturePolicy.isCompatible(modality, it) }
                ?: return@launch
            val function = bundle.standardCurve?.fittingFunction
                ?.let(FittingFunction::fromIdentifier)
            val points = bundle.calibrationPoints
                .filterNot(CalibrationPoint::excluded)
                .sortedWith(compareBy(CalibrationPoint::concentration).thenBy(CalibrationPoint::repeatIndex))
                .map { it.concentration to it.signalValue }
            val restoredResult = function?.let { selectedFunction ->
                runCatching { FittingEngine.fitSingle(points, selectedFunction) }
                    .getOrNull()
                    ?.takeIf(FittingResult::isSuccess)
            }
            val restoredCandidate = restoredResult?.let { result ->
                StandardCurveFitCandidate(
                    id = "edit-${bundle.model.id}",
                    feature = feature,
                    sourceLabel = bundle.model.name,
                    points = points,
                    result = result
                )
            }
            val carriers = decodeCarrierTypes(bundle.model.compatibleCarrierTypesJson)

            _uiState.update { state ->
                state.copy(
                    modelName = bundle.model.name,
                    selectedAnalyteId = bundle.model.analyteId,
                    detectionModality = modality,
                    concentrationUnit = bundle.model.concentrationUnit,
                    compatibleCarrierTypes = carriers,
                    selectedFeature = feature,
                    selectedFunction = function,
                    manualPoints = points.map { (concentration, signal) ->
                        StandardCurvePointDraft(
                            concentration = concentration.toEditableNumber(),
                            signal = signal.toEditableNumber()
                        )
                    }.ifEmpty { List(4) { StandardCurvePointDraft() } },
                    importDraft = null,
                    candidates = listOfNotNull(restoredCandidate),
                    selectedCandidateId = restoredCandidate?.id,
                    editingModelId = bundle.model.id,
                    isLoading = false
                )
            }
        }
    }

    fun addManualPoint() = resetFit {
        copy(manualPoints = manualPoints + StandardCurvePointDraft())
    }

    fun removeManualPoint(index: Int) = resetFit {
        if (manualPoints.size <= 2 || index !in manualPoints.indices) this
        else copy(manualPoints = manualPoints.toMutableList().apply { removeAt(index) })
    }

    fun updateManualPoint(index: Int, concentration: String? = null, signal: String? = null) =
        resetFit {
            if (index !in manualPoints.indices) return@resetFit this
            val current = manualPoints[index]
            copy(
                manualPoints = manualPoints.toMutableList().apply {
                    this[index] = current.copy(
                        concentration = concentration ?: current.concentration,
                        signal = signal ?: current.signal
                    )
                }
            )
        }

    /** 读取文件文本后立即生成映射草稿；已知标题自动选中，未知标题等待用户确认。 */
    fun importCalibrationText(rawText: String) {
        val table = CalibrationDataParser.parseTable(rawText)
        val state = _uiState.value
        val concentrationIndex = table.concentrationColumnIndex ?: 0
        val mappings = table.columns.mapNotNull { column ->
            val feature = column.detectedFeature
                ?.takeIf { AnalysisFeaturePolicy.isCompatible(state.detectionModality, it) }
            feature?.let { column.index to it }
        }.toMap()
        val selectedColumns = mappings.keys.toMutableSet()
        if (selectedColumns.isEmpty() && table.columns.size == 2) {
            val signalIndex = table.columns.first { it.index != concentrationIndex }.index
            selectedColumns += signalIndex
        }
        _uiState.update {
            it.copy(
                importDraft = StandardCurveImportDraft(
                    table = table,
                    concentrationColumnIndex = concentrationIndex,
                    selectedSignalColumnIndices = selectedColumns,
                    featureMappings = mappings
                ),
                candidates = emptyList(),
                selectedCandidateId = null
            )
        }
    }

    fun clearImport() = resetFit { copy(importDraft = null) }

    fun updateImportConcentrationColumn(index: Int) = resetFit {
        val draft = importDraft ?: return@resetFit this
        if (index !in draft.table.columns.indices) this
        else copy(importDraft = draft.copy(concentrationColumnIndex = index))
    }

    fun toggleImportSignalColumn(index: Int) = resetFit {
        val draft = importDraft ?: return@resetFit this
        if (index == draft.concentrationColumnIndex || index !in draft.table.columns.indices) {
            this
        } else {
            val selected = draft.selectedSignalColumnIndices.toMutableSet().apply {
                if (!add(index)) remove(index)
            }
            copy(importDraft = draft.copy(selectedSignalColumnIndices = selected))
        }
    }

    fun updateImportFeature(index: Int, feature: AnalysisPrimaryFeature) = resetFit {
        val draft = importDraft ?: return@resetFit this
        if (!AnalysisFeaturePolicy.isCompatible(detectionModality, feature)) {
            this
        } else {
            copy(
                importDraft = draft.copy(
                    selectedSignalColumnIndices = draft.selectedSignalColumnIndices + index,
                    featureMappings = draft.featureMappings + (index to feature)
                )
            )
        }
    }

    /** 对手工数据或全部已确认 CSV 信号列执行同一套自动拟合。 */
    fun fit() {
        val state = _uiState.value
        val sources = buildFitSources(state) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isFitting = true, candidates = emptyList()) }
            // 一次拟合开始后冻结系统策略；页面后续修改设置不会重排当前结果。
            val policy = calibrationPolicyPreferences.policyFlow.first()
            val generated = sources.flatMap { source ->
                val uniqueLevels = source.points.map { it.first }.distinct().size
                val requestedFunction = state.selectedFunction
                val results = if (requestedFunction != null &&
                    requestedFunction !in FittingEngine.automaticCalibrationFunctions()
                ) {
                    listOfNotNull(
                        runCatching {
                            FittingEngine.fitSingle(source.points, requestedFunction)
                        }.getOrNull()?.takeIf(FittingResult::isSuccess)
                    )
                } else {
                    val requested = requestedFunction?.let(::setOf) ?: policy.allowedFunctions
                    val allowed = requested.filterTo(linkedSetOf()) { function ->
                        when (function) {
                            FittingFunction.RODBARD ->
                                uniqueLevels >= policy.minimumFourParameterLevels
                            FittingFunction.LOGISTIC ->
                                uniqueLevels >= policy.minimumFiveParameterLevels
                            else -> true
                        }
                    }
                    FittingEngine.fitCalibrationCandidates(source.points, allowed)
                        .filter { result ->
                            (result.metrics["Weighting Scheme"]?.toInt() ?: 0) in
                                policy.enabledWeightingCodes
                        }
                }
                results.map { result ->
                    val weighting = result.metrics["Weighting Scheme"]?.toInt() ?: 0
                    StandardCurveFitCandidate(
                        id = "${source.id}:${result.function.identifier}:$weighting",
                        feature = source.feature,
                        sourceLabel = source.label,
                        points = source.points,
                        result = result
                    )
                }
            }

            // 普通页面每个函数只保留最佳“信号+权重”组合，最多展示线性、4PL、5PL三项。
            val bestPerFunction = generated.groupBy { it.result.function }.mapNotNull { (_, group) ->
                CalibrationRecommendationEngine.rankByMetrics(
                    candidates = group,
                    policy = policy,
                    metricsOf = ::rankingMetrics
                ).firstOrNull()
            }
            val candidates = CalibrationRecommendationEngine.rankByMetrics(
                candidates = bestPerFunction,
                policy = policy,
                metricsOf = ::rankingMetrics
            )
            _uiState.update {
                it.copy(
                    candidates = candidates,
                    selectedCandidateId = candidates.firstOrNull()?.id,
                    isFitting = false
                )
            }
            if (candidates.isEmpty()) {
                _events.emit(StandardCurveBuilderEvent.ValidationFailed(StandardCurveBuilderError.FIT_FAILED))
            }
        }
    }

    fun selectCandidate(id: String) {
        _uiState.update { state ->
            state.copy(selectedCandidateId = id.takeIf { candidateId ->
                state.candidates.any { it.id == candidateId }
            } ?: state.selectedCandidateId)
        }
    }

    /** 保存并立即发布经过拟合验证的标准曲线，使直接新建项目能够立刻自动匹配。 */
    fun save() {
        val state = _uiState.value
        val name = state.modelName.trim()
        val candidate = state.selectedCandidate
        val validationError = when {
            name.isEmpty() -> StandardCurveBuilderError.MODEL_NAME_REQUIRED
            state.availableModels.any {
                it.name.equals(name, ignoreCase = true) &&
                    it.id != state.editingModelId &&
                    it.status != AnalysisModelLifecycleStatus.ARCHIVED.code
            } -> StandardCurveBuilderError.MODEL_NAME_DUPLICATE
            state.selectedAnalyteId.isNullOrBlank() -> StandardCurveBuilderError.ANALYTE_REQUIRED
            state.concentrationUnit.isBlank() -> StandardCurveBuilderError.UNIT_REQUIRED
            state.compatibleCarrierTypes.isEmpty() -> StandardCurveBuilderError.CARRIER_REQUIRED
            candidate == null -> StandardCurveBuilderError.FIT_FIRST
            else -> null
        }
        if (validationError != null) {
            _events.tryEmit(StandardCurveBuilderEvent.ValidationFailed(validationError))
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val selected = requireNotNull(candidate)
                val modality = state.detectionModality
                val processor = AnalysisFeaturePolicy.processorIdentity(modality)
                val reliableRange = reliableConcentrationRange(selected)
                val existing = state.editingModelId?.let { modelId ->
                    analysisModelRepository.getBundle(modelId)
                }
                val now = Date()
                val baseModel = existing?.model ?: AnalysisModel(
                    name = name,
                    modelType = AnalysisModelType.STANDARD_CURVE.code,
                    analyteId = requireNotNull(state.selectedAnalyteId),
                    detectionMode = modality.code,
                    inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                    primaryFeature = selected.feature.code,
                    processorName = processor.first,
                    processorVersion = processor.second,
                    concentrationUnit = state.concentrationUnit.trim(),
                    reliableRangeMin = reliableRange.first,
                    reliableRangeMax = reliableRange.second
                )
                val model = baseModel.copy(
                    name = name,
                    modelType = AnalysisModelType.STANDARD_CURVE.code,
                    analyteId = requireNotNull(state.selectedAnalyteId),
                    detectionMode = modality.code,
                    inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                    primaryFeature = selected.feature.code,
                    processorName = processor.first,
                    processorVersion = processor.second,
                    compatibleCarrierTypesJson = gson.toJson(
                        state.compatibleCarrierTypes.map(CarrierType::code).sorted()
                    ),
                    // 空列表表示由手机自动记录真实采集参数，不要求普通用户维护设备档案。
                    compatibleAcquisitionProfileIdsJson = "[]",
                    concentrationUnit = state.concentrationUnit.trim(),
                    reliableRangeMin = reliableRange.first,
                    reliableRangeMax = reliableRange.second,
                    validationMetricsJson = gson.toJson(
                        finiteStandardCurveMetrics(selected.result.metrics)
                    ),
                    status = existing?.model?.status ?: AnalysisModelLifecycleStatus.DRAFT.code,
                    createdAt = existing?.model?.createdAt ?: now,
                    updatedAt = now
                )
                val bundle = AnalysisModelBundle(
                    model = model,
                    standardCurve = StandardCurveDefinition(
                        analysisModelId = model.id,
                        fittingFunction = selected.result.function.identifier,
                        parametersJson = gson.toJson(selected.result.params),
                        monotonicDirection = monotonicDirection(selected.result),
                        lod = existing?.standardCurve?.lod,
                        loq = existing?.standardCurve?.loq
                    ),
                    calibrationPoints = calibrationPoints(selected)
                )
                val savedId = if (existing == null) {
                    val created = analysisModelRepository.createDraft(bundle)
                    analysisModelRepository.publish(created.model.id)
                    created.model.id
                } else {
                    analysisModelRepository.replace(bundle)
                    existing.model.id
                }
                _events.emit(StandardCurveBuilderEvent.Saved(savedId))
            } catch (exception: Exception) {
                // 保存失败必须保留完整堆栈，避免真实设备上的 Room、外键或 JSON 异常只剩
                // 一个无法定位原因的用户提示；日志不包含原始图片或用户隐私数据。
                Log.e(LOG_TAG, "保存标准曲线失败", exception)
                _events.emit(StandardCurveBuilderEvent.ValidationFailed(StandardCurveBuilderError.SAVE_FAILED))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun buildFitSources(state: StandardCurveBuilderUiState): List<FitSource>? {
        val import = state.importDraft
        if (import != null) {
            if (import.readySignalColumnIndices.isEmpty()) {
                _events.tryEmit(
                    StandardCurveBuilderEvent.ValidationFailed(
                        StandardCurveBuilderError.IMPORT_MAPPING_REQUIRED
                    )
                )
                return null
            }
            val sources = import.readySignalColumnIndices.mapNotNull { columnIndex ->
                val feature = import.featureMappings[columnIndex] ?: return@mapNotNull null
                val points = import.table.pointsFor(import.concentrationColumnIndex, columnIndex)
                points.toValidatedSourceOrNull(
                    id = "csv-$columnIndex-${feature.code}",
                    label = import.table.columns[columnIndex].header,
                    feature = feature
                )
            }
            if (sources.isEmpty()) {
                _events.tryEmit(StandardCurveBuilderEvent.ValidationFailed(StandardCurveBuilderError.POINTS_INVALID))
                return null
            }
            return sources
        }

        val points = state.manualPoints.mapNotNull { point ->
            val concentration = point.concentration.toDoubleOrNull()
            val signal = point.signal.toDoubleOrNull()
            if (concentration != null && signal != null) concentration to signal else null
        }
        val partiallyFilled = state.manualPoints.any {
            it.concentration.isBlank() xor it.signal.isBlank()
        }
        if (partiallyFilled) {
            _events.tryEmit(StandardCurveBuilderEvent.ValidationFailed(StandardCurveBuilderError.POINTS_INVALID))
            return null
        }
        val source = points.toValidatedSourceOrNull(
            id = "manual-${state.selectedFeature.code}",
            label = state.selectedFeature.code,
            feature = state.selectedFeature
        )
        if (source == null) {
            _events.tryEmit(StandardCurveBuilderEvent.ValidationFailed(StandardCurveBuilderError.POINTS_INVALID))
            return null
        }
        return listOf(source)
    }

    private fun List<Pair<Double, Double>>.toValidatedSourceOrNull(
        id: String,
        label: String,
        feature: AnalysisPrimaryFeature
    ): FitSource? {
        val sorted = filter { (concentration, signal) ->
            concentration.isFinite() && concentration >= 0.0 && signal.isFinite()
        }.sortedBy { it.first }
        if (sorted.size < 2 || sorted.map { it.first }.distinct().size < 2) {
            return null
        }
        val range = AnalysisFeaturePolicy.signalRange(feature)
        if (sorted.any { !range.contains(it.second) }) {
            _events.tryEmit(
                StandardCurveBuilderEvent.ValidationFailed(
                    StandardCurveBuilderError.SIGNAL_OUT_OF_RANGE
                )
            )
            return null
        }
        return FitSource(id = id, label = label, feature = feature, points = sorted)
    }

    /** 4PL/5PL 的可靠区间不能从零开始；零浓度点仍完整保存在原始标定点中。 */
    private fun reliableConcentrationRange(candidate: StandardCurveFitCandidate): Pair<Double, Double> {
        val concentrations = candidate.points.map { it.first }
        val minimum = if (candidate.result.function in POSITIVE_DOMAIN_FUNCTIONS) {
            concentrations.filter { it > 0.0 }.minOrNull()
        } else {
            concentrations.minOrNull()
        } ?: error("缺少可靠范围下限")
        val maximum = concentrations.maxOrNull() ?: error("缺少可靠范围上限")
        require(maximum > minimum) { "可靠范围必须包含至少两个不同浓度" }
        return minimum to maximum
    }

    /** 同一浓度的重复行按出现顺序编号，禁止保存前先求均值而丢失重复性证据。 */
    private fun calibrationPoints(candidate: StandardCurveFitCandidate): List<CalibrationPoint> {
        val repeatCounters = mutableMapOf<Double, Int>()
        return candidate.points.map { (concentration, signal) ->
            val repeatIndex = repeatCounters.getOrDefault(concentration, 0)
            repeatCounters[concentration] = repeatIndex + 1
            CalibrationPoint(
                id = UUID.randomUUID().toString(),
                analysisModelId = "",
                concentration = concentration,
                signalValue = signal,
                repeatIndex = repeatIndex
            )
        }
    }

    private fun monotonicDirection(result: FittingResult): String {
        val first = result.curvePoints.firstOrNull()?.second
        val last = result.curvePoints.lastOrNull()?.second
        return if (first != null && last != null && last < first) "DECREASING" else "INCREASING"
    }

    /** 只接受载体稳定编码；未知值保留在数据库但不伪装成已支持载体。 */
    private fun decodeCarrierTypes(json: String?): Set<CarrierType> {
        val values = runCatching {
            gson.fromJson<List<String>>(json, stringListType).orEmpty()
        }.getOrDefault(emptyList())
        return values.mapNotNull(CarrierType::fromCode).toSet()
    }

    /** 编辑表单尽量使用短数字，避免把整数显示成带小数点的科研录入噪声。 */
    private fun Double.toEditableNumber(): String = if (this % 1.0 == 0.0) {
        toLong().toString()
    } else {
        toString()
    }

    private inline fun resetFit(
        transform: StandardCurveBuilderUiState.() -> StandardCurveBuilderUiState
    ) {
        _uiState.update { state ->
            state.transform().copy(candidates = emptyList(), selectedCandidateId = null)
        }
    }

    private fun StandardCurveImportDraft.normalizedFor(
        modality: DetectionModality
    ): StandardCurveImportDraft {
        val compatibleMappings = featureMappings.filterValues {
            AnalysisFeaturePolicy.isCompatible(modality, it)
        }
        return copy(
            selectedSignalColumnIndices = selectedSignalColumnIndices.intersect(compatibleMappings.keys),
            featureMappings = compatibleMappings
        )
    }

    private data class FitSource(
        val id: String,
        val label: String,
        val feature: AnalysisPrimaryFeature,
        val points: List<Pair<Double, Double>>
    )

    companion object {
        private const val LOG_TAG = "StandardCurveBuilder"

        private val POSITIVE_DOMAIN_FUNCTIONS = setOf(
            FittingFunction.POWER,
            FittingFunction.LOG,
            FittingFunction.CUSTOM_LOG,
            FittingFunction.RODBARD,
            FittingFunction.RODBARD_NIH,
            FittingFunction.LOGISTIC
        )

        /** 将旧 FittingResult 适配到全应用统一推荐引擎。 */
        private fun rankingMetrics(candidate: StandardCurveFitCandidate): CalibrationRankingMetrics {
            val result = candidate.result
            val signalMinimum = candidate.points.minOfOrNull { it.second }
            val signalMaximum = candidate.points.maxOfOrNull { it.second }
            val signalRange = if (signalMinimum != null && signalMaximum != null) {
                kotlin.math.abs(signalMaximum - signalMinimum)
            } else {
                0.0
            }
            val rmse = result.metrics["RMSE"]
            return CalibrationRankingMetrics(
                function = result.function,
                rSquared = result.rSquared,
                accepted = (result.metrics["ICH M10 Accepted"] ?: 0.0) >= 1.0,
                backCalculatedRmsePercent = result.metrics["Back-calculated RMSE (%)"],
                acceptedStandardRatio = result.metrics["Accepted Standard Ratio"],
                normalizedRmse = rmse?.takeIf { signalRange > 1e-12 }?.div(signalRange),
                mae = result.metrics["MAE"],
                weightingCode = result.metrics["Weighting Scheme"]?.toInt() ?: 0
            )
        }
    }
}
