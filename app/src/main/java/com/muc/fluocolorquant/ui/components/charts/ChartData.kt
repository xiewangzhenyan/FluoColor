package com.muc.fluocolorquant.ui.components.charts

import androidx.compose.ui.graphics.Color

/**
 * 图表数据点
 */
data class ChartPoint(
    val x: Double,
    val y: Double,
    val label: String? = null
)

/**
 * 图表数据类
 * 用于CurveChart组件显示曲线和散点图
 */
data class ChartData(
    val title: String,
    val xAxisLabel: String,
    val yAxisLabel: String,
    val scatterPoints: List<ChartPoint>? = null,
    val fittedCurve: ((Double) -> Double)? = null,
    val xRange: Pair<Double, Double> = Pair(0.0, 10.0),
    val yRange: Pair<Double, Double> = Pair(0.0, 10.0),
    val curveColor: Color = Color(0xFF2196F3),
    val pointColor: Color = Color(0xFF4CAF50),
    val gridColor: Color = Color(0xFFCCCCCC),
    val backgroundColor: Color = Color.White,
    val showGrid: Boolean = true
) 