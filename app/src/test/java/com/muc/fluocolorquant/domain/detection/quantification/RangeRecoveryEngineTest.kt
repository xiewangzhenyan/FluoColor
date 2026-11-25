package com.muc.fluocolorquant.domain.detection.quantification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动态量程复核必须触发诊断，但不能利用未知样品分布伪造区间内浓度。 */
class RangeRecoveryEngineTest {

    @Test
    fun `六个样品中一半越界时触发复核但无质控锚点不生成校正`() {
        val observations = listOf(
            sample(0, 10.0),
            sample(1, 20.0),
            sample(2, 30.0),
            sample(3, null, ReliableRangeStatus.ABOVE_PROJECT_RANGE),
            sample(4, null, ReliableRangeStatus.ABOVE_PROJECT_RANGE),
            sample(5, null, ReliableRangeStatus.ABOVE_PROJECT_RANGE)
        )

        val decision = RangeRecoveryEngine.evaluate(
            observations = observations,
            projectMinimum = 0.0,
            projectMaximum = 100.0
        )

        assertEquals(RangeRecoveryStatus.TRIGGERED_REVIEW_REQUIRED, decision.status)
        assertEquals(RangeRecoveryReason.INDEPENDENT_CONTROL_REQUIRED, decision.reason)
        assertEquals(0.5, decision.outOfRangeRatio, 0.0)
        assertNull(decision.correctionScale)
        assertNull(decision.correct(120.0))
    }

    @Test
    fun `标准孔和质控孔不进入未知样品越界覆盖率`() {
        val observations = buildList {
            repeat(5) { index -> add(sample(index, 20.0 + index)) }
            add(sample(5, null, ReliableRangeStatus.ABOVE_PROJECT_RANGE))
            repeat(20) { index ->
                add(
                    RangeRecoveryObservation(
                        siteIndex = 100 + index,
                        isSample = false,
                        concentration = null,
                        lowerBound = 100.0,
                        upperBound = null,
                        rangeStatus = ReliableRangeStatus.ABOVE_PROJECT_RANGE,
                        quantificationState = QuantificationState.BOUND_ONLY
                    )
                )
            }
        }

        val decision = RangeRecoveryEngine.evaluate(observations, 0.0, 100.0)

        assertEquals(RangeRecoveryStatus.NOT_TRIGGERED, decision.status)
        assertEquals(6, decision.validSampleCount)
        assertEquals(1, decision.aboveRangeCount)
    }

    @Test
    fun `通过独立质控锚点后才产生可重建的稳健校正`() {
        val observations = (0 until 6).map { index ->
            sample(index, null, ReliableRangeStatus.ABOVE_PROJECT_RANGE)
        }

        val decision = RangeRecoveryEngine.evaluate(
            observations = observations,
            projectMinimum = 0.0,
            projectMaximum = 100.0,
            anchors = listOf(
                RangeCorrectionAnchor(expectedConcentration = 20.0, observedConcentration = 10.0),
                RangeCorrectionAnchor(expectedConcentration = 80.0, observedConcentration = 40.0)
            )
        )

        assertEquals(RangeRecoveryStatus.CORRECTION_APPLIED, decision.status)
        assertEquals(2.0, requireNotNull(decision.correctionScale), 1e-12)
        assertEquals(60.0, requireNotNull(decision.correct(30.0)), 1e-12)
        assertTrue(decision.toSnapshot().containsKey("algorithmVersion"))
    }

    private fun sample(
        siteIndex: Int,
        concentration: Double?,
        status: ReliableRangeStatus = ReliableRangeStatus.WITHIN_RANGE
    ): RangeRecoveryObservation = RangeRecoveryObservation(
        siteIndex = siteIndex,
        isSample = true,
        concentration = concentration,
        lowerBound = null,
        upperBound = null,
        rangeStatus = status,
        quantificationState = if (concentration == null) {
            QuantificationState.BOUND_ONLY
        } else {
            QuantificationState.QUANTIFIED
        }
    )
}
