package com.muc.fluocolorquant.ui.screens.settings.template

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateWizardUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 五步模板向导的稳定导航与基础表单验收测试。 */
class ExperimentTemplateWizardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 新建模板首先展示五步进度和基本信息表单() {
        var nextClicked = false

        composeRule.setContent {
            MaterialTheme {
                ExperimentTemplateWizardContent(
                    state = ExperimentTemplateWizardUiState(),
                    batchDraft = TemplateSiteDraft(),
                    rectangleSelectionEnabled = false,
                    actions = ExperimentTemplateWizardActions(
                        onNextStep = { nextClicked = true }
                    )
                )
            }
        }

        composeRule.onNodeWithTag(ExperimentTemplateWizardTestTags.STEP_PROGRESS)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ExperimentTemplateWizardTestTags.BASIC_FORM)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ExperimentTemplateWizardTestTags.NEXT_BUTTON)
            .performClick()

        composeRule.runOnIdle { assertTrue(nextClicked) }
    }

    @Test
    fun 荧光分析物卡片展示读出通道选择() {
        composeRule.setContent {
            MaterialTheme {
                ExperimentTemplateWizardContent(
                    state = ExperimentTemplateWizardUiState(
                        currentStep = TemplateWizardStep.ANALYTES,
                        draft = TemplateWizardDraft(
                            detectionMode = DetectionModality.FLUORESCENCE.code,
                            analytes = listOf(TemplateAnalyteDraft(analyteId = "cea"))
                        ),
                        analytes = listOf(Analyte(id = "cea", name = "CEA"))
                    ),
                    batchDraft = TemplateSiteDraft(),
                    rectangleSelectionEnabled = false,
                    actions = ExperimentTemplateWizardActions()
                )
            }
        }

        composeRule.onNodeWithTag(
            ExperimentTemplateWizardTestTags.FLUORESCENCE_CHANNEL_FIELD
        ).assertExists()
    }
}
