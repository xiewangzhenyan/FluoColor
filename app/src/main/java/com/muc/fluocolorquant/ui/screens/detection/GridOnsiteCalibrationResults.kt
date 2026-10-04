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
import androidx.compose.foundation.lazy.itemsIndexed
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
 * 现场拟合候选结果、质量门槛与应用确认界面。
 *
 * 这里仅解释已经生成的候选，不在 Composable 中重新拟合或改写科学数值。
 */

@Composable
internal fun ColumnScope.OnsiteCalibrationResultStage(
    resultSet: CalibrationResultSet,
    selectedCandidateId: String?,
    concentrationUnit: String,
    saveToLibrary: Boolean,
    applying: Boolean,
    alreadyApplied: Boolean,
    onSelectCandidate: (String) -> Unit,
    onSetSaveToLibrary: (Boolean) -> Unit,
    onBackToEditing: () -> Unit,
    onApply: (Boolean) -> Unit
) {
    val selectedCandidate = resultSet.candidate(selectedCandidateId)
    val applicationDecision = selectedCandidate?.let { candidate ->
        resultSet.policySnapshot.applicationDecision(candidate.accepted)
    }
    var showLowQualityConfirmation by remember(
        resultSet.inputFingerprint,
        selectedCandidateId
    ) { mutableStateOf(false) }
    var showComparison by rememberSaveable(resultSet.inputFingerprint) {
        mutableStateOf(false)
    }
    val functionListState = rememberLazyListState()
    val selectedFunctionIndex = resultSet.functionResults.indexOfFirst { functionResult ->
        functionResult.candidate?.id == selectedCandidateId
    }
    LaunchedEffect(resultSet.inputFingerprint, selectedFunctionIndex) {
        if (selectedFunctionIndex >= 0) {
            functionListState.animateScrollToItem(selectedFunctionIndex)
        }
    }
    val requestApply: () -> Unit = {
        when (applicationDecision) {
            CalibrationApplicationDecision.REQUIRE_CONFIRMATION ->
                showLowQualityConfirmation = true
            CalibrationApplicationDecision.BLOCK, null -> Unit
            CalibrationApplicationDecision.APPLY -> onApply(false)
        }
    }
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OnsiteCalibrationSummaryCard(
                candidate = selectedCandidate,
                isRecommended = selectedCandidate?.id == resultSet.recommendedCandidateId,
                applicationDecision = applicationDecision,
                concentrationUnit = concentrationUnit,
                projectRangeMin = resultSet.projectRangeMin,
                projectRangeMax = resultSet.projectRangeMax,
                saveToLibrary = saveToLibrary,
                applying = applying,
                alreadyApplied = alreadyApplied,
                showComparison = showComparison,
                onToggleComparison = { showComparison = !showComparison },
                onApply = requestApply
            )
        }
        if (selectedCandidate != null) {
            item {
                AnimatedVisibility(visible = showComparison) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.grid_quant_candidate_comparison),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        /*
                         * 候选模型数量会随自动/专家模式变化，固定宽度横向列表可避免中英文
                         * 函数名在 360dp 屏幕被强行压扁；默认折叠，不占用现场录入主流程。
                         */
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            state = functionListState,
                            contentPadding = PaddingValues(horizontal = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = resultSet.functionResults,
                                key = { _, functionResult -> functionResult.function.identifier }
                            ) { index, functionResult ->
                                CalibrationFunctionOption(
                                    result = functionResult,
                                    rank = if (functionResult.candidate != null) index + 1 else null,
                                    selected = functionResult.candidate?.id == selectedCandidateId,
                                    recommended = functionResult.candidate?.id ==
                                        resultSet.recommendedCandidateId,
                                    onClick = {
                                        functionResult.candidate?.id?.let(onSelectCandidate)
                                    },
                                    modifier = Modifier.width(86.dp)
                                )
                            }
                        }
                        OnsiteFitCandidateDetails(
                            preview = selectedCandidate,
                            concentrationUnit = concentrationUnit
                        )
                        if (!selectedCandidate.accepted) {
                            Text(
                                text = stringResource(
                                    when (applicationDecision) {
                                        CalibrationApplicationDecision.BLOCK ->
                                            R.string.grid_quant_low_quality_view_only_hint
                                        CalibrationApplicationDecision.REQUIRE_CONFIRMATION ->
                                            R.string.grid_quant_low_quality_confirm_hint
                                        else -> R.string.grid_quant_low_quality_allow_hint
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            item {
                CalibrationUnavailableSummary(resultSet.functionResults)
            }
        }
    }
    HorizontalDivider()
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.grid_quant_save_curve_switch),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = stringResource(R.string.grid_quant_save_curve_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = saveToLibrary,
                onCheckedChange = onSetSaveToLibrary,
                enabled = selectedCandidate != null && !applying && !alreadyApplied
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onBackToEditing,
                enabled = !applying
            ) {
                Icon(Icons.Outlined.EditNote, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grid_quant_back_to_edit))
            }
        }
    }
    if (showLowQualityConfirmation) {
        AlertDialog(
            onDismissRequest = { showLowQualityConfirmation = false },
            title = { Text(stringResource(R.string.grid_quant_low_quality_confirm_title)) },
            text = { Text(stringResource(R.string.grid_quant_low_quality_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showLowQualityConfirmation = false
                        onApply(true)
                    }
                ) {
                    Text(stringResource(R.string.grid_quant_low_quality_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLowQualityConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 现场拟合首屏直接回答“系统推荐哪条曲线、R²如何、覆盖什么范围、公式和曲线是什么”。
 * 留一、端点、标准浓度复算等指标只在存在真实值时进入高级验证；应用动作始终针对当前候选。
 */
@Composable
private fun OnsiteCalibrationSummaryCard(
    candidate: CalibrationCandidate?,
    isRecommended: Boolean,
    applicationDecision: CalibrationApplicationDecision?,
    concentrationUnit: String,
    projectRangeMin: Double?,
    projectRangeMax: Double?,
    saveToLibrary: Boolean,
    applying: Boolean,
    alreadyApplied: Boolean,
    showComparison: Boolean,
    onToggleComparison: () -> Unit,
    onApply: () -> Unit
) {
    val calibrationConcentrations = candidate?.standardPoints
        .orEmpty()
        .map { point -> point.first }
        .filter(Double::isFinite)
    val calibrationRange = if (calibrationConcentrations.isNotEmpty()) {
        stringResource(
            R.string.grid_quant_calibration_range_value,
            formatEditableNumber(requireNotNull(calibrationConcentrations.minOrNull())),
            formatEditableNumber(requireNotNull(calibrationConcentrations.maxOrNull())),
            concentrationUnit
        )
    } else {
        stringResource(R.string.grid_quant_value_unavailable)
    }
    val hasProjectRange = projectRangeMin?.isFinite() == true &&
        projectRangeMax?.isFinite() == true &&
        requireNotNull(projectRangeMax) > requireNotNull(projectRangeMin)
    val projectRange = if (hasProjectRange) {
        stringResource(
            R.string.grid_quant_calibration_range_value,
            formatEditableNumber(requireNotNull(projectRangeMin)),
            formatEditableNumber(requireNotNull(projectRangeMax)),
            concentrationUnit
        )
    } else {
        null
    }
    val standardSiteCount = candidate?.standardPoints?.size ?: 0
    val standardLevelCount = candidate?.standardPoints
        .orEmpty()
        .map(Pair<Double, Double>::first)
        .distinct()
        .size
    val fittedCurve = remember(candidate?.function, candidate?.parameters) {
        candidate?.let { selected ->
            FittingEngine.createFunctionFromParameters(selected.function, selected.parameters)
        }
    }
    Card(
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.AutoGraph,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isRecommended) R.string.grid_quant_system_recommendation
                            else R.string.grid_quant_current_selection
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = candidate?.function?.let { fittingFunctionLabel(it) }
                            ?: stringResource(R.string.grid_quant_fit_unavailable),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    candidate?.let { selected ->
                        Text(
                            text = primaryFeatureLabel(selected.primaryFeature),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = when {
                        candidate == null -> MaterialTheme.colorScheme.surfaceContainerHighest
                        candidate.accepted -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Text(
                        text = stringResource(
                            when {
                                candidate == null -> R.string.grid_quant_fit_unavailable
                                candidate.accepted -> R.string.grid_quant_fit_stable
                                else -> R.string.grid_quant_fit_review
                            }
                        ),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (candidate != null) {
                // R² 是用户确认的普通结果页核心指标，使用更强数字层级；标准点数量和
                // 两类范围作为解释上下文，避免把某个高级误差指标误当成推荐依据。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitSummaryMetric(
                        modifier = Modifier.weight(0.85f),
                        icon = Icons.Default.AutoGraph,
                        label = stringResource(R.string.grid_quant_fit_metric_r2),
                        value = formatFitMetricValue(candidate.rSquared),
                        emphasized = true
                    )
                    FitSummaryMetric(
                        modifier = Modifier.weight(1.15f),
                        icon = Icons.Default.Dataset,
                        label = stringResource(R.string.grid_quant_standard_sites),
                        value = stringResource(
                            R.string.grid_quant_standard_summary,
                            standardSiteCount,
                            standardLevelCount
                        )
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitSummaryMetric(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Science,
                        label = stringResource(R.string.grid_quant_calibration_range),
                        value = calibrationRange
                    )
                    projectRange?.let { value ->
                        FitSummaryMetric(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Tune,
                            label = stringResource(R.string.grid_quant_project_range),
                            value = value
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.grid_quant_curve_formula),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    LatexView(
                        latex = candidate.latexFormula,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        alignment = LatexAlignment.CENTER
                    )
                }
                if (fittedCurve != null) {
                    CurveChart(
                        fittedCurve = fittedCurve,
                        selectedFunction = candidate.function,
                        parameters = candidate.parameters,
                        xAxisLabel = stringResource(
                            R.string.grid_quant_chart_concentration_axis,
                            concentrationUnit
                        ),
                        yAxisLabel = primaryFeatureLabel(candidate.primaryFeature),
                        title = stringResource(R.string.grid_quant_curve_preview),
                        dataPoints = candidate.standardPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )
                }
            }
            if (candidate != null && !isRecommended) {
                Text(
                    text = stringResource(R.string.grid_quant_selected_alternative),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApply,
                    enabled = candidate != null &&
                        applicationDecision != CalibrationApplicationDecision.BLOCK &&
                        !applying && !alreadyApplied,
                    modifier = Modifier.weight(1f)
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            when {
                                alreadyApplied -> R.string.grid_quant_curve_applied
                                applicationDecision == CalibrationApplicationDecision.BLOCK ->
                                    R.string.grid_quant_curve_view_only
                                saveToLibrary -> R.string.grid_quant_save_and_apply
                                isRecommended -> R.string.grid_quant_apply_recommended_fit
                                else -> R.string.grid_quant_apply_fit
                            }
                        )
                    )
                }
                OutlinedButton(
                    onClick = onToggleComparison,
                    enabled = candidate != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoGraph, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (showComparison) R.string.grid_quant_hide_comparison
                            else R.string.grid_quant_other_curves
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationFunctionOption(
    result: CalibrationFunctionResult,
    rank: Int?,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = result.candidate != null
    Surface(
        modifier = modifier.clickable(enabled = available, onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.badge),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            available -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = fittingFunctionLabel(result.function),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = when {
                    recommended && rank != null -> stringResource(R.string.grid_quant_fit_rank, rank)
                    rank != null -> stringResource(R.string.grid_quant_fit_rank, rank)
                    result.status == CalibrationCandidateStatus.AVAILABLE ->
                        stringResource(R.string.grid_quant_fit_accepted)
                    result.status == CalibrationCandidateStatus.LOW_QUALITY ->
                        stringResource(R.string.grid_quant_fit_review)
                    else -> stringResource(R.string.grid_quant_fit_unavailable)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1
            )
            if (!available) {
                val reason = result.failureReasons.firstOrNull()
                    ?: CalibrationFailureReason.FIT_DID_NOT_CONVERGE
                Text(
                    text = stringResource(calibrationFailureReasonResource(reason)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CalibrationUnavailableSummary(results: List<CalibrationFunctionResult>) {
    Card(
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.grid_quant_no_applicable_curve),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            results.forEach { result ->
                val reasonValues = result.failureReasons.ifEmpty {
                    setOf(CalibrationFailureReason.FIT_DID_NOT_CONVERGE)
                }
                val localizedReasons = mutableListOf<String>()
                reasonValues.forEach { reason ->
                    localizedReasons += stringResource(calibrationFailureReasonResource(reason))
                }
                val reasons = localizedReasons.joinToString(
                    stringResource(R.string.list_separator)
                )
                Text(
                    text = stringResource(
                        R.string.grid_quant_function_failure,
                        fittingFunctionLabel(result.function),
                        reasons
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun calibrationFailureReasonResource(reason: CalibrationFailureReason): Int =
    when (reason) {
        CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS ->
            R.string.grid_quant_failure_levels
        CalibrationFailureReason.NO_VALID_SIGNAL -> R.string.grid_quant_failure_signal
        CalibrationFailureReason.INVALID_FUNCTION_DOMAIN -> R.string.grid_quant_failure_domain
        CalibrationFailureReason.FIT_DID_NOT_CONVERGE -> R.string.grid_quant_failure_convergence
        CalibrationFailureReason.PARAMETERS_NOT_FINITE -> R.string.grid_quant_failure_parameters
        CalibrationFailureReason.CURVE_NOT_MONOTONIC -> R.string.grid_quant_failure_monotonic
        CalibrationFailureReason.CONCENTRATION_NOT_INVERTIBLE ->
            R.string.grid_quant_failure_inverse
        CalibrationFailureReason.SLOPE_TOO_SMALL -> R.string.grid_quant_failure_slope
    }

@Composable
private fun OnsiteFitCandidateDetails(
    preview: CalibrationCandidate,
    concentrationUnit: String
) {
    val hasAdvancedMetrics = preview.backCalculatedRmsePercent?.isFinite() == true ||
        preview.acceptedStandardRatio?.isFinite() == true ||
        preview.crossValidation?.medianRelativeErrorPercent?.isFinite() == true ||
        preview.crossValidation?.endpointRelativeErrorPercent?.isFinite() == true ||
        preview.crossValidation?.parameterStabilityScore?.isFinite() == true ||
        preview.trustedRange != null
    if (!hasAdvancedMetrics) return
    var expanded by rememberSaveable(preview.id) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.grid_quant_advanced_validation),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.grid_quant_advanced_validation_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.grid_quant_hide_advanced_validation
                            else R.string.grid_quant_show_advanced_validation
                        )
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    preview.backCalculatedRmsePercent?.takeIf(Double::isFinite)?.let { value ->
                        AdvancedValidationMetric(
                            label = stringResource(R.string.grid_quant_fit_inverse_error),
                            value = formatFitPercentValue(value)
                        )
                    }
                    preview.crossValidation?.medianRelativeErrorPercent
                        ?.takeIf(Double::isFinite)?.let { value ->
                            AdvancedValidationMetric(
                                label = stringResource(R.string.grid_quant_fit_metric_loo),
                                value = formatFitPercentValue(value)
                            )
                        }
                    preview.crossValidation?.endpointRelativeErrorPercent
                        ?.takeIf(Double::isFinite)?.let { value ->
                            AdvancedValidationMetric(
                                label = stringResource(R.string.grid_quant_fit_metric_endpoint),
                                value = formatFitPercentValue(value)
                            )
                        }
                    preview.acceptedStandardRatio?.takeIf(Double::isFinite)?.let { value ->
                        AdvancedValidationMetric(
                            label = stringResource(R.string.grid_quant_fit_metric_acceptance),
                            value = formatFitRatioPercentValue(value)
                        )
                    }
                    preview.crossValidation?.parameterStabilityScore
                        ?.takeIf(Double::isFinite)?.let { value ->
                            AdvancedValidationMetric(
                                label = stringResource(R.string.grid_quant_fit_metric_stability),
                                value = formatFitRatioPercentValue(value)
                            )
                        }
                    preview.trustedRange?.let {
                        AdvancedValidationMetric(
                            label = stringResource(R.string.grid_quant_fit_metric_trusted_range),
                            value = formatTrustedRange(preview, concentrationUnit)
                        )
                    }
                }
            }
        }
    }
}

/** 高级验证采用纵向“指标—数值”，长单位和中英文标签不会在 360dp 被三列卡片挤压。 */
@Composable
private fun AdvancedValidationMetric(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.badge),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
        }
    }
}

/** 首屏摘要指标；[emphasized] 只用于 R²，避免所有数字同时争夺视觉焦点。 */
@Composable
private fun FitSummaryMetric(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    emphasized: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.badge),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (emphasized) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = if (emphasized) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 百分数指标已经按 0～100 保存，UI只负责本地化格式化，不再次乘以100。 */
@Composable
private fun formatFitPercentValue(value: Double): String =
    stringResource(R.string.grid_quant_fit_acceptance_value, value)

/** 标准点接受率和稳定性以 0～1 保存，展示时转换为百分数。 */
@Composable
private fun formatFitRatioPercentValue(value: Double): String =
    stringResource(R.string.grid_quant_fit_acceptance_value, value * 100.0)

/** 仅在可信扩展真实存在时调用，调用方不得为缺失范围渲染空指标。 */
@Composable
private fun formatTrustedRange(
    candidate: CalibrationCandidate,
    concentrationUnit: String
): String {
    val range = requireNotNull(candidate.trustedRange)
    return stringResource(
        R.string.grid_quant_fit_trusted_range_value,
        formatFitMetricValue(range.minimum),
        formatFitMetricValue(range.maximum),
        concentrationUnit
    )
}

/**
 * 把原始信号域误差压缩为移动端可读格式。
 *
 * RMSE/MAE仍保留真实信号量纲；这里只改变显示格式，不把标准化RMSE冒充原始误差。
 * 当数值跨度很大或很小时使用科学计数法，避免百万级积分荧光把指标卡撑坏。
 */
@Composable
private fun formatFitMetricValue(value: Double?): String {
    val finiteValue = value?.takeIf(Double::isFinite) ?: return ""
    val absoluteValue = abs(finiteValue)
    if (absoluteValue != 0.0 && (absoluteValue >= 10_000.0 || absoluteValue < 0.001)) {
        val exponent = floor(log10(absoluteValue)).toInt()
        val mantissa = finiteValue / Math.pow(10.0, exponent.toDouble())
        return stringResource(
            R.string.grid_quant_fit_scientific_value,
            String.format(Locale.getDefault(), "%.2f", mantissa),
            exponent.toSuperscriptDigits()
        )
    }
    return when {
        absoluteValue >= 100.0 -> String.format(Locale.getDefault(), "%.1f", finiteValue)
        absoluteValue >= 1.0 -> String.format(Locale.getDefault(), "%.3f", finiteValue)
        else -> String.format(Locale.getDefault(), "%.4f", finiteValue)
    }
}

/** 将科学计数法指数转换为不占额外行高的上标字符。 */
private fun Int.toSuperscriptDigits(): String = toString().map { character ->
    when (character) {
        '-' -> '⁻'
        '0' -> '⁰'
        '1' -> '¹'
        '2' -> '²'
        '3' -> '³'
        '4' -> '⁴'
        '5' -> '⁵'
        '6' -> '⁶'
        '7' -> '⁷'
        '8' -> '⁸'
        '9' -> '⁹'
        else -> character
    }
}.joinToString("")
