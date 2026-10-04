package com.muc.fluocolorquant.ui.screens.detection

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidate
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidateStatus
import com.muc.fluocolorquant.domain.calibration.CalibrationFunctionResult
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.OnsiteCalibrationState
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.acceptOnsiteFitResult
import com.muc.fluocolorquant.domain.detection.isReadyForConfirmation
import com.muc.fluocolorquant.domain.detection.rejectOnsiteFitResult
import com.muc.fluocolorquant.domain.detection.restoreOnsiteReviewingAfterApplyFailure
import com.muc.fluocolorquant.domain.detection.startOnsiteApplying
import com.muc.fluocolorquant.domain.detection.startOnsiteFitting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单分析物定量工作台的纯逻辑回归，避免完成门控和浓度边界在后续 UI 调整中漂移。 */
class GridQuantitationWorkflowTest {

    @Test
    fun `仅信号可直接确认而资源模式必须先选择资源`() {
        assertTrue(
            GridAnalyteQuantitationDraft(
                analyteId = "cea",
                mode = GridAnalyteQuantitationMode.SIGNAL_ONLY
            ).isReadyForConfirmation()
        )
        assertFalse(
            GridAnalyteQuantitationDraft(
                analyteId = "cea",
                mode = GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE
            ).isReadyForConfirmation()
        )
        assertTrue(
            GridAnalyteQuantitationDraft(
                analyteId = "cea",
                mode = GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
                selectedAnalysisModelId = "curve-1"
            ).isReadyForConfirmation()
        )
    }

    @Test
    fun `现场拟合只有生成有效预览后才允许确认`() {
        val initial = GridAnalyteQuantitationDraft(
            analyteId = "ca125",
            mode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
        )
        assertFalse(initial.isReadyForConfirmation())

        val resultSet = onsiteResultSet()
        val fitted = initial.copy(
            onsiteState = OnsiteCalibrationState.Reviewing(resultSet)
        )
        assertTrue(fitted.isReadyForConfirmation())
        assertFalse(
            fitted.copy(
                onsiteState = OnsiteCalibrationState.Fitting("request", "fingerprint")
            ).isReadyForConfirmation()
        )
    }

    @Test
    fun `现场拟合状态机丢弃迟到回调并在应用失败后恢复同一候选`() {
        val initial = GridAnalyteQuantitationDraft(
            analyteId = "ca125",
            mode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
        )
        val firstRequest = initial.startOnsiteFitting("request-1")
        val secondRequest = firstRequest.startOnsiteFitting("request-2")
        val resultSet = onsiteResultSet()

        // request-1 即使最后才返回，也不能覆盖用户已经发起的 request-2。
        assertNull(
            secondRequest.acceptOnsiteFitResult(
                requestId = "request-1",
                resultSet = resultSet,
                saveToLibraryByDefault = true
            )
        )
        assertNull(secondRequest.rejectOnsiteFitResult("request-1"))

        val reviewing = requireNotNull(
            secondRequest.acceptOnsiteFitResult(
                requestId = "request-2",
                resultSet = resultSet,
                saveToLibraryByDefault = true
            )
        )
        val applying = requireNotNull(reviewing.startOnsiteApplying())
        val restored = requireNotNull(
            applying.restoreOnsiteReviewingAfterApplyFailure("net:linear:0")
        )
        val restoredState = restored.onsiteState as OnsiteCalibrationState.Reviewing

        assertEquals("net:linear:0", restoredState.selectedCandidateId)
        assertTrue(restoredState.saveToLibrary)
        assertNull(restored.appliedSnapshot)
    }

    @Test
    fun `标准浓度拒绝负数非有限值和超过项目上限的输入`() {
        assertEquals(0.0, parseStandardConcentration("0", maximum = 100.0)!!, 0.0)
        assertEquals(100.0, parseStandardConcentration("100", maximum = 100.0)!!, 0.0)
        assertNull(parseStandardConcentration("-1", maximum = 100.0))
        assertNull(parseStandardConcentration("100.01", maximum = 100.0))
        assertNull(parseStandardConcentration("NaN", maximum = 100.0))
        assertNull(parseStandardConcentration("", maximum = 100.0))
    }

    /** 统一构造可执行候选，状态机测试只关心请求身份与状态转换，不重复验证拟合算法。 */
    private fun onsiteResultSet(): CalibrationResultSet {
        val candidate = CalibrationCandidate(
            id = "net:linear:0",
            analyteId = "ca125",
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            function = FittingFunction.LINEAR,
            parameters = mapOf("a" to 1.0, "b" to 0.0),
            standardPoints = listOf(0.0 to 0.0, 10.0 to 10.0),
            curvePoints = listOf(0.0 to 0.0, 10.0 to 10.0),
            latexFormula = "y=x",
            rSquared = 1.0,
            rmse = 0.0,
            normalizedRmse = 0.0,
            mae = 0.0,
            backCalculatedRmsePercent = 0.0,
            acceptedStandardRatio = 1.0,
            weightingCode = 0,
            accepted = true,
            status = CalibrationCandidateStatus.AVAILABLE
        )
        return CalibrationResultSet(
            analyteId = "ca125",
            inputFingerprint = "fingerprint",
            policySnapshot = CalibrationPolicy.DEFAULT,
            functionResults = listOf(
                CalibrationFunctionResult(FittingFunction.LINEAR, candidate)
            ),
            recommendedCandidateId = candidate.id,
            processorVersion = "test",
            engineVersion = "test"
        )
    }
}
