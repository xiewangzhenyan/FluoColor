package com.muc.fluocolorquant.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.MainActivity
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
import com.muc.fluocolorquant.utils.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType

private const val TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val toastManager = LocalToastManager.current
    
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val defaultDetectionMode by viewModel.defaultDetectionMode.collectAsState()
    val defaultConcentrationUnit by viewModel.defaultConcentrationUnit.collectAsState()
    val concentrationUnits by viewModel.concentrationUnits.collectAsState()
    val newUnitInput by viewModel.newUnitInput.collectAsState()
    val defaultRows by viewModel.defaultRows.collectAsState()
    val defaultColumns by viewModel.defaultColumns.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // 语言设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.language_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    
                    LanguageSelector(
                        currentLanguage = currentLanguage,
                        onLanguageSelected = { languageCode ->
                            if (languageCode != currentLanguage) {
                                coroutineScope.launch {
                                    // 更新语言设置
                                    viewModel.setLanguage(languageCode)
                                    Log.d(TAG, "Language changed to: $languageCode")
                                    
                                    // 显示Toast提示
                                    toastManager.showToast(
                                        message = context.getString(R.string.language_changed),
                                        type = ToastType.SUCCESS
                                    )
                                }
                            }
                        },
                        languageOptions = viewModel.languageOptions
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 检测设置卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.detection_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    
                    // 默认检测模式选择
                    Text(
                        text = stringResource(R.string.default_detection_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    DetectionModeSelector(
                        currentMode = defaultDetectionMode,
                        onModeSelected = { mode ->
                            if (mode != defaultDetectionMode) {
                                // 使用不触发重启的方法更新设置
                                viewModel.setDefaultDetectionModeWithoutRestart(mode)
                                // 成功修改后提示
                                toastManager.showToast(
                                    message = context.getString(R.string.settings_update_success),
                                    type = ToastType.SUCCESS
                                )
                            }
                        },
                        modeOptions = viewModel.detectionModeOptions
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 项目设置卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.project_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )

                    // 默认行列设置
                    Text(
                        text = stringResource(R.string.default_row_column_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 行数设置
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.default_rows),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 行数选择器
                        var rowsText by remember(defaultRows) { mutableStateOf(defaultRows.toString()) }
                        var rowInputError by remember { mutableStateOf(false) }
                        
                        OutlinedTextField(
                            value = rowsText,
                            onValueChange = { value ->
                                // 仅接受数字输入
                                if (value.isEmpty()) {
                                    rowsText = value
                                    rowInputError = false
                                } else if (value.matches(Regex("^[0-9]+$"))) {
                                    val numValue = value.toInt()
                                    if (numValue in 1..8) {
                                        rowsText = value
                                        viewModel.setDefaultRows(numValue)
                                        rowInputError = false
                                        toastManager.showToast(
                                            message = context.getString(R.string.settings_update_success),
                                            type = ToastType.SUCCESS
                                        )
                                    } else {
                                        rowInputError = true
                                        toastManager.showToast(
                                            message = context.getString(R.string.row_limit_exceeded),
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.width(100.dp),
                            singleLine = true,
                            isError = rowInputError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (rowInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = if (rowInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                errorBorderColor = MaterialTheme.colorScheme.error,
                                errorTrailingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 列数设置
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.default_columns),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 列数选择器
                        var columnsText by remember(defaultColumns) { mutableStateOf(defaultColumns.toString()) }
                        var columnInputError by remember { mutableStateOf(false) }
                        
                        OutlinedTextField(
                            value = columnsText,
                            onValueChange = { value ->
                                // 仅接受数字输入
                                if (value.isEmpty()) {
                                    columnsText = value
                                    columnInputError = false
                                } else if (value.matches(Regex("^[0-9]+$"))) {
                                    val numValue = value.toInt()
                                    if (numValue in 1..12) {
                                        columnsText = value
                                        viewModel.setDefaultColumns(numValue)
                                        columnInputError = false
                                        toastManager.showToast(
                                            message = context.getString(R.string.settings_update_success),
                                            type = ToastType.SUCCESS
                                        )
                                    } else {
                                        columnInputError = true
                                        toastManager.showToast(
                                            message = context.getString(R.string.column_limit_exceeded),
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.width(100.dp),
                            singleLine = true,
                            isError = columnInputError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (columnInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = if (columnInputError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                errorBorderColor = MaterialTheme.colorScheme.error,
                                errorTrailingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // 浓度单位设置
                    Text(
                        text = stringResource(R.string.concentration_unit_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // 默认浓度单位选择
                    ConcentrationUnitSelector(
                        concentrationUnits = concentrationUnits.toList(),
                        currentUnit = defaultConcentrationUnit,
                        onUnitSelected = { unit ->
                            if (unit != defaultConcentrationUnit) {
                                // 使用不触发重启的方法更新设置
                                viewModel.setDefaultConcentrationUnitWithoutRestart(unit)
                                // 成功修改后提示
                                toastManager.showToast(
                                    message = context.getString(R.string.settings_update_success),
                                    type = ToastType.SUCCESS
                                )
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 添加自定义浓度单位
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newUnitInput,
                            onValueChange = { viewModel.updateNewUnitInput(it) },
                            label = { Text(stringResource(R.string.enter_new_unit)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = {
                                if (newUnitInput.isNotBlank()) {
                                    if (concentrationUnits.contains(newUnitInput)) {
                                        toastManager.showToast(
                                            message = context.getString(R.string.unit_already_exists),
                                            type = ToastType.WARNING
                                        )
                                    } else {
                                        viewModel.addConcentrationUnit(newUnitInput)
                                        viewModel.updateNewUnitInput("")
                                        toastManager.showToast(
                                            message = context.getString(R.string.unit_added),
                                            type = ToastType.SUCCESS
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.add))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 显示当前所有单位，并允许删除（除了默认单位）
                    Text(
                        text = stringResource(R.string.concentration_unit),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    var expandedAllUnits by remember { mutableStateOf(false) }
                    val sortedUnits = remember(concentrationUnits, defaultConcentrationUnit) {
                        val all = concentrationUnits.toList().sorted()
                        // 确保默认单位在第一位
                        if (all.contains(defaultConcentrationUnit)) {
                            listOf(defaultConcentrationUnit) + all.filter { it != defaultConcentrationUnit }
                        } else {
                            all
                        }
                    }
                    
                    // 获取要显示的单位列表
                    val displayUnits = if (expandedAllUnits) {
                        sortedUnits
                    } else {
                        // 默认只显示前4个单位（或少于4个的所有单位）
                        sortedUnits.take(minOf(4, sortedUnits.size))
                    }
                    
                    // 显示单位列表
                    Column {
                        displayUnits.forEach { unit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = unit,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    
                                    if (unit == defaultConcentrationUnit) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "(${stringResource(R.string.default_concentration_unit)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                // 删除按钮（默认单位不能删除）
                                if (unit != defaultConcentrationUnit) {
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteConcentrationUnit(unit)
                                            toastManager.showToast(
                                                message = context.getString(R.string.unit_deleted),
                                                type = ToastType.SUCCESS
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete_concentration_unit),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        
                        // 如果有更多单位，添加展开/折叠按钮
                        if (sortedUnits.size > 4) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedAllUnits = !expandedAllUnits }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (expandedAllUnits) stringResource(id = R.string.collapse) else stringResource(id = R.string.expand_all),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                Icon(
                                    imageVector = if (expandedAllUnits) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expandedAllUnits) stringResource(id = R.string.collapse) else stringResource(id = R.string.expand_all),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    languageOptions: List<SettingsViewModel.LanguageOption>
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLanguageName = remember(currentLanguage, languageOptions) {
        languageOptions.find { it.code == currentLanguage }?.name 
            ?: if (currentLanguage == "zh") "中文" else "English"
    }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.select_language),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = { expanded = true }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        
                        Text(
                            text = currentLanguageName,
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
                languageOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            onLanguageSelected(option.code)
                            expanded = false
                        }
                    )
                }
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
    // 使用key参数确保当currentMode变化时重新计算
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
                .height(56.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { expanded = true }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
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
                    
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    
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
    concentrationUnits: List<String>,
    currentUnit: String,
    onUnitSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { expanded = true }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
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
                    
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    
                    // 直接使用currentUnit，确保UI随值变化更新
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
            concentrationUnits.sorted().forEach { unit ->
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