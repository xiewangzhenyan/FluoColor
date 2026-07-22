package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.ui.screens.project.DirectProjectFormState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新建项目页面必须在展示下拉项前完成完整兼容筛选。 */
class DirectProjectModelMatchingTest {

    @Test
    fun `只返回分析物模式单位载体处理器和特征全部兼容的曲线`() {
        val compatible = model(id = "compatible")
        val wrongUnit = model(id = "wrong-unit").copy(concentrationUnit = "pg/mL")
        val wrongCarrier = model(id = "wrong-carrier").copy(
            compatibleCarrierTypesJson = "[\"PLATE\"]"
        )
        val wrongProcessor = model(id = "wrong-processor").copy(processorVersion = "v0")
        val wrongFeature = model(id = "wrong-feature").copy(
            primaryFeature = AnalysisPrimaryFeature.RED_INTENSITY.code
        )

        val state = DirectProjectUiState(
            analysisModels = listOf(
                compatible,
                wrongUnit,
                wrongCarrier,
                wrongProcessor,
                wrongFeature
            ),
            form = DirectProjectFormState(
                detectionModality = DetectionModality.FLUORESCENCE,
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
                selectedAnalyteId = "cea",
                concentrationUnit = "ng/mL"
            )
        )

        assertEquals(listOf("compatible"), state.compatibleModels.map(AnalysisModel::id))
    }

    private fun model(id: String): AnalysisModel = AnalysisModel(
        id = id,
        name = id,
        modelType = AnalysisModelType.STANDARD_CURVE.code,
        analyteId = "cea",
        detectionMode = DetectionModality.FLUORESCENCE.code,
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
        primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
        processorName = "fluorescence-photometry",
        processorVersion = "v1",
        compatibleCarrierTypesJson = "[\"${CarrierType.MICROFLUIDIC_CHIP.code}\"]",
        compatibleAcquisitionProfileIdsJson = "[]",
        concentrationUnit = "ng/mL",
        reliableRangeMin = 0.1,
        reliableRangeMax = 100.0,
        status = AnalysisModelLifecycleStatus.PUBLISHED.code
    )
}
