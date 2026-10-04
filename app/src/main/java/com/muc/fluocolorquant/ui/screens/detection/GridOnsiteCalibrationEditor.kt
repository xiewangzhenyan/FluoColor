package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidate
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidateStatus
import com.muc.fluocolorquant.domain.calibration.CalibrationApplicationDecision
import com.muc.fluocolorquant.domain.calibration.CalibrationFailureReason
import com.muc.fluocolorquant.domain.calibration.CalibrationFunctionResult
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.OnsiteCalibrationState
import com.muc.fluocolorquant.domain.calibration.applicationDecision
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.domain.detection.isConfigurationComplete
import com.muc.fluocolorquant.domain.detection.isReadyForConfirmation
import com.muc.fluocolorquant.domain.signal.SignalFeatureTier
import com.muc.fluocolorquant.ui.components.LatexAlignment
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.analysisFeatureIcon
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import com.muc.fluocolorquant.ui.theme.FluoRadius

/**
 * 孔位布局页底部的“实验模板 / 手动配置”与逐分析物定量方案。
 *
 * 视觉上保持科研工具的克制与高信息密度：首层只做来源决策，第二层才展示每个分析物的
 * 四种定量方式。普通用户不会看到模型文件名、SHA、输入宽高或拟合参数 JSON。
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * 现场拟合的参数编辑与标准浓度录入界面。
 *
 * 本文件只管理 Compose 展示和用户输入，候选计算仍由 ViewModel/领域标定引擎完成。
 */

@Composable
internal fun OnsiteCalibrationDialog(
    preview: GridLocalizationPreview,
    analyte: GridLocalizationAnalyte,
    draft: GridAnalyteQuantitationDraft,
    assignments: Map<Int, GridLayoutAssignmentDraft>,
    detectionMode: DetectionModality?,
    onUpdateAdvanced: (Set<AnalysisPrimaryFeature>, Set<FittingFunction>) -> Unit,
    onUpdateConcentrations: (Map<Int, Double?>) -> Unit,
    onPreviewFit: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onSetSaveToLibrary: (Boolean) -> Unit,
    onBackToEditing: () -> Unit,
    onApply: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val standards = assignments.entries
        .filter { (_, assignment) ->
            assignment.analyteId == analyte.id && assignment.role == TemplateSiteRole.STANDARD
        }
        .sortedBy(Map.Entry<Int, GridLayoutAssignmentDraft>::key)
    var values by remember(standards, analyte.id) {
        mutableStateOf(
            standards.associate { (siteIndex, assignment) ->
                siteIndex to assignment.standardConcentration?.let(::formatEditableNumber).orEmpty()
            }
        )
    }
    var showBatchFill by rememberSaveable(analyte.id) { mutableStateOf(false) }
    var showAdvancedSignal by rememberSaveable(analyte.id) { mutableStateOf(false) }
    var showFunctionPicker by rememberSaveable(analyte.id) { mutableStateOf(false) }
    var showSignalPicker by rememberSaveable(analyte.id) { mutableStateOf(false) }
    var gradientStart by rememberSaveable(analyte.id) { mutableStateOf("") }
    var gradientStep by rememberSaveable(analyte.id) { mutableStateOf("") }
    val crops = remember(preview.runId, preview.rectifiedImagePath, preview.sites) {
        buildRealSiteCrops(preview).associateBy { it.site.siteIndex }
    }
    DisposableEffect(crops) {
        onDispose {
            crops.values.forEach { crop ->
                if (!crop.bitmap.isRecycled) crop.bitmap.recycle()
            }
        }
    }

    val parsedValues = values.mapValues { (_, text) ->
        parseStandardConcentration(text, analyte.maxConcentration)
    }
    val invalidCount = values.count { (_, text) ->
        text.isNotBlank() && parseStandardConcentration(text, analyte.maxConcentration) == null
    }
    val validConcentrations = parsedValues.values.filterNotNull()
    val concentrationLevelCount = validConcentrations.distinct().size
    val fittingInProgress = draft.onsiteState is OnsiteCalibrationState.Fitting
    val resultSet = when (val state = draft.onsiteState) {
        is OnsiteCalibrationState.Reviewing -> state.resultSet
        is OnsiteCalibrationState.Applying -> state.resultSet
        is OnsiteCalibrationState.Applied -> state.resultSet
        else -> null
    }
    val selectedCandidateId = when (val state = draft.onsiteState) {
        is OnsiteCalibrationState.Reviewing -> state.selectedCandidateId
        is OnsiteCalibrationState.Applying -> state.selectedCandidateId
        is OnsiteCalibrationState.Applied -> state.selectedCandidateId
        else -> null
    }
    val saveToLibrary = when (val state = draft.onsiteState) {
        is OnsiteCalibrationState.Reviewing -> state.saveToLibrary
        is OnsiteCalibrationState.Applying -> state.saveToLibrary
        is OnsiteCalibrationState.Applied -> state.snapshot.sourceResourceId != null
        else -> false
    }
    val applying = draft.onsiteState is OnsiteCalibrationState.Applying
    var observedApplying by remember(analyte.id) { mutableStateOf(false) }

    LaunchedEffect(draft.onsiteState) {
        when (draft.onsiteState) {
            is OnsiteCalibrationState.Applying -> observedApplying = true
            is OnsiteCalibrationState.Applied -> {
                if (observedApplying) {
                    // 应用完成后立即回到布局主流程，避免弹窗停留在不可点击的“已应用”页。
                    // 用户以后主动点“查看拟合结果”时仍可重新打开，因为那次没有经历 Applying。
                    observedApplying = false
                    onDismiss()
                }
            }
            else -> Unit
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            shape = RoundedCornerShape(FluoRadius.sheet),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.grid_quant_onsite_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.grid_quant_analyte_title,
                                analyte.name,
                                analyte.concentrationUnit
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                if (resultSet != null) {
                    OnsiteCalibrationResultStage(
                        resultSet = resultSet,
                        selectedCandidateId = selectedCandidateId,
                        concentrationUnit = analyte.concentrationUnit,
                        saveToLibrary = saveToLibrary,
                        applying = applying,
                        alreadyApplied = draft.onsiteState is OnsiteCalibrationState.Applied,
                        onSelectCandidate = onSelectCandidate,
                        onSetSaveToLibrary = onSetSaveToLibrary,
                        onBackToEditing = onBackToEditing,
                        onApply = onApply
                    )
                } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(FluoRadius.control),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.grid_quant_standard_entry_progress,
                                        validConcentrations.size,
                                        standards.size,
                                        concentrationLevelCount
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                analyte.maxConcentration?.let { maximum ->
                                    Text(
                                        text = stringResource(
                                            R.string.grid_quant_maximum_concentration,
                                            formatEditableNumber(maximum),
                                            analyte.concentrationUnit
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        TextButton(onClick = { showBatchFill = !showBatchFill }) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.grid_quant_batch_gradient))
                        }
                    }

                    if (showBatchFill) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompactDecimalField(
                                    value = gradientStart,
                                    onValueChange = { gradientStart = it },
                                    label = stringResource(R.string.grid_quant_gradient_start),
                                    modifier = Modifier.weight(1f)
                                )
                                CompactDecimalField(
                                    value = gradientStep,
                                    onValueChange = { gradientStep = it },
                                    label = stringResource(R.string.grid_quant_gradient_step),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        val start = gradientStart.toDoubleOrNull() ?: return@Button
                                        val step = gradientStep.toDoubleOrNull() ?: return@Button
                                        values = standards.mapIndexed { index, (siteIndex, _) ->
                                            val generated = start + step * index
                                            siteIndex to generated.takeIf { value ->
                                                value.isFinite() && value >= 0.0 &&
                                                    (analyte.maxConcentration == null ||
                                                        value <= analyte.maxConcentration)
                                            }?.let(::formatEditableNumber).orEmpty()
                                        }.toMap()
                                    },
                                    enabled = gradientStart.toDoubleOrNull()?.isFinite() == true &&
                                        gradientStep.toDoubleOrNull()?.isFinite() == true
                                ) {
                                    Text(stringResource(R.string.grid_quant_fill))
                                }
                            }
                        }
                    }

                    item {
                        CalibrationSelectionField(
                            title = stringResource(R.string.grid_quant_function_title),
                            summary = if (draft.selectedFunctions.isEmpty()) {
                                stringResource(
                                    R.string.grid_quant_function_auto_summary,
                                    com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
                                        .DEFAULT_FUNCTIONS.size
                                )
                            } else {
                                stringResource(
                                    R.string.grid_quant_selected_count,
                                    draft.selectedFunctions.size
                                )
                            },
                            onClick = { showFunctionPicker = true }
                        )
                    }

                    item {
                        TextButton(onClick = { showAdvancedSignal = !showAdvancedSignal }) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.grid_quant_advanced_signal))
                        }
                    }

                    if (showAdvancedSignal) {
                        item {
                            CalibrationSelectionField(
                                title = stringResource(R.string.grid_quant_signal_title),
                                summary = if (draft.selectedFeatures.isEmpty()) {
                                    stringResource(
                                        R.string.grid_quant_signal_auto_summary,
                                        detectionMode?.let {
                                            AnalysisFeaturePolicy.recommendedFeatures(it).size
                                        } ?: 0
                                    )
                                } else {
                                    stringResource(
                                        R.string.grid_quant_selected_count,
                                        draft.selectedFeatures.size
                                    )
                                },
                                supportingText = stringResource(
                                    if (detectionMode == DetectionModality.FLUORESCENCE) {
                                        R.string.grid_quant_fluorescence_feature_summary
                                    } else {
                                        R.string.grid_quant_classic_gray_formula
                                    }
                                ),
                                onClick = { showSignalPicker = true }
                            )
                        }
                    }

                    if (standards.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(FluoRadius.control),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.grid_quant_no_standard_sites),
                                    modifier = Modifier.padding(14.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    } else {
                        items(standards, key = { it.key }) { (siteIndex, assignment) ->
                            StandardConcentrationInputRow(
                                assignment = assignment,
                                crop = crops[siteIndex],
                                unit = analyte.concentrationUnit,
                                maximum = analyte.maxConcentration,
                                value = values[siteIndex].orEmpty(),
                                onValueChange = { changed ->
                                    values = values + (siteIndex to changed)
                                }
                            )
                        }
                    }

                    if (invalidCount > 0) {
                        item {
                            Text(
                                text = stringResource(R.string.grid_quant_invalid_standard_values),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (draft.onsiteState is OnsiteCalibrationState.TechnicalFailure) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(FluoRadius.control),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.grid_quant_fit_technical_failure),
                                    modifier = Modifier.padding(14.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            onUpdateConcentrations(parsedValues)
                            onPreviewFit()
                        },
                        enabled = !fittingInProgress && invalidCount == 0 &&
                            concentrationLevelCount >= 2,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (fittingInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.AutoGraph, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (fittingInProgress) R.string.grid_quant_fitting
                                else R.string.grid_quant_start_fit
                            )
                        )
                    }
                }
                }
            }
        }
    }

    if (showFunctionPicker) {
        FittingFunctionMultiSelectDialog(
            selected = draft.selectedFunctions,
            onConfirm = { functions ->
                showFunctionPicker = false
                onUpdateAdvanced(draft.selectedFeatures, functions)
            },
            onDismiss = { showFunctionPicker = false }
        )
    }
    if (showSignalPicker && detectionMode != null) {
        SignalFeatureMultiSelectDialog(
            detectionMode = detectionMode,
            selected = draft.selectedFeatures,
            onConfirm = { features ->
                showSignalPicker = false
                onUpdateAdvanced(features, draft.selectedFunctions)
            },
            onDismiss = { showSignalPicker = false }
        )
    }
}

@Composable
private fun StandardConcentrationInputRow(
    assignment: GridLayoutAssignmentDraft,
    crop: RealSiteCropBitmap?,
    unit: String,
    maximum: Double?,
    value: String,
    onValueChange: (String) -> Unit
) {
    val invalid = value.isNotBlank() && parseStandardConcentration(value, maximum) == null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = siteCoordinateLabel(assignment.rowIndex, assignment.columnIndex),
            modifier = Modifier.width(38.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(FluoRadius.badge),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (crop != null) {
                Image(
                    bitmap = crop.bitmap.asImageBitmap(),
                    contentDescription = siteCoordinateLabel(
                        assignment.rowIndex,
                        assignment.columnIndex
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { changed ->
                val normalized = changed.replace(',', '.')
                if (normalized.matches(STANDARD_CONCENTRATION_PATTERN)) {
                    onValueChange(normalized)
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = invalid,
            label = { Text(stringResource(R.string.concentration)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        // Material 3 的 suffix 在空值且未聚焦时会隐藏；单位独立成固定列，科研录入时始终可见。
        Text(
            text = unit,
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactDecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { changed ->
            val normalized = changed.replace(',', '.')
            if (normalized.matches(SIGNED_DECIMAL_PATTERN)) onValueChange(normalized)
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

/**
 * 现场标定首屏只显示紧凑摘要，完整候选放入多选弹窗，避免31种信号和近20种函数挤占
 * 浓度录入区域。卡片使用同一视觉结构，96孔板与微流控不会出现两套不同交互。
 */
@Composable
private fun CalibrationSelectionField(
    title: String,
    summary: String,
    onClick: () -> Unit,
    supportingText: String? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                supportingText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 函数多选弹窗：默认池与专家池分层，公式由项目既有 jlatexmath 组件真实排版。 */
@Composable
private fun FittingFunctionMultiSelectDialog(
    selected: Set<FittingFunction>,
    onConfirm: (Set<FittingFunction>) -> Unit,
    onDismiss: () -> Unit
) {
    var pending by remember(selected) { mutableStateOf(selected) }
    val automaticFunctions = com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
        .DEFAULT_FUNCTIONS
    val expertFunctions = remember {
        FittingFunction.entries.filter { function ->
            function != FittingFunction.INTERPOLATION && function !in automaticFunctions
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grid_quant_function_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AutomaticSelectionRow(
                        selected = pending.isEmpty(),
                        title = stringResource(R.string.grid_quant_function_auto_title),
                        description = stringResource(
                            R.string.grid_quant_function_auto_summary,
                            automaticFunctions.size
                        ),
                        onClick = { pending = emptySet() }
                    )
                }
                item { PickerGroupTitle(stringResource(R.string.grid_quant_recommended_group)) }
                items(automaticFunctions.toList(), key = FittingFunction::identifier) { function ->
                    FunctionSelectionRow(
                        function = function,
                        checked = function in pending,
                        onToggle = {
                            pending = pending.toggle(function)
                        }
                    )
                }
                item { PickerGroupTitle(stringResource(R.string.grid_quant_expert_group)) }
                items(expertFunctions, key = FittingFunction::identifier) { function ->
                    FunctionSelectionRow(
                        function = function,
                        checked = function in pending,
                        onToggle = { pending = pending.toggle(function) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 信号多选弹窗：按推荐、扩展、兼容和实验分层，经典灰度始终位于推荐组首项。 */
@Composable
private fun SignalFeatureMultiSelectDialog(
    detectionMode: DetectionModality,
    selected: Set<AnalysisPrimaryFeature>,
    onConfirm: (Set<AnalysisPrimaryFeature>) -> Unit,
    onDismiss: () -> Unit
) {
    var pending by remember(selected, detectionMode) { mutableStateOf(selected) }
    val allowed = remember(detectionMode) { AnalysisFeaturePolicy.allowedFeatures(detectionMode) }
    val groups = remember(detectionMode) {
        SignalFeatureTier.entries.mapNotNull { tier ->
            val features = allowed.filter { AnalysisFeaturePolicy.featureTier(it) == tier }
            (tier to features).takeIf { features.isNotEmpty() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grid_quant_signal_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AutomaticSelectionRow(
                        selected = pending.isEmpty(),
                        title = stringResource(R.string.grid_quant_signal_auto_title),
                        description = stringResource(
                            R.string.grid_quant_signal_auto_summary,
                            AnalysisFeaturePolicy.recommendedFeatures(detectionMode).size
                        ),
                        onClick = { pending = emptySet() }
                    )
                }
                groups.forEach { (tier, features) ->
                    item { PickerGroupTitle(signalTierLabel(tier)) }
                    items(features, key = AnalysisPrimaryFeature::code) { feature ->
                        SignalSelectionRow(
                            feature = feature,
                            checked = feature in pending,
                            onToggle = { pending = pending.toggle(feature) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun AutomaticSelectionRow(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PickerGroupTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FunctionSelectionRow(
    function: FittingFunction,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(fittingFunctionLabel(function), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.grid_quant_function_min_levels,
                        minimumConcentrationLevels(function)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LatexView(
                    latex = function.latexFormula,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 30.dp),
                    textSize = 13.sp,
                    alignment = LatexAlignment.START
                )
            }
        }
    }
}

@Composable
private fun SignalSelectionRow(
    feature: AnalysisPrimaryFeature,
    checked: Boolean,
    onToggle: () -> Unit
) {
    // 信号选择器是高频操作界面，不是信号定义手册。这里只保留稳定短名称；经典灰度的
    // 数学公式已经包含在名称资源中。完整定义、处理器版本和适用条件继续由用户手册、
    // 曲线详情及运行快照承担，避免三十余项特征的重复说明拖慢科研用户的选择效率。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Surface(
                shape = RoundedCornerShape(FluoRadius.chip),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
            ) {
                Icon(
                    imageVector = analysisFeatureIcon(feature),
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = analysisFeatureLabel(feature),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun signalTierLabel(tier: SignalFeatureTier): String = stringResource(
    when (tier) {
        SignalFeatureTier.RECOMMENDED -> R.string.grid_quant_recommended_group
        SignalFeatureTier.EXTENDED -> R.string.grid_quant_extended_group
        SignalFeatureTier.LEGACY -> R.string.grid_quant_compatibility_group
        SignalFeatureTier.EXPERIMENTAL -> R.string.grid_quant_experimental_group
    }
)

private fun minimumConcentrationLevels(function: FittingFunction): Int = when (function) {
    FittingFunction.RODBARD -> 5
    FittingFunction.LOGISTIC -> 6
    FittingFunction.LINEAR -> 2
    else -> maxOf(2, function.requiredParams.size + 1)
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value

internal fun parseStandardConcentration(value: String, maximum: Double?): Double? {
    if (value.isBlank()) return null
    return value.toDoubleOrNull()?.takeIf { parsed ->
        parsed.isFinite() && parsed >= 0.0 && (maximum == null || parsed <= maximum)
    }
}

internal fun formatEditableNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private val STANDARD_CONCENTRATION_PATTERN = Regex("\\d{0,12}(\\.\\d{0,6})?")
private val SIGNED_DECIMAL_PATTERN = Regex("-?\\d{0,12}(\\.\\d{0,6})?")
