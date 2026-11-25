package com.muc.fluocolorquant.ui.components

import com.muc.fluocolorquant.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** 分析方式稳定编码与用户文案资源之间的契约测试。 */
class AnalysisMethodPresentationTest {

    @Test
    fun `当前生产编码都有明确文案且不会走未知兼容分支`() {
        val expected = mapOf(
            "DL_MODEL" to R.string.deep_learning_analysis,
            "CURVE_FIT" to R.string.curve_fitting_analysis,
            "SIGNAL_ONLY" to R.string.grid_quant_mode_signal_only,
            "TEMPLATE_MANAGED" to R.string.analysis_method_template_managed,
            "LSPR_SPECTRUM" to R.string.lspr_spectrum_analysis,
            "ONSITE_CALIBRATION" to R.string.grid_quant_onsite_dialog_title
        )

        expected.forEach { (stableCode, expectedResource) ->
            assertEquals(expectedResource, analysisMethodLabelResource(stableCode))
        }
    }

    @Test
    fun `兼容编码归并到相同科学语义`() {
        assertEquals(
            R.string.deep_learning_analysis,
            analysisMethodLabelResource("DEEP_LEARNING_MODEL")
        )
        assertEquals(
            R.string.curve_fitting_analysis,
            analysisMethodLabelResource("EXISTING_STANDARD_CURVE")
        )
        assertEquals(
            R.string.analysis_method_template_managed,
            analysisMethodLabelResource("TEMPLATE_MODEL")
        )
        assertEquals(
            R.string.grid_quant_onsite_dialog_title,
            analysisMethodLabelResource("ONSITE_AUTO_FIT")
        )
    }

    @Test
    fun `未知或空编码只显示历史兼容文案资源`() {
        assertEquals(R.string.analysis_method_historical, analysisMethodLabelResource("FUTURE_CODE"))
        assertEquals(R.string.analysis_method_historical, analysisMethodLabelResource(" "))
        assertEquals(R.string.analysis_method_historical, analysisMethodLabelResource(null))
    }
}
