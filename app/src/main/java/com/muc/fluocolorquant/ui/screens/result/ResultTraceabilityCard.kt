package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.ui.components.ScientificExpandableSection
import java.util.Locale
import com.muc.fluocolorquant.ui.theme.FluoRadius

/**
 * 结果可追溯信息卡片。
 * 用于回看本次结果对应的采集、推理与相机运行参数。
 */
@Composable
fun ResultTraceabilityCard(
    analyteDetails: AnalyteResultDetails,
    modifier: Modifier = Modifier
) {
    val info = analyteDetails.traceabilityInfo ?: return
    val missingValue = stringResource(R.string.result_traceability_not_available)
    var expanded by rememberSaveable(analyteDetails.analyte.id) { mutableStateOf(false) }

    val recognitionType = when (info.recognitionType.uppercase(Locale.ROOT)) {
        "AUTO" -> stringResource(R.string.result_traceability_recognition_auto)
        "MANUAL" -> stringResource(R.string.result_traceability_recognition_manual)
        else -> info.recognitionType
    }

    val captureExposure = info.captureMetadata?.exposureTimeMs?.let {
        stringResource(R.string.result_traceability_exposure_value, formatDecimal(it))
    } ?: missingValue

    val captureIso = info.captureMetadata?.iso?.toString() ?: missingValue
    val captureAwb = when (info.captureMetadata?.awbModeLabel) {
        "AUTO_LOCK" -> stringResource(R.string.camera_capture_compact_wb_lock_value)
        "AUTO" -> stringResource(R.string.camera_capture_compact_wb_auto_value)
        null -> missingValue
        else -> info.captureMetadata.awbModeLabel
    }
    val captureTime = info.captureMetadata?.capturedAtLabel ?: info.runTimestampLabel ?: missingValue
    val confThreshold = info.confidenceThreshold?.let {
        stringResource(R.string.result_traceability_threshold_value, formatDecimal(it.toDouble()))
    } ?: missingValue
    val iouThreshold = info.iouThreshold?.let {
        stringResource(R.string.result_traceability_threshold_value, formatDecimal(it.toDouble()))
    } ?: missingValue

    ScientificExpandableSection(
        title = stringResource(R.string.result_traceability_title),
        summary = stringResource(
            R.string.result_traceability_compact_summary,
            captureTime,
            recognitionType
        ),
        icon = Icons.Default.Inventory2,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        toggleContentDescription = stringResource(
            if (expanded) R.string.result_details_collapse else R.string.result_details_expand
        ),
        modifier = modifier
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_capture_time),
                    value = captureTime
                )
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_recognition_type),
                    value = recognitionType
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_detection_model),
                    value = info.detectionModelName ?: missingValue
                )
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_concentration_model),
                    value = info.concentrationModelName ?: missingValue
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_capture_iso),
                    value = captureIso
                )
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_capture_exposure),
                    value = captureExposure
                )
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_capture_awb),
                    value = captureAwb
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_conf_threshold),
                    value = confThreshold
                )
                TraceabilityMetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_traceability_iou_threshold),
                    value = iouThreshold
                )
            }
        }
    }
}

@Composable
private fun TraceabilityMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.36f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(FluoRadius.control)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
        }
    }
}

private fun formatDecimal(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}
