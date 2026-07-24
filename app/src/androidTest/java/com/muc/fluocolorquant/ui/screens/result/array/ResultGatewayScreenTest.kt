package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.ui.screens.result.RESULT_GATEWAY_ARRAY_TAG
import com.muc.fluocolorquant.ui.screens.result.RESULT_GATEWAY_LEGACY_TAG
import com.muc.fluocolorquant.ui.screens.result.RESULT_GATEWAY_LOADING_TAG
import com.muc.fluocolorquant.ui.screens.result.RESULT_GATEWAY_PLATE96_TAG
import com.muc.fluocolorquant.ui.screens.result.ResultGatewayContent
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.ResultGatewayUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 新旧结果分流只验证分支，不启动旧结果页庞大的真实依赖。 */
@RunWith(AndroidJUnit4::class)
class ResultGatewayScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `新运行只渲染阵列结果分支`() {
        composeRule.setContent {
            FluoColorTheme {
                ResultGatewayContent(
                    state = ResultGatewayUiState.NewArrayResult,
                    onRetry = {},
                    arrayContent = { Text("array-test-content") },
                    legacyContent = { Text("legacy-test-content") }
                )
            }
        }

        composeRule.onNodeWithTag(RESULT_GATEWAY_ARRAY_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_LEGACY_TAG).assertCountEquals(0)
    }

    @Test
    fun `旧运行只渲染旧结果兼容分支`() {
        composeRule.setContent {
            FluoColorTheme {
                ResultGatewayContent(
                    state = ResultGatewayUiState.LegacyResult,
                    onRetry = {},
                    arrayContent = { Text("array-test-content") },
                    legacyContent = { Text("legacy-test-content") }
                )
            }
        }

        composeRule.onNodeWithTag(RESULT_GATEWAY_LEGACY_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_ARRAY_TAG).assertCountEquals(0)
    }

    @Test
    fun `新96孔板运行只渲染独立孔板结果分支`() {
        composeRule.setContent {
            FluoColorTheme {
                ResultGatewayContent(
                    state = ResultGatewayUiState.NewPlate96Result,
                    onRetry = {},
                    arrayContent = { Text("array-test-content") },
                    plate96Content = { Text("plate96-test-content") },
                    legacyContent = { Text("legacy-test-content") }
                )
            }
        }

        composeRule.onNodeWithTag(RESULT_GATEWAY_PLATE96_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_ARRAY_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_LEGACY_TAG).assertCountEquals(0)
    }

    @Test
    fun `加载状态不会提前创建任一结果页面`() {
        composeRule.setContent {
            FluoColorTheme {
                ResultGatewayContent(
                    state = ResultGatewayUiState.Loading,
                    onRetry = {},
                    arrayContent = { Text("array-test-content") },
                    legacyContent = { Text("legacy-test-content") }
                )
            }
        }

        composeRule.onNodeWithTag(RESULT_GATEWAY_LOADING_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_ARRAY_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RESULT_GATEWAY_LEGACY_TAG).assertCountEquals(0)
    }
}
