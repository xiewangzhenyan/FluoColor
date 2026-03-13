package com.muc.fluocolorquant.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.muc.fluocolorquant.data.enums.FittingFunction
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import java.math.BigDecimal
import java.math.RoundingMode
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
    var isDragging by remember { mutableStateOf(false) }
    var crossPosition by remember { mutableStateOf<Offset?>(null) }
    
    // 增加曲线点位置状态，用于存储当前十字线在曲线上的点
    var curvePointPosition by remember { mutableStateOf<Offset?>(null) }
    
    // 获取当前密度，用于dp到px的转换
    val density = LocalDensity.current

    // 根据图表类型确定x轴标签显示内容
    val xAxisLabelText = when (data.chartType) {
        "BLAND_ALTMAN" -> stringResource(id = R.string.chart_mean)
        "REGRESSION" -> stringResource(id = R.string.chart_predicted)
        else -> data.xAxisLabel.ifEmpty { stringResource(id = R.string.chart_concentration) }
    }

    val yAxisLabelText = when (data.chartType) {
        "BLAND_ALTMAN" -> stringResource(id = R.string.chart_difference)
        "REGRESSION" -> stringResource(id = R.string.chart_actual)
        else -> data.yAxisLabel.ifEmpty { stringResource(id = R.string.chart_pixel_value) }
    }

    val availableFunctions = remember {
        FittingFunction.values().filter { it != FittingFunction.INTERPOLATION }
    }

    Column(modifier = modifier) {
        // 图表标题
        Text(
            text = data.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        // 图表Canvas
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(data.backgroundColor)
                    .pointerInput(Unit) {
                        if (!interactive) return@pointerInput
                        detectTapGestures { offset ->
                            // 点击事件处理，查找最近的数据点或曲线点
                            findNearestPointToPosition(data, offset, size.toSize())?.let { (index, pointOffset) ->
                                selectedPointIndex = index
                                crossPosition = offset
                                curvePointPosition = pointOffset
                                isDragging = false
                            } ?: run {
                                // 如果没有找到附近的数据点，则计算曲线上的点
                                findPointOnCurve(data, offset, size.toSize())?.let { curveOffset ->
                                    selectedPointIndex = null
                                    crossPosition = offset
                                    curvePointPosition = curveOffset
                                } ?: run {
                                    selectedPointIndex = null
                                    crossPosition = null
                                    curvePointPosition = null
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        if (!interactive) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                crossPosition = offset
                                
                                // 拖动开始时查找最近的点
                                findNearestPointToPosition(data, offset, size.toSize())?.let { (index, pointOffset) ->
                                    selectedPointIndex = index
                                    curvePointPosition = pointOffset
                                    // 直接将十字线位置设置为数据点位置，增强吸附效果
                                    crossPosition = pointOffset
                                } ?: run {
                                    // 如果没有找到附近的数据点，则计算曲线上的点
                                    findPointOnCurve(data, offset, size.toSize())?.let { curveOffset ->
                                        selectedPointIndex = null
                                        curvePointPosition = curveOffset
                                    }
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDrag = { change, _ ->
                                // 更新十字线位置
                                crossPosition = change.position

                                // 先查找最近的数据点尝试吸附
                                findNearestPointToPosition(data, change.position, size.toSize())?.let { (index, pointOffset) ->
                                    selectedPointIndex = index
                                    // 直接将十字线位置设置为数据点位置，增强吸附效果
                                    curvePointPosition = pointOffset
                                    crossPosition = pointOffset
                                } ?: run {
                                    // 如果没有找到附近的数据点，则计算曲线上的点
                                    selectedPointIndex = null
                                    findPointOnCurve(data, change.position, size.toSize())?.let { curveOffset ->
                                        curvePointPosition = curveOffset
                                    }
                                }
                            }
                        )
                    }
            ) {
                val chartWidth = size.width
                val chartHeight = size.height

                // 基本填充区域
                val leftPadding = 70f  // 增加到70f，更好地容纳Y轴标签
                val rightPadding = 40f
                val topPadding = 40f
                val bottomPadding = 100f  // 增加底部空间

                // 实际绘图区域 (缩小10%)
                val graphWidth = (chartWidth - leftPadding - rightPadding) * 0.9f
                val graphHeight = (chartHeight - topPadding - bottomPadding) * 0.9f

                // 额外内边距 (剩余的10%/2)
                val innerPaddingX = (chartWidth - leftPadding - rightPadding) * 0.05f
                val innerPaddingY = (chartHeight - topPadding - bottomPadding) * 0.05f

                // 计算实际图表绘制起点
                val graphStartX = leftPadding + innerPaddingX
                val graphStartY = topPadding + innerPaddingY
                val graphEndX = graphStartX + graphWidth
                val graphEndY = chartHeight - bottomPadding - innerPaddingY

                // 获取数据范围
                val xMin = data.xRange.first
                val xMax = data.xRange.second
                val yMin = data.yRange.first
                val yMax = data.yRange.second
                val xDiff = xMax - xMin
                val yDiff = yMax - yMin

                // 测量Y轴刻度标签的最大宽度
                val longestYLabel = String.format("%.2f", yMax)
                val yTickStyle = TextStyle(fontSize = 10.sp)
                val yTickTextLayoutResult = textMeasurer.measure(longestYLabel, style = yTickStyle)
                val maxYTickWidth = yTickTextLayoutResult.size.width.toFloat()

                // 绘制坐标轴
                drawLine(
                    color = Color.Black,
                    start = Offset(graphStartX, graphEndY),
                    end = Offset(graphEndX, graphEndY),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(graphStartX, graphEndY),
                    end = Offset(graphStartX, graphStartY),
                    strokeWidth = 2f
                )

                // 绘制网格线
                if (data.showGrid) {
                    val numXGridLines = 5
                    val numYGridLines = 5
                    val xStep = graphWidth / numXGridLines
                    val yStep = graphHeight / numYGridLines

                    // 横向网格线
                    for (i in 0..numYGridLines) {
                        // 计算精确的y值，确保网格线与刻度值严格对应
                        val yValue = yMin + (yDiff * i / numYGridLines)

                        // 计算y值对应的屏幕坐标
                        val yRatio = if (yDiff != 0.0) (yValue - yMin) / yDiff else 0.0
                        val y = graphEndY - (yRatio * graphHeight).toFloat()

                        drawLine(
                            color = data.gridColor,
                            start = Offset(graphStartX, y),
                            end = Offset(graphEndX, y),
                            strokeWidth = 1f
                        )

                        // Y轴刻度
                        val yText = String.format("%.1f", yValue)

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
                            color = data.gridColor,
                            start = Offset(x, graphStartY),
                            end = Offset(x, graphEndY),
                            strokeWidth = 1f
                        )

                        // X轴刻度
                        val xText = String.format("%.1f", xValue)

                        // 使用TextMeasurer测量文本宽度，以便居中对齐
                        val xTextStyle = TextStyle(fontSize = 10.sp)
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
                        
                        // 均值线 - 绿色
                        if (meanLine.size >= 2) {
                            // 确保绘制水平线
                            val y = graphEndY - ((meanLine[0].second - yMin) / yDiff * graphHeight).toFloat()
                            
                            drawLine(
                                color = Color.Green,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = 2f
                            )
                            
                            // 绘制均值标签
                            val labelText = "Mean"
                            drawText(
                                textMeasurer = textMeasurer,
                                text = labelText,
                                style = TextStyle(color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                topLeft = Offset(
                                    x = graphEndX - 40f,
                                    y = y - 15f
                                )
                            )
                        }
                        
                        // 上限线 - 红色，虚线
                        if (upperLine.size >= 2) {
                            // 确保绘制水平线
                            val y = graphEndY - ((upperLine[0].second - yMin) / yDiff * graphHeight).toFloat()
                            
                            drawLine(
                                color = Color.Red,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )

                            // 绘制上限标签
                            val labelText = "Upper"
                            drawText(
                                textMeasurer = textMeasurer,
                                text = labelText,
                                style = TextStyle(color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold),
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
                                color = Color.Red,
                                start = Offset(graphStartX, y),
                                end = Offset(graphEndX, y),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )

                            // 绘制下限标签
                            val labelText = "Lower"
                            drawText(
                                textMeasurer = textMeasurer,
                                text = labelText,
                                style = TextStyle(color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold),
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
                        color = data.curveColor,
                        style = Stroke(width = 3f)
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
                        color = data.curveColor,
                        style = Stroke(width = 2f)
                    )
                }

                // 绘制附加曲线（如多通道叠加对比）
                data.overlayLines.forEach { line ->
                    if (line.points.isEmpty()) return@forEach

                    val overlayPath = Path()
                    var isFirstPoint = true

                    line.points.forEach { point ->
                        val x = point.first
                        val y = point.second
                        if (x < xMin || x > xMax || y < yMin || y > yMax) {
                            return@forEach
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
                data.scatterPoints?.forEachIndexed { index, point ->
                    // 计算屏幕坐标，使用安全的除法
                    val xRatio = if (xDiff != 0.0) (point.x - xMin) / xDiff else 0.0
                    val yRatio = if (yDiff != 0.0) (point.y - yMin) / yDiff else 0.0

                    val pointX = graphStartX + (xRatio * graphWidth).toFloat()
                    val pointY = graphEndY - (yRatio * graphHeight).toFloat()

                    // 保存点的屏幕坐标
                    pointOffsets.add(Offset(pointX, pointY))

                    // 绘制数据点
                    drawCircle(
                        color = if (index == selectedPointIndex) Color.Red else data.pointColor,
                        radius = if (index == selectedPointIndex) 8f else 5f,
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
                            color = Color.Red,
                            start = Offset(boundedX, graphStartY),
                            end = Offset(boundedX, graphEndY),
                            strokeWidth = 1f
                        )

                        // 水平线
                        drawLine(
                            color = Color.Red,
                            start = Offset(graphStartX, boundedY),
                            end = Offset(graphEndX, boundedY),
                            strokeWidth = 1f
                        )
                        
                        // 绘制十字线交叉点的小圆点
                        drawCircle(
                            color = Color.Red,
                            radius = 4f,
                            center = Offset(boundedX, boundedY)
                        )
                    }
                }

                // 测量和绘制坐标轴标签
                // X轴标签
                val xAxisTextStyle = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                val xLabelWidth = textMeasurer.measure(xAxisLabelText, style = xAxisTextStyle).size.width

                drawText(
                    textMeasurer = textMeasurer,
                    text = xAxisLabelText,
                    style = xAxisTextStyle,
                    topLeft = Offset(
                        x = (chartWidth - xLabelWidth) / 2f,
                        y = chartHeight - 60f // 底部留出更多空间给X轴标签
                    )
                )

                // Y轴标签
                val yAxisTextStyle = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                // 计算Y轴标签的位置，确保与Y轴刻度不重叠
                val yAxisTitleX = 16f  // 距离画布左边的距离

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
                            color = android.graphics.Color.BLACK
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
                    val halfHeight = with(density) { 125.dp.toPx() } // 画布高度的一半
                    val isInUpperHalf = position.y < halfHeight
                    
                    // 获取数值
                    val (xValue, yValue) = if (selectedPointIndex != null) {
                        // 如果是选中的数据点，直接显示数据点的值
                        data.scatterPoints?.getOrNull(selectedPointIndex!!)?.let { point ->
                            Pair(point.x, point.y)
                        } ?: Pair(0.0, 0.0)
                    } else {
                        // 如果是曲线上的点，计算对应的值
                        val chartWidth = with(density) { 250.dp.toPx() } // 估计的图表宽度
                        val leftPadding = 70f
                        val rightPadding = 40f
                        val graphWidth = (chartWidth - leftPadding - rightPadding) * 0.9f
                        val innerPaddingX = (chartWidth - leftPadding - rightPadding) * 0.05f
                        val graphStartX = leftPadding + innerPaddingX
                        
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
                        // 根据图表类型，提供不同的标签和格式
                        val (xLabel, yLabel) = when (data.chartType) {
                            "BLAND_ALTMAN" -> Pair(
                                stringResource(id = R.string.chart_mean), 
                                stringResource(id = R.string.chart_difference)
                            )
                            "REGRESSION" -> Pair(
                                stringResource(id = R.string.chart_predicted), 
                                stringResource(id = R.string.chart_actual)
                            )
                            else -> Pair(
                                stringResource(id = R.string.chart_concentration), 
                                stringResource(id = R.string.chart_pixel_value)
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
                                text = "$xLabel: ${String.format("%.4f", xValue)}\n" +
                                       "$yLabel: ${String.format("%.4f", yValue)}",
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

/**
 * 查找距离指定位置最近的数据点
 *
 * @param data 图表数据
 * @param position 当前位置
 * @param size Canvas大小
 * @return Pair<索引, 屏幕坐标> 或 null(如果没找到合适的点)
 */
private fun findNearestPointToPosition(data: ChartData, position: Offset, size: androidx.compose.ui.geometry.Size): Pair<Int, Offset>? {
    val points = data.scatterPoints ?: return null
    if (points.isEmpty()) return null

    val chartWidth = size.width
    val chartHeight = size.height

    // 基本布局参数
    val leftPadding = 70f
    val rightPadding = 40f
    val topPadding = 40f
    val bottomPadding = 100f

    // 实际绘图区域 (缩小10%)
    val graphWidth = (chartWidth - leftPadding - rightPadding) * 0.9f
    val graphHeight = (chartHeight - topPadding - bottomPadding) * 0.9f

    // 额外内边距
    val innerPaddingX = (chartWidth - leftPadding - rightPadding) * 0.05f
    val innerPaddingY = (chartHeight - topPadding - bottomPadding) * 0.05f

    val graphStartX = leftPadding + innerPaddingX
    val graphEndY = chartHeight - bottomPadding - innerPaddingY

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
        
        // 增强水平方向吸附效果：
        // 1. 增大水平方向吸附范围到60像素
        // 2. 当水平距离较小时，大幅降低垂直方向的权重
        val effectiveDistance = if (horizontalDistance < 60) {
            // 水平方向距离权重增加，垂直方向权重降低
            horizontalDistance * 0.7f + verticalDistance * 0.3f
        } else {
            // 常规欧几里得距离计算
            sqrt(
                (pointX - position.x).pow(2) +
                (pointY - position.y).pow(2)
            )
        }

        // 增大吸附阈值到60像素，使得更容易吸附到数据点
        if (effectiveDistance < minDistance && effectiveDistance < 60) {
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
private fun findPointOnCurve(data: ChartData, position: Offset, size: androidx.compose.ui.geometry.Size): Offset? {
    val fittedCurve = data.fittedCurve ?: return null
    
    val chartWidth = size.width
    val chartHeight = size.height

    // 基本布局参数
    val leftPadding = 70f
    val rightPadding = 40f
    val topPadding = 40f
    val bottomPadding = 100f

    // 实际绘图区域 (缩小10%)
    val graphWidth = (chartWidth - leftPadding - rightPadding) * 0.9f
    val graphHeight = (chartHeight - topPadding - bottomPadding) * 0.9f

    // 额外内边距
    val innerPaddingX = (chartWidth - leftPadding - rightPadding) * 0.05f
    val innerPaddingY = (chartHeight - topPadding - bottomPadding) * 0.05f

    val graphStartX = leftPadding + innerPaddingX
    val graphEndY = chartHeight - bottomPadding - innerPaddingY
    val graphStartY = topPadding + innerPaddingY

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
    val chartDataResult = remember(selectedFunction, parameters, fittedCurve, dataPoints) {
        derivedStateOf {
            if (fittedCurve != null && selectedFunction != null) {
                try {
                    // 根据函数类型确定合适的x轴范围
                    val (xMin, xMax) = when (selectedFunction) {
                        FittingFunction.LOG,
                        FittingFunction.CUSTOM_LOG -> Pair(0.1, 100.0) // 对数函数从非零值开始

                        FittingFunction.POWER -> {
                            // 幂函数根据指数值判断是否需要从非零开始
                            val b = parameters["b"] ?: 0.0
                            if (b < 0) Pair(0.1, 100.0) else Pair(0.0, 100.0)
                        }

                        FittingFunction.RODBARD,
                        FittingFunction.HILL,
                        FittingFunction.RODBARD_NIH,
                        FittingFunction.LOGISTIC -> {
                            // 这些函数在x非常接近0时可能出现数值问题
                            Pair(0.01, 100.0)
                        }

                        else -> Pair(0.0, 100.0) // 大多数函数可以从0开始
                    }

                    // 生成曲线点数据
                    val yValues = (0..100).mapNotNull { i ->
                        val x = xMin + i * (xMax - xMin) / 100
                        try {
                            val y = fittedCurve(x)
                            if (y.isNaN() || y.isInfinite()) null else y
                        } catch (e: Exception) { null }
                    }

                    // 如果有散点数据，确保X轴和Y轴范围能覆盖所有散点
                    var adjustedXMin = xMin
                    var adjustedXMax = xMax
                    var yMin = yValues.minOrNull() ?: 0.0
                    var yMax = yValues.maxOrNull() ?: 10.0

                    // 处理散点数据
                    val scatterPoints = dataPoints?.map {
                        ChartPoint(x = it.first, y = it.second)
                    }

                    // 如果有散点，调整X和Y轴范围以适应散点
                    if (scatterPoints != null && scatterPoints.isNotEmpty()) {
                        val scatterXMin = scatterPoints.minByOrNull { it.x }?.x ?: adjustedXMin
                        val scatterXMax = scatterPoints.maxByOrNull { it.x }?.x ?: adjustedXMax
                        val scatterYMin = scatterPoints.minByOrNull { it.y }?.y ?: yMin
                        val scatterYMax = scatterPoints.maxByOrNull { it.y }?.y ?: yMax

                        // 调整范围以包含所有散点，添加10%的边距
                        adjustedXMin = minOf(adjustedXMin, scatterXMin * 0.9)
                        adjustedXMax = maxOf(adjustedXMax, scatterXMax * 1.1)
                        yMin = minOf(yMin, scatterYMin * 0.9)
                        yMax = maxOf(yMax, scatterYMax * 1.1)
                    }

                    // 关键修正：仅当Y轴范围为0时（即水平线），才添加一个微小的固定边距以保证图表可以被绘制
                    if (abs(yMax - yMin) < 1e-9) {
                        yMin -= 1.0
                        yMax += 1.0
                    }

                    // 添加额外的安全检查，确保没有NaN或Infinite值
                    if (adjustedXMin.isNaN() || adjustedXMin.isInfinite()) adjustedXMin = 0.0
                    if (adjustedXMax.isNaN() || adjustedXMax.isInfinite()) adjustedXMax = 100.0
                    if (yMin.isNaN() || yMin.isInfinite()) yMin = 0.0
                    if (yMax.isNaN() || yMax.isInfinite()) yMax = 10.0

                    if (yValues.isNotEmpty() || scatterPoints != null) {
                        Result.success(
                            ChartData(
                                title = title,
                                xAxisLabel = xAxisLabel,
                                yAxisLabel = yAxisLabel,
                                fittedCurve = fittedCurve,
                                scatterPoints = scatterPoints,
                                xRange = Pair(adjustedXMin, adjustedXMax),
                                yRange = Pair(yMin, yMax),
                                backgroundColor = Color.White,
                                curveColor = Color(0xFF2196F3),
                                pointColor = Color(0xFF4CAF50),
                                gridColor = Color(0xFFCCCCCC),
                                showGrid = true
                            )
                        )
                    } else {
                        Result.failure(Exception("Invalid parameters, curve cannot be drawn"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } else {
                Result.failure(Exception("Please provide valid function and parameters"))
            }
        }
    }.value

    Box(modifier = modifier) {
        chartDataResult.fold(
            onSuccess = { chartData ->
                CurveChart(
                    data = chartData,
                    modifier = Modifier.fillMaxSize()
                )
            },
            onFailure = { error ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error.message ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        )
    }
}
