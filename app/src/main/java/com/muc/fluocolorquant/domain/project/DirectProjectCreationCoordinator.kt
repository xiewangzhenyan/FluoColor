package com.muc.fluocolorquant.domain.project

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 普通新建项目可直接选择的载体规格。
 *
 * 这些预设只描述本次实验使用的物理载体，不要求用户先创建或发布“载体档案”。
 */
enum class DirectCarrierPreset {
    PLATE_96,
    MICROFLUIDIC_10_X_10,
    MICROFLUIDIC_15_X_15,
    MICROFLUIDIC_CUSTOM
}

/** 单个分析物随项目创建请求提交的用户可理解配置。 */
data class DirectProjectAnalyteRequest(
    val analyte: Analyte,
    val concentrationUnit: String,
    val maxConcentration: Double = 100.0
)

/** 普通用户直接创建项目时真正需要提供的运行信息。 */
data class DirectProjectCreateRequest(
    val name: String,
    val detectionModality: DetectionModality,
    val carrierPreset: DirectCarrierPreset,
    val customRows: Int? = null,
    val customColumns: Int? = null,
    val analytes: List<DirectProjectAnalyteRequest>,
    val imageUri: String,
    val userId: String
)

/** 直接创建项目的稳定结果，页面只需要处理成功或输入不完整两种情况。 */
sealed interface DirectProjectCreationOutcome {
    data class Created(
        val project: Project,
        val destination: ProjectDetectionDestination
    ) : DirectProjectCreationOutcome

    data object InvalidRequest : DirectProjectCreationOutcome
}

/**
 * 无模板项目创建协调器。
 *
 * 创建阶段为每个分析物保存独立单位和内部“仅信号”占位模型，但不预先把任何物理位点
 * 指派给第一个分析物。真实孔位角色、分析物归属及定量方案必须等定位完成后由布局页面
 * 冻结，避免多分析物项目在创建瞬间就产生错误的全阵列归属。
 */
@Singleton
class DirectProjectCreationCoordinator @Inject constructor(
    private val projectRepository: ProjectRepository
) {
    private val gson = Gson()

    suspend fun create(request: DirectProjectCreateRequest): DirectProjectCreationOutcome {
        val normalizedName = request.name.trim()
        val normalizedImageUri = request.imageUri.trim()
        val normalizedUserId = request.userId.trim()
        val normalizedAnalytes = request.analytes.map { selection ->
            selection.copy(concentrationUnit = selection.concentrationUnit.trim())
        }
        val analyteIds = normalizedAnalytes.map { it.analyte.id }
        if (
            normalizedName.isEmpty() ||
            normalizedImageUri.isEmpty() ||
            normalizedUserId.isEmpty() ||
            normalizedAnalytes.isEmpty() ||
            normalizedAnalytes.any {
                it.analyte.id.isBlank() ||
                    it.concentrationUnit.isBlank() ||
                    !it.maxConcentration.isFinite() ||
                    it.maxConcentration <= 0.0
            } ||
            analyteIds.distinct().size != analyteIds.size
        ) {
            return DirectProjectCreationOutcome.InvalidRequest
        }

        if (request.detectionModality == DetectionModality.SPECTRUM) {
            return createSpectrumProject(
                request = request.copy(analytes = normalizedAnalytes),
                normalizedName = normalizedName,
                normalizedImageUri = normalizedImageUri,
                normalizedUserId = normalizedUserId
            )
        }

        val geometry = resolveGeometry(request) ?: return DirectProjectCreationOutcome.InvalidRequest
        val now = Date()
        val projectId = UUID.randomUUID().toString()
        val templateId = "direct-template-$projectId"
        val carrierId = "direct-carrier-$projectId"
        val acquisitionId = "direct-acquisition-$projectId"
        val legacyPrimaryAnalyte = normalizedAnalytes.first()

        val carrier = CarrierProfile(
            id = carrierId,
            name = geometry.displayName,
            carrierType = geometry.carrierType.code,
            rows = geometry.rows,
            columns = geometry.columns,
            siteShape = geometry.siteShape.code,
            locatorConfigJson = geometry.polarity?.let(
                ScientificDetectionConfigCodec::encodeCarrierLocator
            ),
            status = ResourceStatus.ACTIVE.code,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        val acquisition = AcquisitionProfile(
            id = acquisitionId,
            name = "direct-auto-capture",
            supportedModesJson = gson.toJson(listOf(request.detectionModality.code)),
            compatibleCarrierTypesJson = gson.toJson(listOf(geometry.carrierType.code)),
            cameraControlStrategy = "AUTO_AND_LOCK",
            status = ResourceStatus.ACTIVE.code,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        val template = ExperimentTemplate(
            id = templateId,
            templateName = normalizedName,
            // 旧字段仅用于兼容旧页面；多分析物真值以 analytes 子快照为准。
            analyteId = legacyPrimaryAnalyte.analyte.id,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = legacyPrimaryAnalyte.maxConcentration,
            concentrationUnit = legacyPrimaryAnalyte.concentrationUnit,
            defaultLayoutJson = null,
            createdAt = now,
            updatedAt = now,
            version = 1,
            status = TemplateLifecycleStatus.PUBLISHED.code,
            carrierProfileId = carrierId,
            detectionMode = request.detectionModality.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = acquisitionId,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            purpose = "direct-project"
        )
        val defaultPrimaryFeature = when (request.detectionModality) {
            DetectionModality.COLORIMETRIC -> AnalysisPrimaryFeature.DELTA_E_2000
            DetectionModality.FLUORESCENCE -> AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            DetectionModality.SPECTRUM -> error("光谱项目由独立分支创建")
        }
        val processor = when (request.detectionModality) {
            DetectionModality.COLORIMETRIC ->
                COLORIMETRIC_PROCESSOR_NAME to COLORIMETRIC_PROCESSOR_VERSION
            DetectionModality.FLUORESCENCE ->
                FLUORESCENCE_PROCESSOR_NAME to FLUORESCENCE_PROCESSOR_VERSION
            DetectionModality.SPECTRUM -> error("光谱项目由独立分支创建")
        }

        val analyteSnapshots = normalizedAnalytes.mapIndexed { index, selection ->
            val modelBundle = createSignalOnlyBundle(
                projectId = projectId,
                selection = selection,
                modality = request.detectionModality,
                carrierType = geometry.carrierType,
                acquisitionId = acquisitionId,
                primaryFeature = defaultPrimaryFeature,
                processor = processor,
                now = now
            )
            TemplateProjectAnalyteSnapshot(
                analyte = selection.analyte,
                templateConfig = TemplateAnalyteConfig(
                    id = "direct-analyte-config-$projectId-${selection.analyte.id}",
                    templateId = templateId,
                    analyteId = selection.analyte.id,
                    analysisModelId = modelBundle.model.id,
                    concentrationUnit = selection.concentrationUnit,
                    reliableRangeMin = 0.0,
                    reliableRangeMax = selection.maxConcentration,
                    displayOrder = index,
                    displayConfigJson = if (
                        request.detectionModality == DetectionModality.FLUORESCENCE
                    ) {
                        ScientificDetectionConfigCodec.encodeFluorescenceDisplay(
                            FluorescenceChannel.GREEN
                        )
                    } else {
                        null
                    }
                ),
                analysisModel = modelBundle,
                // 直接新建延续“有标准孔就自动推荐曲线”的低门槛默认值；用户仍可在布局页
                // 为每个分析物切换为已有曲线、深度学习或仅查看信号。
                quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code
            )
        }
        val snapshot = TemplateProjectSnapshot(
            frozenAtEpochMillis = now.time,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = analyteSnapshots,
            // 创建时不再把全部位点错误绑定到第一个分析物；布局完成后再写入真实分配。
            siteAssignments = emptyList()
        )
        val project = Project(
            id = projectId,
            name = normalizedName,
            detectionMode = request.detectionModality.code,
            recognitionType = "AUTO",
            imageUri = normalizedImageUri,
            rows = geometry.rows,
            columns = geometry.columns,
            createTime = now,
            userId = normalizedUserId,
            lastRunTimestamp = null,
            analysisMethod = "SIGNAL_ONLY",
            templateId = templateId,
            templateVersion = 1,
            templateSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot),
            overrideJson = TemplateProjectOverrideCodec.encode(
                TemplateProjectOverrideSnapshot(sampleSlotMapping = emptyMap())
            )
        )
        val joins = normalizedAnalytes.map { selection ->
            ProjectAnalyteJoin(
                projectId = projectId,
                analyteId = selection.analyte.id,
                maxConcentration = selection.maxConcentration,
                concentrationUnit = selection.concentrationUnit,
                fkTemplateId = null,
                dlModelName = null,
                fkCurveModelId = null
            )
        }
        projectRepository.createProjectWithAnalytes(project, joins)
        return DirectProjectCreationOutcome.Created(
            project = project,
            destination = ProjectDetectionDestination.GRID_ENDPOINT
        )
    }

    /**
     * 为尚未选择定量方案的分析物构造不可执行模型。
     * 空参数对象会被量化器识别为仅信号，绝不会生成伪造浓度。
     */
    private fun createSignalOnlyBundle(
        projectId: String,
        selection: DirectProjectAnalyteRequest,
        modality: DetectionModality,
        carrierType: CarrierType,
        acquisitionId: String,
        primaryFeature: AnalysisPrimaryFeature,
        processor: Pair<String, String>,
        now: Date
    ): AnalysisModelBundle {
        val modelId = "direct-signal-only-$projectId-${selection.analyte.id}"
        val model = AnalysisModel(
            id = modelId,
            name = "signal-only",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = selection.analyte.id,
            detectionMode = modality.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = primaryFeature.code,
            processorName = processor.first,
            processorVersion = processor.second,
            compatibleCarrierTypesJson = gson.toJson(listOf(carrierType.code)),
            compatibleAcquisitionProfileIdsJson = gson.toJson(listOf(acquisitionId)),
            concentrationUnit = selection.concentrationUnit,
            reliableRangeMin = 0.0,
            reliableRangeMax = selection.maxConcentration,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        return AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = modelId,
                fittingFunction = "linear",
                parametersJson = "{}",
                monotonicDirection = "AUTO"
            )
        )
    }

    /** 光谱继续沿用现有单图标定链，但项目与数据库关联仍完整保存全部分析物。 */
    private suspend fun createSpectrumProject(
        request: DirectProjectCreateRequest,
        normalizedName: String,
        normalizedImageUri: String,
        normalizedUserId: String
    ): DirectProjectCreationOutcome {
        val now = Date()
        val projectId = UUID.randomUUID().toString()
        val project = Project(
            id = projectId,
            name = normalizedName,
            detectionMode = DetectionModality.SPECTRUM.code,
            recognitionType = "AUTO",
            imageUri = normalizedImageUri,
            rows = 1,
            columns = 1,
            createTime = now,
            userId = normalizedUserId,
            lastRunTimestamp = null,
            analysisMethod = "SIGNAL_ONLY"
        )
        val joins = request.analytes.map { selection ->
            ProjectAnalyteJoin(
                projectId = projectId,
                analyteId = selection.analyte.id,
                maxConcentration = selection.maxConcentration,
                concentrationUnit = selection.concentrationUnit,
                fkTemplateId = null
            )
        }
        projectRepository.createProjectWithAnalytes(project, joins)
        return DirectProjectCreationOutcome.Created(
            project = project,
            destination = ProjectDetectionDestination.SPECTRUM_SINGLE
        )
    }

    private fun resolveGeometry(request: DirectProjectCreateRequest): DirectGeometry? {
        return when (request.carrierPreset) {
            DirectCarrierPreset.PLATE_96 -> DirectGeometry(
                displayName = "96-well-plate",
                carrierType = CarrierType.PLATE,
                rows = 8,
                columns = 12,
                siteShape = SiteShape.CIRCLE,
                polarity = null
            )

            DirectCarrierPreset.MICROFLUIDIC_10_X_10 -> DirectGeometry(
                displayName = "microfluidic-10x10",
                carrierType = CarrierType.MICROFLUIDIC_CHIP,
                rows = 10,
                columns = 10,
                siteShape = SiteShape.SQUARE,
                polarity = GridTargetPolarity.DARK
            )

            DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> DirectGeometry(
                displayName = "microfluidic-15x15",
                carrierType = CarrierType.MICROFLUIDIC_CHIP,
                rows = 15,
                columns = 15,
                siteShape = SiteShape.SQUARE,
                polarity = GridTargetPolarity.DARK
            )

            DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> {
                val rows = request.customRows
                    ?.takeIf(GridLayoutPolicy::isValidDimension)
                    ?: return null
                val columns = request.customColumns
                    ?.takeIf(GridLayoutPolicy::isValidDimension)
                    ?: return null
                DirectGeometry(
                    displayName = "microfluidic-${rows}x$columns",
                    carrierType = CarrierType.MICROFLUIDIC_CHIP,
                    rows = rows,
                    columns = columns,
                    siteShape = SiteShape.SQUARE,
                    polarity = GridTargetPolarity.DARK
                )
            }
        }
    }

    private data class DirectGeometry(
        val displayName: String,
        val carrierType: CarrierType,
        val rows: Int,
        val columns: Int,
        val siteShape: SiteShape,
        val polarity: GridTargetPolarity?
    )
}
