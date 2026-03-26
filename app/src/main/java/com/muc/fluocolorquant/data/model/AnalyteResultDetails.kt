package com.muc.fluocolorquant.data.model

import com.muc.fluocolorquant.ui.components.charts.ChartData

/**
 * 封装单个分析物所有报告详情的核心数据类
 * 封装好的数据模型，不是具体的数据库表
 */
data class AnalyteResultDetails(
    // 基础信息
    val analyte: Analyte,
    val wellResults: List<WellResult>, // 只包含该分析物相关的孔位结果
    val project: Project, // 包含项目元数据
    val concentrationUnit: String, // 【新增】直接携带浓度单位

    // 分析方案信息
    val analysisMethod: String, // "DL_MODEL" 或 "CURVE_FIT"
    val usedTemplate: ExperimentTemplate? = null, // 如果使用了模板，则包含模板信息
    val fittedCurveModel: CurveModel? = null, // 存储关联的曲线模型（来自模板或手动拟合）

    // 预计算的图表数据
    val standardCurveChartData: ChartData? = null, // 标准曲线的图表数据 (仅CURVE_FIT有)
    val concentrationTrendChartData: ChartData? = null, // 浓度折线图的数据

    // 精度验证数据 (可空，因为需要用户触发)
    val validationData: ValidationData? = null,
    val traceabilityInfo: ResultTraceabilityInfo? = null
)

/**
 * 封装"精度验证"模块的所有数据
 */
data class ValidationData(
    val regressionPlotData: ChartData, // 用于 "预测 vs. 真实" 回归图
    val regressionMetrics: Map<String, String>, // R², MSE, MAPE等
    val blandAltmanPlotData: ChartData, // 用于 Bland-Altman 分析图
    val blandAltmanMetrics: Map<String, String> // Bland-Altman 的评价指标
)
