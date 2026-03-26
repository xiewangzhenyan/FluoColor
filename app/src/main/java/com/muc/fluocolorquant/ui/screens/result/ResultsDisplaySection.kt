@file:OptIn(ExperimentalFoundationApi::class)

package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.tables.MetricsTable
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import kotlinx.coroutines.launch
import java.util.Locale
import android.util.Log

/**
 * 结果展示区域
 * 使用内部HorizontalPager展示多种结果视图
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultsDisplaySection(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    // 确定要显示的页面数量
    val pageCount = if (details.analysisMethod == "CURVE_FIT" && details.fittedCurveModel != null) 4 else 3
    val pageOptions = remember(pageCount) {
        buildList {
            add(
                ResultDisplayPageOption(
                    titleRes = R.string.heatmap,
                    descriptionRes = R.string.result_display_heatmap_desc
                )
            )
            add(
                ResultDisplayPageOption(
                    titleRes = R.string.value_map,
                    descriptionRes = R.string.result_display_value_map_desc
                )
            )
            add(
                ResultDisplayPageOption(
                    titleRes = R.string.concentration_chart,
                    descriptionRes = R.string.result_display_chart_desc
                )
            )
            if (pageCount == 4) {
                add(
                    ResultDisplayPageOption(
                        titleRes = R.string.standard_curve,
                        descriptionRes = R.string.result_display_standard_curve_desc
                    )
                )
            }
        }
    }

    val pagerState = rememberPagerState { pageCount }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.result_display),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            ResultDisplaySegmentedControl(
                options = pageOptions,
                selectedIndex = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth(),
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
            )

            Text(
                text = stringResource(pageOptions[pagerState.currentPage].descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 页面内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth() // 移除固定高度
                // .height(350.dp) // 移除固定高度以支持动态高度
            ) { page ->
                when (page) {
                    0 -> PlateHeatmapView(details = details)
                    1 -> PlateValueView(details = details)
                    2 -> ConcentrationTrendView(details = details)
                    3 -> if (pageCount == 4) {
                        StandardCurveView(details = details)
                    }
                }
            }

            // 页面指示器
            ResultPageIndicator(
                pagerState = pagerState,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            )
        }
    }
}

private data class ResultDisplayPageOption(
    val titleRes: Int,
    val descriptionRes: Int
)

@Composable
private fun ResultDisplaySegmentedControl(
    options: List<ResultDisplayPageOption>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val rows = remember(options) {
        if (options.size > 3) options.chunked(2) else listOf(options)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var optionIndex = 0
        rows.forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowOptions.forEach { option ->
                    val currentIndex = optionIndex
                    val selected = currentIndex == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) colorScheme.primary
                                else colorScheme.surface.copy(alpha = 0.9f)
                            )
                            .border(
                                width = if (selected) 0.dp else 1.dp,
                                color = colorScheme.outline.copy(alpha = 0.22f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelect(currentIndex) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(option.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    optionIndex += 1
                }
            }
        }
    }
}

/**
 * 【修复】获取浓度单位
 * 直接从 AnalyteResultDetails 中获取已经处理好的单位，使UI层逻辑更简单
 */
@Composable
fun getConcentrationUnit(details: AnalyteResultDetails): String {
    return details.concentrationUnit
}

/**
 * 浓度热力图视图
 */
@Composable
fun PlateHeatmapView(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    PlateHeatmapCard(
        wellResults = details.wellResults,
        concentrationUnit = getConcentrationUnit(details),
        minConcentration = 0.0,
        maxConcentration = details.wellResults
            .mapNotNull { it.predictedConcentration }
            .filter { it.isFinite() }
            .maxOrNull() ?: 100.0,
        project = details.project
    )
}

/**
 * 浓度数值图视图
 */
@Composable
fun PlateValueView(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    SquareHeatmapCard(
        wellResults = details.wellResults,
        concentrationUnit = getConcentrationUnit(details),
        minConcentration = 0.0,
        maxConcentration = details.wellResults
            .mapNotNull { it.predictedConcentration }
            .filter { it.isFinite() }
            .maxOrNull() ?: 100.0,
        project = details.project
    )
}

/**
 * 浓度趋势图视图
 */
@Composable
fun ConcentrationTrendView(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    ConcentrationTrendCard(
        wellResults = details.wellResults,
        concentrationUnit = getConcentrationUnit(details),
        analyteName = details.analyte.name,
        maxConcentration = details.wellResults
            .mapNotNull { it.predictedConcentration }
            .filter { it.isFinite() }
            .maxOrNull() ?: 100.0
    )
}

/**
 * 标准曲线视图
 */
@Composable
fun StandardCurveView(
    details: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    val curveModel = details.fittedCurveModel ?: run {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(stringResource(R.string.no_standard_curve_data))
        }
        return
    }

    // 添加日志输出，帮助诊断问题
    Log.d("StandardCurveView", "Analyte: ${details.analyte.name}, Function: ${curveModel.function}, " +
            "Has standardCurveChartData: ${details.standardCurveChartData != null}, " +
            "Has dataPoints: ${!curveModel.dataPoints.isNullOrEmpty()}, " +
            "Parameters: ${curveModel.parameters}")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // 创建函数
        val curveFunction: (Double) -> Double = { x ->
            try {
                FittingEngine.calculate(curveModel.function, curveModel.parameters, x)
            } catch (e: Exception) {
                Log.e("StandardCurveView", "Error calculating curve value for x=$x: ${e.message}")
                0.0 // 发生错误时返回默认值
            }
        }

        // 使用CurveChart显示曲线
        details.standardCurveChartData?.let {
            Log.d("StandardCurveView", "Using standardCurveChartData")
            CurveChart(
                data = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            )
        } ?: run {
            // 如果没有标准曲线数据，使用函数直接绘制
            Log.d("StandardCurveView", "Using fallback with function and dataPoints")
            
            // 获取数据点用于绘制散点
            val dataPoints = curveModel.dataPoints ?: emptyList()
            Log.d("StandardCurveView", "DataPoints count: ${dataPoints.size}")
            
            // 确保X轴和Y轴范围合理
            val xMin = dataPoints.minOfOrNull { it.first } ?: 0.0
            val xMax = dataPoints.maxOfOrNull { it.first } ?: 100.0
            val yValues = try {
                (0..100).mapNotNull { i ->
                    val x = xMin + i * (xMax - xMin) / 100
                    try {
                        val y = curveFunction(x)
                        if (y.isNaN() || y.isInfinite()) null else y
                    } catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                Log.e("StandardCurveView", "Error generating curve points: ${e.message}")
                emptyList()
            }
            
            Log.d("StandardCurveView", "Generated ${yValues.size} curve points")
            
            CurveChart(
                fittedCurve = curveFunction,
                selectedFunction = curveModel.function,
                parameters = curveModel.parameters,
                xAxisLabel = stringResource(R.string.concentration),
                yAxisLabel = curveModel.pixelType.displayName,
                title = curveModel.function.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                dataPoints = dataPoints  // 添加数据点
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.function_expression),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val latexExpression = remember(curveModel) {
                    FittingEngine.formatParametersToLatex(curveModel.function, curveModel.parameters)
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

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.curve_parameters),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Divider()
                curveModel.parameters.forEach { (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(text = key, modifier = Modifier.weight(1f))
                        Text(text = String.format(Locale.US, "%.4g", value))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        curveModel.metrics?.let { metrics ->
            if (metrics.isNotEmpty()) {
                val formattedMetrics = remember(metrics) {
                    metrics.mapValues { String.format(Locale.US, "%.4f", it.value) }
                }
                MetricsTable(
                    metrics = formattedMetrics,
                    title = stringResource(R.string.fitting_quality)
                )
            }
        }
    }
}

/**
 * 页面指示器组件
 */
@Composable
fun ResultPageIndicator(
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pagerState.pageCount) { index ->
            val color = if (index == pagerState.currentPage) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
