package com.muc.fluocolorquant.utils.math

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 光谱处理工具（依赖 OpenCV）。使用前请确保 OpenCVLoader.initDebug() 已调用。
 */
object SpectrumCVUtils {
    private const val TAG = "SpectrumCVUtils"

    /**
     * 垂直光谱条带自动定位。
     * 流程：Bitmap -> Mat -> 灰度 -> 高斯模糊 -> 自适应阈值 -> 形态学闭运算 -> 轮廓筛选。
     */
    fun detectSpectrumTracks(bitmap: Bitmap, expectedTracks: Int): List<Rect> {
        if (expectedTracks <= 0) return emptyList()

        val src = Mat()
        val gray = Mat()
        val blurred = Mat()
        val binary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 50.0)) // 垂直核，更好连接竖向亮斑
        val closed = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.adaptiveThreshold(
                blurred,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11,
                2.0
            )
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)

            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Log.d(TAG, "Contours found: ${contours.size}")

            val candidateRects = contours.mapNotNull { contour ->
                val rect = Imgproc.boundingRect(contour)
                val area = rect.area()
                val heightGtWidth = rect.height > rect.width
                val aspectRatioOk = rect.height > 0 && rect.width > 0 && rect.height.toDouble() / rect.width.toDouble() >= 1.2
                if (area < 500.0) return@mapNotNull null
                if (!heightGtWidth || !aspectRatioOk) return@mapNotNull null
                rect
            }

            val picked = candidateRects
                .sortedByDescending { it.area() }
                .take(expectedTracks)
                .sortedBy { it.x }

            Log.d(TAG, "Tracks after filtering: ${picked.size} (expected $expectedTracks)")

            picked.map { it.toAndroidRect() }
        } finally {
            // 确保释放 Mat 资源，避免内存泄漏
            src.release()
            gray.release()
            blurred.release()
            binary.release()
            kernel.release()
            closed.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * 竖直方向强度曲线提取：按行(Y轴)求平均。
     */
    fun extractIntensityProfile(bitmap: Bitmap, roi: Rect? = null): FloatArray {
        val crop = if (roi != null) {
            val safeLeft = max(0, roi.left)
            val safeTop = max(0, roi.top)
            val safeRight = max(safeLeft + 1, minOf(bitmap.width, roi.right))
            val safeBottom = max(safeTop + 1, minOf(bitmap.height, roi.bottom))
            Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
        } else {
            bitmap
        }

        val mat = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(crop, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

            val width = gray.cols()
            val height = gray.rows()
            val output = FloatArray(height)

            // 行方向投影：每行像素求平均（波长沿 Y 轴变化）
            for (y in 0 until height) {
                var sum = 0.0
                for (x in 0 until width) {
                    val intensity = gray.get(y, x)[0]
                    sum += intensity
                }
                output[y] = (sum / width).toFloat()
            }
            output
        } finally {
            mat.release()
            gray.release()
            if (crop !== bitmap) {
                crop.recycle()
            }
        }
    }

    /**
     * 垂直偏移标定：基于参考列系数和中心点 Y 偏移，计算新列系数。
     */
    fun calculateOffsetCalibration(
        baseRect: Rect,
        targetRect: Rect,
        baseCoeffs: DoubleArray
    ): DoubleArray {
        require(baseCoeffs.size >= 3) { "baseCoeffs 必须至少包含 [A, B, C]" }
        val deltaY = (targetRect.centerY() - baseRect.centerY()).toDouble()
        val a = baseCoeffs[0]
        val b = baseCoeffs[1]
        val c = baseCoeffs[2]

        val newA = a
        val newB = b - 2 * a * deltaY
        val newC = a * deltaY * deltaY - b * deltaY + c

        return doubleArrayOf(newA, newB, newC)
    }

    private fun CvRect.area(): Double = this.width.toDouble() * this.height.toDouble()

    private fun CvRect.toAndroidRect(): Rect = Rect(this.x, this.y, this.x + this.width, this.y + this.height)
}
