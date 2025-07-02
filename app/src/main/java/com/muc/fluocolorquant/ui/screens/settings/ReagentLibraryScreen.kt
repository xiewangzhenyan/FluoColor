package com.muc.fluocolorquant.ui.screens.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ReagentViewModel
import kotlinx.coroutines.launch

/**
 * 试剂库管理界面
 * 显示按分析物分组的试剂列表，支持添加和删除操作
 */
@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReagentLibraryScreen(
    viewModel: ReagentViewModel = hiltViewModel(),
    navigateBack: () -> Unit
) {
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()
    
    // 状态收集
    val reagentsGroupedByAnalyte by viewModel.reagentsGroupedByAnalyte.collectAsState()
    val analytes by viewModel.allAnalytes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val concentrationUnits by viewModel.concentrationUnits.collectAsState()
    
    // 预先加载字符串资源，避免在非Composable上下文中调用
    val reagentAddedSuccessMsg = stringResource(R.string.reagent_added_success)
    val reagentDeletedSuccessMsg = stringResource(R.string.reagent_deleted_success)
    val reagentUpdatedSuccessMsg = stringResource(R.string.reagent_updated_success)
    
    // 确保至少有一个默认的浓度单位可用
    val unitsList = remember(concentrationUnits) {
        if (concentrationUnits.isEmpty()) {
            listOf("ng/ml", "μg/ml", "mg/ml")
        } else {
            concentrationUnits.toList()
        }
    }
    
    // 计算试剂总数
    val totalReagentsCount = reagentsGroupedByAnalyte.values.sumOf { it.size }
    
    // 对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var reagentToDelete by remember { mutableStateOf<Reagent?>(null) }
    var reagentToEdit by remember { mutableStateOf<Reagent?>(null) }
    
    // 错误消息处理
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearError()
        }
    }
    
    // Scaffold布局
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reagent_library_title)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                // 加载中显示进度条
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (reagentsGroupedByAnalyte.isEmpty()) {
                // 无试剂数据显示提示信息
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_reagents_found),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_your_first_reagent),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 显示试剂列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // 添加固定头部，显示试剂总数
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
                                    // 使用字符串资源传递总数
                                    text = stringResource(R.string.history_total_records, totalReagentsCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    
                    // 按分析物分组显示试剂
                    reagentsGroupedByAnalyte.forEach { (analyte, reagents) ->
                        item {
                            // 分析物标题
                            AnalyteHeader(analyte = analyte)
                        }
                        
                        items(reagents, key = { it.id }) { reagent ->
                            ReagentItem(
                                reagent = reagent,
                                analyteName = analyte.name,
                                onDelete = { reagentToDelete = reagent },
                                onEdit = { reagentToEdit = reagent }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                    
                    // 底部间距
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
    
    // 添加试剂对话框
    if (showAddDialog) {
        AddReagentDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { analyteId, name, type, manufacturer, molecularWeight, unit ->
                scope.launch {
                    viewModel.addReagent(
                        analyteId = analyteId,
                        reagentName = name,
                        reagentType = type,
                        manufacturer = manufacturer,
                        molecularWeightStr = molecularWeight,
                        unit = unit
                    )
                    showAddDialog = false
                    // 添加成功提示
                    toastManager.showToast(
                        reagentAddedSuccessMsg,
                        ToastType.SUCCESS
                    )
                }
            },
            analytes = analytes,
            concentrationUnits = unitsList
        )
    }
    
    // 删除试剂对话框
    reagentToDelete?.let { reagent ->
        DeleteReagentDialog(
            reagentName = reagent.reagentName,
            onDismiss = { reagentToDelete = null },
            onConfirm = {
                scope.launch {
                    viewModel.deleteReagent(reagent)
                    reagentToDelete = null
                    // 删除成功提示
                    toastManager.showToast(
                        reagentDeletedSuccessMsg,
                        ToastType.SUCCESS
                    )
                }
            }
        )
    }
    
    // 编辑试剂对话框
    reagentToEdit?.let { reagent ->
        EditReagentDialog(
            reagent = reagent,
            onDismiss = { reagentToEdit = null },
            onConfirm = { id, analyteId, name, type, manufacturer, molecularWeight, unit ->
                scope.launch {
                    viewModel.updateReagent(
                        id = id,
                        analyteId = analyteId,
                        reagentName = name,
                        reagentType = type,
                        manufacturer = manufacturer,
                        molecularWeightStr = molecularWeight,
                        unit = unit
                    )
                    reagentToEdit = null
                    // 更新成功提示
                    toastManager.showToast(
                        reagentUpdatedSuccessMsg,
                        ToastType.SUCCESS
                    )
                }
            },
            analytes = analytes,
            concentrationUnits = unitsList
        )
    }
}

/**
 * 分析物分组标题
 */
@Composable
fun AnalyteHeader(analyte: Analyte) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = analyte.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
    }
}

/**
 * 试剂列表项
 */
@Composable
fun ReagentItem(
    reagent: Reagent,
    analyteName: String,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onEdit),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reagent.reagentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "${stringResource(R.string.analyte)}: $analyteName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = when (reagent.reagentType) {
                            "antigen" -> stringResource(R.string.antigen)
                            "antibody" -> stringResource(R.string.antibody)
                            else -> reagent.reagentType
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                // 删除按钮
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // 额外信息
            reagent.manufacturer?.let {
                if (it.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.manufacturer)}: $it",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                reagent.molecularWeight?.let {
                    Text(
                        text = "${stringResource(R.string.molecular_weight)}: $it kDa",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                reagent.unit?.let {
                    if (it.isNotBlank()) {
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "${stringResource(R.string.concentration_unit)}: $it",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * 添加试剂对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReagentDialog(
    onDismiss: () -> Unit,
    onConfirm: (analyteId: String, name: String, type: String, manufacturer: String?, molecularWeight: String?, unit: String?) -> Unit,
    analytes: List<Analyte>,
    concentrationUnits: List<String>
) {
    var reagentName by remember { mutableStateOf("") }
    var selectedAnalyteId by remember { mutableStateOf("") }
    var selectedAnalyteName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    var molecularWeight by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf("") }
    
    // 下拉菜单状态
    var analyteExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var unitExpanded by remember { mutableStateOf(false) }
    
    // 验证状态
    var nameError by remember { mutableStateOf(false) }
    var analyteError by remember { mutableStateOf(false) }
    var typeError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_reagent)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // 试剂名称
                OutlinedTextField(
                    value = reagentName,
                    onValueChange = { 
                        reagentName = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.reagent_name)) },
                    placeholder = { Text(stringResource(R.string.enter_reagent_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.reagent_name_empty))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 分析物选择
                ExposedDropdownMenuBox(
                    expanded = analyteExpanded,
                    onExpandedChange = { analyteExpanded = !analyteExpanded }
                ) {
                    TextField(
                        value = selectedAnalyteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.select_analyte)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = analyteExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        isError = analyteError,
                        supportingText = {
                            if (analyteError) {
                                Text(stringResource(R.string.analyte_not_selected))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = analyteExpanded,
                        onDismissRequest = { analyteExpanded = false }
                    ) {
                        analytes.forEach { analyte ->
                            DropdownMenuItem(
                                text = { Text(analyte.name) },
                                onClick = {
                                    selectedAnalyteId = analyte.id
                                    selectedAnalyteName = analyte.name
                                    analyteError = false
                                    analyteExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 试剂类型选择
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    TextField(
                        value = when (selectedType) {
                            "antigen" -> stringResource(R.string.antigen)
                            "antibody" -> stringResource(R.string.antibody)
                            else -> ""
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.reagent_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        isError = typeError,
                        supportingText = {
                            if (typeError) {
                                Text(stringResource(R.string.reagent_type_not_selected))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.antigen)) },
                            onClick = {
                                selectedType = "antigen"
                                typeError = false
                                typeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.antibody)) },
                            onClick = {
                                selectedType = "antibody"
                                typeError = false
                                typeExpanded = false
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 制造商
                OutlinedTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = { Text(stringResource(R.string.manufacturer)) },
                    placeholder = { Text(stringResource(R.string.enter_manufacturer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 分子量
                OutlinedTextField(
                    value = molecularWeight,
                    onValueChange = { newValue -> 
                        // 只允许输入数字和小数点
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            molecularWeight = newValue 
                        }
                    },
                    label = { Text(stringResource(R.string.molecular_weight)) },
                    placeholder = { Text(stringResource(R.string.enter_molecular_weight)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 浓度单位选择
                if (concentrationUnits.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = !unitExpanded }
                    ) {
                        TextField(
                            value = selectedUnit,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.concentration_unit)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = unitExpanded,
                            onDismissRequest = { unitExpanded = false }
                        ) {
                            concentrationUnits.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit) },
                                    onClick = {
                                        selectedUnit = unit
                                        unitExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // 如果没有可用的浓度单位，显示文本输入框
                    OutlinedTextField(
                        value = selectedUnit,
                        onValueChange = { selectedUnit = it },
                        label = { Text(stringResource(R.string.concentration_unit)) },
                        placeholder = { Text(stringResource(R.string.enter_new_unit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 验证必填字段
                    nameError = reagentName.isBlank()
                    analyteError = selectedAnalyteId.isBlank()
                    typeError = selectedType.isBlank()
                    
                    if (!nameError && !analyteError && !typeError) {
                        onConfirm(
                            selectedAnalyteId,
                            reagentName,
                            selectedType,
                            manufacturer.takeIf { it.isNotBlank() },
                            molecularWeight.takeIf { it.isNotBlank() },
                            selectedUnit.takeIf { it.isNotBlank() }
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.confirm))
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
 * 编辑试剂对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditReagentDialog(
    reagent: Reagent,
    onDismiss: () -> Unit,
    onConfirm: (id: String, analyteId: String, name: String, type: String, manufacturer: String?, molecularWeight: String?, unit: String?) -> Unit,
    analytes: List<Analyte>,
    concentrationUnits: List<String>
) {
    // 初始化状态
    var reagentName by remember { mutableStateOf(reagent.reagentName) }
    var selectedAnalyteId by remember { mutableStateOf(reagent.analyteId) }
    var selectedAnalyteName by remember { 
        mutableStateOf(analytes.find { it.id == reagent.analyteId }?.name ?: "") 
    }
    var selectedType by remember { mutableStateOf(reagent.reagentType) }
    var manufacturer by remember { mutableStateOf(reagent.manufacturer ?: "") }
    var molecularWeight by remember { 
        mutableStateOf(reagent.molecularWeight?.toString() ?: "") 
    }
    var selectedUnit by remember { mutableStateOf(reagent.unit ?: "") }
    
    // 下拉菜单状态
    var analyteExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var unitExpanded by remember { mutableStateOf(false) }
    
    // 验证状态
    var nameError by remember { mutableStateOf(false) }
    var analyteError by remember { mutableStateOf(false) }
    var typeError by remember { mutableStateOf(false) }
    
    // 检测是否有变化
    val hasChanges = (reagentName != reagent.reagentName && reagentName.isNotBlank()) ||
                     (selectedAnalyteId != reagent.analyteId) ||
                     (selectedType != reagent.reagentType) ||
                     (manufacturer != (reagent.manufacturer ?: "")) ||
                     (molecularWeight != (reagent.molecularWeight?.toString() ?: "")) ||
                     (selectedUnit != (reagent.unit ?: ""))
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_reagent)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // 试剂名称
                OutlinedTextField(
                    value = reagentName,
                    onValueChange = { 
                        reagentName = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.reagent_name)) },
                    placeholder = { Text(stringResource(R.string.enter_reagent_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.reagent_name_empty))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 分析物选择
                ExposedDropdownMenuBox(
                    expanded = analyteExpanded,
                    onExpandedChange = { analyteExpanded = !analyteExpanded }
                ) {
                    TextField(
                        value = selectedAnalyteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.select_analyte)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = analyteExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        isError = analyteError,
                        supportingText = {
                            if (analyteError) {
                                Text(stringResource(R.string.analyte_not_selected))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = analyteExpanded,
                        onDismissRequest = { analyteExpanded = false }
                    ) {
                        analytes.forEach { analyte ->
                            DropdownMenuItem(
                                text = { Text(analyte.name) },
                                onClick = {
                                    selectedAnalyteId = analyte.id
                                    selectedAnalyteName = analyte.name
                                    analyteError = false
                                    analyteExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 试剂类型选择
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded }
                ) {
                    TextField(
                        value = when (selectedType) {
                            "antigen" -> stringResource(R.string.antigen)
                            "antibody" -> stringResource(R.string.antibody)
                            else -> ""
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.reagent_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        isError = typeError,
                        supportingText = {
                            if (typeError) {
                                Text(stringResource(R.string.reagent_type_not_selected))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.antigen)) },
                            onClick = {
                                selectedType = "antigen"
                                typeError = false
                                typeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.antibody)) },
                            onClick = {
                                selectedType = "antibody"
                                typeError = false
                                typeExpanded = false
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 制造商
                OutlinedTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = { Text(stringResource(R.string.manufacturer)) },
                    placeholder = { Text(stringResource(R.string.enter_manufacturer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 分子量
                OutlinedTextField(
                    value = molecularWeight,
                    onValueChange = { newValue -> 
                        // 只允许输入数字和小数点
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            molecularWeight = newValue 
                        }
                    },
                    label = { Text(stringResource(R.string.molecular_weight)) },
                    placeholder = { Text(stringResource(R.string.enter_molecular_weight)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 浓度单位选择
                if (concentrationUnits.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = unitExpanded,
                        onExpandedChange = { unitExpanded = !unitExpanded }
                    ) {
                        TextField(
                            value = selectedUnit,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.concentration_unit)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = unitExpanded,
                            onDismissRequest = { unitExpanded = false }
                        ) {
                            concentrationUnits.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit) },
                                    onClick = {
                                        selectedUnit = unit
                                        unitExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // 如果没有可用的浓度单位，显示文本输入框
                    OutlinedTextField(
                        value = selectedUnit,
                        onValueChange = { selectedUnit = it },
                        label = { Text(stringResource(R.string.concentration_unit)) },
                        placeholder = { Text(stringResource(R.string.enter_new_unit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 验证必填字段
                    nameError = reagentName.isBlank()
                    analyteError = selectedAnalyteId.isBlank()
                    typeError = selectedType.isBlank()
                    
                    if (!nameError && !analyteError && !typeError) {
                        onConfirm(
                            reagent.id,
                            selectedAnalyteId,
                            reagentName,
                            selectedType,
                            manufacturer.takeIf { it.isNotBlank() },
                            molecularWeight.takeIf { it.isNotBlank() },
                            selectedUnit.takeIf { it.isNotBlank() }
                        )
                    }
                },
                enabled = hasChanges && reagentName.isNotBlank() && selectedAnalyteId.isNotBlank() && selectedType.isNotBlank()
            ) {
                Text(stringResource(R.string.update))
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
 * 删除试剂确认对话框
 */
@Composable
fun DeleteReagentDialog(
    reagentName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_reagent_title)) },
        text = { 
            Text(
                stringResource(
                    R.string.confirm_delete_reagent_with_name,
                    reagentName
                )
            ) 
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
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