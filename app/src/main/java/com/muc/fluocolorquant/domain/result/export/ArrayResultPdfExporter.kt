package com.muc.fluocolorquant.domain.result.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQualityLevel
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.resolveQualityLevel
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.utils.pdf.PdfCoverPageContent
import com.muc.fluocolorquant.utils.pdf.PdfCoverPageRenderer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.abs

/**
 * 使用统一 XML 封面模板和 Android 原生 PdfDocument 绘制科研摘要。
 *
 * PDF 只读取冻结结果领域，不访问数据库；封面复用96孔板报告的 pdf_cover_page.xml，
 * 后续热力图颜色与 QC 标记分开绘制，避免失败标记覆盖科学数值底色。
 */
object ArrayResultPdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 40f

    fun createPdf(
        context: Context,
        snapshot: ArrayResultSnapshot,
        labels: ArrayResultPdfLabels,
        validations: Map<String, ResultValidationSnapshot> = emptyMap(),
        validationLabels: ArrayResultValidationPdfLabels? = null
    ): ByteArray {
        val document = PdfDocument()
        val sortedAnalytes = snapshot.analytes.sortedBy(ArrayAnalyteResult::displayOrder)
        val validationEntries = if (validationLabels == null) {
            emptyList()
        } else {
            sortedAnalytes.mapNotNull { analyte ->
                validations[analyte.analyteId]?.let { validation -> analyte to validation }
            }
        }
        // 页数为：封面 + 总览 + 每个分析物一页 + 每个验证结果一页 + QC/追溯。
        val totalPages = sortedAnalytes.size + validationEntries.size + 3
        try {
            drawCoverPage(
                document = document,
                context = context,
                snapshot = snapshot,
                labels = labels,
                totalPages = totalPages,
                validationEntries = validationEntries,
                validationLabels = validationLabels
            )
            drawOverviewPage(document, snapshot, labels, pageNumber = 2, totalPages = totalPages)
            sortedAnalytes.forEachIndexed { index, analyte ->
                drawAnalytePage(
                    document = document,
                    snapshot = snapshot,
                    analyte = analyte,
                    labels = labels,
                    pageNumber = index + 3,
                    totalPages = totalPages
                )
            }
            validationEntries.forEachIndexed { index, (analyte, validation) ->
                drawValidationPage(
                    document = document,
                    analyte = analyte,
                    validation = validation,
                    labels = requireNotNull(validationLabels),
                    footerLabels = labels,
                    pageNumber = sortedAnalytes.size + index + 3,
                    totalPages = totalPages
                )
            }
            drawTraceabilityPage(
                document = document,
                snapshot = snapshot,
                labels = labels,
                pageNumber = totalPages,
                totalPages = totalPages
            )
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    /** 使用用户指定的 pdf_cover_page.xml 生成第一页，不再单独手绘一套阵列封面。 */
    private fun drawCoverPage(
        document: PdfDocument,
        context: Context,
        snapshot: ArrayResultSnapshot,
        labels: ArrayResultPdfLabels,
        totalPages: Int,
        validationEntries: List<Pair<ArrayAnalyteResult, ResultValidationSnapshot>>,
        validationLabels: ArrayResultValidationPdfLabels?
    ) {
        val page = document.startPage(pageInfo(1))
        val sortedAnalytes = snapshot.analytes.sortedBy(ArrayAnalyteResult::displayOrder)
        val overviewLines = buildList {
            add(context.getString(R.string.pdf_chapter_title_format, 1, labels.overviewHeatmap))
            sortedAnalytes.forEachIndexed { index, analyte ->
                add(context.getString(R.string.pdf_chapter_title_format, index + 2, analyte.name))
            }
            validationEntries.forEachIndexed { index, (analyte, _) ->
                add(
                    context.getString(
                        R.string.pdf_chapter_title_format,
                        sortedAnalytes.size + index + 2,
                        "${validationLabels?.title.orEmpty()} · ${analyte.name}"
                    )
                )
            }
            add(
                context.getString(
                    R.string.pdf_chapter_title_format,
                    sortedAnalytes.size + validationEntries.size + 2,
                    labels.qualitySummary
                )
            )
        }
        PdfCoverPageRenderer.draw(
            context = context,
            targetCanvas = page.canvas,
            pageWidth = PAGE_WIDTH,
            pageHeight = PAGE_HEIGHT,
            content = PdfCoverPageContent(
                title = labels.documentTitle,
                projectLine = context.getString(
                    R.string.pdf_label_project_name_format,
                    snapshot.projectName
                ),
                dateLine = context.getString(
                    R.string.array_pdf_cover_run_time_format,
                    formatTimestamp(snapshot.runTimestampEpochMillis)
                ),
                detectionModeLine = context.getString(
                    R.string.pdf_label_detection_mode_format,
                    snapshot.detectionMode
                ),
                overviewLines = overviewLines,
                generatedAtLine = context.getString(
                    R.string.pdf_generated_on,
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                        Locale.getDefault()
                    ).format(Date())
                ),
                pageNumberLine = context.getString(R.string.pdf_page_number, 1, totalPages)
            )
        )
        document.finishPage(page)
    }

    private fun drawOverviewPage(
        document: PdfDocument,
        snapshot: ArrayResultSnapshot,
        labels: ArrayResultPdfLabels,
        pageNumber: Int,
        totalPages: Int
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        val canvas = page.canvas
        val titlePaint = textPaint(22f, Color.rgb(28, 44, 61), bold = true)
        val bodyPaint = textPaint(10.5f, Color.rgb(55, 65, 81))
        val labelPaint = textPaint(10.5f, Color.rgb(75, 85, 99), bold = true)
        canvas.drawText(labels.documentTitle, PAGE_MARGIN, 54f, titlePaint)
        var y = drawWrappedText(
            canvas,
            labels.frozenEvidenceNote,
            PAGE_MARGIN,
            75f,
            PAGE_WIDTH - PAGE_MARGIN * 2,
            bodyPaint,
            14f
        ) + 10f
        y = drawKeyValue(canvas, labels.project, snapshot.projectName, y, labelPaint, bodyPaint)
        y = drawKeyValue(canvas, labels.runId, snapshot.runId, y, labelPaint, bodyPaint)
        y = drawKeyValue(
            canvas,
            labels.runTime,
            formatTimestamp(snapshot.runTimestampEpochMillis),
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(canvas, labels.status, snapshot.runStatus, y, labelPaint, bodyPaint)
        y = drawKeyValue(canvas, labels.detectionMode, snapshot.detectionMode, y, labelPaint, bodyPaint)
        y = drawKeyValue(
            canvas,
            labels.carrier,
            "${snapshot.carrier.name} v${snapshot.carrier.version}",
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(canvas, labels.layout, "${snapshot.rows} × ${snapshot.columns}", y, labelPaint, bodyPaint)
        y = drawKeyValue(
            canvas,
            labels.physicalSites,
            snapshot.sites.size.toString(),
            y,
            labelPaint,
            bodyPaint
        )

        val measurementRecords = snapshot.sites.flatMap { site ->
            site.measurements.map { measurement -> site to measurement }
        }
        val measurementCount = measurementRecords.size
        val qualityCounts = measurementRecords.groupingBy { (site, measurement) ->
            measurement.resolveQualityLevel(site)
        }.eachCount()
        y += 5f
        drawSummaryMetric(canvas, labels.measurements, measurementCount.toString(), PAGE_MARGIN, y, 116f)
        drawSummaryMetric(
            canvas,
            labels.validMeasurements,
            qualityCounts[ArrayMeasurementQualityLevel.VALID].orZero().toString(),
            169f,
            y,
            116f
        )
        drawSummaryMetric(
            canvas,
            labels.reviewMeasurements,
            qualityCounts[ArrayMeasurementQualityLevel.REVIEW].orZero().toString(),
            298f,
            y,
            116f
        )
        drawSummaryMetric(
            canvas,
            labels.unavailableMeasurements,
            qualityCounts[ArrayMeasurementQualityLevel.UNAVAILABLE].orZero().toString(),
            427f,
            y,
            116f
        )
        y += 64f

        canvas.drawText(labels.overviewHeatmap, PAGE_MARGIN, y, textPaint(14f, Color.rgb(31, 41, 55), true))
        y += 12f
        drawGrid(
            canvas = canvas,
            snapshot = snapshot,
            top = y,
            maxHeight = min(420f, PAGE_HEIGHT - y - 70f),
            cellColor = { site -> overviewColor(site) }
        )
        drawFooter(canvas, pageNumber, totalPages, labels)
        document.finishPage(page)
    }

    private fun drawAnalytePage(
        document: PdfDocument,
        snapshot: ArrayResultSnapshot,
        analyte: ArrayAnalyteResult,
        labels: ArrayResultPdfLabels,
        pageNumber: Int,
        totalPages: Int
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        val canvas = page.canvas
        val titlePaint = textPaint(20f, Color.rgb(28, 44, 61), bold = true)
        val bodyPaint = textPaint(10.5f, Color.rgb(55, 65, 81))
        val labelPaint = textPaint(10.5f, Color.rgb(75, 85, 99), bold = true)
        canvas.drawText("${labels.analyteSection}: ${analyte.name}", PAGE_MARGIN, 54f, titlePaint)
        var y = 83f
        y = drawKeyValue(
            canvas,
            labels.model,
            "${analyte.modelName} v${analyte.modelVersion}",
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(canvas, labels.primaryFeature, analyte.primaryFeature, y, labelPaint, bodyPaint)
        y = drawKeyValue(
            canvas,
            labels.projectRange,
            rangeText(
                analyte.projectRangeMin,
                analyte.projectRangeMax,
                analyte.concentrationUnit,
                labels.noValue
            ),
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(
            canvas,
            labels.calibrationRange,
            rangeText(
                analyte.calibrationRangeMin,
                analyte.calibrationRangeMax,
                analyte.concentrationUnit,
                labels.noValue
            ),
            y,
            labelPaint,
            bodyPaint
        )

        val siteMeasurements = snapshot.sites.mapNotNull { site ->
            site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
        }
        val concentrationValues = siteMeasurements.mapNotNull {
            it.concentrationHeatmapValue()?.finiteOrNull()
        }
        val useConcentration = concentrationValues.isNotEmpty() || siteMeasurements.any {
            it.quantificationState.equals("BOUND_ONLY", ignoreCase = true)
        }
        val observedValues = if (useConcentration) {
            concentrationValues
        } else {
            siteMeasurements.mapNotNull { it.primaryFeatureValue?.finiteOrNull() }
        }
        val minimum = if (useConcentration) {
            analyte.projectRangeMin?.finiteOrNull() ?: observedValues.minOrNull() ?: 0.0
        } else {
            observedValues.minOrNull() ?: 0.0
        }
        val maximum = if (useConcentration) {
            analyte.projectRangeMax?.finiteOrNull() ?: observedValues.maxOrNull() ?: 1.0
        } else {
            observedValues.maxOrNull() ?: 1.0
        }
        y += 10f
        canvas.drawText(
            if (useConcentration) labels.concentrationHeatmap else labels.signalHeatmap,
            PAGE_MARGIN,
            y,
            textPaint(14f, Color.rgb(31, 41, 55), true)
        )
        y += 12f
        drawGrid(
            canvas = canvas,
            snapshot = snapshot,
            top = y,
            maxHeight = min(520f, PAGE_HEIGHT - y - 75f),
            cellColor = { site ->
                val measurement = site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
                val value = if (useConcentration) {
                    measurement?.concentrationHeatmapValue()
                } else {
                    measurement?.primaryFeatureValue
                }
                value?.finiteOrNull()?.let { heatmapColor(it, minimum, maximum) }
                    ?: Color.rgb(226, 232, 240)
            },
            qualityForSite = { site ->
                val measurement = site.measurements.firstOrNull {
                    it.analyteId == analyte.analyteId
                }
                val value = if (useConcentration) {
                    measurement?.concentrationHeatmapValue()
                } else {
                    measurement?.primaryFeatureValue
                }
                measurement?.resolveQualityLevel(site, value)
            },
            measurementForQc = { site ->
                site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
            }
        )
        drawFooter(canvas, pageNumber, totalPages, labels)
        document.finishPage(page)
    }

    /**
     * 将保存后的预测精度验证绘制为独立科研图表页。
     *
     * 页面同时保留回归图和Bland–Altman图，所有点都来自验证修订中的冻结预测值与参考值，
     * 不读取当前项目或重新计算浓度。
     */
    private fun drawValidationPage(
        document: PdfDocument,
        analyte: ArrayAnalyteResult,
        validation: ResultValidationSnapshot,
        labels: ArrayResultValidationPdfLabels,
        footerLabels: ArrayResultPdfLabels,
        pageNumber: Int,
        totalPages: Int
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        val canvas = page.canvas
        canvas.drawText(
            "${labels.title} · ${analyte.name}",
            PAGE_MARGIN,
            54f,
            textPaint(21f, Color.rgb(28, 44, 61), bold = true)
        )
        canvas.drawText(
            String.format(
                Locale.getDefault(),
                labels.summaryFormat,
                validation.concentrationUnit,
                validation.revision,
                validation.points.size
            ),
            PAGE_MARGIN,
            78f,
            textPaint(10f, Color.rgb(71, 85, 105))
        )

        val metricWidth = 121f
        val metricGap = 10f
        val metricLefts = List(4) { index -> PAGE_MARGIN + index * (metricWidth + metricGap) }
        val regression = validation.regression
        drawSummaryMetric(
            canvas,
            labels.rSquared,
            regression.rSquared.pdfNumberOr(footerLabels.noValue),
            metricLefts[0],
            96f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.slope,
            regression.slope.pdfNumberOr(footerLabels.noValue),
            metricLefts[1],
            96f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.rmse,
            regression.rmse.pdfNumberWithUnit(validation.concentrationUnit),
            metricLefts[2],
            96f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.mae,
            regression.mae.pdfNumberWithUnit(validation.concentrationUnit),
            metricLefts[3],
            96f,
            metricWidth
        )
        val bland = validation.blandAltman
        drawSummaryMetric(
            canvas,
            labels.meanBias,
            bland.meanBias.pdfNumberWithUnit(validation.concentrationUnit),
            metricLefts[0],
            154f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.lowerLimit,
            bland.lowerLimit.pdfNumberWithUnit(validation.concentrationUnit),
            metricLefts[1],
            154f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.upperLimit,
            bland.upperLimit.pdfNumberWithUnit(validation.concentrationUnit),
            metricLefts[2],
            154f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.withinLimits,
            "${formatPdfNumber(bland.withinLimitsRatio * 100.0)}%",
            metricLefts[3],
            154f,
            metricWidth
        )

        val referencePredictionPoints = validation.points.map { point ->
            point.referenceValue to point.predictedValue
        }
        val commonRange = paddedPdfRange(
            validation.points.flatMap { point -> listOf(point.referenceValue, point.predictedValue) }
        )
        val regressionLines = buildList {
            add(
                PdfValidationLine(
                    points = listOf(commonRange.first to commonRange.first, commonRange.second to commonRange.second),
                    color = Color.rgb(148, 163, 184)
                )
            )
            val slope = regression.slope
            val intercept = regression.intercept
            if (slope != null && intercept != null && slope.isFinite() && intercept.isFinite()) {
                add(
                    PdfValidationLine(
                        points = listOf(
                            commonRange.first to (slope * commonRange.first + intercept),
                            commonRange.second to (slope * commonRange.second + intercept)
                        ),
                        color = Color.rgb(13, 148, 136)
                    )
                )
            }
        }
        canvas.drawText(
            labels.regressionTitle,
            PAGE_MARGIN,
            229f,
            textPaint(13f, Color.rgb(31, 41, 55), true)
        )
        drawValidationPlot(
            canvas = canvas,
            bounds = RectF(PAGE_MARGIN, 240f, PAGE_WIDTH - PAGE_MARGIN, 472f),
            points = referencePredictionPoints,
            xRange = commonRange,
            yRange = commonRange,
            lines = regressionLines,
            xAxisLabel = labels.referenceAxis,
            yAxisLabel = labels.predictedAxis
        )

        val blandPoints = validation.points.map { point ->
            ((point.predictedValue + point.referenceValue) / 2.0) to
                (point.predictedValue - point.referenceValue)
        }
        val blandXRange = paddedPdfRange(blandPoints.map { point -> point.first })
        val blandYRange = paddedPdfRange(
            blandPoints.map { point -> point.second } +
                listOf(bland.meanBias, bland.lowerLimit, bland.upperLimit)
        )
        val blandLines = listOf(
            PdfValidationLine(horizontalPdfLine(blandXRange, bland.meanBias), Color.rgb(13, 148, 136)),
            PdfValidationLine(horizontalPdfLine(blandXRange, bland.lowerLimit), Color.rgb(234, 88, 12)),
            PdfValidationLine(horizontalPdfLine(blandXRange, bland.upperLimit), Color.rgb(234, 88, 12))
        )
        canvas.drawText(
            labels.blandAltmanTitle,
            PAGE_MARGIN,
            507f,
            textPaint(13f, Color.rgb(31, 41, 55), true)
        )
        drawValidationPlot(
            canvas = canvas,
            bounds = RectF(PAGE_MARGIN, 518f, PAGE_WIDTH - PAGE_MARGIN, 750f),
            points = blandPoints,
            xRange = blandXRange,
            yRange = blandYRange,
            lines = blandLines,
            xAxisLabel = labels.meanAxis,
            yAxisLabel = labels.differenceAxis
        )
        drawFooter(canvas, pageNumber, totalPages, footerLabels)
        document.finishPage(page)
    }

    /** 绘制轻量散点图；固定留白保证中英文轴标签和极值不会覆盖数据点。 */
    private fun drawValidationPlot(
        canvas: Canvas,
        bounds: RectF,
        points: List<Pair<Double, Double>>,
        xRange: Pair<Double, Double>,
        yRange: Pair<Double, Double>,
        lines: List<PdfValidationLine>,
        xAxisLabel: String,
        yAxisLabel: String
    ) {
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, 10f, 10f, framePaint)
        val plot = RectF(bounds.left + 46f, bounds.top + 18f, bounds.right - 16f, bounds.bottom - 38f)
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(plot, axisPaint)

        fun mapPoint(point: Pair<Double, Double>): Pair<Float, Float> {
            val xRatio = ((point.first - xRange.first) / (xRange.second - xRange.first))
                .coerceIn(0.0, 1.0)
            val yRatio = ((point.second - yRange.first) / (yRange.second - yRange.first))
                .coerceIn(0.0, 1.0)
            return (plot.left + plot.width() * xRatio.toFloat()) to
                (plot.bottom - plot.height() * yRatio.toFloat())
        }

        lines.forEach { line ->
            if (line.points.size >= 2) {
                val start = mapPoint(line.points.first())
                val end = mapPoint(line.points.last())
                canvas.drawLine(
                    start.first,
                    start.second,
                    end.first,
                    end.second,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = line.color
                        strokeWidth = 2f
                    }
                )
            }
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235)
            style = Paint.Style.FILL
        }
        points.filter { point -> point.first.isFinite() && point.second.isFinite() }.forEach { point ->
            val mapped = mapPoint(point)
            canvas.drawCircle(mapped.first, mapped.second, 3.4f, pointPaint)
        }

        val labelPaint = textPaint(7.5f, Color.rgb(71, 85, 105))
        canvas.drawText(formatPdfNumber(xRange.first), plot.left, bounds.bottom - 23f, labelPaint)
        val xMax = formatPdfNumber(xRange.second)
        canvas.drawText(xMax, plot.right - labelPaint.measureText(xMax), bounds.bottom - 23f, labelPaint)
        canvas.drawText(formatPdfNumber(yRange.first), bounds.left + 4f, plot.bottom, labelPaint)
        canvas.drawText(formatPdfNumber(yRange.second), bounds.left + 4f, plot.top + 7f, labelPaint)
        val xAxisPaint = textPaint(8.5f, Color.rgb(51, 65, 85), true)
        canvas.drawText(
            xAxisLabel,
            plot.centerX() - xAxisPaint.measureText(xAxisLabel) / 2f,
            bounds.bottom - 7f,
            xAxisPaint
        )
        canvas.drawText(yAxisLabel, bounds.left + 4f, bounds.top + 12f, xAxisPaint)
    }

    private data class PdfValidationLine(
        val points: List<Pair<Double, Double>>,
        val color: Int
    )

    private fun horizontalPdfLine(
        xRange: Pair<Double, Double>,
        value: Double
    ): List<Pair<Double, Double>> = listOf(xRange.first to value, xRange.second to value)

    private fun paddedPdfRange(values: List<Double>): Pair<Double, Double> {
        val finite = values.filter(Double::isFinite)
        if (finite.isEmpty()) return 0.0 to 1.0
        val minimum = finite.minOrNull() ?: 0.0
        val maximum = finite.maxOrNull() ?: 1.0
        val span = (maximum - minimum).takeIf { it > 0.0 }
            ?: maxOf(abs(maximum) * 0.2, 1.0)
        return (minimum - span * 0.1) to (maximum + span * 0.1)
    }

    private fun Double?.pdfNumberOr(missing: String): String =
        this?.takeIf(Double::isFinite)?.let(::formatPdfNumber) ?: missing

    private fun Double.pdfNumberWithUnit(unit: String): String =
        if (unit.isBlank()) formatPdfNumber(this) else "${formatPdfNumber(this)} $unit"

    private fun formatPdfNumber(value: Double): String = when {
        !value.isFinite() -> "—"
        abs(value) >= 10_000.0 || (value != 0.0 && abs(value) < 0.001) ->
            String.format(Locale.US, "%.3e", value)
        else -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    }

    private fun drawTraceabilityPage(
        document: PdfDocument,
        snapshot: ArrayResultSnapshot,
        labels: ArrayResultPdfLabels,
        pageNumber: Int,
        totalPages: Int
    ) {
        val page = document.startPage(pageInfo(pageNumber))
        val canvas = page.canvas
        val titlePaint = textPaint(20f, Color.rgb(28, 44, 61), bold = true)
        val sectionPaint = textPaint(14f, Color.rgb(31, 41, 55), bold = true)
        val bodyPaint = textPaint(10.5f, Color.rgb(55, 65, 81))
        val labelPaint = textPaint(10.5f, Color.rgb(75, 85, 99), bold = true)
        canvas.drawText(labels.qualitySummary, PAGE_MARGIN, 54f, titlePaint)

        val measurementRecords = snapshot.sites.flatMap { site ->
            site.measurements.map { measurement -> site to measurement }
        }
        val reviewCount = measurementRecords.count { (site, measurement) ->
            measurement.resolveQualityLevel(site) == ArrayMeasurementQualityLevel.REVIEW
        }
        val unavailableCount = measurementRecords.count { (site, measurement) ->
            measurement.resolveQualityLevel(site) == ArrayMeasurementQualityLevel.UNAVAILABLE
        }
        val lowSignalCount = measurementRecords.count { (_, measurement) ->
            !measurement.signalDetectable
        }
        var y = 86f
        y = drawKeyValue(
            canvas,
            labels.frameReviewCount,
            snapshot.frame.qcIssues.size.toString(),
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(
            canvas,
            labels.reviewMeasurementCount,
            reviewCount.toString(),
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(
            canvas,
            labels.unavailableMeasurementCount,
            unavailableCount.toString(),
            y,
            labelPaint,
            bodyPaint
        )
        y = drawKeyValue(canvas, labels.lowSignalCount, lowSignalCount.toString(), y, labelPaint, bodyPaint)
        if (snapshot.frame.qcIssues.isNotEmpty()) {
            y += 8f
            for (issue in snapshot.frame.qcIssues.take(12)) {
                val evidence = "${issue.severity.name} · ${issue.code.name} · ${issue.measuredValue} / ${issue.threshold}"
                y = drawWrappedText(
                    canvas,
                    evidence,
                    PAGE_MARGIN + 8f,
                    y,
                    PAGE_WIDTH - PAGE_MARGIN * 2 - 8f,
                    bodyPaint,
                    14f
                ) + 4f
            }
        }

        y += 18f
        canvas.drawText(labels.traceability, PAGE_MARGIN, y, sectionPaint)
        y += 24f
        y = drawKeyValue(
            canvas,
            labels.locator,
            "${snapshot.frame.locatorName} ${snapshot.frame.locatorVersion}",
            y,
            labelPaint,
            bodyPaint
        )
        val processors = snapshot.analytes
            .map { "${it.processorName} ${it.processorVersion}" }
            .distinct()
            .joinToString()
            .ifBlank { labels.noValue }
        y = drawKeyValue(canvas, labels.processor, processors, y, labelPaint, bodyPaint)
        canvas.drawText(labels.frozenSnapshot, PAGE_MARGIN, y, labelPaint)
        y += 18f
        y = drawWrappedText(
            canvas,
            "SHA-256 ${ArrayResultExporter.sha256(snapshot.effectiveConfigSnapshotJson.toByteArray(StandardCharsets.UTF_8))}",
            PAGE_MARGIN,
            y,
            PAGE_WIDTH - PAGE_MARGIN * 2,
            bodyPaint,
            14f
        ) + 5f
        y += 12f
        drawWrappedText(
            canvas,
            snapshot.runId,
            PAGE_MARGIN,
            y,
            PAGE_WIDTH - PAGE_MARGIN * 2,
            bodyPaint,
            14f
        )
        drawFooter(canvas, pageNumber, totalPages, labels)
        document.finishPage(page)
    }

    private fun drawGrid(
        canvas: Canvas,
        snapshot: ArrayResultSnapshot,
        top: Float,
        maxHeight: Float,
        cellColor: (ArrayPhysicalSiteResult) -> Int,
        qualityForSite: ((ArrayPhysicalSiteResult) -> ArrayMeasurementQualityLevel?)? = null,
        measurementForQc: ((ArrayPhysicalSiteResult) -> ArraySiteMeasurementResult?)? = null
    ) {
        val width = PAGE_WIDTH - PAGE_MARGIN * 2
        // 10×10/15×15 仍会尽量使用页面宽度；小阵列限制单元上限，避免 1×1 或 4×4
        // 被拉伸成缺乏阵列语义的整页色块。
        val cellSize = minOf(
            width / snapshot.columns.coerceAtLeast(1),
            maxHeight / snapshot.rows.coerceAtLeast(1),
            42f
        )
        val gridWidth = cellSize * snapshot.columns
        val left = PAGE_MARGIN + (width - gridWidth) / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = Color.argb(100, 71, 85, 105)
        }
        val reviewBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = min(2.2f, cellSize / 4f)
            color = Color.rgb(245, 158, 11)
        }
        val unavailableBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = min(2.2f, cellSize / 4f)
            color = Color.rgb(220, 38, 38)
        }
        val lowSignal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(2, 136, 209)
        }
        val quantificationMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val circularSites = snapshot.carrier.siteShape.equals("CIRCLE", ignoreCase = true)
        val sitesByIndex = snapshot.sites.associateBy(ArrayPhysicalSiteResult::siteIndex)
        for (row in 0 until snapshot.rows) {
            for (column in 0 until snapshot.columns) {
                val index = row * snapshot.columns + column
                val site = sitesByIndex[index]
                val rect = RectF(
                    left + column * cellSize,
                    top + row * cellSize,
                    left + (column + 1) * cellSize,
                    top + (row + 1) * cellSize
                )
                fill.color = site?.let(cellColor) ?: Color.rgb(241, 245, 249)
                if (circularSites) {
                    val radius = cellSize * 0.43f
                    canvas.drawCircle(rect.centerX(), rect.centerY(), radius, fill)
                    canvas.drawCircle(rect.centerX(), rect.centerY(), radius, border)
                } else {
                    canvas.drawRect(rect, fill)
                    canvas.drawRect(rect, border)
                }
                when (site?.let { qualityForSite?.invoke(it) }) {
                    ArrayMeasurementQualityLevel.REVIEW -> if (circularSites) {
                        canvas.drawCircle(rect.centerX(), rect.centerY(), cellSize * 0.43f, reviewBorder)
                    } else {
                        canvas.drawRect(rect, reviewBorder)
                    }
                    ArrayMeasurementQualityLevel.UNAVAILABLE -> if (circularSites) {
                        canvas.drawCircle(rect.centerX(), rect.centerY(), cellSize * 0.43f, unavailableBorder)
                    } else {
                        canvas.drawRect(rect, unavailableBorder)
                    }
                    ArrayMeasurementQualityLevel.VALID,
                    null -> Unit
                }
                val measurement = site?.let { measurementForQc?.invoke(it) }
                measurement?.quantificationMarker()?.let { marker ->
                    quantificationMarker.textSize = min(9f, cellSize * 0.30f)
                    // 估计值使用右上角轻量≈；单侧界限使用中央</>，即使黑白打印也可区分。
                    val markerX = if (marker == "≈") rect.right - cellSize * 0.20f else rect.centerX()
                    val markerY = if (marker == "≈") {
                        rect.top + quantificationMarker.textSize
                    } else {
                        rect.centerY() -
                            (quantificationMarker.ascent() + quantificationMarker.descent()) / 2f
                    }
                    quantificationMarker.color = if (marker == "≈") {
                        Color.WHITE
                    } else {
                        Color.rgb(31, 41, 55)
                    }
                    canvas.drawText(marker, markerX, markerY, quantificationMarker)
                }
                if (measurement != null && !measurement.signalDetectable) {
                    canvas.drawCircle(
                        rect.right - cellSize * 0.22f,
                        rect.top + cellSize * 0.22f,
                        min(3f, cellSize * 0.10f),
                        lowSignal
                    )
                }
            }
        }
    }

    private fun overviewColor(site: ArrayPhysicalSiteResult): Int {
        if (site.measurements.isEmpty()) return Color.rgb(226, 232, 240)
        val levels = site.measurements.map { measurement -> measurement.resolveQualityLevel(site) }
        return when {
            ArrayMeasurementQualityLevel.UNAVAILABLE in levels -> Color.rgb(254, 226, 226)
            ArrayMeasurementQualityLevel.REVIEW in levels -> Color.rgb(254, 240, 190)
            else -> Color.rgb(187, 247, 208)
        }
    }

    /** PDF 热力图与页面/CSV共用同一浓度语义，单侧界限只用于端点着色，不冒充点浓度。 */
    private fun ArraySiteMeasurementResult.concentrationHeatmapValue(): Double? =
        concentrationValue?.finiteOrNull()
            ?: when {
                quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                    censoringDirection.equals("LOWER_BOUND", ignoreCase = true) ->
                    concentrationLowerBound?.finiteOrNull()
                quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                    censoringDirection.equals("UPPER_BOUND", ignoreCase = true) ->
                    concentrationUpperBound?.finiteOrNull()
                else -> null
            }

    private fun ArraySiteMeasurementResult.quantificationMarker(): String? = when {
        quantificationState.equals("ESTIMATED", ignoreCase = true) -> "≈"
        quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            censoringDirection.equals("LOWER_BOUND", ignoreCase = true) -> ">"
        quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            censoringDirection.equals("UPPER_BOUND", ignoreCase = true) -> "<"
        else -> null
    }

    private fun heatmapColor(value: Double, minimum: Double, maximum: Double): Int {
        val normalized = if (maximum > minimum) {
            ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return if (normalized <= 0.5) {
            interpolateColor(Color.rgb(37, 99, 235), Color.rgb(245, 158, 11), normalized * 2.0)
        } else {
            interpolateColor(Color.rgb(245, 158, 11), Color.rgb(220, 38, 38), (normalized - 0.5) * 2.0)
        }
    }

    private fun interpolateColor(start: Int, end: Int, ratio: Double): Int {
        fun channel(startValue: Int, endValue: Int): Int {
            return (startValue + (endValue - startValue) * ratio).toInt().coerceIn(0, 255)
        }
        return Color.rgb(
            channel(Color.red(start), Color.red(end)),
            channel(Color.green(start), Color.green(end)),
            channel(Color.blue(start), Color.blue(end))
        )
    }

    private fun drawSummaryMetric(
        canvas: Canvas,
        label: String,
        value: String,
        left: Float,
        top: Float,
        width: Float = 150f
    ) {
        val rect = RectF(left, top, left + width, top + 50f)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 245, 249)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 10f, 10f, background)
        canvas.drawText(value, left + 12f, top + 22f, textPaint(16f, Color.rgb(15, 118, 110), true))
        canvas.drawText(label, left + 12f, top + 39f, textPaint(8.5f, Color.rgb(71, 85, 105)))
    }

    private fun drawKeyValue(
        canvas: Canvas,
        label: String,
        value: String,
        top: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ): Float {
        canvas.drawText(label, PAGE_MARGIN, top, labelPaint)
        val nextY = drawWrappedText(
            canvas,
            value,
            165f,
            top,
            PAGE_WIDTH - 165f - PAGE_MARGIN,
            valuePaint,
            14f
        )
        return nextY + 5f
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        maxWidth: Float,
        paint: Paint,
        lineHeight: Float
    ): Float {
        if (text.isEmpty()) return top
        var y = top
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null)
                .coerceAtLeast(1)
            val newline = text.indexOf('\n', start).takeIf { it in start until (start + count) }
            val end = newline ?: (start + count)
            canvas.drawText(text, start, end, left, y, paint)
            start = if (newline != null) newline + 1 else end
            y += lineHeight
        }
        return y
    }

    private fun drawFooter(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        labels: ArrayResultPdfLabels
    ) {
        val footer = String.format(Locale.getDefault(), labels.pageFormat, pageNumber, totalPages)
        val paint = textPaint(9f, Color.rgb(100, 116, 139))
        canvas.drawText(footer, PAGE_WIDTH - PAGE_MARGIN - paint.measureText(footer), PAGE_HEIGHT - 24f, paint)
    }

    private fun rangeText(
        minimumValue: Double?,
        maximumValue: Double?,
        unit: String,
        missing: String
    ): String {
        val minimum = minimumValue?.finiteOrNull() ?: return missing
        val maximum = maximumValue?.finiteOrNull() ?: return missing
        return "$minimum – $maximum $unit"
    }

    private fun formatTimestamp(timestamp: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(timestamp))
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo {
        return PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    private fun Double.finiteOrNull(): Double? = takeIf(Double::isFinite)

    private fun Int?.orZero(): Int = this ?: 0
}
