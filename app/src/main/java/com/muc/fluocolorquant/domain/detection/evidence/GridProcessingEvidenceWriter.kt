package com.muc.fluocolorquant.domain.detection.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

const val GRID_PROCESSING_EVIDENCE_SCHEMA: String = "pg-processing-evidence-v1"

/** 一张已经写入应用私有目录的处理证据及其稳定机器元数据。 */
data class GridProcessingEvidenceRecord(
    val role: CaptureRole,
    val path: String,
    val checksumSha256: String,
    val metadataJson: String
)

/** 检测协调器依赖的处理证据写入边界；测试可使用 [NoOpGridProcessingEvidenceWriter]。 */
interface GridProcessingEvidenceWriter {
    fun write(
        runId: String,
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult?
    ): List<GridProcessingEvidenceRecord>
}

/** JVM/轻量协调器测试不访问 Android 文件系统，但保留相同调用契约。 */
object NoOpGridProcessingEvidenceWriter : GridProcessingEvidenceWriter {
    override fun write(
        runId: String,
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult?
    ): List<GridProcessingEvidenceRecord> = emptyList()
}

/**
 * 把 PG-Grid/PG-Quant 的真实中间结果渲染为只读 PNG 证据。
 *
 * 所有图像均从冻结原图、冻结单应矩阵、最终位点和实际光度结果重建；不会为了演示生成
 * 与算法无关的“示意动画”。派生图只用于解释和导出，定量仍始终读取原始 Bitmap。
 */
@Singleton
class AndroidGridProcessingEvidenceWriter @Inject constructor(
    @ApplicationContext private val context: Context
) : GridProcessingEvidenceWriter {

    private val gson = Gson()

    override fun write(
        runId: String,
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult?
    ): List<GridProcessingEvidenceRecord> {
        grid.requireValid()
        quant?.requireValid()
        require(runId.isNotBlank()) { "处理证据 runId 不能为空" }

        val outputDirectory = File(context.filesDir, "processing_evidence/$runId")
        check(outputDirectory.exists() || outputDirectory.mkdirs()) {
            "无法创建处理证据目录：${outputDirectory.absolutePath}"
        }
        val records = mutableListOf<GridProcessingEvidenceRecord>()
        val rectified = rectify(sourceBitmap, grid)
        try {
            records += writeEvidence(
                outputDirectory,
                CaptureRole.PROCESS_ORIGINAL_GEOMETRY,
                "01_original_geometry.png",
                drawOriginalGeometry(sourceBitmap, grid),
                stage = "original_geometry",
                order = 1
            )
            records += writeEvidence(
                outputDirectory,
                CaptureRole.PROCESS_CANDIDATE_RESPONSE,
                "02_candidate_response.png",
                renderCandidateResponse(rectified, grid.targetPolarity),
                stage = "candidate_response",
                order = 2
            )
            records += writeEvidence(
                outputDirectory,
                CaptureRole.PROCESS_RECTIFIED,
                "03_rectified_chip.png",
                rectified.copy(Bitmap.Config.ARGB_8888, false),
                stage = "perspective_rectification",
                order = 3
            )
            records += writeEvidence(
                outputDirectory,
                CaptureRole.PROCESS_GRID_OVERLAY,
                "04_final_grid_overlay.png",
                drawRectifiedGrid(rectified, grid),
                stage = "final_grid",
                order = 4
            )

            if (quant != null) {
                records += writeEvidence(
                    outputDirectory,
                    CaptureRole.PROCESS_ROI_BACKGROUND,
                    "05_roi_background.png",
                    drawRoiAndBackgroundRings(rectified, grid, quant),
                    stage = "roi_background_sampling",
                    order = 5,
                    extra = mapOf(
                        "roiRadiusPx" to quant.roiRadiusPx,
                        "annulusInnerPx" to quant.annulusInnerPx,
                        "annulusOuterPx" to quant.annulusOuterPx
                    )
                )
                records += writeEvidence(
                    outputDirectory,
                    CaptureRole.PROCESS_BACKGROUND_FIELD,
                    "06_background_field.png",
                    renderScalarGrid(
                        rows = quant.rows,
                        columns = quant.columns,
                        values = quant.sites.map { it.backgroundMedianGray },
                        logarithmic = false
                    ),
                    stage = "background_field",
                    order = 6,
                    extra = mapOf(
                        "illuminationModel" to quant.illuminationModel,
                        "illuminationUniformity" to quant.illuminationUniformity
                    )
                )
                records += writeEvidence(
                    outputDirectory,
                    CaptureRole.PROCESS_SIGNAL_HEATMAP,
                    "07_corrected_signal_heatmap.png",
                    renderScalarGrid(
                        rows = quant.rows,
                        columns = quant.columns,
                        values = quant.sites.map { it.correctedSignalGray },
                        logarithmic = false
                    ),
                    stage = "corrected_signal",
                    order = 7
                )
                records += writeEvidence(
                    outputDirectory,
                    CaptureRole.PROCESS_SNR_HEATMAP,
                    "08_snr_heatmap.png",
                    renderScalarGrid(
                        rows = quant.rows,
                        columns = quant.columns,
                        values = quant.sites.map { it.signalToNoiseRatio },
                        logarithmic = true
                    ),
                    stage = "signal_to_noise",
                    order = 8
                )
                records += writeEvidence(
                    outputDirectory,
                    CaptureRole.PROCESS_CORRECTED_COLOR,
                    "09_corrected_color_map.png",
                    renderCorrectedColorGrid(quant),
                    stage = "corrected_color",
                    order = 9
                )
            }
            return records
        } finally {
            rectified.recycle()
        }
    }

    /** 使用冻结正向单应矩阵生成真实矫正图，不重新运行芯片区域检测。 */
    private fun rectify(source: Bitmap, grid: PgGridResult): Bitmap {
        val input = Mat()
        val output = Mat()
        val matrix = Mat(3, 3, CvType.CV_64F)
        return try {
            Utils.bitmapToMat(source, input)
            matrix.put(0, 0, *grid.homography.forward.toDoubleArray())
            Imgproc.warpPerspective(
                input,
                output,
                matrix,
                Size(grid.rectifiedWidth.toDouble(), grid.rectifiedHeight.toDouble()),
                Imgproc.INTER_CUBIC
            )
            Bitmap.createBitmap(
                grid.rectifiedWidth,
                grid.rectifiedHeight,
                Bitmap.Config.ARGB_8888
            ).also { Utils.matToBitmap(output, it) }
        } finally {
            matrix.release()
            output.release()
            input.release()
        }
    }

    /** 原图上冻结芯片四角与最终位点，用户可确认算法实际处理了哪一块区域。 */
    private fun drawOriginalGeometry(source: Bitmap, grid: PgGridResult): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val scale = min(bitmap.width, bitmap.height) / 720f
        val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 188, 212)
            style = Paint.Style.STROKE
            strokeWidth = max(3f, 4f * scale)
        }
        val polygon = Path().apply {
            val first = grid.chipCorners.first()
            moveTo(first.x.toFloat(), first.y.toFloat())
            grid.chipCorners.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            close()
        }
        canvas.drawPath(polygon, cornerPaint)
        val radius = max(3f, min(bitmap.width, bitmap.height) / 260f)
        grid.sites.forEach { site ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = sourceColor(site.source)
                style = Paint.Style.STROKE
                strokeWidth = max(2f, radius * 0.45f)
            }
            canvas.drawCircle(site.original.x.toFloat(), site.original.y.toFloat(), radius, paint)
        }
        return bitmap
    }

    /** 复现定位器的 black-hat/top-hat 响应，让用户看到候选证据而非只看最终点。 */
    private fun renderCandidateResponse(
        rectified: Bitmap,
        polarity: GridTargetPolarity
    ): Bitmap {
        val rgba = Mat()
        val gray = Mat()
        val response = Mat()
        val normalized = Mat()
        return try {
            Utils.bitmapToMat(rectified, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val side = min(gray.cols(), gray.rows())
            val rawKernel = when (polarity) {
                GridTargetPolarity.DARK -> max(31, (side * 0.065).toInt())
                GridTargetPolarity.BRIGHT -> max(25, (side * 0.050).toInt())
            }
            val kernelSize = if (rawKernel % 2 == 0) rawKernel + 1 else rawKernel
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(
                    gray,
                    response,
                    if (polarity == GridTargetPolarity.DARK) {
                        Imgproc.MORPH_BLACKHAT
                    } else {
                        Imgproc.MORPH_TOPHAT
                    },
                    kernel
                )
            } finally {
                kernel.release()
            }
            Core.normalize(response, normalized, 0.0, 255.0, Core.NORM_MINMAX)
            Bitmap.createBitmap(
                normalized.cols(),
                normalized.rows(),
                Bitmap.Config.ARGB_8888
            ).also { Utils.matToBitmap(normalized, it) }
        } finally {
            normalized.release()
            response.release()
            gray.release()
            rgba.release()
        }
    }

    /** 在矫正图上绘制最终规则网格及候选精修/模型补位来源。 */
    private fun drawRectifiedGrid(rectified: Bitmap, grid: PgGridResult): Bitmap {
        val bitmap = rectified.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 188, 212)
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, min(bitmap.width, bitmap.height) / 700f)
        }
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns - 1) {
                val first = grid.sites[row * grid.columns + column].rectified
                val second = grid.sites[row * grid.columns + column + 1].rectified
                canvas.drawLine(first.x.toFloat(), first.y.toFloat(), second.x.toFloat(), second.y.toFloat(), linePaint)
            }
        }
        for (column in 0 until grid.columns) {
            for (row in 0 until grid.rows - 1) {
                val first = grid.sites[row * grid.columns + column].rectified
                val second = grid.sites[(row + 1) * grid.columns + column].rectified
                canvas.drawLine(first.x.toFloat(), first.y.toFloat(), second.x.toFloat(), second.y.toFloat(), linePaint)
            }
        }
        val radius = max(3f, min(bitmap.width, bitmap.height) / 220f)
        grid.sites.forEach { site ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = sourceColor(site.source)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(site.rectified.x.toFloat(), site.rectified.y.toFloat(), radius, paint)
        }
        return bitmap
    }

    /** 冻结每个位点的圆形 ROI 与背景环边界，半径直接来自本次 PG-Quant 结果。 */
    private fun drawRoiAndBackgroundRings(
        rectified: Bitmap,
        grid: PgGridResult,
        quant: PgQuantResult
    ): Bitmap {
        val bitmap = rectified.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 0, 200, 120)
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, min(bitmap.width, bitmap.height) / 700f)
        }
        val annulusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 171, 0)
            style = Paint.Style.STROKE
            strokeWidth = roiPaint.strokeWidth
        }
        grid.sites.forEach { site ->
            val x = site.rectified.x.toFloat()
            val y = site.rectified.y.toFloat()
            canvas.drawCircle(x, y, quant.roiRadiusPx.toFloat(), roiPaint)
            canvas.drawCircle(x, y, quant.annulusInnerPx.toFloat(), annulusPaint)
            canvas.drawCircle(x, y, quant.annulusOuterPx.toFloat(), annulusPaint)
        }
        return bitmap
    }

    /** 绘制背景、信号或 SNR 的规则阵列热图；色阶由本次观测的稳健 5%~95% 范围决定。 */
    private fun renderScalarGrid(
        rows: Int,
        columns: Int,
        values: List<Double>,
        logarithmic: Boolean
    ): Bitmap {
        require(values.size == rows * columns) { "热图数值数量与阵列规格不一致" }
        val transformed = values.map { value ->
            if (logarithmic) ln1p(max(0.0, value)) else value
        }
        val sorted = transformed.sorted()
        val lower = percentile(sorted, 0.05)
        val upper = percentile(sorted, 0.95)
        val span = max(upper - lower, 1e-9)
        val (width, height) = evidenceCanvasSize(rows, columns)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 250, 250))
        val padding = 36f
        val cellWidth = (width - padding * 2) / columns
        val cellHeight = (height - padding * 2) / rows
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 26, 55, 61)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        transformed.forEachIndexed { index, value ->
            val row = index / columns
            val column = index % columns
            val fraction = ((value - lower) / span).coerceIn(0.0, 1.0)
            val left = padding + column * cellWidth
            val top = padding + row * cellHeight
            val right = left + cellWidth
            val bottom = top + cellHeight
            canvas.drawRect(left, top, right, bottom, Paint().apply { color = heatColor(fraction) })
            canvas.drawRect(left, top, right, bottom, border)
        }
        return bitmap
    }

    /** 校正颜色图直接使用平场校正后的 RGB 中位数，不套用热力伪彩。 */
    private fun renderCorrectedColorGrid(quant: PgQuantResult): Bitmap {
        val (width, height) = evidenceCanvasSize(quant.rows, quant.columns)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 250, 250))
        val padding = 36f
        val cellWidth = (width - padding * 2) / quant.columns
        val cellHeight = (height - padding * 2) / quant.rows
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 26, 55, 61)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        quant.sites.forEach { site ->
            val left = padding + site.columnIndex * cellWidth
            val top = padding + site.rowIndex * cellHeight
            val right = left + cellWidth
            val bottom = top + cellHeight
            val rgb = site.correctedMedianRgb
            val color = Color.rgb(
                rgb.red.roundToInt().coerceIn(0, 255),
                rgb.green.roundToInt().coerceIn(0, 255),
                rgb.blue.roundToInt().coerceIn(0, 255)
            )
            canvas.drawRect(left, top, right, bottom, Paint().apply { this.color = color })
            canvas.drawRect(left, top, right, bottom, border)
        }
        return bitmap
    }

    /** 保存 PNG 后立即回收派生 Bitmap，避免九张大图同时占用 Java 堆。 */
    private fun writeEvidence(
        directory: File,
        role: CaptureRole,
        fileName: String,
        bitmap: Bitmap,
        stage: String,
        order: Int,
        extra: Map<String, Any?> = emptyMap()
    ): GridProcessingEvidenceRecord {
        val file = File(directory, fileName)
        try {
            FileOutputStream(file, false).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码处理证据：${file.absolutePath}"
                }
            }
        } finally {
            bitmap.recycle()
        }
        val metadata = linkedMapOf<String, Any?>(
            "schemaVersion" to GRID_PROCESSING_EVIDENCE_SCHEMA,
            "stage" to stage,
            "order" to order,
            "captureRole" to role.code,
            "scientificUse" to "diagnostic_only"
        ).apply { putAll(extra) }
        return GridProcessingEvidenceRecord(
            role = role,
            path = file.absolutePath,
            checksumSha256 = sha256(file),
            metadataJson = gson.toJson(metadata)
        )
    }

    private fun evidenceCanvasSize(rows: Int, columns: Int): Pair<Int, Int> {
        val longest = 900
        val shortest = 520
        return if (columns >= rows) {
            longest to max(shortest, (longest * rows.toDouble() / columns).roundToInt())
        } else {
            max(shortest, (longest * columns.toDouble() / rows).roundToInt()) to longest
        }
    }

    private fun sourceColor(source: GridPointSource): Int = when (source) {
        GridPointSource.CANDIDATE_REFINED -> Color.rgb(0, 174, 112)
        GridPointSource.MODEL_IMPUTED -> Color.rgb(255, 171, 0)
        GridPointSource.UNADJUSTED -> Color.rgb(84, 110, 122)
    }

    /** 蓝绿黄红连续色阶，避免常见紫色 AI 风格，并与科研热图语义保持一致。 */
    private fun heatColor(fraction: Double): Int {
        val stops = arrayOf(
            Triple(0.0, Color.rgb(25, 74, 120), "deep_blue"),
            Triple(0.35, Color.rgb(0, 150, 136), "teal"),
            Triple(0.68, Color.rgb(255, 193, 7), "amber"),
            Triple(1.0, Color.rgb(198, 40, 40), "red")
        )
        val upperIndex = stops.indexOfFirst { fraction <= it.first }.takeIf { it >= 0 }
            ?: stops.lastIndex
        if (upperIndex == 0) return stops.first().second
        val lower = stops[upperIndex - 1]
        val upper = stops[upperIndex]
        val local = ((fraction - lower.first) / max(upper.first - lower.first, 1e-9)).coerceIn(0.0, 1.0)
        return interpolateColor(lower.second, upper.second, local)
    }

    private fun interpolateColor(first: Int, second: Int, fraction: Double): Int {
        fun channel(start: Int, end: Int): Int =
            (start + (end - start) * fraction).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(first), Color.red(second)),
            channel(Color.green(first), Color.green(second)),
            channel(Color.blue(first), Color.blue(second))
        )
    }

    private fun percentile(sorted: List<Double>, ratio: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = ((sorted.size - 1) * ratio).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
