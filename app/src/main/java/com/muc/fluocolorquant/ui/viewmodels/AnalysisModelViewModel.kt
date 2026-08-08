package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelDraft
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelFormError
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelStatusFilter
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

/** 分析模型编辑器当前执行的业务动作。 */
enum class AnalysisModelEditorMode {
    CREATE,
    EDIT_DRAFT
}

/**
 * 一次性事件对应的稳定操作码。
 *
 * 页面根据操作码选择中英文文案；ViewModel 不保存 Context，也不直接拼接用户可见文本。
 */
enum class AnalysisModelOperation {
    LOAD,
    SAVE,
    CREATE_VERSION,
    PUBLISH,
    ARCHIVE
}

/** 统一分析模型页面的一次性事件。 */
sealed interface AnalysisModelEvent {
    data class ValidationFailed(val errors: Set<AnalysisModelFormError>) : AnalysisModelEvent
    data object DraftSaved : AnalysisModelEvent
    data object VersionCreated : AnalysisModelEvent
    data object Published : AnalysisModelEvent
    data object Archived : AnalysisModelEvent
    data class OperationFailed(val operation: AnalysisModelOperation) : AnalysisModelEvent
}

/** 统一分析模型库的不可变页面状态。 */
data class AnalysisModelUiState(
    val models: List<AnalysisModel> = emptyList(),
    val analytes: List<Analyte> = emptyList(),
    val acquisitionProfiles: List<AcquisitionProfile> = emptyList(),
    val selectedType: AnalysisModelType? = null,
    val selectedStatus: AnalysisModelStatusFilter = AnalysisModelStatusFilter.ALL,
    val draft: AnalysisModelDraft = AnalysisModelDraft(),
    val editorMode: AnalysisModelEditorMode? = null,
    val editingModelId: String? = null,
    val isEditorVisible: Boolean = false,
    val isSaving: Boolean = false
) {
    /** 类型与生命周期筛选必须同时满足，避免筛选芯片互相覆盖。 */
    val visibleModels: List<AnalysisModel>
        get() = models.filter { model ->
            val matchesType = selectedType == null || model.modelType == selectedType.code
            val matchesStatus = when (selectedStatus) {
                AnalysisModelStatusFilter.ALL -> true
                AnalysisModelStatusFilter.DRAFT ->
                    model.status == AnalysisModelLifecycleStatus.DRAFT.code
                AnalysisModelStatusFilter.PUBLISHED ->
                    model.status == AnalysisModelLifecycleStatus.PUBLISHED.code
                AnalysisModelStatusFilter.ARCHIVED ->
                    model.status == AnalysisModelLifecycleStatus.ARCHIVED.code
                AnalysisModelStatusFilter.LEGACY ->
                    model.status == AnalysisModelLifecycleStatus.LEGACY.code
            }
            matchesType && matchesStatus
        }

    val publishedCount: Int
        get() = models.count { it.status == AnalysisModelLifecycleStatus.PUBLISHED.code }

    val standardCurveCount: Int
        get() = models.count { it.modelType == AnalysisModelType.STANDARD_CURVE.code }

    val deepLearningCount: Int
        get() = models.count { it.modelType == AnalysisModelType.DEEP_LEARNING.code }
}

/**
 * 统一分析模型库的业务编排。
 *
 * ViewModel 负责把可编辑表单转换为 Room 数据包，并在发布前重新读取持久化版本完成完整
 * 校验。这样即使页面重组或进程恢复，也不会依赖内存中可能过期的表单状态。
 */
@HiltViewModel
class AnalysisModelViewModel @Inject constructor(
    private val analysisModelRepository: AnalysisModelRepository,
    private val analyteRepository: AnalyteRepository,
    private val acquisitionProfileRepository: AcquisitionProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisModelUiState())
    val uiState: StateFlow<AnalysisModelUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AnalysisModelEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AnalysisModelEvent> = _events.asSharedFlow()

    init {
        // 三类基础数据分别响应式收集，任意一类变化都不会覆盖其他已加载状态。
        viewModelScope.launch {
            analysisModelRepository.observeAll().collect { models ->
                _uiState.value = _uiState.value.copy(models = models)
            }
        }
        viewModelScope.launch {
            analyteRepository.getAllAnalytes().collect { analytes ->
                _uiState.value = _uiState.value.copy(analytes = analytes)
            }
        }
        viewModelScope.launch {
            acquisitionProfileRepository.observeAll().collect { profiles ->
                _uiState.value = _uiState.value.copy(acquisitionProfiles = profiles)
            }
        }
    }

    fun selectType(type: AnalysisModelType?) {
        _uiState.value = _uiState.value.copy(selectedType = type)
    }

    fun selectStatus(status: AnalysisModelStatusFilter) {
        _uiState.value = _uiState.value.copy(selectedStatus = status)
    }

    fun openCreateEditor() {
        _uiState.value = _uiState.value.copy(
            draft = AnalysisModelDraft(),
            editorMode = AnalysisModelEditorMode.CREATE,
            editingModelId = null,
            isEditorVisible = true
        )
    }

    /** 只允许草稿进入原地编辑，发布版本必须使用“创建下一版本”。 */
    fun openEditDraft(modelId: String) {
        viewModelScope.launch {
            try {
                val bundle = analysisModelRepository.getBundle(modelId)
                    ?: throw IllegalArgumentException("分析模型不存在")
                check(bundle.model.status == AnalysisModelLifecycleStatus.DRAFT.code)
                openBundleEditor(bundle)
            } catch (_: Exception) {
                _events.emit(AnalysisModelEvent.OperationFailed(AnalysisModelOperation.LOAD))
            }
        }
    }

    fun updateDraft(draft: AnalysisModelDraft) {
        _uiState.value = _uiState.value.copy(draft = draft)
    }

    fun dismissEditor() {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(
            isEditorVisible = false,
            editorMode = null,
            editingModelId = null
        )
    }

    /** 保存新草稿或更新当前草稿；草稿阶段不强制要求发布兼容范围完整。 */
    fun saveDraft() {
        val state = _uiState.value
        val errors = state.draft.validateForDraft()
        if (errors.isNotEmpty()) {
            _events.tryEmit(AnalysisModelEvent.ValidationFailed(errors))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val currentModel = state.editingModelId?.let { modelId ->
                    analysisModelRepository.getBundle(modelId)?.model
                        ?: throw IllegalArgumentException("分析模型不存在")
                }
                val bundle = state.draft.toBundle(currentModel)
                if (state.editorMode == AnalysisModelEditorMode.EDIT_DRAFT) {
                    analysisModelRepository.updateDraft(bundle)
                } else {
                    analysisModelRepository.createDraft(bundle)
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isEditorVisible = false,
                    editorMode = null,
                    editingModelId = null
                )
                _events.emit(AnalysisModelEvent.DraftSaved)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(AnalysisModelEvent.OperationFailed(AnalysisModelOperation.SAVE))
            }
        }
    }

    /**
     * 创建下一版本时旧发布版本保持可用，新版本会立即成为独立草稿并打开编辑器。
     */
    fun createNextVersion(previousId: String) {
        viewModelScope.launch {
            try {
                val nextDraft = analysisModelRepository.createNextDraft(previousId)
                openBundleEditor(nextDraft)
                _events.emit(AnalysisModelEvent.VersionCreated)
            } catch (_: Exception) {
                _events.emit(
                    AnalysisModelEvent.OperationFailed(AnalysisModelOperation.CREATE_VERSION)
                )
            }
        }
    }

    /** 发布前从仓库重建草稿并执行完整校验，防止绕过载体/设备兼容约束。 */
    fun publish(modelId: String) {
        viewModelScope.launch {
            try {
                val bundle = analysisModelRepository.getBundle(modelId)
                    ?: throw IllegalArgumentException("分析模型不存在")
                val errors = bundle.toDraft().validateForPublication()
                if (errors.isNotEmpty()) {
                    _events.emit(AnalysisModelEvent.ValidationFailed(errors))
                    return@launch
                }
                analysisModelRepository.publish(modelId)
                _events.emit(AnalysisModelEvent.Published)
            } catch (_: Exception) {
                _events.emit(AnalysisModelEvent.OperationFailed(AnalysisModelOperation.PUBLISH))
            }
        }
    }

    fun archive(modelId: String) {
        viewModelScope.launch {
            try {
                analysisModelRepository.archive(modelId)
                _events.emit(AnalysisModelEvent.Archived)
            } catch (_: Exception) {
                _events.emit(AnalysisModelEvent.OperationFailed(AnalysisModelOperation.ARCHIVE))
            }
        }
    }

    private fun openBundleEditor(bundle: AnalysisModelBundle) {
        _uiState.value = _uiState.value.copy(
            draft = bundle.toDraft(),
            editorMode = AnalysisModelEditorMode.EDIT_DRAFT,
            editingModelId = bundle.model.id,
            isEditorVisible = true,
            isSaving = false
        )
    }
}

/** 将表单转换成主档和互斥的类型专用定义。 */
private fun AnalysisModelDraft.toBundle(currentModel: AnalysisModel?): AnalysisModelBundle {
    val reliableRange = requireNotNull(reliableRangeOrNull())
    val modelId = currentModel?.id ?: UUID.randomUUID().toString()
    val now = Date()
    val model = AnalysisModel(
        id = modelId,
        name = name.trim(),
        modelType = modelType.code,
        analyteId = analyteId,
        detectionMode = detectionMode,
        inputProtocol = inputProtocol,
        primaryFeature = primaryFeature,
        processorName = processorName.trim(),
        processorVersion = processorVersion.trim(),
        compatibleCarrierTypesJson = AnalysisModelCompatibilityJsonCodec.encode(
            compatibleCarrierTypes
        ),
        compatibleAcquisitionProfileIdsJson = AnalysisModelCompatibilityJsonCodec.encode(
            compatibleAcquisitionProfileIds
        ),
        concentrationUnit = concentrationUnit.trim(),
        reliableRangeMin = reliableRange.first,
        reliableRangeMax = reliableRange.second,
        validationMetricsJson = currentModel?.validationMetricsJson,
        status = currentModel?.status ?: AnalysisModelLifecycleStatus.DRAFT.code,
        version = currentModel?.version ?: 1,
        createdAt = currentModel?.createdAt ?: now,
        updatedAt = now
    )

    return when (modelType) {
        AnalysisModelType.STANDARD_CURVE -> AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = modelId,
                fittingFunction = fittingFunction.trim(),
                parametersJson = parametersJson.trim(),
                monotonicDirection = monotonicDirection,
                lod = lodInput.toDoubleOrNull(),
                loq = loqInput.toDoubleOrNull()
            )
        )

        AnalysisModelType.DEEP_LEARNING -> AnalysisModelBundle(
            model = model,
            deepLearning = DeepLearningModelDefinition(
                analysisModelId = modelId,
                modelFileName = modelFileName.trim(),
                checksumSha256 = checksumSha256.trim(),
                inputWidth = inputWidthInput.toIntOrNull() ?: 0,
                inputHeight = inputHeightInput.toIntOrNull() ?: 0,
                normalizationJson = normalizationJson.trim(),
                trainingDataVersion = trainingDataVersion.trim()
            )
        )
    }
}

/** 将仓库数据包恢复为表单，供编辑和发布校验共用同一套契约。 */
private fun AnalysisModelBundle.toDraft(): AnalysisModelDraft {
    val type = AnalysisModelType.fromCode(model.modelType) ?: AnalysisModelType.STANDARD_CURVE
    return AnalysisModelDraft(
        name = model.name,
        modelType = type,
        analyteId = model.analyteId,
        detectionMode = model.detectionMode,
        inputProtocol = model.inputProtocol,
        primaryFeature = model.primaryFeature,
        processorName = model.processorName,
        processorVersion = model.processorVersion,
        compatibleCarrierTypes = AnalysisModelCompatibilityJsonCodec.decode(
            model.compatibleCarrierTypesJson
        ),
        compatibleAcquisitionProfileIds = AnalysisModelCompatibilityJsonCodec.decode(
            model.compatibleAcquisitionProfileIdsJson
        ),
        concentrationUnit = model.concentrationUnit,
        reliableRangeMinInput = model.reliableRangeMin.toInputText(),
        reliableRangeMaxInput = model.reliableRangeMax.toInputText(),
        fittingFunction = standardCurve?.fittingFunction.orEmpty(),
        parametersJson = standardCurve?.parametersJson.orEmpty(),
        monotonicDirection = standardCurve?.monotonicDirection ?: "AUTO",
        lodInput = standardCurve?.lod?.toInputText().orEmpty(),
        loqInput = standardCurve?.loq?.toInputText().orEmpty(),
        modelFileName = deepLearning?.modelFileName.orEmpty(),
        checksumSha256 = deepLearning?.checksumSha256.orEmpty(),
        inputWidthInput = deepLearning?.inputWidth?.toString().orEmpty(),
        inputHeightInput = deepLearning?.inputHeight?.toString().orEmpty(),
        normalizationJson = deepLearning?.normalizationJson.orEmpty(),
        trainingDataVersion = deepLearning?.trainingDataVersion.orEmpty()
    )
}

/** 避免把整数范围恢复成带 `.0` 的输入文本，使编辑表单更清晰。 */
private fun Double.toInputText(): String = if (this % 1.0 == 0.0) {
    toLong().toString()
} else {
    toString()
}

/**
 * 兼容范围 JSON 编解码器。
 *
 * 只处理稳定编码数组，输出会去空、去重并排序，保证数据库快照和导出差异稳定；解析时
 * 保留未知编码，后续页面可以明确显示“资源已缺失”，而不是静默换成错误设备。
 */
private object AnalysisModelCompatibilityJsonCodec {
    private val quotedValueRegex = Regex("\\\"([^\\\"]*)\\\"")

    fun encode(codes: Set<String>): String {
        return codes.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString(prefix = "[", postfix = "]", separator = ",") { code ->
                "\"${escape(code)}\""
            }
    }

    fun decode(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return quotedValueRegex.findAll(json)
            .map { match -> unescape(match.groupValues[1]) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}
