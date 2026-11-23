package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 标准曲线库中一张卡片所需的完整展示数据（含拟合函数、R²、荧光通道与标定点摘要）。 */
data class StandardCurveLibraryItem(
    val model: AnalysisModel,
    val analyteName: String,
    val modality: DetectionModality,
    val feature: AnalysisPrimaryFeature,
    val carrierTypes: Set<CarrierType>,
    val fittingFunction: FittingFunction? = null,
    val parameters: Map<String, Double> = emptyMap(),
    val rSquared: Double? = null,
    val fluorescenceChannel: FluorescenceChannel? = null,
    val standardPointCount: Int = 0
)

/** 标准曲线库页面状态；只显示已发布、可供新项目与模板匹配的统一标准曲线。 */
data class StandardCurveLibraryUiState(
    val items: List<StandardCurveLibraryItem> = emptyList(),
    val deletingModelId: String? = null,
    val isLoading: Boolean = true
)

/** 删除结果使用稳定事件码，实际文案由 Compose 页面从资源中读取。 */
sealed interface StandardCurveLibraryEvent {
    data object Deleted : StandardCurveLibraryEvent
    data object DeleteFailed : StandardCurveLibraryEvent
}

@HiltViewModel
class StandardCurveLibraryViewModel @Inject constructor(
    private val analysisModelRepository: AnalysisModelRepository,
    analyteRepository: AnalyteRepository
) : ViewModel() {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val _uiState = MutableStateFlow(StandardCurveLibraryUiState())
    val uiState: StateFlow<StandardCurveLibraryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<StandardCurveLibraryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<StandardCurveLibraryEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                analysisModelRepository.observeAll(),
                analyteRepository.getAllAnalytes()
            ) { models, analytes ->
                val analyteNames = analytes.associate { it.id to it.name }
                models
                    .filter { it.modelType == AnalysisModelType.STANDARD_CURVE.code }
                    .filter { it.status == AnalysisModelLifecycleStatus.PUBLISHED.code }
                    .mapNotNull { model ->
                        val modality = DetectionModality.fromCode(model.detectionMode)
                            ?: return@mapNotNull null
                        val feature = AnalysisPrimaryFeature.fromCode(model.primaryFeature)
                            ?: return@mapNotNull null
                        val bundle = analysisModelRepository.getBundle(model.id)
                        val fn = bundle?.standardCurve?.fittingFunction
                            ?.let(FittingFunction::fromIdentifier)
                        val params = parseDoubleMap(bundle?.standardCurve?.parametersJson)
                        val metrics = parseDoubleMap(model.validationMetricsJson)
                        val r2 = (metrics["rSquared"] ?: metrics["r2"] ?: metrics["R2"])
                            ?.takeIf { it.isFinite() }
                        val channel = if (modality == DetectionModality.FLUORESCENCE) {
                            parseChannelFromProcessorVersion(model.processorVersion)
                        } else null
                        StandardCurveLibraryItem(
                            model = model,
                            analyteName = analyteNames[model.analyteId].orEmpty(),
                            modality = modality,
                            feature = feature,
                            carrierTypes = decodeCarriers(model.compatibleCarrierTypesJson),
                            fittingFunction = fn,
                            parameters = params,
                            rSquared = r2,
                            fluorescenceChannel = channel,
                            standardPointCount = bundle?.calibrationPoints?.size ?: 0
                        )
                    }
                    .sortedWith(
                        compareBy<StandardCurveLibraryItem> { it.analyteName }
                            .thenBy { it.model.name }
                    )
            }.collect { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    fun delete(modelId: String) {
        if (_uiState.value.deletingModelId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(deletingModelId = modelId) }
            try {
                analysisModelRepository.delete(modelId)
                _events.emit(StandardCurveLibraryEvent.Deleted)
            } catch (_: Exception) {
                _events.emit(StandardCurveLibraryEvent.DeleteFailed)
            } finally {
                _uiState.update { it.copy(deletingModelId = null) }
            }
        }
    }

    private fun decodeCarriers(json: String?): Set<CarrierType> {
        val codes = runCatching {
            gson.fromJson<List<String>>(json, stringListType).orEmpty()
        }.getOrDefault(emptyList())
        return codes.mapNotNull(CarrierType::fromCode).toSet()
    }

    private fun parseDoubleMap(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            buildMap {
                obj.entrySet().forEach { (k, v) ->
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
                        val d = v.asDouble
                        if (d.isFinite()) put(k, d)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun parseChannelFromProcessorVersion(version: String): FluorescenceChannel {
        val marker = "-ch-"
        val idx = version.lastIndexOf(marker)
        if (idx >= 0) {
            val code = version.substring(idx + marker.length).uppercase()
            FluorescenceChannel.entries.firstOrNull { it.name == code }?.let { return it }
        }
        return FluorescenceChannel.GREEN
    }
}
