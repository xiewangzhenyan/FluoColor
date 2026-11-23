package com.muc.fluocolorquant.ui.screens.detection.plate96

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizedSite
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.ui.components.FluoMetricTile
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoStatusChip
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.screens.detection.ArrayDisplayFilterBar
import com.muc.fluocolorquant.ui.screens.detection.ArrayDisplayFilterMode
import com.muc.fluocolorquant.ui.screens.detection.ArrayLocalizationHelpAction
import com.muc.fluocolorquant.ui.screens.detection.ArrayLocalizationPreviewFrame
import com.muc.fluocolorquant.ui.screens.detection.ArrayLocalizationReview
import com.muc.fluocolorquant.ui.screens.detection.ManualCornerEditor
import com.muc.fluocolorquant.ui.screens.detection.rememberArrayDisplayImage
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.theme.FluoTheme
import com.muc.fluocolorquant.ui.viewmodels.Plate96ImageViewMode
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationError
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationStage
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationSelection
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationUiState
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationViewModel
import com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttempt
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** 定位页稳定测试标签；设备测试和截图自审使用同一组入口。 */
object Plate96LocalizationTestTags {
    const val READY_CONTENT: String = "plate96_localization_ready"
    const val ORIENTATION_CARD: String = "plate96_orientation_card"
    const val PREVIEW: String = "plate96_localization_preview"
    const val DISPLAY_CONTROLS: String = "plate96_display_controls"
    const val MANUAL_ADJUST_BUTTON: String = "plate96_manual_adjust_button"
    const val SUMMARY: String = "plate96_localization_summary"
    const val ADJUSTMENT_SHEET: String = "plate96_adjustment_sheet"
    const val CROP_PREVIEW: String = "plate96_adjustment_crop_preview"
    const val NUDGE_UP: String = "plate96_adjustment_nudge_up"
    const val NUDGE_RIGHT: String = "plate96_adjustment_nudge_right"
    const val RADIUS_SLIDER: String = "plate96_adjustment_radius_slider"
    const val RESTORE_AUTOMATIC: String = "plate96_adjustment_restore"
    const val DONE: String = "plate96_adjustment_done"
}

/** 新96孔板定位确认页；尚未切换生产导航前也可由Compose测试独立渲染。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Plate96LocalizationScreen(
    imageUri: String?,
    onBack: () -> Unit,
    onContinue: (Plate96LocalizationSelection) -> Unit,
    viewModel: Plate96LocalizationViewModel = hiltViewModel(),
    initialPreferences: ArrayAnalysisPreferences = ArrayAnalysisPreferences()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(imageUri) { viewModel.start(imageUri, initialPreferences) }
    BackHandler(enabled = state is Plate96LocalizationUiState.Manual, onBack = viewModel::cancelManual)

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.plate96_localization_title),
                onBack = when (state) {
                    is Plate96LocalizationUiState.Manual -> viewModel::cancelManual
                    else -> onBack
                },
                actions = {
                    if (state is Plate96LocalizationUiState.Ready || state is Plate96LocalizationUiState.LocatingArray) {
                        ArrayLocalizationHelpAction(isPlate = true)
                    }
                }
            )
        }
    ) { padding ->
        when (val current = state) {
            Plate96LocalizationUiState.Idle -> Plate96LoadingContent(
                stage = Plate96LocalizationStage.LOADING_IMAGE,
                modifier = Modifier.padding(padding)
            )
            is Plate96LocalizationUiState.Loading -> Plate96LoadingContent(
                stage = current.stage,
                modifier = Modifier.padding(padding)
            )
            is Plate96LocalizationUiState.Error -> Plate96ErrorContent(
                reason = current.reason,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(padding)
            )
            is Plate96LocalizationUiState.Manual -> Box(Modifier.padding(padding).fillMaxSize()) {
                ManualCornerEditor(current.draft, viewModel::addManualCorner, viewModel::undoManualCorner,
                    viewModel::clearManualCorners, viewModel::applyManualCorners,
                    viewModel::setManualDiameter, viewModel::confirmManualDiameter)
            }
            is Plate96LocalizationUiState.Ready, is Plate96LocalizationUiState.LocatingArray -> Plate96ReadyContent(
                state = current as? Plate96LocalizationUiState.Ready,
                attempt = (current as? Plate96LocalizationUiState.LocatingArray)?.attempt,
                onImageViewChange = viewModel::selectImageView,
                onShowOutlinesChange = viewModel::setShowOutlines,
                onShowLabelsChange = viewModel::setShowLabels,
                onSelectSite = viewModel::selectSite,
                onLocatorModeChange = viewModel::setLocatorMode,
                onConfirmCurrentOrientation = viewModel::confirmCurrentOrientation,
                onOriginCornerChange = viewModel::applyOriginCorner,
                onRestoreAutomaticOrientation = viewModel::restoreAutomaticOrientation,
                onNudgeSelectedSite = viewModel::nudgeSelectedSite,
                onSetSelectedSiteRadius = viewModel::setSelectedSiteRadius,
                onRestoreSelectedSite = viewModel::restoreSelectedSite,
                onManualCorners = viewModel::openManualCorners,
                onRetry = viewModel::retry,
                onRestore = viewModel::restorePreviousLocalization,
                onContinue = {
                    // 移交当前同一份原图、标准图和人工确认后的定位会话，禁止下游重新定位。
                    viewModel.currentSelection()?.let(onContinue)
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
internal fun Plate96ReadyContent(
    state: Plate96LocalizationUiState.Ready?,
    onImageViewChange: (Plate96ImageViewMode) -> Unit,
    onShowOutlinesChange: (Boolean) -> Unit,
    onShowLabelsChange: (Boolean) -> Unit,
    onSelectSite: (Int?) -> Unit,
    onLocatorModeChange: (ArrayLocatorMode) -> Unit,
    onConfirmCurrentOrientation: () -> Unit,
    onOriginCornerChange: (ArrayOriginCorner) -> Unit,
    onRestoreAutomaticOrientation: () -> Unit,
    onNudgeSelectedSite: (Int, Int) -> Unit,
    onSetSelectedSiteRadius: (Double) -> Unit,
    onRestoreSelectedSite: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onManualCorners: (() -> Unit)? = null,
    attempt: ArrayLocatorAttempt? = null,
    onRetry: () -> Unit = {},
    onRestore: () -> Unit = {}
) {
    var adjustmentSiteIndex by remember { mutableStateOf<Int?>(null) }
    var displayFilter by rememberSaveable { mutableStateOf(ArrayDisplayFilterMode.ORIGINAL) }
    val result = state?.session?.result
    val isManual = state?.locatorMode == ArrayLocatorMode.MANUAL_CORNERS
    val selectedSite = state?.selectedSiteIndex?.let { result?.sites?.getOrNull(it) }
    val adjustmentSite = adjustmentSiteIndex?.let { result?.sites?.getOrNull(it) }
    ArrayLocalizationReview(
        scope = ArrayAnalysisScope.STANDARD_PLATE,
        selected = attempt?.mode ?: state?.locatorMode,
        attempt = attempt,
        onChooseLocator = onLocatorModeChange,
        onManualCorners = onManualCorners,
        onRetry = onRetry,
        onRestore = onRestore,
        onContinue = onContinue,
        canContinue = state?.orientationConfirmed == true,
        modifier = modifier.testTag(Plate96LocalizationTestTags.READY_CONTENT)
    ) {
        if (state != null && result != null) {
            val bitmap = if (state.imageViewMode == Plate96ImageViewMode.NORMALIZED) state.normalizedBitmap else state.sourceBitmap
            Plate96ImagePreview(
                bitmap = bitmap,
                displayFilter = displayFilter,
                sites = result.sites,
                normalized = state.imageViewMode == Plate96ImageViewMode.NORMALIZED,
                showOutlines = state.showOutlines,
                showLabels = state.showLabels,
                selectedSiteIndex = state.selectedSiteIndex,
                onSelectSite = { siteIndex ->
                    onSelectSite(siteIndex)
                    if (siteIndex != null && !isManual) adjustmentSiteIndex = siteIndex
                }
            )
            // 与芯片相同，图像在同一位置；方向与单孔微调仅在有正式孔板网格时出现。
            if (!isManual) Plate96OrientationCard(state, onConfirmCurrentOrientation, onOriginCornerChange, onRestoreAutomaticOrientation)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Plate96ViewSelector(
                    selected = state.imageViewMode,
                    onSelected = onImageViewChange
                )
                Plate96DisplayControls(
                    showOutlines = state.showOutlines,
                    showLabels = state.showLabels,
                    hasSelectedSite = selectedSite != null && !isManual,
                    onShowOutlinesChange = onShowOutlinesChange,
                    onShowLabelsChange = onShowLabelsChange,
                    onOpenAdjustment = { adjustmentSiteIndex = state.selectedSiteIndex }
                )
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
            if (isManual) {
                Text(stringResource(R.string.grid_manual_summary, result.sites.size - 4), style = MaterialTheme.typography.bodyMedium)
            } else {
                Plate96LocalizationSummary(state)
            }
        }
    }

    if (state != null && adjustmentSite != null) {
        Plate96AdjustmentSheet(
            state = state,
            site = adjustmentSite,
            onDismiss = { adjustmentSiteIndex = null },
            onNudge = onNudgeSelectedSite,
            onRadiusChange = onSetSelectedSiteRadius,
            onRestoreAutomatic = onRestoreSelectedSite
        )
    }
}

@Composable
private fun Plate96OrientationCard(
    state: Plate96LocalizationUiState.Ready,
    onConfirmCurrent: () -> Unit,
    onCornerChange: (ArrayOriginCorner) -> Unit,
    onRestoreAutomatic: () -> Unit
) {
    val orientation = state.session.result.orientation
    var menuExpanded by remember { mutableStateOf(false) }
    val compatibleCorners = if (orientation.sourceRows == 8) {
        listOf(ArrayOriginCorner.TOP_LEFT, ArrayOriginCorner.BOTTOM_RIGHT)
    } else {
        listOf(ArrayOriginCorner.BOTTOM_LEFT, ArrayOriginCorner.TOP_RIGHT)
    }
    // 方向未确认是"必须处理但可继续查看"的状态，属于警告语义。此前借用 tertiaryContainer
    // 表达，紫粉色既没有警告含义也与主色争夺注意力；改用语义警告色后，页面里"待确认"
    // 与其他页面的"需复核"呈现同一种颜色（AGENTS.md 7.2）。
    val confirmed = state.orientationConfirmed
    val statusContainer = if (confirmed) {
        FluoTheme.semantic.successContainer
    } else {
        FluoTheme.semantic.warningContainer
    }
    val statusContent = if (confirmed) {
        FluoTheme.semantic.onSuccessContainer
    } else {
        FluoTheme.semantic.onWarningContainer
    }
    FluoSectionCard(
        modifier = Modifier.testTag(Plate96LocalizationTestTags.ORIENTATION_CARD),
        accentColor = if (confirmed) FluoTheme.semantic.success else FluoTheme.semantic.warning
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.plate96_orientation_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FluoStatusChip(
                    text = stringResource(
                        if (confirmed) R.string.plate96_orientation_status_confirmed
                        else R.string.plate96_orientation_status_pending
                    ),
                    icon = if (confirmed) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentColor = statusContent,
                    containerColor = statusContainer
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Plate96OrientationMetric(
                    value = stringResource(R.string.plate96_orientation_dimensions, 8, 12),
                    label = stringResource(R.string.plate96_orientation_standard_label),
                    modifier = Modifier.weight(1f)
                )
                Plate96OrientationMetric(
                    value = stringResource(
                        R.string.plate96_orientation_dimensions,
                        orientation.sourceRows,
                        orientation.sourceColumns
                    ),
                    label = stringResource(R.string.plate96_orientation_original_label),
                    modifier = Modifier.weight(1f)
                )
                Plate96OrientationMetric(
                    value = originCornerText(orientation.originCorner),
                    label = stringResource(R.string.plate96_orientation_a1_label),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(FluoRadius.control)
                    ) {
                        Text(stringResource(R.string.plate96_orientation_adjust), maxLines = 1)
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(FluoIconSize.medium)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        compatibleCorners.forEach { corner ->
                            DropdownMenuItem(
                                text = { Text(originCornerText(corner)) },
                                onClick = {
                                    menuExpanded = false
                                    onCornerChange(corner)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.plate96_orientation_restore_auto)) },
                            onClick = {
                                menuExpanded = false
                                onRestoreAutomatic()
                            }
                        )
                    }
                }
                // 确认按钮只在未确认时出现，用 AnimatedVisibility 让它按标准节奏淡入淡出，
                // 避免确认后按钮"凭空消失"导致同一行控件宽度瞬间跳变。
                AnimatedVisibility(
                    visible = !confirmed,
                    modifier = Modifier.weight(1f),
                    enter = FluoMotion.expandEnter,
                    exit = FluoMotion.expandExit
                ) {
                    Button(
                        onClick = onConfirmCurrent,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(FluoRadius.control)
                    ) {
                        Text(stringResource(R.string.plate96_orientation_confirm_current), maxLines = 1)
                    }
                }
            }
    }
}

/** 方向卡中的紧凑指标，避免重复长句占用首屏。 */
@Composable
private fun Plate96OrientationMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun Plate96ViewSelector(
    selected: Plate96ImageViewMode,
    onSelected: (Plate96ImageViewMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == Plate96ImageViewMode.NORMALIZED,
            onClick = { onSelected(Plate96ImageViewMode.NORMALIZED) },
            label = { Text(stringResource(R.string.plate96_view_normalized)) }
        )
        FilterChip(
            selected = selected == Plate96ImageViewMode.ORIGINAL,
            onClick = { onSelected(Plate96ImageViewMode.ORIGINAL) },
            label = { Text(stringResource(R.string.plate96_view_original)) }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Plate96DisplayControls(
    showOutlines: Boolean,
    showLabels: Boolean,
    hasSelectedSite: Boolean,
    onShowOutlinesChange: (Boolean) -> Unit,
    onShowLabelsChange: (Boolean) -> Unit,
    onOpenAdjustment: () -> Unit
) {
    // 窄屏或长英文标签自动换行，以便微调入口不会挤到横向滚动区外。
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(Plate96LocalizationTestTags.DISPLAY_CONTROLS),
        horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
    ) {
        FilterChip(
            selected = showOutlines,
            onClick = { onShowOutlinesChange(!showOutlines) },
            label = { Text(stringResource(R.string.plate96_show_outlines)) }
        )
        FilterChip(
            selected = showLabels,
            onClick = { onShowLabelsChange(!showLabels) },
            label = { Text(stringResource(R.string.plate96_show_labels)) }
        )
        FilterChip(
            selected = false,
            enabled = hasSelectedSite,
            onClick = onOpenAdjustment,
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.plate96_manual_adjust)) },
            modifier = Modifier.testTag(Plate96LocalizationTestTags.MANUAL_ADJUST_BUTTON)
        )
    }
}

@Composable
private fun Plate96LocalizationSummary(state: Plate96LocalizationUiState.Ready) {
    val diagnostics = state.session.result.diagnostics
    val allObserved = diagnostics.imputedSiteCount == 0
    FluoSectionCard(
        modifier = Modifier.testTag(Plate96LocalizationTestTags.SUMMARY),
        contentPadding = FluoSpacing.md,
        verticalSpacing = FluoSpacing.sm,
        accentColor = if (allObserved) FluoTheme.semantic.success else MaterialTheme.colorScheme.primary
    ) {
        FluoSectionHeader(
            title = stringResource(R.string.grid_localization_summary_title),
            icon = Icons.Outlined.Analytics,
            accentColor = if (allObserved) FluoTheme.semantic.success else MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
        ) {
            Plate96Metric(
                R.string.plate96_summary_observed,
                diagnostics.observedSiteCount,
                Modifier.weight(1f)
            )
            Plate96Metric(
                R.string.plate96_summary_refined,
                diagnostics.shapeRefinedSiteCount,
                Modifier.weight(1f)
            )
            Plate96Metric(
                R.string.plate96_summary_imputed,
                diagnostics.imputedSiteCount,
                Modifier.weight(1f)
            )
        }
    }
}

/**
 * 定位摘要指标。
 *
 * 原实现把标签放在数值上方且两者字号接近，扫视时先读到的是标签而不是数字。改用共享
 * 指标块后数值优先、标签次之，三项指标的对齐、圆角和底色与其他页面一致。
 */
@Composable
private fun Plate96Metric(labelId: Int, value: Int, modifier: Modifier = Modifier) {
    FluoMetricTile(
        modifier = modifier,
        label = stringResource(labelId),
        value = stringResource(R.string.plate96_summary_value, value)
    )
}

/**
 * 单孔微调底部面板。
 *
 * 面板只提供受物理约束的像素级中心移动与半径调整，不暴露霍夫阈值等算法参数。用户看到的
 * 裁切预览与后续信号提取读取同一份圆形区域，避免“界面改了、科学计算没改”的状态分裂。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Plate96AdjustmentSheet(
    state: Plate96LocalizationUiState.Ready,
    site: ArrayLocalizedSite,
    onDismiss: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onRadiusChange: (Double) -> Unit,
    onRestoreAutomatic: () -> Unit
) {
    val bitmap = if (state.imageViewMode == Plate96ImageViewMode.NORMALIZED) {
        state.normalizedBitmap
    } else {
        state.sourceBitmap
    }
    val automaticRadius = state.session.automaticResult.sites
        .getOrNull(site.siteIndex)
        ?.radiusPx
        ?: site.radiusPx
        ?: 12.0
    val minimumRadius = (automaticRadius * 0.55).toFloat().coerceAtLeast(2f)
    val maximumRadius = (automaticRadius * 1.45).toFloat().coerceAtLeast(minimumRadius + 1f)
    var draftRadius by remember(site.siteIndex, site.radiusPx) {
        mutableFloatStateOf((site.radiusPx ?: automaticRadius).toFloat().coerceIn(minimumRadius, maximumRadius))
    }
    val isAdjusted = ArraySiteLocalizationSource.USER_ADJUSTED == site.source

    // 微调是主动打开的工具，不停在只露出把手的半展开态；横屏和大字体时可滚动到确认按钮。
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .testTag(Plate96LocalizationTestTags.ADJUSTMENT_SHEET),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.plate96_adjustment_title, site.displayLabel),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            if (isAdjusted) R.string.plate96_adjustment_status_manual
                            else R.string.plate96_adjustment_status_automatic
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAdjusted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Plate96SiteCropPreview(
                    bitmap = bitmap,
                    site = site,
                    normalized = state.imageViewMode == Plate96ImageViewMode.NORMALIZED
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.plate96_adjustment_center),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Plate96NudgePad(onNudge = onNudge)
                }
                Column(modifier = Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.plate96_adjustment_radius),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.plate96_adjustment_radius_value, draftRadius),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = draftRadius,
                        onValueChange = { draftRadius = it },
                        onValueChangeFinished = { onRadiusChange(draftRadius.toDouble()) },
                        valueRange = minimumRadius..maximumRadius,
                        modifier = Modifier.testTag(Plate96LocalizationTestTags.RADIUS_SLIDER)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRestoreAutomatic,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(Plate96LocalizationTestTags.RESTORE_AUTOMATIC)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.plate96_adjustment_restore), maxLines = 1)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(Plate96LocalizationTestTags.DONE)
                ) {
                    Text(stringResource(R.string.plate96_adjustment_done))
                }
            }
        }
    }
}

/** 以当前科学裁切边界绘制圆孔预览，掩膜外区域由圆形裁剪直接隐藏。 */
@Composable
private fun Plate96SiteCropPreview(
    bitmap: android.graphics.Bitmap,
    site: ArrayLocalizedSite,
    normalized: Boolean
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val bounds = if (normalized) site.normalizedRegion.bounds else site.sourceRegion.bounds
    val outlineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, outlineColor.copy(alpha = 0.75f), CircleShape)
            .testTag(Plate96LocalizationTestTags.CROP_PREVIEW)
    ) {
        drawImage(
            image = image,
            srcOffset = IntOffset(bounds.left, bounds.top),
            srcSize = IntSize(bounds.width, bounds.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        )
    }
}

/** 四向像素微调盘；中心留白避免用户误以为它是第五个动作。 */
@Composable
private fun Plate96NudgePad(onNudge: (Int, Int) -> Unit) {
    val upDescription = stringResource(R.string.plate96_adjustment_move_up)
    val downDescription = stringResource(R.string.plate96_adjustment_move_down)
    val leftDescription = stringResource(R.string.plate96_adjustment_move_left)
    val rightDescription = stringResource(R.string.plate96_adjustment_move_right)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = { onNudge(0, -1) },
            modifier = Modifier.testTag(Plate96LocalizationTestTags.NUDGE_UP)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = upDescription)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onNudge(-1, 0) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = leftDescription)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
            FilledTonalIconButton(
                onClick = { onNudge(1, 0) },
                modifier = Modifier.testTag(Plate96LocalizationTestTags.NUDGE_RIGHT)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = rightDescription)
            }
        }
        FilledTonalIconButton(onClick = { onNudge(0, 1) }) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = downDescription)
        }
    }
}

@Composable
private fun Plate96ImagePreview(
    bitmap: android.graphics.Bitmap,
    displayFilter: ArrayDisplayFilterMode = ArrayDisplayFilterMode.ORIGINAL,
    sites: List<ArrayLocalizedSite>,
    normalized: Boolean,
    showOutlines: Boolean,
    showLabels: Boolean,
    selectedSiteIndex: Int?,
    onSelectSite: (Int?) -> Unit
) {
    val rawImage = remember(bitmap) { bitmap.asImageBitmap() }
    val filteredImage by rememberArrayDisplayImage(bitmap, displayFilter)
    val image = if (displayFilter == ArrayDisplayFilterMode.ORIGINAL) rawImage else (filteredImage ?: rawImage)
    val outlineColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onPrimary
    val labelBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val contentDescription = stringResource(R.string.plate96_preview_content_description)
    ArrayLocalizationPreviewFrame { imageModifier ->
            Canvas(
                modifier = imageModifier
                    .testTag(Plate96LocalizationTestTags.PREVIEW)
                    .semantics { this.contentDescription = contentDescription }
                    .pointerInput(bitmap, normalized, sites) {
                        detectTapGestures { tap ->
                            val fit = imageFit(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                            val imageX = (tap.x - fit.offsetX) / fit.scale
                            val imageY = (tap.y - fit.offsetY) / fit.scale
                            if (imageX !in 0f..bitmap.width.toFloat() || imageY !in 0f..bitmap.height.toFloat()) {
                                onSelectSite(null)
                                return@detectTapGestures
                            }
                            val selected = sites.minByOrNull { site ->
                                val center = if (normalized) site.normalizedCenter else site.sourceCenter
                                hypot(center.x - imageX, center.y - imageY)
                            }
                            val center = selected?.let { if (normalized) it.normalizedCenter else it.sourceCenter }
                            val radius = selected?.radiusPx ?: 0.0
                            if (selected != null && center != null &&
                                hypot(center.x - imageX, center.y - imageY) <= radius * 1.6
                            ) {
                                onSelectSite(selected.siteIndex)
                            } else {
                                onSelectSite(null)
                            }
                        }
                    }
            ) {
                val fit = imageFit(size.width, size.height, bitmap.width, bitmap.height)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                    dstSize = IntSize(
                        (bitmap.width * fit.scale).roundToInt(),
                        (bitmap.height * fit.scale).roundToInt()
                    )
                )
                if (showOutlines || showLabels) {
                    sites.forEach { site ->
                        val center = if (normalized) site.normalizedCenter else site.sourceCenter
                        val screenCenter = Offset(
                            fit.offsetX + center.x.toFloat() * fit.scale,
                            fit.offsetY + center.y.toFloat() * fit.scale
                        )
                        val radius = (site.radiusPx ?: 1.0).toFloat() * fit.scale
                        val isSelected = site.siteIndex == selectedSiteIndex
                        if (showOutlines) {
                            drawCircle(
                                color = if (isSelected) selectedColor else outlineColor.copy(
                                    alpha = if (site.source == ArraySiteLocalizationSource.GRID_IMPUTED) 0.45f else 0.82f
                                ),
                                radius = radius,
                                center = screenCenter,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = if (isSelected) 3.dp.toPx() else 1.2.dp.toPx()
                                )
                            )
                        }
                        if (showLabels && radius >= 7f) {
                            val textSize = (radius * 0.52f).coerceIn(7f, 12f)
                            drawCircle(
                                color = labelBackground,
                                radius = textSize * 0.95f,
                                center = screenCenter
                            )
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(
                                    site.displayLabel,
                                    screenCenter.x,
                                    screenCenter.y + textSize * 0.34f,
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = labelColor.toArgb()
                                        textAlign = Paint.Align.CENTER
                                        this.textSize = textSize
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                )
                            }
                        }
                    }
                }
            }
    }
}

private data class ImageFit(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun imageFit(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int): ImageFit {
    val scale = min(canvasWidth / imageWidth, canvasHeight / imageHeight)
    return ImageFit(
        scale = scale,
        offsetX = (canvasWidth - imageWidth * scale) / 2f,
        offsetY = (canvasHeight - imageHeight * scale) / 2f
    )
}

@Composable
private fun Plate96LoadingContent(stage: Plate96LocalizationStage, modifier: Modifier = Modifier) {
    val label = when (stage) {
        Plate96LocalizationStage.LOADING_IMAGE -> R.string.plate96_stage_loading_image
        Plate96LocalizationStage.LOCATING -> R.string.plate96_stage_locating
        Plate96LocalizationStage.PREPARING_PREVIEW -> R.string.plate96_stage_preparing_preview
    }
    // 定位耗时较长，阶段文本必须如实反映当前处于哪一步，不能用无限动画掩盖无响应
    // （AGENTS.md 9.2）。三个阶段文案由 ViewModel 驱动，此处只负责呈现。
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FluoStatePlaceholder(text = stringResource(label))
    }
}

@Composable
private fun Plate96ErrorContent(
    reason: Plate96LocalizationError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FluoStatePlaceholder(
            text = stringResource(
                if (reason == Plate96LocalizationError.IMAGE_LOAD_FAILED) R.string.plate96_error_image
                else R.string.plate96_error_localization
            ),
            icon = Icons.Outlined.ErrorOutline,
            iconTint = MaterialTheme.colorScheme.error,
            actionText = stringResource(R.string.plate96_retry),
            onAction = onRetry
        )
    }
}

@Composable
private fun originCornerText(corner: ArrayOriginCorner): String {
    return stringResource(
        when (corner) {
            ArrayOriginCorner.TOP_LEFT -> R.string.plate96_origin_top_left
            ArrayOriginCorner.TOP_RIGHT -> R.string.plate96_origin_top_right
            ArrayOriginCorner.BOTTOM_LEFT -> R.string.plate96_origin_bottom_left
            ArrayOriginCorner.BOTTOM_RIGHT -> R.string.plate96_origin_bottom_right
        }
    )
}
