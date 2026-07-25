package com.muc.fluocolorquant.domain.result.validation

/** 单个参与预测精度验证的位点。预测值来自冻结运行，参考值由用户在结果页录入。 */
data class ResultValidationPoint(
    val siteIndex: Int,
    val siteLabel: String,
    val predictedValue: Double,
    val referenceValue: Double
)
/** 预测值相对于参考值的回归与直接误差指标。 */
data class ResultRegressionMetrics(
    val slope: Double?,
    val intercept: Double?,
    val rSquared: Double?,
    val rmse: Double,
    val mae: Double
)

/** Bland–Altman 使用“预测值 - 参考值”为差值方向，页面和导出必须保持同一语义。 */
data class ResultBlandAltmanMetrics(
    val meanBias: Double,
    val standardDeviation: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val withinLimitsRatio: Double
)

/** 一次已保存的验证修订；它属于运行后的派生分析，不修改原始检测快照。 */
data class ResultValidationSnapshot(
    val validationId: String,
    val runId: String,
    val analyteId: String,
    val revision: Int,
    val concentrationUnit: String,
    val points: List<ResultValidationPoint>,
    val regression: ResultRegressionMetrics,
    val blandAltman: ResultBlandAltmanMetrics,
    val processorVersion: String,
    val inputFingerprint: String,
    val createdAtEpochMillis: Long
)
