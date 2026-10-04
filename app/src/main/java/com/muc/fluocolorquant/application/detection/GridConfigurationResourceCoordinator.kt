package com.muc.fluocolorquant.application.detection

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateQuantitationBinding
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.domain.calibration.CalibrationResourceFingerprint
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationBindingFingerprint
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshot
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.calibration.buildCalibrationValidationMetrics
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.isDirectAcquisitionProfileId
import com.muc.fluocolorquant.domain.detection.quantification.BuiltInSharedConcentrationModel
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** 布局页可见的兼容模板与模型摘要。 */
data class GridConfigurationResources(
    val templates: List<GridExperimentTemplateOption>,
    val models: List<GridAnalysisModelOption>
)

/** 应用模板时必须同时拿到模板数据包和它真实绑定的载体档案。 */
data class GridResolvedExperimentTemplate(
    val bundle: ExperimentTemplateBundle,
    val carrier: com.muc.fluocolorquant.data.model.CarrierProfile
)

/**
 * 规则阵列配置资源的应用流程协调器。
 *
 * 该类集中处理模板、模型、现场曲线和直接新建项目临时档案的仓库交互。ViewModel 只保留
 * 页面状态与用户意图，不再自行拼装 Room 外键关系。这里仍位于 detection 包，是当前仓库尚未
 * 建立独立 application/usecase 模块时的过渡边界；它不参与定位、光度或定量算法计算。
 */
class GridConfigurationResourceCoordinator @Inject constructor(
    private val templateRepository: ExperimentTemplateRepository,
    private val carrierProfileRepository: CarrierProfileRepository,
    private val acquisitionProfileRepository: AcquisitionProfileRepository,
    private val analysisModelRepository: AnalysisModelRepository
) {
    private val gson = Gson()

    /** 名称比较忽略大小写，与普通模板管理页的用户可见唯一性保持一致。 */
    suspend fun templateNameExists(name: String): Boolean =
        templateRepository.getAllTemplates().first().any { template ->
            template.templateName.equals(name, ignoreCase = true)
        }

    /**
     * 读取模板及其载体的同一时点数据。
     *
     * 载体已被删除或外键为空时返回 null，由调用方统一映射成“模板不兼容”，不能使用当前
     * 项目的载体临时补齐，否则会把资源损坏伪装成一份可执行模板。
     */
    suspend fun getResolvedTemplate(templateId: String): GridResolvedExperimentTemplate? {
        val bundle = templateRepository.getBundle(templateId) ?: return null
        val carrierId = bundle.template.carrierProfileId ?: return null
        val carrier = carrierProfileRepository.getById(carrierId) ?: return null
        return GridResolvedExperimentTemplate(bundle = bundle, carrier = carrier)
    }

    /**
     * 解析用户选择的模型；内置共享 PTL 首次选择时只创建数据库定义，不复制 APK 二进制。
     */
    suspend fun resolveAnalysisModel(
        snapshot: TemplateProjectSnapshot,
        analyteId: String,
        modelId: String
    ): AnalysisModelBundle {
        return if (BuiltInSharedConcentrationModel.isOptionId(modelId)) {
            require(BuiltInSharedConcentrationModel.canExecute(snapshot)) {
                "当前模态或输入协议不能执行内置共享浓度模型"
            }
            resolveBuiltInSharedModel(snapshot, analyteId)
        } else {
            analysisModelRepository.getBundle(modelId) ?: error("模型不存在")
        }
    }

    /**
     * 只返回与当前模态、协议、行列和分析物集合兼容的发布态资源。
     *
     * 页面停留期间资源仍可能变化，因此这里只提供选择摘要；真正应用时必须再次读取完整数据包。
     */
    suspend fun loadCompatibleResources(
        snapshot: TemplateProjectSnapshot,
        rows: Int,
        columns: Int
    ): GridConfigurationResources {
        val analyteIds = snapshot.analytes.map { it.analyte.id }.toSet()
        val builtInSharedExecutable = BuiltInSharedConcentrationModel.canExecute(snapshot)
        val builtInSharedValidated = BuiltInSharedConcentrationModel.isValidatedFor(snapshot)
        val storedModels = analysisModelRepository.observeAll().first().mapNotNull { model ->
            val type = AnalysisModelType.fromCode(model.modelType) ?: return@mapNotNull null
            val feature = AnalysisPrimaryFeature.fromCode(model.primaryFeature) ?: return@mapNotNull null
            if (
                model.status != AnalysisModelLifecycleStatus.PUBLISHED.code ||
                model.analyteId !in analyteIds ||
                model.detectionMode != snapshot.template.detectionMode ||
                model.inputProtocol != snapshot.template.inputProtocol
            ) {
                return@mapNotNull null
            }
            val builtInShared = BuiltInSharedConcentrationModel.isBuiltInResourceName(model.name)
            if (builtInShared && !builtInSharedExecutable) {
                // 模态或输入协议在技术上无法执行时仍必须过滤；载体超出验证范围则不再
                // 硬禁用，而是通过 requiresExplicitScopeConfirmation 要求用户确认。
                return@mapNotNull null
            }
            GridAnalysisModelOption(
                id = model.id,
                name = model.name,
                version = model.version,
                analyteId = model.analyteId,
                modelType = type,
                primaryFeature = feature,
                concentrationUnit = model.concentrationUnit,
                reliableRangeMin = model.reliableRangeMin,
                reliableRangeMax = model.reliableRangeMax,
                builtInShared = builtInShared,
                requiresExplicitScopeConfirmation = builtInShared && !builtInSharedValidated
            )
        }
        val persistedBuiltInKeys = storedModels
            .filter(GridAnalysisModelOption::builtInShared)
            .map(GridAnalysisModelOption::analyteId)
            .toSet()
        val builtInOptions: List<GridAnalysisModelOption> = if (!builtInSharedExecutable) {
            emptyList()
        } else {
            snapshot.analytes.mapNotNull { analyteSnapshot ->
                if (analyteSnapshot.analyte.id in persistedBuiltInKeys) return@mapNotNull null
                val feature = AnalysisPrimaryFeature.fromCode(
                    analyteSnapshot.analysisModel.model.primaryFeature
                ) ?: return@mapNotNull null
                GridAnalysisModelOption(
                    id = BuiltInSharedConcentrationModel.optionId(
                        analyteId = analyteSnapshot.analyte.id,
                        detectionMode = snapshot.template.detectionMode.orEmpty()
                    ),
                    name = BuiltInSharedConcentrationModel.resourceName(
                        analyteId = analyteSnapshot.analyte.id,
                        detectionMode = snapshot.template.detectionMode.orEmpty()
                    ),
                    version = 1,
                    analyteId = analyteSnapshot.analyte.id,
                    modelType = AnalysisModelType.DEEP_LEARNING,
                    primaryFeature = feature,
                    concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
                    reliableRangeMin = analyteSnapshot.templateConfig.reliableRangeMin,
                    reliableRangeMax = analyteSnapshot.templateConfig.reliableRangeMax,
                    builtInShared = true,
                    requiresExplicitScopeConfirmation = !builtInSharedValidated
                )
            }
        }
        val models = (storedModels + builtInOptions).sortedWith(
            compareBy(GridAnalysisModelOption::analyteId, GridAnalysisModelOption::name)
        )

        val templates = templateRepository.getAllTemplates().first().mapNotNull { template ->
            if (
                template.status != TemplateLifecycleStatus.PUBLISHED.code ||
                template.detectionMode != snapshot.template.detectionMode
            ) {
                return@mapNotNull null
            }
            val bundle = templateRepository.getBundle(template.id) ?: return@mapNotNull null
            val carrier = template.carrierProfileId
                ?.let { carrierProfileRepository.getById(it) }
                ?: return@mapNotNull null
            val templateAnalyteIds = bundle.analyteConfigs.map { it.analyteId }.toSet()
            if (
                carrier.rows != rows ||
                carrier.columns != columns ||
                templateAnalyteIds != analyteIds
            ) {
                return@mapNotNull null
            }
            GridExperimentTemplateOption(
                id = template.id,
                name = template.templateName,
                version = template.version,
                analyteIds = templateAnalyteIds
            )
        }.sortedBy(GridExperimentTemplateOption::name)

        return GridConfigurationResources(templates = templates, models = models)
    }

    /**
     * 将已完成配置的项目快照保存为一份发布模板。
     *
     * 每个分析物必须已经携带冻结定量快照。资源库未来被编辑或删除时，模板仍使用这里保存的
     * AnalysisModelBundle 与 AnalyteQuantitationSnapshot，不会在执行时重新解释当前资源。
     */
    suspend fun savePublishedTemplate(
        name: String,
        snapshot: TemplateProjectSnapshot
    ) {
        require(snapshot.siteAssignments.isNotEmpty()) { "模板孔位不能为空" }
        require(snapshot.analytes.isNotEmpty()) { "模板分析物不能为空" }
        require(snapshot.analytes.all { it.analyteQuantitationSnapshot != null }) {
            "模板定量快照不完整"
        }

        val carrier = resolvePersistedCarrier(snapshot)
        val acquisition = resolvePersistedAcquisition(snapshot)
        val temporaryTemplateId = "saved-template-${UUID.randomUUID()}"
        val firstAnalyte = snapshot.analytes.first()
        val template = ExperimentTemplate(
            id = temporaryTemplateId,
            templateName = name,
            analyteId = firstAnalyte.analyte.id,
            reagentAntigenId = firstAnalyte.templateConfig.reagentAntigenId,
            reagentAntibodyId = firstAnalyte.templateConfig.reagentAntibodyId,
            // 新模板只使用 TemplateAnalyteConfig.analysisModelId；旧 CurveModel 外键必须为空。
            fkCurveModelId = null,
            reliableRangeMin = firstAnalyte.templateConfig.reliableRangeMin ?: 0.0,
            reliableRangeMax = firstAnalyte.templateConfig.reliableRangeMax
                ?: firstAnalyte.analysisModel.model.reliableRangeMax,
            concentrationUnit = firstAnalyte.templateConfig.concentrationUnit,
            defaultLayoutJson = null,
            carrierProfileId = carrier.id,
            detectionMode = snapshot.template.detectionMode,
            readoutLayout = snapshot.template.readoutLayout,
            acquisitionProfileId = acquisition.id,
            inputProtocol = snapshot.template.inputProtocol,
            qcProfileJson = snapshot.template.qcProfileJson,
            purpose = snapshot.template.purpose ?: "array-quantitation-template"
        )
        val bindings = snapshot.analytes.map { analyte ->
            val quantitation = requireNotNull(analyte.analyteQuantitationSnapshot)
            val frozen = TemplateQuantitationResourceSnapshot(
                quantitation = quantitation,
                analysisModel = analyte.analysisModel
            )
            TemplateQuantitationBinding(
                id = "saved-template-quant-${UUID.randomUUID()}",
                templateId = temporaryTemplateId,
                analyteId = analyte.analyte.id,
                method = quantitation.method.name,
                sourceResourceId = quantitation.sourceResourceId,
                resourceSnapshotJson = TemplateQuantitationResourceSnapshotCodec.encode(frozen),
                contentFingerprint = TemplateQuantitationBindingFingerprint.create(frozen),
                processorName = analyte.analysisModel.model.processorName,
                processorVersion = quantitation.processorVersion
            )
        }
        val bundle = ExperimentTemplateBundle(
            template = template,
            analyteConfigs = snapshot.analytes.mapIndexed { index, analyte ->
                val quantitation = requireNotNull(analyte.analyteQuantitationSnapshot)
                analyte.templateConfig.copy(
                    id = "saved-template-analyte-${UUID.randomUUID()}",
                    templateId = temporaryTemplateId,
                    // 只记录真实资源外键；未入曲线库的现场拟合通过冻结摘要继续可执行。
                    analysisModelId = quantitation.sourceResourceId,
                    displayOrder = index
                )
            },
            siteAssignments = snapshot.siteAssignments.map { assignment ->
                assignment.copy(
                    id = "saved-template-site-${UUID.randomUUID()}",
                    templateId = temporaryTemplateId
                )
            },
            quantitationBindings = bindings
        )
        val created = templateRepository.createDraft(bundle)
        templateRepository.publish(created.template.id)
    }

    /**
     * 构建并发布一条由本次真实标准孔得到的标准曲线。
     *
     * 内容指纹而非名称决定是否复用，避免用户重复点击生成科学内容相同的多条资源。
     */
    suspend fun createAndPublishOnsiteCurve(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        resultSet: CalibrationResultSet,
        selectedCandidateId: String,
        name: String,
        runId: String?
    ): AnalysisModelBundle {
        val candidate = requireNotNull(resultSet.candidate(selectedCandidateId)) {
            "选中的现场曲线候选不存在"
        }
        val minimum = candidate.standardPoints.minOf { point -> point.first }
        val maximum = candidate.standardPoints.maxOf { point -> point.first }
        val contentFingerprint = CalibrationResourceFingerprint.create(
            analyteId = analyteSnapshot.analyte.id,
            modalityCode = snapshot.template.detectionMode.orEmpty(),
            concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
            candidate = candidate,
            processorVersion = resultSet.processorVersion,
            engineVersion = resultSet.engineVersion
        )

        analysisModelRepository.getReusableBundleByContentFingerprint(contentFingerprint)
            ?.let { existing ->
                if (existing.model.status == AnalysisModelLifecycleStatus.DRAFT.code) {
                    analysisModelRepository.publish(existing.model.id)
                }
                return analysisModelRepository.getBundle(existing.model.id)
                    ?: error("标准曲线复用失败")
            }
        val now = Date()
        val model = analyteSnapshot.analysisModel.model.copy(
            id = UUID.randomUUID().toString(),
            name = name,
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            detectionMode = snapshot.template.detectionMode.orEmpty(),
            inputProtocol = snapshot.template.inputProtocol,
            primaryFeature = candidate.primaryFeature.code,
            processorVersion = resultSet.processorVersion,
            // 直接新建项目的采集ID包含项目UUID，不能把它写成长效设备约束。
            compatibleAcquisitionProfileIdsJson = if (
                isDirectAcquisitionProfileId(snapshot.acquisitionProfile.id)
            ) {
                gson.toJson(emptyList<String>())
            } else {
                gson.toJson(listOf(snapshot.acquisitionProfile.id))
            },
            concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
            reliableRangeMin = minimum,
            reliableRangeMax = maximum,
            // 保存到资源库不得重新解释现场候选。schema、鲁棒目标、交叉验证和可信范围
            // 必须与本次运行的内存模型逐字段一致，内容指纹只额外用于资源去重。
            validationMetricsJson = gson.toJson(
                buildCalibrationValidationMetrics(
                    candidate = candidate,
                    resultSet = resultSet,
                    contentFingerprint = contentFingerprint
                )
            ),
            contentFingerprint = contentFingerprint,
            status = AnalysisModelLifecycleStatus.DRAFT.code,
            createdAt = now,
            updatedAt = now
        )
        val draft = analysisModelRepository.createDraft(
            AnalysisModelBundle(
                model = model,
                standardCurve = StandardCurveDefinition(
                    analysisModelId = model.id,
                    fittingFunction = candidate.function.identifier,
                    parametersJson = gson.toJson(candidate.parameters),
                    monotonicDirection = "AUTO"
                ),
                calibrationPoints = candidate.standardPoints.mapIndexed {
                        index, (concentration, signal) ->
                    CalibrationPoint(
                        id = UUID.randomUUID().toString(),
                        analysisModelId = model.id,
                        concentration = concentration,
                        signalValue = signal,
                        repeatIndex = index,
                        runId = runId
                    )
                }
            )
        )
        analysisModelRepository.publish(draft.model.id)
        return analysisModelRepository.getBundle(draft.model.id)
            ?: error("标准曲线保存失败")
    }

    /** 直接新建项目的内存载体先复用同规格资源，确无可复用项时才创建外键记录。 */
    private suspend fun resolvePersistedCarrier(
        snapshot: TemplateProjectSnapshot
    ): com.muc.fluocolorquant.data.model.CarrierProfile {
        carrierProfileRepository.getById(snapshot.carrierProfile.id)?.let { return it }
        val reusable = carrierProfileRepository.observeAll().first().firstOrNull { carrier ->
            carrier.name == snapshot.carrierProfile.name &&
                carrier.version == snapshot.carrierProfile.version &&
                carrier.carrierType == snapshot.carrierProfile.carrierType &&
                carrier.rows == snapshot.carrierProfile.rows &&
                carrier.columns == snapshot.carrierProfile.columns &&
                carrier.siteShape == snapshot.carrierProfile.siteShape &&
                carrier.locatorConfigJson == snapshot.carrierProfile.locatorConfigJson
        }
        if (reusable != null) return reusable
        carrierProfileRepository.create(snapshot.carrierProfile)
        return carrierProfileRepository.getById(snapshot.carrierProfile.id)
            ?: error("载体保存失败")
    }

    /** 自动采集配置只为模板外键落库，普通用户仍不需要填写设备专业参数。 */
    private suspend fun resolvePersistedAcquisition(
        snapshot: TemplateProjectSnapshot
    ): com.muc.fluocolorquant.data.model.AcquisitionProfile {
        acquisitionProfileRepository.getById(snapshot.acquisitionProfile.id)?.let { return it }
        val reusable = acquisitionProfileRepository.observeAll().first().firstOrNull { acquisition ->
            acquisition.name == snapshot.acquisitionProfile.name &&
                acquisition.version == snapshot.acquisitionProfile.version &&
                acquisition.supportedModesJson == snapshot.acquisitionProfile.supportedModesJson &&
                acquisition.compatibleCarrierTypesJson ==
                    snapshot.acquisitionProfile.compatibleCarrierTypesJson &&
                acquisition.cameraControlStrategy == snapshot.acquisitionProfile.cameraControlStrategy
        }
        if (reusable != null) return reusable
        acquisitionProfileRepository.create(snapshot.acquisitionProfile)
        return acquisitionProfileRepository.getById(snapshot.acquisitionProfile.id)
            ?: error("采集配置保存失败")
    }

    /** 内置模型首次使用时创建并发布分析物专属定义；后续按稳定资源名直接复用。 */
    private suspend fun resolveBuiltInSharedModel(
        snapshot: TemplateProjectSnapshot,
        analyteId: String
    ): AnalysisModelBundle {
        require(BuiltInSharedConcentrationModel.canExecute(snapshot)) {
            "当前模态或输入协议不能执行内置共享浓度模型"
        }
        val resourceName = BuiltInSharedConcentrationModel.resourceName(
            analyteId = analyteId,
            detectionMode = snapshot.template.detectionMode.orEmpty()
        )
        val existing = analysisModelRepository.observeAll().first().firstOrNull { model ->
            model.name == resourceName &&
                model.status == AnalysisModelLifecycleStatus.PUBLISHED.code
        }
        if (existing != null) {
            return analysisModelRepository.getBundle(existing.id)
                ?: error("内置共享模型定义不完整")
        }
        val analyteSnapshot = snapshot.analytes.firstOrNull { it.analyte.id == analyteId }
            ?: error("分析物不存在")
        val draft = analysisModelRepository.createDraft(
            BuiltInSharedConcentrationModel.createBundle(snapshot, analyteSnapshot)
        )
        analysisModelRepository.publish(draft.model.id)
        return analysisModelRepository.getBundle(draft.model.id)
            ?: error("内置共享模型保存失败")
    }
}
