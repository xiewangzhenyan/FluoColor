package com.muc.fluocolorquant.domain.detection.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 96孔板处理证据的稳定结构版本。 */
const val PLATE96_PROCESSING_EVIDENCE_SCHEMA: String = "plate96-processing-evidence-v1"

/** 96孔板处理证据写入边界；JVM测试可以使用无文件系统实现。 */
interface Plate96ProcessingEvidenceWriter {
    fun write(
        runId: String,
        sourceBitmap: Bitmap,
        normalizedBitmap: Bitmap,
        session: Plate96Locator.Session,
        quant: PgQuantResult
    ): List<GridProcessingEvidenceRecord>
}

object NoOpPlate96ProcessingEvidenceWriter : Plate96ProcessingEvidenceWriter {
    override fun write(
        runId: String,
        sourceBitmap: Bitmap,
        normalizedBitmap: Bitmap,
        session: Plate96Locator.Session,
        quant: PgQuantResult
    ): List<GridProcessingEvidenceRecord> = emptyList()
}

/**
 * 从真实定位会话生成96孔板过程图。
 *
 * 所有派生图仅用于结果页解释和科研归档；浓度计算始终读取原始Bitmap与冻结圆孔掩膜，
 * 不会把带标注图片或接触表再次送入定量算法。
 */
@Singleton
class AndroidPlate96ProcessingEvidenceWriter @Inject constructor(
    @ApplicationContext private val context: Context
) : Plate96ProcessingEvidenceWriter {
    private val gson = Gson()

    override fun write(
        runId: String,
        sourceBitmap: Bitmap,
        normalizedBitmap: Bitmap,
        session: Plate96Locator.Session,
        quant: PgQuantResult
    ): List<GridProcessingEvidenceRecord> {
        require(runId.isNotBlank()) { "96孔板处理证据runId不能为空" }
        session.result.requireValid()
        quant.requireValid()
        val directory = File(context.filesDir, "processing_evidence/$runId")
        check(directory.exists() || directory.mkdirs()) { "无法创建96孔板处理证据目录" }

        return listOf(
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_ORIENTATION_NORMALIZED,
                fileName = "01_orientation_normalized.png",
                bitmap = normalizedBitmap.copy(Bitmap.Config.ARGB_8888, false),
                stage = "orientation_normalized",
                order = 1
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_YOLO_OVERLAY,
                fileName = "02_yolo_overlay.png",
                bitmap = drawObjectCandidates(sourceBitmap, session),
                stage = "object_detection",
                order = 2,
                extra = mapOf("candidateCount" to session.circles.size)
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_HOUGH_CIRCLE_OVERLAY,
                fileName = "03_circle_overlay.png",
                bitmap = drawCircleCandidates(sourceBitmap, session),
                stage = "circle_refinement",
                order = 3,
                extra = mapOf(
                    "houghCount" to session.circles.count { it.source == Plate96CircleSource.HOUGH },
                    "contourCount" to session.circles.count { it.source == Plate96CircleSource.CONTOUR },
                    "fallbackCount" to session.circles.count { it.source == Plate96CircleSource.BOX_FALLBACK }
                )
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_GRID_OVERLAY,
                fileName = "04_standard_grid_overlay.png",
                bitmap = drawFinalGrid(normalizedBitmap, session, useSourceCoordinates = false),
                stage = "standard_grid",
                order = 4
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_ORIGINAL_PROJECTION_OVERLAY,
                fileName = "05_original_projection.png",
                bitmap = drawFinalGrid(sourceBitmap, session, useSourceCoordinates = true),
                stage = "original_projection",
                order = 5
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_CROP_CONTACT_SHEET,
                fileName = "06_crop_contact_sheet.png",
                bitmap = drawCropContactSheet(normalizedBitmap, session),
                stage = "circular_crops",
                order = 6
            ),
            writeEvidence(
                directory = directory,
                role = CaptureRole.PROCESS_SIGNAL_HEATMAP,
                fileName = "07_signal_heatmap.png",
                bitmap = drawSignalHeatmap(quant),
                stage = "signal_extraction",
                order = 7
            )
        )
    }

    /** YOLO候选使用矩形边界，避免把后续霍夫圆结果伪装成目标检测输出。 */
    private fun drawObjectCandidates(source: Bitmap, session: Plate96Locator.Session): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 0, 137, 123)
            style = Paint.Style.STROKE
            strokeWidth = scaledStroke(bitmap)
        }
        session.circles.forEach { circle ->
            val bounds = circle.objectBounds
            canvas.drawRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                paint
            )
        }
        return bitmap
    }

    /** 圆候选按来源着色，真实霍夫/轮廓与矩形兜底能够一眼区分。 */
    private fun drawCircleCandidates(source: Bitmap, session: Plate96Locator.Session): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        session.circles.forEach { circle ->
            val color = when (circle.source) {
                Plate96CircleSource.HOUGH -> Color.rgb(0, 160, 112)
                Plate96CircleSource.CONTOUR -> Color.rgb(30, 120, 210)
                Plate96CircleSource.BOX_FALLBACK -> Color.rgb(245, 166, 35)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = scaledStroke(bitmap)
            }
            canvas.drawCircle(
                circle.centerX.toFloat(),
                circle.centerY.toFloat(),
                circle.radius.toFloat(),
                paint
            )
        }
        return bitmap
    }

    /** 标准图和原图使用同一份冻结位点，只切换坐标，保证投影关系可核对。 */
    private fun drawFinalGrid(
        source: Bitmap,
        session: Plate96Locator.Session,
        useSourceCoordinates: Boolean
    ): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 0, 137, 123)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, scaledStroke(bitmap) * 0.55f)
        }
        val sites = session.result.sites
        repeat(session.result.orientation.canonicalRows) { row ->
            repeat(session.result.orientation.canonicalColumns - 1) { column ->
                val first = sites[row * 12 + column]
                val second = sites[row * 12 + column + 1]
                val firstCenter = if (useSourceCoordinates) first.sourceCenter else first.normalizedCenter
                val secondCenter = if (useSourceCoordinates) second.sourceCenter else second.normalizedCenter
                canvas.drawLine(
                    firstCenter.x.toFloat(), firstCenter.y.toFloat(),
                    secondCenter.x.toFloat(), secondCenter.y.toFloat(), linePaint
                )
            }
        }
        repeat(session.result.orientation.canonicalColumns) { column ->
            repeat(session.result.orientation.canonicalRows - 1) { row ->
                val first = sites[row * 12 + column]
                val second = sites[(row + 1) * 12 + column]
                val firstCenter = if (useSourceCoordinates) first.sourceCenter else first.normalizedCenter
                val secondCenter = if (useSourceCoordinates) second.sourceCenter else second.normalizedCenter
                canvas.drawLine(
                    firstCenter.x.toFloat(), firstCenter.y.toFloat(),
                    secondCenter.x.toFloat(), secondCenter.y.toFloat(), linePaint
                )
            }
        }
        sites.forEach { site ->
            val center = if (useSourceCoordinates) site.sourceCenter else site.normalizedCenter
            val radius = if (useSourceCoordinates) {
                min(site.sourceBounds.width, site.sourceBounds.height) / 2.0
            } else {
                site.radiusPx ?: min(site.normalizedBounds.width, site.normalizedBounds.height) / 2.0
            }
            val color = when (site.source) {
                ArraySiteLocalizationSource.GRID_IMPUTED -> Color.rgb(245, 166, 35)
                ArraySiteLocalizationSource.USER_ADJUSTED -> Color.rgb(90, 86, 200)
                else -> Color.rgb(0, 150, 110)
            }
            canvas.drawCircle(
                center.x.toFloat(),
                center.y.toFloat(),
                radius.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = scaledStroke(bitmap)
                }
            )
        }
        return bitmap
    }

    /** 将96个真实圆孔按A1～H12排列，接触表只缩放显示，不参与任何信号计算。 */
    private fun drawCropContactSheet(source: Bitmap, session: Plate96Locator.Session): Bitmap {
        val cell = 64
        val header = 28
        val padding = 12
        val width = padding * 2 + header + cell * 12
        val height = padding * 2 + header + cell * 8
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 250, 250))
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 70, 76)
            textAlign = Paint.Align.CENTER
            textSize = 14f
        }
        repeat(12) { column ->
            canvas.drawText(
                (column + 1).toString(),
                (padding + header + column * cell + cell / 2).toFloat(),
                (padding + 18).toFloat(),
                labelPaint
            )
        }
        repeat(8) { row ->
            canvas.drawText(
                ('A'.code + row).toChar().toString(),
                (padding + header / 2).toFloat(),
                (padding + header + row * cell + cell / 2 + 5).toFloat(),
                labelPaint
            )
        }
        session.result.sites.forEach { site ->
            val crop = ArrayUnitBitmapCropper.crop(
                source = source,
                region = site.normalizedRegion,
                targetWidth = cell - 10,
                targetHeight = cell - 10,
                transparentOutsideMask = true
            )
            try {
                val left = padding + header + site.canonicalCoordinate.columnIndex * cell + 5
                val top = padding + header + site.canonicalCoordinate.rowIndex * cell + 5
                canvas.drawBitmap(crop, left.toFloat(), top.toFloat(), null)
                canvas.drawCircle(
                    left + (cell - 10) / 2f,
                    top + (cell - 10) / 2f,
                    (cell - 12) / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (site.source == ArraySiteLocalizationSource.GRID_IMPUTED) {
                            Color.rgb(245, 166, 35)
                        } else {
                            Color.rgb(0, 150, 110)
                        }
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f
                    }
                )
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return bitmap
    }

    /** 信号图保留圆孔形态，颜色仅表达本次校正信号的相对分布。 */
    private fun drawSignalHeatmap(quant: PgQuantResult): Bitmap {
        val width = 900
        val height = 620
        val paddingX = 58f
        val paddingY = 44f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 250, 250))
        val values = quant.sites.map { it.correctedSignalGray }
        val sorted = values.sorted()
        val lower = percentile(sorted, 0.05)
        val upper = percentile(sorted, 0.95)
        val span = max(upper - lower, 1e-9)
        val cellWidth = (width - paddingX * 2) / 12f
        val cellHeight = (height - paddingY * 2) / 8f
        val radius = min(cellWidth, cellHeight) * 0.36f
        quant.sites.forEach { site ->
            val fraction = ((site.correctedSignalGray - lower) / span).coerceIn(0.0, 1.0)
            val centerX = paddingX + (site.columnIndex + 0.5f) * cellWidth
            val centerY = paddingY + (site.rowIndex + 0.5f) * cellHeight
            canvas.drawCircle(
                centerX,
                centerY,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = heatColor(fraction) }
            )
        }
        return bitmap
    }

    /** 每张图写入后立即回收，避免同时持有多张实拍分辨率派生图。 */
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
                    "无法编码96孔板处理证据：${file.absolutePath}"
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val metadata = linkedMapOf<String, Any?>(
            "schemaVersion" to PLATE96_PROCESSING_EVIDENCE_SCHEMA,
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

    private fun scaledStroke(bitmap: Bitmap): Float =
        max(2f, min(bitmap.width, bitmap.height) / 520f)

    private fun percentile(sorted: List<Double>, ratio: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = ((sorted.size - 1) * ratio).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }

    private fun heatColor(fraction: Double): Int {
        val blue = Color.rgb(34, 88, 142)
        val teal = Color.rgb(0, 150, 136)
        val amber = Color.rgb(255, 193, 7)
        val red = Color.rgb(198, 40, 40)
        return when {
            fraction <= 0.38 -> interpolateColor(blue, teal, fraction / 0.38)
            fraction <= 0.72 -> interpolateColor(teal, amber, (fraction - 0.38) / 0.34)
            else -> interpolateColor(amber, red, (fraction - 0.72) / 0.28)
        }
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
