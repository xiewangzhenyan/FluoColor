package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.ArrayOptionField
import com.muc.fluocolorquant.ui.components.ArrayPercentSlider
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.detectionModeIcon
import com.muc.fluocolorquant.ui.components.label
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.MultiHoleStatistic
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel

/**
 * 统一管理新建项目预填值（检测方式、自定义阵列规格、浓度单位）与全局孔内取值/多孔统计默认规范。
 *
 * 定位算法切换收敛在现场定位确认页按图操作，孔内取值与多孔统计集中在本页配置，
 * 避免两处重复表单或单图临时改口径导致标准曲线版本不兼容；已保存历史运行始终读取冻结快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val toastManager = LocalToastManager.current
    
    val defaultDetectionMode by viewModel.defaultDetectionMode.collectAsState()
    val defaultConcentrationUnit by viewModel.defaultConcentrationUnit.collectAsState()
    val concentrationUnits by viewModel.concentrationUnits.collectAsState()
    val newUnitInput by viewModel.newUnitInput.collectAsState()
    val settingsUiState by viewModel.uiState.collectAsState()
    val readoutDefaults by viewModel.globalArrayReadoutDefaults.collectAsState()
    var defaultRowsInput by rememberSaveable { mutableStateOf("") }
    var defaultColumnsInput by rememberSaveable { mutableStateOf("") }
    val invalidGridMessage = stringResource(R.string.default_custom_grid_invalid)
    val savedMessage = stringResource(R.string.settings_update_success)
    val unitAddedMessage = stringResource(R.string.unit_added_success)
    val duplicateUnitMessage = stringResource(R.string.unit_already_exists)
    val unitDeletedMessage = stringResource(R.string.unit_deleted)
    val defaultUnitMessage = stringResource(R.string.cannot_delete_default_unit)

    // DataStore 首次返回后同步草稿；保存成功后的发射也会把规范化数值写回输入框。
    LaunchedEffect(settingsUiState.defaultCustomRows, settingsUiState.defaultCustomColumns) {
        defaultRowsInput = settingsUiState.defaultCustomRows.toString()
        defaultColumnsInput = settingsUiState.defaultCustomColumns.toString()
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.settings_detection_title),
                onBack = { navController.navigateUp() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(FluoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FluoSpacing.lg)
        ) {
            FluoSectionCard(verticalSpacing = FluoSpacing.sm) {
                DetectionModeSegments(
                    title = stringResource(R.string.default_detection_mode),
                    selected = DetectionModality.fromCode(defaultDetectionMode) ?: DetectionModality.FLUORESCENCE,
                    modifier = Modifier.testTag("default_detection_mode_picker"),
                    onSelect = { mode ->
                        if (mode.code != defaultDetectionMode) {
                            viewModel.setDefaultDetectionModeWithoutRestart(mode.code)
                        }
                    }
                )
                Text(
                    text = stringResource(R.string.default_detection_mode_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 这里只配置“新建自定义阵列”的预填值。固定 96 孔板和已有项目不会读取它；
            // 行列经同一个 ViewModel 调用原子保存，不能分别写入造成半更新规格。
            FluoSectionCard(verticalSpacing = FluoSpacing.md) {
                FluoSectionHeader(
                    title = stringResource(R.string.default_custom_grid_title),
                    icon = Icons.Default.GridView
                )
                Text(
                    text = stringResource(R.string.default_custom_grid_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)) {
                    OutlinedTextField(
                        value = defaultRowsInput,
                        onValueChange = { defaultRowsInput = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.direct_create_rows)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = defaultColumnsInput,
                        onValueChange = { defaultColumnsInput = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.direct_create_columns)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Button(
                    onClick = {
                        val saved = viewModel.updateDefaultCustomGrid(
                            rows = defaultRowsInput.toIntOrNull(), columns = defaultColumnsInput.toIntOrNull()
                        )
                        toastManager.showToast(
                            message = if (saved) savedMessage else invalidGridMessage,
                            type = if (saved) ToastType.SUCCESS else ToastType.ERROR
                        )
                    },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(FluoSpacing.sm))
                    Text(stringResource(R.string.save))
                }
            }

            ArrayReadoutDefaultsCard(
                config = readoutDefaults,
                onSave = { updated ->
                    if (updated != readoutDefaults) {
                        viewModel.saveGlobalArrayReadoutDefaults(updated)
                        toastManager.showToast(savedMessage, ToastType.SUCCESS)
                    }
                }
            )

            FluoSectionCard(verticalSpacing = FluoSpacing.md) {
                ConcentrationUnitSelector(
                    currentUnit = defaultConcentrationUnit,
                    onUnitSelected = { unit ->
                        if (unit != defaultConcentrationUnit) {
                            viewModel.setDefaultConcentrationUnitWithoutRestart(unit)
                            toastManager.showToast(savedMessage, ToastType.SUCCESS)
                        }
                    },
                    availableUnits = concentrationUnits.toList()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
                ) {
                    OutlinedTextField(
                        value = newUnitInput,
                        onValueChange = viewModel::updateNewUnitInput,
                        label = { Text(stringResource(R.string.new_concentration_unit)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val added = viewModel.addConcentrationUnit(newUnitInput)
                            if (added) viewModel.updateNewUnitInput("")
                            toastManager.showToast(
                                message = if (added) unitAddedMessage else duplicateUnitMessage,
                                type = if (added) ToastType.SUCCESS else ToastType.ERROR
                            )
                        },
                        enabled = newUnitInput.isNotBlank()
                    ) {
                        Text(stringResource(R.string.add))
                    }
                }
                Text(text = stringResource(R.string.available_concentration_units), style = MaterialTheme.typography.bodyMedium)
                ConcentrationUnitChips(
                    units = concentrationUnits.toList(),
                    defaultUnit = defaultConcentrationUnit,
                    onDeleteUnit = { unit ->
                        val deleted = viewModel.deleteConcentrationUnit(unit)
                        toastManager.showToast(
                            message = if (deleted) unitDeletedMessage else defaultUnitMessage,
                            type = if (deleted) ToastType.SUCCESS else ToastType.WARNING
                        )
                    }
                )
            }
        }
    }
}

/**
 * 全局孔内取值与多孔统计设置卡（合并入「检测默认值」，不再按 3 种载体 × 2 种模态拆分重复入口）。
 */
@Composable
internal fun ArrayReadoutDefaultsCard(
    config: ArrayAnalysisPreferences,
    onSave: (ArrayAnalysisPreferences) -> Unit
) {
    val defaultConfig = remember { ArrayAnalysisPreferences() }
    val isCustomized = config.readout != defaultConfig.readout ||
        config.multiHoleStatistic != defaultConfig.multiHoleStatistic ||
        config.centerScale != defaultConfig.centerScale
    FluoSectionCard(
        modifier = Modifier.testTag("array_readout_defaults_card"),
        verticalSpacing = FluoSpacing.md
    ) {
        FluoSectionHeader(
            title = stringResource(R.string.settings_readout_section_title),
            icon = Icons.Outlined.Colorize
        )
        Text(
            text = stringResource(R.string.settings_readout_section_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ArrayOptionField(
            R.string.array_readout_label,
            WellReadoutMode.entries,
            config.readout,
            { it.label() },
            Icons.Outlined.Colorize,
            { onSave(config.copy(readout = it)) },
            Modifier.testTag("array_readout_picker")
        )
        if (config.readout in setOf(WellReadoutMode.CENTER_MEDIAN, WellReadoutMode.CENTER_MEAN)) {
            ArrayPercentSlider(
                R.string.array_readout_center_percent,
                config.centerScale,
                50..90
            ) { onSave(config.copy(centerScale = it)) }
        }
        if (config.readout != WellReadoutMode.FULL_MEDIAN) {
            Surface(
                shape = RoundedCornerShape(FluoRadius.badge),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        if (config.readout == WellReadoutMode.CENTER_PIXEL) R.string.array_readout_diagnostic
                        else R.string.array_readout_recalibrate
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        ArrayOptionField(
            R.string.array_group_label,
            MultiHoleStatistic.entries,
            config.multiHoleStatistic,
            { it.label() },
            Icons.Outlined.Analytics,
            { onSave(config.copy(multiHoleStatistic = it)) },
            Modifier.testTag("array_group_picker")
        )
        if (config.multiHoleStatistic != MultiHoleStatistic.INDIVIDUAL) {
            Text(
                text = stringResource(R.string.array_group_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isCustomized) {
            TextButton(
                onClick = { onSave(defaultConfig) },
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .testTag("array_readout_reset")
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.array_analysis_reset))
            }
        }
    }
}

/**
 * 默认方式和阵列档案共用视觉，不共用写入回调。这里仅显示现有可持久化的比色、荧光，
 * 不把新建项目里可手选的光谱误当成当前支持的默认值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetectionModeSegments(
    title: String,
    selected: DetectionModality,
    onSelect: (DetectionModality) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(DetectionModality.COLORIMETRIC, DetectionModality.FLUORESCENCE)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FluoSpacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labels = listOf(stringResource(R.string.array_modality_color), stringResource(R.string.array_modality_fluorescence))
            val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            val measure = rememberTextMeasurer()
            val longestLabel = labels.maxOf { measure.measure(it, style = labelStyle, maxLines = 1).size.width }
            val iconSize = 18.dp
            val iconSpacing = 6.dp
            val inlineWidth = with(LocalDensity.current) { longestLabel.toDp() + iconSize + iconSpacing + 24.dp }
            // 两侧一起切换图标布局，并按较粗的选中文字测宽。不能只让英文荧光换行，破坏分段接缝。
            val stackIcons = maxWidth / 2 < inlineWidth
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.heightIn(min = if (stackIcons) 64.dp else 48.dp)
                            .testTag("detection_mode_${mode.name}"),
                        icon = {}
                    ) {
                        val icon: @Composable () -> Unit = {
                            Icon(detectionModeIcon(mode), contentDescription = null, modifier = Modifier.size(iconSize))
                        }
                        val name: @Composable () -> Unit = {
                            Text(labels[index], style = labelStyle, textAlign = TextAlign.Center)
                        }
                        if (stackIcons) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
                            ) {
                                icon()
                                name()
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(iconSpacing), verticalAlignment = Alignment.CenterVertically) {
                                icon()
                                name()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConcentrationUnitSelector(
    currentUnit: String,
    onUnitSelected: (String) -> Unit,
    availableUnits: List<String>
) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(R.string.default_concentration_unit)
    ScientificSelectionField(
        title, currentUnit, title, Icons.Outlined.Straighten,
        onClick = { expanded = true }, allowValueWrap = true
    )
    if (expanded) {
        ScientificPickerSheet(
            title = title,
            options = availableUnits.sorted().map { ScientificPickerOption(id = it, title = it) },
            selectedId = currentUnit,
            onSelect = onUnitSelected,
            onDismiss = { expanded = false },
            compact = true
        )
    }
}

@Composable
fun ConcentrationUnitChips(
    units: List<String>,
    defaultUnit: String,
    onDeleteUnit: (String) -> Unit
) {
    var unitToDelete by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    
    // 删除确认对话框
    unitToDelete?.let { unit ->
        AlertDialog(
            onDismissRequest = { unitToDelete = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.confirm_delete_single)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteUnit(unit)
                        unitToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { unitToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // 获取排序后的所有单位（默认单位除外）
    val otherUnits = units.sorted().filter { it != defaultUnit }
    
    // 确定要显示的单位数量
    val displayCount = if (expanded) otherUnits.size else minOf(3, otherUnits.size)
    
    Column {
        // 显示默认单位
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = defaultUnit,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f)
            )
            
            Text(
                text = stringResource(R.string.parenthesized_value, stringResource(R.string.default_unit)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Divider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
        
        // 显示其他单位（限制数量）
        otherUnits.take(displayCount).forEach { unit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = { unitToDelete = unit }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        // 显示展开/折叠按钮（仅当有更多单位时）
        if (otherUnits.size > 3) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(
                        id = if (expanded) R.string.collapse else R.string.expand_all
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Icon(
                    imageVector = if (expanded) 
                        Icons.Default.KeyboardArrowUp 
                    else 
                        Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
