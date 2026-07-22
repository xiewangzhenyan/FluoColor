package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiError
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState

/**
 * 微流控检测专用页面内容。
 *
 * 页面只展示普通实验人员需要的阶段、重拍和结果入口；极性、阈值、单应参数和模型
 * 兼容细节留在管理员配置及结果诊断中，避免便携检测流程被工程参数淹没。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridDetectionGatewayContent(
    state: GridDetectionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onViewResults: (String) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.microfluidic_detection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                GridDetectionUiState.ResolvingProject -> ProcessingCard(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                GridDetectionUiState.LegacyPlate -> ProcessingCard(
                    title = stringResource(R.string.grid_stage_preparing)
                )

                is GridDetectionUiState.Processing -> ProcessingCard(
                    title = stageText(state.stage)
                )

                is GridDetectionUiState.Completed -> CompletedCard(
                    state = state,
                    onBack = onBack,
                    onViewResults = onViewResults
                )

                is GridDetectionUiState.RetakeRequired -> MessageCard(
                    title = stringResource(R.string.grid_detection_retake_title),
                    message = stringResource(R.string.grid_detection_retake_message),
                    isError = true,
                    primaryLabel = stringResource(R.string.retry),
                    onPrimary = onRetry,
                    onBack = onBack
                )

                is GridDetectionUiState.Blocked -> BlockedCard(state.reasons, onBack)

                is GridDetectionUiState.Error -> MessageCard(
                    title = stringResource(R.string.grid_detection_error_title),
                    message = errorText(state.reason),
                    isError = true,
                    primaryLabel = stringResource(R.string.retry),
                    onPrimary = onRetry,
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun ProcessingCard(title: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.grid_detection_processing_description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompletedCard(
    state: GridDetectionUiState.Completed,
    onBack: () -> Unit,
    onViewResults: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.grid_detection_completed_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.grid_detection_completed_message,
                    state.measurementCount
                ),
                textAlign = TextAlign.Center
            )
            if (state.signalOnlyAnalyteIds.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.grid_detection_signal_only_warning),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            if (state.frameQcIssueCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        R.string.grid_detection_quality_review_warning,
                        state.frameQcIssueCount
                    ),
                    color = MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.go_back))
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onViewResults(state.runId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.GridView, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.view_grid_results))
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    isError: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.go_back))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
private fun BlockedCard(reasons: Set<GridDetectionBlockReason>, onBack: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.grid_detection_blocked_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            reasons.forEach { reason ->
                Text(text = "• ${blockReasonText(reason)}")
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.go_back))
            }
        }
    }
}

@Composable
private fun stageText(stage: GridDetectionStage): String = when (stage) {
    GridDetectionStage.PREPARING -> stringResource(R.string.grid_stage_preparing)
    GridDetectionStage.LOCATING -> stringResource(R.string.grid_stage_locating)
    GridDetectionStage.PHOTOMETRY -> stringResource(R.string.grid_stage_photometry)
    GridDetectionStage.RENDERING_EVIDENCE -> stringResource(R.string.grid_stage_rendering_evidence)
    GridDetectionStage.CHECKING_MODELS -> stringResource(R.string.grid_stage_checking_models)
    GridDetectionStage.PERSISTING -> stringResource(R.string.grid_stage_persisting)
    GridDetectionStage.COMPLETED -> stringResource(R.string.grid_stage_completed)
}

@Composable
private fun blockReasonText(reason: GridDetectionBlockReason): String = when (reason) {
    GridDetectionBlockReason.PROJECT_SNAPSHOT_MISMATCH -> stringResource(R.string.grid_block_snapshot_mismatch)
    GridDetectionBlockReason.UNSUPPORTED_CARRIER -> stringResource(R.string.grid_block_unsupported_carrier)
    GridDetectionBlockReason.INVALID_TEMPLATE_PROTOCOL -> stringResource(R.string.grid_block_invalid_protocol)
    GridDetectionBlockReason.MISSING_LOCATOR_CONFIG -> stringResource(R.string.grid_block_missing_locator)
    GridDetectionBlockReason.INVALID_LOCATOR_CONFIG -> stringResource(R.string.grid_block_invalid_locator)
    GridDetectionBlockReason.MISSING_ANALYTE_ASSIGNMENT -> stringResource(R.string.grid_block_missing_assignment)
    GridDetectionBlockReason.MISSING_COLORIMETRIC_REFERENCE -> stringResource(R.string.grid_block_missing_reference)
    GridDetectionBlockReason.MISSING_FLUORESCENCE_CHANNEL -> stringResource(R.string.grid_block_missing_fluorescence_channel)
    GridDetectionBlockReason.INVALID_PRIMARY_FEATURE -> stringResource(R.string.grid_block_invalid_feature)
    // 以下原因都表示冻结快照的科学结构已损坏，必须向用户明确指出具体配置问题，
    // 不能合并成笼统的“快照无效”，否则用户无法回到模板配置中进行针对性修复。
    GridDetectionBlockReason.EMPTY_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_empty_analyte_snapshot)
    GridDetectionBlockReason.DUPLICATE_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_duplicate_analyte_snapshot)
    GridDetectionBlockReason.INCONSISTENT_ANALYTE_SNAPSHOT -> stringResource(R.string.grid_block_inconsistent_analyte_snapshot)
    GridDetectionBlockReason.INVALID_SITE_COORDINATE -> stringResource(R.string.grid_block_invalid_site_coordinate)
    GridDetectionBlockReason.DUPLICATE_ENABLED_SITE -> stringResource(R.string.grid_block_duplicate_enabled_site)
    GridDetectionBlockReason.ORPHAN_SITE_ANALYTE -> stringResource(R.string.grid_block_orphan_site_analyte)
}

@Composable
private fun errorText(error: GridDetectionUiError): String = when (error) {
    GridDetectionUiError.INVALID_ARGUMENTS -> stringResource(R.string.grid_error_invalid_arguments)
    GridDetectionUiError.PROJECT_NOT_FOUND -> stringResource(R.string.grid_error_project_not_found)
    GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID -> stringResource(R.string.grid_error_snapshot_invalid)
    GridDetectionUiError.IMAGE_LOAD_FAILED -> stringResource(R.string.grid_error_image_load_failed)
    GridDetectionUiError.EXECUTION_FAILED -> stringResource(R.string.grid_error_execution_failed)
}
