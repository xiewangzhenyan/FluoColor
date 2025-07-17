package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.ui.viewmodels.ResultViewModel
import com.muc.fluocolorquant.ui.screens.result.getConcentrationUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log

private const val TAG = "ValidationCard"

/**
 * 预测精度验证卡片
 */
@Composable
fun ValidationCard(
    analyteId: String,
    analyteDetails: AnalyteResultDetails,
    viewModel: ResultViewModel,
    modifier: Modifier = Modifier
) {
    // 对话框显示状态
    var showDialog by remember { mutableStateOf(false) }

    // 验证报告生成状态
    var isGeneratingReport by remember { mutableStateOf(false) }

    // 直接从ViewModel获取最新状态
    val analytesMap by viewModel.analyteResultsMap.collectAsState()

    // 使用map中的最新数据，如果不存在则使用传入的数据
    val currentDetails = analytesMap[analyteId] ?: analyteDetails

    // 当验证数据更新后自动关闭加载状态
    LaunchedEffect(currentDetails.validationData) {
        if (currentDetails.validationData != null && isGeneratingReport) {
            Log.d(TAG, "验证数据已更新，关闭加载状态")
            isGeneratingReport = false
        }
    }

    // 创建协程作用域
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.prediction_accuracy_validation),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 根据是否有验证数据或正在生成报告显示不同内容
            if (isGeneratingReport) {
                // 显示加载中状态
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.generating_validation_report),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (currentDetails.validationData == null) {
                // 显示开始验证按钮
                Button(
                    onClick = { showDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.input_true_values_to_compare))
                }
            } else {
                // 显示验证结果
                ValidationResults(details = currentDetails)
            }
        }
    }

    // 真实浓度输入对话框
    if (showDialog) {
        TrueConcentrationInputDialog(
            details = currentDetails,
            onDismiss = { showDialog = false },
            onSave = { values ->
                Log.d(TAG, "保存真实浓度值并生成验证报告: ${values.size}个值")
                isGeneratingReport = true
                showDialog = false

                // 【已修改】直接调用ViewModel的函数，不再自己管理协程
                viewModel.updateTrueConcentrations(analyteId, values)
            },
            viewModel = viewModel
        )
    }
}

/**
 * 验证结果显示组件
 */
@Composable
fun ValidationResults(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    val validationData = details.validationData

    if (validationData == null) {
        // 显示没有数据的提示
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "暂无验证数据",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 回归分析结果
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.concentration_regression_analysis),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 回归分析图
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    CurveChart(
                        data = validationData.regressionPlotData,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 回归分析指标
                MetricsTable(
                    metrics = validationData.regressionMetrics,
                    title = stringResource(R.string.regression_metrics)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bland-Altman分析结果
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.bland_altman_analysis),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Bland-Altman图
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    CurveChart(
                        data = validationData.blandAltmanPlotData,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bland-Altman指标
                MetricsTable(
                    metrics = validationData.blandAltmanMetrics,
                    title = stringResource(R.string.agreement_metrics)
                )
            }
        }
    }
}

/**
 * 真实浓度输入对话框
 */
@Composable
fun TrueConcentrationInputDialog(
    details: AnalyteResultDetails,
    onDismiss: () -> Unit,
    onSave: (Map<Long, Double>) -> Unit,
    viewModel: ResultViewModel
) {
    // 获取样本孔位（非标准品）
    val sampleWells = details.wellResults.filter { it.roleType == "SAMPLE" || !it.isStandard }

    // 初始化真实浓度输入Map，优先使用数据库中已有的值
    val trueConcentrationsMap = remember {
        mutableStateMapOf<Long, Double>().apply {
            // 优先使用已保存的真实浓度值
            sampleWells.forEach { well ->
                if (well.trueConcentration != null) {
                    Log.d(TAG, "预填充已有浓度值: ${well.resultId} = ${well.trueConcentration}")
                    put(well.resultId, well.trueConcentration!!)
                }
            }
        }
    }

    // 获取浓度单位
    val concentrationUnit = getConcentrationUnit(details)

    // 对话框本地加载状态
    var dialogIsLoading by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.input_true_concentrations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 滚动列表，显示所有样本孔位
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    items(sampleWells) { well ->
                        WellConcentrationInputRow(
                            well = well,
                            currentValue = trueConcentrationsMap[well.resultId],
                            onValueChange = { value ->
                                if (value != null) {
                                    trueConcentrationsMap[well.resultId] = value
                                } else {
                                    trueConcentrationsMap.remove(well.resultId)
                                }
                            },
                            concentrationUnit = concentrationUnit,
                            viewModel = viewModel
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            dialogIsLoading = true
                            // 保存真实浓度值并生成验证报告
                            onSave(trueConcentrationsMap.toMap())
                        },
                        enabled = trueConcentrationsMap.isNotEmpty() && !dialogIsLoading
                    ) {
                        if (dialogIsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(stringResource(R.string.generate_validation_report))
                    }
                }
            }
        }
    }
}

/**
 * 单个孔位的浓度输入行组件
 */
@Composable
fun WellConcentrationInputRow(
    well: com.muc.fluocolorquant.data.model.WellResult,
    currentValue: Double?,
    onValueChange: (Double?) -> Unit,
    concentrationUnit: String,
    viewModel: ResultViewModel
) {
    val wellLabel = if (well.virtualRow != null && well.virtualCol != null) {
        "${('A' + well.virtualRow).toChar()}${well.virtualCol + 1}"
    } else {
        "Well ${well.wellIndex + 1}"
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 孔位图像
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val imageResource = viewModel.getWellImageFile(well)
                if (imageResource != null) {
                    Image(
                        painter = rememberAsyncImagePainter(model = imageResource),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = wellLabel,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 孔位标签
            Text(
                text = wellLabel,
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 预测浓度显示
            Text(
                text = stringResource(
                    R.string.predicted_concentration_format,
                    well.predictedConcentration ?: 0.0,
                    concentrationUnit
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 真实浓度输入框
            var textValue by remember(currentValue) {
                // 不再使用toString()，而是使用自定义格式化，避免显示".0"后缀
                mutableStateOf(if (currentValue != null) {
                    // 移除尾部的".0"
                    val valueStr = currentValue.toString()
                    if (valueStr.endsWith(".0")) valueStr.substring(0, valueStr.length - 2) else valueStr
                } else "")
            }

            OutlinedTextField(
                value = textValue,
                onValueChange = { newValue ->
                    // 只允许输入数字、小数点和负号，且小数点只能有一个
                    val validInput = newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))
                    if (validInput) {
                        textValue = newValue
                        val doubleValue = newValue.toDoubleOrNull()
                        onValueChange(doubleValue)
                    }
                },
                label = { Text(stringResource(R.string.true_concentration), fontSize = 12.sp) },
                modifier = Modifier.width(120.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )
        }
    }
}