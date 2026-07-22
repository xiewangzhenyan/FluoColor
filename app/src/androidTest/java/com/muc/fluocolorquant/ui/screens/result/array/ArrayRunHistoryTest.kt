package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultUiState
import com.muc.fluocolorquant.ui.viewmodels.ArrayRunHistoryItem
import com.muc.fluocolorquant.ui.viewmodels.ArrayRunModelVersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 运行历史入口、当前运行标记和无覆盖切换行为的 Compose 测试。 */
@RunWith(AndroidJUnit4::class)
class ArrayRunHistoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `历史面板标记当前运行并把旧运行ID交给切换回调`() {
        var selectedRunId: String? = null
        val history = listOf(
            historyItem("run-new", 3_000L, "Completed", 3),
            historyItem("run-old", 1_000L, "SignalOnlyCompleted", 1)
        )
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(
                        snapshot = snapshot("run-new"),
                        history = history
                    ),
                    onBack = {},
                    onRetry = {},
                    onSelectRun = { selectedRunId = it }
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RUN_HISTORY_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(ARRAY_RUN_HISTORY_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_RUN_HISTORY_LIST_TAG).performScrollToIndex(1)
        composeRule.onNodeWithTag(
            ARRAY_RUN_HISTORY_CURRENT_BADGE_TAG,
            useUnmergedTree = true
        ).assertExists()
        composeRule.onNodeWithTag(ARRAY_RUN_HISTORY_LIST_TAG).performScrollToIndex(2)
        composeRule.onNodeWithTag("${ARRAY_RUN_HISTORY_ITEM_TAG_PREFIX}run-old")
            .performClick()

        composeRule.runOnIdle { assertEquals("run-old", selectedRunId) }
    }

    @Test
    fun `切换历史运行时保留当前结果并显示轻量进度`() {
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(
                        snapshot = snapshot("run-new"),
                        history = listOf(historyItem("run-old", 1_000L, "Completed", 1)),
                        switchingRunId = "run-old"
                    ),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RESULT_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_RUN_SWITCH_PROGRESS_TAG).assertIsDisplayed()
    }

    private fun historyItem(
        runId: String,
        timestamp: Long,
        status: String,
        modelVersion: Int
    ): ArrayRunHistoryItem {
        return ArrayRunHistoryItem(
            runId = runId,
            timestampEpochMillis = timestamp,
            status = status,
            measurementCount = 100,
            reliablePercent = 92.0,
            modelVersions = listOf(ArrayRunModelVersion("CEA", modelVersion)),
            reasonSummary = if (status == "SignalOnlyCompleted") "MODEL_NOT_PUBLISHED" else null
        )
    }

    private fun snapshot(runId: String): ArrayResultSnapshot {
        return ArrayResultSnapshot(
            runId = runId,
            projectId = "project-history",
            projectName = "History chip",
            runTimestampEpochMillis = 3_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier-history",
                name = "10×10 chip",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 10,
            columns = 10,
            analytes = emptyList(),
            sites = emptyList(),
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 100,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = 100,
                    outlierCount = 0,
                    meanConfidence = 0.95
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }
}
