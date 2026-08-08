package com.muc.fluocolorquant.domain.result.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * 从冻结结果离屏绘制固定分辨率PNG。
 *
 * 绘制器不截取手机页面，因此导出图片不会包含导航栏、滚动位置或设备分辨率差异。圆孔板
 * 使用圆形位点，微流控继续使用圆角方形位点，两者共享相同色带和数值来源。
 */
object ArrayResultPngExporter {
    private const val WIDTH = 1600
    private const val HEIGHT = 1100

    fun createHeatmapPng(
        snapshot: ArrayResultSnapshot,
        analyteId: String?,
        labels: ArrayResultPngLabels
    ): ByteArray {
        val analyte = snapshot.analytes.firstOrNull { candidate -> candidate.analyteId == analyteId }
            ?: snapshot.analytes.minByOrNull(ArrayAnalyteResult::displayOrder)
            ?: error("PNG_EXPORT_ANALYTE_UNAVAILABLE")
        val concentrationMode = snapshot.sites.any { site ->
            val measurement = site.measurementFor(analyte.analyteId)
            measurement?.concentrationValue?.isFinite() == true ||
                measurement?.quantificationState.equals("BOUND_ONLY", ignoreCase = true)
        }
        val values = snapshot.sites.mapNotNull { site ->
            val measurement = site.measurementFor(analyte.analyteId) ?: return@mapNotNull null
            if (concentrationMode) {
                measurement.concentrationHeatmapValue()
            } else {
                measurement.primaryFeatureValue
            }
        }.filter(Double::isFinite)
        val dataMinimum = values.minOrNull() ?: 0.0
        val dataMaximum = values.maxOrNull() ?: 1.0
        val scaleMinimum = if (concentrationMode) {
            analyte.projectRangeMin?.takeIf(Double::isFinite) ?: dataMinimum
        } else {
            dataMinimum
        }
        val scaleMaximum = if (concentrationMode) {
            analyte.projectRangeMax?.takeIf(Double::isFinite) ?: dataMaximum
        } else {
            dataMaximum
        }.let { maximum -> if (maximum > scaleMinimum) maximum else scaleMinimum + 1.0 }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 38, 59)
            textSize = 48f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 101, 120)
            textSize = 28f
        }
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 80, 98)
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val title = String.format(
            if (concentrationMode) labels.concentrationTitleFormat else labels.signalTitleFormat,
            analyte.name
        )
        canvas.drawText(title, 90f, 85f, titlePaint)
        canvas.drawText(
            if (concentrationMode) labels.concentration else labels.signal,
            90f,
            130f,
            subtitlePaint
        )

        val gridTop = 210f
        val gridBottom = 910f
        val gridLeft = 150f
        val gridRight = 1510f
        val cellSize = min(
            (gridRight - gridLeft) / snapshot.columns.coerceAtLeast(1),
            (gridBottom - gridTop) / snapshot.rows.coerceAtLeast(1)
        )
        val actualWidth = cellSize * snapshot.columns
        val actualHeight = cellSize * snapshot.rows
        val startX = gridLeft + (gridRight - gridLeft - actualWidth) / 2f
        val startY = gridTop + (gridBottom - gridTop - actualHeight) / 2f

        repeat(snapshot.columns) { column ->
            canvas.drawText(
                (column + 1).toString(),
                startX + (column + 0.5f) * cellSize,
                startY - 18f,
                axisPaint
            )
        }
        repeat(snapshot.rows) { row ->
            canvas.drawText(
                platePngRowLabel(row),
                startX - 38f,
                startY + (row + 0.5f) * cellSize + axisPaint.textSize * 0.35f,
                axisPaint
            )
        }

        val siteByIndex = snapshot.sites.associateBy(ArrayPhysicalSiteResult::siteIndex)
        val siteShape = SiteShape.fromCode(snapshot.carrier.siteShape)
        repeat(snapshot.rows) { row ->
            repeat(snapshot.columns) { column ->
                val siteIndex = row * snapshot.columns + column
                val site = siteByIndex[siteIndex]
                val measurement = site?.measurementFor(analyte.analyteId)
                val value = if (concentrationMode) {
                    measurement?.concentrationHeatmapValue()
                } else {
                    measurement?.primaryFeatureValue
                }?.takeIf(Double::isFinite)
                val color = value?.let { finite ->
                    HeatmapColorUtil.getColor(finite, scaleMinimum, scaleMaximum).toArgb()
                } ?: Color.rgb(229, 232, 238)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                val inset = cellSize * 0.11f
                val left = startX + column * cellSize + inset
                val top = startY + row * cellSize + inset
                val right = startX + (column + 1) * cellSize - inset
                val bottom = startY + (row + 1) * cellSize - inset
                if (siteShape == SiteShape.CIRCLE) {
                    canvas.drawCircle(
                        (left + right) / 2f,
                        (top + bottom) / 2f,
                        min(right - left, bottom - top) / 2f,
                        paint
                    )
                } else {
                    canvas.drawRoundRect(RectF(left, top, right, bottom), inset, inset, paint)
                }
                if (concentrationMode && measurement != null) {
                    drawQuantificationMarker(
                        canvas = canvas,
                        measurement = measurement,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        cellSize = cellSize
                    )
                }
            }
        }

        drawLegend(canvas, scaleMinimum, scaleMaximum, startX, actualWidth, subtitlePaint)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "PNG_EXPORT_ENCODING_FAILED"
            }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun drawLegend(
        canvas: Canvas,
        minimum: Double,
        maximum: Double,
        startX: Float,
        width: Float,
        textPaint: Paint
    ) {
        val legendTop = 980f
        val colors = HeatmapColorUtil.getLegendColors(120)
        val segmentWidth = width / colors.size
        colors.forEachIndexed { index, color ->
            val paint = Paint().apply { this.color = color.toArgb() }
            canvas.drawRect(
                startX + index * segmentWidth,
                legendTop,
                startX + (index + 1) * segmentWidth + 1f,
                legendTop + 22f,
                paint
            )
        }
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(formatPngValue(minimum), startX, legendTop + 62f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatPngValue(maximum), startX + width, legendTop + 62f, textPaint)
    }

    private fun ArrayPhysicalSiteResult.measurementFor(analyteId: String) =
        measurements.firstOrNull { measurement -> measurement.analyteId == analyteId }

    /**
     * 单侧界限没有点浓度，但仍可用冻结的浓度界限决定端点颜色；这不是伪造精确值，
     * 因为导出图会同时叠加明确的 “>” 或 “<” 标记。
     */
    private fun ArraySiteMeasurementResult.concentrationHeatmapValue(): Double? =
        concentrationValue?.takeIf(Double::isFinite)
            ?: when {
                quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                    censoringDirection.equals("LOWER_BOUND", ignoreCase = true) ->
                    concentrationLowerBound?.takeIf(Double::isFinite)
                quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                    censoringDirection.equals("UPPER_BOUND", ignoreCase = true) ->
                    concentrationUpperBound?.takeIf(Double::isFinite)
                else -> null
            }

    /** 离屏图用最小符号复现页面语义：估计值为≈，浓度下界为>，上界为<。 */
    private fun drawQuantificationMarker(
        canvas: Canvas,
        measurement: ArraySiteMeasurementResult,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cellSize: Float
    ) {
        val marker = when {
            measurement.quantificationState.equals("ESTIMATED", ignoreCase = true) -> "≈"
            measurement.quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                measurement.censoringDirection.equals("LOWER_BOUND", ignoreCase = true) -> ">"
            measurement.quantificationState.equals("BOUND_ONLY", ignoreCase = true) &&
                measurement.censoringDirection.equals("UPPER_BOUND", ignoreCase = true) -> "<"
            else -> null
        } ?: return
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = min(28f, cellSize * 0.34f)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val centerX = if (marker == "≈") right - (right - left) * 0.18f else (left + right) / 2f
        val centerY = if (marker == "≈") {
            top + markerPaint.textSize
        } else {
            (top + bottom) / 2f - (markerPaint.ascent() + markerPaint.descent()) / 2f
        }
        canvas.drawText(marker, centerX, centerY, markerPaint)
    }

    private fun platePngRowLabel(rowIndex: Int): String {
        var value = rowIndex + 1
        val label = StringBuilder()
        while (value > 0) {
            value -= 1
            label.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return label.reverse().toString()
    }

    private fun formatPngValue(value: Double): String = when {
        kotlin.math.abs(value) >= 10_000.0 -> "%.3e".format(value)
        else -> "%.4f".format(value).trimEnd('0').trimEnd('.')
    }
}
