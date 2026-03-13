package com.muc.fluocolorquant.ui.components.charts

import androidx.compose.ui.graphics.Color

/**
 * 额外曲线，用于多通道叠加或辅助展示。
 */
data class ChartLine(
    val label: String,
    val points: List<Pair<Double, Double>>,
    val color: Color,
    val strokeWidth: Float = 2f,
    val dashed: Boolean = false
)

/**
 * 竖向标记线，用于峰值等关键位置标注。
 */
data class ChartVerticalMarker(
    val x: Double,
    val label: String,
    val color: Color = Color(0xFFFF6B6B)
)
