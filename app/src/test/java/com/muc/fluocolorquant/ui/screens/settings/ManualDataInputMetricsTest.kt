package com.muc.fluocolorquant.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 普通标准曲线页面的指标展示契约测试。 */
class ManualDataInputMetricsTest {

    @Test
    fun `普通页面只保留用户可理解的四项指标并维持稳定顺序`() {
        val visibleMetrics = selectStandardCurveVisibleMetrics(
            linkedMapOf(
                "R²" to 0.9987,
                "Adj. R²" to 0.0,
                "MSE" to 1.25,
                "RMSE" to 1.118,
                "MAE" to 0.8,
                "ICH M10 Accepted" to 1.0,
                "Accepted Standard Ratio" to 0.8,
                "Weighting Preference" to 2.0
            )
        )

        assertEquals(
            listOf("R²", "RMSE", "MAE", "Accepted Standard Ratio"),
            visibleMetrics.keys.toList()
        )
        assertFalse(visibleMetrics.containsKey("Adj. R²"))
        assertFalse(visibleMetrics.containsKey("ICH M10 Accepted"))
        assertFalse(visibleMetrics.containsKey("Weighting Preference"))
    }

    @Test
    fun `非有限指标不会进入普通用户结果表`() {
        val visibleMetrics = selectStandardCurveVisibleMetrics(
            mapOf(
                "R²" to Double.NaN,
                "RMSE" to Double.POSITIVE_INFINITY,
                "MAE" to 0.25
            )
        )

        assertEquals(mapOf("MAE" to 0.25), visibleMetrics)
    }
}
