package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction

/**
 * 曲线拟合结果数据类
 * 
 * @param function 使用的拟合函数类型
 * @param params 拟合得到的参数值映射表，键为参数名称（如"a", "b", "c"等），值为参数数值
 * @param metrics 拟合质量评估指标，如R²、RMSE等
 * @param isSuccess 拟合是否成功
 * @param errorMessage 如果拟合失败，包含错误信息
 */
data class FittingResult(
    val function: FittingFunction,
    val params: Map<String, Double>,
    val metrics: Map<String, Double>, // 包含 R², RMSE, MSE, MAE 等评估指标
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val dataPoints: List<Pair<Double, Double>> = emptyList() // 原始数据点，用于图表显示
) 