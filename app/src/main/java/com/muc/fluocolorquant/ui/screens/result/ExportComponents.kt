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
import androidx.compose.ui.res.stringResource
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
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.ui.components.ToastManager

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
    captureScreenshot: () -> Bitmap?,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
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
                                text = stringResource(R.string.export_title), // 使用已有的 stringResource
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // 导出数据选项（CSV）
                            ExportOption(
                                title = stringResource(R.string.export_data_csv), // 使用已有的 stringResource
                                description = stringResource(R.string.export_data_csv_desc), // 使用已有的 stringResource
                                icon = Icons.Default.Description,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    val success = exportDataToCsv(context, project, wellResults, projectAnalyteJoinRepository)
                                    if (success) {
                                        toastManager.showToast(
                                            context.getString(R.string.csv_saved), // 使用已有的 stringResource
                                            ToastType.SUCCESS
                                        )
                                        onDismiss()
                                    } else {
                                        toastManager.showToast(
                                            context.getString(R.string.csv_failed), // 使用已有的 stringResource
                                            ToastType.ERROR
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 导出图表选项（PNG）
                            ExportOption(
                                title = stringResource(R.string.export_chart_png), // 使用已有的 stringResource
                                description = stringResource(R.string.export_chart_png_desc), // 使用已有的 stringResource
                                icon = Icons.Default.Image,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    // 导出浓度热力图和浓度数值图
                                    val success = exportHeatmapAndValueCharts(context, project, wellResults, projectAnalyteJoinRepository)
                                    if (success) {
                                        toastManager.showToast(
                                            context.getString(R.string.charts_saved), // 使用已有的 stringResource
                                            ToastType.SUCCESS
                                        )
                                        onDismiss()
                                    } else {
                                        toastManager.showToast(
                                            context.getString(R.string.charts_failed), // 使用已有的 stringResource
                                            ToastType.ERROR
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 导出完整报告选项（PDF）
                            ExportOption(
                                title = stringResource(R.string.export_report_pdf), // 使用已有的 stringResource
                                description = stringResource(R.string.export_report_pdf_desc), // 使用已有的 stringResource
                                icon = Icons.Default.PictureAsPdf,
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                coroutineScope.launch {
                                    exportFullPdfReport(
                                        context,
                                        project,
                                        wellResults,
                                        projectAnalyteJoinRepository,
                                        toastManager
                                        )
                                        onDismiss()
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 取消按钮
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.cancel)) // 使用已有的 stringResource
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
    wellResults: List<WellResult>,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_${project.name.replace(" ", "_")}_$timestamp.csv"

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)

        // 获取项目配置值
        val (concentrationUnit, maxConcentration) = getProjectConfigValues(project, projectAnalyteJoinRepository)

        FileOutputStream(file).use { fos ->
            // CSV头部
            val header = "${context.getString(R.string.csv_label_project_name_colon)}${project.name}\n" +
                    "${context.getString(R.string.csv_label_detection_mode_colon)}${if (project.detectionMode == "FLUORESCENCE") context.getString(R.string.fluorescence_detection) else context.getString(R.string.colorimetric_detection)}\n" +
                    "${context.getString(R.string.csv_label_recognition_type_colon)}${if (project.recognitionType == "AUTO") context.getString(R.string.auto_recognition) else context.getString(R.string.manual_crop)}\n" +
                    "${context.getString(R.string.csv_label_max_concentration_colon)}${maxConcentration} ${concentrationUnit}\n" +
                    "${context.getString(R.string.csv_label_creation_time_colon)}${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(project.createTime)}\n\n" +
                    context.getString(R.string.csv_header_line_format, concentrationUnit)


            fos.write(header.toByteArray())

            // 数据行
            wellResults.forEach { result ->
                if (result.predictedConcentration != null) {
                    val rowChar = ('A' + result.wellIndex / 12).toChar()
                    val colNumber = (result.wellIndex % 12) + 1
                    val wellLabel = "$rowChar$colNumber"

                    val predictedPercent = result.predictedConcentration
                    val actualConcentration = (predictedPercent!! / 100.0) * maxConcentration

                    val line = "$wellLabel,${result.wellIndex},${String.format(Locale.US, "%.2f", predictedPercent)},${String.format(Locale.US, "%.2f", actualConcentration)}\n"
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
    wellResults: List<WellResult>,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
): Boolean = withContext(Dispatchers.IO) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // 生成热力图
        val heatmapBitmap = generateHeatmapBitmap(context, project, wellResults, projectAnalyteJoinRepository)
        // 生成数值图
        val valueChartBitmap = generateValueChartBitmap(context, project, wellResults, projectAnalyteJoinRepository)

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
suspend fun generateHeatmapBitmap(
    context: Context, 
    project: Project, 
    wellResults: List<WellResult>,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
): Bitmap? = withContext(Dispatchers.Default) {
    try {
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
    canvas.drawText(context.getString(R.string.bitmap_title_heatmap_with_name, project.name), width / 2f, 60f, titlePaint)

        // 获取项目配置值
        val (concentrationUnit, maxConcentration) = getProjectConfigValues(project, projectAnalyteJoinRepository)

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
                        val actualValue = (wellResult.predictedConcentration / 100.0) * maxConcentration
                    val valuePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 10f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText(
                            String.format(Locale.US, "%.1f", actualValue),
                        left + cellWidth / 2,
                            top + cellHeight / 2 + 12,
                        valuePaint
                    )
                }
            }
        }

        // 绘制图例
            val legendStartX = startX
            val legendStartY = startY + gridHeight * cellHeight + 40
            val legendWidth = 300
            val legendHeight = 20

            // 绘制颜色渐变条
            for (i in 0 until legendWidth) {
                val normalizedValue = i.toFloat() / legendWidth
                val color = getHeatmapColor(normalizedValue)
                val paint = android.graphics.Paint().apply { this.color = color }
                canvas.drawRect(
                    legendStartX + i,
                    legendStartY,
                    legendStartX + i + 1,
                    legendStartY + legendHeight,
                    paint
                )
            }

            // 绘制图例标签
            val legendTextPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
                textSize = 14f
                textAlign = android.graphics.Paint.Align.LEFT
            }
            canvas.drawText(
                context.getString(R.string.bitmap_legend_concentration_range_format, maxConcentration.toString(), concentrationUnit),
                legendStartX,
                legendStartY + legendHeight + 20,
                legendTextPaint
            )
    } else {
            // 如果没有结果，显示"无数据"消息
        val noPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
            canvas.drawText(
                context.getString(R.string.no_concentration_data),
                width / 2f,
                height / 2f,
                noPaint
            )
        }

        return@withContext bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}

/**
 * 生成浓度数值图的Bitmap
 */
suspend fun generateValueChartBitmap(
    context: Context, 
    project: Project, 
    wellResults: List<WellResult>,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
): Bitmap? = withContext(Dispatchers.Default) {
    try {
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
    canvas.drawText(context.getString(R.string.bitmap_title_value_distribution_with_name, project.name), width / 2f, 60f, titlePaint)

        // 获取项目配置值
        val (concentrationUnit, maxConcentration) = getProjectConfigValues(project, projectAnalyteJoinRepository)

    // 过滤有效结果
    val validResults = wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }

    if (validResults.isNotEmpty()) {
        // 计算实际浓度值
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

            // 绘制Y轴刻度和标签
            val ySteps = 5 // Y轴刻度数量
            for (i in 0..ySteps) {
                val yValue = (i * yMax / ySteps)
                val yPosition = startY - (i * chartHeight / ySteps)
                
                // 绘制水平网格线
                val gridPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    strokeWidth = 1f
                    alpha = 100
                }
                canvas.drawLine(startX, yPosition, startX + chartWidth, yPosition, gridPaint)
                
                // 绘制Y轴刻度和标签
        val yLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            textAlign = android.graphics.Paint.Align.RIGHT
        }
                canvas.drawText(
                    String.format(Locale.US, "%.1f", yValue), 
                    startX - 5f, 
                    yPosition + 5f, 
                    yLabelPaint
                )
            }
            
            // 绘制Y轴单位标签
            val yAxisLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.save()
            canvas.rotate(-90f, 30f, startY - chartHeight / 2)
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_concentration_with_unit, concentrationUnit),
                30f,
                startY - chartHeight / 2,
                yAxisLabelPaint
            )
        canvas.restore()

            // 绘制X轴标签（Well ID）
            val xAxisLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
            textAlign = android.graphics.Paint.Align.CENTER
        }
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_well_id_capital),
                startX + chartWidth / 2,
                startY + 50f,
                xAxisLabelPaint
            )

            // 绘制柱状图和标签
            sortedResults.forEachIndexed { index, result ->
                val concentration = (result.predictedConcentration!! / 100.0) * maxConcentration
                val barHeight = (concentration / yMax * maxHeight).toFloat()
                
                // 确定颜色（使用热力图颜色）
                val normalizedValue = (result.predictedConcentration / 100.0).toFloat()
                val color = getHeatmapColor(normalizedValue)
                barPaint.color = color
                
                val barPositionX = startX + index * barWidth
                val barTop = startY - barHeight
                
                canvas.drawRect(
                    barPositionX,
                    barTop,
                    barPositionX + barWidth * 0.8f, // 使柱子稍窄，留出间隔
                    startY,
                    barPaint
                )
                
                // 绘制孔位标签（如果间隔足够大或按指定间隔）
                if (drawEveryLabel || index % labelInterval == 0) {
                    val rowChar = ('A' + result.wellIndex / 12).toChar()
                    val colNumber = (result.wellIndex % 12) + 1
                    val wellLabel = "$rowChar$colNumber"
                    
                    // 旋转标签，避免重叠
                    canvas.save()
                    canvas.rotate(-45f, barPositionX + barWidth * 0.4f, startY + 5f)
                    canvas.drawText(
                        wellLabel,
                        barPositionX + barWidth * 0.4f,
                        startY + 15f,
                        textPaint
                    )
                    canvas.restore()
                }
                
                // 在柱子顶部显示浓度值
                if (barWidth >= 20f) { // 只在柱子足够宽时显示
                    val valuePaint = android.graphics.Paint().apply {
                        val color = android.graphics.Color.BLACK
                        textSize = 10f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText(
                        String.format(Locale.US, "%.1f", concentration),
                        barPositionX + barWidth * 0.4f,
                        barTop - 5f,
                        valuePaint
                    )
                }
            }
            
            // 显示完整的图例或提示信息
            val footnotePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                textSize = 12f
                textAlign = android.graphics.Paint.Align.LEFT
            }
            canvas.drawText(
                "* " + context.getString(R.string.bitmap_legend_concentration_range_format, maxConcentration.toString(), concentrationUnit),
                startX,
                height - 20f,
                footnotePaint
            )
            
    } else {
            // 如果没有有效结果，显示提示信息
        val noPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
            canvas.drawText(
                context.getString(R.string.no_concentration_data),
                width / 2f,
                height / 2f,
                noPaint
            )
        }

        return@withContext bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
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
 * 从项目分析物配置中获取浓度单位和最大浓度
 * @param project 项目实体
 * @param projectAnalyteJoinRepository 项目分析物关联仓库
 * @return Pair<String, Double> 浓度单位和最大浓度
 */
private suspend fun getProjectConfigValues(
    project: Project,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
): Pair<String, Double> = withContext(Dispatchers.IO) {
    // 获取项目的第一个分析物配置
    val analyteJoin = projectAnalyteJoinRepository.getFirstProjectAnalyteJoin(project.id)
    
    // 如果存在配置，则返回其浓度单位和最大浓度；否则返回默认值
    Pair(
        analyteJoin?.concentrationUnit ?: "ng/ml",
        analyteJoin?.maxConcentration ?: 100.0
    )
}

/**
 * 导出完整的PDF报告
 */
suspend fun exportFullPdfReport(
    context: Context,
    project: Project,
    wellResults: List<WellResult>,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository,
    toastManager: ToastManager
) {
    withContext(Dispatchers.IO) {
        try {
            // 获取配置值
            val (concentrationUnit, maxConcentration) = getProjectConfigValues(project, projectAnalyteJoinRepository)
            
            // 创建临时文件
            val fileName = "report_${project.name}_${System.currentTimeMillis()}.pdf"
            val reportFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            // 使用PdfDocument创建PDF文档
        val document = PdfDocument()
            
            // 报告页面设置
            val pageWidth = 612 // Letter宽度，72 dpi
            val pageHeight = 792 // Letter高度，72 dpi
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

            // 准备所有画笔
            val paintSet = preparePdfPaints()
            val titlePaint = paintSet.titlePaint
            val headerPaint = paintSet.headerPaint
            val textPaint = paintSet.textPaint
            val linePaint = paintSet.linePaint
            
            // 绘制标题
            canvas.drawText(context.getString(R.string.pdf_title_analysis_report), pageWidth/2f, 40f, titlePaint)
            canvas.drawLine(30f, 50f, pageWidth-30f, 50f, linePaint)
            
            // 绘制项目信息部分
            var yPosition = 80f
            canvas.drawText(context.getString(R.string.pdf_header_project_information), 30f, yPosition, headerPaint)
            yPosition += 20f
            yPosition = drawProjectInfo(canvas, context, project, concentrationUnit, maxConcentration, textPaint, yPosition)
            
            // 添加热力图
            val heatmapBitmap = generateHeatmapBitmap(context, project, wellResults, projectAnalyteJoinRepository)
            if (heatmapBitmap != null) {
                yPosition = drawImageSection(canvas, heatmapBitmap, context.getString(R.string.pdf_header_heatmap), 
                                          headerPaint, yPosition, 500f)
            }
            
            // 添加数值分布图
            val valueChartBitmap = generateValueChartBitmap(context, project, wellResults, projectAnalyteJoinRepository)
            if (valueChartBitmap != null) {
                yPosition = drawImageSection(canvas, valueChartBitmap, context.getString(R.string.pdf_header_concentration_distribution), 
                                          headerPaint, yPosition, 500f)
            }
            
            // 绘制数据表格（如果页面空间允许）
            if (yPosition < 650f && wellResults.isNotEmpty()) {
                yPosition = drawResultsTable(canvas, wellResults, context, headerPaint, textPaint, linePaint, 
                                          concentrationUnit, maxConcentration, yPosition)
            }
            
            // 添加页脚
            drawFooter(canvas, context, pageWidth, textPaint, linePaint)
            
            // 完成PDF创建并保存
            document.finishPage(page)
            
            // 将PDF写入文件
            try {
                FileOutputStream(reportFile).use { out ->
                    document.writeTo(out)
                }
                document.close()
                
                // 通过MediaStore更新媒体库
                val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                val contentUri = android.net.Uri.fromFile(reportFile)
                mediaScanIntent.data = contentUri
                context.sendBroadcast(mediaScanIntent)
                
                // 发送成功消息
                withContext(Dispatchers.Main) {
                    toastManager.showToast(
                        context.getString(R.string.pdf_export_success, reportFile.absolutePath),
                        ToastType.SUCCESS
                    )
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toastManager.showToast(
                        context.getString(R.string.pdf_export_error, e.localizedMessage),
                        ToastType.ERROR
                    )
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                toastManager.showToast(
                    context.getString(R.string.pdf_export_error, e.localizedMessage),
                    ToastType.ERROR
                )
            }
        }
    }
}

// PDF画笔集合
private data class PdfPaintSet(
    val titlePaint: android.graphics.Paint,
    val headerPaint: android.graphics.Paint,
    val textPaint: android.graphics.Paint,
    val linePaint: android.graphics.Paint
)

// 准备所有PDF绘制所需的画笔
private fun preparePdfPaints(): PdfPaintSet {
        val titlePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 18f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val headerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 14f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
    }
    
    val linePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 1f
        style = android.graphics.Paint.Style.STROKE
    }
    
    return PdfPaintSet(titlePaint, headerPaint, textPaint, linePaint)
}

// 绘制项目信息部分
private fun drawProjectInfo(
    canvas: android.graphics.Canvas,
    context: Context,
    project: Project,
    concentrationUnit: String,
    maxConcentration: Double,
    textPaint: android.graphics.Paint,
    startY: Float
): Float {
    var y = startY
    
    canvas.drawText(context.getString(R.string.pdf_label_project_name, project.name), 40f, y, textPaint)
    y += 20f
    
    canvas.drawText(context.getString(R.string.pdf_label_creation_date, getFormattedDate(project.createTime.time)), 40f, y, textPaint)
    y += 20f
    
    canvas.drawText(context.getString(R.string.pdf_label_detection_mode, getDetectionModeText(context, project.detectionMode)), 40f, y, textPaint)
    y += 20f
    
    canvas.drawText(context.getString(R.string.pdf_label_recognition_type, getRecognitionTypeText(context, project.recognitionType)), 40f, y, textPaint)
    y += 20f
    
    canvas.drawText(context.getString(R.string.pdf_label_plate_layout, "${project.rows} × ${project.columns}"), 40f, y, textPaint)
    y += 20f
    
    canvas.drawText(context.getString(R.string.pdf_label_max_concentration, "$maxConcentration $concentrationUnit"), 40f, y, textPaint)
    y += 30f
    
    return y
}

// 绘制图像部分（热力图或数值分布图）
private fun drawImageSection(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    title: String,
    headerPaint: android.graphics.Paint,
    startY: Float,
    imageWidth: Float
): Float {
    var y = startY
    
    val scaleFactor = imageWidth / bitmap.width
    val imageHeight = bitmap.height * scaleFactor
    
    canvas.drawText(title, 30f, y, headerPaint)
    y += 20f
    
    val rectF = android.graphics.RectF(30f, y, 30f + imageWidth, y + imageHeight)
    canvas.drawBitmap(bitmap, null, rectF, null)
    y += imageHeight + 30f
    
    return y
}

// 绘制结果数据表格
private fun drawResultsTable(
    canvas: android.graphics.Canvas,
    wellResults: List<WellResult>,
    context: Context,
    headerPaint: android.graphics.Paint,
    textPaint: android.graphics.Paint,
    linePaint: android.graphics.Paint,
    concentrationUnit: String,
    maxConcentration: Double,
    startY: Float
): Float {
    var y = startY
    
    canvas.drawText(context.getString(R.string.pdf_header_result_data), 30f, y, headerPaint)
    y += 20f
    
    // 表格头部
    val colWidth = 85f
    val rowHeight = 20f
    var x = 40f
    
    // 绘制表头
    val headers = listOf(
        R.string.pdf_table_header_well,
        R.string.pdf_table_header_row,
        R.string.pdf_table_header_column,
        R.string.pdf_table_header_raw_value,
        R.string.pdf_table_header_percentage,
        R.string.pdf_table_header_concentration
    )
    
    headers.forEach { headerRes ->
        canvas.drawText(context.getString(headerRes), x, y, headerPaint)
        x += colWidth
    }
    y += rowHeight
    
    // 绘制水平分隔线
    canvas.drawLine(40f, y - rowHeight + 15f, x, y - rowHeight + 15f, linePaint)
    
    // 显示数据行（最多显示20行以避免超出页面）
    val sortedResults = wellResults.sortedBy { it.wellIndex }.take(20)
    for (result in sortedResults) {
        // 在每行循环中重置X坐标
        x = 40f
        
        // 孔位标识
        val rowChar = ('A' + result.wellIndex / 12).toChar()
        val colNumber = (result.wellIndex % 12) + 1
        
        // 绘制孔位标识
        canvas.drawText("$rowChar$colNumber", x, y, textPaint)
        x += colWidth
        
        // 行号
        canvas.drawText(rowChar.toString(), x, y, textPaint)
        x += colWidth
        
        // 列号
        canvas.drawText(colNumber.toString(), x, y, textPaint)
        x += colWidth
        
        // 原始值
        x = drawCellValue(canvas, result.pixelValueJson, x, y, textPaint, colWidth) { jsonValue ->
            try {
                val numericValue = jsonValue.toDoubleOrNull() 
                    ?: org.json.JSONObject(jsonValue).optDouble("average", 0.0)
                String.format(Locale.US, "%.2f", numericValue)
            } catch (e: Exception) {
                jsonValue.take(10) + "..."
            }
        }
        
        // 百分比
        x = drawCellValue(canvas, result.predictedConcentration, x, y, textPaint, colWidth) { value ->
            String.format(Locale.US, "%.2f%%", value)
        }
        
        // 浓度
        drawCellValue(canvas, result.predictedConcentration, x, y, textPaint, colWidth) { value ->
            val resultConcentration = (value / 100.0) * maxConcentration
            String.format(Locale.US, "%.2f %s", resultConcentration, concentrationUnit)
        }
        
        y += rowHeight
        
        // 检查是否超出页面范围
        if (y > 750f) break
    }
    
    // 如果结果数量超过显示限制，添加说明
    if (wellResults.size > 20) {
        canvas.drawText(context.getString(R.string.pdf_note_more_results, wellResults.size - 20), 40f, y, textPaint)
        y += rowHeight
    }
    
    return y
}

// 绘制单元格值
private inline fun <T> drawCellValue(
    canvas: android.graphics.Canvas,
    value: T?,
    x: Float,
    y: Float,
    textPaint: android.graphics.Paint,
    colWidth: Float,
    formatter: (T) -> String
): Float {
    value?.let {
        canvas.drawText(formatter(it), x, y, textPaint)
    } ?: canvas.drawText("-", x, y, textPaint)
    return x + colWidth
}

// 绘制页脚
private fun drawFooter(
    canvas: android.graphics.Canvas,
    context: Context,
    pageWidth: Int,
    textPaint: android.graphics.Paint,
    linePaint: android.graphics.Paint
) {
    canvas.drawLine(30f, 760f, pageWidth-30f, 760f, linePaint)
    canvas.drawText(context.getString(R.string.pdf_footer_generated_date, getFormattedDateTime(System.currentTimeMillis())), 30f, 775f, textPaint)
    
        val footerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 10f
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    canvas.drawText(context.getString(R.string.pdf_footer_app_name), pageWidth-30f, 775f, footerPaint)
}

/**
 * 格式化日期为字符串表示
 */
private fun getFormattedDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 格式化日期时间为字符串表示
 */
private fun getFormattedDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 获取检测模式的文本表示
 */
private fun getDetectionModeText(context: Context, mode: String): String {
    return when (mode) {
        "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
        "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
        else -> mode
    }
}

/**
 * 获取识别类型的文本表示
 */
private fun getRecognitionTypeText(context: Context, type: String): String {
    return when (type) {
        "AUTO" -> context.getString(R.string.auto_recognition)
        "MANUAL" -> context.getString(R.string.manual_crop)
        else -> type
    }
}