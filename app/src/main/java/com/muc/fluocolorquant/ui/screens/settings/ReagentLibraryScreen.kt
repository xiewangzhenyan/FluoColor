@file:OptIn(ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.QrCode2
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.ui.components.FluoIconBadge
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.ReagentViewModel
import kotlinx.coroutines.launch

/**
 * 试剂批次库管理页。
 *
 * 与「分析物管理」形成一对多关联：每个标志物可登记实验用捕获/检测抗体或标准抗原及其厂商、批号。
 * 新建检测时可按需关联，并冻结至结果页与导出 PDF 报告中作为实验批次溯源依据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReagentLibraryScreen(
    initialAnalyteId: String? = null,
    viewModel: ReagentViewModel = hiltViewModel(),
    navigateBack: () -> Unit
) {
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()

    val reagentsGroupedByAnalyte by viewModel.reagentsGroupedByAnalyte.collectAsState()
    val analytes by viewModel.allAnalytes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    val reagentAddedSuccessMsg = stringResource(R.string.reagent_added_success)
    val reagentDeletedSuccessMsg = stringResource(R.string.reagent_deleted_success)
    val reagentUpdatedSuccessMsg = stringResource(R.string.reagent_updated_success)

    var selectedAnalyteFilterId by rememberSaveable(initialAnalyteId) {
        mutableStateOf(initialAnalyteId?.takeIf(String::isNotBlank))
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var reagentToEdit by remember { mutableStateOf<Reagent?>(null) }
    var reagentToDelete by remember { mutableStateOf<Reagent?>(null) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearError()
        }
    }

    val allFlatEntries = remember(reagentsGroupedByAnalyte) {
        reagentsGroupedByAnalyte.entries
            .sortedBy { it.key.name.lowercase() }
            .flatMap { (analyte, list) ->
                list.sortedBy { it.reagentName.lowercase() }.map { reagent -> analyte to reagent }
            }
    }
    val totalCount = allFlatEntries.size
    val antigenCount = remember(allFlatEntries) {
        allFlatEntries.count { it.second.reagentType.equals("antigen", ignoreCase = true) }
    }
    val antibodyCount = remember(allFlatEntries) {
        allFlatEntries.count { it.second.reagentType.equals("antibody", ignoreCase = true) }
    }

    val filteredEntries = remember(allFlatEntries, selectedAnalyteFilterId, searchQuery) {
        val query = searchQuery.trim()
        allFlatEntries.filter { (analyte, reagent) ->
            val matchesAnalyte = selectedAnalyteFilterId == null || analyte.id == selectedAnalyteFilterId
            val matchesQuery = query.isEmpty() ||
                reagent.reagentName.contains(query, ignoreCase = true) ||
                analyte.name.contains(query, ignoreCase = true) ||
                (reagent.manufacturer?.contains(query, ignoreCase = true) == true) ||
                (reagent.lotNumber?.contains(query, ignoreCase = true) == true)
            matchesAnalyte && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.reagent_library_title),
                onBack = navigateBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_reagent)) }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("reagent_library_screen")
        ) {
            when {
                isLoading && totalCount == 0 -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 10.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "summary_header") {
                            FluoSectionCard(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                verticalSpacing = 6.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    FluoIconBadge(
                                        icon = Icons.Outlined.Science,
                                        accentColor = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(
                                                R.string.reagent_summary_counts,
                                                totalCount,
                                                antigenCount,
                                                antibodyCount
                                            ),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.reagent_summary_traceability_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (analytes.isNotEmpty()) {
                            item(key = "analyte_filter_row") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = selectedAnalyteFilterId == null,
                                        onClick = { selectedAnalyteFilterId = null },
                                        label = { Text(stringResource(R.string.resource_filter_all)) }
                                    )
                                    analytes.forEach { analyte ->
                                        FilterChip(
                                            selected = selectedAnalyteFilterId == analyte.id,
                                            onClick = {
                                                selectedAnalyteFilterId =
                                                    if (selectedAnalyteFilterId == analyte.id) null else analyte.id
                                            },
                                            label = { Text(analyte.name) }
                                        )
                                    }
                                }
                            }
                        }

                        if (totalCount >= 4 || searchQuery.isNotEmpty()) {
                            item(key = "search_bar") {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource(R.string.reagent_search_placeholder)) },
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

                        if (filteredEntries.isEmpty()) {
                            item(key = "empty_state") {
                                FluoStatePlaceholder(
                                    icon = Icons.Outlined.Science,
                                    text = stringResource(R.string.no_reagents_found),
                                    supportingText = stringResource(R.string.add_your_first_reagent),
                                    actionText = stringResource(R.string.add_reagent),
                                    onAction = { showAddDialog = true }
                                )
                            }
                        } else {
                            items(filteredEntries, key = { it.second.id }) { (analyte, reagent) ->
                                ReagentCard(
                                    reagent = reagent,
                                    analyteName = analyte.name,
                                    onEdit = { reagentToEdit = reagent },
                                    onDelete = { reagentToDelete = reagent }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ReagentFormDialog(
            initialReagent = null,
            defaultAnalyteId = selectedAnalyteFilterId ?: analytes.firstOrNull()?.id.orEmpty(),
            analytes = analytes,
            onDismiss = { showAddDialog = false },
            onConfirm = { analyteId, name, type, manufacturer, lotNumber ->
                scope.launch {
                    val saved = viewModel.addReagent(
                        analyteId = analyteId,
                        reagentName = name,
                        reagentType = type,
                        manufacturer = manufacturer,
                        lotNumber = lotNumber
                    )
                    if (saved) {
                        showAddDialog = false
                        toastManager.showToast(reagentAddedSuccessMsg, ToastType.SUCCESS)
                    }
                }
            }
        )
    }

    reagentToEdit?.let { editing ->
        ReagentFormDialog(
            initialReagent = editing,
            defaultAnalyteId = editing.analyteId,
            analytes = analytes,
            onDismiss = { reagentToEdit = null },
            onConfirm = { analyteId, name, type, manufacturer, lotNumber ->
                scope.launch {
                    val updated = viewModel.updateReagent(
                        id = editing.id,
                        analyteId = analyteId,
                        reagentName = name,
                        reagentType = type,
                        manufacturer = manufacturer,
                        lotNumber = lotNumber
                    )
                    if (updated) {
                        reagentToEdit = null
                        toastManager.showToast(reagentUpdatedSuccessMsg, ToastType.SUCCESS)
                    }
                }
            }
        )
    }

    reagentToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { reagentToDelete = null },
            title = { Text(stringResource(R.string.delete_reagent_title)) },
            text = {
                Text(stringResource(R.string.confirm_delete_reagent_with_name, target.reagentName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteReagent(target)
                        reagentToDelete = null
                        toastManager.showToast(reagentDeletedSuccessMsg, ToastType.SUCCESS)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { reagentToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ReagentCard(
    reagent: Reagent,
    analyteName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isAntigen = reagent.reagentType.equals("antigen", ignoreCase = true)
    val accent = if (isAntigen) Color(0xFF0E7490) else MaterialTheme.colorScheme.primary
    val typeLabel = if (isAntigen) stringResource(R.string.antigen) else stringResource(R.string.antibody)

    FluoSectionCard(
        modifier = Modifier.clickable(onClick = onEdit),
        contentPadding = FluoSpacing.md,
        verticalSpacing = 8.dp,
        accentColor = accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FluoIconBadge(
                icon = Icons.Outlined.Science,
                accentColor = accent
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = reagent.reagentName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ReagentMetaPill(
                        text = analyteName,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    ReagentMetaPill(
                        text = typeLabel,
                        containerColor = accent.copy(alpha = 0.14f),
                        contentColor = accent
                    )
                    reagent.manufacturer?.takeIf(String::isNotBlank)?.let { maker ->
                        ReagentMetaPill(
                            text = maker,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    reagent.lotNumber?.takeIf(String::isNotBlank)?.let { lot ->
                        ReagentMetaPill(
                            text = stringResource(R.string.reagent_lot_badge, lot),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, modifier = Modifier.size(42.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_reagent),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(42.dp)) {
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
private fun ReagentMetaPill(
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReagentFormDialog(
    initialReagent: Reagent?,
    defaultAnalyteId: String,
    analytes: List<Analyte>,
    onDismiss: () -> Unit,
    onConfirm: (analyteId: String, name: String, type: String, manufacturer: String?, lotNumber: String?) -> Unit
) {
    var selectedAnalyteId by rememberSaveable(initialReagent?.id, defaultAnalyteId) {
        mutableStateOf(initialReagent?.analyteId ?: defaultAnalyteId)
    }
    var reagentName by rememberSaveable(initialReagent?.id) {
        mutableStateOf(initialReagent?.reagentName.orEmpty())
    }
    var selectedType by rememberSaveable(initialReagent?.id) {
        mutableStateOf(initialReagent?.reagentType?.takeIf(String::isNotBlank) ?: "antibody")
    }
    var manufacturer by rememberSaveable(initialReagent?.id) {
        mutableStateOf(initialReagent?.manufacturer.orEmpty())
    }
    var lotNumber by rememberSaveable(initialReagent?.id) {
        mutableStateOf(initialReagent?.lotNumber.orEmpty())
    }
    var showAnalytePicker by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    val selectedAnalyteName = analytes.firstOrNull { it.id == selectedAnalyteId }?.name
        ?: stringResource(R.string.select_analyte)
    val typeChoices = listOf(
        "antibody" to stringResource(R.string.antibody),
        "antigen" to stringResource(R.string.antigen)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (initialReagent == null) R.string.add_reagent else R.string.edit_reagent
                ),
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
                ScientificSelectionField(
                    label = stringResource(R.string.analyte),
                    value = selectedAnalyteName,
                    placeholder = stringResource(R.string.select_analyte),
                    icon = Icons.Outlined.Biotech,
                    onClick = { showAnalytePicker = true },
                    enabled = analytes.isNotEmpty()
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.reagent_type),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        typeChoices.forEachIndexed { idx, (code, label) ->
                            SegmentedButton(
                                selected = selectedType == code,
                                onClick = { selectedType = code },
                                shape = SegmentedButtonDefaults.itemShape(idx, typeChoices.size),
                                modifier = Modifier.heightIn(min = 44.dp),
                                icon = {}
                            ) {
                                Text(label, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = reagentName,
                    onValueChange = {
                        reagentName = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.reagent_name)) },
                    placeholder = { Text(stringResource(R.string.enter_reagent_name)) },
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = { Text(stringResource(R.string.manufacturer)) },
                    placeholder = { Text(stringResource(R.string.enter_manufacturer)) },
                    leadingIcon = { Icon(Icons.Outlined.Factory, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = lotNumber,
                    onValueChange = { lotNumber = it },
                    label = { Text(stringResource(R.string.reagent_lot_label)) },
                    placeholder = { Text(stringResource(R.string.reagent_lot_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.QrCode2, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(FluoRadius.control),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = reagentName.trim()
                    if (trimmed.isEmpty()) {
                        nameError = true
                    } else if (selectedAnalyteId.isNotBlank()) {
                        onConfirm(
                            selectedAnalyteId,
                            trimmed,
                            selectedType,
                            manufacturer.trim().takeIf(String::isNotEmpty),
                            lotNumber.trim().takeIf(String::isNotEmpty)
                        )
                    }
                },
                enabled = selectedAnalyteId.isNotBlank() && reagentName.isNotBlank()
            ) {
                Text(stringResource(if (initialReagent == null) R.string.confirm else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showAnalytePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.select_analyte),
            options = analytes.map { analyte ->
                ScientificPickerOption(id = analyte.id, title = analyte.name)
            },
            selectedId = selectedAnalyteId,
            onSelect = { id ->
                selectedAnalyteId = id
                showAnalytePicker = false
            },
            onDismiss = { showAnalytePicker = false },
            compact = true
        )
    }
}
