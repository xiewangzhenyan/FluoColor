package com.muc.fluocolorquant.domain.detection

import android.graphics.Bitmap
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.domain.detection.evidence.GridPersistedSourceInput
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceRecord
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import java.util.Date
import java.util.UUID

/** 根据冻结载体档案选择的检测主链。 */
enum class GridCarrierRoute {
    MICROFLUIDIC_PG_GRID,
    PLATE96,
    UNSUPPORTED
}

/**
 * 载体路由只读取项目快照中的明确 [CarrierType]，不能用行列数或圆/方形状猜测。
 * 10×10 孔板和 10×10 芯片在物理定位、方向语义和采样掩膜上仍是不同对象。
 */
object GridDetectionRouteResolver {
    fun resolve(carrierType: CarrierType): GridCarrierRoute = when (carrierType) {
        CarrierType.MICROFLUIDIC_CHIP -> GridCarrierRoute.MICROFLUIDIC_PG_GRID
        CarrierType.PLATE -> GridCarrierRoute.PLATE96
        CarrierType.CUSTOM -> GridCarrierRoute.UNSUPPORTED
    }
}

/** 深度快照结构预检结果；该结果在任何定位或位点索引访问之前生成。 */
internal data class GridDetectionPreflightValidation(
    val reasons: Set<GridDetectionBlockReason>
)

/**
 * 纯结构预检器，与 Bitmap/OpenCV 解耦，损坏快照在昂贵定位前即可失败闭合。
 */
internal object GridDetectionPreflightValidator {
    fun validate(
        project: Project,
        snapshot: TemplateProjectSnapshot
    ): GridDetectionPreflightValidation {
        val reasons = linkedSetOf<GridDetectionBlockReason>()
        if (snapshot.analytes.isEmpty()) reasons += GridDetectionBlockReason.EMPTY_ANALYTE_SNAPSHOT
        val analyteIdList = snapshot.analytes.map { it.analyte.id }
        if (analyteIdList.toSet().size != analyteIdList.size) {
            reasons += GridDetectionBlockReason.DUPLICATE_ANALYTE_SNAPSHOT
        }
        if (snapshot.analytes.any { !hasConsistentAnalyteRelations(snapshot, it) }) {
            reasons += GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT
        }
        if (
            snapshot.template.carrierProfileId != snapshot.carrierProfile.id ||
            snapshot.template.acquisitionProfileId != snapshot.acquisitionProfile.id ||
            snapshot.siteAssignments.any { it.templateId != snapshot.template.id }
        ) {
            reasons += GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT
        }
        val analyteIds = analyteIdList.toSet()
        val enabledCoordinates = mutableSetOf<Pair<Int, Int>>()
        snapshot.siteAssignments.filter(TemplateSiteAssignment::enabled).forEach { assignment ->
            if (
                assignment.rowIndex !in 0 until snapshot.carrierProfile.rows ||
                assignment.columnIndex !in 0 until snapshot.carrierProfile.columns
            ) {
                reasons += GridDetectionBlockReason.INVALID_SITE_COORDINATE
            }
            if (!enabledCoordinates.add(assignment.rowIndex to assignment.columnIndex)) {
                reasons += GridDetectionBlockReason.DUPLICATE_ENABLED_SITE
            }
            if (assignment.analyteId != null && assignment.analyteId !in analyteIds) {
                reasons += GridDetectionBlockReason.ORPHAN_SITE_ANALYTE
            }
        }
        if (
            project.templateId != snapshot.template.id ||
            project.templateVersion != snapshot.template.version ||
            project.rows != snapshot.carrierProfile.rows ||
            project.columns != snapshot.carrierProfile.columns ||
            project.detectionMode != snapshot.template.detectionMode
        ) {
            reasons += GridDetectionBlockReason.PROJECT_SNAPSHOT_MISMATCH
        }
        return GridDetectionPreflightValidation(reasons)
    }

    /** 分析物、模板配置、模型和类型专用定义必须属于同一冻结关系图。 */
    private fun hasConsistentAnalyteRelations(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): Boolean {
        val analyteId = analyteSnapshot.analyte.id
        val config = analyteSnapshot.templateConfig
        val bundle = analyteSnapshot.analysisModel
        val model = bundle.model
        if (
            config.templateId != snapshot.template.id ||
            config.analyteId != analyteId ||
            config.analysisModelId != model.id ||
            model.analyteId != analyteId ||
            bundle.standardCurve?.analysisModelId?.let { it != model.id } == true ||
            bundle.deepLearning?.analysisModelId?.let { it != model.id } == true ||
            bundle.calibrationPoints.any { it.analysisModelId != model.id }
        ) {
            return false
        }
        return when (AnalysisModelType.fromCode(model.modelType)) {
            AnalysisModelType.STANDARD_CURVE -> bundle.standardCurve != null && bundle.deepLearning == null
            AnalysisModelType.DEEP_LEARNING ->
                bundle.deepLearning != null && bundle.standardCurve == null && bundle.calibrationPoints.isEmpty()
            null -> false
        }
    }
}

/** 运行协调器对页面公开的阶段。 */
enum class GridDetectionStage {
    PREPARING,
    LOCATING,
    PHOTOMETRY,
    RENDERING_EVIDENCE,
    CHECKING_MODELS,
    PERSISTING,
    COMPLETED
}

/** 无法开始新阵列检测的稳定原因码。 */
enum class GridDetectionBlockReason {
    PROJECT_SNAPSHOT_MISMATCH,
    UNSUPPORTED_CARRIER,
    INVALID_TEMPLATE_PROTOCOL,
    MISSING_LOCATOR_CONFIG,
    INVALID_LOCATOR_CONFIG,
    MISSING_ANALYTE_ASSIGNMENT,
    MISSING_COLORIMETRIC_REFERENCE,
    MISSING_FLUORESCENCE_CHANNEL,
    INVALID_PRIMARY_FEATURE,
    EMPTY_ANALYTE_SNAPSHOT,
    DUPLICATE_ANALYTE_SNAPSHOT,
    INCONSISTENT_ANALYTE_SNAPSHOT,
    INVALID_SITE_COORDINATE,
    DUPLICATE_ENABLED_SITE,
    ORPHAN_SITE_ANALYTE
}

/** 执行一次模板驱动阵列检测所需的运行期输入。 */
data class GridDetectionRequest(
    val project: Project,
    val snapshot: TemplateProjectSnapshot,
    val endpointBitmap: Bitmap,
    val endpointPath: String,
    val operatorId: String?,
    val runId: String = UUID.randomUUID().toString(),
    val capturedAt: Date = Date(),
    val acquisitionMetadataJson: String? = null,
    val onStageChanged: (GridDetectionStage) -> Unit = {}
)

sealed interface GridDetectionOutcome {
    data object LegacyPlateRequired : GridDetectionOutcome
    data class Blocked(val reasons: Set<GridDetectionBlockReason>) : GridDetectionOutcome
    data class RetakeRequired(val runId: String, val frameQcJson: String) : GridDetectionOutcome

    data class Completed(
        val runId: String,
        val measurementCount: Int,
        val signalOnlyAnalyteIds: Set<String>,
        val frameQcIssueCount: Int = 0,
        /** 本次真正执行并写入 DetectionRun 的校准后快照。 */
        val effectiveSnapshot: TemplateProjectSnapshot
    ) : GridDetectionOutcome
}

/** 定位确认页与最终定量之间共享的内存会话，防止同图重复定位产生漂移。 */
data class GridLocalizationSession(
    val request: GridDetectionRequest,
    val grid: PgGridResult,
    val quant: PgQuantResult,
    val frameQcJson: String,
    val persistedSourceInput: GridPersistedSourceInput?,
    val processingEvidence: List<GridProcessingEvidenceRecord>,
    val presentation: GridLocalizationPresentation,
    val geometryPersistence: GridGeometryPersistence,
    val detectionModelUsed: String,
    val processingVersions: Map<String, String>,
    val confidenceThreshold: Float? = null,
    val iouThreshold: Float? = null,
    val frameQcIssueCount: Int = 0
)

enum class GridLocalizationPresentation { MICROFLUIDIC, PLATE96 }

/** 几何 JSON 的持久化描述，96 孔板不能错误写入 pgGrid 字段。 */
data class GridGeometryPersistence(
    val jsonKey: String,
    val schemaVersion: String,
    val json: String
) {
    init {
        require(jsonKey in setOf("pgGrid", "plate96Geometry")) { "不支持的阵列几何持久化键" }
        require(schemaVersion.isNotBlank() && json.isNotBlank()) { "阵列几何版本和JSON不能为空" }
    }
}

sealed interface GridLocalizationOutcome {
    data object LegacyPlateRequired : GridLocalizationOutcome
    data class Blocked(val reasons: Set<GridDetectionBlockReason>) : GridLocalizationOutcome
    data class Ready(val session: GridLocalizationSession) : GridLocalizationOutcome
}
