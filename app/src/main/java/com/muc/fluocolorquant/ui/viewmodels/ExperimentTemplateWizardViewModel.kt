package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.BuiltInResourceIds
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ConcentrationUnitPreferences
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateAnalyteDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateArrayLayoutDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateSiteCoordinate
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateSiteDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateWizardDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateWizardError
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateWizardStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/** 模板向导异步操作的稳定编码。 */
enum class ExperimentTemplateWizardOperation {
    LOAD,
    SAVE,
    PUBLISH,
    CREATE_VERSION,
    ARCHIVE
}

/** 页面消费的一次性事件，不包含硬编码用户文本。 */
sealed interface ExperimentTemplateWizardEvent {
    data class ValidationFailed(val errors: Set<TemplateWizardError>) :
        ExperimentTemplateWizardEvent
    data class DraftSaved(val templateId: String) : ExperimentTemplateWizardEvent
    data class Published(val templateId: String) : ExperimentTemplateWizardEvent
    data class VersionCreated(val templateId: String) : ExperimentTemplateWizardEvent
    data class Archived(val templateId: String) : ExperimentTemplateWizardEvent
    data class OperationFailed(val operation: ExperimentTemplateWizardOperation) :
        ExperimentTemplateWizardEvent
}

/** 微流控模板向导的不可变页面状态。 */
data class ExperimentTemplateWizardUiState(
    val currentStep: TemplateWizardStep = TemplateWizardStep.BASIC,
    val draft: TemplateWizardDraft = TemplateWizardDraft(),
    val carriers: List<CarrierProfile> = emptyList(),
    val acquisitionProfiles: List<AcquisitionProfile> = emptyList(),
    val analytes: List<Analyte> = emptyList(),
    val reagents: List<Reagent> = emptyList(),
    val analysisModels: List<AnalysisModel> = emptyList(),
    val concentrationUnits: List<String> = emptyList(),
    val defaultConcentrationUnit: String = "",
    val editingTemplateId: String? = null,
    // 记录“复制并调整”的来源，防止旋转屏幕后 LaunchedEffect 重复创建 v3/v4 草稿。
    val versionSourceTemplateId: String? = null,
    val isCreatingVersion: Boolean = false,
    val isSaving: Boolean = false,
    val isPublishing: Boolean = false
) {
    val selectedCarrier: CarrierProfile?
        get() = carriers.find { it.id == draft.carrierProfileId }

    val activeCarriers: List<CarrierProfile>
        get() = carriers.filter { it.status == ResourceStatus.ACTIVE.code }

    /** 设备必须同时支持当前模态和所选载体物理类型。 */
    val compatibleAcquisitionProfiles: List<AcquisitionProfile>
        get() {
            val carrierType = selectedCarrier?.carrierType ?: return emptyList()
            return acquisitionProfiles.filter { profile ->
                profile.status == ResourceStatus.ACTIVE.code &&
                    draft.detectionMode in StableCodeArrayJson.decode(profile.supportedModesJson) &&
                    carrierType in StableCodeArrayJson.decode(profile.compatibleCarrierTypesJson)
            }
        }

    /**
     * 返回与分析物、模态、协议和载体匹配的已发布模型。
     *
     * 采集设备不再是普通用户创建模板的前置条件。若历史模板已经保存了设备 ID，则继续
     * 尊重模型的设备兼容范围；新模板设备为空时不因此隐藏所有模型，真正拍摄参数由相机
     * 自动记录，并在运行时决定是否可以定量。
     */
    fun compatibleModelsFor(analyteId: String): List<AnalysisModel> {
        val carrierType = selectedCarrier?.carrierType ?: return emptyList()
        val acquisitionId = draft.acquisitionProfileId.takeIf(String::isNotBlank)
        return analysisModels.filter { model ->
            val compatibleAcquisitionIds = StableCodeArrayJson.decode(
                model.compatibleAcquisitionProfileIdsJson
            )
            model.status == AnalysisModelLifecycleStatus.PUBLISHED.code &&
                model.analyteId == analyteId &&
                model.detectionMode == draft.detectionMode &&
                model.inputProtocol == draft.inputProtocol &&
                carrierType in StableCodeArrayJson.decode(model.compatibleCarrierTypesJson) &&
                (acquisitionId == null || compatibleAcquisitionIds.isEmpty() ||
                    acquisitionId in compatibleAcquisitionIds)
        }.sortedWith(compareBy(AnalysisModel::name).thenByDescending(AnalysisModel::version))
    }
}

/**
 * 多步骤实验模板向导业务编排。
 *
 * 载体是阵列几何的唯一来源；检测模态、载体或采集设备改变时会清空已选分析模型，
 * 防止用户在界面上留下看似完整、实际不兼容的科学配置。
 */
@HiltViewModel
class ExperimentTemplateWizardViewModel @Inject constructor(
    private val templateRepository: ExperimentTemplateRepository,
    private val carrierProfileRepository: CarrierProfileRepository,
    private val acquisitionProfileRepository: AcquisitionProfileRepository,
    private val analyteRepository: AnalyteRepository,
    private val reagentRepository: ReagentRepository,
    private val analysisModelRepository: AnalysisModelRepository,
    private val concentrationUnitPreferences: ConcentrationUnitPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExperimentTemplateWizardUiState())
    val uiState: StateFlow<ExperimentTemplateWizardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ExperimentTemplateWizardEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ExperimentTemplateWizardEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            carrierProfileRepository.observeAll().collect { values ->
                _uiState.value = _uiState.value.copy(carriers = values)
                autoSelectDefaultResources()
            }
        }
        viewModelScope.launch {
            acquisitionProfileRepository.observeAll().collect { values ->
                _uiState.value = _uiState.value.copy(acquisitionProfiles = values)
                autoSelectDefaultResources()
            }
        }
        viewModelScope.launch {
            analyteRepository.getAllAnalytes().collect { values ->
                _uiState.value = _uiState.value.copy(analytes = values)
            }
        }
        viewModelScope.launch {
            reagentRepository.getAllReagents().collect { values ->
                _uiState.value = _uiState.value.copy(reagents = values)
            }
        }
        viewModelScope.launch {
            analysisModelRepository.observeAll().collect { values ->
                _uiState.value = _uiState.value.copy(analysisModels = values)
            }
        }
        viewModelScope.launch {
            concentrationUnitPreferences.defaultConcentrationUnitFlow.collect { unit ->
                _uiState.value = _uiState.value.copy(defaultConcentrationUnit = unit)
            }
        }
        viewModelScope.launch {
            concentrationUnitPreferences.concentrationUnitsFlow.collect { units ->
                val currentUnits = _uiState.value.draft.analytes
                    .map(TemplateAnalyteDraft::concentrationUnit)
                    .filter(String::isNotBlank)
                _uiState.value = _uiState.value.copy(
                    concentrationUnits = (units + currentUnits).sorted()
                )
            }
        }
    }

    fun updateDraft(draft: TemplateWizardDraft) {
        _uiState.value = _uiState.value.copy(draft = draft)
    }

    /**
     * 为新建草稿自动选中内置默认载体，使普通用户无需进入载体库。
     *
     * 只作用于新建流程（[ExperimentTemplateWizardUiState.editingTemplateId] 为空）且字段仍
     * 为空时；编辑既有模板不覆盖用户历史选择。采集设备不再由用户预先建档，手机型号、
     * Camera ID、ISO、曝光、焦距和光圈等改由拍摄链自动记录。
     */
    private fun autoSelectDefaultResources() {
        val state = _uiState.value
        if (state.editingTemplateId != null) return
        var draft = state.draft
        var changed = false

        if (draft.carrierProfileId.isBlank()) {
            val defaultCarrier = state.carriers.firstOrNull {
                it.id == BuiltInResourceIds.DEFAULT_CARRIER_ID &&
                    it.status == ResourceStatus.ACTIVE.code
            } ?: state.activeCarriers.firstOrNull()
            if (defaultCarrier != null) {
                draft = draft.copy(
                    carrierProfileId = defaultCarrier.id,
                    layout = TemplateArrayLayoutDraft(
                        rows = defaultCarrier.rows,
                        columns = defaultCarrier.columns
                    )
                )
                changed = true
            }
        }

        if (changed) {
            _uiState.value = _uiState.value.copy(draft = draft)
        }
    }

    fun updateBasicInformation(name: String, purpose: String, versionNote: String) {
        val draft = _uiState.value.draft
        updateDraft(
            draft.copy(
                templateName = name,
                purpose = purpose,
                versionNote = versionNote
            )
        )
    }

    fun selectDetectionMode(mode: DetectionModality) {
        val draft = _uiState.value.draft
        val protocol = when (mode) {
            DetectionModality.COLORIMETRIC,
            DetectionModality.FLUORESCENCE -> InputProtocol.ENDPOINT_ONLY.code
            DetectionModality.SPECTRUM -> InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code
        }
        val readoutLayout = when (mode) {
            DetectionModality.COLORIMETRIC,
            DetectionModality.FLUORESCENCE -> ReadoutLayout.GRID_SITES.code
            DetectionModality.SPECTRUM -> {
                // 光谱不是独立物理载体，而是同一载体上的读出组织方式；首次切换时默认采用
                // 光谱轨道，若用户已选择逐位点光谱或单区域则保留其明确选择。
                draft.readoutLayout.takeIf { current ->
                    current == ReadoutLayout.SPECTRAL_TRACKS.code ||
                        current == ReadoutLayout.SINGLE_REGION.code ||
                        current == ReadoutLayout.PER_SITE_SPECTRUM.code
                } ?: ReadoutLayout.SPECTRAL_TRACKS.code
            }
        }
        updateDraft(
            draft.copy(
                detectionMode = mode.code,
                readoutLayout = readoutLayout,
                inputProtocol = protocol,
                acquisitionProfileId = "",
                analytes = draft.analytes.map { analyte ->
                    analyte.copy(
                        analysisModelId = null,
                        // 从比色切换到荧光时只为当前空值提供新草稿默认值；已保存选择保持不变。
                        fluorescenceChannel = if (mode == DetectionModality.FLUORESCENCE) {
                            analyte.fluorescenceChannel ?: FluorescenceChannel.GREEN
                        } else {
                            analyte.fluorescenceChannel
                        }
                    )
                }
            )
        )
        autoSelectDefaultResources()
    }

    fun selectInputProtocol(protocol: InputProtocol) {
        val draft = _uiState.value.draft
        if (protocol.code !in draft.allowedProtocols) return
        updateDraft(
            draft.copy(
                inputProtocol = protocol.code,
                analytes = draft.analytes.map { it.copy(analysisModelId = null) }
            )
        )
    }

    fun selectCarrier(carrierId: String) {
        val carrier = _uiState.value.carriers.find { it.id == carrierId } ?: return
        val draft = _uiState.value.draft
        updateDraft(
            draft.copy(
                carrierProfileId = carrier.id,
                acquisitionProfileId = "",
                analytes = draft.analytes.map { it.copy(analysisModelId = null) },
                // 更换物理载体时从空布局重新开始，绝不复用相同左上角坐标的旧含义。
                layout = TemplateArrayLayoutDraft(
                    rows = carrier.rows,
                    columns = carrier.columns
                )
            )
        )
        autoSelectDefaultResources()
    }

    fun selectAcquisitionProfile(profileId: String) {
        if (_uiState.value.compatibleAcquisitionProfiles.none { it.id == profileId }) return
        val draft = _uiState.value.draft
        updateDraft(
            draft.copy(
                acquisitionProfileId = profileId,
                analytes = draft.analytes.map { it.copy(analysisModelId = null) }
            )
        )
    }

    fun addAnalyte(analyteId: String) {
        val state = _uiState.value
        if (state.analytes.none { it.id == analyteId }) return
        if (state.draft.analytes.any { it.analyteId == analyteId }) return
        updateDraft(
            state.draft.copy(
                analytes = state.draft.analytes + TemplateAnalyteDraft(analyteId = analyteId)
            )
        )
    }

    fun removeAnalyte(analyteId: String) {
        val draft = _uiState.value.draft
        updateDraft(
            draft.copy(
                analytes = draft.analytes.filterNot { it.analyteId == analyteId },
                layout = draft.layout.copy(
                    assignments = draft.layout.assignments.filterValues {
                        it.analyteId != analyteId
                    }
                )
            )
        )
    }

    fun updateAnalyte(analyteId: String, replacement: TemplateAnalyteDraft) {
        val draft = _uiState.value.draft
        updateDraft(
            draft.copy(
                analytes = draft.analytes.map { current ->
                    if (current.analyteId == analyteId) replacement else current
                }
            )
        )
    }

    fun selectAnalysisModel(analyteId: String, modelId: String?) {
        val state = _uiState.value
        val model = modelId?.let { id ->
            state.compatibleModelsFor(analyteId).find { it.id == id }
        }
        if (modelId != null && model == null) return
        val draft = state.draft
        updateDraft(
            draft.copy(
                analytes = draft.analytes.map { analyte ->
                    if (analyte.analyteId == analyteId) {
                        if (model == null) {
                            // 用户明确选择“仅信号”时清空定量语义，避免结果页误以为存在
                            // 可执行模型；原始信号、热力图与质量控制仍然完整保留。
                            analyte.copy(
                                analysisModelId = null,
                                concentrationUnit = "",
                                reliableRangeMinInput = "",
                                reliableRangeMaxInput = ""
                            )
                        } else {
                            analyte.copy(
                                analysisModelId = model.id,
                                concentrationUnit = model.concentrationUnit,
                                reliableRangeMinInput = model.reliableRangeMin.toInputText(),
                                reliableRangeMaxInput = model.reliableRangeMax.toInputText()
                            )
                        }
                    } else {
                        analyte
                    }
                }
            )
        )
    }

    fun toggleLayoutSite(coordinate: TemplateSiteCoordinate) {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.toggleSelection(coordinate)))
    }

    fun selectRectanglePoint(coordinate: TemplateSiteCoordinate) {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.selectRectanglePoint(coordinate)))
    }

    fun selectLayoutRow(rowIndex: Int) {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.selectRow(rowIndex)))
    }

    fun selectLayoutColumn(columnIndex: Int) {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.selectColumn(columnIndex)))
    }

    fun selectAllLayoutSites() {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.selectAll()))
    }

    fun clearLayoutSelection() {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.clearSelection()))
    }

    fun applyToSelectedSites(site: TemplateSiteDraft) {
        val draft = _uiState.value.draft
        updateDraft(draft.copy(layout = draft.layout.applyToSelection(site)))
    }

    fun previousStep() {
        val currentIndex = _uiState.value.currentStep.ordinal
        if (currentIndex <= 0) return
        _uiState.value = _uiState.value.copy(
            currentStep = TemplateWizardStep.entries[currentIndex - 1]
        )
    }

    fun nextStep() {
        val state = _uiState.value
        val errors = state.errorsForCurrentStep()
        if (errors.isNotEmpty()) {
            _events.tryEmit(ExperimentTemplateWizardEvent.ValidationFailed(errors))
            return
        }
        val nextIndex = state.currentStep.ordinal + 1
        if (nextIndex < TemplateWizardStep.entries.size) {
            _uiState.value = state.copy(currentStep = TemplateWizardStep.entries[nextIndex])
        }
    }

    fun loadTemplate(templateId: String) {
        viewModelScope.launch {
            try {
                val bundle = templateRepository.getBundle(templateId)
                    ?: throw IllegalArgumentException("实验模板不存在")
                // 载体 Flow 与导航加载可能并发到达。这里按模板外键主动补查一次，确保即使
                // 页面刚进入或旧模板没有任何位点分配，也能恢复载体声明的真实行列几何。
                val selectedCarrier = bundle.template.carrierProfileId?.let { carrierId ->
                    carrierProfileRepository.getById(carrierId)
                }
                val carriersForRestore = (_uiState.value.carriers + listOfNotNull(selectedCarrier))
                    .distinctBy(CarrierProfile::id)
                _uiState.value = _uiState.value.copy(
                    draft = bundle.toDraft(carriersForRestore),
                    editingTemplateId = bundle.template.id,
                    versionSourceTemplateId = null,
                    currentStep = TemplateWizardStep.BASIC
                )
            } catch (_: Exception) {
                _events.emit(
                    ExperimentTemplateWizardEvent.OperationFailed(
                        ExperimentTemplateWizardOperation.LOAD
                    )
                )
            }
        }
    }

    fun createNextVersion(previousId: String) {
        val current = _uiState.value
        if (current.isCreatingVersion || current.versionSourceTemplateId == previousId) return
        _uiState.value = current.copy(isCreatingVersion = true)
        viewModelScope.launch {
            try {
                val next = templateRepository.createNextDraft(previousId)
                _uiState.value = _uiState.value.copy(
                    draft = next.toDraft(_uiState.value.carriers),
                    editingTemplateId = next.template.id,
                    versionSourceTemplateId = previousId,
                    isCreatingVersion = false,
                    currentStep = TemplateWizardStep.BASIC
                )
                _events.emit(ExperimentTemplateWizardEvent.VersionCreated(next.template.id))
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isCreatingVersion = false)
                _events.emit(
                    ExperimentTemplateWizardEvent.OperationFailed(
                        ExperimentTemplateWizardOperation.CREATE_VERSION
                    )
                )
            }
        }
    }

    /** 已发布模板通过归档退出可选列表，数据库记录和历史项目引用仍完整保留。 */
    fun archiveTemplate(templateId: String) {
        viewModelScope.launch {
            try {
                templateRepository.archive(templateId)
                _events.emit(ExperimentTemplateWizardEvent.Archived(templateId))
            } catch (_: Exception) {
                _events.emit(
                    ExperimentTemplateWizardEvent.OperationFailed(
                        ExperimentTemplateWizardOperation.ARCHIVE
                    )
                )
            }
        }
    }

    fun saveDraft() {
        val state = _uiState.value
        val errors = state.draft.validateForDraft()
        if (errors.isNotEmpty()) {
            _events.tryEmit(ExperimentTemplateWizardEvent.ValidationFailed(errors))
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                // Repository 查询是挂起函数，必须在当前协程中显式调用；不能作为普通 let 转换函数引用。
                val current = state.editingTemplateId?.let { templateId ->
                    templateRepository.getBundle(templateId)
                }
                val bundle = state.draft.toBundle(current?.template)
                val templateId = if (current == null) {
                    templateRepository.createDraft(bundle).template.id
                } else {
                    templateRepository.updateDraft(bundle)
                    current.template.id
                }
                _uiState.value = _uiState.value.copy(
                    editingTemplateId = templateId,
                    isSaving = false
                )
                _events.emit(ExperimentTemplateWizardEvent.DraftSaved(templateId))
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(
                    ExperimentTemplateWizardEvent.OperationFailed(
                        ExperimentTemplateWizardOperation.SAVE
                    )
                )
            }
        }
    }

    fun publish() {
        val state = _uiState.value
        // 普通用户只执行一次“保存模板”。设备档案和定量模型均为可选项，完整布局仍需
        // 校验；新模板保存后在后台标记为可用，以兼容现有模板查询和历史快照逻辑。
        val errors = state.draft.validateForPublication()
        if (errors.isNotEmpty()) {
            _events.tryEmit(ExperimentTemplateWizardEvent.ValidationFailed(errors))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPublishing = true)
            try {
                // 保存前重新读取最新模板，确保主档与分析物、位点子项基于同一记录原子更新。
                val current = state.editingTemplateId?.let { templateId ->
                    templateRepository.getBundle(templateId)
                }
                val candidate = state.draft.toBundle(current?.template)
                val templateId = if (current == null) {
                    templateRepository.createDraft(candidate).template.id
                } else {
                    templateRepository.updateDraft(candidate)
                    current.template.id
                }
                templateRepository.publish(templateId)
                _uiState.value = _uiState.value.copy(
                    editingTemplateId = templateId,
                    isPublishing = false
                )
                _events.emit(ExperimentTemplateWizardEvent.Published(templateId))
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isPublishing = false)
                _events.emit(
                    ExperimentTemplateWizardEvent.OperationFailed(
                        ExperimentTemplateWizardOperation.PUBLISH
                    )
                )
            }
        }
    }
}

/** 当前步骤只拦截完成该步骤所必需的信息，允许用户分步构建复杂模板。 */
private fun ExperimentTemplateWizardUiState.errorsForCurrentStep(): Set<TemplateWizardError> {
    val draftErrors = draft.validateForDraft()
    return when (currentStep) {
        TemplateWizardStep.BASIC -> draftErrors.filterTo(linkedSetOf()) {
            it == TemplateWizardError.NAME_REQUIRED
        }
        TemplateWizardStep.DETECTION_AND_RESOURCES -> buildSet {
            addAll(draftErrors.filter {
                it == TemplateWizardError.DETECTION_MODE_REQUIRED ||
                    it == TemplateWizardError.READOUT_LAYOUT_REQUIRED ||
                    it == TemplateWizardError.INPUT_PROTOCOL_INCOMPATIBLE
            })
            if (draft.carrierProfileId.isBlank()) add(TemplateWizardError.CARRIER_REQUIRED)
        }
        TemplateWizardStep.ANALYTES -> buildSet {
            if (draft.analytes.isEmpty()) add(TemplateWizardError.ANALYTE_REQUIRED)
            addAll(draftErrors.filter {
                it == TemplateWizardError.ANALYTE_REQUIRED ||
                    it == TemplateWizardError.DUPLICATE_ANALYTE ||
                    it == TemplateWizardError.CONCENTRATION_UNIT_REQUIRED ||
                    it == TemplateWizardError.RELIABLE_RANGE_INVALID
            })
        }
        TemplateWizardStep.LAYOUT -> draft.validateForPublication().filterTo(linkedSetOf()) {
            it in LAYOUT_ERRORS
        }
        TemplateWizardStep.QC_AND_REVIEW -> emptySet()
    }
}

private val LAYOUT_ERRORS = setOf(
    TemplateWizardError.LAYOUT_SIZE_INVALID,
    TemplateWizardError.LAYOUT_OUT_OF_BOUNDS,
    TemplateWizardError.LAYOUT_NOT_FULLY_ASSIGNED,
    TemplateWizardError.SITE_ANALYTE_REQUIRED,
    TemplateWizardError.SITE_ANALYTE_UNKNOWN,
    TemplateWizardError.STANDARD_CONCENTRATION_INVALID,
    TemplateWizardError.SAMPLE_SITE_REQUIRED,
    TemplateWizardError.ANALYTE_BLANK_REQUIRED
)

/** 将向导草稿转换为 Room 11 主档和两类子项。 */
private fun TemplateWizardDraft.toBundle(current: ExperimentTemplate?): ExperimentTemplateBundle {
    val templateId = current?.id ?: UUID.randomUUID().toString()
    val now = Date()
    val template = ExperimentTemplate(
        id = templateId,
        templateName = templateName.trim(),
        analyteId = null,
        reagentAntigenId = null,
        reagentAntibodyId = null,
        fkCurveModelId = null,
        reliableRangeMin = 0.0,
        reliableRangeMax = 0.0,
        concentrationUnit = "",
        defaultLayoutJson = null,
        createdAt = current?.createdAt ?: now,
        updatedAt = now,
        version = current?.version ?: 1,
        status = current?.status ?: TemplateLifecycleStatus.DRAFT.code,
        carrierProfileId = carrierProfileId,
        detectionMode = detectionMode,
        readoutLayout = readoutLayout,
        acquisitionProfileId = acquisitionProfileId,
        inputProtocol = inputProtocol,
        qcProfileJson = qcProfileJson,
        publishedAt = current?.publishedAt,
        purpose = purpose.trim().ifEmpty { null },
        versionNote = versionNote.trim().ifEmpty { null }
    )
    return ExperimentTemplateBundle(
        template = template,
        analyteConfigs = analytes.mapIndexed { index, analyte ->
            val range = analyte.reliableRangeOrNull()
            TemplateAnalyteConfig(
                templateId = templateId,
                analyteId = analyte.analyteId,
                reagentAntigenId = analyte.reagentAntigenId,
                reagentAntibodyId = analyte.reagentAntibodyId,
                analysisModelId = analyte.analysisModelId,
                concentrationUnit = analyte.concentrationUnit.trim(),
                reliableRangeMin = range?.first,
                reliableRangeMax = range?.second,
                displayOrder = index,
                displayConfigJson = if (detectionMode == DetectionModality.FLUORESCENCE.code) {
                    ScientificDetectionConfigCodec.encodeFluorescenceDisplay(
                        requireNotNull(analyte.fluorescenceChannel) {
                            "荧光模板分析物必须明确读出通道"
                        }
                    )
                } else {
                    null
                }
            )
        },
        siteAssignments = layout.assignments.entries.sortedBy(Map.Entry<TemplateSiteCoordinate, *>::key)
            .map { (coordinate, rawSite) ->
                val site = rawSite.normalized()
                TemplateSiteAssignment(
                    templateId = templateId,
                    rowIndex = coordinate.rowIndex,
                    columnIndex = coordinate.columnIndex,
                    analyteId = site.analyteId,
                    roleType = site.role.code,
                    standardConcentration = site.standardConcentrationInput.toDoubleOrNull(),
                    repeatGroup = site.repeatGroup.ifEmpty { null },
                    defaultSampleSlot = site.defaultSampleSlot.ifEmpty { null },
                    referenceScope = site.referenceScope.code,
                    enabled = site.enabled
                )
            }
    )
}

/** 将已保存模板恢复为可继续编辑的向导草稿。 */
private fun ExperimentTemplateBundle.toDraft(carriers: List<CarrierProfile>): TemplateWizardDraft {
    val carrier = carriers.find { it.id == template.carrierProfileId }
    val rows = carrier?.rows ?: ((siteAssignments.maxOfOrNull { it.rowIndex } ?: -1) + 1)
    val columns = carrier?.columns ?: ((siteAssignments.maxOfOrNull { it.columnIndex } ?: -1) + 1)
    val restoredAnalytes = if (analyteConfigs.isNotEmpty()) {
        analyteConfigs.map { config ->
            TemplateAnalyteDraft(
                analyteId = config.analyteId,
                reagentAntigenId = config.reagentAntigenId,
                reagentAntibodyId = config.reagentAntibodyId,
                analysisModelId = config.analysisModelId,
                concentrationUnit = config.concentrationUnit,
                reliableRangeMinInput = config.reliableRangeMin?.toInputText().orEmpty(),
                reliableRangeMaxInput = config.reliableRangeMax?.toInputText().orEmpty(),
                fluorescenceChannel = if (
                    template.detectionMode == DetectionModality.FLUORESCENCE.code
                ) {
                    // 缺失、损坏或未知 schema 保持 null，让发布预检明确指出配置不完整。
                    ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                        config.displayConfigJson
                    )
                } else {
                    null
                }
            )
        }
    } else {
        // Room 10 以前的模板只有主表单分析物字段。迁移到新向导时保留其科学身份、试剂、
        // 单位与范围，但不把旧 CurveModel ID 伪装成统一 AnalysisModel ID；用户需要显式
        // 选择兼容且已发布的新分析模型后才能发布下一版本。
        listOfNotNull(
            template.analyteId?.let { analyteId ->
                TemplateAnalyteDraft(
                    analyteId = analyteId,
                    reagentAntigenId = template.reagentAntigenId,
                    reagentAntibodyId = template.reagentAntibodyId,
                    analysisModelId = null,
                    concentrationUnit = template.concentrationUnit,
                    reliableRangeMinInput = template.reliableRangeMin.toInputText(),
                    reliableRangeMaxInput = template.reliableRangeMax.toInputText(),
                    // 旧主表从未保存过荧光通道，保持缺失状态并要求创建新版本时显式选择。
                    fluorescenceChannel = null
                )
            }
        )
    }
    return TemplateWizardDraft(
        templateName = template.templateName,
        purpose = template.purpose.orEmpty(),
        versionNote = template.versionNote.orEmpty(),
        detectionMode = template.detectionMode ?: DetectionModality.COLORIMETRIC.code,
        readoutLayout = template.readoutLayout ?: ReadoutLayout.GRID_SITES.code,
        inputProtocol = template.inputProtocol,
        carrierProfileId = template.carrierProfileId.orEmpty(),
        acquisitionProfileId = template.acquisitionProfileId.orEmpty(),
        analytes = restoredAnalytes,
        layout = TemplateArrayLayoutDraft(
            rows = rows,
            columns = columns,
            assignments = siteAssignments.associate { assignment ->
                TemplateSiteCoordinate(assignment.rowIndex, assignment.columnIndex) to
                    TemplateSiteDraft(
                        analyteId = assignment.analyteId,
                        role = com.muc.fluocolorquant.data.enums.TemplateSiteRole.fromCode(
                            assignment.roleType
                        ) ?: com.muc.fluocolorquant.data.enums.TemplateSiteRole.DISABLED,
                        standardConcentrationInput = assignment.standardConcentration
                            ?.toInputText().orEmpty(),
                        repeatGroup = assignment.repeatGroup.orEmpty(),
                        defaultSampleSlot = assignment.defaultSampleSlot.orEmpty(),
                        referenceScope = com.muc.fluocolorquant.data.enums.TemplateReferenceScope
                            .fromCode(assignment.referenceScope)
                            ?: com.muc.fluocolorquant.data.enums.TemplateReferenceScope.ANALYTE,
                        enabled = assignment.enabled
                    )
            }
        ),
        qcProfileJson = template.qcProfileJson ?: "{}"
    )
}

private fun Double.toInputText(): String = if (this % 1.0 == 0.0) {
    toLong().toString()
} else {
    toString()
}

/** 稳定编码数组的轻量 JSON 解析器，未知编码原样保留供兼容检查。 */
private object StableCodeArrayJson {
    private val quotedValueRegex = Regex("\\\"([^\\\"]*)\\\"")

    fun decode(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return quotedValueRegex.findAll(json)
            .map { it.groupValues[1] }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }
}
