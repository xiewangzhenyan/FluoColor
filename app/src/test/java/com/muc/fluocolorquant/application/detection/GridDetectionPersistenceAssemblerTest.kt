package com.muc.fluocolorquant.application.detection

import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.domain.detection.GridGeometryPersistence
import com.muc.fluocolorquant.domain.detection.evidence.GridPersistedSourceInput
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceRecord
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化组装器的纯 JVM 契约测试。
 *
 * 测试固定“实际快照、长期原图、载体几何、过程证据和质量汇总”必须属于同一 runId；这些字段
 * 一旦写入历史记录就不可再由当前项目设置补算或替换。
 */
class GridDetectionPersistenceAssemblerTest {

    @Test
    fun `组装器冻结实际快照并优先引用长期原图与正确载体几何`() {
        val snapshot = snapshot()
        val project = Project(
            id = "project-1",
            name = "持久化测试",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            recognitionType = "AUTO",
            imageUri = "content://source/image",
            rows = 1,
            columns = 2,
            createTime = Date(1_000L),
            userId = "operator-1",
            lastRunTimestamp = null,
            analysisMethod = "SIGNAL_ONLY",
            templateId = snapshot.template.id,
            templateVersion = snapshot.template.version,
            templateSnapshotJson = "过期项目快照不得被运行复用",
            overrideJson = "{\"sampleSlotMapping\":{\"A1\":\"S-01\"}}"
        )
        val measurements = listOf(
            SiteMeasurement(
                runId = "run-1",
                siteIndex = 0,
                analyteId = "cea",
                detectionMode = DetectionModality.COLORIMETRIC.code,
                rawSignalJson = "{}",
                primaryFeatureName = "GRAY_LUMINOSITY",
                primaryFeatureValue = 12.0,
                signalDetectable = true,
                qualityReliable = true,
                processorName = "colorimetric-photometry",
                processorVersion = "2.0.0",
                concentrationValue = 3.5,
                concentrationUnit = "ng/mL",
                reliableRangeStatus = "BELOW_RANGE",
                quantificationQcJson = "{\"scope\":\"SITE\"}"
            ),
            SiteMeasurement(
                runId = "run-1",
                siteIndex = 1,
                analyteId = null,
                detectionMode = DetectionModality.COLORIMETRIC.code,
                rawSignalJson = "{}",
                primaryFeatureName = COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE,
                signalDetectable = true,
                qualityReliable = false,
                processorName = "colorimetric-photometry",
                processorVersion = "2.0.0",
                // 损坏的旧 QC JSON 不能阻止整个运行保存，也不能被误计为 SITE 警告。
                quantificationQcJson = "{broken"
            )
        )
        val bundle = GridDetectionPersistenceAssembler().assemble(
            GridDetectionPersistenceInput(
                project = project,
                snapshot = snapshot,
                runId = "run-1",
                capturedAt = Date(2_000L),
                endpointPath = project.imageUri,
                operatorId = project.userId,
                acquisitionMetadataJson = "{\"source\":\"camera\"}",
                geometryPersistence = GridGeometryPersistence(
                    jsonKey = "plate96Geometry",
                    schemaVersion = "plate96-run-geometry-v1",
                    json = "{\"rows\":1,\"columns\":2}"
                ),
                frameQcJson = "{\"issues\":[]}",
                measurements = measurements,
                status = "PartiallyQuantified",
                modelUsageJson = "{\"cea\":{\"execution\":\"standard_curve_applied\"}}",
                persistedSourceInput = GridPersistedSourceInput(
                    path = "/private/run-1/endpoint_input.png",
                    checksumSha256 = "a".repeat(64),
                    metadataJson = "{\"encoding\":\"PNG_LOSSLESS\"}"
                ),
                processingEvidence = listOf(
                    GridProcessingEvidenceRecord(
                        role = CaptureRole.PROCESS_GRID_OVERLAY,
                        path = "/private/run-1/grid.png",
                        checksumSha256 = "b".repeat(64),
                        metadataJson = "{\"scientificUse\":\"diagnostic_only\"}"
                    )
                ),
                detectionModelUsed = "plate96-auto-fusion-v1",
                processingVersions = mapOf("photometry" to "2.0.0"),
                confidenceThreshold = 0.25f,
                iouThreshold = 0.45f
            )
        )

        assertEquals("/private/run-1/endpoint_input.png", bundle.endpointArtifact.originalPath)
        assertEquals("a".repeat(64), bundle.endpointArtifact.checksumSha256)
        assertEquals(1, bundle.diagnosticArtifacts.size)
        assertEquals("run-1", bundle.diagnosticArtifacts.single().runId)
        assertEquals(project.overrideJson, bundle.run.configurationDeviationJson)
        val frozen = TemplateProjectSnapshotCodec.decode(
            requireNotNull(bundle.run.effectiveConfigSnapshotJson)
        )
        assertEquals(snapshot.template.id, frozen.template.id)

        val frame = JsonParser.parseString(bundle.run.frameQcJson).asJsonObject
        assertTrue(frame.has("plate96Geometry"))
        assertFalse(frame.has("pgGrid"))
        assertEquals(
            "plate96-run-geometry-v1",
            frame.get("geometrySchemaVersion").asString
        )
        val summary = JsonParser.parseString(bundle.run.siteQcSummaryJson).asJsonObject
        assertEquals(2, summary.get("total").asInt)
        assertEquals(1, summary.get("reliable").asInt)
        assertEquals(2, summary.get("detectable").asInt)
        assertEquals(1, summary.get("quantified").asInt)
        assertEquals(1, summary.get("outOfRange").asInt)
        assertEquals(1, summary.get("siteSignalOnly").asInt)
        assertEquals(1, summary.get("referenceEvidence").asInt)
    }

    private fun snapshot(): TemplateProjectSnapshot {
        val template = ExperimentTemplate(
            id = "template-1",
            templateName = "冻结方案",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            concentrationUnit = "ng/mL",
            defaultLayoutJson = null,
            carrierProfileId = "carrier-1x2",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = "device-1",
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_500L,
            template = template,
            carrierProfile = CarrierProfile(
                id = "carrier-1x2",
                name = "1×2 圆孔测试载体",
                carrierType = CarrierType.PLATE.code,
                rows = 1,
                columns = 2,
                siteShape = SiteShape.CIRCLE.code
            ),
            acquisitionProfile = AcquisitionProfile(
                id = "device-1",
                name = "自动采集",
                supportedModesJson = "[\"COLORIMETRIC\"]",
                compatibleCarrierTypesJson = "[\"PLATE\"]",
                cameraControlStrategy = "AUTO_METADATA"
            ),
            analytes = emptyList(),
            siteAssignments = emptyList()
        )
    }
}
