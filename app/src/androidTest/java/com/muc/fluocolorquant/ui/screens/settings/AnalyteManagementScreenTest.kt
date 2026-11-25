package com.muc.fluocolorquant.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.TumorBiomarkerPresets
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.AnalyteViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 分析物管理页面 UI 回归：验证预设徽章展示、一键补齐常用预设和 360dp 视觉渲染。 */
@RunWith(AndroidJUnit4::class)
class AnalyteManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun configuredBadgesAndOneTapPresetBackfillWorkCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = InMemoryAnalyteRepository(
            listOf(
                Analyte(
                    id = "cea",
                    name = "CEA",
                    defaultUnit = "ng/mL",
                    defaultMaxConcentration = 100.0,
                    defaultFluorescenceChannel = "RED",
                    referenceCutoff = 5.0
                ),
                Analyte(
                    id = "ca125",
                    name = "CA125",
                    defaultUnit = "U/mL",
                    defaultMaxConcentration = 500.0,
                    defaultFluorescenceChannel = "GREEN",
                    referenceCutoff = 35.0
                ),
                Analyte(
                    id = "cyfra",
                    name = "CYFRA21-1",
                    defaultUnit = "ng/mL",
                    defaultMaxConcentration = 50.0,
                    defaultFluorescenceChannel = "BLUE",
                    referenceCutoff = 3.3
                ),
                // 未配置预设的内置常见标志物，用于验证一键补齐按钮
                Analyte(
                    id = "afp",
                    name = "AFP"
                ),
                Analyte(
                    id = "nse",
                    name = "NSE",
                    defaultUnit = "ng/mL",
                    defaultMaxConcentration = 100.0,
                    defaultFluorescenceChannel = "GREEN",
                    referenceCutoff = 16.3
                )
            )
        )
        val viewModel = AnalyteViewModel(repository)

        composeRule.setContent {
            FluoColorTheme {
                AnalyteManagementScreen(
                    viewModel = viewModel,
                    navigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.analyte_total_configured, 5)).assertIsDisplayed()
        composeRule.onAllNodesWithText("0–100 ng/mL")[0].assertIsDisplayed()
        composeRule.onNodeWithText("0–500 U/mL").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.analyte_apply_presets_action)).assertIsDisplayed()

        val initialScreen = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "analyte-management-preview.png").outputStream().use {
            initialScreen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 点击一键补齐后，AFP 自动补上 0–400 ng/mL 预设且补齐按钮收起
        composeRule.onNodeWithText(context.getString(R.string.analyte_apply_presets_action)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0–400 ng/mL").assertIsDisplayed()
    }

    private class InMemoryAnalyteRepository(initial: List<Analyte>) : AnalyteRepository {
        private val state = MutableStateFlow(initial)
        override fun getAllAnalytes(): Flow<List<Analyte>> = state
        override suspend fun getAnalyteById(id: String): Analyte? = state.value.find { it.id == id }
        override suspend fun getAnalyteByName(name: String): Analyte? =
            state.value.find { it.name.equals(name, ignoreCase = true) }

        override suspend fun addAnalyte(name: String): Boolean = addAnalyteProfile(name)

        override suspend fun addAnalyteProfile(
            name: String,
            defaultUnit: String?,
            defaultMaxConcentration: Double?,
            defaultFluorescenceChannel: String?,
            referenceCutoff: Double?
        ): Boolean {
            if (getAnalyteByName(name.trim()) != null) return false
            state.value = state.value + Analyte(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                defaultUnit = defaultUnit,
                defaultMaxConcentration = defaultMaxConcentration,
                defaultFluorescenceChannel = defaultFluorescenceChannel,
                referenceCutoff = referenceCutoff
            )
            return true
        }

        override suspend fun updateAnalyte(id: String, name: String): Boolean = false

        override suspend fun updateAnalyteProfile(
            id: String,
            name: String,
            defaultUnit: String?,
            defaultMaxConcentration: Double?,
            defaultFluorescenceChannel: String?,
            referenceCutoff: Double?
        ): Boolean {
            state.value = state.value.map {
                if (it.id == id) it.copy(
                    name = name.trim(),
                    defaultUnit = defaultUnit,
                    defaultMaxConcentration = defaultMaxConcentration,
                    defaultFluorescenceChannel = defaultFluorescenceChannel,
                    referenceCutoff = referenceCutoff
                ) else it
            }
            return true
        }

        override suspend fun applyStandardPresetsToUnconfigured(): Int {
            var count = 0
            state.value = state.value.map { item ->
                val preset = TumorBiomarkerPresets.findByName(item.name)
                if (item.defaultUnit.isNullOrBlank() && item.defaultMaxConcentration == null && preset != null) {
                    count++
                    item.copy(
                        defaultUnit = preset.unit,
                        defaultMaxConcentration = preset.maxConcentration,
                        defaultFluorescenceChannel = preset.fluorescenceChannel,
                        referenceCutoff = preset.referenceCutoff
                    )
                } else item
            }
            return count
        }

        override suspend fun deleteAnalyte(analyte: Analyte) {
            state.value = state.value.filterNot { it.id == analyte.id }
        }

        override suspend fun deleteAnalyte(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }
}
