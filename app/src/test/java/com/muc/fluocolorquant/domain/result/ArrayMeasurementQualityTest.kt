package com.muc.fluocolorquant.domain.result

import org.junit.Assert.assertEquals
import org.junit.Test

/** 三级质量语义必须由结果页、PDF 和 CSV 共享，防止同一位点在不同出口显示不同结论。 */
class ArrayMeasurementQualityTest {

    @Test
    fun `普通光度标志和低信号不会覆盖冻结的可靠性结论`() {
        val level = resolveArrayMeasurementQuality(
            displayValue = 32.0,
            qualityReliable = true,
            saturationRatio = 0.08,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0
        )

        assertEquals(ArrayMeasurementQualityLevel.VALID, level)
    }

    @Test
    fun `曲线外推属于范围状态而不是测量质量复核`() {
        val level = resolveArrayMeasurementQuality(
            displayValue = 82.0,
            qualityReliable = true,
            saturationRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0
        )

        assertEquals(ArrayMeasurementQualityLevel.VALID, level)
    }

    @Test
    fun `严重饱和或冻结可靠性失败才进入建议复核`() {
        val severeSaturation = resolveArrayMeasurementQuality(
            displayValue = 50.0,
            qualityReliable = true,
            saturationRatio = 0.30,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0
        )
        val unreliableMeasurement = resolveArrayMeasurementQuality(
            displayValue = 35.0,
            qualityReliable = false,
            saturationRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0
        )

        assertEquals(ArrayMeasurementQualityLevel.REVIEW, severeSaturation)
        assertEquals(ArrayMeasurementQualityLevel.REVIEW, unreliableMeasurement)
    }

    @Test
    fun `当前显示维度没有有限值时才判为不可用`() {
        val level = resolveArrayMeasurementQuality(
            displayValue = null,
            qualityReliable = false,
            saturationRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0
        )

        assertEquals(ArrayMeasurementQualityLevel.UNAVAILABLE, level)
    }
}
