package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.domain.detection.AnalysisModelCompatibilityChecker
import com.muc.fluocolorquant.domain.detection.ModelCompatibilityRequest
import com.muc.fluocolorquant.domain.detection.ModelCompatibilityResult
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.domain.project.DirectProjectCreateRequest
import com.muc.fluocolorquant.domain.project.DirectProjectCreationCoordinator
import com.muc.fluocolorquant.domain.project.DirectProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.ui.screens.project.DirectProjectFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 直接新建页面所需的最小状态。 */
data class DirectProjectUiState(
    val analytes: List<Analyte> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val analysisModels: List<AnalysisModel> = emptyList(),
    val form: DirectProjectFormState = DirectProjectFormState(),
    val isLoading: Boolean = true
) {
    /**
     * 按完整科学契约筛选当前表单可用的发布曲线。
     *
     * 每个模型使用自己的主特征参与检查，因此同一分析物可以同时拥有 ΔE、光密度或不同
     * 荧光信号曲线；真正选择哪条曲线由用户完成，检测链不会在后台擅自替换。
     */
    val compatibleModels: List<AnalysisModel>
        get() {
            val analyteId = form.selectedAnalyteId ?: return emptyList()
            if (form.detectionModality == DetectionModality.SPECTRUM) return emptyList()
            val processor = AnalysisFeaturePolicy.processorIdentity(form.detectionModality)
            return analysisModels.filter { model ->
                val feature = AnalysisPrimaryFeature.fromCode(model.primaryFeature)
                    ?: return@filter false
                model.modelType == AnalysisModelType.STANDARD_CURVE.code &&
                    model.concentrationUnit == form.concentrationUnit.trim() &&
                    AnalysisFeaturePolicy.isCompatible(form.detectionModality, feature) &&
                    AnalysisModelCompatibilityChecker.check(
                        model = model,
                        request = ModelCompatibilityRequest(
                            analyteId = analyteId,
                            modality = form.detectionModality,
                            inputProtocol = InputProtocol.ENDPOINT_ONLY,
                            primaryFeature = feature,
                            carrierType = form.carrierType,
                            // 直接新建使用一次性手机采集档案；模型设备范围为空数组时按通配处理。
                            acquisitionProfileId = DIRECT_AUTO_ACQUISITION_ID,
                            processorName = processor.first,
                            processorVersion = processor.second
                        )
                    ) == ModelCompatibilityResult.Compatible
            }.sortedBy(AnalysisModel::name)
        }

    companion object {
        private const val DIRECT_AUTO_ACQUISITION_ID = "direct-auto-capture"
    }
}

/** 导航和 Toast 使用的一次性事件。 */
sealed interface DirectProjectEvent {
    data class Created(
        val projectId: String,
        val imageUri: String,
        val destination: ProjectDetectionDestination
    ) : DirectProjectEvent

    data object FormIncomplete : DirectProjectEvent
    data object UnexpectedFailure : DirectProjectEvent
}

/**
 * 直接新建项目状态机。
 *
 * ViewModel 不读取或创建实验模板、载体档案、采集设备档案和已发布模型。它只收集用户输入，
 * 再交给 [DirectProjectCreationCoordinator] 生成项目专属冻结快照，从而避免页面重新承担
 * 数据库关系拼装和仅信号模型的内部实现细节。
 */
@HiltViewModel
class DirectProjectViewModel @Inject constructor(
    private val analyteRepository: AnalyteRepository,
    private val settingsRepository: SettingsRepository,
    private val analysisModelRepository: AnalysisModelRepository,
    private val coordinator: DirectProjectCreationCoordinator
) : ViewModel() {
    private val _uiState = MutableStateFlow(DirectProjectUiState())
    val uiState: StateFlow<DirectProjectUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DirectProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DirectProjectEvent> = _events.asSharedFlow()

    init {
        observeAnalytes()
        observeConcentrationUnits()
        observeAnalysisModels()
    }

    private fun observeAnalytes() {
        viewModelScope.launch {
            analyteRepository.getAllAnalytes().collect { analytes ->
                _uiState.update { state ->
                    val retainedSelection = state.form.selectedAnalyteId
                        ?.takeIf { selectedId -> analytes.any { it.id == selectedId } }
                    val nextAnalyteId = retainedSelection ?: analytes.singleOrNull()?.id
                    val analyteChanged = nextAnalyteId != state.form.selectedAnalyteId
                    state.copy(
                        analytes = analytes.sortedBy(Analyte::name),
                        form = state.form.copy(
                            selectedAnalyteId = nextAnalyteId,
                            selectedAnalysisModelId = if (analyteChanged) {
                                null
                            } else {
                                state.form.selectedAnalysisModelId
                            },
                            analysisModelSelectionExplicit = if (analyteChanged) {
                                false
                            } else {
                                state.form.analysisModelSelectionExplicit
                            }
                        ),
                        isLoading = false
                    ).reconcileAnalysisModelSelection()
                }
            }
        }
    }

    private fun observeConcentrationUnits() {
        viewModelScope.launch {
            settingsRepository.concentrationUnitsFlow.collect { units ->
                val sortedUnits = units
                    .filter(String::isNotBlank)
                    .sorted()
                    .ifEmpty { listOf("ng/mL", "pg/mL", "μg/mL", "mg/mL") }
                _uiState.update { state ->
                    val nextUnit = state.form.concentrationUnit
                        .takeIf { it in sortedUnits }
                        ?: sortedUnits.firstOrNull()
                        ?: "ng/mL"
                    val unitChanged = nextUnit != state.form.concentrationUnit
                    state.copy(
                        concentrationUnits = sortedUnits,
                        form = state.form.copy(
                            concentrationUnit = nextUnit,
                            selectedAnalysisModelId = if (unitChanged) {
                                null
                            } else {
                                state.form.selectedAnalysisModelId
                            },
                            analysisModelSelectionExplicit = if (unitChanged) {
                                false
                            } else {
                                state.form.analysisModelSelectionExplicit
                            }
                        )
                    ).reconcileAnalysisModelSelection()
                }
            }
        }
    }

    private fun observeAnalysisModels() {
        viewModelScope.launch {
            analysisModelRepository.observeAll().collect { models ->
                _uiState.update { state ->
                    state.copy(analysisModels = models).reconcileAnalysisModelSelection()
                }
            }
        }
    }

    fun updateProjectName(value: String) = updateForm { copy(projectName = value) }
    fun updateDetectionModality(value: DetectionModality) = updateModelMatchingForm {
        copy(detectionModality = value)
    }
    fun updateCarrierPreset(value: DirectCarrierPreset) = updateModelMatchingForm {
        copy(carrierPreset = value)
    }
    fun updateCustomRows(value: String) = updateModelMatchingForm {
        copy(customRowsInput = value.filter(Char::isDigit))
    }
    fun updateCustomColumns(value: String) = updateModelMatchingForm {
        copy(customColumnsInput = value.filter(Char::isDigit))
    }
    fun updateAnalyte(value: String) = updateModelMatchingForm { copy(selectedAnalyteId = value) }
    fun updateConcentrationUnit(value: String) = updateModelMatchingForm {
        copy(concentrationUnit = value)
    }
    fun updateAnalysisModel(value: String?) = updateForm {
        copy(
            selectedAnalysisModelId = value,
            analysisModelSelectionExplicit = true
        )
    }
    fun updateSampleId(value: String) = updateForm { copy(sampleId = value) }
    fun updateImageUri(value: String?) = updateForm { copy(imageUri = value) }
    fun updateColorReferenceRow(value: String) = updateForm {
        copy(colorReferenceRowInput = value.filter(Char::isDigit))
    }
    fun updateColorReferenceColumn(value: String) = updateForm {
        copy(colorReferenceColumnInput = value.filter(Char::isDigit))
    }

    fun createProject(userId: String) {
        val state = _uiState.value
        val form = state.form
        if (!form.canSubmit) {
            _events.tryEmit(DirectProjectEvent.FormIncomplete)
            return
        }
        val analyte = state.analytes.firstOrNull { it.id == form.selectedAnalyteId }
        if (analyte == null) {
            _events.tryEmit(DirectProjectEvent.FormIncomplete)
            return
        }
        _uiState.update { it.copy(form = it.form.copy(isSubmitting = true)) }
        viewModelScope.launch {
            try {
                val outcome = coordinator.create(
                    DirectProjectCreateRequest(
                        name = form.projectName,
                        detectionModality = form.detectionModality,
                        carrierPreset = form.carrierPreset,
                        customRows = form.rows,
                        customColumns = form.columns,
                        analyte = analyte,
                        imageUri = form.imageUri.orEmpty(),
                        userId = userId,
                        concentrationUnit = form.concentrationUnit,
                        analysisModelId = form.selectedAnalysisModelId,
                        sampleId = form.sampleId,
                        colorReferenceRow = if (form.requiresColorReference) {
                            form.colorReferenceRowInput.toIntOrNull()?.minus(1)
                        } else {
                            null
                        },
                        colorReferenceColumn = if (form.requiresColorReference) {
                            form.colorReferenceColumnInput.toIntOrNull()?.minus(1)
                        } else {
                            null
                        }
                    )
                )
                _uiState.update { it.copy(form = it.form.copy(isSubmitting = false)) }
                when (outcome) {
                    is DirectProjectCreationOutcome.Created -> _events.emit(
                        DirectProjectEvent.Created(
                            projectId = outcome.project.id,
                            imageUri = outcome.project.imageUri,
                            destination = outcome.destination
                        )
                    )
                    DirectProjectCreationOutcome.InvalidRequest ->
                        _events.emit(DirectProjectEvent.FormIncomplete)
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(form = it.form.copy(isSubmitting = false)) }
                _events.emit(DirectProjectEvent.UnexpectedFailure)
            }
        }
    }

    private inline fun updateForm(
        transform: DirectProjectFormState.() -> DirectProjectFormState
    ) {
        _uiState.update { state -> state.copy(form = state.form.transform()) }
    }

    /** 影响兼容筛选的字段变化时清除旧选择，再按“唯一候选自动选中”规则重新裁决。 */
    private inline fun updateModelMatchingForm(
        transform: DirectProjectFormState.() -> DirectProjectFormState
    ) {
        _uiState.update { state ->
            state.copy(
                form = state.form.transform().copy(
                    selectedAnalysisModelId = null,
                    analysisModelSelectionExplicit = false
                )
            ).reconcileAnalysisModelSelection()
        }
    }

    /**
     * 唯一兼容曲线自动选中；多个候选等待用户选择；没有候选则保持仅信号。
     * 用户明确选择“仅信号”后不会被后续数据库 Flow 刷新强行改回曲线。
     */
    private fun DirectProjectUiState.reconcileAnalysisModelSelection(): DirectProjectUiState {
        val candidates = compatibleModels
        val currentId = form.selectedAnalysisModelId
        val currentStillValid = currentId != null && candidates.any { it.id == currentId }
        if (form.analysisModelSelectionExplicit && (currentId == null || currentStillValid)) {
            return this
        }
        return copy(
            form = form.copy(
                selectedAnalysisModelId = candidates.singleOrNull()?.id,
                analysisModelSelectionExplicit = false
            )
        )
    }
}
