package com.muc.fluocolorquant.ui.viewmodels

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 标准曲线拟合指标持久化边界测试。 */
class StandardCurveBuilderPersistenceTest {

    @Test
    fun `统计自由度不足产生的非有限指标不会阻止标准曲线保存`() {
        val persistedMetrics = finiteStandardCurveMetrics(
            linkedMapOf(
                "R²" to 0.9946,
                "RMSE" to 0.760219,
                "AICc" to Double.POSITIVE_INFINITY,
                "Undefined" to Double.NaN
            )
        )

        assertEquals(listOf("R²", "RMSE"), persistedMetrics.keys.toList())
        assertFalse(persistedMetrics.containsKey("AICc"))
        assertFalse(persistedMetrics.containsKey("Undefined"))

        // 回归真实故障：过滤后的快照必须能被项目当前使用的默认 Gson 正常序列化。
        assertEquals(
            "{\"R²\":0.9946,\"RMSE\":0.760219}",
            Gson().toJson(persistedMetrics)
        )
    }
}
