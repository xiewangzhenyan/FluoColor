package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.utils.HeatmapColorUtil

/**
 * 阵列热力图的页面展示壳。
 *
 * 数值归一化、范围状态和 QC 编码仍由 [ArrayHeatmap] 中的纯规则构建；本文件只负责卡片、
 * 色带图例和统计摘要，避免结果页编排同时承担热力图的全部展示细节。
 */

/** 热力图卡片统一承载网格、科学色带和独立 QC 图例。 */
@Composable
internal fun ArrayHeatmapResultCard(
    title: String,
    subtitle: String,
    model: ArrayHeatmapModel,
    onScaleModeChange: (ArrayHeatmapConcentrationScaleMode) -> Unit,
    onSiteClick: (ArrayHeatmapCell) -> Unit
) {
    var showZoomHint by rememberSaveable(model.analyteId, model.rows, model.columns) {
        mutableStateOf(maxOf(model.rows, model.columns) >= 15)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_HEATMAP_CARD_TAG_PREFIX${model.analyteId ?: "overview"}"),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 色带来源是必要的科学语义，但压缩成一行状态标签，避免结果页出现说明段落。
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                shape = RoundedCornerShape(FluoRadius.badge)
            ) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            ArrayHeatmapScaleSelector(
                scale = model.scale,
                onScaleModeChange = onScaleModeChange
            )
            if (showZoomHint) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(FluoRadius.badge)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.array_heatmap_zoom_hint),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        IconButton(
                            onClick = { showZoomHint = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.array_heatmap_zoom_hint_dismiss),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            ArrayHeatmap(model = model, onSiteClick = onSiteClick)
            ArrayHeatmapScaleLegend(model.scale)
            ArrayHeatmapQcLegend(model)
        }
    }
}

/** 当前色带的最小值、最大值和来源必须与分析物切换同步。 */
@Composable
private fun ArrayHeatmapScaleLegend(scale: ArrayHeatmapScale) {
    val unitSuffix = scale.unit.takeIf(String::isNotBlank).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HeatmapColorUtil.getLegendColors(24).forEach { color ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(8.dp)
                        .background(color)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatArrayHeatmapValue(scale.minimum),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = scaleLegendTitle(scale, unitSuffix),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatArrayHeatmapValue(scale.maximum),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 复核、范围方向、低信号和无法计算使用独立弱符号，图例必须与实际单元编码一致。 */
@Composable
fun ArrayHeatmapQcLegend(model: ArrayHeatmapModel) {
    val denseArray = maxOf(model.rows, model.columns) >= 15
    val visibleKinds = buildList {
        // 大阵列不会逐格绘制轻度复核边框，因此图例也不能继续宣称存在这种视觉编码。
        if (!denseArray && model.cells.any { it.qc.warning }) add(ArrayHeatmapLegendKind.WARNING)
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED }) {
            add(ArrayHeatmapLegendKind.EXTRAPOLATED)
        }
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.BELOW_PROJECT_RANGE }) {
            add(ArrayHeatmapLegendKind.BELOW_PROJECT_RANGE)
        }
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE }) {
            add(ArrayHeatmapLegendKind.ABOVE_PROJECT_RANGE)
        }
        // 15×15 总览不逐格画低信号点，因此仅在小阵列图例中展示该符号。
        if (!denseArray && model.cells.any { it.qc.lowSignal }) add(ArrayHeatmapLegendKind.LOW_SIGNAL)
        if (model.cells.any { it.qc.failure }) add(ArrayHeatmapLegendKind.FAILURE)
    }
    if (visibleKinds.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // 范围状态使用三列紧凑布局，使“曲线外推 / 低于量程 / 高于量程”在常见手机宽度下同排展示。
        // 每个图例仍保留等宽区域，避免中英文长度差异导致方向标记错位或视觉节奏凌乱。
        visibleKinds.chunked(3).forEach { rowKinds ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowKinds.forEachIndexed { slotIndex, kind ->
                    // 三个等宽槽位分别靠左、居中、靠右，既保持均匀节奏，也让首尾图例与色带两端对齐。
                    val slotAlignment = when (slotIndex) {
                        0 -> Alignment.CenterStart
                        1 -> Alignment.Center
                        else -> Alignment.CenterEnd
                    }
                    when (kind) {
                        ArrayHeatmapLegendKind.WARNING -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_warning),
                            slotAlignment = slotAlignment
                        )
                        ArrayHeatmapLegendKind.EXTRAPOLATED -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_extrapolated),
                            slotAlignment = slotAlignment
                        )
                        ArrayHeatmapLegendKind.BELOW_PROJECT_RANGE -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_below_project),
                            slotAlignment = slotAlignment
                        )
                        ArrayHeatmapLegendKind.ABOVE_PROJECT_RANGE -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_above_project),
                            slotAlignment = slotAlignment
                        )
                        ArrayHeatmapLegendKind.LOW_SIGNAL -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_low_signal),
                            slotAlignment = slotAlignment
                        )
                        ArrayHeatmapLegendKind.FAILURE -> QcLegendItem(
                            kind = kind,
                            label = stringResource(R.string.array_heatmap_legend_failure),
                            slotAlignment = slotAlignment
                        )
                    }
                }
                // 不足三项时补齐剩余列，确保现有图例保持固定列宽，不会被拉伸成整行。
                repeat(3 - rowKinds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 图例只列出当前热力图真正绘制的视觉状态，避免用户看到不存在的警告符号。 */
private enum class ArrayHeatmapLegendKind {
    WARNING,
    EXTRAPOLATED,
    BELOW_PROJECT_RANGE,
    ABOVE_PROJECT_RANGE,
    LOW_SIGNAL,
    FAILURE
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QcLegendItem(
    kind: ArrayHeatmapLegendKind,
    label: String,
    slotAlignment: Alignment
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .testTag("$ARRAY_HEATMAP_LEGEND_TAG_PREFIX${kind.name}"),
        contentAlignment = slotAlignment
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArrayHeatmapLegendMarker(kind)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 图例使用中性灰还原弱状态标记，避免在图例中重新引入与浓度色带冲突的强调色。 */
@Composable
private fun ArrayHeatmapLegendMarker(kind: ArrayHeatmapLegendKind) {
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(modifier = Modifier.size(11.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            ArrayHeatmapLegendKind.WARNING -> Box(
                modifier = Modifier
                    .size(9.dp)
                    .border(1.dp, markerColor, RoundedCornerShape(3.dp))
            )
            ArrayHeatmapLegendKind.EXTRAPOLATED -> Canvas(modifier = Modifier.size(7.dp)) {
                val triangle = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(triangle, markerColor)
            }
            ArrayHeatmapLegendKind.BELOW_PROJECT_RANGE -> Canvas(
                modifier = Modifier.size(width = 8.dp, height = 5.dp)
            ) {
                // 向下三角与热力图单元保持同一语义，用户无需依赖图标在框内的位置判断。
                val triangle = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(triangle, markerColor)
            }
            ArrayHeatmapLegendKind.ABOVE_PROJECT_RANGE -> Canvas(
                modifier = Modifier.size(width = 8.dp, height = 5.dp)
            ) {
                // 向上三角与“高于项目量程”形成直观方向对应。
                val triangle = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(0f, size.height)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(triangle, markerColor)
            }
            ArrayHeatmapLegendKind.LOW_SIGNAL -> Box(
                modifier = Modifier
                    .size(3.dp)
                    .background(markerColor, RoundedCornerShape(50))
            )
            ArrayHeatmapLegendKind.FAILURE -> Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(Color(0xFFE1E5EA), RoundedCornerShape(3.dp))
            )
        }
    }
}

/**
 * 统计卡优先展示定量范围分布，避免把曲线外推误写成测量质量事故。
 *
 * 测量质量、低信号和未测位点仅在确实存在时追加一行说明，普通运行不再被大量
 * “建议复核”占据视觉焦点。
 */
@Composable
internal fun ArrayHeatmapStatistics(model: ArrayHeatmapModel) {
    val concentrationMode = model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(
                    if (concentrationMode) {
                        R.string.array_heatmap_quantitation_summary_title
                    } else {
                        R.string.array_heatmap_signal_summary_title
                    }
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (concentrationMode) {
                    HeatmapMetric(
                        value = model.quantifiedCount.toString(),
                        label = stringResource(R.string.array_heatmap_quantified)
                    )
                    HeatmapMetric(
                        value = model.estimatedCount.toString(),
                        label = stringResource(R.string.array_heatmap_estimated)
                    )
                    HeatmapMetric(
                        value = model.retestCount.toString(),
                        label = stringResource(R.string.array_heatmap_retest)
                    )
                } else {
                    HeatmapMetric(
                        value = model.calculatedCount.toString(),
                        label = stringResource(R.string.array_heatmap_calculated)
                    )
                    HeatmapMetric(
                        value = model.reliableCount.toString(),
                        label = stringResource(R.string.array_heatmap_reliable)
                    )
                    HeatmapMetric(
                        value = model.warningCount.toString(),
                        label = stringResource(R.string.array_heatmap_warning)
                    )
                    HeatmapMetric(
                        value = model.failureCount.toString(),
                        label = stringResource(R.string.array_heatmap_failure)
                    )
                }
            }
            if (model.warningCount > 0 || model.failureCount > 0) {
                Text(
                    text = stringResource(
                        R.string.array_heatmap_quality_summary_format,
                        model.reliableCount,
                        model.warningCount,
                        model.failureCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.lowSignalCount > 0) {
                Text(
                    text = stringResource(R.string.array_heatmap_low_signal_count, model.lowSignalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.missingCount > 0) {
                Text(
                    text = stringResource(R.string.array_heatmap_missing_count, model.missingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun HeatmapMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
