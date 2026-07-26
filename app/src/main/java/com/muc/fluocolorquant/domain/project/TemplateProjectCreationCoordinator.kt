package com.muc.fluocolorquant.domain.project

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目创建前检查的稳定机器码。
 *
 * UI 只负责把机器码映射为中英文说明；导出、日志和自动化测试保存机器码本身。这样既能
 * 修改文案，又不会破坏科研追溯或让测试依赖某一种语言。
 */
enum class TemplatePreflightCode {
    TEMPLATE_NOT_FOUND,
    TEMPLATE_NOT_PUBLISHED,
    CARRIER_MISSING,
    CARRIER_ARCHIVED,
    ACQUISITION_MISSING,
    ACQUISITION_ARCHIVED,
    ACQUISITION_MODALITY_MISMATCH,
    ACQUISITION_CARRIER_MISMATCH,
    ANALYTE_CONFIG_MISSING,
    ANALYTE_MISSING,
    ANALYSIS_MODEL_MISSING,
    ANALYSIS_MODEL_NOT_PUBLISHED,
    MODEL_ANALYTE_MISMATCH,
    MODEL_MODALITY_MISMATCH,
    MODEL_PROTOCOL_MISMATCH,
    MODEL_CARRIER_MISMATCH,
    MODEL_ACQUISITION_MISMATCH,
    MODEL_UNIT_MISMATCH,
    MODEL_RELIABLE_RANGE_MISMATCH,
    LAYOUT_INCOMPLETE,
    LAYOUT_OUT_OF_BOUNDS,
    SITE_ANALYTE_UNKNOWN,
    UNSUPPORTED_DETECTION_ROUTE,
    PROJECT_NAME_MISSING,
    IMAGE_MISSING,
    USER_MISSING,
    SAMPLE_MAPPING_INCOMPLETE
}

/** 单条实验前检查结果；[context] 只保存便于定位问题的稳定 ID。 */
data class TemplatePreflightIssue(
    val code: TemplatePreflightCode,
    val context: String? = null
)

/** 已经完整解析并通过检查的模板项目配置。 */
data class ResolvedTemplateProjectConfiguration(
    val snapshot: TemplateProjectSnapshot,
    val destination: ProjectDetectionDestination
)

/** 模板解析结果明确区分可用与阻断，禁止用 null 混淆“加载失败”和“检查失败”。 */
sealed interface TemplateResolution {
    data class Ready(
        val configuration: ResolvedTemplateProjectConfiguration
    ) : TemplateResolution

    data class Blocked(
        val template: ExperimentTemplate?,
        val issues: List<TemplatePreflightIssue>
    ) : TemplateResolution
}

/**
 * 页面状态机依赖的最小协调器接口。
 *
 * 接口让 ViewModel 单元测试可以使用确定性的内存实现，同时生产实现仍集中执行同一套
 * 发布态、资源兼容和原子创建规则。
 */
interface TemplateProjectCoordinator {
    fun observePublishedTemplates(): Flow<List<ExperimentTemplate>>
    suspend fun resolveTemplate(templateId: String): TemplateResolution
    suspend fun createProject(request: TemplateProjectCreateRequest): TemplateProjectCreationOutcome
}

/**
 * 模板优先项目创建的科学配置协调器。
 *
 * 协调器每次解析都重新读取模板和资源，而不是相信页面先前缓存的数据。后续真正创建
 * 项目时会再次调用同一入口，从而防止用户停留在页面期间模板被归档、模型被切换版本或
 * 设备档案失效后仍然生成不一致项目。
 */
@Singleton
class TemplateProjectCreationCoordinator @Inject constructor(
    private val templateRepository: ExperimentTemplateRepository,
    private val carrierProfileRepository: CarrierProfileRepository,
    private val acquisitionProfileRepository: AcquisitionProfileRepository,
    private val analysisModelRepository: AnalysisModelRepository,
    private val analyteRepository: AnalyteRepository,
    private val projectRepository: ProjectRepository
) : TemplateProjectCoordinator {

    /**
     * 普通新建项目入口只观察已发布模板。
     *
     * 返回列表不携带“当前选中项”，因此上层必须等待用户明确选择，不能自动套用第一项。
     */
    override fun observePublishedTemplates(): Flow<List<ExperimentTemplate>> {
        return templateRepository.getAllTemplates().map { templates ->
            templates
                .filter { it.status == TemplateLifecycleStatus.PUBLISHED.code }
                .sortedWith(
                    compareBy<ExperimentTemplate> { it.templateName.lowercase() }
                        .thenByDescending(ExperimentTemplate::version)
                )
        }
    }

    /** 解析模板关联的全部科学资源并执行实验前检查。 */
    override suspend fun resolveTemplate(templateId: String): TemplateResolution {
        val bundle = templateRepository.getBundle(templateId)
            ?: return TemplateResolution.Blocked(
                template = null,
                issues = listOf(
                    TemplatePreflightIssue(
                        code = TemplatePreflightCode.TEMPLATE_NOT_FOUND,
                        context = templateId
                    )
                )
            )
        val template = bundle.template
        val issues = mutableListOf<TemplatePreflightIssue>()

        if (template.status != TemplateLifecycleStatus.PUBLISHED.code) {
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.TEMPLATE_NOT_PUBLISHED,
                template.id
            )
        }

        val carrier = template.carrierProfileId
            ?.takeIf(String::isNotBlank)
            ?.let { carrierProfileRepository.getById(it) }
        if (carrier == null) {
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.CARRIER_MISSING,
                template.carrierProfileId
            )
        } else if (carrier.status != ResourceStatus.ACTIVE.code) {
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.CARRIER_ARCHIVED,
                carrier.id
            )
        }

        val acquisition = template.acquisitionProfileId
            ?.takeIf(String::isNotBlank)
            ?.let { acquisitionProfileRepository.getById(it) }
        if (acquisition == null) {
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.ACQUISITION_MISSING,
                template.acquisitionProfileId
            )
        } else {
            if (acquisition.status != ResourceStatus.ACTIVE.code) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ACQUISITION_ARCHIVED,
                    acquisition.id
                )
            }
            if (template.detectionMode !in StableCodeArrayJson.decode(acquisition.supportedModesJson)) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ACQUISITION_MODALITY_MISMATCH,
                    acquisition.id
                )
            }
            if (carrier != null &&
                carrier.carrierType !in StableCodeArrayJson.decode(acquisition.compatibleCarrierTypesJson)
            ) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ACQUISITION_CARRIER_MISMATCH,
                    acquisition.id
                )
            }
        }

        val destination = ProjectDetectionRouter.resolve(
            detectionMode = template.detectionMode,
            inputProtocol = template.inputProtocol,
            readoutLayout = template.readoutLayout
        )
        if (destination == ProjectDetectionDestination.UNSUPPORTED ||
            destination == ProjectDetectionDestination.LSPR_PAIRED
        ) {
            // LSPR 的配对采集状态机在工作包 7 接通前必须阻止创建，避免产生无法继续的项目。
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.UNSUPPORTED_DETECTION_ROUTE,
                "${template.detectionMode}/${template.inputProtocol}/${template.readoutLayout}"
            )
        }

        if (bundle.analyteConfigs.isEmpty()) {
            issues += TemplatePreflightIssue(
                TemplatePreflightCode.ANALYTE_CONFIG_MISSING,
                template.id
            )
        }

        val bindingsByAnalyte = bundle.quantitationBindings.associateBy { it.analyteId }
        val resolvedAnalytes = bundle.analyteConfigs.mapNotNull { config ->
            val analyte = analyteRepository.getAnalyteById(config.analyteId)
            if (analyte == null) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ANALYTE_MISSING,
                    config.analyteId
                )
                return@mapNotNull null
            }

            val binding = bindingsByAnalyte[config.analyteId]
            val frozenResource = binding?.let { stored ->
                runCatching {
                    TemplateQuantitationResourceSnapshotCodec.decode(stored.resourceSnapshotJson)
                }.getOrNull()
            }
            val modelId = config.analysisModelId
            val modelBundle = frozenResource?.analysisModel ?: modelId
                ?.takeIf(String::isNotBlank)
                ?.let { analysisModelRepository.getBundle(it) }
            if (modelBundle == null) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ANALYSIS_MODEL_MISSING,
                    modelId ?: config.id
                )
                return@mapNotNull null
            }

            val model = modelBundle.model
            if (frozenResource == null &&
                model.status != AnalysisModelLifecycleStatus.PUBLISHED.code
            ) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.ANALYSIS_MODEL_NOT_PUBLISHED,
                    model.id
                )
            }
            if (model.analyteId != config.analyteId) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_ANALYTE_MISMATCH,
                    model.id
                )
            }
            if (model.detectionMode != template.detectionMode) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_MODALITY_MISMATCH,
                    model.id
                )
            }
            if (model.inputProtocol != template.inputProtocol) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_PROTOCOL_MISMATCH,
                    model.id
                )
            }
            if (carrier != null &&
                carrier.carrierType !in StableCodeArrayJson.decode(model.compatibleCarrierTypesJson)
            ) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_CARRIER_MISMATCH,
                    model.id
                )
            }
            val compatibleAcquisitionIds = StableCodeArrayJson.decode(
                model.compatibleAcquisitionProfileIdsJson
            )
            if (acquisition != null && compatibleAcquisitionIds.isNotEmpty() &&
                acquisition.id !in compatibleAcquisitionIds
            ) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_ACQUISITION_MISMATCH,
                    model.id
                )
            }
            if (model.concentrationUnit != config.concentrationUnit) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_UNIT_MISMATCH,
                    model.id
                )
            }
            val configMin = config.reliableRangeMin
            val configMax = config.reliableRangeMax
            val invalidProjectRange = configMin == null || configMax == null ||
                !configMin.isFinite() || !configMax.isFinite() || configMin >= configMax
            val modelType = AnalysisModelType.fromCode(model.modelType)
            val outsideDeepLearningRange = modelType == AnalysisModelType.DEEP_LEARNING &&
                configMin != null && configMax != null &&
                (configMin < model.reliableRangeMin || configMax > model.reliableRangeMax)
            // 标准曲线的模型范围是“标定范围”，项目量程允许更宽，范围外部分由执行器明确标记外推。
            // 深度学习模型没有可审计的数学外推函数，仍要求项目量程落在模型验证范围内。
            if (invalidProjectRange || outsideDeepLearningRange) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.MODEL_RELIABLE_RANGE_MISMATCH,
                    model.id
                )
            }

            TemplateProjectAnalyteSnapshot(
                analyte = analyte,
                templateConfig = config.copy(analysisModelId = binding?.sourceResourceId ?: modelId),
                analysisModel = modelBundle,
                quantitationMode = frozenResource?.quantitation?.method?.toGridModeCode(),
                onsiteSelectedFeature = frozenResource?.quantitation?.calibration?.primaryFeature,
                onsiteSelectedFunction = frozenResource?.quantitation?.calibration?.fittingFunction,
                analyteQuantitationSnapshot = frozenResource?.quantitation?.copy(
                    // 若资源被删除，外键列已经SET_NULL，项目快照不能继续声称实时资源存在。
                    sourceResourceId = binding?.sourceResourceId
                )
            )
        }

        validateLayout(
            templateId = template.id,
            readoutLayout = template.readoutLayout,
            rows = carrier?.rows,
            columns = carrier?.columns,
            configuredAnalyteIds = bundle.analyteConfigs.map { it.analyteId }.toSet(),
            assignments = bundle.siteAssignments,
            issues = issues
        )

        if (issues.isNotEmpty() || carrier == null || acquisition == null ||
            resolvedAnalytes.size != bundle.analyteConfigs.size
        ) {
            return TemplateResolution.Blocked(
                template = template,
                issues = issues.distinct()
            )
        }

        val snapshot = TemplateProjectSnapshot(
            frozenAtEpochMillis = System.currentTimeMillis(),
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = resolvedAnalytes,
            siteAssignments = bundle.siteAssignments.sortedWith(
                compareBy({ it.rowIndex }, { it.columnIndex }, { it.id })
            )
        )
        return TemplateResolution.Ready(
            ResolvedTemplateProjectConfiguration(
                snapshot = snapshot,
                destination = destination
            )
        )
    }

    /**
     * 重新预检模板并原子创建项目。
     *
     * 页面传入的只有项目身份、样本映射和图片来源；模态、行列、分析物、模型和设备全部
     * 从本次重新解析得到的模板快照生成，防止全局设置或页面缓存偷偷改变实验配置。
     */
    override suspend fun createProject(
        request: TemplateProjectCreateRequest
    ): TemplateProjectCreationOutcome {
        val requestIssues = mutableListOf<TemplatePreflightIssue>()
        if (request.name.isBlank()) {
            requestIssues += TemplatePreflightIssue(TemplatePreflightCode.PROJECT_NAME_MISSING)
        }
        if (request.imageUri.isBlank()) {
            requestIssues += TemplatePreflightIssue(TemplatePreflightCode.IMAGE_MISSING)
        }
        if (request.userId.isBlank()) {
            requestIssues += TemplatePreflightIssue(TemplatePreflightCode.USER_MISSING)
        }

        val resolution = resolveTemplate(request.templateId)
        if (resolution is TemplateResolution.Blocked) {
            return TemplateProjectCreationOutcome.Blocked(
                issues = (requestIssues + resolution.issues).distinct()
            )
        }
        val ready = resolution as TemplateResolution.Ready
        val initialSnapshot = ready.configuration.snapshot

        val requiredSampleKeys = initialSnapshot.siteAssignments
            .asSequence()
            .filter { it.enabled && it.roleType == TemplateSiteRole.SAMPLE.code }
            .map { TemplateSiteKey.format(it.rowIndex, it.columnIndex) }
            .toSet()
        val normalizedSampleMapping = request.sampleSlotMapping
            .mapKeys { (key, _) -> key.trim() }
            .mapValues { (_, value) -> value.trim() }
            .filterValues(String::isNotEmpty)
            .toSortedMap()
        val missingSampleKeys = requiredSampleKeys - normalizedSampleMapping.keys
        if (missingSampleKeys.isNotEmpty()) {
            requestIssues += TemplatePreflightIssue(
                TemplatePreflightCode.SAMPLE_MAPPING_INCOMPLETE,
                missingSampleKeys.sorted().joinToString(separator = ",")
            )
        }
        if (requestIssues.isNotEmpty()) {
            return TemplateProjectCreationOutcome.Blocked(requestIssues.distinct())
        }

        val frozenAt = System.currentTimeMillis()
        val snapshot = initialSnapshot.copy(frozenAtEpochMillis = frozenAt)
        val template = snapshot.template
        val projectId = UUID.randomUUID().toString()
        val modelTypes = snapshot.analytes.map { it.analysisModel.model.modelType }.toSet()
        val compatibilityAnalysisMethod = when (modelTypes.singleOrNull()) {
            AnalysisModelType.DEEP_LEARNING.code -> "DL_MODEL"
            AnalysisModelType.STANDARD_CURVE.code -> "CURVE_FIT"
            else -> "TEMPLATE_MANAGED"
        }
        val project = Project(
            id = projectId,
            name = request.name.trim(),
            detectionMode = requireNotNull(template.detectionMode) { "模板检测模态不能为空" },
            recognitionType = "AUTO",
            imageUri = request.imageUri.trim(),
            rows = snapshot.carrierProfile.rows,
            columns = snapshot.carrierProfile.columns,
            lightSource = snapshot.acquisitionProfile.opticalModuleName,
            // 光谱通道与分析物一一对应；非光谱项目该字段无意义，保持 1。
            // 此前这里写成 `if (...) 1 else 1` 的恒等式，等于永远单通道，模板里配了几个
            // 分析物都不起作用，逐通道分析物归属也一并丢失。
            spectrumColumnCount = if (
                ready.configuration.destination == ProjectDetectionDestination.SPECTRUM_SINGLE
            ) {
                snapshot.analytes.size.coerceAtLeast(1)
            } else {
                1
            },
            spectrumColumnMappingJson = if (
                ready.configuration.destination == ProjectDetectionDestination.SPECTRUM_SINGLE
            ) {
                encodeSpectrumColumnMapping(snapshot.analytes.map { it.analyte.id })
            } else {
                null
            },
            createTime = Date(frozenAt),
            userId = request.userId.trim(),
            lastRunTimestamp = null,
            analysisMethod = compatibilityAnalysisMethod,
            templateId = template.id,
            templateVersion = template.version,
            templateSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot),
            overrideJson = TemplateProjectOverrideCodec.encode(
                TemplateProjectOverrideSnapshot(
                    sampleSlotMapping = normalizedSampleMapping,
                    reasons = request.overrideReasons
                        .mapKeys { (key, _) -> key.trim() }
                        .mapValues { (_, value) -> value.trim() }
                        .filterValues(String::isNotEmpty)
                        .toSortedMap()
                )
            ),
            projectBatch = request.projectBatch.trim().ifBlank { null },
            sampleBatch = request.sampleBatch.trim().ifBlank { null }
        )
        val joins = snapshot.analytes.map { analyteSnapshot ->
            val config = analyteSnapshot.templateConfig
            ProjectAnalyteJoin(
                projectId = projectId,
                analyteId = analyteSnapshot.analyte.id,
                maxConcentration = config.reliableRangeMax,
                concentrationUnit = config.concentrationUnit,
                fkTemplateId = template.id,
                dlModelName = analyteSnapshot.analysisModel.deepLearning?.modelFileName,
                // 新标准曲线属于统一 AnalysisModel，不伪装成旧 CurveModel 外键。
                fkCurveModelId = null
            )
        }
        projectRepository.createProjectWithAnalytes(project, joins)
        return TemplateProjectCreationOutcome.Created(
            project = project,
            destination = ready.configuration.destination
        )
    }

    /** 校验规则阵列完整性、边界和位点所引用的分析物。 */
    /**
     * 生成“光谱通道 → 分析物”映射，键为 **1 基通道号字符串**。
     *
     * 与 [DirectProjectCreationCoordinator] 保持同一契约：`SpectrumCalibrationViewModel`
     * 按 `analyteMapping[(index + 1).toString()]` 取用，键型不一致会让整张映射静默失配。
     */
    private fun encodeSpectrumColumnMapping(analyteIds: List<String>): String? {
        if (analyteIds.isEmpty()) return null
        val mapping = analyteIds.mapIndexed { index, id -> (index + 1).toString() to id }.toMap()
        return Gson().toJson(mapping)
    }

    private fun validateLayout(
        templateId: String,
        readoutLayout: String?,
        rows: Int?,
        columns: Int?,
        configuredAnalyteIds: Set<String>,
        assignments: List<com.muc.fluocolorquant.data.model.TemplateSiteAssignment>,
        issues: MutableList<TemplatePreflightIssue>
    ) {
        if (readoutLayout != ReadoutLayout.GRID_SITES.code) return
        if (rows == null || columns == null || rows <= 0 || columns <= 0) {
            issues += TemplatePreflightIssue(TemplatePreflightCode.LAYOUT_INCOMPLETE, templateId)
            return
        }

        val coordinates = assignments.map { it.rowIndex to it.columnIndex }
        val expectedCount = rows * columns
        if (assignments.size != expectedCount || coordinates.toSet().size != expectedCount) {
            issues += TemplatePreflightIssue(TemplatePreflightCode.LAYOUT_INCOMPLETE, templateId)
        }
        if (assignments.any { assignment ->
                assignment.rowIndex !in 0 until rows || assignment.columnIndex !in 0 until columns
            }
        ) {
            issues += TemplatePreflightIssue(TemplatePreflightCode.LAYOUT_OUT_OF_BOUNDS, templateId)
        }
        assignments.forEach { assignment ->
            val disabled = assignment.roleType == TemplateSiteRole.DISABLED.code || !assignment.enabled
            if (!disabled && assignment.analyteId !in configuredAnalyteIds) {
                issues += TemplatePreflightIssue(
                    TemplatePreflightCode.SITE_ANALYTE_UNKNOWN,
                    assignment.id
                )
            }
        }
    }
}

/** 领域层映射到项目快照的稳定定量模式编码，避免项目创建依赖UI枚举。 */
private fun AnalyteQuantitationMethod.toGridModeCode(): String = when (this) {
    AnalyteQuantitationMethod.ONSITE_CALIBRATION -> "ONSITE_AUTO_FIT"
    AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE -> "EXISTING_STANDARD_CURVE"
    AnalyteQuantitationMethod.DEEP_LEARNING_MODEL -> "DEEP_LEARNING_MODEL"
    AnalyteQuantitationMethod.SIGNAL_ONLY -> "SIGNAL_ONLY"
}

/**
 * 稳定编码数组的轻量解析器。
 *
 * 未知编码会原样保留，调用方据此判定不兼容；绝不能过滤后自动使用第一个已知编码。
 */
private object StableCodeArrayJson {
    private val quotedValueRegex = Regex("\\\"([^\\\"]*)\\\"")

    fun decode(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return quotedValueRegex.findAll(json)
            .map { it.groupValues[1].trim() }
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }
}
