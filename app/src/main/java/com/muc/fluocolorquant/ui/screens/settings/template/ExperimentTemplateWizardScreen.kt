@file:OptIn(ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.settings.template

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSectionTitle
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateWizardEvent
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateWizardOperation
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateWizardUiState
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateWizardViewModel

/** 模板向导的稳定语义标签，页面测试不依赖具体排版像素。 */
object ExperimentTemplateWizardTestTags {
    const val STEP_PROGRESS = "template_wizard_step_progress"
    const val BASIC_FORM = "template_wizard_basic_form"
    const val FLUORESCENCE_CHANNEL_FIELD = "template_wizard_fluorescence_channel_field"
    const val NEXT_BUTTON = "template_wizard_next_button"
    const val PUBLISH_BUTTON = "template_wizard_publish_button"
}

/**
 * 无状态页面使用的动作集合。
 *
 * 将大量回调集中为一个不可变对象，可以避免页面函数参数无限膨胀，也让 Compose 测试只
 * 覆盖关心的动作。默认空实现仅供预览和测试，正式入口会全部绑定到 ViewModel。
 */
data class ExperimentTemplateWizardActions(
    val onNavigateBack: () -> Unit = {},
    val onBasicInformationChange: (String, String, String) -> Unit = { _, _, _ -> },
    val onSelectDetectionMode: (DetectionModality) -> Unit = {},
    val onSelectInputProtocol: (InputProtocol) -> Unit = {},
    val onSelectReadoutLayout: (ReadoutLayout) -> Unit = {},
    val onSelectCarrier: (String) -> Unit = {},
    val onAddAnalyte: (String) -> Unit = {},
    val onRemoveAnalyte: (String) -> Unit = {},
    val onUpdateAnalyte: (String, TemplateAnalyteDraft) -> Unit = { _, _ -> },
    val onSelectAnalysisModel: (String, String?) -> Unit = { _, _ -> },
    val onToggleLayoutSite: (TemplateSiteCoordinate) -> Unit = {},
    val onSelectRectanglePoint: (TemplateSiteCoordinate) -> Unit = {},
    val onSelectLayoutRow: (Int) -> Unit = {},
    val onSelectLayoutColumn: (Int) -> Unit = {},
    val onSelectAllLayoutSites: () -> Unit = {},
    val onClearLayoutSelection: () -> Unit = {},
    val onApplyToSelectedSites: (TemplateSiteDraft) -> Unit = {},
    val onBatchDraftChange: (TemplateSiteDraft) -> Unit = {},
    val onRectangleSelectionEnabledChange: (Boolean) -> Unit = {},
    val onPreviousStep: () -> Unit = {},
    val onNextStep: () -> Unit = {},
    val onPublish: () -> Unit = {}
)

/**
 * 实验模板向导正式页面入口。
 *
 * 页面只负责资源化文本、一次性 Toast 和导航；模板校验、资源兼容过滤、保存与发布事务
 * 均由 [ExperimentTemplateWizardViewModel] 处理，保证旋转屏幕或页面重组不改变科学状态。
 */
@Composable
fun ExperimentTemplateWizardScreen(
    navController: NavController,
    templateId: String?,
    sourceTemplateId: String? = null,
    viewModel: ExperimentTemplateWizardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    var batchDraft by remember { mutableStateOf(TemplateSiteDraft()) }
    var rectangleSelectionEnabled by rememberSaveable { mutableStateOf(false) }

    // “复制并调整”必须先创建下一草稿版本；已发布模板绝不能通过 updateDraft 原地覆盖。
    // 两个参数互斥，由 sourceTemplateId 触发的版本化复制具有更高优先级。
    LaunchedEffect(templateId, sourceTemplateId) {
        when {
            sourceTemplateId != null -> viewModel.createNextVersion(sourceTemplateId)
            templateId != null -> viewModel.loadTemplate(templateId)
        }
    }

    // 分析物被删除后同步清理批量编辑器中的悬空引用，防止用户把无效 ID 写回位点。
    LaunchedEffect(state.draft.analytes) {
        val validAnalyteIds = state.draft.analytes.mapTo(hashSetOf()) { it.analyteId }
        if (batchDraft.analyteId != null && batchDraft.analyteId !in validAnalyteIds) {
            batchDraft = batchDraft.copy(analyteId = null)
        }
    }

    LaunchedEffect(viewModel, context, toastManager) {
        viewModel.events.collect { event ->
            when (event) {
                is ExperimentTemplateWizardEvent.ValidationFailed -> {
                    val firstError = event.errors.firstOrNull()
                    if (firstError != null) {
                        toastManager.showToast(
                            context.getString(firstError.messageResource()),
                            ToastType.WARNING
                        )
                    }
                }
                is ExperimentTemplateWizardEvent.DraftSaved -> {
                    toastManager.showToast(
                        context.getString(R.string.template_wizard_draft_saved),
                        ToastType.SUCCESS
                    )
                }
                is ExperimentTemplateWizardEvent.Published -> {
                    toastManager.showToast(
                        context.getString(R.string.template_wizard_published),
                        ToastType.SUCCESS
                    )
                    navController.navigateUp()
                }
                is ExperimentTemplateWizardEvent.VersionCreated -> {
                    toastManager.showToast(
                        context.getString(R.string.template_wizard_version_created),
                        ToastType.INFO
                    )
                }
                is ExperimentTemplateWizardEvent.Archived -> {
                    toastManager.showToast(
                        context.getString(R.string.template_wizard_archived),
                        ToastType.SUCCESS
                    )
                }
                is ExperimentTemplateWizardEvent.OperationFailed -> {
                    toastManager.showToast(
                        context.getString(event.operation.messageResource()),
                        ToastType.ERROR
                    )
                }
            }
        }
    }

    ExperimentTemplateWizardContent(
        state = state,
        batchDraft = batchDraft,
        rectangleSelectionEnabled = rectangleSelectionEnabled,
        actions = ExperimentTemplateWizardActions(
            onNavigateBack = { navController.navigateUp() },
            onBasicInformationChange = viewModel::updateBasicInformation,
            onSelectDetectionMode = viewModel::selectDetectionMode,
            onSelectInputProtocol = viewModel::selectInputProtocol,
            onSelectReadoutLayout = { layout ->
                viewModel.updateDraft(state.draft.copy(readoutLayout = layout.code))
            },
            onSelectCarrier = viewModel::selectCarrier,
            onAddAnalyte = viewModel::addAnalyte,
            onRemoveAnalyte = viewModel::removeAnalyte,
            onUpdateAnalyte = viewModel::updateAnalyte,
            onSelectAnalysisModel = viewModel::selectAnalysisModel,
            onToggleLayoutSite = viewModel::toggleLayoutSite,
            onSelectRectanglePoint = viewModel::selectRectanglePoint,
            onSelectLayoutRow = viewModel::selectLayoutRow,
            onSelectLayoutColumn = viewModel::selectLayoutColumn,
            onSelectAllLayoutSites = viewModel::selectAllLayoutSites,
            onClearLayoutSelection = viewModel::clearLayoutSelection,
            onApplyToSelectedSites = viewModel::applyToSelectedSites,
            onBatchDraftChange = { batchDraft = it },
            onRectangleSelectionEnabledChange = { enabled ->
                rectangleSelectionEnabled = enabled
                if (!enabled) viewModel.clearLayoutSelection()
            },
            onPreviousStep = viewModel::previousStep,
            onNextStep = viewModel::nextStep,
            onPublish = viewModel::publish
        )
    )
}

/**
 * 五步向导的无状态主体。
 *
 * 视觉方向延续项目现有蓝灰 Material 3 风格，同时采用“科研仪器控制台”信息层级：步骤、
 * 科学资源、阵列编辑和发布门控始终使用一致的卡片边界与状态色，不加入无关装饰动画。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentTemplateWizardContent(
    state: ExperimentTemplateWizardUiState,
    batchDraft: TemplateSiteDraft,
    rectangleSelectionEnabled: Boolean,
    actions: ExperimentTemplateWizardActions
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (state.editingTemplateId == null) {
                                R.string.template_wizard_create_title
                            } else {
                                R.string.template_wizard_edit_title
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            WizardBottomBar(
                state = state,
                onPrevious = actions.onPreviousStep,
                onNext = actions.onNextStep,
                onPublish = actions.onPublish
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                WizardStepProgress(currentStep = state.currentStep)
            }
            item {
                when (state.currentStep) {
                    TemplateWizardStep.BASIC -> BasicInformationStep(
                        draft = state.draft,
                        onChange = actions.onBasicInformationChange
                    )
                    TemplateWizardStep.DETECTION_AND_RESOURCES -> {
                        DetectionAndResourcesStep(state = state, actions = actions)
                    }
                    TemplateWizardStep.ANALYTES -> AnalytesStep(
                        state = state,
                        actions = actions
                    )
                    TemplateWizardStep.LAYOUT -> LayoutStep(
                        state = state,
                        batchDraft = batchDraft,
                        rectangleSelectionEnabled = rectangleSelectionEnabled,
                        actions = actions
                    )
                    TemplateWizardStep.QC_AND_REVIEW -> ReviewStep(state = state)
                }
            }
        }
    }
}

/**
 * 模板向导进度概览。
 *
 * 原五个胶囊容易被误认为可以直接点击，并且窄屏会只露出半个步骤名称。这里改为当前
 * 步骤图标、位置文本和线性进度，不制造虚假的点击暗示，也不会发生横向截断。
 */
@Composable
private fun WizardStepProgress(currentStep: TemplateWizardStep) {
    val stepCount = TemplateWizardStep.entries.size
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ExperimentTemplateWizardTestTags.STEP_PROGRESS),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = templateWizardStepIcon(currentStep),
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = templateWizardStepLabel(currentStep),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.template_wizard_step_fraction,
                        currentStep.ordinal + 1,
                        stepCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            LinearProgressIndicator(
                progress = { (currentStep.ordinal + 1f) / stepCount },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun BasicInformationStep(
    draft: TemplateWizardDraft,
    onChange: (String, String, String) -> Unit
) {
    StepCard(
        title = stringResource(R.string.template_wizard_basic_title),
        icon = Icons.Default.Science,
        modifier = Modifier.testTag(ExperimentTemplateWizardTestTags.BASIC_FORM)
    ) {
        OutlinedTextField(
            value = draft.templateName,
            onValueChange = { onChange(it, draft.purpose, draft.versionNote) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.template_wizard_name_label)) },
            leadingIcon = {
                Icon(Icons.Default.Science, contentDescription = null)
            },
            singleLine = true
        )
        OutlinedTextField(
            value = draft.purpose,
            onValueChange = { onChange(draft.templateName, it, draft.versionNote) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.template_wizard_purpose_label)) },
            leadingIcon = {
                Icon(Icons.Default.Biotech, contentDescription = null)
            },
            minLines = 2,
            maxLines = 3
        )
        OutlinedTextField(
            value = draft.versionNote,
            onValueChange = { onChange(draft.templateName, draft.purpose, it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.template_wizard_version_note_label)) },
            leadingIcon = {
                Icon(Icons.Default.Description, contentDescription = null)
            },
            minLines = 1,
            maxLines = 3
        )
    }
}

@Composable
private fun DetectionAndResourcesStep(
    state: ExperimentTemplateWizardUiState,
    actions: ExperimentTemplateWizardActions
) {
    var showMoreCarriers by rememberSaveable { mutableStateOf(false) }
    val selectedMode = DetectionModality.fromCode(state.draft.detectionMode)
    val selectedProtocol = InputProtocol.fromCode(state.draft.inputProtocol)
    val selectedReadout = ReadoutLayout.fromCode(state.draft.readoutLayout)
    val recommendedCarriers = state.activeCarriers.filter {
        (it.rows == 10 && it.columns == 10) || (it.rows == 15 && it.columns == 15)
    }
    val otherCarriers = state.activeCarriers - recommendedCarriers.toSet()

    StepCard(
        title = stringResource(R.string.template_wizard_resources_title),
        icon = Icons.Default.Memory
    ) {
        SectionLabel(stringResource(R.string.template_wizard_modality_title))
        // 三种检测模态是固定且高频的顶层选择，三等分展示可避免“光谱”只露出半张卡片。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetectionModality.entries.forEach { mode ->
                ScientificChoiceCard(
                    modifier = Modifier.weight(1f),
                    selected = selectedMode == mode,
                    title = detectionModeLabel(mode),
                    icon = detectionModeIcon(mode),
                    onClick = { actions.onSelectDetectionMode(mode) }
                )
            }
        }

        SectionLabel(stringResource(R.string.template_wizard_protocol_title))
        ChoiceChipRow(
            values = state.draft.allowedProtocols.mapNotNull(InputProtocol::fromCode),
            selected = selectedProtocol,
            label = { protocol -> inputProtocolLabel(protocol) },
            onSelect = actions.onSelectInputProtocol
        )

        SectionLabel(stringResource(R.string.template_wizard_readout_title))
        val readoutOptions = if (selectedMode == DetectionModality.SPECTRUM) {
            listOf(
                ReadoutLayout.SPECTRAL_TRACKS,
                ReadoutLayout.SINGLE_REGION,
                ReadoutLayout.PER_SITE_SPECTRUM
            )
        } else {
            listOf(ReadoutLayout.GRID_SITES)
        }
        ChoiceChipRow(
            values = readoutOptions,
            selected = selectedReadout,
            label = { layout -> readoutLayoutLabel(layout) },
            onSelect = actions.onSelectReadoutLayout
        )

        HorizontalDivider()
        SectionLabel(stringResource(R.string.template_wizard_carrier_title))
        if (state.activeCarriers.isEmpty()) {
            InlineNotice(
                text = stringResource(R.string.template_wizard_no_carriers),
                warning = true
            )
        } else {
            if (recommendedCarriers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.template_wizard_recommended_carriers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                CarrierChoiceList(
                    carriers = recommendedCarriers,
                    selectedId = state.draft.carrierProfileId,
                    onSelect = actions.onSelectCarrier
                )
            }
            if (otherCarriers.isNotEmpty()) {
                TextButton(onClick = { showMoreCarriers = !showMoreCarriers }) {
                    Icon(
                        imageVector = if (showMoreCarriers) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (showMoreCarriers) {
                                R.string.template_wizard_hide_more_carriers
                            } else {
                                R.string.template_wizard_more_carriers
                            }
                        )
                    )
                }
                if (showMoreCarriers) {
                    CarrierChoiceList(
                        carriers = otherCarriers,
                        selectedId = state.draft.carrierProfileId,
                        onSelect = actions.onSelectCarrier
                    )
                }
            }
        }
        InlineNotice(
            text = stringResource(R.string.template_wizard_device_auto_recorded),
            warning = false
        )
    }
}

@Composable
private fun AnalytesStep(
    state: ExperimentTemplateWizardUiState,
    actions: ExperimentTemplateWizardActions
) {
    var showAnalytePicker by rememberSaveable { mutableStateOf(false) }
    val configuredIds = state.draft.analytes.mapTo(hashSetOf()) { it.analyteId }
    val availableAnalytes = state.analytes.filterNot { it.id in configuredIds }

    StepCard(
        title = stringResource(R.string.template_wizard_analytes_title),
        icon = Icons.Default.Biotech
    ) {
        SectionLabel(stringResource(R.string.template_wizard_available_analytes))
        if (state.analytes.isEmpty()) {
            InlineNotice(
                text = stringResource(R.string.template_wizard_no_analyte_library),
                warning = true
            )
        } else if (availableAnalytes.isNotEmpty()) {
            // 分析物库可能包含十余项，主页面只保留一个入口，避免选项按钮淹没已配置内容。
            OutlinedButton(
                onClick = { showAnalytePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.template_wizard_choose_analyte))
            }
        }

        state.draft.analytes.forEach { analyteDraft ->
            val analyte = state.analytes.find { it.id == analyteDraft.analyteId }
            AnalyteConfigurationCard(
                analyteName = analyte?.name ?: analyteDraft.analyteId,
                draft = analyteDraft,
                detectionMode = DetectionModality.fromCode(state.draft.detectionMode),
                reagents = state.reagents.filter { it.analyteId == analyteDraft.analyteId },
                models = state.compatibleModelsFor(analyteDraft.analyteId),
                concentrationUnits = state.concentrationUnits,
                defaultConcentrationUnit = state.defaultConcentrationUnit,
                onUpdate = { replacement ->
                    actions.onUpdateAnalyte(analyteDraft.analyteId, replacement)
                },
                onSelectModel = { modelId ->
                    actions.onSelectAnalysisModel(analyteDraft.analyteId, modelId)
                },
                onRemove = { actions.onRemoveAnalyte(analyteDraft.analyteId) }
            )
        }
    }

    if (showAnalytePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.template_wizard_available_analytes),
            options = availableAnalytes.map { analyte ->
                ScientificPickerOption(
                    id = analyte.id,
                    title = analyte.name,
                    icon = Icons.Default.Biotech
                )
            },
            selectedId = null,
            onSelect = actions.onAddAnalyte,
            onDismiss = { showAnalytePicker = false }
        )
    }
}

@Composable
private fun LayoutStep(
    state: ExperimentTemplateWizardUiState,
    batchDraft: TemplateSiteDraft,
    rectangleSelectionEnabled: Boolean,
    actions: ExperimentTemplateWizardActions
) {
    StepCard(
        title = stringResource(R.string.template_wizard_layout_title),
        icon = Icons.Default.GridView
    ) {
        TemplateArrayLayoutEditor(
            layout = state.draft.layout,
            analytes = state.analytes.filter { analyte ->
                state.draft.analytes.any { it.analyteId == analyte.id }
            },
            batchDraft = batchDraft,
            rectangleSelectionEnabled = rectangleSelectionEnabled,
            onRectangleSelectionEnabledChange = actions.onRectangleSelectionEnabledChange,
            onToggleSite = actions.onToggleLayoutSite,
            onSelectRectanglePoint = actions.onSelectRectanglePoint,
            onSelectRow = actions.onSelectLayoutRow,
            onSelectColumn = actions.onSelectLayoutColumn,
            onSelectAll = actions.onSelectAllLayoutSites,
            onClearSelection = actions.onClearLayoutSelection,
            onBatchDraftChange = actions.onBatchDraftChange,
            onApplyToSelection = { actions.onApplyToSelectedSites(batchDraft) }
        )
    }
}

@Composable
private fun ReviewStep(state: ExperimentTemplateWizardUiState) {
    val errors = state.publicationErrorsForUi()
    val ready = errors.isEmpty()
    val carrierName = state.selectedCarrier?.name
        ?: stringResource(R.string.template_wizard_not_selected)

    StepCard(
        title = stringResource(R.string.template_wizard_review_title),
        icon = if (ready) Icons.Default.CheckCircle else Icons.Default.ErrorOutline
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = if (ready) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Column {
                    Text(
                        text = stringResource(
                            if (ready) R.string.template_wizard_ready_title
                            else R.string.template_wizard_blocked_title
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            if (ready) R.string.template_wizard_ready_desc
                            else R.string.template_wizard_blocked_desc
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        ReviewMetricRow(
            label = stringResource(R.string.template_wizard_review_carrier),
            value = carrierName
        )
        ReviewMetricRow(
            label = stringResource(R.string.template_wizard_review_analytes),
            value = stringResource(
                R.string.template_wizard_review_count,
                state.draft.analytes.size
            )
        )
        ReviewMetricRow(
            label = stringResource(R.string.template_wizard_review_sites),
            value = "${state.draft.layout.assignments.size}/${state.draft.layout.siteCount}"
        )

        HorizontalDivider()
        QcCheckRow(
            passed = TemplateWizardError.LAYOUT_NOT_FULLY_ASSIGNED !in errors &&
                TemplateWizardError.LAYOUT_SIZE_INVALID !in errors,
            text = stringResource(R.string.template_wizard_qc_full_assignment)
        )
        QcCheckRow(
            passed = TemplateWizardError.SAMPLE_SITE_REQUIRED !in errors &&
                TemplateWizardError.ANALYTE_BLANK_REQUIRED !in errors,
            text = stringResource(R.string.template_wizard_qc_blank)
        )
        QcCheckRow(
            passed = true,
            text = stringResource(R.string.template_wizard_qc_optional_quantification)
        )

        if (errors.isNotEmpty()) {
            HorizontalDivider()
            errors.forEach { error ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(error.messageResource()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 通用步骤卡只保留图标与短标题。
 *
 * 页面本身是操作界面，不默认重复展示说明文档式段落；只有校验错误、空状态和用户主动
 * 展开的帮助内容才承担解释职责，从而让实验员更快进入参数配置。
 */
@Composable
private fun StepCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ScientificSectionTitle(title = title, icon = icon)
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ScientificChoiceCard(
    modifier: Modifier = Modifier,
    selected: Boolean,
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun <T> ChoiceChipRow(
    values: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) }
            )
        }
    }
}

@Composable
private fun CarrierChoiceList(
    carriers: List<CarrierProfile>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        carriers.forEach { carrier ->
            ResourceChoiceRow(
                selected = carrier.id == selectedId,
                title = carrier.name,
                subtitle = stringResource(
                    R.string.template_wizard_carrier_meta,
                    carrier.rows,
                    carrier.columns,
                    carrier.version
                ),
                icon = Icons.Default.GridView,
                onClick = { onSelect(carrier.id) }
            )
        }
    }
}

@Composable
private fun ResourceChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AnalyteConfigurationCard(
    analyteName: String,
    draft: TemplateAnalyteDraft,
    detectionMode: DetectionModality?,
    reagents: List<Reagent>,
    models: List<AnalysisModel>,
    concentrationUnits: List<String>,
    defaultConcentrationUnit: String,
    onUpdate: (TemplateAnalyteDraft) -> Unit,
    onSelectModel: (String?) -> Unit,
    onRemove: () -> Unit
) {
    val antigenReagents = reagents.filter(Reagent::isAntigen)
    val antibodyReagents = reagents.filter(Reagent::isAntibody)
    var showUnitPicker by rememberSaveable { mutableStateOf(false) }
    val unitOptions = remember(concentrationUnits, draft.concentrationUnit) {
        (concentrationUnits + listOf(draft.concentrationUnit))
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.template_wizard_analyte_card, analyteName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            ReagentChipGroup(
                title = stringResource(R.string.template_wizard_antigen_label),
                reagents = antigenReagents,
                selectedId = draft.reagentAntigenId,
                onSelect = { onUpdate(draft.copy(reagentAntigenId = it)) }
            )
            ReagentChipGroup(
                title = stringResource(R.string.template_wizard_antibody_label),
                reagents = antibodyReagents,
                selectedId = draft.reagentAntibodyId,
                onSelect = { onUpdate(draft.copy(reagentAntibodyId = it)) }
            )

            SectionLabel(stringResource(R.string.template_wizard_model_optional_label))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = draft.analysisModelId == null,
                    onClick = { onSelectModel(null) },
                    label = { Text(stringResource(R.string.template_wizard_signal_only)) }
                )
                if (models.isNotEmpty()) {
                    models.forEach { model ->
                        FilterChip(
                            selected = draft.analysisModelId == model.id,
                            onClick = { onSelectModel(model.id) },
                            label = { Text(model.name) }
                        )
                    }
                }
            }
            if (models.isEmpty()) {
                InlineNotice(
                    text = stringResource(R.string.template_wizard_no_compatible_model_signal_only),
                    warning = false
                )
            }

            if (detectionMode == DetectionModality.FLUORESCENCE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ExperimentTemplateWizardTestTags.FLUORESCENCE_CHANNEL_FIELD),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionLabel(stringResource(R.string.template_wizard_fluorescence_channel_label))
                    Text(
                        text = stringResource(R.string.template_wizard_fluorescence_channel_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FluorescenceChannel.entries.forEach { channel ->
                            FilterChip(
                                selected = draft.fluorescenceChannel == channel,
                                onClick = { onUpdate(draft.copy(fluorescenceChannel = channel)) },
                                label = { Text(fluorescenceChannelLabel(channel)) }
                            )
                        }
                    }
                }
            }

            if (draft.analysisModelId != null) {
                // 定量模型已选中后才展示单位与可靠范围。单位沿用检测设置的同一列表，
                // 数值框拒绝字母、重复小数点和负值，避免非法文本进入模板快照。
                OutlinedButton(
                    onClick = { showUnitPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = draft.concentrationUnit.ifBlank {
                            defaultConcentrationUnit.ifBlank {
                                stringResource(R.string.template_wizard_select_unit)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft.reliableRangeMinInput,
                        onValueChange = { input ->
                            if (input.isNonNegativeDecimalInput()) {
                                onUpdate(draft.copy(reliableRangeMinInput = input))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.template_wizard_range_min_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = draft.reliableRangeMaxInput,
                        onValueChange = { input ->
                            if (input.isNonNegativeDecimalInput()) {
                                onUpdate(draft.copy(reliableRangeMaxInput = input))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.template_wizard_range_max_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }
        }
    }

    if (showUnitPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.template_wizard_unit_label),
            options = unitOptions.map { unit ->
                ScientificPickerOption(id = unit, title = unit)
            },
            selectedId = draft.concentrationUnit,
            onSelect = { unit -> onUpdate(draft.copy(concentrationUnit = unit)) },
            onDismiss = { showUnitPicker = false }
        )
    }
}

@Composable
private fun ReagentChipGroup(
    title: String,
    reagents: List<Reagent>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.none)) }
        )
        reagents.forEach { reagent ->
            FilterChip(
                selected = selectedId == reagent.id,
                onClick = { onSelect(reagent.id) },
                label = { Text(reagent.reagentName) }
            )
        }
    }
}

@Composable
private fun InlineNotice(text: String, warning: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (warning) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ReviewMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun QcCheckRow(passed: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (passed) Color(0xFF00897B) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WizardBottomBar(
    state: ExperimentTemplateWizardUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPublish: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.currentStep != TemplateWizardStep.BASIC) {
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = !state.isSaving && !state.isPublishing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.previous))
                }
            }

            if (state.currentStep == TemplateWizardStep.QC_AND_REVIEW) {
                Button(
                    onClick = onPublish,
                    enabled = !state.isPublishing && !state.isSaving,
                    modifier = Modifier
                        .weight(if (state.currentStep == TemplateWizardStep.BASIC) 1f else 1.35f)
                        .testTag(ExperimentTemplateWizardTestTags.PUBLISH_BUTTON)
                ) {
                    if (state.isPublishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.template_wizard_publish))
                }
            } else {
                Button(
                    onClick = onNext,
                    enabled = !state.isPublishing && !state.isSaving,
                    modifier = Modifier
                        .weight(if (state.currentStep == TemplateWizardStep.BASIC) 1f else 1.35f)
                        .testTag(ExperimentTemplateWizardTestTags.NEXT_BUTTON)
                ) {
                    Text(stringResource(R.string.next))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/** UI 复核与最终保存使用同一套可用性校验；设备和定量模型均不再作为强制门槛。 */
private fun ExperimentTemplateWizardUiState.publicationErrorsForUi(): Set<TemplateWizardError> {
    return draft.validateForPublication()
}

private fun Reagent.isAntigen(): Boolean {
    return reagentType.equals("antigen", ignoreCase = true) || reagentType.contains("抗原")
}

private fun Reagent.isAntibody(): Boolean {
    return reagentType.equals("antibody", ignoreCase = true) || reagentType.contains("抗体")
}

/**
 * 可靠范围只接受非负十进制输入，并允许用户编辑过程中暂时留空或只输入一个小数点。
 * 最终的上下限大小关系仍由 [TemplateWizardDraft.validateForDraft] 统一校验。
 */
private fun String.isNonNegativeDecimalInput(): Boolean {
    return isEmpty() || this == "." || matches(Regex("^\\d*(?:\\.\\d*)?$"))
}

@Composable
private fun templateWizardStepLabel(step: TemplateWizardStep): String = when (step) {
    TemplateWizardStep.BASIC -> stringResource(R.string.template_wizard_step_basic)
    TemplateWizardStep.DETECTION_AND_RESOURCES -> {
        stringResource(R.string.template_wizard_step_resources)
    }
    TemplateWizardStep.ANALYTES -> stringResource(R.string.template_wizard_step_analytes)
    TemplateWizardStep.LAYOUT -> stringResource(R.string.template_wizard_step_layout)
    TemplateWizardStep.QC_AND_REVIEW -> stringResource(R.string.template_wizard_step_review)
}

/** 每一步使用稳定图标表达当前配置对象，避免把进度状态设计成可点击标签。 */
private fun templateWizardStepIcon(step: TemplateWizardStep): ImageVector = when (step) {
    TemplateWizardStep.BASIC -> Icons.Default.Science
    TemplateWizardStep.DETECTION_AND_RESOURCES -> Icons.Default.Memory
    TemplateWizardStep.ANALYTES -> Icons.Default.Biotech
    TemplateWizardStep.LAYOUT -> Icons.Default.GridView
    TemplateWizardStep.QC_AND_REVIEW -> Icons.Default.CheckCircle
}

@Composable
private fun detectionModeLabel(mode: DetectionModality): String = when (mode) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.analysis_model_mode_colorimetric)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.analysis_model_mode_fluorescence)
    DetectionModality.SPECTRUM -> stringResource(R.string.analysis_model_mode_spectrum)
}

private fun detectionModeIcon(mode: DetectionModality): ImageVector = when (mode) {
    DetectionModality.COLORIMETRIC -> Icons.Default.ColorLens
    DetectionModality.FLUORESCENCE -> Icons.Default.Flare
    DetectionModality.SPECTRUM -> Icons.Default.ShowChart
}

@Composable
private fun inputProtocolLabel(protocol: InputProtocol): String = when (protocol) {
    InputProtocol.ENDPOINT_ONLY -> stringResource(R.string.analysis_model_protocol_endpoint)
    InputProtocol.SINGLE_SPECTRUM_ANALYSIS -> {
        stringResource(R.string.analysis_model_protocol_single_spectrum)
    }
    InputProtocol.LSPR_PAIRED_QUANTIFICATION -> {
        stringResource(R.string.analysis_model_protocol_lspr_pair)
    }
}

@Composable
private fun readoutLayoutLabel(layout: ReadoutLayout): String = when (layout) {
    ReadoutLayout.GRID_SITES -> stringResource(R.string.template_wizard_readout_grid)
    ReadoutLayout.SPECTRAL_TRACKS -> stringResource(R.string.template_wizard_readout_tracks)
    ReadoutLayout.SINGLE_REGION -> {
        stringResource(R.string.template_wizard_readout_single_region)
    }
    ReadoutLayout.PER_SITE_SPECTRUM -> {
        stringResource(R.string.template_wizard_readout_per_site_spectrum)
    }
}

@StringRes
private fun TemplateWizardError.messageResource(): Int = when (this) {
    TemplateWizardError.NAME_REQUIRED -> R.string.template_wizard_validation_name
    TemplateWizardError.DETECTION_MODE_REQUIRED -> R.string.template_wizard_validation_mode
    TemplateWizardError.READOUT_LAYOUT_REQUIRED -> R.string.template_wizard_validation_readout
    TemplateWizardError.INPUT_PROTOCOL_INCOMPATIBLE -> R.string.template_wizard_validation_protocol
    TemplateWizardError.CARRIER_REQUIRED -> R.string.template_wizard_validation_carrier
    TemplateWizardError.ACQUISITION_PROFILE_REQUIRED -> R.string.template_wizard_validation_device
    TemplateWizardError.ANALYTE_REQUIRED -> R.string.template_wizard_validation_analyte
    TemplateWizardError.DUPLICATE_ANALYTE -> R.string.template_wizard_validation_duplicate_analyte
    TemplateWizardError.ANALYSIS_MODEL_REQUIRED -> R.string.template_wizard_validation_model
    TemplateWizardError.FLUORESCENCE_CHANNEL_REQUIRED -> {
        R.string.template_wizard_validation_fluorescence_channel
    }
    TemplateWizardError.CONCENTRATION_UNIT_REQUIRED -> R.string.template_wizard_validation_unit
    TemplateWizardError.RELIABLE_RANGE_INVALID -> R.string.template_wizard_validation_range
    TemplateWizardError.LAYOUT_SIZE_INVALID -> R.string.template_wizard_validation_layout_size
    TemplateWizardError.LAYOUT_OUT_OF_BOUNDS -> R.string.template_wizard_validation_layout_bounds
    TemplateWizardError.LAYOUT_NOT_FULLY_ASSIGNED -> R.string.template_wizard_validation_layout_full
    TemplateWizardError.SITE_ANALYTE_REQUIRED -> R.string.template_wizard_validation_site_analyte
    TemplateWizardError.SITE_ANALYTE_UNKNOWN -> R.string.template_wizard_validation_site_unknown
    TemplateWizardError.STANDARD_CONCENTRATION_INVALID -> {
        R.string.template_wizard_validation_standard
    }
    TemplateWizardError.SAMPLE_SITE_REQUIRED -> R.string.template_wizard_validation_sample
    TemplateWizardError.ANALYTE_BLANK_REQUIRED -> R.string.template_wizard_validation_blank
}

/** 荧光通道名称只描述相机科学读出通道，不使用伪彩色名称。 */
@Composable
private fun fluorescenceChannelLabel(channel: FluorescenceChannel): String = when (channel) {
    FluorescenceChannel.RED -> stringResource(R.string.template_wizard_fluorescence_channel_red)
    FluorescenceChannel.GREEN -> stringResource(R.string.template_wizard_fluorescence_channel_green)
    FluorescenceChannel.BLUE -> stringResource(R.string.template_wizard_fluorescence_channel_blue)
    FluorescenceChannel.GRAY -> stringResource(R.string.template_wizard_fluorescence_channel_gray)
}

@StringRes
private fun ExperimentTemplateWizardOperation.messageResource(): Int = when (this) {
    ExperimentTemplateWizardOperation.LOAD -> R.string.template_wizard_load_failed
    ExperimentTemplateWizardOperation.SAVE -> R.string.template_wizard_save_failed
    ExperimentTemplateWizardOperation.PUBLISH -> R.string.template_wizard_publish_failed
    ExperimentTemplateWizardOperation.CREATE_VERSION -> R.string.template_wizard_version_failed
    ExperimentTemplateWizardOperation.ARCHIVE -> R.string.template_wizard_archive_failed
}
