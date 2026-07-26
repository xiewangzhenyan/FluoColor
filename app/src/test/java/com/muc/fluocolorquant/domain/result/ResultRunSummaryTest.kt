package com.muc.fluocolorquant.domain.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 运行摘要的统计契约。
 *
 * 这里锁的不是"好坏判定"——摘要不做判定；锁的是"不能凭空造出数字"：无定义的 CV 不得
 * 当成 0、非有限值不得进入统计、缺失项必须保持为 null 让 UI 隐藏而不是显示 0。
 */
class ResultRunSummaryTest {

    private fun summary(
        rSquared: Double? = 0.9987,
        concentrations: List<Double> = listOf(1.0, 2.0, 3.0),
        repeatGroups: List<List<Double>> = emptyList(),
        evaluatedCount: Int = 96,
        outsideProjectRangeCount: Int = 0,
        extrapolatedCount: Int = 0
    ) = computeResultRunSummary(
        rSquared = rSquared,
        concentrations = concentrations,
        repeatGroups = repeatGroups,
        evaluatedCount = evaluatedCount,
        outsideProjectRangeCount = outsideProjectRangeCount,
        extrapolatedCount = extrapolatedCount
    )

    @Test
    fun `如实保留传入的计数与R方`() {
        val result = summary(outsideProjectRangeCount = 31, extrapolatedCount = 4)
        assertEquals(0.9987, result.rSquared!!, 1e-9)
        assertEquals(31, result.outsideProjectRangeCount)
        assertEquals(4, result.extrapolatedCount)
    }

    @Test
    fun `超量程孔很多也不改变任何统计量`() {
        // 摘要不做判定：96 孔里 31 个超量程是实验状况，不是数据错误，
        // 统计量应与超量程数量无关。
        val few = summary(outsideProjectRangeCount = 1)
        val many = summary(outsideProjectRangeCount = 31)
        assertEquals(few.rSquared, many.rSquared)
        assertEquals(few.concentrationMedian, many.concentrationMedian)
    }

    @Test
    fun `没有标准曲线时R方为空而不是零`() {
        assertNull(summary(rSquared = null).rSquared)
    }

    @Test
    fun `非有限的R方视为缺失`() {
        assertNull(summary(rSquared = Double.NaN).rSquared)
    }

    @Test
    fun `均值为零的重复孔组不计入CV而不是记为零`() {
        // 均值为 0 时 CV 无定义。当成 0 会让"完全测不出信号"的孔显示成完美重复。
        assertNull(summary(repeatGroups = listOf(listOf(0.0, 0.0, 0.0))).repeatCvPercent)
    }

    @Test
    fun `单个值的重复孔组不参与CV计算`() {
        assertNull(summary(repeatGroups = listOf(listOf(10.0))).repeatCvPercent)
    }

    @Test
    fun `多组重复孔取各组CV的平均`() {
        // 两组各自 CV 相同，平均值应等于单组值。
        val single = summary(repeatGroups = listOf(listOf(9.0, 11.0)))
        val double = summary(repeatGroups = listOf(listOf(9.0, 11.0), listOf(18.0, 22.0)))
        assertEquals(single.repeatCvPercent!!, double.repeatCvPercent!!, 1e-9)
    }

    @Test
    fun `浓度范围与中位数只统计有限值`() {
        val result = summary(
            concentrations = listOf(3.0, 1.0, Double.NaN, 5.0, Double.POSITIVE_INFINITY)
        )
        assertEquals(1.0, result.concentrationMinimum!!, 1e-9)
        assertEquals(5.0, result.concentrationMaximum!!, 1e-9)
        assertEquals(3.0, result.concentrationMedian!!, 1e-9)
    }

    @Test
    fun `偶数个浓度取中间两值均值`() {
        assertEquals(2.5, summary(concentrations = listOf(1.0, 2.0, 3.0, 4.0)).concentrationMedian!!, 1e-9)
    }

    @Test
    fun `没有浓度时范围为空而不是零`() {
        val result = summary(concentrations = emptyList())
        assertNull(result.concentrationMinimum)
        assertNull(result.concentrationMaximum)
        assertNull(result.concentrationMedian)
    }
}
