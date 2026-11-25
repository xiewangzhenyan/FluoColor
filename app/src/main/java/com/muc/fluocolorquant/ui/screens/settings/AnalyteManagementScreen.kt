package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.TumorBiomarkerPreset
import com.muc.fluocolorquant.data.model.TumorBiomarkerPresets
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.components.FluoIconBadge
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.AnalyteViewModel
import java.util.Locale

private val STANDARD_ANALYTE_UNITS = listOf("ng/mL", "U/mL", "pg/mL", "μg/mL", "mg/mL")

/**
 * 分析物（肿瘤标志物）管理界面。
 *
 * 采用科研仪器式卡片呈现每个标志物的常用单位、默认检测量程、默认荧光发射通道与阳性参考截断值，
 * 在直接新建项目勾选分析物时自动联动带出预设，且修改预设绝不篡改已保存的历史运行快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyteManagementScreen(
    viewModel: AnalyteViewModel = hiltViewModel(),
    navigateBack: () -> Unit,
    onOpenReagents: ((String) -> Unit)? = null
) {
    val toastManager = LocalToastManager.current
    val context = LocalContext.current

    val analytes by viewModel.analytes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var analyteToDelete by remember { mutableStateOf<Analyte?>(null) }
    var analyteToEdit by remember { mutableStateOf<Analyte?>(null) }

    // 预先在 Composable 上下文读取全部 Toast 文案
    val analyteAddedSuccessMsg = stringResource(R.string.analyte_added_success)
    val analyteDeletedSuccessMsg = stringResource(R.string.analyte_deleted_success)
    val analyteUpdatedSuccessMsg = stringResource(R.string.analyte_updated_success)
    val presetsAppliedFormat = stringResource(R.string.analyte_presets_applied_toast)
    val presetsNoneNeededMsg = stringResource(R.string.analyte_presets_none_needed_toast)

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it.asString(context), ToastType.ERROR)
            viewModel.clearErrorMessage()
        }
    }

    val filteredAnalytes = remember(analytes, searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            analytes
        } else {
            analytes.filter { it.name.contains(trimmed, ignoreCase = true) }
        }
    }

    val hasUnconfiguredRecognizedMarkers = remember(analytes) {
        analytes.any { analyte ->
            analyte.defaultUnit.isNullOrBlank() &&
                analyte.defaultMaxConcentration == null &&
                analyte.referenceCutoff == null &&
                TumorBiomarkerPresets.findByName(analyte.name) != null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.analyte_management_title),
                onBack = navigateBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(FluoRadius.control),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.add_analyte),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading && analytes.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (analytes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(FluoSpacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    FluoStatePlaceholder(
                        icon = Icons.Outlined.Biotech,
                        text = stringResource(R.string.no_analytes_found),
                        supportingText = stringResource(R.string.add_your_first_analyte)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = FluoSpacing.lg,
                        end = FluoSpacing.lg,
                        top = FluoSpacing.md,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "summary_header") {
                        FluoSectionCard(
                            contentPadding = FluoSpacing.md,
                            verticalSpacing = FluoSpacing.sm,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FluoIconBadge(icon = Icons.Outlined.Biotech)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.analyte_total_configured, analytes.size),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.analyte_management_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = hasUnconfiguredRecognizedMarkers,
                                enter = FluoMotion.expandEnter,
                                exit = FluoMotion.expandExit
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.applyStandardPresets { updated ->
                                            if (updated > 0) {
                                                toastManager.showToast(
                                                    String.format(Locale.getDefault(), presetsAppliedFormat, updated),
                                                    ToastType.SUCCESS
                                                )
                                            } else {
                                                toastManager.showToast(presetsNoneNeededMsg, ToastType.INFO)
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(FluoRadius.control),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoFixHigh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.analyte_apply_presets_action))
                                }
                            }
                        }
                    }

                    if (analytes.size >= 5) {
                        item(key = "search_bar") {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.analyte_search_placeholder)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.close)
                                            )
                                        }
                                    }
                                } else null,
                                singleLine = true,
                                shape = RoundedCornerShape(FluoRadius.control),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }
                    }

                    items(filteredAnalytes, key = { it.id }) { analyte ->
                        AnalyteItem(
                            analyte = analyte,
                            onDelete = { analyteToDelete = analyte },
                            onEdit = { analyteToEdit = analyte },
                            onOpenReagents = onOpenReagents?.let { open -> { open(analyte.id) } }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AnalyteProfileDialog(
            initialAnalyte = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { draft ->
                viewModel.addAnalyteProfile(
                    name = draft.name,
                    defaultUnit = draft.defaultUnit,
                    defaultMaxConcentration = draft.defaultMaxConcentration,
                    defaultFluorescenceChannel = draft.defaultFluorescenceChannel,
                    referenceCutoff = draft.referenceCutoff,
                    onSaved = {
                        showAddDialog = false
                        toastManager.showToast(analyteAddedSuccessMsg, ToastType.SUCCESS)
                    }
                )
            }
        )
    }

    analyteToEdit?.let { analyte ->
        AnalyteProfileDialog(
            initialAnalyte = analyte,
            onDismiss = { analyteToEdit = null },
            onConfirm = { draft ->
                viewModel.updateAnalyteProfile(
                    id = analyte.id,
                    name = draft.name,
                    defaultUnit = draft.defaultUnit,
                    defaultMaxConcentration = draft.defaultMaxConcentration,
                    defaultFluorescenceChannel = draft.defaultFluorescenceChannel,
                    referenceCutoff = draft.referenceCutoff,
                    onSaved = {
                        analyteToEdit = null
                        toastManager.showToast(analyteUpdatedSuccessMsg, ToastType.SUCCESS)
                    }
                )
            }
        )
    }

    analyteToDelete?.let { analyte ->
        DeleteAnalyteDialog(
            analyteName = analyte.name,
            onDismiss = { analyteToDelete = null },
            onConfirm = {
                viewModel.deleteAnalyte(analyte.id)
                analyteToDelete = null
                toastManager.showToast(analyteDeletedSuccessMsg, ToastType.SUCCESS)
            }
        )
    }
}

/**
 * 现代科研仪器风格的分析物档案卡。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyteItem(
    analyte: Analyte,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onOpenReagents: (() -> Unit)? = null
) {
    val channel = analyte.defaultFluorescenceChannel
        ?.uppercase()
        ?.let { code -> FluorescenceChannel.entries.firstOrNull { it.name == code } }
    val channelAccent = channel?.let(::channelSemanticColor) ?: MaterialTheme.colorScheme.primary
    val hasConfiguredPreset = !analyte.defaultUnit.isNullOrBlank() ||
        analyte.defaultMaxConcentration != null ||
        analyte.referenceCutoff != null ||
        channel != null

    FluoSectionCard(
        modifier = Modifier.clickable(onClick = onEdit),
        contentPadding = FluoSpacing.md,
        verticalSpacing = 8.dp,
        accentColor = if (hasConfiguredPreset) channelAccent else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FluoIconBadge(
                icon = Icons.Outlined.Biotech,
                accentColor = channelAccent
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = analyte.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasConfiguredPreset) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val unit = analyte.defaultUnit?.takeIf(String::isNotBlank)
                        val maxRange = analyte.defaultMaxConcentration?.takeIf { it.isFinite() && it > 0.0 }
                        if (maxRange != null && unit != null) {
                            AnalyteMetaBadge(
                                text = stringResource(
                                    R.string.analyte_card_range_badge,
                                    formatCompactValue(maxRange),
                                    unit
                                ),
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else if (unit != null) {
                            AnalyteMetaBadge(
                                text = stringResource(R.string.analyte_card_unit_only_badge, unit),
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        val resolvedChannel = channel ?: FluorescenceChannel.GREEN
                        val dotColor = channelSemanticColor(resolvedChannel)
                        Surface(
                            shape = RoundedCornerShape(FluoRadius.badge),
                            color = dotColor.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(dotColor, CircleShape)
                                )
                                Text(
                                    text = channelShortLabel(resolvedChannel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dotColor,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        analyte.referenceCutoff?.takeIf { it.isFinite() && it > 0.0 }?.let { cutoff ->
                            val cutoffDisplay = if (unit != null) {
                                "${formatCompactValue(cutoff)} $unit"
                            } else {
                                formatCompactValue(cutoff)
                            }
                            AnalyteMetaBadge(
                                text = stringResource(R.string.analyte_card_cutoff_badge, cutoffDisplay),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.analyte_card_no_preset_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenReagents != null) {
                    IconButton(onClick = onOpenReagents, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Science,
                            contentDescription = stringResource(R.string.analyte_open_reagents_action),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_analyte),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyteMetaBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(FluoRadius.badge),
        color = containerColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class AnalyteProfileDraft(
    val name: String,
    val defaultUnit: String?,
    val defaultMaxConcentration: Double?,
    val defaultFluorescenceChannel: String?,
    val referenceCutoff: Double?
)

/**
 * 新建与编辑共用的分析物预设弹窗。
 *
 * 只有“名称”为必填项；其余预设均为可选，支持常见肿瘤标志物一键带入推荐单位、量程与参考截断值。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AnalyteProfileDialog(
    initialAnalyte: Analyte?,
    onDismiss: () -> Unit,
    onConfirm: (AnalyteProfileDraft) -> Unit
) {
    val isEditing = initialAnalyte != null
    var name by remember(initialAnalyte) { mutableStateOf(initialAnalyte?.name.orEmpty()) }
    var selectedUnit by remember(initialAnalyte) { mutableStateOf(initialAnalyte?.defaultUnit) }
    var maxInput by remember(initialAnalyte) {
        mutableStateOf(
            initialAnalyte?.defaultMaxConcentration
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::formatCompactValue)
                .orEmpty()
        )
    }
    var cutoffInput by remember(initialAnalyte) {
        mutableStateOf(
            initialAnalyte?.referenceCutoff
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::formatCompactValue)
                .orEmpty()
        )
    }
    var selectedChannel by remember(initialAnalyte) {
        mutableStateOf(
            initialAnalyte?.defaultFluorescenceChannel
                ?.uppercase()
                ?.let { code -> FluorescenceChannel.entries.firstOrNull { it.name == code } }
                ?: FluorescenceChannel.GREEN
        )
    }
    var nameError by remember { mutableStateOf(false) }

    fun applyPreset(preset: TumorBiomarkerPreset, overwriteName: Boolean) {
        if (overwriteName) {
            name = preset.canonicalName
            nameError = false
        }
        selectedUnit = preset.unit
        maxInput = formatCompactValue(preset.maxConcentration)
        selectedChannel = FluorescenceChannel.entries.firstOrNull {
            it.name == preset.fluorescenceChannel
        } ?: FluorescenceChannel.GREEN
        cutoffInput = preset.referenceCutoff?.let(::formatCompactValue).orEmpty()
    }

    val matchedPreset = remember(name) { TumorBiomarkerPresets.findByName(name) }
    val parsedMax = maxInput.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val maxInvalid = maxInput.isNotBlank() && (parsedMax == null || !parsedMax.isFinite() || parsedMax <= 0.0)
    val parsedCutoff = cutoffInput.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val cutoffInvalid = cutoffInput.isNotBlank() && (parsedCutoff == null || !parsedCutoff.isFinite() || parsedCutoff <= 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(FluoRadius.card),
        title = {
            Text(
                text = stringResource(if (isEditing) R.string.edit_analyte else R.string.add_analyte),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isEditing) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.analyte_quick_presets_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TumorBiomarkerPresets.all.take(8).forEach { preset ->
                                val active = name.equals(preset.canonicalName, ignoreCase = true)
                                FilterChip(
                                    selected = active,
                                    onClick = { applyPreset(preset, overwriteName = true) },
                                    label = { Text(preset.canonicalName) }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.analyte_name)) },
                    placeholder = { Text(stringResource(R.string.enter_analyte_name)) },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.analyte_name_empty)) }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.fillMaxWidth()
                )

                if (matchedPreset != null && selectedUnit != matchedPreset.unit) {
                    Surface(
                        shape = RoundedCornerShape(FluoRadius.badge),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.clickable { applyPreset(matchedPreset, overwriteName = false) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(
                                    R.string.analyte_use_matched_preset,
                                    matchedPreset.canonicalName,
                                    "0–${formatCompactValue(matchedPreset.maxConcentration)} ${matchedPreset.unit}"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.analyte_default_unit_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val unitOptions = remember(selectedUnit) {
                        val custom = selectedUnit?.takeIf { it.isNotBlank() && it !in STANDARD_ANALYTE_UNITS }
                        if (custom != null) listOf(custom) + STANDARD_ANALYTE_UNITS else STANDARD_ANALYTE_UNITS
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        unitOptions.forEach { unit ->
                            val selected = selectedUnit.equals(unit, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { selectedUnit = if (selected) null else unit },
                                label = { Text(unit) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = maxInput,
                        onValueChange = { value ->
                            val clean = value.replace(',', '.')
                            if (clean.matches(Regex("\\d{0,9}(\\.\\d{0,4})?"))) maxInput = clean
                        },
                        label = {
                            Text(
                                stringResource(R.string.analyte_default_max_label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        isError = maxInvalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(FluoRadius.control),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = cutoffInput,
                        onValueChange = { value ->
                            val clean = value.replace(',', '.')
                            if (clean.matches(Regex("\\d{0,9}(\\.\\d{0,4})?"))) cutoffInput = clean
                        },
                        label = {
                            Text(
                                stringResource(R.string.analyte_reference_cutoff_label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        isError = cutoffInvalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(FluoRadius.control),
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.analyte_default_fluo_channel_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FluorescenceChannel.entries.forEach { channel ->
                            val selected = selectedChannel == channel
                            val accent = channelSemanticColor(channel)
                            Surface(
                                shape = RoundedCornerShape(FluoRadius.badge),
                                color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .clickable { selectedChannel = channel }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(accent, CircleShape)
                                    )
                                    Text(
                                        text = channelShortLabel(channel),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    nameError = name.isBlank()
                    if (!nameError && !maxInvalid && !cutoffInvalid) {
                        onConfirm(
                            AnalyteProfileDraft(
                                name = name.trim(),
                                defaultUnit = selectedUnit,
                                defaultMaxConcentration = parsedMax,
                                defaultFluorescenceChannel = selectedChannel.name,
                                referenceCutoff = parsedCutoff
                            )
                        )
                    }
                },
                enabled = name.isNotBlank() && !maxInvalid && !cutoffInvalid,
                shape = RoundedCornerShape(FluoRadius.control)
            ) {
                Text(stringResource(if (isEditing) R.string.update else R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 删除分析物确认对话框
 */
@Composable
fun DeleteAnalyteDialog(
    analyteName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(FluoRadius.card),
        title = { Text(stringResource(R.string.delete_analyte_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                stringResource(
                    R.string.confirm_delete_analyte_with_name,
                    analyteName
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(FluoRadius.control)
            ) {
                Text(stringResource(R.string.delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun channelSemanticColor(channel: FluorescenceChannel): Color = when (channel) {
    FluorescenceChannel.RED -> Color(0xFFE53935)
    FluorescenceChannel.GREEN -> Color(0xFF2E7D32)
    FluorescenceChannel.BLUE -> Color(0xFF1E88E5)
    FluorescenceChannel.GRAY -> Color(0xFF546E7A)
}

@Composable
private fun channelShortLabel(channel: FluorescenceChannel): String = stringResource(
    when (channel) {
        FluorescenceChannel.RED -> R.string.analyte_channel_red_short
        FluorescenceChannel.GREEN -> R.string.analyte_channel_green_short
        FluorescenceChannel.BLUE -> R.string.analyte_channel_blue_short
        FluorescenceChannel.GRAY -> R.string.analyte_channel_gray_short
    }
)

private fun formatCompactValue(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}
