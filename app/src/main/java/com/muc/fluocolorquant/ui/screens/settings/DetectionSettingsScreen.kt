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
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 行数输入
                OutlinedTextField(
                    value = defaultRows.toString(),
                    onValueChange = { newValue ->
                        newValue.toIntOrNull()?.let { rows ->
                            if (rows > 0 && rows <= 16) { // 限制行数范围
                                viewModel.setDefaultRows(rows)
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.rows)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // 列数输入
                OutlinedTextField(
                    value = defaultColumns.toString(),
                    onValueChange = { newValue ->
                        newValue.toIntOrNull()?.let { columns ->
                            if (columns > 0 && columns <= 24) { // 限制列数范围
                                viewModel.setDefaultColumns(columns)
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.columns)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
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