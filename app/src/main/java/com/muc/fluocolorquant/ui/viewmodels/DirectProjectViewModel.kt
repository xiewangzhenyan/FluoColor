package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
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
    val form: DirectProjectFormState = DirectProjectFormState(),
    val isLoading: Boolean = true
)

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
    private val coordinator: DirectProjectCreationCoordinator
) : ViewModel() {
    private val _uiState = MutableStateFlow(DirectProjectUiState())
    val uiState: StateFlow<DirectProjectUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DirectProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DirectProjectEvent> = _events.asSharedFlow()

    init {
        observeAnalytes()
        observeConcentrationUnits()
    }

    private fun observeAnalytes() {
        viewModelScope.launch {
            analyteRepository.getAllAnalytes().collect { analytes ->
                _uiState.update { state ->
                    val retainedSelection = state.form.selectedAnalyteId
                        ?.takeIf { selectedId -> analytes.any { it.id == selectedId } }
                    state.copy(
                        analytes = analytes.sortedBy(Analyte::name),
                        form = state.form.copy(
                            selectedAnalyteId = retainedSelection ?: analytes.singleOrNull()?.id
                        ),
                        isLoading = false
                    )
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
                    state.copy(
                        concentrationUnits = sortedUnits,
                        form = state.form.copy(
                            concentrationUnit = state.form.concentrationUnit
                                .takeIf { it in sortedUnits }
                                ?: sortedUnits.firstOrNull()
                                ?: "ng/mL"
                        )
                    )
                }
            }
        }
    }

    fun updateProjectName(value: String) = updateForm { copy(projectName = value) }
    fun updateDetectionModality(value: DetectionModality) = updateForm {
        copy(detectionModality = value)
    }
    fun updateCarrierPreset(value: DirectCarrierPreset) = updateForm { copy(carrierPreset = value) }
    fun updateCustomRows(value: String) = updateForm { copy(customRowsInput = value.filter(Char::isDigit)) }
    fun updateCustomColumns(value: String) = updateForm {
        copy(customColumnsInput = value.filter(Char::isDigit))
    }
    fun updateAnalyte(value: String) = updateForm { copy(selectedAnalyteId = value) }
    fun updateConcentrationUnit(value: String) = updateForm { copy(concentrationUnit = value) }
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
}
