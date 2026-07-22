package com.muc.fluocolorquant.ui.viewmodels

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
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.ResolvedTemplateProjectConfiguration
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreateRequest
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateResolution
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

/** 模板优先新建项目页面状态机测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var coordinator: FakeTemplateProjectCoordinator
    private lateinit var projectRepository: FakeProjectRepository
    private lateinit var viewModel: ProjectViewModel

    @Before
    fun setUp() {
        coordinator = FakeTemplateProjectCoordinator()
        projectRepository = FakeProjectRepository()
        viewModel = ProjectViewModel(
            projectRepository = projectRepository,
            templateProjectCoordinator = coordinator
        )
    }

    @Test
    fun `加载已发布模板后仍保持未选择状态`() = runTest(mainDispatcherRule.testDispatcher) {
        coordinator.templates.value = listOf(template())
        advanceUntilIdle()

        assertEquals(listOf("template-v1"), viewModel.uiState.value.publishedTemplates.map { it.id })
        assertNull(viewModel.uiState.value.form.selectedTemplateId)
        assertFalse(viewModel.uiState.value.form.templateReady)
    }

    @Test
    fun `选择模板后自动带入只读配置和默认样本映射`() = runTest(mainDispatcherRule.testDispatcher) {
        val snapshot = snapshot()
        coordinator.templates.value = listOf(snapshot.template)
        coordinator.resolutions[snapshot.template.id] = TemplateResolution.Ready(
            ResolvedTemplateProjectConfiguration(
                snapshot = snapshot,
                destination = ProjectDetectionDestination.GRID_ENDPOINT
            )
        )

        viewModel.selectTemplate(snapshot.template.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(snapshot.template.id, state.form.selectedTemplateId)
        assertTrue(state.form.templateReady)
        assertEquals("sample-001", state.form.sampleSlotMapping["R01C01"])
        assertEquals("sample-001", state.form.sampleSlotMapping["R01C02"])
        assertEquals(2, state.form.requiredSampleSites.size)
        assertEquals("10×10 微流控芯片", state.resolvedConfiguration?.snapshot?.carrierProfile?.name)
    }

    @Test
    fun `整芯片样本编号会覆盖全部样本位但不会写入空白位`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val snapshot = snapshot()
            coordinator.resolutions[snapshot.template.id] = TemplateResolution.Ready(
                ResolvedTemplateProjectConfiguration(
                    snapshot = snapshot,
                    destination = ProjectDetectionDestination.GRID_ENDPOINT
                )
            )
            viewModel.selectTemplate(snapshot.template.id)
            advanceUntilIdle()

            val sampleKeys = viewModel.uiState.value.form.requiredSampleSites.map { it.siteKey }
            viewModel.applySampleSlotToSites(sampleKeys, "patient-2026-01")

            val mapping = viewModel.uiState.value.form.sampleSlotMapping
            assertEquals("patient-2026-01", mapping["R01C01"])
            assertEquals("patient-2026-01", mapping["R01C02"])
            assertFalse("R01C03" in mapping)
        }

    @Test
    fun `填写最少运行信息后创建成功事件携带下一检测目的地`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val snapshot = snapshot()
            coordinator.resolutions[snapshot.template.id] = TemplateResolution.Ready(
                ResolvedTemplateProjectConfiguration(snapshot, ProjectDetectionDestination.GRID_ENDPOINT)
            )
            viewModel.selectTemplate(snapshot.template.id)
            advanceUntilIdle()
            viewModel.updateProjectName("CEA 芯片批次 01")
            viewModel.updateProjectBatch("P-01")
            viewModel.updateSampleBatch("S-01")
            viewModel.updateImageUri("content://chip/1")

            val createdProject = project(snapshot.template)
            coordinator.creationOutcome = TemplateProjectCreationOutcome.Created(
                project = createdProject,
                destination = ProjectDetectionDestination.GRID_ENDPOINT
            )
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.createProject(userId = "1")
            advanceUntilIdle()

            assertEquals("P-01", coordinator.lastRequest?.projectBatch)
            assertEquals("S-01", coordinator.lastRequest?.sampleBatch)
            assertEquals(
                ProjectEvent.Created(
                    projectId = createdProject.id,
                    imageUri = createdProject.imageUri,
                    destination = ProjectDetectionDestination.GRID_ENDPOINT
                ),
                event.await()
            )
        }

    private fun snapshot(): TemplateProjectSnapshot {
        val template = template()
        val carrier = CarrierProfile(
            id = "carrier-10x10",
            name = "10×10 微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.CIRCLE.code,
            status = ResourceStatus.ACTIVE.code
        )
        val acquisition = AcquisitionProfile(
            id = "device-v1",
            name = "固定比色装置",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "FIXED_PROFILE",
            status = ResourceStatus.ACTIVE.code
        )
        val analyte = Analyte("cea", "CEA")
        val config = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = template.id,
            analyteId = analyte.id,
            analysisModelId = "model-cea",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0
        )
        val model = AnalysisModel(
            id = "model-cea",
            name = "CEA ΔE 标准曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.COLORIMETRIC.code,
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
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(analyte, config, AnalysisModelBundle(model))
            ),
            siteAssignments = listOf(
                TemplateSiteAssignment(
                    id = "site-1",
                    templateId = template.id,
                    rowIndex = 0,
                    columnIndex = 0,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = "sample-001"
                ),
                TemplateSiteAssignment(
                    id = "site-2",
                    templateId = template.id,
                    rowIndex = 0,
                    columnIndex = 1,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = "sample-001"
                ),
                TemplateSiteAssignment(
                    id = "site-3",
                    templateId = template.id,
                    rowIndex = 0,
                    columnIndex = 2,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.BLANK.code
                )
            )
        )
    }

    private fun template() = ExperimentTemplate(
        id = "template-v1",
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
        status = TemplateLifecycleStatus.PUBLISHED.code,
        carrierProfileId = "carrier-10x10",
        detectionMode = DetectionModality.COLORIMETRIC.code,
        readoutLayout = ReadoutLayout.GRID_SITES.code,
        acquisitionProfileId = "device-v1",
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code
    )

    private fun project(template: ExperimentTemplate) = Project(
        id = "project-1",
        name = "CEA 芯片批次 01",
        detectionMode = DetectionModality.COLORIMETRIC.code,
        recognitionType = "AUTO",
        imageUri = "content://chip/1",
        rows = 10,
        columns = 10,
        createTime = Date(1L),
        userId = "1",
        lastRunTimestamp = null,
        analysisMethod = "CURVE_FIT",
        templateId = template.id,
        templateVersion = template.version,
        templateSnapshotJson = "{}",
        overrideJson = "{}"
    )

    private class FakeTemplateProjectCoordinator : TemplateProjectCoordinator {
        val templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
        val resolutions = mutableMapOf<String, TemplateResolution>()
        var creationOutcome: TemplateProjectCreationOutcome? = null
        var lastRequest: TemplateProjectCreateRequest? = null

        override fun observePublishedTemplates(): Flow<List<ExperimentTemplate>> = templates
        override suspend fun resolveTemplate(templateId: String): TemplateResolution =
            resolutions.getValue(templateId)
        override suspend fun createProject(
            request: TemplateProjectCreateRequest
        ): TemplateProjectCreationOutcome {
            lastRequest = request
            return requireNotNull(creationOutcome)
        }
    }

    private class FakeProjectRepository : ProjectRepository {
        override suspend fun getAllProjects(): List<Project> = emptyList()
        override suspend fun getProjectById(projectId: String): Project? = null
        override suspend fun createProject(project: Project) = Unit
        override suspend fun createProjectWithAnalytes(
            project: Project,
            joins: List<ProjectAnalyteJoin>
        ) = Unit
        override suspend fun updateProject(project: Project) = Unit
        override suspend fun deleteProject(projectId: String) = Unit
        override suspend fun updateSpectrumConfig(
            projectId: String,
            lightSource: String?,
            spectrumColumnCount: Int,
            spectrumColumnMappingJson: String?
        ) = Unit
    }
}
