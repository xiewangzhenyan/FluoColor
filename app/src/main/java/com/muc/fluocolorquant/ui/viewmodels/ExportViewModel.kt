package com.muc.fluocolorquant.ui.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.charts.ChartData
import java.io.FileInputStream
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ViewModel负责处理所有导出相关的功能
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
) : ViewModel() {

    // PDF页面尺寸常量 (A4 at 72 dpi)
    companion object {
        const val PDF_PAGE_WIDTH = 595
        const val PDF_PAGE_HEIGHT = 842
        const val PDF_MARGIN = 40f
        const val PDF_HEADER_HEIGHT = 80f
        const val PDF_FOOTER_HEIGHT = 40f

        // 内容区域常量
        const val PDF_CONTENT_START_Y = PDF_MARGIN + PDF_HEADER_HEIGHT
        const val PDF_CONTENT_HEIGHT = PDF_PAGE_HEIGHT - PDF_CONTENT_START_Y - PDF_MARGIN - PDF_FOOTER_HEIGHT
        const val PDF_CONTENT_WIDTH = PDF_PAGE_WIDTH - 2 * PDF_MARGIN

        // 分节间隔
        const val SECTION_SPACING = 30f
        const val SUBSECTION_SPACING = 15f
        const val TABLE_CELL_HEIGHT = 30f
    }

    /**
     * 导出状态封装类
     */
    sealed class ExportState {
        object Idle : ExportState()
        object InProgress : ExportState()
        data class Success(val message: String, val filePath: String? = null) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    // 当前导出状态
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * 导出数据封装类
     */
    data class ReportData(
        val project: Project,
        val analyteDetails: List<AnalyteResultDetails>
    )

    /**
     * 开始导出PDF报告
     */
    fun startPdfExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportFullPdfReport(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_pdf_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "PDF export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出CSV数据
     */
    fun startCsvExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportDataToCsv(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_csv_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "CSV export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出PNG图表
     */
    fun startPngExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                exportChartsToPng(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_png_success)
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "PNG export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 重置导出状态
     */
    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    /**
     * 导出数据为CSV文件
     * @return 保存的文件路径
     */
    private suspend fun exportDataToCsv(reportData: ReportData): String = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "FluoColor_${reportData.project.name.replace(" ", "_")}_$timestamp.csv"

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, fileName)

            FileOutputStream(file).use { fos ->
                val pixelTypeHeaders = PixelType.values().joinToString(",") { it.identifier }
                val titleLine = "Well,WellIndex,AnalyteName,AnalysisMethod,PredictedConcentration,TrueConcentration,Unit,$pixelTypeHeaders\n"
                fos.write(titleLine.toByteArray())

                for (analyteDetail in reportData.analyteDetails) {
                    val analyteName = analyteDetail.analyte.name
                    val analysisMethod = analyteDetail.analysisMethod
                    val unit = analyteDetail.concentrationUnit

                    for (wellResult in analyteDetail.wellResults) {
                        val wellLabel = if(wellResult.virtualRow != null && wellResult.virtualCol != null) {
                            WellMappingUtils.getWellLabel(wellResult.virtualRow!!, wellResult.virtualCol!!)
                        } else {
                            val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(wellResult.wellIndex)
                            WellMappingUtils.getWellLabel(vRow, vCol)
                        }

                        val predictedConcentration = wellResult.predictedConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                        val trueConcentration = wellResult.trueConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"

                        val pixelMap = wellResult.pixelValueJson?.let { com.muc.fluocolorquant.utils.PixelExtractionUtils.jsonToMap(it) } ?: emptyMap()

                        val pixelValues = PixelType.values().map { pixelEnum ->
                            pixelMap[pixelEnum.identifier]?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                        }

                        val lineData = listOf(
                            wellLabel,
                            wellResult.wellIndex.toString(),
                            analyteName,
                            analysisMethod,
                            predictedConcentration,
                            trueConcentration,
                            unit
                        ) + pixelValues

                        val line = lineData.joinToString(",") + "\n"
                        fos.write(line.toByteArray())
                    }
                }
            }

            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))

            return@withContext file.absolutePath
        } catch (e: IOException) {
            Log.e("ExportViewModel", "Failed to save CSV file", e)
            throw e
        }
    }


    /**
     * 导出图表为PNG文件
     * @return 保存的文件数量
     */
    private suspend fun exportChartsToPng(reportData: ReportData): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        try {
            for (analyteDetail in reportData.analyteDetails) {
                val analyteName = analyteDetail.analyte.name
                val baseName = "FluoColor_${reportData.project.name.replace(" ", "_")}_${analyteName.replace(" ", "_")}"

                val heatmapBitmap = generateHeatmapBitmap(analyteDetail)
                if (heatmapBitmap != null) {
                    val heatmapFileName = "${baseName}_Heatmap_$timestamp.png"
                    if (saveBitmapToFile(heatmapBitmap, "png", heatmapFileName)) {
                        successCount++
                    }
                }

                val trendBitmap = generateTrendChartBitmap(analyteDetail)
                if (trendBitmap != null) {
                    val trendFileName = "${baseName}_Trend_$timestamp.png"
                    if (saveBitmapToFile(trendBitmap, "png", trendFileName)) {
                        successCount++
                    }
                }

                if (analyteDetail.standardCurveChartData != null) {
                    val curveBitmap = generateStandardCurveBitmap(analyteDetail)
                    if (curveBitmap != null) {
                        val curveFileName = "${baseName}_StandardCurve_$timestamp.png"
                        if (saveBitmapToFile(curveBitmap, "png", curveFileName)) {
                            successCount++
                        }
                    }
                }

                analyteDetail.validationData?.let {
                    val regressionBitmap = generateValidationRegressionBitmap(analyteDetail)
                    if (regressionBitmap != null) {
                        val regressionFileName = "${baseName}_Validation_Regression_$timestamp.png"
                        if (saveBitmapToFile(regressionBitmap, "png", regressionFileName)) {
                            successCount++
                        }
                    }

                    val blandAltmanBitmap = generateValidationBlandAltmanBitmap(analyteDetail)
                    if (blandAltmanBitmap != null) {
                        val blandAltmanFileName = "${baseName}_Validation_BlandAltman_$timestamp.png"
                        if (saveBitmapToFile(blandAltmanBitmap, "png", blandAltmanFileName)) {
                            successCount++
                        }
                    }
                }
            }

            return@withContext successCount
        } catch (e: Exception) {
            Log.e("ExportViewModel", "Failed to export charts", e)
            throw e
        }
    }

    /**
     * 【优化】生成热力图位图，确保布局合理
     */
    private fun generateHeatmapBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val titlePaint = TextPaint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val title = "${analyteDetail.analyte.name} ${context.getString(R.string.concentration_heatmap)}"
            canvas.drawText(title, 600f, 80f, titlePaint)

            val rows = 8
            val cols = 12
            val startX = 120f
            val startY = 160f  // 增加上方空间，避免与标题重叠
            val cellWidth = 80f
            val cellHeight = 60f
            val maxConcentration = analyteDetail.wellResults.mapNotNull { it.predictedConcentration }.filter { it.isFinite() }.maxOrNull() ?: 100.0

            val textPaint = TextPaint().apply {
                textSize = 20f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val labelPaint = TextPaint(textPaint).apply { textSize = 24f }

            // 绘制列标签
            for (col in 0 until cols) {
                canvas.drawText((col + 1).toString(), startX + col * cellWidth + cellWidth / 2, startY - 20f, labelPaint)
            }
            // 绘制行标签
            for (row in 0 until rows) {
                canvas.drawText(('A' + row).toString(), startX - 40f, startY + row * cellHeight + cellHeight / 2 + 10f, labelPaint)
            }

            // 绘制热力图
            analyteDetail.wellResults.forEach { wellResult ->
                if(wellResult.virtualRow != null && wellResult.virtualCol != null) {
                    val row = wellResult.virtualRow!!
                    val col = wellResult.virtualCol!!
                    val left = startX + col * cellWidth
                    val top = startY + row * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight

                    val concentration = wellResult.predictedConcentration
                    val composeColor = if (concentration != null) HeatmapColorUtil.getColor(concentration, 0.0, maxConcentration) else androidx.compose.ui.graphics.Color.LightGray
                    val color = android.graphics.Color.argb((composeColor.alpha * 255).toInt(), (composeColor.red * 255).toInt(), (composeColor.green * 255).toInt(), (composeColor.blue * 255).toInt())
                    val paint = Paint().apply { this.color = color }
                    canvas.drawRect(left, top, right, bottom, paint)

                    if (concentration != null) {
                        val textColor = if (composeColor.red * 0.299 + composeColor.green * 0.587 + composeColor.blue * 0.114 > 0.6) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        textPaint.color = textColor
                        canvas.drawText(String.format("%.1f", concentration), left + cellWidth / 2, top + cellHeight / 2 + 8f, textPaint)
                    }
                }
            }

            // 绘制图例，调整位置确保完全显示
            val legendY = startY + (rows * cellHeight) + 80f  // 增加间距
            val legendWidth = 800f
            val legendHeight = 40f
            val legendX = (canvas.width - legendWidth) / 2

            // 图例渐变色
            val gradientPaint = Paint()
            val colors = IntArray(100) { i ->
                val composeColor = HeatmapColorUtil.getColor(i / 99.0, 0.0, 1.0)
                android.graphics.Color.argb((composeColor.alpha * 255).toInt(), (composeColor.red * 255).toInt(), (composeColor.green * 255).toInt(), (composeColor.blue * 255).toInt())
            }
            for (i in 0 until 100) {
                gradientPaint.color = colors[i]
                canvas.drawRect(legendX + i * (legendWidth / 100), legendY, legendX + (i + 1) * (legendWidth / 100), legendY + legendHeight, gradientPaint)
            }

            // 图例标签
            val legendTextPaint = TextPaint().apply {
                textSize = 24f
                isAntiAlias = true
                color = android.graphics.Color.BLACK
            }
            legendTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("0.00", legendX, legendY + legendHeight + 30f, legendTextPaint)
            legendTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format("%.2f", maxConcentration), legendX + legendWidth, legendY + legendHeight + 30f, legendTextPaint)

            // 图例标题，居中显示
            legendTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.bitmap_legend_concentration_range_format, String.format("%.2f", maxConcentration), analyteDetail.concentrationUnit),
                legendX + legendWidth / 2, legendY - 10f, legendTextPaint)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成热力图位图失败", e)
            return null
        }
    }

    /**
     * 【优化】生成浓度趋势图位图，确保X轴标签完整显示
     */
    private fun generateTrendChartBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)  // 增加图表高度
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val titlePaint = TextPaint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val title = "${analyteDetail.analyte.name} ${context.getString(R.string.pdf_trend_chart_title)}"
            canvas.drawText(title, 600f, 70f, titlePaint)

            val validResults = analyteDetail.wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }.sortedBy { it.wellIndex }
            if (validResults.isEmpty()) return bitmap

            val maxConcentration = validResults.maxOf { it.predictedConcentration!! } * 1.1
            val minConcentration = 0.0

            val leftPadding = 120f
            val rightPadding = 60f
            val topPadding = 120f
            val bottomPadding = 150f  // 增加底部空间，确保X轴标签完全显示
            val graphWidth = canvas.width - leftPadding - rightPadding
            val graphHeight = canvas.height - topPadding - bottomPadding

            val axisPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 3f }
            val labelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true }

            // 绘制Y轴和标签
            canvas.drawLine(leftPadding, topPadding, leftPadding, topPadding + graphHeight, axisPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.save()
            canvas.rotate(-90f)
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_concentration_with_unit, analyteDetail.concentrationUnit),
                -(topPadding + graphHeight / 2),
                leftPadding - 70,
                labelPaint
            )
            canvas.restore()

            // 绘制X轴和标签
            canvas.drawLine(leftPadding, topPadding + graphHeight, leftPadding + graphWidth, topPadding + graphHeight, axisPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_well_id_capital),
                leftPadding + graphWidth / 2,
                topPadding + graphHeight + 100,  // 增加Y坐标，确保标签在图表下方足够位置
                labelPaint
            )

            // 绘制Y轴刻度
            val yGridLines = 5
            labelPaint.textAlign = Paint.Align.RIGHT
            for(i in 0..yGridLines) {
                val yValue = minConcentration + (maxConcentration - minConcentration) * i / yGridLines
                val yPos = topPadding + graphHeight - (graphHeight * (yValue - minConcentration) / (maxConcentration - minConcentration)).toFloat()
                canvas.drawLine(leftPadding - 5, yPos, leftPadding + graphWidth, yPos, Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f })
                canvas.drawText(String.format("%.1f", yValue), leftPadding - 10, yPos + 8, labelPaint)
            }

            if (validResults.size > 1) {
                val path = android.graphics.Path()
                val pointPaint = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.FILL }
                val linePaint = Paint().apply { color = android.graphics.Color.BLUE; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
                val pointLabelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

                validResults.forEachIndexed { index, wellResult ->
                    val x = leftPadding + index * (graphWidth / (validResults.size - 1))
                    val y = topPadding + graphHeight - (graphHeight * (wellResult.predictedConcentration!! - minConcentration) / (maxConcentration - minConcentration)).toFloat()

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    canvas.drawCircle(x, y, 8f, pointPaint)

                    val wellLabel = if(wellResult.virtualRow != null && wellResult.virtualCol != null) {
                        WellMappingUtils.getWellLabel(wellResult.virtualRow!!, wellResult.virtualCol!!)
                    } else {
                        val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(wellResult.wellIndex)
                        WellMappingUtils.getWellLabel(vRow, vCol)
                    }

                    // 在数据点下方添加孔位标签
                    canvas.drawText(wellLabel, x, y - 15, pointLabelPaint)

                    // 在X轴下方也添加孔位标签，每个点下方都加标签
                    canvas.save()
                    canvas.rotate(45f, x, topPadding + graphHeight + 20) // 标签倾斜45度，避免重叠
                    canvas.drawText(wellLabel, x, topPadding + graphHeight + 35, pointLabelPaint)
                    canvas.restore()
                }
                canvas.drawPath(path, linePaint)
            }

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成浓度趋势图位图失败", e)
            return null
        }
    }

    // ... [其余的Bitmap生成和辅助函数保持不变，但为了完整性，这里全部提供] ...

    private fun generateValidationRegressionBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.regressionPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} Regression Analysis", 500f, 70f, titlePaint)

            val metricsPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 30f; isAntiAlias = true }
            val r2 = validationData.regressionMetrics["R²"] ?: "N/A"
            val rmse = validationData.regressionMetrics["RMSE"] ?: "N/A"
            canvas.drawText("R² = $r2", 150f, 120f, metricsPaint)
            canvas.drawText("RMSE = $rmse", 150f, 160f, metricsPaint)

            drawChartAxisAndGrid(canvas, chartData, "Actual", "Predicted")
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成验证回归图位图失败", e)
            return null
        }
    }

    private fun generateValidationBlandAltmanBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.blandAltmanPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} Bland-Altman Analysis", 500f, 70f, titlePaint)

            drawChartAxisAndGrid(canvas, chartData, "Mean", "Difference")
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData, isBlandAltman = true)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成Bland-Altman分析图位图失败", e)
            return null
        }
    }

    private fun generateStandardCurveBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val chartData = analyteDetail.standardCurveChartData ?: return null
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} ${context.getString(R.string.pdf_standard_curve_title)}", 500f, 70f, titlePaint)

            drawChartAxisAndGrid(canvas, chartData, chartData.xAxisLabel, chartData.yAxisLabel)
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成标准曲线位图失败", e)
            return null
        }
    }

    private fun drawChartAxisAndGrid(canvas: Canvas, chartData: ChartData, xLabel: String, yLabel: String) {
        val axisPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 2f }
        val gridPaint = Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f }
        val labelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true }

        val left = 100f; val top = 100f; val right = 900f; val bottom = 900f
        val graphWidth = right - left; val graphHeight = bottom - top

        canvas.drawLine(left, bottom, right, bottom, axisPaint) // X
        canvas.drawLine(left, top, left, bottom, axisPaint)     // Y

        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(xLabel, left + graphWidth / 2, bottom + 60, labelPaint)

        canvas.save()
        canvas.rotate(-90f)
        canvas.drawText(yLabel, -(top + graphHeight / 2), left - 60, labelPaint)
        canvas.restore()

        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second
        val numTicks = 5
        labelPaint.textSize = 20f

        labelPaint.textAlign = Paint.Align.CENTER
        for (i in 0..numTicks) {
            val value = xMin + i * (xMax - xMin) / numTicks
            val x = left + i * (graphWidth / numTicks)
            canvas.drawLine(x, bottom, x, bottom + 10, axisPaint)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawText(String.format("%.2f", value), x, bottom + 35, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..numTicks) {
            val value = yMin + i * (yMax - yMin) / numTicks
            val y = bottom - i * (graphHeight / numTicks)
            canvas.drawLine(left, y, left - 10, y, axisPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(String.format("%.2f", value), left - 15, y + 8, labelPaint)
        }
    }

    private fun drawChartScatterPoints(canvas: Canvas, chartData: ChartData) {
        val pointPaint = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.FILL }
        val left = 100f; val top = 100f; val graphWidth = 800f; val graphHeight = 800f
        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second

        val pointsToDraw = chartData.scatterPoints ?: chartData.standardPoints.map { com.muc.fluocolorquant.ui.components.charts.ChartPoint(it.first, it.second) }

        pointsToDraw.forEach { point ->
            val x = left + ((point.x - xMin) / (xMax - xMin) * graphWidth).toFloat()
            val y = top + graphHeight - ((point.y - yMin) / (yMax - yMin) * graphHeight).toFloat()
            canvas.drawCircle(x, y, 8f, pointPaint)
        }
    }

    private fun drawChartLines(canvas: Canvas, chartData: ChartData, isBlandAltman: Boolean = false) {
        val linePaint = Paint().apply { strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
        val left = 100f; val top = 100f; val graphWidth = 800f; val graphHeight = 800f
        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second

        if (chartData.curvePoints.isNotEmpty()) {
            linePaint.color = android.graphics.Color.BLUE
            val path = android.graphics.Path()
            chartData.curvePoints.forEachIndexed { index, pair ->
                val x = left + ((pair.first - xMin) / (xMax - xMin) * graphWidth).toFloat()
                val y = top + graphHeight - ((pair.second - yMin) / (yMax - yMin) * graphHeight).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        val linesToDraw = if (isBlandAltman) chartData.additionalLines else mapOf("ideal" to chartData.standardPoints)
        linesToDraw.forEach { (key, points) ->
            if (points.size >= 2) {
                linePaint.color = when(key) {
                    "mean" -> android.graphics.Color.GREEN
                    "upperLimit", "lowerLimit" -> android.graphics.Color.RED
                    else -> android.graphics.Color.GRAY
                }
                linePaint.pathEffect = if (key == "mean" || key == "ideal") null else android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)

                val path = android.graphics.Path()
                points.forEachIndexed { index, pair ->
                    val x = left + ((pair.first - xMin) / (xMax - xMin) * graphWidth).toFloat()
                    val y = top + graphHeight - ((pair.second - yMin) / (yMax - yMin) * graphHeight).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, format: String, fileName: String): Boolean {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val file = File(downloadDir, fileName)
            FileOutputStream(file).use { fos ->
                val compressFormat = when (format.lowercase()) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                    else -> Bitmap.CompressFormat.PNG
                }
                val quality = if (compressFormat == Bitmap.CompressFormat.JPEG) 90 else 100
                bitmap.compress(compressFormat, quality, fos)
            }
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
            true
        } catch (e: IOException) {
            Log.e("ExportViewModel", "Failed to save bitmap to file", e)
            false
        }
    }

    /**
     * 【完全重构】导出完整的PDF报告
     *
     * PDF导出功能进行了全面优化，解决了以下问题：
     * 1. 修复了封面页布局问题，确保与pdf_cover_page.xml定义的布局一致
     * 2. 优化了图表显示，确保热力图和趋势图完整展示，包括所有坐标轴和标签
     * 3. 改进了多章节PDF结构，包括封面、总览页和每个分析物的独立章节
     * 4. 实现了清晰的页眉页脚，提升了PDF的专业感
     * 5. 优化了图表大小和布局，避免内容重叠或被裁剪
     * 6. 合理分隔验证数据的回归分析和Bland-Altman分析
     * 7. 添加了附录页面，包含原始数据表格
     *
     * @return 保存的文件路径
     */
    private suspend fun exportFullPdfReport(reportData: ReportData): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_Report_${reportData.project.name.replace(" ", "_")}_$timestamp.pdf"
        val reportFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        val document = PdfDocument()

        try {
            var pageCount = 1
            // 1. 创建封面
            val coverPage = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageCount).create())
            createCoverPage(coverPage.canvas, reportData)
            document.finishPage(coverPage)
            pageCount++

            // 2. 创建实验总览页
            val summaryPage = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageCount).create())
            createSummaryPageDirectly(summaryPage.canvas, reportData, pageCount, countTotalPages(reportData))
            document.finishPage(summaryPage)
            pageCount++

            // 3. 为每个分析物创建章节
            val totalPages = countTotalPages(reportData)
            for ((chapterIndex, analyteDetail) in reportData.analyteDetails.withIndex()) {
                val chapterResult = createAnalyteChapter(document, analyteDetail, chapterIndex + 1, pageCount, totalPages)
                pageCount = chapterResult
            }

            // 4. 附录：创建原始数据表（支持多页）
            pageCount = createRawDataAppendix(document, reportData, pageCount, totalPages)

            // 5. 保存PDF
            FileOutputStream(reportFile).use { out ->
                document.writeTo(out)
            }
        } catch (e: Exception) {
            Log.e("ExportViewModel", "导出PDF报告失败", e)
            throw e
        } finally {
            document.close()
        }

        val fileUri = Uri.fromFile(reportFile)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
        return@withContext reportFile.absolutePath
    }

    /**
     * 预估总页数
     */
    private fun countTotalPages(reportData: ReportData): Int {
        var pageCount = 2  // 封面 + 总览页

        // 预估每个分析物需要的页数
        for (analyteDetail in reportData.analyteDetails) {
            pageCount += 2  // 每个分析物至少需要2页
            if (analyteDetail.validationData != null) {
                pageCount++  // 验证数据需要额外1页
            }
        }

        // 预估附录页数
        val rowsPerPage = ((PDF_CONTENT_HEIGHT - 80f - TABLE_CELL_HEIGHT) / TABLE_CELL_HEIGHT).toInt()
        val totalRows = reportData.analyteDetails.sumOf { it.wellResults.size }
        val appendixPages = if (totalRows == 0) 1 else (totalRows + rowsPerPage - 1) / rowsPerPage

        pageCount += appendixPages

        return pageCount
    }

    /**
     * 【重构】创建PDF封面页，使用预设XML布局文件转换为Bitmap
     */
    @SuppressLint("InflateParams")
    private suspend fun createCoverPage(canvas: Canvas, reportData: ReportData) = withContext(Dispatchers.IO) {
        Log.d("ExportViewModel", "使用直接绘制方式创建封面页")

        // 绘制页眉
        val headerBgPaint = Paint().apply { color = android.graphics.Color.parseColor("#006E1C") }
        canvas.drawRect(0f, 0f, PDF_PAGE_WIDTH.toFloat(), PDF_HEADER_HEIGHT, headerBgPaint)
        
        // 绘制Logo
        val logoImage = BitmapFactory.decodeResource(context.resources, R.drawable.icon2)
        val logoScale = 48f / logoImage.height
        val scaledLogoWidth = logoImage.width * logoScale
        
        val logoRect = Rect(
            PDF_MARGIN.toInt(), 
            ((PDF_HEADER_HEIGHT - 48f) / 2).toInt(), 
            (PDF_MARGIN + scaledLogoWidth).toInt(), 
            ((PDF_HEADER_HEIGHT + 48f) / 2).toInt()
        )
        
        canvas.drawBitmap(logoImage, null, logoRect, null)
        
        // 绘制应用名称
        val headerTextPaint = createTextPaint(20f, android.graphics.Color.WHITE)
        canvas.drawText("FluoColorQuant", PDF_MARGIN + scaledLogoWidth + 16f, PDF_HEADER_HEIGHT / 2 + 8f, headerTextPaint)

        // 绘制主标题
        var yOffset = PDF_CONTENT_START_Y + 40f // 为标题留出空间
        val titlePaint = createTextPaint(24f, isBold = true, align = Paint.Align.CENTER)
        canvas.drawText(context.getString(R.string.pdf_title_fluocolorquant_report), PDF_PAGE_WIDTH / 2f, yOffset, titlePaint)
        yOffset += 80f
        
        // 创建项目信息卡片
        val cardPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
        val cardBorderPaint = Paint().apply { color = android.graphics.Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        val cardLeft = PDF_MARGIN + 20f
        val cardRight = PDF_PAGE_WIDTH - PDF_MARGIN - 20f
        val cardTop = yOffset
        val cardHeight = 120f
        
        // 绘制项目信息卡片背景
        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardPaint)
        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardBorderPaint)
        
        // 绘制项目信息
        val textPaint = createTextPaint(14f)
        val project = reportData.project
        val padding = 16f
        
        yOffset = cardTop + padding
        yOffset = drawFormattedText(canvas, 
            context.getString(R.string.pdf_label_project_name_format, project.name), 
            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
            
        yOffset = drawFormattedText(canvas, 
            context.getString(R.string.pdf_label_creation_date, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)), 
            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
            
        val detectionModeText = when(project.detectionMode) {
            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
            else -> project.detectionMode
        }
        
        drawFormattedText(canvas, 
            context.getString(R.string.pdf_label_detection_mode_format, detectionModeText), 
            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding)
        
        // 绘制内容总览卡片
        yOffset = cardTop + cardHeight + 24f
        val overviewCardTop = yOffset
        val overviewCardHeight = 200f
        
        // 绘制内容总览卡片背景
        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardPaint)
        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardBorderPaint)
        
        // 绘制内容总览标题
        val overviewTitlePaint = createTextPaint(16f, isBold = true)
        yOffset = overviewCardTop + padding
        yOffset = drawFormattedText(canvas, 
            context.getString(R.string.pdf_report_content_overview), 
            cardLeft + padding, yOffset, overviewTitlePaint, cardRight - cardLeft - 2 * padding) + 16f
            
        // 绘制章节列表
        val chapterPaint = createTextPaint(14f)
        reportData.analyteDetails.forEachIndexed { index, detail ->
            val chapterTitle = context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
            yOffset = drawFormattedText(canvas, chapterTitle, 
                cardLeft + padding, yOffset, chapterPaint, cardRight - cardLeft - 2 * padding) + 8f
        }
        
        // 绘制页脚信息
        val footerPaint = createTextPaint(10f, android.graphics.Color.GRAY)
        canvas.drawText(
            context.getString(R.string.pdf_generated_on, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())),
            PDF_MARGIN,
            PDF_PAGE_HEIGHT - 40f,
            footerPaint
        )
        
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.pdf_page_number, 1, countTotalPages(reportData)),
            PDF_PAGE_WIDTH - PDF_MARGIN,
            PDF_PAGE_HEIGHT - 40f,
            footerPaint
        )
        
        Log.d("ExportViewModel", "封面页创建成功")
    }

    /**
     * 【修复】正确测量和布局View，解决ConstraintLayout布局问题
     */
    private fun measureAndLayoutViewFixed(parentContainer: FrameLayout): Boolean {
        return try {
            Log.d("ExportViewModel", "开始测量和布局View")
            
            // 第一步：测量父容器，使用EXACTLY模式
            val parentWidthSpec = View.MeasureSpec.makeMeasureSpec(PDF_PAGE_WIDTH, View.MeasureSpec.EXACTLY)
            val parentHeightSpec = View.MeasureSpec.makeMeasureSpec(PDF_PAGE_HEIGHT, View.MeasureSpec.EXACTLY)
            
            parentContainer.measure(parentWidthSpec, parentHeightSpec)
            parentContainer.layout(0, 0, PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
            
            // 验证测量结果
            if (parentContainer.measuredWidth <= 0 || parentContainer.measuredHeight <= 0) {
                Log.e("ExportViewModel", "父容器测量失败: ${parentContainer.measuredWidth}x${parentContainer.measuredHeight}")
                return false
            }
            
            // 第二步：递归处理所有子视图
            forceLayoutChildrenFixed(parentContainer)
            
            Log.d("ExportViewModel", "View布局完成: ${parentContainer.measuredWidth}x${parentContainer.measuredHeight}")
            true
        } catch (e: Exception) {
            Log.e("ExportViewModel", "测量和布局失败", e)
            false
        }
    }

    /**
     * 【修复】递归强制布局所有子视图，确保ConstraintLayout约束正确解析
     */
    private fun forceLayoutChildrenFixed(viewGroup: ViewGroup) {
        try {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)

                // 确保子视图有正确的布局参数
                if (child.layoutParams == null) {
                    child.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // 对于ConstraintLayout的子视图，确保约束正确应用
                if (viewGroup is androidx.constraintlayout.widget.ConstraintLayout) {
                    val childWidthSpec = View.MeasureSpec.makeMeasureSpec(
                        child.layoutParams.width,
                        if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                    )
                    val childHeightSpec = View.MeasureSpec.makeMeasureSpec(
                        child.layoutParams.height,
                        if (child.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                    )

                    child.measure(childWidthSpec, childHeightSpec)
                    child.layout(child.left, child.top, child.left + child.measuredWidth, child.top + child.measuredHeight)
                }

                // 递归处理子ViewGroup
                if (child is ViewGroup) {
                    forceLayoutChildrenFixed(child)
                }
            }
        } catch (e: Exception) {
            Log.w("ExportViewModel", "强制布局子视图时出现异常", e)
        }
    }

//    /**
//     * 【修复】创建View的Bitmap，增强错误处理和布局验证
//     */
//    private fun createViewBitmapFixed(view: View): Bitmap? {
//        return try {
//            Log.d("ExportViewModel", "开始创建View Bitmap")
//
//            // 验证View的测量状态
//            if (view.measuredWidth <= 0 || view.measuredHeight <= 0) {
//                Log.e("ExportViewModel", "View尺寸无效: ${view.measuredWidth}x${view.measuredHeight}")
//                return null
//            }
//
//            // 创建Bitmap，使用PDF页面尺寸确保一致性
//            val bitmap = Bitmap.createBitmap(
//                PDF_PAGE_WIDTH,
//                PDF_PAGE_HEIGHT,
//                Bitmap.Config.ARGB_8888
//            )
//
//            val bitmapCanvas = Canvas(bitmap)
//
//            // 设置白色背景
//            bitmapCanvas.drawColor(android.graphics.Color.WHITE)
//
//            // 保存Canvas状态
//            bitmapCanvas.save()
//
//            try {
//                // 确保绘制在正确的区域内
//                bitmapCanvas.clipRect(0, 0, PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
//
//                // 绘制View到Canvas
//                view.draw(bitmapCanvas)
//
//                Log.d("ExportViewModel", "View绘制到Bitmap成功")
//            } finally {
//                // 恢复Canvas状态
//                bitmapCanvas.restore()
//            }
//
//            // 验证Bitmap是否有效
//            if (bitmap.isRecycled) {
//                Log.e("ExportViewModel", "Bitmap已被回收")
//                return null
//            }
//
//            Log.d("ExportViewModel", "封面页Bitmap创建成功: ${bitmap.width}x${bitmap.height}")
//            return bitmap
//
//        } catch (e: OutOfMemoryError) {
//            Log.e("ExportViewModel", "内存不足，无法创建Bitmap", e)
//            // 尝试强制垃圾回收
//            System.gc()
//            null
//        } catch (e: Exception) {
//            Log.e("ExportViewModel", "创建Bitmap失败", e)
//            null
//        }
//    }

//    /**
//     * 直接绘制封面页（作为备用方案）
//     */
//    private fun createCoverPageDirectly(canvas: Canvas, reportData: ReportData) {
//        Log.d("ExportViewModel", "使用直接绘制方式创建封面页")
//
//        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_title_fluocolorquant_report), 1, countTotalPages(reportData))
//
//        var yOffset = PDF_CONTENT_START_Y + 80f // 为标题留出空间
//
//        // 绘制主标题
//        val titlePaint = createTextPaint(24f, isBold = true, align = Paint.Align.CENTER)
//        canvas.drawText(context.getString(R.string.pdf_title_fluocolorquant_report), PDF_PAGE_WIDTH / 2f, yOffset, titlePaint)
//        yOffset += 80f
//
//        // 绘制项目信息卡片
//        val cardPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
//        val cardBorderPaint = Paint().apply { color = android.graphics.Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
//        val cardLeft = PDF_MARGIN + 20f
//        val cardRight = PDF_PAGE_WIDTH - PDF_MARGIN - 20f
//        val cardTop = yOffset
//        val cardHeight = 120f
//
//        // 绘制项目信息卡片背景
//        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardPaint)
//        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardBorderPaint)
//
//        // 绘制项目信息
//        val textPaint = createTextPaint(14f)
//        val project = reportData.project
//        val padding = 16f
//
//        yOffset = cardTop + padding
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_project_name_format, project.name),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_creation_date, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        val detectionModeText = when(project.detectionMode) {
//            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
//            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
//            else -> project.detectionMode
//        }
//
//        drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_detection_mode_format, detectionModeText),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding)
//
//        // 绘制内容总览卡片
//        yOffset = cardTop + cardHeight + 24f
//        val overviewCardTop = yOffset
//        val overviewCardHeight = 200f
//
//        // 绘制内容总览卡片背景
//        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardPaint)
//        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardBorderPaint)
//
//        // 绘制内容总览标题
//        val overviewTitlePaint = createTextPaint(16f, isBold = true)
//        yOffset = overviewCardTop + padding
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_report_content_overview),
//            cardLeft + padding, yOffset, overviewTitlePaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        // 绘制章节列表
//        val chapterPaint = createTextPaint(14f)
//        reportData.analyteDetails.forEachIndexed { index, detail ->
//            val chapterTitle = context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
//            yOffset = drawFormattedText(canvas, chapterTitle,
//                cardLeft + padding, yOffset, chapterPaint, cardRight - cardLeft - 2 * padding) + 8f
//        }
//
//        // 绘制页脚信息
//        val footerPaint = createTextPaint(10f, android.graphics.Color.GRAY)
//        canvas.drawText(
//            context.getString(R.string.pdf_generated_on, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())),
//            PDF_MARGIN,
//            PDF_PAGE_HEIGHT - 40f,
//            footerPaint
//        )
//
//        footerPaint.textAlign = Paint.Align.RIGHT
//        canvas.drawText(
//            context.getString(R.string.pdf_page_number, 1, countTotalPages(reportData)),
//            PDF_PAGE_WIDTH - PDF_MARGIN,
//            PDF_PAGE_HEIGHT - 40f,
//            footerPaint
//        )
//
//        Log.d("ExportViewModel", "直接绘制封面页完成")
//    }



    /**
     * 【已重构】创建实验总览页 - 直接绘制版本
     */
    private fun createSummaryPageDirectly(canvas: Canvas, reportData: ReportData, pageNumber: Int, totalPages: Int) {
        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_report_content_overview), pageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_report_content_overview), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 项目信息
        val textPaint = createTextPaint(12f)
        val project = reportData.project

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_project_name_format, project.name),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 10f

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_creation_date, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 10f

        val detectionModeText = when(project.detectionMode) {
            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
            else -> project.detectionMode
        }

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_detection_mode_format, detectionModeText),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 20f

        // 章节目录
        val chapterTitlePaint = createTextPaint(14f)
        reportData.analyteDetails.forEachIndexed { index, detail ->
            val chapterTitle = context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
            yOffset = drawFormattedText(canvas, chapterTitle, PDF_MARGIN + 20f, yOffset, chapterTitlePaint, PDF_CONTENT_WIDTH - 20f) + 10f
        }
    }

    /**
     * 创建原始数据附录页 - 直接绘制版本
     */
    private fun createRawDataAppendixDirectly(canvas: Canvas, reportData: ReportData, pageNumber: Int, totalPages: Int) {
        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_appendix_title), pageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 绘制原始数据表格
        val headers = listOf(
            context.getString(R.string.pdf_table_header_well),
            context.getString(R.string.analyte),
            "分析方法",
            "角色",
            "预测浓度",
            "真实浓度",
            "单位"
        )

        val data = mutableListOf<List<String>>()

        for (analyteDetail in reportData.analyteDetails) {
            val analyteName = analyteDetail.analyte.name
            val method = if (analyteDetail.analysisMethod == "CURVE_FIT")
                context.getString(R.string.pdf_analysis_curve_fit)
            else
                context.getString(R.string.pdf_analysis_dl_model)

            for (wellResult in analyteDetail.wellResults) {
                val wellLabel = if(wellResult.virtualRow != null && wellResult.virtualCol != null) {
                    WellMappingUtils.getWellLabel(wellResult.virtualRow!!, wellResult.virtualCol!!)
                } else {
                    val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(wellResult.wellIndex)
                    WellMappingUtils.getWellLabel(vRow, vCol)
                }

                // 确保通过wellResult访问wellType属性
                val wellTypeValue = try {
                    // 尝试通过反射获取属性，如果不存在则返回默认值
                    val field = wellResult.javaClass.getDeclaredField("wellType")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: "UNKNOWN"
                } catch (e: Exception) {
                    "UNKNOWN" // 如果没有该属性则默认使用UNKNOWN
                }

                val role = when(wellTypeValue) {
                    "STANDARD" -> "标准品"
                    "SAMPLE" -> "样本"
                    "BLANK" -> "空白对照"
                    "QUALITY_CONTROL" -> "质控品"
                    else -> "未知"
                }

                val predicted = wellResult.predictedConcentration?.let { String.format("%.4f", it) } ?: "-"
                val trueVal = wellResult.trueConcentration?.let { String.format("%.4f", it) } ?: "-"

                data.add(listOf(
                    wellLabel,
                    analyteName,
                    method,
                    role,
                    predicted,
                    trueVal,
                    analyteDetail.concentrationUnit
                ))
            }
        }

        // 如果数据量太大，只显示一部分
        val maxRowsToShow = 20
        val displayedData = if (data.size > maxRowsToShow) data.take(maxRowsToShow) else data

        yOffset = drawTable(
            canvas,
            yOffset,
            headers,
            displayedData,
            floatArrayOf(60f, 85f, 85f, 70f, 70f, 70f, 45f)
        )

        if (data.size > maxRowsToShow) {
            val notePaint = createTextPaint(10f, android.graphics.Color.GRAY, false, Paint.Align.CENTER)
            canvas.drawText(
                context.getString(R.string.pdf_note_more_results, data.size - maxRowsToShow),
                PDF_PAGE_WIDTH / 2f,
                yOffset + 15f,
                notePaint
            )
        }

        // 添加导出的数据文件说明
        val notePaint = createTextPaint(12f)
        yOffset += 30f
//        yOffset = drawFormattedText(
//            canvas,
//            "注意：完整的原始数据已导出为CSV文件，包含所有孔位的详细像素值和浓度数据。",
//            PDF_MARGIN,
//            yOffset,
//            notePaint,
//            PDF_CONTENT_WIDTH
//        ) + 10f
    }

    /**
     * 绘制页眉和页脚
     */
    private fun drawPageHeaderFooter(canvas: Canvas, projectName: String, chapterTitle: String, pageNumber: Int, totalPages: Int) {
        // 页眉
        val headerBgPaint = Paint().apply { color = android.graphics.Color.parseColor("#006E1C") }
        canvas.drawRect(0f, 0f, PDF_PAGE_WIDTH.toFloat(), PDF_HEADER_HEIGHT, headerBgPaint)

        val logoImage = BitmapFactory.decodeResource(context.resources, R.drawable.icon2)
        val logoScale = 48f / logoImage.height
        val scaledLogoWidth = logoImage.width * logoScale

        val logoRect = Rect(
            PDF_MARGIN.toInt(),
            ((PDF_HEADER_HEIGHT - 48f) / 2).toInt(),
            (PDF_MARGIN + scaledLogoWidth).toInt(),
            ((PDF_HEADER_HEIGHT + 48f) / 2).toInt()
        )

        canvas.drawBitmap(logoImage, null, logoRect, null)

        val headerTextPaint = createTextPaint(20f, android.graphics.Color.WHITE)
        canvas.drawText("FluoColorQuant", PDF_MARGIN + scaledLogoWidth + 16f, PDF_HEADER_HEIGHT / 2 + 8f, headerTextPaint)

        // 项目名称和章节
        val subHeaderPaint = createTextPaint(12f)
        canvas.drawText(
            "$projectName - $chapterTitle",
            PDF_MARGIN,
            PDF_HEADER_HEIGHT + 20f,
            subHeaderPaint
        )

        // 页脚
        val footerPaint = createTextPaint(10f, android.graphics.Color.GRAY)
        canvas.drawText(
            context.getString(R.string.pdf_footer_app_name),
            PDF_MARGIN,
            PDF_PAGE_HEIGHT - 20f,
            footerPaint
        )

        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.pdf_page_number, pageNumber, totalPages),
            PDF_PAGE_WIDTH - PDF_MARGIN,
            PDF_PAGE_HEIGHT - 20f,
            footerPaint
        )
    }

    /**
     * 创建文本绘制画笔
     */
    private fun createTextPaint(
        size: Float,
        color: Int = android.graphics.Color.BLACK,
        isBold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): TextPaint {
        return TextPaint().apply {
            this.textSize = size
            this.color = color
            this.isAntiAlias = true
            this.textAlign = align
            this.typeface = Typeface.create(Typeface.DEFAULT, if (isBold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * 绘制格式化文本
     * @return 返回文本结束的Y坐标
     */
    @SuppressLint("NewApi")
    private fun drawFormattedText(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint, maxWidth: Float): Float {
        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt()).build()
        canvas.save()
        canvas.translate(x, y)
        staticLayout.draw(canvas)
        canvas.restore()
        return y + staticLayout.height
    }

    /**
     * 绘制表格（支持指定起始X坐标）
     */
    private fun drawTable(
        canvas: Canvas,
        startY: Float,
        headers: List<String>,
        data: List<List<String>>,
        columnWidths: FloatArray,
        startX: Float = PDF_MARGIN
    ): Float {
        var currentY = startY
        val rowHeight = TABLE_CELL_HEIGHT
        val headerPaint = Paint().apply { color = android.graphics.Color.parseColor("#E0E0E0"); style = Paint.Style.FILL }
        val rowPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = android.graphics.Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
        val textPaint = createTextPaint(10f)
        val headerTextPaint = createTextPaint(10f, isBold = true)

        // Draw header
        canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, headerPaint)
        var currentX = startX
        headers.forEachIndexed { i, header ->
            canvas.drawText(header, currentX + 5, currentY + rowHeight - 10, headerTextPaint)
            currentX += columnWidths[i]
        }
        currentY += rowHeight

        // Draw rows
        data.forEachIndexed { rowIndex, rowData ->
            if (rowIndex % 2 != 0) {
                canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, rowPaint)
            }
            currentX = startX
            rowData.forEachIndexed { i, cellData ->
                canvas.drawText(cellData, currentX + 5, currentY + rowHeight - 10, textPaint)
                currentX += columnWidths[i]
            }
            currentY += rowHeight
        }

        // Draw table borders
        canvas.drawRect(startX, startY, startX + columnWidths.sum(), currentY, borderPaint)
        currentX = startX
        for (width in columnWidths) {
            canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)
            currentX += width
        }
        canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)

        return currentY + 10f
    }

    /**
     * 创建分析物章节，返回下一页的页号
     */
    private fun createAnalyteChapter(
        document: PdfDocument,
        analyteDetail: AnalyteResultDetails,
        chapterIndex: Int,
        startPageNumber: Int,
        totalPages: Int
    ): Int {
        var currentPageNumber = startPageNumber
        val chapterTitle = context.getString(R.string.pdf_chapter_title_format, chapterIndex, analyteDetail.analyte.name)

        // 开始第一页 - 分析方案和标准曲线
        var page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
        var canvas = page.canvas

        // 绘制页眉页脚
        drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, chapterTitle, PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 分析方案部分
        yOffset = drawAnalysisPlan(canvas, yOffset, analyteDetail)

        // 绘制标准曲线（如果有）
        if (analyteDetail.analysisMethod == "CURVE_FIT" && analyteDetail.standardCurveChartData != null) {
            yOffset = drawStandardCurve(canvas, yOffset, analyteDetail)
        }

        document.finishPage(page)
        currentPageNumber++

        // 开始第二页 - 浓度分布
        page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
        canvas = page.canvas
        drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

        yOffset = PDF_CONTENT_START_Y
        yOffset = drawConcentrationDistribution(canvas, yOffset, analyteDetail)

        document.finishPage(page)
        currentPageNumber++

        // 如果有验证数据，创建额外的页面
        if (analyteDetail.validationData != null) {
            page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
            canvas = page.canvas
            drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

            yOffset = PDF_CONTENT_START_Y
            yOffset = drawValidationSection(canvas, yOffset, analyteDetail)

            document.finishPage(page)
            currentPageNumber++
        }

        return currentPageNumber
    }

    /**
     * 绘制分析方案部分
     */
    private fun drawAnalysisPlan(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val textPaint = createTextPaint(12f)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_analysis_plan_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        val method = if (details.analysisMethod == "CURVE_FIT")
            context.getString(R.string.pdf_analysis_curve_fit)
        else
            context.getString(R.string.pdf_analysis_dl_model)

        y = drawFormattedText(canvas, "${context.getString(R.string.pdf_analysis_method_title)} $method", PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

        val template = details.usedTemplate?.templateName ?: context.getString(R.string.pdf_no_template)
        y = drawFormattedText(canvas, "${context.getString(R.string.pdf_template_used_title)} $template", PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

        // 添加试剂信息（如果有）
        details.usedTemplate?.let { template ->
            val reagentInfo = mutableListOf<String>()

            // 尝试安全访问模板的antigen属性
            try {
                val antigenField = template.javaClass.getDeclaredField("antigen")
                antigenField.isAccessible = true
                val antigenObj = antigenField.get(template)

                if (antigenObj != null) {
                    val nameField = antigenObj.javaClass.getDeclaredField("name")
                    nameField.isAccessible = true
                    val name = nameField.get(antigenObj)?.toString() ?: "未知"

                    val manufacturerField = antigenObj.javaClass.getDeclaredField("manufacturer")
                    manufacturerField.isAccessible = true
                    val manufacturer = manufacturerField.get(antigenObj)?.toString() ?: "未知厂商"

                    reagentInfo.add("抗原: $name ($manufacturer)")
                }
            } catch (e: Exception) {
                Log.d("ExportViewModel", "No antigen info available: ${e.message}")
            }

            // 尝试安全访问模板的antibody属性
            try {
                val antibodyField = template.javaClass.getDeclaredField("antibody")
                antibodyField.isAccessible = true
                val antibodyObj = antibodyField.get(template)

                if (antibodyObj != null) {
                    val nameField = antibodyObj.javaClass.getDeclaredField("name")
                    nameField.isAccessible = true
                    val name = nameField.get(antibodyObj)?.toString() ?: "未知"

                    val manufacturerField = antibodyObj.javaClass.getDeclaredField("manufacturer")
                    manufacturerField.isAccessible = true
                    val manufacturer = manufacturerField.get(antibodyObj)?.toString() ?: "未知厂商"

                    reagentInfo.add("抗体: $name ($manufacturer)")
                }
            } catch (e: Exception) {
                Log.d("ExportViewModel", "No antibody info available: ${e.message}")
            }

            if (reagentInfo.isNotEmpty()) {
                y = drawFormattedText(canvas, "试剂信息:", PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 5f

                reagentInfo.forEach { info ->
                    y = drawFormattedText(canvas, "• $info", PDF_MARGIN + 40f, y, textPaint, PDF_CONTENT_WIDTH - 40f) + 5f
                }

                y += 5f
            }
        }

        return y + 10f
    }

    /**
     * 绘制标准曲线部分
     */
    private fun drawStandardCurve(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val textPaint = createTextPaint(12f)
        val smallTextPaint = createTextPaint(10f)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_standard_curve_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        val curveBitmap = generateStandardCurveBitmap(details)
        if (curveBitmap != null) {
            // 调整图表大小，确保完全显示
            val scale = min(0.6f, (PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 100f) / curveBitmap.height)
            val scaledWidth = curveBitmap.width * scale
            val scaledHeight = curveBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + scaledWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(curveBitmap, null, destRect, null)
            y += scaledHeight + 15f
        }

        // 绘制函数表达式和参数
        details.fittedCurveModel?.let { model ->
            y = drawFormattedText(canvas, context.getString(R.string.function_expression), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f
            val latexExpression = FittingEngine.formatParametersToLatex(model.function, model.parameters)
            y = drawFormattedText(canvas, latexExpression, PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

            y = drawFormattedText(canvas, context.getString(R.string.curve_parameters), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f

            val paramText = model.parameters.entries.joinToString(", ") { (key, value) ->
                "$key: ${String.format("%.4f", value)}"
            }
            y = drawFormattedText(canvas, paramText, PDF_MARGIN + 20f, y, smallTextPaint, PDF_CONTENT_WIDTH - 20f) + 10f

            // 拟合指标
            model.metrics?.let { metrics ->
                y = drawFormattedText(canvas, context.getString(R.string.fitting_quality), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f

                val metricText = metrics.entries.joinToString(", ") { (key, value) ->
                    "$key: ${String.format("%.4f", value)}"
                }
                y = drawFormattedText(canvas, metricText, PDF_MARGIN + 20f, y, smallTextPaint, PDF_CONTENT_WIDTH - 20f) + 10f
            }
        }

        return y
    }

    /**
     * 绘制浓度分布部分
     */
    private fun drawConcentrationDistribution(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_concentration_distribution_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        // 先绘制热力图
        val heatmapBitmap = generateHeatmapBitmap(details)
        if (heatmapBitmap != null) {
            val maxHeight = (PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 120f) / 2 // 预留足够空间给两个图表
            val scale = min(0.8f, maxHeight / heatmapBitmap.height)
            val scaledWidth = heatmapBitmap.width * scale
            val scaledHeight = heatmapBitmap.height * scale

            val destRect = Rect(
                ((PDF_PAGE_WIDTH - scaledWidth) / 2).toInt(),
                y.toInt(),
                ((PDF_PAGE_WIDTH + scaledWidth) / 2).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(heatmapBitmap, null, destRect, null)
            y += scaledHeight + 20f
        }

        // 再绘制趋势图
        val trendBitmap = generateTrendChartBitmap(details)
        if(trendBitmap != null) {
            val maxHeight = PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 30f
            val scale = min(0.8f, maxHeight / trendBitmap.height)
            val scaledWidth = trendBitmap.width * scale
            val scaledHeight = trendBitmap.height * scale

            val destRect = Rect(
                ((PDF_PAGE_WIDTH - scaledWidth) / 2).toInt(),
                y.toInt(),
                ((PDF_PAGE_WIDTH + scaledWidth) / 2).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(trendBitmap, null, destRect, null)
            y += scaledHeight + 10f
        }

        return y
    }

    /**
     * 绘制验证部分
     */
    private fun drawValidationSection(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        val validationData = details.validationData ?: return startY
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val subSectionPaint = createTextPaint(14f, isBold = true)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_validation_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        // 绘制回归分析部分
        y = drawFormattedText(canvas, context.getString(R.string.pdf_regression_title), PDF_MARGIN + 10f, y, subSectionPaint, PDF_CONTENT_WIDTH - 10f) + 10f

        // 计算左侧图表和右侧表格的布局
        val leftWidth = (PDF_CONTENT_WIDTH - 20f) * 0.65f  // 左侧图表占65%宽度
        val rightWidth = (PDF_CONTENT_WIDTH - 20f) * 0.35f // 右侧表格占35%宽度
        val regressionSectionStartY = y

        // 绘制回归分析图表（左侧）
        val regressionBitmap = generateValidationRegressionBitmap(details)
        var regressionSectionHeight = 0f

        if (regressionBitmap != null) {
            val scale = leftWidth / regressionBitmap.width
            val scaledHeight = regressionBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + leftWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(regressionBitmap, null, destRect, null)
            regressionSectionHeight = scaledHeight
        }

        // 绘制回归分析指标表格（右侧）
        if (validationData.regressionMetrics.isNotEmpty()) {
            val headers = listOf("Metric Name", "Value")
            val data = validationData.regressionMetrics.map { (key, value) -> listOf(key, value) }

            drawTable(
                canvas,
                regressionSectionStartY,
                headers,
                data,
                floatArrayOf(rightWidth * 0.6f, rightWidth * 0.4f),
                PDF_MARGIN + leftWidth + 10f  // 表格从图表右侧开始
            )
        }

        // 更新Y坐标到回归分析部分的底部
        y = regressionSectionStartY + regressionSectionHeight + 30f

        // 绘制Bland-Altman分析部分
        y = drawFormattedText(canvas, context.getString(R.string.pdf_bland_altman_title), PDF_MARGIN + 10f, y, subSectionPaint, PDF_CONTENT_WIDTH - 10f) + 10f
        val blandAltmanSectionStartY = y

        // 绘制Bland-Altman图表（左侧）
        val blandAltmanBitmap = generateValidationBlandAltmanBitmap(details)
        var blandAltmanSectionHeight = 0f

        if (blandAltmanBitmap != null) {
            val scale = leftWidth / blandAltmanBitmap.width
            val scaledHeight = blandAltmanBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + leftWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(blandAltmanBitmap, null, destRect, null)
            blandAltmanSectionHeight = scaledHeight
        }

        // 绘制Bland-Altman指标表格（右侧）
        if (validationData.blandAltmanMetrics.isNotEmpty()) {
            val headers = listOf("Metric Name", "Value")
            val data = validationData.blandAltmanMetrics.map { (key, value) -> listOf(key, value) }

            drawTable(
                canvas,
                blandAltmanSectionStartY,
                headers,
                data,
                floatArrayOf(rightWidth * 0.6f, rightWidth * 0.4f),
                PDF_MARGIN + leftWidth + 10f  // 表格从图表右侧开始
            )
        }

        // 更新Y坐标到Bland-Altman分析部分的底部
        y = blandAltmanSectionStartY + blandAltmanSectionHeight + 20f

        return y
    }

    /**
     * 【重构】创建原始数据附录 - 支持多页显示
     * @return 返回下一页的页号
     */
    private fun createRawDataAppendix(
        document: PdfDocument,
        reportData: ReportData,
        startPageNumber: Int,
        totalPages: Int
    ): Int {
        var currentPageNumber = startPageNumber

        // 准备原始数据
        val headers = listOf(
            context.getString(R.string.pdf_table_header_well),
            "Images",
            context.getString(R.string.analyte),
            "Role",
            "Predicted Conc.",
            "True Conc.",
            "Unit"
        )

        val data = mutableListOf<List<String>>()
        val imageMap = mutableMapOf<Int, Bitmap?>() // 存储图片索引和对应的Bitmap

        for (analyteDetail in reportData.analyteDetails) {
            val analyteName = analyteDetail.analyte.name

            for (wellResult in analyteDetail.wellResults) {
                val wellLabel = if(wellResult.virtualRow != null && wellResult.virtualCol != null) {
                    WellMappingUtils.getWellLabel(wellResult.virtualRow!!, wellResult.virtualCol!!)
                } else {
                    val (vRow, vCol) = WellMappingUtils.mapRealToVirtualCoordinates(wellResult.wellIndex)
                    WellMappingUtils.getWellLabel(vRow, vCol)
                }

                // 获取角色类型
                val roleType = try {
                    val field = wellResult.javaClass.getDeclaredField("roleType")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: "UNKNOWN"
                } catch (e: Exception) {
                    "UNKNOWN" // 如果没有该属性则默认使用UNKNOWN
                }

                val role = when(roleType) {
                    "STANDARD" -> "Standard"
                    "SAMPLE" -> "Sample"
                    "BLANK" -> "Blank"
                    "QC" -> "QC"
                    "UNKNOWN" -> "Unknown"
                    "NONE" -> "None"
                    else -> roleType
                }

                // 获取裁剪图片标识符
                val imageId = try {
                    val field = wellResult.javaClass.getDeclaredField("croppedImageIdentifier")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: ""
                } catch (e: Exception) {
                    "" // 如果没有该属性则默认使用空字符串
                }

                // 尝试加载图片
                if (imageId.isNotEmpty()) {
                    try {
                        val imageIndex = data.size // 使用当前数据索引作为图片索引
                        // 异步加载图片，避免阻塞主线程
                        imageMap[imageIndex] = loadImageFromPath(imageId)
                    } catch (e: Exception) {
                        Log.e("ExportViewModel", "加载图片失败: $imageId", e)
                    }
                }

                val predicted = wellResult.predictedConcentration?.let { String.format("%.4f", it) } ?: "-"
                val trueVal = wellResult.trueConcentration?.let { String.format("%.4f", it) } ?: "-"

                data.add(listOf(
                    wellLabel,
                    imageId, // 暂时存储路径，稍后会替换为图片
                    analyteName,
                    role,
                    predicted,
                    trueVal,
                    analyteDetail.concentrationUnit
                ))
            }
        }

        // 计算每页可以显示的行数
        val rowHeight = TABLE_CELL_HEIGHT
        val headerHeight = rowHeight
        val pageContentHeight = PDF_CONTENT_HEIGHT - 80f // 减去标题和说明的空间
        val rowsPerPage = ((pageContentHeight - headerHeight) / rowHeight).toInt()

        // 分页显示数据
        val appendixPages = if (data.isEmpty()) 1 else (data.size + rowsPerPage - 1) / rowsPerPage

        for (pageIndex in 0 until appendixPages) {
            val startRow = pageIndex * rowsPerPage
            val endRow = minOf((pageIndex + 1) * rowsPerPage, data.size)
            val pageData = data.subList(startRow, endRow)

            val page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
            val canvas = page.canvas

            // 绘制页眉页脚
            val pageTitle = if (pageIndex == 0)
                context.getString(R.string.pdf_appendix_title)
            else
                context.getString(R.string.pdf_appendix_title) + " (Continued)"

            drawPageHeaderFooter(canvas, reportData.project.name, pageTitle, currentPageNumber, totalPages)

            var yOffset = PDF_CONTENT_START_Y

            // 绘制章节标题
            if (pageIndex == 0) {
                val titlePaint = createTextPaint(18f, isBold = true)
                yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f
            } else {
                val titlePaint = createTextPaint(18f, isBold = true)
                yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title) + " (Continued)", PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f
            }

            // 绘制表格
            yOffset = drawTableWithImages(
                canvas,
                yOffset,
                headers,
                pageData,
                floatArrayOf(60f, 60f, 85f, 70f, 70f, 70f, 45f),
                imageMap,
                startRow
            )

            // 如果是最后一页，添加导出说明
            if (pageIndex == appendixPages - 1) {
                val notePaint = createTextPaint(12f)
                yOffset += 30f
                drawFormattedText(
                    canvas,
                    "Note: Complete raw data has been exported as CSV file, containing detailed pixel values and concentration data for all wells.",
                    PDF_MARGIN,
                    yOffset,
                    notePaint,
                    PDF_CONTENT_WIDTH
                )
            }

            document.finishPage(page)
            currentPageNumber++
        }

        // 清理图片资源
        for (bitmap in imageMap.values) {
            bitmap?.recycle()
        }

        return currentPageNumber
    }

    /**
     * 加载图片路径并返回Bitmap
     */
    private fun loadImageFromPath(imagePath: String): Bitmap? {
        return try {
            if (imagePath.isEmpty()) return null

            // 尝试从文件系统加载图片
            val file = File(imagePath)
            if (file.exists() && file.canRead()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true  // 先只获取图片尺寸
                }
                BitmapFactory.decodeFile(file.absolutePath, options)

                // 计算合适的缩放比例，保持纵横比
                val sampleSize = calculateInSampleSize(options, 200, 200)

                options.apply {
                    inJustDecodeBounds = false
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                // 如果文件不存在或无法读取，尝试从URI加载
                try {
                    val uri = Uri.parse(imagePath)
                    val inputStream = context.contentResolver.openInputStream(uri)

                    // 先获取图片尺寸
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // 计算缩放比例
                    val sampleSize = calculateInSampleSize(options, 200, 200)

                    // 重新打开流并解码图片
                    val newInputStream = context.contentResolver.openInputStream(uri)
                    options.apply {
                        inJustDecodeBounds = false
                        inSampleSize = sampleSize
                    }
                    val bitmap = BitmapFactory.decodeStream(newInputStream, null, options)
                    newInputStream?.close()
                    bitmap
                } catch (e: Exception) {
                    Log.e("ExportViewModel", "无法从URI加载图片: $imagePath", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("ExportViewModel", "加载图片失败: $imagePath", e)
            null
        }
    }

    /**
     * 计算合适的图片缩放比例
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的inSampleSize值，该值是2的幂，同时保持高度和宽度大于请求的高度和宽度
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 绘制带图片的表格
     */
    private fun drawTableWithImages(
        canvas: Canvas,
        startY: Float,
        headers: List<String>,
        data: List<List<String>>,
        columnWidths: FloatArray,
        imageMap: Map<Int, Bitmap?>,
        startRowIndex: Int,
        startX: Float = PDF_MARGIN
    ): Float {
        var currentY = startY
        val rowHeight = TABLE_CELL_HEIGHT
        val headerPaint = Paint().apply { color = android.graphics.Color.parseColor("#E0E0E0"); style = Paint.Style.FILL }
        val rowPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = android.graphics.Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
        val textPaint = createTextPaint(10f)
        val headerTextPaint = createTextPaint(10f, isBold = true)

        // Draw header
        canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, headerPaint)
        var currentX = startX
        headers.forEachIndexed { i, header ->
            canvas.drawText(header, currentX + 5, currentY + rowHeight - 10, headerTextPaint)
            currentX += columnWidths[i]
        }
        currentY += rowHeight

        // Draw rows
        data.forEachIndexed { rowIndex, rowData ->
            val absoluteRowIndex = startRowIndex + rowIndex

            if (rowIndex % 2 != 0) {
                canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, rowPaint)
            }

            currentX = startX

            // 绘制第一列（Well ID）
            canvas.drawText(rowData[0], currentX + 5, currentY + rowHeight - 10, textPaint)
            currentX += columnWidths[0]

            // 绘制第二列（图片）
            val imageBitmap = imageMap[absoluteRowIndex]
            if (imageBitmap != null) {
                // 计算保持纵横比的图像尺寸
                val imageWidth = columnWidths[1] - 10
                val imageHeight = rowHeight - 6

                // 计算保持原始纵横比的尺寸
                val originalRatio = imageBitmap.width.toFloat() / imageBitmap.height.toFloat()

                // 确定最终绘制尺寸，保持纵横比
                val finalWidth: Float
                val finalHeight: Float

                if (originalRatio > 1) {  // 宽图
                    finalWidth = imageWidth
                    finalHeight = finalWidth / originalRatio
                } else {  // 高图或正方形
                    finalHeight = imageHeight
                    finalWidth = finalHeight * originalRatio
                }

                // 计算居中位置
                val leftPadding = (imageWidth - finalWidth) / 2 + 5
                val topPadding = (imageHeight - finalHeight) / 2 + 3

                val imageRect = RectF(
                    currentX + leftPadding,
                    currentY + topPadding,
                    currentX + leftPadding + finalWidth,
                    currentY + topPadding + finalHeight
                )

                // 绘制边框
                val imageBorderPaint = Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                }
                canvas.drawRect(
                    currentX + 5,
                    currentY + 3,
                    currentX + 5 + imageWidth,
                    currentY + 3 + imageHeight,
                    imageBorderPaint
                )

                // 绘制图片
                canvas.drawBitmap(imageBitmap, null, imageRect, null)
            }
            currentX += columnWidths[1]

            // 绘制剩余列
            for (i in 2 until rowData.size) {
                canvas.drawText(rowData[i], currentX + 5, currentY + rowHeight - 10, textPaint)
                currentX += columnWidths[i]
            }

            currentY += rowHeight
        }

        // Draw table borders
        canvas.drawRect(startX, startY, startX + columnWidths.sum(), currentY, borderPaint)
        currentX = startX
        for (width in columnWidths) {
            canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)
            currentX += width
        }
        canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)

        return currentY + 10f
    }

}