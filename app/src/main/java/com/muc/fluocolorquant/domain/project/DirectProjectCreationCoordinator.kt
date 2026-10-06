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
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
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
    val customSiteShape: SiteShape? = null,
    val spectrumLightSource: SpectrumLightSource? = null,
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

/** 无模板项目的隐式载体 ID 前缀；每个项目一份，ID 为该前缀加项目 ID。 */
const val DIRECT_CARRIER_ID_PREFIX: String = "direct-carrier-"

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
            // 光谱光源必须由新建表单明确确认；协调器不读取全局默认值，避免后台创建时
            // 静默写入一个用户从未看见的采集条件。
            if (request.spectrumLightSource == null) {
                return DirectProjectCreationOutcome.InvalidRequest
            }
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
        val carrierId = "$DIRECT_CARRIER_ID_PREFIX$projectId"
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
            // 直接项目的采集档案ID包含项目UUID，只用于冻结本次运行，不是长期设备身份。
            // 空数组表示由手机在每次拍摄时记录真实元数据，避免保存现场曲线后无法跨项目复用。
            compatibleAcquisitionProfileIdsJson = gson.toJson(emptyList<String>()),
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
        // 光谱通道数与所选分析物一一对应：一个分析物占一条光谱轨道。此前这里从未写入
        // spectrumColumnCount，Project 只能落到默认值 1，导致标定页无论选了几个分析物都
        // 只按单通道检测轨道；逐通道的分析物归属也随之丢失（映射为 null）。
        val channelCount = request.analytes.size.coerceAtLeast(1)
        val project = Project(
            id = projectId,
            name = normalizedName,
            detectionMode = DetectionModality.SPECTRUM.code,
            recognitionType = "AUTO",
            imageUri = normalizedImageUri,
            rows = 1,
            columns = 1,
            // 仅保存枚举稳定 name，显示名称由资源层本地化；该字段不参与任何光谱校正。
            lightSource = requireNotNull(request.spectrumLightSource).name,
            spectrumColumnCount = channelCount,
            spectrumColumnMappingJson = encodeSpectrumColumnMapping(request.analytes),
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

    /**
     * 生成“光谱通道 → 分析物”映射。
     *
     * 键是**1 基通道号的字符串**，值是分析物 ID：`SpectrumCalibrationViewModel` 在保存
     * 逐通道结果时按 `analyteMapping[(index + 1).toString()]` 取用，键型改成 0 基或数值
     * 都会让整张映射静默失配、逐通道分析物归属全部丢失。
     *
     * 顺序即绑定关系：第 n 个被选中的分析物对应第 n 条光谱轨道，与创建页展示给用户的
     * 顺序一致。
     */
    private fun encodeSpectrumColumnMapping(
        analytes: List<DirectProjectAnalyteRequest>
    ): String? {
        if (analytes.isEmpty()) return null
        val mapping = analytes.mapIndexed { index, selection ->
            (index + 1).toString() to selection.analyte.id
        }.toMap()
        return Gson().toJson(mapping)
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
                val siteShape = request.customSiteShape
                    ?.takeIf { it == SiteShape.CIRCLE || it == SiteShape.SQUARE }
                    ?: return null
                DirectGeometry(
                    displayName = "microfluidic-${rows}x$columns",
                    // “自定义”描述的是行列规格，不表示定位协议未知。普通新建当前明确使用
                    // PG-Grid，所以圆形位点也保持 MICROFLUIDIC_CHIP；若按 CIRCLE 改成
                    // PLATE，会错误进入只接受固定 8×12 的 96 孔板定位链。
                    carrierType = CarrierType.MICROFLUIDIC_CHIP,
                    rows = rows,
                    columns = columns,
                    siteShape = siteShape,
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
