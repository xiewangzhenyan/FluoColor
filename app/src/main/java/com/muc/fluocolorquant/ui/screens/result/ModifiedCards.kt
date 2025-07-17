package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.WellMappingUtils

/**
 * 修改版的PlateHeatmapCard，支持按分析物过滤
 * - 增加了完整的行列标签和布局，参考旧版ResultScreen.kt
 * - 调整了边距和标签大小以放大网格
 */
@Composable
fun PlateHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    minConcentration: Double,
    maxConcentration: Double,
    showOnlyCurrentAnalyte: Boolean = false,
    currentAnalyteId: String? = null,
    project: Project? = null
) {
    val projectRows = project?.rows ?: 8
    val projectColumns = project?.columns ?: 12

    val filteredResults = if (showOnlyCurrentAnalyte && currentAnalyteId != null) {
        wellResults.filter { it.fkAnalyteId == currentAnalyteId }
    } else {
        wellResults
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) { // 调整内边距
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.concentration_heatmap),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.unit_label, concentrationUnit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HeatmapLegend(minValue = minConcentration, maxValue = maxConcentration, unit = concentrationUnit)
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                val wellsByVirtualCoord = remember(filteredResults) {
                    filteredResults
                        .filter { it.virtualRow != null && it.virtualCol != null }
                        .associateBy { Pair(it.virtualRow!!, it.virtualCol!!) }
                }

                val displayRows = 8
                val displayCols = 12
                val labelSize = 16.dp // 减小标签占用空间

                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)) { // 减小内边距
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(labelSize)) // 调整标签间距
                            for (col in 1..displayCols) {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    Text(text = col.toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }

                        for (row in 0 until displayRows) {
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(labelSize), contentAlignment = Alignment.Center) { // 调整标签间距
                                    Text(text = ('A' + row).toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }

                                for (col in 0 until displayCols) {
                                    val wellResult = wellsByVirtualCoord[Pair(row, col)]
                                    val realIndex = WellMappingUtils.mapVirtualToRealIndex(row, col)
                                    val isWithinProjectBounds = realIndex < (projectRows * projectColumns)

                                    Box(modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                        if (isWithinProjectBounds) {
                                            PlateWell(
                                                wellResult = wellResult,
                                                minConcentration = minConcentration,
                                                maxConcentration = maxConcentration,
                                                project = project,
                                                concentrationUnit = concentrationUnit
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(1.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(224, 224, 224, 100))
                                                    .border(0.5.dp, Color.DarkGray.copy(alpha = 0.1f), CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 修改版的SquareHeatmapCard，放大网格
 */
@Composable
fun SquareHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    minConcentration: Double,
    maxConcentration: Double,
    showOnlyCurrentAnalyte: Boolean = false,
    currentAnalyteId: String? = null,
    project: Project? = null
) {
    val projectRows = project?.rows ?: 8
    val projectColumns = project?.columns ?: 12

    val filteredResults = if (showOnlyCurrentAnalyte && currentAnalyteId != null) {
        wellResults.filter { it.fkAnalyteId == currentAnalyteId }
    } else {
        wellResults
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) { // 调整内边距
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.concentration_values),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.unit_label, concentrationUnit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HeatmapLegend(minValue = minConcentration, maxValue = maxConcentration, unit = concentrationUnit)
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                val wellsByVirtualCoord = remember(filteredResults) {
                    filteredResults
                        .filter { it.virtualRow != null && it.virtualCol != null }
                        .associateBy { Pair(it.virtualRow!!, it.virtualCol!!) }
                }

                val displayRows = 8
                val displayCols = 12
                val labelSize = 16.dp // 减小标签占用空间

                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)) { // 减小内边距
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(labelSize)) // 调整标签间距
                            for (col in 1..displayCols) {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    Text(text = col.toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }
                        for (row in 0 until displayRows) {
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(labelSize), contentAlignment = Alignment.Center) { // 调整标签间距
                                    Text(text = ('A' + row).toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                                for (col in 0 until displayCols) {
                                    val wellResult = wellsByVirtualCoord[Pair(row, col)]
                                    val realIndex = WellMappingUtils.mapVirtualToRealIndex(row, col)
                                    val isWithinProjectBounds = realIndex < (projectRows * projectColumns)

                                    Box(modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                        if (isWithinProjectBounds) {
                                            SquareWell(
                                                wellResult = wellResult,
                                                minConcentration = minConcentration,
                                                maxConcentration = maxConcentration,
                                                project = project,
                                                concentrationUnit = concentrationUnit
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(1.dp)
                                                    .background(Color(224, 224, 224, 100))
                                                    .border(0.5.dp, Color.DarkGray.copy(alpha = 0.1f))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* PlateWell, SquareWell, 和其他辅助函数保持不变，因为它们的逻辑是正确的。
   ...
*/
// The rest of the file (PlateWell, SquareWell, HeatmapLegend, calculateLuminance) remains the same.
// To save space, I will omit them here, but they are part of the full correct file.
// I have included the full implementation in the previous response.
@Composable
fun PlateWell(
    wellResult: WellResult?,
    minConcentration: Double,
    maxConcentration: Double,
    project: Project? = null,
    concentrationUnit: String
) {
    val actualConcentration = wellResult?.predictedConcentration
    val percentValue = if (actualConcentration != null && maxConcentration > 0) {
        (actualConcentration / maxConcentration) * 100.0
    } else null

    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(
            value = actualConcentration,
            minValue = minConcentration,
            maxValue = maxConcentration
        )
    } else Color(224, 224, 224, 180)

    var showTooltip by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(CircleShape)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.3f), CircleShape)
            .clickable { showTooltip = !showTooltip },
        contentAlignment = Alignment.Center
    ) {
        if (showTooltip && actualConcentration != null && wellResult != null && percentValue != null) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { showTooltip = false }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val wellLabel = if (wellResult.virtualRow != null && wellResult.virtualCol != null) {
                            WellMappingUtils.getWellLabel(wellResult.virtualRow!!, wellResult.virtualCol!!)
                        } else {
                            val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(wellResult.wellIndex)
                            WellMappingUtils.getWellLabel(vRow, vCol)
                        }

                        Text(
                            text = wellLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.concentration_percent_format, percentValue),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            text = stringResource(
                                R.string.concentration_value_format,
                                actualConcentration,
                                concentrationUnit
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SquareWell(
    wellResult: WellResult?,
    minConcentration: Double,
    maxConcentration: Double,
    project: Project? = null,
    concentrationUnit: String
) {
    val actualConcentration = wellResult?.predictedConcentration

    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(
            value = actualConcentration,
            minValue = minConcentration,
            maxValue = maxConcentration
        )
    } else Color(224, 224, 224, 180)

    val displayText = if (actualConcentration != null) {
        stringResource(R.string.value_format, actualConcentration)
    } else ""

    val textColor = if (calculateLuminance(wellColor) > 0.5f) Color.Black else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
fun HeatmapLegend(minValue: Double, maxValue: Double, unit: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                HeatmapColorUtil.getLegendColors(20).forEach { color ->
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(color))
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(R.string.value_format, minValue), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(text = stringResource(R.string.value_format, maxValue), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}


private fun calculateLuminance(color: Color): Float {
    val red = color.red
    val green = color.green
    val blue = color.blue
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}

/**
 * 浓度趋势卡片
 * 显示浓度趋势图，支持交互式选择数据点
 */
@Composable
fun ConcentrationTrendCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    analyteName: String,
    maxConcentration: Double,
    modifier: Modifier = Modifier
) {
    val validResults = wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }
    val sortedResults = remember(validResults) { validResults.sortedBy { it.wellIndex } }
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }

    if (sortedResults.isNotEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val chartHeight = 250.dp
            // 使用已有的最终浓度值
            val dataPointConcentrations = sortedResults.map { result ->
                result to (result.predictedConcentration ?: 0.0)
            }

            // 图表Canvas部分
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 40.dp)
            ) {
                Column(
                    modifier = Modifier
                        .height(chartHeight - 52.dp)
                        .align(Alignment.CenterStart)
                        .offset(x = (-38).dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 5 downTo 0) {
                        val percentOfMax = i * 20.0 / 100.0
                        val actualValue = percentOfMax * maxConcentration
                        Text(
                            text = stringResource(R.string.value_format, actualValue), 
                            fontSize = 10.sp, 
                            textAlign = TextAlign.End, 
                            modifier = Modifier.width(36.dp), 
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                if (sortedResults.size > 1) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(scrollState)
                    ) {
                        val pointCount = sortedResults.size
                        val dataPointWidth = 50.dp
                        val chartWidth = maxOf(dataPointWidth * pointCount, 350.dp) + 20.dp
                        val primaryColorArgb = MaterialTheme.colorScheme.primary.toArgb()
                        val primaryColorHighlightedArgb = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f).toArgb()
                        val canvasBackgroundColor = Color.White
                        val dataPoints = remember { mutableStateListOf<Pair<Offset, Int>>() }
                        var tapSelectedIndex by remember { mutableStateOf<Int?>(null) }
                        LaunchedEffect(tapSelectedIndex) { selectedPointIndex = tapSelectedIndex }

                        // 使用 virtualRow 和 virtualCol 生成正确的标签
                        val xLabels = remember(sortedResults) {
                            sortedResults.map { result ->
                                if (result.virtualRow != null && result.virtualCol != null) {
                                    WellMappingUtils.getWellLabel(result.virtualRow!!, result.virtualCol!!)
                                } else {
                                    // 不使用虚拟坐标的旧数据回退
                                    val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(result.wellIndex)
                                    WellMappingUtils.getWellLabel(vRow, vCol)
                                }
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .width(chartWidth)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures { tapPosition ->
                                        val closestPoint = dataPoints.minByOrNull { (position, _) ->
                                            val dx = position.x - tapPosition.x
                                            val dy = position.y - tapPosition.y
                                            dx * dx + dy * dy
                                        }
                                        closestPoint?.let { (position, index) ->
                                            val dx = position.x - tapPosition.x
                                            val dy = position.y - tapPosition.y
                                            if (kotlin.math.sqrt(dx * dx + dy * dy) < 25) {
                                                tapSelectedIndex = index
                                            }
                                        }
                                    }
                                }
                        ) {
                            val height = size.height - 12.dp.toPx()
                            val width = size.width
                            dataPoints.clear()

                            // 绘制网格
                            val gridColor = Color.Gray.copy(alpha = 0.1f)
                            val gridStrokeWidth = 1f

                            // 绘制水平网格线
                            for (i in 0..5) {
                                val y = height - (height * i / 5)
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, y),
                                    end = Offset(width, y),
                                    strokeWidth = gridStrokeWidth
                                )
                            }

                            // 计算每个点的水平间距
                            val pointSpacing = if (pointCount > 1) (width - 30.dp.toPx()) / (pointCount - 1) else width / 2

                            // 绘制垂直网格线
                            for (i in 0 until pointCount) {
                                val x = 15.dp.toPx() + i * pointSpacing
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, height),
                                    strokeWidth = gridStrokeWidth
                                )
                            }

                            // 数据点的最小高度（以防止与X轴标签重叠）
                            val minPointHeight = height - height * 0.95f

                            // 绘制数据线和点
                            val path = Path()
                            var firstPoint = true

                            dataPointConcentrations.forEachIndexed { i, (result, actualConcentrationValue) ->
                                val x = 15.dp.toPx() + i * pointSpacing
                                val normalizedY = if (maxConcentration > 0) (actualConcentrationValue / maxConcentration).toFloat() else 0f

                                // 确保点不会太低，与X轴标签重叠
                                val y = (height - (normalizedY * height)).coerceIn(minPointHeight, height)

                                dataPoints.add(Offset(x, y) to i)

                                if (firstPoint) {
                                    path.moveTo(x, y)
                                    firstPoint = false
                                } else {
                                    path.lineTo(x, y)
                                }

                                // 绘制点阴影
                                if (i == selectedPointIndex) {
                                    // 选中点的阴影
                                    drawCircle(
                                        color = Color.Gray.copy(alpha = 0.2f),
                                        radius = 12f,
                                        center = Offset(x, y)
                                    )
                                }

                                // 绘制数据点
                                val pointRadius = if (i == selectedPointIndex) 8f else 5f
                                val pointColor = if (i == selectedPointIndex)
                                    Color(primaryColorHighlightedArgb)
                                else
                                    Color(primaryColorArgb)

                                // 白色边框
                                if (i == selectedPointIndex) {
                                    drawCircle(
                                        color = canvasBackgroundColor,
                                        radius = pointRadius + 2f,
                                        center = Offset(x, y)
                                    )
                                }

                                // 实际数据点
                                drawCircle(
                                    color = pointColor,
                                    radius = pointRadius,
                                    center = Offset(x, y)
                                )
                            }

                            // 绘制曲线
                            drawPath(
                                path = path,
                                color = Color(primaryColorArgb).copy(alpha = 0.8f),
                                style = Stroke(
                                    width = 3f,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.cornerPathEffect(8f)
                                )
                            )
                        }

                        // X轴标签绘制区域
                        Box(
                            modifier = Modifier
                                .width(chartWidth)
                                .height(40.dp)
                                .align(Alignment.BottomCenter)
                        ) {
                            val density = LocalDensity.current
                            val textColorArgb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f).toArgb()

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val textPaint = android.text.TextPaint().apply {
                                    textSize = with(density) { 11.sp.toPx() }
                                    color = textColorArgb
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                    isFakeBoldText = true
                                }

                                // 使用与数据点相同的间距和起始点
                                val pointSpacing = if (xLabels.size > 1) (size.width - 30.dp.toPx()) / (xLabels.size - 1) else size.width / 2

                                // 绘制标签
                                xLabels.forEachIndexed { i, label ->
                                    val xPos = 15.dp.toPx() + i * pointSpacing
                                    val yPos = size.height - 8.dp.toPx()

                                    // 绘制标签文本
                                    this.drawContext.canvas.nativeCanvas.drawText(
                                        label,
                                        xPos,
                                        yPos,
                                        textPaint
                                    )
                                }
                            }
                        }
                    }
                } else if (sortedResults.size == 1) { // 单个数据点的情况
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val result = sortedResults[0]
                        val rowChar = ('A' + result.wellIndex % 8).toChar()
                        val colNumber = (result.wellIndex / 8) + 1
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(4.dp), 
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.well_position_short, rowChar.toString(), colNumber),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 选中点的信息卡片
            selectedPointIndex?.let { index ->
                if (index < dataPointConcentrations.size) {
                    val (result, actualConcentrationValue) = dataPointConcentrations[index]
                    val rowChar = ('A' + result.wellIndex % 8).toChar()
                    val colNumber = (result.wellIndex / 8) + 1
                    val percentValue = if (maxConcentration > 0) (actualConcentrationValue / maxConcentration) * 100.0 else 0.0

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.well_position_short, rowChar.toString(), colNumber),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.concentration_percent_and_value, percentValue, actualConcentrationValue, concentrationUnit),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .clickable { selectedPointIndex = null },
                                contentAlignment = Alignment.Center
                            ) { 
                                Text(
                                    text = stringResource(R.string.close_button),
                                    fontWeight = FontWeight.Bold
                                ) 
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.click_datapoint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.no_concentration_data))
        }
    }
}