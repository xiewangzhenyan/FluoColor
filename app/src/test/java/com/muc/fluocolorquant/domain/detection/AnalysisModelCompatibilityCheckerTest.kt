package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分析模型兼容性必须逐字段严格失败，不能通过“最接近”的通用模型继续定量。 */
class AnalysisModelCompatibilityCheckerTest {

    @Test
    fun `全部科学配置一致时模型兼容`() {
        val result = AnalysisModelCompatibilityChecker.check(
            model = compatibleModel(),
            request = compatibleRequest()
        )

        assertEquals(ModelCompatibilityResult.Compatible, result)
    }

    @Test
    fun `模态协议主特征载体设备和处理器不匹配时返回全部原因`() {
        val result = AnalysisModelCompatibilityChecker.check(
            model = compatibleModel().copy(
                status = AnalysisModelLifecycleStatus.DRAFT.code,
                analyteId = "other-analyte",
                detectionMode = DetectionModality.COLORIMETRIC.code,
                inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                primaryFeature = AnalysisPrimaryFeature.OPTICAL_DENSITY.code,
                processorName = "colorimetric-photometry",
                processorVersion = "v9",
                compatibleCarrierTypesJson = "[\"PLATE\"]",
                compatibleAcquisitionProfileIdsJson = "[\"other-device\"]"
            ),
            request = compatibleRequest()
        )

        assertTrue(result is ModelCompatibilityResult.Incompatible)
        result as ModelCompatibilityResult.Incompatible
        assertEquals(
            setOf(
                ModelCompatibilityReason.MODEL_NOT_PUBLISHED,
                ModelCompatibilityReason.ANALYTE_MISMATCH,
                ModelCompatibilityReason.MODALITY_MISMATCH,
                ModelCompatibilityReason.INPUT_PROTOCOL_MISMATCH,
                ModelCompatibilityReason.PRIMARY_FEATURE_MISMATCH,
                ModelCompatibilityReason.CARRIER_TYPE_MISMATCH,
                ModelCompatibilityReason.ACQUISITION_PROFILE_MISMATCH,
                ModelCompatibilityReason.PROCESSOR_NAME_MISMATCH,
                ModelCompatibilityReason.PROCESSOR_VERSION_MISMATCH
            ),
            result.reasons
        )
    }

    @Test
    fun `兼容范围JSON损坏时明确拒绝模型`() {
        val result = AnalysisModelCompatibilityChecker.check(
            model = compatibleModel().copy(compatibleCarrierTypesJson = "not-json"),
            request = compatibleRequest()
        )

        assertTrue(result is ModelCompatibilityResult.Incompatible)
        result as ModelCompatibilityResult.Incompatible
        assertTrue(ModelCompatibilityReason.INVALID_COMPATIBILITY_METADATA in result.reasons)
    }

    private fun compatibleModel(): AnalysisModel {
        return AnalysisModel(
            id = "model-1",
            name = "CEA fluorescence",
            modelType = "STANDARD_CURVE",
            analyteId = "cea",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-1\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
    }

    private fun compatibleRequest(): ModelCompatibilityRequest {
        return ModelCompatibilityRequest(
            analyteId = "cea",
            modality = DetectionModality.FLUORESCENCE,
            inputProtocol = InputProtocol.ENDPOINT_ONLY,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            carrierType = CarrierType.MICROFLUIDIC_CHIP,
            acquisitionProfileId = "device-1",
            processorName = "fluorescence-photometry",
            processorVersion = "v1"
        )
    }
}
