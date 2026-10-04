package com.muc.fluocolorquant.domain.result.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQualityLevel
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.resolveQualityLevel
import com.muc.fluocolorquant.domain.result.plate96.plateWellLabel
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.utils.math.FittingEngine
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
    private const val WELL_DETAIL_ROWS_PER_PAGE = 10

    fun createPdf(
        context: Context,
        snapshot: ArrayResultSnapshot,
        labels: ArrayResultPdfLabels,
        validations: Map<String, ResultValidationSnapshot> = emptyMap(),
        validationLabels: ArrayResultValidationPdfLabels? = null,
        wellImageProvider: ((Int) -> Bitmap?)? = null
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
        val calibrationAnalyteIds = sortedAnalytes
            .filter(::hasRenderableCalibration)
            .mapTo(mutableSetOf(), ArrayAnalyteResult::analyteId)
        val wellDetailRows = if (wellImageProvider == null) {
            emptyList()
        } else {
            buildWellDetailRows(snapshot, validations)
        }
        val wellDetailPageCount = wellDetailRows.chunked(WELL_DETAIL_ROWS_PER_PAGE).size
        // 页数为：封面 + 总览 + 分析物结果 + 可执行标准曲线 + 预测验证 + 追溯 + 逐孔图像附录。
        val totalPages = sortedAnalytes.size + calibrationAnalyteIds.size +
            validationEntries.size + wellDetailPageCount + 3
        try {
            drawCoverPage(
                document = document,
                context = context,
                snapshot = snapshot,
                labels = labels,
                totalPages = totalPages,
                validationEntries = validationEntries,
                validationLabels = validationLabels,
                includeWellDetails = wellDetailRows.isNotEmpty()
            )
            drawOverviewPage(document, snapshot, labels, pageNumber = 2, totalPages = totalPages)
            var pageNumber = 3
            sortedAnalytes.forEach { analyte ->
                drawAnalytePage(
                    document = document,
                    snapshot = snapshot,
                    analyte = analyte,
                    labels = labels,
                    pageNumber = pageNumber,
                    totalPages = totalPages
                )
                pageNumber += 1
                if (analyte.analyteId in calibrationAnalyteIds) {
                    drawCalibrationPage(
                        document = document,
                        context = context,
                        analyte = analyte,
                        labels = labels,
                        pageNumber = pageNumber,
                        totalPages = totalPages
                    )
                    pageNumber += 1
                }
                validations[analyte.analyteId]
                    ?.takeIf { validationLabels != null }
                    ?.let { validation ->
                        drawValidationPage(
                            document = document,
                            analyte = analyte,
                            validation = validation,
                            labels = requireNotNull(validationLabels),
                            footerLabels = labels,
                            pageNumber = pageNumber,
                            totalPages = totalPages
                        )
                        pageNumber += 1
                    }
            }
            drawTraceabilityPage(
                document = document,
                snapshot = snapshot,
                labels = labels,
                pageNumber = pageNumber,
                totalPages = totalPages
            )
            pageNumber += 1
            if (wellDetailRows.isNotEmpty()) {
                drawWellDetailPages(
                    document = document,
                    context = context,
                    rows = wellDetailRows,
                    labels = labels,
                    circularSites = snapshot.carrier.siteShape.equals("CIRCLE", ignoreCase = true),
                    wellImageProvider = requireNotNull(wellImageProvider),
                    startPageNumber = pageNumber,
                    totalPages = totalPages
                )
            }
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
        validationLabels: ArrayResultValidationPdfLabels?,
        includeWellDetails: Boolean
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
            if (includeWellDetails) {
                add(
                    context.getString(
                        R.string.pdf_chapter_title_format,
                        sortedAnalytes.size + validationEntries.size + 3,
                        context.getString(R.string.array_pdf_well_details_title)
                    )
                )
            }
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

        // PDF 与单独 PNG 必须使用同一个范围解析器和同一套专业色带。此前这里自建
        // 蓝-橙-红插值，而 PNG 使用蓝-青-绿-黄-红，导致相同冻结浓度看起来像两份结果。
        val scale = resolveArrayResultExportHeatmapScale(snapshot, analyte)
        val useConcentration = scale.useConcentration
        y += 10f
        canvas.drawText(
            if (useConcentration) labels.concentrationHeatmap else labels.signalHeatmap,
            PAGE_MARGIN,
            y,
            textPaint(14f, Color.rgb(31, 41, 55), true)
        )
        y += 12f
        val gridBottom = drawGrid(
            canvas = canvas,
            snapshot = snapshot,
            top = y,
            maxHeight = min(520f, PAGE_HEIGHT - y - 75f),
            cellColor = { site ->
                val measurement = site.measurementForExport(analyte.analyteId)
                val value = if (useConcentration) {
                    measurement?.exportHeatmapValue()
                } else {
                    measurement?.primaryFeatureValue
                }
                value?.finiteOrNull()?.let { exportHeatmapColor(it, scale) }
                    ?: Color.rgb(226, 232, 240)
            },
            qualityForSite = { site ->
                val measurement = site.measurementForExport(analyte.analyteId)
                val value = if (useConcentration) {
                    measurement?.exportHeatmapValue()
                } else {
                    measurement?.primaryFeatureValue
                }
                measurement?.resolveQualityLevel(site, value)
            },
            measurementForQc = { site ->
                site.measurementForExport(analyte.analyteId)
            }
        )
        drawHeatmapLegend(
            canvas = canvas,
            top = gridBottom + 12f,
            scale = scale,
            unit = if (useConcentration) analyte.concentrationUnit else analyte.primaryFeature
        )
        drawFooter(canvas, pageNumber, totalPages, labels)
        document.finishPage(page)
    }

    /**
     * 只有冻结函数、有限参数和至少两个不同浓度的标准点都存在时，才恢复旧报告中的
     * 标准曲线页。深度学习和仅信号运行不会生成空白曲线页面。
     */
    private fun hasRenderableCalibration(analyte: ArrayAnalyteResult): Boolean {
        val function = FittingFunction.fromIdentifier(analyte.fittingFunction.orEmpty()) ?: return false
        val points = analyte.calibrationPoints.filter { point ->
            point.concentration.isFinite() && point.signalValue.isFinite()
        }
        return points.map { it.concentration }.distinct().size >= 2 &&
            function.requiredParams.all { parameter ->
                analyte.fittingParameters[parameter]?.isFinite() == true
            }
    }

    /**
     * 恢复旧版“分析方案 + 标准曲线”的核心信息，但直接读取现代运行的冻结曲线快照。
     * 页面打开历史记录时不会重新拟合；曲线采样只是把已经冻结的函数和参数绘制出来。
     */
    private fun drawCalibrationPage(
        document: PdfDocument,
        context: Context,
        analyte: ArrayAnalyteResult,
        labels: ArrayResultPdfLabels,
        pageNumber: Int,
        totalPages: Int
    ) {
        val function = requireNotNull(FittingFunction.fromIdentifier(analyte.fittingFunction.orEmpty()))
        val standardPoints = analyte.calibrationPoints
            .filter { point -> point.concentration.isFinite() && point.signalValue.isFinite() }
            .sortedBy { point -> point.concentration }
        val concentrations = standardPoints.map { point -> point.concentration }
        val minimum = concentrations.minOrNull() ?: 0.0
        val maximum = concentrations.maxOrNull() ?: 1.0
        val curvePoints = List(161) { index ->
            val ratio = index / 160.0
            val concentration = minimum + (maximum - minimum) * ratio
            val signal = runCatching {
                FittingEngine.calculate(function, analyte.fittingParameters, concentration)
            }.getOrNull()
            if (signal?.isFinite() == true) concentration to signal else null
        }.filterNotNull()
        val yRange = paddedPdfRange(
            standardPoints.map { point -> point.signalValue } + curvePoints.map { point -> point.second }
        )
        val page = document.startPage(pageInfo(pageNumber))
        val canvas = page.canvas
        canvas.drawText(
            "${context.getString(R.string.pdf_standard_curve_title)} · ${analyte.name}",
            PAGE_MARGIN,
            54f,
            textPaint(21f, Color.rgb(28, 44, 61), bold = true)
        )
        val metricWidth = 121f
        val metricGap = 10f
        val metricLefts = List(4) { index -> PAGE_MARGIN + index * (metricWidth + metricGap) }
        drawSummaryMetric(
            canvas,
            context.getString(R.string.fitting_function),
            fittingFunctionName(context, function),
            metricLefts[0],
            78f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            context.getString(R.string.grid_quant_fit_metric_r2),
            (analyte.validationMetrics["R2"] ?: analyte.validationMetrics["R²"])
                .pdfNumberOr(labels.noValue),
            metricLefts[1],
            78f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            context.getString(R.string.array_pdf_standard_points),
            standardPoints.size.toString(),
            metricLefts[2],
            78f,
            metricWidth
        )
        drawSummaryMetric(
            canvas,
            labels.calibrationRange,
            "${formatPdfNumber(minimum)}–${formatPdfNumber(maximum)} ${analyte.concentrationUnit}",
            metricLefts[3],
            78f,
            metricWidth
        )

        canvas.drawText(
            context.getString(R.string.pdf_standard_curve_title),
            PAGE_MARGIN,
            154f,
            textPaint(13f, Color.rgb(31, 41, 55), true)
        )
        drawValidationPlot(
            canvas = canvas,
            bounds = RectF(PAGE_MARGIN, 166f, PAGE_WIDTH - PAGE_MARGIN, 638f),
            points = standardPoints.map { point -> point.concentration to point.signalValue },
            xRange = paddedPdfRange(concentrations),
            yRange = yRange,
            lines = if (curvePoints.size >= 2) {
                listOf(PdfValidationLine(curvePoints, Color.rgb(13, 148, 136)))
            } else {
                emptyList()
            },
            xAxisLabel = analyte.concentrationUnit,
            yAxisLabel = analyte.primaryFeature
        )

        val parameterText = function.requiredParams.joinToString(" · ") { name ->
            "$name=${analyte.fittingParameters[name]?.let(::formatPdfNumber) ?: labels.noValue}"
        }
        canvas.drawText(
            context.getString(R.string.curve_parameters),
            PAGE_MARGIN,
            670f,
            textPaint(12f, Color.rgb(31, 41, 55), true)
        )
        drawWrappedText(
            canvas = canvas,
            text = parameterText,
            left = PAGE_MARGIN,
            top = 690f,
            maxWidth = PAGE_WIDTH - PAGE_MARGIN * 2,
            paint = textPaint(9.5f, Color.rgb(71, 85, 105)),
            lineHeight = 14f
        )
        drawFooter(canvas, pageNumber, totalPages, labels)
        document.finishPage(page)
    }

    /** 非 Compose 报告使用同一组字符串资源，不把 LINEAR、RODBARD 等机器编码写进 PDF。 */
    private fun fittingFunctionName(context: Context, function: FittingFunction): String = context.getString(
        when (function) {
            FittingFunction.LINEAR -> R.string.fitting_function_linear
            FittingFunction.QUADRATIC -> R.string.fitting_function_quadratic
            FittingFunction.CUBIC -> R.string.fitting_function_cubic
            FittingFunction.QUARTIC -> R.string.fitting_function_quartic
            FittingFunction.EXPONENTIAL -> R.string.fitting_function_exponential
            FittingFunction.POWER -> R.string.fitting_function_power
            FittingFunction.LOG -> R.string.fitting_function_log
            FittingFunction.RODBARD -> R.string.fitting_function_rodbard_4pl
            FittingFunction.GAMMA_VARIATE -> R.string.fitting_function_gamma_variate
            FittingFunction.CUSTOM_LOG -> R.string.fitting_function_custom_log
            FittingFunction.RODBARD_NIH -> R.string.fitting_function_rodbard_nih
            FittingFunction.EXPONENTIAL_WITH_OFFSET -> R.string.fitting_function_exponential_offset
            FittingFunction.GAUSSIAN -> R.string.fitting_function_gaussian
            FittingFunction.EXPONENTIAL_RECOVERY -> R.string.fitting_function_exponential_recovery
            FittingFunction.LOGISTIC -> R.string.fitting_function_logistic_5pl
            FittingFunction.GOMPERTZ -> R.string.fitting_function_gompertz
            FittingFunction.HILL -> R.string.fitting_function_hill
            FittingFunction.GENERAL_GOMPERTZ -> R.string.fitting_function_general_gompertz
            FittingFunction.RICHARDS -> R.string.fitting_function_richards
            FittingFunction.INTERPOLATION -> R.string.fitting_function_interpolation
        }
    )

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
                // 标准曲线可能是非线性的，不能像旧验证直线那样只连接首尾两点。
                // 逐点构造 Path 后，回归线、Bland-Altman 水平线和非线性标定曲线仍共用
                // 同一绘图原语，同时不会把 4PL/5PL 错画成直线。
                val path = Path()
                line.points.forEachIndexed { index, point ->
                    val mapped = mapPoint(point)
                    if (index == 0) {
                        path.moveTo(mapped.first, mapped.second)
                    } else {
                        path.lineTo(mapped.first, mapped.second)
                    }
                }
                canvas.drawPath(
                    path,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = line.color
                        strokeWidth = 2f
                        style = Paint.Style.STROKE
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

    /** 旧报告逐孔附录在现代冻结快照中的稳定行模型；不通过反射猜测字段。 */
    private data class PdfWellDetailRow(
        val site: ArrayPhysicalSiteResult,
        val analyte: ArrayAnalyteResult?,
        val measurement: ArraySiteMeasurementResult?,
        val referenceValue: Double?
    )

    private fun buildWellDetailRows(
        snapshot: ArrayResultSnapshot,
        validations: Map<String, ResultValidationSnapshot>
    ): List<PdfWellDetailRow> {
        val analytesById = snapshot.analytes.associateBy(ArrayAnalyteResult::analyteId)
        val analyteOrder = snapshot.analytes.associate { analyte ->
            analyte.analyteId to analyte.displayOrder
        }
        return snapshot.sites.sortedBy(ArrayPhysicalSiteResult::siteIndex).flatMap { site ->
            val measurements = site.measurements.sortedWith(
                compareBy { measurement -> analyteOrder[measurement.analyteId] ?: Int.MAX_VALUE }
            )
            if (measurements.isEmpty()) {
                listOf(
                    PdfWellDetailRow(
                        site = site,
                        analyte = site.analyteId?.let(analytesById::get),
                        measurement = null,
                        referenceValue = null
                    )
                )
            } else {
                measurements.map { measurement ->
                    val analyte = measurement.analyteId?.let(analytesById::get)
                    val reference = measurement.analyteId
                        ?.let(validations::get)
                        ?.points
                        ?.firstOrNull { point -> point.siteIndex == site.siteIndex }
                        ?.referenceValue
                        ?.takeIf(Double::isFinite)
                    PdfWellDetailRow(site, analyte, measurement, reference)
                }
            }
        }
    }

    /**
     * 以微流控引入前的旧版为信息基线，恢复“孔号 + 裁切图 + 对应科学结果”的逐孔附录。
     * 新实现按页即时读取并绘制裁切图，不再像旧代码那样一次性持有全部 Bitmap，也不使用
     * 反射访问角色和图片路径；缺图时明确显示未记录，不重新定位或补造历史证据。
     */
    private fun drawWellDetailPages(
        document: PdfDocument,
        context: Context,
        rows: List<PdfWellDetailRow>,
        labels: ArrayResultPdfLabels,
        circularSites: Boolean,
        wellImageProvider: (Int) -> Bitmap?,
        startPageNumber: Int,
        totalPages: Int
    ) {
        val chunks = rows.chunked(WELL_DETAIL_ROWS_PER_PAGE)
        chunks.forEachIndexed { pageIndex, pageRows ->
            val pageNumber = startPageNumber + pageIndex
            val page = document.startPage(pageInfo(pageNumber))
            val canvas = page.canvas
            canvas.drawText(
                context.getString(
                    if (pageIndex == 0) {
                        R.string.array_pdf_well_details_title
                    } else {
                        R.string.array_pdf_well_details_continued
                    }
                ),
                PAGE_MARGIN,
                54f,
                textPaint(20f, Color.rgb(28, 44, 61), bold = true)
            )
            drawWrappedText(
                canvas = canvas,
                text = context.getString(R.string.array_pdf_well_details_note),
                left = PAGE_MARGIN,
                top = 76f,
                maxWidth = PAGE_WIDTH - PAGE_MARGIN * 2,
                paint = textPaint(8.5f, Color.rgb(71, 85, 105)),
                lineHeight = 12f
            )
            drawWellDetailTable(
                canvas = canvas,
                context = context,
                rows = pageRows,
                labels = labels,
                circularSites = circularSites,
                wellImageProvider = wellImageProvider,
                top = 104f
            )
            drawFooter(canvas, pageNumber, totalPages, labels)
            document.finishPage(page)
        }
    }

    private fun drawWellDetailTable(
        canvas: Canvas,
        context: Context,
        rows: List<PdfWellDetailRow>,
        labels: ArrayResultPdfLabels,
        circularSites: Boolean,
        wellImageProvider: (Int) -> Bitmap?,
        top: Float
    ) {
        val columnWidths = floatArrayOf(38f, 50f, 84f, 75f, 112f, 70f, 86f)
        val headers = listOf(
            context.getString(R.string.pdf_table_header_well),
            context.getString(R.string.array_pdf_table_image),
            context.getString(R.string.array_pdf_table_sample_role),
            context.getString(R.string.analyte),
            context.getString(R.string.array_pdf_table_result_reference),
            context.getString(R.string.array_pdf_table_signal),
            context.getString(R.string.array_pdf_table_status)
        )
        val headerHeight = 28f
        val rowHeight = 60f
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 110, 28)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
        }
        val tableLeft = PAGE_MARGIN
        val tableRight = PAGE_WIDTH - PAGE_MARGIN
        canvas.drawRect(tableLeft, top, tableRight, top + headerHeight, headerPaint)
        var x = tableLeft
        headers.forEachIndexed { index, header ->
            drawTableCellLines(
                canvas = canvas,
                lines = listOf(header),
                bounds = RectF(x, top, x + columnWidths[index], top + headerHeight),
                paint = textPaint(8.2f, Color.WHITE, bold = true),
                maxLines = 2
            )
            x += columnWidths[index]
        }

        rows.forEachIndexed { rowIndex, row ->
            val rowTop = top + headerHeight + rowIndex * rowHeight
            val qualityLevel = row.measurement?.resolveQualityLevel(row.site)
            val backgroundColor = when (qualityLevel) {
                ArrayMeasurementQualityLevel.UNAVAILABLE -> Color.rgb(254, 242, 242)
                ArrayMeasurementQualityLevel.REVIEW -> Color.rgb(255, 251, 235)
                ArrayMeasurementQualityLevel.VALID,
                null -> if (rowIndex % 2 == 0) Color.WHITE else Color.rgb(248, 250, 252)
            }
            canvas.drawRect(
                tableLeft,
                rowTop,
                tableRight,
                rowTop + rowHeight,
                Paint().apply { color = backgroundColor }
            )
            val measurement = row.measurement
            val sampleText = row.site.sampleSlot
                ?: row.site.defaultSampleSlot
                ?: labels.noValue
            val roleText = plateRoleText(context, row.site.roleCode)
            val resultLines = buildWellResultLines(row, labels, context)
            val signalText = measurement?.primaryFeatureValue
                ?.takeIf(Double::isFinite)
                ?.let(::formatPdfNumber)
                ?: labels.noValue
            val statusLines = buildWellStatusLines(row, labels, context)
            val cellLines = listOf(
                listOf(plateWellLabel(row.site.rowIndex, row.site.columnIndex)),
                emptyList(),
                listOf(sampleText, roleText),
                listOf(row.analyte?.name ?: labels.noValue),
                resultLines,
                listOf(signalText),
                statusLines
            )

            x = tableLeft
            cellLines.forEachIndexed { columnIndex, lines ->
                val bounds = RectF(x, rowTop, x + columnWidths[columnIndex], rowTop + rowHeight)
                if (columnIndex == 1) {
                    drawWellImageCell(
                        canvas = canvas,
                        bounds = bounds,
                        bitmap = runCatching { wellImageProvider(row.site.siteIndex) }.getOrNull(),
                        circularSite = circularSites,
                        missing = labels.noValue
                    )
                } else {
                    drawTableCellLines(
                        canvas = canvas,
                        lines = lines,
                        bounds = bounds,
                        paint = textPaint(
                            size = if (columnIndex == 0) 9f else 8.2f,
                            color = Color.rgb(51, 65, 85),
                            bold = columnIndex == 0 || columnIndex == 4
                        ),
                        maxLines = if (columnIndex == 4) 3 else 2
                    )
                }
                canvas.drawRect(bounds, borderPaint)
                x += columnWidths[columnIndex]
            }
        }
    }

    private fun buildWellResultLines(
        row: PdfWellDetailRow,
        labels: ArrayResultPdfLabels,
        context: Context
    ): List<String> {
        val measurement = row.measurement
        val unit = measurement?.concentrationUnit ?: row.analyte?.concentrationUnit.orEmpty()
        val result = when {
            measurement == null -> labels.noValue
            measurement.concentrationValue?.isFinite() == true -> {
                val prefix = if (measurement.quantificationState.equals("ESTIMATED", true)) "≈" else ""
                "$prefix${formatPdfNumber(requireNotNull(measurement.concentrationValue))} $unit".trim()
            }
            measurement.quantificationState.equals("BOUND_ONLY", true) &&
                measurement.censoringDirection.equals("LOWER_BOUND", true) ->
                measurement.concentrationLowerBound?.takeIf(Double::isFinite)?.let { value ->
                    ">${formatPdfNumber(value)} $unit".trim()
                } ?: labels.noValue
            measurement.quantificationState.equals("BOUND_ONLY", true) &&
                measurement.censoringDirection.equals("UPPER_BOUND", true) ->
                measurement.concentrationUpperBound?.takeIf(Double::isFinite)?.let { value ->
                    "<${formatPdfNumber(value)} $unit".trim()
                } ?: labels.noValue
            else -> context.getString(R.string.array_pdf_state_signal_only)
        }
        val reference = row.referenceValue?.let { value ->
            "${context.getString(R.string.pdf_table_header_true_concentration)} " +
                "${formatPdfNumber(value)} $unit".trim()
        }
        return listOfNotNull(result, reference)
    }

    private fun buildWellStatusLines(
        row: PdfWellDetailRow,
        labels: ArrayResultPdfLabels,
        context: Context
    ): List<String> {
        val measurement = row.measurement ?: return listOf(labels.noValue)
        val quality = when (measurement.resolveQualityLevel(row.site)) {
            ArrayMeasurementQualityLevel.VALID -> labels.validMeasurements
            ArrayMeasurementQualityLevel.REVIEW -> labels.reviewMeasurements
            ArrayMeasurementQualityLevel.UNAVAILABLE -> labels.unavailableMeasurements
        }
        val quantification = when {
            measurement.quantificationState.equals("QUANTIFIED", true) ->
                context.getString(R.string.array_heatmap_quantified)
            measurement.quantificationState.equals("ESTIMATED", true) ->
                context.getString(R.string.array_heatmap_estimated)
            measurement.quantificationState.equals("BOUND_ONLY", true) ->
                context.getString(R.string.array_heatmap_retest)
            measurement.concentrationValue == null && measurement.primaryFeatureValue?.isFinite() == true ->
                context.getString(R.string.array_pdf_state_signal_only)
            else -> null
        }
        return listOfNotNull(quality, quantification)
    }

    private fun plateRoleText(context: Context, roleCode: String?): String = context.getString(
        when (roleCode) {
            "SAMPLE" -> R.string.plate96_result_role_sample
            "STANDARD" -> R.string.plate96_result_role_standard
            "BLANK" -> R.string.plate96_result_role_blank
            "NEGATIVE_CONTROL" -> R.string.plate96_result_role_negative
            "POSITIVE_CONTROL" -> R.string.plate96_result_role_positive
            "REFERENCE" -> R.string.plate96_result_role_reference
            else -> R.string.plate96_result_role_unassigned
        }
    )

    private fun drawWellImageCell(
        canvas: Canvas,
        bounds: RectF,
        bitmap: Bitmap?,
        circularSite: Boolean,
        missing: String
    ) {
        val inset = 7f
        val frameSize = (min(bounds.width(), bounds.height()) - inset * 2f).coerceAtLeast(1f)
        val imageFrame = RectF(
            bounds.centerX() - frameSize / 2f,
            bounds.centerY() - frameSize / 2f,
            bounds.centerX() + frameSize / 2f,
            bounds.centerY() + frameSize / 2f
        )
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 245, 249)
        }
        if (circularSite) {
            canvas.drawOval(imageFrame, backgroundPaint)
        } else {
            canvas.drawRoundRect(imageFrame, 6f, 6f, backgroundPaint)
        }
        if (bitmap != null && !bitmap.isRecycled) {
            /*
             * 旧实现把任意 Bitmap 直接拉伸到“列宽减边距 × 行高减边距”的矩形中。逐孔列
             * 比数据行更窄，因此圆孔会稳定变成长椭圆，非方形裁切也会改变真实几何比例。
             * 这里先建立正方形显示框，再按 FIT_CENTER 等比例缩放；圆孔只裁掉显示框外的
             * 透明/背景区域，绝不修改冻结图片本身，也不重新执行定位或裁切算法。
             */
            val bitmapBounds = fitCenterBitmapBounds(bitmap, imageFrame)
            val saveCount = canvas.save()
            if (circularSite) {
                canvas.clipPath(Path().apply { addOval(imageFrame, Path.Direction.CW) })
            } else {
                canvas.clipRect(imageFrame)
            }
            canvas.drawBitmap(
                bitmap,
                null,
                bitmapBounds,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            canvas.restoreToCount(saveCount)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(148, 163, 184)
                style = Paint.Style.STROKE
                strokeWidth = 0.7f
            }
            if (circularSite) {
                canvas.drawOval(imageFrame, borderPaint)
            } else {
                canvas.drawRoundRect(imageFrame, 6f, 6f, borderPaint)
            }
        } else {
            drawTableCellLines(
                canvas,
                listOf(missing),
                imageFrame,
                textPaint(6.8f, Color.rgb(148, 163, 184)),
                maxLines = 2
            )
        }
    }

    /**
     * 将原图完整放入目标框且保持宽高比。返回值只描述 PDF 画布上的显示区域，
     * 不创建新 Bitmap，也不会影响后续模型推理、信号计算或历史冻结证据。
     */
    private fun fitCenterBitmapBounds(bitmap: Bitmap, container: RectF): RectF {
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return RectF(container)

        val scale = min(container.width() / sourceWidth, container.height() / sourceHeight)
        val targetWidth = sourceWidth * scale
        val targetHeight = sourceHeight * scale
        return RectF(
            container.centerX() - targetWidth / 2f,
            container.centerY() - targetHeight / 2f,
            container.centerX() + targetWidth / 2f,
            container.centerY() + targetHeight / 2f
        )
    }

    /** 表格单元格最多绘制固定行数；过长名称只在视觉层省略，不修改导出快照或 CSV。 */
    private fun drawTableCellLines(
        canvas: Canvas,
        lines: List<String>,
        bounds: RectF,
        paint: Paint,
        maxLines: Int
    ) {
        val padding = 5f
        val lineHeight = paint.textSize + 3f
        val visible = lines.filter(String::isNotBlank).take(maxLines)
        val blockHeight = visible.size * lineHeight
        var baseline = bounds.centerY() - blockHeight / 2f - paint.ascent()
        visible.forEach { line ->
            val fitted = fitPdfCellText(line, paint, bounds.width() - padding * 2)
            canvas.drawText(fitted, bounds.left + padding, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun fitPdfCellText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + suffix) > maxWidth) {
            end -= 1
        }
        return text.substring(0, end) + suffix
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
    ): Float {
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
        return top + cellSize * snapshot.rows
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

    private fun ArraySiteMeasurementResult.quantificationMarker(): String? = when {
        quantificationState.equals("ESTIMATED", ignoreCase = true) -> "≈"
        quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            censoringDirection.equals("LOWER_BOUND", ignoreCase = true) -> ">"
        quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
            censoringDirection.equals("UPPER_BOUND", ignoreCase = true) -> "<"
        else -> null
    }

    /** PDF 图例直接遍历同一导出契约，确保颜色、最小值和最大值与独立 PNG 完全一致。 */
    private fun drawHeatmapLegend(
        canvas: Canvas,
        top: Float,
        scale: ArrayResultExportHeatmapScale,
        unit: String
    ) {
        val left = PAGE_MARGIN + 24f
        val right = PAGE_WIDTH - PAGE_MARGIN - 24f
        val height = 10f
        val segments = 120
        val segmentWidth = (right - left) / segments
        repeat(segments) { index ->
            val ratio = if (segments == 1) 0.5 else index.toDouble() / (segments - 1)
            val value = if (scale.maximum > scale.minimum) {
                scale.minimum + (scale.maximum - scale.minimum) * ratio
            } else {
                scale.minimum
            }
            canvas.drawRect(
                left + index * segmentWidth,
                top,
                left + (index + 1) * segmentWidth + 0.5f,
                top + height,
                Paint().apply { color = exportHeatmapColor(value, scale) }
            )
        }
        val labelPaint = textPaint(8f, Color.rgb(71, 85, 105))
        val minimumText = formatPdfNumber(scale.minimum)
        val maximumText = formatPdfNumber(scale.maximum)
        canvas.drawText(minimumText, left, top + 23f, labelPaint)
        canvas.drawText(maximumText, right - labelPaint.measureText(maximumText), top + 23f, labelPaint)
        val unitText = unit.ifBlank { " " }
        canvas.drawText(
            unitText,
            (PAGE_WIDTH - labelPaint.measureText(unitText)) / 2f,
            top + 23f,
            labelPaint
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
        val valuePaint = textPaint(16f, Color.rgb(15, 118, 110), true)
        // 长函数名、长单位和科学计数法不能越出指标卡；优先逐级缩小字号，仍放不下时
        // 才做视觉省略。完整值继续保存在曲线参数区、CSV和冻结快照中。
        while (valuePaint.textSize > 9f && valuePaint.measureText(value) > width - 24f) {
            valuePaint.textSize -= 0.5f
        }
        canvas.drawText(
            fitPdfCellText(value, valuePaint, width - 24f),
            left + 12f,
            top + 22f,
            valuePaint
        )
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
        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val newlineIndex = text.indexOf('\n', paragraphStart)
            val paragraphEnd = if (newlineIndex >= 0) newlineIndex else text.length
            var lineStart = paragraphStart

            if (lineStart == paragraphEnd) {
                // 显式空行也必须占据真实行高，否则报告中的分段语义会被悄悄压平。
                y += lineHeight
            }
            while (lineStart < paragraphEnd) {
                val characterCount = paint.breakText(
                    text,
                    lineStart,
                    paragraphEnd,
                    true,
                    maxWidth,
                    null
                ).coerceAtLeast(1)
                var lineEnd = lineStart + characterCount

                /*
                 * Android Paint.breakText 只按字符宽度截断，旧实现会把 replacement 等英文单词
                 * 从中间拆开。若本行不是段落末尾，优先回退到最后一个空白边界；中文通常没有
                 * 空白，因此仍按字符自然换行。这样同时兼顾中英文且不引入仅适用于某种语言的
                 * 特殊分支。
                 */
                if (lineEnd < paragraphEnd) {
                    val whitespaceIndex = (lineEnd - 1 downTo lineStart + 1)
                        .firstOrNull { index -> text[index].isWhitespace() }
                    if (whitespaceIndex != null) {
                        lineEnd = whitespaceIndex
                    }
                }

                var drawEnd = lineEnd
                while (drawEnd > lineStart && text[drawEnd - 1].isWhitespace()) {
                    drawEnd--
                }
                if (drawEnd > lineStart) {
                    canvas.drawText(text, lineStart, drawEnd, left, y, paint)
                }
                lineStart = lineEnd
                while (lineStart < paragraphEnd && text[lineStart].isWhitespace()) {
                    lineStart++
                }
                y += lineHeight
            }

            if (newlineIndex < 0) break
            paragraphStart = newlineIndex + 1
        }
        return y
    }

    private fun drawFooter(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        labels: ArrayResultPdfLabels
    ) {
        // 恢复旧版报告的品牌色页眉，但压缩为低干扰色带，避免大面积高饱和背景抢夺
        // 热力图和曲线。所有内容页使用同一绿 + 科研蓝层级，封面继续复用既有模板。
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 7f, Paint().apply {
            color = Color.rgb(0, 110, 28)
        })
        canvas.drawRect(0f, 7f, PAGE_WIDTH.toFloat(), 9f, Paint().apply {
            color = Color.rgb(37, 99, 235)
        })
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
