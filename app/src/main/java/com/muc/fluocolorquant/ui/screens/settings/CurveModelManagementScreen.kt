package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.CurveModelViewModel
import com.muc.fluocolorquant.utils.math.FittingEngine
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

/**
 * 曲线模型管理页面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CurveModelManagementScreen(
    navController: NavController,
    viewModel: CurveModelViewModel = hiltViewModel()
) {
    val models by viewModel.models.collectAsState()
    val expandedModelId by viewModel.expandedModelId.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    var showAddModelDialog by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<CurveModel?>(null) }
    var modelToEdit by remember { mutableStateOf<CurveModel?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.curve_model_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddModelDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_new_model)
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (models.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = stringResource(R.string.click_add_button))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    // 添加固定头部，显示模型总数
                    stickyHeader {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            tonalElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.history_total_records, models.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    items(models) { model ->
                        ExpandableCurveModelItem(
                            model = model,
                            isExpanded = model.id == expandedModelId,
                            onExpandClick = { viewModel.toggleExpandModel(model.id) },
                            onDeleteClick = { modelToDelete = model },
                            onEditClick = { modelToEdit = model },
                            onExportClick = { /* 处理导出 */ }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            
            // 显示错误消息
            errorMessage?.let {
                AlertDialog(
                    onDismissRequest = { viewModel.clearErrorMessage() },
                    title = { Text(stringResource(R.string.error)) },
                    text = { Text(it) },
                    confirmButton = {
                        Button(onClick = { viewModel.clearErrorMessage() }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
            
            // 删除确认对话框
            modelToDelete?.let { model ->
                AlertDialog(
                    onDismissRequest = { modelToDelete = null },
                    title = { Text(stringResource(R.string.confirm_delete)) },
                    text = { Text(stringResource(R.string.confirm_delete_model)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteModel(model.id)
                                modelToDelete = null
                            }
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { modelToDelete = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            
            // 编辑模型对话框
            modelToEdit?.let { model ->
                var editedName by remember { mutableStateOf(model.name) }
                var pixelTypeExpanded by remember { mutableStateOf(false) }
                var selectedPixelType by remember { mutableStateOf(model.pixelType) }
                
                // 判断是否有修改
                val hasChanges by remember(editedName, selectedPixelType) {
                    derivedStateOf {
                        editedName != model.name ||
                        selectedPixelType != model.pixelType
                    }
                }
                
                AlertDialog(
                    onDismissRequest = { modelToEdit = null },
                    title = { Text(stringResource(R.string.edit_model_title)) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 名称输入框
                            OutlinedTextField(
                                value = editedName,
                                onValueChange = { editedName = it },
                                label = { Text(stringResource(R.string.model_name_label)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // 像素类型下拉选择框
                            Text(
                                text = stringResource(R.string.select_pixel_type_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            
                            ExposedDropdownMenuBox(
                                expanded = pixelTypeExpanded,
                                onExpandedChange = { pixelTypeExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedPixelType.displayName,
                                    onValueChange = { },
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pixelTypeExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )
                                
                                ExposedDropdownMenu(
                                    expanded = pixelTypeExpanded,
                                    onDismissRequest = { pixelTypeExpanded = false }
                                ) {
                                    PixelType.values().forEach { pixelType ->
                                        DropdownMenuItem(
                                            text = { Text(pixelType.displayName) },
                                            onClick = {
                                                selectedPixelType = pixelType
                                                pixelTypeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.updateModel(
                                    model.copy(
                                        name = editedName,
                                        pixelType = selectedPixelType
                                    )
                                )
                                modelToEdit = null
                            },
                            enabled = editedName.isNotBlank() && hasChanges
                        ) {
                            Text(stringResource(R.string.save_changes))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { modelToEdit = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            
            // 添加模型的底部弹窗
            if (showAddModelDialog) {
                val sheetState = rememberModalBottomSheetState()
                ModalBottomSheet(
                    onDismissRequest = { showAddModelDialog = false },
                    sheetState = sheetState
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.add_new_model),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Button(
                            onClick = {
                                showAddModelDialog = false
                                navController.navigate(Screen.ManualCurveInput.route)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.import_curve))
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = {
                                showAddModelDialog = false
                                navController.navigate(Screen.ManualDataInput.route)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.import_data))
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * 可展开的曲线模型列表项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandableCurveModelItem(
    model: CurveModel,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotationState by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "rotation"
    )
    
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onExpandClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 摘要视图（始终显示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${stringResource(R.string.function_type)}: ${model.function.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${stringResource(R.string.pixel_type)}: ${model.pixelType.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    // 如果有R²，显示它
                    model.metrics?.get("R²")?.let {
                        Text(
                            text = "R² = ${String.format("%.4f", it)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                IconButton(onClick = onExpandClick) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.back) else stringResource(R.string.cancel),
                        modifier = Modifier.rotate(rotationState)
                    )
                }
            }
            
            // 详细视图（展开时显示）
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 创建函数
                    val curveFunction: (Double) -> Double = { x ->
                        FittingEngine.calculate(model.function, model.parameters, x)
                    }
                    
                    // 使用新的CurveChart重载函数
                    CurveChart(
                        fittedCurve = curveFunction,
                        selectedFunction = model.function,
                        parameters = model.parameters,
                        xAxisLabel = stringResource(R.string.concentration),
                        yAxisLabel = model.pixelType.displayName,
                        title = model.function.displayName,
                        modifier = Modifier.height(250.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 函数表达式卡片 - 更紧凑的设计
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            // 标题靠左对齐
                            Text(
                                text = stringResource(R.string.function_expression),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            
                            // 使用FittingEngine.formatParametersToLatex()生成LaTeX表达式
                            val latexExpression = FittingEngine.formatParametersToLatex(model.function, model.parameters)
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LatexView(
                                    latex = latexExpression,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                    
                    // 参数表
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.curve_parameters),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Divider()
                            
                            model.parameters.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = key,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        // 使用 "%.4g" 格式化，能智能显示科学计数法或小数
                                        text = String.format(Locale.US, "%.4g", value)
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 评估指标，如果有的话
                    model.metrics?.let {
                        if (it.isNotEmpty()) {
                            MetricsTable(
                                // 使用 "%.4g" 格式化
                                metrics = it.mapValues { entry -> String.format(Locale.US, "%.4g", entry.value) },
                                title = stringResource(R.string.fitting_quality)
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    
                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onExportClick) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.export)
                            )
                        }
                        
                        IconButton(onClick = onEditClick) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit_model)
                            )
                        }
                        
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
} 