package com.muc.fluocolorquant.domain.spectrum.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import java.util.Locale

/**
 * 光谱导出图表的坐标轴文案。
 *
 * 与 `ArrayResultPdfLabels` 保持同一约定：字符串由 UI 层解析后传入，渲染层不持有
 * `Context`，也就不会因为取不到资源而在导出路径上悄悄退回某一种语言。
 */
data class SpectrumChartLabels(
    val wavelengthAxis: String,
    val intensityAxis: String
)

/**
 * 光谱曲线图渲染。
 *
 * 从 `ExportViewModel` 下沉而来：图表是导出产物的科学内容，不属于界面状态编排。这里只
 * 依赖 `android.graphics`，不依赖 ViewModel、Hilt 或 Context，因此仪器测试可以直接调用
 * 而不必搭出整个依赖图。
 */
object SpectrumChartRenderer {

    /** 单通道曲线图画布尺寸。 */
    private const val SINGLE_WIDTH = 1200
    private const val SINGLE_HEIGHT = 900

    /** 多通道合并图画布尺寸，需要额外横向空间容纳图例。 */
    private const val MERGED_WIDTH = 1400
    private const val MERGED_HEIGHT = 1000

    /**
     * 多通道配色：15 种高区分度颜色循环使用。
     *
     * 顺序固定，保证同一项目多次导出的通道配色一致，便于跨图对照。
     */
    private val CHANNEL_COLORS: List<Int> = listOf(
        Color.rgb(59, 130, 246), Color.rgb(16, 185, 129), Color.rgb(239, 68, 68),
        Color.rgb(245, 158, 11), Color.rgb(168, 85, 247), Color.rgb(236, 72, 153),
        Color.rgb(14, 165, 233), Color.rgb(34, 197, 94), Color.rgb(251, 146, 60),
        Color.rgb(139, 92, 246), Color.rgb(244, 114, 182), Color.rgb(20, 184, 166),
        Color.rgb(251, 191, 36), Color.rgb(248, 113, 113), Color.rgb(129, 140, 248)
    )

    /** 渲染单通道光谱曲线；无曲线数据时返回 null，由调用方决定是否跳过该通道。 */
    fun renderChannelCurve(
        channel: SpectrumChannelExportModel,
        title: String,
        labels: SpectrumChartLabels
    ): Bitmap? {
        val points = channel.chartData.curvePoints
        if (points.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(SINGLE_WIDTH, SINGLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        canvas.drawText(title, SINGLE_WIDTH / 2f, 70f, titlePaint(40f))

        val frame = ChartFrame(left = 150f, top = 120f, right = 1100f, bottom = 720f)
        val range = ChartRange.of(
            xValues = points.map { it.first },
            yValues = points.map { it.second }
        )
        drawAxes(canvas, frame, range, labels, xTickCount = 5, yTickCount = 5, axisLabelOffset = 100f)

        val linePaint = Paint().apply {
            color = Color.BLUE
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawPath(buildCurvePath(points, frame, range), linePaint)

        channel.chartData.scatterPoints?.takeIf { it.isNotEmpty() }?.let { peaks ->
            val scatterPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
            }
            val peakLabelPaint = TextPaint().apply {
                color = Color.RED
                textSize = 20f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            peaks.forEach { peak ->
                val x = frame.mapX(peak.x, range)
                val y = frame.mapY(peak.y, range)
                canvas.drawCircle(x, y, 8f, scatterPaint)
                canvas.drawText(String.format(Locale.US, "%.1f nm", peak.x), x, y - 15f, peakLabelPaint)
            }
        }
        return bitmap
    }

    /** 渲染多通道叠加对比图，附带“通道号 + 分析物”图例。 */
    fun renderMergedCurves(
        channels: List<SpectrumChannelExportModel>,
        title: String,
        labels: SpectrumChartLabels
    ): Bitmap? {
        if (channels.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(MERGED_WIDTH, MERGED_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        canvas.drawText(title, MERGED_WIDTH / 2f, 60f, titlePaint(40f))

        val frame = ChartFrame(left = 160f, top = 120f, right = 1200f, bottom = 820f)
        // 所有通道共用同一坐标范围，叠加对比才有意义。
        val range = ChartRange.of(
            xValues = channels.flatMap { it.chartData.curvePoints.map { point -> point.first } },
            yValues = channels.flatMap { it.chartData.curvePoints.map { point -> point.second } }
        )
        drawAxes(canvas, frame, range, labels, xTickCount = 6, yTickCount = 5, axisLabelOffset = 110f)

        channels.forEachIndexed { index, channel ->
            val points = channel.chartData.curvePoints
            if (points.size <= 1) return@forEachIndexed
            val channelColor = CHANNEL_COLORS[index % CHANNEL_COLORS.size]
            val linePaint = Paint().apply {
                color = channelColor
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawPath(buildCurvePath(points, frame, range), linePaint)

            channel.chartData.scatterPoints?.takeIf { it.isNotEmpty() }?.let { peaks ->
                val peakPaint = Paint().apply {
                    color = channelColor
                    style = Paint.Style.FILL
                }
                peaks.forEach { peak ->
                    canvas.drawCircle(frame.mapX(peak.x, range), frame.mapY(peak.y, range), 8f, peakPaint)
                }
            }
        }

        val legendX = frame.right - 150f
        var legendY = frame.top + 20f
        val legendPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 18f
            isAntiAlias = true
        }
        channels.forEachIndexed { index, channel ->
            val swatch = Paint().apply { color = CHANNEL_COLORS[index % CHANNEL_COLORS.size] }
            canvas.drawRect(legendX - 30f, legendY - 10f, legendX - 10f, legendY + 10f, swatch)
            canvas.drawText("Ch${channel.channelIndex}: ${channel.analyteName}", legendX, legendY + 5f, legendPaint)
            legendY += 30f
        }
        return bitmap
    }

    private fun titlePaint(size: Float) = TextPaint().apply {
        color = Color.BLACK
        textSize = size
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawAxes(
        canvas: Canvas,
        frame: ChartFrame,
        range: ChartRange,
        labels: SpectrumChartLabels,
        xTickCount: Int,
        yTickCount: Int,
        axisLabelOffset: Float
    ) {
        val axisPaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawLine(frame.left, frame.bottom, frame.right, frame.bottom, axisPaint)
        canvas.drawLine(frame.left, frame.top, frame.left, frame.bottom, axisPaint)

        val labelPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            labels.wavelengthAxis,
            frame.left + frame.width / 2f,
            frame.bottom + 80f,
            labelPaint
        )
        canvas.save()
        canvas.rotate(-90f)
        canvas.drawText(
            labels.intensityAxis,
            -(frame.top + frame.height / 2f),
            frame.left - axisLabelOffset,
            labelPaint
        )
        canvas.restore()

        val tickPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (xTickCount > 5) 18f else 20f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        for (index in 0..xTickCount) {
            val value = range.xMin + (range.xMax - range.xMin) * index / xTickCount
            val position = frame.mapX(value, range)
            canvas.drawLine(position, frame.bottom, position, frame.bottom + 10f, axisPaint)
            canvas.drawText(String.format(Locale.US, "%.0f", value), position, frame.bottom + 35f, tickPaint)
        }
        tickPaint.textAlign = Paint.Align.RIGHT
        for (index in 0..yTickCount) {
            val value = range.yMin + (range.yMax - range.yMin) * index / yTickCount
            val position = frame.mapY(value, range)
            canvas.drawLine(frame.left - 10f, position, frame.left, position, axisPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", value), frame.left - 15f, position + 6f, tickPaint)
        }
    }

    private fun buildCurvePath(
        points: List<Pair<Double, Double>>,
        frame: ChartFrame,
        range: ChartRange
    ): Path {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = frame.mapX(point.first, range)
            val y = frame.mapY(point.second, range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    private data class ChartFrame(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun mapX(value: Double, range: ChartRange): Float =
            left + (width * range.normalizeX(value)).toFloat()

        fun mapY(value: Double, range: ChartRange): Float =
            top + height - (height * range.normalizeY(value)).toFloat()
    }

    /**
     * 坐标范围。
     *
     * [normalizeX] / [normalizeY] 在跨度为 0 时返回 0.5 而不是做除法：平坦光谱、单点通道
     * 或全零强度都会让 `max == min`，原实现直接除以零得到 NaN 坐标，画出来是空白或异常
     * 图形，且不会报错——属于会静默污染导出图的缺陷。跨度为 0 时把数据画在轴中线上，
     * 既不崩也不伪造分布。
     */
    private data class ChartRange(
        val xMin: Double,
        val xMax: Double,
        val yMin: Double,
        val yMax: Double
    ) {
        fun normalizeX(value: Double): Double = normalize(value, xMin, xMax)

        fun normalizeY(value: Double): Double = normalize(value, yMin, yMax)

        private fun normalize(value: Double, min: Double, max: Double): Double {
            val span = max - min
            if (span <= 0.0 || !span.isFinite()) return 0.5
            val ratio = (value - min) / span
            // 刻意**不**把 ratio 夹到 [0,1]：超出坐标范围的点会被画到绘图区之外，那是一个
            // 看得见的异常信号；夹到边界则等于把数据悄悄搬到轴上，读图的人无从察觉。
            // 只有非有限值才回落到中线，因为那种点根本无法定位。
            return if (ratio.isFinite()) ratio else 0.5
        }

        companion object {
            fun of(xValues: List<Double>, yValues: List<Double>): ChartRange {
                val finiteX = xValues.filter(Double::isFinite)
                val finiteY = yValues.filter(Double::isFinite)
                return ChartRange(
                    xMin = finiteX.minOrNull() ?: 0.0,
                    xMax = finiteX.maxOrNull() ?: 1.0,
                    yMin = finiteY.minOrNull() ?: 0.0,
                    yMax = finiteY.maxOrNull() ?: 1.0
                )
            }
        }
    }
}
