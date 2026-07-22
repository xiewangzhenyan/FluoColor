package com.muc.fluocolorquant.ui.screens.project

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.ResolvedTemplateProjectConfiguration
import com.muc.fluocolorquant.domain.project.TemplatePreflightCode
import com.muc.fluocolorquant.domain.project.TemplatePreflightIssue
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.ProjectUiState
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模板优先新建项目页面的关键交互契约测试。 */
class NewProjectScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalFlowStartsWithTemplateAndHidesManualScientificControls() {
        composeRule.setContent {
            FluoColorTheme {
                TemplateProjectContent(
                    state = readyState(),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithTag(NewProjectTestTags.TEMPLATE_SELECTOR).assertIsDisplayed()
        composeRule.onNodeWithTag(NewProjectTestTags.PROJECT_NAME_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(NewProjectTestTags.TEMPLATE_SUMMARY).assertIsDisplayed()
        composeRule.onAllNodesWithTag("manual_detection_mode").assertCountEquals(0)
        composeRule.onAllNodesWithTag("manual_rows_columns").assertCountEquals(0)
    }

    @Test
    fun preflightFailureDisablesCreateAndShowsIssueList() {
        val blocked = readyState().copy(
            form = readyState().form.copy(
                templateReady = false,
                preflightIssues = listOf(
                    TemplatePreflightIssue(TemplatePreflightCode.MODEL_MODALITY_MISMATCH)
                )
            )
        )
        composeRule.setContent {
            FluoColorTheme {
                TemplateProjectContent(state = blocked, onAction = {})
            }
        }

        composeRule.onNodeWithTag(NewProjectTestTags.PREFLIGHT_ISSUES)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(NewProjectTestTags.CREATE_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun defaultViewShowsGroupedSampleSummaryInsteadOfEverySiteEditor() {
        composeRule.setContent {
            FluoColorTheme {
                TemplateProjectContent(state = readyState(), onAction = {})
            }
        }

        composeRule.onNodeWithTag(NewProjectTestTags.SAMPLE_GROUP_SUMMARY)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag(NewProjectTestTags.SAMPLE_SITE_EDITOR).assertCountEquals(0)
    }

    @Test
    fun quickFlowOnlyShowsRunInputsAndPublishedProtocolSummary() {
        composeRule.setContent {
            FluoColorTheme {
                QuickCreateProjectContent(
                    state = readyState(),
                    onNavigateBack = {},
                    onSelectTemplate = {},
                    onCopyTemplate = {},
                    onCreateCustomTemplate = {},
                    onProjectNameChange = {},
                    onWholeChipSampleChange = {},
                    onProjectBatchChange = {},
                    onSampleBatchChange = {},
                    onChooseImage = {},
                    onRemoveImage = {},
                    onCreateProject = {}
                )
            }
        }

        composeRule.onNodeWithTag(QuickCreateProjectTestTags.TEMPLATE_SELECTOR)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(QuickCreateProjectTestTags.TEMPLATE_SUMMARY)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(QuickCreateProjectTestTags.PROJECT_NAME)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(QuickCreateProjectTestTags.WHOLE_CHIP_SAMPLE)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("quick_detection_mode_editor").assertCountEquals(0)
        composeRule.onAllNodesWithTag("quick_grid_layout_editor").assertCountEquals(0)
    }

    @Test
    fun copyAndAdjustPassesPublishedTemplateIdToVersionedFlow() {
        var copiedTemplateId: String? = null
        composeRule.setContent {
            FluoColorTheme {
                QuickCreateProjectContent(
                    state = readyState(),
                    onNavigateBack = {},
                    onSelectTemplate = {},
                    onCopyTemplate = { copiedTemplateId = it },
                    onCreateCustomTemplate = {},
                    onProjectNameChange = {},
                    onWholeChipSampleChange = {},
                    onProjectBatchChange = {},
                    onSampleBatchChange = {},
                    onChooseImage = {},
                    onRemoveImage = {},
                    onCreateProject = {}
                )
            }
        }

        composeRule.onNodeWithTag(QuickCreateProjectTestTags.COPY_TEMPLATE).performClick()

        assertEquals("template-v1", copiedTemplateId)
    }

    private fun readyState(): ProjectUiState {
        val template = ExperimentTemplate(
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
            name = "实验室固定比色装置",
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
        val snapshot = TemplateProjectSnapshot(
            frozenAtEpochMillis = 1L,
            template = template,
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(analyte, config, AnalysisModelBundle(model))
            ),
            siteAssignments = emptyList()
        )
        return ProjectUiState(
            publishedTemplates = listOf(template),
            form = TemplateProjectFormState(
                projectName = "CEA 芯片项目",
                selectedTemplateId = template.id,
                imageUri = "content://chip/1",
                templateReady = true,
                requiredSampleSites = listOf(
                    TemplateSampleSiteField("R01C01", "CEA", "sample-001"),
                    TemplateSampleSiteField("R01C02", "CEA", "sample-001")
                ),
                sampleSlotMapping = mapOf(
                    "R01C01" to "sample-001",
                    "R01C02" to "sample-001"
                )
            ),
            resolvedConfiguration = ResolvedTemplateProjectConfiguration(
                snapshot = snapshot,
                destination = ProjectDetectionDestination.GRID_ENDPOINT
            ),
            isLoadingTemplates = false
        )
    }
}
