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
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.data.repository.ConcentrationUnitPreferences
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateAnalyteDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateSiteCoordinate
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateSiteDraft
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateWizardError
import com.muc.fluocolorquant.ui.screens.settings.template.TemplateWizardStep
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 微流控模板向导 ViewModel 的资源联动和状态编排测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentTemplateWizardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var templateRepository: FakeTemplateRepository
    private lateinit var carrierRepository: FakeCarrierRepository
    private lateinit var acquisitionRepository: FakeAcquisitionRepository
    private lateinit var analyteRepository: FakeAnalyteRepository
    private lateinit var reagentRepository: FakeReagentRepository
    private lateinit var analysisModelRepository: FakeAnalysisModelRepository
    private lateinit var viewModel: ExperimentTemplateWizardViewModel

    @Before
    fun setUp() {
        templateRepository = FakeTemplateRepository()
        carrierRepository = FakeCarrierRepository()
        acquisitionRepository = FakeAcquisitionRepository()
        analyteRepository = FakeAnalyteRepository()
        reagentRepository = FakeReagentRepository()
        analysisModelRepository = FakeAnalysisModelRepository()

        carrierRepository.profiles.value = listOf(carrier10x10(), carrier15x15())
        acquisitionRepository.profiles.value = listOf(acquisitionProfile())
        analyteRepository.analytes.value = listOf(
            Analyte(id = "cea", name = "CEA"),
            Analyte(id = "nse", name = "NSE")
        )
        analysisModelRepository.models.value = listOf(
            compatibleModel(id = "model-cea", analyteId = "cea"),
            compatibleModel(id = "model-nse", analyteId = "nse"),
            compatibleModel(
                id = "model-archived",
                analyteId = "cea",
                status = AnalysisModelLifecycleStatus.ARCHIVED.code
            ),
            compatibleModel(
                id = "model-fluorescence",
                analyteId = "cea",
                detectionMode = DetectionModality.FLUORESCENCE.code,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code
            )
        )

        viewModel = ExperimentTemplateWizardViewModel(
            templateRepository = templateRepository,
            carrierProfileRepository = carrierRepository,
            acquisitionProfileRepository = acquisitionRepository,
            analyteRepository = analyteRepository,
            reagentRepository = reagentRepository,
            analysisModelRepository = analysisModelRepository,
            concentrationUnitPreferences = FakeConcentrationUnitPreferences()
        )
    }

    @Test
    fun `选择10乘10载体后阵列几何来自载体档案`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()

        viewModel.selectCarrier("carrier-10x10")

        assertEquals(10, viewModel.uiState.value.draft.layout.rows)
        assertEquals(10, viewModel.uiState.value.draft.layout.columns)
        assertEquals("carrier-10x10", viewModel.uiState.value.draft.carrierProfileId)
    }

    @Test
    fun `检测模态变化会切换合法协议并清除已选分析模型`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        viewModel.addAnalyte("cea")
        viewModel.updateAnalyte(
            "cea",
            viewModel.uiState.value.draft.analytes.single().copy(
                analysisModelId = "model-cea"
            )
        )

        viewModel.selectDetectionMode(DetectionModality.FLUORESCENCE)

        assertEquals(
            InputProtocol.ENDPOINT_ONLY.code,
            viewModel.uiState.value.draft.inputProtocol
        )
        assertEquals(
            ReadoutLayout.GRID_SITES.code,
            viewModel.uiState.value.draft.readoutLayout
        )
        assertTrue(viewModel.uiState.value.draft.analytes.single().analysisModelId == null)
    }

    @Test
    fun `复制并调整只创建一次下一版本并进入新草稿`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val source = ExperimentTemplate(
                id = "template-v1",
                templateName = "CEA 芯片方案",
                analyteId = null,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = 0.0,
                reliableRangeMax = 0.0,
                concentrationUnit = "",
                defaultLayoutJson = null,
                version = 1,
                status = com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus.PUBLISHED.code,
                carrierProfileId = "carrier-10x10",
                detectionMode = DetectionModality.COLORIMETRIC.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = "device-v1",
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            )
            val next = ExperimentTemplateBundle(
                template = source.copy(
                    id = "template-v2",
                    version = 2,
                    status = com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus.DRAFT.code
                ),
                analyteConfigs = emptyList(),
                siteAssignments = emptyList()
            )
            templateRepository.nextDraft = next
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.createNextVersion(source.id)
            advanceUntilIdle()
            // 模拟配置变更后页面的 LaunchedEffect 使用同一来源再次触发。
            viewModel.createNextVersion(source.id)
            advanceUntilIdle()

            assertEquals(listOf(source.id), templateRepository.nextDraftRequests)
            assertEquals("template-v2", viewModel.uiState.value.editingTemplateId)
            assertEquals(source.id, viewModel.uiState.value.versionSourceTemplateId)
            assertEquals(
                ExperimentTemplateWizardEvent.VersionCreated("template-v2"),
                event.await()
            )
        }

    @Test
    fun `切换到光谱模态会采用光谱轨道读出而不是伪装成孔位阵列`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()

        viewModel.selectDetectionMode(DetectionModality.SPECTRUM)

        assertEquals(
            InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
            viewModel.uiState.value.draft.inputProtocol
        )
        assertEquals(
            ReadoutLayout.SPECTRAL_TRACKS.code,
            viewModel.uiState.value.draft.readoutLayout
        )
    }

    @Test
    fun `兼容模型只包含已发布且匹配分析物模态载体和设备的模型`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        viewModel.selectCarrier("carrier-10x10")
        viewModel.selectAcquisitionProfile("device-v1")
        viewModel.addAnalyte("cea")

        val compatibleIds = viewModel.uiState.value.compatibleModelsFor("cea").map { it.id }

        assertEquals(listOf("model-cea"), compatibleIds)
    }

    @Test
    fun `同一模板可以添加多个分析物且选择模型后同步可靠范围`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        viewModel.selectCarrier("carrier-10x10")
        viewModel.selectAcquisitionProfile("device-v1")

        viewModel.addAnalyte("cea")
        viewModel.addAnalyte("nse")
        viewModel.selectAnalysisModel("cea", "model-cea")

        assertEquals(listOf("cea", "nse"), viewModel.uiState.value.draft.analytes.map { it.analyteId })
        val cea = viewModel.uiState.value.draft.analytes.first { it.analyteId == "cea" }
        assertEquals("ng/mL", cea.concentrationUnit)
        assertEquals("0.1", cea.reliableRangeMinInput)
        assertEquals("100", cea.reliableRangeMaxInput)
    }

    @Test
    fun `荧光分析物保存用户选择的红色定量通道`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        viewModel.updateDraft(
            viewModel.uiState.value.draft.copy(
                templateName = "CEA 红色荧光模板",
                detectionMode = DetectionModality.FLUORESCENCE.code,
                analytes = listOf(
                    TemplateAnalyteDraft(
                        analyteId = "cea",
                        concentrationUnit = "ng/mL",
                        reliableRangeMinInput = "0.1",
                        reliableRangeMaxInput = "100",
                        fluorescenceChannel = FluorescenceChannel.RED
                    )
                )
            )
        )

        viewModel.saveDraft()
        advanceUntilIdle()

        val configJson = templateRepository.created.single().analyteConfigs.single().displayConfigJson
        assertEquals(
            FluorescenceChannel.RED,
            ScientificDetectionConfigCodec.decodeFluorescenceChannel(configJson)
        )
    }

    @Test
    fun `加载荧光模板时恢复已保存的蓝色定量通道`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val template = ExperimentTemplate(
            id = "fluorescence-template",
            templateName = "CEA 蓝色荧光模板",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 0.0,
            concentrationUnit = "",
            defaultLayoutJson = null,
            detectionMode = DetectionModality.FLUORESCENCE.code
        )
        templateRepository.bundles[template.id] = ExperimentTemplateBundle(
            template = template,
            analyteConfigs = listOf(
                TemplateAnalyteConfig(
                    templateId = template.id,
                    analyteId = "cea",
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.1,
                    reliableRangeMax = 100.0,
                    displayConfigJson = ScientificDetectionConfigCodec.encodeFluorescenceDisplay(
                        FluorescenceChannel.BLUE
                    )
                )
            ),
            siteAssignments = emptyList()
        )
        advanceUntilIdle()

        viewModel.loadTemplate(template.id)
        advanceUntilIdle()

        assertEquals(
            FluorescenceChannel.BLUE,
            viewModel.uiState.value.draft.analytes.single().fluorescenceChannel
        )
    }

    @Test
    fun `整行选择可批量分配为指定分析物样本位`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        viewModel.selectCarrier("carrier-10x10")

        viewModel.selectLayoutRow(0)
        viewModel.applyToSelectedSites(
            TemplateSiteDraft(analyteId = "cea", role = TemplateSiteRole.SAMPLE)
        )

        val assignments = viewModel.uiState.value.draft.layout.assignments
        assertEquals(10, assignments.size)
        assertTrue(assignments.values.all { it.analyteId == "cea" })
    }

    @Test
    fun `基本信息不完整时下一步发送稳定校验错误`() =
        runTest(mainDispatcherRule.testDispatcher) {
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.nextStep()
        advanceUntilIdle()

        val validation = event.await() as ExperimentTemplateWizardEvent.ValidationFailed
        assertTrue(TemplateWizardError.NAME_REQUIRED in validation.errors)
        assertEquals(TemplateWizardStep.BASIC, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `加载旧单分析物模板时保留分析物试剂单位和可靠范围`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val legacyTemplate = ExperimentTemplate(
            id = "legacy-template",
            templateName = "旧版 CEA 模板",
            analyteId = "cea",
            reagentAntigenId = "antigen-cea",
            reagentAntibodyId = "antibody-cea",
            fkCurveModelId = "legacy-curve",
            reliableRangeMin = 0.5,
            reliableRangeMax = 80.0,
            concentrationUnit = "ng/mL",
            defaultLayoutJson = null
        )
        templateRepository.bundles[legacyTemplate.id] = ExperimentTemplateBundle(
            template = legacyTemplate,
            analyteConfigs = emptyList(),
            siteAssignments = emptyList()
        )
        advanceUntilIdle()

        viewModel.loadTemplate(legacyTemplate.id)
        advanceUntilIdle()

        val analyte = viewModel.uiState.value.draft.analytes.single()
        assertEquals("cea", analyte.analyteId)
        assertEquals("antigen-cea", analyte.reagentAntigenId)
        assertEquals("antibody-cea", analyte.reagentAntibodyId)
        assertEquals("ng/mL", analyte.concentrationUnit)
        assertEquals("0.5", analyte.reliableRangeMinInput)
        assertEquals("80", analyte.reliableRangeMaxInput)
        assertTrue(analyte.analysisModelId == null)
    }

    @Test
    fun `已发布模板可以归档但不会物理删除科研历史`() =
        runTest(mainDispatcherRule.testDispatcher) {
        viewModel.archiveTemplate("published-template")
        advanceUntilIdle()

        assertEquals(listOf("published-template"), templateRepository.archivedIds)
    }

    private fun carrier10x10() = CarrierProfile(
        id = "carrier-10x10",
        name = "10×10 微流控芯片",
        carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
        rows = 10,
        columns = 10,
        siteShape = SiteShape.SQUARE.code,
        status = ResourceStatus.ACTIVE.code
    )

    private fun carrier15x15() = carrier10x10().copy(
        id = "carrier-15x15",
        name = "15×15 微流控芯片",
        rows = 15,
        columns = 15
    )

    private fun acquisitionProfile() = AcquisitionProfile(
        id = "device-v1",
        name = "实验室固定采集装置",
        supportedModesJson = "[\"COLORIMETRIC\",\"FLUORESCENCE\"]",
        compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
        cameraControlStrategy = "AUTO_AND_LOCK",
        status = ResourceStatus.ACTIVE.code
    )

    private fun compatibleModel(
        id: String,
        analyteId: String,
        status: String = AnalysisModelLifecycleStatus.PUBLISHED.code,
        detectionMode: String = DetectionModality.COLORIMETRIC.code,
        primaryFeature: String = AnalysisPrimaryFeature.DELTA_E_2000.code
    ) = AnalysisModel(
        id = id,
        name = id,
        modelType = AnalysisModelType.STANDARD_CURVE.code,
        analyteId = analyteId,
        detectionMode = detectionMode,
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
        primaryFeature = primaryFeature,
        processorName = "processor",
        processorVersion = "1.0",
        compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
        compatibleAcquisitionProfileIdsJson = "[\"device-v1\"]",
        concentrationUnit = "ng/mL",
        reliableRangeMin = 0.1,
        reliableRangeMax = 100.0,
        status = status
    )

    private class FakeTemplateRepository : ExperimentTemplateRepository {
        val templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
        val bundles = mutableMapOf<String, ExperimentTemplateBundle>()
        val created = mutableListOf<ExperimentTemplateBundle>()
        val archivedIds = mutableListOf<String>()
        var nextDraft: ExperimentTemplateBundle? = null
        val nextDraftRequests = mutableListOf<String>()
        override fun getAllTemplates(): Flow<List<ExperimentTemplate>> = templates
        override suspend fun getTemplateById(id: String) = templates.value.find { it.id == id }
        override suspend fun getTemplatesByAnalyteId(analyteId: String) =
            templates.value.filter { it.analyteId == analyteId }
        override suspend fun saveTemplate(template: ExperimentTemplate) = Unit
        override suspend fun updateTemplate(template: ExperimentTemplate) = Unit
        override suspend fun deleteTemplate(template: ExperimentTemplate) = Unit
        override suspend fun deleteTemplateById(id: String) = Unit
        override suspend fun getBundle(id: String): ExperimentTemplateBundle? = bundles[id]
        override suspend fun createDraft(bundle: ExperimentTemplateBundle): ExperimentTemplateBundle {
            created += bundle
            return bundle
        }
        override suspend fun updateDraft(bundle: ExperimentTemplateBundle) = Unit
        override suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle {
            nextDraftRequests += previousId
            return requireNotNull(nextDraft)
        }
        override suspend fun publish(id: String) = Unit
        override suspend fun archive(id: String) {
            archivedIds += id
        }
    }

    private class FakeCarrierRepository : CarrierProfileRepository {
        val profiles = MutableStateFlow<List<CarrierProfile>>(emptyList())
        override fun observeAll(): Flow<List<CarrierProfile>> = profiles
        override suspend fun getById(id: String) = profiles.value.find { it.id == id }
        override suspend fun create(profile: CarrierProfile) = Unit
        override suspend fun createNextVersion(previousId: String, replacement: CarrierProfile) = replacement
        override suspend fun archive(id: String) = Unit
    }

    private class FakeAcquisitionRepository : AcquisitionProfileRepository {
        val profiles = MutableStateFlow<List<AcquisitionProfile>>(emptyList())
        override fun observeAll(): Flow<List<AcquisitionProfile>> = profiles
        override suspend fun getById(id: String) = profiles.value.find { it.id == id }
        override suspend fun create(profile: AcquisitionProfile) = Unit
        override suspend fun createNextVersion(
            previousId: String,
            replacement: AcquisitionProfile
        ) = replacement
        override suspend fun archive(id: String) = Unit
    }

    private class FakeAnalyteRepository : AnalyteRepository {
        val analytes = MutableStateFlow<List<Analyte>>(emptyList())
        override fun getAllAnalytes(): Flow<List<Analyte>> = analytes
        override suspend fun getAnalyteById(id: String) = analytes.value.find { it.id == id }
        override suspend fun getAnalyteByName(name: String) = analytes.value.find { it.name == name }
        override suspend fun addAnalyte(name: String) = false
        override suspend fun updateAnalyte(id: String, name: String) = false
        override suspend fun deleteAnalyte(analyte: Analyte) = Unit
        override suspend fun deleteAnalyte(id: String) = Unit
    }

    private class FakeReagentRepository : ReagentRepository {
        val reagents = MutableStateFlow<List<Reagent>>(emptyList())
        override fun getAllReagents(): Flow<List<Reagent>> = reagents
        override fun getReagentsByAnalyteId(analyteId: String): Flow<List<Reagent>> =
            MutableStateFlow(reagents.value.filter { it.analyteId == analyteId })
        override suspend fun getReagentById(id: String) = reagents.value.find { it.id == id }
        override suspend fun addReagent(
            analyteId: String,
            reagentName: String,
            reagentType: String,
            manufacturer: String?,
            molecularWeight: Double?,
            unit: String?
        ) = false
        override suspend fun updateReagent(
            id: String,
            analyteId: String,
            reagentName: String,
            reagentType: String,
            manufacturer: String?,
            molecularWeight: Double?,
            unit: String?
        ) = false
        override suspend fun deleteReagent(reagent: Reagent) = Unit
    }

    private class FakeAnalysisModelRepository : AnalysisModelRepository {
        val models = MutableStateFlow<List<AnalysisModel>>(emptyList())
        override fun observeAll(): Flow<List<AnalysisModel>> = models
        override suspend fun getBundle(id: String): AnalysisModelBundle? = null
        override suspend fun createDraft(bundle: AnalysisModelBundle) = bundle
        override suspend fun updateDraft(bundle: AnalysisModelBundle) = Unit
        override suspend fun replace(bundle: AnalysisModelBundle) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun createNextDraft(previousId: String): AnalysisModelBundle =
            error("本测试未使用")
        override suspend fun publish(id: String) = Unit
        override suspend fun archive(id: String) = Unit
    }

    /** 使用和检测设置一致的默认单位，验证向导下拉列表不依赖 Android DataStore。 */
    private class FakeConcentrationUnitPreferences : ConcentrationUnitPreferences {
        override val defaultConcentrationUnitFlow = MutableStateFlow("ng/mL")
        override val concentrationUnitsFlow = MutableStateFlow(setOf("ng/mL", "μg/mL"))
    }
}
