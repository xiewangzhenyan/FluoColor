package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.ui.components.InteractivePlateGrid
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.RoleSelector
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateViewModel

/**
 * 实验模板创建/编辑页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExperimentTemplateScreen(
    navController: NavController,
    templateId: String? = null,
    viewModel: ExperimentTemplateViewModel = hiltViewModel(),
    navigateBack: () -> Unit = { navController.navigateUp() }
) {
    val toastManager = LocalToastManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    // 状态收集
    val templateState by viewModel.creationState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val availableAnalytes by viewModel.allAnalytes.collectAsState()
    val availableAntigens by viewModel.availableReagents.collectAsState()
    val availableAntibodies by viewModel.availableReagents.collectAsState()
    val availableCurveModels by viewModel.availableCurveModels.collectAsState()
    val concentrationUnits by viewModel.concentrationUnits.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState() // 收集保存成功状态

    // 下拉菜单状态
    var showAnalyteDropdown by remember { mutableStateOf(false) }
    var showAntigenDropdown by remember { mutableStateOf(false) }
    var showAntibodyDropdown by remember { mutableStateOf(false) }
    var showCurveModelDropdown by remember { mutableStateOf(false) }
    var showUnitDropdown by remember { mutableStateOf(false) }

    // 预加载字符串资源
    val saveSuccessMessage = stringResource(R.string.template_save_success)
    val loadingTemplateMessage = stringResource(R.string.loading_template)
    val editingTemplateTitle = stringResource(R.string.edit_template)
    val createTemplateTitle = stringResource(R.string.create_template)

    // 监听保存成功状态，执行导航
    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            toastManager.showToast(saveSuccessMessage, ToastType.SUCCESS)
            // 保存成功后返回上一页面
            navigateBack()
            // 重置状态，防止重复导航
            viewModel.resetSaveSuccess()
        }
    }

    // 加载模板数据
    LaunchedEffect(templateId) {
        if (templateId != null) {
            viewModel.loadTemplate(templateId)
        } else {
            viewModel.resetTemplateState()
        }
    }

    // 调试输出当前模板状态
    LaunchedEffect(templateState) {
        if (templateState.selectedAnalyte != null) {
            println("模板状态: 分析物=${templateState.selectedAnalyte?.name}, " +
                    "抗原=${templateState.selectedAntigen?.reagentName}, " +
                    "抗体=${templateState.selectedAntibody?.reagentName}, " +
                    "曲线模型=${templateState.selectedCurveModel?.name}")
        }
    }

    // 监听availableReagents变化，确保在编辑模式下能加载所有试剂
    LaunchedEffect(availableAntigens, availableAntibodies) {
        if (templateId != null && availableAntigens.isNotEmpty() &&
            (templateState.selectedAntigen == null || templateState.selectedAntibody == null)) {
            // 如果是编辑模式但还没有加载抗原/抗体数据，尝试从试剂列表中查找并设置
            val antigenId = viewModel.getSelectedAntigenId()
            val antibodyId = viewModel.getSelectedAntibodyId()

            if (antigenId != null) {
                availableAntigens.find { reagent -> reagent.id == antigenId }?.let { reagent ->
                    viewModel.selectAntigen(reagent)
                }
            }

            if (antibodyId != null) {
                availableAntibodies.find { reagent -> reagent.id == antibodyId }?.let { reagent ->
                    viewModel.selectAntibody(reagent)
                }
            }
        }
    }

    // 监听availableCurveModels变化，确保在编辑模式下能加载曲线模型
    LaunchedEffect(availableCurveModels) {
        if (templateId != null && availableCurveModels.isNotEmpty() && templateState.selectedCurveModel == null) {
            // 如果是编辑模式但还没有加载曲线模型，尝试从曲线模型列表中查找并设置
            val curveModelId = viewModel.getSelectedCurveModelId()

            if (curveModelId != null) {
                availableCurveModels.find { model -> model.id == curveModelId }?.let { model ->
                    viewModel.selectCurveModel(model)
                }
            }
        }
    }

    // 错误处理
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = if (templateId != null) editingTemplateTitle else createTemplateTitle,
                onBack = { navigateBack() }
            )
        },
        floatingActionButton = {
            if (!isLoading) {
                FloatingActionButton(
                    onClick = {
                        // 直接调用保存函数，不再需要回调
                        viewModel.saveTemplate()
                    }
                ) {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = loadingTemplateMessage)
                }
            } else {
                // 表单内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 基本信息卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.basic_info),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // 模板名称
                            OutlinedTextField(
                                value = templateState.templateName,
                                onValueChange = { viewModel.updateTemplateName(it) },
                                label = { Text(stringResource(R.string.template_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            // 分析物选择
                            ExposedDropdownMenuBox(
                                expanded = showAnalyteDropdown,
                                onExpandedChange = { showAnalyteDropdown = !showAnalyteDropdown }
                            ) {
                                TextField(
                                    value = templateState.selectedAnalyte?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.analyte)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showAnalyteDropdown)
                                    },
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showAnalyteDropdown,
                                    onDismissRequest = { showAnalyteDropdown = false }
                                ) {
                                    availableAnalytes.forEach { analyte: Analyte ->
                                        DropdownMenuItem(
                                            text = { Text(analyte.name) },
                                            onClick = {
                                                viewModel.onAnalyteSelectedInCreation(analyte)
                                                showAnalyteDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 试剂卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.reagents),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // 抗原选择
                            ExposedDropdownMenuBox(
                                expanded = showAntigenDropdown,
                                onExpandedChange = { showAntigenDropdown = !showAntigenDropdown }
                            ) {
                                TextField(
                                    value = templateState.selectedAntigen?.reagentName ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.antigen)) },
                                    trailingIcon = {
                                        Row {
                                            if (templateState.selectedAntigen != null) {
                                                IconButton(onClick = { viewModel.selectAntigen(null) }) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.clear)
                                                    )
                                                }
                                            }
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showAntigenDropdown)
                                        }
                                    },
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showAntigenDropdown,
                                    onDismissRequest = { showAntigenDropdown = false }
                                ) {
                                    // 过滤出抗原类型的试剂
                                    val antigens = availableAntigens.filter { it.reagentType == "antigen" }
                                    antigens.forEach { reagent: Reagent ->
                                        DropdownMenuItem(
                                            text = { Text(reagent.reagentName) },
                                            onClick = {
                                                viewModel.selectAntigen(reagent)
                                                showAntigenDropdown = false
                                            }
                                        )
                                    }
                                }
                            }

                            // 抗体选择
                            ExposedDropdownMenuBox(
                                expanded = showAntibodyDropdown,
                                onExpandedChange = { showAntibodyDropdown = !showAntibodyDropdown }
                            ) {
                                TextField(
                                    value = templateState.selectedAntibody?.reagentName ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.antibody)) },
                                    trailingIcon = {
                                        Row {
                                            if (templateState.selectedAntibody != null) {
                                                IconButton(onClick = { viewModel.selectAntibody(null) }) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.clear)
                                                    )
                                                }
                                            }
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showAntibodyDropdown)
                                        }
                                    },
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showAntibodyDropdown,
                                    onDismissRequest = { showAntibodyDropdown = false }
                                ) {
                                    // 过滤出抗体类型的试剂
                                    val antibodies = availableAntibodies.filter { it.reagentType == "antibody" }
                                    antibodies.forEach { reagent: Reagent ->
                                        DropdownMenuItem(
                                            text = { Text(reagent.reagentName) },
                                            onClick = {
                                                viewModel.selectAntibody(reagent)
                                                showAntibodyDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 曲线模型卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.curve_model),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // 曲线模型选择
                            ExposedDropdownMenuBox(
                                expanded = showCurveModelDropdown,
                                onExpandedChange = { showCurveModelDropdown = !showCurveModelDropdown }
                            ) {
                                TextField(
                                    value = templateState.selectedCurveModel?.let {
                                        "${it.name} (${it.pixelType})"
                                    } ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.curve_model)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCurveModelDropdown)
                                    },
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showCurveModelDropdown,
                                    onDismissRequest = { showCurveModelDropdown = false }
                                ) {
                                    availableCurveModels.forEach { model ->
                                        val modelText = "${model.name} (${model.pixelType})"
                                        DropdownMenuItem(
                                            text = { Text(modelText) },
                                            onClick = {
                                                viewModel.selectCurveModel(model)
                                                showCurveModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 测量范围卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.measurement_range),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // 范围最小值
                            val minValueGreaterThanMaxError = stringResource(R.string.min_value_greater_than_max)
                            OutlinedTextField(
                                value = templateState.reliableRangeMin,
                                onValueChange = { newValue ->
                                    // 只允许输入非负实数
                                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                        viewModel.updateRangeMin(newValue)

                                        // 如果最小值大于最大值，显示错误提示
                                        val minValue = newValue.toDoubleOrNull() ?: 0.0
                                        val maxValue = templateState.reliableRangeMax.toDoubleOrNull() ?: 0.0
                                        if (minValue > maxValue && maxValue > 0) {
                                            toastManager.showToast(
                                                minValueGreaterThanMaxError,
                                                ToastType.ERROR
                                            )
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.min_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                isError = templateState.reliableRangeMin.isNotEmpty() &&
                                        templateState.reliableRangeMax.isNotEmpty() &&
                                        (templateState.reliableRangeMin.toDoubleOrNull() ?: 0.0) >
                                        (templateState.reliableRangeMax.toDoubleOrNull() ?: 0.0)
                            )

                            // 范围最大值
                            val maxValueLessThanMinError = stringResource(R.string.max_value_less_than_min)
                            OutlinedTextField(
                                value = templateState.reliableRangeMax,
                                onValueChange = { newValue ->
                                    // 只允许输入非负实数
                                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                        viewModel.updateRangeMax(newValue)

                                        // 如果最大值小于最小值，显示错误提示
                                        val minValue = templateState.reliableRangeMin.toDoubleOrNull() ?: 0.0
                                        val maxValue = newValue.toDoubleOrNull() ?: 0.0
                                        if (minValue > maxValue && minValue > 0) {
                                            toastManager.showToast(
                                                maxValueLessThanMinError,
                                                ToastType.ERROR
                                            )
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.max_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                isError = templateState.reliableRangeMin.isNotEmpty() &&
                                        templateState.reliableRangeMax.isNotEmpty() &&
                                        (templateState.reliableRangeMin.toDoubleOrNull() ?: 0.0) >
                                        (templateState.reliableRangeMax.toDoubleOrNull() ?: 0.0)
                            )

                            // 浓度单位
                            ExposedDropdownMenuBox(
                                expanded = showUnitDropdown,
                                onExpandedChange = { showUnitDropdown = !showUnitDropdown }
                            ) {
                                TextField(
                                    value = templateState.concentrationUnit,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.concentration_unit)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showUnitDropdown)
                                    },
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = showUnitDropdown,
                                    onDismissRequest = { showUnitDropdown = false }
                                ) {
                                    // 获取系统可用的浓度单位
                                    val availableUnits = if (concentrationUnits.isNotEmpty()) {
                                        concentrationUnits.toList()
                                    } else {
                                        // 如果设置中没有单位，使用默认单位列表作为后备
                                        listOf("ng/mL", "pg/mL", "μg/mL", "mg/mL", "μmol/L", "mmol/L")
                                    }

                                    availableUnits.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text(unit) },
                                            onClick = {
                                                viewModel.updateConcentrationUnit(unit)
                                                showUnitDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 板布局卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.plate_layout),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // 启用默认布局的开关
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.enable_default_layout),
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Switch(
                                    checked = templateState.enableDefaultLayout,
                                    onCheckedChange = { viewModel.toggleDefaultLayout(it) }
                                )
                            }

                            // 默认布局说明
                            Text(
                                text = stringResource(R.string.create_template_default_layout_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            // 只有当启用默认布局时才显示布局编辑器
                            if (templateState.enableDefaultLayout) {
                                // 行数选择
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.rows),
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf(4, 6, 8, 12).forEach { rowCount ->
                                            val isSelected = templateState.plateRows == rowCount
                                            Surface(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .clickable { viewModel.updatePlateRows(rowCount) },
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.surface,
                                                border = if (!isSelected)
                                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                                else
                                                    null
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = rowCount.toString(),
                                                        color = if (isSelected)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 列数选择
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.columns),
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf(6, 8, 12).forEach { colCount ->
                                            val isSelected = templateState.plateColumns == colCount
                                            Surface(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .clickable { viewModel.updatePlateColumns(colCount) },
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.surface,
                                                border = if (!isSelected)
                                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                                else
                                                    null
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = colCount.toString(),
                                                        color = if (isSelected)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Divider(modifier = Modifier.padding(vertical = 8.dp))

                                // 角色选择器
                                RoleSelector(
                                    selectedRole = templateState.selectedWellRole ?: "",
                                    onRoleSelected = { role ->
                                        viewModel.selectWellRole(role)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // 添加孔板布局标题
                                Text(
                                    text = stringResource(R.string.default_layout),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                // 添加操作提示
                                Text(
                                    text = stringResource(R.string.default_layout_tip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                // 交互式板布局编辑器
                                InteractivePlateGrid(
                                    layout = templateState.defaultLayout,
                                    selectedRole = templateState.selectedWellRole ?: "",
                                    onWellClick = { wellIndex ->
                                        viewModel.updateWellRole(wellIndex)
                                    },
                                    rows = templateState.plateRows,
                                    columns = templateState.plateColumns,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // 底部间距
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}
