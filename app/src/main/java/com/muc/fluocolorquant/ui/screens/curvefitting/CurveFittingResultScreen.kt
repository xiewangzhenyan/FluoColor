package com.muc.fluocolorquant.ui.screens.curvefitting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.CurveFittingViewModel
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType

@Composable
fun CurveFittingResultScreen(
    navController: NavController,
    projectId: String,
    analyteId: String,
    viewModel: CurveFittingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scrollState = rememberScrollState()
    
    // 加载拟合结果
    LaunchedEffect(projectId, analyteId) {
        viewModel.loadFittingResults(projectId, analyteId)
    }
    
    val fittingResults by viewModel.fittingResults.collectAsState()
    val selectedResult by viewModel.selectedFittingResult.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    FluoColorTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.curve_fitting_result_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (selectedResult != null) {
                    // 显示选定的拟合结果
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "${stringResource(R.string.fitting_function)}: ${selectedResult?.function?.displayName ?: ""}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Text(
                                text = "${stringResource(R.string.pixel_type)}: ${selectedResult?.pixelType?.displayName ?: ""}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            
                            Text(
                                text = "${stringResource(R.string.r_squared)}: ${String.format("%.4f", selectedResult?.rSquared)}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            
                            // 显示曲线图表
                            selectedResult?.let { result ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .padding(vertical = 16.dp)
                                ) {
                                    // 创建图表数据
                                    val chartData = ChartData(
                                        title = "${result.function.displayName} - ${result.pixelType?.displayName}",
                                        xRange = Pair(
                                            result.standardPoints.minOfOrNull { it.first }?.let { it * 0.9 } ?: 0.0,
                                            result.standardPoints.maxOfOrNull { it.first }?.let { it * 1.1 } ?: 10.0
                                        ),
                                        yRange = Pair(
                                            result.standardPoints.minOfOrNull { it.second }?.let { it * 0.9 } ?: 0.0,
                                            result.standardPoints.maxOfOrNull { it.second }?.let { it * 1.1 } ?: 10.0
                                        ),
                                        standardPoints = result.standardPoints,
                                        curvePoints = result.curvePoints,
                                        formula = result.formula
                                    )
                                    
                                    CurveChart(
                                        data = chartData
                                    )
                                }
                            }
                            
                            // 显示方程式
                            Text(
                                text = "${stringResource(R.string.equation)}: ${selectedResult?.formula ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    
                    // 预测结果列表
                    if (selectedResult?.predictions?.isNotEmpty() == true) {
                        Text(
                            text = stringResource(R.string.concentration_predictions),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                        
                        selectedResult?.predictions?.forEach { prediction ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = prediction.wellLabel,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "${String.format("%.4f", prediction.concentration)} ng/ml",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                    
                    // 保存按钮
                    Button(
                        onClick = {
                            viewModel.saveFittingResult()
                            toastManager.showToast(
                                message = context.getString(R.string.fitting_result_saved),
                                type = ToastType.SUCCESS
                            )
                            navController.popBackStack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Text(stringResource(R.string.save_and_return))
                    }
                } else {
                    // 没有拟合结果
                    Text(
                        text = stringResource(R.string.no_fitting_results),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    
                    Button(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Text(stringResource(R.string.return_to_curve_fitting))
                    }
                }
            }
        }
    }
} 