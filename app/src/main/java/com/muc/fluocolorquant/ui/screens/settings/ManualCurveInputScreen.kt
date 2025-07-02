package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.viewmodels.CurveModelViewModel
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCurveInputScreen(
    navController: NavController,
    viewModel: CurveModelViewModel = hiltViewModel(),
    navigateBack: () -> Unit = { navController.navigateUp() }
) {
    val creationFlowState by viewModel.activeCreationFlow.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var modelName by remember { mutableStateOf("") }
    var functionExpanded by remember { mutableStateOf(false) }
    var pixelTypeExpanded by remember { mutableStateOf(false) }
    
    // 添加协程作用域用于处理延迟操作
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    LaunchedEffect(key1 = Unit) {
        if (creationFlowState == null) {
            viewModel.startManualCurveInput()
        }
    }

    val manualCurveState = (creationFlowState as? com.muc.fluocolorquant.ui.viewmodels.CreationFlowState.ManualCurveInput)
    val selectedFunction = manualCurveState?.selectedFunction
    val selectedPixelType = manualCurveState?.selectedPixelType
    val parameters = manualCurveState?.parameters ?: mutableMapOf()

    // 使用FittingEngine中的formatParametersToLatex函数
    val functionLatexExpression = remember(selectedFunction, parameters) {
        FittingEngine.formatParametersToLatex(selectedFunction, parameters)
    }

    val allParamsEntered = selectedFunction?.requiredParams?.all { parameters.containsKey(it) } == true

    // 过滤掉INTERPOLATION函数
    val availableFunctions = remember {
        FittingFunction.values().filter { it != FittingFunction.INTERPOLATION }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.import_curve)) },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ExposedDropdownMenuBox(
                        expanded = functionExpanded,
                        onExpandedChange = { functionExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = selectedFunction?.displayName ?: "",
                            onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.function_type)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = functionExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = functionExpanded,
                            onDismissRequest = { functionExpanded = false }
                        ) {
                            availableFunctions.forEach { function ->
                                DropdownMenuItem(
                                    text = { Text(function.displayName) },
                                    onClick = {
                                        viewModel.updateSelectedFunction(function)
                                        functionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    ExposedDropdownMenuBox(
                        expanded = pixelTypeExpanded,
                        onExpandedChange = { pixelTypeExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = selectedPixelType?.displayName ?: "",
                            onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.pixel_type)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pixelTypeExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = pixelTypeExpanded,
                            onDismissRequest = { pixelTypeExpanded = false }
                        ) {
                            PixelType.values().forEach { pixelType ->
                                DropdownMenuItem(
                                    text = { Text(pixelType.displayName) },
                                    onClick = {
                                        viewModel.updateSelectedPixelType(pixelType)
                                        pixelTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (selectedFunction != null) {
                        Text(
                            text = stringResource(R.string.curve_parameters),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        selectedFunction.requiredParams.forEach { paramName ->
                            var localValue by remember(selectedFunction, paramName) {
                                mutableStateOf(parameters[paramName]?.let {
                                    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                                } ?: "")
                            }
                            OutlinedTextField(
                                value = localValue,
                                onValueChange = { text ->
                                    if (text.isEmpty() || text == "-" || text == "." || text == "-." || text.matches(Regex("^-?\\d*\\.?\\d*$"))) {
                                        localValue = text
                                        val doubleValue = text.toDoubleOrNull()
                                        viewModel.updateFunctionParameter(paramName, doubleValue)
                                    }
                                },
                                label = { Text(stringResource(R.string.parameter_placeholder, paramName)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 函数表达式卡片移动到参数输入下方
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.function_expression),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                // 使用 key 来强制重建LatexView，彻底清除其内部错误状态
                                key(functionLatexExpression) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LatexView(
                                            latex = functionLatexExpression,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.manual_curve_preview_title, selectedFunction?.displayName ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        // 使用新的CurveChart重载函数替换原有逻辑
                        if (allParamsEntered && selectedFunction != null) {
                            val curveFunction = viewModel.getCurveFunction()
                            CurveChart(
                                fittedCurve = curveFunction,
                                selectedFunction = selectedFunction,
                                parameters = parameters,
                                xAxisLabel = stringResource(R.string.concentration),
                                yAxisLabel = selectedPixelType?.displayName ?: "",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.manual_curve_prompt_enter_params),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showSaveDialog = true },
                            enabled = allParamsEntered && viewModel.getCurveFunction() != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.save_model))
                        }
                    }
                }
            }

            errorMessage?.let {
                AlertDialog(
                    onDismissRequest = { viewModel.clearErrorMessage() },
                    title = { Text("错误") },
                    text = { Text(it) },
                    confirmButton = { Button(onClick = { viewModel.clearErrorMessage() }) { Text("确定") } }
                )
            }

            if (showSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showSaveDialog = false },
                    title = { Text(stringResource(R.string.save_model)) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = modelName,
                                onValueChange = { modelName = it },
                                label = { Text(stringResource(R.string.model_name)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    // 添加小延迟，解决输入法连接问题
                                    kotlinx.coroutines.delay(50)
                                    
                                    viewModel.saveModel(modelName)
                                    showSaveDialog = false
                                    navigateBack()
                                }
                            },
                            enabled = modelName.isNotBlank()
                        ) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveDialog = false }) { 
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
        }
    }
}