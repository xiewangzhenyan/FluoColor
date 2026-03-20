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
 * 对齐计算结果：包含缩放比例和坐标偏移量
 * 用于将标定图坐标转换到原始图坐标系
 * 转换公式: Coord_orig = Coord_calib * scale - delta
 */
data class AlignmentResult(
    val scale: Double,      // 缩放比例 (original / calibration)
    val deltaX: Double,     // X轴偏移量
    val deltaY: Double      // Y轴偏移量
)

/**
 * 光谱处理工具（依赖 OpenCV）。使用前请确保 OpenCVLoader.initDebug() 已调用。
 */
/**
 * 光谱带在当前通道中的有效高度范围。
 */
data class EffectiveHeightBounds(
    val top: Int,
    val bottomExclusive: Int,
    val coverage: Double,
    val usedFallback: Boolean = false
)

object SpectrumCVUtils {
    private const val TAG = "SpectrumCVUtils"
    
    /**
     * 计算标定图与原始图之间的对齐偏移量
     * 
     * @param originalTracks 原始图的通道矩形列表
     * @param calibrationTracks 标定图的通道矩形列表
     * @param originalWidth 原始图宽度
     * @param originalHeight 原始图高度
     * @param calibrationWidth 标定图宽度
     * @param calibrationHeight 标定图高度
     * @return 对齐结果，如果无法对齐则返回null
     */
    fun calculateAlignmentOffset(
        originalTracks: List<Rect>,
        calibrationTracks: List<Rect>,
        originalWidth: Int,
        originalHeight: Int,
        calibrationWidth: Int,
        calibrationHeight: Int
    ): AlignmentResult? {
        // 1. 数量校验
        if (originalTracks.isEmpty() || calibrationTracks.isEmpty()) {
            Log.w(TAG, "通道列表为空,无法计算对齐")
            return null
        }
        
        if (originalTracks.size != calibrationTracks.size) {
            Log.w(TAG, "通道数量不一致: 原始图=${originalTracks.size}, 标定图=${calibrationTracks.size}")
            return null
        }
        
        // 2. 计算几何中心
        val origCenterX = originalTracks.map { it.centerX() }.average()
        val origCenterY = originalTracks.map { it.centerY() }.average()
        val calibCenterX = calibrationTracks.map { it.centerX() }.average()
        val calibCenterY = calibrationTracks.map { it.centerY() }.average()
        
        // 3. 计算缩放比例 (基于通道群的总宽度)
        val origMinX = originalTracks.minOf { it.left }
        val origMaxX = originalTracks.maxOf { it.right }
        val origWidth = (origMaxX - origMinX).toDouble().coerceAtLeast(1.0)
        
        val calibMinX = calibrationTracks.minOf { it.left }
        val calibMaxX = calibrationTracks.maxOf { it.right }
        val calibWidth = (calibMaxX - calibMinX).toDouble().coerceAtLeast(1.0)
        
        val scale = origWidth / calibWidth
        Log.d(TAG, "通道群宽度: 原始=$origWidth, 标定=$calibWidth, 缩放比例=$scale")
        
        // 4. 计算偏移量
        // 转换公式: Coord_orig = Coord_calib * scale - delta
        // 即: delta = Coord_calib * scale - Coord_orig
        val deltaX = calibCenterX * scale - origCenterX
        val deltaY = calibCenterY * scale - origCenterY
        
        Log.d(TAG, "对齐参数: scale=$scale, deltaX=$deltaX, deltaY=$deltaY")
        Log.d(TAG, "几何中心: orig=($origCenterX, $origCenterY), calib=($calibCenterX, $calibCenterY)")
        
        return AlignmentResult(scale, deltaX, deltaY)
    }
    
    /**
     * 保底对齐策略：假设用户是居中裁切的
     * 当通道检测失败时使用
     */
    fun calculateFallbackAlignment(
        originalWidth: Int,
        originalHeight: Int,
        calibrationWidth: Int,
        calibrationHeight: Int
    ): AlignmentResult {
        // 假设缩放比例为1（未缩放）
        val scale = 1.0
        // 居中对齐偏移量
        val deltaX = (calibrationWidth - originalWidth) / 2.0
        val deltaY = (calibrationHeight - originalHeight) / 2.0
        
        Log.d(TAG, "使用保底对齐策略: deltaX=$deltaX, deltaY=$deltaY")
        return AlignmentResult(scale, deltaX, deltaY)
    }

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
        val safeLeft = roi?.let { max(0, it.left) } ?: 0
        val safeTop = roi?.let { max(0, it.top) } ?: 0
        val safeRight = roi?.let { max(safeLeft + 1, minOf(bitmap.width, it.right)) } ?: bitmap.width
        val safeBottom = roi?.let { max(safeTop + 1, minOf(bitmap.height, it.bottom)) } ?: bitmap.height

        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        val rowPixels = IntArray(width)
        val output = FloatArray(height)

        // 逐行读取像素，避免额外创建裁切 Bitmap 导致大图场景内存峰值过高。
        for (row in 0 until height) {
            bitmap.getPixels(
                rowPixels,
                0,
                width,
                safeLeft,
                safeTop + row,
                width,
                1
            )

            var sum = 0.0
            rowPixels.forEach { pixel ->
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                sum += 0.299 * red + 0.587 * green + 0.114 * blue
            }
            output[row] = (sum / width).toFloat()
        }

        return output
    }

    /**
     * 在 ROI 内仅使用指定的列范围提取纵向强度轮廓。
     */
    private fun extractIntensityProfileByColumns(
        bitmap: Bitmap,
        roi: Rect,
        startColumnOffset: Int,
        columnCount: Int
    ): FloatArray {
        val safeLeft = max(0, roi.left)
        val safeTop = max(0, roi.top)
        val safeRight = minOf(bitmap.width, roi.right)
        val safeBottom = minOf(bitmap.height, roi.bottom)
        val roiWidth = (safeRight - safeLeft).coerceAtLeast(1)
        val roiHeight = (safeBottom - safeTop).coerceAtLeast(1)

        val startX = (safeLeft + startColumnOffset).coerceIn(safeLeft, safeRight - 1)
        val width = columnCount.coerceAtLeast(1).coerceAtMost(safeRight - startX)
        val rowPixels = IntArray(width)
        val output = FloatArray(roiHeight)

        for (row in 0 until roiHeight) {
            bitmap.getPixels(
                rowPixels,
                0,
                width,
                startX,
                safeTop + row,
                width,
                1
            )

            var sum = 0.0
            rowPixels.forEach { pixel ->
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                sum += 0.299 * red + 0.587 * green + 0.114 * blue
            }
            output[row] = (sum / width).toFloat()
        }

        return output
    }

    /**
     * 从左往右寻找第一个明显发亮的列，并返回用于纵向寻峰的小窗口。
     */
    private fun findFirstBrightColumnWindow(bitmap: Bitmap, rect: Rect): Pair<Int, Int>? {
        val safeLeft = max(0, rect.left)
        val safeTop = max(0, rect.top)
        val safeRight = minOf(bitmap.width, rect.right)
        val safeBottom = minOf(bitmap.height, rect.bottom)
        val width = (safeRight - safeLeft).coerceAtLeast(1)
        val height = (safeBottom - safeTop).coerceAtLeast(1)
        val columnPixels = IntArray(height)
        val columnScores = FloatArray(width)

        for (columnOffset in 0 until width) {
            bitmap.getPixels(
                columnPixels,
                0,
                1,
                safeLeft + columnOffset,
                safeTop,
                1,
                height
            )

            var sum = 0.0
            columnPixels.forEach { pixel ->
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                sum += 0.299 * red + 0.587 * green + 0.114 * blue
            }
            columnScores[columnOffset] = (sum / height).toFloat()
        }

        val maxScore = columnScores.maxOrNull() ?: return null
        val avgScore = columnScores.average().toFloat()
        val threshold = maxOf(maxScore * 0.42f, avgScore * 1.2f)
        val firstBright = columnScores.indexOfFirst { it >= threshold }
        if (firstBright < 0) {
            return null
        }

        val windowWidth = (width * 0.18f).toInt().coerceIn(3, 10)
        val availableWidth = width - firstBright
        return firstBright to windowWidth.coerceAtMost(availableWidth.coerceAtLeast(1))
    }

    /**
     * 从纵向强度轮廓中提取局部峰值。
     */
    private fun detectPeaksFromProfile(
        profile: FloatArray,
        rectTop: Int,
        expectedPeaksCount: Int
    ): List<Int> {
        if (profile.isEmpty() || expectedPeaksCount <= 0) return emptyList()

        val maxVal = profile.maxOrNull() ?: 0f
        val avgVal = profile.average().toFloat()
        val threshold = maxOf(maxVal * 0.1f, avgVal * 1.2f)
        val candidates = mutableListOf<Pair<Int, Float>>()

        for (y in 1 until profile.lastIndex) {
            val value = profile[y]
            if (value < threshold) continue
            if (value >= profile[y - 1] && value >= profile[y + 1]) {
                candidates += y to value
            }
        }

        val minPeakDistance = (profile.size * 0.045f).toInt().coerceAtLeast(8)
        val mergedPeaks = mutableListOf<Pair<Int, Float>>()
        candidates.sortedBy { it.first }.forEach { candidate ->
            val nearbyPeak = mergedPeaks.find { kotlin.math.abs(it.first - candidate.first) < minPeakDistance }
            if (nearbyPeak != null) {
                if (candidate.second > nearbyPeak.second) {
                    mergedPeaks.remove(nearbyPeak)
                    mergedPeaks.add(candidate)
                }
            } else {
                mergedPeaks.add(candidate)
            }
        }

        return mergedPeaks
            .sortedByDescending { it.second }
            .take(expectedPeaksCount)
            .sortedBy { it.first }
            .map { rectTop + it.first }
    }

    /**
     * 垂直偏移标定：基于参考列系数和中心点 Y 偏移，计算新列系数。
     */
    /**
     * 基于纵向强度轮廓检测当前通道的有效高度范围。
     * 当整条通道被拉满全图高度时，可借此缩小到真正有信号的区域。
     */
    fun detectEffectiveHeightBounds(bitmap: Bitmap, rect: Rect): EffectiveHeightBounds {
        val profile = extractIntensityProfile(bitmap, rect)
        if (profile.isEmpty()) {
            return EffectiveHeightBounds(
                top = rect.top,
                bottomExclusive = rect.bottom,
                coverage = 1.0,
                usedFallback = true
            )
        }

        val minValue = profile.minOrNull() ?: 0f
        val maxValue = profile.maxOrNull() ?: minValue
        val range = (maxValue - minValue).coerceAtLeast(1f)
        val smoothed = FloatArray(profile.size) { index ->
            val start = (index - 2).coerceAtLeast(0)
            val end = (index + 2).coerceAtMost(profile.lastIndex)
            var sum = 0f
            for (sampleIndex in start..end) {
                sum += profile[sampleIndex]
            }
            sum / (end - start + 1).toFloat()
        }

        val normalized = smoothed.map { ((it - minValue) / range).coerceIn(0f, 1f) }
        // 放宽有效高度阈值，避免较暗的边缘峰被直接裁掉。
        val threshold = maxOf(0.12f, normalized.average().toFloat() * 0.85f)
        val activeRows = normalized.mapIndexedNotNull { index, value ->
            if (value >= threshold) index else null
        }

        if (activeRows.isEmpty()) {
            return EffectiveHeightBounds(
                top = rect.top,
                bottomExclusive = rect.bottom,
                coverage = 1.0,
                usedFallback = true
            )
        }

        val topRow = activeRows.first()
        val bottomRowExclusive = activeRows.last() + 1
        val padding = ((bottomRowExclusive - topRow) * 0.12f).toInt().coerceAtLeast(10)
        val finalTopRow = (topRow - padding).coerceAtLeast(0)
        val finalBottomRow = (bottomRowExclusive + padding).coerceAtMost(profile.size)
        val height = (finalBottomRow - finalTopRow).coerceAtLeast(1)

        return EffectiveHeightBounds(
            top = rect.top + finalTopRow,
            bottomExclusive = rect.top + finalBottomRow,
            coverage = height.toDouble() / profile.size.toDouble(),
            usedFallback = false
        )
    }

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
     * 
     * 优化：支持检测分离的激光点（如标定图中的红、绿、紫色激光点）
     */
    fun findPeaksInTrack(
        bitmap: Bitmap,
        rect: Rect,
        expectedPeaksCount: Int
    ): List<Int> {
        if (expectedPeaksCount <= 0) return emptyList()

        val brightWindow = findFirstBrightColumnWindow(bitmap, rect)
        val peaksFromBrightColumns = brightWindow?.let { (startColumnOffset, columnCount) ->
            val focusedProfile = extractIntensityProfileByColumns(
                bitmap = bitmap,
                roi = rect,
                startColumnOffset = startColumnOffset,
                columnCount = columnCount
            )
            detectPeaksFromProfile(focusedProfile, rect.top, expectedPeaksCount).also { peaks ->
                Log.d(
                    TAG,
                    "亮列寻峰: startColumnOffset=$startColumnOffset, columnCount=$columnCount, peaks=$peaks"
                )
            }
        } ?: emptyList()

        if (peaksFromBrightColumns.size >= expectedPeaksCount) {
            return peaksFromBrightColumns
        }

        val fullWidthProfile = extractIntensityProfile(bitmap, rect)
        val peaksFromFullWidth = detectPeaksFromProfile(
            profile = fullWidthProfile,
            rectTop = rect.top,
            expectedPeaksCount = expectedPeaksCount
        )
        Log.d(TAG, "全宽寻峰结果: $peaksFromFullWidth")

        val selectedPeaks = when {
            peaksFromFullWidth.size >= expectedPeaksCount -> peaksFromFullWidth
            peaksFromBrightColumns.size >= peaksFromFullWidth.size -> peaksFromBrightColumns
            else -> peaksFromFullWidth
        }

        Log.d(TAG, "最终选取 ${selectedPeaks.size} 个峰值点: $selectedPeaks")
        return selectedPeaks

        val profile = extractIntensityProfile(bitmap, rect)
        if (profile.isEmpty()) return emptyList()

        val maxVal = profile.maxOrNull() ?: 0f
        val avgVal = profile.average().toFloat()
        
        // 使用更低的阈值：max(最大值的15%, 平均值的1.5倍) 来适应分离的激光点
        val threshold = maxOf(maxVal * 0.15f, avgVal * 1.5f)
        
        Log.d(TAG, "峰值检测 - maxVal=$maxVal, avgVal=$avgVal, threshold=$threshold")

        // 第一遍：找出所有局部极大值点
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
        
        Log.d(TAG, "初步检测到 ${candidates.size} 个候选峰值点")

        // 合并邻近的峰值点（距离小于图像高度的5%视为同一个峰）
        val minPeakDistance = (profile.size * 0.05).toInt().coerceAtLeast(10)
        val mergedPeaks = mutableListOf<Pair<Int, Float>>()
        
        candidates.sortedBy { it.first }.forEach { candidate ->
            val nearbyPeak = mergedPeaks.find { 
                kotlin.math.abs(it.first - candidate.first) < minPeakDistance 
            }
            if (nearbyPeak != null) {
                // 如果找到邻近峰，保留强度更高的那个
                if (candidate.second > nearbyPeak.second) {
                    mergedPeaks.remove(nearbyPeak)
                    mergedPeaks.add(candidate)
                }
            } else {
                mergedPeaks.add(candidate)
            }
        }
        
        Log.d(TAG, "合并后剩余 ${mergedPeaks.size} 个峰值区域")

        // 取强度最高的 expectedPeaksCount 个点，并按 Y 排序
        val picked = mergedPeaks
            .sortedByDescending { it.second }
            .take(expectedPeaksCount)
            .sortedBy { it.first }
            .map { rect.top + it.first }

        Log.d(TAG, "最终选取 ${picked.size} 个峰值点: $picked")
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
