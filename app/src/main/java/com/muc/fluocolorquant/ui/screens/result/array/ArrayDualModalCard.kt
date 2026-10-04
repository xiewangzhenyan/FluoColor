package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.repository.DualModalCurrentPairing
import com.muc.fluocolorquant.data.repository.DualModalPairingCandidate
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalDecision
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalDecisionReason
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalIncompatibility
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalReading
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalSideReading
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalSideStatus
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalThresholds
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.viewmodels.DualModalNotice
import com.muc.fluocolorquant.ui.viewmodels.DualModalUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

const val ARRAY_DUAL_MODAL_CARD_TAG: String = "array_dual_modal_card"
const val ARRAY_DUAL_MODAL_PAIR_BUTTON_TAG: String = "array_dual_modal_pair_button"
const val ARRAY_DUAL_MODAL_SHEET_TAG: String = "array_dual_modal_sheet"
const val ARRAY_DUAL_MODAL_CANDIDATE_TAG_PREFIX: String = "array_dual_modal_candidate_"
const val ARRAY_DUAL_MODAL_READING_TAG_PREFIX: String = "array_dual_modal_reading_"

/** 卡片回调集合；默认空实现便于预览与快照测试直接注入状态。 */
data class DualModalCardActions(
    val onLoadCandidates: () -> Unit = {},
    val onPair: (String) -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onReAdjudicate: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onRetry: () -> Unit = {}
)

/**
 * 比色—荧光双模态判定卡片。
 *
 * 卡片按"事实＋建议"组织：先列两侧读数与相对离散度，证据可展开查看，最后一行才是按规则
 * 得到的建议，并注明规则版本与阈值来源。建议不改写任何一侧的浓度，结论由检测人员确认。
 */
@Composable
internal fun ArrayDualModalCard(
    state: DualModalUiState,
    analyte: ArrayAnalyteResult,
    actions: DualModalCardActions
) {
    if (state is DualModalUiState.Hidden) return
    var showSheet by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ARRAY_DUAL_MODAL_CARD_TAG),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.array_dual_modal_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            when (state) {
                DualModalUiState.Hidden -> Unit
                DualModalUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                DualModalUiState.Error -> {
                    Text(
                        text = stringResource(R.string.array_dual_modal_load_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = actions.onRetry) {
                        Text(stringResource(R.string.array_dual_modal_retry))
                    }
                }
                is DualModalUiState.Ready -> {
                    state.notice?.let { notice -> NoticeRow(notice, actions.onDismissNotice) }
                    val pairing = state.pairing
                    if (pairing == null) {
                        Text(
                            text = stringResource(R.string.array_dual_modal_unpaired_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                showSheet = true
                                actions.onLoadCandidates()
                            },
                            enabled = !state.working,
                            modifier = Modifier.testTag(ARRAY_DUAL_MODAL_PAIR_BUTTON_TAG)
                        ) {
                            Text(stringResource(R.string.array_dual_modal_pair_action))
                        }
                    } else {
                        PairedContent(pairing = pairing, analyte = analyte, working = state.working, actions = actions)
                    }
                    if (state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (showSheet && state is DualModalUiState.Ready && state.pairing == null) {
        DualModalPairingSheet(
            candidates = state.candidates,
            loading = state.loadingCandidates,
            onSelect = { runId ->
                showSheet = false
                actions.onPair(runId)
            },
            onDismiss = { showSheet = false }
        )
    }
}

@Composable
private fun PairedContent(
    pairing: DualModalCurrentPairing,
    analyte: ArrayAnalyteResult,
    working: Boolean,
    actions: DualModalCardActions
) {
    val counterpart = pairing.counterpart
    Text(
        text = if (counterpart == null) {
            stringResource(R.string.array_dual_modal_counterpart_missing)
        } else {
            stringResource(
                R.string.array_dual_modal_paired_with,
                counterpart.projectName,
                modeLabel(counterpart.detectionMode),
                formatTime(counterpart.timestampEpochMillis)
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val adjudication = pairing.snapshot.adjudication
    val readings = adjudication.readings.filter { it.analyteId == analyte.analyteId }
    if (readings.isEmpty()) {
        Text(
            text = stringResource(R.string.array_dual_modal_no_reading),
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        readings.forEachIndexed { index, reading ->
            if (index > 0) HorizontalDivider()
            ReadingBlock(reading = reading, thresholds = adjudication.thresholds)
        }
    }
    Text(
        text = stringResource(
            R.string.array_dual_modal_rule_note,
            adjudication.ruleVersion,
            formatValue(adjudication.thresholds.deltaPercent),
            formatValue(adjudication.thresholds.qcDeviation)
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onReAdjudicate, enabled = !working && counterpart != null) {
            Text(stringResource(R.string.array_dual_modal_readjudicate))
        }
        TextButton(onClick = actions.onUnpair, enabled = !working) {
            Text(stringResource(R.string.array_dual_modal_unpair))
        }
    }
}

@Composable
private fun ReadingBlock(reading: DualModalReading, thresholds: DualModalThresholds) {
    var expanded by rememberSaveable(reading.analyteId, reading.sampleKey) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_DUAL_MODAL_READING_TAG_PREFIX${reading.analyteId}_${reading.sampleKey}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = reading.sampleKey,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.array_dual_modal_collapse else R.string.array_dual_modal_expand
                )
            )
        }
        Text(
            text = stringResource(
                R.string.array_dual_modal_reading_values,
                formatValue(reading.colorimetric.concentration),
                formatValue(reading.fluorescence.concentration),
                reading.concentrationUnit,
                reading.deltaPercent?.let { formatValue(it) + "%" } ?: "—"
            ),
            style = MaterialTheme.typography.bodySmall
        )
        if (expanded) {
            SideFacts(label = stringResource(R.string.array_dual_modal_mode_colorimetric), side = reading.colorimetric, thresholds = thresholds)
            SideFacts(label = stringResource(R.string.array_dual_modal_mode_fluorescence), side = reading.fluorescence, thresholds = thresholds)
        }
        Text(
            text = decisionText(reading),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = decisionColor(reading.decision)
        )
        Text(
            text = reasonText(reading.reason),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SideFacts(label: String, side: DualModalSideReading, thresholds: DualModalThresholds) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                R.string.array_dual_modal_side_summary,
                label,
                statusLabel(side.status),
                side.validSiteCount,
                side.totalSiteCount,
                stringResource(
                    if (side.localizationTrusted) {
                        R.string.array_dual_modal_localization_trusted
                    } else {
                        R.string.array_dual_modal_localization_untrusted
                    }
                )
            ),
            style = MaterialTheme.typography.labelSmall
        )
        if (side.qcEvidence.none { it.relativeDeviation != null }) {
            Text(
                text = stringResource(R.string.array_dual_modal_qc_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        side.qcEvidence.filter { it.relativeDeviation != null }.forEach { evidence ->
            Text(
                text = stringResource(
                    R.string.array_dual_modal_qc_line,
                    formatValue(evidence.nominalConcentration),
                    formatValue(evidence.measuredSignal),
                    formatValue(evidence.predictedSignal),
                    formatValue(evidence.relativeDeviation),
                    formatValue(thresholds.qcDeviation)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (evidence.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: DualModalNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        DualModalNotice.LoadFailed -> stringResource(R.string.array_dual_modal_notice_load_failed)
        DualModalNotice.SaveFailed -> stringResource(R.string.array_dual_modal_notice_save_failed)
        is DualModalNotice.Incompatible -> stringResource(
            R.string.array_dual_modal_notice_incompatible,
            notice.reasons.sortedBy(DualModalIncompatibility::ordinal).map { incompatibilityLabel(it) }.joinToString("、")
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(FluoRadius.control),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.array_dual_modal_dismiss)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualModalPairingSheet(
    candidates: List<DualModalPairingCandidate>?,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ARRAY_DUAL_MODAL_SHEET_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.array_dual_modal_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.array_dual_modal_sheet_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                loading || candidates == null -> item {
                    Row(modifier = Modifier.padding(vertical = 24.dp)) { CircularProgressIndicator() }
                }
                candidates.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.array_dual_modal_sheet_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> items(candidates, key = DualModalPairingCandidate::runId) { candidate ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(candidate.runId) }
                            .testTag("$ARRAY_DUAL_MODAL_CANDIDATE_TAG_PREFIX${candidate.runId}"),
                        shape = RoundedCornerShape(FluoRadius.card),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = candidate.projectName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = modeLabel(candidate.detectionMode) + " · " + formatTime(candidate.timestampEpochMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(22.dp)) }
        }
    }
}

@Composable
private fun decisionText(reading: DualModalReading): String {
    val value = formatValue(reading.suggestedConcentration)
    return when (reading.decision) {
        DualModalDecision.FUSE -> stringResource(R.string.array_dual_modal_decision_fuse, value, reading.concentrationUnit)
        DualModalDecision.ADOPT_COLORIMETRIC ->
            stringResource(R.string.array_dual_modal_decision_adopt_col, value, reading.concentrationUnit)
        DualModalDecision.ADOPT_FLUORESCENCE ->
            stringResource(R.string.array_dual_modal_decision_adopt_flu, value, reading.concentrationUnit)
        DualModalDecision.RETEST -> stringResource(R.string.array_dual_modal_decision_retest)
    }
}

@Composable
private fun decisionColor(decision: DualModalDecision): Color = when (decision) {
    DualModalDecision.FUSE -> MaterialTheme.colorScheme.primary
    DualModalDecision.ADOPT_COLORIMETRIC, DualModalDecision.ADOPT_FLUORESCENCE -> MaterialTheme.colorScheme.tertiary
    DualModalDecision.RETEST -> MaterialTheme.colorScheme.error
}

@Composable
private fun reasonText(reason: DualModalDecisionReason): String = stringResource(
    when (reason) {
        DualModalDecisionReason.CONSISTENT -> R.string.array_dual_modal_reason_consistent
        DualModalDecisionReason.COLORIMETRIC_UNUSABLE -> R.string.array_dual_modal_reason_colorimetric_unusable
        DualModalDecisionReason.FLUORESCENCE_UNUSABLE -> R.string.array_dual_modal_reason_fluorescence_unusable
        DualModalDecisionReason.BOTH_SIDES_UNUSABLE -> R.string.array_dual_modal_reason_both_unusable
        DualModalDecisionReason.DISCREPANCY_UNATTRIBUTED -> R.string.array_dual_modal_reason_unattributed
    }
)

@Composable
private fun statusLabel(status: DualModalSideStatus): String = stringResource(
    when (status) {
        DualModalSideStatus.USABLE -> R.string.array_dual_modal_status_usable
        DualModalSideStatus.QC_ABNORMAL -> R.string.array_dual_modal_status_qc_abnormal
        DualModalSideStatus.LOCALIZATION_UNTRUSTED -> R.string.array_dual_modal_status_localization_untrusted
        DualModalSideStatus.NO_CONCENTRATION -> R.string.array_dual_modal_status_no_concentration
    }
)

@Composable
private fun incompatibilityLabel(reason: DualModalIncompatibility): String = stringResource(
    when (reason) {
        DualModalIncompatibility.SAME_DETECTION_MODE -> R.string.array_dual_modal_incompat_same_mode
        DualModalIncompatibility.UNSUPPORTED_DETECTION_MODE -> R.string.array_dual_modal_incompat_unsupported_mode
        DualModalIncompatibility.GRID_SIZE_MISMATCH -> R.string.array_dual_modal_incompat_grid
        DualModalIncompatibility.CARRIER_MISMATCH -> R.string.array_dual_modal_incompat_carrier
        DualModalIncompatibility.SITE_LAYOUT_MISMATCH -> R.string.array_dual_modal_incompat_layout
        DualModalIncompatibility.NO_SHARED_ANALYTE -> R.string.array_dual_modal_incompat_no_analyte
        DualModalIncompatibility.UNIT_MISMATCH -> R.string.array_dual_modal_incompat_unit
    }
)

@Composable
private fun modeLabel(mode: String): String = when {
    mode.equals(DualModalAdjudicationEngine.COLORIMETRIC, ignoreCase = true) ->
        stringResource(R.string.array_dual_modal_mode_colorimetric)
    mode.equals(DualModalAdjudicationEngine.FLUORESCENCE, ignoreCase = true) ->
        stringResource(R.string.array_dual_modal_mode_fluorescence)
    else -> mode
}

private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMillis))

/** 浓度、信号与偏差统一按数量级取有效位数显示；空值显示破折号。 */
internal fun formatValue(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val magnitude = abs(value)
    val pattern = when {
        magnitude >= 100.0 -> "%.0f"
        magnitude >= 10.0 -> "%.1f"
        magnitude >= 1.0 -> "%.2f"
        magnitude == 0.0 -> "%.0f"
        else -> "%.3g"
    }
    return String.format(Locale.getDefault(), pattern, value)
}
