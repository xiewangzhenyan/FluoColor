package com.muc.fluocolorquant.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.muc.fluocolorquant.data.enums.FittingFunction
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import com.muc.fluocolorquant.R
import androidx.compose.ui.res.stringResource

/**
 * 曲线图表组件
 *
 * @param data 图表数据
 * @param modifier 修饰符
 */
@Composable
fun CurveChart(
    data: ChartData,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    val textMeasurer = rememberTextMeasurer()
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    var curvePointPosition by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    
    // 获取当前密度，用于dp到px的转换
    val density = LocalDensity.current

    // 图表颜色默认跟随 Material 3 主题；只有调用方显式传入颜色时才覆盖。
    val chartBackgroundColor = data.backgroundColor.orThemeColor(
        MaterialTheme.colorScheme.surfaceContainerLowest
    )
    val curveColor = data.curveColor.orThemeColor(MaterialTheme.colorScheme.primary)
    val pointColor = data.pointColor.orThemeColor(MaterialTheme.colorScheme.tertiary)
    val gridColor = data.gridColor.orThemeColor(
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val plotBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    val crosshairColor = MaterialTheme.colorScheme.secondary
    val selectedPointColor = MaterialTheme.colorScheme.error
    val pointOutlineColor = MaterialTheme.colorScheme.surface
    val meanLineColor = MaterialTheme.colorScheme.tertiary
    val limitLineColor = MaterialTheme.colorScheme.error
    val meanLabel = stringResource(id = R.string.chart_mean)
    val upperLimitLabel = stringResource(id = R.string.chart_upper_limit)
    val lowerLimitLabel = stringResource(id = R.string.chart_lower_limit)

    // 回归图允许调用方明确声明坐标语义。旧结果页未传标签时仍使用“预测值/真实值”兜底，
    // 新96孔板验证页则可按科研惯例显示“参考浓度/预测浓度”，避免通用组件篡改数据含义。
    val xAxisLabelText = when (data.chartType) {
        "BLAND_ALTMAN" -> stringResource(id = R.string.chart_mean)
        "REGRESSION" -> data.xAxisLabel.ifEmpty { stringResource(id = R.string.chart_predicted) }
        else -> data.xAxisLabel.ifEmpty { stringResource(id = R.string.chart_concentration) }
    }

    val yAxisLabelText = when (data.chartType) {
        "BLAND_ALTMAN" -> stringResource(id = R.string.chart_difference)
        "REGRESSION" -> data.yAxisLabel.ifEmpty { stringResource(id = R.string.chart_actual) }
        else -> data.yAxisLabel.ifEmpty { stringResource(id = R.string.chart_pixel_value) }
    }

    Column(
        modifier = modifier
            .heightIn(min = 240.dp)
            .background(chartBackgroundColor, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, plotBorderColor), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        // 调用方未提供标题时不保留空白行，让有限高度优先服务于数据区。
        if (data.title.isNotBlank()) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            )
        }

        if (data.chartType == "STANDARD_CURVE" &&
            data.fittedCurve != null &&
            !data.visibleScatterPoints().isNullOrEmpty()
        ) {
            CurveChartLegend(
                curveColor = curveColor,
                pointColor = pointColor,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
            )
        }

        // 图表Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it.toSize() }
                    .pointerInput(data, interactive, density) {
                        if (!interactive) return@pointerInput
                        detectTapGestures { offset ->
                            // 点击事件处理，查找最近的数据点或曲线点
                            findNearestPointToPosition(data, offset, size.toSize(), density)?.let { (index, pointOffset) ->
                                selectedPointIndex = index
                                curvePointPosition = pointOffset
                            } ?: run {
                                // 如果没有找到附近的数据点，则计算曲线上的点
                                findPointOnCurve(data, offset, size.toSize(), density)?.let { curveOffset ->
                                    selectedPointIndex = null
                                    curvePointPosition = curveOffset
                                } ?: run {
                                    selectedPointIndex = null
                                    curvePointPosition = null
                                }
                            }
                        }
                    }
                    .pointerInput(data, interactive, density) {
                        if (!interactive) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                // 拖动开始时查找最近的点
                                findNearestPointToPosition(data, offset, size.toSize(), density)?.let { (index, pointOffset) ->
                                    selectedPointIndex = index
                                    curvePointPosition = pointOffset
                                } ?: run {
                                    // 如果没有找到附近的数据点，则计算曲线上的点
                                    findPointOnCurve(data, offset, size.toSize(), density)?.let { curveOffset ->
                                        selectedPointIndex = null
                                        curvePointPosition = curveOffset
                                    }
                                }
                            },
                            onDragEnd = {},
                            onDrag = { change, _ ->
                                // 先查找最近的数据点尝试吸附
                                findNearestPointToPosition(data, change.position, size.toSize(), density)?.let { (index, pointOffset) ->
                                    selectedPointIndex = index
                                    curvePointPosition = pointOffset
                                } ?: run {
                                    // 如果没有找到附近的数据点，则计算曲线上的点
                                    selectedPointIndex = null
                                    findPointOnCurve(data, change.position, size.toSize(), density)?.let { curveOffset ->
                                        curvePointPosition = curveOffset
                                    }
                                }
                            }
                        )
                    }
            ) {
                val chartHeight = size.height

                // 使用 dp 计算稳定的移动端绘图区，不再按裸像素写死，也不再额外缩小 10%。
                val plotGeometry = calculatePlotGeometry(size, density)
                val graphWidth = plotGeometry.width
                val graphHeight = plotGeometry.height
                val graphStartX = plotGeometry.startX
                val graphStartY = plotGeometry.startY
                val graphEndX = plotGeometry.endX
                val graphEndY = plotGeometry.endY

                // 获取数据范围
                val xMin = data.xRange.first
                val xMax = data.xRange.second
                val yMin = data.yRange.first
                val yMax = data.yRange.second
                val xDiff = xMax - xMin
                val yDiff = yMax - yMin

                val yTickStyle = TextStyle(fontSize = 10.sp, color = tickColor)

                // 绘制坐标轴
                drawLine(
                    color = axisColor,
                    start = Offset(graphStartX, graphEndY),
                    end = Offset(graphEndX, graphEndY),
                    strokeWidth = with(density) { 1.dp.toPx() }
                )
                drawLine(
                    color = axisColor,
                    start = Offset(graphStartX, graphEndY),
                    end = Offset(graphStartX, graphStartY),
                    strokeWidth = with(density) { 1.dp.toPx() }
                )

                // 绘制网格线
                if (data.showGrid) {
                    val numXGridLines = 5
                    val numYGridLines = 5

                    // 横向网格线
                    for (i in 0..numYGridLines) {
                        // 计算精确的y值，确保网格线与刻度值严格对应
                        val yValue = yMin + (yDiff * i / numYGridLines)

                        // 计算y值对应的屏幕坐标
                        val yRatio = if (yDiff != 0.0) (yValue - yMin) / yDiff else 0.0
                        val y = graphEndY - (yRatio * graphHeight).toFloat()

                        drawLine(
                            color = gridColor,
                            start = Offset(graphStartX, y),
                            end = Offset(graphEndX, y),
                            strokeWidth = with(density) { 0.5.dp.toPx() }
                        )

                        // Y轴刻度
                        val yText = formatAxisTick(yValue, yDiff)

                        drawText(
                            textMeasurer = textMeasurer,
                            text = yText,
                            style = yTickStyle,
                            topLeft = Offset(
                                x = graphStartX - 8f - textMeasurer.measure(yText, yTickStyle).size.width,
                                y = y - textMeasurer.measure(yText, yTickStyle).size.height / 2
                            )
                        )
                    }

                    // 纵向网格线
                    for (i in 0..numXGridLines) {
                        // 计算精确的x值，确保网格线与刻度值严格对应
                        val xValue = xMin + (xDiff * i / numXGridLines)

                        // 计算x值对应的屏幕坐标
                        val xRatio = if (xDiff != 0.0) (xValue - xMin) / xDiff else 0.0
                        val x = graphStartX + (xRatio * graphWidth).toFloat()

                        drawLine(
                            color = gridColor,
                            start = Offset(x, graphStartY),
                            end = Offset(x, graphEndY),
                            strokeWidth = with(density) { 0.5.dp.toPx() }
                        )

                        // X轴刻度
                        val xText = formatAxisTick(xValue, xDiff)

                        // 使用TextMeasurer测量文本宽度，以便居中对齐
                        val xTextStyle = TextStyle(fontSize = 10.sp, color = tickColor)
                        val xTextWidth = textMeasurer.measure(xText, xTextStyle).size.width

                        drawText(
                            textMeasurer = textMeasurer,
                            text = xText,
                            style = xTextStyle,
                            topLeft = Offset(
                                x = x - xTextWidth / 2,
                                y = graphEndY + 10f // 增加与X轴的距离
                            )
                        )
                    }
                }

                // 绘制特殊类型的图表元素
                when (data.chartType) {
                    "BLAND_ALTMAN" -> {
                        // 绘制均值线、上限线和下限线
                        val meanLine = data.additionalLines["mean"] ?: emptyList()
                        val upperLine = data.additionalLines["upperLimit"] ?: emptyList()
                        val lowerLine = data.additionalLines["lowerLimit"] ?: emptyList()
                        
                        // 均值线使用主题强调色，兼容深色模式。
                        if (meanLine.size >= 2) {
                            // 确保绘制水平线
                            val y = graphEndY - ((meanLine[0].second - yMin) / yDiff * graphHeight).toFloat()
                            
                            drawLine(
                                color = meanLineColor,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = with(density) { 1.5.dp.toPx() }
                            )
                            
                            // 绘制均值标签
                            drawText(
                                textMeasurer = textMeasurer,
                                text = meanLabel,
                                style = TextStyle(color = meanLineColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(
                                    x = graphEndX - 40f,
                                    y = y - 15f
                                )
                            )
                        }
                        
                        // 上下限使用错误色虚线，和均值线形成明确区分。
                        if (upperLine.size >= 2) {
                            // 确保绘制水平线
                            val y = graphEndY - ((upperLine[0].second - yMin) / yDiff * graphHeight).toFloat()
                            
                            drawLine(
                                color = limitLineColor,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = with(density) { 1.25.dp.toPx() },
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )

                            // 绘制上限标签
                            drawText(
                                textMeasurer = textMeasurer,
                                text = upperLimitLabel,
                                style = TextStyle(color = limitLineColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(
                                    x = graphEndX - 40f,
                                    y = y - 15f
                                )
                            )
                        }
                        
                        // 下限线 - 红色，虚线
                        if (lowerLine.size >= 2) {
                            // 确保绘制水平线
                            val y = graphEndY - ((lowerLine[0].second - yMin) / yDiff * graphHeight).toFloat()
                            
                            drawLine(
                                color = limitLineColor,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = with(density) { 1.25.dp.toPx() },
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )

                            // 绘制下限标签
                            drawText(
                                textMeasurer = textMeasurer,
                                text = lowerLimitLabel,
                                style = TextStyle(color = limitLineColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(
                                    x = graphEndX - 40f,
                                    y = y + 5f
                                )
                            )
                        }
                    }
                    "REGRESSION" -> {
                        // 为回归分析图表绘制辅助元素（理想线和回归线）
                        
                        // 绘制理想线 y=x (浅灰色)
                        val idealPoints = data.standardPoints
                        if (idealPoints.size >= 2) {
                            val path = Path()
                            var firstPoint = true
                            
                            for (point in idealPoints) {
                                val xRatio = if (xDiff != 0.0) (point.first - xMin) / xDiff else 0.0
                                val yRatio = if (yDiff != 0.0) (point.second - yMin) / yDiff else 0.0
                                
                                val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                                val pointY = graphEndY - (yRatio * graphHeight).toFloat()
                                
                                if (firstPoint) {
                                    path.moveTo(pointX, pointY)
                                    firstPoint = false
                                } else {
                                    path.lineTo(pointX, pointY)
                                }
                            }
                            
                            drawPath(
                                path = path,
                                color = Color.Gray.copy(alpha = 0.5f),
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                    else -> {
                        // 默认的图表绘制行为，无需特殊处理
                    }
                }

                // 绘制拟合曲线
                data.fittedCurve?.let { curve ->
                    val path = Path()
                    val pointCount = 200 // 增加点数，提高曲线精度
                    val xStep = if (pointCount > 0) xDiff / pointCount else 0.0

                    var firstPoint = true

                    for (i in 0..pointCount) {
                        val x = xMin + i * xStep
                        val y = try {
                            curve(x)
                        } catch (e: Exception) {
                            continue
                        }

                        // 忽略范围外的点或无效值
                        if (y.isNaN() || y.isInfinite() || y < yMin || y > yMax) {
                            continue
                        }

                        // 计算屏幕坐标，使用安全的除法
                        val xRatio = if (xDiff != 0.0) (x - xMin) / xDiff else 0.0
                        val yRatio = if (yDiff != 0.0) (y - yMin) / yDiff else 0.0

                        val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                        val pointY = graphEndY - (yRatio * graphHeight).toFloat()

                        if (firstPoint) {
                            path.moveTo(pointX, pointY)
                            firstPoint = false
                        } else {
                            path.lineTo(pointX, pointY)
                        }
                    }

                    drawPath(
                        path = path,
                        color = curveColor,
                        style = Stroke(width = with(density) { 2.5.dp.toPx() })
                    )
                }
                
                // 绘制一般的曲线数据
                if (data.curvePoints.isNotEmpty()) {
                    val path = Path()
                    var firstPoint = true
                    
                    for (point in data.curvePoints) {
                        // 确保点在范围内
                        val x = point.first
                        val y = point.second
                        
                        if (x < xMin || x > xMax || y < yMin || y > yMax) {
                            continue
                        }
                        
                        // 计算屏幕坐标
                        val xRatio = if (xDiff != 0.0) (x - xMin) / xDiff else 0.0
                        val yRatio = if (yDiff != 0.0) (y - yMin) / yDiff else 0.0
                        
                        val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                        val pointY = graphEndY - (yRatio * graphHeight).toFloat()
                        
                        if (firstPoint) {
                            path.moveTo(pointX, pointY)
                            firstPoint = false
                        } else {
                            path.lineTo(pointX, pointY)
                        }
                    }
                    
                    drawPath(
                        path = path,
                        color = curveColor,
                        style = Stroke(width = with(density) { 1.8.dp.toPx() })
                    )
                }

                // 绘制附加曲线（如多通道叠加对比）
                data.overlayLines.forEach lineLoop@{ line ->
                    if (line.points.isEmpty()) return@lineLoop

                    val overlayPath = Path()
                    var isFirstPoint = true

                    line.points.forEach pointLoop@{ point ->
                        val x = point.first
                        val y = point.second
                        if (x < xMin || x > xMax || y < yMin || y > yMax) {
                            return@pointLoop
                        }

                        val xRatio = if (xDiff != 0.0) (x - xMin) / xDiff else 0.0
                        val yRatio = if (yDiff != 0.0) (y - yMin) / yDiff else 0.0
                        val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                        val pointY = graphEndY - (yRatio * graphHeight).toFloat()

                        if (isFirstPoint) {
                            overlayPath.moveTo(pointX, pointY)
                            isFirstPoint = false
                        } else {
                            overlayPath.lineTo(pointX, pointY)
                        }
                    }

                    drawPath(
                        path = overlayPath,
                        color = line.color,
                        style = Stroke(
                            width = line.strokeWidth,
                            pathEffect = if (line.dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            } else {
                                null
                            }
                        )
                    )
                }

                // 绘制竖向标记线（如峰值位置）
                data.verticalMarkers.forEach { marker ->
                    if (marker.x < xMin || marker.x > xMax) return@forEach

                    val xRatio = if (xDiff != 0.0) (marker.x - xMin) / xDiff else 0.0
                    val markerX = graphStartX + (xRatio * graphWidth).toFloat()
                    val labelStyle = TextStyle(
                        color = marker.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val labelLayout = textMeasurer.measure(marker.label, labelStyle)
                    val labelX = (markerX - labelLayout.size.width / 2f)
                        .coerceIn(graphStartX, graphEndX - labelLayout.size.width)

                    drawLine(
                        color = marker.color.copy(alpha = 0.8f),
                        start = Offset(markerX, graphStartY + 8f),
                        end = Offset(markerX, graphEndY),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )

                    drawText(
                        textMeasurer = textMeasurer,
                        text = marker.label,
                        style = labelStyle,
                        topLeft = Offset(labelX, graphStartY - 18f)
                    )
                }

                // 绘制散点
                val pointOffsets = mutableListOf<Offset>() // 保存所有点的屏幕坐标
                data.visibleScatterPoints()?.forEachIndexed { index, point ->
                    // 计算屏幕坐标，使用安全的除法
                    val xRatio = if (xDiff != 0.0) (point.x - xMin) / xDiff else 0.0
                    val yRatio = if (yDiff != 0.0) (point.y - yMin) / yDiff else 0.0

                    val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                    val pointY = graphEndY - (yRatio * graphHeight).toFloat()

                    // 保存点的屏幕坐标
                    pointOffsets.add(Offset(pointX, pointY))

                    // 先绘制浅色描边，再绘制实心点，避免数据点被曲线或网格吞没。
                    drawCircle(
                        color = pointOutlineColor,
                        radius = if (index == selectedPointIndex) {
                            with(density) { 5.5.dp.toPx() }
                        } else {
                            with(density) { 4.5.dp.toPx() }
                        },
                        center = Offset(pointX, pointY)
                    )
                    drawCircle(
                        color = if (index == selectedPointIndex) selectedPointColor else pointColor,
                        radius = if (index == selectedPointIndex) {
                            with(density) { 4.dp.toPx() }
                        } else {
                            with(density) { 3.dp.toPx() }
                        },
                        center = Offset(pointX, pointY)
                    )
                }

                // 绘制十字定位辅助线 - 使用曲线点位置或选中的数据点
                curvePointPosition?.let { position ->
                    // 确保十字线限制在图表区域内，且不是NaN
                    if (!position.x.isNaN() && !position.y.isNaN()) {
                        val boundedX = position.x.coerceIn(graphStartX, graphEndX)
                        val boundedY = position.y.coerceIn(graphStartY, graphEndY)

                        // 垂直线
                        drawLine(
                            color = crosshairColor.copy(alpha = 0.72f),
                            start = Offset(boundedX, graphStartY),
                            end = Offset(boundedX, graphEndY),
                            strokeWidth = with(density) { 0.75.dp.toPx() },
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )

                        // 水平线
                        drawLine(
                            color = crosshairColor.copy(alpha = 0.72f),
                            start = Offset(graphStartX, boundedY),
                            end = Offset(graphEndX, boundedY),
                            strokeWidth = with(density) { 0.75.dp.toPx() },
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                        
                        // 绘制十字线交叉点的小圆点
                        drawCircle(
                            color = crosshairColor,
                            radius = with(density) { 2.5.dp.toPx() },
                            center = Offset(boundedX, boundedY)
                        )
                    }
                }

                // 测量和绘制坐标轴标签
                // X轴标签
                val xAxisTextStyle = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = axisColor
                )
                val xLabelWidth = textMeasurer.measure(xAxisLabelText, style = xAxisTextStyle).size.width

                drawText(
                    textMeasurer = textMeasurer,
                    text = xAxisLabelText,
                    style = xAxisTextStyle,
                    topLeft = Offset(
                        x = graphStartX + (graphWidth - xLabelWidth) / 2f,
                        y = chartHeight - with(density) { 18.dp.toPx() }
                    )
                )

                // Y轴标签
                // 计算Y轴标签的位置，确保与Y轴刻度不重叠
                val yAxisTitleX = with(density) { 8.dp.toPx() }

                // 使用drawIntoCanvas绘制旋转的文本
                drawIntoCanvas { canvas ->
                    canvas.save()

                    // 移动到绘制点
                    canvas.translate(yAxisTitleX, chartHeight / 2f)
                    // 旋转-90度
                    canvas.rotate(-90f)

                    // 绘制文本，现在是水平的（但canvas已旋转）
                    canvas.nativeCanvas.drawText(
                        yAxisLabelText,
                        0f,  // 居中对齐
                        10f,  // y坐标（现在是水平方向）
                        android.graphics.Paint().apply {
                            color = axisColor.toArgb()
                            textSize = with(density) { 10.sp.toPx() }
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true  // 使文本加粗
                        }
                    )

                    canvas.restore()
                }
            }

            // 显示选中点或曲线点的数值
            curvePointPosition?.let { position ->
                // 确保位置不是NaN
                if (!position.x.isNaN() && !position.y.isNaN()) {
                    // 判断点的位置，决定悬浮窗出现在上方还是下方
                    val halfHeight = canvasSize.height / 2f
                    val isInUpperHalf = position.y < halfHeight
                    
                    // 获取数值
                    val (xValue, yValue) = if (selectedPointIndex != null) {
                        // 如果是选中的数据点，直接显示数据点的值
                        data.visibleScatterPoints()?.getOrNull(selectedPointIndex!!)?.let { point ->
                            Pair(point.x, point.y)
                        } ?: Pair(0.0, 0.0)
                    } else {
                        // 如果是曲线上的点，计算对应的值
                        val plotGeometry = calculatePlotGeometry(canvasSize, density)
                        val graphWidth = plotGeometry.width
                        val graphStartX = plotGeometry.startX
                        
                        val xMin = data.xRange.first
                        val xMax = data.xRange.second
                        val xDiff = xMax - xMin
                        
                        // 从屏幕坐标转换回数据坐标
                        val xRatio = (position.x - graphStartX) / graphWidth
                        val xValue = xMin + (xRatio * xDiff)
                        
                        // 使用拟合曲线函数计算y值
                        val yValue = data.fittedCurve?.let { curve ->
                            try {
                                curve(xValue)
                            } catch (e: Exception) {
                                0.0
                            }
                        } ?: 0.0
                        
                        Pair(xValue, yValue)
                    }
                    
                    // 检查值是否有效
                    if (!xValue.isNaN() && !xValue.isInfinite() && !yValue.isNaN() && !yValue.isInfinite()) {
                        val tooltipText = if (isSpectrumChart(data.chartType)) {
                            stringResource(
                                id = R.string.spectrum_chart_tooltip_format,
                                xValue,
                                yValue
                            )
                        } else {
                            val (xLabel, yLabel) = when (data.chartType) {
                                "BLAND_ALTMAN" -> Pair(
                                    stringResource(id = R.string.chart_mean),
                                    stringResource(id = R.string.chart_difference)
                                )
                                // 工具提示必须与坐标轴保持同一语义，不能继续回退到旧版固定文案。
                                "REGRESSION" -> Pair(xAxisLabelText, yAxisLabelText)
                                else -> Pair(
                                    xAxisLabelText,
                                    yAxisLabelText
                                )
                            }
                            stringResource(
                                id = R.string.chart_tooltip_pair_format,
                                xLabel,
                                formatTooltipValue(xValue),
                                yLabel,
                                formatTooltipValue(yValue)
                            )
                        }
                        
                        Card(
                            modifier = Modifier
                                .align(if (isInUpperHalf) Alignment.BottomCenter else Alignment.TopCenter)
                                .padding(
                                    top = if (isInUpperHalf) 0.dp else 8.dp,
                                    bottom = if (isInUpperHalf) 8.dp else 0.dp
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Text(
                                text = tooltipText,
                                modifier = Modifier.padding(8.dp),
                                style = TextStyle(fontSize = 12.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标准曲线图例，明确区分实验标定点与模型拟合曲线。 */
@Composable
private fun CurveChartLegend(
    curveColor: Color,
    pointColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CurveLegendItem(
            label = stringResource(R.string.chart_legend_calibration_points),
            color = pointColor,
            drawAsPoint = true
        )
        Spacer(Modifier.width(14.dp))
        CurveLegendItem(
            label = stringResource(R.string.chart_legend_fitted_curve),
            color = curveColor,
            drawAsPoint = false
        )
    }
}

/** 图例中的最小视觉单元，避免使用字符模拟点或线。 */
@Composable
private fun CurveLegendItem(
    label: String,
    color: Color,
    drawAsPoint: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 16.dp, height = 10.dp)) {
            if (drawAsPoint) {
                drawCircle(color = color, radius = size.minDimension * 0.34f, center = center)
            } else {
                drawLine(
                    color = color,
                    start = Offset(0f, center.y),
                    end = Offset(size.width, center.y),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 图表绘图区的统一坐标契约，绘制和手势命中必须共享同一套几何参数。 */
private data class PlotGeometry(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
) {
    val width: Float get() = (endX - startX).coerceAtLeast(1f)
    val height: Float get() = (endY - startY).coerceAtLeast(1f)
}

/**
 * 根据真实 Canvas 尺寸和设备密度计算绘图区。
 *
 * 左侧为纵轴标题和刻度保留空间，底部为横轴刻度和标题保留空间；不再额外缩小数据区。
 */
private fun calculatePlotGeometry(size: Size, density: Density): PlotGeometry = with(density) {
    val startX = 54.dp.toPx().coerceAtMost(size.width * 0.34f)
    val startY = 18.dp.toPx().coerceAtMost(size.height * 0.22f)
    val endX = (size.width - 12.dp.toPx()).coerceAtLeast(startX + 1f)
    val endY = (size.height - 44.dp.toPx()).coerceAtLeast(startY + 1f)
    PlotGeometry(startX = startX, startY = startY, endX = endX, endY = endY)
}

/** 标准曲线旧数据有时只写入 standardPoints，这里统一补成可交互散点。 */
private fun ChartData.visibleScatterPoints(): List<ChartPoint>? {
    return scatterPoints ?: standardPoints
        .takeIf { chartType == "STANDARD_CURVE" && it.isNotEmpty() }
        ?.map { (x, y) -> ChartPoint(x = x, y = y) }
}

/** 未指定颜色时使用当前主题色，保留光谱等调用方显式传入的专业配色。 */
private fun Color.orThemeColor(themeColor: Color): Color {
    return if (this == Color.Unspecified) themeColor else this
}

/** 根据数值跨度选择刻度精度，兼顾小数、常规量级和科学计数法。 */
private fun formatAxisTick(value: Double, span: Double): String {
    val absolute = abs(value)
    return when {
        !value.isFinite() -> "--"
        absolute >= 100_000 || (absolute in 0.0..0.0001 && absolute > 0.0) ->
            String.format(Locale.US, "%.1e", value)
        abs(span) < 0.01 -> String.format(Locale.US, "%.3f", value)
        abs(span) < 1.0 -> String.format(Locale.US, "%.2f", value)
        abs(span) < 100.0 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.0f", value)
    }
}

/**
 * 统一格式化曲线提示框中的数值，避免不同模式下出现过长小数。
 */
private fun formatTooltipValue(value: Double, scale: Int = 4): String {
    return BigDecimal.valueOf(value)
        .setScale(scale, RoundingMode.HALF_UP)
        .toPlainString()
}

/**
 * 光谱图使用独立的提示文案，避免继续复用浓度/像素值标签。
 */
private fun isSpectrumChart(chartType: String): Boolean {
    return chartType == "SPECTRUM" || chartType == "SPECTRUM_COMPARE"
}

/**
 * 查找距离指定位置最近的数据点
 *
 * @param data 图表数据
 * @param position 当前位置
 * @param size Canvas大小
 * @return Pair<索引, 屏幕坐标> 或 null(如果没找到合适的点)
 */
private fun findNearestPointToPosition(
    data: ChartData,
    position: Offset,
    size: Size,
    density: Density
): Pair<Int, Offset>? {
    val points = data.visibleScatterPoints() ?: return null
    if (points.isEmpty()) return null

    val plotGeometry = calculatePlotGeometry(size, density)
    val graphWidth = plotGeometry.width
    val graphHeight = plotGeometry.height
    val graphStartX = plotGeometry.startX
    val graphEndY = plotGeometry.endY

    // 数据范围
    val xMin = data.xRange.first
    val xMax = data.xRange.second
    val yMin = data.yRange.first
    val yMax = data.yRange.second
    val xDiff = xMax - xMin
    val yDiff = yMax - yMin

    // 找到最近的点
    var closestPointIndex = -1
    var minDistance = Double.MAX_VALUE
    var closestPointOffset: Offset? = null
    val snapThreshold = with(density) { 24.dp.toPx() }

    points.forEachIndexed { index, point ->
        // 确保点的值是有效的
        if (point.x.isNaN() || point.y.isNaN()) {
            return@forEachIndexed
        }
        
        val xRatio = if (xDiff != 0.0) (point.x - xMin) / xDiff else 0.0
        val yRatio = if (yDiff != 0.0) (point.y - yMin) / yDiff else 0.0

        val pointX = graphStartX + (xRatio * graphWidth).toFloat()
        val pointY = graphEndY - (yRatio * graphHeight).toFloat()
        
        // 确保计算的坐标不是NaN
        if (pointX.isNaN() || pointY.isNaN()) {
            return@forEachIndexed
        }
        
        val pointOffset = Offset(pointX, pointY)

        // 计算水平和垂直距离
        val horizontalDistance = abs(pointX - position.x)
        val verticalDistance = abs(pointY - position.y)
        
        // 增强水平方向吸附效果：在 24dp 触控范围内降低垂直距离权重，
        // 既方便手指命中，又不会因为设备密度不同而改变交互手感。
        val effectiveDistance = if (horizontalDistance < snapThreshold) {
            // 水平方向距离权重增加，垂直方向权重降低
            horizontalDistance * 0.7f + verticalDistance * 0.3f
        } else {
            // 常规欧几里得距离计算
            sqrt(
                (pointX - position.x).pow(2) +
                (pointY - position.y).pow(2)
            )
        }

        // 只在统一的密度无关阈值内吸附到标定点。
        if (effectiveDistance < minDistance && effectiveDistance < snapThreshold) {
            minDistance = effectiveDistance.toDouble()
            closestPointIndex = index
            closestPointOffset = pointOffset
        }
    }

    return if (closestPointIndex >= 0 && closestPointOffset != null) {
        Pair(closestPointIndex, closestPointOffset!!)
    } else {
        null
    }
}

/**
 * 查找曲线上最接近指定位置的点
 * 
 * @param data 图表数据
 * @param position 当前位置
 * @param size Canvas大小
 * @return 曲线上点的屏幕坐标 或 null
 */
private fun findPointOnCurve(
    data: ChartData,
    position: Offset,
    size: Size,
    density: Density
): Offset? {
    val fittedCurve = data.fittedCurve ?: return null

    val plotGeometry = calculatePlotGeometry(size, density)
    val graphWidth = plotGeometry.width
    val graphHeight = plotGeometry.height
    val graphStartX = plotGeometry.startX
    val graphEndY = plotGeometry.endY

    // 确保位置在图表范围内
    val boundedX = position.x.coerceIn(graphStartX, graphStartX + graphWidth)
    
    // 数据范围
    val xMin = data.xRange.first
    val xMax = data.xRange.second
    val yMin = data.yRange.first
    val yMax = data.yRange.second
    val xDiff = xMax - xMin
    val yDiff = yMax - yMin
    
    // 从屏幕坐标转换到数据坐标
    val xRatio = (boundedX - graphStartX) / graphWidth
    val xValue = xMin + (xRatio * xDiff)
    
    // 使用拟合曲线计算y值
    return try {
        val yValue = fittedCurve(xValue)
        
        // 确保y值在有效范围内
        if (yValue.isNaN() || yValue.isInfinite() || yValue < yMin || yValue > yMax) {
            null
        } else {
            // 从数据坐标转换回屏幕坐标
            val yRatio = if (yDiff != 0.0) (yValue - yMin) / yDiff else 0.0
            val pointY = graphEndY - (yRatio * graphHeight).toFloat()
            
            // 确保计算的坐标不是NaN
            if (boundedX.isNaN() || pointY.isNaN()) {
                null
            } else {
                Offset(boundedX, pointY)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** 自动构建拟合曲线图时的内部准备结果，错误类型在 Compose 层再映射为资源文本。 */
private data class CurveChartPreparation(
    val data: ChartData?,
    val error: CurveChartPreparationError?
)

private enum class CurveChartPreparationError {
    INVALID_CURVE,
    MISSING_CURVE
}

/** 根据函数定义给出没有标定点时的安全预览范围。 */
private fun defaultCurveDomain(
    function: FittingFunction,
    parameters: Map<String, Double>
): Pair<Double, Double> {
    return when (function) {
        FittingFunction.LOG -> Pair(0.1, 100.0)
        FittingFunction.CUSTOM_LOG -> {
            val offset = parameters["c"] ?: 0.0
            Pair(offset + 0.1, offset + 100.0)
        }
        FittingFunction.POWER -> {
            if ((parameters["b"] ?: 0.0) < 0.0) Pair(0.1, 100.0) else Pair(0.0, 100.0)
        }
        FittingFunction.RODBARD,
        FittingFunction.HILL,
        FittingFunction.RODBARD_NIH,
        FittingFunction.LOGISTIC -> Pair(0.01, 100.0)
        else -> Pair(0.0, 100.0)
    }
}

/** 判断函数在当前参数下是否要求横轴严格大于某个下界。 */
private fun requiresPositiveDomain(
    function: FittingFunction,
    parameters: Map<String, Double>
): Boolean {
    return when (function) {
        FittingFunction.LOG,
        FittingFunction.CUSTOM_LOG,
        FittingFunction.RODBARD,
        FittingFunction.HILL,
        FittingFunction.RODBARD_NIH,
        FittingFunction.LOGISTIC -> true
        FittingFunction.POWER -> (parameters["b"] ?: 0.0) < 0.0
        else -> false
    }
}

/**
 * 根据拟合函数和参数自动生成图表数据的曲线图表组件
 *
 * @param fittedCurve 拟合曲线函数
 * @param selectedFunction 选择的函数类型（用于确定X轴范围）
 * @param parameters 函数参数
 * @param xAxisLabel X轴标签
 * @param yAxisLabel Y轴标签
 * @param title 图表标题
 * @param modifier 修饰符
 * @param dataPoints 散点数据
 */
@Composable
fun CurveChart(
    fittedCurve: ((Double) -> Double)?,
    selectedFunction: FittingFunction?,
    parameters: Map<String, Double>,
    xAxisLabel: String = "",
    yAxisLabel: String = "",
    title: String = "",
    modifier: Modifier = Modifier,
    dataPoints: List<Pair<Double, Double>>? = null
) {
    val preparation = remember(selectedFunction, parameters, fittedCurve, dataPoints, title, xAxisLabel, yAxisLabel) {
        derivedStateOf {
            if (fittedCurve != null && selectedFunction != null) {
                try {
                    // 标准曲线优先围绕真实标定点确定横轴，避免固定 0..100 把数据压缩在角落。
                    val finitePoints = dataPoints.orEmpty()
                        .filter { (x, y) -> x.isFinite() && y.isFinite() }
                    val fallbackDomain = defaultCurveDomain(selectedFunction, parameters)
                    val pointMin = finitePoints.minOfOrNull { it.first }
                    val pointMax = finitePoints.maxOfOrNull { it.first }
                    val rawXMin = pointMin ?: fallbackDomain.first
                    val rawXMax = pointMax ?: fallbackDomain.second
                    val rawXSpan = abs(rawXMax - rawXMin)
                    val xPadding = if (rawXSpan < 1e-9) {
                        maxOf(abs(rawXMin) * 0.08, 1.0)
                    } else {
                        rawXSpan * 0.06
                    }
                    val domainFloor = fallbackDomain.first
                    val adjustedXMin = if (requiresPositiveDomain(selectedFunction, parameters)) {
                        maxOf(domainFloor, rawXMin - xPadding)
                    } else {
                        rawXMin - xPadding
                    }
                    val adjustedXMax = maxOf(
                        adjustedXMin + 1e-6,
                        rawXMax + xPadding
                    )

                    // 在真实显示范围内均匀采样，使图形范围与用户实验数据一致。
                    val yValues = (0..200).mapNotNull { i ->
                        val x = adjustedXMin + i * (adjustedXMax - adjustedXMin) / 200
                        try {
                            val y = fittedCurve(x)
                            y.takeIf(Double::isFinite)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val scatterPoints = finitePoints.map { (x, y) ->
                        ChartPoint(x = x, y = y)
                    }
                    val allYValues = yValues + finitePoints.map { it.second }

                    if (allYValues.isNotEmpty()) {
                        val rawYMin = allYValues.minOrNull() ?: 0.0
                        val rawYMax = allYValues.maxOrNull() ?: 1.0
                        val rawYSpan = abs(rawYMax - rawYMin)
                        val yPadding = if (rawYSpan < 1e-9) {
                            maxOf(abs(rawYMin) * 0.08, 1.0)
                        } else {
                            rawYSpan * 0.08
                        }
                        CurveChartPreparation(
                            data = ChartData(
                                title = title,
                                xAxisLabel = xAxisLabel,
                                yAxisLabel = yAxisLabel,
                                fittedCurve = fittedCurve,
                                scatterPoints = scatterPoints.takeIf(List<ChartPoint>::isNotEmpty),
                                xRange = Pair(adjustedXMin, adjustedXMax),
                                yRange = Pair(rawYMin - yPadding, rawYMax + yPadding),
                                showGrid = true
                            ),
                            error = null
                        )
                    } else {
                        CurveChartPreparation(data = null, error = CurveChartPreparationError.INVALID_CURVE)
                    }
                } catch (_: Exception) {
                    CurveChartPreparation(data = null, error = CurveChartPreparationError.INVALID_CURVE)
                }
            } else {
                CurveChartPreparation(data = null, error = CurveChartPreparationError.MISSING_CURVE)
            }
        }
    }.value

    Box(modifier = modifier) {
        preparation.data?.let { chartData ->
            CurveChart(
                data = chartData,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val errorText = when (preparation.error) {
                CurveChartPreparationError.INVALID_CURVE ->
                    stringResource(R.string.chart_error_invalid_curve)
                CurveChartPreparationError.MISSING_CURVE ->
                    stringResource(R.string.chart_error_missing_curve)
                null -> stringResource(R.string.chart_error_unknown)
            }
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}
