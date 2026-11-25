package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ProjectCreationPreferences
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescencePhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantConfig
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.domain.project.DirectProjectAnalyteRequest
import com.muc.fluocolorquant.domain.project.DirectProjectCreateRequest
import com.muc.fluocolorquant.domain.project.DirectProjectCreationCoordinator
import com.muc.fluocolorquant.domain.project.DirectProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.TemplateProjectCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreateRequest
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.TemplateResolution
import com.muc.fluocolorquant.domain.project.TemplateSiteKey
import com.muc.fluocolorquant.ui.screens.project.DirectAnalyteSelection
import com.muc.fluocolorquant.ui.screens.project.DirectProjectCreationMode
import com.muc.fluocolorquant.ui.screens.project.DirectProjectFormState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 新建项目页直接供用户选择的轻量模板摘要（屏蔽版本化发布与设备档案等底层概念）。 */
data class DirectTemplateQuickOption(
    val id: String,
    val name: String,
    val modality: DetectionModality,
    val rows: Int,
    val columns: Int,
    val isPlate96: Boolean,
    val analyteLabels: List<String>,
    val standardSiteCount: Int,
    val sampleSiteCount: Int,
    val referenceSiteCount: Int,
    val quantitationSummaryCodes: List<String>
)

/** 新建项目页面所需的完整 UI 状态。 */
data class DirectProjectUiState(
    val analytes: List<Analyte> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val defaultConcentrationUnit: String = DEFAULT_DIRECT_CONCENTRATION_UNIT,
    val form: DirectProjectFormState = DirectProjectFormState(),
    val isLoading: Boolean = true,
    val publishedStandardCurves: List<AnalysisModel> = emptyList(),
    val reagentsByAnalyteId: Map<String, List<Reagent>> = emptyMap(),
    val reusableTemplates: List<DirectTemplateQuickOption> = emptyList()
) {
    /** 统计当前检测方式与荧光通道下，该分析物在标准曲线库中可直接复用的已发布曲线数。 */
    fun compatibleCurveCount(selection: DirectAnalyteSelection, modality: DetectionModality): Int {
        if (modality == DetectionModality.SPECTRUM) return 0
        val expectedBasePrefix = if (modality == DetectionModality.FLUORESCENCE) {
            FluorescencePhotometryProcessor.processorVersionFor(PgQuantConfig(), selection.fluorescenceChannel)
        } else {
            COLORIMETRIC_PROCESSOR_VERSION
        }
        return publishedStandardCurves.count { model ->
            model.analyteId == selection.analyteId &&
                model.detectionMode == modality.code &&
                model.concentrationUnit.equals(selection.concentrationUnit, ignoreCase = true) &&
                (model.processorVersion == expectedBasePrefix ||
                    (selection.fluorescenceChannel == FluorescenceChannel.GREEN &&
                        modality == DetectionModality.FLUORESCENCE &&
                        model.processorVersion.startsWith("v1-readout1-") &&
                        !model.processorVersion.contains("-ch-")) ||
                    (selection.fluorescenceChannel != FluorescenceChannel.GREEN &&
                        modality == DetectionModality.FLUORESCENCE &&
                        model.processorVersion.endsWith("-ch-${selection.fluorescenceChannel.name}")) ||
                    (modality == DetectionModality.COLORIMETRIC &&
                        model.processorVersion.startsWith("v1")))
        }
    }

    val selectedTemplateOption: DirectTemplateQuickOption?
        get() = reusableTemplates.firstOrNull { it.id == form.selectedTemplateId }
}

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
 * 新建项目统一状态机（同时支持自由新建与一键从实验模板复用）。
 */
@HiltViewModel
class DirectProjectViewModel @Inject constructor(
    private val analyteRepository: AnalyteRepository,
    private val projectCreationPreferences: ProjectCreationPreferences,
    private val coordinator: DirectProjectCreationCoordinator,
    private val analysisModelRepository: AnalysisModelRepository = EmptyAnalysisModelRepository,
    private val reagentRepository: ReagentRepository = EmptyReagentRepository,
    private val templateCoordinator: TemplateProjectCoordinator = EmptyTemplateProjectCoordinator,
    private val templateRepository: ExperimentTemplateRepository = EmptyExperimentTemplateRepository,
    private val carrierRepository: CarrierProfileRepository = EmptyCarrierProfileRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DirectProjectUiState())
    val uiState: StateFlow<DirectProjectUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DirectProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DirectProjectEvent> = _events.asSharedFlow()

    private var detectionModeEdited = false
    private var customGridEdited = false
    private var spectrumLightSourceEdited = false
    private var preselectedTemplateApplied = false

    init {
        loadProjectCreationDefaults()
        observeProjectCreationOptions()
        observePublishedStandardCurves()
        observeReagents()
        observeReusableTemplates()
    }

    private fun observePublishedStandardCurves() {
        viewModelScope.launch {
            analysisModelRepository.observeAll().collect { models ->
                val curves = models.filter { model ->
                    model.status == AnalysisModelLifecycleStatus.PUBLISHED.code &&
                        model.modelType == AnalysisModelType.STANDARD_CURVE.code
                }
                _uiState.update { it.copy(publishedStandardCurves = curves) }
            }
        }
    }

    private fun observeReagents() {
        viewModelScope.launch {
            reagentRepository.getAllReagents().collect { reagents ->
                val grouped = reagents.groupBy { it.analyteId }
                _uiState.update { it.copy(reagentsByAnalyteId = grouped) }
            }
        }
    }

    private fun observeReusableTemplates() {
        viewModelScope.launch {
            combine(
                templateCoordinator.observePublishedTemplates(),
                analyteRepository.getAllAnalytes()
            ) { templates, analytes -> templates to analytes }
                .collect { (templates, analytes) ->
                    val analyteNames = analytes.associateBy({ it.id }, { it.name })
                    val options = templates.mapNotNull { template ->
                        val modality = DetectionModality.fromCode(template.detectionMode)
                            ?: return@mapNotNull null
                        val bundle = templateRepository.getBundle(template.id)
                            ?: return@mapNotNull null
                        val carrier = template.carrierProfileId
                            ?.let { carrierRepository.getById(it) }
                        val rows = carrier?.rows ?: 8
                        val columns = carrier?.columns ?: 12
                        val isPlate96 = carrier?.carrierType == CarrierType.PLATE.code
                        val bindingsByAnalyte = bundle.quantitationBindings.associateBy { it.analyteId }
                        val analyteLabels = bundle.analyteConfigs
                            .sortedBy { it.displayOrder }
                            .map { cfg ->
                                val name = analyteNames[cfg.analyteId] ?: cfg.analyteId
                                "$name (${cfg.concentrationUnit})"
                            }
                            .ifEmpty {
                                template.analyteId?.let { id ->
                                    listOf(analyteNames[id] ?: id)
                                }.orEmpty()
                            }
                        val methodCodes = bundle.analyteConfigs
                            .sortedBy { it.displayOrder }
                            .map { cfg ->
                                val binding = bindingsByAnalyte[cfg.analyteId]
                                binding?.method ?: if (cfg.analysisModelId != null) {
                                    AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE.name
                                } else {
                                    AnalyteQuantitationMethod.SIGNAL_ONLY.name
                                }
                            }
                        val activeSites = bundle.siteAssignments.filter { it.enabled }
                        DirectTemplateQuickOption(
                            id = template.id,
                            name = template.templateName,
                            modality = modality,
                            rows = rows,
                            columns = columns,
                            isPlate96 = isPlate96,
                            analyteLabels = analyteLabels,
                            standardSiteCount = activeSites.count { it.roleType == TemplateSiteRole.STANDARD.code },
                            sampleSiteCount = activeSites.count { it.roleType == TemplateSiteRole.SAMPLE.code },
                            referenceSiteCount = activeSites.count {
                                it.roleType == TemplateSiteRole.BLANK.code ||
                                    it.roleType == TemplateSiteRole.REFERENCE.code
                            },
                            quantitationSummaryCodes = methodCodes
                        )
                    }
                    _uiState.update { state ->
                        val currentSelected = state.form.selectedTemplateId
                        val resolvedSelected = when {
                            currentSelected != null && options.any { it.id == currentSelected } ->
                                currentSelected
                            state.form.creationMode == DirectProjectCreationMode.FROM_TEMPLATE ->
                                options.firstOrNull()?.id
                            else -> currentSelected
                        }
                        state.copy(
                            reusableTemplates = options,
                            form = state.form.copy(selectedTemplateId = resolvedSelected)
                        )
                    }
                }
        }
    }

    /** 从实验模板库点击「用此模板检测」进入时，自动切至模板复用模式并选中目标模板。 */
    fun preselectTemplate(templateId: String?) {
        if (templateId.isNullOrBlank() || preselectedTemplateApplied) return
        preselectedTemplateApplied = true
        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    creationMode = DirectProjectCreationMode.FROM_TEMPLATE,
                    selectedTemplateId = templateId
                )
            )
        }
    }

    fun setCreationMode(mode: DirectProjectCreationMode) {
        _uiState.update { state ->
            val nextTemplateId = if (mode == DirectProjectCreationMode.FROM_TEMPLATE) {
                state.form.selectedTemplateId?.takeIf { id ->
                    state.reusableTemplates.any { it.id == id }
                } ?: state.reusableTemplates.firstOrNull()?.id
            } else {
                state.form.selectedTemplateId
            }
            state.copy(
                form = state.form.copy(
                    creationMode = mode,
                    selectedTemplateId = nextTemplateId
                )
            )
        }
    }

    fun selectTemplate(templateId: String) = updateForm {
        copy(
            creationMode = DirectProjectCreationMode.FROM_TEMPLATE,
            selectedTemplateId = templateId
        )
    }

    private fun observeProjectCreationOptions() {
        viewModelScope.launch {
            combine(
                analyteRepository.getAllAnalytes(),
                projectCreationPreferences.concentrationUnitsFlow,
                projectCreationPreferences.defaultConcentrationUnitFlow
            ) { analytes, units, preferredUnit -> Triple(analytes, units, preferredUnit) }
                .collect { (analytes, units, preferredUnit) ->
                    val sortedAnalytes = analytes.sortedBy(Analyte::name)
                    val analyteCustomUnits = sortedAnalytes.mapNotNull {
                        it.defaultUnit?.trim()?.takeIf(String::isNotEmpty)
                    }
                    val sortedUnits = (units + analyteCustomUnits)
                        .filter(String::isNotBlank)
                        .distinct()
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
                        val nextSelections = if (retained.isEmpty() && sortedAnalytes.size == 1) {
                            listOf(buildDefaultSelectionFor(sortedAnalytes.single(), sortedUnits, defaultUnit))
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

    fun updateSelectedAnalytes(selectedIds: List<String>) {
        _uiState.update { state ->
            val analytesById = state.analytes.associateBy(Analyte::id)
            val oldSelections = state.form.selectedAnalytes.associateBy(
                DirectAnalyteSelection::analyteId
            )
            val normalizedIds = selectedIds.filter { it in analytesById }.distinct()
            val fallbackUnit = state.defaultConcentrationUnit()
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = normalizedIds.map { analyteId ->
                        oldSelections[analyteId] ?: buildDefaultSelectionFor(
                            analyte = requireNotNull(analytesById[analyteId]),
                            availableUnits = state.concentrationUnits,
                            fallbackUnit = fallbackUnit
                        )
                    }
                )
            )
        }
    }

    private fun buildDefaultSelectionFor(
        analyte: Analyte,
        availableUnits: List<String>,
        fallbackUnit: String
    ): DirectAnalyteSelection {
        val preferredUnit = analyte.defaultUnit?.trim()
            ?.takeIf { candidate -> availableUnits.any { it.equals(candidate, ignoreCase = true) } }
            ?.let { candidate -> availableUnits.first { it.equals(candidate, ignoreCase = true) } }
            ?: fallbackUnit
        val preferredMax = analyte.defaultMaxConcentration
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let(::formatCleanDecimal)
            ?: "100"
        val preferredChannel = analyte.defaultFluorescenceChannel
            ?.trim()
            ?.uppercase()
            ?.let { code -> FluorescenceChannel.entries.firstOrNull { it.name == code } }
            ?: FluorescenceChannel.GREEN
        return DirectAnalyteSelection(
            analyteId = analyte.id,
            concentrationUnit = preferredUnit,
            maxConcentrationInput = preferredMax,
            fluorescenceChannel = preferredChannel
        )
    }

    private fun formatCleanDecimal(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    fun updateAnalyteFluorescenceChannel(analyteId: String, channel: FluorescenceChannel) {
        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = state.form.selectedAnalytes.map { selection ->
                        if (selection.analyteId == analyteId) {
                            selection.copy(fluorescenceChannel = channel)
                        } else {
                            selection
                        }
                    }
                )
            )
        }
    }

    /** 切换某个分析物关联的试剂批次（可选，同类型单选替换）。 */
    fun toggleAnalyteReagent(analyteId: String, reagentId: String) {
        _uiState.update { state ->
            val reagents = state.reagentsByAnalyteId[analyteId].orEmpty()
            val clicked = reagents.firstOrNull { it.id == reagentId } ?: return@update state
            state.copy(
                form = state.form.copy(
                    selectedAnalytes = state.form.selectedAnalytes.map { selection ->
                        if (selection.analyteId != analyteId) {
                            selection
                        } else {
                            val nextIds = if (reagentId in selection.selectedReagentIds) {
                                selection.selectedReagentIds - reagentId
                            } else {
                                val sameTypeIds = reagents
                                    .filter { it.reagentType.equals(clicked.reagentType, ignoreCase = true) }
                                    .map { it.id }
                                    .toSet()
                                (selection.selectedReagentIds - sameTypeIds) + reagentId
                            }
                            selection.copy(selectedReagentIds = nextIds)
                        }
                    }
                )
            )
        }
    }

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

        if (form.creationMode == DirectProjectCreationMode.FROM_TEMPLATE) {
            createFromTemplate(userId, form)
            return
        }

        val analytesById = state.analytes.associateBy(Analyte::id)
        val requestedAnalytes = form.selectedAnalytes.mapNotNull { selection ->
            analytesById[selection.analyteId]?.let { analyte ->
                val availableReagents = state.reagentsByAnalyteId[analyte.id].orEmpty()
                val chosenReagents = availableReagents.filter { it.id in selection.selectedReagentIds }
                val antigenId = chosenReagents
                    .firstOrNull { it.reagentType.equals("antigen", ignoreCase = true) }?.id
                val antibodyId = chosenReagents
                    .firstOrNull { it.reagentType.equals("antibody", ignoreCase = true) }?.id
                val reagentSummary = chosenReagents
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = " · ") { reagent ->
                        buildString {
                            append(reagent.reagentName)
                            val extras = listOfNotNull(
                                reagent.manufacturer?.takeIf(String::isNotBlank),
                                reagent.lotNumber?.takeIf(String::isNotBlank)?.let { "Lot:$it" }
                            )
                            if (extras.isNotEmpty()) {
                                append(" (")
                                append(extras.joinToString(" · "))
                                append(")")
                            }
                        }
                    }
                DirectProjectAnalyteRequest(
                    analyte = analyte,
                    concentrationUnit = selection.concentrationUnit,
                    maxConcentration = selection.maxConcentration
                        ?: return@mapNotNull null,
                    fluorescenceChannel = selection.fluorescenceChannel,
                    reagentAntigenId = antigenId,
                    reagentAntibodyId = antibodyId,
                    reagentSummary = reagentSummary
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

    /**
     * 一键从实验模板创建项目：仅需项目名称与实验图，孔位样本编号自动继承模板布局。
     */
    private fun createFromTemplate(userId: String, form: DirectProjectFormState) {
        val templateId = form.selectedTemplateId?.takeIf(String::isNotBlank) ?: run {
            _events.tryEmit(DirectProjectEvent.FormIncomplete)
            return
        }
        _uiState.update { it.copy(form = it.form.copy(isSubmitting = true)) }
        viewModelScope.launch {
            try {
                val resolution = templateCoordinator.resolveTemplate(templateId)
                if (resolution !is TemplateResolution.Ready) {
                    _uiState.update { it.copy(form = it.form.copy(isSubmitting = false)) }
                    _events.emit(DirectProjectEvent.UnexpectedFailure)
                    return@launch
                }
                val sampleMapping = resolution.configuration.snapshot.siteAssignments
                    .filter { it.enabled && it.roleType == TemplateSiteRole.SAMPLE.code }
                    .associate { site ->
                        val key = TemplateSiteKey.format(site.rowIndex, site.columnIndex)
                        val defaultLabel = site.defaultSampleSlot?.takeIf(String::isNotBlank)
                            ?: site.repeatGroup?.takeIf(String::isNotBlank)
                            ?: coordinateLabel(site.rowIndex, site.columnIndex)
                        key to defaultLabel
                    }
                val outcome = templateCoordinator.createProject(
                    TemplateProjectCreateRequest(
                        templateId = templateId,
                        name = form.projectName,
                        imageUri = form.imageUri.orEmpty(),
                        userId = userId,
                        sampleSlotMapping = sampleMapping
                    )
                )
                _uiState.update { it.copy(form = it.form.copy(isSubmitting = false)) }
                when (outcome) {
                    is TemplateProjectCreationOutcome.Created -> _events.emit(
                        DirectProjectEvent.Created(
                            projectId = outcome.project.id,
                            imageUri = outcome.project.imageUri,
                            destination = outcome.destination
                        )
                    )
                    is TemplateProjectCreationOutcome.Blocked ->
                        _events.emit(DirectProjectEvent.UnexpectedFailure)
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(form = it.form.copy(isSubmitting = false)) }
                _events.emit(DirectProjectEvent.UnexpectedFailure)
            }
        }
    }

    private fun coordinateLabel(rowIndex: Int, columnIndex: Int): String {
        val rowLetter = if (rowIndex in 0..25) ('A' + rowIndex).toString() else "R${rowIndex + 1}"
        return "$rowLetter${columnIndex + 1}"
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

private object EmptyAnalysisModelRepository : AnalysisModelRepository {
    override fun observeAll(): Flow<List<AnalysisModel>> = flowOf(emptyList())
    override suspend fun getBundle(id: String): AnalysisModelBundle? = null
    override suspend fun getReusableBundleByContentFingerprint(fingerprint: String): AnalysisModelBundle? = null
    override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle = bundle
    override suspend fun updateDraft(bundle: AnalysisModelBundle) = Unit
    override suspend fun replace(bundle: AnalysisModelBundle) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun createNextDraft(previousId: String): AnalysisModelBundle =
        error("EmptyAnalysisModelRepository does not support draft creation")
    override suspend fun publish(id: String) = Unit
    override suspend fun archive(id: String) = Unit
}

private object EmptyReagentRepository : ReagentRepository {
    override fun getAllReagents(): Flow<List<Reagent>> = flowOf(emptyList())
    override fun getReagentsByAnalyteId(analyteId: String): Flow<List<Reagent>> = flowOf(emptyList())
    override suspend fun getReagentById(id: String): Reagent? = null
    override suspend fun addReagent(
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String?,
        molecularWeight: Double?,
        unit: String?,
        lotNumber: String?
    ): Boolean = false
    override suspend fun updateReagent(
        id: String,
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String?,
        molecularWeight: Double?,
        unit: String?,
        lotNumber: String?
    ): Boolean = false
    override suspend fun deleteReagent(reagent: Reagent) = Unit
}

private object EmptyTemplateProjectCoordinator : TemplateProjectCoordinator {
    override fun observePublishedTemplates(): Flow<List<ExperimentTemplate>> = flowOf(emptyList())
    override suspend fun resolveTemplate(templateId: String): TemplateResolution =
        TemplateResolution.Blocked(null, emptyList())
    override suspend fun createProject(request: TemplateProjectCreateRequest): TemplateProjectCreationOutcome =
        TemplateProjectCreationOutcome.Blocked(emptyList())
}

private object EmptyExperimentTemplateRepository : ExperimentTemplateRepository {
    override fun getAllTemplates(): Flow<List<ExperimentTemplate>> = flowOf(emptyList())
    override suspend fun getTemplateById(id: String): ExperimentTemplate? = null
    override suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate> = emptyList()
    override suspend fun saveTemplate(template: ExperimentTemplate) = Unit
    override suspend fun updateTemplate(template: ExperimentTemplate) = Unit
    override suspend fun deleteTemplate(template: ExperimentTemplate) = Unit
    override suspend fun deleteTemplateById(id: String) = Unit
    override suspend fun getBundle(id: String): ExperimentTemplateBundle? = null
    override suspend fun createDraft(bundle: ExperimentTemplateBundle): ExperimentTemplateBundle = bundle
    override suspend fun updateDraft(bundle: ExperimentTemplateBundle) = Unit
    override suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle =
        error("EmptyExperimentTemplateRepository does not support draft creation")
    override suspend fun publish(id: String) = Unit
    override suspend fun archive(id: String) = Unit
}

private object EmptyCarrierProfileRepository : CarrierProfileRepository {
    override fun observeAll(): Flow<List<CarrierProfile>> = flowOf(emptyList())
    override suspend fun getById(id: String): CarrierProfile? = null
    override suspend fun create(profile: CarrierProfile) = Unit
    override suspend fun createNextVersion(previousId: String, replacement: CarrierProfile): CarrierProfile =
        replacement
    override suspend fun archive(id: String) = Unit
}
