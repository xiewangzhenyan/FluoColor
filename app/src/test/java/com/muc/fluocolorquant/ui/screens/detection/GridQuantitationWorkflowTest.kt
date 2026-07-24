package com.muc.fluocolorquant.ui.screens.detection

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridOnsiteFitPreview
import com.muc.fluocolorquant.domain.detection.isReadyForConfirmation
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

        val fitted = initial.copy(
            onsitePreview = GridOnsiteFitPreview(
                analyteId = "ca125",
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                function = FittingFunction.LINEAR,
                parameters = mapOf("a" to 1.0, "b" to 0.0),
                standardPoints = listOf(0.0 to 0.0, 10.0 to 10.0),
                curvePoints = listOf(0.0 to 0.0, 10.0 to 10.0),
                latexFormula = "y=x",
                rSquared = 1.0,
                rmse = 0.0,
                mae = 0.0,
                acceptedStandardRatio = 1.0,
                accepted = true
            )
        )
        assertTrue(fitted.isReadyForConfirmation())
        assertFalse(fitted.copy(fittingInProgress = true).isReadyForConfirmation())
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
}
