package com.muc.fluocolorquant.domain.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行质量裁决的判定契约。
 *
 * 这些用例锁住的是"结论不能比证据更乐观"：缺少曲线时不得判为良好、无定义的 CV 不得
 * 当成 0、任一指标不达标都必须能把整体判定拉低。
 */
class ResultQualitySummaryTest {

    private fun summary(
        rSquared: Double? = 0.999,
        concentrations: List<Double> = listOf(1.0, 2.0, 3.0),
        repeatGroups: List<List<Double>> = emptyList(),
        evaluatedCount: Int = 96,
        inRangeCount: Int = 96,
        retestCount: Int = 0,
        extrapolatedCount: Int = 0,
        thresholds: ResultQualityThresholds = ResultQualityThresholds.Default
    ) = computeResultQualitySummary(
        rSquared = rSquared,
        concentrations = concentrations,
        repeatGroups = repeatGroups,
        evaluatedCount = evaluatedCount,
        inRangeCount = inRangeCount,
        retestCount = retestCount,
        extrapolatedCount = extrapolatedCount,
        thresholds = thresholds
    )

    @Test
    fun `各项达标时判定为良好`() {
        val result = summary(rSquared = 0.9987, repeatGroups = listOf(listOf(10.0, 10.2, 9.9)))
        assertEquals(ResultQualityLevel.GOOD, result.level)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `缺少标准曲线时判定为证据不足而非良好`() {
        // 仅信号运行没有 R²。此时不能因为"其他指标都正常"就判为良好——
        // 没有曲线就没有浓度可言，结论必须如实呈现为无法判定。
        val result = summary(rSquared = null)
        assertEquals(ResultQualityLevel.UNKNOWN, result.level)
    }

    @Test
    fun `证据不足的判定不会被后续达标项改写回良好`() {
        val result = summary(
            rSquared = null,
            repeatGroups = listOf(listOf(10.0, 10.1)),
            retestCount = 0
        )
        assertEquals(ResultQualityLevel.UNKNOWN, result.level)
    }

    @Test
    fun `R方低于最低阈值判定为不建议使用`() {
        val result = summary(rSquared = 0.90)
        assertEquals(ResultQualityLevel.UNRELIABLE, result.level)
        assertTrue(result.issues.contains(ResultQualityIssue.LOW_R_SQUARED))
    }

    @Test
    fun `R方介于两阈值之间判定为待复核`() {
        val result = summary(rSquared = 0.97)
        assertEquals(ResultQualityLevel.REVIEW, result.level)
        assertTrue(result.issues.contains(ResultQualityIssue.LOW_R_SQUARED))
    }

    @Test
    fun `不建议使用的判定不会被其他达标项回升`() {
        val result = summary(
            rSquared = 0.80,
            repeatGroups = listOf(listOf(10.0, 10.05)),
            retestCount = 0,
            extrapolatedCount = 0
        )
        assertEquals(ResultQualityLevel.UNRELIABLE, result.level)
    }

    @Test
    fun `重复孔变异过大触发待复核`() {
        // 三个重复孔 5 / 10 / 15，CV 50%，远超 10% 上限。
        val result = summary(repeatGroups = listOf(listOf(5.0, 10.0, 15.0)))
        assertEquals(ResultQualityLevel.REVIEW, result.level)
        assertTrue(result.issues.contains(ResultQualityIssue.HIGH_REPEAT_CV))
    }

    @Test
    fun `均值为零的重复孔组不计入CV而不是记为零`() {
        // 均值为 0 时 CV 无定义。若把它当成 0，"完全测不出信号"的孔反而会显示成
        // 完美重复，这正是必须避免的伪造结论。
        val result = summary(repeatGroups = listOf(listOf(0.0, 0.0, 0.0)))
        assertNull(result.repeatCvPercent)
        assertTrue(result.issues.none { it == ResultQualityIssue.HIGH_REPEAT_CV })
    }

    @Test
    fun `单个值的重复孔组不参与CV计算`() {
        val result = summary(repeatGroups = listOf(listOf(10.0)))
        assertNull(result.repeatCvPercent)
    }

    @Test
    fun `需复测占比超限触发待复核`() {
        val result = summary(evaluatedCount = 96, retestCount = 20)
        assertEquals(ResultQualityLevel.REVIEW, result.level)
        assertTrue(result.issues.contains(ResultQualityIssue.HIGH_RETEST_RATIO))
    }

    @Test
    fun `外推占比超限触发待复核`() {
        val result = summary(evaluatedCount = 96, extrapolatedCount = 40)
        assertEquals(ResultQualityLevel.REVIEW, result.level)
        assertTrue(result.issues.contains(ResultQualityIssue.HIGH_EXTRAPOLATION_RATIO))
    }

    @Test
    fun `浓度范围与中位数按有限值统计`() {
        val result = summary(
            concentrations = listOf(3.0, 1.0, Double.NaN, 5.0, Double.POSITIVE_INFINITY)
        )
        assertEquals(1.0, result.concentrationMinimum!!, 1e-9)
        assertEquals(5.0, result.concentrationMaximum!!, 1e-9)
        assertEquals(3.0, result.concentrationMedian!!, 1e-9)
    }

    @Test
    fun `偶数个浓度取中间两值均值`() {
        val result = summary(concentrations = listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(2.5, result.concentrationMedian!!, 1e-9)
    }

    @Test
    fun `没有有效位点时在量程比例为空而不是零`() {
        // 0/0 不是 0。返回 null 让 UI 隐藏该项，而不是显示"0% 在量程内"。
        val result = summary(evaluatedCount = 0, inRangeCount = 0)
        assertNull(result.inRangeRatio)
    }

    @Test
    fun `阈值可由调用方覆盖`() {
        val strict = ResultQualityThresholds(
            goodRSquared = 0.9999,
            minimumRSquared = 0.999,
            maxRepeatCvPercent = 1.0,
            maxRetestRatio = 0.01,
            maxExtrapolationRatio = 0.01
        )
        val result = summary(rSquared = 0.999, thresholds = strict)
        assertEquals(ResultQualityLevel.REVIEW, result.level)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `良好阈值低于最低阈值时拒绝构造`() {
        ResultQualityThresholds(goodRSquared = 0.90, minimumRSquared = 0.99)
    }
}
