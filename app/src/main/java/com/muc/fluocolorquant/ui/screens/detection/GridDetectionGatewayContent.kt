package com.muc.fluocolorquant.ui.screens.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.FluoMetricTile
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoStatusChip
import com.muc.fluocolorquant.ui.components.FluoStepIndicator
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoMotion
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
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitFallbackReason
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegionSource
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.ArrayLocatorPicker
import com.muc.fluocolorquant.ui.components.label
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import com.muc.fluocolorquant.ui.screens.result.array.calculateArrayImageFitTransform
import com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttempt
import com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttemptStatus
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiError
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.ArrayLayoutReadiness
import com.muc.fluocolorquant.ui.viewmodels.ArrayLayoutReadinessIssue
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutPaintIntent
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationSitePreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import com.muc.fluocolorquant.ui.viewmodels.evaluateArrayLayoutReadiness
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    onSaveTemplate: (String) -> Unit = {},
    onManualCorners: () -> Unit = {},
    onMarkCorner: (GridPoint) -> Unit = {},
    onUndoCorner: () -> Unit = {},
    onClearCorners: () -> Unit = {},
    onApplyCorners: () -> Unit = {},
    onCancelCorners: () -> Unit = {},
    onSetManualDiameter: (Double) -> Unit = {},
    onConfirmManualDiameter: () -> Unit = {},
    onChooseLocator: (ArrayLocatorMode) -> Unit = {},
    onRestoreLocalization: () -> Unit = {},
    onToggleManualBadSite: (Int) -> Unit = {},
    onNudgeRegularSite: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onScaleRegularSite: (Int, Double) -> Unit = { _, _ -> },
    onRestoreRegularSite: (Int) -> Unit = {},
    onDirectFinalizeTemplate: () -> Unit = {}
) {
    BackHandler(enabled = state is GridDetectionUiState.ManualCorners, onBack = onCancelCorners)
    val attempt = (state as? GridDetectionUiState.LocatingArray)?.attempt
    val review = (state as? GridDetectionUiState.LocalizationReady)?.takeUnless { it.editingLayout }
    val reviewScope = attempt?.scope ?: review?.preview?.analysisScope
    val presentation = when (state) {
        is GridDetectionUiState.Processing -> state.presentation
        is GridDetectionUiState.LocalizationReady -> state.preview.presentation
        else -> GridLocalizationPresentation.MICROFLUIDIC
    }
    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(
                    when {
                        reviewScope == ArrayAnalysisScope.ROUND_ARRAY -> R.string.array_localization_round_title
                        reviewScope != null -> R.string.array_localization_chip_title
                        presentation == GridLocalizationPresentation.PLATE96 -> R.string.plate96_layout_screen_title
                        else -> R.string.microfluidic_detection_title
                    }
                ),
                onBack = when (state) {
                    is GridDetectionUiState.ManualCorners -> onCancelCorners
                    else -> onBack
                },
                actions = {
                    if (review != null || attempt != null) ArrayLocalizationHelpAction(isPlate = false)
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (review != null || attempt != null) {
                GridLocalizationConfirmation(
                    preview = review?.preview,
                    attempt = attempt,
                    onChooseLocator = onChooseLocator,
                    onManualCorners = onManualCorners,
                    onRetry = onRetry,
                    onRestore = onRestoreLocalization,
                    onContinue = onOpenLayout,
                    onDirectFinalize = onDirectFinalizeTemplate,
                    readoutStage = review?.readoutStage,
                    onToggleManualBadSite = onToggleManualBadSite,
                    onNudgeRegularSite = onNudgeRegularSite,
                    onScaleRegularSite = onScaleRegularSite,
                    onRestoreRegularSite = onRestoreRegularSite
                )
            } else when (state) {
                GridDetectionUiState.ResolvingProject -> CenteredProcessing(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                GridDetectionUiState.Plate96Localization -> CenteredProcessing(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                is GridDetectionUiState.Processing -> CenteredProcessing(
                    title = stageText(state.stage)
                )

                is GridDetectionUiState.LocatingArray -> Unit

                is GridDetectionUiState.LocalizationReady -> GridLayoutWorkflow(
                    state = state,
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

                is GridDetectionUiState.ManualCorners -> ManualCornerEditor(state, onMarkCorner, onUndoCorner, onClearCorners, onApplyCorners,
                    onSetManualDiameter, onConfirmManualDiameter)
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

/** 布局复用已确认定位会话，不在切换步骤时重跑算法。 */
@Composable
private fun GridLayoutWorkflow(
    state: GridDetectionUiState.LocalizationReady,
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

    val isPlate96 = preview.presentation == GridLocalizationPresentation.PLATE96
    ArrayLayoutEditor(
        preview = preview,
        assignments = assignments,
        onAssignmentsChange = { changed -> onAssignmentsChange(changed.values.toList()) },
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
        visualStyle = if (isPlate96 || preview.analysisScope == ArrayAnalysisScope.ROUND_ARRAY) Plate96SiteVisualStyle else ArraySiteVisualStyle.SQUARE,
        compactHeader = isPlate96,
        includeQuantitationStep = isPlate96,
        realPreviewTitleRes = if (isPlate96) R.string.plate96_layout_real_preview else R.string.grid_layout_real_preview
    )
}

/** 用户可切换查看算法真实生成的处理证据，并点击单孔检视采样质量、微调孔位或标记异常孔。 */
@Composable
private fun GridLocalizationConfirmation(
    preview: GridLocalizationPreview?,
    attempt: ArrayLocatorAttempt?,
    onChooseLocator: (ArrayLocatorMode) -> Unit,
    onManualCorners: () -> Unit,
    onRetry: () -> Unit,
    onRestore: () -> Unit,
    onContinue: () -> Unit,
    onDirectFinalize: () -> Unit,
    readoutStage: GridDetectionStage?,
    onToggleManualBadSite: (Int) -> Unit,
    onNudgeRegularSite: (Int, Int, Int) -> Unit,
    onScaleRegularSite: (Int, Double) -> Unit,
    onRestoreRegularSite: (Int) -> Unit
) {
    val canUseGridOptions = preview == null || (preview.rows >= 2 && preview.columns >= 2)
    val mode = attempt?.mode ?: preview?.let {
        if (it.manualGeometry) ArrayLocatorMode.MANUAL_CORNERS else it.analysisPreferences.locator
    }
    val canDirectFinalizeFromTemplate = remember(preview) {
        preview != null &&
            preview.configurationSource == com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource.EXPERIMENT_TEMPLATE &&
            evaluateArrayLayoutReadiness(
                assignments = preview.initialAssignments,
                quantitationDrafts = preview.quantitationDrafts,
                totalSiteCount = preview.siteCount
            ).canStart
    }
    ArrayLocalizationReview(
        scope = attempt?.scope ?: requireNotNull(preview).analysisScope,
        selected = mode.takeIf { canUseGridOptions },
        attempt = attempt,
        onChooseLocator = onChooseLocator,
        onManualCorners = onManualCorners.takeIf { canUseGridOptions },
        onRetry = onRetry,
        onRestore = onRestore,
        onContinue = onContinue,
        canContinue = preview != null,
        updatingReadout = readoutStage != null,
        onDirectFinalize = onDirectFinalize.takeIf { canDirectFinalizeFromTemplate }
    ) {
        if (preview != null) {
            GridLocalizationEvidenceContent(
                preview = preview,
                readoutStage = readoutStage,
                onToggleManualBadSite = onToggleManualBadSite,
                onNudgeRegularSite = onNudgeRegularSite,
                onScaleRegularSite = onScaleRegularSite,
                onRestoreRegularSite = onRestoreRegularSite
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun GridLocalizationEvidenceContent(
    preview: GridLocalizationPreview,
    readoutStage: GridDetectionStage?,
    onToggleManualBadSite: (Int) -> Unit,
    onNudgeRegularSite: (Int, Int, Int) -> Unit,
    onScaleRegularSite: (Int, Double) -> Unit,
    onRestoreRegularSite: (Int) -> Unit
) {
    val context = LocalContext.current
    val evidenceRoles = listOf(
        CaptureRole.PROCESS_ORIGINAL_GEOMETRY,
        CaptureRole.PROCESS_RECTIFIED,
        CaptureRole.PROCESS_ROI_BACKGROUND,
        CaptureRole.PROCESS_GRID_OVERLAY
    ).filter { role -> preview.evidence.any { it.role == role } }
    var selectedRole by rememberSaveable(preview.originalImageUri) {
        mutableStateOf(evidenceRoles.lastOrNull() ?: CaptureRole.PROCESS_ORIGINAL_GEOMETRY)
    }
    var displayFilter by rememberSaveable(preview.originalImageUri) {
        mutableStateOf(ArrayDisplayFilterMode.ORIGINAL)
    }
    var inspectedSiteIndex by rememberSaveable(preview.runId) {
        mutableStateOf<Int?>(null)
    }
    val visibleRole = selectedRole.takeIf { it in evidenceRoles } ?: evidenceRoles.lastOrNull()
    val selectedPath = preview.evidence.firstOrNull { it.role == visibleRole }?.path
    val imageModel: Any = remember(context, selectedPath, preview.originalImageUri, preview.evidenceRevision) {
        if (selectedPath != null) {
            ImageRequest.Builder(context)
                .data(File(selectedPath))
                .memoryCacheKey("$selectedPath#rev=${preview.evidenceRevision}")
                .build()
        } else {
            preview.originalImageUri
        }
    }
    val filteredBitmap by rememberFilteredFileImage(selectedPath, displayFilter, preview.evidenceRevision)

    val useOriginalCoords = visibleRole == CaptureRole.PROCESS_ORIGINAL_GEOMETRY
    val coordWidth = if (useOriginalCoords) preview.originalWidth else preview.rectifiedWidth
    val coordHeight = if (useOriginalCoords) preview.originalHeight else preview.rectifiedHeight
    val canTapInspect = preview.sites.isNotEmpty() && coordWidth > 0 && coordHeight > 0
    val estimatedPitch = remember(preview.sites, preview.rows, preview.columns, useOriginalCoords) {
        if (!canTapInspect) 0.0 else {
            val pts = preview.sites.map { site ->
                if (useOriginalCoords) GridPoint(site.originalX, site.originalY)
                else GridPoint(site.rectifiedX, site.rectifiedY)
            }
            RegularGridGeometry.estimatePitch(pts, preview.rows, preview.columns).representativePx
        }
    }
    val invertColorFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArrayLocalizationPreviewFrame { imageModifier ->
            Box(imageModifier) {
                val bitmapToDraw = filteredBitmap
                if (bitmapToDraw != null) {
                    Canvas(Modifier.matchParentSize().clipToBounds()) {
                        val fit = calculateArrayImageFitTransform(
                            size.width,
                            size.height,
                            bitmapToDraw.width.toFloat(),
                            bitmapToDraw.height.toFloat()
                        ) ?: return@Canvas
                        drawImage(
                            image = bitmapToDraw,
                            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                            dstSize = IntSize(
                                (bitmapToDraw.width * fit.scale).roundToInt().coerceAtLeast(1),
                                (bitmapToDraw.height * fit.scale).roundToInt().coerceAtLeast(1)
                            )
                        )
                    }
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = stringResource(R.string.grid_localization_preview),
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (displayFilter == ArrayDisplayFilterMode.INVERT) invertColorFilter else null
                    )
                }

                // 交互式孔位点选与异常孔叠加层（借鉴 GenePix Feature Viewer & Flag Bad）
                if (canTapInspect) {
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .clipToBounds()
                            .pointerInput(preview.sites, useOriginalCoords, coordWidth, coordHeight, estimatedPitch) {
                                detectTapGestures { tap ->
                                    val fit = calculateArrayImageFitTransform(
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        coordWidth.toFloat(),
                                        coordHeight.toFloat()
                                    ) ?: return@detectTapGestures
                                    val imgX = (tap.x - fit.left) / fit.scale
                                    val imgY = (tap.y - fit.top) / fit.scale
                                    if (imgX !in 0f..coordWidth.toFloat() || imgY !in 0f..coordHeight.toFloat()) {
                                        inspectedSiteIndex = null
                                        return@detectTapGestures
                                    }
                                    val nearest = preview.sites.minByOrNull { site ->
                                        val cx = if (useOriginalCoords) site.originalX else site.rectifiedX
                                        val cy = if (useOriginalCoords) site.originalY else site.rectifiedY
                                        hypot(cx - imgX, cy - imgY)
                                    }
                                    val hitRadius = max(12.0, estimatedPitch * 0.58)
                                    if (nearest != null) {
                                        val cx = if (useOriginalCoords) nearest.originalX else nearest.rectifiedX
                                        val cy = if (useOriginalCoords) nearest.originalY else nearest.rectifiedY
                                        inspectedSiteIndex = if (hypot(cx - imgX, cy - imgY) <= hitRadius) {
                                            if (inspectedSiteIndex == nearest.siteIndex) null else nearest.siteIndex
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }
                    ) {
                        val fit = calculateArrayImageFitTransform(
                            size.width,
                            size.height,
                            coordWidth.toFloat(),
                            coordHeight.toFloat()
                        ) ?: return@Canvas
                        val markerRadius = ((estimatedPitch.toFloat() * 0.30f) * fit.scale).coerceIn(4.dp.toPx(), 13.dp.toPx())
                        fun screenPoint(site: GridLocalizationSitePreview): Offset {
                            val cx = if (useOriginalCoords) site.originalX else site.rectifiedX
                            val cy = if (useOriginalCoords) site.originalY else site.rectifiedY
                            return Offset(
                                fit.left + (cx.toFloat() + 0.5f) * fit.scale,
                                fit.top + (cy.toFloat() + 0.5f) * fit.scale
                            )
                        }
                        // 对人工微调过的孔位实时绘制更新后的四邻域网格连线与边框，保证连续点按时零延迟反馈
                        preview.sites.forEach { site ->
                            if (site.isUserAdjusted) {
                                val center = screenPoint(site)
                                val r = site.rowIndex
                                val c = site.columnIndex
                                val linkColor = Color(0xCC29B6F6)
                                listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1).forEach { (nr, nc) ->
                                    if (nr in 0 until preview.rows && nc in 0 until preview.columns) {
                                        val neighbor = preview.sites.getOrNull(nr * preview.columns + nc)
                                        if (neighbor != null) {
                                            drawLine(linkColor, center, screenPoint(neighbor), 1.2.dp.toPx())
                                        }
                                    }
                                }
                                if (!useOriginalCoords && site.cropRegion != null) {
                                    val b = site.cropRegion.bounds
                                    val topLeft = Offset(fit.left + b.left * fit.scale, fit.top + b.top * fit.scale)
                                    val boxSize = Size(b.width * fit.scale, b.height * fit.scale)
                                    if (site.cropRegion.shape == ArrayUnitShape.SQUARE) {
                                        drawRect(Color(0xFF00E5FF), topLeft, boxSize, style = Stroke(1.6.dp.toPx()))
                                    } else {
                                        drawOval(Color(0xFF00E5FF), topLeft, boxSize, style = Stroke(1.6.dp.toPx()))
                                    }
                                } else {
                                    drawCircle(Color(0xFF00E5FF), markerRadius * 0.68f, center, style = Stroke(1.8.dp.toPx()))
                                }
                            }
                        }
                        preview.sites.forEach { site ->
                            val center = screenPoint(site)
                            if (site.manuallyFlaggedBad) {
                                val badColor = Color(0xFFFF6B6B)
                                val r = markerRadius * 0.72f
                                drawCircle(Color(0x88141C24), r + 2.dp.toPx(), center)
                                drawLine(badColor, center - Offset(r, r), center + Offset(r, r), 2.dp.toPx())
                                drawLine(badColor, center + Offset(-r, r), center + Offset(r, -r), 2.dp.toPx())
                            }
                            if (site.siteIndex == inspectedSiteIndex) {
                                drawCircle(
                                    color = Color(0xFFFFD54F),
                                    radius = markerRadius + 2.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.2.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }

        // 视图选择与纯显示增强控制区
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (evidenceRoles.isNotEmpty()) {
                // 默认选中的网格不能藏在屏幕右缘；普通字号一行，大字体换行而不截断选项。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    evidenceRoles.forEach { role ->
                        FilterChip(
                            selected = visibleRole == role,
                            onClick = { selectedRole = role },
                            label = { Text(evidenceRoleLabel(role)) }
                        )
                    }
                }
            }
            ArrayDisplayFilterBar(
                selected = displayFilter,
                onSelect = { displayFilter = it }
            )
            if (displayFilter != ArrayDisplayFilterMode.ORIGINAL) {
                Text(
                    text = stringResource(R.string.array_display_filter_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 单孔放大检视与异常孔排除卡（点击任意孔位展开）
        val inspectedSite = inspectedSiteIndex?.let { index ->
            preview.sites.firstOrNull { it.siteIndex == index }
        }
        AnimatedVisibility(
            visible = inspectedSite != null,
            enter = FluoMotion.expandEnter,
            exit = FluoMotion.expandExit
        ) {
            if (inspectedSite != null) {
                WellSpotInspectorCard(
                    preview = preview,
                    site = inspectedSite,
                    displayFilter = displayFilter,
                    enabled = readoutStage == null,
                    onToggleBad = { onToggleManualBadSite(inspectedSite.siteIndex) },
                    onNudge = { dx, dy -> onNudgeRegularSite(inspectedSite.siteIndex, dx, dy) },
                    onScaleChange = { scale -> onScaleRegularSite(inspectedSite.siteIndex, scale) },
                    onRestoreAuto = { onRestoreRegularSite(inspectedSite.siteIndex) },
                    onClose = { inspectedSiteIndex = null }
                )
            }
        }

        // 定位与采样质量摘要卡
        val excludedCount = preview.sites.count { it.manuallyFlaggedBad }
        val summaryAccent = when {
            excludedCount > 0 -> FluoTheme.semantic.warning
            preview.manualGeometry -> MaterialTheme.colorScheme.primary
            preview.trustedGeometry -> FluoTheme.semantic.success
            else -> FluoTheme.semantic.warning
        }
        FluoSectionCard(
            contentPadding = FluoSpacing.md,
            verticalSpacing = FluoSpacing.sm,
            accentColor = summaryAccent
        ) {
            FluoSectionHeader(
                title = stringResource(R.string.grid_localization_summary_title),
                icon = Icons.Outlined.Analytics,
                accentColor = summaryAccent,
                trailingContent = {
                    when {
                        excludedCount > 0 -> FluoStatusChip(
                            text = stringResource(R.string.grid_localization_excluded_count, excludedCount),
                            icon = Icons.Outlined.ReportProblem,
                            contentColor = FluoTheme.semantic.onWarningContainer,
                            containerColor = FluoTheme.semantic.warningContainer
                        )
                        preview.manualGeometry -> FluoStatusChip(
                            text = stringResource(R.string.grid_localization_status_manual),
                            icon = Icons.Outlined.CropFree,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                        preview.trustedGeometry -> FluoStatusChip(
                            text = stringResource(R.string.grid_localization_status_trusted),
                            icon = Icons.Default.CheckCircle,
                            contentColor = FluoTheme.semantic.onSuccessContainer,
                            containerColor = FluoTheme.semantic.successContainer
                        )
                        else -> FluoStatusChip(
                            text = stringResource(R.string.grid_localization_status_review),
                            icon = Icons.Outlined.ReportProblem,
                            contentColor = FluoTheme.semantic.onWarningContainer,
                            containerColor = FluoTheme.semantic.warningContainer
                        )
                    }
                }
            )
            if (preview.manualGeometry) {
                Text(
                    text = stringResource(R.string.grid_manual_summary, preview.siteCount - 4),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
            }
            if (canTapInspect && inspectedSite == null) {
                Text(
                    text = stringResource(R.string.grid_localization_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 借鉴 GenePix Pro `Feature Viewer` + `Nudge/Resize` + `Flag Bad` 的单孔内联检视与微调卡。
 *
 * 左侧高倍展示该孔真实前景边界与外围背景环（以自动晶格为视野参考中心，使微调移动和缩放直观可见），
 * 右侧给出通俗状态解读、前景中位/均值、背景中位、信噪比与有效发光面积，并支持内联微调孔位与标记异常孔。
 */
@Composable
private fun WellSpotInspectorCard(
    preview: GridLocalizationPreview,
    site: GridLocalizationSitePreview,
    displayFilter: ArrayDisplayFilterMode,
    enabled: Boolean,
    onToggleBad: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onScaleChange: (Double) -> Unit,
    onRestoreAuto: () -> Unit,
    onClose: () -> Unit
) {
    val label = siteCoordinateLabel(site.rowIndex, site.columnIndex)
    val region = site.cropRegion
    var showAdjustPanel by rememberSaveable(site.siteIndex) { mutableStateOf(false) }
    var nudgeStepPx by rememberSaveable { mutableIntStateOf(1) }
    var showMetricsHelp by rememberSaveable { mutableStateOf(false) }
    var draftScalePercent by remember(site.siteIndex, site.boxScale) {
        mutableFloatStateOf((site.boxScale * 100.0).toFloat().coerceIn(60f, 140f))
    }

    val segAnomaly = !site.isUserAdjusted && region?.fallbackReason in setOf(
        ArrayUnitFallbackReason.WINDOW_BOUNDARY_TOUCH,
        ArrayUnitFallbackReason.ABNORMAL_SIZE,
        ArrayUnitFallbackReason.EXCESSIVE_CENTER_SHIFT,
        ArrayUnitFallbackReason.IRREGULAR_SHAPE
    )
    val segBadgeText = when {
        site.manuallyFlaggedBad -> stringResource(R.string.grid_spot_flagged_badge)
        site.isUserAdjusted -> stringResource(R.string.grid_spot_inspector_seg_adjusted)
        segAnomaly -> stringResource(R.string.grid_spot_inspector_seg_anomaly)
        region?.source == ArrayUnitRegionSource.FALLBACK_MEDIAN_GEOMETRY ->
            stringResource(R.string.grid_spot_inspector_seg_dark)
        else -> stringResource(R.string.grid_spot_inspector_seg_ok)
    }

    // 高倍视口固定锚定在自动基线中心（autoRectifiedX/Y），这样点击方向键时绿框在视口内真实移动对准斑点。
    data class CropViewport(val bitmap: ImageBitmap, val left: Int, val top: Int, val width: Int, val height: Int)
    val cropViewport by produceState<CropViewport?>(
        initialValue = null,
        preview.rectifiedImagePath,
        site.siteIndex,
        displayFilter
    ) {
        val path = preview.rectifiedImagePath ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            val full = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            try {
                val baseUnitSpan = region?.bounds?.let { max(it.width, it.height) }
                    ?: (preview.cropHalfSizePx * 2).roundToInt()
                val halfSpan = max(20, (baseUnitSpan * 1.35).roundToInt())
                val cx = site.autoRectifiedX.roundToInt()
                val cy = site.autoRectifiedY.roundToInt()
                val left = (cx - halfSpan).coerceIn(0, (full.width - 1).coerceAtLeast(0))
                val top = (cy - halfSpan).coerceIn(0, (full.height - 1).coerceAtLeast(0))
                val right = (cx + halfSpan).coerceIn(left + 1, full.width)
                val bottom = (cy + halfSpan).coerceIn(top + 1, full.height)
                val patch = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                val filtered = applyDisplayFilter(patch, displayFilter)
                if (patch !== filtered) patch.recycle()
                CropViewport(filtered.asImageBitmap(), left, top, right - left, bottom - top)
            } finally {
                full.recycle()
            }
        }
    }

    val activeAreaPercent = (site.pctAboveBgPlus2Sd * 100.0).roundToInt()
    val diagnosticSummary = when {
        site.manuallyFlaggedBad -> stringResource(R.string.grid_spot_status_excluded)
        site.signalToNoiseRatio < 3.0 -> stringResource(R.string.grid_spot_status_no_signal)
        site.pctAboveBgPlus2Sd >= 0.45 && kotlin.math.abs(site.foregroundMeanGray - site.foregroundMedianGray) <= 10.0 ->
            stringResource(R.string.grid_spot_status_uniform_signal, activeAreaPercent)
        site.pctAboveBgPlus2Sd < 0.25 -> stringResource(R.string.grid_spot_status_speck_warning)
        else -> stringResource(R.string.grid_spot_status_skew_hint)
    }

    FluoSectionCard(
        contentPadding = FluoSpacing.md,
        verticalSpacing = FluoSpacing.sm,
        accentColor = when {
            site.manuallyFlaggedBad || !site.qualityReliable -> MaterialTheme.colorScheme.error
            site.isUserAdjusted -> Color(0xFF0288D1)
            else -> MaterialTheme.colorScheme.primary
        }
    ) {
        FluoSectionHeader(
            title = stringResource(R.string.grid_spot_inspector_title, label),
            icon = Icons.Outlined.CenterFocusStrong,
            accentColor = if (site.manuallyFlaggedBad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Surface(
                        shape = RoundedCornerShape(FluoRadius.badge),
                        color = when {
                            site.manuallyFlaggedBad || segAnomaly -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Text(
                            text = segBadgeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                site.manuallyFlaggedBad || segAnomaly -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                    IconButton(onClick = { showMetricsHelp = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.grid_spot_metrics_help_title),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
                    }
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val previewBoxDp = if (showAdjustPanel) 86.dp else 76.dp
            Surface(
                modifier = Modifier.size(previewBoxDp),
                shape = RoundedCornerShape(FluoRadius.control),
                color = Color(0xFF141C24),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val vp = cropViewport
                    if (vp != null && vp.width > 0 && vp.height > 0) {
                        drawImage(
                            image = vp.bitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        )
                        val scaleX = size.width / vp.width.toFloat()
                        val scaleY = size.height / vp.height.toFloat()
                        val centerInCanvas = Offset(
                            ((site.rectifiedX - vp.left + 0.5) * scaleX).toFloat(),
                            ((site.rectifiedY - vp.top + 0.5) * scaleY).toFloat()
                        )
                        if (region != null) {
                            val b = region.bounds
                            val topLeft = Offset((b.left - vp.left) * scaleX, (b.top - vp.top) * scaleY)
                            val boxSize = Size(b.width * scaleX, b.height * scaleY)
                            val fgColor = if (site.isUserAdjusted) Color(0xFF00E5FF) else Color(0xFF00E676)
                            if (region.shape == ArrayUnitShape.SQUARE) {
                                drawRect(fgColor, topLeft, boxSize, style = Stroke(1.8.dp.toPx()))
                            } else {
                                drawOval(fgColor, topLeft, boxSize, style = Stroke(1.8.dp.toPx()))
                            }
                            val bgRadius = max(boxSize.width, boxSize.height) * 0.78f
                            drawCircle(Color(0xAAFFAB00), bgRadius, centerInCanvas, style = Stroke(1.1.dp.toPx()))
                        } else {
                            val fgRadius = size.minDimension * 0.22f
                            drawCircle(Color(0xFF00E676), fgRadius, centerInCanvas, style = Stroke(1.8.dp.toPx()))
                            drawCircle(Color(0xAAFFAB00), fgRadius * 1.7f, centerInCanvas, style = Stroke(1.1.dp.toPx()))
                        }
                    } else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val fgRadius = size.minDimension * 0.24f
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = center - Offset(fgRadius, fgRadius),
                            size = Size(fgRadius * 2f, fgRadius * 2f),
                            style = Stroke(1.6.dp.toPx())
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = diagnosticSummary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        site.manuallyFlaggedBad -> MaterialTheme.colorScheme.error
                        site.signalToNoiseRatio >= 3.0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = stringResource(
                        R.string.grid_spot_inspector_fg_bg,
                        site.foregroundMedianGray,
                        site.foregroundMeanGray,
                        site.backgroundMedianGray
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (preview.analysisPreferences.readout != WellReadoutMode.FULL_MEDIAN) {
                    Text(
                        text = stringResource(
                            R.string.grid_spot_inspector_active_readout,
                            stringResource(preview.analysisPreferences.readout.label()),
                            site.selectedReadoutGray
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = stringResource(
                        R.string.grid_spot_inspector_snr_b2sd,
                        site.signalToNoiseRatio,
                        activeAreaPercent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 内联微调面板（十字方向移动 + 采样框比例滑块 + 单孔恢复自动）
        AnimatedVisibility(
            visible = showAdjustPanel,
            enter = FluoMotion.expandEnter,
            exit = FluoMotion.expandExit
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.control),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1 to R.string.grid_spot_adjust_step_1px, 3 to R.string.grid_spot_adjust_step_3px).forEach { (step, labelRes) ->
                                FilterChip(
                                    selected = nudgeStepPx == step,
                                    onClick = { nudgeStepPx = step },
                                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                draftScalePercent = 100f
                                onRestoreAuto()
                            },
                            enabled = enabled && site.isUserAdjusted,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.grid_spot_adjust_restore), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 左侧十字微调盘
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = { onNudge(0, -nudgeStepPx) },
                                enabled = enabled,
                                modifier = Modifier.size(38.dp).testTag("grid_spot_nudge_up")
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.plate96_adjustment_move_up))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = { onNudge(-nudgeStepPx, 0) },
                                    enabled = enabled,
                                    modifier = Modifier.size(38.dp).testTag("grid_spot_nudge_left")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.plate96_adjustment_move_left))
                                }
                                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onNudge(nudgeStepPx, 0) },
                                    enabled = enabled,
                                    modifier = Modifier.size(38.dp).testTag("grid_spot_nudge_right")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.plate96_adjustment_move_right))
                                }
                            }
                            FilledTonalIconButton(
                                onClick = { onNudge(0, nudgeStepPx) },
                                enabled = enabled,
                                modifier = Modifier.size(38.dp).testTag("grid_spot_nudge_down")
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.plate96_adjustment_move_down))
                            }
                        }

                        // 右侧采样框尺寸缩放滑块
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.grid_spot_adjust_size_label, draftScalePercent.roundToInt()),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = draftScalePercent,
                                onValueChange = { draftScalePercent = it },
                                onValueChangeFinished = {
                                    onScaleChange(draftScalePercent.roundToInt() / 100.0)
                                },
                                valueRange = 60f..140f,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("grid_spot_scale_slider")
                            )
                        }
                    }
                }
            }
        }

        // 底部并排双主操作：微调孔位 ｜ 标为异常孔
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showAdjustPanel) {
                Button(
                    onClick = { showAdjustPanel = false },
                    enabled = enabled,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("grid_spot_adjust_toggle")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grid_spot_adjust_collapse), maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = { showAdjustPanel = true },
                    enabled = enabled,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("grid_spot_adjust_toggle")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grid_spot_adjust_action), maxLines = 1)
                }
            }

            if (site.manuallyFlaggedBad) {
                OutlinedButton(
                    onClick = onToggleBad,
                    enabled = enabled,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grid_spot_unflag_bad), maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = onToggleBad,
                    enabled = enabled,
                    shape = RoundedCornerShape(FluoRadius.control),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Icon(
                        Icons.Outlined.ReportProblem,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.grid_spot_flag_bad),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (showMetricsHelp) {
        AlertDialog(
            onDismissRequest = { showMetricsHelp = false },
            shape = RoundedCornerShape(FluoRadius.card),
            title = {
                Text(
                    text = stringResource(R.string.grid_spot_metrics_help_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.grid_spot_metrics_help_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showMetricsHelp = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

/**
 * 芯片、圆阵与标准孔板共用定位页骨架。算法运行、失败和纯对照只替换预览槽；不创建页面，
 * 不把其他算法的叠加冒充本次结果。底栏固定留出手动入口与主要操作，大字体可自然扩高。
 */
@Composable
internal fun ArrayLocalizationReview(
    scope: ArrayAnalysisScope,
    selected: ArrayLocatorMode?,
    attempt: ArrayLocatorAttempt?,
    onChooseLocator: (ArrayLocatorMode) -> Unit,
    onManualCorners: (() -> Unit)?,
    onRetry: () -> Unit,
    onRestore: () -> Unit,
    onContinue: () -> Unit,
    canContinue: Boolean,
    modifier: Modifier = Modifier,
    updatingReadout: Boolean = false,
    onDirectFinalize: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    val running = attempt?.status == ArrayLocatorAttemptStatus.Running || updatingReadout
    val canRestore = attempt?.previousMode != null
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkflowStepHeader(currentStep = 1, includeQuantitationStep = scope == ArrayAnalysisScope.STANDARD_PLATE)
            if (selected != null) {
                ArrayLocatorPicker(scope, selected, onChooseLocator, includeComparisons = true, enabled = !running)
            }
        }
    }
    val preview: @Composable () -> Unit = {
        if (attempt != null) ArrayLocatorAttemptContent(attempt, onRetry) else content()
    }
    Column(modifier.fillMaxSize().testTag("array_localization_review")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 横屏高度不足时并排放置选择和预览；不让标题、选择器与固定底栏把图像一起挤出首屏。
            if (maxWidth >= 600.dp && maxWidth > maxHeight * 1.45f) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(.85f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        controls()
                    }
                    Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        preview()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    controls()
                    preview()
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (onDirectFinalize != null && !canRestore) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.array_localization_template_ready_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onContinue,
                    enabled = !running,
                    modifier = Modifier.testTag("array_localization_edit_layout")
                ) {
                    Text(stringResource(R.string.array_localization_review_layout_action))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onManualCorners != null) ManualCornerEntry(onManualCorners, Modifier.weight(1f), enabled = !running)
            Button(
                onClick = when {
                    canRestore -> onRestore
                    onDirectFinalize != null -> onDirectFinalize
                    else -> onContinue
                },
                enabled = !running && (canRestore || canContinue),
                shape = RoundedCornerShape(FluoRadius.control),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1.15f).heightIn(min = 48.dp)
                    .testTag(if (canRestore) "array_locator_restore" else "array_localization_continue")
            ) {
                Text(
                    stringResource(
                        when {
                            canRestore -> R.string.array_locator_comparison_back
                            onDirectFinalize != null -> R.string.array_localization_direct_finalize_action
                            else -> R.string.array_localization_continue
                        }
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 同一槽位统一给原图和正式证据留空间；横屏限制画布高度，不用屏宽把整页撑出视口。 */
@Composable
internal fun ArrayLocalizationPreviewFrame(content: @Composable (Modifier) -> Unit) {
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .52f
    Surface(
        shape = RoundedCornerShape(FluoRadius.card),
        color = Color(0xFF141C24),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(minOf(maxWidth, maximumHeight))
                    .clip(RoundedCornerShape(FluoRadius.card))
                    .testTag("array_localization_preview")
            ) {
                content(Modifier.matchParentSize())
            }
        }
    }
}

/** 对照只画候选和推算中心，不伪造内径、物理 A1 或裁切；无结果直接显示同一原图。 */
@Composable
private fun ArrayLocatorAttemptContent(attempt: ArrayLocatorAttempt, onRetry: () -> Unit) {
    val grid = (attempt.status as? ArrayLocatorAttemptStatus.Preview)?.grid
    var displayFilter by rememberSaveable(attempt.mode) { mutableStateOf(ArrayDisplayFilterMode.ORIGINAL) }
    val image by rememberArrayDisplayImage(attempt.bitmap, displayFilter)
    var zoomToGrid by rememberSaveable(attempt.mode) { mutableStateOf(false) }
    val width = attempt.bitmap.width.toFloat()
    val height = attempt.bitmap.height.toFloat()
    val centers = remember(grid) { grid?.sites?.map { it.original }.orEmpty() }
    val pitch = remember(grid) { grid?.let { RegularGridGeometry.estimatePitch(centers, it.rows, it.columns).representativePx.toFloat() } ?: 0f }
    val overview = stringResource(R.string.array_localization_image)
    ArrayLocalizationPreviewFrame { imageModifier ->
        Box(imageModifier) {
            Canvas(Modifier.fillMaxSize().clipToBounds()
                .then(if (grid != null) Modifier.testTag("array_locator_comparison_overlay") else Modifier)
                .semantics { contentDescription = overview }) {
                val bitmap = image ?: return@Canvas
                val fit = calculateArrayImageFitTransform(size.width, size.height, width, height) ?: return@Canvas
                var scale = fit.scale
                var origin = Offset(fit.left, fit.top)
                if (zoomToGrid && centers.isNotEmpty()) {
                    val left = centers.minOf { it.x }.toFloat()
                    val right = centers.maxOf { it.x }.toFloat()
                    val top = centers.minOf { it.y }.toFloat()
                    val bottom = centers.maxOf { it.y }.toFloat()
                    scale = minOf(size.width / (right - left + 2 * pitch), size.height / (bottom - top + 2 * pitch))
                        .coerceIn(fit.scale, fit.scale * 6f)
                    origin = Offset((size.width - (left + right + 1f) * scale) / 2f, (size.height - (top + bottom + 1f) * scale) / 2f)
                }
                drawImage(bitmap, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1)))
                val radius = (.19f * pitch * scale).coerceIn(1f, 5.dp.toPx())
                val stroke = minOf(radius * .52f, 1.dp.toPx())
                grid?.sites?.forEach { site ->
                    val point = origin + Offset((site.original.x + .5).toFloat(), (site.original.y + .5).toFloat()) * scale
                    if (site.source == GridPointSource.MODEL_IMPUTED) {
                        val warm = Color(0xFFFFCB78)
                        drawLine(warm, point - Offset(radius, radius), point + Offset(radius, radius), stroke)
                        drawLine(warm, point + Offset(-radius, radius), point + Offset(radius, -radius), stroke)
                    } else {
                        drawCircle(Color(0xFF78DCE7), radius, point, style = Stroke(stroke))
                    }
                }
            }
            if (attempt.status == ArrayLocatorAttemptStatus.Running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val status = attempt.status) {
            ArrayLocatorAttemptStatus.Running -> Text(stringResource(R.string.array_localization_running),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            ArrayLocatorAttemptStatus.NoGrid, ArrayLocatorAttemptStatus.Failed -> {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(if (status == ArrayLocatorAttemptStatus.NoGrid) R.string.array_localization_no_grid
                    else R.string.array_localization_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
            }
            is ArrayLocatorAttemptStatus.Preview -> {
                Text(stringResource(R.string.array_localization_preview_count, status.grid.geometry.inlierCount, status.grid.sites.size),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { zoomToGrid = !zoomToGrid }) {
                    Icon(if (zoomToGrid) Icons.Outlined.ZoomOut else Icons.Outlined.ZoomIn,
                        stringResource(if (zoomToGrid) R.string.grid_manual_full_image else R.string.array_locator_comparison_focus))
                }
            }
        }
    }
    ArrayDisplayFilterBar(
        selected = displayFilter,
        onSelect = { displayFilter = it }
    )
}

/** 长说明放在用户主动打开的帮助里，失败、进行中和仅预览的短状态仍常驻。 */
@Composable
internal fun ArrayLocalizationHelpAction(isPlate: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.array_localization_help)
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("array_localization_help")) {
        Icon(Icons.Default.ErrorOutline, title, tint = MaterialTheme.colorScheme.primary)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (isPlate) R.string.array_localization_plate_help else R.string.grid_localization_description))
                Text(stringResource(R.string.array_localization_recovery_help))
                Text(stringResource(R.string.array_localization_comparison_help))
                Text(stringResource(R.string.array_locator_comparison_legend))
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } }
    )
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
        val excludedInLayoutCount = preview.sites.count { it.manuallyFlaggedBad }
        if (excludedInLayoutCount > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.control),
                color = FluoTheme.semantic.warningContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, FluoTheme.semantic.warning.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ReportProblem,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = FluoTheme.semantic.onWarningContainer
                    )
                    Text(
                        text = stringResource(R.string.grid_layout_excluded_wells_hint, excludedInLayoutCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = FluoTheme.semantic.onWarningContainer
                    )
                }
            }
        }
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
            .verticalScroll(rememberScrollState())
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
private fun ManualCornerEntry(onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(FluoRadius.control),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag("grid_manual_entry")) {
        Icon(Icons.Outlined.CropFree, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.array_locator_manual), textAlign = TextAlign.Center)
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
    CaptureRole.PROCESS_ROI_BACKGROUND -> stringResource(R.string.array_readout_region_preview)
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
    GridDetectionBlockReason.PREVIEW_ONLY_LOCALIZATION -> stringResource(R.string.array_locator_comparison_warning)
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
