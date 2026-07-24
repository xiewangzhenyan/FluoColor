package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 微流控检测网关 UI 测试，确保阶段进度和完成结果不再复用旧孔位文案。 */
@RunWith(AndroidJUnit4::class)
class GridDetectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `定位阶段显示微流控专用进度`() {
        composeRule.setContent {
            MaterialTheme {
                GridDetectionGatewayContent(
                    state = GridDetectionUiState.Processing(GridDetectionStage.LOCATING),
                    onBack = {},
                    onRetry = {},
                    onOpenLayout = {},
                    onReviewLocalization = {},
                    onAssignmentsChange = {},
                    onPaintAssignments = { GridPaintMergeResult(emptyMap(), 0) },
                    onFinalizeLayout = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_stage_locating)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_detection_processing_description)).assertIsDisplayed()
    }

    @Test
    fun `完成状态直接显示正在打开结果而不是中转卡`() {
        composeRule.setContent {
            MaterialTheme {
                GridDetectionGatewayContent(
                    state = GridDetectionUiState.Completed(
                        runId = "run-1",
                        measurementCount = 100,
                        signalOnlyAnalyteIds = emptySet(),
                        frameQcIssueCount = 2
                    ),
                    onBack = {},
                    onRetry = {},
                    onOpenLayout = {},
                    onReviewLocalization = {},
                    onAssignmentsChange = {},
                    onPaintAssignments = { GridPaintMergeResult(emptyMap(), 0) },
                    onFinalizeLayout = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_detection_opening_results)).assertIsDisplayed()
    }

    @Test
    fun `孔位角色一级工具只显示高频角色且更多菜单保留科研角色`() {
        composeRule.setContent {
            MaterialTheme {
                GridRolePalette(
                    selectedRole = TemplateSiteRole.SAMPLE,
                    clearMode = false,
                    onRoleSelected = {},
                    onClearSelected = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.template_array_role_sample)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_standard)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_blank)).assertIsDisplayed()
        // 一级工具栏使用短标签以保证英文和360dp窄屏不截断；长文案只用于说明区域。
        composeRule.onNodeWithText(string(R.string.grid_layout_clear_short)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.template_array_role_negative_control))
            .assertCountEquals(0)

        composeRule.onNodeWithText(string(R.string.grid_layout_more_short)).performClick()
        composeRule.onNodeWithText(string(R.string.template_array_role_negative_control))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_positive_control))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_reference))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_disabled))
            .assertIsDisplayed()
    }

    @Test
    fun `孔位布局底部同时提供模板入口和四种逐分析物定量方式`() {
        composeRule.setContent {
            MaterialTheme {
                // 单独为长配置区提供滚动容器，模拟它在真实孔位布局页中的纵向浏览行为。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    GridExperimentConfigurationSection(
                        preview = quantitationPreview(),
                        assignments = sampleAssignments(),
                        onUseManualConfiguration = {},
                        onApplyTemplate = {},
                        onSetQuantitationMode = { _, _ -> },
                        onSelectAnalysisModel = { _, _ -> },
                        onUpdateOnsiteAdvanced = { _, _, _ -> },
                        onPreviewOnsiteFit = {},
                        onSaveTemplate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_configuration_use_template))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_configuration_manual))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_onsite))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_curve))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_deep_learning))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_signal_only))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_save_as_experiment_template))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `实验模板入口展示兼容模板并把选择结果回传`() {
        var selectedTemplateId: String? = null
        composeRule.setContent {
            MaterialTheme {
                GridExperimentConfigurationSection(
                    preview = quantitationPreview(),
                    assignments = sampleAssignments(),
                    onUseManualConfiguration = {},
                    onApplyTemplate = { selectedTemplateId = it },
                    onSetQuantitationMode = { _, _ -> },
                    onSelectAnalysisModel = { _, _ -> },
                    onUpdateOnsiteAdvanced = { _, _, _ -> },
                    onPreviewOnsiteFit = {},
                    onSaveTemplate = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_configuration_use_template)).performClick()
        composeRule.onNodeWithText(string(R.string.grid_template_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText("15×15 双分析物模板").performClick()
        composeRule.runOnIdle {
            assertEquals("template-15", selectedTemplateId)
        }
    }

    @Test
    fun `现场标定逐标准孔显示固定单位且不再要求画笔预填浓度`() {
        val standards = (0 until 3).associateWith { column ->
            GridLayoutAssignmentDraft(
                rowIndex = 0,
                columnIndex = column,
                analyteId = "cea",
                role = TemplateSiteRole.STANDARD,
                standardConcentration = null
            )
        }
        composeRule.setContent {
            MaterialTheme {
                GridExperimentConfigurationSection(
                    preview = quantitationPreview(),
                    assignments = standards,
                    onUseManualConfiguration = {},
                    onApplyTemplate = {},
                    onSetQuantitationMode = { _, _ -> },
                    onSelectAnalysisModel = { _, _ -> },
                    onUpdateOnsiteAdvanced = { _, _, _ -> },
                    onPreviewOnsiteFit = {},
                    onSaveTemplate = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_quant_enter_standards)).performClick()
        composeRule.onNodeWithText("A1").assertIsDisplayed()
        composeRule.onNodeWithText("A2").assertIsDisplayed()
        composeRule.onNodeWithText("A3").assertIsDisplayed()
        // 单位是输入框右侧的独立固定列，空值且未聚焦时仍必须全部可见。
        composeRule.onAllNodesWithText("ng/mL").assertCountEquals(3)
    }

    /** 构造与真实流程一致的手动配置摘要，验证模板与定量工具不是二选一模型。 */
    private fun quantitationPreview(): GridLocalizationPreview {
        return GridLocalizationPreview(
            runId = "run-layout",
            originalImageUri = "content://test/real-chip.jpg",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            rows = 15,
            columns = 15,
            siteCount = 225,
            observedRatio = 1.0,
            meanConfidence = 0.95,
            frameQcIssueCount = 0,
            rectifiedImagePath = null,
            cropHalfSizePx = 14.0,
            sites = emptyList(),
            analytes = listOf(
                GridLocalizationAnalyte(
                    id = "cea",
                    name = "CEA",
                    concentrationUnit = "ng/mL",
                    maxConcentration = 100.0
                )
            ),
            evidence = emptyList(),
            configurationSource = GridLayoutConfigurationSource.MANUAL,
            availableTemplates = listOf(
                GridExperimentTemplateOption(
                    id = "template-15",
                    name = "15×15 双分析物模板",
                    version = 1,
                    analyteIds = setOf("cea")
                )
            ),
            quantitationDrafts = listOf(
                GridAnalyteQuantitationDraft(
                    analyteId = "cea",
                    mode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT,
                    selectedFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR,
                    // 已确认后才展示“保存为实验模板”，与真实页面门控保持一致。
                    appliedSnapshot = AnalyteQuantitationSnapshot(
                        analyteId = "cea",
                        method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
                        concentrationUnit = "ng/mL",
                        processorVersion = "test",
                        inputFingerprint = "test"
                    )
                )
            )
        )
    }

    /** 至少保留一个有效孔位，使“保存为实验模板”入口处于真实可用状态。 */
    private fun sampleAssignments(): Map<Int, GridLayoutAssignmentDraft> {
        return mapOf(
            0 to GridLayoutAssignmentDraft(
                rowIndex = 0,
                columnIndex = 0,
                analyteId = "cea",
                role = TemplateSiteRole.SAMPLE,
                sampleId = "S-01"
            )
        )
    }

    private fun string(id: Int, vararg arguments: Any): String {
        return ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(id, *arguments)
    }
}
