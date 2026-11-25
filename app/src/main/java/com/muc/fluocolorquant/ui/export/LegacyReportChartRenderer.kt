package com.muc.fluocolorquant.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧孔板报告使用的离屏图表渲染器。
 *
 * 该类负责 Android Canvas/Bitmap 与资源文本，不负责导出状态、文件写入或数据库查询；
 * 新阵列结果继续使用冻结快照专用的 ArrayResult 导出器，两条历史语义不能混用。
 */
@Singleton
class LegacyReportChartRenderer @Inject constructor() {
    /**
     * 使用项目冻结列数生成孔位标签。旧 virtualRow/virtualCol 可能由固定 12 列算法写入，
     * 因此渲染时仍以线性 wellIndex 和项目兼容尺寸为准。
     */
    private fun resolveWellLabel(wellResult: WellResult, project: Project): String {
        val dimensions = GridLayoutPolicy.resolveProject(project)
        return WellMappingUtils.getWellLabelForIndex(
            index = wellResult.wellIndex,
            columns = dimensions.columns
        )
    }

    /**
     * 【优化】生成热力图位图，确保布局合理
     */
    fun generateHeatmapBitmap(context: Context, analyteDetail: AnalyteResultDetails): Bitmap? {
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
            val title = context.getString(
                R.string.pdf_analyte_section_title,
                analyteDetail.analyte.name,
                context.getString(R.string.concentration_heatmap)
            )
            canvas.drawText(title, 600f, 80f, titlePaint)

            val dimensions = GridLayoutPolicy.resolveProject(analyteDetail.project)
            val rows = dimensions.rows
            val cols = dimensions.columns
            val startX = 120f
            val startY = 160f  // 增加上方空间，避免与标题重叠
            // 固定导出画布，通过真实行列动态计算格子大小，15×15 和非方阵不会被裁掉。
            val cellWidth = (1200f - startX - 60f) / cols
            val cellHeight = (900f - startY - 80f) / rows
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
                canvas.drawText(WellMappingUtils.getRowLabel(row), startX - 40f, startY + row * cellHeight + cellHeight / 2 + 10f, labelPaint)
            }

            // 绘制热力图
            analyteDetail.wellResults.forEach { wellResult ->
                val (row, col) = WellMappingUtils.mapRealToVirtualCoordinates(
                    realIndex = wellResult.wellIndex,
                    columns = cols
                )
                if (row in 0 until rows && col in 0 until cols) {
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
                        canvas.drawText(String.format(Locale.US, "%.1f", concentration), left + cellWidth / 2, top + cellHeight / 2 + 8f, textPaint)
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
            canvas.drawText(String.format(Locale.US, "%.2f", maxConcentration), legendX + legendWidth, legendY + legendHeight + 30f, legendTextPaint)

            // 图例标题，居中显示
            legendTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.bitmap_legend_concentration_range_format, String.format(Locale.US, "%.2f", maxConcentration), analyteDetail.concentrationUnit),
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
    fun generateTrendChartBitmap(context: Context, analyteDetail: AnalyteResultDetails): Bitmap? {
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
            val title = context.getString(
                R.string.pdf_analyte_section_title,
                analyteDetail.analyte.name,
                context.getString(R.string.pdf_trend_chart_title)
            )
            canvas.drawText(title, 600f, 70f, titlePaint)

            val validResults = analyteDetail.wellResults
                .filter { it.predictedConcentration?.isFinite() == true }
                .sortedBy { it.wellIndex }
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
                canvas.drawText(String.format(Locale.US, "%.1f", yValue), leftPadding - 10, yPos + 8, labelPaint)
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

                    val wellLabel = resolveWellLabel(wellResult, analyteDetail.project)

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

    fun generateValidationRegressionBitmap(
        context: Context,
        analyteDetail: AnalyteResultDetails
    ): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.regressionPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText(
                context.getString(
                    R.string.pdf_analyte_section_title,
                    analyteDetail.analyte.name,
                    context.getString(R.string.pdf_regression_title)
                ),
                500f,
                70f,
                titlePaint
            )

            val metricsPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 30f; isAntiAlias = true }
            val r2 = validationData.regressionMetrics["R²"] ?: context.getString(R.string.not_available_short)
            val rmse = validationData.regressionMetrics["RMSE"] ?: context.getString(R.string.not_available_short)
            canvas.drawText(context.getString(R.string.pdf_metric_value, "R²", r2), 150f, 120f, metricsPaint)
            canvas.drawText(context.getString(R.string.pdf_metric_value, "RMSE", rmse), 150f, 160f, metricsPaint)

            drawChartAxisAndGrid(
                canvas,
                chartData,
                context.getString(R.string.chart_actual),
                context.getString(R.string.chart_predicted)
            )
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成验证回归图位图失败", e)
            return null
        }
    }

    fun generateValidationBlandAltmanBitmap(
        context: Context,
        analyteDetail: AnalyteResultDetails
    ): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.blandAltmanPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText(
                context.getString(
                    R.string.pdf_analyte_section_title,
                    analyteDetail.analyte.name,
                    context.getString(R.string.pdf_bland_altman_title)
                ),
                500f,
                70f,
                titlePaint
            )

            drawChartAxisAndGrid(
                canvas,
                chartData,
                context.getString(R.string.chart_mean),
                context.getString(R.string.chart_difference)
            )
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData, isBlandAltman = true)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成Bland-Altman分析图位图失败", e)
            return null
        }
    }

    fun generateStandardCurveBitmap(
        context: Context,
        analyteDetail: AnalyteResultDetails
    ): Bitmap? {
        val chartData = analyteDetail.standardCurveChartData ?: return null
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText(
                context.getString(
                    R.string.pdf_analyte_section_title,
                    analyteDetail.analyte.name,
                    context.getString(R.string.pdf_standard_curve_title)
                ),
                500f,
                70f,
                titlePaint
            )

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
            canvas.drawText(String.format(Locale.US, "%.2f", value), x, bottom + 35, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..numTicks) {
            val value = yMin + i * (yMax - yMin) / numTicks
            val y = bottom - i * (graphHeight / numTicks)
            canvas.drawLine(left, y, left - 10, y, axisPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", value), left - 15, y + 8, labelPaint)
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
}
