package com.muc.fluocolorquant.domain.result

import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.domain.detection.grid.GridFrameQcIssue
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteFlag
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.LabPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry

/**
 * 阵列结果加载失败的稳定机器原因。
 *
 * 未来 Compose 页面只把这些原因映射为中英文说明，不直接展示 JSON/Gson/Room 异常，
 * 从而避免内部实现信息泄漏，也让自动化测试能够稳定断言失败语义。
 */
enum class ArrayResultErrorCode {
    RUN_NOT_FOUND,
    PROJECT_NOT_FOUND,
    PROJECT_RUN_MISMATCH,
    MISSING_EFFECTIVE_CONFIG_SNAPSHOT,
    CORRUPT_EFFECTIVE_CONFIG_SNAPSHOT,
    CORRUPT_CONFIGURATION_DEVIATION,
    MISSING_PG_GRID_GEOMETRY,
    CORRUPT_PG_GRID_GEOMETRY,
    INCONSISTENT_SNAPSHOT,
    INCONSISTENT_GEOMETRY,
    INVALID_MEASUREMENT,
    CORRUPT_DECLARED_SIGNAL_SCHEMA,
    DATABASE_READ_FAILED
}

/** 结果加载使用显式成功/失败类型，页面不得依赖抛异常来判断历史数据是否可读。 */
sealed interface ArrayResultLoadResult {
    data class Success(val snapshot: ArrayResultSnapshot) : ArrayResultLoadResult
    data class Failure(val errorCode: ArrayResultErrorCode) : ArrayResultLoadResult
}

/** Mapper 的完整只读输入；所有集合都必须来自同一个 Room 事务。 */
data class ArrayResultSnapshotSource(
    val run: DetectionRun,
    val project: Project,
    val artifacts: List<CaptureArtifact>,
    val measurements: List<SiteMeasurement>
)

/**
 * 一次新阵列检测的完整只读结果。
 *
 * JSON 原文与解析后的科学字段同时保留：前者用于科研归档和未来兼容，后者供结果页、
 * 热力图与导出直接消费。任何字段均来自检测运行冻结证据，不读取当前模板库。
 */
data class ArrayResultSnapshot(
    val runId: String,
    val projectId: String,
    val projectName: String,
    val runTimestampEpochMillis: Long,
    val runStatus: String,
    val detectionMode: String,
    val carrier: ArrayCarrierResult,
    val rows: Int,
    val columns: Int,
    val analytes: List<ArrayAnalyteResult>,
    val sites: List<ArrayPhysicalSiteResult>,
    val frame: ArrayFrameResult,
    val artifacts: List<ArrayCaptureEvidence>,
    val effectiveConfigSnapshotJson: String,
    val configurationDeviationJson: String?,
    val acquisitionMetadataJson: String?,
    val processingVersionJson: String?,
    val modelUsageJson: String?,
    val siteQcSummaryJson: String?
)

/** 载体身份与几何版本来自模板运行快照。 */
data class ArrayCarrierResult(
    val id: String,
    val name: String,
    val carrierType: String,
    val version: Int,
    val siteShape: String,
    val orientationMarkerJson: String?
)

/** 单个分析物的冻结显示身份、模型身份和可靠范围。 */
data class ArrayAnalyteResult(
    val analyteId: String,
    val name: String,
    val displayOrder: Int,
    val concentrationUnit: String,
    val reliableRangeMin: Double?,
    val reliableRangeMax: Double?,
    val modelId: String,
    val modelName: String,
    val modelType: String,
    val modelVersion: Int,
    val primaryFeature: String,
    val processorName: String,
    val processorVersion: String,
    /** 冻结运行实际使用的标准曲线函数；深度学习或仅信号运行时为空。 */
    val fittingFunction: String? = null,
    /** 只保留可安全解析的有限参数，结果页不会直接展示底层 JSON。 */
    val fittingParameters: Map<String, Double> = emptyMap(),
    /** 本次运行冻结的真实标准点，用于结果页重建拟合曲线。 */
    val calibrationPoints: List<ArrayCalibrationPointResult> = emptyList(),
    /** 拟合时冻结的有限验证指标，例如 R²、RMSE、MAE 和标准点接受率。 */
    val validationMetrics: Map<String, Double> = emptyMap()
)

/** 结果页绘图需要的最小标准点契约，避免 UI 依赖 Room 实体。 */
data class ArrayCalibrationPointResult(
    val concentration: Double,
    val signalValue: Double,
    val repeatIndex: Int
)

/** 帧级几何与质量控制；完整 PG-Grid 原文仍保留在运行 JSON 中。 */
data class ArrayFrameResult(
    val locatorName: String,
    val locatorVersion: String,
    val rectifiedWidth: Int,
    val rectifiedHeight: Int,
    val chipRegionMethod: String,
    val geometry: GridGeometryDiagnostics,
    val qcIssues: List<GridFrameQcIssue>,
    val frameQcJson: String
)

/** 一个物理阵列位点，无测量时也必须存在。 */
data class ArrayPhysicalSiteResult(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val siteKey: String,
    val enabled: Boolean,
    val roleCode: String?,
    val analyteId: String?,
    val defaultSampleSlot: String?,
    val sampleSlot: String?,
    val overrideReason: String?,
    val standardConcentration: Double?,
    val repeatGroup: String?,
    val referenceScope: String?,
    val geometry: ArraySiteGeometry,
    val measurements: List<ArraySiteMeasurementResult>
)

/** 位点同时保存矫正图与原图坐标，原图叠加不得重新执行定位。 */
data class ArraySiteGeometry(
    val rectified: GridPoint,
    val original: GridPoint,
    val confidence: Double,
    val source: GridPointSource,
    val flags: Set<GridSiteFlag>
)

/** 数据库逐位点测量在结果领域中的稳定表达。 */
data class ArraySiteMeasurementResult(
    val measurementId: Long,
    val analyteId: String?,
    val detectionMode: String,
    val primaryFeatureName: String,
    val primaryFeatureValue: Double?,
    val concentrationValue: Double?,
    val concentrationUnit: String?,
    val reliableRangeStatus: String?,
    val backgroundValue: Double?,
    val signalToNoiseRatio: Double?,
    val confidence: Double?,
    val signalDetectable: Boolean,
    val qualityReliable: Boolean,
    val processorName: String,
    val processorVersion: String,
    val modelSnapshotJson: String?,
    val rawSignalJson: String,
    val correctedSignalJson: String?,
    val qcJson: String?,
    val quantificationQcJson: String?,
    val qc: ArrayMeasurementQc,
    val detail: ArrayMeasurementDetail
)

/** 结果页常用的机器 QC 字段；未知扩展字段仍保留在原 JSON 中。 */
data class ArrayMeasurementQc(
    val geometrySourceCode: String?,
    val geometryFlags: Set<String>,
    val photometryFlags: Set<String>,
    val quantificationStatus: String?,
    val quantificationScope: String?,
    val quantificationReason: String?
)

/** 模态专用详情，避免比色和荧光再次被压成同一套通用数值。 */
sealed interface ArrayMeasurementDetail {
    data class Colorimetric(
        val site: ColorimetricSitePhotometry,
        val calibrationContext: ArrayColorimetricCalibrationContext,
        val schemaVersion: String
    ) : ArrayMeasurementDetail

    data class Fluorescence(
        val site: FluorescenceSitePhotometry
    ) : ArrayMeasurementDetail

    data class ColorimetricReference(
        val roleCode: String,
        val site: BaseSitePhotometry,
        val schemaVersion: String
    ) : ArrayMeasurementDetail

    /** 旧运行没有声明结构版本时只保留证据，不猜测科学字段。 */
    data class LegacyUnparsed(
        val rawSignalJson: String,
        val correctedSignalJson: String?
    ) : ArrayMeasurementDetail
}

/** 比色校正必须能追溯到实际参考位和白平衡参数。 */
data class ArrayColorimetricCalibrationContext(
    val referenceIndices: List<Int>,
    val whiteBalanceGains: RgbPhotometry,
    val referenceRgb: RgbPhotometry,
    val referenceLab: LabPhotometry
)

/** 原始采集附件在结果页与导出中的只读证据。 */
data class ArrayCaptureEvidence(
    val artifactId: String,
    val captureRole: String,
    val originalPath: String,
    val derivedPath: String?,
    val capturedAtEpochMillis: Long,
    val operatorId: String?,
    val actualMetadataJson: String?,
    val profileSnapshotJson: String?,
    val imageQcJson: String?,
    val checksumSha256: String?,
    val locked: Boolean,
    val revision: Int
)
