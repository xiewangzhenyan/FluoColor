package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.array.ArrayMultiHoleStatistics
import com.muc.fluocolorquant.domain.detection.array.MultiHoleValue
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.label

/** 新结果展示实际的冻结口径和派生统计；旧结果没有该字段就不从当前设置补写。 */
@Composable
internal fun ArrayFrozenReadoutSummary(snapshot: ArrayResultSnapshot, analyteId: String) {
    val method = remember(snapshot.processingVersionJson) {
        runCatching {
            val name = JsonParser.parseString(snapshot.processingVersionJson).asJsonObject.get("wellReadout")?.asString
            WellReadoutMode.entries.firstOrNull { it.name == name }
        }.getOrNull()
    }
    val statistics = remember(snapshot.siteQcSummaryJson) { ArrayMultiHoleStatistics.fromSiteQc(snapshot.siteQcSummaryJson) }
    if (method == null && statistics == null) return
    FluoSectionCard(modifier = Modifier.testTag("array_frozen_readout"), verticalSpacing = 8.dp) {
        if (method != null) Text(stringResource(R.string.array_readout_label) + ": " + stringResource(method.label()),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (statistics != null) {
            Text(stringResource(R.string.array_group_label) + ": " + stringResource(statistics.mode.label()),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val groups = statistics.groups.filter { it.analyteId == analyteId }
            if (groups.isEmpty()) Text(stringResource(R.string.array_group_help), style = MaterialTheme.typography.bodySmall)
            groups.forEach { group ->
                Text(listOfNotNull(group.sampleId, group.repeatGroup).joinToString(" · "), fontWeight = FontWeight.Medium)
                StatisticValue(stringResource(R.string.array_group_signal), group.signal, group.siteIndices.size)
                StatisticValue(stringResource(R.string.array_group_concentration), group.concentration, group.siteIndices.size)
            }
        }
    }
}

@Composable
private fun StatisticValue(label: String, metric: MultiHoleValue, total: Int) {
    val value = metric.value?.let { listOfNotNull(formatArrayHeatmapValue(it), metric.unit).joinToString(" ") }
        ?: stringResource(R.string.array_group_no_value)
    Text(stringResource(R.string.array_group_members, label, value, metric.usedWells, total), style = MaterialTheme.typography.bodySmall)
}
