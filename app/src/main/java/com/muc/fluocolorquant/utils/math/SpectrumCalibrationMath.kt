package com.muc.fluocolorquant.utils.math

import android.graphics.Rect

/**
 * 鍏夎氨鑷姩鏍囧畾涓殑褰掍竴鍖栧潗鏍囪绠楀伐鍏枫€? */
object SpectrumCalibrationMath {

    /**
     * 灏嗙粷瀵?Y 鍧愭爣褰掍竴鍖栧埌鎸囧畾鐭╁舰鍐呴儴鐨?0.0 - 1.0 鍖洪棿銆?     */
    fun normalizeAbsoluteY(y: Double, rect: Rect): Double {
        return normalizeAbsoluteY(
            y = y,
            top = rect.top,
            bottomExclusive = rect.bottom
        )
    }

    fun normalizeAbsoluteY(y: Double, top: Int, bottomExclusive: Int): Double {
        val span = (bottomExclusive - top - 1).coerceAtLeast(1).toDouble()
        return ((y - top.toDouble()) / span).coerceIn(0.0, 1.0)
    }

    /**
     * 灏?ROI 鍐呴儴鐨勭浉瀵硅绱㈠紩褰掍竴鍖栧埌 0.0 - 1.0 鍖洪棿銆?     */
    fun normalizeRelativeRow(rowIndex: Int, totalRows: Int): Double {
        if (totalRows <= 1) return 0.0
        return (rowIndex.toDouble() / (totalRows - 1).toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 缁熶竴璁＄畻浜屾澶氶」寮忓湪鎸囧畾鍧愭爣涓婄殑娉㈤暱鍊笺€?     */
    fun evaluatePolynomial(coefficients: DoubleArray, x: Double): Double {
        val a = coefficients.getOrElse(0) { 0.0 }
        val b = coefficients.getOrElse(1) { 0.0 }
        val c = coefficients.getOrElse(2) { 0.0 }
        return a * x * x + b * x + c
    }

    /**
     * 计算拟合后的均方根误差，用于衡量自动标定质量。
     */
    fun calculateFitRmse(
        points: List<Pair<Double, Double>>,
        coefficients: DoubleArray
    ): Double {
        if (points.isEmpty()) return 0.0

        val mse = points.map { (x, y) ->
            val diff = evaluatePolynomial(coefficients, x) - y
            diff * diff
        }.average()
        return kotlin.math.sqrt(mse)
    }

    /**
     * 根据峰匹配完整度、拟合残差和有效高度覆盖率综合计算自动标定得分。
     */
    fun calculateAutoCalibrationQualityScore(
        detectedPeakCount: Int,
        referencePeakCount: Int,
        fitRmse: Double?,
        effectiveCoverage: Double?,
        usedFallbackAlignment: Boolean
    ): Int {
        var score = 100.0

        if (referencePeakCount > 0) {
            val completeness = (detectedPeakCount.toDouble() / referencePeakCount.toDouble())
                .coerceIn(0.0, 1.0)
            score -= (1.0 - completeness) * 35.0
        }

        fitRmse?.let { rmse ->
            score -= when {
                rmse <= 5.0 -> 0.0
                rmse <= 10.0 -> 3.0
                rmse <= 20.0 -> 8.0
                rmse <= 30.0 -> 14.0
                rmse <= 45.0 -> 22.0
                else -> 30.0
            }
        }

        effectiveCoverage?.let { coverage ->
            when {
                coverage < 0.12 -> score -= 16.0
                coverage < 0.18 -> score -= 8.0
                coverage > 0.98 -> score -= 14.0
                coverage > 0.95 -> score -= 8.0
            }
        }

        if (usedFallbackAlignment) {
            score -= 10.0
        }

        return score.toInt().coerceIn(0, 100)
    }
}
