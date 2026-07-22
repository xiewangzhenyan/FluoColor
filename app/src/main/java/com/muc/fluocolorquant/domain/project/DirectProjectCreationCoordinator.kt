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
import com.muc.fluocolorquant.data.enums.TemplateReferenceScope
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.ProjectRepository
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
 * 这些规格只负责给用户提供清楚的实验对象，不再要求用户先进入“载体档案”创建、发布或
 * 归档资源。自定义规格仍按规则微流控阵列处理，因此可以继续复用 PG-Grid 定位器。
 */
enum class DirectCarrierPreset {
    PLATE_96,
    MICROFLUIDIC_10_X_10,
    MICROFLUIDIC_15_X_15,
    MICROFLUIDIC_CUSTOM
}

/** 普通用户直接创建项目时真正需要提供的运行信息。 */
data class DirectProjectCreateRequest(
    val name: String,
    val detectionModality: DetectionModality,
    val carrierPreset: DirectCarrierPreset,
    val customRows: Int? = null,
    val customColumns: Int? = null,
    val analyte: Analyte,
    val imageUri: String,
    val userId: String,
    val concentrationUnit: String,
    val sampleId: String = "",
    val colorReferenceRow: Int? = null,
    val colorReferenceColumn: Int? = null
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
 * 协调器不会向模板、载体、设备或分析模型表写入一次性记录，而是构造一份只属于当前项目的
 * 冻结快照。这样普通用户可以直接创建项目，历史结果仍然拥有 PG-Grid 和科研导出需要的完整
 * 行列、处理器、位点角色和采集配置。没有定量模型时使用一个明确不可执行的内部标准曲线
 * 定义，使检测协调器安全回退为“仅信号模式”，绝不生成虚假浓度。
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
        if (
            normalizedName.isEmpty() ||
            normalizedImageUri.isEmpty() ||
            normalizedUserId.isEmpty() ||
            request.concentrationUnit.isBlank()
        ) {
            return DirectProjectCreationOutcome.InvalidRequest
        }

        if (request.detectionModality == DetectionModality.SPECTRUM) {
            return createSpectrumProject(request, normalizedName, normalizedImageUri, normalizedUserId)
        }

        val geometry = resolveGeometry(request) ?: return DirectProjectCreationOutcome.InvalidRequest
        val referenceCoordinate = resolveColorReference(request, geometry)
            ?: if (
                request.detectionModality == DetectionModality.COLORIMETRIC &&
                geometry.carrierType == CarrierType.MICROFLUIDIC_CHIP
            ) {
                return DirectProjectCreationOutcome.InvalidRequest
            } else {
                null
            }

        val now = Date()
        val projectId = UUID.randomUUID().toString()
        val templateId = "direct-template-$projectId"
        val carrierId = "direct-carrier-$projectId"
        val acquisitionId = "direct-acquisition-$projectId"
        val modelId = "direct-signal-only-$projectId"

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
            analyteId = request.analyte.id,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 1.0,
            concentrationUnit = request.concentrationUnit.trim(),
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
        val primaryFeature = when (request.detectionModality) {
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
        val model = AnalysisModel(
            id = modelId,
            name = "signal-only",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = request.analyte.id,
            detectionMode = request.detectionModality.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = primaryFeature.code,
            processorName = processor.first,
            processorVersion = processor.second,
            compatibleCarrierTypesJson = gson.toJson(listOf(geometry.carrierType.code)),
            compatibleAcquisitionProfileIdsJson = gson.toJson(listOf(acquisitionId)),
            concentrationUnit = request.concentrationUnit.trim(),
            reliableRangeMin = 0.0,
            reliableRangeMax = 1.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        val templateAnalyte = TemplateAnalyteConfig(
            id = "direct-analyte-config-$projectId",
            templateId = templateId,
            analyteId = request.analyte.id,
            analysisModelId = modelId,
            concentrationUnit = request.concentrationUnit.trim(),
            reliableRangeMin = null,
            reliableRangeMax = null,
            displayConfigJson = if (request.detectionModality == DetectionModality.FLUORESCENCE) {
                ScientificDetectionConfigCodec.encodeFluorescenceDisplay(FluorescenceChannel.GREEN)
            } else {
                null
            }
        )
        val modelBundle = AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = modelId,
                fittingFunction = "linear",
                // 空参数对象是有意的：量化器会将其识别为不可执行模型并回退为仅信号，
                // 而不是把任意默认斜率伪装成真实标准曲线。
                parametersJson = "{}",
                monotonicDirection = "AUTO"
            )
        )
        val assignments = buildAssignments(
            projectId = projectId,
            templateId = templateId,
            analyteId = request.analyte.id,
            rows = geometry.rows,
            columns = geometry.columns,
            sampleId = request.sampleId.trim(),
            colorReference = referenceCoordinate
        )
        val snapshot = TemplateProjectSnapshot(
            frozenAtEpochMillis = now.time,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = request.analyte,
                    templateConfig = templateAnalyte,
                    analysisModel = modelBundle
                )
            ),
            siteAssignments = assignments
        )
        val sampleMapping = assignments
            .filter { it.roleType == TemplateSiteRole.SAMPLE.code && !it.defaultSampleSlot.isNullOrBlank() }
            .associate { assignment ->
                TemplateSiteKey.format(assignment.rowIndex, assignment.columnIndex) to
                    requireNotNull(assignment.defaultSampleSlot)
            }
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
                TemplateProjectOverrideSnapshot(sampleSlotMapping = sampleMapping)
            )
        )
        val join = ProjectAnalyteJoin(
            projectId = projectId,
            analyteId = request.analyte.id,
            maxConcentration = null,
            concentrationUnit = request.concentrationUnit.trim(),
            fkTemplateId = null,
            dlModelName = null,
            fkCurveModelId = null
        )
        projectRepository.createProjectWithAnalytes(project, listOf(join))
        return DirectProjectCreationOutcome.Created(
            project = project,
            destination = ProjectDetectionDestination.GRID_ENDPOINT
        )
    }

    /** 光谱仍沿用现有单图标定链，不要求构造规则阵列快照。 */
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
        val join = ProjectAnalyteJoin(
            projectId = projectId,
            analyteId = request.analyte.id,
            maxConcentration = null,
            concentrationUnit = request.concentrationUnit.trim(),
            fkTemplateId = null
        )
        projectRepository.createProjectWithAnalytes(project, listOf(join))
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
                // 当前用户提供的 15×15 EL 背光实物与 10×10 一样，都是亮背景上的暗单元。
                // 定位器仍会自动裁决极性；这里修复直接新建流程遗留的错误默认值，避免旧偏好
                // 在日志、快照和兼容工具中继续误导用户。
                polarity = GridTargetPolarity.DARK
            )
            DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> {
                val rows = request.customRows?.takeIf(GridLayoutPolicy::isValidDimension) ?: return null
                val columns = request.customColumns?.takeIf(GridLayoutPolicy::isValidDimension) ?: return null
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

    private fun resolveColorReference(
        request: DirectProjectCreateRequest,
        geometry: DirectGeometry
    ): Pair<Int, Int>? {
        if (
            request.detectionModality != DetectionModality.COLORIMETRIC ||
            geometry.carrierType != CarrierType.MICROFLUIDIC_CHIP
        ) {
            return null
        }
        val row = request.colorReferenceRow ?: return null
        val column = request.colorReferenceColumn ?: return null
        return (row to column).takeIf {
            row in 0 until geometry.rows && column in 0 until geometry.columns
        }
    }

    private fun buildAssignments(
        projectId: String,
        templateId: String,
        analyteId: String,
        rows: Int,
        columns: Int,
        sampleId: String,
        colorReference: Pair<Int, Int>?
    ): List<TemplateSiteAssignment> {
        return buildList(rows * columns) {
            repeat(rows) { row ->
                repeat(columns) { column ->
                    val isReference = colorReference == (row to column)
                    add(
                        TemplateSiteAssignment(
                            id = "direct-site-$projectId-$row-$column",
                            templateId = templateId,
                            rowIndex = row,
                            columnIndex = column,
                            analyteId = analyteId,
                            roleType = if (isReference) {
                                TemplateSiteRole.REFERENCE.code
                            } else {
                                TemplateSiteRole.SAMPLE.code
                            },
                            defaultSampleSlot = sampleId.takeIf(String::isNotBlank),
                            referenceScope = if (isReference) {
                                TemplateReferenceScope.ANALYTE.code
                            } else {
                                null
                            },
                            enabled = true
                        )
                    )
                }
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
