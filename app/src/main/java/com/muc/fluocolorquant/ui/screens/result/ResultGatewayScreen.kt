package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.screens.result.array.ArrayResultScreen
import com.muc.fluocolorquant.ui.screens.result.plate96.Plate96ResultScreen
import com.muc.fluocolorquant.ui.viewmodels.ResultGatewayUiState
import com.muc.fluocolorquant.ui.viewmodels.ResultGatewayViewModel

const val RESULT_GATEWAY_LOADING_TAG: String = "result_gateway_loading"
const val RESULT_GATEWAY_ARRAY_TAG: String = "result_gateway_array"
const val RESULT_GATEWAY_PLATE96_TAG: String = "result_gateway_plate96"
const val RESULT_GATEWAY_LEGACY_TAG: String = "result_gateway_legacy"

/**
 * 新旧结果的唯一导航入口。
 *
 * 分流依据只能是运行是否存在 SiteMeasurement；10×10、15×15 等行列数不能证明载体
 * 类型，旧孔板也可能拥有相同行列，因此禁止用布局尺寸猜测页面。
 */
@Composable
fun ResultGatewayScreen(
    navController: NavController,
    runId: String?,
    viewModel: ResultGatewayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(runId) { viewModel.load(runId) }

    ResultGatewayContent(
        state = state,
        onRetry = viewModel::retry,
        arrayContent = {
            ArrayResultScreen(navController = navController, runId = runId)
        },
        plate96Content = {
            Plate96ResultScreen(navController = navController, runId = runId)
        },
        legacyContent = {
            NewResultScreen(navController = navController, runId = runId)
        }
    )
}

/** 独立内容函数使 Compose 测试无需启动 Hilt 和真实 Room。 */
@Composable
fun ResultGatewayContent(
    state: ResultGatewayUiState,
    onRetry: () -> Unit,
    arrayContent: @Composable () -> Unit,
    plate96Content: @Composable () -> Unit = arrayContent,
    legacyContent: @Composable () -> Unit
) {
    when (state) {
        ResultGatewayUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(RESULT_GATEWAY_LOADING_TAG),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.array_result_gateway_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        ResultGatewayUiState.NewArrayResult -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(RESULT_GATEWAY_ARRAY_TAG)
        ) { arrayContent() }
        ResultGatewayUiState.NewPlate96Result -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(RESULT_GATEWAY_PLATE96_TAG)
        ) { plate96Content() }
        ResultGatewayUiState.LegacyPlate96Result -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(RESULT_GATEWAY_PLATE96_TAG)
        ) { plate96Content() }
        ResultGatewayUiState.LegacyResult -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(RESULT_GATEWAY_LEGACY_TAG)
        ) { legacyContent() }
        ResultGatewayUiState.InvalidRun,
        ResultGatewayUiState.Error -> ResultGatewayError(
            invalidRun = state == ResultGatewayUiState.InvalidRun,
            onRetry = onRetry
        )
    }
}

@Composable
private fun ResultGatewayError(invalidRun: Boolean, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(
                    if (invalidRun) R.string.array_result_invalid_run
                    else R.string.array_result_gateway_error
                ),
                style = MaterialTheme.typography.bodyLarge
            )
            if (!invalidRun) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}
