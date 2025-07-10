package com.muc.fluocolorquant.ui.screens.settings

import android.util.Log
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.LaunchedEffect

private const val TAG = "DetectionSettingsScreen"

/**
 * 检测设置页面
 * 提供检测模式、像素提取方式、图像预处理和默认参数等设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val toastManager = LocalToastManager.current
    
    val defaultDetectionMode by viewModel.defaultDetectionMode.collectAsState()
    val defaultConcentrationUnit by viewModel.defaultConcentrationUnit.collectAsState()
    val concentrationUnits by viewModel.concentrationUnits.collectAsState()
    val newUnitInput by viewModel.newUnitInput.collectAsState()
    val defaultRows by viewModel.defaultRows.collectAsState()
    val defaultColumns by viewModel.defaultColumns.collectAsState()
    val pixelExtractionMethod by viewModel.pixelExtractionMethod.collectAsState()
    val imagePreprocessingEnabled by viewModel.imagePreprocessingEnabled.collectAsState()

    // 添加LaunchedEffect确保页面打开时刷新设置
    LaunchedEffect(Unit) {
        viewModel.refreshSettings()
    }

    // 将行列输入框的状态提升到这里，使保存按钮可以访问
    var rowsText by remember(defaultRows) { mutableStateOf(defaultRows.toString()) }
    var rowInputError by remember { mutableStateOf(false) }
    var columnsText by remember(defaultColumns) { mutableStateOf(defaultColumns.toString()) }
    var columnInputError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_detection_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 像素提取方式设置
            Text(
                text = stringResource(R.string.settings_pixel_extraction_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Text(
                text = stringResource(R.string.settings_pixel_extraction_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            PixelExtractionSelector(
                currentMethod = pixelExtractionMethod,
                onMethodSelected = { method ->
                    if (method != pixelExtractionMethod) {
                        viewModel.setPixelExtractionMethod(method)
                        toastManager.showToast(
                            message = context.getString(R.string.settings_update_success),
                            type = ToastType.SUCCESS
                        )
                    }
                },
                options = viewModel.pixelExtractionOptions
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            
            // 图像预处理设置
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_preprocessing_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_preprocessing_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                Switch(
                    checked = imagePreprocessingEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setImagePreprocessingEnabled(enabled)
                        toastManager.showToast(
                            message = context.getString(R.string.settings_update_success),
                            type = ToastType.SUCCESS
                        )
                    }
                )
            }
            
            Divider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // 默认检测模式选择
            Text(
                text = stringResource(R.string.default_detection_mode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            DetectionModeSelector(
                currentMode = defaultDetectionMode,
                onModeSelected = { mode ->
                    if (mode != defaultDetectionMode) {
                        viewModel.setDefaultDetectionModeWithoutRestart(mode)
                        toastManager.showToast(
                            message = context.getString(R.string.settings_update_success),
                            type = ToastType.SUCCESS
                        )
                    }
                },
                modeOptions = viewModel.detectionModeOptions
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            
            // 默认浓度单位设置
            Text(
                text = stringResource(R.string.default_concentration_unit),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            ConcentrationUnitSelector(
                currentUnit = defaultConcentrationUnit,
                onUnitSelected = { unit ->
                    if (unit != defaultConcentrationUnit) {
                        viewModel.setDefaultConcentrationUnitWithoutRestart(unit)
                        toastManager.showToast(
                            message = context.getString(R.string.settings_update_success),
                            type = ToastType.SUCCESS
                        )
                    }
                },
                availableUnits = concentrationUnits.toList()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 浓度单位管理
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newUnitInput,
                    onValueChange = { viewModel.updateNewUnitInput(it) },
                    label = { Text(stringResource(R.string.new_concentration_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        val success = viewModel.addConcentrationUnit(newUnitInput)
                        if (success) {
                            viewModel.updateNewUnitInput("")
                            toastManager.showToast(
                                message = context.getString(R.string.unit_added_success),
                                type = ToastType.SUCCESS
                            )
                        } else {
                            toastManager.showToast(
                                message = context.getString(R.string.unit_already_exists),
                                type = ToastType.ERROR
                            )
                        }
                    },
                    enabled = newUnitInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.add))
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 显示可删除的浓度单位
            Text(
                text = stringResource(R.string.available_concentration_units),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            ConcentrationUnitChips(
                units = concentrationUnits.toList(),
                defaultUnit = defaultConcentrationUnit,
                onDeleteUnit = { unit ->
                    val success = viewModel.deleteConcentrationUnit(unit)
                    if (success) {
                        toastManager.showToast(
                            message = context.getString(R.string.unit_deleted),
                            type = ToastType.SUCCESS
                        )
                    } else {
                        toastManager.showToast(
                            message = context.getString(R.string.cannot_delete_default_unit),
                            type = ToastType.WARNING
                        )
                    }
                }
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            
            // 默认孔板尺寸设置
            Text(
                text = stringResource(R.string.default_plate_size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 行数输入
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { value ->
                            // 允许空输入，但不自动填充默认值
                            if (value.isEmpty()) {
                                rowsText = value
                                rowInputError = false
                                // 不再调用 viewModel.setDefaultRows(12)
                            } else if (value.matches(Regex("^[0-9]+$"))) {
                                val numValue = value.toInt()
                                // 检查行*列是否小于等于96
                                val columns: Int = defaultColumns
                                if (numValue > 0 && numValue * columns <= 96) {
                                    rowsText = value
                                    viewModel.setDefaultRows(numValue)
                                    rowInputError = false
                                } else {
                                    rowInputError = true
                                    toastManager.showToast(
                                        message = context.getString(R.string.plate_size_limit_exceeded),
                                        type = ToastType.WARNING
                                    )
                                }
                            } else {
                                // 非数字输入，不更新值，显示错误
                                rowInputError = true
                                toastManager.showToast(
                                    message = context.getString(R.string.input_number_only),
                                    type = ToastType.ERROR
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.rows)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (rowInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = if (rowInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        shape = MaterialTheme.shapes.small,
                        isError = rowInputError
                    )
                }
                
                // 列数输入
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { value ->
                            // 允许空输入，但不自动填充默认值
                            if (value.isEmpty()) {
                                columnsText = value
                                columnInputError = false
                                // 不再调用 viewModel.setDefaultColumns(8)
                            } else if (value.matches(Regex("^[0-9]+$"))) {
                                val numValue = value.toInt()
                                // 检查行*列是否小于等于96
                                val rows: Int = defaultRows
                                if (numValue > 0 && rows * numValue <= 96) {
                                    columnsText = value
                                    viewModel.setDefaultColumns(numValue)
                                    columnInputError = false
                                } else {
                                    columnInputError = true
                                    toastManager.showToast(
                                        message = context.getString(R.string.plate_size_limit_exceeded),
                                        type = ToastType.WARNING
                                    )
                                }
                            } else {
                                // 非数字输入，不更新值，显示错误
                                columnInputError = true
                                toastManager.showToast(
                                    message = context.getString(R.string.input_number_only),
                                    type = ToastType.ERROR
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.columns)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (columnInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = if (columnInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        shape = MaterialTheme.shapes.small,
                        isError = columnInputError
                    )
                }
            }
            
            // 添加说明文字
            Text(
                text = stringResource(R.string.plate_size_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // 添加保存按钮
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    // 检查行列输入是否为空，如果为空则使用上次保存的合法值
                    if (rowsText.isEmpty() && columnsText.isEmpty()) {
                        // 两个都为空，不做任何操作，保持原来的值
                        toastManager.showToast(
                            message = context.getString(R.string.settings_update_success),
                            type = ToastType.SUCCESS
                        )
                    } else if (rowsText.isEmpty()) {
                        // 行为空，列不为空
                        if (columnsText.matches(Regex("^[0-9]+$"))) {
                            val numColumns = columnsText.toInt()
                            // 检查行*列是否小于等于96
                            val rows: Int = defaultRows
                            if (rows * numColumns <= 96) {
                                viewModel.setDefaultColumns(numColumns)
                                toastManager.showToast(
                                    message = context.getString(R.string.settings_update_success),
                                    type = ToastType.SUCCESS
                                )
                            } else {
                                toastManager.showToast(
                                    message = context.getString(R.string.plate_size_limit_exceeded),
                                    type = ToastType.WARNING
                                )
                            }
                        } else {
                            toastManager.showToast(
                                message = context.getString(R.string.input_number_only),
                                type = ToastType.ERROR
                            )
                        }
                    } else if (columnsText.isEmpty()) {
                        // 列为空，行不为空
                        if (rowsText.matches(Regex("^[0-9]+$"))) {
                            val numRows = rowsText.toInt()
                            // 检查行*列是否小于等于96
                            val columns: Int = defaultColumns
                            if (numRows * columns <= 96) {
                                viewModel.setDefaultRows(numRows)
                                toastManager.showToast(
                                    message = context.getString(R.string.settings_update_success),
                                    type = ToastType.SUCCESS
                                )
                            } else {
                                toastManager.showToast(
                                    message = context.getString(R.string.plate_size_limit_exceeded),
                                    type = ToastType.WARNING
                                )
                            }
                        } else {
                            toastManager.showToast(
                                message = context.getString(R.string.input_number_only),
                                type = ToastType.ERROR
                            )
                        }
                    } else {
                        // 两个都不为空
                        if (rowsText.matches(Regex("^[0-9]+$")) && columnsText.matches(Regex("^[0-9]+$"))) {
                            val numRows = rowsText.toInt()
                            val numColumns = columnsText.toInt()
                            // 检查行*列是否小于等于96
                            if (numRows * numColumns <= 96) {
                                viewModel.setDefaultRows(numRows)
                                viewModel.setDefaultColumns(numColumns)
                                toastManager.showToast(
                                    message = context.getString(R.string.settings_update_success),
                                    type = ToastType.SUCCESS
                                )
                            } else {
                                toastManager.showToast(
                                    message = context.getString(R.string.plate_size_limit_exceeded),
                                    type = ToastType.WARNING
                                )
                            }
                        } else {
                            toastManager.showToast(
                                message = context.getString(R.string.input_number_only),
                                type = ToastType.ERROR
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelExtractionSelector(
    currentMethod: String,
    onMethodSelected: (String) -> Unit,
    options: List<SettingsViewModel.PixelExtractionOption>
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOptionText = options.find { it.code == currentMethod }?.let {
        stringResource(id = R.string::class.java.getField(it.resourceId).getInt(null))
    } ?: options.first().let {
        stringResource(id = R.string::class.java.getField(it.resourceId).getInt(null))
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { expanded = true }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = selectedOptionText,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            options.forEach { option ->
                val optionText = stringResource(id = R.string::class.java.getField(option.resourceId).getInt(null))
                DropdownMenuItem(
                    text = { Text(text = optionText) },
                    onClick = {
                        onMethodSelected(option.code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DetectionModeSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    modeOptions: List<SettingsViewModel.DetectionModeOption>
) {
    var expanded by remember { mutableStateOf(false) }
    val currentModeName by remember(currentMode) {
        mutableStateOf(
            modeOptions.find { it.code == currentMode }?.name 
                ?: if (currentMode == "FLUORESCENCE") 
                    "Fluorescence Detection" 
                else 
                    "Colorimetric Detection"
        )
    }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { expanded = true }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = currentModeName,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            modeOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onModeSelected(option.code)
                        expanded = false
                    }
                )
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
    
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { expanded = true }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = currentUnit,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            availableUnits.sorted().forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit) },
                    onClick = {
                        onUnitSelected(unit)
                        expanded = false
                    }
                )
            }
        }
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
                text = "(${stringResource(R.string.default_unit)})",
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