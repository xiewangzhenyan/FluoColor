package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelDraft
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelFormError
import com.muc.fluocolorquant.ui.screens.settings.analysis.AnalysisModelStatusFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 统一分析模型 ViewModel 的状态编排测试。
 *
 * Fake 仓库直接保存真实领域实体，避免测试只验证 Mock 调用次数；同时替换 Main 调度器，
 * 让 viewModelScope 中的 Flow 收集、保存和发布操作都能在单元测试中确定性完成。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisModelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var modelRepository: FakeAnalysisModelRepository
    private lateinit var analyteRepository: FakeAnalyteRepository
    private lateinit var acquisitionRepository: FakeAcquisitionProfileRepository
    private lateinit var viewModel: AnalysisModelViewModel

    @Before
    fun setUp() {
        modelRepository = FakeAnalysisModelRepository()
        analyteRepository = FakeAnalyteRepository()
        acquisitionRepository = FakeAcquisitionProfileRepository()
        viewModel = AnalysisModelViewModel(
            analysisModelRepository = modelRepository,
            analyteRepository = analyteRepository,
            acquisitionProfileRepository = acquisitionRepository
        )
    }

    @Test
    fun `模型类型和生命周期筛选会组合生效`() = runTest(mainDispatcherRule.testDispatcher) {
        val draftCurve = model(id = "curve-draft")
        val publishedCurve = model(
            id = "curve-published",
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        val publishedDeepModel = model(
            id = "deep-published",
            modelType = AnalysisModelType.DEEP_LEARNING.code,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        modelRepository.models.value = listOf(draftCurve, publishedCurve, publishedDeepModel)
        advanceUntilIdle()

        viewModel.selectType(AnalysisModelType.STANDARD_CURVE)
        viewModel.selectStatus(AnalysisModelStatusFilter.PUBLISHED)

        assertEquals(listOf(publishedCurve), viewModel.uiState.value.visibleModels)
    }

    @Test
    fun `保存不完整草稿时返回稳定错误码且不写入仓库`() =
        runTest(mainDispatcherRule.testDispatcher) {
        viewModel.openCreateEditor()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.saveDraft()
        advanceUntilIdle()

        assertTrue(
            AnalysisModelFormError.NAME_REQUIRED in
                (event.await() as AnalysisModelEvent.ValidationFailed).errors
        )
        assertTrue(modelRepository.createdBundles.isEmpty())
    }

    @Test
    fun `有效草稿保存为标准曲线数据包并关闭编辑器`() =
        runTest(mainDispatcherRule.testDispatcher) {
        analyteRepository.analytes.value = listOf(Analyte(id = "cea", name = "CEA"))
        viewModel.openCreateEditor()
        viewModel.updateDraft(completeDraft())
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.saveDraft()
        advanceUntilIdle()

        val saved = modelRepository.createdBundles.single()
        assertEquals("CEA 比色标准曲线", saved.model.name)
        assertEquals(AnalysisModelType.STANDARD_CURVE.code, saved.model.modelType)
        assertEquals("[\"MICROFLUIDIC_CHIP\"]", saved.model.compatibleCarrierTypesJson)
        assertEquals("[\"device-v1\"]", saved.model.compatibleAcquisitionProfileIdsJson)
        assertEquals("linear", saved.standardCurve?.fittingFunction)
        assertEquals(AnalysisModelEvent.DraftSaved, event.await())
        assertFalse(viewModel.uiState.value.isEditorVisible)
    }

    @Test
    fun `发布前校验会阻止缺少设备兼容范围的草稿`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val incomplete = bundleFromDraft(
            completeDraft().copy(compatibleAcquisitionProfileIds = emptySet()),
            id = "model-1"
        )
        modelRepository.bundles[incomplete.model.id] = incomplete
        modelRepository.models.value = listOf(incomplete.model)
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.publish(incomplete.model.id)
        advanceUntilIdle()

        val validation = event.await() as AnalysisModelEvent.ValidationFailed
        assertTrue(AnalysisModelFormError.ACQUISITION_PROFILE_REQUIRED in validation.errors)
        assertTrue(modelRepository.publishedIds.isEmpty())
    }

    @Test
    fun `发布资料完整时解析兼容范围并调用仓库发布`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val complete = bundleFromDraft(completeDraft(), id = "model-ready")
        modelRepository.bundles[complete.model.id] = complete
        modelRepository.models.value = listOf(complete.model)
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.publish(complete.model.id)
        advanceUntilIdle()

        assertEquals(listOf(complete.model.id), modelRepository.publishedIds)
        assertEquals(AnalysisModelEvent.Published, event.await())
    }

    @Test
    fun `创建下一版本后自动打开新草稿编辑器`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val source = bundleFromDraft(completeDraft(), id = "model-v1")
        modelRepository.bundles[source.model.id] = source
        modelRepository.models.value = listOf(source.model)
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

        viewModel.createNextVersion(source.model.id)
        advanceUntilIdle()

        assertEquals(listOf(source.model.id), modelRepository.nextVersionSourceIds)
        assertEquals(AnalysisModelEditorMode.EDIT_DRAFT, viewModel.uiState.value.editorMode)
        assertTrue(viewModel.uiState.value.isEditorVisible)
        assertEquals(AnalysisModelEvent.VersionCreated, event.await())
    }

    private fun completeDraft(): AnalysisModelDraft = AnalysisModelDraft(
        name = "CEA 比色标准曲线",
        analyteId = "cea",
        processorName = "ColorimetricProcessor",
        processorVersion = "2.0.0",
        compatibleCarrierTypes = setOf("MICROFLUIDIC_CHIP"),
        compatibleAcquisitionProfileIds = setOf("device-v1"),
        concentrationUnit = "ng/mL",
        reliableRangeMinInput = "0.1",
        reliableRangeMaxInput = "100",
        fittingFunction = "linear",
        parametersJson = "{\"a\":1.0,\"b\":0.0}"
    )

    private fun model(
        id: String,
        modelType: String = AnalysisModelType.STANDARD_CURVE.code,
        status: String = AnalysisModelLifecycleStatus.DRAFT.code
    ): AnalysisModel = AnalysisModel(
        id = id,
        name = id,
        modelType = modelType,
        analyteId = "cea",
        detectionMode = DetectionModality.COLORIMETRIC.code,
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
        primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
        processorName = "processor",
        processorVersion = "1.0",
        concentrationUnit = "ng/mL",
        reliableRangeMin = 0.1,
        reliableRangeMax = 100.0,
        status = status
    )

    private fun bundleFromDraft(draft: AnalysisModelDraft, id: String): AnalysisModelBundle {
        val range = requireNotNull(draft.reliableRangeOrNull())
        val model = AnalysisModel(
            id = id,
            name = draft.name,
            modelType = draft.modelType.code,
            analyteId = draft.analyteId,
            detectionMode = draft.detectionMode,
            inputProtocol = draft.inputProtocol,
            primaryFeature = draft.primaryFeature,
            processorName = draft.processorName,
            processorVersion = draft.processorVersion,
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = if (
                draft.compatibleAcquisitionProfileIds.isEmpty()
            ) "[]" else "[\"device-v1\"]",
            concentrationUnit = draft.concentrationUnit,
            reliableRangeMin = range.first,
            reliableRangeMax = range.second
        )
        return AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = id,
                fittingFunction = draft.fittingFunction,
                parametersJson = draft.parametersJson,
                monotonicDirection = draft.monotonicDirection
            )
        )
    }

    /** 保存真实数据包的分析模型 Fake 仓库。 */
    private class FakeAnalysisModelRepository : AnalysisModelRepository {
        val models = MutableStateFlow<List<AnalysisModel>>(emptyList())
        val bundles = linkedMapOf<String, AnalysisModelBundle>()
        val createdBundles = mutableListOf<AnalysisModelBundle>()
        val updatedBundles = mutableListOf<AnalysisModelBundle>()
        val nextVersionSourceIds = mutableListOf<String>()
        val publishedIds = mutableListOf<String>()
        val archivedIds = mutableListOf<String>()

        override fun observeAll(): Flow<List<AnalysisModel>> = models

        override suspend fun getBundle(id: String): AnalysisModelBundle? = bundles[id]

        override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle {
            createdBundles += bundle
            val saved = bundle.copy(model = bundle.model.copy(id = "created-${createdBundles.size}"))
            bundles[saved.model.id] = saved
            models.value = models.value + saved.model
            return saved
        }

        override suspend fun updateDraft(bundle: AnalysisModelBundle) {
            updatedBundles += bundle
            bundles[bundle.model.id] = bundle
            models.value = models.value.map { if (it.id == bundle.model.id) bundle.model else it }
        }

        override suspend fun createNextDraft(previousId: String): AnalysisModelBundle {
            nextVersionSourceIds += previousId
            val source = requireNotNull(bundles[previousId])
            val next = source.copy(
                model = source.model.copy(
                    id = "$previousId-v2",
                    status = AnalysisModelLifecycleStatus.DRAFT.code,
                    version = source.model.version + 1
                )
            )
            bundles[next.model.id] = next
            models.value = models.value + next.model
            return next
        }

        override suspend fun publish(id: String) {
            publishedIds += id
        }

        override suspend fun archive(id: String) {
            archivedIds += id
        }
    }

    /** 仅提供响应式列表的分析物 Fake 仓库。 */
    private class FakeAnalyteRepository : AnalyteRepository {
        val analytes = MutableStateFlow<List<Analyte>>(emptyList())
        override fun getAllAnalytes(): Flow<List<Analyte>> = analytes
        override suspend fun getAnalyteById(id: String): Analyte? = analytes.value.find { it.id == id }
        override suspend fun getAnalyteByName(name: String): Analyte? = analytes.value.find { it.name == name }
        override suspend fun addAnalyte(name: String): Boolean = false
        override suspend fun updateAnalyte(id: String, name: String): Boolean = false
        override suspend fun deleteAnalyte(analyte: Analyte) = Unit
        override suspend fun deleteAnalyte(id: String) = Unit
    }

    /** 仅提供响应式列表的采集设备 Fake 仓库。 */
    private class FakeAcquisitionProfileRepository : AcquisitionProfileRepository {
        val profiles = MutableStateFlow<List<AcquisitionProfile>>(emptyList())
        override fun observeAll(): Flow<List<AcquisitionProfile>> = profiles
        override suspend fun getById(id: String): AcquisitionProfile? = profiles.value.find { it.id == id }
        override suspend fun create(profile: AcquisitionProfile) = Unit
        override suspend fun createNextVersion(
            previousId: String,
            replacement: AcquisitionProfile
        ): AcquisitionProfile = replacement
        override suspend fun archive(id: String) = Unit
    }
}

/** 将 ViewModel 使用的 Main 调度器替换成测试调度器。 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
