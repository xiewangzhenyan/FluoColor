package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapCell
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.arrayHeatmapBaseColor
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue

const val PLATE96_HEATMAP_TAG: String = "plate96_heatmap"
const val PLATE96_WELL_TAG_PREFIX: String = "plate96_result_well_"

enum class Plate96ResultDisplayMode {
    HEATMAP,
    VALUES
}

/**
 * 96孔板专属圆孔热力图。
 *
 * 主填充色始终表达浓度或信号；外推、低于量程和高于量程仅使用细弧与短箭头编码，
 * 不用整孔警告色覆盖科学数值。真正没有可解释结果时才显示浅灰圆孔。
 */
@Composable
fun Plate96Heatmap(
    model: ArrayHeatmapModel,
    displayMode: Plate96ResultDisplayMode,
    onWellClick: (ArrayHeatmapCell) -> Unit,
    selectedSiteIndex: Int? = null,
    modifier: Modifier = Modifier
) {
    require(model.rows > 0 && model.columns > 0) {
        "圆孔板热力图行列必须为正数"
    }
    val cellByIndex = model.cells.associateBy(ArrayHeatmapCell::siteIndex)
    val axisFontSize = when {
        model.columns >= 16 -> 7.sp
        model.columns >= 12 -> 9.sp
        else -> 10.sp
    }
    val rowLabelWidth = if (model.rows > 26) 24.dp else 18.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PLATE96_HEATMAP_TAG),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(rowLabelWidth))
            repeat(model.columns) { column ->
                Text(
                    text = (column + 1).toString(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = axisFontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
        repeat(model.rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plateHeatmapRowLabel(row),
                    modifier = Modifier.width(rowLabelWidth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                repeat(model.columns) { column ->
                    val index = row * model.columns + column
                    val cell = requireNotNull(cellByIndex[index]) { "圆孔板热力图缺少孔位$index" }
                    Plate96HeatmapWell(
                        cell = cell,
                        model = model,
                        displayMode = displayMode,
                        selected = cell.siteIndex == selectedSiteIndex,
                        onClick = { onWellClick(cell) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 与孔板分析页使用相同的Excel式行标签，支持自定义圆孔板超过26行。 */
private fun plateHeatmapRowLabel(rowIndex: Int): String {
    var value = rowIndex + 1
    val label = StringBuilder()
    while (value > 0) {
        value -= 1
        label.append(('A'.code + value % 26).toChar())
        value /= 26
    }
    return label.reverse().toString()
}

@Composable
private fun Plate96HeatmapWell(
    cell: ArrayHeatmapCell,
    model: ArrayHeatmapModel,
    displayMode: Plate96ResultDisplayMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    // 单侧界限已经在绘图模型中投影到当前色带端点；箭头和数值模式的 < / > 负责提示
    // 该颜色只表达量程方向，不代表保存了精确浓度。真正不可用的孔仍由 null 值显示为灰色。
    val baseColor = arrayHeatmapBaseColor(cell.normalizedValue, model.scale.mode)
    val textColor = if (baseColor.luminance() > 0.52f) Color(0xFF172033) else Color.White
    val rangeMarkerColor = if (baseColor.luminance() > 0.52f) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
    } else {
        Color.White.copy(alpha = 0.86f)
    }
    val failureMarkerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
    val selectedRingColor = MaterialTheme.colorScheme.primary
    val unavailableText = stringResource(R.string.plate96_result_no_value_short)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(baseColor)
            .clickable(onClick = onClick)
            .testTag("$PLATE96_WELL_TAG_PREFIX${cell.siteIndex}"),
        contentAlignment = Alignment.Center
    ) {
        if (displayMode == Plate96ResultDisplayMode.VALUES) {
            Text(
                text = when (cell.valueState) {
                    ArrayHeatmapValueState.BELOW_PROJECT_RANGE ->
                        stringResource(R.string.array_heatmap_upper_bound_symbol)
                    ArrayHeatmapValueState.ABOVE_PROJECT_RANGE ->
                        stringResource(R.string.array_heatmap_lower_bound_symbol)
                    ArrayHeatmapValueState.QUANTIFIED,
                    ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED ->
                        cell.displayValue?.let(::formatArrayHeatmapValue) ?: unavailableText
                    ArrayHeatmapValueState.UNAVAILABLE -> unavailableText
                },
                color = textColor,
                fontSize = 6.5.sp,
                lineHeight = 7.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 1.dp)
            )
        }

        // 状态标记刻意使用低占用的圆周符号，既能识别又不会破坏96孔整体色带。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = (size.minDimension * 0.07f).coerceAtLeast(1f)
            when (cell.valueState) {
                ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> drawArc(
                    color = rangeMarkerColor,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> if (
                    displayMode == Plate96ResultDisplayMode.HEATMAP
                ) {
                    drawRangeArrow(
                        upward = false,
                        color = rangeMarkerColor,
                        strokeWidth = strokeWidth
                    )
                }
                ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> if (
                    displayMode == Plate96ResultDisplayMode.HEATMAP
                ) {
                    drawRangeArrow(
                        upward = true,
                        color = rangeMarkerColor,
                        strokeWidth = strokeWidth
                    )
                }
                ArrayHeatmapValueState.QUANTIFIED,
                ArrayHeatmapValueState.UNAVAILABLE -> Unit
            }
            if (cell.qc.failure) {
                drawCircle(
                    color = failureMarkerColor,
                    radius = size.minDimension * 0.10f,
                    center = Offset(size.width * 0.76f, size.height * 0.24f)
                )
            }
            // 选中态只增加一圈清晰但克制的描边，孔内颜色仍完整表达浓度。
            if (selected) {
                drawCircle(
                    color = selectedRingColor,
                    radius = size.minDimension * 0.43f,
                    style = Stroke(width = (size.minDimension * 0.075f).coerceAtLeast(1.5f))
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRangeArrow(
    upward: Boolean,
    color: Color,
    strokeWidth: Float
) {
    val centerX = size.width * 0.5f
    val upperY = size.height * 0.16f
    val lowerY = size.height * 0.35f
    val tipY = if (upward) upperY else lowerY
    val tailY = if (upward) lowerY else upperY
    drawLine(color, Offset(centerX, tailY), Offset(centerX, tipY), strokeWidth, StrokeCap.Round)
    val direction = if (upward) 1f else -1f
    val path = Path().apply {
        moveTo(centerX, tipY)
        lineTo(centerX - size.width * 0.10f, tipY + direction * size.height * 0.09f)
        moveTo(centerX, tipY)
        lineTo(centerX + size.width * 0.10f, tipY + direction * size.height * 0.09f)
    }
    drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round))
}
