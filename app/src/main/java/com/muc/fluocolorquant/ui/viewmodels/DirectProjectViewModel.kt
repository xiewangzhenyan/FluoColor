package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ProjectCreationPreferences
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 直接新建页面所需的数据。定量模型和孔位角色将在后续布局页面选择。 */
data class DirectProjectUiState(
    val analytes: List<Analyte> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val defaultConcentrationUnit: String = DEFAULT_DIRECT_CONCENTRATION_UNIT,
    val form: DirectProjectFormState = DirectProjectFormState(),
    val isLoading: Boolean = true
)

private const val DEFAULT_DIRECT_CONCENTRATION_UNIT = "ng/mL"

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
    private val projectCreationPreferences: ProjectCreationPreferences,
    private val coordinator: DirectProjectCreationCoordinator
) : ViewModel() {
    private val _uiState = MutableStateFlow(DirectProjectUiState())
    val uiState: StateFlow<DirectProjectUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DirectProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DirectProjectEvent> = _events.asSharedFlow()

    // DataStore 首次读取通常很快，但仍是异步过程。若用户在返回前已经操作表单，下面三个
    // 标记可阻止迟到的默认值覆盖真实输入。
    private var detectionModeEdited = false
    private var customGridEdited = false
    private var spectrumLightSourceEdited = false

    init {
        loadProjectCreationDefaults()
        observeProjectCreationOptions()
    }

    /**
     * 原子合并分析物、单位列表和默认单位，避免三个独立 Flow 的启动顺序让自动选择的
     * 单一分析物短暂拿到列表首项，随后又因为该单位“仍合法”而永远错过用户默认单位。
     */
    private fun observeProjectCreationOptions() {
        viewModelScope.launch {
            combine(
                analyteRepository.getAllAnalytes(),
                projectCreationPreferences.concentrationUnitsFlow,
                projectCreationPreferences.defaultConcentrationUnitFlow
            ) { analytes, units, preferredUnit -> Triple(analytes, units, preferredUnit) }
                .collect { (analytes, units, preferredUnit) ->
                val sortedAnalytes = analytes.sortedBy(Analyte::name)
                val sortedUnits = units
                    .filter(String::isNotBlank)
                    .sorted()
                    .ifEmpty { DEFAULT_CONCENTRATION_UNITS }
                val defaultUnit = preferredUnit.takeIf { it in sortedUnits }
                    ?: sortedUnits.first()
                _uiState.update { state ->
                    val validIds = sortedAnalytes.map(Analyte::id).toSet()
                    val retained = state.form.selectedAnalytes.filter {
                        it.analyteId in validIds
                    }.map { selection ->
                        selection.takeIf { it.concentrationUnit in sortedUnits }
                            ?: selection.copy(concentrationUnit = defaultUnit)
                    }
                    // 只有数据库中恰好一个分析物时才自动选择，避免多分析物场景被静默缩成单选。
                    val nextSelections = if (retained.isEmpty() && sortedAnalytes.size == 1) {
                        listOf(
                            DirectAnalyteSelection(
                                analyteId = sortedAnalytes.single().id,
                                concentrationUnit = defaultUnit
                            )
                        )
                    } else {
                        retained
                    }
                    state.copy(
                        analytes = sortedAnalytes,
                        concentrationUnits = sortedUnits,
                        defaultConcentrationUnit = defaultUnit,
                        form = state.form.copy(selectedAnalytes = nextSelections),
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * 所有新建默认值只在页面首次初始化时读取一次。
     *
     * 持续收集设置 Flow 会在用户已经修改行列或光源后再次覆盖表单；使用 `first()` 明确
     * 划分“设置预填”和“本项目输入”，项目创建后再由持久化快照承担历史边界。
     */
    private fun loadProjectCreationDefaults() {
        viewModelScope.launch {
            val defaults = projectCreationPreferences.projectCreationDefaultsFlow.first()
            val defaultMode = DetectionModality.fromCode(
                defaults.detectionMode
            ) ?: DetectionModality.FLUORESCENCE
            _uiState.update { state ->
                state.copy(
                    form = state.form.copy(
                        detectionModality = if (detectionModeEdited) {
                            state.form.detectionModality
                        } else {
                            defaultMode
                        },
                        customRowsInput = if (customGridEdited) {
                            state.form.customRowsInput
                        } else {
                            defaults.customGrid.rows.toString()
                        },
                        customColumnsInput = if (customGridEdited) {
                            state.form.customColumnsInput
                        } else {
                            defaults.customGrid.columns.toString()
                        },
                        spectrumLightSource = if (spectrumLightSourceEdited) {
                            state.form.spectrumLightSource
                        } else {
                            defaults.spectrumLightSource
                        }
                    )
                )
            }
        }
    }

    fun updateProjectName(value: String) = updateForm { copy(projectName = value) }

    fun updateDetectionModality(value: DetectionModality) {
        detectionModeEdited = true
        updateForm { copy(detectionModality = value) }
    }

    fun updateCarrierPreset(value: DirectCarrierPreset) = updateForm {
        copy(carrierPreset = value)
    }

    fun updateCustomRows(value: String) {
        customGridEdited = true
        updateForm { copy(customRowsInput = value.filter(Char::isDigit)) }
    }

    fun updateCustomColumns(value: String) {
        customGridEdited = true
        updateForm { copy(customColumnsInput = value.filter(Char::isDigit)) }
    }

    fun updateCustomSiteShape(value: SiteShape) = updateForm {
        // POINT/CUSTOM 没有普通创建页可提供的稳定定位和分割协议，不能写入生产快照。
        if (value == SiteShape.CIRCLE || value == SiteShape.SQUARE) {
            copy(customSiteShape = value)
        } else {
            this
        }
    }

    fun updateSpectrumLightSource(value: SpectrumLightSource) {
        spectrumLightSourceEdited = true
        updateForm { copy(spectrumLightSource = value) }
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
                        customSiteShape = form.customSiteShape
                            .takeIf {
                                form.carrierPreset == DirectCarrierPreset.MICROFLUIDIC_CUSTOM
                            },
                        spectrumLightSource = form.spectrumLightSource.takeIf {
                            form.detectionModality == DetectionModality.SPECTRUM
                        },
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
        defaultConcentrationUnit.takeIf { it in concentrationUnits }
            ?: concentrationUnits.firstOrNull()
            ?: DEFAULT_CONCENTRATION_UNITS.first()

    private companion object {
        val DEFAULT_CONCENTRATION_UNITS = listOf("ng/mL", "pg/mL", "μg/mL", "mg/mL")
    }
}
