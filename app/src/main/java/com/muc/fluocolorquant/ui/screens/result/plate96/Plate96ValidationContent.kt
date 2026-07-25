package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.AddChart
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import java.text.NumberFormat
import kotlin.math.abs

/**
 * 圆孔板预测精度验证页。
 *
 * 页面只允许录入参考浓度；预测浓度来自运行冻结快照。保存后生成回归和Bland–Altman，
 * 不重新定位、拟合或计算孔位浓度。
 */
@Composable
fun Plate96ValidationContent(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    validation: ResultValidationSnapshot?,
    saving: Boolean,
    saveFailed: Boolean,
    onSave: (Map<Int, Double>) -> Unit
) {
    var showInputDialog by remember(snapshot.runId, analyte.analyteId) { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PlateValidationHeaderCard(
                analyte = analyte,
                validation = validation,
                saving = saving,
                onEdit = { showInputDialog = true }
            )
        }
        if (saveFailed) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plate_validation_save_failed),
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (validation != null) {
            item { PlateRegressionCard(validation) }
            item { PlateBlandAltmanCard(validation) }
        }
    }

    if (showInputDialog) {
        PlateValidationInputDialog(
            snapshot = snapshot,
            analyte = analyte,
            previous = validation,
            saving = saving,
            onDismiss = { if (!saving) showInputDialog = false },
            onSave = { values ->
                onSave(values)
                showInputDialog = false
            }
        )
    }
}

@Composable
private fun PlateValidationHeaderCard(
    analyte: ArrayAnalyteResult,
    validation: ResultValidationSnapshot?,
    saving: Boolean,
    onEdit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.FactCheck,
                        contentDescription = null,
                        modifier = Modifier.padding(11.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.plate_validation_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            R.string.plate_validation_analyte_summary,
                            analyte.name,
                            analyte.concentrationUnit
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                validation?.let { saved ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.plate_validation_revision, saved.revision),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Button(
                onClick = onEdit,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (validation == null) Icons.Outlined.AddChart else Icons.Outlined.Edit,
                        contentDescription = null
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (validation == null) R.string.plate_validation_input_action
                        else R.string.plate_validation_edit_action
                    )
                )
            }
        }
    }
}

@Composable
private fun PlateRegressionCard(validation: ResultValidationSnapshot) {
    val points = validation.points
    val xValues = points.map { point -> point.referenceValue }
    val yValues = points.map { point -> point.predictedValue }
    val xRange = validationPaddedRange(xValues)
    val yRange = validationPaddedRange(yValues)
    val commonMin = minOf(xRange.first, yRange.first)
    val commonMax = maxOf(xRange.second, yRange.second)
    val regressionLine = validation.regression.slope?.let { slope ->
        validation.regression.intercept?.let { intercept ->
            listOf(
                commonMin to (slope * commonMin + intercept),
                commonMax to (slope * commonMax + intercept)
            )
        }
    }.orEmpty()
    PlateValidationChartCard(
        title = stringResource(R.string.plate_validation_regression_title),
        icon = Icons.Outlined.Insights
    ) {
        CurveChart(
            data = ChartData(
                // 外层卡片已经承担标题与图标层级，图内不再重复标题，把有限高度留给数据区。
                title = "",
                xRange = commonMin to commonMax,
                yRange = commonMin to commonMax,
                standardPoints = listOf(commonMin to commonMin, commonMax to commonMax),
                curvePoints = regressionLine,
                scatterPoints = points.map { point ->
                    ChartPoint(
                        x = point.referenceValue,
                        y = point.predictedValue,
                        label = point.siteLabel
                    )
                },
                xAxisLabel = stringResource(R.string.plate_validation_reference_axis),
                yAxisLabel = stringResource(R.string.plate_validation_predicted_axis),
                chartType = "REGRESSION"
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            interactive = true
        )
        PlateValidationMetricGrid(
            metrics = listOf(
                R.string.plate_validation_metric_r2 to validation.regression.rSquared,
                R.string.plate_validation_metric_slope to validation.regression.slope,
                R.string.plate_validation_metric_rmse to validation.regression.rmse,
                R.string.plate_validation_metric_mae to validation.regression.mae
            ),
            unit = validation.concentrationUnit
        )
    }
}

@Composable
private fun PlateBlandAltmanCard(validation: ResultValidationSnapshot) {
    val averages = validation.points.map { point ->
        (point.predictedValue + point.referenceValue) / 2.0
    }
    val differences = validation.points.map { point ->
        point.predictedValue - point.referenceValue
    }
    val xRange = validationPaddedRange(averages)
    val yRange = validationPaddedRange(
        differences + validation.blandAltman.lowerLimit + validation.blandAltman.upperLimit
    )
    fun horizontalLine(value: Double): List<Pair<Double, Double>> = listOf(
        xRange.first to value,
        xRange.second to value
    )
    PlateValidationChartCard(
        title = stringResource(R.string.plate_validation_bland_title),
        icon = Icons.Outlined.Science
    ) {
        CurveChart(
            data = ChartData(
                // 与回归图保持一致，仅保留外层卡片标题，减少移动端首屏的重复信息。
                title = "",
                xRange = xRange,
                yRange = yRange,
                scatterPoints = validation.points.mapIndexed { index, point ->
                    ChartPoint(
                        x = averages[index],
                        y = differences[index],
                        label = point.siteLabel
                    )
                },
                xAxisLabel = stringResource(R.string.plate_validation_mean_axis),
                yAxisLabel = stringResource(R.string.plate_validation_difference_axis),
                chartType = "BLAND_ALTMAN",
                additionalLines = mapOf(
                    "mean" to horizontalLine(validation.blandAltman.meanBias),
                    "upperLimit" to horizontalLine(validation.blandAltman.upperLimit),
                    "lowerLimit" to horizontalLine(validation.blandAltman.lowerLimit)
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            interactive = true
        )
        PlateValidationMetricGrid(
            metrics = listOf(
                R.string.plate_validation_metric_bias to validation.blandAltman.meanBias,
                R.string.plate_validation_metric_lower to validation.blandAltman.lowerLimit,
                R.string.plate_validation_metric_upper to validation.blandAltman.upperLimit,
                R.string.plate_validation_metric_within to
                    (validation.blandAltman.withinLimitsRatio * 100.0)
            ),
            unit = validation.concentrationUnit,
            percentageLast = true
        )
    }
}

@Composable
private fun PlateValidationChartCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun PlateValidationMetricGrid(
    metrics: List<Pair<Int, Double?>>, unit: String, percentageLast: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEachIndexed { rowIndex, rowMetrics ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMetrics.forEachIndexed { columnIndex, (label, value) ->
                    val metricIndex = rowIndex * 2 + columnIndex
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value?.let { finite ->
                                    val formatted = formatValidationNumber(finite)
                                    if (percentageLast && metricIndex == metrics.lastIndex) {
                                        stringResource(R.string.plate_validation_percent_value, formatted)
                                    } else if (label in setOf(
                                            R.string.plate_validation_metric_rmse,
                                            R.string.plate_validation_metric_mae,
                                            R.string.plate_validation_metric_bias,
                                            R.string.plate_validation_metric_lower,
                                            R.string.plate_validation_metric_upper
                                        )
                                    ) {
                                        stringResource(R.string.plate_validation_unit_value, formatted, unit)
                                    } else {
                                        formatted
                                    }
                                } ?: stringResource(R.string.plate96_result_no_value_short),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PlateValidationInputDialog(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    previous: ResultValidationSnapshot?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<Int, Double>) -> Unit
) {
    val candidates = remember(snapshot.runId, analyte.analyteId) {
        snapshot.wells.mapNotNull { well ->
            val predicted = well.site.measurements
                .firstOrNull { measurement -> measurement.analyteId == analyte.analyteId }
                ?.concentrationValue
                ?.takeIf(Double::isFinite) ?: return@mapNotNull null
            PlateValidationCandidate(well, predicted)
        }
    }
    val inputs = remember(previous?.validationId, candidates) {
        mutableStateMapOf<Int, String>().apply {
            previous?.points?.forEach { point ->
                put(point.siteIndex, formatEditableValidationNumber(point.referenceValue))
            }
        }
    }
    val parsedValues = inputs.mapNotNull { (siteIndex, value) ->
        value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { siteIndex to it }
    }.toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plate_validation_input_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(candidates, key = { candidate -> candidate.well.wellIndex }) { candidate ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = candidate.well.wellLabel,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(modifier = Modifier.weight(0.8f)) {
                                Text(
                                    text = stringResource(R.string.plate_validation_predicted_short),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.plate_validation_unit_value,
                                        formatValidationNumber(candidate.predicted),
                                        analyte.concentrationUnit
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedTextField(
                                value = inputs[candidate.well.wellIndex].orEmpty(),
                                onValueChange = { changed ->
                                    inputs[candidate.well.wellIndex] = changed.filter { character ->
                                        character.isDigit() || character == '.' || character == '-'
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.plate_validation_reference_short)) },
                                suffix = { Text(analyte.concentrationUnit) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsedValues) },
                enabled = parsedValues.size >= 2 && !saving
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.plate_validation_calculate_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private data class PlateValidationCandidate(
    val well: Plate96WellResult,
    val predicted: Double
)

private fun validationPaddedRange(values: List<Double>): Pair<Double, Double> {
    val finite = values.filter(Double::isFinite)
    if (finite.isEmpty()) return 0.0 to 1.0
    val minimum = finite.minOrNull() ?: 0.0
    val maximum = finite.maxOrNull() ?: 1.0
    val span = (maximum - minimum).takeIf { it > 0.0 }
        ?: maxOf(abs(maximum) * 0.2, 1.0)
    return (minimum - span * 0.1) to (maximum + span * 0.1)
}

private fun formatValidationNumber(value: Double): String {
    val formatter = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 4 }
    return formatter.format(value)
}

private fun formatEditableValidationNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
