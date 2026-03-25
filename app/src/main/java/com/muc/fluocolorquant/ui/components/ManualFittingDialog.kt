package com.muc.fluocolorquant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import java.io.File

/**
 * 手动拟合对话框
 * 包含两个阶段：浓度输入和结果展示
 */
@Composable
fun ManualFittingDialog(
    standardWells: List<WellResult>,
    fittingResults: List<FittingResult>,
    isLoading: Boolean,
    recommendedPixelTypes: Set<PixelType>,
    onDismiss: () -> Unit,
    onStartFitting: (concentrations: Map<Int, Double>, functions: Set<FittingFunction>, pixelTypes: Set<PixelType>) -> Unit,
    onResultSelected: (FittingResult) -> Unit,
    onSaveAsTemplate: (FittingResult) -> Unit,
    onProcessNext: (FittingResult) -> Unit,
    allAnalytesConfigured: Boolean = false
) {
    // 对话框分为两个阶段：输入阶段和结果展示阶段
    val isResultStage = fittingResults.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (isLoading) {
                // 加载状态
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.calculating_fitting),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (isResultStage) {
                // 结果展示阶段
                FittingResultStage(
                    results = fittingResults,
                    onResultSelected = onResultSelected,
                    onSaveAsTemplate = onSaveAsTemplate,
                    onDismiss = onDismiss,
                    onProcessNext = onProcessNext,
                    allAnalytesConfigured = allAnalytesConfigured
                )
            } else {
                // 浓度输入阶段
                FittingInputStage(
                    standardWells = standardWells,
                    recommendedPixelTypes = recommendedPixelTypes,
                    onDismiss = onDismiss,
                    onStartFitting = onStartFitting
                )
            }
        }
    }
}

/**
 * 阶段一：输入浓度和选择算法
 */
@Composable
private fun FittingInputStage(
    standardWells: List<WellResult>,
    recommendedPixelTypes: Set<PixelType>,
    onDismiss: () -> Unit,
    onStartFitting: (concentrations: Map<Int, Double>, functions: Set<FittingFunction>, pixelTypes: Set<PixelType>) -> Unit
) {
    var concentrations by remember { mutableStateOf(mapOf<Int, String>()) }
    // 默认勾选常用且计算速度快的算法
    var selectedFunctions by remember { mutableStateOf(setOf(FittingFunction.LINEAR, FittingFunction.QUADRATIC, FittingFunction.RODBARD)) }
    var selectedPixelTypes by remember(recommendedPixelTypes) {
        mutableStateOf(
            if (recommendedPixelTypes.isNotEmpty()) {
                recommendedPixelTypes
            } else {
                setOf(PixelType.GRAY_LUMINOSITY, PixelType.RATIO_RB)
            }
        )
    }

    // 检查是否可以开始拟合（所有浓度都已输入）
    val canStartFitting = remember(concentrations, standardWells) {
        concentrations.size == standardWells.size && concentrations.values.all { 
            it.isNotBlank() && it.toDoubleOrNull() != null 
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 标题
        Text(
            text = stringResource(R.string.concentration_input_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.CenterHorizontally)
        )

        // 浓度输入列表 (可滚动)
        LazyColumn(
            modifier = Modifier
                .weight(1f) // 占据剩余空间的主要部分
                .padding(horizontal = 16.dp)
        ) {
            items(standardWells, key = { it.resultId }) { well ->
                StandardConcentrationInputRow(
                    well = well,
                    value = concentrations[well.wellIndex] ?: "",
                    onValueChange = { newValue ->
                        concentrations = concentrations + (well.wellIndex to newValue)
                    }
                )
                Divider()
            }
        }

        // 算法选择区域 (可滚动)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp) // 给算法选择一个最大高度，超出则可滚动
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 函数选择
            Text(
                text = stringResource(R.string.select_fitting_functions),
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(Modifier.height(8.dp))
            
            // 使用 FlowRow 自动换行
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalGap = 8.dp,
                verticalGap = 4.dp
            ) {
                FittingFunction.values().forEach { func ->
                    if (func != FittingFunction.INTERPOLATION) {
                        FilterChip(
                            selected = func in selectedFunctions,
                            onClick = {
                                selectedFunctions = if (func in selectedFunctions) {
                                    // 至少保留一个函数
                                    if (selectedFunctions.size > 1) {
                                        selectedFunctions - func
                                    } else {
                                        selectedFunctions
                                    }
                                } else {
                                    selectedFunctions + func
                                }
                            },
                            label = { Text(func.displayName) }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // 像素类型选择
            Text(
                text = stringResource(R.string.select_pixel_types),
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(Modifier.height(8.dp))
            
            // 使用 FlowRow 自动换行
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalGap = 8.dp,
                verticalGap = 4.dp
            ) {
                PixelType.values().forEach { type ->
                    FilterChip(
                        selected = type in selectedPixelTypes,
                        onClick = {
                            selectedPixelTypes = if (type in selectedPixelTypes) {
                                // 至少保留一个像素类型
                                if (selectedPixelTypes.size > 1) {
                                    selectedPixelTypes - type
                                } else {
                                    selectedPixelTypes
                                }
                            } else {
                                selectedPixelTypes + type
                            }
                        },
                        label = { Text(type.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }

        // 操作按钮（固定在底部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            
            Spacer(Modifier.width(8.dp))
            
            Button(
                onClick = {
                    // 将字符串转换为Double并开始拟合
                    val finalConcentrations = concentrations.mapValues { 
                        it.value.toDoubleOrNull() ?: 0.0 
                    }
                    onStartFitting(finalConcentrations, selectedFunctions, selectedPixelTypes)
                },
                enabled = canStartFitting
            ) {
                Text(stringResource(R.string.start_fitting))
            }
        }
    }
}

/**
 * 标准品浓度输入行
 */
@Composable
private fun StandardConcentrationInputRow(
    well: WellResult,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 孔位标签
        val wellLabel = WellMappingUtils.getWellLabel(well.virtualRow ?: 0, well.virtualCol ?: 0)
        
        // 孔位图像
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(well.croppedImageIdentifier?.let { File(it) })
                .crossfade(true)
                .build(),
            contentDescription = wellLabel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
        
        Spacer(Modifier.width(16.dp))
        
        // 孔位标签
        Text(
            text = wellLabel,
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(Modifier.width(16.dp))
        
        // 浓度输入框
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.concentration)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
    }
}

/**
 * 阶段二：展示和选择拟合结果
 */
@Composable
private fun FittingResultStage(
    results: List<FittingResult>,
    onResultSelected: (FittingResult) -> Unit,
    onSaveAsTemplate: (FittingResult) -> Unit,
    onDismiss: () -> Unit,
    onProcessNext: (FittingResult) -> Unit,  // 修改为接收FittingResult参数
    allAnalytesConfigured: Boolean = false
) {
    // 默认选择第一个结果（最佳拟合）
    var selectedResult by remember { mutableStateOf(results.firstOrNull()) }
    
    if (selectedResult == null && results.isNotEmpty()) {
        selectedResult = results.first()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = stringResource(R.string.fitting_result),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        
        if (selectedResult != null) {
            // 主结果显示区域
            Text(
                text = stringResource(R.string.best_fit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            FittingResultCard(
                result = selectedResult!!,
                isMain = true,
                onClick = { /* 主卡片不可点击 */ }
            )

            // 备选结果
            val otherResults = results.filter { it != selectedResult }.take(3)
            if (otherResults.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.alternative_fits),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                
                otherResults.forEach { result ->
                    FittingResultCard(
                        result = result,
                        isMain = false,
                        onClick = {
                            onResultSelected(result)
                            selectedResult = result
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 取消按钮
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 保存为模板按钮
                OutlinedButton(
                    onClick = { 
                        selectedResult?.let { onSaveAsTemplate(it) } 
                    },
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text(stringResource(R.string.save_as_template))
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 应用并处理下一个按钮
                Button(
                    onClick = { 
                        selectedResult?.let { onProcessNext(it) }  // 传递选中的结果
                    },
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text(stringResource(R.string.confirm_and_continue))
                }
            }
        } else {
            // 无结果时显示提示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_valid_fitting_results),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 拟合结果卡片
 */
@Composable
private fun FittingResultCard(
    result: FittingResult,
    isMain: Boolean,
    onClick: () -> Unit
) {
    val cardModifier = if (isMain) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }
    
    Card(
        modifier = cardModifier.padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isMain) 4.dp else 2.dp)
    ) {
        Column(Modifier.padding(if (isMain) 16.dp else 8.dp)) {
            // 标题：函数名称和像素类型
            Text(
                text = "${result.function.displayName} - ${result.pixelType?.displayName ?: "未知"}",
                style = if (isMain) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // R²值
            Text(
                text = stringResource(R.string.r_squared, result.rSquared),
                style = if (isMain) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            // 主卡片显示曲线图
            if (isMain) {
                Spacer(Modifier.height(8.dp))

                // 曲线图
                CurveChart(
                    dataPoints = result.standardPoints,
                    fittedCurve = { x -> 
                        FittingEngine.calculate(result.function, result.params, x) 
                    },
                    selectedFunction = result.function,
                    parameters = result.params,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
                
                // 函数公式 - 使用LatexView替换普通Text
                Spacer(Modifier.height(8.dp))
                val latexExpression = remember(result.function, result.params) {
                    FittingEngine.formatParametersToLatex(result.function, result.params)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LatexView(latex = latexExpression)
                }
            }
        }
    }
} 
