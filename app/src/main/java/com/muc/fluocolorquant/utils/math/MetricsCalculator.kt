package com.muc.fluocolorquant.utils.math

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 指标计算工具类
 * 用于计算各种拟合质量评估指标
 */
object MetricsCalculator {

    /**
     * 计算所有评估指标
     * 
     * @param observed 观测值列表
     * @param predicted 预测值列表
     * @param numParameters 拟合模型中的参数数量
     * @return 包含所有评估指标的映射表
     */
    fun calculateAllMetrics(
        observed: List<Double>,
        predicted: List<Double>,
        numParameters: Int
    ): Map<String, Double> {
        if (observed.size != predicted.size || observed.isEmpty()) {
            throw IllegalArgumentException("观测值和预测值必须非空且长度相等")
        }

        val n = observed.size
        
        // 计算平均值
        val mean = observed.sum() / n
        
        // 计算误差平方和
        var ssRes = 0.0 // 残差平方和
        var ssTot = 0.0 // 总平方和
        var sumAbsError = 0.0 // 绝对误差和
        
        for (i in observed.indices) {
            val residual = observed[i] - predicted[i]
            ssRes += residual * residual
            ssTot += (observed[i] - mean).pow(2)
            sumAbsError += abs(residual)
        }
        
        // 确保总平方和不为零，避免除零错误
        if (ssTot < 1e-10) {
            ssTot = 1.0 // 设置一个小的非零值
        }
        
        // 计算各项指标
        val r2 = 1.0 - (ssRes / ssTot)
        
        // 限制R²在合理范围内（0到1之间）
        val clampedR2 = r2.coerceIn(0.0, 1.0)
        
        // 计算调整后的R²
        val adjustedR2 = if (n > numParameters) {
            1.0 - ((1.0 - clampedR2) * (n - 1) / (n - numParameters - 1))
        } else {
            0.0
        }
        
        // 确保调整后的R²也在合理范围内
        val clampedAdjustedR2 = adjustedR2.coerceIn(0.0, 1.0)
        
        // 计算均方误差
        val mse = if (n > 0) ssRes / n else 0.0
        
        // 计算均方根误差
        val rmse = sqrt(mse)
        
        // 计算平均绝对误差
        val mae = if (n > 0) sumAbsError / n else 0.0
        
        // 返回计算结果，确保所有值都是有限的
        return mapOf(
            "R²" to clampedR2,
            "Adj. R²" to clampedAdjustedR2,
            "MSE" to mse,
            "RMSE" to rmse,
            "MAE" to mae
        ).mapValues { (_, value) ->
            if (value.isNaN() || value.isInfinite()) 0.0 else value
        }
    }
    
    /**
     * 计算决定系数（R²）
     * R² = 1 - (SS_res / SS_tot)
     * 
     * @param observed 观测值列表
     * @param predicted 预测值列表
     * @return R² 值
     */
    fun calculateR2(observed: List<Double>, predicted: List<Double>): Double {
        val metrics = calculateAllMetrics(observed, predicted, 1)
        return metrics["R²"] ?: 0.0
    }
} 