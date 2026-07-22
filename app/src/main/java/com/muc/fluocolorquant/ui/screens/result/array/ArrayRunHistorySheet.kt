package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.viewmodels.ArrayRunHistoryItem
import java.text.DateFormat
import java.util.Date
import java.util.Locale

const val ARRAY_RUN_HISTORY_SHEET_TAG: String = "array_run_history_sheet"
const val ARRAY_RUN_HISTORY_LIST_TAG: String = "array_run_history_list"
const val ARRAY_RUN_HISTORY_ITEM_TAG_PREFIX: String = "array_run_history_item_"
const val ARRAY_RUN_HISTORY_CURRENT_BADGE_TAG: String = "array_run_history_current_badge"
const val ARRAY_RUN_HISTORY_SWITCHING_TAG: String = "array_run_history_switching"
const val ARRAY_RUN_HISTORY_READ_ERROR_TAG: String = "array_run_history_read_error"

/**
 * 同一项目的运行历史只展示 DetectionRun 冻结摘要。
 *
 * 点击历史记录只通知 ViewModel 切换读取目标快照；该组件本身不修改项目、模板或运行记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrayRunHistorySheet(
    history: List<ArrayRunHistoryItem>,
    currentRunId: String,
    switchingRunId: String?,
    historyReadFailed: Boolean,
    onSelectRun: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ARRAY_RUN_HISTORY_SHEET_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag(ARRAY_RUN_HISTORY_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RunHistoryHeader(history.size)
            }
            if (historyReadFailed) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ARRAY_RUN_HISTORY_READ_ERROR_TAG),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.array_run_history_read_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.array_run_history_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(history, key = ArrayRunHistoryItem::runId) { run ->
                    RunHistoryCard(
                        run = run,
                        isCurrent = run.runId == currentRunId,
                        isSwitching = run.runId == switchingRunId,
                        selectionEnabled = switchingRunId == null,
                        onClick = { onSelectRun(run.runId) }
                    )
                }
            }
            item {
                // 为系统底部手势区留出空间，避免最后一条运行记录被遮挡。
                Spacer(modifier = Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun RunHistoryHeader(runCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.array_run_history_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.array_run_history_subtitle, runCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单条历史卡片把状态、定量覆盖率、模型版本和降级原因放在同一追溯上下文中。 */
@Composable
private fun RunHistoryCard(
    run: ArrayRunHistoryItem,
    isCurrent: Boolean,
    isSwitching: Boolean,
    selectionEnabled: Boolean,
    onClick: () -> Unit
) {
    val statusStyle = runStatusStyle(run.status)
    val modelLabels = mutableListOf<String>()
    for (model in run.modelVersions) {
        modelLabels += stringResource(
            R.string.array_run_history_model_version_value,
            model.analyteName,
            model.version
        )
    }
    val reasonText = localizedRunReason(run.reasonSummary)
    Card(
        onClick = onClick,
        enabled = selectionEnabled && !isCurrent,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_RUN_HISTORY_ITEM_TAG_PREFIX${run.runId}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            disabledContainerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = if (isCurrent) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = formatRunTimestamp(run.timestampEpochMillis),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.array_run_history_run_id, run.runId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (isSwitching) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag(ARRAY_RUN_HISTORY_SWITCHING_TAG),
                        strokeWidth = 2.5.dp
                    )
                } else if (isCurrent) {
                    Surface(
                        modifier = Modifier.testTag(ARRAY_RUN_HISTORY_CURRENT_BADGE_TAG),
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.array_run_history_current),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = statusStyle.container, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        text = statusStyle.label,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusStyle.content,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = stringResource(
                        R.string.array_run_history_measurement_count,
                        run.measurementCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = run.reliablePercent?.let {
                        stringResource(R.string.array_run_history_reliable_rate, it)
                    } ?: stringResource(R.string.array_run_history_reliable_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HistoryDetailRow(
                label = stringResource(R.string.array_run_history_models),
                value = modelLabels.takeIf(List<String>::isNotEmpty)?.joinToString()
                    ?: stringResource(R.string.array_run_history_models_missing)
            )
            if (reasonText != null) {
                HistoryDetailRow(
                    label = stringResource(R.string.array_run_history_reason),
                    value = reasonText,
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun HistoryDetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.28f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.72f),
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class RunStatusStyle(
    val label: String,
    val container: Color,
    val content: Color
)

@Composable
private fun runStatusStyle(status: String): RunStatusStyle {
    return when (status) {
        "Completed" -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = Color(0xFFDDF3E5),
            content = Color(0xFF146C3A)
        )
        "SignalOnlyCompleted" -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = Color(0xFFFFEAC2),
            content = Color(0xFF7A4D00)
        )
        "RetakeRequired" -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer
        )
        "Failed" -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer
        )
        "Processing" -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> RunStatusStyle(
            label = arrayRunStatusLabel(status),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 统一把数据库稳定状态码映射为用户语言，结果头部和运行历史不得直接显示机器枚举值。 */
@Composable
internal fun arrayRunStatusLabel(status: String): String = when (status) {
    "Completed" -> stringResource(R.string.array_run_status_completed)
    "SignalOnlyCompleted" -> stringResource(R.string.array_run_status_signal_only)
    "RetakeRequired" -> stringResource(R.string.array_run_status_retake)
    "Failed" -> stringResource(R.string.array_run_status_failed)
    "Processing" -> stringResource(R.string.array_run_status_processing)
    else -> stringResource(R.string.array_run_status_unknown)
}

/** 把稳定机器原因转换为用户能执行的简短说明；未知历史文本原样保留以避免丢证据。 */
@Composable
private fun localizedRunReason(summary: String?): String? {
    if (summary.isNullOrBlank()) return null
    val localized = mutableListOf<String>()
    for (reason in summary.split(',').map(String::trim).filter(String::isNotEmpty)) {
        localized += when (reason) {
            "RETAKE_REQUIRED" -> stringResource(R.string.array_run_reason_retake)
            "SIGNAL_ONLY" -> stringResource(R.string.array_run_reason_signal_only)
            "MODEL_NOT_PUBLISHED" -> stringResource(R.string.array_run_reason_model_not_published)
            "ANALYTE_MISMATCH" -> stringResource(R.string.array_run_reason_analyte_mismatch)
            "MODALITY_MISMATCH" -> stringResource(R.string.array_run_reason_modality_mismatch)
            "INPUT_PROTOCOL_MISMATCH" -> stringResource(R.string.array_run_reason_protocol_mismatch)
            "PRIMARY_FEATURE_MISMATCH" -> stringResource(R.string.array_run_reason_feature_mismatch)
            "CARRIER_TYPE_MISMATCH" -> stringResource(R.string.array_run_reason_carrier_mismatch)
            "ACQUISITION_PROFILE_MISMATCH" -> stringResource(R.string.array_run_reason_acquisition_mismatch)
            "PROCESSOR_NAME_MISMATCH", "PROCESSOR_VERSION_MISMATCH" ->
                stringResource(R.string.array_run_reason_processor_mismatch)
            "INVALID_COMPATIBILITY_METADATA" ->
                stringResource(R.string.array_run_reason_compatibility_invalid)
            "UNSUPPORTED_MODEL_TYPE" -> stringResource(R.string.array_run_reason_model_unsupported)
            "MISSING_STANDARD_CURVE" -> stringResource(R.string.array_run_reason_curve_missing)
            "INVALID_MODEL_DEFINITION" -> stringResource(R.string.array_run_reason_model_invalid)
            "NON_MONOTONIC_MODEL" -> stringResource(R.string.array_run_reason_model_non_monotonic)
            "NON_FINITE_SIGNAL" -> stringResource(R.string.array_run_reason_non_finite_signal)
            else -> reason
        }
    }
    return localized.distinct().joinToString().takeIf(String::isNotBlank)
}

@Composable
private fun formatRunTimestamp(timestampEpochMillis: Long): String {
    val locale = Locale.getDefault()
    return remember(timestampEpochMillis, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(timestampEpochMillis))
    }
}
