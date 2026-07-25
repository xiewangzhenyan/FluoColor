package com.muc.fluocolorquant.ui.screens.settings.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelEditorMode
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelEvent
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelUiState
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelViewModel
import kotlinx.coroutines.flow.Flow

/** Compose 测试只依赖这些稳定语义标签，不与具体排版像素耦合。 */
object AnalysisModelTestTags {
    const val SUMMARY = "analysis_model_summary"
    const val CREATE_BUTTON = "analysis_model_create_button"
    const val LEGACY_BUTTON = "analysis_model_legacy_button"
}

/**
 * 统一分析模型管理页面入口。
 *
 * 旧曲线库通过独立兼容路由打开；当前页面只消费不可变状态和稳定事件，不直接访问 DAO。
 */
@Composable
fun AnalysisModelManagementScreen(
    navController: NavController,
    onOpenLegacyLibrary: () -> Unit,
    viewModel: AnalysisModelViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    AnalysisModelEventEffect(viewModel.events)

    AnalysisModelManagementContent(
        state = state,
        onNavigateBack = { navController.navigateUp() },
        onOpenLegacyLibrary = onOpenLegacyLibrary,
        onSelectType = viewModel::selectType,
        onSelectStatus = viewModel::selectStatus,
        onCreate = viewModel::openCreateEditor,
        onEditDraft = viewModel::openEditDraft,
        onCreateNextVersion = viewModel::createNextVersion,
        onPublish = viewModel::publish,
        onArchive = viewModel::archive,
        onDraftChange = viewModel::updateDraft,
        onDismissEditor = viewModel::dismissEditor,
        onSaveDraft = viewModel::saveDraft
    )
}

/**
 * 可独立预览和测试的无状态页面内容。
 *
 * 视觉采用“科研仪器控制台”方向：蓝灰信息底、明确状态色和紧凑元数据，优先保证实验
 * 人员能快速判断模型是否可用，而不是增加装饰性动效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisModelManagementContent(
    state: AnalysisModelUiState,
    onNavigateBack: () -> Unit,
    onOpenLegacyLibrary: () -> Unit,
    onSelectType: (AnalysisModelType?) -> Unit,
    onSelectStatus: (AnalysisModelStatusFilter) -> Unit,
    onCreate: () -> Unit,
    onEditDraft: (String) -> Unit,
    onCreateNextVersion: (String) -> Unit,
    onPublish: (String) -> Unit,
    onArchive: (String) -> Unit,
    onDraftChange: (AnalysisModelDraft) -> Unit,
    onDismissEditor: () -> Unit,
    onSaveDraft: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.analysis_model_library_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    // 旧曲线库属于兼容入口，不再占用列表首屏；保留为明确的历史图标入口。
                    IconButton(
                        onClick = onOpenLegacyLibrary,
                        modifier = Modifier.testTag(AnalysisModelTestTags.LEGACY_BUTTON)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(
                                R.string.analysis_model_legacy_library
                            )
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.testTag(AnalysisModelTestTags.CREATE_BUTTON),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.analysis_model_add)) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AnalysisModelSummary(state)
            }
            item {
                AnalysisModelFilterPanel(
                    selectedType = state.selectedType,
                    selectedStatus = state.selectedStatus,
                    onSelectType = onSelectType,
                    onSelectStatus = onSelectStatus
                )
            }

            if (state.visibleModels.isEmpty()) {
                item { AnalysisModelEmptyState() }
            } else {
                items(state.visibleModels, key = AnalysisModel::id) { model ->
                    AnalysisModelCard(
                        model = model,
                        analyte = state.analytes.find { it.id == model.analyteId },
                        onEditDraft = { onEditDraft(model.id) },
                        onCreateNextVersion = { onCreateNextVersion(model.id) },
                        onPublish = { onPublish(model.id) },
                        onArchive = { onArchive(model.id) }
                    )
                }
            }
        }
    }

    if (state.isEditorVisible) {
        AnalysisModelEditorSheet(
            state = state,
            onDraftChange = onDraftChange,
            onDismiss = onDismissEditor,
            onSave = onSaveDraft
        )
    }
}

/**
 * 紧凑模型统计条。
 *
 * TopAppBar 已经给出页面名称，所以这里只展示真正帮助筛选和判断资源状态的四个数字，
 * 避免再次出现标题、副标题和大面积装饰背景。
 */
@Composable
private fun AnalysisModelSummary(state: AnalysisModelUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AnalysisModelTestTags.SUMMARY),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryMetric(
                label = stringResource(R.string.analysis_model_summary_total),
                value = state.models.size,
                icon = Icons.Default.Science,
                modifier = Modifier.weight(1f)
            )
            SummaryMetric(
                label = stringResource(R.string.analysis_model_summary_published),
                value = state.publishedCount,
                icon = Icons.Default.CheckCircle,
                modifier = Modifier.weight(1f)
            )
            SummaryMetric(
                label = stringResource(R.string.analysis_model_summary_curves),
                value = state.standardCurveCount,
                icon = Icons.Default.Tune,
                modifier = Modifier.weight(1f)
            )
            SummaryMetric(
                label = stringResource(R.string.analysis_model_summary_intelligent),
                value = state.deepLearningCount,
                icon = Icons.Default.Memory,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 紧凑双筛选器。
 *
 * 原页面把两组全部选项永久铺开，既占据半屏，又让最后一个生命周期选项在窄屏被截断。
 * 现在首屏只显示两个当前条件，点击后使用全宽底部面板选择，所有选项均可完整阅读。
 */
@Composable
private fun AnalysisModelFilterPanel(
    selectedType: AnalysisModelType?,
    selectedStatus: AnalysisModelStatusFilter,
    onSelectType: (AnalysisModelType?) -> Unit,
    onSelectStatus: (AnalysisModelStatusFilter) -> Unit
) {
    var showTypePicker by rememberSaveable { mutableStateOf(false) }
    var showStatusPicker by rememberSaveable { mutableStateOf(false) }
    val allLabel = stringResource(R.string.analysis_model_filter_all)
    val curveLabel = stringResource(R.string.analysis_model_type_standard_curve)
    val intelligentLabel = stringResource(R.string.analysis_model_type_deep_learning)
    val selectedTypeLabel = when (selectedType) {
        AnalysisModelType.STANDARD_CURVE -> curveLabel
        AnalysisModelType.DEEP_LEARNING -> intelligentLabel
        null -> allLabel
    }
    // 集合构造器的 lambda 不是 Composable 上下文，因此所有资源文本先在这里解析。
    val statusLabels = mapOf(
        AnalysisModelStatusFilter.ALL to allLabel,
        AnalysisModelStatusFilter.DRAFT to stringResource(
            R.string.analysis_model_status_draft
        ),
        AnalysisModelStatusFilter.PUBLISHED to stringResource(
            R.string.analysis_model_status_published
        ),
        AnalysisModelStatusFilter.ARCHIVED to stringResource(
            R.string.analysis_model_status_archived
        ),
        AnalysisModelStatusFilter.LEGACY to stringResource(
            R.string.analysis_model_status_legacy
        )
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompactFilterField(
            label = stringResource(R.string.analysis_model_filter_type),
            value = selectedTypeLabel,
            icon = Icons.Default.Tune,
            onClick = { showTypePicker = true },
            modifier = Modifier.weight(1f)
        )
        CompactFilterField(
            label = stringResource(R.string.analysis_model_filter_status),
            value = statusLabels.getValue(selectedStatus),
            icon = Icons.Default.FilterAlt,
            onClick = { showStatusPicker = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showTypePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.analysis_model_filter_type),
            options = listOf(
                ScientificPickerOption(id = FILTER_ALL_ID, title = allLabel, icon = Icons.Default.Tune),
                ScientificPickerOption(
                    id = AnalysisModelType.STANDARD_CURVE.code,
                    title = curveLabel,
                    icon = Icons.Default.Tune
                ),
                ScientificPickerOption(
                    id = AnalysisModelType.DEEP_LEARNING.code,
                    title = intelligentLabel,
                    icon = Icons.Default.Memory
                )
            ),
            selectedId = selectedType?.code ?: FILTER_ALL_ID,
            onSelect = { selectedId ->
                onSelectType(
                    if (selectedId == FILTER_ALL_ID) null
                    else AnalysisModelType.entries.first { it.code == selectedId }
                )
            },
            onDismiss = { showTypePicker = false }
        )
    }

    if (showStatusPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.analysis_model_filter_status),
            options = AnalysisModelStatusFilter.entries.map { status ->
                ScientificPickerOption(
                    id = status.name,
                    title = statusLabels.getValue(status),
                    icon = Icons.Default.FilterAlt
                )
            },
            selectedId = selectedStatus.name,
            onSelect = { selectedId ->
                onSelectStatus(AnalysisModelStatusFilter.valueOf(selectedId))
            },
            onDismiss = { showStatusPicker = false }
        )
    }
}

private const val FILTER_ALL_ID = "analysis-model-filter-all"

/** 单个紧凑筛选字段，明确展示筛选维度和当前值。 */
@Composable
private fun CompactFilterField(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnalysisModelEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Analytics,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.analysis_model_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.analysis_model_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 模型卡将“科学身份—处理器—可靠范围—生命周期”按阅读顺序展示。 */
@Composable
private fun AnalysisModelCard(
    model: AnalysisModel,
    analyte: Analyte?,
    onEditDraft: () -> Unit,
    onCreateNextVersion: () -> Unit,
    onPublish: () -> Unit,
    onArchive: () -> Unit
) {
    val isDraft = model.status == AnalysisModelLifecycleStatus.DRAFT.code
    val canCreateVersion = model.status != AnalysisModelLifecycleStatus.DRAFT.code
    val canArchive = model.status != AnalysisModelLifecycleStatus.ARCHIVED.code

    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MetadataPill(stringResource(R.string.analysis_model_version, model.version))
                        MetadataPill(modelTypeLabel(model.modelType))
                    }
                }
                StatusBadge(model.status)
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MetadataPill(
                    analyte?.name ?: stringResource(R.string.analysis_model_unknown_analyte)
                )
                MetadataPill(detectionModeLabel(model.detectionMode))
                MetadataPill(inputProtocolLabel(model.inputProtocol))
                MetadataPill(primaryFeatureLabel(model.primaryFeature))
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.analysis_model_processor,
                        model.processorName,
                        model.processorVersion
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.analysis_model_range,
                        model.reliableRangeMin.toCompactText(),
                        model.reliableRangeMax.toCompactText(),
                        model.concentrationUnit
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End
            ) {
                if (isDraft) {
                    ModelAction(
                        label = stringResource(R.string.analysis_model_edit),
                        icon = Icons.Default.Edit,
                        onClick = onEditDraft
                    )
                    ModelAction(
                        label = stringResource(R.string.analysis_model_publish),
                        icon = Icons.Default.Publish,
                        onClick = onPublish
                    )
                }
                if (canCreateVersion) {
                    ModelAction(
                        label = stringResource(R.string.analysis_model_new_version),
                        icon = Icons.Default.Upgrade,
                        onClick = onCreateNextVersion
                    )
                }
                if (canArchive) {
                    ModelAction(
                        label = stringResource(R.string.analysis_model_archive),
                        icon = Icons.Default.Archive,
                        onClick = onArchive
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label)
    }
}

@Composable
private fun MetadataPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusBadge(status: String) {
    val statusEnum = AnalysisModelLifecycleStatus.fromCode(status)
    val (container, content) = when (statusEnum) {
        AnalysisModelLifecycleStatus.PUBLISHED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AnalysisModelLifecycleStatus.DRAFT ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        AnalysisModelLifecycleStatus.ARCHIVED,
        AnalysisModelLifecycleStatus.LEGACY,
        null -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            statusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
    }
}

/** 分析模型草稿编辑器，按科学身份、处理契约、兼容范围和模型定义分段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisModelEditorSheet(
    state: AnalysisModelUiState,
    onDraftChange: (AnalysisModelDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.draft
    var showAdvancedCompatibility by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(
                    if (state.editorMode == AnalysisModelEditorMode.CREATE) {
                        R.string.analysis_model_create_title
                    } else {
                        R.string.analysis_model_edit_title
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            EditorSectionTitle(stringResource(R.string.analysis_model_identity_section))
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.analysis_model_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = state.editorMode == AnalysisModelEditorMode.EDIT_DRAFT
            )
            if (state.analytes.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f)
                ) {
                    Text(
                        stringResource(R.string.analysis_model_no_analytes),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            } else {
                ChoiceRow(
                    title = stringResource(R.string.analysis_model_analyte_label),
                    options = state.analytes,
                    selected = state.analytes.find { it.id == draft.analyteId },
                    label = { it.name },
                    onSelected = { onDraftChange(draft.copy(analyteId = it.id)) }
                )
            }
            ChoiceRow(
                title = stringResource(R.string.analysis_model_detection_mode_label),
                options = DetectionModality.entries,
                selected = DetectionModality.fromCode(draft.detectionMode)
                    ?: DetectionModality.COLORIMETRIC,
                label = { detectionModeLabel(it.code) },
                onSelected = { onDraftChange(draft.withDetectionMode(it)) }
            )
            ChoiceRow(
                title = stringResource(R.string.analysis_model_input_protocol_label),
                options = InputProtocol.entries.filter { it.code in draft.allowedProtocols },
                selected = InputProtocol.fromCode(draft.inputProtocol)
                    ?: InputProtocol.ENDPOINT_ONLY,
                label = { inputProtocolLabel(it.code) },
                onSelected = { onDraftChange(draft.withInputProtocol(it)) }
            )
            ChoiceRow(
                title = stringResource(R.string.analysis_model_primary_feature_label),
                options = AnalysisPrimaryFeature.entries.filter {
                    it.code in draft.allowedPrimaryFeatures
                },
                selected = AnalysisPrimaryFeature.fromCode(draft.primaryFeature)
                    ?: AnalysisPrimaryFeature.DELTA_E_2000,
                label = { primaryFeatureLabel(it.code) },
                onSelected = { onDraftChange(draft.copy(primaryFeature = it.code)) }
            )

            EditorSectionTitle(stringResource(R.string.analysis_model_processing_section))
            ChoiceRow(
                title = stringResource(R.string.analysis_model_filter_type),
                options = AnalysisModelType.entries,
                selected = draft.modelType,
                label = { modelTypeLabel(it.code) },
                onSelected = { onDraftChange(draft.copy(modelType = it)) }
            )
            // 比色/荧光的处理器由模态自动锁定并隐藏；仅光谱需要显式声明处理器身份。
            if (draft.detectionMode == DetectionModality.SPECTRUM.code) {
                OutlinedTextField(
                    value = draft.processorName,
                    onValueChange = { onDraftChange(draft.copy(processorName = it)) },
                    label = { Text(stringResource(R.string.analysis_model_processor_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.processorVersion,
                    onValueChange = { onDraftChange(draft.copy(processorVersion = it)) },
                    label = { Text(stringResource(R.string.analysis_model_processor_version_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            EditorSectionTitle(stringResource(R.string.analysis_model_compatibility_section))
            OutlinedTextField(
                value = draft.concentrationUnit,
                onValueChange = { onDraftChange(draft.copy(concentrationUnit = it)) },
                label = { Text(stringResource(R.string.analysis_model_unit_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.reliableRangeMinInput,
                    onValueChange = { onDraftChange(draft.copy(reliableRangeMinInput = it)) },
                    label = { Text(stringResource(R.string.analysis_model_range_min_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.reliableRangeMaxInput,
                    onValueChange = { onDraftChange(draft.copy(reliableRangeMaxInput = it)) },
                    label = { Text(stringResource(R.string.analysis_model_range_max_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            ChoiceRow(
                title = stringResource(R.string.analysis_model_carriers_label),
                options = CarrierType.entries,
                selectedValues = draft.compatibleCarrierTypes,
                code = CarrierType::code,
                label = { carrierTypeLabel(it.code) },
                onToggle = { carrier ->
                    onDraftChange(
                        draft.copy(
                            compatibleCarrierTypes = draft.compatibleCarrierTypes.toggle(
                                carrier.code
                            )
                        )
                    )
                }
            )
            // 采集设备兼容性会直接影响模型是否能用于正式定量，因此不能由“万能默认设备”
            // 偷偷满足。为保持普通表单简洁，将它放入高级折叠区，但发布前仍要求明确选择。
            TextButton(
                onClick = { showAdvancedCompatibility = !showAdvancedCompatibility },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.analysis_model_advanced_compatibility),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (showAdvancedCompatibility) Icons.Default.ExpandLess
                    else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (showAdvancedCompatibility) {
                Text(
                    stringResource(R.string.analysis_model_acquisition_profiles_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val activeAcquisitionProfiles = state.acquisitionProfiles.filter {
                    it.status == ResourceStatus.ACTIVE.code
                }
                if (activeAcquisitionProfiles.isEmpty()) {
                    Text(
                        stringResource(R.string.analysis_model_no_acquisition_profiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ChoiceRow(
                        title = stringResource(R.string.analysis_model_acquisition_profiles_label),
                        options = activeAcquisitionProfiles,
                        selectedValues = draft.compatibleAcquisitionProfileIds,
                        code = AcquisitionProfile::id,
                        label = { profile -> profile.name },
                        onToggle = { profile ->
                            onDraftChange(
                                draft.copy(
                                    compatibleAcquisitionProfileIds =
                                        draft.compatibleAcquisitionProfileIds.toggle(profile.id)
                                )
                            )
                        }
                    )
                }
            }

            EditorSectionTitle(stringResource(R.string.analysis_model_definition_section))
            when (draft.modelType) {
                AnalysisModelType.STANDARD_CURVE -> StandardCurveFields(draft, onDraftChange)
                AnalysisModelType.DEEP_LEARNING -> DeepLearningFields(draft, onDraftChange)
            }

            Button(
                onClick = onSave,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Text(stringResource(R.string.analysis_model_save_draft))
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StandardCurveFields(
    draft: AnalysisModelDraft,
    onDraftChange: (AnalysisModelDraft) -> Unit
) {
    OutlinedTextField(
        value = draft.fittingFunction,
        onValueChange = { onDraftChange(draft.copy(fittingFunction = it)) },
        label = { Text(stringResource(R.string.analysis_model_fitting_function_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    OutlinedTextField(
        value = draft.parametersJson,
        onValueChange = { onDraftChange(draft.copy(parametersJson = it)) },
        label = { Text(stringResource(R.string.analysis_model_parameters_label)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    ChoiceRow(
        title = stringResource(R.string.analysis_model_monotonic_label),
        options = listOf("AUTO", "INCREASING", "DECREASING"),
        selected = draft.monotonicDirection,
        label = { monotonicDirectionLabel(it) },
        onSelected = { onDraftChange(draft.copy(monotonicDirection = it)) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.lodInput,
            onValueChange = { onDraftChange(draft.copy(lodInput = it)) },
            label = { Text(stringResource(R.string.analysis_model_lod_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.loqInput,
            onValueChange = { onDraftChange(draft.copy(loqInput = it)) },
            label = { Text(stringResource(R.string.analysis_model_loq_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }
}

@Composable
private fun DeepLearningFields(
    draft: AnalysisModelDraft,
    onDraftChange: (AnalysisModelDraft) -> Unit
) {
    OutlinedTextField(
        value = draft.modelFileName,
        onValueChange = { onDraftChange(draft.copy(modelFileName = it)) },
        label = { Text(stringResource(R.string.analysis_model_file_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    OutlinedTextField(
        value = draft.checksumSha256,
        onValueChange = { onDraftChange(draft.copy(checksumSha256 = it)) },
        label = { Text(stringResource(R.string.analysis_model_checksum_label)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.inputWidthInput,
            onValueChange = { onDraftChange(draft.copy(inputWidthInput = it)) },
            label = { Text(stringResource(R.string.analysis_model_input_width_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedTextField(
            value = draft.inputHeightInput,
            onValueChange = { onDraftChange(draft.copy(inputHeightInput = it)) },
            label = { Text(stringResource(R.string.analysis_model_input_height_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }
    OutlinedTextField(
        value = draft.normalizationJson,
        onValueChange = { onDraftChange(draft.copy(normalizationJson = it)) },
        label = { Text(stringResource(R.string.analysis_model_normalization_label)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    OutlinedTextField(
        value = draft.trainingDataVersion,
        onValueChange = { onDraftChange(draft.copy(trainingDataVersion = it)) },
        label = { Text(stringResource(R.string.analysis_model_training_version_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun EditorSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 单选横向芯片组，适合数量有限且需要快速比较的稳定枚举。 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    label = { Text(label(option)) }
                )
            }
        }
    }
}

/** 多选横向芯片组，用于载体和采集设备兼容范围。 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selectedValues: Set<String>,
    code: (T) -> String,
    label: @Composable (T) -> String,
    onToggle: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = code(option) in selectedValues,
                    onClick = { onToggle(option) },
                    label = { Text(label(option)) }
                )
            }
        }
    }
}

/** 页面只在模态变化时自动切换协议、特征和锁定的光度处理器，避免瞬时不合法组合。 */
private fun AnalysisModelDraft.withDetectionMode(mode: DetectionModality): AnalysisModelDraft {
    return when (mode) {
        DetectionModality.COLORIMETRIC -> copy(
            detectionMode = mode.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
            processorName = COLORIMETRIC_PROCESSOR_NAME,
            processorVersion = COLORIMETRIC_PROCESSOR_VERSION
        )
        DetectionModality.FLUORESCENCE -> copy(
            detectionMode = mode.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
            processorName = FLUORESCENCE_PROCESSOR_NAME,
            processorVersion = FLUORESCENCE_PROCESSOR_VERSION
        )
        DetectionModality.SPECTRUM -> copy(
            detectionMode = mode.code,
            inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
            primaryFeature = AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM.code,
            // 光谱走独立标定流程，处理器名称仍由用户在光谱专用字段中显式声明。
            processorName = "",
            processorVersion = ""
        )
    }
}

/** 光谱协议变化时同步选择峰值或峰位移特征。 */
private fun AnalysisModelDraft.withInputProtocol(protocol: InputProtocol): AnalysisModelDraft {
    val nextFeature = when (protocol) {
        InputProtocol.SINGLE_SPECTRUM_ANALYSIS ->
            AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM.code
        InputProtocol.LSPR_PAIRED_QUANTIFICATION ->
            AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM.code
        InputProtocol.ENDPOINT_ONLY -> primaryFeature
    }
    return copy(inputProtocol = protocol.code, primaryFeature = nextFeature)
}

private fun Set<String>.toggle(code: String): Set<String> =
    if (code in this) this - code else this + code

private fun Double.toCompactText(): String = if (this % 1.0 == 0.0) {
    toLong().toString()
} else {
    toString()
}

@Composable
private fun modelTypeLabel(code: String): String = when (AnalysisModelType.fromCode(code)) {
    AnalysisModelType.DEEP_LEARNING -> stringResource(R.string.analysis_model_type_deep_learning)
    AnalysisModelType.STANDARD_CURVE,
    null -> stringResource(R.string.analysis_model_type_standard_curve)
}

@Composable
private fun statusLabel(code: String): String = when (AnalysisModelLifecycleStatus.fromCode(code)) {
    AnalysisModelLifecycleStatus.DRAFT -> stringResource(R.string.analysis_model_status_draft)
    AnalysisModelLifecycleStatus.PUBLISHED -> stringResource(R.string.analysis_model_status_published)
    AnalysisModelLifecycleStatus.ARCHIVED -> stringResource(R.string.analysis_model_status_archived)
    AnalysisModelLifecycleStatus.LEGACY,
    null -> stringResource(R.string.analysis_model_status_legacy)
}

@Composable
private fun statusFilterLabel(filter: AnalysisModelStatusFilter): String = when (filter) {
    AnalysisModelStatusFilter.ALL -> stringResource(R.string.analysis_model_filter_all)
    AnalysisModelStatusFilter.DRAFT -> stringResource(R.string.analysis_model_status_draft)
    AnalysisModelStatusFilter.PUBLISHED -> stringResource(R.string.analysis_model_status_published)
    AnalysisModelStatusFilter.ARCHIVED -> stringResource(R.string.analysis_model_status_archived)
    AnalysisModelStatusFilter.LEGACY -> stringResource(R.string.analysis_model_status_legacy)
}

@Composable
private fun detectionModeLabel(code: String): String = when (DetectionModality.fromCode(code)) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.analysis_model_mode_colorimetric)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.analysis_model_mode_fluorescence)
    DetectionModality.SPECTRUM,
    null -> stringResource(R.string.analysis_model_mode_spectrum)
}

@Composable
private fun inputProtocolLabel(code: String): String = when (InputProtocol.fromCode(code)) {
    InputProtocol.ENDPOINT_ONLY -> stringResource(R.string.analysis_model_protocol_endpoint)
    InputProtocol.SINGLE_SPECTRUM_ANALYSIS -> stringResource(
        R.string.analysis_model_protocol_single_spectrum
    )
    InputProtocol.LSPR_PAIRED_QUANTIFICATION,
    null -> stringResource(R.string.analysis_model_protocol_lspr_pair)
}

@Composable
private fun primaryFeatureLabel(code: String): String {
    val feature = AnalysisPrimaryFeature.fromCode(code) ?: return code
    return analysisFeatureLabel(feature)
}

@Composable
private fun carrierTypeLabel(code: String): String = when (CarrierType.fromCode(code)) {
    CarrierType.PLATE -> stringResource(R.string.analysis_model_carrier_plate)
    CarrierType.MICROFLUIDIC_CHIP -> stringResource(R.string.analysis_model_carrier_chip)
    CarrierType.CUSTOM,
    null -> stringResource(R.string.analysis_model_carrier_custom)
}

@Composable
private fun monotonicDirectionLabel(code: String): String = when (code) {
    "INCREASING" -> stringResource(R.string.analysis_model_monotonic_increasing)
    "DECREASING" -> stringResource(R.string.analysis_model_monotonic_decreasing)
    else -> stringResource(R.string.analysis_model_monotonic_auto)
}

/** 将稳定事件码映射为本地化自定义 Toast。 */
@Composable
private fun AnalysisModelEventEffect(events: Flow<AnalysisModelEvent>) {
    val toastManager = LocalToastManager.current
    val saved = stringResource(R.string.analysis_model_saved)
    val versionCreated = stringResource(R.string.analysis_model_version_created)
    val published = stringResource(R.string.analysis_model_published)
    val archived = stringResource(R.string.analysis_model_archived)
    val failed = stringResource(R.string.analysis_model_operation_failed)
    val validationMessages = mapOf(
        AnalysisModelFormError.NAME_REQUIRED to stringResource(
            R.string.analysis_model_validation_name
        ),
        AnalysisModelFormError.ANALYTE_REQUIRED to stringResource(
            R.string.analysis_model_validation_analyte
        ),
        AnalysisModelFormError.PROCESSOR_NAME_REQUIRED to stringResource(
            R.string.analysis_model_validation_processor
        ),
        AnalysisModelFormError.PROCESSOR_VERSION_REQUIRED to stringResource(
            R.string.analysis_model_validation_processor
        ),
        AnalysisModelFormError.CONCENTRATION_UNIT_REQUIRED to stringResource(
            R.string.analysis_model_validation_unit
        ),
        AnalysisModelFormError.RELIABLE_RANGE_INVALID to stringResource(
            R.string.analysis_model_validation_range
        ),
        AnalysisModelFormError.INPUT_PROTOCOL_INCOMPATIBLE to stringResource(
            R.string.analysis_model_validation_protocol
        ),
        AnalysisModelFormError.PRIMARY_FEATURE_INCOMPATIBLE to stringResource(
            R.string.analysis_model_validation_feature
        ),
        AnalysisModelFormError.CARRIER_REQUIRED to stringResource(
            R.string.analysis_model_validation_carrier
        ),
        AnalysisModelFormError.ACQUISITION_PROFILE_REQUIRED to stringResource(
            R.string.analysis_model_validation_device
        ),
        AnalysisModelFormError.FITTING_FUNCTION_REQUIRED to stringResource(
            R.string.analysis_model_validation_curve
        ),
        AnalysisModelFormError.PARAMETERS_REQUIRED to stringResource(
            R.string.analysis_model_validation_curve
        ),
        AnalysisModelFormError.MODEL_FILE_REQUIRED to stringResource(
            R.string.analysis_model_validation_file
        ),
        AnalysisModelFormError.CHECKSUM_INVALID to stringResource(
            R.string.analysis_model_validation_file
        ),
        AnalysisModelFormError.INPUT_SIZE_INVALID to stringResource(
            R.string.analysis_model_validation_file
        ),
        AnalysisModelFormError.NORMALIZATION_REQUIRED to stringResource(
            R.string.analysis_model_validation_file
        ),
        AnalysisModelFormError.TRAINING_DATA_VERSION_REQUIRED to stringResource(
            R.string.analysis_model_validation_file
        )
    )

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is AnalysisModelEvent.ValidationFailed -> toastManager.showToast(
                    event.errors.firstNotNullOfOrNull(validationMessages::get) ?: failed,
                    ToastType.WARNING
                )
                AnalysisModelEvent.DraftSaved -> toastManager.showToast(saved, ToastType.SUCCESS)
                AnalysisModelEvent.VersionCreated -> toastManager.showToast(
                    versionCreated,
                    ToastType.SUCCESS
                )
                AnalysisModelEvent.Published -> toastManager.showToast(
                    published,
                    ToastType.SUCCESS
                )
                AnalysisModelEvent.Archived -> toastManager.showToast(
                    archived,
                    ToastType.SUCCESS
                )
                is AnalysisModelEvent.OperationFailed -> toastManager.showToast(
                    failed,
                    ToastType.ERROR
                )
            }
        }
    }
}
