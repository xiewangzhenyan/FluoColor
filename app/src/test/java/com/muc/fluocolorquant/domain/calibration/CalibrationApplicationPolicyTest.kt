package com.muc.fluocolorquant.domain.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证低质量曲线的三种应用策略不会被UI或调用入口绕过。 */
class CalibrationApplicationPolicyTest {

    @Test
    fun `合格候选不受低质量策略限制`() {
        val policy = CalibrationPolicy.DEFAULT.copy(
            lowQualityAction = LowQualityCalibrationAction.VIEW_ONLY
        )

        assertEquals(
            CalibrationApplicationDecision.APPLY,
            policy.applicationDecision(candidateAccepted = true)
        )
    }

    @Test
    fun `提示但允许策略可直接应用低质量候选`() {
        val policy = CalibrationPolicy.DEFAULT.copy(
            lowQualityAction = LowQualityCalibrationAction.WARN_AND_ALLOW
        )

        assertEquals(
            CalibrationApplicationDecision.APPLY,
            policy.applicationDecision(candidateAccepted = false)
        )
    }

    @Test
    fun `二次确认策略只有明确确认后才允许应用`() {
        val policy = CalibrationPolicy.DEFAULT.copy(
            lowQualityAction = LowQualityCalibrationAction.REQUIRE_CONFIRMATION
        )

        assertEquals(
            CalibrationApplicationDecision.REQUIRE_CONFIRMATION,
            policy.applicationDecision(candidateAccepted = false)
        )
        assertEquals(
            CalibrationApplicationDecision.APPLY,
            policy.applicationDecision(candidateAccepted = false, userConfirmed = true)
        )
    }

    @Test
    fun `仅查看策略始终阻止低质量候选进入定量`() {
        val policy = CalibrationPolicy.DEFAULT.copy(
            lowQualityAction = LowQualityCalibrationAction.VIEW_ONLY
        )

        assertEquals(
            CalibrationApplicationDecision.BLOCK,
            policy.applicationDecision(candidateAccepted = false, userConfirmed = true)
        )
    }
}
