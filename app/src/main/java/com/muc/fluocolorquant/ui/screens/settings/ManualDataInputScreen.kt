package com.muc.fluocolorquant.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureDescription
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.components.analysisFeatureRangeLabel
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveBuilderError
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveBuilderEvent
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveBuilderUiState
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveBuilderViewModel
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveFitCandidate
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveImportDraft
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 普通创建页当前展示的数据来源。 */
private enum class StandardCurveDataSource {
    NONE,
    CSV,
    MANUAL
}

/**
 * 普通用户结果页允许展示的拟合指标键，顺序同时就是页面展示顺序。
 *
 * 拟合引擎还会生成调整后 R²、ICH M10 裁决、权重方式等内部诊断信息。这些数据必须继续
 * 供自动择优和科研追溯使用，但直接展示会让普通用户误以为需要理解法规裁决细节，因此这里
 * 只建立展示白名单，不修改底层指标契约。
 */
private val STANDARD_CURVE_VISIBLE_METRIC_KEYS = listOf(
    "R²",
    "RMSE",
    "MAE",
    "Accepted Standard Ratio"
)

/**
 * 从完整拟合诊断中提取普通用户真正需要查看的四项指标。
 *
 * 使用 [LinkedHashMap] 保持稳定顺序，避免底层 Map 实现变化导致界面上的指标顺序跳动。
 * 该函数不负责本地化，UI 层随后使用字符串资源把内部稳定键转换为当前语言。
 */
internal fun selectStandardCurveVisibleMetrics(
    allMetrics: Map<String, Double>
): Map<String, Double> = linkedMapOf<String, Double>().apply {
    STANDARD_CURVE_VISIBLE_METRIC_KEYS.forEach { key ->
        allMetrics[key]?.takeIf(Double::isFinite)?.let { value -> put(key, value) }
    }
}

/**
 * 统一标准曲线创建页面。
 *
 * 页面不再写入旧 CurveModel，也不允许用户输入函数参数 JSON。分析物、检测模式、单位、
 * 主信号和载体在保存时共同冻结到 AnalysisModel，保证创建项目时能够执行严格兼容匹配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDataInputScreen(
    navController: NavController,
    viewModel: StandardCurveBuilderViewModel = hiltViewModel(),
    navigateBack: () -> Unit = { navController.navigateUp() }
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()
    var dataSource by remember { mutableStateOf(StandardCurveDataSource.NONE) }
    var showAnalytePicker by remember { mutableStateOf(false) }
    var showUnitPicker by remember { mutableStateOf(false) }
    var showFeaturePicker by remember { mutableStateOf(false) }
    var showFunctionPicker by remember { mutableStateOf(false) }
    var showCsvMapping by remember { mutableStateOf(false) }
    var mappingFeatureColumn by remember { mutableStateOf<Int?>(null) }

    // 在 Composable 上下文中提前解析全部资源，事件协程只读取普通字符串。
    val errorMessages = mapOf(
        StandardCurveBuilderError.MODEL_NAME_REQUIRED to
            stringResource(R.string.standard_curve_error_name_required),
        StandardCurveBuilderError.MODEL_NAME_DUPLICATE to
            stringResource(R.string.standard_curve_error_name_duplicate),
        StandardCurveBuilderError.ANALYTE_REQUIRED to
            stringResource(R.string.standard_curve_error_analyte_required),
        StandardCurveBuilderError.UNIT_REQUIRED to
            stringResource(R.string.standard_curve_error_unit_required),
        StandardCurveBuilderError.CARRIER_REQUIRED to
            stringResource(R.string.standard_curve_error_carrier_required),
        StandardCurveBuilderError.POINTS_INVALID to
            stringResource(R.string.standard_curve_points_invalid),
        StandardCurveBuilderError.SIGNAL_OUT_OF_RANGE to
            stringResource(R.string.standard_curve_error_signal_range),
        StandardCurveBuilderError.IMPORT_MAPPING_REQUIRED to
            stringResource(R.string.standard_curve_error_mapping_required),
        StandardCurveBuilderError.FIT_FAILED to
            stringResource(R.string.standard_curve_fit_failed),
        StandardCurveBuilderError.FIT_FIRST to
            stringResource(R.string.standard_curve_fit_first),
        StandardCurveBuilderError.SAVE_FAILED to
            stringResource(R.string.standard_curve_save_failed)
    )

    LaunchedEffect(viewModel, toastManager, navigateBack) {
        viewModel.events.collect { event ->
            when (event) {
                is StandardCurveBuilderEvent.Saved -> {
                    toastManager.showToast(
                        context.getString(R.string.standard_curve_saved_unified),
                        ToastType.SUCCESS
                    )
                    navigateBack()
                }
                is StandardCurveBuilderEvent.ValidationFailed -> {
                    toastManager.showToast(
                        errorMessages.getValue(event.error),
                        if (event.error == StandardCurveBuilderError.SAVE_FAILED) {
                            ToastType.ERROR
                        } else {
                            ToastType.WARNING
                        }
                    )
                }
            }
        }
    }

    // 编辑已有曲线时，ViewModel 会恢复真实标定点；页面自动展开手工数据区，让用户直接
    // 看见并修改原始数据，不需要再猜测应该点击“CSV”还是“手工录入”。
    LaunchedEffect(state.editingModelId) {
        if (state.editingModelId != null && dataSource == StandardCurveDataSource.NONE) {
            dataSource = StandardCurveDataSource.MANUAL
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                toastManager.showToast(
                    context.getString(R.string.standard_curve_import_failed),
                    ToastType.ERROR
                )
            } else {
                viewModel.importCalibrationText(content)
                dataSource = StandardCurveDataSource.CSV
                showCsvMapping = true
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.editingModelId == null) {
                                R.string.standard_curve_unified_title
                            } else {
                                R.string.standard_curve_edit_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    UnifiedCurveSection(
                        icon = Icons.Default.Science,
                        title = stringResource(R.string.standard_curve_identity_section),
                        subtitle = stringResource(R.string.standard_curve_identity_help)
                    ) {
                        OutlinedTextField(
                            value = state.modelName,
                            onValueChange = viewModel::updateModelName,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.standard_curve_name_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        DetectionModeSelector(
                            selected = state.detectionModality,
                            onSelected = viewModel::updateDetectionModality
                        )
                        val analyteName = state.analytes
                            .firstOrNull { it.id == state.selectedAnalyteId }
                            ?.name
                        ScientificSelectionField(
                            label = stringResource(R.string.analyte),
                            value = analyteName,
                            placeholder = stringResource(R.string.direct_create_select_analyte),
                            icon = Icons.Default.Biotech,
                            onClick = { showAnalytePicker = true },
                            enabled = !state.isLoading
                        )
                        ScientificSelectionField(
                            label = stringResource(R.string.concentration_unit),
                            value = state.concentrationUnit,
                            placeholder = stringResource(R.string.direct_create_select_unit),
                            icon = Icons.Default.Straighten,
                            onClick = { showUnitPicker = true }
                        )
                        Text(
                            stringResource(R.string.standard_curve_carrier_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(CarrierType.PLATE, CarrierType.MICROFLUIDIC_CHIP).forEach { carrier ->
                                FilterChip(
                                    selected = carrier in state.compatibleCarrierTypes,
                                    onClick = { viewModel.toggleCarrier(carrier) },
                                    label = { Text(carrierLabel(carrier)) },
                                    leadingIcon = if (carrier in state.compatibleCarrierTypes) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp)) }
                                    } else null
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.standard_curve_carrier_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    UnifiedCurveSection(
                        icon = Icons.Default.Analytics,
                        title = stringResource(R.string.standard_curve_feature_section),
                        subtitle = stringResource(R.string.standard_curve_feature_help)
                    ) {
                        ScientificSelectionField(
                            label = stringResource(R.string.standard_curve_feature_label),
                            value = analysisFeatureLabel(state.selectedFeature),
                            placeholder = stringResource(R.string.standard_curve_feature_label),
                            icon = Icons.Default.Analytics,
                            onClick = { showFeaturePicker = true },
                            supportingValue = analysisFeatureRangeLabel(state.selectedFeature)
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    analysisFeatureDescription(state.selectedFeature),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                item {
                    UnifiedCurveSection(
                        icon = Icons.Default.GridView,
                        title = stringResource(R.string.standard_curve_data_section),
                        subtitle = stringResource(R.string.standard_curve_data_help)
                    ) {
                        DataSourceOption(
                            title = stringResource(R.string.standard_curve_import_wide_csv),
                            description = stringResource(R.string.standard_curve_import_wide_csv_desc),
                            icon = Icons.Default.FileOpen,
                            selected = dataSource == StandardCurveDataSource.CSV,
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "text/*",
                                        "text/csv",
                                        "text/comma-separated-values",
                                        "application/csv",
                                        "application/vnd.ms-excel",
                                        // 部分 Android 文件提供方会把 CSV 标成二进制流；实际内容
                                        // 仍由解析器严格验证，允许选择比让合法文件变灰更可靠。
                                        "application/octet-stream"
                                    )
                                )
                            }
                        )
                        DataSourceOption(
                            title = stringResource(R.string.standard_curve_manual_advanced),
                            description = stringResource(R.string.standard_curve_manual_advanced_desc),
                            icon = Icons.Default.Functions,
                            selected = dataSource == StandardCurveDataSource.MANUAL,
                            onClick = {
                                viewModel.clearImport()
                                dataSource = StandardCurveDataSource.MANUAL
                            }
                        )

                        state.importDraft?.let { importDraft ->
                            ImportedDataSummary(
                                importDraft = importDraft,
                                onAdjust = { showCsvMapping = true },
                                onRemove = {
                                    viewModel.clearImport()
                                    dataSource = StandardCurveDataSource.NONE
                                }
                            )
                        }
                    }
                }

                if (dataSource == StandardCurveDataSource.MANUAL && state.importDraft == null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                            )
                        ) {
                            Text(
                                stringResource(R.string.standard_curve_manual_warning),
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    itemsIndexed(state.manualPoints) { index, point ->
                        ManualCalibrationPointCard(
                            index = index,
                            point = point,
                            concentrationUnit = state.concentrationUnit,
                            signalLabel = analysisFeatureLabel(state.selectedFeature),
                            signalRange = analysisFeatureRangeLabel(state.selectedFeature),
                            allowDelete = state.manualPoints.size > 2,
                            onConcentrationChange = {
                                if (it.isDecimalInput(allowNegative = false)) {
                                    viewModel.updateManualPoint(index, concentration = it)
                                }
                            },
                            onSignalChange = {
                                if (it.isDecimalInput(allowNegative = true)) {
                                    viewModel.updateManualPoint(index, signal = it)
                                }
                            },
                            onDelete = { viewModel.removeManualPoint(index) }
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = viewModel::addManualPoint,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(R.string.standard_curve_add_point))
                        }
                    }
                }

                if (dataSource != StandardCurveDataSource.NONE) {
                    item {
                        UnifiedCurveSection(
                            icon = Icons.Default.AutoGraph,
                            title = stringResource(R.string.standard_curve_result_section),
                            subtitle = stringResource(R.string.standard_curve_auto_recommend_desc)
                        ) {
                            OutlinedButton(
                                onClick = { showFunctionPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    state.selectedFunction?.let { localizedFittingFunctionTitle(it) }
                                        ?: stringResource(R.string.standard_curve_auto_recommend),
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                            Button(
                                onClick = viewModel::fit,
                                enabled = !state.isFitting && !state.isSaving,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 13.dp)
                            ) {
                                if (state.isFitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.AutoGraph, contentDescription = null)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.standard_curve_start_auto_fit))
                            }
                        }
                    }
                }

                if (state.candidates.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.standard_curve_fit_candidates),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    itemsIndexed(state.candidates) { index, candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate.id == state.selectedCandidateId,
                            recommended = index == 0,
                            onClick = { viewModel.selectCandidate(candidate.id) }
                        )
                    }
                    state.selectedCandidate?.let { candidate ->
                        item {
                            SelectedCandidateResult(
                                candidate = candidate,
                                concentrationUnit = state.concentrationUnit
                            )
                        }
                        item {
                            Button(
                                onClick = viewModel::save,
                                enabled = !state.isSaving,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                if (state.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (state.editingModelId == null) {
                                            R.string.standard_curve_save_and_use
                                        } else {
                                            R.string.standard_curve_save_changes
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }

            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (showAnalytePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_analyte),
            options = state.analytes.map {
                ScientificPickerOption(id = it.id, title = it.name, icon = Icons.Default.Biotech)
            },
            selectedId = state.selectedAnalyteId,
            onSelect = viewModel::updateAnalyte,
            onDismiss = { showAnalytePicker = false }
        )
    }
    if (showUnitPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_unit),
            options = state.concentrationUnits.map {
                ScientificPickerOption(id = it, title = it, icon = Icons.Default.Straighten)
            },
            selectedId = state.concentrationUnit,
            onSelect = viewModel::updateConcentrationUnit,
            onDismiss = { showUnitPicker = false }
        )
    }
    if (showFeaturePicker) {
        FeaturePicker(
            state = state,
            selectedFeature = state.selectedFeature,
            onSelect = viewModel::updateFeature,
            onDismiss = { showFeaturePicker = false }
        )
    }
    if (showFunctionPicker) {
        FunctionPicker(
            selectedFunction = state.selectedFunction,
            onSelect = viewModel::updateFunction,
            onDismiss = { showFunctionPicker = false }
        )
    }
    if (showCsvMapping) {
        state.importDraft?.let { draft ->
            CsvMappingSheet(
                draft = draft,
                onConcentrationColumn = viewModel::updateImportConcentrationColumn,
                onSignalColumnToggle = viewModel::toggleImportSignalColumn,
                onFeatureClick = { columnIndex ->
                    showCsvMapping = false
                    mappingFeatureColumn = columnIndex
                },
                onDismiss = { showCsvMapping = false }
            )
        }
    }
    mappingFeatureColumn?.let { columnIndex ->
        FeaturePicker(
            state = state,
            selectedFeature = state.importDraft?.featureMappings?.get(columnIndex),
            onSelect = { feature -> viewModel.updateImportFeature(columnIndex, feature) },
            onDismiss = {
                mappingFeatureColumn = null
                showCsvMapping = true
            }
        )
    }
}

@Composable
private fun UnifiedCurveSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun DetectionModeSelector(
    selected: DetectionModality,
    onSelected: (DetectionModality) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            stringResource(R.string.standard_curve_mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DetectionModality.COLORIMETRIC, DetectionModality.FLUORESCENCE).forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    label = { Text(detectionModeLabel(mode)) },
                    leadingIcon = if (selected == mode) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp)) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun DataSourceOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ImportedDataSummary(
    importDraft: StandardCurveImportDraft,
    onAdjust: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(
                    R.string.standard_curve_csv_rows_summary,
                    importDraft.table.rows.size,
                    importDraft.table.columns.size
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    R.string.standard_curve_csv_selected_summary,
                    importDraft.readySignalColumnIndices.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.standard_curve_csv_adjust_mapping))
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.standard_curve_csv_remove))
                }
            }
        }
    }
}

@Composable
private fun ManualCalibrationPointCard(
    index: Int,
    point: com.muc.fluocolorquant.ui.viewmodels.StandardCurvePointDraft,
    concentrationUnit: String,
    signalLabel: String,
    signalRange: String,
    allowDelete: Boolean,
    onConcentrationChange: (String) -> Unit,
    onSignalChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.standard_curve_point_number, index + 1),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDelete, enabled = allowDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.remove))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = point.concentration,
                    onValueChange = onConcentrationChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.concentration)) },
                    suffix = { Text(concentrationUnit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = point.signal,
                    onValueChange = onSignalChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(signalLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
            Text(
                signalRange,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: StandardCurveFitCandidate,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit
) {
    val acceptance = candidate.result.metrics["Accepted Standard Ratio"]
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                if (selected) Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        localizedFittingFunctionTitle(candidate.result.function),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                stringResource(R.string.standard_curve_recommended_badge),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.standard_curve_candidate_feature,
                        analysisFeatureLabel(candidate.feature)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.standard_curve_candidate_source, candidate.sourceLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                acceptance?.let {
                    Text(
                        stringResource(
                            R.string.standard_curve_candidate_acceptance,
                            String.format(Locale.US, "%.0f%%", it * 100.0)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                String.format(Locale.US, "R² %.4f", candidate.result.rSquared),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun SelectedCandidateResult(
    candidate: StandardCurveFitCandidate,
    concentrationUnit: String
) {
    val visibleMetrics = selectStandardCurveVisibleMetrics(candidate.result.metrics)
    val localizedMetrics = buildMap {
        visibleMetrics["R²"]?.let { value ->
            put(
                stringResource(R.string.standard_curve_metric_r_squared),
                String.format(Locale.US, "%.4f", value)
            )
        }
        visibleMetrics["RMSE"]?.let { value ->
            put(
                stringResource(R.string.standard_curve_metric_rmse),
                String.format(Locale.US, "%.6g", value)
            )
        }
        visibleMetrics["MAE"]?.let { value ->
            put(
                stringResource(R.string.standard_curve_metric_mae),
                String.format(Locale.US, "%.6g", value)
            )
        }
        visibleMetrics["Accepted Standard Ratio"]?.let { value ->
            put(
                stringResource(R.string.standard_curve_metric_standard_acceptance),
                String.format(Locale.US, "%.0f%%", value * 100.0)
            )
        }
    }
    UnifiedCurveSection(
        icon = Icons.Default.AutoGraph,
        title = localizedFittingFunctionTitle(candidate.result.function),
        subtitle = analysisFeatureLabel(candidate.feature)
    ) {
        CurveChart(
            fittedCurve = { x ->
                FittingEngine.calculate(candidate.result.function, candidate.result.params, x)
            },
            selectedFunction = candidate.result.function,
            parameters = candidate.result.params,
            xAxisLabel = "${stringResource(R.string.concentration)} ($concentrationUnit)",
            yAxisLabel = analysisFeatureLabel(candidate.feature),
            title = "",
            dataPoints = candidate.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )
        LatexView(
            latex = FittingEngine.formatParametersToLatex(
                candidate.result.function,
                candidate.result.params
            ),
            modifier = Modifier.fillMaxWidth(),
            textSize = 18.sp
        )
        MetricsTable(
            metrics = localizedMetrics,
            title = stringResource(R.string.standard_curve_metrics_title)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CsvMappingSheet(
    draft: StandardCurveImportDraft,
    onConcentrationColumn: (Int) -> Unit,
    onSignalColumnToggle: (Int) -> Unit,
    onFeatureClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.standard_curve_csv_mapping_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Text(
                    stringResource(R.string.standard_curve_csv_concentration_column),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    draft.table.columns.forEach { column ->
                        FilterChip(
                            selected = column.index == draft.concentrationColumnIndex,
                            onClick = { onConcentrationColumn(column.index) },
                            label = { Text(column.header) }
                        )
                    }
                }
            }
            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.standard_curve_csv_signal_columns),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            itemsIndexed(draft.table.columns.filter { it.index != draft.concentrationColumnIndex }) { _, column ->
                val selected = column.index in draft.selectedSignalColumnIndices
                val feature = draft.featureMappings[column.index]
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { onSignalColumnToggle(column.index) }
                            )
                            Text(
                                column.header,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        OutlinedButton(
                            onClick = { onFeatureClick(column.index) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selected
                        ) {
                            Text(
                                feature?.let { analysisFeatureLabel(it) }
                                    ?: stringResource(R.string.standard_curve_csv_select_feature),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                        }
                    }
                }
            }
            item {
                CsvPreview(draft)
            }
            item {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@Composable
private fun CsvPreview(draft: StandardCurveImportDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            stringResource(R.string.standard_curve_csv_preview),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (draft.table.rows.isEmpty()) {
            Text(
                stringResource(R.string.standard_curve_csv_no_data),
                color = MaterialTheme.colorScheme.error
            )
            return
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Row {
                draft.table.columns.forEach { column ->
                    Text(
                        column.header,
                        modifier = Modifier
                            .width(128.dp)
                            .padding(6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            draft.table.rows.take(5).forEach { row ->
                Row {
                    row.values.forEach { value ->
                        Text(
                            value?.let { String.format(Locale.US, "%.6g", it) }.orEmpty(),
                            modifier = Modifier
                                .width(128.dp)
                                .padding(6.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturePicker(
    state: StandardCurveBuilderUiState,
    selectedFeature: AnalysisPrimaryFeature?,
    onSelect: (AnalysisPrimaryFeature) -> Unit,
    onDismiss: () -> Unit
) {
    ScientificPickerSheet(
        title = stringResource(R.string.standard_curve_feature_label),
        options = state.allowedFeatures.map { feature ->
            ScientificPickerOption(
                id = feature.code,
                title = analysisFeatureLabel(feature),
                subtitle = analysisFeatureRangeLabel(feature),
                icon = Icons.Default.Analytics
            )
        },
        selectedId = selectedFeature?.code,
        onSelect = { code -> AnalysisPrimaryFeature.fromCode(code)?.let(onSelect) },
        onDismiss = onDismiss
    )
}

@Composable
private fun FunctionPicker(
    selectedFunction: FittingFunction?,
    onSelect: (FittingFunction?) -> Unit,
    onDismiss: () -> Unit
) {
    val autoId = "AUTO"
    val functions = FittingFunction.entries.filter { it != FittingFunction.INTERPOLATION }
    ScientificPickerSheet(
        title = stringResource(R.string.standard_curve_function_picker_title),
        options = listOf(
            ScientificPickerOption(
                id = autoId,
                title = stringResource(R.string.standard_curve_auto_recommend),
                subtitle = stringResource(R.string.standard_curve_auto_recommend_short_desc)
            )
        ) + functions.map { function ->
            ScientificPickerOption(
                id = function.identifier,
                title = localizedFittingFunctionTitle(function),
                latexSubtitle = function.latexFormula
            )
        },
        selectedId = selectedFunction?.identifier ?: autoId,
        onSelect = { id -> onSelect(if (id == autoId) null else FittingFunction.fromIdentifier(id)) },
        onDismiss = onDismiss
    )
}

@Composable
private fun detectionModeLabel(modality: DetectionModality): String = when (modality) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.analysis_model_mode_colorimetric)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.analysis_model_mode_fluorescence)
    DetectionModality.SPECTRUM -> stringResource(R.string.analysis_model_mode_spectrum)
}

@Composable
private fun carrierLabel(carrierType: CarrierType): String = when (carrierType) {
    CarrierType.PLATE -> stringResource(R.string.analysis_model_carrier_plate)
    CarrierType.MICROFLUIDIC_CHIP -> stringResource(R.string.analysis_model_carrier_chip)
    CarrierType.CUSTOM -> stringResource(R.string.analysis_model_carrier_custom)
}

@Composable
private fun localizedFittingFunctionTitle(function: FittingFunction): String = stringResource(
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

/** 过滤字母、重复小数点和非法负号，避免输入框把错误数据送入拟合器。 */
private fun String.isDecimalInput(allowNegative: Boolean): Boolean {
    if (isEmpty() || this == ".") return true
    if (allowNegative && (this == "-" || this == "-.")) return true
    val pattern = if (allowNegative) Regex("^-?\\d*(?:\\.\\d*)?$")
    else Regex("^\\d*(?:\\.\\d*)?$")
    return matches(pattern)
}
