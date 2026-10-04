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
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryDirection
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryReason
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryStatus

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
    MISSING_PLATE96_GEOMETRY,
    CORRUPT_PLATE96_GEOMETRY,
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

/**
 * 单个分析物的冻结显示身份、模型身份和两类浓度范围。
 *
 * 项目量程描述用户创建项目时声明的业务范围；曲线标定范围只描述本次曲线真实覆盖的标准点区间。
 * 二者必须独立保存，否则现场标准点较窄时会把项目最大浓度错误缩小，并导致大量位点被误判为失败。
 */
data class ArrayAnalyteResult(
    val analyteId: String,
    val name: String,
    val displayOrder: Int,
    val concentrationUnit: String,
    /**
     * 旧字段保留给现有导出和历史测试兼容；其语义从本版本起固定为“项目量程”。
     * 新代码应优先读取 [projectRangeMin] 与 [projectRangeMax]，避免再次把它理解为曲线标定范围。
     */
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
    val validationMetrics: Map<String, Double> = emptyMap(),
    /** 用户在项目或模板中声明的最低浓度；旧调用方未提供时兼容映射自旧字段。 */
    val projectRangeMin: Double? = reliableRangeMin,
    /** 用户在项目或模板中声明的最高浓度；热力图色带和项目边界判断使用该值。 */
    val projectRangeMax: Double? = reliableRangeMax,
    /** 标准曲线真实参与拟合的最低浓度；仅用于解释插值区间和外推状态。 */
    val calibrationRangeMin: Double? = null,
    /** 标准曲线真实参与拟合的最高浓度；不得覆盖项目量程。 */
    val calibrationRangeMax: Double? = null,
    /** 多数样品越界后的冻结动态复核；旧运行或未执行复核时为空。 */
    val rangeRecovery: ArrayRangeRecoveryResult? = null
)

/** 结果页使用的最小动态量程复核快照，不暴露协调器内部 JSON 结构。 */
data class ArrayRangeRecoveryResult(
    val status: RangeRecoveryStatus,
    val reason: RangeRecoveryReason,
    val direction: RangeRecoveryDirection,
    val validSampleCount: Int,
    val withinRangeCount: Int,
    val belowRangeCount: Int,
    val aboveRangeCount: Int,
    val outOfRangeRatio: Double,
    val algorithmVersion: String
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
    val quantificationState: String? = null,
    val concentrationLowerBound: Double? = null,
    val concentrationUpperBound: Double? = null,
    val intervalConfidenceLevel: Double? = null,
    val censoringDirection: String? = null,
    val quantificationVersion: String? = null,
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
    val quantificationReason: String?,
    /** 深度学习位点失败时冻结的 PTL 原始输出；其他量化方式或旧记录为空。 */
    val rawModelOutput: Double? = null,
    /** 应用模型 scale/offset 后参与声明域判断的输出。 */
    val transformedModelOutput: Double? = null,
    val declaredOutputMin: Double? = null,
    val declaredOutputMax: Double? = null
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
    /** RGB/Lab等直接信号可以不使用参考位，此时参考颜色上下文为空。 */
    val referenceRgb: RgbPhotometry?,
    val referenceLab: LabPhotometry?
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
