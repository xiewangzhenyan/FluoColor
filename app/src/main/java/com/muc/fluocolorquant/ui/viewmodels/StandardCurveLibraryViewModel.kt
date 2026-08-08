package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
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

/** 标准曲线库中一张卡片所需的完整展示数据。 */
data class StandardCurveLibraryItem(
    val model: AnalysisModel,
    val analyteName: String,
    val modality: DetectionModality,
    val feature: AnalysisPrimaryFeature,
    val carrierTypes: Set<CarrierType>
)

/** 普通曲线库页面状态；只显示已经发布、可供新项目匹配的统一标准曲线。 */
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

/**
 * 统一标准曲线库业务层。
 *
 * 普通用户不再看到草稿、发布、归档和版本号；创建页面保存后会自动发布，资源库只负责
 * 查看、直接编辑和确认删除。旧 CurveModel 继续由单独的历史兼容入口读取。
 */
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
                models.asSequence()
                    .filter { it.modelType == AnalysisModelType.STANDARD_CURVE.code }
                    .filter { it.status == AnalysisModelLifecycleStatus.PUBLISHED.code }
                    .mapNotNull { model ->
                        val modality = DetectionModality.fromCode(model.detectionMode)
                            ?: return@mapNotNull null
                        val feature = AnalysisPrimaryFeature.fromCode(model.primaryFeature)
                            ?: return@mapNotNull null
                        StandardCurveLibraryItem(
                            model = model,
                            analyteName = analyteNames[model.analyteId].orEmpty(),
                            modality = modality,
                            feature = feature,
                            carrierTypes = decodeCarriers(model.compatibleCarrierTypesJson)
                        )
                    }
                    .sortedWith(
                        compareBy<StandardCurveLibraryItem> { it.analyteName }
                            .thenBy { it.model.name }
                    )
                    .toList()
            }.collect { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    /** 删除前由页面弹窗确认；删除过程中只锁定对应卡片，其他内容仍可浏览。 */
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
}
