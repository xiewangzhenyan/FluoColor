package com.muc.fluocolorquant.ui.screens.settings.analysis

import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一分析模型表单的纯 JVM 规则测试。
 *
 * 这些约束决定模型能否发布并用于浓度计算，必须脱离 Compose 页面独立验证，避免
 * 页面筛选正常但底层仍把比色、荧光或光谱模型混用。
 */
class AnalysisModelFormStateTest {

    @Test
    fun `新模型默认不绑定通用未标定采集档案`() {
        val draft = AnalysisModelDraft()

        assertTrue(draft.compatibleAcquisitionProfileIds.isEmpty())
        assertFalse(
            AnalysisModelFormError.ACQUISITION_PROFILE_REQUIRED in
                completeStandardCurveDraft()
                    .copy(compatibleAcquisitionProfileIds = emptySet())
                    .validateForPublication()
        )
    }

    @Test
    fun `比色和荧光只允许终点输入协议`() {
        val colorimetric = completeStandardCurveDraft().copy(
            detectionMode = DetectionModality.COLORIMETRIC.code
        )
        val fluorescence = completeStandardCurveDraft().copy(
            detectionMode = DetectionModality.FLUORESCENCE.code
        )

        assertEquals(setOf(InputProtocol.ENDPOINT_ONLY.code), colorimetric.allowedProtocols)
        assertEquals(setOf(InputProtocol.ENDPOINT_ONLY.code), fluorescence.allowedProtocols)
    }

    @Test
    fun `光谱主特征必须和单图或LSPR协议匹配`() {
        val invalidSingleSpectrum = completeStandardCurveDraft().copy(
            detectionMode = DetectionModality.SPECTRUM.code,
            inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM.code
        )
        val validLspr = invalidSingleSpectrum.copy(
            inputProtocol = InputProtocol.LSPR_PAIRED_QUANTIFICATION.code
        )

        assertTrue(
            AnalysisModelFormError.PRIMARY_FEATURE_INCOMPATIBLE in
                invalidSingleSpectrum.validateForDraft()
        )
        assertFalse(
            AnalysisModelFormError.PRIMARY_FEATURE_INCOMPATIBLE in
                validLspr.validateForDraft()
        )
    }

    @Test
    fun `可靠范围上限必须大于下限`() {
        val draft = completeStandardCurveDraft().copy(
            reliableRangeMinInput = "10",
            reliableRangeMaxInput = "1"
        )

        assertTrue(AnalysisModelFormError.RELIABLE_RANGE_INVALID in draft.validateForDraft())
    }

    @Test
    fun `发布校验仍要求载体但允许手机自动采集元数据`() {
        val draft = completeStandardCurveDraft().copy(
            compatibleCarrierTypes = emptySet(),
            compatibleAcquisitionProfileIds = emptySet()
        )

        val errors = draft.validateForPublication()

        assertTrue(AnalysisModelFormError.CARRIER_REQUIRED in errors)
        assertFalse(AnalysisModelFormError.ACQUISITION_PROFILE_REQUIRED in errors)
    }

    @Test
    fun `智能模型发布时要求校验值输入尺寸和训练数据版本`() {
        val draft = completeStandardCurveDraft().copy(
            modelType = AnalysisModelType.DEEP_LEARNING,
            modelFileName = "model.pte",
            checksumSha256 = "bad-checksum",
            inputWidthInput = "0",
            inputHeightInput = "224",
            normalizationJson = "{}",
            trainingDataVersion = ""
        )

        val errors = draft.validateForPublication()

        assertTrue(AnalysisModelFormError.CHECKSUM_INVALID in errors)
        assertTrue(AnalysisModelFormError.INPUT_SIZE_INVALID in errors)
        assertTrue(AnalysisModelFormError.TRAINING_DATA_VERSION_REQUIRED in errors)
    }

    private fun completeStandardCurveDraft(): AnalysisModelDraft = AnalysisModelDraft(
        name = "CEA 比色标准曲线",
        modelType = AnalysisModelType.STANDARD_CURVE,
        analyteId = "analyte-cea",
        detectionMode = DetectionModality.COLORIMETRIC.code,
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
        primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
        processorName = "ColorimetricProcessor",
        processorVersion = "1.0.0",
        concentrationUnit = "ng/mL",
        reliableRangeMinInput = "0.1",
        reliableRangeMaxInput = "100",
        compatibleCarrierTypes = setOf(CarrierType.MICROFLUIDIC_CHIP.code),
        compatibleAcquisitionProfileIds = setOf("device-v1"),
        fittingFunction = "linear",
        parametersJson = "{\"a\":1.0,\"b\":0.0}"
    )
}
