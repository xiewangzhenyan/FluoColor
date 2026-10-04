package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ProjectCreationPreferences
import com.muc.fluocolorquant.data.repository.ProjectCreationDefaults
import com.muc.fluocolorquant.data.repository.CustomGridDefaults
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.VisibleSettingProductionContracts
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.repository.SettingsValuePolicy
import com.muc.fluocolorquant.domain.project.DirectProjectCreationCoordinator
import com.muc.fluocolorquant.ui.screens.project.DirectAnalyteSelection
import com.muc.fluocolorquant.ui.screens.project.DirectProjectFormState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 新建页已取消模型匹配，本测试固定多分析物单位不会被全局字段覆盖。 */
class DirectProjectModelMatchingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `表单为每个分析物独立保存浓度单位`() {
        val state = DirectProjectUiState(
            concentrationUnits = listOf("ng/mL", "pg/mL"),
            form = DirectProjectFormState(
                selectedAnalytes = listOf(
                    DirectAnalyteSelection("cea", "ng/mL"),
                    DirectAnalyteSelection("afp", "pg/mL")
                )
            )
        )

        assertEquals("ng/mL", state.form.selectedAnalytes[0].concentrationUnit)
        assertEquals("pg/mL", state.form.selectedAnalytes[1].concentrationUnit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `检测方式和默认浓度单位存在真实的新建项目消费者`() =
        runTest(mainDispatcherRule.testDispatcher) {
        val preferences = FakeProjectCreationPreferences(
            detectionMode = DetectionModality.COLORIMETRIC.code,
            customRows = 7,
            customColumns = 11,
            lightSource = SpectrumLightSource.HALOGEN,
            defaultUnit = "pg/mL",
            units = setOf("ng/mL", "pg/mL")
        )
        val analyte = Analyte(id = "cea", name = "CEA")
        val viewModel = DirectProjectViewModel(
            analyteRepository = FakeAnalyteRepository(analyte),
            projectCreationPreferences = preferences,
            coordinator = DirectProjectCreationCoordinator(FakeProjectRepository())
        )

        advanceUntilIdle()

        assertEquals(DetectionModality.COLORIMETRIC, viewModel.uiState.value.form.detectionModality)
        assertEquals("7", viewModel.uiState.value.form.customRowsInput)
        assertEquals("11", viewModel.uiState.value.form.customColumnsInput)
        assertEquals(SpectrumLightSource.HALOGEN, viewModel.uiState.value.form.spectrumLightSource)
        assertEquals("pg/mL", viewModel.uiState.value.defaultConcentrationUnit)
        assertEquals("pg/mL", viewModel.uiState.value.form.selectedAnalytes.single().concentrationUnit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `新建默认值只预填一次且后续设置变化不会覆盖本页输入`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val preferences = FakeProjectCreationPreferences(
                detectionMode = DetectionModality.SPECTRUM.code,
                customRows = 8,
                customColumns = 9,
                lightSource = SpectrumLightSource.LED_WHITE,
                defaultUnit = "ng/mL",
                units = setOf("ng/mL")
            )
            val viewModel = DirectProjectViewModel(
                analyteRepository = FakeAnalyteRepository(Analyte(id = "cea", name = "CEA")),
                projectCreationPreferences = preferences,
                coordinator = DirectProjectCreationCoordinator(FakeProjectRepository())
            )
            advanceUntilIdle()

            viewModel.updateCustomRows("6")
            viewModel.updateCustomColumns("13")
            viewModel.updateSpectrumLightSource(SpectrumLightSource.MERCURY)
            preferences.projectCreationDefaultsFlow.value = ProjectCreationDefaults(
                detectionMode = DetectionModality.FLUORESCENCE.code,
                customGrid = CustomGridDefaults(rows = 20, columns = 21),
                spectrumLightSource = SpectrumLightSource.SUNLIGHT
            )
            advanceUntilIdle()

            assertEquals(DetectionModality.SPECTRUM, viewModel.uiState.value.form.detectionModality)
            assertEquals("6", viewModel.uiState.value.form.customRowsInput)
            assertEquals("13", viewModel.uiState.value.form.customColumnsInput)
            assertEquals(SpectrumLightSource.MERCURY, viewModel.uiState.value.form.spectrumLightSource)
        }

    @Test
    fun `所有可见设置都有生产消费者且空设置不再公开`() {
        val contracts = VisibleSettingProductionContracts.all
        val visibleIds = contracts.map { it.settingId }

        assertEquals(visibleIds.size, visibleIds.distinct().size)
        assertTrue(contracts.all { it.consumers.isNotEmpty() })
        setOf(
            "pixel_extraction_method",
            "image_preprocessing_enabled",
            "spectrum_default_track_count",
            "spectrum_max_track_count"
        ).forEach { removedSetting ->
            assertFalse(removedSetting in visibleIds)
        }
        assertTrue("default_rows" in visibleIds)
        assertTrue("default_columns" in visibleIds)
        assertTrue("spectrum_default_light_source" in visibleIds)
    }

    @Test
    fun `设置值策略拒绝非法检测方式并把旧光谱脏值恢复为稳定默认值`() {
        assertEquals("FLUORESCENCE", SettingsValuePolicy.detectionModeOrDefault("unknown"))
        assertEquals("COLORIMETRIC", SettingsValuePolicy.requireDetectionMode("colorimetric"))

        val normalized = SettingsValuePolicy.spectrumPreferences(
            minWavelength = 900f,
            maxWavelength = 400f,
            smoothingLevel = 99,
            sensitivity = "extreme"
        )

        assertEquals(SettingsRepository.DEFAULT_SPECTRUM_MIN_WAVELENGTH, normalized.minWavelength)
        assertEquals(SettingsRepository.DEFAULT_SPECTRUM_MAX_WAVELENGTH, normalized.maxWavelength)
        assertEquals(SettingsRepository.DEFAULT_SPECTRUM_SMOOTHING, normalized.smoothingLevel)
        assertEquals(SettingsRepository.DEFAULT_SPECTRUM_SENSITIVITY, normalized.sensitivity)

        val invalidGrid = SettingsValuePolicy.customGridOrDefault(rows = 12, columns = 0)
        assertEquals(SettingsRepository.DEFAULT_CUSTOM_GRID_ROWS, invalidGrid.rows)
        assertEquals(SettingsRepository.DEFAULT_CUSTOM_GRID_COLUMNS, invalidGrid.columns)
        assertEquals(
            SpectrumLightSource.LED_WHITE,
            SettingsValuePolicy.spectrumLightSourceOrDefault("unsupported")
        )
        assertEquals(
            SpectrumLightSource.HALOGEN,
            SettingsValuePolicy.spectrumLightSourceOrDefault("halogen")
        )
    }

    private class FakeProjectCreationPreferences(
        detectionMode: String,
        customRows: Int,
        customColumns: Int,
        lightSource: SpectrumLightSource,
        defaultUnit: String,
        units: Set<String>
    ) : ProjectCreationPreferences {
        override val projectCreationDefaultsFlow = MutableStateFlow(
            ProjectCreationDefaults(
                detectionMode = detectionMode,
                customGrid = CustomGridDefaults(customRows, customColumns),
                spectrumLightSource = lightSource
            )
        )
        override val defaultConcentrationUnitFlow = MutableStateFlow(defaultUnit)
        override val concentrationUnitsFlow = MutableStateFlow(units)
    }

    private class FakeAnalyteRepository(
        private val analyte: Analyte
    ) : AnalyteRepository {
        override fun getAllAnalytes(): Flow<List<Analyte>> = flowOf(listOf(analyte))
        override suspend fun getAnalyteById(id: String): Analyte? = analyte.takeIf { it.id == id }
        override suspend fun getAnalyteByName(name: String): Analyte? = analyte.takeIf { it.name == name }
        override suspend fun addAnalyte(name: String): Boolean = false
        override suspend fun updateAnalyte(id: String, name: String): Boolean = false
        override suspend fun deleteAnalyte(analyte: Analyte) = Unit
        override suspend fun deleteAnalyte(id: String) = Unit
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
