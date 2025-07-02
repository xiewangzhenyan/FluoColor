package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.ui.viewmodels.ColumnType
import com.muc.fluocolorquant.ui.viewmodels.CreationFlowState
import com.muc.fluocolorquant.ui.viewmodels.CurveModelViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手动数据输入页面
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
    
    // 本地状态
    var modelName by remember { mutableStateOf("") }
    var pixelTypeExpanded by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var selectedPixelType by remember { mutableStateOf<PixelType?>(PixelType.GRAY_LUMINOSITY) }
    
    // 行列数输入状态 - 默认为4行2列
    var rowCountInput by remember { mutableStateOf("4") }
    var columnCountInput by remember { mutableStateOf("2") }
    
    // 添加协程作用域用于处理延迟操作
    val scope = rememberCoroutineScope()
    
    // 屏幕宽度信息，用于计算列宽
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val horizontalPadding = 16.dp * 2 // 屏幕左右两边的填充
    val rowNumberWidth = 60.dp // 行号列宽度
    val availableWidth = screenWidth - horizontalPadding - rowNumberWidth
    
    // 计算是否需要水平滚动
    val columnCount = columnCountInput.toIntOrNull() ?: 2
    val needsScroll = columnCount >= 4
    
    // 计算列宽度
    val columnWidth = if (needsScroll) {
        150.dp // 固定宽度，超过4列时使用
    } else {
        // 2-3列时等分可用宽度
        availableWidth / columnCount
    }
    
    // 从ViewModel的状态初始化本地状态
    LaunchedEffect(key1 = Unit) {
        if (creationFlowState == null) {
            viewModel.startManualDataInput()
            
            // 确保初始表格大小与默认行列数匹配
            val rowCount = rowCountInput.toIntOrNull() ?: 4
            val columnCount = columnCountInput.toIntOrNull() ?: 2
            
            // 对于初始状态，需要添加行
            for (i in 1 until rowCount) { // 默认已有1行，所以从1开始
                viewModel.addDataRow()
            }
        }
    }
    
    val manualDataState = (creationFlowState as? CreationFlowState.ManualDataInput)
    
    // 确保数据表格与用户输入行列数一致
    LaunchedEffect(key1 = rowCountInput, key2 = columnCountInput) {
        val rowCount = rowCountInput.toIntOrNull() ?: 4
        val columnCount = columnCountInput.toIntOrNull() ?: 2
        
        if (rowCount > 0 && columnCount > 0 && manualDataState != null) {
            // 调整数据表格大小
            val currentRowCount = manualDataState.dataTable.size
            val currentColumnCount = if (manualDataState.dataTable.isNotEmpty()) manualDataState.dataTable[0].size else 0
            
            // 先处理行数变化
            if (rowCount > currentRowCount) {
                // 增加行
                for (i in 1..(rowCount - currentRowCount)) {
                    viewModel.addDataRow()
                }
            } else if (rowCount < currentRowCount) {
                // 减少行
                for (i in 1..(currentRowCount - rowCount)) {
                    viewModel.removeDataRow()
                }
            }
            
            // 再处理列数变化
            if (columnCount > currentColumnCount) {
                // 增加列
                for (i in 1..(columnCount - currentColumnCount)) {
                    viewModel.addDataColumn()
                }
            } else if (columnCount < currentColumnCount) {
                // 减少列
                for (i in 1..(currentColumnCount - columnCount)) {
                    viewModel.removeDataColumn()
                }
            }
        }
    }
    
    val dataTable = manualDataState?.dataTable ?: listOf(listOf(0.0, 0.0))
    val columnTypes = manualDataState?.columnTypes ?: listOf(ColumnType.NONE, ColumnType.NONE)
    val fittingResult = manualDataState?.fittingResult
    
    // 检查是否有一个浓度列和至少一个像素类型列
    val hasConcAndPixel = remember(columnTypes) {
        derivedStateOf {
            columnTypes.contains(ColumnType.CONCENTRATION) && 
            columnTypes.contains(ColumnType.PIXEL_VALUE)
        }
    }
    
    // 添加水平滚动状态
    val horizontalScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.import_data)) },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 行列数输入区域
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 行数输入
                        OutlinedTextField(
                            value = rowCountInput,
                            onValueChange = { input ->
                                // 只接受正整数
                                if (input.isEmpty() || input.all { it.isDigit() }) {
                                    rowCountInput = input
                                }
                            },
                            label = { Text(stringResource(R.string.row_count)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // 列数输入
                        OutlinedTextField(
                            value = columnCountInput,
                            onValueChange = { input ->
                                // 只接受正整数
                                if (input.isEmpty() || input.all { it.isDigit() }) {
                                    columnCountInput = input
                                }
                            },
                            label = { Text(stringResource(R.string.column_count)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 数据输入表格 - 根据列数决定是否使用水平滚动
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column {
                            // 表头 - 列类型选择器
                            Row(
                                modifier = Modifier
                                    .let { if (needsScroll) it.horizontalScroll(horizontalScrollState) else it.fillMaxWidth() }
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                // 行号列
                                Box(
                                    modifier = Modifier
                                        .width(rowNumberWidth)
                                        .height(48.dp)
                                        .border(0.5.dp, MaterialTheme.colorScheme.outline),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "#",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                
                                // 数据列类型选择器
                                columnTypes.forEachIndexed { colIndex, columnType ->
                                    var expanded by remember { mutableStateOf(false) }
                                    
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        modifier = Modifier
                                            .width(columnWidth)
                                            .height(48.dp)
                                            .border(0.5.dp, MaterialTheme.colorScheme.outline)
                                    ) {
                                        TextField(
                                            value = when (columnType) {
                                                ColumnType.CONCENTRATION -> stringResource(R.string.concentration)
                                                ColumnType.PIXEL_VALUE -> selectedPixelType?.displayName ?: stringResource(R.string.pixel_type)
                                                else -> stringResource(R.string.column_type)
                                            },
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(),
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                            colors = ExposedDropdownMenuDefaults.textFieldColors(
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        )
                                        
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            // 检查是否已有浓度列，如果有且当前列不是浓度列，则不显示浓度选项
                                            val hasConcentration = columnTypes.contains(ColumnType.CONCENTRATION)
                                            val canSetConcentration = !hasConcentration || columnType == ColumnType.CONCENTRATION
                                            
                                            if (canSetConcentration) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.concentration)) },
                                                    onClick = {
                                                        viewModel.updateColumnType(colIndex, ColumnType.CONCENTRATION)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                            
                                            // 像素类型选项
                                            DropdownMenuItem(
                                                text = { 
                                                    if (selectedPixelType == null) {
                                                        Text("选择像素类型...")
                                                    } else {
                                                        Text(selectedPixelType!!.displayName)
                                                    }
                                                },
                                                onClick = {
                                                    // 先将列类型设置为像素值
                                                    viewModel.updateColumnType(colIndex, ColumnType.PIXEL_VALUE)
                                                    expanded = false
                                                    // 然后打开像素类型选择对话框
                                                    pixelTypeExpanded = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // 表格数据行 - 根据列数决定是否使用水平滚动
                            dataTable.forEachIndexed { rowIndex, rowData ->
                                Row(
                                    modifier = Modifier
                                        .let { if (needsScroll) it.horizontalScroll(horizontalScrollState) else it.fillMaxWidth() }
                                ) {
                                    // 行号
                                    Box(
                                        modifier = Modifier
                                            .width(rowNumberWidth)
                                            .height(48.dp)
                                            .border(0.5.dp, MaterialTheme.colorScheme.outline)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (rowIndex + 1).toString(),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    
                                    // 数据单元格
                                    rowData.forEachIndexed { colIndex, cellValue ->
                                        var cellValueText by remember(rowIndex, colIndex, cellValue) {
                                            mutableStateOf(
                                                if (cellValue == 0.0 && rowIndex > 0) "" 
                                                else if (cellValue == cellValue.toInt().toDouble()) cellValue.toInt().toString()
                                                else cellValue.toString()
                                            )
                                        }
                                        
                                        OutlinedTextField(
                                            value = cellValueText,
                                            onValueChange = { text ->
                                                // 只允许输入数字和小数点
                                                if (text.isEmpty() || text.matches(Regex("^-?\\d*\\.?\\d*$"))) {
                                                    cellValueText = text
                                                    val value = text.toDoubleOrNull() ?: 0.0
                                                    viewModel.updateDataTableCell(rowIndex, colIndex, value)
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier
                                                .width(columnWidth)
                                                .height(48.dp),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                textAlign = TextAlign.Center
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 开始拟合按钮
                    Button(
                        onClick = { viewModel.performFitFromData() },
                        enabled = hasConcAndPixel.value && dataTable.size > 1,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.start_fitting))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 显示拟合结果
                    fittingResult?.let { result ->
                        if (result.isSuccess) {
                            // 创建函数
                            val curveFunction = viewModel.getCurveFunction()

                            if (curveFunction != null) {
                                // 使用新的CurveChart重载函数替换原有的计算逻辑
                                CurveChart(
                                    fittedCurve = curveFunction,
                                    selectedFunction = result.function,
                                    parameters = result.params,
                                    xAxisLabel = stringResource(R.string.concentration),
                                    yAxisLabel = selectedPixelType?.displayName ?: "",
                                    title = "${result.function.displayName} 拟合曲线",
                                    modifier = Modifier.height(250.dp),
                                    dataPoints = result.dataPoints // 传递拟合结果中的数据点
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // 拟合函数显示
                                Text(
                                    text = "拟合函数: ${result.function.displayName}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // 参数显示
                                result.params.forEach { paramEntry ->
                                    Text(
                                        text = "${paramEntry.key} = ${String.format("%.6f", paramEntry.value)}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // 指标表格
                                val metricsMap = result.metrics.entries.associate { entry ->
                                    entry.key to String.format("%.2f", entry.value)
                                }
                                
                                MetricsTable(
                                    metrics = metricsMap,
                                    title = stringResource(R.string.fitting_quality)
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // 保存模型按钮
                                Button(
                                    onClick = { showSaveDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.save_model))
                                }
                            }
                        }
                    }
                }
            }
            
            // 错误消息对话框
            errorMessage?.let {
                AlertDialog(
                    onDismissRequest = { viewModel.clearErrorMessage() },
                    title = { Text("错误") },
                    text = { Text(it) },
                    confirmButton = {
                        Button(onClick = { viewModel.clearErrorMessage() }) {
                            Text("确定")
                        }
                    }
                )
            }
            
            // 像素类型选择对话框
            if (pixelTypeExpanded) {
                AlertDialog(
                    onDismissRequest = { pixelTypeExpanded = false },
                    title = { Text(stringResource(R.string.pixel_type)) },
                    text = {
                        // 使用垂直滚动使得所有像素类型都可见
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp)
                        ) {
                            PixelType.getByCategory().forEach { (category, pixelTypes) ->
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                
                                pixelTypes.forEach { pixelType ->
                                    TextButton(
                                        onClick = {
                                            selectedPixelType = pixelType
                                            pixelTypeExpanded = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(pixelType.displayName)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { pixelTypeExpanded = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            
            // 保存对话框
            if (showSaveDialog) {
                AlertDialog(
                    onDismissRequest = { 
                        showSaveDialog = false
                    },
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
                                    delay(50)
                                    
                                    viewModel.saveModel(modelName)
                                    showSaveDialog = false
                                    navigateBack()
                                }
                            },
                            enabled = modelName.isNotBlank()
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            showSaveDialog = false 
                        }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }
    }
} 