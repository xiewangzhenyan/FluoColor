package com.muc.fluocolorquant.utils

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 热力图颜色工具类
 * 用于根据浓度值生成不同颜色
 */
object HeatmapColorUtil {
    // 专业渐变色系
    private val colorStops = listOf(
        0.0f to Color(13, 71, 161),    // 深蓝色  - 最低浓度
        0.25f to Color(25, 118, 210),  // 蓝色
        0.4f to Color(66, 165, 245),   // 浅蓝色
        0.5f to Color(46, 125, 50),    // 绿色
        0.6f to Color(139, 195, 74),   // 浅绿色
        0.7f to Color(255, 235, 59),   // 黄色
        0.8f to Color(255, 152, 0),    // 橙色
        0.9f to Color(244, 67, 54),    // 红色
        1.0f to Color(183, 28, 28)     // 深红色 - 最高浓度
    )
    
    /**
     * 生成热力图颜色，从低浓度到高浓度渐变
     * @param value 当前值
     * @param minValue 最小值
     * @param maxValue 最大值
     * @return 对应的颜色
     */
    fun getColor(value: Double, minValue: Double, maxValue: Double): Color {
        if (!value.isFinite() || value < minValue) {
            return Color(224, 224, 224, 180) // 半透明浅灰色作为无效值
        }
        
        // 计算归一化值 (0.0-1.0之间)
        val normalizedValue = if (maxValue > minValue) {
            min(1.0, max(0.0, (value - minValue) / (maxValue - minValue)))
        } else {
            0.5 // 如果最大和最小值相同，则使用中间颜色
        }
        
        val normFloat = normalizedValue.toFloat()
        
        // 找到对应的渐变区间
        val (lowerStop, upperStop) = colorStops.zipWithNext().find { (lower, upper) ->
            normFloat >= lower.first && normFloat <= upper.first
        } ?: (colorStops.first() to colorStops.last())
        
        // 计算在区间内的百分比
        val rangeFraction = if (upperStop.first > lowerStop.first) {
            (normFloat - lowerStop.first) / (upperStop.first - lowerStop.first)
        } else {
            0f
        }
        
        // 在两个颜色之间插值
        return lerpColor(lowerStop.second, upperStop.second, rangeFraction)
    }
    
    /**
     * 在两个颜色之间线性插值
     */
    private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
        return Color(
            red = lerp(start.red, end.red, fraction),
            green = lerp(start.green, end.green, fraction),
            blue = lerp(start.blue, end.blue, fraction),
            alpha = lerp(start.alpha, end.alpha, fraction)
        )
    }
    
    /**
     * 线性插值辅助函数
     */
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    /**
     * 获取用于热力图图例的渐变颜色列表
     * @param steps 颜色梯度步数
     * @return 颜色列表
     */
    fun getLegendColors(steps: Int = 10): List<Color> {
        return (0 until steps).map { i ->
            val normalizedValue = i.toFloat() / (steps - 1)
            
            // 找到对应的渐变区间
            val (lowerStop, upperStop) = colorStops.zipWithNext().find { (lower, upper) ->
                normalizedValue >= lower.first && normalizedValue <= upper.first
            } ?: (colorStops.first() to colorStops.last())
            
            // 计算在区间内的百分比
            val rangeFraction = if (upperStop.first > lowerStop.first) {
                (normalizedValue - lowerStop.first) / (upperStop.first - lowerStop.first)
            } else {
                0f
            }
            
            // 在两个颜色之间插值
            lerpColor(lowerStop.second, upperStop.second, rangeFraction)
        }
    }
} 