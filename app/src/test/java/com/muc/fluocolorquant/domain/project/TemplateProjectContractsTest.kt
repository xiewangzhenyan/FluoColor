package com.muc.fluocolorquant.domain.project

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 模板项目不可变快照契约测试。
 *
 * 这里刻意使用完整的 10×10 位点集合，确保 JSON 往返不是只验证少量示例字段，
 * 而是能承载真实微流控模板在项目创建时需要冻结的全部阵列信息。
 */
class TemplateProjectContractsTest {

    @Test
    fun `模板快照往返后保留模板载体设备模型和全部位点`() {
        val json = TemplateProjectSnapshotCodec.encode(snapshot())
        val decoded = TemplateProjectSnapshotCodec.decode(json)

        assertEquals(TemplateProjectSnapshot.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("template-v2", decoded.template.id)
        assertEquals("carrier-10x10", decoded.carrierProfile.id)
        assertEquals("device-v1", decoded.acquisitionProfile.id)
        assertEquals("model-cea", decoded.analytes.single().analysisModel.model.id)
        assertEquals(100, decoded.siteAssignments.size)
        assertEquals("R01C01", decoded.siteAssignments.first().defaultSampleSlot)
    }

    @Test
    fun `项目覆盖往返后保留样本槽位和覆盖原因`() {
        val override = TemplateProjectOverrideSnapshot(
            sampleSlotMapping = linkedMapOf(
                "R01C01" to "sample-001",
                "R01C02" to "sample-002"
            ),
            reasons = mapOf("R01C02" to "样本管标签与模板默认值不同")
        )

        val decoded = TemplateProjectOverrideCodec.decode(
            TemplateProjectOverrideCodec.encode(override)
        )

        assertEquals("sample-001", decoded.sampleSlotMapping["R01C01"])
        assertEquals("样本管标签与模板默认值不同", decoded.reasons["R01C02"])
    }

    @Test
    fun `现场标定多选信号和函数经过快照往返后保持顺序与内容`() {
        val source = snapshot().let { original ->
            original.copy(
                analytes = original.analytes.map { analyte ->
                    analyte.copy(
                        onsiteSelectedFeatures = listOf(
                            AnalysisPrimaryFeature.GRAY_LUMINOSITY.code,
                            AnalysisPrimaryFeature.GREEN_INTENSITY.code,
                            AnalysisPrimaryFeature.CIE_A_STAR.code
                        ),
                        onsiteSelectedFunctions = listOf(
                            FittingFunction.LINEAR.identifier,
                            FittingFunction.QUADRATIC.identifier,
                            FittingFunction.RODBARD.identifier
                        )
                    )
                }
            )
        }

        val restored = TemplateProjectSnapshotCodec.decode(
            TemplateProjectSnapshotCodec.encode(source)
        ).analytes.single()

        assertEquals(source.analytes.single().onsiteSelectedFeatures, restored.onsiteSelectedFeatures)
        assertEquals(source.analytes.single().onsiteSelectedFunctions, restored.onsiteSelectedFunctions)
    }

    @Test
    fun `空白快照拒绝解码而不是静默返回默认配置`() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateProjectSnapshotCodec.decode("   ")
        }
    }

    @Test
    fun `检测路由严格区分阵列终点单图光谱和LSPR配对`() {
        assertEquals(
            ProjectDetectionDestination.GRID_ENDPOINT,
            ProjectDetectionRouter.resolve(
                detectionMode = DetectionModality.COLORIMETRIC.code,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code
            )
        )
        assertEquals(
            ProjectDetectionDestination.GRID_ENDPOINT,
            ProjectDetectionRouter.resolve(
                detectionMode = DetectionModality.FLUORESCENCE.code,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code
            )
        )
        assertEquals(
            ProjectDetectionDestination.SPECTRUM_SINGLE,
            ProjectDetectionRouter.resolve(
                detectionMode = DetectionModality.SPECTRUM.code,
                inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                readoutLayout = ReadoutLayout.SPECTRAL_TRACKS.code
            )
        )
        assertEquals(
            ProjectDetectionDestination.LSPR_PAIRED,
            ProjectDetectionRouter.resolve(
                detectionMode = DetectionModality.SPECTRUM.code,
                inputProtocol = InputProtocol.LSPR_PAIRED_QUANTIFICATION.code,
                readoutLayout = ReadoutLayout.SPECTRAL_TRACKS.code
            )
        )
        assertEquals(
            ProjectDetectionDestination.UNSUPPORTED,
            ProjectDetectionRouter.resolve(
                detectionMode = DetectionModality.COLORIMETRIC.code,
                inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code
            )
        )
    }

    private fun snapshot(): TemplateProjectSnapshot {
        val template = ExperimentTemplate(
            id = "template-v2",
            templateName = "CEA 10×10 比色芯片",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 0.0,
            concentrationUnit = "",
            defaultLayoutJson = null,
            version = 2,
            status = TemplateLifecycleStatus.PUBLISHED.code,
            carrierProfileId = "carrier-10x10",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = "device-v1",
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code
        )
        val carrier = CarrierProfile(
            id = "carrier-10x10",
            name = "10×10 微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.CIRCLE.code,
            status = ResourceStatus.ACTIVE.code
        )
        val acquisition = AcquisitionProfile(
            id = "device-v1",
            name = "实验室固定比色装置",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "FIXED_PROFILE",
            status = ResourceStatus.ACTIVE.code
        )
        val analyte = Analyte(id = "cea", name = "CEA")
        val templateConfig = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = template.id,
            analyteId = analyte.id,
            analysisModelId = "model-cea",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0
        )
        val analysisModel = AnalysisModel(
            id = "model-cea",
            name = "CEA ΔE 标准曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.COLORIMETRIC.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
            processorName = "ColorimetricProcessor",
            processorVersion = "1.0.0",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-v1\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        val sites = (0 until 10).flatMap { row ->
            (0 until 10).map { column ->
                TemplateSiteAssignment(
                    id = "site-$row-$column",
                    templateId = template.id,
                    rowIndex = row,
                    columnIndex = column,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = String.format("R%02dC%02d", row + 1, column + 1)
                )
            }
        }
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_753_000_000_000L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = analyte,
                    templateConfig = templateConfig,
                    analysisModel = AnalysisModelBundle(model = analysisModel)
                )
            ),
            siteAssignments = sites
        )
    }
}
