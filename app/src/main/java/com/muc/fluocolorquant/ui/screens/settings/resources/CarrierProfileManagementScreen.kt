package com.muc.fluocolorquant.ui.screens.settings.resources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.ui.viewmodels.CarrierProfileUiState
import com.muc.fluocolorquant.ui.viewmodels.CarrierProfileViewModel

/**
 * 载体与布局库页面。
 *
 * 该页面只管理物理载体几何，不提供比色/荧光/光谱切换，从交互层贯彻“载体与检测
 * 模态正交”的设计。10×10、15×15 是主快捷规格，4×4 保留在更多规格中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarrierProfileManagementScreen(
    navController: NavController,
    viewModel: CarrierProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    ResourceProfileEventEffect(viewModel.events)

    val chip10Name = stringResource(R.string.carrier_default_name_chip_10)
    val chip15Name = stringResource(R.string.carrier_default_name_chip_15)
    val plate96Name = stringResource(R.string.carrier_default_name_plate_96)
    val legacy4Name = stringResource(R.string.carrier_default_name_legacy_4)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.carrier_library_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openCreateEditor(CarrierPreset.CUSTOM, "") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.carrier_add_profile)) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ResourceLibraryHeader(
                    title = stringResource(R.string.carrier_library_title),
                    subtitle = stringResource(R.string.carrier_library_subtitle),
                    accentColor = MaterialTheme.colorScheme.primary,
                    metrics = listOf(
                        ResourceSummaryMetric(
                            stringResource(R.string.resource_summary_total),
                            state.profiles.size.toString(),
                            Icons.Default.Dataset
                        ),
                        ResourceSummaryMetric(
                            stringResource(R.string.resource_summary_active),
                            state.activeCount.toString(),
                            Icons.Default.GridOn
                        ),
                        ResourceSummaryMetric(
                            stringResource(R.string.carrier_summary_chip),
                            state.microfluidicCount.toString(),
                            Icons.Default.Memory
                        )
                    )
                )
            }

            item {
                QuickCarrierPresets(
                    onChip10 = {
                        viewModel.openCreateEditor(CarrierPreset.MICROFLUIDIC_10_X_10, chip10Name)
                    },
                    onChip15 = {
                        viewModel.openCreateEditor(CarrierPreset.MICROFLUIDIC_15_X_15, chip15Name)
                    }
                )
            }

            item {
                ResourceStatusFilterBar(
                    selected = state.filter,
                    onSelected = viewModel::selectFilter
                )
            }

            if (state.visibleProfiles.isEmpty()) {
                item {
                    ResourceEmptyState(
                        title = stringResource(R.string.carrier_empty_title),
                        description = stringResource(R.string.carrier_empty_desc)
                    )
                }
            } else {
                items(state.visibleProfiles, key = CarrierProfile::id) { profile ->
                    CarrierProfileCard(
                        profile = profile,
                        onCreateNewVersion = { viewModel.openNewVersionEditor(profile) },
                        onArchive = { viewModel.requestArchive(profile) }
                    )
                }
            }
        }
    }

    if (state.isEditorVisible) {
        CarrierEditorSheet(
            state = state,
            chip10Name = chip10Name,
            chip15Name = chip15Name,
            plate96Name = plate96Name,
            legacy4Name = legacy4Name,
            onDraftChange = viewModel::updateDraft,
            onDismiss = viewModel::dismissEditor,
            onSave = viewModel::save
        )
    }

    state.pendingArchive?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::cancelArchive,
            title = { Text(stringResource(R.string.resource_archive_confirm_title)) },
            text = {
                Text(stringResource(R.string.resource_archive_confirm_message, profile.name))
            },
            confirmButton = {
                Button(onClick = viewModel::confirmArchive) {
                    Text(stringResource(R.string.resource_archive))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelArchive) {
                    Text(stringResource(R.string.resource_cancel))
                }
            }
        )
    }
}

/** 主页面只突出当前常用的 10×10 与 15×15。 */
@Composable
private fun QuickCarrierPresets(onChip10: () -> Unit, onChip15: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.carrier_quick_create),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = false,
                    onClick = onChip10,
                    label = { Text(stringResource(R.string.carrier_preset_chip_10)) },
                    leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null) }
                )
                FilterChip(
                    selected = false,
                    onClick = onChip15,
                    label = { Text(stringResource(R.string.carrier_preset_chip_15)) },
                    leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null) }
                )
            }
        }
    }
}

/** 单个载体版本卡片，归档版本只读。 */
@Composable
private fun CarrierProfileCard(
    profile: CarrierProfile,
    onCreateNewVersion: () -> Unit,
    onArchive: () -> Unit
) {
    val isActive = profile.status == ResourceStatus.ACTIVE.code
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.resource_version_value, profile.version),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ResourceStatusBadge(profile.status)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ResourceMetadataPill(carrierTypeLabel(profile.carrierType), Icons.Default.Memory)
                ResourceMetadataPill(
                    stringResource(R.string.carrier_grid_value, profile.rows, profile.columns),
                    Icons.Default.GridOn
                )
                ResourceMetadataPill(
                    stringResource(R.string.carrier_sites_value, profile.rows * profile.columns),
                    Icons.Default.Dataset
                )
                ResourceMetadataPill(siteShapeLabel(profile.siteShape), Icons.Default.Circle)
            }

            if (isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCreateNewVersion) {
                        Icon(Icons.Default.Upgrade, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.resource_new_version))
                    }
                    TextButton(onClick = onArchive) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.resource_archive))
                    }
                }
            }
        }
    }
}

/** 载体创建与新版本编辑底部面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarrierEditorSheet(
    state: CarrierProfileUiState,
    chip10Name: String,
    chip15Name: String,
    plate96Name: String,
    legacy4Name: String,
    onDraftChange: (CarrierProfileDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.draft
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(
                    if (state.editingSourceId == null) R.string.carrier_create_title
                    else R.string.carrier_new_version_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.carrier_quick_create),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CarrierPresetChip(
                    label = stringResource(R.string.carrier_preset_chip_10),
                    onClick = {
                        onDraftChange(CarrierPreset.MICROFLUIDIC_10_X_10.createDraft(chip10Name))
                    }
                )
                CarrierPresetChip(
                    label = stringResource(R.string.carrier_preset_chip_15),
                    onClick = {
                        onDraftChange(CarrierPreset.MICROFLUIDIC_15_X_15.createDraft(chip15Name))
                    }
                )
                CarrierPresetChip(
                    label = stringResource(R.string.carrier_preset_plate_96),
                    onClick = { onDraftChange(CarrierPreset.PLATE_96.createDraft(plate96Name)) }
                )
            }

            Text(
                text = stringResource(R.string.resource_more_specs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarrierPresetChip(
                    label = stringResource(R.string.carrier_preset_legacy_4),
                    onClick = { onDraftChange(CarrierPreset.LEGACY_4_X_4.createDraft(legacy4Name)) }
                )
                CarrierPresetChip(
                    label = stringResource(R.string.carrier_preset_custom),
                    onClick = { onDraftChange(CarrierPreset.CUSTOM.createDraft("")) }
                )
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.resource_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = state.editingSourceId != null
            )

            ChoiceChipRow(
                title = stringResource(R.string.carrier_type_label),
                choices = CarrierType.entries,
                selected = draft.carrierType,
                label = { type -> carrierTypeLabel(type.code) },
                onSelected = { onDraftChange(draft.copy(carrierType = it)) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.rowsInput,
                    onValueChange = { onDraftChange(draft.copy(rowsInput = it.filter(Char::isDigit))) },
                    label = { Text(stringResource(R.string.carrier_rows_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.columnsInput,
                    onValueChange = {
                        onDraftChange(draft.copy(columnsInput = it.filter(Char::isDigit)))
                    },
                    label = { Text(stringResource(R.string.carrier_columns_label)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            ChoiceChipRow(
                title = stringResource(R.string.carrier_site_shape_label),
                choices = SiteShape.entries,
                selected = draft.siteShape,
                label = { shape -> siteShapeLabel(shape.code) },
                onSelected = { onDraftChange(draft.copy(siteShape = it)) }
            )

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Text(
                    stringResource(
                        if (state.editingSourceId == null) R.string.resource_save
                        else R.string.resource_create_next_version
                    )
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CarrierPresetChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

/** 泛型单选 Chip 行，用于载体类型和位点形状。 */
@Composable
private fun <T> ChoiceChipRow(
    title: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelected(choice) },
                    label = { Text(label(choice)) }
                )
            }
        }
    }
}

@Composable
private fun carrierTypeLabel(code: String): String = when (CarrierType.fromCode(code)) {
    CarrierType.PLATE -> stringResource(R.string.carrier_type_plate)
    CarrierType.MICROFLUIDIC_CHIP -> stringResource(R.string.carrier_type_chip)
    CarrierType.CUSTOM, null -> stringResource(R.string.carrier_type_custom)
}

@Composable
private fun siteShapeLabel(code: String): String = when (SiteShape.fromCode(code)) {
    SiteShape.CIRCLE -> stringResource(R.string.carrier_site_shape_circle)
    SiteShape.SQUARE -> stringResource(R.string.carrier_site_shape_square)
    SiteShape.POINT -> stringResource(R.string.carrier_site_shape_point)
    SiteShape.CUSTOM, null -> stringResource(R.string.carrier_site_shape_custom)
}
