package com.muc.fluocolorquant.ui.screens.settings.resources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.ui.viewmodels.AcquisitionProfileUiState
import com.muc.fluocolorquant.ui.viewmodels.AcquisitionProfileViewModel

/**
 * 采集设备档案管理页面。
 *
 * 普通实验人员后续只选择命名后的档案；本页面向管理员保存模态、载体、光学模块、
 * 固定装置和相机控制策略，不要求用户输入厘米距离或安装角度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcquisitionProfileManagementScreen(
    navController: NavController,
    viewModel: AcquisitionProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    ResourceProfileEventEffect(viewModel.events)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.acquisition_library_title)) },
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
                onClick = viewModel::openCreateEditor,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.acquisition_add_profile)) }
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
                    accentColor = MaterialTheme.colorScheme.secondary,
                    metrics = listOf(
                        ResourceSummaryMetric(
                            stringResource(R.string.resource_summary_total),
                            state.profiles.size.toString(),
                            Icons.Default.Dataset
                        ),
                        ResourceSummaryMetric(
                            stringResource(R.string.resource_summary_active),
                            state.activeCount.toString(),
                            Icons.Default.PhotoCamera
                        ),
                        ResourceSummaryMetric(
                            stringResource(R.string.acquisition_summary_fixture),
                            state.fixedFixtureCount.toString(),
                            Icons.Default.Lock
                        )
                    )
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
                        title = stringResource(R.string.acquisition_empty_title),
                        description = stringResource(R.string.acquisition_empty_desc)
                    )
                }
            } else {
                items(state.visibleProfiles, key = AcquisitionProfile::id) { profile ->
                    AcquisitionProfileCard(
                        profile = profile,
                        onCreateNewVersion = { viewModel.openNewVersionEditor(profile) },
                        onArchive = { viewModel.requestArchive(profile) }
                    )
                }
            }
        }
    }

    if (state.isEditorVisible) {
        AcquisitionEditorSheet(
            state = state,
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

/** 设备卡片把 JSON 集合转换为普通用户可读的本地化摘要。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcquisitionProfileCard(
    profile: AcquisitionProfile,
    onCreateNewVersion: () -> Unit,
    onArchive: () -> Unit
) {
    val isActive = profile.status == ResourceStatus.ACTIVE.code
    val modeCodes = ResourceProfileJsonCodec.decodeCodes(
        profile.supportedModesJson,
        DetectionModality.entries.mapTo(mutableSetOf()) { it.code }
    )
    val modeLabels = mutableListOf<String>()
    for (code in modeCodes) modeLabels += detectionModeLabel(code)
    val modes = modeLabels.joinToString(" · ")

    val carrierCodes = ResourceProfileJsonCodec.decodeCodes(
        profile.compatibleCarrierTypesJson,
        CarrierType.entries.mapTo(mutableSetOf()) { it.code }
    )
    val carrierLabels = mutableListOf<String>()
    for (code in carrierCodes) carrierLabels += carrierTypeDisplayLabel(code)
    val carrierTypes = carrierLabels.joinToString(" · ")
    val opticalModule = profile.opticalModuleName
        ?: stringResource(R.string.acquisition_no_optical_module)
    val fixture = profile.fixtureId ?: stringResource(R.string.acquisition_no_fixture)

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

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = stringResource(R.string.acquisition_modes_value, modes),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.acquisition_carriers_value, carrierTypes),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ResourceMetadataPill(opticalModule, Icons.Default.CameraAlt)
                ResourceMetadataPill(fixture, Icons.Default.Lock)
                ResourceMetadataPill(
                    cameraStrategyLabel(profile.cameraControlStrategy),
                    Icons.Default.Memory
                )
            }

            if (isActive) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

/** 采集设备创建与新版本编辑面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcquisitionEditorSheet(
    state: AcquisitionProfileUiState,
    onDraftChange: (AcquisitionProfileDraft) -> Unit,
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
                    if (state.editingSourceId == null) R.string.acquisition_create_title
                    else R.string.acquisition_new_version_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.resource_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = state.editingSourceId != null
            )

            MultiChoiceChipSection(
                title = stringResource(R.string.acquisition_modes_label),
                values = DetectionModality.entries,
                selectedCodes = draft.supportedModes,
                code = DetectionModality::code,
                label = { value -> detectionModeEnumLabel(value) },
                onToggle = { modality ->
                    onDraftChange(
                        draft.copy(supportedModes = draft.supportedModes.toggle(modality.code))
                    )
                }
            )

            MultiChoiceChipSection(
                title = stringResource(R.string.acquisition_carriers_label),
                values = CarrierType.entries,
                selectedCodes = draft.compatibleCarrierTypes,
                code = CarrierType::code,
                label = { value -> carrierTypeEnumLabel(value) },
                onToggle = { carrierType ->
                    onDraftChange(
                        draft.copy(
                            compatibleCarrierTypes = draft.compatibleCarrierTypes.toggle(
                                carrierType.code
                            )
                        )
                    )
                }
            )

            SingleChoiceChipSection(
                title = stringResource(R.string.acquisition_control_strategy_label),
                values = CameraControlStrategy.entries,
                selected = draft.cameraControlStrategy,
                label = { value -> cameraStrategyEnumLabel(value) },
                onSelected = { onDraftChange(draft.copy(cameraControlStrategy = it)) }
            )

            OutlinedTextField(
                value = draft.opticalModuleName,
                onValueChange = { onDraftChange(draft.copy(opticalModuleName = it)) },
                label = { Text(stringResource(R.string.acquisition_optical_module_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.fixtureId,
                onValueChange = { onDraftChange(draft.copy(fixtureId = it)) },
                label = { Text(stringResource(R.string.acquisition_fixture_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.deviceMatcherNote,
                onValueChange = { onDraftChange(draft.copy(deviceMatcherNote = it)) },
                label = { Text(stringResource(R.string.acquisition_device_matcher_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
            ) {
                Text(
                    text = stringResource(R.string.acquisition_distance_note),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

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
private fun <T> MultiChoiceChipSection(
    title: String,
    values: List<T>,
    selectedCodes: Set<String>,
    code: (T) -> String,
    label: @Composable (T) -> String,
    onToggle: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = code(value) in selectedCodes,
                    onClick = { onToggle(value) },
                    label = { Text(label(value)) }
                )
            }
        }
    }
}

@Composable
private fun <T> SingleChoiceChipSection(
    title: String,
    values: List<T>,
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
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(label(value)) }
                )
            }
        }
    }
}

private fun Set<String>.toggle(code: String): Set<String> =
    if (code in this) this - code else this + code

@Composable
private fun detectionModeEnumLabel(value: DetectionModality): String = detectionModeLabel(value.code)

@Composable
private fun detectionModeLabel(code: String): String = when (code) {
    DetectionModality.COLORIMETRIC.code -> stringResource(R.string.acquisition_mode_colorimetric)
    DetectionModality.FLUORESCENCE.code -> stringResource(R.string.acquisition_mode_fluorescence)
    else -> stringResource(R.string.acquisition_mode_spectrum)
}

@Composable
private fun carrierTypeEnumLabel(value: CarrierType): String = carrierTypeDisplayLabel(value.code)

@Composable
private fun carrierTypeDisplayLabel(code: String): String = when (CarrierType.fromCode(code)) {
    CarrierType.PLATE -> stringResource(R.string.carrier_type_plate)
    CarrierType.MICROFLUIDIC_CHIP -> stringResource(R.string.carrier_type_chip)
    CarrierType.CUSTOM, null -> stringResource(R.string.carrier_type_custom)
}

@Composable
private fun cameraStrategyEnumLabel(value: CameraControlStrategy): String =
    cameraStrategyLabel(value.code)

@Composable
private fun cameraStrategyLabel(code: String): String = when (code) {
    CameraControlStrategy.TEMPLATE_CONSTRAINED.code -> stringResource(
        R.string.acquisition_strategy_template
    )
    CameraControlStrategy.FIXED_PARAMETERS.code -> stringResource(R.string.acquisition_strategy_fixed)
    else -> stringResource(R.string.acquisition_strategy_auto_lock)
}
