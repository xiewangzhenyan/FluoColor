package com.muc.fluocolorquant.ui.screens.detection

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.isConfigurationComplete
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.detection.GridLocalizationPresentation
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiError
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutPaintIntent
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import java.io.File

/**
 * 微流控统一工作流页面。
 *
 * 页面先让用户查看真实的原图定位、透视矫正和最终网格证据，再进入孔位布局；不会再显示
 * “检测完成”中转卡。所有定量和保存都必须等用户确认位点归属后才执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridDetectionGatewayContent(
    state: GridDetectionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenLayout: () -> Unit,
    onReviewLocalization: () -> Unit,
    onAssignmentsChange: (List<GridLayoutAssignmentDraft>) -> Unit,
    onPaintAssignments: (GridLayoutPaintIntent) -> GridPaintMergeResult,
    onFinalizeLayout: (List<GridLayoutAssignmentDraft>) -> Unit,
    onUseManualConfiguration: () -> Unit = {},
    onApplyTemplate: (String) -> Unit = {},
    onSelectQuantitationAnalyte: (String) -> Unit = {},
    onSetQuantitationMode: (String, GridAnalyteQuantitationMode) -> Unit = { _, _ -> },
    onSelectAnalysisModel: (String, String) -> Unit = { _, _ -> },
    onUpdateOnsiteAdvanced: (String, AnalysisPrimaryFeature?, FittingFunction?) -> Unit =
        { _, _, _ -> },
    onUpdateStandardConcentrations: (String, Map<Int, Double?>) -> Unit = { _, _ -> },
    onPreviewOnsiteFit: (String) -> Unit = {},
    onSelectOnsiteCandidate: (String, String) -> Unit = { _, _ -> },
    onSetOnsiteSaveToLibrary: (String, Boolean) -> Unit = { _, _ -> },
    onEditOnsiteCalibration: (String) -> Unit = {},
    onConfirmQuantitationAnalyte: (String, Boolean) -> Unit = { _, _ -> },
    onSaveTemplate: (String) -> Unit = {}
) {
    val presentation = when (state) {
        is GridDetectionUiState.Processing -> state.presentation
        is GridDetectionUiState.LocalizationReady -> state.preview.presentation
        else -> GridLocalizationPresentation.MICROFLUIDIC
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (presentation == GridLocalizationPresentation.PLATE96) {
                                R.string.plate96_layout_screen_title
                            } else {
                                R.string.microfluidic_detection_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state) {
                GridDetectionUiState.ResolvingProject -> CenteredProcessing(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                GridDetectionUiState.Plate96Localization -> CenteredProcessing(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                is GridDetectionUiState.Processing -> CenteredProcessing(
                    title = stageText(state.stage)
                )

                is GridDetectionUiState.LocalizationReady -> LocalizationWorkflow(
                    state = state,
                    onRetry = onRetry,
                    onOpenLayout = onOpenLayout,
                    onReviewLocalization = onReviewLocalization,
                    onAssignmentsChange = onAssignmentsChange,
                    onPaintAssignments = onPaintAssignments,
                    onFinalizeLayout = onFinalizeLayout,
                    onUseManualConfiguration = onUseManualConfiguration,
                    onApplyTemplate = onApplyTemplate,
                    onSelectQuantitationAnalyte = onSelectQuantitationAnalyte,
                    onSetQuantitationMode = onSetQuantitationMode,
                    onSelectAnalysisModel = onSelectAnalysisModel,
                    onUpdateOnsiteAdvanced = onUpdateOnsiteAdvanced,
                    onUpdateStandardConcentrations = onUpdateStandardConcentrations,
                    onPreviewOnsiteFit = onPreviewOnsiteFit,
                    onSelectOnsiteCandidate = onSelectOnsiteCandidate,
                    onSetOnsiteSaveToLibrary = onSetOnsiteSaveToLibrary,
                    onEditOnsiteCalibration = onEditOnsiteCalibration,
                    onConfirmQuantitationAnalyte = onConfirmQuantitationAnalyte,
                    onSaveTemplate = onSaveTemplate
                )

                is GridDetectionUiState.Completed -> CenteredProcessing(
                    title = stringResource(R.string.grid_detection_opening_results)
                )

                is GridDetectionUiState.RetakeRequired -> MessageCard(
                    title = stringResource(R.string.grid_detection_retake_title),
                    message = stringResource(R.string.grid_detection_retake_message),
                    primaryLabel = stringResource(R.string.retry),
                    onPrimary = onRetry,
                    onBack = onBack
                )

                is GridDetectionUiState.Blocked -> BlockedCard(
                    reasons = state.reasons,
                    onRetry = onRetry,
                    onBack = onBack
                )

                is GridDetectionUiState.Error -> MessageCard(
                    title = stringResource(R.string.grid_detection_error_title),
                    message = errorText(state.reason),
                    primaryLabel = stringResource(R.string.retry),
                    onPrimary = onRetry,
                    onBack = onBack
                )
            }
        }
    }
}

/** 定位确认和布局编辑共用同一定位会话，页面切换不会重新运行算法。 */
@Composable
private fun LocalizationWorkflow(
    state: GridDetectionUiState.LocalizationReady,
    onRetry: () -> Unit,
    onOpenLayout: () -> Unit,
    onReviewLocalization: () -> Unit,
    onAssignmentsChange: (List<GridLayoutAssignmentDraft>) -> Unit,
    onPaintAssignments: (GridLayoutPaintIntent) -> GridPaintMergeResult,
    onFinalizeLayout: (List<GridLayoutAssignmentDraft>) -> Unit,
    onUseManualConfiguration: () -> Unit,
    onApplyTemplate: (String) -> Unit,
    onSelectQuantitationAnalyte: (String) -> Unit,
    onSetQuantitationMode: (String, GridAnalyteQuantitationMode) -> Unit,
    onSelectAnalysisModel: (String, String) -> Unit,
    onUpdateOnsiteAdvanced: (String, AnalysisPrimaryFeature?, FittingFunction?) -> Unit,
    onUpdateStandardConcentrations: (String, Map<Int, Double?>) -> Unit,
    onPreviewOnsiteFit: (String) -> Unit,
    onSelectOnsiteCandidate: (String, String) -> Unit,
    onSetOnsiteSaveToLibrary: (String, Boolean) -> Unit,
    onEditOnsiteCalibration: (String) -> Unit,
    onConfirmQuantitationAnalyte: (String, Boolean) -> Unit,
    onSaveTemplate: (String) -> Unit
) {
    val preview = state.preview
    val assignments = remember(preview.initialAssignments, preview.columns) {
        preview.initialAssignments.toIndexedAssignmentMap(preview.columns)
    }

    if (state.editingLayout) {
        val isPlate96 = preview.presentation == GridLocalizationPresentation.PLATE96
        ArrayLayoutEditor(
            preview = preview,
            assignments = assignments,
            onAssignmentsChange = { changed ->
                onAssignmentsChange(changed.values.toList())
            },
            onPaintAssignments = onPaintAssignments,
            onBackToLocalization = onReviewLocalization,
            onFinalize = { onFinalizeLayout(assignments.values.toList()) },
            onUseManualConfiguration = onUseManualConfiguration,
            onApplyTemplate = onApplyTemplate,
            onSelectQuantitationAnalyte = onSelectQuantitationAnalyte,
            onSetQuantitationMode = onSetQuantitationMode,
            onSelectAnalysisModel = onSelectAnalysisModel,
            onUpdateOnsiteAdvanced = onUpdateOnsiteAdvanced,
            onUpdateStandardConcentrations = onUpdateStandardConcentrations,
            onPreviewOnsiteFit = onPreviewOnsiteFit,
            onSelectOnsiteCandidate = onSelectOnsiteCandidate,
            onSetOnsiteSaveToLibrary = onSetOnsiteSaveToLibrary,
            onEditOnsiteCalibration = onEditOnsiteCalibration,
            onConfirmQuantitationAnalyte = onConfirmQuantitationAnalyte,
            onSaveTemplate = onSaveTemplate,
            visualStyle = if (isPlate96) Plate96SiteVisualStyle else ArraySiteVisualStyle.SQUARE,
            compactHeader = isPlate96,
            includeQuantitationStep = isPlate96,
            realPreviewTitleRes = if (isPlate96) {
                R.string.plate96_layout_real_preview
            } else {
                R.string.grid_layout_real_preview
            }
        )
    } else {
        LocalizationConfirmation(
            preview = preview,
            onRetry = onRetry,
            onContinue = onOpenLayout
        )
    }
}

/** 用户可切换查看算法真实生成的处理证据，并确认定位质量。 */
@Composable
private fun LocalizationConfirmation(
    preview: GridLocalizationPreview,
    onRetry: () -> Unit,
    onContinue: () -> Unit
) {
    val evidenceRoles = listOf(
        CaptureRole.PROCESS_ORIGINAL_GEOMETRY,
        CaptureRole.PROCESS_RECTIFIED,
        CaptureRole.PROCESS_GRID_OVERLAY
    ).filter { role -> preview.evidence.any { it.role == role } }
    var selectedRole by rememberSaveable(preview.runId) {
        mutableStateOf(evidenceRoles.lastOrNull() ?: CaptureRole.PROCESS_ORIGINAL_GEOMETRY)
    }
    val selectedPath = preview.evidence.firstOrNull { it.role == selectedRole }?.path
    val imageModel: Any = selectedPath?.let(::File) ?: preview.originalImageUri

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        WorkflowStepHeader(currentStep = 1)
        Text(
            text = stringResource(R.string.grid_localization_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.grid_localization_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (evidenceRoles.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(evidenceRoles, key = CaptureRole::code) { role ->
                    FilterChip(
                        selected = selectedRole == role,
                        onClick = { selectedRole = role },
                        label = { Text(evidenceRoleLabel(role)) }
                    )
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            AsyncImage(
                model = imageModel,
                contentDescription = stringResource(R.string.grid_localization_preview),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentScale = ContentScale.Fit
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalizationMetric(
                modifier = Modifier.weight(1f),
                value = "${preview.rows}×${preview.columns}",
                label = stringResource(R.string.grid_localization_grid_size)
            )
            LocalizationMetric(
                modifier = Modifier.weight(1f),
                value = stringResource(
                    R.string.grid_localization_percent,
                    preview.observedRatio * 100
                ),
                label = stringResource(R.string.grid_localization_observed_ratio)
            )
            LocalizationMetric(
                modifier = Modifier.weight(1f),
                value = stringResource(
                    R.string.grid_localization_percent,
                    preview.meanConfidence * 100
                ),
                label = stringResource(R.string.grid_localization_confidence)
            )
        }

        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.grid_localization_retry))
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.grid_localization_confirm))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 规则阵列共用的孔位布局与定量编辑器。
 *
 * 页面状态、画笔合并、模板和逐分析物定量完全复用；载体差异仅通过 [visualStyle] 和
 * [realCropSourceBitmap] 注入。这样96孔板可以使用圆孔外观与内存中的标准方向图，同时不会
 * 复制微流控已经稳定的多笔画笔、孔位保护和定量状态机。
 */
@Composable
internal fun ArrayLayoutEditor(
    preview: GridLocalizationPreview,
    assignments: Map<Int, GridLayoutAssignmentDraft>,
    onAssignmentsChange: (Map<Int, GridLayoutAssignmentDraft>) -> Unit,
    onPaintAssignments: (GridLayoutPaintIntent) -> GridPaintMergeResult,
    onBackToLocalization: () -> Unit,
    onFinalize: () -> Unit,
    onUseManualConfiguration: () -> Unit,
    onApplyTemplate: (String) -> Unit,
    onSelectQuantitationAnalyte: (String) -> Unit,
    onSetQuantitationMode: (String, GridAnalyteQuantitationMode) -> Unit,
    onSelectAnalysisModel: (String, String) -> Unit,
    onUpdateOnsiteAdvanced: (String, AnalysisPrimaryFeature?, FittingFunction?) -> Unit,
    onUpdateStandardConcentrations: (String, Map<Int, Double?>) -> Unit,
    onPreviewOnsiteFit: (String) -> Unit,
    onSelectOnsiteCandidate: (String, String) -> Unit,
    onSetOnsiteSaveToLibrary: (String, Boolean) -> Unit,
    onEditOnsiteCalibration: (String) -> Unit,
    onConfirmQuantitationAnalyte: (String, Boolean) -> Unit,
    onSaveTemplate: (String) -> Unit,
    realCropSourceBitmap: Bitmap? = null,
    visualStyle: ArraySiteVisualStyle = ArraySiteVisualStyle.SQUARE,
    compactHeader: Boolean = false,
    includeQuantitationStep: Boolean = false,
    realPreviewTitleRes: Int = R.string.grid_layout_real_preview
) {
    var selectedAnalyteId by rememberSaveable(preview.runId) {
        mutableStateOf(preview.analytes.firstOrNull()?.id)
    }
    var selectedRole by rememberSaveable(preview.runId) {
        mutableStateOf(TemplateSiteRole.SAMPLE)
    }
    var clearMode by rememberSaveable(preview.runId) { mutableStateOf(false) }
    var sampleId by rememberSaveable(preview.runId) { mutableStateOf("") }
    var selectedSiteIndex by rememberSaveable(preview.runId) { mutableStateOf<Int?>(null) }
    val toastManager = LocalToastManager.current
    // stringResource 必须在 Composable 上下文提前读取，点击回调中只使用已经解析的字符串。
    val occupiedSiteMessage = stringResource(R.string.grid_layout_occupied_site_protected)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(ArrayLayoutEditorTestTags.ROOT)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        WorkflowStepHeader(
            currentStep = 2,
            includeQuantitationStep = includeQuantitationStep
        )
        if (compactHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.plate96_layout_standard_summary),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onBackToLocalization) {
                    Text(stringResource(R.string.plate96_layout_review_short), maxLines = 1)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.grid_layout_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.grid_layout_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onBackToLocalization) {
                    Text(stringResource(R.string.grid_layout_review_localization))
                }
            }
        }

        LayoutSectionTitle(stringResource(realPreviewTitleRes))
        RealSiteCropGrid(
            preview = preview,
            sourceBitmap = realCropSourceBitmap,
            visualStyle = visualStyle,
            selectedSiteIndex = selectedSiteIndex,
            onSiteSelected = { selectedSiteIndex = it }
        )

        LayoutSectionTitle(stringResource(R.string.grid_layout_analyte))
        GridAnalyteSelector(
            analytes = preview.analytes,
            selectedAnalyteId = selectedAnalyteId,
            onSelected = { analyteId ->
                clearMode = false
                selectedAnalyteId = analyteId
            }
        )

        LayoutSectionTitle(stringResource(R.string.array_site_role))
        GridRolePalette(
            selectedRole = selectedRole,
            clearMode = clearMode,
            onRoleSelected = { role ->
                clearMode = false
                selectedRole = role
            },
            onClearSelected = { clearMode = true }
        )

        if (!clearMode && selectedRole == TemplateSiteRole.SAMPLE) {
            OutlinedTextField(
                value = sampleId,
                onValueChange = { sampleId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.grid_layout_sample_id_optional)) },
                singleLine = true
            )
        }

        LayoutSectionTitle(
            stringResource(
                R.string.grid_layout_virtual_grid,
                assignments.size,
                preview.siteCount
            )
        )
        /*
         * 点击单孔和拖动画笔统一经过这一条批量意图路径。一次手势只提交一次位点集合，
         * ViewModel 在最新完整草稿上原子合并，避免连续重组和旧 UI Map 覆盖前一笔。
         */
        fun paintIndices(indices: Set<Int>) {
            if (indices.isEmpty()) return
            selectedSiteIndex = indices.last()
            val analyteId = selectedAnalyteId
            if (!clearMode && analyteId == null && selectedRole != TemplateSiteRole.DISABLED) return

            val mergeResult = onPaintAssignments(
                GridLayoutPaintIntent(
                    paintedSiteIndices = indices,
                    analyteId = analyteId,
                    role = selectedRole,
                    // 标准孔只在布局阶段标记角色；真实浓度统一在现场拟合工作台逐孔录入。
                    standardConcentration = null,
                    sampleId = sampleId,
                    clearMode = clearMode
                )
            )
            if (mergeResult.protectedSiteCount > 0) {
                // 已有科学归属不能被另一分析物或角色静默覆盖；用户可先切换清除画笔。
                toastManager.showToast(occupiedSiteMessage, ToastType.WARNING)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            CompactVirtualLayoutGrid(
                preview = preview,
                assignments = assignments,
                onPaintIndices = ::paintIndices,
                visualStyle = visualStyle,
                selectedSiteIndex = selectedSiteIndex,
                onSiteSelected = { selectedSiteIndex = it }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onAssignmentsChange(emptyMap()) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.grid_layout_clear_all))
            }
            OutlinedButton(
                onClick = {
                    paintIndices(
                        (0 until preview.siteCount)
                            .filterNot(assignments::containsKey)
                            .toSet()
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.grid_layout_fill_unassigned))
            }
        }

        GridExperimentConfigurationSection(
            preview = preview,
            assignments = assignments,
            onUseManualConfiguration = onUseManualConfiguration,
            onApplyTemplate = onApplyTemplate,
            onSelectQuantitationAnalyte = onSelectQuantitationAnalyte,
            onSetQuantitationMode = onSetQuantitationMode,
            onSelectAnalysisModel = onSelectAnalysisModel,
            onUpdateOnsiteAdvanced = onUpdateOnsiteAdvanced,
            onUpdateStandardConcentrations = onUpdateStandardConcentrations,
            onPreviewOnsiteFit = onPreviewOnsiteFit,
            onSelectOnsiteCandidate = onSelectOnsiteCandidate,
            onSetOnsiteSaveToLibrary = onSetOnsiteSaveToLibrary,
            onEditOnsiteCalibration = onEditOnsiteCalibration,
            onConfirmQuantitationAnalyte = onConfirmQuantitationAnalyte,
            onSaveTemplate = onSaveTemplate
        )

        Button(
            onClick = onFinalize,
            enabled = assignments.isNotEmpty() && preview.hasCompleteQuantitationConfiguration(
                assignments
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.grid_layout_analyze_results))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 最终分析按钮只检查用户方案是否完整，不重复科学预检。
 * 现场拟合允许不先点预览，但必须已有两个浓度水平；曲线/模型必须明确选中资源。
 */
private fun GridLocalizationPreview.hasCompleteQuantitationConfiguration(
    assignments: Map<Int, GridLayoutAssignmentDraft>
): Boolean {
    return assignments.isNotEmpty() &&
        quantitationDrafts.isNotEmpty() &&
        quantitationDrafts.all { draft -> draft.isConfigurationComplete() }
}

/** 把行列草稿转换为虚拟布局板使用的行优先索引，避免各调用方重复坐标公式。 */
private fun List<GridLayoutAssignmentDraft>.toIndexedAssignmentMap(
    columns: Int
): Map<Int, GridLayoutAssignmentDraft> {
    return associateBy { draft -> draft.rowIndex * columns + draft.columnIndex }
}


@Composable
private fun WorkflowStepHeader(
    currentStep: Int,
    includeQuantitationStep: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val stepLabels = if (includeQuantitationStep) {
            listOf(
                R.string.plate96_step_localization,
                R.string.plate96_step_layout,
                R.string.plate96_step_quantitation
            )
        } else {
            listOf(
                R.string.grid_workflow_step_localization,
                R.string.grid_workflow_step_layout
            )
        }
        stepLabels.forEachIndexed { index, labelRes ->
            val selected = currentStep == index + 1
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            ) {
                Text(
                    text = stringResource(labelRes),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun LocalizationMetric(modifier: Modifier, value: String, label: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LayoutSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun CenteredProcessing(title: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.grid_detection_processing_description),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.go_back))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedCard(
    reasons: Set<GridDetectionBlockReason>,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.grid_detection_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                reasons.forEach { reason ->
                    Text(
                        text = stringResource(
                            R.string.grid_block_reason_item,
                            blockReasonText(reason)
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.grid_layout_return_to_edit))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.go_back))
                }
            }
        }
    }
}

@Composable
private fun evidenceRoleLabel(role: CaptureRole): String = when (role) {
    CaptureRole.PROCESS_ORIGINAL_GEOMETRY -> stringResource(R.string.grid_evidence_original)
    CaptureRole.PROCESS_RECTIFIED -> stringResource(R.string.grid_evidence_rectified)
    CaptureRole.PROCESS_GRID_OVERLAY -> stringResource(R.string.grid_evidence_overlay)
    else -> stringResource(R.string.grid_evidence_process)
}

@Composable
private fun stageText(stage: GridDetectionStage): String = when (stage) {
    GridDetectionStage.PREPARING -> stringResource(R.string.grid_stage_preparing)
    GridDetectionStage.LOCATING -> stringResource(R.string.grid_stage_locating)
    GridDetectionStage.PHOTOMETRY -> stringResource(R.string.grid_stage_photometry)
    GridDetectionStage.RENDERING_EVIDENCE -> stringResource(R.string.grid_stage_rendering_evidence)
    GridDetectionStage.CHECKING_MODELS -> stringResource(R.string.grid_stage_checking_models)
    GridDetectionStage.PERSISTING -> stringResource(R.string.grid_stage_persisting)
    GridDetectionStage.COMPLETED -> stringResource(R.string.grid_stage_completed)
}

@Composable
private fun blockReasonText(reason: GridDetectionBlockReason): String = when (reason) {
    GridDetectionBlockReason.PROJECT_SNAPSHOT_MISMATCH -> stringResource(R.string.grid_block_snapshot_mismatch)
    GridDetectionBlockReason.UNSUPPORTED_CARRIER -> stringResource(R.string.grid_block_unsupported_carrier)
    GridDetectionBlockReason.INVALID_TEMPLATE_PROTOCOL -> stringResource(R.string.grid_block_invalid_protocol)
    GridDetectionBlockReason.MISSING_LOCATOR_CONFIG -> stringResource(R.string.grid_block_missing_locator)
    GridDetectionBlockReason.INVALID_LOCATOR_CONFIG -> stringResource(R.string.grid_block_invalid_locator)
    GridDetectionBlockReason.MISSING_ANALYTE_ASSIGNMENT -> stringResource(R.string.grid_block_missing_assignment)
    GridDetectionBlockReason.MISSING_COLORIMETRIC_REFERENCE -> stringResource(R.string.grid_block_missing_reference)
    GridDetectionBlockReason.MISSING_FLUORESCENCE_CHANNEL -> stringResource(R.string.grid_block_missing_fluorescence_channel)
    GridDetectionBlockReason.INVALID_PRIMARY_FEATURE -> stringResource(R.string.grid_block_invalid_feature)
    GridDetectionBlockReason.EMPTY_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_empty_analyte_snapshot)
    GridDetectionBlockReason.DUPLICATE_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_duplicate_analyte_snapshot)
    GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_inconsistent_analyte_snapshot)
    GridDetectionBlockReason.INVALID_SITE_COORDINATE -> stringResource(R.string.grid_block_invalid_site_coordinate)
    GridDetectionBlockReason.DUPLICATE_ENABLED_SITE -> stringResource(R.string.grid_block_duplicate_enabled_site)
    GridDetectionBlockReason.ORPHAN_SITE_ANALYTE -> stringResource(R.string.grid_block_orphan_site_analyte)
}

@Composable
private fun errorText(error: GridDetectionUiError): String = when (error) {
    GridDetectionUiError.INVALID_ARGUMENTS -> stringResource(R.string.grid_error_invalid_arguments)
    GridDetectionUiError.PROJECT_NOT_FOUND -> stringResource(R.string.grid_error_project_not_found)
    GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID -> stringResource(R.string.grid_error_snapshot_invalid)
    GridDetectionUiError.IMAGE_LOAD_FAILED -> stringResource(R.string.grid_error_image_load_failed)
    GridDetectionUiError.EXECUTION_FAILED -> stringResource(R.string.grid_error_execution_failed)
}
