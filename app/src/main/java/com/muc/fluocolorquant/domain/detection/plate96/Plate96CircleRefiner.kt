package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import javax.inject.Inject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 在YOLO候选框内执行圆形精定位。
 *
 * 第一选择为霍夫圆；失败后同时尝试亮前景和暗前景的Otsu轮廓，并按圆度、中心距离与
 * 尺寸合理性选择。两者都失败时才保留矩形中心兜底，且来源会明确标记为BOX_FALLBACK。
 */
class Plate96CircleRefiner @Inject constructor() {

    fun refine(
        sourceBitmap: Bitmap,
        candidates: List<Plate96ObjectCandidate>
    ): List<Plate96CircleCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val rgba = Mat()
        val rawGray = Mat()
        val enhancedGray = Mat()
        val clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, Size(CLAHE_TILE_SIZE, CLAHE_TILE_SIZE))
        return try {
            Utils.bitmapToMat(sourceBitmap, rgba)
            Imgproc.cvtColor(rgba, rawGray, Imgproc.COLOR_RGBA2GRAY)
            clahe.apply(rawGray, enhancedGray)
            candidates.map { candidate -> refineCandidate(enhancedGray, candidate) }
        } finally {
            clahe.collectGarbage()
            enhancedGray.release()
            rawGray.release()
            rgba.release()
        }
    }

    private fun refineCandidate(gray: Mat, candidate: Plate96ObjectCandidate): Plate96CircleCandidate {
        val roiRect = expandedClippedRect(candidate, gray.cols(), gray.rows())
        val roi = gray.submat(roiRect)
        val blurred = Mat()
        return try {
            Imgproc.GaussianBlur(roi, blurred, Size(5.0, 5.0), 2.0, 2.0)
            val hough = findHoughCircle(blurred)
            if (hough != null) {
                return hough.toCandidate(candidate, roiRect, Plate96CircleSource.HOUGH, 1.0)
            }
            val contour = findContourCircle(blurred)
            if (contour != null) {
                return contour.toCandidate(candidate, roiRect, Plate96CircleSource.CONTOUR, 0.88)
            }
            fallbackCandidate(candidate)
        } finally {
            blurred.release()
            roi.release()
        }
    }

    private fun findHoughCircle(roi: Mat): LocalCircle? {
        val circles = Mat()
        return try {
            val minimumDimension = min(roi.cols(), roi.rows())
            Imgproc.HoughCircles(
                roi,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1.0,
                max(roi.rows(), roi.cols()) / 1.2,
                100.0,
                30.0,
                max(2, minimumDimension / 6),
                max(3, minimumDimension / 2)
            )
            val roiCenterX = (roi.cols() - 1) / 2.0
            val roiCenterY = (roi.rows() - 1) / 2.0
            (0 until circles.cols()).mapNotNull { column ->
                val values = circles.get(0, column) ?: return@mapNotNull null
                if (values.size < 3) return@mapNotNull null
                LocalCircle(values[0], values[1], values[2])
            }.minByOrNull { circle ->
                hypot(circle.x - roiCenterX, circle.y - roiCenterY) / minimumDimension.coerceAtLeast(1)
            }
        } finally {
            circles.release()
        }
    }

    private fun findContourCircle(roi: Mat): LocalCircle? {
        val variants = listOf(Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV)
        return variants.mapNotNull { thresholdType -> contourCandidate(roi, thresholdType) }
            .maxByOrNull { it.score }
            ?.circle
    }

    private fun contourCandidate(roi: Mat, thresholdType: Int): ScoredCircle? {
        val binary = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        return try {
            Imgproc.threshold(roi, binary, 0.0, 255.0, thresholdType + Imgproc.THRESH_OTSU)
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val roiCenterX = (roi.cols() - 1) / 2.0
            val roiCenterY = (roi.rows() - 1) / 2.0
            val minimumDimension = min(roi.cols(), roi.rows()).toDouble().coerceAtLeast(1.0)
            contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < MINIMUM_CONTOUR_AREA_PX) return@mapNotNull null
                val points = MatOfPoint2f(*contour.toArray())
                val center = Point()
                val radius = FloatArray(1)
                try {
                    val perimeter = Imgproc.arcLength(points, true)
                    if (perimeter <= 0.0) return@mapNotNull null
                    val circularity = (4.0 * PI * area / (perimeter * perimeter)).coerceIn(0.0, 1.0)
                    if (circularity < MINIMUM_CIRCULARITY) return@mapNotNull null
                    Imgproc.minEnclosingCircle(points, center, radius)
                    val radiusValue = radius[0].toDouble()
                    if (radiusValue !in minimumDimension / 8.0..minimumDimension / 1.8) return@mapNotNull null
                    val centerDistance = hypot(center.x - roiCenterX, center.y - roiCenterY) / minimumDimension
                    val fillRatio = area / (PI * radiusValue * radiusValue).coerceAtLeast(1.0)
                    val score = circularity * 0.50 + fillRatio.coerceIn(0.0, 1.0) * 0.30 +
                        (1.0 - centerDistance.coerceIn(0.0, 1.0)) * 0.20
                    ScoredCircle(LocalCircle(center.x, center.y, radiusValue), score)
                } finally {
                    points.release()
                }
            }.maxByOrNull { it.score }
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            binary.release()
        }
    }

    private fun expandedClippedRect(candidate: Plate96ObjectCandidate, width: Int, height: Int): Rect {
        val bounds = candidate.bounds
        val centerX = (bounds.left + bounds.right) / 2.0
        val centerY = (bounds.top + bounds.bottom) / 2.0
        val expandedWidth = bounds.width * (1.0 + BOX_EXPANSION_RATIO)
        val expandedHeight = bounds.height * (1.0 + BOX_EXPANSION_RATIO)
        val left = (centerX - expandedWidth / 2.0).roundToInt().coerceIn(0, width - 1)
        val top = (centerY - expandedHeight / 2.0).roundToInt().coerceIn(0, height - 1)
        val right = (centerX + expandedWidth / 2.0).roundToInt().coerceIn(left + 1, width)
        val bottom = (centerY + expandedHeight / 2.0).roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun LocalCircle.toCandidate(
        objectCandidate: Plate96ObjectCandidate,
        roi: Rect,
        source: Plate96CircleSource,
        confidenceMultiplier: Double
    ): Plate96CircleCandidate {
        return Plate96CircleCandidate(
            candidateIndex = objectCandidate.candidateIndex,
            centerX = x + roi.x,
            centerY = y + roi.y,
            radius = radius,
            confidence = (objectCandidate.confidence * confidenceMultiplier).coerceIn(0.0, 1.0),
            source = source,
            objectBounds = objectCandidate.bounds
        )
    }

    private fun fallbackCandidate(candidate: Plate96ObjectCandidate): Plate96CircleCandidate {
        val bounds = candidate.bounds
        return Plate96CircleCandidate(
            candidateIndex = candidate.candidateIndex,
            centerX = (bounds.left + bounds.right) / 2.0,
            centerY = (bounds.top + bounds.bottom) / 2.0,
            radius = min(bounds.width, bounds.height) * FALLBACK_RADIUS_RATIO,
            confidence = (candidate.confidence * FALLBACK_CONFIDENCE_MULTIPLIER).coerceIn(0.0, 1.0),
            source = Plate96CircleSource.BOX_FALLBACK,
            objectBounds = bounds
        )
    }

    private data class LocalCircle(val x: Double, val y: Double, val radius: Double)
    private data class ScoredCircle(val circle: LocalCircle, val score: Double)

    private companion object {
        const val CLAHE_CLIP_LIMIT: Double = 4.0
        const val CLAHE_TILE_SIZE: Double = 8.0
        const val BOX_EXPANSION_RATIO: Double = 0.06
        const val FALLBACK_RADIUS_RATIO: Double = 0.42
        const val FALLBACK_CONFIDENCE_MULTIPLIER: Double = 0.65
        const val MINIMUM_CONTOUR_AREA_PX: Double = 20.0
        const val MINIMUM_CIRCULARITY: Double = 0.45
    }
}
