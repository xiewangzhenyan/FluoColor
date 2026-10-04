package com.muc.fluocolorquant.domain.detection

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.application.detection.GridDetectionPersistenceAssembler
import com.muc.fluocolorquant.application.detection.GridDetectionPersistenceInput
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
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
import com.muc.fluocolorquant.domain.detection.evidence.GridPersistedSourceInput
import com.muc.fluocolorquant.domain.detection.evidence.NoOpGridProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.NoOpPlate96ProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.evidence.PLATE96_PROCESSING_EVIDENCE_SCHEMA
import com.muc.fluocolorquant.domain.detection.evidence.Plate96ProcessingEvidenceWriter
import com.muc.fluocolorquant.domain.detection.quantification.ENDPOINT_QUANTIFIER_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.FORMULA_ENGINE_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningBatchResult
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningFailureReason
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningSiteFailure
import com.muc.fluocolorquant.domain.detection.quantification.GRID_DEEP_LEARNING_QUANTIFIER_VERSION
import com.muc.fluocolorquant.domain.detection.quantification.GridOnsiteCalibrationService
import com.muc.fluocolorquant.domain.detection.quantification.UnavailableGridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.PreparedEndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.QuantificationObservation
import com.muc.fluocolorquant.domain.detection.quantification.QuantificationCensoringDirection
import com.muc.fluocolorquant.domain.detection.quantification.QuantificationState
import com.muc.fluocolorquant.domain.detection.quantification.RangeCorrectionAnchor
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryDecision
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryEngine
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryObservation
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryStatus
import com.muc.fluocolorquant.domain.detection.quantification.ReliableRangeStatus
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
import com.muc.fluocolorquant.domain.detection.plate96.PLATE96_RUN_GEOMETRY_SCHEMA_V1
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunGeometryCodec
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunGeometrySnapshot
import com.muc.fluocolorquant.domain.detection.plate96.Plate96ScientificSamplingAdapter
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        UnavailableGridDeepLearningExecutor,
    /** 现场标准品的信号组装、候选拟合和冻结适配由独立科学服务负责。 */
    private val onsiteCalibrationService: GridOnsiteCalibrationService =
        GridOnsiteCalibrationService(),
    /** 96孔板使用独立圆孔过程图；测试默认不写文件。 */
    private val plate96EvidenceWriter: Plate96ProcessingEvidenceWriter =
        NoOpPlate96ProcessingEvidenceWriter,
    /** 只组装运行、附件与位点实体，不执行数据库或文件 IO。 */
    private val persistenceAssembler: GridDetectionPersistenceAssembler =
        GridDetectionPersistenceAssembler()
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
            GridCarrierRoute.PLATE96 -> return GridLocalizationOutcome.LegacyPlateRequired
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
        val persistedSourceInput = writeSourceInput(request)
        val processingEvidence = writeProcessingEvidence(request, grid, quant)
        return GridLocalizationOutcome.Ready(
            GridLocalizationSession(
                request = request,
                grid = grid,
                quant = quant,
                frameQcJson = frameQcJson,
                persistedSourceInput = persistedSourceInput,
                processingEvidence = processingEvidence,
                presentation = GridLocalizationPresentation.MICROFLUIDIC,
                geometryPersistence = GridGeometryPersistence(
                    jsonKey = "pgGrid",
                    schemaVersion = PG_GRID_SCHEMA_V2_1,
                    json = PgGridJsonCodec.encode(grid)
                ),
                // 处理器版本必须跟随定位器实际版本，不能写死：它会冻结进运行快照，
                // 是历史结果“用哪一版算法算出来的”唯一凭据。
                detectionModelUsed = "${grid.locatorName} ${grid.locatorVersion}",
                processingVersions = linkedMapOf(
                    "geometry" to PG_GRID_SCHEMA_V2_1,
                    "basePhotometry" to PG_QUANT_PROCESSOR_VERSION,
                    "colorimetric" to COLORIMETRIC_PROCESSOR_VERSION,
                    "fluorescence" to FLUORESCENCE_PROCESSOR_VERSION,
                    "endpointQuantifier" to ENDPOINT_QUANTIFIER_VERSION,
                    "formulaEngine" to FORMULA_ENGINE_VERSION,
                    "processingEvidence" to GRID_PROCESSING_EVIDENCE_SCHEMA
                ),
                frameQcIssueCount = grid.frameQc.size
            )
        )
    }

    /**
     * 接收用户已经确认和微调完成的96孔板定位会话，生成一次可复用的光度会话。
     *
     * 方向与圆孔定位在进入本方法前已经完成；这里不会再次运行YOLO或霍夫圆，只执行一次
     * 原图科学采样、过程证据写入，并把独立`plate96Geometry`冻结到后续运行。
     */
    suspend fun preparePlate96Localization(
        request: GridDetectionRequest,
        normalizedBitmap: Bitmap,
        locatorSession: Plate96Locator.Session,
        exifRotationDegrees: Int = 0,
        exifFlipped: Boolean = false
    ): GridLocalizationOutcome {
        request.onStageChanged(GridDetectionStage.PREPARING)
        val carrierType = CarrierType.fromCode(request.snapshot.carrierProfile.carrierType)
        if (
            carrierType != CarrierType.PLATE ||
            SiteShape.fromCode(request.snapshot.carrierProfile.siteShape) != SiteShape.CIRCLE ||
            request.snapshot.carrierProfile.rows != 8 ||
            request.snapshot.carrierProfile.columns != 12
        ) {
            return GridLocalizationOutcome.Blocked(setOf(GridDetectionBlockReason.UNSUPPORTED_CARRIER))
        }
        val preflight = preflight(
            request = request,
            requireSiteAssignments = false,
            requireLocatorConfig = false
        )
        if (preflight.reasons.isNotEmpty()) {
            return GridLocalizationOutcome.Blocked(preflight.reasons)
        }

        val localization = locatorSession.result.requireValid()
        val geometry = Plate96RunGeometrySnapshot.from(localization)
        val sampling = Plate96ScientificSamplingAdapter.create(localization)
        request.onStageChanged(GridDetectionStage.PHOTOMETRY)
        val quant = withContext(Dispatchers.Default) {
            PgQuantSampler.sample(
                bitmap = request.endpointBitmap,
                grid = sampling.grid,
                unitSegmentation = sampling.segmentation
            )
        }
        val frameQcJson = gson.toJson(
            linkedMapOf(
                "geometry" to localization.diagnostics,
                "orientation" to localization.orientation,
                "issues" to emptyList<Any>()
            )
        )
        request.onStageChanged(GridDetectionStage.RENDERING_EVIDENCE)
        val persistedSourceInput = writeSourceInput(request)
        val processingEvidence = withContext(Dispatchers.IO) {
            runCatching {
                plate96EvidenceWriter.write(
                    runId = request.runId,
                    sourceBitmap = request.endpointBitmap,
                    normalizedBitmap = normalizedBitmap,
                    session = locatorSession,
                    quant = quant
                )
            }.onFailure { error ->
                Log.w(PROCESSING_EVIDENCE_LOG_TAG, "96孔板处理证据写入失败：${request.runId}", error)
            }.getOrDefault(emptyList())
        }
        val requestWithOrientation = request.copy(
            acquisitionMetadataJson = Plate96RunGeometryCodec.mergeAcquisitionMetadata(
                existingJson = request.acquisitionMetadataJson,
                geometry = geometry,
                exifRotationDegrees = exifRotationDegrees,
                exifFlipped = exifFlipped
            )
        )
        val locatorModeName = when (locatorSession.locatorMode) {
            ArrayLocatorMode.AUTO -> "AUTO_YOLO_HOUGH_CONTOUR_GRID"
            ArrayLocatorMode.OBJECT_DETECTION -> "YOLO_ONLY"
            ArrayLocatorMode.GEOMETRIC_SHAPE -> "YOLO_HOUGH_CONTOUR"
        }
        return GridLocalizationOutcome.Ready(
            GridLocalizationSession(
                request = requestWithOrientation,
                grid = sampling.grid,
                quant = quant,
                frameQcJson = frameQcJson,
                persistedSourceInput = persistedSourceInput,
                processingEvidence = processingEvidence,
                presentation = GridLocalizationPresentation.PLATE96,
                geometryPersistence = GridGeometryPersistence(
                    jsonKey = "plate96Geometry",
                    schemaVersion = PLATE96_RUN_GEOMETRY_SCHEMA_V1,
                    json = Plate96RunGeometryCodec.encode(geometry)
                ),
                detectionModelUsed = "Plate96 $locatorModeName ${localization.locatorVersion}",
                processingVersions = linkedMapOf(
                    "geometry" to PLATE96_RUN_GEOMETRY_SCHEMA_V1,
                    "locatorMode" to locatorSession.locatorMode.name,
                    "orientation" to localization.orientation.schemaVersion,
                    "imageTransform" to localization.imageTransform.processorVersion,
                    "basePhotometry" to PG_QUANT_PROCESSOR_VERSION,
                    "colorimetric" to COLORIMETRIC_PROCESSOR_VERSION,
                    "fluorescence" to FLUORESCENCE_PROCESSOR_VERSION,
                    "endpointQuantifier" to ENDPOINT_QUANTIFIER_VERSION,
                    "formulaEngine" to FORMULA_ENGINE_VERSION,
                    "processingEvidence" to PLATE96_PROCESSING_EVIDENCE_SCHEMA
                ),
                confidenceThreshold = Plate96Locator.DEFAULT_CONFIG.confidenceThreshold,
                iouThreshold = Plate96Locator.DEFAULT_CONFIG.iouThreshold,
                frameQcIssueCount = 0
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
        // 现场曲线已经在用户点击“应用此曲线”时冻结。这里直接执行最终快照，禁止因为
        // 线程时序、算法升级或数值初值变化再次拟合出与用户所见不同的曲线。
        val request = session.request.copy(snapshot = finalizedSnapshot)
        val preflight = preflight(
            request = request,
            requireSiteAssignments = true,
            requireLocatorConfig = session.presentation == GridLocalizationPresentation.MICROFLUIDIC
        )
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

        val bundle = persistenceAssembler.assemble(
            GridDetectionPersistenceInput(
                project = request.project,
                snapshot = request.snapshot,
                runId = request.runId,
                capturedAt = request.capturedAt,
                endpointPath = request.endpointPath,
                operatorId = request.operatorId,
                acquisitionMetadataJson = request.acquisitionMetadataJson,
                geometryPersistence = session.geometryPersistence,
                frameQcJson = session.frameQcJson,
                measurements = processed.measurements,
                status = statusForQuantification(
                    signalOnlyAnalyteIds = processed.signalOnlyAnalyteIds,
                    measurements = processed.measurements
                ),
                modelUsageJson = gson.toJson(processed.modelUsage),
                persistedSourceInput = session.persistedSourceInput,
                processingEvidence = session.processingEvidence,
                detectionModelUsed = session.detectionModelUsed,
                processingVersions = session.processingVersions,
                confidenceThreshold = session.confidenceThreshold,
                iouThreshold = session.iouThreshold
            )
        )
        request.onStageChanged(GridDetectionStage.PERSISTING)
        withContext(Dispatchers.IO) { repository.save(bundle) }
        request.onStageChanged(GridDetectionStage.COMPLETED)
        return GridDetectionOutcome.Completed(
            runId = request.runId,
            measurementCount = processed.measurements.size,
            signalOnlyAnalyteIds = processed.signalOnlyAnalyteIds,
            frameQcIssueCount = session.frameQcIssueCount,
            effectiveSnapshot = request.snapshot
        )
    }

    /**
     * 将用户在结果阶段选中的现场曲线冻结到项目分析物快照。
     *
     * 调用方必须传入拟合阶段已经生成的 [CalibrationResultSet] 和候选ID。本方法只完成
     * 冻结与既有标准曲线执行契约的适配，绝不读取标准孔信号或重新调用拟合引擎。
     */
    internal fun applyOnsiteCalibrationSelection(
        snapshot: TemplateProjectSnapshot,
        resultSet: CalibrationResultSet,
        selectedCandidateId: String,
        runId: String,
        sourceResourceId: String? = null
    ): TemplateProjectSnapshot = onsiteCalibrationService.applySelection(
        snapshot = snapshot,
        resultSet = resultSet,
        selectedCandidateId = selectedCandidateId,
        runId = runId,
        sourceResourceId = sourceResourceId
    )

    /**
     * 根据本次真实标准孔，为一个分析物比较“候选信号 × 线性/4PL/5PL”。
     *
     * 普通模式不要求用户理解像素公式：未指定高级选项时会遍历当前检测模态允许的全部
     * 主信号，并按验收状态、标准点接受率、反算误差、标准化 RMSE 和 R² 选择最稳妥
     * 组合。原始 RMSE/MAE 只在同一信号的详情中展示，禁止跨量纲比较。高级模式只会
     * 缩小候选集合，不允许输入任意函数名或参数 JSON。
     */
    internal fun previewOnsiteCalibration(
        snapshot: TemplateProjectSnapshot,
        quant: PgQuantResult,
        analyteId: String,
        policy: CalibrationPolicy = CalibrationPolicy.DEFAULT
    ): CalibrationResultSet = onsiteCalibrationService.preview(
        snapshot = snapshot,
        quant = quant,
        analyteId = analyteId,
        policy = policy
    )

    private fun preflight(
        request: GridDetectionRequest,
        requireSiteAssignments: Boolean,
        /** 只有PG-Grid需要极性配置；圆孔板由独立定位器冻结圆形几何。 */
        requireLocatorConfig: Boolean = true
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

        val polarity = if (requireLocatorConfig) {
            ScientificDetectionConfigCodec.decodeCarrierPolarity(
                snapshot.carrierProfile.locatorConfigJson
            ).also { decoded ->
                if (snapshot.carrierProfile.locatorConfigJson.isNullOrBlank()) {
                    reasons += GridDetectionBlockReason.MISSING_LOCATOR_CONFIG
                } else if (decoded == null) {
                    reasons += GridDetectionBlockReason.INVALID_LOCATOR_CONFIG
                }
            }
        } else {
            null
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
                feature != null &&
                AnalysisFeaturePolicy.requiresReference(feature) &&
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
            ).withDynamicRangeReview(
                snapshot = request.snapshot,
                analyteSnapshot = analyteSnapshot
            )
            measurements += quantified.measurements
            if (quantified.isSignalOnlyResult) signalOnly += analyteSnapshot.analyte.id
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
            ).withDynamicRangeReview(
                snapshot = request.snapshot,
                analyteSnapshot = analyteSnapshot
            )
            measurements += quantified.measurements
            if (quantified.isSignalOnlyResult) signalOnly += analyteSnapshot.analyte.id
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
     * 标定范围外但仍落在项目量程内的结果保留浓度并标记为外推；只有超出项目有界
     * 反算域时才不写浓度。模型定义不可执行时保留全部信号并把整个分析物标记为仅信号。
     */
    internal fun applyQuantification(
        measurements: List<SiteMeasurement>,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        compatibility: ModelCompatibilityResult,
        prepareQuantifier: (AnalysisModelBundle) -> PreparedStandardCurveQuantifier =
            { bundle ->
                StandardCurveQuantifier.prepare(
                    bundle = bundle,
                    projectRangeMin = analyteSnapshot.templateConfig.reliableRangeMin,
                    projectRangeMax = analyteSnapshot.templateConfig.reliableRangeMax
                )
            }
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
                measurements = measurements.map { measurement ->
                    measurement.asUnavailableStandardCurveMeasurement(reasonJson)
                },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size,
                unavailableCount = measurements.size
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
                measurements = measurements.map { measurement ->
                    measurement.asUnavailableStandardCurveMeasurement(reasonJson)
                },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size,
                unavailableCount = measurements.size
            )
        }
        val ready = prepared as PreparedStandardCurveQuantifier.Ready
        var quantifiedCount = 0
        var outOfRangeCount = 0
        var extrapolatedCount = 0
        var siteSignalOnlyCount = 0
        var estimatedCount = 0
        var boundOnlyCount = 0
        var unavailableCount = 0
        val evaluated = measurements.map { measurement ->
            measurement to ready.quantify(measurement.toQuantificationObservation())
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
                    measurement.asUnavailableStandardCurveMeasurement(reasonJson)
                },
                modelExecutable = false,
                quantifiedCount = 0,
                outOfRangeCount = 0,
                siteSignalOnlyCount = 0,
                total = measurements.size,
                unavailableCount = measurements.size
            )
        }

        val quantifiedMeasurements = evaluated.map { (measurement, result) ->
            when (result) {
                is PreparedEndpointQuantificationResult.Quantified -> {
                    val extrapolated = result.rangeStatus in setOf(
                        ReliableRangeStatus.BELOW_RANGE,
                        ReliableRangeStatus.ABOVE_RANGE
                    )
                    measurement.copy(
                        concentrationValue = result.concentration,
                        concentrationUnit = result.unit,
                        reliableRangeStatus = result.rangeStatus.name,
                        modelSnapshotJson = result.modelSnapshotJson,
                        quantificationState = result.quantificationState.name,
                        concentrationLowerBound = result.concentrationLowerBound,
                        concentrationUpperBound = result.concentrationUpperBound,
                        intervalConfidenceLevel = result.intervalConfidenceLevel,
                        censoringDirection = null,
                        quantificationVersion = ENDPOINT_QUANTIFIER_VERSION,
                        quantificationQcJson = gson.toJson(
                            mapOf(
                                "status" to result.quantificationState.name,
                                "rangeStatus" to result.rangeStatus.name,
                                "intervalConfidenceLevel" to result.intervalConfidenceLevel
                            )
                        )
                    ).also {
                        when (result.quantificationState) {
                            QuantificationState.QUANTIFIED -> quantifiedCount += 1
                            QuantificationState.ESTIMATED -> {
                                estimatedCount += 1
                                extrapolatedCount += 1
                            }
                            // Quantified 结果的领域契约不应携带这两种状态；保留防御分支，
                            // 避免未来扩展时把单侧界限或不可用结果误计为精确浓度。
                            QuantificationState.BOUND_ONLY -> boundOnlyCount += 1
                            QuantificationState.UNAVAILABLE -> unavailableCount += 1
                        }
                        if (extrapolated && result.quantificationState != QuantificationState.ESTIMATED) {
                            extrapolatedCount += 1
                        }
                    }
                }

                is PreparedEndpointQuantificationResult.OutOfRange -> measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = result.rangeStatus.name,
                    modelSnapshotJson = result.modelSnapshotJson,
                    quantificationState = result.quantificationState.name,
                    concentrationLowerBound = when (result.censoringDirection) {
                        QuantificationCensoringDirection.LOWER_BOUND -> result.concentrationBound
                        else -> null
                    },
                    concentrationUpperBound = when (result.censoringDirection) {
                        QuantificationCensoringDirection.UPPER_BOUND -> result.concentrationBound
                        else -> null
                    },
                    intervalConfidenceLevel = null,
                    censoringDirection = result.censoringDirection.name,
                    quantificationVersion = ENDPOINT_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to result.quantificationState.name,
                            "rangeStatus" to result.rangeStatus.name,
                            "concentrationBound" to result.concentrationBound,
                            "censoringDirection" to result.censoringDirection.name,
                            "concentrationUnavailable" to true
                        )
                    )
                ).also {
                    outOfRangeCount += 1
                    boundOnlyCount += 1
                }

                PreparedEndpointQuantificationResult.SiteSignalOnly -> measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = null,
                    modelSnapshotJson = null,
                    quantificationState = QuantificationState.UNAVAILABLE.name,
                    concentrationLowerBound = null,
                    concentrationUpperBound = null,
                    intervalConfidenceLevel = null,
                    censoringDirection = null,
                    quantificationVersion = ENDPOINT_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to QuantificationState.UNAVAILABLE.name,
                            "scope" to "SITE",
                            "reason" to PreparedEndpointQuantificationResult.SiteSignalOnly.reason.name
                        )
                    )
                ).also {
                    siteSignalOnlyCount += 1
                    unavailableCount += 1
                }

                is PreparedEndpointQuantificationResult.Unavailable -> measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    modelSnapshotJson = result.modelSnapshotJson,
                    quantificationState = result.quantificationState.name,
                    concentrationLowerBound = null,
                    concentrationUpperBound = null,
                    intervalConfidenceLevel = null,
                    censoringDirection = null,
                    quantificationVersion = ENDPOINT_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to result.quantificationState.name,
                            "scope" to "SITE",
                            "reason" to result.reason.name
                        )
                    )
                ).also {
                    siteSignalOnlyCount += 1
                    unavailableCount += 1
                }

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
            total = quantifiedMeasurements.size,
            extrapolatedCount = extrapolatedCount,
            estimatedCount = estimatedCount,
            boundOnlyCount = boundOnlyCount,
            unavailableCount = unavailableCount
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
     * 模型兼容、文件、SHA、输入协议或模型加载失败时，整分析物从原始测量重建为“仅信号”。
     * 已成功执行但输出越出声明域的单个位点只影响自身，其余有效浓度必须保留。
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
        val success = execution as GridDeepLearningBatchResult.Success
        val predictions = success.predictions
        val siteFailures = success.siteFailures
        val overlappingIndices = predictions.keys intersect siteFailures.keys
        val producedIndices = predictions.keys + siteFailures.keys
        if (overlappingIndices.isNotEmpty() ||
            producedIndices.size != measurements.size ||
            measurements.any { measurement -> measurement.siteIndex !in producedIndices }
        ) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = "INCOMPLETE_BATCH_OUTPUT"
            )
        }
        val model = analyteSnapshot.analysisModel.model
        val outputConsistent = model.reliableRangeMin.isFinite() &&
            model.reliableRangeMax.isFinite() &&
            model.reliableRangeMax > model.reliableRangeMin &&
            predictions.all { (siteIndex, prediction) ->
                prediction.siteIndex == siteIndex && when (prediction.rangeStatus) {
                    ReliableRangeStatus.WITHIN_RANGE ->
                        prediction.concentration?.isFinite() == true
                    ReliableRangeStatus.BELOW_RANGE,
                    ReliableRangeStatus.ABOVE_RANGE -> prediction.concentration == null
                    // 深度学习执行器当前只声明模型可靠范围，不得把标准曲线的可信扩展或
                    // 项目量程状态混入该批次，否则无法确定单侧界限来自哪一条冻结边界。
                    else -> false
                }
            } && siteFailures.all { (siteIndex, failure) ->
                failure.siteIndex == siteIndex &&
                failure.reason == GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE &&
                    failure.rawModelOutput.isFinite() &&
                    failure.transformedModelOutput.isFinite() &&
                    failure.declaredOutputMin.isFinite() &&
                    failure.declaredOutputMax.isFinite() &&
                    failure.declaredOutputMax > failure.declaredOutputMin &&
                    (failure.transformedModelOutput < failure.declaredOutputMin ||
                        failure.transformedModelOutput > failure.declaredOutputMax) &&
                    failure.modelSnapshotJson.isNotBlank()
            }
        if (!outputConsistent) {
            return signalOnlyBatch(
                measurements = measurements,
                reason = "INCONSISTENT_BATCH_OUTPUT"
            )
        }

        var quantifiedCount = 0
        var outOfRangeCount = 0
        var unavailableCount = 0
        val updated = measurements.map { measurement ->
            val siteFailure = siteFailures[measurement.siteIndex]
            val prediction = predictions[measurement.siteIndex]
            if (siteFailure != null) {
                unavailableCount += 1
                measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = model.concentrationUnit,
                    reliableRangeStatus = null,
                    modelSnapshotJson = siteFailure.modelSnapshotJson,
                    quantificationState = QuantificationState.UNAVAILABLE.name,
                    concentrationLowerBound = null,
                    concentrationUpperBound = null,
                    intervalConfidenceLevel = null,
                    censoringDirection = null,
                    quantificationVersion = GRID_DEEP_LEARNING_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        linkedMapOf(
                            "status" to QuantificationState.UNAVAILABLE.name,
                            "scope" to "SITE",
                            "method" to "DEEP_LEARNING",
                            "reason" to siteFailure.reason.name,
                            "rawModelOutput" to siteFailure.rawModelOutput,
                            "transformedModelOutput" to siteFailure.transformedModelOutput,
                            "declaredOutputMin" to siteFailure.declaredOutputMin,
                            "declaredOutputMax" to siteFailure.declaredOutputMax
                        )
                    )
                )
            } else if (requireNotNull(prediction).concentration != null) {
                quantifiedCount += 1
                measurement.copy(
                    concentrationValue = prediction.concentration,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = prediction.rangeStatus.name,
                    modelSnapshotJson = prediction.modelSnapshotJson,
                    quantificationState = QuantificationState.QUANTIFIED.name,
                    concentrationLowerBound = prediction.concentration,
                    concentrationUpperBound = prediction.concentration,
                    intervalConfidenceLevel = null,
                    censoringDirection = QuantificationCensoringDirection.NONE.name,
                    quantificationVersion = GRID_DEEP_LEARNING_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to QuantificationState.QUANTIFIED.name,
                            "method" to "DEEP_LEARNING",
                            "rangeStatus" to prediction.rangeStatus.name
                        )
                    )
                )
            } else {
                outOfRangeCount += 1
                val belowRange = prediction.rangeStatus == ReliableRangeStatus.BELOW_RANGE
                val concentrationBound = if (belowRange) {
                    model.reliableRangeMin
                } else {
                    model.reliableRangeMax
                }
                val censoringDirection = if (belowRange) {
                    QuantificationCensoringDirection.UPPER_BOUND
                } else {
                    QuantificationCensoringDirection.LOWER_BOUND
                }
                measurement.copy(
                    concentrationValue = null,
                    concentrationUnit = analyteSnapshot.analysisModel.model.concentrationUnit,
                    reliableRangeStatus = prediction.rangeStatus.name,
                    modelSnapshotJson = prediction.modelSnapshotJson,
                    // 模型范围外不能伪造点浓度，但冻结的可靠范围本身足以形成单侧界限。
                    // ABOVE_RANGE 保存“浓度 > 上限”，BELOW_RANGE 保存“浓度 < 下限”。
                    quantificationState = QuantificationState.BOUND_ONLY.name,
                    concentrationLowerBound = concentrationBound.takeIf { !belowRange },
                    concentrationUpperBound = concentrationBound.takeIf { belowRange },
                    intervalConfidenceLevel = null,
                    censoringDirection = censoringDirection.name,
                    quantificationVersion = GRID_DEEP_LEARNING_QUANTIFIER_VERSION,
                    quantificationQcJson = gson.toJson(
                        mapOf(
                            "status" to QuantificationState.BOUND_ONLY.name,
                            "method" to "DEEP_LEARNING",
                            "rangeStatus" to prediction.rangeStatus.name,
                            "concentrationBound" to concentrationBound,
                            "censoringDirection" to censoringDirection.name,
                            "concentrationUnavailable" to true
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
            siteSignalOnlyCount = unavailableCount,
            total = updated.size,
            boundOnlyCount = outOfRangeCount,
            unavailableCount = unavailableCount,
            deepLearningSiteFailures = siteFailures.values.sortedBy { it.siteIndex },
            diagnosticReasons = siteFailures.values.mapTo(linkedSetOf()) { it.reason.name }
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
                    // 模型级失败必须同时清空强类型量化字段。否则同一内存测量被重试时，
                    // 可能残留上一次成功得到的浓度界限，并在结果页被误认为本次结果。
                    quantificationState = QuantificationState.UNAVAILABLE.name,
                    concentrationLowerBound = null,
                    concentrationUpperBound = null,
                    intervalConfidenceLevel = null,
                    censoringDirection = null,
                    quantificationVersion = null,
                    quantificationQcJson = reasonJson
                )
            },
            modelExecutable = false,
            quantifiedCount = 0,
            outOfRangeCount = 0,
            siteSignalOnlyCount = 0,
            total = measurements.size,
            unavailableCount = measurements.size,
            diagnosticReasons = setOf(reason)
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

    /**
     * 原始输入属于历史恢复的核心科学附件，因此优先尝试长期保存。
     *
     * 文件系统异常不能销毁已经完成的定位结果：失败时记录日志并返回 null，持久化阶段会
     * 回退到 request.endpointPath。后续 ZIP 清单仍会如实标注该附件是否可读取。
     */
    private suspend fun writeSourceInput(
        request: GridDetectionRequest
    ): GridPersistedSourceInput? {
        return withContext(Dispatchers.IO) {
            runCatching {
                evidenceWriter.writeSourceInput(
                    runId = request.runId,
                    sourceBitmap = request.endpointBitmap
                )
            }.onFailure { error ->
                Log.w(PROCESSING_EVIDENCE_LOG_TAG, "运行原始输入写入失败：${request.runId}", error)
            }.getOrNull()
        }
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

    /**
     * 在普通定量完成后执行批次级动态量程复核。
     *
     * 只有 SAMPLE 位点进入“多数越界”比例；标准孔、空白、参考和质控孔均被排除。正/负
     * 质控若同时保存了已知浓度并成功定量，可作为独立锚点。没有独立锚点时仅冻结诊断，
     * 绝不会依据未知样品分布把浓度强制压回项目量程。
     */
    internal fun QuantificationBatch.withDynamicRangeReview(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): QuantificationBatch {
        val projectMinimum = analyteSnapshot.templateConfig.reliableRangeMin
            ?: analyteSnapshot.analysisModel.model.reliableRangeMin
        val projectMaximum = analyteSnapshot.templateConfig.reliableRangeMax
            ?: analyteSnapshot.analysisModel.model.reliableRangeMax
        if (
            !projectMinimum.isFinite() ||
            !projectMaximum.isFinite() ||
            projectMinimum < 0.0 ||
            projectMaximum <= projectMinimum
        ) return this
        val frozenCalibrationMinimum = analyteSnapshot.analysisModel.model.reliableRangeMin
        val frozenCalibrationMaximum = analyteSnapshot.analysisModel.model.reliableRangeMax
        val hasValidCalibrationRange = frozenCalibrationMinimum.isFinite() &&
            frozenCalibrationMaximum.isFinite() &&
            frozenCalibrationMaximum > frozenCalibrationMinimum
        val calibrationMinimum = if (hasValidCalibrationRange) {
            frozenCalibrationMinimum
        } else {
            projectMinimum
        }
        val calibrationMaximum = if (hasValidCalibrationRange) {
            frozenCalibrationMaximum
        } else {
            projectMaximum
        }

        val assignments = assignmentsForAnalyte(snapshot, analyteSnapshot)
        val assignmentBySite = assignments.associateBy { assignment ->
            assignment.rowIndex * snapshot.carrierProfile.columns + assignment.columnIndex
        }
        val observations = measurements.map { measurement ->
            val assignment = assignmentBySite[measurement.siteIndex]
            RangeRecoveryObservation(
                siteIndex = measurement.siteIndex,
                isSample = assignment?.roleType == TemplateSiteRole.SAMPLE.code,
                concentration = measurement.concentrationValue,
                lowerBound = measurement.concentrationLowerBound,
                upperBound = measurement.concentrationUpperBound,
                rangeStatus = measurement.reliableRangeStatus?.let { status ->
                    runCatching { ReliableRangeStatus.valueOf(status) }.getOrNull()
                },
                quantificationState = measurement.quantificationState?.let { state ->
                    runCatching { QuantificationState.valueOf(state) }.getOrNull()
                }
            )
        }
        val measurementBySite = measurements.associateBy(SiteMeasurement::siteIndex)
        val anchors = assignmentBySite.mapNotNull { (siteIndex, assignment) ->
            if (
                assignment.roleType !in setOf(
                    TemplateSiteRole.NEGATIVE_CONTROL.code,
                    TemplateSiteRole.POSITIVE_CONTROL.code
                )
            ) return@mapNotNull null
            val expected = assignment.standardConcentration?.takeIf(Double::isFinite)
                ?: return@mapNotNull null
            val measurement = measurementBySite[siteIndex] ?: return@mapNotNull null
            val observed = measurement.concentrationValue?.takeIf(Double::isFinite)
                ?: return@mapNotNull null
            RangeCorrectionAnchor(
                expectedConcentration = expected,
                observedConcentration = observed,
                reliable = measurement.qualityReliable
            )
        }
        val decision = RangeRecoveryEngine.evaluate(
            observations = observations,
            projectMinimum = projectMinimum,
            projectMaximum = projectMaximum,
            anchors = anchors
        )
        val reviewedMeasurements = if (decision.status == RangeRecoveryStatus.CORRECTION_APPLIED) {
            measurements.map { measurement ->
                measurement.withAcceptedRangeCorrection(
                    decision = decision,
                    projectMinimum = projectMinimum,
                    projectMaximum = projectMaximum,
                    calibrationMinimum = calibrationMinimum,
                    calibrationMaximum = calibrationMaximum
                )
            }
        } else {
            measurements
        }
        return withReviewedMeasurements(
            reviewedMeasurements = reviewedMeasurements,
            decision = decision
        )
    }

    /**
     * 将已经通过独立质控验收的仿射校正应用到点估计和双侧区间，并把原判定一并写入
     * 位点 QC JSON。单侧界限和 null 浓度不在此阶段猜测，仍保持原始 <下限 / >上限。
     */
    private fun SiteMeasurement.withAcceptedRangeCorrection(
        decision: RangeRecoveryDecision,
        projectMinimum: Double,
        projectMaximum: Double,
        calibrationMinimum: Double,
        calibrationMaximum: Double
    ): SiteMeasurement {
        val originalConcentration = concentrationValue?.takeIf(Double::isFinite) ?: return this
        val originalState = quantificationState?.let { state ->
            runCatching { QuantificationState.valueOf(state) }.getOrNull()
        } ?: return this
        if (originalState !in setOf(QuantificationState.QUANTIFIED, QuantificationState.ESTIMATED)) {
            return this
        }
        val scale = decision.correctionScale?.takeIf(Double::isFinite) ?: return this
        val offset = decision.correctionOffset?.takeIf(Double::isFinite) ?: return this
        val rawCorrected = scale * originalConcentration + offset
        if (!rawCorrected.isFinite()) return this

        // 项目量程是本次运行允许报告精确点浓度的硬边界。质控校正后的点一旦越过该边界，
        // 必须降为单侧界限，不能形成“超项目量程但仍携带精确浓度”的损坏历史快照。
        val correctedStatus = when {
            rawCorrected < projectMinimum -> ReliableRangeStatus.BELOW_PROJECT_RANGE
            rawCorrected > projectMaximum -> ReliableRangeStatus.ABOVE_PROJECT_RANGE
            rawCorrected < calibrationMinimum -> ReliableRangeStatus.BELOW_RANGE
            rawCorrected > calibrationMaximum -> ReliableRangeStatus.ABOVE_RANGE
            else -> ReliableRangeStatus.WITHIN_RANGE
        }
        val correctedState = when {
            correctedStatus in setOf(
                ReliableRangeStatus.BELOW_PROJECT_RANGE,
                ReliableRangeStatus.ABOVE_PROJECT_RANGE
            ) -> QuantificationState.BOUND_ONLY
            originalState == QuantificationState.ESTIMATED ||
                correctedStatus != ReliableRangeStatus.WITHIN_RANGE -> QuantificationState.ESTIMATED
            else -> QuantificationState.QUANTIFIED
        }
        val pointConcentration = rawCorrected
            .takeIf { correctedState != QuantificationState.BOUND_ONLY }
            ?.coerceAtLeast(0.0)
        fun correctedIntervalBound(value: Double?): Double? = value
            ?.takeIf(Double::isFinite)
            ?.let { bound -> scale * bound + offset }
            ?.takeIf(Double::isFinite)
            ?.coerceAtLeast(0.0)
        val correctedLower = when (correctedStatus) {
            ReliableRangeStatus.ABOVE_PROJECT_RANGE -> projectMaximum
            ReliableRangeStatus.BELOW_PROJECT_RANGE -> null
            else -> correctedIntervalBound(concentrationLowerBound)
        }
        val correctedUpper = when (correctedStatus) {
            ReliableRangeStatus.BELOW_PROJECT_RANGE -> projectMinimum
            ReliableRangeStatus.ABOVE_PROJECT_RANGE -> null
            else -> correctedIntervalBound(concentrationUpperBound)
        }
        val correctedCensoring = when (correctedStatus) {
            ReliableRangeStatus.BELOW_PROJECT_RANGE ->
                QuantificationCensoringDirection.UPPER_BOUND.name
            ReliableRangeStatus.ABOVE_PROJECT_RANGE ->
                QuantificationCensoringDirection.LOWER_BOUND.name
            else -> null
        }
        val qcRoot = runCatching {
            quantificationQcJson?.let { gson.fromJson(it, JsonObject::class.java) }
        }.getOrNull() ?: JsonObject()
        val siteRecoverySnapshot = decision.toSnapshot().toMutableMap().apply {
            // 每个位点同时冻结校正前后值，后续即使算法升级，也能独立审计本次转换。
            put("originalConcentration", originalConcentration)
            reliableRangeStatus?.let { put("originalRangeStatus", it) }
            put("originalQuantificationState", originalState.name)
            put("correctedRangeStatus", correctedStatus.name)
            put("correctedQuantificationState", correctedState.name)
            pointConcentration?.let { put("correctedConcentration", it) }
            correctedLower?.let { put("correctedLowerBound", it) }
            correctedUpper?.let { put("correctedUpperBound", it) }
        }
        qcRoot.add("rangeRecovery", gson.toJsonTree(siteRecoverySnapshot))
        qcRoot.addProperty("status", correctedState.name)
        qcRoot.addProperty("rangeStatus", correctedStatus.name)
        if (correctedState == QuantificationState.BOUND_ONLY) {
            qcRoot.addProperty("concentrationUnavailable", true)
            qcRoot.addProperty(
                "concentrationBound",
                correctedLower ?: correctedUpper
            )
            qcRoot.addProperty("censoringDirection", correctedCensoring)
        } else {
            qcRoot.remove("concentrationUnavailable")
            qcRoot.remove("concentrationBound")
            qcRoot.remove("censoringDirection")
        }
        return copy(
            concentrationValue = pointConcentration,
            concentrationLowerBound = correctedLower,
            concentrationUpperBound = correctedUpper,
            intervalConfidenceLevel = intervalConfidenceLevel
                .takeIf { correctedState != QuantificationState.BOUND_ONLY },
            quantificationState = correctedState.name,
            censoringDirection = correctedCensoring,
            reliableRangeStatus = correctedStatus.name,
            quantificationQcJson = gson.toJson(qcRoot)
        )
    }

    /**
     * 质控校正改变了逐孔浓度、量化状态和范围状态，批次摘要必须从更新后的冻结测量重新汇总。
     * siteSignalOnlyCount 仍沿用量化阶段的原始语义，因为仅凭 UNAVAILABLE 无法区分模型失败与
     * 位点非有限信号；动态校正不会改变这两类输入失败。
     */
    private fun QuantificationBatch.withReviewedMeasurements(
        reviewedMeasurements: List<SiteMeasurement>,
        decision: RangeRecoveryDecision
    ): QuantificationBatch {
        fun SiteMeasurement.state(): QuantificationState? = quantificationState?.let { value ->
            runCatching { QuantificationState.valueOf(value) }.getOrNull()
        }
        fun SiteMeasurement.rangeStatus(): ReliableRangeStatus? = reliableRangeStatus?.let { value ->
            runCatching { ReliableRangeStatus.valueOf(value) }.getOrNull()
        }
        val quantified = reviewedMeasurements.count { measurement ->
            measurement.state() == QuantificationState.QUANTIFIED &&
                measurement.concentrationValue?.isFinite() == true
        }
        val estimated = reviewedMeasurements.count { measurement ->
            measurement.state() == QuantificationState.ESTIMATED &&
                measurement.concentrationValue?.isFinite() == true
        }
        val boundOnly = reviewedMeasurements.count { measurement ->
            measurement.state() == QuantificationState.BOUND_ONLY
        }
        val unavailable = reviewedMeasurements.count { measurement ->
            measurement.state() == QuantificationState.UNAVAILABLE
        }
        val outOfRange = reviewedMeasurements.count { measurement ->
            measurement.state() == QuantificationState.BOUND_ONLY ||
                measurement.rangeStatus() in setOf(
                    ReliableRangeStatus.BELOW_PROJECT_RANGE,
                    ReliableRangeStatus.ABOVE_PROJECT_RANGE
                )
        }
        val extrapolated = reviewedMeasurements.count { measurement ->
            measurement.concentrationValue?.isFinite() == true &&
                (
                    measurement.state() == QuantificationState.ESTIMATED ||
                        measurement.rangeStatus() in setOf(
                            ReliableRangeStatus.BELOW_RANGE,
                            ReliableRangeStatus.ABOVE_RANGE
                        )
                    )
        }
        return copy(
            measurements = reviewedMeasurements,
            quantifiedCount = quantified,
            estimatedCount = estimated,
            boundOnlyCount = boundOnly,
            unavailableCount = unavailable,
            outOfRangeCount = outOfRange,
            extrapolatedCount = extrapolated,
            total = reviewedMeasurements.size,
            rangeRecovery = decision
        )
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
            ModelCompatibilityResult.Compatible -> buildMap {
                put("modelId", analyteSnapshot.analysisModel.model.id)
                put("compatible", true)
                put("execution", batch.execution)
                put("quantifiedCount", batch.quantifiedCount)
                put("estimatedCount", batch.estimatedCount)
                put("boundOnlyCount", batch.boundOnlyCount)
                put("unavailableCount", batch.unavailableCount)
                put("retestCount", batch.retestCount)
                put("outOfRangeCount", batch.outOfRangeCount)
                put("extrapolatedCount", batch.extrapolatedCount)
                put("siteSignalOnlyCount", batch.siteSignalOnlyCount)
                put("total", batch.total)
                if (batch.diagnosticReasons.isNotEmpty()) {
                    put("reasons", batch.diagnosticReasons.sorted())
                }
                if (batch.deepLearningSiteFailures.isNotEmpty()) {
                    val failures = batch.deepLearningSiteFailures
                    put("outOfDeclaredDomainCount", failures.size)
                    put("outOfDeclaredDomainSiteIndices", failures.map { it.siteIndex })
                    put("outOfDeclaredDomainRawMin", failures.minOf { it.rawModelOutput })
                    put("outOfDeclaredDomainRawMax", failures.maxOf { it.rawModelOutput })
                    put("declaredOutputMin", failures.minOf { it.declaredOutputMin })
                    put("declaredOutputMax", failures.maxOf { it.declaredOutputMax })
                }
                batch.rangeRecovery?.let { put("rangeRecovery", it.toSnapshot()) }
            }
            is ModelCompatibilityResult.Incompatible -> mapOf(
                "modelId" to analyteSnapshot.analysisModel.model.id,
                "compatible" to false,
                "reasons" to compatibility.reasons.map(Enum<*>::name),
                "execution" to "signal_only",
                "quantifiedCount" to 0,
                "estimatedCount" to 0,
                "boundOnlyCount" to 0,
                "unavailableCount" to batch.unavailableCount,
                "retestCount" to batch.retestCount,
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

    /**
     * 将已经冻结到 SiteMeasurement 的光度证据组装为富量化观测。
     *
     * 96孔板和微流控都把 BaseSitePhotometry 写入 rawSignalJson，因此这里是两种载体共用
     * 的唯一适配点。旧记录或特殊参考测量解析失败时只缺少饱和比例，不会重新读取图片。
     */
    private fun SiteMeasurement.toQuantificationObservation(): QuantificationObservation {
        val base = runCatching {
            gson.fromJson(rawSignalJson, BaseSitePhotometry::class.java)
        }.getOrNull()
        return QuantificationObservation(
            signalValue = primaryFeatureValue ?: Double.NaN,
            qualityReliable = qualityReliable,
            saturationRatio = base?.saturationRatio,
            photometryFlags = base?.qc?.flags?.mapTo(linkedSetOf()) { it.name }.orEmpty()
        )
    }

    /**
     * 标准曲线在模型级不可执行时统一清空所有旧浓度与区间字段，并写入稳定不可用状态。
     * 这样重试同一内存对象或未来复用协调器时，不会把上一次成功结果残留到本次失败快照。
     */
    private fun SiteMeasurement.asUnavailableStandardCurveMeasurement(
        reasonJson: String
    ): SiteMeasurement = copy(
        concentrationValue = null,
        concentrationUnit = null,
        reliableRangeStatus = null,
        modelSnapshotJson = null,
        quantificationState = QuantificationState.UNAVAILABLE.name,
        concentrationLowerBound = null,
        concentrationUpperBound = null,
        intervalConfidenceLevel = null,
        censoringDirection = null,
        quantificationVersion = ENDPOINT_QUANTIFIER_VERSION,
        quantificationQcJson = reasonJson
    )

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
        val total: Int,
        val extrapolatedCount: Int = 0,
        /** 可信扩展范围内、拥有区间的估计数量。 */
        val estimatedCount: Int = 0,
        /** 只能形成单侧浓度界限的数量。 */
        val boundOnlyCount: Int = 0,
        /** 模型、信号或质量证据不足，无法形成浓度或界限的数量。 */
        val unavailableCount: Int = 0,
        /** 批次级多数越界诊断和经独立质控验收的校正参数；随运行 modelUsage 冻结。 */
        val rangeRecovery: RangeRecoveryDecision? = null,
        /** 深度学习输出离域的逐孔证据；每孔原始输出还会进入 quantificationQcJson。 */
        val deepLearningSiteFailures: List<GridDeepLearningSiteFailure> = emptyList(),
        /** 结果历史摘要使用的稳定机器原因；为空时不渲染猜测性说明。 */
        val diagnosticReasons: Set<String> = emptySet()
    ) {
        /** 结果页“复测”由单侧界限和真正不可用组成，两者在数据库中仍保持可区分。 */
        val retestCount: Int = boundOnlyCount + unavailableCount

        /** 没有任何点浓度、估计值或单侧界限时，用户可见结果仍然只能称为“仅信号”。 */
        val isSignalOnlyResult: Boolean = !modelExecutable ||
            quantifiedCount + estimatedCount + boundOnlyCount == 0

        /** 模型可执行但存在范围或位点信号警告时，保留成功浓度并明确记录警告。 */
        val execution: String = when {
            isSignalOnlyResult -> "signal_only"
            estimatedCount > 0 || boundOnlyCount > 0 || unavailableCount > 0 ||
                outOfRangeCount > 0 || extrapolatedCount > 0 || siteSignalOnlyCount > 0 ->
                "standard_curve_applied_with_warnings"
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
