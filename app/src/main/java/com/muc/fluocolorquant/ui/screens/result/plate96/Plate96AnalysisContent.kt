package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import com.muc.fluocolorquant.ui.components.LatexAlignment
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.buildAnalyteHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val PLATE96_ANALYSIS_TAG: String = "plate96_analysis"
const val PLATE96_SAMPLE_TABLE_TAG: String = "plate96_sample_table"
const val PLATE96_CURVE_CARD_TAG: String = "plate96_curve_card"
const val PLATE96_FIT_METRICS_TAG: String = "plate96_fit_metrics"
const val PLATE96_REPEATABILITY_TAG: String = "plate96_repeatability"
const val PLATE96_DISTRIBUTION_TAG: String = "plate96_distribution"

/** 孔板分布只在同一张卡中切换，避免把行、列和边缘拆成三个重复模块。 */
private enum class Plate96DistributionMode {
    ROW,
    COLUMN,
    EDGE
}

/** 样本表筛选仅改变显示，不改写运行快照。 */
private enum class Plate96SampleFilter {
    ALL,
    WITHIN_RANGE,
    NEEDS_ATTENTION
}

/**
 * 96孔板分析页。
 *
 * 页面严格按照科研阅读顺序组织：先复核标准曲线，再查看拟合指标、重复孔、孔板空间分布，
 * 最后核对样本明细。所有内容只读取冻结结果，不会在结果页重新拟合或重新计算浓度。
 */
@Composable
fun Plate96AnalysisContent(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult
) {
    val heatmapModel = remember(snapshot.runId, analyte.analyteId) {
        buildAnalyteHeatmapModel(snapshot.arraySnapshot, analyte)
    }
    val stateBySite = remember(heatmapModel) {
        heatmapModel.cells.associate { it.siteIndex to it.valueState }
    }
    val records = remember(snapshot.runId, analyte.analyteId, stateBySite) {
        snapshot.wells.mapNotNull { well ->
            well.site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
                ?.let { measurement ->
                    Plate96AnalysisRecord(
                        well = well,
                        measurement = measurement,
                        valueState = stateBySite[well.wellIndex] ?: ArrayHeatmapValueState.UNAVAILABLE
                    )
                }
        }
    }

    LazyColumn(
        modifier = Modifier.testTag(PLATE96_ANALYSIS_TAG),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Plate96CurveCard(analyte) }
        item { Plate96FitMetrics(analyte) }
        item { Plate96RepeatabilityCard(records, analyte.concentrationUnit) }
        item {
            Plate96DistributionCard(
                records = records,
                rows = snapshot.arraySnapshot.rows,
                columns = snapshot.arraySnapshot.columns,
                unit = analyte.concentrationUnit
            )
        }
        item {
            Plate96SampleTableCard(
                snapshot = snapshot,
                records = records.filter { it.well.site.roleCode == "SAMPLE" },
                analyte = analyte
            )
        }
    }
}

/** 标准曲线卡保留曲线、标准点和LaTeX公式，拟合指标移到独立三卡区域。 */
@Composable
private fun Plate96CurveCard(analyte: ArrayAnalyteResult) {
    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_curve),
        icon = Icons.AutoMirrored.Outlined.ShowChart,
        modifier = Modifier.testTag(PLATE96_CURVE_CARD_TAG),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
        trailing = {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
            ) {
                Text(
                    text = analyte.name,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    ) {
        val function = FittingFunction.fromIdentifier(analyte.fittingFunction.orEmpty())
        val points = analyte.calibrationPoints.map { it.concentration to it.signalValue }
        if (function == null || points.size < 2 || analyte.fittingParameters.isEmpty()) {
            Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_curve_unavailable))
            return@Plate96AnalysisCard
        }

        val formula = FittingEngine.formatParametersToLatex(function, analyte.fittingParameters)
        val xRange = paddedRange(points.map(Pair<Double, Double>::first))
        val yRange = paddedRange(points.map(Pair<Double, Double>::second))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.control),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CurveChart(
                    data = ChartData(
                        xRange = xRange,
                        yRange = yRange,
                        standardPoints = points,
                        fittedCurve = { x -> FittingEngine.calculate(function, analyte.fittingParameters, x) },
                        formula = formula,
                        xAxisLabel = stringResource(R.string.chart_concentration),
                        yAxisLabel = stringResource(R.string.chart_pixel_value),
                        chartType = "STANDARD_CURVE"
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    interactive = true
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    LatexView(
                        latex = formula,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        alignment = LatexAlignment.CENTER
                    )
                }
            }
        }
    }
}

/** 原型中的R²、RMSE、MAE使用三个独立卡片，首屏即可横向比较。 */
@Composable
private fun Plate96FitMetrics(analyte: ArrayAnalyteResult) {
    val metrics = analyte.validationMetrics
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PLATE96_FIT_METRICS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Plate96FitMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.standard_curve_metric_r_squared),
            value = (metrics["R2"] ?: metrics["R²"])?.let { formatFixedMetric(it, 4) }
        )
        Plate96FitMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.standard_curve_metric_rmse),
            value = metrics["RMSE"]?.let { formatFixedMetric(it, 2) }
        )
        Plate96FitMetricCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.standard_curve_metric_mae),
            value = metrics["MAE"]?.let { formatFixedMetric(it, 2) }
        )
    }
}

@Composable
private fun Plate96FitMetricCard(modifier: Modifier, label: String, value: String?) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value ?: stringResource(R.string.plate96_result_no_value_short),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/** 重复孔同时展示范围、均值和CV，比单行文本更容易发现离群重复孔。 */
@Composable
private fun Plate96RepeatabilityCard(records: List<Plate96AnalysisRecord>, unit: String) {
    val groups = remember(records) {
        records.mapNotNull { record ->
            val value = record.concentration?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val group = record.well.site.repeatGroup
                ?.takeIf(String::isNotBlank)
                ?: record.well.site.sampleSlot?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            group to value
        }.groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
            .map { (name, values) -> Plate96RepeatGroup.create(name, values) }
            .sortedBy(Plate96RepeatGroup::name)
    }
    val allValues = groups.flatMap(Plate96RepeatGroup::values)
    val range = paddedRange(allValues)

    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_repeatability),
        icon = Icons.Outlined.Repeat,
        modifier = Modifier.testTag(PLATE96_REPEATABILITY_TAG),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
    ) {
        if (groups.isEmpty()) {
            Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_no_repeats))
        } else {
            groups.take(8).forEachIndexed { index, group ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                }
                Plate96RepeatRangeRow(group = group, range = range, unit = unit)
            }
        }
    }
}

@Composable
private fun Plate96RepeatRangeRow(
    group: Plate96RepeatGroup,
    range: Pair<Double, Double>,
    unit: String
) {
    val span = (range.second - range.first).takeIf { it > 0.0 } ?: 1.0
    val start = ((group.minimum - range.first) / span).coerceIn(0.0, 1.0).toFloat()
    val end = ((group.maximum - range.first) / span).coerceIn(0.0, 1.0).toFloat()
    val mean = ((group.mean - range.first) / span).coerceIn(0.0, 1.0).toFloat()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = group.name,
            modifier = Modifier.width(34.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(22.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            )
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * start)
                    .width((maxWidth * (end - start)).coerceAtLeast(6.dp))
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.42f), RoundedCornerShape(50))
            )
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth * mean - 5.dp).coerceAtLeast(0.dp))
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Column(
            modifier = Modifier.width(78.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = stringResource(
                    R.string.plate96_analysis_mean_short,
                    formatArrayHeatmapValue(group.mean),
                    unit
                ),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            Text(
                text = stringResource(
                    R.string.plate96_analysis_cv_short,
                    group.cvPercent?.let(::formatArrayHeatmapValue)
                        ?: stringResource(R.string.plate96_result_no_value_short)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if ((group.cvPercent ?: 0.0) > 15.0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/** 行、列、边缘三种空间统计共用同一张分布卡，切换时即时重绘冻结浓度的均值。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Plate96DistributionCard(
    records: List<Plate96AnalysisRecord>,
    rows: Int,
    columns: Int,
    unit: String
) {
    var mode by rememberSaveable { mutableStateOf(Plate96DistributionMode.ROW) }
    val safeRows = rows.coerceAtLeast(1)
    val safeColumns = columns.coerceAtLeast(1)
    val rowValues = (0 until safeRows).map { row ->
        plateAnalysisRowLabel(row) to records.filter { it.well.rowIndex == row }
            .mapNotNull(Plate96AnalysisRecord::concentration).finiteMean()
    }
    val columnValues = (0 until safeColumns).map { column ->
        (column + 1).toString() to records.filter { it.well.columnIndex == column }
            .mapNotNull(Plate96AnalysisRecord::concentration).finiteMean()
    }
    val lastRow = (safeRows - 1).coerceAtLeast(0)
    val lastColumn = (safeColumns - 1).coerceAtLeast(0)
    val edgeMean = records.filter { record ->
        record.well.rowIndex == 0 || record.well.rowIndex == lastRow ||
            record.well.columnIndex == 0 || record.well.columnIndex == lastColumn
    }.mapNotNull(Plate96AnalysisRecord::concentration).finiteMean()
    val innerMean = records.filter { record ->
        record.well.rowIndex in 1 until lastRow &&
            record.well.columnIndex in 1 until lastColumn
    }.mapNotNull(Plate96AnalysisRecord::concentration).finiteMean()
    val edgeValues = listOf(
        stringResource(R.string.plate96_analysis_edge_wells_short) to edgeMean,
        stringResource(R.string.plate96_analysis_inner_wells_short) to innerMean
    )
    val values = when (mode) {
        Plate96DistributionMode.ROW -> rowValues
        Plate96DistributionMode.COLUMN -> columnValues
        Plate96DistributionMode.EDGE -> edgeValues
    }

    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_distribution),
        icon = Icons.Outlined.GridView,
        modifier = Modifier.testTag(PLATE96_DISTRIBUTION_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Plate96DistributionMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { mode = option },
                    shape = SegmentedButtonDefaults.itemShape(index, Plate96DistributionMode.entries.size),
                    label = {
                        Text(
                            text = stringResource(
                                when (option) {
                                    Plate96DistributionMode.ROW -> R.string.plate96_analysis_rows_short
                                    Plate96DistributionMode.COLUMN -> R.string.plate96_analysis_columns_short
                                    Plate96DistributionMode.EDGE -> R.string.plate96_analysis_edge_short
                                }
                            ),
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Plate96DistributionBars(values = values, unit = unit)
        if (mode == Plate96DistributionMode.EDGE) {
            val difference = if (edgeMean != null && innerMean != null && innerMean != 0.0) {
                abs((edgeMean - innerMean) / innerMean * 100.0)
            } else {
                null
            }
            Text(
                text = difference?.let {
                    stringResource(R.string.plate96_analysis_edge_summary, formatArrayHeatmapValue(it))
                } ?: stringResource(R.string.plate96_analysis_edge_summary_unavailable),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Plate96DistributionBars(values: List<Pair<String, Double?>>, unit: String) {
    val maximum = values.mapNotNull { it.second?.takeIf(Double::isFinite) }
        .maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEach { (label, value) ->
            val ratio = ((value ?: 0.0) / maximum).coerceIn(0.0, 1.0)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                if (values.size <= 4) {
                    Text(
                        text = value?.let { "${formatArrayHeatmapValue(it)} $unit" }
                            ?: stringResource(R.string.plate96_result_no_value_short),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .width(if (values.size <= 4) 34.dp else 14.dp)
                        .height((ratio * 82.0).coerceIn(3.0, 82.0).dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                            RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                        )
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

/** 样本明细保留真实圆孔缩略图，并提供可用的搜索和范围筛选。 */
@Composable
private fun Plate96SampleTableCard(
    snapshot: Plate96ResultSnapshot,
    records: List<Plate96AnalysisRecord>,
    analyte: ArrayAnalyteResult
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var expandedWellIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val filter = Plate96SampleFilter.entries[filterIndex]
    val filtered = records.filter { record ->
        val sample = record.well.site.sampleSlot ?: record.well.site.defaultSampleSlot.orEmpty()
        val matchesQuery = query.isBlank() ||
            record.well.wellLabel.contains(query, ignoreCase = true) ||
            sample.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            Plate96SampleFilter.ALL -> true
            Plate96SampleFilter.WITHIN_RANGE -> record.valueState == ArrayHeatmapValueState.QUANTIFIED
            Plate96SampleFilter.NEEDS_ATTENTION ->
                record.valueState != ArrayHeatmapValueState.QUANTIFIED
        }
        matchesQuery && matchesFilter
    }

    Plate96AnalysisCard(
        title = stringResource(R.string.plate96_analysis_sample_table),
        icon = Icons.Outlined.TableRows,
        modifier = Modifier.testTag(PLATE96_SAMPLE_TABLE_TAG),
        containerColor = MaterialTheme.colorScheme.surface,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                IconButton(onClick = { searchVisible = !searchVisible }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = {
                        filterIndex = (filterIndex + 1) % Plate96SampleFilter.entries.size
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = stringResource(R.string.filter),
                        modifier = Modifier.size(19.dp),
                        tint = if (filter == Plate96SampleFilter.ALL) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    ) {
        if (searchVisible) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.plate96_analysis_search_samples)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
            )
        }
        Text(
            text = stringResource(
                when (filter) {
                    Plate96SampleFilter.ALL -> R.string.plate96_analysis_filter_all
                    Plate96SampleFilter.WITHIN_RANGE -> R.string.plate96_analysis_filter_within
                    Plate96SampleFilter.NEEDS_ATTENTION -> R.string.plate96_analysis_filter_attention
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.End)
        )
        if (filtered.isEmpty()) {
            Plate96EmptyAnalysisText(stringResource(R.string.plate96_analysis_no_samples))
        } else {
            Plate96SampleTableHeader()
            filtered.forEachIndexed { index, record ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                }
                Plate96SampleTableRow(
                    snapshot = snapshot,
                    record = record,
                    analyte = analyte,
                    expanded = expandedWellIndex == record.well.wellIndex,
                    onClick = {
                        expandedWellIndex = if (expandedWellIndex == record.well.wellIndex) {
                            null
                        } else {
                            record.well.wellIndex
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun Plate96SampleTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.plate96_analysis_column_well_sample),
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.plate96_analysis_column_concentration),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.plate96_analysis_column_status),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun Plate96SampleTableRow(
    snapshot: Plate96ResultSnapshot,
    record: Plate96AnalysisRecord,
    analyte: ArrayAnalyteResult,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1.5f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Plate96WellThumbnail(snapshot = snapshot, well = record.well, size = 36.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.well.wellLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = record.well.site.sampleSlot ?: record.well.site.defaultSampleSlot
                            ?: stringResource(R.string.plate96_result_default_sample, record.well.wellLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = concentrationText(record.measurement, analyte, record.valueState),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                modifier = Modifier.width(72.dp),
                shape = RoundedCornerShape(50),
                color = plate96StatusColor(record.valueState)
            ) {
                Text(
                    text = plate96RangeStatus(record.valueState),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.badge),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.plate96_analysis_signal_detail,
                            record.measurement.primaryFeatureValue?.let(::formatArrayHeatmapValue)
                                ?: stringResource(R.string.plate96_result_no_value_short)
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = stringResource(
                            R.string.plate96_analysis_role_detail,
                            plate96RoleLabel(record.well.site.roleCode)
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun plate96StatusColor(state: ArrayHeatmapValueState): Color = when (state) {
    ArrayHeatmapValueState.QUANTIFIED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.74f)
    ArrayHeatmapValueState.BELOW_PROJECT_RANGE,
    ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.74f)
    ArrayHeatmapValueState.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceContainerHighest
}

/** 分析页统一卡片头，保证四个区块具有一致的图标、圆角和间距。 */
@Composable
private fun Plate96AnalysisCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(7.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun Plate96EmptyAnalysisText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private data class Plate96AnalysisRecord(
    val well: Plate96WellResult,
    val measurement: ArraySiteMeasurementResult,
    val valueState: ArrayHeatmapValueState
) {
    val concentration: Double? get() = measurement.concentrationValue
}

private data class Plate96RepeatGroup(
    val name: String,
    val values: List<Double>,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val cvPercent: Double?
) {
    companion object {
        /** 使用样本标准差计算CV，和结果导出中的重复孔统计保持一致。 */
        fun create(name: String, values: List<Double>): Plate96RepeatGroup {
            val mean = values.average()
            val standardDeviation = sqrt(
                values.sumOf { value -> (value - mean) * (value - mean) } /
                    (values.size - 1).coerceAtLeast(1)
            )
            return Plate96RepeatGroup(
                name = name,
                values = values,
                minimum = values.minOrNull() ?: mean,
                maximum = values.maxOrNull() ?: mean,
                mean = mean,
                cvPercent = if (mean == 0.0) null else standardDeviation / abs(mean) * 100.0
            )
        }
    }
}

private fun List<Double>.finiteMean(): Double? =
    filter(Double::isFinite).takeIf(List<Double>::isNotEmpty)?.average()

private fun paddedRange(values: List<Double>): Pair<Double, Double> {
    val finite = values.filter(Double::isFinite)
    if (finite.isEmpty()) return 0.0 to 1.0
    val minimum = finite.minOrNull() ?: 0.0
    val maximum = finite.maxOrNull() ?: 1.0
    val span = (maximum - minimum).takeIf { it > 0.0 }
        ?: maxOf(abs(maximum) * 0.2, 1.0)
    return (minimum - span * 0.08) to (maximum + span * 0.08)
}

/** 拟合指标使用固定小数位，避免R²=0.998被通用短格式误显示成1.00。 */
private fun formatFixedMetric(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

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
