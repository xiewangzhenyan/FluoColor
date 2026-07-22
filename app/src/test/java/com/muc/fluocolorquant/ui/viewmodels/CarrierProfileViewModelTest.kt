package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.ui.screens.settings.resources.CarrierPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 载体编辑器必须把用户看到的亮暗选择保存为检测协调器可读取的版本化配置。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarrierProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeCarrierProfileRepository
    private lateinit var viewModel: CarrierProfileViewModel

    @Before
    fun setUp() {
        repository = FakeCarrierProfileRepository()
        viewModel = CarrierProfileViewModel(repository)
    }

    @Test
    fun `十五乘十五快捷载体保存亮结构配置`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.openCreateEditor(CarrierPreset.MICROFLUIDIC_15_X_15, "15×15 亮点芯片")

        viewModel.save()
        advanceUntilIdle()

        val saved = repository.created.single()
        assertEquals(
            GridTargetPolarity.BRIGHT,
            ScientificDetectionConfigCodec.decodeCarrierPolarity(saved.locatorConfigJson)
        )
    }

    @Test
    fun `创建下一版本时恢复原有亮结构配置`() = runTest(mainDispatcherRule.testDispatcher) {
        val source = CarrierProfile(
            id = "carrier-v1",
            name = "10×10 亮点芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.SQUARE.code,
            locatorConfigJson = ScientificDetectionConfigCodec.encodeCarrierLocator(
                GridTargetPolarity.BRIGHT
            )
        )
        repository.profiles.value = listOf(source)

        viewModel.openNewVersionEditor(source)

        assertEquals(GridTargetPolarity.BRIGHT, viewModel.uiState.value.draft.targetPolarity)
    }
}

/** 保存真实实体的轻量仓库，避免只验证 Mock 调用次数而忽略最终 JSON 内容。 */
private class FakeCarrierProfileRepository : CarrierProfileRepository {
    val profiles = MutableStateFlow<List<CarrierProfile>>(emptyList())
    val created = mutableListOf<CarrierProfile>()

    override fun observeAll(): Flow<List<CarrierProfile>> = profiles

    override suspend fun getById(id: String): CarrierProfile? = profiles.value.find { it.id == id }

    override suspend fun create(profile: CarrierProfile) {
        created += profile
        profiles.value = profiles.value + profile
    }

    override suspend fun createNextVersion(
        previousId: String,
        replacement: CarrierProfile
    ): CarrierProfile {
        val next = replacement.copy(id = "${previousId}-next", version = 2)
        created += next
        profiles.value = profiles.value + next
        return next
    }

    override suspend fun archive(id: String) = Unit
}
