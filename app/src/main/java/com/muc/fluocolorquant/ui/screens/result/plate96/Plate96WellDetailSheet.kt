package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue

const val PLATE96_WELL_DETAIL_SHEET_TAG: String = "plate96_well_detail_sheet"

/** 96孔板单孔详情只展示当前分析物，避免把同一物理孔的其他分析物字段混在一起。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Plate96WellDetailSheet(
    well: Plate96WellResult,
    analyte: ArrayAnalyteResult,
    valueState: ArrayHeatmapValueState,
    onDismiss: () -> Unit
) {
    val measurement = well.site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PLATE96_WELL_DETAIL_SHEET_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = well.wellLabel,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = analyte.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = plate96RoleLabel(well.site.roleCode),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            HorizontalDivider()
            Plate96DetailValue(
                label = stringResource(R.string.plate96_result_concentration),
                value = concentrationText(measurement, analyte, valueState)
            )
            Plate96DetailValue(
                label = stringResource(R.string.plate96_result_signal),
                value = measurement?.primaryFeatureValue?.let(::formatArrayHeatmapValue)
                    ?: stringResource(R.string.plate96_result_no_value)
            )
            Plate96DetailValue(
                label = stringResource(R.string.plate96_result_sample),
                value = well.site.sampleSlot ?: well.site.defaultSampleSlot
                    ?: stringResource(R.string.plate96_result_unassigned)
            )
            Plate96DetailValue(
                label = stringResource(R.string.plate96_result_range_status),
                value = plate96RangeStatus(valueState)
            )
            Plate96DetailValue(
                label = stringResource(R.string.plate96_result_localization),
                value = stringResource(
                    R.string.plate96_result_localization_value,
                    (well.site.geometry.confidence * 100.0).toInt(),
                    well.site.geometry.source.name
                )
            )
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Plate96DetailValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.padding(start = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun concentrationText(
    measurement: ArraySiteMeasurementResult?,
    analyte: ArrayAnalyteResult,
    valueState: ArrayHeatmapValueState
): String {
    return when (valueState) {
        ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> stringResource(
            R.string.plate96_result_below_range_value,
            analyte.projectRangeMin?.let(::formatArrayHeatmapValue).orEmpty(),
            analyte.concentrationUnit
        )
        ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> stringResource(
            R.string.plate96_result_above_range_value,
            analyte.projectRangeMax?.let(::formatArrayHeatmapValue).orEmpty(),
            analyte.concentrationUnit
        )
        ArrayHeatmapValueState.QUANTIFIED,
        ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> measurement?.concentrationValue?.let {
            "${formatArrayHeatmapValue(it)} ${measurement.concentrationUnit ?: analyte.concentrationUnit}"
        } ?: stringResource(R.string.plate96_result_no_value)
        ArrayHeatmapValueState.UNAVAILABLE -> stringResource(R.string.plate96_result_no_value)
    }
}

@Composable
private fun plate96RangeStatus(state: ArrayHeatmapValueState): String = stringResource(
    when (state) {
        ArrayHeatmapValueState.QUANTIFIED -> R.string.plate96_result_range_within
        ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> R.string.plate96_result_range_extrapolated
        ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> R.string.plate96_result_range_below
        ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> R.string.plate96_result_range_above
        ArrayHeatmapValueState.UNAVAILABLE -> R.string.plate96_result_range_unavailable
    }
)

@Composable
private fun plate96RoleLabel(roleCode: String?): String = stringResource(
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
