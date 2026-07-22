package com.muc.fluocolorquant.domain.project

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
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板项目创建协调器的实验前检查测试。
 *
 * Fake 仓库只模拟读取，不把 Room 或 Compose 引入单元测试，从而能精确验证某一项科学
 * 资源不匹配时返回的机器码，而不是只验证页面是否弹出一条模糊错误消息。
 */
class TemplateProjectCreationCoordinatorTest {

    private val templateRepository = FakeTemplateRepository()
    private val carrierRepository = FakeCarrierRepository()
    private val acquisitionRepository = FakeAcquisitionRepository()
    private val analysisModelRepository = FakeAnalysisModelRepository()
    private val analyteRepository = FakeAnalyteRepository()
    private val projectRepository = FakeProjectRepository()

    private val coordinator = TemplateProjectCreationCoordinator(
        templateRepository = templateRepository,
        carrierProfileRepository = carrierRepository,
        acquisitionProfileRepository = acquisitionRepository,
        analysisModelRepository = analysisModelRepository,
        analyteRepository = analyteRepository,
        projectRepository = projectRepository
    )

    @Test
    fun `普通入口只展示已发布模板且不会自动选择第一项`() = runTest {
        seedReadyTemplate()
        templateRepository.bundles["draft-template"] = templateBundle(
            id = "draft-template",
            status = TemplateLifecycleStatus.DRAFT.code
        )
        templateRepository.refresh()

        val published = coordinator.observePublishedTemplates().first()

        assertEquals(listOf("template-v1"), published.map { it.id })
    }

    @Test
    fun `已发布且资源模型完整兼容的模板可以进入创建流程`() = runTest {
        seedReadyTemplate()

        val result = coordinator.resolveTemplate("template-v1")

        assertTrue(result is TemplateResolution.Ready)
        val ready = result as TemplateResolution.Ready
        assertEquals(100, ready.configuration.snapshot.siteAssignments.size)
        assertEquals("carrier-10x10", ready.configuration.snapshot.carrierProfile.id)
        assertEquals("device-v1", ready.configuration.snapshot.acquisitionProfile.id)
        assertEquals(ProjectDetectionDestination.GRID_ENDPOINT, ready.configuration.destination)
    }

    @Test
    fun `模型模态不匹配时返回明确机器码并阻止创建`() = runTest {
        seedReadyTemplate()
        analysisModelRepository.bundles["model-cea"] = modelBundle(
            detectionMode = DetectionModality.FLUORESCENCE.code
        )

        val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked

        assertTrue(result.issues.any { it.code == TemplatePreflightCode.MODEL_MODALITY_MISMATCH })
    }

    @Test
    fun `草稿模板不能从普通新建项目入口使用`() = runTest {
        seedReadyTemplate(templateStatus = TemplateLifecycleStatus.DRAFT.code)

        val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked

        assertTrue(result.issues.any { it.code == TemplatePreflightCode.TEMPLATE_NOT_PUBLISHED })
    }

    @Test
    fun `载体资源缺失时阻止创建而不是退回默认96孔板`() = runTest {
        seedReadyTemplate()
        carrierRepository.profiles.clear()

        val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked

        assertTrue(result.issues.any { it.code == TemplatePreflightCode.CARRIER_MISSING })
    }

    @Test
    fun `规则阵列位点数量不足时阻止创建`() = runTest {
        seedReadyTemplate()
        val current = templateRepository.bundles.getValue("template-v1")
        templateRepository.bundles["template-v1"] = current.copy(
            siteAssignments = current.siteAssignments.dropLast(1)
        )

        val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked

        assertTrue(result.issues.any { it.code == TemplatePreflightCode.LAYOUT_INCOMPLETE })
    }

    @Test
    fun `创建时冻结模板版本项目批次样本批次并原子保存分析物关联`() = runTest {
        seedReadyTemplate()

        val outcome = coordinator.createProject(
            TemplateProjectCreateRequest(
                name = "CEA 10×10 批次 01",
                templateId = "template-v1",
                projectBatch = "P-20260720",
                sampleBatch = "S-01",
                sampleSlotMapping = (1..100).associate { index ->
                    val row = (index - 1) / 10 + 1
                    val column = (index - 1) % 10 + 1
                    String.format("R%02dC%02d", row, column) to String.format("sample-%03d", index)
                },
                imageUri = "content://chip/1",
                userId = "1"
            )
        )

        assertTrue(outcome is TemplateProjectCreationOutcome.Created)
        val created = outcome as TemplateProjectCreationOutcome.Created
        assertEquals("template-v1", created.project.templateId)
        assertEquals(1, created.project.templateVersion)
        assertEquals("P-20260720", created.project.projectBatch)
        assertEquals("S-01", created.project.sampleBatch)
        assertEquals(10, created.project.rows)
        assertEquals(10, created.project.columns)
        assertTrue(created.project.templateSnapshotJson.orEmpty().contains("\"model-cea\""))
        assertEquals("template-v1", projectRepository.savedJoins.single().fkTemplateId)
        assertEquals(created.project.id, projectRepository.savedProject?.id)
    }

    private fun seedReadyTemplate(
        templateStatus: String = TemplateLifecycleStatus.PUBLISHED.code
    ) {
        templateRepository.bundles["template-v1"] = templateBundle(status = templateStatus)
        templateRepository.refresh()
        carrierRepository.profiles["carrier-10x10"] = CarrierProfile(
            id = "carrier-10x10",
            name = "10×10 微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.CIRCLE.code,
            status = ResourceStatus.ACTIVE.code
        )
        acquisitionRepository.profiles["device-v1"] = AcquisitionProfile(
            id = "device-v1",
            name = "固定比色装置",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "FIXED_PROFILE",
            status = ResourceStatus.ACTIVE.code
        )
        analyteRepository.analytes["cea"] = Analyte(id = "cea", name = "CEA")
        analysisModelRepository.bundles["model-cea"] = modelBundle()
    }

    private fun templateBundle(
        id: String = "template-v1",
        status: String = TemplateLifecycleStatus.PUBLISHED.code
    ): ExperimentTemplateBundle {
        val template = ExperimentTemplate(
            id = id,
            templateName = "CEA 10×10 比色芯片",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 0.0,
            concentrationUnit = "",
            defaultLayoutJson = null,
            version = 1,
            status = status,
            carrierProfileId = "carrier-10x10",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = "device-v1",
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code
        )
        val analyteConfig = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = id,
            analyteId = "cea",
            analysisModelId = "model-cea",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0
        )
        val sites = (0 until 10).flatMap { row ->
            (0 until 10).map { column ->
                TemplateSiteAssignment(
                    id = "site-$row-$column",
                    templateId = id,
                    rowIndex = row,
                    columnIndex = column,
                    analyteId = "cea",
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = String.format("R%02dC%02d", row + 1, column + 1)
                )
            }
        }
        return ExperimentTemplateBundle(
            template = template,
            analyteConfigs = listOf(analyteConfig),
            siteAssignments = sites
        )
    }

    private fun modelBundle(
        detectionMode: String = DetectionModality.COLORIMETRIC.code
    ): AnalysisModelBundle {
        return AnalysisModelBundle(
            model = AnalysisModel(
                id = "model-cea",
                name = "CEA ΔE 标准曲线",
                modelType = AnalysisModelType.STANDARD_CURVE.code,
                analyteId = "cea",
                detectionMode = detectionMode,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
                processorName = "ColorimetricProcessor",
                processorVersion = "1.0.0",
                compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                compatibleAcquisitionProfileIdsJson = "[\"device-v1\"]",
                concentrationUnit = "ng/mL",
                reliableRangeMin = 0.1,
                reliableRangeMax = 100.0,
                status = AnalysisModelLifecycleStatus.PUBLISHED.code
            )
        )
    }

    private class FakeTemplateRepository : ExperimentTemplateRepository {
        val bundles = linkedMapOf<String, ExperimentTemplateBundle>()
        private val templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())

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
        override suspend fun createDraft(bundle: ExperimentTemplateBundle): ExperimentTemplateBundle = unsupported()
        override suspend fun updateDraft(bundle: ExperimentTemplateBundle) = unsupported()
        override suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle = unsupported()
        override suspend fun publish(id: String) = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeCarrierRepository : CarrierProfileRepository {
        val profiles = linkedMapOf<String, CarrierProfile>()
        override fun observeAll(): Flow<List<CarrierProfile>> = MutableStateFlow(profiles.values.toList())
        override suspend fun getById(id: String): CarrierProfile? = profiles[id]
        override suspend fun create(profile: CarrierProfile) = unsupported()
        override suspend fun createNextVersion(previousId: String, replacement: CarrierProfile): CarrierProfile = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeAcquisitionRepository : AcquisitionProfileRepository {
        val profiles = linkedMapOf<String, AcquisitionProfile>()
        override fun observeAll(): Flow<List<AcquisitionProfile>> = MutableStateFlow(profiles.values.toList())
        override suspend fun getById(id: String): AcquisitionProfile? = profiles[id]
        override suspend fun create(profile: AcquisitionProfile) = unsupported()
        override suspend fun createNextVersion(previousId: String, replacement: AcquisitionProfile): AcquisitionProfile = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeAnalysisModelRepository : AnalysisModelRepository {
        val bundles = linkedMapOf<String, AnalysisModelBundle>()
        override fun observeAll(): Flow<List<AnalysisModel>> =
            MutableStateFlow(bundles.values.map(AnalysisModelBundle::model))
        override suspend fun getBundle(id: String): AnalysisModelBundle? = bundles[id]
        override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle = unsupported()
        override suspend fun updateDraft(bundle: AnalysisModelBundle) = unsupported()
        override suspend fun replace(bundle: AnalysisModelBundle) = unsupported()
        override suspend fun delete(id: String) = unsupported()
        override suspend fun createNextDraft(previousId: String): AnalysisModelBundle = unsupported()
        override suspend fun publish(id: String) = unsupported()
        override suspend fun archive(id: String) = unsupported()
    }

    private class FakeAnalyteRepository : AnalyteRepository {
        val analytes = linkedMapOf<String, Analyte>()
        override fun getAllAnalytes(): Flow<List<Analyte>> = MutableStateFlow(analytes.values.toList())
        override suspend fun getAnalyteById(id: String): Analyte? = analytes[id]
        override suspend fun getAnalyteByName(name: String): Analyte? = analytes.values.find { it.name == name }
        override suspend fun addAnalyte(name: String): Boolean = unsupported()
        override suspend fun updateAnalyte(id: String, name: String): Boolean = unsupported()
        override suspend fun deleteAnalyte(analyte: Analyte) = unsupported()
        override suspend fun deleteAnalyte(id: String) = unsupported()
    }

    private class FakeProjectRepository : ProjectRepository {
        var savedProject: Project? = null
        var savedJoins: List<ProjectAnalyteJoin> = emptyList()

        override suspend fun getAllProjects(): List<Project> = emptyList()
        override suspend fun getProjectById(projectId: String): Project? = savedProject
        override suspend fun createProject(project: Project) {
            savedProject = project
        }
        override suspend fun createProjectWithAnalytes(
            project: Project,
            joins: List<ProjectAnalyteJoin>
        ) {
            savedProject = project
            savedJoins = joins
        }
        override suspend fun updateProject(project: Project) = unsupported()
        override suspend fun deleteProject(projectId: String) = unsupported()
        override suspend fun updateSpectrumConfig(
            projectId: String,
            lightSource: String?,
            spectrumColumnCount: Int,
            spectrumColumnMappingJson: String?
        ) = unsupported()
    }

    private companion object {
        fun unsupported(): Nothing = throw UnsupportedOperationException("该 Fake 只支持读取")
    }
}
