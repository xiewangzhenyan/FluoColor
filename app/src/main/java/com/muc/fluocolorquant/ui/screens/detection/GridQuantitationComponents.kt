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
@Composable
internal fun GridExperimentConfigurationSection(
    preview: GridLocalizationPreview,
    assignments: Map<Int, GridLayoutAssignmentDraft>,
    onUseManualConfiguration: () -> Unit,
    onApplyTemplate: (String) -> Unit,
    onSelectQuantitationAnalyte: (String) -> Unit = {},
    onSetQuantitationMode: (String, GridAnalyteQuantitationMode) -> Unit,
    onSelectAnalysisModel: (String, String) -> Unit,
    onUpdateOnsiteAdvanced: (
        String,
        Set<AnalysisPrimaryFeature>,
        Set<FittingFunction>
    ) -> Unit,
    onUpdateStandardConcentrations: (String, Map<Int, Double?>) -> Unit = { _, _ -> },
    onPreviewOnsiteFit: (String) -> Unit,
    onSelectOnsiteCandidate: (String, String) -> Unit = { _, _ -> },
    onSetOnsiteSaveToLibrary: (String, Boolean) -> Unit = { _, _ -> },
    onEditOnsiteCalibration: (String) -> Unit = {},
    onConfirmQuantitationAnalyte: (String, Boolean) -> Unit = { _, _ -> },
    onSaveTemplate: (String) -> Unit
) {
    var showTemplateDialog by rememberSaveable(preview.runId) { mutableStateOf(false) }
    var showTemplateNameDialog by rememberSaveable(preview.runId) { mutableStateOf(false) }
    var onsiteEditorAnalyteId by rememberSaveable(preview.runId) { mutableStateOf<String?>(null) }
    var modeInfo by rememberSaveable(preview.runId) {
        mutableStateOf<GridAnalyteQuantitationMode?>(null)
    }
    val selectedTemplate = preview.availableTemplates.firstOrNull {
        it.id == preview.selectedTemplateId
    }
    val completedCount = preview.quantitationDrafts.count { it.isConfigurationComplete() }
    val allCompleted = preview.quantitationDrafts.isNotEmpty() &&
        completedCount == preview.quantitationDrafts.size
    val selectedAnalyteId = preview.selectedQuantitationAnalyteId
        ?.takeIf { id -> preview.analytes.any { it.id == id } }
        ?: preview.analytes.firstOrNull()?.id
    val selectedAnalyte = preview.analytes.firstOrNull { it.id == selectedAnalyteId }
    val selectedDraft = preview.quantitationDrafts.firstOrNull {
        it.analyteId == selectedAnalyteId
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ArrayLayoutEditorTestTags.QUANTITATION),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.grid_quant_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = if (allCompleted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = stringResource(
                            R.string.grid_quant_progress_count,
                            completedCount,
                            preview.quantitationDrafts.size
                        ),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    if (preview.quantitationDrafts.isEmpty()) 0f
                    else completedCount.toFloat() / preview.quantitationDrafts.size
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            ConfigurationSourceSwitcher(
                source = preview.configurationSource,
                onTemplate = { showTemplateDialog = true },
                onManual = onUseManualConfiguration
            )

            if (preview.configurationSource == GridLayoutConfigurationSource.EXPERIMENT_TEMPLATE) {
                TemplateAppliedSummary(
                    template = selectedTemplate,
                    drafts = preview.quantitationDrafts,
                    preview = preview,
                    onChangeTemplate = { showTemplateDialog = true }
                )
            } else if (selectedAnalyte != null && selectedDraft != null) {
                val availableAnalyteModels = preview.availableModels.filter { option ->
                    option.analyteId == selectedAnalyte.id
                }
                AnalyteConfigurationDropdown(
                    analytes = preview.analytes,
                    drafts = preview.quantitationDrafts,
                    selectedAnalyteId = selectedAnalyte.id,
                    onSelected = onSelectQuantitationAnalyte
                )

                QuantitationModeGrid(
                    selectedMode = selectedDraft.mode,
                    // 定量方式入口表达用户想做什么，不能因为当前分析物尚无已发布资源
                    // 就静默变灰。进入后由资源下拉框明确展示空状态，确认按钮仍会在未
                    // 选择真实兼容资源时保持不可用，因此不会放宽跨分析物复用等科学门控。
                    isEnabled = { true },
                    onSelected = { mode ->
                        onSetQuantitationMode(selectedAnalyte.id, mode)
                    },
                    onInfo = { modeInfo = it }
                )
                if (availableAnalyteModels.none {
                        it.modelType == AnalysisModelType.DEEP_LEARNING
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(FluoRadius.control),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.48f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = stringResource(
                                    R.string.grid_quant_no_compatible_deep_learning_hint
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                when (selectedDraft.mode) {
                    GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> {
                        val standards = assignments.values.filter {
                            it.analyteId == selectedAnalyte.id &&
                                it.role == TemplateSiteRole.STANDARD
                        }
                        val levels = standards.mapNotNull { it.standardConcentration }.distinct()
                        CompactOnsiteFitControl(
                            standardCount = standards.size,
                            concentrationLevelCount = levels.size,
                            draft = selectedDraft,
                            onOpen = { onsiteEditorAnalyteId = selectedAnalyte.id }
                        )
                    }

                    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
                    GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> {
                        AnalysisResourceDropdown(
                            mode = selectedDraft.mode,
                            selectedModelId = selectedDraft.selectedAnalysisModelId,
                            models = availableAnalyteModels.filter { option ->
                                option.modelType == selectedDraft.mode.requiredModelType()
                            },
                            onSelected = { modelId ->
                                onSelectAnalysisModel(selectedAnalyte.id, modelId)
                            }
                        )
                    }

                    GridAnalyteQuantitationMode.SIGNAL_ONLY -> Unit
                }

                if (
                    selectedDraft.mode != GridAnalyteQuantitationMode.ONSITE_AUTO_FIT ||
                    selectedDraft.isConfigurationComplete()
                ) {
                    Button(
                        onClick = {
                            onConfirmQuantitationAnalyte(selectedAnalyte.id, false)
                        },
                        enabled = selectedDraft.isReadyForConfirmation() &&
                            !selectedDraft.isConfigurationComplete(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (selectedDraft.isConfigurationComplete()) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.AutoMirrored.Filled.ArrowForward
                            },
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (selectedDraft.isConfigurationComplete()) {
                                    R.string.grid_quant_current_completed
                                } else {
                                    R.string.grid_quant_complete_current
                                }
                            )
                        )
                    }
                }
            }

            AnimatedVisibility(allCompleted && assignments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = { showTemplateNameDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.grid_save_as_experiment_template))
                    }
                }
            }
        }
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            templates = preview.availableTemplates,
            onSelect = { templateId ->
                showTemplateDialog = false
                onApplyTemplate(templateId)
            },
            onDismiss = { showTemplateDialog = false }
        )
    }
    onsiteEditorAnalyteId?.let { analyteId ->
        val analyte = preview.analytes.firstOrNull { it.id == analyteId }
        val draft = preview.quantitationDrafts.firstOrNull { it.analyteId == analyteId }
        if (analyte != null && draft != null) {
            OnsiteCalibrationDialog(
                preview = preview,
                analyte = analyte,
                draft = draft,
                assignments = assignments,
                detectionMode = DetectionModality.fromCode(preview.detectionMode),
                onUpdateAdvanced = { features, functions ->
                    onUpdateOnsiteAdvanced(analyteId, features, functions)
                },
                onUpdateConcentrations = { values ->
                    onUpdateStandardConcentrations(analyteId, values)
                },
                onPreviewFit = { onPreviewOnsiteFit(analyteId) },
                onSelectCandidate = { candidateId ->
                    onSelectOnsiteCandidate(analyteId, candidateId)
                },
                onSetSaveToLibrary = { save ->
                    onSetOnsiteSaveToLibrary(analyteId, save)
                },
                onBackToEditing = { onEditOnsiteCalibration(analyteId) },
                onApply = { lowQualityConfirmed ->
                    onConfirmQuantitationAnalyte(analyteId, lowQualityConfirmed)
                    onsiteEditorAnalyteId = null
                },
                onDismiss = { onsiteEditorAnalyteId = null }
            )
        }
    }
    modeInfo?.let { mode ->
        AlertDialog(
            onDismissRequest = { modeInfo = null },
            title = { Text(quantitationModeLabel(mode)) },
            text = { Text(quantitationModeDescription(mode)) },
            confirmButton = {
                TextButton(onClick = { modeInfo = null }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
    if (showTemplateNameDialog) {
        ResourceNameDialog(
            title = stringResource(R.string.grid_save_template_dialog_title),
            label = stringResource(R.string.grid_save_template_name_label),
            onConfirm = { name ->
                showTemplateNameDialog = false
                onSaveTemplate(name)
            },
            onDismiss = { showTemplateNameDialog = false }
        )
    }
}

@Composable
private fun ConfigurationSourceSwitcher(
    source: GridLayoutConfigurationSource,
    onTemplate: () -> Unit,
    onManual: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(FluoRadius.control)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConfigurationSourceSegment(
            modifier = Modifier.weight(1f),
            selected = source == GridLayoutConfigurationSource.EXPERIMENT_TEMPLATE,
            icon = Icons.Outlined.Description,
            label = stringResource(R.string.grid_configuration_use_template),
            onClick = onTemplate
        )
        ConfigurationSourceSegment(
            modifier = Modifier.weight(1f),
            selected = source == GridLayoutConfigurationSource.MANUAL,
            icon = Icons.Outlined.EditNote,
            label = stringResource(R.string.grid_configuration_manual),
            onClick = onManual
        )
    }
}

@Composable
private fun ConfigurationSourceSegment(
    modifier: Modifier,
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.badge),
        color = if (selected) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TemplateAppliedSummary(
    template: GridExperimentTemplateOption?,
    drafts: List<GridAnalyteQuantitationDraft>,
    preview: GridLocalizationPreview,
    onChangeTemplate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template?.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    template?.let {
                        Text(
                            text = stringResource(R.string.grid_template_version, it.version),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onChangeTemplate) {
                    Text(stringResource(R.string.grid_template_change))
                }
            }
            Text(
                text = stringResource(R.string.grid_template_quantitation_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            drafts.forEach { draft ->
                val analyte = preview.analytes.firstOrNull { it.id == draft.analyteId }
                    ?: return@forEach
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(analyte.name, modifier = Modifier.weight(1f))
                    Text(
                        text = quantitationModeLabel(draft.mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyteConfigurationDropdown(
    analytes: List<GridLocalizationAnalyte>,
    drafts: List<GridAnalyteQuantitationDraft>,
    selectedAnalyteId: String,
    onSelected: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = analytes.firstOrNull { it.id == selectedAnalyteId } ?: return
    val statusByAnalyte = drafts.associateBy(GridAnalyteQuantitationDraft::analyteId)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = stringResource(
                R.string.grid_quant_analyte_title,
                selected.name,
                selected.concentrationUnit
            ),
            onValueChange = {},
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.grid_quant_current_analyte)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            analytes.forEach { analyte ->
                val draft = statusByAnalyte[analyte.id]
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(analyte.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(
                                    when {
                                        draft?.isConfigurationComplete() == true ->
                                            R.string.grid_quant_status_completed
                                        analyte.id == selectedAnalyteId ->
                                            R.string.grid_quant_status_editing
                                        else -> R.string.grid_quant_status_pending
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (draft?.isConfigurationComplete() == true) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Science
                            },
                            contentDescription = null,
                            tint = if (draft?.isConfigurationComplete() == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(analyte.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun QuantitationModeGrid(
    selectedMode: GridAnalyteQuantitationMode,
    isEnabled: (GridAnalyteQuantitationMode) -> Boolean,
    onSelected: (GridAnalyteQuantitationMode) -> Unit,
    onInfo: (GridAnalyteQuantitationMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridAnalyteQuantitationMode.entries.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowModes.forEach { mode ->
                    val enabled = isEnabled(mode)
                    QuantitationModeOption(
                        modifier = Modifier.weight(1f),
                        mode = mode,
                        selected = mode == selectedMode,
                        enabled = enabled,
                        onClick = { onSelected(mode) },
                        onInfo = { onInfo(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuantitationModeOption(
    modifier: Modifier,
    mode: GridAnalyteQuantitationMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 58.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = quantitationModeIcon(mode),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = quantitationModeLabel(mode),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.grid_quant_method_info),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactOnsiteFitControl(
    standardCount: Int,
    concentrationLevelCount: Int,
    draft: GridAnalyteQuantitationDraft,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Dataset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.grid_quant_standard_levels,
                        standardCount,
                        concentrationLevelCount
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge
                )
                if (
                    draft.onsiteState is OnsiteCalibrationState.Reviewing ||
                    draft.onsiteState is OnsiteCalibrationState.Applied
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoGraph, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (
                            draft.onsiteState is OnsiteCalibrationState.Reviewing ||
                            draft.onsiteState is OnsiteCalibrationState.Applied
                        ) R.string.grid_quant_review_fit
                        else R.string.grid_quant_enter_standards
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisResourceDropdown(
    mode: GridAnalyteQuantitationMode,
    selectedModelId: String?,
    models: List<GridAnalysisModelOption>,
    onSelected: (String) -> Unit
) {
    var expanded by rememberSaveable(mode) { mutableStateOf(false) }
    var pendingExperimentalModel by remember { mutableStateOf<GridAnalysisModelOption?>(null) }
    val selected = models.firstOrNull { it.id == selectedModelId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (models.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.let { modelOptionDisplayName(it) }.orEmpty(),
            onValueChange = {},
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .testTag(ArrayLayoutEditorTestTags.MODEL_SELECTOR),
            readOnly = true,
            singleLine = true,
            label = {
                Text(
                    stringResource(
                        if (mode == GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE) {
                            R.string.grid_quant_mode_curve
                        } else {
                            R.string.grid_quant_mode_deep_learning
                        }
                    )
                )
            },
            placeholder = { Text(stringResource(R.string.grid_quant_select_resource)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = {
                Text(
                    selected?.let { modelOptionSummary(it) }
                        ?: stringResource(R.string.grid_quant_no_models).takeIf { models.isEmpty() }
                        .orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    modifier = Modifier.testTag(
                        ArrayLayoutEditorTestTags.MODEL_OPTION_PREFIX + model.id
                    ),
                    text = {
                        Column {
                            Text(modelOptionDisplayName(model), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = modelOptionSummary(model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (model.requiresExplicitScopeConfirmation) {
                            pendingExperimentalModel = model
                        } else {
                            onSelected(model.id)
                        }
                    }
                )
            }
        }
    }

    pendingExperimentalModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingExperimentalModel = null },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.grid_quant_model_scope_dialog_title)) },
            text = { Text(stringResource(R.string.grid_quant_model_scope_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExperimentalModel = null
                        onSelected(model.id)
                    }
                ) {
                    Text(stringResource(R.string.grid_quant_model_scope_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExperimentalModel = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun modelOptionSummary(model: GridAnalysisModelOption): String {
    val range = if (
        model.reliableRangeMin?.isFinite() == true &&
        model.reliableRangeMax?.isFinite() == true
    ) {
        stringResource(
            R.string.grid_quant_resource_range,
            requireNotNull(model.reliableRangeMin),
            requireNotNull(model.reliableRangeMax),
            model.concentrationUnit
        )
    } else {
        model.concentrationUnit
    }
    val summary = stringResource(
        R.string.grid_quant_resource_summary,
        model.version,
        primaryFeatureLabel(model.primaryFeature),
        range
    )
    return if (model.requiresExplicitScopeConfirmation) {
        stringResource(R.string.grid_quant_resource_experimental_summary, summary)
    } else {
        summary
    }
}

/**
 * 现场拟合使用接近全屏的科研录入面板，避免在主布局页堆叠 100～225 个标准孔输入框。
 * 标准品只来自当前分析物的 STANDARD 位点；普通样本浓度未知，绝不会混入标定列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
/** 保存曲线和模板共用的轻量命名弹窗；空名称在 UI 与 ViewModel 两层都被拒绝。 */
@Composable
internal fun ResourceNameDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by rememberSaveable(title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { changed -> value = changed.take(MAXIMUM_RESOURCE_NAME_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(label) }
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private const val MAXIMUM_RESOURCE_NAME_LENGTH = 80

@Composable
private fun TemplatePickerDialog(
    templates: List<GridExperimentTemplateOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grid_template_dialog_title)) },
        text = {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.grid_template_none_available))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    templates.forEach { template ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template.id) },
                            shape = RoundedCornerShape(FluoRadius.control),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(template.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.grid_template_version, template.version),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** 内置共享模型显示本地化名称，资源库中的稳定机器名永远不直接暴露给普通用户。 */
@Composable
private fun modelOptionDisplayName(model: GridAnalysisModelOption): String {
    return if (model.builtInShared) {
        stringResource(R.string.grid_quant_builtin_shared_model)
    } else {
        model.name
    }
}

private fun GridAnalyteQuantitationMode.requiredModelType(): AnalysisModelType? = when (this) {
    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE -> AnalysisModelType.STANDARD_CURVE
    GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> AnalysisModelType.DEEP_LEARNING
    else -> null
}

@Composable
private fun quantitationModeLabel(mode: GridAnalyteQuantitationMode): String = stringResource(
    when (mode) {
        GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> R.string.grid_quant_mode_onsite
        GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE -> R.string.grid_quant_mode_curve
        GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> R.string.grid_quant_mode_deep_learning
        GridAnalyteQuantitationMode.SIGNAL_ONLY -> R.string.grid_quant_mode_signal_only
    }
)

@Composable
private fun quantitationModeDescription(mode: GridAnalyteQuantitationMode): String = stringResource(
    when (mode) {
        GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> R.string.grid_quant_mode_onsite_desc
        GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE -> R.string.grid_quant_mode_curve_desc
        GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> R.string.grid_quant_mode_deep_learning_desc
        GridAnalyteQuantitationMode.SIGNAL_ONLY -> R.string.grid_quant_mode_signal_only_desc
    }
)

private fun quantitationModeIcon(mode: GridAnalyteQuantitationMode): ImageVector = when (mode) {
    GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> Icons.Default.AutoGraph
    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE -> Icons.Default.Dataset
    GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> Icons.Default.Memory
    GridAnalyteQuantitationMode.SIGNAL_ONLY -> Icons.Outlined.Info
}

@Composable
internal fun primaryFeatureLabel(feature: AnalysisPrimaryFeature): String =
    analysisFeatureLabel(feature)

@Composable
internal fun fittingFunctionLabel(function: FittingFunction): String = stringResource(
    when (function) {
        FittingFunction.LINEAR -> R.string.fitting_function_linear
        FittingFunction.QUADRATIC -> R.string.fitting_function_quadratic
        FittingFunction.CUBIC -> R.string.fitting_function_cubic
        FittingFunction.QUARTIC -> R.string.fitting_function_quartic
        FittingFunction.EXPONENTIAL -> R.string.fitting_function_exponential
        FittingFunction.POWER -> R.string.fitting_function_power
        FittingFunction.LOG -> R.string.fitting_function_log
        FittingFunction.RODBARD -> R.string.fitting_function_rodbard_4pl
        FittingFunction.GAMMA_VARIATE -> R.string.fitting_function_gamma_variate
        FittingFunction.CUSTOM_LOG -> R.string.fitting_function_custom_log
        FittingFunction.RODBARD_NIH -> R.string.fitting_function_rodbard_nih
        FittingFunction.EXPONENTIAL_WITH_OFFSET -> R.string.fitting_function_exponential_offset
        FittingFunction.GAUSSIAN -> R.string.fitting_function_gaussian
        FittingFunction.EXPONENTIAL_RECOVERY -> R.string.fitting_function_exponential_recovery
        FittingFunction.LOGISTIC -> R.string.fitting_function_logistic_5pl
        FittingFunction.GOMPERTZ -> R.string.fitting_function_gompertz
        FittingFunction.HILL -> R.string.fitting_function_hill
        FittingFunction.GENERAL_GOMPERTZ -> R.string.fitting_function_general_gompertz
        FittingFunction.RICHARDS -> R.string.fitting_function_richards
        FittingFunction.INTERPOLATION -> R.string.fitting_function_interpolation
    }
)
