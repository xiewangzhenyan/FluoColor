package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.ui.theme.FluoRadius

/** 结果页首屏只负责运行身份和三项核心计数，详细分析放入后续标签页。 */
@Composable
internal fun ArrayResultHero(snapshot: ArrayResultSnapshot) {
    val measuredSiteCount = snapshot.sites.count { it.measurements.isNotEmpty() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(FluoRadius.control)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Biotech,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        text = snapshot.userFacingCarrierName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.array_result_carrier_summary,
                            snapshot.rows,
                            snapshot.columns,
                            arrayRunStatusLabel(snapshot.userFacingRunStatus())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroMetric(stringResource(R.string.array_result_physical_sites), snapshot.sites.size)
                HeroMetric(stringResource(R.string.array_result_measured_sites), measuredSiteCount)
                HeroMetric(stringResource(R.string.array_result_analyte_count), snapshot.analytes.size)
            }
        }
    }
}

/**
 * 旧运行可能统一写 Completed，但实际只保存信号；标题必须依据冻结浓度结果修正语义。
 * 单侧界限虽然没有点浓度，仍是模型成功执行后形成的浓度结论，不能降级成“仅信号”。
 * 失败、处理中和重拍状态仍尊重数据库原值。
 */
private fun ArrayResultSnapshot.userFacingRunStatus(): String {
    val analyteIds = analytes.map { it.analyteId }.toSet()
    val finiteConcentrationAnalyteIds = sites.asSequence()
        .flatMap { it.measurements.asSequence() }
        .filter { it.concentrationValue?.isFinite() == true }
        .mapNotNull { it.analyteId }
        .toSet()
    val boundaryAnalyteIds = sites.asSequence()
        .flatMap { it.measurements.asSequence() }
        .filter { it.quantificationState.equals("BOUND_ONLY", ignoreCase = true) }
        .mapNotNull { it.analyteId }
        .toSet()
    return resolveArrayResultRunStatus(
        storedStatus = runStatus,
        analyteIds = analyteIds,
        finiteConcentrationAnalyteIds = finiteConcentrationAnalyteIds,
        boundaryAnalyteIds = boundaryAnalyteIds
    )
}

/**
 * 把运行状态与冻结逐分析物结果合并成首屏科学语义。
 *
 * “仅报告界限”表示模型已执行且形成了浓度上下界，但没有任何点浓度；它既不是模型
 * 不可执行的“仅信号”，也不能冒充拥有精确数值的“已定量”。
 */
internal fun resolveArrayResultRunStatus(
    storedStatus: String,
    analyteIds: Set<String>,
    finiteConcentrationAnalyteIds: Set<String>,
    boundaryAnalyteIds: Set<String>
): String {
    val completedStatuses = setOf("Completed", "PartiallyQuantified", "SignalOnlyCompleted")
    if (storedStatus !in completedStatuses) return storedStatus
    val concentrationResultAnalyteIds = finiteConcentrationAnalyteIds + boundaryAnalyteIds
    return when {
        concentrationResultAnalyteIds.isEmpty() -> "SignalOnlyCompleted"
        finiteConcentrationAnalyteIds.isEmpty() &&
            analyteIds.isNotEmpty() && analyteIds.all(boundaryAnalyteIds::contains) ->
            "BoundaryOnlyCompleted"
        analyteIds.isNotEmpty() && analyteIds.all(finiteConcentrationAnalyteIds::contains) ->
            "Completed"
        else -> "PartiallyQuantified"
    }
}

/** 内置机器名本地化，自定义载体名保持用户原始输入。 */
@Composable
private fun ArrayResultSnapshot.userFacingCarrierName(): String {
    val builtInName = "microfluidic-${rows}x${columns}"
    return if (
        carrier.carrierType.equals("MICROFLUIDIC_CHIP", ignoreCase = true) &&
        carrier.name.equals(builtInName, ignoreCase = true)
    ) {
        stringResource(R.string.array_result_microfluidic_carrier_format, rows, columns)
    } else {
        carrier.name
    }
}

@Composable
private fun HeroMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
        )
    }
}
