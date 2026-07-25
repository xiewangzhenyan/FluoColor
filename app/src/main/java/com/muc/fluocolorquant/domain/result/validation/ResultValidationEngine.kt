package com.muc.fluocolorquant.domain.result.validation

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 圆孔板和微流控可共用的预测精度验证数学内核。
 *
 * 引擎只接收冻结预测值和用户参考值，不依赖Compose、Room或载体形状，保证回归图、
 * Bland–Altman图、PDF与历史恢复使用完全一致的数值定义。
 */
object ResultValidationEngine {
    const val PROCESSOR_VERSION: String = "result-validation-v2"

    data class Result(
        val regression: ResultRegressionMetrics,
        val blandAltman: ResultBlandAltmanMetrics
    )

    fun calculate(points: List<ResultValidationPoint>): Result {
        val valid = points.filter { point ->
            point.predictedValue.isFinite() && point.referenceValue.isFinite()
        }
        require(valid.size >= 2) { "预测精度验证至少需要两个有效位点" }

        val references = valid.map(ResultValidationPoint::referenceValue)
        val predictions = valid.map(ResultValidationPoint::predictedValue)
        val count = valid.size.toDouble()
        val referenceMean = references.average()
        val predictionMean = predictions.average()
        val referenceVarianceSum = references.sumOf { value -> (value - referenceMean).pow(2) }
        val covarianceSum = references.zip(predictions).sumOf { (reference, prediction) ->
            (reference - referenceMean) * (prediction - predictionMean)
        }
        val slope = if (referenceVarianceSum > NUMERIC_EPSILON) {
            covarianceSum / referenceVarianceSum
        } else {
            null
        }
        val intercept = slope?.let { predictionMean - it * referenceMean }

        val directErrors = predictions.zip(references).map { (prediction, reference) ->
            prediction - reference
        }
        val squaredErrorSum = directErrors.sumOf { error -> error * error }
        val rmse = sqrt(squaredErrorSum / count)
        val mae = directErrors.sumOf { error -> kotlin.math.abs(error) } / count
        // 预测精度验证中的参考浓度是真值，因此R²分母必须使用参考值相对其均值的总离差。
        // 若误用预测值自身方差，预测整体缩放或偏移时会得到误导性的R²，且与旧验证页语义不一致。
        val rSquared = if (referenceVarianceSum > NUMERIC_EPSILON) {
            (1.0 - squaredErrorSum / referenceVarianceSum).takeIf(Double::isFinite)
        } else {
            null
        }

        val meanBias = directErrors.average()
        val standardDeviation = if (directErrors.size > 1) {
            sqrt(
                directErrors.sumOf { error -> (error - meanBias).pow(2) } /
                    (directErrors.size - 1).toDouble()
            )
        } else {
            0.0
        }
        val lowerLimit = meanBias - BLAND_ALTMAN_Z * standardDeviation
        val upperLimit = meanBias + BLAND_ALTMAN_Z * standardDeviation
        val withinLimitsRatio = directErrors.count { error ->
            error in lowerLimit..upperLimit
        }.toDouble() / count

        return Result(
            regression = ResultRegressionMetrics(
                slope = slope,
                intercept = intercept,
                rSquared = rSquared,
                rmse = rmse,
                mae = mae
            ),
            blandAltman = ResultBlandAltmanMetrics(
                meanBias = meanBias,
                standardDeviation = standardDeviation,
                lowerLimit = lowerLimit,
                upperLimit = upperLimit,
                withinLimitsRatio = withinLimitsRatio
            )
        )
    }

    private const val BLAND_ALTMAN_Z: Double = 1.96
    private const val NUMERIC_EPSILON: Double = 1e-12
}
