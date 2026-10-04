package com.muc.fluocolorquant.application.detection

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.domain.detection.GridGeometryPersistence
import com.muc.fluocolorquant.domain.detection.evidence.GridPersistedSourceInput
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceRecord
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import java.util.Date
import javax.inject.Inject

/**
 * 一次检测完成后组装原子持久化数据包所需的全部非图像输入。
 *
 * 这里刻意不携带 Bitmap、定位器或量化器，使持久化快照可以在 JVM 中独立验证；所有字段都来自
 * 本次内存会话，不允许在组装阶段回读可变设置或重新计算科学结果。
 */
data class GridDetectionPersistenceInput(
    val project: Project,
    val snapshot: TemplateProjectSnapshot,
    val runId: String,
    val capturedAt: Date,
    val endpointPath: String,
    val operatorId: String?,
    val acquisitionMetadataJson: String?,
    val geometryPersistence: GridGeometryPersistence,
    val frameQcJson: String,
    val measurements: List<SiteMeasurement>,
    val status: String,
    val modelUsageJson: String?,
    val persistedSourceInput: GridPersistedSourceInput? = null,
    val processingEvidence: List<GridProcessingEvidenceRecord> = emptyList(),
    val detectionModelUsed: String,
    val processingVersions: Map<String, String>,
    val confidenceThreshold: Float? = null,
    val iouThreshold: Float? = null
)

/**
 * 把检测会话转换为 Room 仓库的一次性原子写入数据包。
 *
 * 该类只负责结构组装与一致性校验，不执行文件写入或数据库事务。原始输入和诊断图必须已经由
 * 上游写入应用私有目录；仓库随后在单个 Room 事务中保存这里返回的全部实体。
 */
class GridDetectionPersistenceAssembler @Inject constructor() {
    private val gson = Gson()

    fun assemble(input: GridDetectionPersistenceInput): GridDetectionPersistenceBundle {
        // 历史必须读取本次实际执行的冻结快照，不能照抄 Project 中可能过期或损坏的 JSON。
        val effectiveSnapshot = TemplateProjectSnapshotCodec.encode(input.snapshot)
        val run = DetectionRun(
            runId = input.runId,
            projectId = input.project.id,
            timestamp = input.capturedAt,
            detectionModelUsed = input.detectionModelUsed,
            concentrationModelUsed = input.modelUsageJson,
            status = input.status,
            errorMessage = null,
            confThreshold = input.confidenceThreshold,
            iouThreshold = input.iouThreshold,
            wellsDetected = input.measurements.size,
            effectiveConfigSnapshotJson = effectiveSnapshot,
            acquisitionMetadataJson = input.acquisitionMetadataJson,
            processingVersionJson = gson.toJson(input.processingVersions),
            frameQcJson = input.frameQcJson,
            siteQcSummaryJson = summarizeSiteQc(input.measurements),
            // 项目覆盖只在运行开始时冻结；结果页不能回读用户之后修改的 Project.overrideJson。
            configurationDeviationJson = input.project.overrideJson
        )
        val endpointArtifact = CaptureArtifact(
            id = "${input.runId}-endpoint-1",
            runId = input.runId,
            captureRole = CaptureRole.ENDPOINT.code,
            // 长期私有文件优先；写入失败时保留原路径，兼容 content URI 和旧项目输入。
            originalPath = input.persistedSourceInput?.path ?: input.endpointPath,
            capturedAt = input.capturedAt,
            operatorId = input.operatorId,
            actualMetadataJson = input.persistedSourceInput?.metadataJson
                ?: input.acquisitionMetadataJson,
            profileSnapshotJson = gson.toJson(input.snapshot.acquisitionProfile),
            imageQcJson = input.frameQcJson,
            checksumSha256 = input.persistedSourceInput?.checksumSha256,
            locked = true,
            revision = 1
        )
        val diagnosticArtifacts = input.processingEvidence.mapIndexed { index, evidence ->
            CaptureArtifact(
                id = "${input.runId}-${evidence.role.code.lowercase()}-1",
                runId = input.runId,
                captureRole = evidence.role.code,
                originalPath = evidence.path,
                capturedAt = input.capturedAt,
                operatorId = input.operatorId,
                actualMetadataJson = evidence.metadataJson,
                imageQcJson = input.frameQcJson,
                checksumSha256 = evidence.checksumSha256,
                pairingKey = "${input.runId}-processing",
                locked = true,
                revision = index + 1
            )
        }
        // 几何 JSON 是运行快照而非文件路径。键由载体协议提供，96孔板不能冒充 pgGrid。
        val runWithGeometry = run.copy(
            frameQcJson = gson.toJson(
                linkedMapOf<String, Any?>(
                    "frame" to gson.fromJson(input.frameQcJson, JsonObject::class.java),
                    input.geometryPersistence.jsonKey to gson.fromJson(
                        input.geometryPersistence.json,
                        JsonObject::class.java
                    ),
                    "geometrySchemaVersion" to input.geometryPersistence.schemaVersion
                )
            )
        )
        return GridDetectionPersistenceBundle(
            run = runWithGeometry,
            endpointArtifact = endpointArtifact,
            measurements = input.measurements,
            diagnosticArtifacts = diagnosticArtifacts
        ).requireValid()
    }

    private fun summarizeSiteQc(measurements: List<SiteMeasurement>): String {
        return gson.toJson(
            mapOf(
                "total" to measurements.size,
                "reliable" to measurements.count(SiteMeasurement::qualityReliable),
                "detectable" to measurements.count(SiteMeasurement::signalDetectable),
                "quantified" to measurements.count { it.concentrationValue != null },
                "outOfRange" to measurements.count {
                    it.reliableRangeStatus in setOf("BELOW_RANGE", "ABOVE_RANGE")
                },
                "siteSignalOnly" to measurements.count { measurement ->
                    quantificationScope(measurement.quantificationQcJson) == "SITE"
                },
                "referenceEvidence" to measurements.count {
                    it.primaryFeatureName == COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE
                }
            )
        )
    }

    /** 损坏的量化 QC JSON 只是不计入位点警告，不能中断整次运行保存。 */
    private fun quantificationScope(quantificationQcJson: String?): String? {
        if (quantificationQcJson.isNullOrBlank()) return null
        return try {
            gson.fromJson(quantificationQcJson, JsonObject::class.java)
                ?.get("scope")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        } catch (_: RuntimeException) {
            null
        }
    }
}
