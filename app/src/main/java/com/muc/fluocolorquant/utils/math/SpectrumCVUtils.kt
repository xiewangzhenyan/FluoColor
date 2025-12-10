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
import kotlin.math.min

/**
 * 光谱处理工具（依赖 OpenCV）。使用前请确保 OpenCVLoader.initDebug() 已调用。
 */
object SpectrumCVUtils {
    private const val TAG = "SpectrumCVUtils"

    /**
     * 垂直光谱条带自动定位 (缩放优化 + CLAHE 增强 + 极端垂直膨胀)。
     * 流程: 缩小图像 -> 灰度 -> CLAHE 增强 -> 大津法阈值 -> 极端垂直膨胀 -> 轮廓筛选 -> 坐标还原。
     * 优化: 在缩小的图像上处理,避免 ANR,最终强制全高度覆盖。
     */
    fun detectSpectrumTracks(bitmap: Bitmap, expectedTracks: Int): List<Rect> {
        if (expectedTracks <= 0) return emptyList()

        val src = Mat()
        val resizedSrc = Mat()
        val gray = Mat()
        val enhanced = Mat()
        val binary = Mat()
        val dilated = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        return try {
            // 1. 加载 Bitmap -> Mat
            Utils.bitmapToMat(bitmap, src)
            
            // 2. 缩放优化: 将图像高度缩小到 800px 左右,避免 ANR
            val targetHeight = 800.0
            val scale = if (src.rows() > targetHeight) {
                targetHeight / src.rows()
            } else {
                1.0 // 原图小于 800px 则不缩放
            }
            
            if (scale < 1.0) {
                val newWidth = (src.cols() * scale).toInt()
                val newHeight = (src.rows() * scale).toInt()
                Imgproc.resize(src, resizedSrc, Size(newWidth.toDouble(), newHeight.toDouble()))
                Log.d(TAG, "图像已缩放: ${src.cols()}x${src.rows()} -> ${newWidth}x${newHeight}, scale=$scale")
            } else {
                src.copyTo(resizedSrc)
                Log.d(TAG, "图像无需缩放: ${src.cols()}x${src.rows()}")
            }

            // 3. 转灰度
            Imgproc.cvtColor(resizedSrc, gray, Imgproc.COLOR_RGBA2GRAY)

            // 4. CLAHE 对比度增强 (增强暗部可见度)
            val clahe = Imgproc.createCLAHE(4.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)
            Log.d(TAG, "CLAHE 对比度增强完成")

            // 5. 二值化: 大津法阈值
            Imgproc.threshold(
                enhanced,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )

            // 6. 极端垂直膨胀 (在缩小后的图上计算核大小)
            val kernelHeight = (resizedSrc.rows() / 3).coerceAtLeast(50)
            val kernelWidth = 3
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelWidth.toDouble(), kernelHeight.toDouble())
            )
            Imgproc.morphologyEx(binary, dilated, Imgproc.MORPH_CLOSE, verticalKernel)
            verticalKernel.release()
            Log.d(TAG, "垂直膨胀完成,核大小: ${kernelWidth}x${kernelHeight} (缩放后)")

            // 7. 轮廓查找
            Imgproc.findContours(
                dilated,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            Log.d(TAG, "轮廓总数: ${contours.size}")

            // 8. 筛选轮廓
            val minArea = 500.0
            val candidateRects = contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < minArea) return@mapNotNull null
                
                val rect = Imgproc.boundingRect(contour)
                
                // 保留高度大于宽度的矩形(垂直条带特征)
                if (rect.height > rect.width) {
                    rect
                } else {
                    null
                }
            }

            Log.d(TAG, "筛选后的候选矩形数: ${candidateRects.size}")

            // 9. 排序和选取
            val picked = if (candidateRects.size > expectedTracks) {
                candidateRects
                    .sortedByDescending { it.area() }
                    .take(expectedTracks)
                    .sortedBy { it.x }
            } else {
                candidateRects.sortedBy { it.x }
            }

            // 10. 坐标还原 + 强制全高度
            val xPadding = 2
            val result = picked.map { rect ->
                // 还原 X 坐标到原始图像尺寸
                val originalLeft = ((rect.x - xPadding) / scale).toInt().coerceAtLeast(0)
                val originalRight = ((rect.x + rect.width + xPadding) / scale).toInt().coerceAtMost(bitmap.width)
                
                // 强制全高度: top=0, bottom=原始图片高度
                Rect(originalLeft, 0, originalRight, bitmap.height)
            }
            
            // 11. 保底策略
            if (result.size < expectedTracks) {
                Log.w(TAG, "检测到的通道数不足 (${result.size}/$expectedTracks),启用保底策略:均分图片宽度")
                return createFallbackTracks(bitmap.width, bitmap.height, expectedTracks)
            }
            
            Log.d(TAG, "成功检测到 ${result.size} 个光谱通道 (覆盖完整高度, 坐标已还原)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV 检测失败: ${e.message}, 使用保底策略")
            createFallbackTracks(bitmap.width, bitmap.height, expectedTracks)
        } finally {
            // 释放资源
            src.release()
            resizedSrc.release()
            gray.release()
            enhanced.release()
            binary.release()
            dilated.release()
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

    /**
     * 在指定列 ROI 中寻找最显著的若干个波峰（用于自动标定）。
     * 返回的 Y 坐标以像素为单位（相对于原图），数量不超过 expectedPeaksCount。
     */
    fun findPeaksInTrack(
        bitmap: Bitmap,
        rect: Rect,
        expectedPeaksCount: Int
    ): List<Int> {
        if (expectedPeaksCount <= 0) return emptyList()

        val profile = extractIntensityProfile(bitmap, rect)
        if (profile.isEmpty()) return emptyList()

        val maxVal = profile.maxOrNull() ?: 0f
        val threshold = maxVal * 0.35f // 过滤低噪声

        val candidates = mutableListOf<Pair<Int, Float>>() // y to intensity
        for (y in 1 until profile.lastIndex) {
            val v = profile[y]
            if (v < threshold) continue
            val prev = profile[y - 1]
            val next = profile[y + 1]
            if (v >= prev && v >= next) {
                candidates += y to v
            }
        }

        // 取强度最高的 expectedPeaksCount 个点，并按 Y 排序（便于后续与波长匹配）
        val picked = candidates
            .sortedByDescending { it.second }
            .take(expectedPeaksCount)
            .sortedBy { it.first }
            .map { rect.top + it.first }

        return picked
    }

    /**
     * 保底策略:将图片宽度均分为 count 份,生成默认的垂直矩形通道区域。
     * 用于 OpenCV 检测失败或检测结果不足时。
     */
    private fun createFallbackTracks(width: Int, height: Int, count: Int): List<Rect> {
        if (count <= 0) return emptyList()
        
        val trackWidth = width / count
        return List(count) { index ->
            val left = index * trackWidth
            val right = if (index == count - 1) width else (index + 1) * trackWidth
            Rect(left, 0, right, height)
        }
    }

    private fun CvRect.area(): Double = this.width.toDouble() * this.height.toDouble()

    private fun CvRect.toAndroidRect(): Rect = Rect(this.x, this.y, this.x + this.width, this.y + this.height)
}
