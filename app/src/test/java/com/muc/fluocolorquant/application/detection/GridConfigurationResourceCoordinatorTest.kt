package com.muc.fluocolorquant.application.detection

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.calibration.CALIBRATION_ALGORITHM_SCHEMA_V2
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidate
import com.muc.fluocolorquant.domain.calibration.CalibrationCandidateStatus
import com.muc.fluocolorquant.domain.calibration.CalibrationCrossValidationMetrics
import com.muc.fluocolorquant.domain.calibration.CalibrationFunctionResult
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.CalibrationTrustedRange
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.quantification.BuiltInSharedConcentrationModel
import com.muc.fluocolorquant.domain.detection.quantification.PreparedStandardCurveQuantifier
import com.muc.fluocolorquant.domain.detection.quantification.StandardCurveQuantifier
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置资源协调器测试只使用内存仓库，固定模板发布时的冻结摘要和外键边界。
 *
 * 这些断言保护的不是页面文案，而是“现场曲线可以只冻结在模板中”和“损坏载体引用失败闭合”
 * 两项科学契约，防止职责从 ViewModel 下沉后悄悄改变历史可复现语义。
 */
class GridConfigurationResourceCoordinatorTest {

    @Test
    fun `发布模板保留冻结定量摘要且不伪造现场曲线外键`() = runTest {
        val templateRepository = FakeTemplateRepository()
        val carrierRepository = FakeCarrierRepository()
        val acquisitionRepository = FakeAcquisitionRepository()
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = templateRepository,
            carrierProfileRepository = carrierRepository,
            acquisitionProfileRepository = acquisitionRepository,
            analysisModelRepository = FakeAnalysisModelRepository()
        )
        val snapshot = snapshot()
        carrierRepository.profiles[snapshot.carrierProfile.id] = snapshot.carrierProfile
        acquisitionRepository.profiles[snapshot.acquisitionProfile.id] = snapshot.acquisitionProfile

        coordinator.savePublishedTemplate("现场方案", snapshot)

        val created = requireNotNull(templateRepository.createdBundle)
        assertEquals(created.template.id, templateRepository.publishedId)
        assertEquals(snapshot.carrierProfile.id, created.template.carrierProfileId)
        assertEquals(snapshot.acquisitionProfile.id, created.template.acquisitionProfileId)
        assertNull(created.analyteConfigs.single().analysisModelId)
        val binding = created.quantitationBindings.single()
        assertNull(binding.sourceResourceId)
        val frozen = TemplateQuantitationResourceSnapshotCodec.decode(binding.resourceSnapshotJson)
        assertEquals(
            snapshot.analytes.single().analyteQuantitationSnapshot,
            frozen.quantitation
        )
        val sourceModel = snapshot.analytes.single().analysisModel.model
        assertEquals(sourceModel.id, frozen.analysisModel.model.id)
        assertEquals(sourceModel.primaryFeature, frozen.analysisModel.model.primaryFeature)
        assertEquals(sourceModel.processorVersion, frozen.analysisModel.model.processorVersion)
        assertEquals(sourceModel.concentrationUnit, frozen.analysisModel.model.concentrationUnit)
    }

    @Test
    fun `现场曲线保存前后保留同一算法schema并可覆盖项目量程`() = runTest {
        val snapshot = snapshot()
        val analysisRepository = FakeAnalysisModelRepository()
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = FakeTemplateRepository(),
            carrierProfileRepository = FakeCarrierRepository(),
            acquisitionProfileRepository = FakeAcquisitionRepository(),
            analysisModelRepository = analysisRepository
        )
        val candidate = CalibrationCandidate(
            id = "gray:linear:0",
            analyteId = "cea",
            primaryFeature = AnalysisPrimaryFeature.GRAY_LUMINOSITY,
            function = FittingFunction.LINEAR,
            parameters = mapOf("a" to 2.0, "b" to 1.0),
            standardPoints = listOf(20.0 to 41.0, 60.0 to 121.0),
            curvePoints = listOf(20.0 to 41.0, 60.0 to 121.0),
            latexFormula = "y=2x+1",
            rSquared = 1.0,
            rmse = 0.0,
            normalizedRmse = 0.0,
            mae = 0.0,
            backCalculatedRmsePercent = 0.0,
            acceptedStandardRatio = 1.0,
            weightingCode = 0,
            accepted = true,
            status = CalibrationCandidateStatus.AVAILABLE,
            crossValidation = CalibrationCrossValidationMetrics(
                validationLevelCount = 2,
                successfulLevelCount = 2,
                successRatio = 1.0,
                medianRelativeErrorPercent = 0.0,
                p90RelativeErrorPercent = 0.0,
                endpointRelativeErrorPercent = 0.0,
                parameterStabilityScore = 1.0
            ),
            trustedRange = CalibrationTrustedRange(
                minimum = 0.0,
                maximum = 100.0,
                confidenceLevel = 0.95,
                parameterSampleCount = 256,
                validSampleRatio = 1.0,
                methodVersion = "hessian-sampling-v1",
                transformedParameterCovariance = listOf(
                    listOf(0.0, 0.0),
                    listOf(0.0, 0.0)
                ),
                samplingSeed = 42L,
                residualSignalScale = 0.01
            )
        )
        val resultSet = CalibrationResultSet(
            analyteId = "cea",
            inputFingerprint = "onsite-input",
            policySnapshot = CalibrationPolicy.DEFAULT,
            functionResults = listOf(
                CalibrationFunctionResult(FittingFunction.LINEAR, candidate)
            ),
            recommendedCandidateId = candidate.id,
            processorVersion = "2.0.0",
            engineVersion = "test-engine",
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        )

        val saved = coordinator.createAndPublishOnsiteCurve(
            snapshot = snapshot,
            analyteSnapshot = snapshot.analytes.single(),
            resultSet = resultSet,
            selectedCandidateId = candidate.id,
            name = "CEA 现场曲线",
            runId = "run-1"
        )

        val metadata = JsonParser.parseString(saved.model.validationMetricsJson).asJsonObject
        assertEquals(
            CALIBRATION_ALGORITHM_SCHEMA_V2,
            metadata.get("CALIBRATION_ALGORITHM_SCHEMA").asString
        )
        assertTrue(metadata.has("CROSS_VALIDATION"))
        assertTrue(metadata.has("TRUSTED_RANGE"))
        assertTrue(metadata.has("CONTENT_FINGERPRINT"))
        assertTrue(
            StandardCurveQuantifier.prepare(
                bundle = saved,
                projectRangeMin = 0.0,
                projectRangeMax = 100.0
            ) is PreparedStandardCurveQuantifier.Ready
        )
    }

    @Test
    fun `模板名称忽略大小写且损坏载体引用不会被当前项目补齐`() = runTest {
        val snapshot = snapshot()
        val templateRepository = FakeTemplateRepository().apply {
            bundles[snapshot.template.id] = ExperimentTemplateBundle(
                template = snapshot.template.copy(templateName = "Reusable Assay")
            )
            refresh()
        }
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = templateRepository,
            carrierProfileRepository = FakeCarrierRepository(),
            acquisitionProfileRepository = FakeAcquisitionRepository(),
            analysisModelRepository = FakeAnalysisModelRepository()
        )

        assertTrue(coordinator.templateNameExists("reusable assay"))
        // Fake 载体仓库故意为空：协调器必须把损坏资源判为不可应用，而不是拿项目载体兜底。
        assertNull(coordinator.getResolvedTemplate(snapshot.template.id))
    }

    @Test
    fun `资源列表只公开同模态同协议同规格和同分析物的发布资源`() = runTest {
        val snapshot = snapshot()
        val templateRepository = FakeTemplateRepository()
        val carrierRepository = FakeCarrierRepository().apply {
            profiles[snapshot.carrierProfile.id] = snapshot.carrierProfile
            profiles["carrier-15x15"] = snapshot.carrierProfile.copy(
                id = "carrier-15x15",
                rows = 15,
                columns = 15
            )
        }
        val matchingTemplate = snapshot.template.copy(
            id = "matching-template",
            templateName = "兼容方案",
            status = TemplateLifecycleStatus.PUBLISHED.code
        )
        val wrongSizeTemplate = matchingTemplate.copy(
            id = "wrong-size-template",
            templateName = "错误规格方案",
            carrierProfileId = "carrier-15x15"
        )
        templateRepository.bundles[matchingTemplate.id] = ExperimentTemplateBundle(
            template = matchingTemplate,
            analyteConfigs = listOf(
                snapshot.analytes.single().templateConfig.copy(templateId = matchingTemplate.id)
            )
        )
        templateRepository.bundles[wrongSizeTemplate.id] = ExperimentTemplateBundle(
            template = wrongSizeTemplate,
            analyteConfigs = listOf(
                snapshot.analytes.single().templateConfig.copy(templateId = wrongSizeTemplate.id)
            )
        )
        templateRepository.refresh()
        val analysisRepository = FakeAnalysisModelRepository().apply {
            put(
                AnalysisModelBundle(
                    model = snapshot.analytes.single().analysisModel.model.copy(
                        id = "matching-model",
                        name = "兼容曲线",
                        status = AnalysisModelLifecycleStatus.PUBLISHED.code
                    )
                )
            )
            put(
                AnalysisModelBundle(
                    model = snapshot.analytes.single().analysisModel.model.copy(
                        id = "wrong-protocol-model",
                        name = "错误协议曲线",
                        inputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                        status = AnalysisModelLifecycleStatus.PUBLISHED.code
                    )
                )
            )
            put(
                AnalysisModelBundle(
                    model = snapshot.analytes.single().analysisModel.model.copy(
                        id = "legacy-wrong-built-in",
                        name = BuiltInSharedConcentrationModel.resourceName(
                            analyteId = "cea",
                            detectionMode = DetectionModality.COLORIMETRIC.code
                        ),
                        modelType = AnalysisModelType.DEEP_LEARNING.code,
                        // 模拟旧版错误数据：模型兼容 JSON 曾照抄当前微流控载体。
                        compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                        status = AnalysisModelLifecycleStatus.PUBLISHED.code
                    )
                )
            )
        }
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = templateRepository,
            carrierProfileRepository = carrierRepository,
            acquisitionProfileRepository = FakeAcquisitionRepository(),
            analysisModelRepository = analysisRepository
        )

        val resources = coordinator.loadCompatibleResources(
            snapshot = snapshot,
            rows = 10,
            columns = 10
        )

        assertEquals(listOf("matching-template"), resources.templates.map { it.id })
        assertTrue(resources.models.any { it.id == "matching-model" })
        val experimentalBuiltIn = resources.models.single(GridAnalysisModelOption::builtInShared)
        assertTrue(experimentalBuiltIn.requiresExplicitScopeConfirmation)
        assertTrue(resources.models.none { it.id == "wrong-protocol-model" })
    }

    @Test
    fun `内置共享模型在96圆孔板标记为已验证并冻结技术载体范围`() = runTest {
        val base = snapshot()
        val plateSnapshot = base.copy(
            template = base.template.copy(carrierProfileId = "carrier-plate96"),
            carrierProfile = base.carrierProfile.copy(
                id = "carrier-plate96",
                name = "96 孔板",
                carrierType = CarrierType.PLATE.code,
                rows = 8,
                columns = 12,
                siteShape = SiteShape.CIRCLE.code
            ),
            acquisitionProfile = base.acquisitionProfile.copy(
                id = "direct-acquisition-plate-project",
                compatibleCarrierTypesJson = "[\"PLATE\"]"
            )
        )
        val analysisRepository = FakeAnalysisModelRepository()
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = FakeTemplateRepository(),
            carrierProfileRepository = FakeCarrierRepository(),
            acquisitionProfileRepository = FakeAcquisitionRepository(),
            analysisModelRepository = analysisRepository
        )

        val resources = coordinator.loadCompatibleResources(
            snapshot = plateSnapshot,
            rows = 8,
            columns = 12
        )

        val option = resources.models.single(GridAnalysisModelOption::builtInShared)
        assertTrue(!option.requiresExplicitScopeConfirmation)
        val resolved = coordinator.resolveAnalysisModel(
            snapshot = plateSnapshot,
            analyteId = "cea",
            modelId = option.id
        )
        assertEquals(
            "[\"PLATE\",\"MICROFLUIDIC_CHIP\"]",
            resolved.model.compatibleCarrierTypesJson
        )
        assertEquals(
            "legacy-plate96-concentration-v1",
            resolved.deepLearning?.trainingDataVersion
        )
    }

    @Test
    fun `微流控确认风险后可解析内置模型并保留验证范围元数据`() = runTest {
        val snapshot = snapshot()
        val coordinator = GridConfigurationResourceCoordinator(
            templateRepository = FakeTemplateRepository(),
            carrierProfileRepository = FakeCarrierRepository(),
            acquisitionProfileRepository = FakeAcquisitionRepository(),
            analysisModelRepository = FakeAnalysisModelRepository()
        )

        val resources = coordinator.loadCompatibleResources(snapshot, rows = 10, columns = 10)
        val option = resources.models.single(GridAnalysisModelOption::builtInShared)
        assertTrue(option.requiresExplicitScopeConfirmation)

        val resolved = coordinator.resolveAnalysisModel(
            snapshot = snapshot,
            analyteId = "cea",
            modelId = BuiltInSharedConcentrationModel.optionId(
                analyteId = "cea",
                detectionMode = DetectionModality.COLORIMETRIC.code
            )
        )

        assertTrue(
            resolved.deepLearning?.metadataJson.orEmpty()
                .contains("VALIDATED_FOR_PLATE96_CIRCLE_ONLY")
        )
    }

    private fun snapshot(): TemplateProjectSnapshot {
        val template = ExperimentTemplate(
            id = "project-template",
            templateName = "项目内存方案",
            analyteId = "cea",
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            concentrationUnit = "ng/mL",
            defaultLayoutJson = null,
            status = TemplateLifecycleStatus.PUBLISHED.code,
            carrierProfileId = "carrier-10x10",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = "direct-acquisition-project-1",
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code
        )
        val carrier = CarrierProfile(
            id = "carrier-10x10",
            name = "10×10 芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.SQUARE.code,
            status = ResourceStatus.ACTIVE.code
        )
        val acquisition = AcquisitionProfile(
            id = "direct-acquisition-project-1",
            name = "自动采集",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "AUTO_METADATA",
            status = ResourceStatus.ACTIVE.code
        )
        val analyte = Analyte(id = "cea", name = "CEA")
        val model = AnalysisModel(
            id = "onsite-memory-model",
            name = "本次现场曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.COLORIMETRIC.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.GRAY_LUMINOSITY.code,
            processorName = "ColorimetricProcessor",
            processorVersion = "2.0.0",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.DRAFT.code
        )
        val quantitation = AnalyteQuantitationSnapshot(
            analyteId = analyte.id,
            method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
            concentrationUnit = "ng/mL",
            // 未勾选保存到曲线库时没有真实外键，但完整模型和定量内容仍必须进入模板摘要。
            sourceResourceId = null,
            processorVersion = "2.0.0",
            inputFingerprint = "onsite-fingerprint"
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_753_000_000_000L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = analyte,
                    templateConfig = TemplateAnalyteConfig(
                        id = "config-cea",
                        templateId = template.id,
                        analyteId = analyte.id,
                        analysisModelId = model.id,
                        concentrationUnit = "ng/mL",
                        reliableRangeMin = 0.0,
                        reliableRangeMax = 100.0
                    ),
                    analysisModel = AnalysisModelBundle(model = model),
                    quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                    analyteQuantitationSnapshot = quantitation
                )
            ),
            siteAssignments = listOf(
                TemplateSiteAssignment(
                    id = "site-0-0",
                    templateId = template.id,
                    rowIndex = 0,
                    columnIndex = 0,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = "A1"
                )
            )
        )
    }

    private class FakeTemplateRepository : ExperimentTemplateRepository {
        val bundles = linkedMapOf<String, ExperimentTemplateBundle>()
        private val templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
        var createdBundle: ExperimentTemplateBundle? = null
        var publishedId: String? = null

        fun refresh() {
            templates.value = bundles.values.map(ExperimentTemplateBundle::template)
        }

        override fun getAllTemplates(): Flow<List<ExperimentTemplate>> = templates
        override suspend fun getTemplateById(id: String): ExperimentTemplate? = bundles[id]?.template
        override suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate> = emptyList()
        override suspend fun getBundle(id: String): ExperimentTemplateBundle? = bundles[id]
        override suspend fun saveTemplate(template: ExperimentTemplate) = unsupported()
        override suspend fun updateTemplate(template: ExperimentTemplate) = unsupported()
        override suspend fun deleteTemplate(template: ExperimentTemplate) = unsupported()
        override suspend fun deleteTemplateById(id: String) = unsupported()
        override suspend fun createDraft(
            bundle: ExperimentTemplateBundle
        ): ExperimentTemplateBundle {
            createdBundle = bundle
            bundles[bundle.template.id] = bundle
            refresh()
            return bundle
        }
        override suspend fun updateDraft(bundle: ExperimentTemplateBundle) = unsupported()
        override suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle = unsupported()
        override suspend fun publish(id: String) {
            publishedId = id
        }
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeCarrierRepository : CarrierProfileRepository {
        val profiles = linkedMapOf<String, CarrierProfile>()
        override fun observeAll(): Flow<List<CarrierProfile>> =
            MutableStateFlow(profiles.values.toList())
        override suspend fun getById(id: String): CarrierProfile? = profiles[id]
        override suspend fun create(profile: CarrierProfile) {
            profiles[profile.id] = profile
        }
        override suspend fun createNextVersion(
            previousId: String,
            replacement: CarrierProfile
        ): CarrierProfile = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeAcquisitionRepository : AcquisitionProfileRepository {
        val profiles = linkedMapOf<String, AcquisitionProfile>()
        override fun observeAll(): Flow<List<AcquisitionProfile>> =
            MutableStateFlow(profiles.values.toList())
        override suspend fun getById(id: String): AcquisitionProfile? = profiles[id]
        override suspend fun create(profile: AcquisitionProfile) {
            profiles[profile.id] = profile
        }
        override suspend fun createNextVersion(
            previousId: String,
            replacement: AcquisitionProfile
        ): AcquisitionProfile = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeAnalysisModelRepository : AnalysisModelRepository {
        private val bundles = linkedMapOf<String, AnalysisModelBundle>()

        fun put(bundle: AnalysisModelBundle) {
            bundles[bundle.model.id] = bundle
        }

        override fun observeAll(): Flow<List<AnalysisModel>> =
            MutableStateFlow(bundles.values.map(AnalysisModelBundle::model))
        override suspend fun getBundle(id: String): AnalysisModelBundle? = bundles[id]
        override suspend fun getReusableBundleByContentFingerprint(
            fingerprint: String
        ): AnalysisModelBundle? = bundles.values.firstOrNull { bundle ->
            bundle.model.contentFingerprint == fingerprint
        }
        override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle {
            bundles[bundle.model.id] = bundle
            return bundle
        }
        override suspend fun updateDraft(bundle: AnalysisModelBundle) = unsupported()
        override suspend fun replace(bundle: AnalysisModelBundle) = unsupported()
        override suspend fun delete(id: String) = unsupported()
        override suspend fun createNextDraft(previousId: String): AnalysisModelBundle = unsupported()
        override suspend fun publish(id: String) = Unit
        override suspend fun archive(id: String) = unsupported()
    }

    private companion object {
        fun unsupported(): Nothing = throw UnsupportedOperationException("该 Fake 不支持此操作")
    }
}
