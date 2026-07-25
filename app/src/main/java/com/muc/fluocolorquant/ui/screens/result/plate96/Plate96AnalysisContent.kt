package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.BorderOuter
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import com.muc.fluocolorquant.ui.components.LatexAlignment
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlin.math.sqrt

const val PLATE96_ANALYSIS_TAG: String = "plate96_analysis"
const val PLATE96_SAMPLE_TABLE_TAG: String = "plate96_sample_table"

/**
 * 96孔板分析页集中承载曲线、样本表和孔板专属统计。
 *
 * 结果页不再重复样本浓度表；用户先通过热力图定位异常，再到本页查看重复孔、行列和边缘效应。
 */
@Composable
fun Plate96AnalysisContent(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    onWellClick: (Int) -> Unit
) {
    val records = remember(snapshot.runId, analyte.analyteId) {
        snapshot.wells.mapNotNull { well ->
            well.site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
                ?.let { measurement -> Plate96AnalysisRecord(well, measurement.concentrationValue) }
        }
    }
    LazyColumn(
        modifier = Modifier.testTag(PLATE96_ANALYSIS_TAG),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Plate96CurveCard(analyte) }
        item { Plate96RepeatabilityCard(records) }
        item {
            Plate96DistributionCard(
                records = records,
                rows = snapshot.arraySnapshot.rows,
                columns = snapshot.arraySnapshot.columns
            )
        }
        item {
            Plate96EdgeEffectCard(
                records = records,
                rows = snapshot.arraySnapshot.rows,
                columns = snapshot.arraySnapshot.columns
            )
        }
        item { Plate96RoleSummaryCard(snapshot, analyte) }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.TableRows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.plate96_analysis_sample_table),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item {
            Column(
                modifier = Modifier.testTag(PLATE96_SAMPLE_TABLE_TAG),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val samples = records.filter { it.well.site.roleCode == "SAMPLE" }
                if (samples.isEmpty()) {
                    Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_no_samples))
                } else {
                    samples.forEach { record ->
                        Plate96SampleRow(
                            record = record,
                            unit = analyte.concentrationUnit,
                            onClick = { onWellClick(record.well.wellIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Plate96CurveCard(analyte: ArrayAnalyteResult) {
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_curve),
        icon = Icons.AutoMirrored.Outlined.ShowChart
    ) {
        val function = FittingFunction.fromIdentifier(analyte.fittingFunction.orEmpty())
        val points = analyte.calibrationPoints.map { it.concentration to it.signalValue }
        if (function == null || points.size < 2 || analyte.fittingParameters.isEmpty()) {
            Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_curve_unavailable))
            return@Plate96AnalysisCard
        }
        val xValues = points.map { point -> point.first }
        val yValues = points.map { point -> point.second }
        val xRange = paddedRange(xValues)
        val yRange = paddedRange(yValues)
        CurveChart(
            data = ChartData(
                xRange = xRange,
                yRange = yRange,
                standardPoints = points,
                fittedCurve = { x -> FittingEngine.calculate(function, analyte.fittingParameters, x) },
                formula = FittingEngine.formatParametersToLatex(function, analyte.fittingParameters),
                xAxisLabel = stringResource(R.string.chart_concentration),
                yAxisLabel = stringResource(R.string.chart_pixel_value),
                chartType = "STANDARD_CURVE"
            ),
            modifier = Modifier.fillMaxWidth(),
            interactive = true
        )
        LatexView(
            latex = FittingEngine.formatParametersToLatex(function, analyte.fittingParameters),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            alignment = LatexAlignment.CENTER
        )
        val rSquared = analyte.validationMetrics["R2"] ?: analyte.validationMetrics["R²"]
        if (rSquared != null) {
            Text(
                text = stringResource(
                    R.string.plate96_analysis_r_squared,
                    formatArrayHeatmapValue(rSquared)
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun Plate96RepeatabilityCard(records: List<Plate96AnalysisRecord>) {
    val groups = records.groupBy { it.well.site.repeatGroup }
        .filterKeys { !it.isNullOrBlank() }
        .mapNotNull { (name, group) ->
            val values = group.mapNotNull { it.concentration?.takeIf(Double::isFinite) }
            if (values.size < 2) null else {
                val mean = values.average()
                val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
                Plate96RepeatGroup(name.orEmpty(), values.size, mean, if (mean == 0.0) null else sd / mean * 100.0)
            }
        }
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_repeatability),
        icon = Icons.Outlined.Repeat
    ) {
        if (groups.isEmpty()) {
            Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_no_repeats))
        } else {
            groups.take(8).forEach { group ->
                Plate96MetricLine(
                    label = group.name,
                    value = stringResource(
                        R.string.plate96_analysis_repeat_value,
                        group.count,
                        formatArrayHeatmapValue(group.mean),
                        group.cvPercent?.let(::formatArrayHeatmapValue)
                            ?: stringResource(R.string.plate96_result_no_value_short)
                    )
                )
            }
        }
    }
}

@Composable
private fun Plate96DistributionCard(
    records: List<Plate96AnalysisRecord>,
    rows: Int,
    columns: Int
) {
    val safeRows = rows.coerceAtLeast(1)
    val safeColumns = columns.coerceAtLeast(1)
    val rowMeans = (0 until safeRows).map { row ->
        records.filter { it.well.rowIndex == row }.mapNotNull { it.concentration }.finiteMean()
    }
    val columnMeans = (0 until safeColumns).map { column ->
        records.filter { it.well.columnIndex == column }.mapNotNull { it.concentration }.finiteMean()
    }
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_distribution),
        icon = Icons.Outlined.GridView
    ) {
        Plate96MiniBars(
            label = stringResource(R.string.plate96_analysis_rows),
            values = rowMeans,
            labels = (0 until safeRows).map(::plateAnalysisRowLabel)
        )
        Plate96MiniBars(
            label = stringResource(R.string.plate96_analysis_columns),
            values = columnMeans,
            labels = (1..safeColumns).map(Int::toString)
        )
    }
}

@Composable
private fun Plate96EdgeEffectCard(
    records: List<Plate96AnalysisRecord>,
    rows: Int,
    columns: Int
) {
    val lastRow = (rows - 1).coerceAtLeast(0)
    val lastColumn = (columns - 1).coerceAtLeast(0)
    val edge = records.filter { record ->
        record.well.rowIndex == 0 || record.well.rowIndex == lastRow ||
            record.well.columnIndex == 0 || record.well.columnIndex == lastColumn
    }
        .mapNotNull { it.concentration }.finiteMean()
    val inner = records.filter { record ->
        record.well.rowIndex in 1 until lastRow &&
            record.well.columnIndex in 1 until lastColumn
    }
        .mapNotNull { it.concentration }.finiteMean()
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_edge_effect),
        icon = Icons.Outlined.BorderOuter
    ) {
        Plate96MetricLine(
            label = stringResource(R.string.plate96_analysis_edge_wells),
            value = edge?.let(::formatArrayHeatmapValue)
                ?: stringResource(R.string.plate96_result_no_value)
        )
        Plate96MetricLine(
            label = stringResource(R.string.plate96_analysis_inner_wells),
            value = inner?.let(::formatArrayHeatmapValue)
                ?: stringResource(R.string.plate96_result_no_value)
        )
        val difference = if (edge != null && inner != null && inner != 0.0) {
            (edge - inner) / inner * 100.0
        } else null
        Plate96MetricLine(
            label = stringResource(R.string.plate96_analysis_edge_difference),
            value = difference?.let { "${formatArrayHeatmapValue(it)}%" }
                ?: stringResource(R.string.plate96_result_no_value)
        )
    }
}

@Composable
private fun Plate96RoleSummaryCard(snapshot: Plate96ResultSnapshot, analyte: ArrayAnalyteResult) {
    val wells = snapshot.wells.filter { it.site.analyteId == analyte.analyteId }
    val counts = wells.groupingBy { it.site.roleCode.orEmpty() }.eachCount()
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_role_summary),
        icon = Icons.Outlined.Science
    ) {
        val roleCounts: List<Pair<Int, Int>> = listOf(
            R.string.plate96_result_role_standard to (counts["STANDARD"] ?: 0),
            R.string.plate96_result_role_blank to (counts["BLANK"] ?: 0),
            R.string.plate96_result_role_negative to (counts["NEGATIVE_CONTROL"] ?: 0),
            R.string.plate96_result_role_positive to (counts["POSITIVE_CONTROL"] ?: 0)
        )
        roleCounts.forEach { (label, count) ->
            Plate96MetricLine(stringResource(label), count.toString())
        }
    }
}

@Composable
private fun Plate96SampleRow(record: Plate96AnalysisRecord, unit: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = record.well.wellLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = record.well.site.sampleSlot ?: record.well.site.defaultSampleSlot
                    ?: stringResource(R.string.plate96_result_unassigned),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = record.concentration?.let { "${formatArrayHeatmapValue(it)} $unit" }
                    ?: stringResource(R.string.plate96_result_no_value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun Plate96MiniBars(label: String, values: List<Double?>, labels: List<String>) {
    val maximum = values.mapNotNull { it?.takeIf(Double::isFinite) }.maxOrNull()?.takeIf { it > 0.0 }
        ?: 1.0
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { index, value ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(((value ?: 0.0) / maximum * 48.0).coerceIn(2.0, 48.0).dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.74f),
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )
                Text(
                    text = labels[index],
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun Plate96AnalysisCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(7.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

@Composable
private fun Plate96MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Plate96EmptyAnalysisText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private data class Plate96AnalysisRecord(val well: Plate96WellResult, val concentration: Double?)
private data class Plate96RepeatGroup(val name: String, val count: Int, val mean: Double, val cvPercent: Double?)

private fun List<Double>.finiteMean(): Double? = filter(Double::isFinite).takeIf(List<Double>::isNotEmpty)?.average()

private fun paddedRange(values: List<Double>): Pair<Double, Double> {
    val finite = values.filter(Double::isFinite)
    if (finite.isEmpty()) return 0.0 to 1.0
    val minimum = finite.minOrNull() ?: 0.0
    val maximum = finite.maxOrNull() ?: 1.0
    val span = (maximum - minimum).takeIf { it > 0.0 } ?: maxOf(kotlin.math.abs(maximum) * 0.2, 1.0)
    return (minimum - span * 0.08) to (maximum + span * 0.08)
}

/** 支持超过26行的自定义圆孔板，A～Z之后继续使用AA、AB。 */
private fun plateAnalysisRowLabel(rowIndex: Int): String {
    var value = rowIndex + 1
    val label = StringBuilder()
    while (value > 0) {
        value -= 1
        label.append(('A'.code + value % 26).toChar())
        value /= 26
    }
    return label.reverse().toString()
}
