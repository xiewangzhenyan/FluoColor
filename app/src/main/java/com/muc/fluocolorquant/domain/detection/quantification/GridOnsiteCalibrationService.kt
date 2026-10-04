package com.muc.fluocolorquant.domain.detection.quantification

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.calibration.ArrayCalibrationEngine
import com.muc.fluocolorquant.domain.calibration.CalibrationApplicationService
import com.muc.fluocolorquant.domain.calibration.CalibrationDraft
import com.muc.fluocolorquant.domain.calibration.CalibrationInputFingerprint
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.CalibrationStandardObservation
import com.muc.fluocolorquant.domain.calibration.buildCalibrationValidationMetrics
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricPhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricProcessorConfig
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FluorescencePhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceProcessorConfig
import com.muc.fluocolorquant.domain.detection.photometry.PG_QUANT_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则阵列现场标定的科学服务。
 *
 * 该类只负责三件事：从本次冻结快照和光度结果组装标准观测、比较候选曲线、把用户已经
 * 选定的候选冻结为可执行定量快照。它不访问 Room、不保存曲线资源，也不持有页面状态，
 * 因而 96 孔板和自定义阵列可以共享同一套科学契约。
 *
 * [preview] 与 [applySelection] 被刻意分开：预览阶段允许比较多个候选；应用阶段只接受
 * 已经产生的结果集和候选 ID，绝不能重新读取标准孔或再次拟合，否则同一次用户确认可能
 * 得到不同参数，破坏历史结果的可复现性。
 */
@Singleton
class GridOnsiteCalibrationService @Inject constructor(
    private val calibrationEngine: ArrayCalibrationEngine,
    private val calibrationApplicationService: CalibrationApplicationService
) {
    /** JVM 协调器测试不启动 Hilt，使用与生产依赖同实现的轻量构造入口。 */
    constructor() : this(ArrayCalibrationEngine(), CalibrationApplicationService())

    private val gson = Gson()

    /**
     * 将用户在审阅阶段选中的现场曲线冻结到项目分析物快照。
     *
     * 项目量程与现场标定范围具有不同科学语义：前者继续保留在模板配置中，后者只写入
     * 曲线模型和标定快照。保存为资源后传入的 [sourceResourceId] 只记录来源，不会改变
     * 本次已经确认的函数、参数、标准点或质量指标。
     */
    fun applySelection(
        snapshot: TemplateProjectSnapshot,
        resultSet: CalibrationResultSet,
        selectedCandidateId: String,
        runId: String,
        sourceResourceId: String? = null
    ): TemplateProjectSnapshot {
        val updatedAnalytes = snapshot.analytes.map { analyteSnapshot ->
            if (analyteSnapshot.analyte.id != resultSet.analyteId) {
                return@map analyteSnapshot
            }
            val candidate = requireNotNull(resultSet.candidate(selectedCandidateId)) {
                "选中的现场曲线候选不存在"
            }
            val quantitationSnapshot = calibrationApplicationService.freezeOnsiteCalibration(
                resultSet = resultSet,
                selectedCandidateId = selectedCandidateId,
                concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
                sourceResourceId = sourceResourceId
            )
            val calibration = requireNotNull(quantitationSnapshot.calibration)
            val model = analyteSnapshot.analysisModel.model.copy(
                name = "onsite-auto-fit",
                modelType = AnalysisModelType.STANDARD_CURVE.code,
                primaryFeature = calibration.primaryFeature,
                reliableRangeMin = calibration.reliableRangeMin,
                reliableRangeMax = calibration.reliableRangeMax,
                // 内存应用和保存到曲线库必须复用同一元数据构造器；否则保存动作会改变
                // 同一条曲线的可执行语义，最终表现为拟合页有浓度、结果页只剩信号。
                validationMetricsJson = gson.toJson(
                    buildCalibrationValidationMetrics(candidate, resultSet)
                ),
                updatedAt = Date()
            )
            val curve = StandardCurveDefinition(
                analysisModelId = model.id,
                fittingFunction = calibration.fittingFunction,
                parametersJson = gson.toJson(calibration.parameters),
                monotonicDirection = "AUTO"
            )
            val calibrationPoints = calibration.standardPoints.mapIndexed {
                    index, (concentration, signal) ->
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
                    // 模板配置必须指向真正冻结的曲线模型；项目量程仍保留原值，不能被
                    // 本次标准点覆盖范围替换。
                    analysisModelId = model.id
                ),
                analysisModel = AnalysisModelBundle(
                    model = model,
                    standardCurve = curve,
                    calibrationPoints = calibrationPoints
                ),
                quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                onsiteSelectedFeature = candidate.primaryFeature.code,
                onsiteSelectedFunction = candidate.function.identifier,
                analyteQuantitationSnapshot = quantitationSnapshot
            )
        }
        return snapshot.copy(analytes = updatedAnalytes)
    }

    /**
     * 根据本次真实标准孔，为一个分析物比较“候选信号 × 候选拟合函数”。
     *
     * 每种信号只执行一次光度处理，再供多个拟合函数共享。跨信号特征不能使用原始 RMSE
     * 排名，因为灰度、Lab、光密度和荧光强度量纲不同；最终推荐由标定引擎使用接受率、
     * 反算误差、标准化 RMSE、R² 和策略门槛共同裁决。
     */
    fun preview(
        snapshot: TemplateProjectSnapshot,
        quant: PgQuantResult,
        analyteId: String,
        policy: CalibrationPolicy = CalibrationPolicy.DEFAULT
    ): CalibrationResultSet {
        val modality = DetectionModality.fromCode(snapshot.template.detectionMode)
            ?: return unavailableResult(analyteId, policy)
        val analyteSnapshot = snapshot.analytes.firstOrNull { it.analyte.id == analyteId }
            ?: return unavailableResult(analyteId, policy)
        val standards = validStandards(snapshot, analyteId)

        val selectedFeatures = analyteSnapshot.onsiteSelectedFeatures.orEmpty()
            .mapNotNull(AnalysisPrimaryFeature::fromCode)
            .toCollection(linkedSetOf())
            .ifEmpty {
                analyteSnapshot.onsiteSelectedFeature
                    ?.let(AnalysisPrimaryFeature::fromCode)
                    ?.let(::setOf)
                    .orEmpty()
            }
        val policyFeatures = when (modality) {
            DetectionModality.COLORIMETRIC -> policy.colorimetricFeatures
            DetectionModality.FLUORESCENCE -> policy.fluorescenceFeatures
            DetectionModality.SPECTRUM -> emptySet()
        }
        val features = selectedFeatures.takeIf(Set<AnalysisPrimaryFeature>::isNotEmpty)
            ?: AnalysisFeaturePolicy.allowedFeatures(modality).intersect(policyFeatures)
        val selectedFunctions = analyteSnapshot.onsiteSelectedFunctions.orEmpty()
            .mapNotNull(FittingFunction::fromIdentifier)
            .filterTo(linkedSetOf()) { it != FittingFunction.INTERPOLATION }
            .ifEmpty {
                analyteSnapshot.onsiteSelectedFunction
                    ?.let(FittingFunction::fromIdentifier)
                    ?.takeIf { it != FittingFunction.INTERPOLATION }
                    ?.let(::setOf)
                    .orEmpty()
            }
        val functions = selectedFunctions.takeIf(Set<FittingFunction>::isNotEmpty)
            ?: policy.allowedFunctions

        // 每个信号只提取一次，再按标准孔组装稳定矩阵；函数之间禁止重复执行光度处理。
        val signalMatrix = features.associateWith { feature ->
            signalValuesForFeature(
                snapshot = snapshot,
                quant = quant,
                analyteSnapshot = analyteSnapshot,
                modality = modality,
                feature = feature
            ).orEmpty()
        }
        val observations = standards.mapNotNull { assignment ->
            val concentration = assignment.standardConcentration ?: return@mapNotNull null
            val siteIndex = assignment.rowIndex * snapshot.carrierProfile.columns +
                assignment.columnIndex
            val basePhotometry = quant.sites.getOrNull(siteIndex)
            CalibrationStandardObservation(
                siteIndex = siteIndex,
                concentration = concentration,
                signals = features.associateWith { feature ->
                    signalMatrix[feature]?.get(siteIndex)?.takeIf(Double::isFinite)
                },
                qualityReliable = basePhotometry?.qc?.qualityReliable,
                saturationRatio = basePhotometry?.saturationRatio,
                photometryFlags = basePhotometry?.qc?.flags
                    ?.mapTo(linkedSetOf()) { it.name }
                    .orEmpty()
            )
        }
        val processorVersion = when (modality) {
            DetectionModality.COLORIMETRIC -> COLORIMETRIC_PROCESSOR_VERSION
            DetectionModality.FLUORESCENCE -> FLUORESCENCE_PROCESSOR_VERSION
            DetectionModality.SPECTRUM -> PG_QUANT_PROCESSOR_VERSION
        }
        val fingerprint = CalibrationInputFingerprint.create(
            analyteId = analyteId,
            concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
            processorVersion = processorVersion,
            observations = observations,
            requestedFeatures = features,
            requestedFunctions = functions,
            policy = policy,
            projectRangeMin = analyteSnapshot.templateConfig.reliableRangeMin,
            projectRangeMax = analyteSnapshot.templateConfig.reliableRangeMax
        )
        return calibrationEngine.fit(
            CalibrationDraft(
                analyteId = analyteId,
                modality = modality,
                concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
                observations = observations,
                requestedFeatures = features,
                requestedFunctions = functions,
                processorVersion = processorVersion,
                policy = policy,
                inputFingerprint = fingerprint,
                projectRangeMin = analyteSnapshot.templateConfig.reliableRangeMin,
                projectRangeMax = analyteSnapshot.templateConfig.reliableRangeMax
            )
        )
    }

    /** 缺少模态或分析物属于结构化科学失败，不应退回 nullable 或抛技术异常。 */
    private fun unavailableResult(
        analyteId: String,
        policy: CalibrationPolicy
    ): CalibrationResultSet {
        val emptyDraft = CalibrationDraft(
            analyteId = analyteId,
            modality = DetectionModality.COLORIMETRIC,
            concentrationUnit = "",
            observations = emptyList(),
            requestedFeatures = policy.colorimetricFeatures,
            requestedFunctions = policy.allowedFunctions,
            processorVersion = PG_QUANT_PROCESSOR_VERSION,
            policy = policy,
            inputFingerprint = "unavailable:$analyteId"
        )
        return calibrationEngine.fit(emptyDraft)
    }

    /** 获取一个分析物全部有效标准孔，并保持物理行优先顺序以稳定输入指纹。 */
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
     *
     * 只有 ΔE2000 和相对光密度依赖真实参考位；灰度、RGB、Lab 及扩展颜色特征没有
     * 参考位也能计算。不能把“某个候选缺少前置条件”扩大成整个分析物没有有效信号。
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
                if (references.isEmpty() && AnalysisFeaturePolicy.requiresReference(feature)) {
                    return null
                }
                ColorimetricPhotometryProcessor.process(
                    quant,
                    ColorimetricProcessorConfig(references, feature)
                ).sites.mapNotNull { site ->
                    site.primaryFeatureValue?.takeIf(Double::isFinite)?.let { value ->
                        site.base.siteIndex to value
                    }
                }.toMap()
            }

            DetectionModality.FLUORESCENCE -> {
                val channel = ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                    analyteSnapshot.templateConfig.displayConfigJson
                ) ?: return null
                FluorescencePhotometryProcessor.process(
                    quant,
                    FluorescenceProcessorConfig(channel, feature)
                ).sites.mapNotNull { site ->
                    site.primaryFeatureValue?.takeIf(Double::isFinite)?.let { value ->
                        site.base.siteIndex to value
                    }
                }.toMap()
            }

            DetectionModality.SPECTRUM -> null
        }
    }

    /** 分析物专属参考位与全局参考位共同生效，索引计算必须使用冻结列数。 */
    private fun referenceIndices(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): Set<Int> = snapshot.siteAssignments.filter { assignment ->
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
