package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.domain.project.DirectProjectAnalyteRequest
import com.muc.fluocolorquant.domain.project.DirectProjectCreateRequest
import com.muc.fluocolorquant.domain.project.DirectProjectCreationCoordinator
import com.muc.fluocolorquant.domain.project.DirectProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.ui.screens.project.DirectAnalyteSelection
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

/** 直接新建页面所需的数据。定量模型和孔位角色将在后续布局页面选择。 */
data class DirectProjectUiState(
    val analytes: List<Analyte> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val form: DirectProjectFormState = DirectProjectFormState(),
    val isLoading: Boolean = true
)

/** 导航和自定义 Toast 使用的一次性事件。 */
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
 * 页面允许同时选择多个分析物，并为每个分析物单独保存浓度单位。创建页不再匹配标准曲线、
 * 深度学习模型，也不预填样本位；这些信息必须结合真实定位结果在后续孔位布局页完成。
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
                val sortedAnalytes = analytes.sortedBy(Analyte::name)
                _uiState.update { state ->
                    val validIds = sortedAnalytes.map(Analyte::id).toSet()
                    val retained = state.form.selectedAnalytes.filter {
                        it.analyteId in validIds
                    }
                    // 只有数据库中恰好一个分析物时才自动选择，避免多分析物场景被静默缩成单选。
                    val nextSelections = if (retained.isEmpty() && sortedAnalytes.size == 1) {
                        listOf(
                            DirectAnalyteSelection(
                                analyteId = sortedAnalytes.single().id,
                                concentrationUnit = state.defaultConcentrationUnit()
                            )
                        )
                    } else {
                        retained
                    }
                    state.copy(
                        analytes = sortedAnalytes,
                        form = state.form.copy(selectedAnalytes = nextSelections),
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
                    .ifEmpty { DEFAULT_CONCENTRATION_UNITS }
                _uiState.update { state ->
                    val defaultUnit = sortedUnits.first()
                    state.copy(
                        concentrationUnits = sortedUnits,
                        form = state.form.copy(
                            selectedAnalytes = state.form.selectedAnalytes.map { selection ->
                                selection.takeIf {
                                    it.concentrationUnit in sortedUnits
                                } ?: selection.copy(concentrationUnit = defaultUnit)
                            }
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

    fun updateCarrierPreset(value: DirectCarrierPreset) = updateForm {
        copy(carrierPreset = value)
    }

    fun updateCustomRows(value: String) = updateForm {
        copy(customRowsInput = value.filter(Char::isDigit))
    }

    fun updateCustomColumns(value: String) = updateForm {
        copy(customColumnsInput = value.filter(Char::isDigit))
    }

    /**
     * 接收多选面板确认后的分析物 ID 列表。
     * 已选分析物保留原单位，新加入分析物使用系统浓度单位列表的首项作为默认值。
     */
    fun updateSelectedAnalytes(selectedIds: List<String>) {
        _uiState.update { state ->
            val availableIds = state.analytes.map(Analyte::id).toSet()
            val oldSelections = state.form.selectedAnalytes.associateBy(
                DirectAnalyteSelection::analyteId
            )
            val normalizedIds = selectedIds.filter { it in availableIds }.distinct()
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = normalizedIds.map { analyteId ->
                        oldSelections[analyteId] ?: DirectAnalyteSelection(
                            analyteId = analyteId,
                            concentrationUnit = state.defaultConcentrationUnit()
                        )
                    }
                )
            )
        }
    }

    /** 单独更新某个分析物的单位，禁止用全局单位覆盖其他分析物。 */
    fun updateAnalyteConcentrationUnit(analyteId: String, unit: String) {
        _uiState.update { state ->
            if (unit !in state.concentrationUnits) return@update state
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = state.form.selectedAnalytes.map { selection ->
                        if (selection.analyteId == analyteId) {
                            selection.copy(concentrationUnit = unit)
                        } else {
                            selection
                        }
                    }
                )
            )
        }
    }

    /**
     * 单独更新某个分析物的最大浓度。
     * 只接收普通十进制正数，防止字母、多个小数点和无限值进入项目冻结数据。
     */
    fun updateAnalyteMaxConcentration(analyteId: String, value: String) {
        val normalized = value.replace(',', '.')
        if (!normalized.matches(Regex("\\d{0,12}(\\.\\d{0,6})?"))) return
        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = state.form.selectedAnalytes.map { selection ->
                        if (selection.analyteId == analyteId) {
                            selection.copy(maxConcentrationInput = normalized)
                        } else {
                            selection
                        }
                    }
                )
            )
        }
    }

    fun removeAnalyte(analyteId: String) {
        updateForm {
            copy(selectedAnalytes = selectedAnalytes.filterNot { it.analyteId == analyteId })
        }
    }

    fun updateImageUri(value: String?) = updateForm { copy(imageUri = value) }

    fun createProject(userId: String) {
        val state = _uiState.value
        val form = state.form
        if (!form.canSubmit) {
            _events.tryEmit(DirectProjectEvent.FormIncomplete)
            return
        }

        val analytesById = state.analytes.associateBy(Analyte::id)
        val requestedAnalytes = form.selectedAnalytes.mapNotNull { selection ->
            analytesById[selection.analyteId]?.let { analyte ->
                DirectProjectAnalyteRequest(
                    analyte = analyte,
                    concentrationUnit = selection.concentrationUnit,
                    maxConcentration = selection.maxConcentration
                        ?: return@mapNotNull null
                )
            }
        }
        if (requestedAnalytes.size != form.selectedAnalytes.size) {
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
                        analytes = requestedAnalytes,
                        imageUri = form.imageUri.orEmpty(),
                        userId = userId
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

    private fun DirectProjectUiState.defaultConcentrationUnit(): String =
        concentrationUnits.firstOrNull() ?: DEFAULT_CONCENTRATION_UNITS.first()

    private companion object {
        val DEFAULT_CONCENTRATION_UNITS = listOf("ng/mL", "pg/mL", "μg/mL", "mg/mL")
    }
}
