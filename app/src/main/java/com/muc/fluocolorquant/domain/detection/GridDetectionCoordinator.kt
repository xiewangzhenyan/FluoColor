package com.muc.fluocolorquant.domain.detection

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PG_GRID_SCHEMA_V2_1
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.evidence.GRID_PROCESSING_EVIDENCE_SCHEMA
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceRecord
import com.muc.fluocolorquant.domain.detection.evidence.GridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.NoOpGridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.quantification.ENDPOINT_QUANTIFIER_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.FORMULA_ENGINE_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningBatchResult
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.UnavailableGridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.PreparedEndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.StandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_CORRECTED_SIGNAL_SCHEMA_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_SCHEMA_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricPhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricPhotometryResult
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricProcessorConfig
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescencePhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceProcessorConfig
import com.muc.fluocolorquant.domain.detection.photometry.PG_QUANT_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.PG_QUANT_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantSampler
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 根据冻结载体档案选择的检测主链。 */
enum class GridCarrierRoute {
    /** 微流控规则阵列使用 PG-Grid 几何和新的模态专用光度。 */
    MICROFLUIDIC_PG_GRID,

    /** 旧孔板继续使用已有 YOLO＋霍夫圆兼容链，避免破坏历史实验。 */
    LEGACY_PLATE,

    /** 自定义载体必须先声明定位协议，不能猜测为微流控或孔板。 */
    UNSUPPORTED
}

/**
 * 载体路由解析器。
 *
 * 路由只读取项目模板快照中的 [CarrierType]，不读取行列数、位点形状或检测模态来
 * 猜测载体。10×10 孔板和 10×10 芯片在物理定位上仍是不同对象。
 */
object GridDetectionRouteResolver {
    fun resolve(carrierType: CarrierType): GridCarrierRoute {
        return when (carrierType) {
            CarrierType.MICROFLUIDIC_CHIP -> GridCarrierRoute.MICROFLUIDIC_PG_GRID
            CarrierType.PLATE -> GridCarrierRoute.LEGACY_PLATE
            CarrierType.CUSTOM -> GridCarrierRoute.UNSUPPORTED
        }
    }
}

/** 深度快照结构预检结果；该结果在任何定位或位点索引访问之前生成。 */
internal data class GridDetectionPreflightValidation(
    val reasons: Set<GridDetectionBlockReason>
)

/**
 * 只依赖冻结项目和模板快照的结构预检器。
 *
 * 将结构校验与 Android Bitmap、OpenCV 定位器解耦，既保证损坏快照在定位前被阻止，
 * 也允许 JVM 测试直接覆盖空分析物、非法坐标、重复坐标和悬空分析物引用。
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
        // 顶层冻结对象同样属于一次历史运行的科学关系图。模板引用的载体/采集档案，
        // 以及每个启用或禁用位点所属的模板都必须与快照根对象一致；不能只校验分析物子树。
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

    /**
     * 校验一个冻结分析物快照内部所有关系与类型专用定义。
     *
     * 快照是历史运行的唯一科学配置来源，因此不能容忍“显示分析物是 A、配置/模型却属于 B”
     * 或标准曲线与智能模型定义混装；任一关系错配都必须在访问位点索引和调用定位器前阻断。
     */
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
            AnalysisModelType.STANDARD_CURVE ->
                bundle.standardCurve != null && bundle.deepLearning == null
            AnalysisModelType.DEEP_LEARNING ->
                bundle.deepLearning != null &&
                    bundle.standardCurve == null &&
                    bundle.calibrationPoints.isEmpty()
            null -> false
        }
    }
}

/** 运行协调器对页面公开的阶段，页面可以立即展示处理进度而不阻塞主线程。 */
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

    data class Blocked(
        val reasons: Set<GridDetectionBlockReason>
    ) : GridDetectionOutcome

    data class RetakeRequired(
        val runId: String,
        val frameQcJson: String
    ) : GridDetectionOutcome

    /**
     * 几何和光度已保存；[signalOnlyAnalyteIds] 非空表示模型不兼容，只保存信号不输出浓度。
     */
    data class Completed(
        val runId: String,
        val measurementCount: Int,
        val signalOnlyAnalyteIds: Set<String>,
        /** 帧级 QC 只作为复核证据，不再阻止已经形成完整晶格的图片继续分析。 */
        val frameQcIssueCount: Int = 0,
        /** 本次真正执行并写入 DetectionRun 的校准后快照，项目草稿据此继续编辑。 */
        val effectiveSnapshot: TemplateProjectSnapshot
    ) : GridDetectionOutcome
}

/**
 * 定位确认页与最终定量之间共享的内存会话。
 *
 * 会话保留本次真实定位、光度和处理证据，用户完成孔位布局后直接继续定量，避免再次运行
 * OpenCV 导致同一张图片在两个页面得到轻微不同的定位结果。
 */
data class GridLocalizationSession(
    val request: GridDetectionRequest,
    val grid: PgGridResult,
    val quant: PgQuantResult,
    val frameQcJson: String,
    val processingEvidence: List<GridProcessingEvidenceRecord>
)

/** 只执行芯片定位、基础光度与处理证据生成后的稳定结果。 */
sealed interface GridLocalizationOutcome {
    data object LegacyPlateRequired : GridLocalizationOutcome

    data class Blocked(
        val reasons: Set<GridDetectionBlockReason>
    ) : GridLocalizationOutcome

    data class Ready(
        val session: GridLocalizationSession
    ) : GridLocalizationOutcome
}

/**
 * 模板驱动的微流控检测协调器。
 *
 * 该类只做运行编排，不包含 OpenCV 细节或颜色公式。微流控使用 [PgGridLocator]，孔板
 * 返回旧链路标志；比色/荧光共享一次基础 PG-Quant 采样，再分别调用自己的处理器。
 */
@Singleton
class GridDetectionCoordinator @Inject constructor(
    private val locator: PgGridLocator,
    private val repository: GridDetectionRunRepository,
    private val evidenceWriter: GridProcessingEvidenceWriter = NoOpGridProcessingEvidenceWriter,
    private val unitSegmenter: OpenCvArrayUnitSegmenter = OpenCvArrayUnitSegmenter(),
    /** JVM 测试省略该参数时安全降级；Hilt 生产图会注入真实 PyTorch Lite 实现。 */
    private val deepLearningExecutor: GridDeepLearningExecutor =
        UnavailableGridDeepLearningExecutor
) {
    private val gson = Gson()

    /** 兼容旧调用：已有完整布局的项目仍可一次执行到底。 */
    suspend fun execute(request: GridDetectionRequest): GridDetectionOutcome {
        return when (val localization = localize(request)) {
            GridLocalizationOutcome.LegacyPlateRequired -> GridDetectionOutcome.LegacyPlateRequired
            is GridLocalizationOutcome.Blocked -> GridDetectionOutcome.Blocked(localization.reasons)
            is GridLocalizationOutcome.Ready -> finalizeLocalized(
                session = localization.session,
                finalizedSnapshot = request.snapshot
            )
        }
    }

    /**
     * 第一阶段只做定位、基础光度与可视化证据，不要求项目已经配置孔位角色。
     * 这使普通用户可以先看到算法实际定位效果，再决定每个物理位点属于哪个分析物。
     */
    suspend fun localize(request: GridDetectionRequest): GridLocalizationOutcome {
        request.onStageChanged(GridDetectionStage.PREPARING)
        val carrierType = CarrierType.fromCode(request.snapshot.carrierProfile.carrierType)
            ?: return GridLocalizationOutcome.Blocked(
                setOf(GridDetectionBlockReason.UNSUPPORTED_CARRIER)
            )
        when (GridDetectionRouteResolver.resolve(carrierType)) {
            GridCarrierRoute.LEGACY_PLATE -> return GridLocalizationOutcome.LegacyPlateRequired
            GridCarrierRoute.UNSUPPORTED -> {
                return GridLocalizationOutcome.Blocked(
                    setOf(GridDetectionBlockReason.UNSUPPORTED_CARRIER)
                )
            }
            GridCarrierRoute.MICROFLUIDIC_PG_GRID -> Unit
        }

        val preflight = preflight(request, requireSiteAssignments = false)
        if (preflight.reasons.isNotEmpty()) {
            return GridLocalizationOutcome.Blocked(preflight.reasons)
        }
        val polarity = requireNotNull(preflight.targetPolarity)

        request.onStageChanged(GridDetectionStage.LOCATING)
        val grid = withContext(Dispatchers.Default) {
            locator.locate(
                bitmap = request.endpointBitmap,
                config = PgGridLocatorConfig(
                    rows = request.snapshot.carrierProfile.rows,
                    columns = request.snapshot.carrierProfile.columns,
                    targetPolarity = polarity
                )
            )
        }
        val frameQcJson = gson.toJson(
            mapOf(
                "geometry" to grid.geometry,
                "issues" to grid.frameQc
            )
        )
        // 帧级 QC 是面向复核的保守诊断，不能再把“曝光/模糊/光照阈值超限”等同于
        // “算法无法计算”。只要定位器已经返回通过强契约校验的完整晶格，就继续采样、
        // 保存结果和九步处理证据；用户可在质控页结合原图自行决定是否需要重拍。
        // 真正不可计算的情况（图片解码失败、定位异常、坐标契约损坏）仍由异常路径阻止。
        request.onStageChanged(GridDetectionStage.PHOTOMETRY)
        val siteShape = SiteShape.fromCode(request.snapshot.carrierProfile.siteShape)
            ?: SiteShape.CUSTOM
        val unitSegmentation = withContext(Dispatchers.Default) {
            unitSegmenter.segment(
                sourceBitmap = request.endpointBitmap,
                grid = grid,
                shape = ArrayUnitShape.fromSiteShape(siteShape)
            )
        }
        val quant = withContext(Dispatchers.Default) {
            PgQuantSampler.sample(
                bitmap = request.endpointBitmap,
                grid = grid,
                unitSegmentation = unitSegmentation
            )
        }
        request.onStageChanged(GridDetectionStage.RENDERING_EVIDENCE)
        val processingEvidence = writeProcessingEvidence(request, grid, quant)
        return GridLocalizationOutcome.Ready(
            GridLocalizationSession(
                request = request,
                grid = grid,
                quant = quant,
                frameQcJson = frameQcJson,
                processingEvidence = processingEvidence
            )
        )
    }

    /**
     * 第二阶段使用用户确认后的位点布局执行模态处理、定量与原子保存。
     * [finalizedSnapshot] 会进入 DetectionRun 的不可变快照，历史结果不再回读项目草稿布局。
     */
    suspend fun finalizeLocalized(
        session: GridLocalizationSession,
        finalizedSnapshot: TemplateProjectSnapshot
    ): GridDetectionOutcome {
        // 用户在布局页录入了标准品浓度时，先根据本次真实光度自动选择最佳曲线并冻结到
        // 运行快照；没有足够标准点的分析物继续保持仅信号，不会伪造浓度。
        val calibratedSnapshot = withContext(Dispatchers.Default) {
            applyOnsiteCalibrations(
                snapshot = finalizedSnapshot,
                quant = session.quant,
                runId = session.request.runId
            )
        }
        val request = session.request.copy(snapshot = calibratedSnapshot)
        val preflight = preflight(request, requireSiteAssignments = true)
        if (preflight.reasons.isNotEmpty()) return GridDetectionOutcome.Blocked(preflight.reasons)
        val modality = requireNotNull(DetectionModality.fromCode(request.snapshot.template.detectionMode))

        request.onStageChanged(GridDetectionStage.CHECKING_MODELS)
        val processed = withContext(Dispatchers.Default) {
            when (modality) {
                DetectionModality.COLORIMETRIC -> {
                    processColorimetric(request, session.grid, session.quant)
                }
                DetectionModality.FLUORESCENCE -> {
                    processFluorescence(request, session.grid, session.quant)
                }
                DetectionModality.SPECTRUM -> error("预检已经阻止光谱进入规则阵列终点链")
            }
        }

        val bundle = buildPersistenceBundle(
            request = request,
            gridJson = PgGridJsonCodec.encode(session.grid),
            frameQcJson = session.frameQcJson,
            measurements = processed.measurements,
            status = statusForQuantification(
                signalOnlyAnalyteIds = processed.signalOnlyAnalyteIds,
                measurements = processed.measurements
            ),
            modelUsageJson = gson.toJson(processed.modelUsage),
            processingEvidence = session.processingEvidence
        )
        request.onStageChanged(GridDetectionStage.PERSISTING)
        withContext(Dispatchers.IO) { repository.save(bundle) }
        request.onStageChanged(GridDetectionStage.COMPLETED)
        return GridDetectionOutcome.Completed(
            runId = request.runId,
            measurementCount = processed.measurements.size,
            signalOnlyAnalyteIds = processed.signalOnlyAnalyteIds,
            frameQcIssueCount = session.grid.frameQc.size,
            effectiveSnapshot = request.snapshot
        )
    }

    /**
     * 为设置了至少两个有效标准浓度水平的分析物执行现场自动拟合。
     *
     * 比色和荧光共用同一成熟拟合引擎，但信号提取严格按各自处理器和主特征执行；因此
     * 两种模态不会共用同一条曲线实例，也不会把 RGB 灰度误当作荧光净强度。
     */
    /**
     * 模块内可测的现场标定入口。
     *
     * 保持为 `internal` 而不是公开 API：生产代码仍只应从 [finalizeLocalized] 调用，
     * 单元测试则可以直接注入冻结光度结果，验证曲线、单位和仅信号回退语义，不必构造
     * Android Bitmap 或真正执行一次 OpenCV 定位。
     */
    internal fun applyOnsiteCalibrations(
        snapshot: TemplateProjectSnapshot,
        quant: PgQuantResult,
        runId: String
    ): TemplateProjectSnapshot {
        val updatedAnalytes = snapshot.analytes.map { analyteSnapshot ->
            if (
                analyteSnapshot.resolvedGridQuantitationMode() !=
                GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
            ) {
                return@map analyteSnapshot
            }
            val preview = previewOnsiteCalibration(
                snapshot = snapshot,
                quant = quant,
                analyteId = analyteSnapshot.analyte.id
            ) ?: return@map analyteSnapshot
            val minimum = preview.standardPoints.minOf { it.first }
            val maximum = preview.standardPoints.maxOf { it.first }
            val model = analyteSnapshot.analysisModel.model.copy(
                name = "onsite-auto-fit",
                modelType = AnalysisModelType.STANDARD_CURVE.code,
                primaryFeature = preview.primaryFeature.code,
                reliableRangeMin = minimum,
                reliableRangeMax = maximum,
                validationMetricsJson = gson.toJson(
                    linkedMapOf(
                        "R2" to preview.rSquared,
                        "RMSE" to preview.rmse,
                        "MAE" to preview.mae,
                        "ACCEPTED_STANDARD_RATIO" to preview.acceptedStandardRatio,
                        "ACCEPTED" to preview.accepted
                    )
                ),
                updatedAt = Date()
            )
            val curve = StandardCurveDefinition(
                analysisModelId = model.id,
                fittingFunction = preview.function.identifier,
                parametersJson = gson.toJson(preview.parameters),
                monotonicDirection = "AUTO"
            )
            val calibrationPoints = preview.standardPoints.mapIndexed { index, (concentration, signal) ->
                CalibrationPoint(
                    id = "$runId-${analyteSnapshot.analyte.id}-standard-$index",
                    analysisModelId = model.id,
                    concentration = concentration,
                    signalValue = signal,
                    repeatIndex = index
                )
            }
            analyteSnapshot.copy(
                templateConfig = analyteSnapshot.templateConfig.copy(
                    reliableRangeMin = minimum,
                    reliableRangeMax = maximum
                ),
                analysisModel = AnalysisModelBundle(
                    model = model,
                    standardCurve = curve,
                    calibrationPoints = calibrationPoints
                ),
                quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                onsiteSelectedFeature = preview.primaryFeature.code,
                onsiteSelectedFunction = preview.function.identifier
            )
        }
        return snapshot.copy(analytes = updatedAnalytes)
    }

    /**
     * 根据本次真实标准孔，为一个分析物比较“候选信号 × 线性/4PL/5PL”。
     *
     * 普通模式不要求用户理解像素公式：未指定高级选项时会遍历当前检测模态允许的全部
     * 主信号，并按验收状态、标准点接受率、RMSE、MAE 和 R² 选择最稳妥组合。高级模式
     * 只会缩小候选集合，不允许输入任意函数名或参数 JSON。
     */
    internal fun previewOnsiteCalibration(
        snapshot: TemplateProjectSnapshot,
        quant: PgQuantResult,
        analyteId: String
    ): GridOnsiteFitPreview? {
        val modality = DetectionModality.fromCode(snapshot.template.detectionMode) ?: return null
        val analyteSnapshot = snapshot.analytes.firstOrNull { it.analyte.id == analyteId }
            ?: return null
        val standards = validStandards(snapshot, analyteId)
        if (standards.mapNotNull(TemplateSiteAssignment::standardConcentration).distinct().size < 2) {
            return null
        }

        val selectedFeature = AnalysisPrimaryFeature.fromCode(analyteSnapshot.onsiteSelectedFeature)
        val features = selectedFeature?.let(::setOf) ?: AnalysisFeaturePolicy.allowedFeatures(modality)
        val selectedFunction = analyteSnapshot.onsiteSelectedFunction
            ?.let(FittingFunction::fromIdentifier)
        val functions = selectedFunction?.let(::setOf)
            ?: FittingEngine.automaticCalibrationFunctions()

        val candidates = features.flatMap { feature ->
            val signalBySite = signalValuesForFeature(
                snapshot = snapshot,
                quant = quant,
                analyteSnapshot = analyteSnapshot,
                modality = modality,
                feature = feature
            ) ?: return@flatMap emptyList()
            val points = standards.mapNotNull { assignment ->
                val concentration = assignment.standardConcentration ?: return@mapNotNull null
                val siteIndex = assignment.rowIndex * snapshot.carrierProfile.columns +
                    assignment.columnIndex
                val signal = signalBySite[siteIndex]?.takeIf(Double::isFinite)
                    ?: return@mapNotNull null
                concentration to signal
            }
            if (points.map { it.first }.distinct().size < 2) return@flatMap emptyList()
            FittingEngine.fitCalibrationCandidates(points, functions).map { result ->
                feature to result
            }
        }
        val selected = candidates.minWithOrNull(
            compareBy<Pair<AnalysisPrimaryFeature, FittingResult>>(
                { if ((it.second.metrics["ICH M10 Accepted"] ?: 0.0) >= 1.0) 0 else 1 },
                { -(it.second.metrics["Accepted Standard Ratio"] ?: 0.0) },
                { it.second.metrics["RMSE"] ?: Double.POSITIVE_INFINITY },
                { it.second.metrics["MAE"] ?: Double.POSITIVE_INFINITY },
                { -it.second.rSquared }
            )
        ) ?: return null
        val feature = selected.first
        val fitting = selected.second
        if (!fitting.isSuccess || fitting.parameters.isEmpty()) return null
        return GridOnsiteFitPreview(
            analyteId = analyteId,
            primaryFeature = feature,
            function = fitting.function,
            parameters = fitting.params,
            standardPoints = fitting.standardPoints,
            curvePoints = fitting.curvePoints,
            latexFormula = FittingEngine.formatParametersToLatex(
                fitting.function,
                fitting.params
            ),
            rSquared = fitting.rSquared,
            rmse = fitting.metrics["RMSE"]?.takeIf(Double::isFinite),
            mae = fitting.metrics["MAE"]?.takeIf(Double::isFinite),
            acceptedStandardRatio = fitting.metrics["Accepted Standard Ratio"]
                ?.takeIf(Double::isFinite),
            accepted = (fitting.metrics["ICH M10 Accepted"] ?: 0.0) >= 1.0
        )
    }

    /** 获取一个分析物全部有效标准孔，保持物理行优先顺序。 */
    private fun validStandards(
        snapshot: TemplateProjectSnapshot,
        analyteId: String
    ): List<TemplateSiteAssignment> = snapshot.siteAssignments.filter { assignment ->
        assignment.enabled &&
            assignment.analyteId == analyteId &&
            assignment.roleType == TemplateSiteRole.STANDARD.code &&
            assignment.standardConcentration?.isFinite() == true &&
            requireNotNull(assignment.standardConcentration) >= 0.0
    }.sortedBy { it.rowIndex * snapshot.carrierProfile.columns + it.columnIndex }

    /**
     * 按候选主特征执行一次模态专用光度处理。
     * 比色仍强制使用真实参考位，荧光仍强制读取冻结通道，不会为了拟合候选而绕过科学校正。
     */
    private fun signalValuesForFeature(
        snapshot: TemplateProjectSnapshot,
        quant: PgQuantResult,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        modality: DetectionModality,
        feature: AnalysisPrimaryFeature
    ): Map<Int, Double>? {
        if (!AnalysisFeaturePolicy.isCompatible(modality, feature)) return null
        return when (modality) {
            DetectionModality.COLORIMETRIC -> {
                val references = referenceIndices(snapshot, analyteSnapshot)
                if (references.isEmpty()) return null
                ColorimetricPhotometryProcessor.process(
                    quant,
                    ColorimetricProcessorConfig(references, feature)
                ).sites.associate { it.base.siteIndex to it.primaryFeatureValue }
            }

            DetectionModality.FLUORESCENCE -> {
                val channel = ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                    analyteSnapshot.templateConfig.displayConfigJson
                ) ?: return null
                FluorescencePhotometryProcessor.process(
                    quant,
                    FluorescenceProcessorConfig(channel, feature)
                ).sites.associate { it.base.siteIndex to it.primaryFeatureValue }
            }

            DetectionModality.SPECTRUM -> null
        }
    }

    private fun preflight(
        request: GridDetectionRequest,
        requireSiteAssignments: Boolean
    ): PreflightResult {
        val reasons = linkedSetOf<GridDetectionBlockReason>().apply {
            addAll(GridDetectionPreflightValidator.validate(request.project, request.snapshot).reasons)
        }
        val snapshot = request.snapshot
        if (
            snapshot.template.inputProtocol != InputProtocol.ENDPOINT_ONLY.code ||
            snapshot.template.readoutLayout != ReadoutLayout.GRID_SITES.code ||
            DetectionModality.fromCode(snapshot.template.detectionMode) !in setOf(
                DetectionModality.COLORIMETRIC,
                DetectionModality.FLUORESCENCE
            )
        ) {
            reasons += GridDetectionBlockReason.INVALID_TEMPLATE_PROTOCOL
        }

        val polarity = ScientificDetectionConfigCodec.decodeCarrierPolarity(
            snapshot.carrierProfile.locatorConfigJson
        )
        if (snapshot.carrierProfile.locatorConfigJson.isNullOrBlank()) {
            reasons += GridDetectionBlockReason.MISSING_LOCATOR_CONFIG
        } else if (polarity == null) {
            reasons += GridDetectionBlockReason.INVALID_LOCATOR_CONFIG
        }

        snapshot.analytes.forEach { analyteSnapshot ->
            val assignments = assignmentsForAnalyte(snapshot, analyteSnapshot)
            if (requireSiteAssignments && assignments.isEmpty()) {
                reasons += GridDetectionBlockReason.MISSING_ANALYTE_ASSIGNMENT
            }
            val modality = DetectionModality.fromCode(snapshot.template.detectionMode)
            val feature = AnalysisPrimaryFeature.fromCode(analyteSnapshot.analysisModel.model.primaryFeature)
            if (feature == null || !isFeatureSupported(modality, feature)) {
                reasons += GridDetectionBlockReason.INVALID_PRIMARY_FEATURE
            }
            if (
                requireSiteAssignments &&
                modality == DetectionModality.COLORIMETRIC &&
                referenceIndices(snapshot, analyteSnapshot).isEmpty()
            ) {
                reasons += GridDetectionBlockReason.MISSING_COLORIMETRIC_REFERENCE
            }
            if (
                modality == DetectionModality.FLUORESCENCE &&
                ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                    analyteSnapshot.templateConfig.displayConfigJson
                ) == null
            ) {
                reasons += GridDetectionBlockReason.MISSING_FLUORESCENCE_CHANNEL
            }
        }
        return PreflightResult(reasons, polarity)
    }

    private fun processColorimetric(
        request: GridDetectionRequest,
        grid: com.muc.fluocolorquant.domain.detection.grid.PgGridResult,
        quant: com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
    ): ProcessedMeasurements {
        val measurements = mutableListOf<SiteMeasurement>()
        val signalOnly = linkedSetOf<String>()
        val modelUsage = linkedMapOf<String, Any>()
        // 全局空白/参考属于物理阵列证据，不属于任一分析物。必须在分析物循环外只保存
        // 一次，否则多分析物模板会为同一物理位点制造重复数据库行和虚高 measurementCount。
        measurements += buildGlobalColorimetricReferenceMeasurements(request, grid, quant)
        request.snapshot.analytes.forEach { analyteSnapshot ->
            val feature = AnalysisPrimaryFeature.fromCode(analyteSnapshot.analysisModel.model.primaryFeature)
                ?: AnalysisPrimaryFeature.DELTA_E_2000
            val calibrationReferenceIndices = referenceIndices(request.snapshot, analyteSnapshot)
            val result = ColorimetricPhotometryProcessor.process(
                quant,
                ColorimetricProcessorConfig(calibrationReferenceIndices, feature)
            )
            val compatibility = modelCompatibility(request, analyteSnapshot, feature)
            val baseMeasurements = assignmentsForAnalyte(request.snapshot, analyteSnapshot).map { assignment ->
                val index = assignment.rowIndex * grid.columns + assignment.columnIndex
                val site = result.sites[index]
                SiteMeasurement(
                    runId = request.runId,
                    siteIndex = index,
                    analyteId = analyteSnapshot.analyte.id,
                    detectionMode = DetectionModality.COLORIMETRIC.code,
                    rawSignalJson = gson.toJson(site.base),
                    correctedSignalJson = colorimetricCorrectedSignalJson(
                        site = site,
                        result = result,
                        referenceIndices = calibrationReferenceIndices
                    ),
                    primaryFeatureName = site.primaryFeature.code,
                    primaryFeatureValue = site.primaryFeatureValue,
                    backgroundValue = site.base.backgroundMedianGray,
                    signalToNoiseRatio = site.base.signalToNoiseRatio,
                    confidence = grid.sites[index].confidence,
                    signalDetectable = site.qc.signalDetectable,
                    qualityReliable = mergedReliability(site.qc.qualityReliable, grid, index),
                    qcJson = siteQcJson(grid, index, site.qc.flags),
                    processorName = COLORIMETRIC_PROCESSOR_NAME,
                    processorVersion = COLORIMETRIC_PROCESSOR_VERSION
                )
            }
            val quantified = applyConfiguredQuantification(
                measurements = baseMeasurements,
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                sourceBitmap = request.endpointBitmap,
                grid = grid,
                quant = quant
            )
            measurements += quantified.measurements
            if (!quantified.modelExecutable) signalOnly += analyteSnapshot.analyte.id
            modelUsage[analyteSnapshot.analyte.id] = modelUsageEntry(
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                batch = quantified
            )
        }
        return ProcessedMeasurements(measurements, signalOnly, modelUsage)
    }

    /**
     * 为全部无分析物的全局空白/参考物理位点创建一次原始证据测量。
     *
     * 这些行保留 PG-Quant 原始光度、几何来源、位点 QC 和处理器版本，但主特征值为空，
     * 不进入任何分析物的标准曲线反算或 modelUsage 计数。
     */
    private fun buildGlobalColorimetricReferenceMeasurements(
        request: GridDetectionRequest,
        grid: com.muc.fluocolorquant.domain.detection.grid.PgGridResult,
        quant: com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
    ): List<SiteMeasurement> {
        return request.snapshot.siteAssignments.asSequence()
            .filter { assignment ->
                assignment.enabled &&
                    assignment.analyteId == null &&
                    assignment.roleType in setOf(
                        TemplateSiteRole.BLANK.code,
                        TemplateSiteRole.REFERENCE.code
                    )
            }
            .sortedBy { it.rowIndex * grid.columns + it.columnIndex }
            .map { assignment ->
                val index = assignment.rowIndex * grid.columns + assignment.columnIndex
                val site = quant.sites[index]
                SiteMeasurement(
                    runId = request.runId,
                    siteIndex = index,
                    analyteId = null,
                    detectionMode = DetectionModality.COLORIMETRIC.code,
                    rawSignalJson = gson.toJson(
                        linkedMapOf(
                            "schemaVersion" to COLORIMETRIC_REFERENCE_EVIDENCE_SCHEMA_VERSION,
                            "roleType" to assignment.roleType,
                            "site" to site
                        )
                    ),
                    correctedSignalJson = null,
                    primaryFeatureName = COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE,
                    primaryFeatureValue = null,
                    backgroundValue = site.backgroundMedianGray,
                    signalToNoiseRatio = site.signalToNoiseRatio,
                    confidence = grid.sites[index].confidence,
                    signalDetectable = site.qc.signalDetectable,
                    qualityReliable = mergedReliability(site.qc.qualityReliable, grid, index),
                    qcJson = siteQcJson(grid, index, site.qc.flags),
                    processorName = PG_QUANT_PROCESSOR_NAME,
                    processorVersion = quant.processorVersion
                )
            }
            .toList()
    }

    /**
     * 构建可复算比色校正来源的版本化包装。
     *
     * `site` 保留位点级白平衡 RGB、Lab、ΔE 和 OD；`calibrationContext` 明确记录实际
     * 参考索引、白平衡增益及参考 RGB/Lab，避免历史结果只能看到最终数值却无法重建校正。
     */
    private fun colorimetricCorrectedSignalJson(
        site: ColorimetricSitePhotometry,
        result: ColorimetricPhotometryResult,
        referenceIndices: Set<Int>
    ): String {
        return gson.toJson(
            linkedMapOf(
                "schemaVersion" to COLORIMETRIC_CORRECTED_SIGNAL_SCHEMA_VERSION,
                "site" to site,
                "calibrationContext" to linkedMapOf(
                    "referenceIndices" to referenceIndices.sorted(),
                    "whiteBalanceGains" to result.whiteBalanceGains,
                    "referenceRgb" to result.referenceRgb,
                    "referenceLab" to result.referenceLab
                )
            )
        )
    }

    private fun processFluorescence(
        request: GridDetectionRequest,
        grid: com.muc.fluocolorquant.domain.detection.grid.PgGridResult,
        quant: com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
    ): ProcessedMeasurements {
        val measurements = mutableListOf<SiteMeasurement>()
        val signalOnly = linkedSetOf<String>()
        val modelUsage = linkedMapOf<String, Any>()
        request.snapshot.analytes.forEach { analyteSnapshot ->
            val feature = AnalysisPrimaryFeature.fromCode(analyteSnapshot.analysisModel.model.primaryFeature)
                ?: AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            val channel = requireNotNull(
                ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                    analyteSnapshot.templateConfig.displayConfigJson
                )
            )
            val result = FluorescencePhotometryProcessor.process(
                quant,
                FluorescenceProcessorConfig(channel = channel, primaryFeature = feature)
            )
            val compatibility = modelCompatibility(request, analyteSnapshot, feature)
            val baseMeasurements = assignmentsForAnalyte(request.snapshot, analyteSnapshot).map { assignment ->
                val index = assignment.rowIndex * grid.columns + assignment.columnIndex
                val site = result.sites[index]
                val background = fluorescenceChannelValue(
                    site.base.backgroundMedianRgb,
                    site.base.backgroundMedianGray,
                    channel
                )
                SiteMeasurement(
                    runId = request.runId,
                    siteIndex = index,
                    analyteId = analyteSnapshot.analyte.id,
                    detectionMode = DetectionModality.FLUORESCENCE.code,
                    rawSignalJson = gson.toJson(site.base),
                    correctedSignalJson = gson.toJson(site),
                    primaryFeatureName = site.primaryFeature.code,
                    primaryFeatureValue = site.primaryFeatureValue,
                    backgroundValue = background,
                    signalToNoiseRatio = site.signalToNoiseRatio,
                    confidence = grid.sites[index].confidence,
                    signalDetectable = site.qc.signalDetectable,
                    qualityReliable = mergedReliability(site.qc.qualityReliable, grid, index),
                    qcJson = siteQcJson(grid, index, site.qc.flags),
                    processorName = FLUORESCENCE_PROCESSOR_NAME,
                    processorVersion = FLUORESCENCE_PROCESSOR_VERSION
                )
            }
            val quantified = applyConfiguredQuantification(
                measurements = baseMeasurements,
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                sourceBitmap = request.endpointBitmap,
                grid = grid,
                quant = quant
            )
            measurements += quantified.measurements
            if (!quantified.modelExecutable) signalOnly += analyteSnapshot.analyte.id
            modelUsage[analyteSnapshot.analyte.id] = modelUsageEntry(
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                batch = quantified
            )
        }
        return ProcessedMeasurements(measurements, signalOnly, modelUsage)
    }

    /**
     * 在模型兼容门控通过后执行端点浓度反算。
     *
     * 超可靠范围只写范围状态和模型快照，不写浓度；模型定义不可执行时保留全部信号，
     * 并把整个分析物标记为仅信号。这样结果页可以区分“样本超范围”和“模型不可用”。
     */
    internal fun applyQuantification(
        measurements: List<SiteMeasurement>,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        prepareQuantifier: (AnalysisModelBundle) -> PreparedStandardCurveQuantifier =
            StandardCurveQuantifier::prepare
    ): QuantificationBatch {
        if (compatibility is ModelCompatibilityResult.Incompatible) {
            val reasonJson = gson.toJson(
                mapOf(
                    "status" to "SIGNAL_ONLY",
                    "scope" to "MODEL",
                    "reason" to "MODEL_INCOMPATIBLE",
                    "compatibilityReasons" to compatibility.reasons.map(Enum<*>::name)
                )
            )
            return QuantificationBatch(
                measurements = measurements.map { it.copy(quantificationQcJson = reasonJson) },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size
            )
        }

        // 模型解析、严格定义域、快照和单调性检查都只允许执行一次。若准备阶段失败，
        // 这是分析物级不可执行；Ready 内只有非有限原始信号允许产生位点级仅信号。
        val prepared = prepareQuantifier(analyteSnapshot.analysisModel)
        if (prepared is PreparedStandardCurveQuantifier.SignalOnly) {
            val reasonJson = gson.toJson(
                mapOf(
                    "status" to "SIGNAL_ONLY",
                    "scope" to "MODEL",
                    "reason" to prepared.reason.name
                )
            )
            return QuantificationBatch(
                measurements = measurements.map { it.copy(quantificationQcJson = reasonJson) },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size
            )
        }
        val ready = prepared as PreparedStandardCurveQuantifier.Ready
        var quantifiedCount = 0
        var outOfRangeCount = 0
        var siteSignalOnlyCount = 0
        val evaluated = measurements.map { measurement ->
            measurement to ready.quantify(measurement.primaryFeatureValue ?: Double.NaN)
        }

        // Ready 的正向求值、二分或插值内部一旦暴露模型故障，同一分析物的全部结果都
        // 必须从原始测量重新生成模型级 SignalOnly，不能保留故障出现前的部分浓度。
        val modelFailure = evaluated.firstNotNullOfOrNull { (_, result) ->
            result as? PreparedEndpointQuantificationResult.ModelFailure
        }
        if (modelFailure != null) {
            val reasonJson = gson.toJson(
                mapOf(
                    "status" to "SIGNAL_ONLY",
                    "scope" to "MODEL",
                    "reason" to modelFailure.reason.name
                )
            )
            return QuantificationBatch(
                measurements = measurements.map { measurement ->
                    measurement.copy(
                        concentrationValue = null,
                        concentrationUnit = null,
                        reliableRangeStatus = null,
                        modelSnapshotJson = null,
                        quantificationQcJson = reasonJson
                    )
                },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size
            )
        }

        val quantifiedMeasurements = evaluated.map { (measurement, result) ->
            when (result) {
                is PreparedEndpointQuantificationResult.Quantified -> measurement.copy(
                    concentrationValue = result.concentration,
                    concentrationUnit = result.unit,
                    reliableRangeStatus = result.rangeStatus.name,
                    modelSnapshotJson = result.modelSnapshotJson,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to "QUANTIFIED",
                            "rangeStatus" to result.rangeStatus.name
                        )
                    )
                ).also { quantifiedCount += 1 }

                is PreparedEndpointQuantificationResult.OutOfRange -> measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = result.rangeStatus.name,
                    modelSnapshotJson = result.modelSnapshotJson,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to "OUT_OF_RELIABLE_RANGE",
                            "rangeStatus" to result.rangeStatus.name,
                            "concentrationSuppressed" to true
                        )
                    )
                ).also { outOfRangeCount += 1 }

                PreparedEndpointQuantificationResult.SiteSignalOnly -> measurement.copy(
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to "SIGNAL_ONLY",
                            "scope" to "SITE",
                            "reason" to PreparedEndpointQuantificationResult.SiteSignalOnly.reason.name
                        )
                    )
                ).also { siteSignalOnlyCount += 1 }

                // 上方已对整个批次做过 ModelFailure 门控；保留穷尽分支防止后续新增结果
                // 类型时静默绕过模型级回退。
                is PreparedEndpointQuantificationResult.ModelFailure -> measurement
            }
        }
        return QuantificationBatch(
            measurements = quantifiedMeasurements,
            modelExecutable = true,
            quantifiedCount = quantifiedCount,
            outOfRangeCount = outOfRangeCount,
            siteSignalOnlyCount = siteSignalOnlyCount,
            total = quantifiedMeasurements.size
        )
    }

    /**
     * 按布局页冻结的定量方式分流。
     *
     * 显式“仅信号”必须优先于模型内容，防止用户切换方案后仍执行旧曲线；标准曲线和
     * 现场拟合共用严格反算器。深度学习执行器接入前保持模型级仅信号并写明原因，绝不
     * 把 PTL 文件当作标准曲线参数解析。
     */
    private fun applyConfiguredQuantification(
        measurements: List<SiteMeasurement>,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult
    ): QuantificationBatch {
        return when (analyteSnapshot.resolvedGridQuantitationMode()) {
            GridAnalyteQuantitationMode.ONSITE_AUTO_FIT,
            GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE -> applyQuantification(
                measurements = measurements,
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility
            )

            GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> applyDeepLearningQuantification(
                measurements = measurements,
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                sourceBitmap = sourceBitmap,
                grid = grid,
                quant = quant
            )

            GridAnalyteQuantitationMode.SIGNAL_ONLY -> signalOnlyBatch(
                measurements = measurements,
                reason = "USER_SELECTED_SIGNAL_ONLY"
            )
        }
    }

    /**
     * 使用真实紧致单元裁切执行逐孔 PTL 推理。
     *
     * 模型兼容、文件、SHA、输入协议或任意一个位点推理失败时，整分析物从原始测量重建
     * 为“仅信号”；绝不保留故障发生前已经算出的部分浓度。
     */
    private fun applyDeepLearningQuantification(
        measurements: List<SiteMeasurement>,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult
    ): QuantificationBatch {
        if (compatibility is ModelCompatibilityResult.Incompatible) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = "MODEL_INCOMPATIBLE",
                extra = mapOf(
                    "compatibilityReasons" to compatibility.reasons.map(Enum<*>::name)
                )
            )
        }
        val execution = deepLearningExecutor.execute(
            sourceBitmap = sourceBitmap,
            grid = grid,
            segmentation = quant.unitSegmentation,
            measurements = measurements,
            modelBundle = analyteSnapshot.analysisModel
        )
        return applyDeepLearningBatchResult(
            measurements = measurements,
            analyteSnapshot = analyteSnapshot,
            compatibility = compatibility,
            execution = execution
        )
    }

    /**
     * 深度学习批次结果到数据库测量的纯映射入口。
     *
     * 与 Bitmap/PyTorch 解耦后，JVM 测试可以严格验证成功、超范围、失败和不完整输出的
     * 原子语义；生产调用仍只能从 [applyDeepLearningQuantification] 进入真实执行链。
     */
    internal fun applyDeepLearningBatchResult(
        measurements: List<SiteMeasurement>,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        execution: GridDeepLearningBatchResult
    ): QuantificationBatch {
        if (compatibility is ModelCompatibilityResult.Incompatible) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = "MODEL_INCOMPATIBLE",
                extra = mapOf(
                    "compatibilityReasons" to compatibility.reasons.map(Enum<*>::name)
                )
            )
        }
        if (execution is GridDeepLearningBatchResult.Failure) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = execution.reason.name
            )
        }
        val predictions = (execution as GridDeepLearningBatchResult.Success).predictions
        if (predictions.size != measurements.size ||
            measurements.any { measurement -> measurement.siteIndex !in predictions }
        ) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = "INCOMPLETE_BATCH_OUTPUT"
            )
        }

        var quantifiedCount = 0
        var outOfRangeCount = 0
        val updated = measurements.map { measurement ->
            val prediction = requireNotNull(predictions[measurement.siteIndex])
            if (prediction.concentration != null) {
                quantifiedCount += 1
                measurement.copy(
                    concentrationValue = prediction.concentration,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = prediction.rangeStatus.name,
                    modelSnapshotJson = prediction.modelSnapshotJson,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to "QUANTIFIED",
                            "method" to "DEEP_LEARNING",
                            "rangeStatus" to prediction.rangeStatus.name
                        )
                    )
                )
            } else {
                outOfRangeCount += 1
                measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = prediction.rangeStatus.name,
                    modelSnapshotJson = prediction.modelSnapshotJson,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to "OUT_OF_RELIABLE_RANGE",
                            "method" to "DEEP_LEARNING",
                            "rangeStatus" to prediction.rangeStatus.name,
                            "concentrationSuppressed" to true
                        )
                    )
                )
            }
        }
        return QuantificationBatch(
            measurements = updated,
            modelExecutable = true,
            quantifiedCount = quantifiedCount,
            outOfRangeCount = outOfRangeCount,
            siteSignalOnlyCount = 0,
            total = updated.size
        )
    }

    /** 构造统一的模型级仅信号结果，保留全部原始和校正光度证据。 */
    private fun signalOnlyBatch(
        measurements: List<SiteMeasurement>,
        reason: String,
        extra: Map<String, Any?> = emptyMap()
    ): QuantificationBatch {
        val reasonJson = gson.toJson(
            linkedMapOf<String, Any?>(
                "status" to "SIGNAL_ONLY",
                "scope" to "MODEL",
                "reason" to reason
            ).apply { putAll(extra) }
        )
        return QuantificationBatch(
            measurements = measurements.map { measurement ->
                measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = null,
                    reliableRangeStatus = null,
                    modelSnapshotJson = null,
                    quantificationQcJson = reasonJson
                )
            },
            modelExecutable = false,
            quantifiedCount = 0,
            outOfRangeCount = 0,
            siteSignalOnlyCount = 0,
            total = measurements.size
        )
    }

    private fun modelCompatibility(
        request: GridDetectionRequest,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        feature: AnalysisPrimaryFeature
    ): ModelCompatibilityResult {
        val modality = requireNotNull(DetectionModality.fromCode(request.snapshot.template.detectionMode))
        val processorName = if (modality == DetectionModality.COLORIMETRIC) {
            COLORIMETRIC_PROCESSOR_NAME
        } else {
            FLUORESCENCE_PROCESSOR_NAME
        }
        val processorVersion = if (modality == DetectionModality.COLORIMETRIC) {
            COLORIMETRIC_PROCESSOR_VERSION
        } else {
            FLUORESCENCE_PROCESSOR_VERSION
        }
        return AnalysisModelCompatibilityChecker.check(
            model = analyteSnapshot.analysisModel.model,
            request = ModelCompatibilityRequest(
                analyteId = analyteSnapshot.analyte.id,
                modality = modality,
                inputProtocol = InputProtocol.ENDPOINT_ONLY,
                primaryFeature = feature,
                carrierType = requireNotNull(
                    CarrierType.fromCode(request.snapshot.carrierProfile.carrierType)
                ),
                acquisitionProfileId = request.snapshot.acquisitionProfile.id,
                processorName = processorName,
                processorVersion = processorVersion
            )
        )
    }

    private fun buildPersistenceBundle(
        request: GridDetectionRequest,
        gridJson: String,
        frameQcJson: String,
        measurements: List<SiteMeasurement>,
        status: String,
        modelUsageJson: String?,
        processingEvidence: List<GridProcessingEvidenceRecord> = emptyList()
    ): GridDetectionPersistenceBundle {
        // 运行快照必须描述“本次实际执行的配置对象”。不能照抄 Project 中可能来自旧版本、
        // 人工导入或已损坏的 JSON 字符串，否则历史结果会与真实处理参数不一致。
        val effectiveSnapshot = TemplateProjectSnapshotCodec.encode(request.snapshot)
        val run = DetectionRun(
            runId = request.runId,
            projectId = request.project.id,
            timestamp = request.capturedAt,
            detectionModelUsed = "OpenCV PG-Grid 2.1.0",
            concentrationModelUsed = modelUsageJson,
            status = status,
            errorMessage = null,
            confThreshold = null,
            iouThreshold = null,
            wellsDetected = measurements.size,
            effectiveConfigSnapshotJson = effectiveSnapshot,
            acquisitionMetadataJson = request.acquisitionMetadataJson,
            processingVersionJson = gson.toJson(
                mapOf(
                    "geometry" to PG_GRID_SCHEMA_V2_1,
                    "basePhotometry" to PG_QUANT_PROCESSOR_VERSION,
                    "colorimetric" to COLORIMETRIC_PROCESSOR_VERSION,
                    "fluorescence" to FLUORESCENCE_PROCESSOR_VERSION,
                    "endpointQuantifier" to ENDPOINT_QUANTIFIER_VERSION,
                    "formulaEngine" to FORMULA_ENGINE_VERSION,
                    "processingEvidence" to GRID_PROCESSING_EVIDENCE_SCHEMA
                )
            ),
            frameQcJson = frameQcJson,
            siteQcSummaryJson = summarizeSiteQc(measurements),
            // 项目样本槽映射和覆盖原因在检测时冻结到运行；结果页不得回读后来被修改的
            // Project.overrideJson。旧项目没有覆盖记录时保持 null，不能套用当前默认值。
            configurationDeviationJson = request.project.overrideJson
        )
        val artifact = CaptureArtifact(
            id = "${request.runId}-endpoint-1",
            runId = request.runId,
            captureRole = CaptureRole.ENDPOINT.code,
            originalPath = request.endpointPath,
            capturedAt = request.capturedAt,
            operatorId = request.operatorId,
            actualMetadataJson = request.acquisitionMetadataJson,
            profileSnapshotJson = gson.toJson(request.snapshot.acquisitionProfile),
            imageQcJson = frameQcJson,
            locked = true,
            revision = 1
        )
        val diagnosticArtifacts = processingEvidence.mapIndexed { index, evidence ->
            CaptureArtifact(
                id = "${request.runId}-${evidence.role.code.lowercase()}-1",
                runId = request.runId,
                captureRole = evidence.role.code,
                originalPath = evidence.path,
                capturedAt = request.capturedAt,
                operatorId = request.operatorId,
                actualMetadataJson = evidence.metadataJson,
                imageQcJson = frameQcJson,
                checksumSha256 = evidence.checksumSha256,
                pairingKey = "${request.runId}-processing",
                locked = true,
                revision = index + 1
            )
        }
        // gridJson 进入附件派生字段会误被解释为文件路径，因此保存在运行 QC/版本快照中。
        val runWithGeometry = run.copy(
            frameQcJson = gson.toJson(
                mapOf(
                    "frame" to gson.fromJson(frameQcJson, JsonObject::class.java),
                    "pgGrid" to gson.fromJson(gridJson, JsonObject::class.java)
                )
            )
        )
        return GridDetectionPersistenceBundle(
            run = runWithGeometry,
            endpointArtifact = artifact,
            measurements = measurements,
            diagnosticArtifacts = diagnosticArtifacts
        ).requireValid()
    }

    /**
     * 处理证据属于辅助可解释能力，单张 PNG 写入失败不能销毁已经完成的科学测量。
     * 失败会记录日志并返回空列表，运行的 processingVersion 仍能表明当前版本支持该能力。
     */
    private suspend fun writeProcessingEvidence(
        request: GridDetectionRequest,
        grid: PgGridResult,
        quant: PgQuantResult?
    ): List<GridProcessingEvidenceRecord> {
        return withContext(Dispatchers.IO) {
            runCatching {
                evidenceWriter.write(
                    runId = request.runId,
                    sourceBitmap = request.endpointBitmap,
                    grid = grid,
                    quant = quant
                )
            }.onFailure { error ->
                Log.w(PROCESSING_EVIDENCE_LOG_TAG, "处理证据写入失败：${request.runId}", error)
            }.getOrDefault(emptyList())
        }
    }

    private fun assignmentsForAnalyte(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): List<TemplateSiteAssignment> {
        return snapshot.siteAssignments.filter { assignment ->
            assignment.enabled && assignment.analyteId == analyteSnapshot.analyte.id
        }.sortedBy { it.rowIndex * snapshot.carrierProfile.columns + it.columnIndex }
    }

    private fun referenceIndices(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): Set<Int> {
        return snapshot.siteAssignments.filter { assignment ->
            assignment.enabled &&
                assignment.roleType in setOf(
                    TemplateSiteRole.BLANK.code,
                    TemplateSiteRole.REFERENCE.code
                ) &&
                (assignment.analyteId == null || assignment.analyteId == analyteSnapshot.analyte.id)
        }.mapTo(linkedSetOf()) { assignment ->
            assignment.rowIndex * snapshot.carrierProfile.columns + assignment.columnIndex
        }
    }

    private fun isFeatureSupported(
        modality: DetectionModality?,
        feature: AnalysisPrimaryFeature
    ): Boolean {
        return modality?.let { AnalysisFeaturePolicy.isCompatible(it, feature) } == true
    }

    private fun fluorescenceChannelValue(
        rgb: com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry,
        gray: Double,
        channel: FluorescenceChannel
    ): Double = when (channel) {
        FluorescenceChannel.RED -> rgb.red
        FluorescenceChannel.GREEN -> rgb.green
        FluorescenceChannel.BLUE -> rgb.blue
        FluorescenceChannel.GRAY -> gray
    }

    private fun mergedReliability(
        photometryReliable: Boolean,
        grid: com.muc.fluocolorquant.domain.detection.grid.PgGridResult,
        index: Int
    ): Boolean = isSiteMeasurementReliable(
        photometryReliable = photometryReliable,
        pointSource = grid.sites[index].source
    )

    /**
     * 位点可靠性只由该位点自身的光度质量和定位来源决定。
     *
     * `grid.geometry.trusted` 是整帧的保守复核提示；当算法已经形成完整晶格时，不能再用
     * 一个帧级布尔值把全部 100/225 个真实观测位点同时判为失败。模型补位仍明确标为
     * 不可靠，真实观测点则保留各自的光度判定，避免结果页出现“已测 225、可靠 0”。
     */
    internal fun isSiteMeasurementReliable(
        photometryReliable: Boolean,
        pointSource: GridPointSource
    ): Boolean {
        return photometryReliable && pointSource != GridPointSource.MODEL_IMPUTED
    }

    private fun siteQcJson(
        grid: com.muc.fluocolorquant.domain.detection.grid.PgGridResult,
        index: Int,
        photometryFlags: Set<com.muc.fluocolorquant.domain.detection.photometry.PhotometryFlag>
    ): String {
        return gson.toJson(
            mapOf(
                "geometrySource" to grid.sites[index].source,
                "geometryFlags" to grid.sites[index].flags,
                "photometryFlags" to photometryFlags
            )
        )
    }

    internal fun modelUsageEntry(
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        batch: QuantificationBatch
    ): Map<String, Any> {
        return when (compatibility) {
            ModelCompatibilityResult.Compatible -> mapOf(
                "modelId" to analyteSnapshot.analysisModel.model.id,
                "compatible" to true,
                "execution" to batch.execution,
                "quantifiedCount" to batch.quantifiedCount,
                "outOfRangeCount" to batch.outOfRangeCount,
                "siteSignalOnlyCount" to batch.siteSignalOnlyCount,
                "total" to batch.total
            )
            is ModelCompatibilityResult.Incompatible -> mapOf(
                "modelId" to analyteSnapshot.analysisModel.model.id,
                "compatible" to false,
                "reasons" to compatibility.reasons.map(Enum<*>::name),
                "execution" to "signal_only",
                "quantifiedCount" to 0,
                "outOfRangeCount" to 0,
                "siteSignalOnlyCount" to 0,
                "total" to batch.total
            )
        }
    }

    /** 运行状态只由模型级不可执行分析物决定，位点警告不会把整个运行降级。 */
    internal fun statusForSignalOnlyAnalytes(signalOnlyAnalyteIds: Set<String>): String {
        return if (signalOnlyAnalyteIds.isEmpty()) STATUS_COMPLETED else STATUS_SIGNAL_ONLY
    }

    /**
     * 多分析物运行允许不同分析物使用不同定量方案。
     * 只要已有任一有效浓度，同时仍有分析物仅保留信号，运行状态就应显示“部分定量”，
     * 不能把已经生成的浓度和现场曲线统称为“仅信号”。
     */
    internal fun statusForQuantification(
        signalOnlyAnalyteIds: Set<String>,
        measurements: List<SiteMeasurement>
    ): String {
        return when {
            signalOnlyAnalyteIds.isEmpty() -> STATUS_COMPLETED
            measurements.any { it.concentrationValue?.isFinite() == true } -> STATUS_PARTIAL
            else -> STATUS_SIGNAL_ONLY
        }
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

    /** 损坏的量化 QC JSON 不应中断运行汇总；无法解析时只是不计入位点警告。 */
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

    private data class PreflightResult(
        val reasons: Set<GridDetectionBlockReason>,
        val targetPolarity: GridTargetPolarity?
    )

    private data class ProcessedMeasurements(
        val measurements: List<SiteMeasurement>,
        val signalOnlyAnalyteIds: Set<String>,
        val modelUsage: Map<String, Any>
    )

    internal data class QuantificationBatch(
        val measurements: List<SiteMeasurement>,
        val modelExecutable: Boolean,
        val quantifiedCount: Int,
        val outOfRangeCount: Int,
        val siteSignalOnlyCount: Int,
        val total: Int
    ) {
        /** 模型可执行但存在范围或位点信号警告时，保留成功浓度并明确记录警告。 */
        val execution: String = when {
            !modelExecutable -> "signal_only"
            outOfRangeCount > 0 || siteSignalOnlyCount > 0 -> "standard_curve_applied_with_warnings"
            else -> "standard_curve_applied"
        }
    }

    private companion object {
        const val PROCESSING_EVIDENCE_LOG_TAG: String = "GridProcessingEvidence"
        const val STATUS_SIGNAL_ONLY: String = "SignalOnlyCompleted"
        const val STATUS_PARTIAL: String = "PartiallyQuantified"
        const val STATUS_COMPLETED: String = "Completed"
    }
}
