package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
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
                    onViewResults = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_stage_locating)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_detection_processing_description)).assertIsDisplayed()
    }

    @Test
    fun `完成状态显示位点数量和查看结果入口`() {
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
                    onViewResults = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_detection_completed_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_detection_completed_message, 100)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_detection_quality_review_warning, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.view_grid_results)).assertIsDisplayed()
    }

    private fun string(id: Int, vararg arguments: Any): String {
        return ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(id, *arguments)
    }
}
