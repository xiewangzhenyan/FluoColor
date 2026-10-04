package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.application.detection.GridConfigurationResourceCoordinator
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateReferenceScope
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.CalibrationPolicyPreferences
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.calibration.CalibrationApplicationDecision
import com.muc.fluocolorquant.domain.calibration.OnsiteCalibrationState
import com.muc.fluocolorquant.domain.calibration.applicationDecision
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridCarrierRoute
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionCoordinator
import com.muc.fluocolorquant.domain.detection.GridDetectionOutcome
import com.muc.fluocolorquant.domain.detection.GridDetectionRequest
import com.muc.fluocolorquant.domain.detection.GridDetectionRouteResolver
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.domain.detection.GridLocalizationOutcome
import com.muc.fluocolorquant.domain.detection.GridLocalizationPresentation
import com.muc.fluocolorquant.domain.detection.GridLocalizationSession
import com.muc.fluocolorquant.domain.detection.acceptOnsiteFitResult
import com.muc.fluocolorquant.domain.detection.completeOnsiteApplying
import com.muc.fluocolorquant.domain.detection.isConfigurationComplete
import com.muc.fluocolorquant.domain.detection.isReadyForConfirmation
import com.muc.fluocolorquant.domain.detection.rejectOnsiteFitResult
import com.muc.fluocolorquant.domain.detection.resolvedGridQuantitationMode
import com.muc.fluocolorquant.domain.detection.restoreOnsiteReviewingAfterApplyFailure
import com.muc.fluocolorquant.domain.detection.returnToOnsiteEditing
import com.muc.fluocolorquant.domain.detection.selectOnsiteCandidate
import com.muc.fluocolorquant.domain.detection.setOnsiteSaveToLibrary
import com.muc.fluocolorquant.domain.detection.startOnsiteApplying
import com.muc.fluocolorquant.domain.detection.startOnsiteFitting
import com.muc.fluocolorquant.domain.detection.withPersistedOnsiteCurveResource
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLDecoder
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微流控“定位确认 → 孔位布局 → 定量保存”状态机。
 *
 * 定位阶段不要求预先存在孔位分配；页面确认布局后才调用最终定量。定位会话保存在同一个
 * ViewModel 中，因此不会因页面重组重复运行 OpenCV，也不会用两次不同结果完成同一实验。
 */
@HiltViewModel
class GridDetectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val coordinator: GridDetectionCoordinator,
    private val configurationResourceCoordinator: GridConfigurationResourceCoordinator,
    private val calibrationPolicyPreferences: CalibrationPolicyPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<GridDetectionUiState>(
        GridDetectionUiState.ResolvingProject
    )
    val uiState: StateFlow<GridDetectionUiState> = _uiState.asStateFlow()

    private val _configurationEvents = MutableSharedFlow<GridConfigurationEvent>(extraBufferCapacity = 8)
    val configurationEvents: SharedFlow<GridConfigurationEvent> = _configurationEvents.asSharedFlow()

    private var lastProjectId: String? = null
    private var lastImageUri: String? = null
    private var running: Boolean = false
    private var localizationSession: GridLocalizationSession? = null
    private val layoutDraftStore = GridLayoutDraftStore()
    private var configurationSource = GridLayoutConfigurationSource.MANUAL
    private var selectedTemplateId: String? = null
    private var availableTemplates: List<GridExperimentTemplateOption> = emptyList()
    private var availableModels: List<GridAnalysisModelOption> = emptyList()
    private var quantitationDrafts: Map<String, GridAnalyteQuantitationDraft> = emptyMap()
    private var selectedQuantitationAnalyteId: String? = null
    private var manualAssignmentsBackup: List<GridLayoutAssignmentDraft> = emptyList()
    private var manualQuantitationBackup: Map<String, GridAnalyteQuantitationDraft> = emptyMap()
    private var manualSnapshotBackup:
        com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot? = null
    private var savingReusableResource: Boolean = false

    fun start(projectId: String?, imageUri: String?) {
        if (projectId.isNullOrBlank() || imageUri.isNullOrBlank()) {
            _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.INVALID_ARGUMENTS)
            return
        }
        val sameRequest = projectId == lastProjectId && imageUri == lastImageUri
        if (running || sameRequest && _uiState.value !is GridDetectionUiState.Error) {
            return
        }
        if (!sameRequest) {
            // ViewModel 可能被导航框架短暂复用；切换项目或图片时必须清空上一实验的布局，
            // 避免同规格芯片之间误带分析物、标准浓度或样本编号。
            layoutDraftStore.clear()
            configurationSource = GridLayoutConfigurationSource.MANUAL
            selectedTemplateId = null
            availableTemplates = emptyList()
            availableModels = emptyList()
            quantitationDrafts = emptyMap()
            selectedQuantitationAnalyteId = null
            manualAssignmentsBackup = emptyList()
            manualQuantitationBackup = emptyMap()
            manualSnapshotBackup = null
        }
        lastProjectId = projectId
        lastImageUri = imageUri
        localize(projectId, imageUri)
    }

    /** 执行失败时重新定位；布局校验失败时则回到已存在的定位结果，避免重复计算。 */
    fun retry() {
        val session = localizationSession
        if (session != null && _uiState.value is GridDetectionUiState.Blocked) {
            _uiState.value = GridDetectionUiState.LocalizationReady(
                preview = session.toPreview(layoutDraftStore.current()),
                editingLayout = true
            )
            return
        }
        val projectId = lastProjectId ?: return
        val imageUri = lastImageUri ?: return
        if (!running) localize(projectId, imageUri)
    }

    /** 从定位确认进入孔位布局；步骤状态由 ViewModel 持有，旋转屏幕后仍停留在当前步骤。 */
    fun openLayoutEditor() {
        val current = _uiState.value as? GridDetectionUiState.LocalizationReady ?: return
        _uiState.value = current.copy(editingLayout = true)
    }

    /** 返回查看定位证据，但不清空用户已经完成的孔位布局。 */
    fun showLocalizationPreview() {
        val current = _uiState.value as? GridDetectionUiState.LocalizationReady ?: return
        val session = localizationSession ?: return
        _uiState.value = if (session.presentation == GridLocalizationPresentation.PLATE96) {
            // 96孔板必须返回可微调圆心和半径的专属定位页，不能降级成通用证据预览。
            GridDetectionUiState.Plate96Localization
        } else {
            current.copy(editingLayout = false)
        }
    }

    /** 定位页左上角返回布局时，仅结束复核，不退出整个新建项目流程。 */
    fun cancelPlate96LocalizationReview() {
        val session = localizationSession ?: return
        if (session.presentation != GridLocalizationPresentation.PLATE96) return
        publishPreview(editingLayout = true)
    }

    /** Compose根据该值区分首次定位和从布局返回复核，两种返回行为不能混用。 */
    fun hasActivePlate96LayoutSession(): Boolean =
        localizationSession?.presentation == GridLocalizationPresentation.PLATE96

    /**
     * 接收96孔板定位页已经确认的同一份原图、标准方向图和圆孔会话。
     *
     * 这里不会重新解码图片或重新运行YOLO/霍夫圆；只生成一次光度矩阵与长期证据，然后
     * 进入和微流控共用的布局/模板/逐分析物定量工作台。
     */
    fun acceptPlate96Localization(selection: Plate96LocalizationSelection) {
        val projectId = lastProjectId ?: return
        val imageUri = lastImageUri ?: return
        if (running) return
        viewModelScope.launch {
            running = true
            try {
                val previousSession = localizationSession?.takeIf {
                    it.presentation == GridLocalizationPresentation.PLATE96
                }
                val project = projectRepository.getProjectById(projectId)
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.PROJECT_NOT_FOUND
                        )
                        return@launch
                    }
                val snapshot = previousSession?.request?.snapshot
                    ?: project.templateSnapshotJson?.let { json ->
                        runCatching { TemplateProjectSnapshotCodec.decode(json) }.getOrNull()
                    } ?: run {
                    _uiState.value = GridDetectionUiState.Error(
                        GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID
                    )
                    return@launch
                }
                val outcome = coordinator.preparePlate96Localization(
                    request = GridDetectionRequest(
                        project = project,
                        snapshot = snapshot,
                        endpointBitmap = selection.sourceBitmap,
                        endpointPath = imageUri,
                        operatorId = project.userId,
                        // 复核定位属于同一次尚未完成的运行，不能生成新的运行ID或采集时间。
                        runId = previousSession?.request?.runId ?: java.util.UUID.randomUUID().toString(),
                        capturedAt = previousSession?.request?.capturedAt ?: java.util.Date(),
                        acquisitionMetadataJson = previousSession?.request?.acquisitionMetadataJson,
                        onStageChanged = { stage ->
                            _uiState.value = GridDetectionUiState.Processing(
                                stage = stage,
                                presentation = GridLocalizationPresentation.PLATE96
                            )
                        }
                    ),
                    normalizedBitmap = selection.normalizedBitmap,
                    locatorSession = selection.session,
                    exifRotationDegrees = selection.exifRotationDegrees,
                    exifFlipped = selection.exifFlipped
                )
                _uiState.value = when (outcome) {
                    GridLocalizationOutcome.LegacyPlateRequired ->
                        GridDetectionUiState.Plate96Localization
                    is GridLocalizationOutcome.Blocked ->
                        GridDetectionUiState.Blocked(outcome.reasons)
                    is GridLocalizationOutcome.Ready -> if (previousSession == null) {
                        initializeLocalizationSession(
                            session = outcome.session,
                            openLayoutImmediately = true
                        )
                    } else {
                        refreshPlate96LocalizationSession(outcome.session)
                    }
                }
            } catch (_: Exception) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } catch (_: LinkageError) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } finally {
                running = false
            }
        }
    }

    /**
     * 使用用户微调后的圆孔几何替换科学采样，同时保留全部布局和逐分析物定量草稿。
     *
     * 新会话沿用原运行ID和当前冻结快照；只更新圆心、半径、裁切、光度和过程证据，绝不
     * 重置模板来源、现场曲线选择或用户已经输入的标准浓度。
     */
    private fun refreshPlate96LocalizationSession(
        refreshed: GridLocalizationSession
    ): GridDetectionUiState.LocalizationReady {
        localizationSession = refreshed
        val assignments = layoutDraftStore.current()
        return GridDetectionUiState.LocalizationReady(
            preview = refreshed.toPreview(assignments),
            editingLayout = true
        )
    }

    /**
     * 切换单分析物配置工作台的当前对象。
     *
     * 这里只改变页面焦点，不修改任何科学快照；因此用户可以随时回来复核已经完成的项目。
     */
    fun selectQuantitationAnalyte(analyteId: String) {
        val session = localizationSession ?: return
        if (session.request.snapshot.analytes.none { it.analyte.id == analyteId }) return
        selectedQuantitationAnalyteId = analyteId
        publishPreview(editingLayout = true)
    }

    /**
     * 明确确认当前分析物方案，并自动切换到下一个尚未完成的分析物。
     *
     * “仅信号”无需额外资源；曲线/模型必须已经选择；现场拟合必须已经生成有效预览。
     * UI 会同步禁用不满足条件的按钮，这里仍做第二层校验，避免测试或未来调用方绕过门控。
     */
    fun confirmQuantitationAnalyte(
        analyteId: String,
        lowQualityConfirmed: Boolean = false
    ) {
        val current = quantitationDrafts[analyteId] ?: return
        if (!current.isReadyForConfirmation()) return
        if (current.mode == GridAnalyteQuantitationMode.ONSITE_AUTO_FIT) {
            applyOnsiteCalibration(analyteId, lowQualityConfirmed)
            return
        }
        val session = localizationSession ?: return
        val analyteSnapshot = session.request.snapshot.analytes.firstOrNull {
            it.analyte.id == analyteId
        } ?: return
        val method = when (current.mode) {
            GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE ->
                AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE
            GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL ->
                AnalyteQuantitationMethod.DEEP_LEARNING_MODEL
            GridAnalyteQuantitationMode.SIGNAL_ONLY -> AnalyteQuantitationMethod.SIGNAL_ONLY
            GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> return
        }
        val model = analyteSnapshot.analysisModel.model
        val applied = AnalyteQuantitationSnapshot(
            analyteId = analyteId,
            method = method,
            concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
            sourceResourceId = current.selectedAnalysisModelId,
            processorVersion = model.processorVersion,
            inputFingerprint = listOf(
                analyteId,
                method.name,
                current.selectedAnalysisModelId.orEmpty(),
                model.processorVersion,
                analyteSnapshot.templateConfig.concentrationUnit
            ).joinToString(":")
        )
        quantitationDrafts = quantitationDrafts + (
            analyteId to current.copy(appliedSnapshot = applied)
        )
        replaceSessionSnapshot(
            session.request.snapshot.copy(
                analytes = session.request.snapshot.analytes.map { analyte ->
                    if (analyte.analyte.id == analyteId) {
                        analyte.copy(analyteQuantitationSnapshot = applied)
                    } else {
                        analyte
                    }
                }
            )
        )
        selectedQuantitationAnalyteId = quantitationDrafts.values
            .sortedByAnalyteOrder(
                localizationSession?.request?.snapshot?.analytes?.map { it.analyte.id }.orEmpty()
            )
            .firstOrNull { !it.isConfigurationComplete() }
            ?.analyteId
            ?: analyteId
        rememberManualQuantitationIfNeeded()
        publishPreview(editingLayout = true)
    }

    /**
     * 实时保存孔位布局草稿。
     *
     * 草稿不再只依赖 Compose 的 `remember`，因此系统重建页面、返回定位复核以及科学门控
     * 阻止分析时，都能完整恢复分析物、角色、标准浓度和样本编号。
     */
    fun updateLayoutDraft(drafts: List<GridLayoutAssignmentDraft>) {
        val session = localizationSession ?: return
        val previous = layoutDraftStore.current()
        val normalized = layoutDraftStore.update(
            rows = session.grid.rows,
            columns = session.grid.columns,
            drafts = drafts
        )
        // 现场拟合必须读取用户此刻看到的布局，而不是进入页面前的旧快照。每次画笔变化
        // 都同步更新内存科学快照，并清除可能已经过期的拟合预览。
        val synchronizedSnapshot = session.request.snapshot.copy(
            siteAssignments = normalized.toTemplateAssignments(
                templateId = session.request.snapshot.template.id,
                idPrefix = "${session.request.runId}-layout"
            )
        )
        replaceSessionSnapshot(synchronizedSnapshot)
        val previousBySite = previous.associateBy { it.rowIndex to it.columnIndex }
        val normalizedBySite = normalized.associateBy { it.rowIndex to it.columnIndex }
        val changedAnalyteIds = buildSet {
            (previousBySite.keys + normalizedBySite.keys).forEach { key ->
                val before = previousBySite[key]
                val after = normalizedBySite[key]
                if (before != after) {
                    before?.analyteId?.let(::add)
                    after?.analyteId?.let(::add)
                }
            }
        }
        quantitationDrafts = quantitationDrafts.mapValues { (analyteId, draft) ->
            if (analyteId !in changedAnalyteIds) return@mapValues draft
            draft.copy(
                onsiteState = if (draft.mode == GridAnalyteQuantitationMode.ONSITE_AUTO_FIT) {
                    OnsiteCalibrationState.Editing
                } else {
                    draft.onsiteState
                },
                appliedSnapshot = null
            )
        }
        if (changedAnalyteIds.isNotEmpty()) {
            val latestSnapshot = requireNotNull(localizationSession).request.snapshot
            replaceSessionSnapshot(
                latestSnapshot.copy(
                    analytes = latestSnapshot.analytes.map { analyte ->
                        if (analyte.analyte.id in changedAnalyteIds) {
                            analyte.copy(analyteQuantitationSnapshot = null)
                        } else {
                            analyte
                        }
                    }
                )
            )
        }
        val current = _uiState.value as? GridDetectionUiState.LocalizationReady ?: return
        _uiState.value = current.copy(
            preview = current.preview.copy(
                initialAssignments = normalized,
                quantitationDrafts = quantitationDrafts.values.sortedByAnalyteOrder(
                    current.preview.analytes.map { it.id }
                )
            ),
            editingLayout = true
        )
        if (configurationSource == GridLayoutConfigurationSource.MANUAL) {
            manualAssignmentsBackup = normalized
            manualSnapshotBackup = localizationSession?.request?.snapshot
        }
    }

    /**
     * 批量写入一个分析物的逐标准孔真实浓度。
     *
     * 孔位索引使用行优先规则，与真实裁切预览和虚拟布局板完全一致。输入在 ViewModel 再次
     * 检查有限值、非负和项目最大浓度，防止异常 UI 状态进入冻结实验快照。
     */
    fun updateStandardConcentrations(
        analyteId: String,
        concentrationsBySite: Map<Int, Double?>
    ) {
        val session = localizationSession ?: return
        val analyte = session.request.snapshot.analytes.firstOrNull {
            it.analyte.id == analyteId
        } ?: return
        val maximum = analyte.templateConfig.reliableRangeMax
            ?: analyte.analysisModel.model.reliableRangeMax
        val columns = session.grid.columns
        val updated = layoutDraftStore.current().map { assignment ->
            val siteIndex = assignment.rowIndex * columns + assignment.columnIndex
            if (
                assignment.analyteId != analyteId ||
                assignment.role != TemplateSiteRole.STANDARD ||
                siteIndex !in concentrationsBySite
            ) {
                return@map assignment
            }
            val concentration = concentrationsBySite[siteIndex]?.takeIf { value ->
                value.isFinite() && value >= 0.0 && value <= maximum
            }
            assignment.copy(standardConcentration = concentration)
        }
        updateLayoutDraft(updated)
    }

    /** 切回手动配置，并恢复用户应用模板前已经绘制的布局与逐分析物方案。 */
    fun useManualConfiguration() {
        val session = localizationSession ?: return
        configurationSource = GridLayoutConfigurationSource.MANUAL
        selectedTemplateId = null
        val restored = layoutDraftStore.update(
            rows = session.grid.rows,
            columns = session.grid.columns,
            drafts = manualAssignmentsBackup
        )
        val restoredSnapshot = (manualSnapshotBackup ?: session.request.snapshot).copy(
            siteAssignments = restored.toTemplateAssignments(
                templateId = session.request.snapshot.template.id,
                idPrefix = "${session.request.runId}-manual-layout"
            ),
            sourceTemplateId = null,
            sourceTemplateName = null,
            sourceTemplateVersion = null
        )
        localizationSession = session.copy(
            request = session.request.copy(snapshot = restoredSnapshot)
        )
        quantitationDrafts = if (manualQuantitationBackup.isNotEmpty()) {
            manualQuantitationBackup
        } else {
            restoredSnapshot.analytes.associate { analyte ->
                analyte.analyte.id to analyte.toQuantitationDraft()
            }
        }
        selectedQuantitationAnalyteId = selectedQuantitationAnalyteId
            ?.takeIf(quantitationDrafts::containsKey)
            ?: restoredSnapshot.analytes.firstOrNull()?.analyte?.id
        publishPreview(assignments = restored, editingLayout = true)
    }

    /**
     * 应用一个与当前项目严格兼容的实验模板。
     *
     * 模板根 ID 不会覆盖项目自身身份；位点、单位和每分析物模型会被复制到项目快照，
     * 因此模板或模型资源今后被编辑/删除也不会改变本次检测与历史结果。
     */
    fun applyExperimentTemplate(templateId: String) {
        val session = localizationSession ?: return
        viewModelScope.launch {
            runCatching {
                val resolvedTemplate = configurationResourceCoordinator
                    .getResolvedTemplate(templateId)
                    ?: error("模板不存在")
                val bundle = resolvedTemplate.bundle
                val template = bundle.template
                val carrier = resolvedTemplate.carrier
                val currentSnapshot = session.request.snapshot
                val currentAnalytes = currentSnapshot.analytes.associateBy { it.analyte.id }
                val templateAnalyteIds = bundle.analyteConfigs.map { it.analyteId }.toSet()
                val bindingsByAnalyte = bundle.quantitationBindings.associateBy { it.analyteId }
                require(template.detectionMode == currentSnapshot.template.detectionMode)
                require(carrier.rows == session.grid.rows && carrier.columns == session.grid.columns)
                require(templateAnalyteIds == currentAnalytes.keys)

                if (configurationSource == GridLayoutConfigurationSource.MANUAL) {
                    manualAssignmentsBackup = layoutDraftStore.current()
                    manualQuantitationBackup = quantitationDrafts
                    manualSnapshotBackup = session.request.snapshot
                }
                val adaptedAnalytes = bundle.analyteConfigs.sortedBy { it.displayOrder }.map { config ->
                    val current = currentAnalytes.getValue(config.analyteId)
                    val binding = bindingsByAnalyte[config.analyteId]
                    val frozenBinding = binding
                        ?.let { templateBinding ->
                            TemplateQuantitationResourceSnapshotCodec.decode(
                                templateBinding.resourceSnapshotJson
                            )
                        }
                    if (frozenBinding != null) {
                        val frozenQuantitation = frozenBinding.quantitation.copy(
                            // 外键被 SET_NULL 表示资源已删除；JSON中的旧ID不能再次当真实外键使用。
                            sourceResourceId = binding.sourceResourceId
                        )
                        require(frozenQuantitation.analyteId == config.analyteId)
                        val frozenMode = frozenQuantitation.method.toGridQuantitationMode()
                        return@map current.copy(
                            templateConfig = config.copy(
                                templateId = currentSnapshot.template.id,
                                analysisModelId = frozenQuantitation.sourceResourceId
                            ),
                            analysisModel = frozenBinding.analysisModel,
                            quantitationMode = frozenMode.code,
                            onsiteSelectedFeature = frozenQuantitation.calibration
                                ?.primaryFeature,
                            onsiteSelectedFunction = frozenQuantitation.calibration
                                ?.fittingFunction,
                            analyteQuantitationSnapshot = frozenQuantitation
                        )
                    }

                    // Room 12及更早模板没有冻结绑定，只能按旧 analysisModelId 兼容读取。
                    // 新保存模板一律走上面的摘要路径，不再依赖资源库实时内容。
                    val selectedBundle = config.analysisModelId?.let { modelId ->
                        runCatching {
                            configurationResourceCoordinator.resolveAnalysisModel(
                                snapshot = currentSnapshot,
                                analyteId = config.analyteId,
                                modelId = modelId
                            )
                        }.getOrNull()
                    }
                    val effectiveBundle = selectedBundle ?: current.toSignalOnlyBundle()
                    val effectiveMode = effectiveBundle.toQuantitationMode()
                    current.copy(
                        templateConfig = config.copy(
                            templateId = currentSnapshot.template.id,
                            analysisModelId = effectiveBundle.model.id
                        ),
                        analysisModel = effectiveBundle,
                        quantitationMode = effectiveMode.code,
                        onsiteSelectedFeature = null,
                        onsiteSelectedFunction = null,
                        onsiteSelectedFeatures = null,
                        onsiteSelectedFunctions = null,
                        analyteQuantitationSnapshot = current.buildResourceQuantitationSnapshot(
                            mode = effectiveMode,
                            bundle = effectiveBundle
                        )
                    )
                }
                val adaptedAssignments = bundle.siteAssignments.map { assignment ->
                    assignment.copy(templateId = currentSnapshot.template.id)
                }
                val adaptedSnapshot = currentSnapshot.copy(
                    analytes = adaptedAnalytes,
                    siteAssignments = adaptedAssignments,
                    sourceTemplateId = template.id,
                    sourceTemplateName = template.templateName,
                    sourceTemplateVersion = template.version
                )
                localizationSession = session.copy(
                    request = session.request.copy(snapshot = adaptedSnapshot)
                )
                configurationSource = GridLayoutConfigurationSource.EXPERIMENT_TEMPLATE
                selectedTemplateId = template.id
                quantitationDrafts = adaptedAnalytes.associate { analyte ->
                    // 已发布实验模板应用后已经拥有冻结方案，无需用户重复逐项确认。
                    analyte.analyte.id to analyte.toQuantitationDraft()
                }
                selectedQuantitationAnalyteId = adaptedAnalytes.firstOrNull()?.analyte?.id
                val restored = layoutDraftStore.update(
                    rows = session.grid.rows,
                    columns = session.grid.columns,
                    drafts = adaptedAssignments.mapNotNull(TemplateSiteAssignment::toLayoutDraft)
                )
                publishPreview(assignments = restored, editingLayout = true)
            }.onSuccess {
                _configurationEvents.emit(GridConfigurationEvent.TemplateApplied)
            }.onFailure {
                _configurationEvents.emit(GridConfigurationEvent.TemplateIncompatible)
            }
        }
    }

    /** 为一个分析物切换定量方式；仅信号和现场拟合不会继续误用之前选中的资源库曲线。 */
    fun setQuantitationMode(analyteId: String, mode: GridAnalyteQuantitationMode) {
        val session = localizationSession ?: return
        val snapshot = session.request.snapshot
        val analytes = snapshot.analytes.map { analyte ->
            if (analyte.analyte.id != analyteId) return@map analyte
            when (mode) {
                GridAnalyteQuantitationMode.ONSITE_AUTO_FIT,
                GridAnalyteQuantitationMode.SIGNAL_ONLY -> analyte.copy(
                    analysisModel = analyte.toSignalOnlyBundle(),
                    templateConfig = analyte.templateConfig.copy(
                        analysisModelId = analyte.toSignalOnlyBundle().model.id
                    ),
                    quantitationMode = mode.code,
                    onsiteSelectedFeature = null,
                    onsiteSelectedFunction = null,
                    onsiteSelectedFeatures = null,
                    onsiteSelectedFunctions = null,
                    analyteQuantitationSnapshot = null
                )

                GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
                GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> analyte.copy(
                    quantitationMode = mode.code,
                    analyteQuantitationSnapshot = null
                )
            }
        }
        replaceSessionSnapshot(snapshot.copy(analytes = analytes))
        val current = quantitationDrafts[analyteId]
            ?: analytes.first { it.analyte.id == analyteId }.toQuantitationDraft()
        quantitationDrafts = quantitationDrafts + (
            analyteId to current.copy(
                mode = mode,
                selectedAnalysisModelId = null,
                selectedFeature = null,
                selectedFunction = null,
                onsiteState = OnsiteCalibrationState.Editing,
                appliedSnapshot = null
            )
        )
        rememberManualQuantitationIfNeeded()
        publishPreview(editingLayout = true)
    }

    /** 从资源库选择已有标准曲线或深度学习模型，并立即冻结完整模型数据包到项目快照。 */
    fun selectAnalysisModel(analyteId: String, modelId: String) {
        if (localizationSession == null) return
        val expectedMode = quantitationDrafts[analyteId]?.mode ?: return
        viewModelScope.launch {
            runCatching {
                val snapshot = requireNotNull(localizationSession).request.snapshot
                val bundle = configurationResourceCoordinator.resolveAnalysisModel(
                    snapshot = snapshot,
                    analyteId = analyteId,
                    modelId = modelId
                )
                require(bundle.model.analyteId == analyteId)
                val actualMode = bundle.toQuantitationMode()
                require(actualMode == expectedMode)
                val analytes = snapshot.analytes.map { analyte ->
                    if (analyte.analyte.id != analyteId) analyte else analyte.copy(
                        templateConfig = analyte.templateConfig.copy(
                            analysisModelId = bundle.model.id,
                            concentrationUnit = bundle.model.concentrationUnit
                        ),
                        analysisModel = bundle,
                        quantitationMode = actualMode.code,
                        onsiteSelectedFeature = null,
                        onsiteSelectedFunction = null,
                        onsiteSelectedFeatures = null,
                        onsiteSelectedFunctions = null,
                        analyteQuantitationSnapshot = null
                    )
                }
                replaceSessionSnapshot(snapshot.copy(analytes = analytes))
                quantitationDrafts = quantitationDrafts + (
                    analyteId to requireNotNull(quantitationDrafts[analyteId]).copy(
                        selectedAnalysisModelId = bundle.model.id,
                        selectedFeature = AnalysisPrimaryFeature.fromCode(bundle.model.primaryFeature),
                        onsiteState = OnsiteCalibrationState.Editing,
                        appliedSnapshot = null
                    )
                )
                // 首次选择内置共享模型会把冻结定义持久化成发布资源；刷新选择器后模板
                // 保存可以引用真实模型外键，页面也不会同时出现临时项和持久化项。
                loadConfigurationResources(requireNotNull(localizationSession))
                rememberManualQuantitationIfNeeded()
                publishPreview(editingLayout = true)
            }.onSuccess {
                _configurationEvents.emit(GridConfigurationEvent.ModelApplied)
            }.onFailure {
                _configurationEvents.emit(GridConfigurationEvent.ModelIncompatible)
            }
        }
    }

    /** 更新现场拟合高级多选；空集合表示继续由后台自动推荐。 */
    fun updateOnsiteAdvanced(
        analyteId: String,
        features: Set<AnalysisPrimaryFeature>,
        functions: Set<FittingFunction>
    ) {
        val session = localizationSession ?: return
        val snapshot = session.request.snapshot
        val analytes = snapshot.analytes.map { analyte ->
            if (analyte.analyte.id != analyteId) analyte else analyte.copy(
                // 单选字段仅用于旧快照兼容；新版真实状态由多选列表保存。
                onsiteSelectedFeature = features.singleOrNull()?.code,
                onsiteSelectedFunction = functions.singleOrNull()?.identifier,
                onsiteSelectedFeatures = features.map(AnalysisPrimaryFeature::code),
                onsiteSelectedFunctions = functions.map(FittingFunction::identifier),
                quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                analyteQuantitationSnapshot = null
            )
        }
        replaceSessionSnapshot(snapshot.copy(analytes = analytes))
        val current = quantitationDrafts[analyteId] ?: return
        quantitationDrafts = quantitationDrafts + (
            analyteId to current.copy(
                selectedFeature = features.singleOrNull(),
                selectedFunction = functions.singleOrNull(),
                selectedFeatures = features,
                selectedFunctions = functions,
                onsiteState = OnsiteCalibrationState.Editing,
                appliedSnapshot = null
            )
        )
        rememberManualQuantitationIfNeeded()
        publishPreview(editingLayout = true)
    }

    /** 使用当前真实孔位布局和标准浓度生成可审阅的现场拟合结果。 */
    fun previewOnsiteFit(analyteId: String) {
        val session = localizationSession ?: return
        val current = quantitationDrafts[analyteId] ?: return
        val requestId = UUID.randomUUID().toString()
        quantitationDrafts = quantitationDrafts + (
            analyteId to current.startOnsiteFitting(requestId)
        )
        publishPreview(editingLayout = true)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                // 开始拟合时只读取一次系统默认策略，并把它完整冻结到结果集。设置页面后续
                // 的修改只影响下一次新拟合，不会让当前弹窗候选在后台悄悄重新排序。
                val policySnapshot = calibrationPolicyPreferences.policyFlow.first()
                coordinator.previewOnsiteCalibration(
                    snapshot = requireNotNull(localizationSession).request.snapshot,
                    quant = session.quant,
                    analyteId = analyteId,
                    policy = policySnapshot
                )
            }.onSuccess { resultSet ->
                val latest = quantitationDrafts[analyteId] ?: return@onSuccess
                val updated = latest.acceptOnsiteFitResult(
                    requestId = requestId,
                    resultSet = resultSet,
                    saveToLibraryByDefault = resultSet.policySnapshot.saveToLibraryByDefault
                ) ?: return@onSuccess
                quantitationDrafts = quantitationDrafts + (
                    analyteId to updated
                )
                rememberManualQuantitationIfNeeded()
                publishPreview(editingLayout = true)
                _configurationEvents.emit(GridConfigurationEvent.FitReady)
            }.onFailure {
                val latest = quantitationDrafts[analyteId] ?: return@onFailure
                val updated = latest.rejectOnsiteFitResult(requestId) ?: return@onFailure
                quantitationDrafts = quantitationDrafts + (
                    analyteId to updated
                )
                publishPreview(editingLayout = true)
                _configurationEvents.emit(GridConfigurationEvent.FitUnavailable)
            }
        }
    }

    /** 在拟合结果阶段切换线性、4PL或5PL候选；不可用函数没有候选ID，不能被选择。 */
    fun selectOnsiteCandidate(analyteId: String, candidateId: String) {
        val current = quantitationDrafts[analyteId] ?: return
        val updated = current.selectOnsiteCandidate(candidateId) ?: return
        quantitationDrafts = quantitationDrafts + (
            analyteId to updated
        )
        publishPreview(editingLayout = true)
    }

    /** 保存开关只决定是否创建可复用资源，不改变本次运行最终冻结的曲线内容。 */
    fun setOnsiteSaveToLibrary(analyteId: String, saveToLibrary: Boolean) {
        val current = quantitationDrafts[analyteId] ?: return
        val updated = current.setOnsiteSaveToLibrary(saveToLibrary) ?: return
        quantitationDrafts = quantitationDrafts + (
            analyteId to updated
        )
        publishPreview(editingLayout = true)
    }

    /** 从结果阶段返回浓度录入；旧候选和已应用快照立即失效，防止修改后继续执行旧参数。 */
    fun editOnsiteCalibration(analyteId: String) {
        val session = localizationSession ?: return
        val current = quantitationDrafts[analyteId] ?: return
        quantitationDrafts = quantitationDrafts + (
            analyteId to current.returnToOnsiteEditing()
        )
        val updatedAnalytes = session.request.snapshot.analytes.map { analyte ->
            if (analyte.analyte.id != analyteId) analyte else analyte.copy(
                analysisModel = analyte.toSignalOnlyBundle(),
                analyteQuantitationSnapshot = null,
                quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code
            )
        }
        replaceSessionSnapshot(session.request.snapshot.copy(analytes = updatedAnalytes))
        rememberManualQuantitationIfNeeded()
        publishPreview(editingLayout = true)
    }

    /**
     * 应用用户已经审阅的候选并立即冻结分析物定量快照。
     *
     * 最终“计算结果”只执行这里冻结的参数，不再读取标准孔重新拟合。资源保存后续与该
     * 快照使用相同候选，失败时不会偷偷换用另一条曲线。
     */
    fun applyOnsiteCalibration(
        analyteId: String,
        lowQualityConfirmed: Boolean = false
    ) {
        val session = localizationSession ?: return
        val current = quantitationDrafts[analyteId] ?: return
        val reviewing = current.onsiteState as? OnsiteCalibrationState.Reviewing ?: return
        val selectedCandidateId = reviewing.selectedCandidateId ?: return
        val selectedCandidate = reviewing.resultSet.candidate(selectedCandidateId) ?: return
        val applicationDecision = reviewing.resultSet.policySnapshot.applicationDecision(
            candidateAccepted = selectedCandidate.accepted,
            userConfirmed = lowQualityConfirmed
        )
        when (applicationDecision) {
            CalibrationApplicationDecision.BLOCK -> {
                _configurationEvents.tryEmit(GridConfigurationEvent.LowQualityCalibrationBlocked)
                return
            }
            CalibrationApplicationDecision.REQUIRE_CONFIRMATION -> {
                _configurationEvents.tryEmit(
                    GridConfigurationEvent.LowQualityCalibrationConfirmationRequired
                )
                return
            }
            CalibrationApplicationDecision.APPLY -> Unit
        }
        if (!selectedCandidate.accepted) {
            // 只显示轻量自定义Toast；完整质量指标继续留在候选卡和运行快照中。
            _configurationEvents.tryEmit(GridConfigurationEvent.LowQualityCalibrationApplied)
        }
        val applying = current.startOnsiteApplying() ?: return
        quantitationDrafts = quantitationDrafts + (
            analyteId to applying
        )
        publishPreview(editingLayout = true)

        viewModelScope.launch {
            runCatching {
                var sourceResourceId: String? = null
                var sourceBundle: AnalysisModelBundle? = null
                if (reviewing.saveToLibrary) {
                    val analyteSnapshot = requireNotNull(localizationSession).request.snapshot
                        .analytes.first { it.analyte.id == analyteId }
                    val generatedName = generateOnsiteCurveName(analyteSnapshot.analyte.name)
                    sourceBundle = configurationResourceCoordinator.createAndPublishOnsiteCurve(
                        snapshot = requireNotNull(localizationSession).request.snapshot,
                        analyteSnapshot = analyteSnapshot,
                        resultSet = reviewing.resultSet,
                        selectedCandidateId = selectedCandidateId,
                        name = generatedName,
                        runId = session.request.runId
                    )
                    sourceResourceId = sourceBundle.model.id
                }
                var updatedSnapshot = coordinator.applyOnsiteCalibrationSelection(
                    snapshot = requireNotNull(localizationSession).request.snapshot,
                    resultSet = reviewing.resultSet,
                    selectedCandidateId = selectedCandidateId,
                    runId = session.request.runId,
                    sourceResourceId = sourceResourceId
                )
                val persistedSourceBundle = sourceBundle
                if (persistedSourceBundle != null) {
                    updatedSnapshot = updatedSnapshot.copy(
                        analytes = updatedSnapshot.analytes.map { analyte ->
                            if (analyte.analyte.id == analyteId) {
                                // 保存到曲线库会生成新的模型ID，必须同步模板配置、模型数据包
                                // 和运行定量快照；只替换 analysisModel 会被预检判定为关系错配。
                                analyte.withPersistedOnsiteCurveResource(persistedSourceBundle)
                            } else {
                                analyte
                            }
                        }
                    )
                }
                val applied = requireNotNull(
                    updatedSnapshot.analytes.first { it.analyte.id == analyteId }
                        .analyteQuantitationSnapshot
                )
                val latest = requireNotNull(quantitationDrafts[analyteId])
                val completed = latest.completeOnsiteApplying(
                    selectedCandidateId = selectedCandidateId,
                    snapshot = applied
                ) ?: return@runCatching
                // 只有仍处于同一 Applying 状态时才发布更新后的项目快照，防止迟到的
                // 资源保存回调覆盖用户已经重新编辑的现场标定。
                replaceSessionSnapshot(updatedSnapshot)
                quantitationDrafts = quantitationDrafts + (
                    analyteId to completed
                )
                advanceToNextIncompleteAnalyte(analyteId)
                rememberManualQuantitationIfNeeded()
                loadConfigurationResources(requireNotNull(localizationSession))
                publishPreview(editingLayout = true)
            }.onFailure {
                val latest = quantitationDrafts[analyteId] ?: return@onFailure
                val restored = latest.restoreOnsiteReviewingAfterApplyFailure(
                    selectedCandidateId
                ) ?: return@onFailure
                quantitationDrafts = quantitationDrafts + (
                    analyteId to restored
                )
                publishPreview(editingLayout = true)
                _configurationEvents.emit(
                    if (reviewing.saveToLibrary) GridConfigurationEvent.CurveSaveFailed
                    else GridConfigurationEvent.OperationFailed
                )
            }
        }
    }

    /** 当前分析物完成后按项目顺序自动跳转到下一项；全部完成时保持当前项供用户复核。 */
    private fun advanceToNextIncompleteAnalyte(completedAnalyteId: String) {
        selectedQuantitationAnalyteId = quantitationDrafts.values
            .sortedByAnalyteOrder(
                localizationSession?.request?.snapshot?.analytes?.map { it.analyte.id }.orEmpty()
            )
            .firstOrNull { !it.isConfigurationComplete() }
            ?.analyteId
            ?: completedAnalyteId
    }

    /** 自动生成不阻塞主流程的曲线名称；用户之后可在曲线库中直接编辑。 */
    private fun generateOnsiteCurveName(analyteName: String): String {
        val localTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date())
        return context.getString(R.string.grid_onsite_curve_auto_name, analyteName, localTime)
    }

    /**
     * 将当前完整布局和逐分析物定量方案保存为实验模板。
     *
     * 现场拟合分析物会先把当前预览固化为标准曲线；已有曲线和深度学习只保存模型关联，
     * 不复制 PTL 二进制。仅信号分析物保持空模型关联，应用模板时仍明确恢复为仅信号。
     */
    fun saveCurrentConfigurationAsTemplate(requestedName: String) {
        val normalizedName = requestedName.trim()
        if (localizationSession == null) return
        if (normalizedName.isEmpty() || savingReusableResource) {
            _configurationEvents.tryEmit(GridConfigurationEvent.TemplateSaveFailed)
            return
        }
        viewModelScope.launch {
            savingReusableResource = true
            try {
                if (configurationResourceCoordinator.templateNameExists(normalizedName)) {
                    _configurationEvents.emit(GridConfigurationEvent.TemplateNameConflict)
                    return@launch
                }
                var snapshot = requireNotNull(localizationSession).request.snapshot
                if (
                    snapshot.siteAssignments.isEmpty() ||
                    quantitationDrafts.values.any { !it.isConfigurationComplete() }
                ) {
                    _configurationEvents.emit(GridConfigurationEvent.TemplateSaveIncomplete)
                    return@launch
                }

                // 模板保存完整冻结摘要，不再强迫“仅用于本次运行”的现场曲线进入曲线库。
                // 已有资源仍保留 sourceResourceId 追溯；资源以后被编辑或删除，模板继续执行
                // 此刻的 AnalysisModelBundle 与 AnalyteQuantitationSnapshot。
                val preparedAnalytes = snapshot.analytes.map { analyteSnapshot ->
                    val draft = quantitationDrafts[analyteSnapshot.analyte.id]
                        ?: error("定量方案缺失")
                    val applied = draft.appliedSnapshot
                        ?: analyteSnapshot.analyteQuantitationSnapshot
                        ?: run {
                            _configurationEvents.emit(GridConfigurationEvent.TemplateSaveIncomplete)
                            return@launch
                        }
                    require(applied.analyteId == analyteSnapshot.analyte.id)
                    analyteSnapshot.copy(
                        quantitationMode = draft.mode.code,
                        analyteQuantitationSnapshot = applied
                    )
                }
                snapshot = snapshot.copy(analytes = preparedAnalytes)
                configurationResourceCoordinator.savePublishedTemplate(
                    name = normalizedName,
                    snapshot = snapshot
                )

                // 当前项目继续使用已经固化的现场曲线，但配置来源仍保持“手动配置”；
                // 保存模板是复用动作，不应把用户无提示地切换到模板控制模式。
                replaceSessionSnapshot(snapshot)
                quantitationDrafts = snapshot.analytes.associate { analyte ->
                    val previous = quantitationDrafts[analyte.analyte.id]
                    analyte.analyte.id to analyte.toQuantitationDraft().copy(
                        onsiteState = previous?.onsiteState ?: OnsiteCalibrationState.Editing,
                        appliedSnapshot = previous?.appliedSnapshot
                            ?: analyte.analyteQuantitationSnapshot
                    )
                }
                loadConfigurationResources(requireNotNull(localizationSession))
                rememberManualQuantitationIfNeeded()
                publishPreview(editingLayout = true)
                _configurationEvents.emit(GridConfigurationEvent.TemplateSaved(normalizedName))
            } catch (_: RuntimeException) {
                _configurationEvents.emit(GridConfigurationEvent.TemplateSaveFailed)
            } finally {
                savingReusableResource = false
            }
        }
    }

    /** 将已持久化模型冻结回当前项目分析物，并同步页面定量方式。 */
    private fun applySavedCurveToSession(analyteId: String, bundle: AnalysisModelBundle) {
        val snapshot = requireNotNull(localizationSession).request.snapshot
        val analytes = snapshot.analytes.map { analyte ->
            if (analyte.analyte.id == analyteId) analyte.withReusableModel(bundle) else analyte
        }
        replaceSessionSnapshot(snapshot.copy(analytes = analytes))
        quantitationDrafts = quantitationDrafts + (
            analyteId to analytes.first { it.analyte.id == analyteId }
                .toQuantitationDraft()
        )
    }

    /** 将资源库模型完整冻结到项目快照；模型类型决定最终定量方式。 */
    private fun TemplateProjectAnalyteSnapshot.withReusableModel(
        bundle: AnalysisModelBundle
    ): TemplateProjectAnalyteSnapshot = copy(
        templateConfig = templateConfig.copy(
            analysisModelId = bundle.model.id,
            // 选择曲线资源只改变定量工具，不改变用户在新建项目时确定的预期量程。
            concentrationUnit = bundle.model.concentrationUnit
        ),
        analysisModel = bundle,
        quantitationMode = bundle.toQuantitationMode().code,
        onsiteSelectedFeature = null,
        onsiteSelectedFunction = null,
        onsiteSelectedFeatures = null,
        onsiteSelectedFunctions = null
    )

    /**
     * 在 ViewModel 的最新草稿上增量执行一次画笔操作。
     *
     * 这是多笔绘制的唯一可信写入入口：即使页面连续快速提交两笔，第二笔也会读取
     * [GridLayoutDraftStore] 中已经保存的第一笔，而不是依赖可能尚未重组完成的 UI 快照。
     */
    fun paintLayoutDraft(intent: GridLayoutPaintIntent): GridPaintMergeResult {
        val session = localizationSession
            ?: return GridPaintMergeResult(emptyMap(), protectedSiteCount = 0)
        val columns = session.grid.columns
        val existing = layoutDraftStore.current().associateBy { draft ->
            draft.rowIndex * columns + draft.columnIndex
        }
        val result = mergePaintedAssignments(
            existing = existing,
            paintedSiteIndices = intent.paintedSiteIndices,
            rows = session.grid.rows,
            columns = columns,
            analyteId = intent.analyteId,
            role = intent.role,
            standardConcentration = intent.standardConcentration,
            sampleId = intent.sampleId,
            clearMode = intent.clearMode
        )
        updateLayoutDraft(result.assignments.values.toList())
        return result
    }

    /**
     * 将可视化布局草稿转换为冻结模板位点，并继续执行模态处理与保存。
     * 坐标去重和边界校验在进入协调器前完成，防止 UI 状态异常制造重复物理位点。
     */
    fun finalizeLayout(drafts: List<GridLayoutAssignmentDraft>) {
        val session = localizationSession ?: run {
            _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            return
        }
        if (running) return
        val readiness = evaluateArrayLayoutReadiness(
            assignments = drafts,
            quantitationDrafts = quantitationDrafts.values,
            totalSiteCount = session.grid.rows * session.grid.columns
        )
        if (!readiness.canStart) {
            // Compose 已经禁用“计算结果”，这里继续保留业务层门控，避免未来入口绕过确认流程。
            _configurationEvents.tryEmit(GridConfigurationEvent.OperationFailed)
            return
        }

        // 点击分析时先同步保存草稿，再进入耗时处理。即使协调器随后被空白位、参考位或
        // 分析物完整性门控阻止，返回编辑仍然能拿到本次点击时的完整布局。
        val normalized = layoutDraftStore.update(
            rows = session.grid.rows,
            columns = session.grid.columns,
            drafts = drafts
        )
        val templateId = session.request.snapshot.template.id
        val assignments = normalized.toTemplateAssignments(
            templateId = templateId,
            idPrefix = "${session.request.runId}-layout"
        )
        val finalizedSnapshot = session.request.snapshot.copy(siteAssignments = assignments)

        viewModelScope.launch {
            running = true
            try {
                val outcome = coordinator.finalizeLocalized(session, finalizedSnapshot)
                _uiState.value = when (outcome) {
                    GridDetectionOutcome.LegacyPlateRequired -> GridDetectionUiState.Plate96Localization
                    is GridDetectionOutcome.Blocked -> GridDetectionUiState.Blocked(outcome.reasons)
                    is GridDetectionOutcome.RetakeRequired -> {
                        GridDetectionUiState.RetakeRequired(outcome.runId)
                    }
                    is GridDetectionOutcome.Completed -> {
                        // 项目保存最新可继续编辑的布局；历史结果仍优先读取运行级冻结快照。
                        projectRepository.updateProject(
                            session.request.project.copy(
                                templateSnapshotJson = TemplateProjectSnapshotCodec.encode(
                                    outcome.effectiveSnapshot
                                ),
                                lastRunTimestamp = Date()
                            )
                        )
                        GridDetectionUiState.Completed(
                            runId = outcome.runId,
                            measurementCount = outcome.measurementCount,
                            signalOnlyAnalyteIds = outcome.signalOnlyAnalyteIds,
                            frameQcIssueCount = outcome.frameQcIssueCount
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } catch (_: LinkageError) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } finally {
                running = false
            }
        }
    }

    private fun localize(projectId: String, imageUri: String) {
        viewModelScope.launch {
            running = true
            localizationSession = null
            _uiState.value = GridDetectionUiState.ResolvingProject
            try {
                val project = projectRepository.getProjectById(projectId)
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.PROJECT_NOT_FOUND
                        )
                        return@launch
                    }
                val snapshotJson = project.templateSnapshotJson
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID
                        )
                        return@launch
                    }
                val snapshot = runCatching { TemplateProjectSnapshotCodec.decode(snapshotJson) }
                    .getOrElse {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID
                        )
                        return@launch
                    }
                val carrierType = CarrierType.fromCode(snapshot.carrierProfile.carrierType)
                if (carrierType == null) {
                    _uiState.value = GridDetectionUiState.Blocked(
                        setOf(GridDetectionBlockReason.UNSUPPORTED_CARRIER)
                    )
                    return@launch
                }
                if (GridDetectionRouteResolver.resolve(carrierType) == GridCarrierRoute.PLATE96) {
                    _uiState.value = GridDetectionUiState.Plate96Localization
                    return@launch
                }
                val bitmap = loadBitmap(imageUri)
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.IMAGE_LOAD_FAILED
                        )
                        return@launch
                    }
                val outcome = coordinator.localize(
                    GridDetectionRequest(
                        project = project,
                        snapshot = snapshot,
                        endpointBitmap = bitmap,
                        endpointPath = imageUri,
                        operatorId = project.userId,
                        onStageChanged = { stage ->
                            _uiState.value = GridDetectionUiState.Processing(stage)
                        }
                    )
                )
                _uiState.value = when (outcome) {
                    GridLocalizationOutcome.LegacyPlateRequired -> GridDetectionUiState.Plate96Localization
                    is GridLocalizationOutcome.Blocked -> GridDetectionUiState.Blocked(outcome.reasons)
                    is GridLocalizationOutcome.Ready -> initializeLocalizationSession(
                        session = outcome.session,
                        openLayoutImmediately = false
                    )
                }
            } catch (_: Exception) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } catch (_: LinkageError) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } finally {
                running = false
            }
        }
    }

    /**
     * 微流控与96孔板进入布局页前共享的唯一初始化入口。
     *
     * 资源列表、模板来源、定量草稿和多笔画笔都在这里一次性建立，避免两种载体分别维护
     * 状态后出现“模板能用但现场曲线状态不同步”的分叉行为。
     */
    private suspend fun initializeLocalizationSession(
        session: GridLocalizationSession,
        openLayoutImmediately: Boolean
    ): GridDetectionUiState.LocalizationReady {
        localizationSession = session
        loadConfigurationResources(session)
        configurationSource = if (session.request.snapshot.sourceTemplateId != null) {
            GridLayoutConfigurationSource.EXPERIMENT_TEMPLATE
        } else {
            GridLayoutConfigurationSource.MANUAL
        }
        selectedTemplateId = session.request.snapshot.sourceTemplateId
        quantitationDrafts = session.request.snapshot.analytes.associate { analyte ->
            analyte.analyte.id to analyte.toQuantitationDraft()
        }
        selectedQuantitationAnalyteId = session.request.snapshot.analytes.firstOrNull()?.analyte?.id
        val frozenAssignments = session.snapshotAssignmentDrafts()
        val recoverableAssignments = layoutDraftStore.beginSession(
            rows = session.grid.rows,
            columns = session.grid.columns,
            frozenAssignments = frozenAssignments
        )
        if (configurationSource == GridLayoutConfigurationSource.MANUAL) {
            manualAssignmentsBackup = recoverableAssignments
            manualQuantitationBackup = quantitationDrafts
            manualSnapshotBackup = session.request.snapshot
        }
        return GridDetectionUiState.LocalizationReady(
            preview = session.toPreview(recoverableAssignments),
            editingLayout = openLayoutImmediately
        )
    }

    private fun GridLocalizationSession.toPreview(
        assignments: List<GridLayoutAssignmentDraft>
    ): GridLocalizationPreview {
        return GridLocalizationPreview(
            runId = request.runId,
            originalImageUri = request.endpointPath,
            detectionMode = request.snapshot.template.detectionMode.orEmpty(),
            rows = grid.rows,
            columns = grid.columns,
            siteCount = grid.sites.size,
            observedRatio = grid.geometry.observedRatio,
            meanConfidence = grid.geometry.meanConfidence,
            frameQcIssueCount = frameQcIssueCount,
            rectifiedImagePath = processingEvidence.firstOrNull {
                it.role == CaptureRole.PROCESS_RECTIFIED ||
                    it.role == CaptureRole.PROCESS_ORIENTATION_NORMALIZED
            }?.path,
            // 兼容旧内存会话的最后兜底值；新运行的每个位点都从 unitSegmentation 取紧致区域。
            cropHalfSizePx = (quant.pitchPx * 0.46).coerceAtLeast(quant.roiRadiusPx),
            sites = grid.sites.map { site ->
                GridLocalizationSitePreview(
                    siteIndex = site.siteIndex,
                    rowIndex = site.key.rowIndex,
                    columnIndex = site.key.columnIndex,
                    rectifiedX = site.rectified.x,
                    rectifiedY = site.rectified.y,
                    cropRegion = quant.unitSegmentation?.regions?.getOrNull(site.siteIndex)
                )
            },
            analytes = request.snapshot.analytes
                .sortedBy { it.templateConfig.displayOrder }
                .map { analyteSnapshot ->
                    GridLocalizationAnalyte(
                        id = analyteSnapshot.analyte.id,
                        name = analyteSnapshot.analyte.name,
                        concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
                        maxConcentration = analyteSnapshot.templateConfig.reliableRangeMax
                            ?: analyteSnapshot.analysisModel.model.reliableRangeMax
                    )
                },
            evidence = processingEvidence.map { evidence ->
                GridLocalizationEvidence(role = evidence.role, path = evidence.path)
            },
            initialAssignments = assignments,
            configurationSource = configurationSource,
            selectedTemplateId = selectedTemplateId,
            availableTemplates = availableTemplates,
            availableModels = availableModels,
            quantitationDrafts = quantitationDrafts.values.sortedByAnalyteOrder(
                request.snapshot.analytes.map { it.analyte.id }
            ),
            selectedQuantitationAnalyteId = selectedQuantitationAnalyteId
                ?.takeIf { selectedId -> request.snapshot.analytes.any { it.analyte.id == selectedId } }
                ?: request.snapshot.analytes.firstOrNull()?.analyte?.id,
            presentation = presentation
        )
    }

    /** 每次资源选择或现场拟合变化后，从当前内存会话重新生成单一可信页面快照。 */
    private fun publishPreview(
        assignments: List<GridLayoutAssignmentDraft> = layoutDraftStore.current(),
        editingLayout: Boolean
    ) {
        val session = localizationSession ?: return
        _uiState.value = GridDetectionUiState.LocalizationReady(
            preview = session.toPreview(assignments),
            editingLayout = editingLayout
        )
    }

    /** 替换内存会话中的科学快照；定位、裁切和光度数据保持完全不变。 */
    private fun replaceSessionSnapshot(snapshot: com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot) {
        val session = localizationSession ?: return
        localizationSession = session.copy(request = session.request.copy(snapshot = snapshot))
    }

    /** 手动配置状态要独立备份，用户试用模板后可以无损切回。 */
    private fun rememberManualQuantitationIfNeeded() {
        if (configurationSource == GridLayoutConfigurationSource.MANUAL) {
            manualQuantitationBackup = quantitationDrafts
            manualSnapshotBackup = localizationSession?.request?.snapshot
        }
    }

    /**
     * 只加载与当前模态、规格和分析物集合兼容的模板；模型摘要同样只保留发布态资源。
     * 实际应用时仍会重新读取完整 bundle，防止页面停留期间资源被修改。
     */
    private suspend fun loadConfigurationResources(session: GridLocalizationSession) {
        val resources = configurationResourceCoordinator.loadCompatibleResources(
            snapshot = session.request.snapshot,
            rows = session.grid.rows,
            columns = session.grid.columns
        )
        availableModels = resources.models
        availableTemplates = resources.templates
    }

    /** 将项目冻结快照转换成可编辑草稿；旧模板中的未知角色会被安全忽略。 */
    private fun GridLocalizationSession.snapshotAssignmentDrafts(): List<GridLayoutAssignmentDraft> {
        return request.snapshot.siteAssignments.mapNotNull(TemplateSiteAssignment::toLayoutDraft)
    }

    /** 支持 content/file URI 和历史项目保存的普通绝对路径。 */
    private suspend fun loadBitmap(rawUri: String): Bitmap? = withContext(Dispatchers.IO) {
        val decoded = runCatching { URLDecoder.decode(rawUri, Charsets.UTF_8.name()) }
            .getOrDefault(rawUri)
        val uri = Uri.parse(decoded)
        when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
            null, "" -> BitmapFactory.decodeFile(File(decoded).absolutePath)
            else -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }
}

/** 将模板位点安全转换为页面草稿；未知角色不会进入可编辑布局。 */
private fun TemplateSiteAssignment.toLayoutDraft(): GridLayoutAssignmentDraft? {
    val role = TemplateSiteRole.fromCode(roleType) ?: return null
    return GridLayoutAssignmentDraft(
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        analyteId = analyteId,
        role = role,
        standardConcentration = standardConcentration,
        sampleId = defaultSampleSlot
    )
}

/** 将页面草稿转换为冻结模板位点，预览、保存模板和最终分析共用同一条转换规则。 */
private fun List<GridLayoutAssignmentDraft>.toTemplateAssignments(
    templateId: String,
    idPrefix: String
): List<TemplateSiteAssignment> = map { draft ->
    val isReferenceRole = draft.role in setOf(
        TemplateSiteRole.BLANK,
        TemplateSiteRole.REFERENCE
    )
    TemplateSiteAssignment(
        id = "$idPrefix-${draft.rowIndex}-${draft.columnIndex}",
        templateId = templateId,
        rowIndex = draft.rowIndex,
        columnIndex = draft.columnIndex,
        analyteId = draft.analyteId,
        roleType = draft.role.code,
        standardConcentration = draft.standardConcentration,
        defaultSampleSlot = draft.sampleId?.trim()?.takeIf(String::isNotEmpty)
            ?: defaultArraySampleSlot(draft),
        referenceScope = if (isReferenceRole) {
            TemplateReferenceScope.ANALYTE.code
        } else {
            null
        },
        enabled = draft.role != TemplateSiteRole.DISABLED
    )
}

/**
 * 样本孔未输入样本编号时，用其物理孔号冻结一个可读且稳定的默认编号。
 *
 * 这样新运行的分析表、验证录入和导出不再出现含义模糊的“未分配”；如果多个孔属于同一
 * 样本，用户仍可在布局页输入相同样本编号，将它们明确归为重复孔组。
 */
internal fun defaultArraySampleSlot(draft: GridLayoutAssignmentDraft): String? {
    if (draft.role != TemplateSiteRole.SAMPLE) return null
    var rowNumber = draft.rowIndex + 1
    val rowLabel = StringBuilder()
    while (rowNumber > 0) {
        rowNumber -= 1
        rowLabel.append(('A'.code + rowNumber % 26).toChar())
        rowNumber /= 26
    }
    return "${rowLabel.reverse()}${draft.columnIndex + 1}"
}

/** 根据资源模型类型生成布局页定量方式；损坏或空参数模型安全降为仅信号。 */
private fun AnalysisModelBundle.toQuantitationMode(): GridAnalyteQuantitationMode {
    return when (AnalysisModelType.fromCode(model.modelType)) {
        AnalysisModelType.DEEP_LEARNING -> if (deepLearning != null) {
            GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL
        } else {
            GridAnalyteQuantitationMode.SIGNAL_ONLY
        }

        AnalysisModelType.STANDARD_CURVE -> {
            val parameters = standardCurve?.parametersJson.orEmpty().trim()
            if (standardCurve != null && parameters.isNotEmpty() && parameters != "{}") {
                GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE
            } else {
                GridAnalyteQuantitationMode.SIGNAL_ONLY
            }
        }

        null -> GridAnalyteQuantitationMode.SIGNAL_ONLY
    }
}

/**
 * 构造显式仅信号模型快照。
 *
 * 保留分析物、单位、可靠范围、处理器和主特征，只清空可执行曲线/深度学习定义；因此
 * 后续切回现场拟合仍有完整光度契约，但量化器绝不会误用用户先前选择的资源模型。
 */
private fun TemplateProjectAnalyteSnapshot.toSignalOnlyBundle(): AnalysisModelBundle {
    val signalModel = analysisModel.model.copy(
        name = "signal-only",
        modelType = AnalysisModelType.STANDARD_CURVE.code
    )
    return AnalysisModelBundle(
        model = signalModel,
        standardCurve = StandardCurveDefinition(
            analysisModelId = signalModel.id,
            fittingFunction = FittingFunction.LINEAR.identifier,
            parametersJson = "{}",
            monotonicDirection = "AUTO"
        )
    )
}

/** 从冻结分析物快照恢复 ViewModel 定量草稿。 */
private fun TemplateProjectAnalyteSnapshot.toQuantitationDraft(): GridAnalyteQuantitationDraft {
    val mode = resolvedGridQuantitationMode()
    val restoredFeatures = onsiteSelectedFeatures.orEmpty()
        .mapNotNull(AnalysisPrimaryFeature::fromCode)
        .toCollection(linkedSetOf())
        .ifEmpty {
            onsiteSelectedFeature?.let(AnalysisPrimaryFeature::fromCode)?.let(::setOf).orEmpty()
        }
    val restoredFunctions = onsiteSelectedFunctions.orEmpty()
        .mapNotNull(FittingFunction::fromIdentifier)
        .filterTo(linkedSetOf()) { it != FittingFunction.INTERPOLATION }
        .ifEmpty {
            onsiteSelectedFunction?.let(FittingFunction::fromIdentifier)?.let(::setOf).orEmpty()
        }
    return GridAnalyteQuantitationDraft(
        analyteId = analyte.id,
        mode = mode,
        selectedAnalysisModelId = analysisModel.model.id.takeIf {
            mode in setOf(
                GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
                GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL
            )
        },
        selectedFeature = AnalysisPrimaryFeature.fromCode(
            onsiteSelectedFeature ?: analysisModel.model.primaryFeature
        ),
        selectedFunction = onsiteSelectedFunction?.let(FittingFunction::fromIdentifier),
        selectedFeatures = restoredFeatures,
        selectedFunctions = restoredFunctions,
        appliedSnapshot = analyteQuantitationSnapshot,
        onsiteState = if (
            mode == GridAnalyteQuantitationMode.ONSITE_AUTO_FIT &&
            analyteQuantitationSnapshot?.calibration != null
        ) {
            // 旧会话恢复时没有完整候选结果集，保留完成快照即可；重新打开现场弹窗会要求
            // 用户按当前标准孔重新生成候选，不会伪造一个无法审阅的结果集。
            OnsiteCalibrationState.Editing
        } else {
            OnsiteCalibrationState.Editing
        }
    )
}

/** 将模板中的资源模型转换为当前项目可执行的分析物快照。 */
private fun TemplateProjectAnalyteSnapshot.buildResourceQuantitationSnapshot(
    mode: GridAnalyteQuantitationMode,
    bundle: AnalysisModelBundle
): AnalyteQuantitationSnapshot {
    val method = when (mode) {
        GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE ->
            AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE
        GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL ->
            AnalyteQuantitationMethod.DEEP_LEARNING_MODEL
        GridAnalyteQuantitationMode.SIGNAL_ONLY -> AnalyteQuantitationMethod.SIGNAL_ONLY
        GridAnalyteQuantitationMode.ONSITE_AUTO_FIT ->
            AnalyteQuantitationMethod.ONSITE_CALIBRATION
    }
    return AnalyteQuantitationSnapshot(
        analyteId = analyte.id,
        method = method,
        concentrationUnit = bundle.model.concentrationUnit,
        sourceResourceId = bundle.model.id.takeUnless {
            mode == GridAnalyteQuantitationMode.SIGNAL_ONLY
        },
        processorVersion = bundle.model.processorVersion,
        inputFingerprint = listOf(
            analyte.id,
            method.name,
            bundle.model.id,
            bundle.model.version,
            bundle.model.processorVersion
        ).joinToString(":")
    )
}

/** 模板冻结方法恢复为布局页使用的四种短模式，不依赖实时资源类型推断。 */
private fun AnalyteQuantitationMethod.toGridQuantitationMode(): GridAnalyteQuantitationMode =
    when (this) {
        AnalyteQuantitationMethod.ONSITE_CALIBRATION ->
            GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
        AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE ->
            GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE
        AnalyteQuantitationMethod.DEEP_LEARNING_MODEL ->
            GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL
        AnalyteQuantitationMethod.SIGNAL_ONLY -> GridAnalyteQuantitationMode.SIGNAL_ONLY
    }

/** 按项目分析物顺序稳定展示定量卡片，避免 Map 顺序随页面重建变化。 */
private fun Collection<GridAnalyteQuantitationDraft>.sortedByAnalyteOrder(
    analyteOrder: List<String>
): List<GridAnalyteQuantitationDraft> {
    val order = analyteOrder.withIndex().associate { it.value to it.index }
    return sortedBy { order[it.analyteId] ?: Int.MAX_VALUE }
}
