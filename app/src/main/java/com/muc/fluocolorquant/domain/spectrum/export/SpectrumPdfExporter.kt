package com.muc.fluocolorquant.domain.spectrum.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.util.Log
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import com.muc.fluocolorquant.data.model.SpectrumExportData
import com.muc.fluocolorquant.domain.export.pdf.PdfPageCanvas
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 光谱 PDF 报告生成。
 *
 * 从 `ExportViewModel` 下沉而来，与 `ArrayResultPdfExporter` 处于同一层：报告的页面结构与
 * 科学内容属于领域职责，界面状态编排才属于 ViewModel。页眉页脚、表格、文本折行等版式
 * 原语统一取自 [PdfPageCanvas]，避免光谱与孔板两条链路各写一套而让报告版式悄悄分叉。
 *
 * 文件写入与媒体扫描仍留在 ViewModel：那是平台职责。
 */
object SpectrumPdfExporter {

    private const val LOG_TAG: String = "SpectrumPdfExporter"

    /** 单通道页里原始裁切图的最大显示高度，避免挤掉下方曲线图。 */
    private const val SOURCE_IMAGE_MAX_HEIGHT: Float = 200f

    /** 摘要页汇总曲线图的最大显示高度。 */
    private const val SUMMARY_CHART_MAX_HEIGHT: Float = 230f

    /** 摘要页表格列宽，合计 500pt，居中放置于 A4 页面。 */
    private val SUMMARY_COLUMN_WIDTHS: List<Float> = listOf(70f, 130f, 100f, 110f, 90f)

    /**
     * 生成完整光谱报告文档。
     *
     * 页面结构：封面 → 总览（汇总曲线图 + 峰值表）→ 每通道一页（原始裁切图 + 曲线图）。
     * 调用方负责把返回的文档写盘并 `close()`。
     */
    fun createDocument(context: Context, data: SpectrumExportData): PdfDocument {
        val document = PdfDocument()
        val totalPages = data.channels.size + 2
        var pageNumber = 1

        val cover = document.startPage(pageInfo(pageNumber))
        drawCoverPage(cover.canvas, context, data, pageNumber, totalPages)
        document.finishPage(cover)
        pageNumber++

        val summary = document.startPage(pageInfo(pageNumber))
        drawSummaryPage(summary.canvas, context, data, pageNumber, totalPages)
        document.finishPage(summary)
        pageNumber++

        data.channels.forEach { channel ->
            val page = document.startPage(pageInfo(pageNumber))
            drawChannelPage(page.canvas, context, channel, data.project.name, pageNumber, totalPages)
            document.finishPage(page)
            pageNumber++
        }
        return document
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(
            PdfPageCanvas.PAGE_WIDTH,
            PdfPageCanvas.PAGE_HEIGHT,
            pageNumber
        ).create()

    private fun drawCoverPage(
        canvas: Canvas,
        context: Context,
        data: SpectrumExportData,
        pageNumber: Int,
        totalPages: Int
    ) {
        PdfPageCanvas.drawPageHeaderFooter(
            canvas = canvas,
            context = context,
            projectName = data.project.name,
            chapterTitle = "Cover",
            pageNumber = pageNumber,
            totalPages = totalPages
        )

        val centerX = PdfPageCanvas.PAGE_WIDTH / 2f
        canvas.drawText(
            context.getString(R.string.spectrum_pdf_cover_title),
            centerX,
            280f,
            PdfPageCanvas.textPaint(36f, isBold = true, align = Paint.Align.CENTER)
        )
        canvas.drawText(
            "Spectrum Analysis Report",
            centerX,
            320f,
            PdfPageCanvas.textPaint(18f, Color.GRAY, align = Paint.Align.CENTER)
        )

        val cardLeft = PdfPageCanvas.MARGIN + 50f
        val cardRight = PdfPageCanvas.PAGE_WIDTH - PdfPageCanvas.MARGIN - 50f
        val cardTop = 400f
        val cardBottom = 600f
        canvas.drawRoundRect(
            cardLeft, cardTop, cardRight, cardBottom, 10f, 10f,
            Paint().apply {
                color = Color.parseColor("#F5F5F5")
                style = Paint.Style.FILL
            }
        )

        val infoPaint = PdfPageCanvas.textPaint(18f)
        val lineHeight = 45f
        var y = cardTop + 50f
        listOf(
            context.getString(R.string.spectrum_pdf_project_label, data.project.name),
            context.getString(R.string.spectrum_pdf_mode_label),
            context.getString(R.string.spectrum_pdf_channels_label, data.channels.size),
            context.getString(
                R.string.spectrum_pdf_date_label,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )
        ).forEach { line ->
            canvas.drawText(line, cardLeft + 30f, y, infoPaint)
            y += lineHeight
        }
    }

    private fun drawSummaryPage(
        canvas: Canvas,
        context: Context,
        data: SpectrumExportData,
        pageNumber: Int,
        totalPages: Int
    ) {
        val summaryTitle = context.getString(R.string.spectrum_pdf_summary_title)
        PdfPageCanvas.drawPageHeaderFooter(
            canvas = canvas,
            context = context,
            projectName = data.project.name,
            chapterTitle = summaryTitle,
            pageNumber = pageNumber,
            totalPages = totalPages
        )
        canvas.drawText(
            summaryTitle,
            PdfPageCanvas.MARGIN,
            PdfPageCanvas.CONTENT_START_Y + 30f,
            PdfPageCanvas.textPaint(22f, isBold = true)
        )

        val rows = data.channels.map { channel ->
            listOf(
                channel.channelIndex.toString(),
                channel.analyteName,
                channel.peakWavelength?.let { String.format(Locale.US, "%.1f", it) } ?: MISSING,
                channel.peakIntensity?.let { String.format(Locale.US, "%.3f", it) } ?: MISSING,
                channel.dataPointCount.toString()
            )
        }
        val tableWidth = SUMMARY_COLUMN_WIDTHS.sum()
        val tableLeft = (PdfPageCanvas.PAGE_WIDTH - tableWidth) / 2f
        val tableHeight = PdfPageCanvas.TABLE_CELL_HEIGHT * (rows.size + 1)
        val tableSpacing = 24f
        var currentY = PdfPageCanvas.CONTENT_START_Y + 55f

        // 汇总曲线图放在表格之上：先看整体谱线走势，再核对逐通道峰值数字。
        // 只有在扣除表格所需高度后仍有足够空间时才绘制，避免把表格挤出页面。
        renderMergedChart(context, data.channels)?.let { chart ->
            try {
                val availableHeight = PdfPageCanvas.CONTENT_START_Y + PdfPageCanvas.CONTENT_HEIGHT -
                    currentY - tableSpacing - tableHeight - 12f
                if (availableHeight > 120f) {
                    val scale = min(
                        (PdfPageCanvas.CONTENT_WIDTH - 20f) / chart.width.toFloat(),
                        min(availableHeight, SUMMARY_CHART_MAX_HEIGHT) / chart.height.toFloat()
                    )
                    val scaledWidth = chart.width * scale
                    val scaledHeight = chart.height * scale
                    val chartLeft = (PdfPageCanvas.PAGE_WIDTH - scaledWidth) / 2f
                    canvas.drawBitmap(
                        chart,
                        null,
                        RectF(chartLeft, currentY, chartLeft + scaledWidth, currentY + scaledHeight),
                        null
                    )
                    currentY += scaledHeight + tableSpacing
                }
            } finally {
                chart.recycle()
            }
        }

        drawSummaryTable(canvas, context, rows, tableLeft, currentY)
    }

    /**
     * 摘要表刻意不复用 [PdfPageCanvas.drawTable]。
     *
     * 光谱摘要表用的是浅绿表头与更紧凑的字号，与孔板报告的灰色三线表是两种版式；强行
     * 复用会让其中一方的既有版面发生可见变化，而这次工作包的目标是移动代码而不是改版。
     */
    private fun drawSummaryTable(
        canvas: Canvas,
        context: Context,
        rows: List<List<String>>,
        tableLeft: Float,
        tableTop: Float
    ) {
        val headers = listOf(
            context.getString(R.string.spectrum_table_header_channel),
            context.getString(R.string.spectrum_table_header_analyte),
            context.getString(R.string.spectrum_table_header_peak_wavelength),
            context.getString(R.string.spectrum_table_header_peak_intensity),
            context.getString(R.string.spectrum_table_header_data_points)
        )
        val cellHeight = PdfPageCanvas.TABLE_CELL_HEIGHT
        val tableWidth = SUMMARY_COLUMN_WIDTHS.sum()
        var currentY = tableTop

        canvas.drawRect(
            tableLeft, currentY, tableLeft + tableWidth, currentY + cellHeight,
            Paint().apply {
                color = Color.parseColor("#E8F5E9")
                style = Paint.Style.FILL
            }
        )
        val headerPaint = PdfPageCanvas.textPaint(11f, isBold = true)
        var x = tableLeft
        headers.forEachIndexed { index, header ->
            canvas.drawText(header, x + 8f, currentY + 20f, headerPaint)
            x += SUMMARY_COLUMN_WIDTHS[index]
        }
        currentY += cellHeight

        val cellPaint = PdfPageCanvas.textPaint(10f)
        val alternatePaint = Paint().apply {
            color = Color.parseColor("#FAFAFA")
            style = Paint.Style.FILL
        }
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex % 2 == 1) {
                canvas.drawRect(
                    tableLeft, currentY, tableLeft + tableWidth, currentY + cellHeight, alternatePaint
                )
            }
            x = tableLeft
            row.forEachIndexed { index, cell ->
                canvas.drawText(cell, x + 8f, currentY + 20f, cellPaint)
                x += SUMMARY_COLUMN_WIDTHS[index]
            }
            currentY += cellHeight
        }

        canvas.drawRect(
            tableLeft, tableTop, tableLeft + tableWidth, currentY,
            Paint().apply {
                color = Color.parseColor("#BDBDBD")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
        )
    }

    private fun drawChannelPage(
        canvas: Canvas,
        context: Context,
        channel: SpectrumChannelExportModel,
        projectName: String,
        pageNumber: Int,
        totalPages: Int
    ) {
        val channelTitle = context.getString(
            R.string.spectrum_pdf_channel_title_format,
            channel.channelIndex,
            channel.analyteName
        )
        PdfPageCanvas.drawPageHeaderFooter(
            canvas = canvas,
            context = context,
            projectName = projectName,
            chapterTitle = channelTitle,
            pageNumber = pageNumber,
            totalPages = totalPages
        )

        var currentY = PdfPageCanvas.CONTENT_START_Y + 30f
        val centerX = PdfPageCanvas.PAGE_WIDTH / 2f
        canvas.drawText(
            channelTitle,
            centerX,
            currentY,
            PdfPageCanvas.textPaint(18f, isBold = true, align = Paint.Align.CENTER)
        )
        currentY += 35f

        val sectionPaint = PdfPageCanvas.textPaint(14f, align = Paint.Align.CENTER)

        currentY = drawChannelSourceImage(canvas, context, channel, currentY, sectionPaint)

        canvas.drawText(
            context.getString(R.string.pdf_spectrum_curve_title),
            centerX,
            currentY,
            sectionPaint
        )
        currentY += 25f

        renderChannelChart(context, channel)?.let { chart ->
            try {
                // 留出页脚空间，曲线图不允许压到页码上。
                val maxHeight = PdfPageCanvas.PAGE_HEIGHT - currentY - 100f
                if (maxHeight > 0f) {
                    val scale = min(
                        (PdfPageCanvas.CONTENT_WIDTH - 40f) / chart.width,
                        maxHeight / chart.height
                    )
                    val scaledWidth = chart.width * scale
                    val scaledHeight = chart.height * scale
                    val left = (PdfPageCanvas.PAGE_WIDTH - scaledWidth) / 2f
                    canvas.drawBitmap(
                        chart,
                        null,
                        RectF(left, currentY, left + scaledWidth, currentY + scaledHeight),
                        null
                    )
                }
            } finally {
                chart.recycle()
            }
        }
    }

    /** 绘制该通道的原始裁切图；没有图或解码失败时原样返回入参 Y，不留空白标题。 */
    private fun drawChannelSourceImage(
        canvas: Canvas,
        context: Context,
        channel: SpectrumChannelExportModel,
        startY: Float,
        sectionPaint: TextPaint
    ): Float {
        val path = channel.croppedImagePath
        if (path.isNullOrEmpty()) return startY
        val source = runCatching { BitmapFactory.decodeFile(path) }
            .onFailure { error -> Log.e(LOG_TAG, "加载通道原始图片失败: $path", error) }
            .getOrNull() ?: return startY

        return try {
            var currentY = startY
            canvas.drawText(
                context.getString(R.string.pdf_spectrum_source_image_title),
                PdfPageCanvas.PAGE_WIDTH / 2f,
                currentY,
                sectionPaint
            )
            currentY += 25f

            val widthScale = (PdfPageCanvas.CONTENT_WIDTH - 100f) / source.width
            val scale = if (source.height * widthScale > SOURCE_IMAGE_MAX_HEIGHT) {
                SOURCE_IMAGE_MAX_HEIGHT / source.height
            } else {
                widthScale
            }
            val scaledWidth = source.width * scale
            val scaledHeight = source.height * scale
            val left = (PdfPageCanvas.PAGE_WIDTH - scaledWidth) / 2f
            canvas.drawBitmap(
                source,
                null,
                RectF(left, currentY, left + scaledWidth, currentY + scaledHeight),
                null
            )
            currentY + scaledHeight + 30f
        } finally {
            source.recycle()
        }
    }

    private fun renderChannelChart(
        context: Context,
        channel: SpectrumChannelExportModel
    ): Bitmap? = runCatching {
        SpectrumChartRenderer.renderChannelCurve(
            channel = channel,
            title = context.getString(
                R.string.spectrum_chart_title_format,
                channel.channelIndex,
                channel.analyteName
            ),
            labels = chartLabels(context)
        )
    }.onFailure { error ->
        Log.e(LOG_TAG, "生成通道曲线图失败", error)
    }.getOrNull()

    private fun renderMergedChart(
        context: Context,
        channels: List<SpectrumChannelExportModel>
    ): Bitmap? = runCatching {
        SpectrumChartRenderer.renderMergedCurves(
            channels = channels,
            title = context.getString(R.string.spectrum_merged_chart_title),
            labels = chartLabels(context)
        )
    }.onFailure { error ->
        Log.e(LOG_TAG, "生成合并曲线图失败", error)
    }.getOrNull()

    private fun chartLabels(context: Context) = SpectrumChartLabels(
        wavelengthAxis = context.getString(R.string.spectrum_axis_wavelength),
        intensityAxis = context.getString(R.string.spectrum_axis_intensity)
    )

    /** 缺失峰值统一写短横线，避免下游把空白当成 0。 */
    private const val MISSING: String = "-"
}
