package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区域假设仲裁的门控边界测试。
 *
 * 这些规则决定“换不换主区域”，一旦放松就会让噪声证据推翻按可靠性排序的首选，
 * 因此每条保护都必须有明确的边界用例。
 */
class PgGridRegionArbiterTest {

    private data class Hypothesis(val name: String, val score: Double)

    @Test
    fun `评分按权重融合三项证据并按残差扣分`() {
        val score = PgGridRegionArbiter.score(
            coverage = 1.0,
            support = 1.0,
            observed = 1.0,
            residualRatio = 0.0
        )
        assertEquals(1.0, score, 1e-12)

        val penalized = PgGridRegionArbiter.score(
            coverage = 1.0,
            support = 1.0,
            observed = 1.0,
            residualRatio = 0.2
        )
        assertEquals(0.8, penalized, 1e-12)
    }

    @Test
    fun `残差扣分存在上限不会把其余证据完全淹没`() {
        val extreme = PgGridRegionArbiter.score(
            coverage = 1.0,
            support = 1.0,
            observed = 1.0,
            residualRatio = 100.0
        )
        assertEquals(1.0 - PgGridRegionArbiter.RESIDUAL_PENALTY_CAP, extreme, 1e-12)
    }

    @Test
    fun `首选本身最优时直接采用`() {
        val first = Hypothesis("bright", 0.90)
        val candidates = listOf(first, Hypothesis("substrate", 0.70))
        assertSame(first, PgGridRegionArbiter.select(candidates, Hypothesis::score))
    }

    @Test
    fun `领先不足最小分差时保留首选`() {
        val first = Hypothesis("bright", 0.70)
        // 0.77 - 0.70 = 0.07 < 0.08，属于噪声级领先，不足以支持切换。
        val candidates = listOf(first, Hypothesis("substrate", 0.77))
        assertSame(first, PgGridRegionArbiter.select(candidates, Hypothesis::score))
    }

    @Test
    fun `领先达到最小分差且证据充分时才切换`() {
        val first = Hypothesis("bright", 0.60)
        // 刻意不取 first + WIN_MARGIN：浮点误差会让边界值落到门限之下，
        // 那样测的是浮点行为而不是门控语义。
        val better = Hypothesis("substrate", 0.75)
        assertTrue(better.score - first.score > PgGridRegionArbiter.WIN_MARGIN)
        assertTrue(better.score >= PgGridRegionArbiter.MINIMUM_EVIDENCE)
        assertSame(better, PgGridRegionArbiter.select(listOf(first, better), Hypothesis::score))
    }

    @Test
    fun `所有假设都低于最低证据线时不切换`() {
        val first = Hypothesis("bright", 0.10)
        // 领先量足够大，但最优分仍低于最低证据线：这是“证据缺席”而不是“证据为负”，
        // 切换属于赌博，必须保留按可靠性排序的首选。
        val best = Hypothesis("dots", 0.54)
        assertTrue(best.score - first.score >= PgGridRegionArbiter.WIN_MARGIN)
        assertTrue(best.score < PgGridRegionArbiter.MINIMUM_EVIDENCE)
        assertSame(first, PgGridRegionArbiter.select(listOf(first, best), Hypothesis::score))
    }

    @Test
    fun `空假设列表明确失败而不是静默返回`() {
        val error = runCatching {
            PgGridRegionArbiter.select(emptyList<Hypothesis>(), Hypothesis::score)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
