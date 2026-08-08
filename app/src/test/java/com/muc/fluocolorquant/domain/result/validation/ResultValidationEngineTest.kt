package com.muc.fluocolorquant.domain.result.validation

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 预测精度验证的回归、直接误差与Bland–Altman数学契约测试。 */
class ResultValidationEngineTest {

    @Test
    fun `预测与参考完全一致时所有误差为零且R2为1`() {
        val result = ResultValidationEngine.calculate(
            listOf(point(0, 1.0, 1.0), point(1, 2.0, 2.0), point(2, 3.0, 3.0))
        )

        assertEquals(1.0, result.regression.slope ?: Double.NaN, TOLERANCE)
        assertEquals(0.0, result.regression.intercept ?: Double.NaN, TOLERANCE)
        assertEquals(1.0, result.regression.rSquared ?: Double.NaN, TOLERANCE)
        assertEquals(0.0, result.regression.rmse, TOLERANCE)
        assertEquals(0.0, result.regression.mae, TOLERANCE)
        assertEquals(0.0, result.blandAltman.meanBias, TOLERANCE)
        assertEquals(0.0, result.blandAltman.standardDeviation, TOLERANCE)
        assertEquals(1.0, result.blandAltman.withinLimitsRatio, TOLERANCE)
    }

    @Test
    fun `已知误差数据使用参考浓度总离差计算R2`() {
        val result = ResultValidationEngine.calculate(
            listOf(point(0, 1.2, 1.0), point(1, 1.8, 2.0), point(2, 3.1, 3.0))
        )

        assertEquals(0.95, result.regression.slope ?: Double.NaN, TOLERANCE)
        assertEquals(0.1333333333, result.regression.intercept ?: Double.NaN, TOLERANCE)
        assertEquals(0.955, result.regression.rSquared ?: Double.NaN, TOLERANCE)
        assertEquals(sqrt(0.03), result.regression.rmse, TOLERANCE)
        assertEquals(1.0 / 6.0, result.regression.mae, TOLERANCE)
        assertEquals(1.0 / 30.0, result.blandAltman.meanBias, TOLERANCE)
        assertEquals(sqrt(0.043333333333333335), result.blandAltman.standardDeviation, TOLERANCE)
        assertEquals(1.0, result.blandAltman.withinLimitsRatio, TOLERANCE)
    }

    @Test
    fun `非有限点会被过滤但两个有效点仍可完成计算`() {
        val result = ResultValidationEngine.calculate(
            listOf(
                point(0, 1.0, 1.0),
                point(1, Double.NaN, 2.0),
                point(2, 3.0, 3.0)
            )
        )

        assertEquals(1.0, result.regression.rSquared ?: Double.NaN, TOLERANCE)
        assertEquals(0.0, result.blandAltman.meanBias, TOLERANCE)
    }

    @Test
    fun `少于两个有效点时拒绝生成误导性验证结果`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResultValidationEngine.calculate(
                listOf(point(0, 1.0, 1.0), point(1, Double.NaN, 2.0))
            )
        }
    }

    @Test
    fun `参考浓度没有变化时斜率与R2不可定义`() {
        val result = ResultValidationEngine.calculate(
            listOf(point(0, 1.0, 2.0), point(1, 2.0, 2.0), point(2, 3.0, 2.0))
        )

        assertNull(result.regression.slope)
        assertNull(result.regression.intercept)
        assertNull(result.regression.rSquared)
    }

    private fun point(index: Int, predicted: Double, reference: Double) = ResultValidationPoint(
        siteIndex = index,
        siteLabel = "A${index + 1}",
        predictedValue = predicted,
        referenceValue = reference
    )

    private companion object {
        const val TOLERANCE: Double = 1e-9
    }
}
