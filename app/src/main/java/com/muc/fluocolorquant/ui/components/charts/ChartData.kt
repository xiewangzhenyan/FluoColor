package com.muc.fluocolorquant.ui.components.charts

import androidx.compose.ui.graphics.Color

/**
 * 图表数据点
 * @param x X坐标
 * @param y Y坐标
 * @param label 标签
 */
data class ChartPoint(
    val x: Double,
    val y: Double,
    val label: String = ""
)

/**
 * 图表数据类
 * @param title 图表标题
 * @param xRange X轴范围
 * @param yRange Y轴范围
 * @param standardPoints 标准点，通常用于标准曲线
 * @param curvePoints 曲线点，用于拟合曲线
 * @param formula 公式表达式
 * @param xAxisLabel X轴标签
 * @param yAxisLabel Y轴标签
 * @param fittedCurve 拟合函数
 * @param scatterPoints 散点数据
 * @param backgroundColor 背景颜色
 * @param curveColor 曲线颜色
 * @param pointColor 点颜色
 * @param gridColor 网格颜色
 * @param showGrid 是否显示网格
 * @param chartType 图表类型，如"STANDARD_CURVE", "REGRESSION", "BLAND_ALTMAN"等
 * @param additionalLines 附加线条，用于特殊图表（如Bland-Altman图的均值线和限值线）
 */
data class ChartData(
    val title: String = "",
    val xRange: Pair<Double, Double> = Pair(0.0, 10.0),
    val yRange: Pair<Double, Double> = Pair(0.0, 10.0),
    val standardPoints: List<Pair<Double, Double>> = emptyList(),
    val curvePoints: List<Pair<Double, Double>> = emptyList(),
    val formula: String = "",
    val xAxisLabel: String = "",
    val yAxisLabel: String = "",
    val fittedCurve: ((Double) -> Double)? = null,
    val scatterPoints: List<ChartPoint>? = null,
    val backgroundColor: Color = Color.White,
    val curveColor: Color = Color(0xFF2196F3),
    val pointColor: Color = Color(0xFF4CAF50),
    val gridColor: Color = Color(0xFFCCCCCC),
    val showGrid: Boolean = true,
    val chartType: String = "STANDARD_CURVE",
    val additionalLines: Map<String, List<Pair<Double, Double>>> = emptyMap(),
    val overlayLines: List<ChartLine> = emptyList(),
    val verticalMarkers: List<ChartVerticalMarker> = emptyList()
) 
