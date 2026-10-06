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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.FluoMetricTile
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoStepIndicator
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.theme.FluoTheme
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.detection.GridLocalizationPresentation
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiError
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.ArrayLayoutReadiness
import com.muc.fluocolorquant.ui.viewmodels.ArrayLayoutReadinessIssue
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutPaintIntent
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import com.muc.fluocolorquant.ui.viewmodels.evaluateArrayLayoutReadiness
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
    onUpdateOnsiteAdvanced: (String, Set<AnalysisPrimaryFeature>, Set<FittingFunction>) -> Unit =
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
            FluoTopBar(
                title = stringResource(
                    if (presentation == GridLocalizationPresentation.PLATE96) {
                        R.string.plate96_layout_screen_title
                    } else {
                        R.string.microfluidic_detection_title
                    }
                ),
                onBack = onBack
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
    onUpdateOnsiteAdvanced: (String, Set<AnalysisPrimaryFeature>, Set<FittingFunction>) -> Unit,
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
            shape = RoundedCornerShape(FluoRadius.card),
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
    onUpdateOnsiteAdvanced: (String, Set<AnalysisPrimaryFeature>, Set<FittingFunction>) -> Unit,
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
    var positiveControlNominal by rememberSaveable(preview.runId) { mutableStateOf("") }
    var selectedSiteIndex by rememberSaveable(preview.runId) { mutableStateOf<Int?>(null) }
    val toastManager = LocalToastManager.current
    // stringResource 必须在 Composable 上下文提前读取，点击回调中只使用已经解析的字符串。
    val occupiedSiteMessage = stringResource(R.string.grid_layout_occupied_site_protected)
    val readiness = remember(assignments, preview.quantitationDrafts, preview.siteCount) {
        evaluateArrayLayoutReadiness(
            assignments = assignments.values,
            quantitationDrafts = preview.quantitationDrafts,
            totalSiteCount = preview.siteCount
        )
    }

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

        // 阳控的名义浓度随角色一并写入，双模态判定据此比较阳控实测信号与曲线预测信号。
        if (!clearMode && selectedRole == TemplateSiteRole.POSITIVE_CONTROL) {
            val unit = preview.analytes.firstOrNull { it.id == selectedAnalyteId }?.concentrationUnit.orEmpty()
            OutlinedTextField(
                value = positiveControlNominal,
                onValueChange = { positiveControlNominal = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.grid_layout_positive_control_nominal_optional, unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
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
                    // 标准孔只在布局阶段标记角色，真实浓度统一在现场拟合工作台逐孔录入；
                    // 阳控的名义浓度在这里随角色写入，空白或非法输入保持为空。
                    standardConcentration = positiveControlNominal.trim().toDoubleOrNull()
                        ?.takeIf { selectedRole == TemplateSiteRole.POSITIVE_CONTROL && it.isFinite() && it >= 0.0 },
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
            shape = RoundedCornerShape(FluoRadius.card),
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

        ArrayLayoutReadinessCard(readiness = readiness)

        if (
            assignments.isEmpty() &&
            preview.analytes.size == 1
        ) {
            val onlyAnalyte = preview.analytes.single()
            Button(
                onClick = {
                    // 单分析物项目提供显式快捷入口；它只填充当前空白布局，不会在后台
                    // 猜测标准品、空白或质控孔，用户之后仍可用清除画笔局部调整。
                    clearMode = false
                    selectedRole = TemplateSiteRole.SAMPLE
                    selectedAnalyteId = onlyAnalyte.id
                    onPaintAssignments(
                        GridLayoutPaintIntent(
                            paintedSiteIndices = (0 until preview.siteCount).toSet(),
                            analyteId = onlyAnalyte.id,
                            role = TemplateSiteRole.SAMPLE,
                            sampleId = null
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.GridView, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.grid_layout_fill_all_samples, onlyAnalyte.name))
            }
        }

        Button(
            onClick = onFinalize,
            enabled = readiness.canStart,
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
 * 在主按钮之前并排展示“孔位布局”和“定量方案”两个进度，禁用原因不再依赖用户猜测。
 */
@Composable
private fun ArrayLayoutReadinessCard(readiness: ArrayLayoutReadiness) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LayoutReadinessMetric(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.GridView,
                    label = stringResource(R.string.grid_layout_progress_sites),
                    completed = readiness.assignedSiteCount,
                    total = readiness.totalSiteCount
                )
                LayoutReadinessMetric(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.AutoFixHigh,
                    label = stringResource(R.string.grid_layout_progress_quantitation),
                    completed = readiness.completedAnalyteCount,
                    total = readiness.totalAnalyteCount
                )
            }
            val reason = when {
                ArrayLayoutReadinessIssue.NO_ASSIGNED_SITES in readiness.issues ->
                    stringResource(R.string.grid_layout_no_sites_reason)
                ArrayLayoutReadinessIssue.NO_QUANTITATION_DRAFTS in readiness.issues ||
                    ArrayLayoutReadinessIssue.QUANTITATION_INCOMPLETE in readiness.issues ->
                    stringResource(R.string.grid_layout_quantitation_incomplete_reason)
                else -> null
            }
            reason?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LayoutReadinessMetric(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    completed: Int,
    total: Int
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.grid_layout_progress_value, completed, total),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else completed.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

/** 把行列草稿转换为虚拟布局板使用的行优先索引，避免各调用方重复坐标公式。 */
private fun List<GridLayoutAssignmentDraft>.toIndexedAssignmentMap(
    columns: Int
): Map<Int, GridLayoutAssignmentDraft> {
    return associateBy { draft -> draft.rowIndex * columns + draft.columnIndex }
}


/**
 * 检测链路步骤条。
 *
 * 改用共享实现后与 96 孔板定位页完全一致：微流控走"定位 → 布局"两步，96 孔板走
 * "定位 → 布局 → 定量"三步，两条链路的进度提示不再是两种长相。
 *
 * currentStep 沿用调用方既有的 1 起始语义，这里换算为共享组件的 0 起始下标。
 */
@Composable
private fun WorkflowStepHeader(
    currentStep: Int,
    includeQuantitationStep: Boolean = false
) {
    val stepLabels = if (includeQuantitationStep) {
        listOf(
            stringResource(R.string.plate96_step_localization),
            stringResource(R.string.plate96_step_layout),
            stringResource(R.string.plate96_step_quantitation)
        )
    } else {
        listOf(
            stringResource(R.string.grid_workflow_step_localization),
            stringResource(R.string.grid_workflow_step_layout)
        )
    }
    FluoStepIndicator(
        steps = stepLabels,
        currentStep = (currentStep - 1).coerceIn(0, stepLabels.lastIndex)
    )
}

/** 定位指标改用共享指标块，与定位页、结果页的数值呈现保持同一层级。 */
@Composable
private fun LocalizationMetric(modifier: Modifier, value: String, label: String) {
    FluoMetricTile(modifier = modifier, label = label, value = value)
}

@Composable
private fun LayoutSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * 定位/定量处理中的占位。
 *
 * 标题由调用方传入真实阶段文本，副文本说明预期耗时；不使用无限装饰动画替代阶段信息
 * （AGENTS.md 9.2）。
 */
@Composable
private fun CenteredProcessing(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        FluoStatePlaceholder(
            text = title,
            supportingText = stringResource(R.string.grid_detection_processing_description)
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
            .padding(FluoSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FluoSectionCard(
            contentPadding = FluoSpacing.xl,
            accentColor = MaterialTheme.colorScheme.error
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(40.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = title,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(FluoSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.md)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(FluoRadius.control)
                ) {
                    Text(stringResource(R.string.go_back))
                }
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(FluoRadius.control)
                ) {
                    Text(primaryLabel)
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
    // 阻断不是失败：布局还差条件，回去补齐即可继续。此前整卡使用 error 红色，与"运行
    // 崩溃/数据不可用"表现相同，会让用户误以为结果已经作废。改用警告语义，并给每条
    // 原因加图标前缀，使原因可逐条扫读而不是一段红字（AGENTS.md 7.2、10）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FluoSpacing.xl),
        verticalArrangement = Arrangement.Center
    ) {
        FluoSectionCard(
            contentPadding = FluoSpacing.xl,
            accentColor = FluoTheme.semantic.warning
        ) {
            FluoSectionHeader(
                title = stringResource(R.string.grid_detection_blocked_title),
                icon = Icons.Default.ErrorOutline,
                accentColor = FluoTheme.semantic.warning
            )
            reasons.forEach { reason ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(FluoIconSize.small),
                        tint = FluoTheme.semantic.warning
                    )
                    Text(
                        text = blockReasonText(reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.control)
            ) {
                Text(stringResource(R.string.grid_layout_return_to_edit))
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.control)
            ) {
                Text(stringResource(R.string.go_back))
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
