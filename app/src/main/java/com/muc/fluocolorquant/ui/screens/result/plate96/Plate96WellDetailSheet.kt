package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapScaleMode
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val PLATE96_INLINE_WELL_DETAIL_TAG: String = "plate96_inline_well_detail"

/**
 * 96孔板单孔详情的页面内联卡片。
 *
 * 详情跟随热力图出现在统计卡下方，不再使用底部弹层遮挡孔板。这样用户可以同时看到
 * 被选中的圆孔、整板颜色分布和当前孔位的科学信息，符合科研结果页的连续阅读方式。
 */
@Composable
fun Plate96InlineWellDetailCard(
    snapshot: Plate96ResultSnapshot,
    well: Plate96WellResult,
    analyte: ArrayAnalyteResult,
    valueState: ArrayHeatmapValueState,
    scaleMode: ArrayHeatmapScaleMode,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val measurement = well.site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
    val concentrationMode = scaleMode == ArrayHeatmapScaleMode.CONCENTRATION
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PLATE96_INLINE_WELL_DETAIL_TAG),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Plate96WellThumbnail(snapshot = snapshot, well = well, size = 56.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = well.wellLabel,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                        ) {
                            Text(
                                text = plate96RoleLabel(well.site.roleCode),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = analyte.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = well.site.sampleSlot ?: well.site.defaultSampleSlot
                            ?: stringResource(R.string.plate96_result_default_sample, well.wellLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.plate96_result_close_detail),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(
                        if (concentrationMode) R.string.plate96_result_calculated_concentration
                        else R.string.plate96_result_concentration
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = concentrationText(measurement, analyte, valueState),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Plate96InlineDetailMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.plate96_result_signal),
                    value = measurement?.primaryFeatureValue?.let(::formatArrayHeatmapValue)
                        ?: stringResource(R.string.plate96_result_no_value_short)
                )
                Plate96InlineDetailMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.plate96_result_range_status),
                    value = if (concentrationMode) {
                        plate96RangeStatus(measurement, valueState)
                    } else {
                        stringResource(R.string.plate96_result_signal_only)
                    }
                )
            }
        }
    }
}

/** 内联详情中的两列紧凑指标，避免再次堆叠大量说明文字。 */
@Composable
private fun Plate96InlineDetailMetric(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 根据项目量程与冻结浓度生成单孔的最终浓度文案。 */
@Composable
internal fun concentrationText(
    measurement: ArraySiteMeasurementResult?,
    analyte: ArrayAnalyteResult,
    valueState: ArrayHeatmapValueState
): String = when (valueState) {
    ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> stringResource(
        R.string.plate96_result_below_range_value,
        measurement?.concentrationUpperBound?.let(::formatArrayHeatmapValue)
            ?: analyte.projectRangeMin?.let(::formatArrayHeatmapValue).orEmpty(),
        analyte.concentrationUnit
    )
    ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> stringResource(
        R.string.plate96_result_above_range_value,
        measurement?.concentrationLowerBound?.let(::formatArrayHeatmapValue)
            ?: analyte.projectRangeMax?.let(::formatArrayHeatmapValue).orEmpty(),
        analyte.concentrationUnit
    )
    ArrayHeatmapValueState.QUANTIFIED -> measurement?.concentrationValue?.let {
        "${formatArrayHeatmapValue(it)} ${measurement.concentrationUnit ?: analyte.concentrationUnit}"
    } ?: stringResource(R.string.plate96_result_no_value)
    ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> measurement?.concentrationValue?.let {
        stringResource(
            R.string.plate96_result_estimated_value,
            formatArrayHeatmapValue(it),
            measurement.concentrationUnit ?: analyte.concentrationUnit
        )
    } ?: stringResource(R.string.plate96_result_no_value)
    ArrayHeatmapValueState.UNAVAILABLE -> stringResource(R.string.plate96_result_no_value)
}

/**
 * 将冻结机器状态映射为稳定、可翻译的科研状态。
 *
 * 热力图为了画方向箭头会把项目边界、可信边界和删失界限收敛到同一绘图状态；详情页
 * 不能据此统称“超出项目量程”，必须优先读取原始范围状态和单侧界限方向。
 */
@Composable
internal fun plate96RangeStatus(
    measurement: ArraySiteMeasurementResult?,
    state: ArrayHeatmapValueState
): String = stringResource(
    when {
        measurement?.reliableRangeStatus.equals("BELOW_TRUSTED_RANGE", ignoreCase = true) ->
            R.string.plate96_result_range_below_trusted
        measurement?.reliableRangeStatus.equals("ABOVE_TRUSTED_RANGE", ignoreCase = true) ->
            R.string.plate96_result_range_above_trusted
        measurement?.reliableRangeStatus.equals("BELOW_PROJECT_RANGE", ignoreCase = true) ->
            R.string.plate96_result_range_below
        measurement?.reliableRangeStatus.equals("ABOVE_PROJECT_RANGE", ignoreCase = true) ->
            R.string.plate96_result_range_above
        measurement?.quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            measurement?.censoringDirection.equals("LOWER_BOUND", ignoreCase = true) ->
            R.string.plate96_result_range_lower_bound_only
        measurement?.quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            measurement?.censoringDirection.equals("UPPER_BOUND", ignoreCase = true) ->
            R.string.plate96_result_range_upper_bound_only
        state == ArrayHeatmapValueState.QUANTIFIED -> R.string.plate96_result_range_within
        state == ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED ->
            R.string.plate96_result_range_extrapolated
        state == ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> R.string.plate96_result_range_below
        state == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> R.string.plate96_result_range_above
        else -> R.string.plate96_result_range_unavailable
    }
)

/** 将冻结孔位角色映射为用户可读标签。 */
@Composable
internal fun plate96RoleLabel(roleCode: String?): String = stringResource(
    when (roleCode) {
        "SAMPLE" -> R.string.plate96_result_role_sample
        "STANDARD" -> R.string.plate96_result_role_standard
        "BLANK" -> R.string.plate96_result_role_blank
        "NEGATIVE_CONTROL" -> R.string.plate96_result_role_negative
        "POSITIVE_CONTROL" -> R.string.plate96_result_role_positive
        "REFERENCE" -> R.string.plate96_result_role_reference
        else -> R.string.plate96_result_role_unassigned
    }
)
