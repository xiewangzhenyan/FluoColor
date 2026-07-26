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
                AnalyteConfigurationDropdown(
                    analytes = preview.analytes,
                    drafts = preview.quantitationDrafts,
                    selectedAnalyteId = selectedAnalyte.id,
                    onSelected = onSelectQuantitationAnalyte
                )

                QuantitationModeGrid(
                    selectedMode = selectedDraft.mode,
                    onSelected = { mode ->
                        onSetQuantitationMode(selectedAnalyte.id, mode)
                    },
                    onInfo = { modeInfo = it }
                )

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
                            models = preview.availableModels.filter { option ->
                                option.analyteId == selectedAnalyte.id &&
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
                    QuantitationModeOption(
                        modifier = Modifier.weight(1f),
                        mode = mode,
                        selected = mode == selectedMode,
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
    onClick: () -> Unit,
    onInfo: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick),
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
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = quantitationModeLabel(mode),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
                .fillMaxWidth(),
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
                        onSelected(model.id)
                    }
                )
            }
        }
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
    return stringResource(
        R.string.grid_quant_resource_summary,
        model.version,
        primaryFeatureLabel(model.primaryFeature),
        range
    )
}

/**
 * 现场拟合使用接近全屏的科研录入面板，避免在主布局页堆叠 100～225 个标准孔输入框。
 * 标准品只来自当前分析物的 STANDARD 位点；普通样本浓度未知，绝不会混入标定列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnsiteCalibrationDialog(
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
                                    R.string.grid_quant_classic_gray_formula
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

private fun formatEditableNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private val STANDARD_CONCENTRATION_PATTERN = Regex("\\d{0,12}(\\.\\d{0,6})?")
private val SIGNED_DECIMAL_PATTERN = Regex("-?\\d{0,12}(\\.\\d{0,6})?")

/** 保存曲线和模板共用的轻量命名弹窗；空名称在 UI 与 ViewModel 两层都被拒绝。 */
@Composable
private fun ResourceNameDialog(
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
private fun ColumnScope.OnsiteCalibrationResultStage(
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
                            items(
                                items = resultSet.functionResults,
                                key = { functionResult -> functionResult.function.identifier }
                            ) { functionResult ->
                                CalibrationFunctionOption(
                                    result = functionResult,
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
 * 现场拟合首屏只保留一个决策摘要，避免把公式、R²、留一验证和曲线图同时塞给普通用户。
 * “对比”展开后才展示科学细节；应用动作始终针对当前选中的冻结候选。
 */
@Composable
private fun OnsiteCalibrationSummaryCard(
    candidate: CalibrationCandidate?,
    isRecommended: Boolean,
    applicationDecision: CalibrationApplicationDecision?,
    saveToLibrary: Boolean,
    applying: Boolean,
    alreadyApplied: Boolean,
    showComparison: Boolean,
    onToggleComparison: () -> Unit,
    onApply: () -> Unit
) {
    val inverseError = candidate?.backCalculatedRmsePercent
        ?: candidate?.crossValidation?.medianRelativeErrorPercent
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoGraph,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.grid_quant_smart_calibration),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
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
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.grid_quant_fit_inverse_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFitPercentValue(inverseError),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (candidate != null && !isRecommended) {
                    Text(
                        text = stringResource(R.string.grid_quant_selected_alternative),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
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
                            else R.string.grid_quant_show_comparison
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
                    recommended -> stringResource(R.string.grid_quant_fit_recommended_short)
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
    val fittedCurve = remember(preview.function, preview.parameters) {
        FittingEngine.createFunctionFromParameters(preview.function, preview.parameters)
    }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.grid_quant_fit_evidence_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.grid_quant_fit_combination,
                            primaryFeatureLabel(preview.primaryFeature),
                            fittingFunctionLabel(preview.function)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(FluoRadius.badge),
                color = MaterialTheme.colorScheme.surface
            ) {
                LatexView(
                    latex = preview.latexFormula,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    alignment = LatexAlignment.CENTER
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FitMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_inverse_error),
                    value = formatFitPercentValue(preview.backCalculatedRmsePercent)
                )
                FitMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_loo),
                    value = formatFitPercentValue(
                        preview.crossValidation?.medianRelativeErrorPercent
                    )
                )
                FitMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_r2),
                    value = formatFitMetricValue(preview.rSquared)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FitMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_endpoint),
                    value = formatFitPercentValue(
                        preview.crossValidation?.endpointRelativeErrorPercent
                    )
                )
                FitMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_acceptance),
                    value = formatFitRatioPercentValue(preview.acceptedStandardRatio)
                )
            }
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
                        text = stringResource(R.string.grid_quant_fit_metric_trusted_range),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTrustedRange(preview, concentrationUnit),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End
                    )
                }
            }
            CurveChart(
                fittedCurve = fittedCurve,
                selectedFunction = preview.function,
                parameters = preview.parameters,
                xAxisLabel = "",
                yAxisLabel = "",
                title = "",
                dataPoints = preview.standardPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            )
        }
    }
}

/** 百分数指标已经按 0～100 保存，UI只负责本地化格式化，不再次乘以100。 */
@Composable
private fun formatFitPercentValue(value: Double?): String {
    val finiteValue = value?.takeIf(Double::isFinite)
        ?: return stringResource(R.string.grid_quant_value_unavailable)
    return stringResource(R.string.grid_quant_fit_acceptance_value, finiteValue)
}

/** 标准点接受率以 0～1 保存，展示时转换为百分数。 */
@Composable
private fun formatFitRatioPercentValue(value: Double?): String {
    val finiteValue = value?.takeIf(Double::isFinite)
        ?: return stringResource(R.string.grid_quant_value_unavailable)
    return stringResource(R.string.grid_quant_fit_acceptance_value, finiteValue * 100.0)
}

/**
 * 可信范围只显示由留一验证和参数采样共同批准的连续区间。
 * 缺失时明确写“无可信扩展”，不能把项目预期上限伪装成算法已经验证的范围。
 */
@Composable
private fun formatTrustedRange(
    candidate: CalibrationCandidate,
    concentrationUnit: String
): String {
    val range = candidate.trustedRange
        ?: return stringResource(R.string.grid_quant_no_trusted_extension)
    return stringResource(
        R.string.grid_quant_fit_trusted_range_value,
        formatFitMetricValue(range.minimum),
        formatFitMetricValue(range.maximum),
        concentrationUnit
    )
}

@Composable
private fun FitMetric(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.badge),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value.ifBlank { stringResource(R.string.grid_quant_value_unavailable) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
private fun primaryFeatureLabel(feature: AnalysisPrimaryFeature): String =
    analysisFeatureLabel(feature)

@Composable
private fun fittingFunctionLabel(function: FittingFunction): String = stringResource(
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
