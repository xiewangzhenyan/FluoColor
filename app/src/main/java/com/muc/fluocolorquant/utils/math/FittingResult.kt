package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType

/**
 * 曲线拟合结果数据类
 */
data class FittingResult(
    val function: FittingFunction,          // 拟合函数类型
    val parameters: DoubleArray,            // 拟合参数
    val formula: String,                    // 公式字符串
    val rSquared: Double,                   // R²值
    val standardPoints: List<Pair<Double, Double>>, // 标准点(浓度, 像素值)
    val curvePoints: List<Pair<Double, Double>>,    // 曲线点(浓度, 像素值)
    val pixelType: PixelType? = null,      // 像素类型
    val predictions: List<ConcentrationPrediction> = emptyList(), // 浓度预测结果
    val isSuccess: Boolean = true,         // 拟合是否成功
    val errorMessage: String? = null,      // 错误信息
    val allMetrics: Map<String, Double> = mapOf("R²" to rSquared), // 所有拟合指标
    // 为了兼容性，添加params和metrics作为计算属性
    val dataPoints: List<Pair<Double, Double>> = standardPoints // 数据点(浓度, 像素值)
) {
    // 为兼容性添加的计算属性
    val params: Map<String, Double>
        get() = function.requiredParams.mapIndexed { index, paramName ->
            paramName to (parameters.getOrNull(index) ?: 0.0)
        }.toMap()
    
    val metrics: Map<String, Double>
        get() = allMetrics
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FittingResult

        if (function != other.function) return false
        if (!parameters.contentEquals(other.parameters)) return false
        if (formula != other.formula) return false
        if (rSquared != other.rSquared) return false
        if (standardPoints != other.standardPoints) return false
        if (curvePoints != other.curvePoints) return false
        if (pixelType != other.pixelType) return false
        if (predictions != other.predictions) return false
        if (isSuccess != other.isSuccess) return false
        if (errorMessage != other.errorMessage) return false
        if (dataPoints != other.dataPoints) return false

        return true
    }

    override fun hashCode(): Int {
        var result = function.hashCode()
        result = 31 * result + parameters.contentHashCode()
        result = 31 * result + formula.hashCode()
        result = 31 * result + rSquared.hashCode()
        result = 31 * result + standardPoints.hashCode()
        result = 31 * result + curvePoints.hashCode()
        result = 31 * result + (pixelType?.hashCode() ?: 0)
        result = 31 * result + predictions.hashCode()
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + dataPoints.hashCode()
        return result
    }
}

/**
 * 浓度预测结果数据类
 */
data class ConcentrationPrediction(
    val wellLabel: String = "",     // 孔位标签（如A1, B2等）
    val wellId: String = "",        // 孔位ID
    val pixelValue: Double,         // 像素值
    val concentration: Double,      // 预测浓度（这是主要的属性名）
    val pixelType: PixelType? = null, // 像素类型（可选）
    val sampleIndex: Int = -1,      // 样本索引（用于批量预测）
    val isValid: Boolean = true,    // 预测是否有效
    val errorMessage: String? = null // 错误信息
) {
    // 为了兼容性，提供 predictedConcentration 别名
    val predictedConcentration: Double get() = concentration
} 