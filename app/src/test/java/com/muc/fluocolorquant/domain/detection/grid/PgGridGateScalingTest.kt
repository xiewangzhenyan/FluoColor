package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 几何闸值按行列数锚定的回归测试。
 *
 * 历史闸值全部在 15×15 上标定，隐含了 `count≈15` 的假设。这里固定两条科学契约：
 * 1. 参考规格 15×15 的闸值必须**逐值不变**（不能靠取整巧合）；
 * 2. 10×10 的真实间距必须落在接受区间内，否则轴拟合会整体退化为均分网格。
 */
class PgGridGateScalingTest {

    @Test
    fun `参考规格十五乘十五的闸值边长逐值不变`() {
        listOf(720.0, 800.0, 1200.0, 1600.0).forEach { side ->
            assertEquals(side, PgGridCandidateDetector.gateReferenceSide(side, 15), 1e-12)
        }
    }

    @Test
    fun `十乘十按间距反比放大闸值边长`() {
        // pitch 正比于 side/(count-1)，因此换算系数为 (15-1)/(10-1)。
        val expected = 800.0 * 14.0 / 9.0
        assertEquals(expected, PgGridCandidateDetector.gateReferenceSide(800.0, 10), 1e-9)
        assertTrue(PgGridCandidateDetector.gateReferenceSide(800.0, 10) > 800.0)
    }

    @Test
    fun `行列数缺失或过小时退化为按边长归一化的历史行为`() {
        assertEquals(800.0, PgGridCandidateDetector.gateReferenceSide(800.0, null), 1e-12)
        assertEquals(800.0, PgGridCandidateDetector.gateReferenceSide(800.0, 1), 1e-12)
    }

    @Test
    fun `名义间距与规则阵列生成约定一致`() {
        // 与 RegularGridGeometry.generate 的排布一致：可用跨度 = length*(1-2*margin)。
        val length = 1200.0
        val expected = length * (1.0 - 2.0 * PgGridCandidateDetector.REFERENCE_MARGIN_RATIO) / 14.0
        assertEquals(expected, PgGridCandidateDetector.expectedAxisPitch(length, 15), 1e-9)
    }

    @Test
    fun `十五乘十五的轴间距区间与历史边长公式等价`() {
        val length = 1200.0
        val bounds = PgGridCandidateDetector.axisPitchBounds(length, 15)
        // 历史写法为 [length*0.045, length*0.095]，在参考规格上必须逐值重合。
        assertEquals(length * 0.045, bounds.start, 1e-9)
        assertEquals(length * 0.095, bounds.endInclusive, 1e-9)
    }

    @Test
    fun `十乘十的真实间距落在新区间内而旧边长区间会拒绝它`() {
        val length = 800.0
        val bounds = PgGridCandidateDetector.axisPitchBounds(length, 10)
        val nominalPitch = PgGridCandidateDetector.expectedAxisPitch(length, 10)
        assertTrue("名义间距必须被接受", nominalPitch in bounds)

        // 主区域框外扩后间距会略大于名义值。旧的按边长区间上界只有 76，相对名义间距
        // 73.8 仅剩约 3% 余量，因此 5% 的正常外扩就会被旧区间拒绝、退化为均分网格；
        // 新区间按行列数定义，上界约 118，仍有充足余量。
        val expandedPitch = nominalPitch * 1.05
        assertTrue("外扩后的间距仍须被接受", expandedPitch in bounds)
        assertTrue("旧边长上界会拒绝外扩后的间距", expandedPitch > length * 0.095)
    }

    @Test
    fun `压缩晶格必须被下界拒绝`() {
        val length = 1200.0
        val bounds = PgGridCandidateDetector.axisPitchBounds(length, 15)
        // 半格压缩晶格照样落在真实孔上并拿到高支撑率，是最危险的静默失败，必须被下界挡住。
        val compressed = PgGridCandidateDetector.expectedAxisPitch(length, 15) / 2.0
        assertTrue(compressed !in bounds)
    }

    @Test
    fun `闸值行列数配对矫正图较短边`() {
        // 较短边对应的行列数才决定单元像素尺寸。
        assertEquals(10, PgGridCandidateDetector.gateCountFor(rows = 15, columns = 10, width = 800, height = 1200))
        assertEquals(15, PgGridCandidateDetector.gateCountFor(rows = 15, columns = 20, width = 1600, height = 1200))
        assertEquals(15, PgGridCandidateDetector.gateCountFor(rows = 15, columns = 15, width = 1200, height = 1200))
    }
}
