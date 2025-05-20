package com.muc.fluocolorquant.ui.screens.result

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ResultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import com.muc.fluocolorquant.R

/**
 * 导出选项面板
 */
@Composable
fun ExportBottomPanel(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    project: Project,
    wellResults: List<WellResult>,
    detectionRun: DetectionRun?,
    captureScreenshot: () -> Bitmap?
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val coroutineScope = rememberCoroutineScope()

    val visibleState = remember {
        MutableTransitionState(false).apply {
            targetState = isVisible
        }
    }

    LaunchedEffect(isVisible) {
        visibleState.targetState = isVisible
    }

    if (visibleState.currentState || visibleState.targetState) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            // 使用DialogWindowProvider来设置背景半透明效果
            (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.5f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() },
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable(enabled = false) { /* 拦截点击，防止关闭面板 */ },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 8.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "导出",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // 导出数据选项（CSV）
                            ExportOption(
                                title = "导出数据 (CSV)",
                                description = "将浓度数据导出为CSV文件",
                                icon = Icons.Default.Description,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    val success = exportDataToCsv(context, project, wellResults)
                                    if (success) {
                                        toastManager.showToast(
                                            "CSV文件已保存到下载文件夹",
                                            ToastType.SUCCESS
                                        )
                                        onDismiss()
                                    } else {
                                        toastManager.showToast(
                                            "导出CSV文件失败",
                                            ToastType.ERROR
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 导出图表选项（PNG）
                            ExportOption(
                                title = "导出图表 (PNG)",
                                description = "将浓度热力图和数值图导出为PNG图片",
                                icon = Icons.Default.Image,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    // 导出浓度热力图和浓度数值图
                                    val success = exportHeatmapAndValueCharts(context, project, wellResults)
                                    if (success) {
                                        toastManager.showToast(
                                            "浓度图表已保存为PNG图片",
                                            ToastType.SUCCESS
                                        )
                                        onDismiss()
                                    } else {
                                        toastManager.showToast(
                                            "导出图表失败",
                                            ToastType.ERROR
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 导出报告选项（PDF）
                            ExportOption(
                                title = "导出完整报告 (PDF)",
                                description = "将检测结果及参数导出为PDF报告",
                                icon = Icons.Default.PictureAsPdf,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    // 导出完整报告，包括浓度热力图和浓度数值图
                                    val success = exportFullReport(context, project, wellResults, detectionRun)
                                    if (success) {
                                        toastManager.showToast(
                                            "PDF报告已保存到下载文件夹",
                                            ToastType.SUCCESS
                                        )
                                        onDismiss()
                                    } else {
                                        toastManager.showToast(
                                            "导出PDF报告失败",
                                            ToastType.ERROR
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(16.dp))

                            // 取消按钮
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("取消")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导出选项组件
 */
@Composable
fun ExportOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // 使用自定义导出图标替代FileDownload图标
        Icon(
            painter = painterResource(id = R.drawable.export),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * 导出数据为CSV文件
 */
suspend fun exportDataToCsv(
    context: Context,
    project: Project,
    wellResults: List<WellResult>
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_${project.name.replace(" ", "_")}_$timestamp.csv"

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)

        FileOutputStream(file).use { fos ->
            // CSV头部
            val header = "项目名称:,${project.name}\n" +
                    "检测模式:,${if (project.detectionMode == "FLUORESCENCE") "荧光检测" else "比色检测"}\n" +
                    "识别类型:,${if (project.recognitionType == "AUTO") "自动识别" else "手动裁剪"}\n" +
                    "最大浓度:,${project.maxConcentration} ng/ml\n" +
                    "创建时间:,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(project.createTime)}\n\n" +
                    "孔位,孔位索引,预测浓度(%),实际浓度(ng/ml)\n"

            fos.write(header.toByteArray())

            // 数据行
            wellResults.forEach { result ->
                if (result.predictedConcentration != null) {
                    val rowChar = ('A' + result.wellIndex / 12).toChar()
                    val colNumber = (result.wellIndex % 12) + 1
                    val wellLabel = "$rowChar$colNumber"

                    val predictedPercent = result.predictedConcentration
                    val actualConcentration = project.maxConcentration?.let { maxConc ->
                        (predictedPercent!! / 100.0) * maxConc
                    } ?: 0.0

                    val line = "$wellLabel,${result.wellIndex},${String.format("%.2f", predictedPercent)},${String.format("%.2f", actualConcentration)}\n"
                    fos.write(line.toByteArray())
                }
            }
        }

        // 让媒体扫描器扫描新文件
        val fileUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))

        return@withContext true
    } catch (e: IOException) {
        e.printStackTrace()
        return@withContext false
    }
}

/**
 * 导出浓度热力图和浓度数值图
 */
suspend fun exportHeatmapAndValueCharts(
    context: Context,
    project: Project,
    wellResults: List<WellResult>
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // 生成热力图
        val heatmapBitmap = generateHeatmapBitmap(project, wellResults)
        // 生成数值图
        val valueChartBitmap = generateValueChartBitmap(project, wellResults)

        var success = true

        // 保存热力图
        if (heatmapBitmap != null) {
            val heatmapFileName = "FluoColor_Heatmap_${project.name.replace(" ", "_")}_$timestamp.png"
            success = success && saveBitmapToFile(context, heatmapBitmap, "png", heatmapFileName)
        }

        // 保存数值图
        if (valueChartBitmap != null) {
            val valueChartFileName = "FluoColor_ValueChart_${project.name.replace(" ", "_")}_$timestamp.png"
            success = success && saveBitmapToFile(context, valueChartBitmap, "png", valueChartFileName)
        }

        return@withContext success
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    }
}

/**
 * 生成浓度热力图的Bitmap
 */
fun generateHeatmapBitmap(project: Project, wellResults: List<WellResult>): Bitmap? {
    // 创建一个Bitmap来绘制热力图
    val width = 800
    val height = 600
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    // 设置白色背景
    canvas.drawColor(android.graphics.Color.WHITE)

    // 绘制标题
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 40f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText("浓度热力图 - ${project.name}", width / 2f, 60f, titlePaint)

    // 绘制热力图
    if (wellResults.isNotEmpty()) {
        // 为了简单起见，这里只绘制一个简化版的热力图
        // 实际应用中，你应该复用你在应用中已有的热力图绘制逻辑
        val gridWidth = 12 // 孔阵列数
        val gridHeight = 8 // 孔阵行数
        val cellWidth = (width - 100) / gridWidth
        val cellHeight = (height - 200) / gridHeight
        val startX = 50f
        val startY = 100f

        val maxConcentration = project.maxConcentration ?: 100.0

        // 绘制网格和颜色
        for (row in 0 until gridHeight) {
            for (col in 0 until gridWidth) {
                val wellIndex = row * gridWidth + col
                val wellResult = wellResults.find { it.wellIndex == wellIndex }

                val rectPaint = android.graphics.Paint()
                if (wellResult?.predictedConcentration != null) {
                    // 根据浓度值确定颜色
                    val normalizedValue = (wellResult.predictedConcentration / 100.0).toFloat()
                    val color = getHeatmapColor(normalizedValue)
                    rectPaint.color = color
                } else {
                    rectPaint.color = android.graphics.Color.LTGRAY
                }

                val left = startX + col * cellWidth
                val top = startY + row * cellHeight
                val right = left + cellWidth
                val bottom = top + cellHeight

                canvas.drawRect(left, top, right, bottom, rectPaint)

                // 绘制孔位标签
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 12f
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                val rowChar = ('A' + row).toChar()
                val colNumber = col + 1
                val label = "$rowChar$colNumber"
                canvas.drawText(label, left + cellWidth / 2, top + cellHeight / 2, labelPaint)

                // 如果有预测值，显示预测值
                if (wellResult?.predictedConcentration != null) {
                    val actualConcentration = (wellResult.predictedConcentration / 100.0) * maxConcentration
                    val valuePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 10f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText(
                        String.format("%.1f", actualConcentration),
                        left + cellWidth / 2,
                        top + cellHeight / 2 + 15,
                        valuePaint
                    )
                }
            }
        }

        // 绘制图例
        val legendPaint = android.graphics.Paint().apply {
            textSize = 14f
            color = android.graphics.Color.BLACK
        }
        canvas.drawText("浓度范围: 0 - $maxConcentration ng/ml", startX, startY + gridHeight * cellHeight + 40, legendPaint)
    } else {
        // 如果没有结果，显示提示信息
        val noPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("没有有效的浓度数据", width / 2f, height / 2f, noPaint)
    }

    return bitmap
}

/**
 * 生成浓度数值图的Bitmap
 */
fun generateValueChartBitmap(project: Project, wellResults: List<WellResult>): Bitmap? {
    // 创建一个Bitmap来绘制数值图
    val width = 1000  // 增加图表宽度，从800增加到1000
    val height = 500  // 增加图表高度，从400增加到500
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    // 设置白色背景
    canvas.drawColor(android.graphics.Color.WHITE)

    // 绘制标题
    val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 40f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText("浓度数值分布 - ${project.name}", width / 2f, 60f, titlePaint)

    // 过滤有效结果
    val validResults = wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }

    if (validResults.isNotEmpty()) {
        // 计算实际浓度值
        val maxConcentration = project.maxConcentration ?: 100.0
        val concentrations = validResults.map {
            (it.predictedConcentration!! / 100.0) * maxConcentration
        }

        // 绘制柱状图
        val chartWidth = width - 150  // 增加左边距，从100增加到150
        val chartHeight = height - 180  // 增加底边距，以便有足够空间显示横轴标签
        val startX = 100f  // 增加左边距，为Y轴单位留出足够空间
        val startY = height - 100f  // 增加底边距

        // 按孔位索引排序
        val sortedResults = validResults.sortedBy { it.wellIndex }

        // 设置柱宽度，考虑柱之间的间隔
        val barCount = sortedResults.size
        val barWidth = if (barCount > 0) (chartWidth.toFloat() / barCount).coerceAtMost(30f) else 30f
        val maxHeight = chartHeight - 20

        // Y轴最大值
        val yMax = concentrations.maxOrNull()?.times(1.1) ?: 100.0 // 110%的最大值

        // 绘制X轴和Y轴
        val axisPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 2f
        }
        canvas.drawLine(startX, startY, startX + chartWidth, startY, axisPaint) // X轴
        canvas.drawLine(startX, startY, startX, startY - chartHeight, axisPaint) // Y轴

        // 绘制柱状图
        val barPaint = android.graphics.Paint()
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // 决定是否绘制所有标签
        val drawEveryLabel = barWidth >= 15f

        // 计算标签间隔（如果柱太多）
        val labelInterval = if (barCount > 24) 4 else if (barCount > 12) 2 else 1

        sortedResults.forEachIndexed { index, result ->
            val concentration = (result.predictedConcentration!! / 100.0) * maxConcentration
            val barHeight = (concentration / yMax * maxHeight.toFloat()).toFloat()

            // 设置柱状图颜色
            val normalizedValue = (concentration / maxConcentration).toFloat()
            barPaint.color = getHeatmapColor(normalizedValue)

            // 绘制柱状图
            val left = startX + index * barWidth
            val top = startY - barHeight
            val right = left + barWidth - 2 // 留一点间隔
            canvas.drawRect(left, top, right, startY, barPaint)

            // 绘制孔位标签（根据间隔显示）
            if (drawEveryLabel || index % labelInterval == 0) {
                val rowChar = ('A' + result.wellIndex / 12).toChar()
                val colNumber = (result.wellIndex % 12) + 1
                val label = "$rowChar$colNumber"

                // 斜向显示文本，防止重叠
                canvas.save()
                val labelX = left + barWidth / 2
                val labelY = startY + 15
                canvas.rotate(45f, labelX, labelY)  // 45度角倾斜显示
                canvas.drawText(label, labelX, labelY, textPaint)
                canvas.restore()
            }

            // 如果空间足够，绘制浓度值
            if (barWidth > 20f) {
                canvas.drawText(
                    String.format("%.1f", concentration),
                    left + barWidth / 2,
                    top - 5,
                    textPaint
                )
            }
        }

        // 绘制Y轴刻度
        val yLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        // 绘制5个刻度
        for (i in 0..5) {
            val y = startY - i * maxHeight.toFloat() / 5
            val value = i * yMax / 5
            canvas.drawLine(startX - 5, y, startX, y, axisPaint) // 刻度线
            canvas.drawText(String.format("%.1f", value), startX - 10, y + 5, yLabelPaint)
        }

        // 绘制单位 - 增加左边距并旋转90度显示
        val unitPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // 旋转画布并绘制Y轴单位
        canvas.save()
        canvas.rotate(-90f, startX - 60, startY - chartHeight.toFloat() / 2)
        canvas.drawText("浓度 (ng/ml)", startX - 60, startY - chartHeight.toFloat() / 2, unitPaint)
        canvas.restore()

        // 绘制X轴标签
        val xLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("孔位编号", startX + chartWidth.toFloat() / 2, startY + 70, xLabelPaint)
    } else {
        // 如果没有结果，显示提示信息
        val noPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("没有有效的浓度数据", width / 2f, height / 2f, noPaint)
    }

    return bitmap
}

/**
 * 获取热力图颜色
 */
fun getHeatmapColor(normalizedValue: Float): Int {
    // 使用9个色点的渐变，从深蓝到深红
    val colors = listOf(
        android.graphics.Color.rgb(13, 71, 161),   // 深蓝色
        android.graphics.Color.rgb(25, 118, 210),  // 蓝色
        android.graphics.Color.rgb(66, 165, 245),  // 浅蓝色
        android.graphics.Color.rgb(46, 125, 50),   // 绿色
        android.graphics.Color.rgb(139, 195, 74),  // 浅绿色
        android.graphics.Color.rgb(255, 235, 59),  // 黄色
        android.graphics.Color.rgb(255, 152, 0),   // 橙色
        android.graphics.Color.rgb(244, 67, 54),   // 红色
        android.graphics.Color.rgb(183, 28, 28)    // 深红色
    )

    val positions = listOf(0.0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1.0f)

    // 找到对应的区间
    for (i in 0 until positions.size - 1) {
        if (normalizedValue >= positions[i] && normalizedValue <= positions[i + 1]) {
            val t = (normalizedValue - positions[i]) / (positions[i + 1] - positions[i])
            return interpolateColor(colors[i], colors[i + 1], t)
        }
    }

    return if (normalizedValue >= 1.0f) colors.last() else colors.first()
}

/**
 * 颜色插值
 */
fun interpolateColor(c1: Int, c2: Int, t: Float): Int {
    val r1 = android.graphics.Color.red(c1)
    val g1 = android.graphics.Color.green(c1)
    val b1 = android.graphics.Color.blue(c1)

    val r2 = android.graphics.Color.red(c2)
    val g2 = android.graphics.Color.green(c2)
    val b2 = android.graphics.Color.blue(c2)

    val r = (r1 + t * (r2 - r1)).toInt()
    val g = (g1 + t * (g2 - g1)).toInt()
    val b = (b1 + t * (b2 - b1)).toInt()

    return android.graphics.Color.rgb(r, g, b)
}

/**
 * 修改保存Bitmap到文件函数，添加自定义文件名参数
 */
suspend fun saveBitmapToFile(
    context: Context,
    bitmap: Bitmap,
    format: String,
    customFileName: String? = null
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = customFileName ?: "FluoColor_Chart_$timestamp.$format"

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)

        FileOutputStream(file).use { fos ->
            // 根据格式选择压缩方式
            val compressFormat = when (format.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                else -> Bitmap.CompressFormat.PNG
            }

            // 压缩质量 (JPEG: 0-100, PNG: 压缩质量无效，但仍需提供)
            val quality = if (compressFormat == Bitmap.CompressFormat.JPEG) 90 else 100

            bitmap.compress(compressFormat, quality, fos)
        }

        // 让媒体扫描器扫描新文件
        val fileUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))

        return@withContext true
    } catch (e: IOException) {
        e.printStackTrace()
        return@withContext false
    }
}

/**
 * 导出完整报告，包括浓度热力图和浓度数值图
 */
suspend fun exportFullReport(
    context: Context,
    project: Project,
    wellResults: List<WellResult>,
    detectionRun: DetectionRun?
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_Report_${project.name.replace(" ", "_")}_$timestamp.pdf"

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)

        // 创建PDF文档
        val document = PdfDocument()

        // 创建页面
        val pageWidth = 595 // A4宽度，72dpi
        val pageHeight = 842 // A4高度，72dpi
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // 设置PDF绘制参数
        val titlePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 18f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }

        val headerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = true
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            textAlign = android.graphics.Paint.Align.LEFT
        }

        // 绘制标题
        canvas.drawText("FluoColor 检测报告", pageWidth / 2f, 40f, titlePaint)

        // 绘制项目信息
        var yOffset = 80f
        canvas.drawText("项目信息", 50f, yOffset, headerPaint)
        yOffset += 20f

        canvas.drawText("项目名称: ${project.name}", 50f, yOffset, textPaint)
        yOffset += 20f

        val detectionMode = if (project.detectionMode == "FLUORESCENCE") "荧光检测" else "比色检测"
        canvas.drawText("检测模式: $detectionMode", 50f, yOffset, textPaint)
        yOffset += 20f

        val recognitionType = if (project.recognitionType == "AUTO") "自动识别" else "手动裁剪"
        canvas.drawText("识别类型: $recognitionType", 50f, yOffset, textPaint)
        yOffset += 20f

        canvas.drawText("最大浓度: ${project.maxConcentration} ng/ml", 50f, yOffset, textPaint)
        yOffset += 20f

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        canvas.drawText("创建时间: ${dateFormat.format(project.createTime)}", 50f, yOffset, textPaint)
        yOffset += 20f

        // 如果是自动模式且有检测运行记录，添加模型信息
        if (project.recognitionType == "AUTO" && detectionRun != null) {
            yOffset += 10f
            canvas.drawText("检测运行信息", 50f, yOffset, headerPaint)
            yOffset += 20f

            detectionRun.detectionModelUsed?.let {
                canvas.drawText("检测模型: $it", 50f, yOffset, textPaint)
                yOffset += 20f
            }

            detectionRun.concentrationModelUsed?.let {
                canvas.drawText("浓度预测模型: $it", 50f, yOffset, textPaint)
                yOffset += 20f
            }

            detectionRun.confThreshold?.let {
                canvas.drawText("置信度阈值: $it", 50f, yOffset, textPaint)
                yOffset += 20f
            }

            detectionRun.iouThreshold?.let {
                canvas.drawText("IoU阈值: $it", 50f, yOffset, textPaint)
                yOffset += 20f
            }

            detectionRun.wellsDetected?.let {
                canvas.drawText("检测到的孔位数量: $it", 50f, yOffset, textPaint)
                yOffset += 20f
            }
        }

        // 统计信息
        yOffset += 10f
        canvas.drawText("统计信息", 50f, yOffset, headerPaint)
        yOffset += 20f

        val validResults = wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }
        canvas.drawText("有效数据点数量: ${validResults.size}", 50f, yOffset, textPaint)
        yOffset += 20f

        if (validResults.isNotEmpty()) {
            val avgPercentage = validResults.map { it.predictedConcentration!! }.average()
            val maxPercentage = validResults.maxOf { it.predictedConcentration!! }
            val minPercentage = validResults.minOf { it.predictedConcentration!! }

            canvas.drawText("平均浓度百分比: ${String.format("%.2f", avgPercentage)}%", 50f, yOffset, textPaint)
            yOffset += 20f

            canvas.drawText("最大浓度百分比: ${String.format("%.2f", maxPercentage)}%", 50f, yOffset, textPaint)
            yOffset += 20f

            canvas.drawText("最小浓度百分比: ${String.format("%.2f", minPercentage)}%", 50f, yOffset, textPaint)
            yOffset += 20f

            // 计算实际浓度
            val actualAvg = project.maxConcentration?.let { (avgPercentage / 100.0) * it } ?: 0.0
            val actualMax = project.maxConcentration?.let { (maxPercentage / 100.0) * it } ?: 0.0
            val actualMin = project.maxConcentration?.let { (minPercentage / 100.0) * it } ?: 0.0

            canvas.drawText("平均实际浓度: ${String.format("%.2f", actualAvg)} ng/ml", 50f, yOffset, textPaint)
            yOffset += 20f

            canvas.drawText("最大实际浓度: ${String.format("%.2f", actualMax)} ng/ml", 50f, yOffset, textPaint)
            yOffset += 20f

            canvas.drawText("最小实际浓度: ${String.format("%.2f", actualMin)} ng/ml", 50f, yOffset, textPaint)
            yOffset += 30f
        }

        // 生成并添加热力图
        canvas.drawText("浓度热力图", 50f, yOffset, headerPaint)
        yOffset += 20f

        val heatmapBitmap = generateHeatmapBitmap(project, wellResults)
        if (heatmapBitmap != null) {
            // 缩放热力图以适应PDF页面
            val scaledHeatmap = Bitmap.createScaledBitmap(
                heatmapBitmap,
                pageWidth - 100, // 留出左右边距
                (heatmapBitmap.height * (pageWidth - 100) / heatmapBitmap.width), // 保持宽高比
                true
            )
            canvas.drawBitmap(scaledHeatmap, 50f, yOffset, null)
            yOffset += scaledHeatmap.height + 30
        } else {
            yOffset += 20f // 如果生成热力图失败，增加一些空间
        }

        // 完成第一页并开始第二页
        document.finishPage(page)

        // 创建第二页
        val pageInfo2 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create()
        val page2 = document.startPage(pageInfo2)
        val canvas2 = page2.canvas

        // 第二页标题
        canvas2.drawText("FluoColor 检测报告 - 续", pageWidth / 2f, 40f, titlePaint)

        // 添加浓度数值图
        var yOffset2 = 80f
        canvas2.drawText("浓度数值分布", 50f, yOffset2, headerPaint)
        yOffset2 += 20f

        val valueChartBitmap = generateValueChartBitmap(project, wellResults)
        if (valueChartBitmap != null) {
            // 缩放数值图以适应PDF页面
            val scaledValueChart = Bitmap.createScaledBitmap(
                valueChartBitmap,
                pageWidth - 100, // 留出左右边距
                (valueChartBitmap.height * (pageWidth - 100) / valueChartBitmap.width), // 保持宽高比
                true
            )
            canvas2.drawBitmap(scaledValueChart, 50f, yOffset2, null)
            yOffset2 += scaledValueChart.height + 30
        } else {
            yOffset2 += 20f // 如果生成数值图失败，增加一些空间
        }

        // 添加页脚
        val footerText = "生成时间: ${dateFormat.format(Date())} | 使用FluoColor应用生成"
        val footerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 10f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas2.drawText(footerText, pageWidth / 2f, pageHeight - 30f, footerPaint)

        // 完成第二页
        document.finishPage(page2)

        // 写入文件
        FileOutputStream(file).use { fos ->
            document.writeTo(fos)
        }
        document.close()

        // 让媒体扫描器扫描新文件
        val fileUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))

        return@withContext true
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    }
} 