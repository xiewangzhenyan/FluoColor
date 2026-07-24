package com.muc.fluocolorquant.ui.screens.detection.plate96

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizedSite
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.ui.viewmodels.Plate96ImageViewMode
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationError
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationStage
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationUiState
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationViewModel
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** 定位页稳定测试标签；设备测试和截图自审使用同一组入口。 */
object Plate96LocalizationTestTags {
    const val READY_CONTENT: String = "plate96_localization_ready"
    const val CONTENT_LIST: String = "plate96_localization_content_list"
    const val ORIENTATION_CARD: String = "plate96_orientation_card"
    const val PREVIEW: String = "plate96_localization_preview"
    const val DISPLAY_CONTROLS: String = "plate96_display_controls"
    const val MANUAL_ADJUST_BUTTON: String = "plate96_manual_adjust_button"
    const val ALGORITHM_SELECTOR: String = "plate96_algorithm_selector"
    const val SUMMARY: String = "plate96_localization_summary"
    const val CONTINUE_BUTTON: String = "plate96_continue_button"
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
    onContinue: (Plate96Locator.Session) -> Unit,
    viewModel: Plate96LocalizationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(imageUri) { viewModel.start(imageUri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plate96_localization_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            is Plate96LocalizationUiState.Ready -> Plate96ReadyContent(
                state = current,
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
                onContinue = { onContinue(current.session) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
internal fun Plate96ReadyContent(
    state: Plate96LocalizationUiState.Ready,
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
    modifier: Modifier = Modifier
) {
    val result = state.session.result
    var adjustmentSiteIndex by remember { mutableStateOf<Int?>(null) }
    val selectedSite = state.selectedSiteIndex?.let(result.sites::getOrNull)
    val adjustmentSite = adjustmentSiteIndex?.let(result.sites::getOrNull)
    Box(modifier = modifier.fillMaxSize().testTag(Plate96LocalizationTestTags.READY_CONTENT)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(Plate96LocalizationTestTags.CONTENT_LIST),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Plate96StepHeader() }
            item {
                Plate96OrientationCard(
                    state = state,
                    onConfirmCurrent = onConfirmCurrentOrientation,
                    onCornerChange = onOriginCornerChange,
                    onRestoreAutomatic = onRestoreAutomaticOrientation
                )
            }
            item {
                Plate96ViewSelector(
                    selected = state.imageViewMode,
                    onSelected = onImageViewChange
                )
            }
            item {
                val bitmap = if (state.imageViewMode == Plate96ImageViewMode.NORMALIZED) {
                    state.normalizedBitmap
                } else {
                    state.sourceBitmap
                }
                Plate96ImagePreview(
                    bitmap = bitmap,
                    sites = result.sites,
                    normalized = state.imageViewMode == Plate96ImageViewMode.NORMALIZED,
                    showOutlines = state.showOutlines,
                    showLabels = state.showLabels,
                    selectedSiteIndex = state.selectedSiteIndex,
                    onSelectSite = { siteIndex ->
                        onSelectSite(siteIndex)
                        if (siteIndex != null) adjustmentSiteIndex = siteIndex
                    }
                )
            }
            item {
                Plate96DisplayControls(
                    showOutlines = state.showOutlines,
                    showLabels = state.showLabels,
                    hasSelectedSite = selectedSite != null,
                    onShowOutlinesChange = onShowOutlinesChange,
                    onShowLabelsChange = onShowLabelsChange,
                    onOpenAdjustment = { adjustmentSiteIndex = state.selectedSiteIndex }
                )
            }
            item {
                Plate96AlgorithmSelector(
                    selected = state.locatorMode,
                    onSelected = onLocatorModeChange
                )
            }
            item {
                Plate96LocalizationSummary(state)
            }
            item {
                Button(
                    onClick = onContinue,
                    enabled = state.orientationConfirmed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(Plate96LocalizationTestTags.CONTINUE_BUTTON)
                ) {
                    Text(stringResource(R.string.plate96_confirm_and_continue))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (adjustmentSite != null) {
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
private fun Plate96StepHeader() {
    val steps = listOf(
        R.string.plate96_step_localization,
        R.string.plate96_step_layout,
        R.string.plate96_step_quantitation
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEachIndexed { index, label ->
            OutlinedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (index == 0) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = stringResource(label),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == 0) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Plate96LocalizationTestTags.ORIENTATION_CARD)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                Surface(
                    shape = CircleShape,
                    color = if (state.orientationConfirmed) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (state.orientationConfirmed) Icons.Default.CheckCircle
                            else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (state.orientationConfirmed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = stringResource(
                                if (state.orientationConfirmed) R.string.plate96_orientation_status_confirmed
                                else R.string.plate96_orientation_status_pending
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.orientationConfirmed) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.plate96_orientation_adjust), maxLines = 1)
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
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
                if (!state.orientationConfirmed) {
                    Button(
                        onClick = onConfirmCurrent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.plate96_orientation_confirm_current), maxLines = 1)
                    }
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
private fun Plate96DisplayControls(
    showOutlines: Boolean,
    showLabels: Boolean,
    hasSelectedSite: Boolean,
    onShowOutlinesChange: (Boolean) -> Unit,
    onShowLabelsChange: (Boolean) -> Unit,
    onOpenAdjustment: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Plate96LocalizationTestTags.DISPLAY_CONTROLS)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plate96_display_title), fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = showOutlines,
                    onClick = { onShowOutlinesChange(!showOutlines) },
                    label = { Text(stringResource(R.string.plate96_show_outlines), maxLines = 1) }
                )
                FilterChip(
                    selected = showLabels,
                    onClick = { onShowLabelsChange(!showLabels) },
                    label = { Text(stringResource(R.string.plate96_show_labels), maxLines = 1) }
                )
                FilterChip(
                    selected = false,
                    enabled = hasSelectedSite,
                    onClick = onOpenAdjustment,
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.plate96_manual_adjust), maxLines = 1) },
                    modifier = Modifier.testTag(Plate96LocalizationTestTags.MANUAL_ADJUST_BUTTON)
                )
            }
        }
    }
}

@Composable
private fun Plate96AlgorithmSelector(
    selected: ArrayLocatorMode,
    onSelected: (ArrayLocatorMode) -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Plate96LocalizationTestTags.ALGORITHM_SELECTOR)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plate96_algorithm_title), fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    ArrayLocatorMode.AUTO to R.string.plate96_algorithm_auto,
                    ArrayLocatorMode.OBJECT_DETECTION to R.string.plate96_algorithm_object,
                    ArrayLocatorMode.GEOMETRIC_SHAPE to R.string.plate96_algorithm_circle
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        label = { Text(stringResource(label), maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Plate96LocalizationSummary(state: Plate96LocalizationUiState.Ready) {
    val diagnostics = state.session.result.diagnostics
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Plate96LocalizationTestTags.SUMMARY)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.plate96_summary_title), fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
            Text(
                text = state.selectedSiteIndex?.let { index ->
                    stringResource(R.string.plate96_selected_well, state.session.result.sites[index].displayLabel)
                } ?: stringResource(R.string.plate96_no_well_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Plate96Metric(labelId: Int, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(labelId), style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.plate96_summary_value, value),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
    sites: List<ArrayLocalizedSite>,
    normalized: Boolean,
    showOutlines: Boolean,
    showLabels: Boolean,
    selectedSiteIndex: Int?,
    onSelectSite: (Int?) -> Unit
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val outlineColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onPrimary
    val labelBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val contentDescription = stringResource(R.string.plate96_preview_content_description)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val previewHeight = (maxWidth / imageAspectRatio).coerceIn(220.dp, 420.dp)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .background(Color.Black.copy(alpha = 0.04f))
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Plate96ErrorContent(
    reason: Plate96LocalizationError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(
                    if (reason == Plate96LocalizationError.IMAGE_LOAD_FAILED) R.string.plate96_error_image
                    else R.string.plate96_error_localization
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.plate96_retry)) }
        }
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
