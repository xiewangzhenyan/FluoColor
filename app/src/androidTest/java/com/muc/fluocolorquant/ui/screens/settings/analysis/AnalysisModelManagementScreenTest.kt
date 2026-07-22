package com.muc.fluocolorquant.ui.screens.settings.analysis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelUiState
import com.muc.fluocolorquant.ui.viewmodels.AnalysisModelEditorMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 分析模型库的关键交互验收测试。
 *
 * 测试只锁定科研工作流不可缺失的语义节点，不依赖具体颜色和像素位置，因此页面可以
 * 在保持行为连续的前提下继续优化视觉细节。
 */
class AnalysisModelManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 页面展示总览模型卡并可触发新建动作() {
        var createClicked = false
        var legacyClicked = false
        val model = AnalysisModel(
            id = "model-1",
            name = "CEA 微流控比色模型",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = "cea",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
            processorName = "ColorimetricProcessor",
            processorVersion = "2.0.0",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        val state = AnalysisModelUiState(
            models = listOf(model),
            analytes = listOf(Analyte(id = "cea", name = "CEA"))
        )

        composeRule.setContent {
            MaterialTheme {
                AnalysisModelManagementContent(
                    state = state,
                    onNavigateBack = {},
                    onOpenLegacyLibrary = { legacyClicked = true },
                    onSelectType = {},
                    onSelectStatus = {},
                    onCreate = { createClicked = true },
                    onEditDraft = {},
                    onCreateNextVersion = {},
                    onPublish = {},
                    onArchive = {},
                    onDraftChange = {},
                    onDismissEditor = {},
                    onSaveDraft = {}
                )
            }
        }

        composeRule.onNodeWithTag(AnalysisModelTestTags.SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithTag(AnalysisModelTestTags.LEGACY_BUTTON)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("CEA 微流控比色模型").assertIsDisplayed()
        composeRule.onNodeWithTag(AnalysisModelTestTags.CREATE_BUTTON).performClick()

        assertTrue(createClicked)
        assertTrue(legacyClicked)
    }

    @Test
    fun 新建表单未选择分析物时首项不会产生伪选中状态() {
        val state = AnalysisModelUiState(
            analytes = listOf(
                Analyte(id = "ca125", name = "CA125"),
                Analyte(id = "cea", name = "CEA")
            ),
            isEditorVisible = true,
            editorMode = AnalysisModelEditorMode.CREATE,
            draft = AnalysisModelDraft(analyteId = "")
        )

        composeRule.setContent {
            MaterialTheme {
                AnalysisModelManagementContent(
                    state = state,
                    onNavigateBack = {},
                    onOpenLegacyLibrary = {},
                    onSelectType = {},
                    onSelectStatus = {},
                    onCreate = {},
                    onEditDraft = {},
                    onCreateNextVersion = {},
                    onPublish = {},
                    onArchive = {},
                    onDraftChange = {},
                    onDismissEditor = {},
                    onSaveDraft = {}
                )
            }
        }

        composeRule.onNode(hasText("CA125") and hasClickAction()).assertIsNotSelected()
    }
}
