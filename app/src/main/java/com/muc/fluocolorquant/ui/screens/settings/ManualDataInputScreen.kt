package com.muc.fluocolorquant.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.ui.viewmodels.CreationFlowState
import com.muc.fluocolorquant.ui.viewmodels.CurveModelEvent
import com.muc.fluocolorquant.ui.viewmodels.CurveModelViewModel
import com.muc.fluocolorquant.utils.math.CalibrationDataParser
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 页面中的一行标准品数据；字符串状态允许用户在编辑过程中暂时清空输入。 */
private data class CalibrationPointInput(
    val concentration: String = "",
    val signal: String = ""
)

/**
 * 标准曲线创建页面。
 *
 * 普通用户只需要输入模型名、选择信号特征、录入或导入两列标定点，然后点击拟合。函数
 * 默认为自动推荐，也可以从下拉列表指定；函数参数始终由 [FittingEngine] 计算并只读展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDataInputScreen(
    navController: NavController,
    viewModel: CurveModelViewModel = hiltViewModel(),
    navigateBack: () -> Unit = { navController.navigateUp() }
) {
    val creationFlowState by viewModel.activeCreationFlow.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val errorResourceId by viewModel.errorResourceId.collectAsState()
    val manualState = creationFlowState as? CreationFlowState.ManualDataInput
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()
    val selectedFunctionTitle = manualState?.selectedFunction?.let { function ->
        localizedFittingFunctionTitle(function)
    }

    var modelName by remember { mutableStateOf("") }
    var showFunctionPicker by remember { mutableStateOf(false) }
    var showPixelPicker by remember { mutableStateOf(false) }
    val pointInputs = remember {
        mutableStateListOf<CalibrationPointInput>().apply {
            repeat(4) { add(CalibrationPointInput()) }
        }
    }

    LaunchedEffect(Unit) {
        if (creationFlowState == null) viewModel.startManualDataInput()
    }

    // 只有仓库确认保存完成后才退出页面，避免数据库失败时用户误以为模型已经创建。
    LaunchedEffect(viewModel, context, toastManager) {
        viewModel.events.collect { event ->
            if (event == CurveModelEvent.ModelSaved) {
                toastManager.showToast(
                    context.getString(R.string.standard_curve_saved),
                    ToastType.SUCCESS
                )
                navigateBack()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parseResult = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        CalibrationDataParser.parse(reader.readText())
                    } ?: error("empty_stream")
                }
            }
            parseResult.onSuccess { result ->
                if (result.points.size < 2) {
                    toastManager.showToast(
                        context.getString(R.string.standard_curve_import_not_enough_points),
                        ToastType.WARNING
                    )
                } else {
                    pointInputs.clear()
                    pointInputs.addAll(
                        result.points.map { (concentration, signal) ->
                            CalibrationPointInput(
                                concentration = concentration.toInputText(),
                                signal = signal.toInputText()
                            )
                        }
                    )
                    toastManager.showToast(
                        context.getString(
                            R.string.standard_curve_import_success,
                            result.points.size,
                            result.ignoredLineCount
                        ),
                        ToastType.SUCCESS
                    )
                }
            }.onFailure {
                toastManager.showToast(
                    context.getString(R.string.standard_curve_import_failed),
                    ToastType.ERROR
                )
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.standard_curve_create_title)) },
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
                    StandardCurveSectionCard(
                        title = stringResource(R.string.standard_curve_basic_section)
                    ) {
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.model_name)) },
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = { showPixelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                manualState?.selectedPixelType?.displayName.orEmpty(),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                        }
                        OutlinedButton(
                            onClick = { showFunctionPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedFunctionTitle
                                    ?: stringResource(R.string.standard_curve_auto_recommend),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ExpandMore, contentDescription = null)
                        }
                        Text(
                            stringResource(R.string.standard_curve_auto_recommend_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    StandardCurveSectionCard(
                        title = stringResource(R.string.standard_curve_points_section)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { pointInputs.add(CalibrationPointInput()) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.standard_curve_add_point))
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(
                                        arrayOf("text/csv", "text/plain", "text/tab-separated-values")
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.standard_curve_import_csv))
                            }
                        }
                        Text(
                            stringResource(R.string.standard_curve_csv_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                itemsIndexed(pointInputs) { index, point ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.standard_curve_point_number, index + 1),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                IconButton(
                                    onClick = { if (pointInputs.size > 2) pointInputs.removeAt(index) },
                                    enabled = pointInputs.size > 2
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = stringResource(R.string.remove)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = point.concentration,
                                    onValueChange = { input ->
                                        if (input.isDecimalInput(allowNegative = false)) {
                                            pointInputs[index] = point.copy(concentration = input)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.concentration)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = point.signal,
                                    onValueChange = { input ->
                                        if (input.isDecimalInput(allowNegative = true)) {
                                            pointInputs[index] = point.copy(signal = input)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.standard_curve_signal_value)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            val points = pointInputs.mapNotNull { point ->
                                val concentration = point.concentration.toDoubleOrNull()
                                val signal = point.signal.toDoubleOrNull()
                                if (concentration != null && signal != null) concentration to signal
                                else null
                            }
                            val partiallyFilled = pointInputs.any { point ->
                                point.concentration.isBlank() xor point.signal.isBlank()
                            }
                            if (partiallyFilled || points.size < 2) {
                                toastManager.showToast(
                                    context.getString(R.string.standard_curve_points_invalid),
                                    ToastType.WARNING
                                )
                            } else {
                                viewModel.performFitFromPoints(points)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 13.dp)
                    ) {
                        Icon(Icons.Default.AutoGraph, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (manualState?.selectedFunction == null) {
                                    R.string.standard_curve_start_auto_fit
                                } else {
                                    R.string.start_fitting
                                }
                            )
                        )
                    }
                }

                manualState?.fittingResult?.takeIf { it.isSuccess }?.let { result ->
                    item {
                        StandardCurveSectionCard(
                            title = stringResource(R.string.standard_curve_result_section)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.standard_curve_recommended_function,
                                            localizedFittingFunctionTitle(result.function)
                                        ),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(
                                            R.string.standard_curve_r_squared,
                                            String.format(Locale.US, "%.4f", result.rSquared)
                                        )
                                    )
                                }
                            }
                            CurveChart(
                                fittedCurve = { x ->
                                    FittingEngine.calculate(result.function, result.params, x)
                                },
                                selectedFunction = result.function,
                                parameters = result.params,
                                xAxisLabel = stringResource(R.string.concentration),
                                yAxisLabel = manualState.selectedPixelType.displayName,
                                title = stringResource(R.string.standard_curve_chart_title),
                                modifier = Modifier.height(260.dp),
                                dataPoints = result.standardPoints
                            )
                            LatexView(
                                latex = FittingEngine.formatParametersToLatex(
                                    result.function,
                                    result.params
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                textSize = 18.sp
                            )
                            MetricsTable(
                                metrics = result.metrics.mapValues { (_, value) ->
                                    String.format(Locale.US, "%.6f", value)
                                },
                                title = stringResource(R.string.standard_curve_metrics_title)
                            )
                            Button(
                                onClick = { viewModel.saveModel(modelName.trim()) },
                                enabled = modelName.isNotBlank() && !isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 13.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.save_model))
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }

            if (isLoading) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 8.dp
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }
            }
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearErrorMessage,
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = viewModel::clearErrorMessage) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    errorResourceId?.let { resourceId ->
        AlertDialog(
            onDismissRequest = viewModel::clearErrorMessage,
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(resourceId)) },
            confirmButton = {
                Button(onClick = viewModel::clearErrorMessage) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    if (showPixelPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.pixel_type),
            options = PixelType.entries.map { pixelType ->
                ScientificPickerOption(id = pixelType.identifier, title = pixelType.displayName)
            },
            selectedId = manualState?.selectedPixelType?.identifier,
            onSelect = { id -> PixelType.fromIdentifier(id)?.let(viewModel::updateDataPixelType) },
            onDismiss = { showPixelPicker = false }
        )
    }

    if (showFunctionPicker) {
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
                    // 使用项目已集成的 jlatexmath-android 直接排版公式，不显示控制符源码。
                    latexSubtitle = function.latexFormula
                )
            },
            selectedId = manualState?.selectedFunction?.identifier ?: autoId,
            onSelect = { id ->
                viewModel.updateDataFittingFunction(
                    if (id == autoId) null else FittingFunction.fromIdentifier(id)
                )
            },
            onDismiss = { showFunctionPicker = false }
        )
    }
}

/**
 * 返回拟合函数在当前语言下的用户可见名称。
 *
 * [FittingFunction.displayName] 仍用于历史数据兼容、日志和内部标识；新建标准曲线页面不再
 * 直接显示其中固定的英文文本，避免中文界面出现大段未本地化的专业名词。
 */
@Composable
private fun localizedFittingFunctionTitle(function: FittingFunction): String {
    val resourceId = when (function) {
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
    return stringResource(resourceId)
}

/** 统一的分区卡片，减少表单中无意义的层层嵌套。 */
@Composable
private fun StandardCurveSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

/** 根据字段语义过滤输入，防止字母、重复小数点和不允许的负号进入状态。 */
private fun String.isDecimalInput(allowNegative: Boolean): Boolean {
    if (isEmpty() || this == ".") return true
    if (allowNegative && (this == "-" || this == "-.")) return true
    val pattern = if (allowNegative) Regex("^-?\\d*(?:\\.\\d*)?$")
    else Regex("^\\d*(?:\\.\\d*)?$")
    return matches(pattern)
}

private fun Double.toInputText(): String {
    return if (this % 1.0 == 0.0) toLong().toString() else toString()
}
