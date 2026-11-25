package com.muc.fluocolorquant.domain.detection.segmentation

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridImageRectifier
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import javax.inject.Inject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 方形微流控单元与圆形孔板单元共用的 OpenCV 分割器。
 *
 * 核心流程逐项对齐 `tools/pg_grid/pg_unit_export.py`：0.45×pitch 局部窗口、极性感知
 * Otsu、3×3 开运算、包含晶格点或最近连通域、窗口边界拒绝、全阵列中位尺寸校验以及
 * 中位几何兜底。圆形单元额外保留连通域掩膜，避免外接矩形四角背景进入信号计算。
 */
class OpenCvArrayUnitSegmenter @Inject constructor() {

    /** 从原图开始：复用冻结单应矩阵生成矫正图，再执行逐单元分割。 */
    fun segment(
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        shape: ArrayUnitShape,
        config: ArrayUnitSegmentationConfig = ArrayUnitSegmentationConfig()
    ): ArrayUnitSegmentationResult {
        val rectified = PgGridImageRectifier.rectify(sourceBitmap, grid)
        return try {
            segmentRectified(
                rectifiedBitmap = rectified,
                points = grid.sites.map { it.rectified },
                rows = grid.rows,
                columns = grid.columns,
                polarity = grid.targetPolarity,
                shape = shape,
                config = config
            )
        } finally {
            if (!rectified.isRecycled) rectified.recycle()
        }
    }

    /**
     * 对已经矫正的图像分割；该入口用于设备合成测试和处理证据复用。
     * 点位必须按行优先排列，返回区域顺序与输入顺序严格一致。
     */
    fun segmentRectified(
        rectifiedBitmap: Bitmap,
        points: List<GridPoint>,
        rows: Int,
        columns: Int,
        polarity: GridTargetPolarity,
        shape: ArrayUnitShape,
        config: ArrayUnitSegmentationConfig = ArrayUnitSegmentationConfig()
    ): ArrayUnitSegmentationResult {
        require(rows > 0 && columns > 0) { "阵列分割行列必须大于 0" }
        require(points.size == rows * columns) { "分割点数必须等于 rows × columns" }
        require(rectifiedBitmap.width > 0 && rectifiedBitmap.height > 0) { "矫正图尺寸无效" }

        val width = rectifiedBitmap.width
        val height = rectifiedBitmap.height
        val rgbaPixels = IntArray(width * height)
        rectifiedBitmap.getPixels(rgbaPixels, 0, width, 0, 0, width, height)

        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(rectifiedBitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            segmentGrayAndColor(
                gray = gray,
                rgbaPixels = rgbaPixels,
                imageWidth = width,
                points = points,
                rows = rows,
                columns = columns,
                polarity = polarity,
                shape = shape,
                config = config
            )
        } finally {
            gray.release()
            rgba.release()
        }
    }

    private fun segmentGrayAndColor(
        gray: Mat,
        rgbaPixels: IntArray,
        imageWidth: Int,
        points: List<GridPoint>,
        rows: Int,
        columns: Int,
        polarity: GridTargetPolarity,
        shape: ArrayUnitShape,
        config: ArrayUnitSegmentationConfig
    ): ArrayUnitSegmentationResult {
        val estimatedPitch = RegularGridGeometry.estimatePitch(points, rows, columns)
            .representativePx
            .takeIf { it.isFinite() && it > MINIMUM_VALID_PITCH }
            ?: minOf(
                gray.cols().toDouble() / maxOf(columns + 1, 2),
                gray.rows().toDouble() / maxOf(rows + 1, 2)
            )
        val halfWindow = maxOf(MINIMUM_WINDOW_HALF_PX, (estimatedPitch * config.windowPitchRatio).roundToInt())
        val maximumCenterShift = estimatedPitch * config.maximumCenterShiftPitchRatio

        // 第一遍只做局部分割并收集全部成功框尺寸；与 Python 一样，几何离群在第二遍处理。
        val attempts = points.map { center ->
            segmentSingleUnit(
                gray = gray,
                rgbaPixels = rgbaPixels,
                imageWidth = imageWidth,
                center = center,
                halfWindow = halfWindow,
                polarity = polarity,
                config = config
            )
        }
        val widths = attempts.mapNotNull { it.segment?.bounds?.width }
        val heights = attempts.mapNotNull { it.segment?.bounds?.height }
        val medianWidth = if (widths.isNotEmpty()) medianInt(widths) else {
            maxOf(MINIMUM_WINDOW_HALF_PX, (estimatedPitch * DEFAULT_FALLBACK_SIZE_PITCH_RATIO).roundToInt())
        }
        val medianHeight = if (heights.isNotEmpty()) medianInt(heights) else {
            maxOf(MINIMUM_WINDOW_HALF_PX, (estimatedPitch * DEFAULT_FALLBACK_SIZE_PITCH_RATIO).roundToInt())
        }

        val regions = points.mapIndexed { index, center ->
            val attempt = attempts[index]
            val raw = attempt.segment
            val rejectionReason = if (raw == null) {
                attempt.fallbackReason ?: ArrayUnitFallbackReason.LOW_CONTRAST
            } else {
                rejectionReason(
                    raw = raw,
                    contrast = attempt.contrast,
                    center = center,
                    medianWidth = medianWidth,
                    medianHeight = medianHeight,
                    maximumCenterShift = maximumCenterShift,
                    shape = shape,
                    config = config
                )
            }
            if (raw != null && rejectionReason == null) {
                buildSegmentedRegion(
                    index = index,
                    columns = columns,
                    raw = raw,
                    shape = shape,
                    imageWidth = gray.cols(),
                    imageHeight = gray.rows(),
                    insetPx = config.insetPx
                )
            } else {
                buildFallbackRegion(
                    index = index,
                    columns = columns,
                    center = center,
                    shape = shape,
                    medianWidth = medianWidth,
                    medianHeight = medianHeight,
                    imageWidth = gray.cols(),
                    imageHeight = gray.rows(),
                    insetPx = config.insetPx,
                    fallbackReason = rejectionReason ?: ArrayUnitFallbackReason.LOW_CONTRAST
                )
            }
        }

        return ArrayUnitSegmentationResult(
            rows = rows,
            columns = columns,
            imageWidth = gray.cols(),
            imageHeight = gray.rows(),
            pitchPx = estimatedPitch,
            medianWidthPx = medianWidth,
            medianHeightPx = medianHeight,
            regions = regions
        ).requireValid()
    }

    /**
     * 在单个晶格点附近执行极性与色彩感知 Otsu 和连通域选择。
     *
     * 借鉴 GenePix Pro 的复合像素强度（CPI）原则：灰度转换 `0.299R+0.587G+0.114B` 会把纯蓝色
     * 荧光衰减近 9 倍，也会漏掉与灰白基底等亮度的黄/洋红显色斑点。因此同时评估有向多通道复合
     * 响应；当常规灰度已具备充分对比度时保持原灰度路径以确保实拍与合成语料逐像素兼容，当色差
     * 或单通道荧光显著强于灰度时自动切换复合平面生成前景掩膜。
     */
    private fun segmentSingleUnit(
        gray: Mat,
        rgbaPixels: IntArray,
        imageWidth: Int,
        center: GridPoint,
        halfWindow: Int,
        polarity: GridTargetPolarity,
        config: ArrayUnitSegmentationConfig
    ): LocalSegmentAttempt {
        val centerX = center.x.roundToInt()
        val centerY = center.y.roundToInt()
        val windowLeft = (centerX - halfWindow).coerceAtLeast(0)
        val windowTop = (centerY - halfWindow).coerceAtLeast(0)
        val windowRight = (centerX + halfWindow + 1).coerceAtMost(gray.cols())
        val windowBottom = (centerY + halfWindow + 1).coerceAtMost(gray.rows())
        val windowWidth = windowRight - windowLeft
        val windowHeight = windowBottom - windowTop
        if (windowWidth * windowHeight < MINIMUM_WINDOW_AREA_PX) {
            return LocalSegmentAttempt(null, ArrayUnitFallbackReason.LOW_CONTRAST, 0.0)
        }

        val window = gray.submat(Rect(windowLeft, windowTop, windowWidth, windowHeight))
        val foreground = Mat()
        val compositeMat = Mat(windowHeight, windowWidth, CvType.CV_8UC1)
        val mask = Mat()
        val opened = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        return try {
            if (polarity == GridTargetPolarity.DARK) {
                Core.bitwise_not(window, foreground)
            } else {
                window.copyTo(foreground)
            }
            val grayExtrema = Core.minMaxLoc(foreground)
            val grayRange = grayExtrema.maxVal - grayExtrema.minVal

            val compositeRange = buildDirectionalCompositePlane(
                rgbaPixels = rgbaPixels,
                imageWidth = imageWidth,
                windowLeft = windowLeft,
                windowTop = windowTop,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                polarity = polarity,
                destination = compositeMat
            )
            val useComposite = compositeRange >= config.minimumContrast &&
                grayRange < compositeRange * GRAY_RETENTION_RATIO
            val activePlane = if (useComposite) compositeMat else foreground
            val effectiveContrast = if (useComposite) compositeRange else grayRange
            if (effectiveContrast < config.minimumContrast) {
                return LocalSegmentAttempt(null, ArrayUnitFallbackReason.LOW_CONTRAST, effectiveContrast)
            }

            Imgproc.threshold(
                activePlane,
                mask,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )
            Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel)
            val componentCount = Imgproc.connectedComponentsWithStats(
                opened,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S
            )
            if (componentCount <= 1) {
                return LocalSegmentAttempt(null, ArrayUnitFallbackReason.SMALL_COMPONENT, effectiveContrast)
            }

            val localX = (centerX - windowLeft).coerceIn(0, windowWidth - 1)
            val localY = (centerY - windowTop).coerceIn(0, windowHeight - 1)
            var chosen = labels.get(localY, localX)?.firstOrNull()?.toInt() ?: 0
            if (chosen == 0) {
                var bestIndex = 0
                var bestDistance = Double.POSITIVE_INFINITY
                for (component in 1 until componentCount) {
                    val area = stat(stats, component, Imgproc.CC_STAT_AREA)
                    if (area < config.minimumComponentAreaPx) continue
                    val centroidX = centroids.get(component, 0)?.firstOrNull() ?: continue
                    val centroidY = centroids.get(component, 1)?.firstOrNull() ?: continue
                    val distance = hypot(centroidX - localX, centroidY - localY)
                    if (distance < bestDistance) {
                        bestIndex = component
                        bestDistance = distance
                    }
                }
                if (bestIndex == 0) {
                    return LocalSegmentAttempt(null, ArrayUnitFallbackReason.SMALL_COMPONENT, effectiveContrast)
                }
                chosen = bestIndex
            }

            val x = stat(stats, chosen, Imgproc.CC_STAT_LEFT)
            val y = stat(stats, chosen, Imgproc.CC_STAT_TOP)
            val width = stat(stats, chosen, Imgproc.CC_STAT_WIDTH)
            val height = stat(stats, chosen, Imgproc.CC_STAT_HEIGHT)
            val area = stat(stats, chosen, Imgproc.CC_STAT_AREA)
            if (area < config.minimumComponentAreaPx) {
                return LocalSegmentAttempt(null, ArrayUnitFallbackReason.SMALL_COMPONENT, effectiveContrast)
            }
            // 触碰局部窗口边界：高对比度下意味着真实反应斑点溢流粘连或跨窗偏离；微弱起伏则归为正常暗孔回退。
            if (x <= 0 || y <= 0 || x + width >= windowWidth || y + height >= windowHeight) {
                val reason = if (effectiveContrast >= config.minimumContrast * STRONG_FEATURE_CONTRAST_MULTIPLIER) {
                    ArrayUnitFallbackReason.WINDOW_BOUNDARY_TOUCH
                } else {
                    ArrayUnitFallbackReason.LOW_CONTRAST
                }
                return LocalSegmentAttempt(null, reason, effectiveContrast)
            }

            val componentMask = ByteArray(width * height)
            for (localRow in 0 until height) {
                for (localColumn in 0 until width) {
                    val label = labels.get(y + localRow, x + localColumn)
                        ?.firstOrNull()?.toInt() ?: 0
                    if (label == chosen) componentMask[localRow * width + localColumn] = 1
                }
            }
            LocalSegmentAttempt(
                segment = RawSegment(
                    bounds = ArrayUnitBounds(
                        left = windowLeft + x,
                        top = windowTop + y,
                        right = windowLeft + x + width,
                        bottom = windowTop + y + height
                    ),
                    componentAreaPx = area,
                    componentMask = componentMask
                ),
                fallbackReason = null,
                contrast = effectiveContrast
            )
        } finally {
            kernel.release()
            centroids.release()
            stats.release()
            labels.release()
            opened.release()
            mask.release()
            compositeMat.release()
            foreground.release()
            window.release()
        }
    }

    /**
     * 以局部窗口边缘 2px 外框的中位颜色作为局部基底背景，构造极性一致的多通道最强响应图。
     *
     * `DARK` 极性提取吸收最强的通道差 $\max(0, B_c - I_c)$，不会把白色高光噪点误识为暗孔；
     * `BRIGHT` 极性提取发射最强的通道差 $\max(0, I_c - B_c)$，可无损捕获纯蓝/纯红/纯绿荧光。
     */
    private fun buildDirectionalCompositePlane(
        rgbaPixels: IntArray,
        imageWidth: Int,
        windowLeft: Int,
        windowTop: Int,
        windowWidth: Int,
        windowHeight: Int,
        polarity: GridTargetPolarity,
        destination: Mat
    ): Double {
        val borderRed = ArrayList<Int>(windowWidth * 4 + windowHeight * 4)
        val borderGreen = ArrayList<Int>(windowWidth * 4 + windowHeight * 4)
        val borderBlue = ArrayList<Int>(windowWidth * 4 + windowHeight * 4)
        val borderBand = minOf(2, minOf(windowWidth, windowHeight) / 4).coerceAtLeast(1)
        for (row in 0 until windowHeight) {
            for (col in 0 until windowWidth) {
                val onBorder = row < borderBand || row >= windowHeight - borderBand ||
                    col < borderBand || col >= windowWidth - borderBand
                if (!onBorder) continue
                val argb = rgbaPixels[(windowTop + row) * imageWidth + (windowLeft + col)]
                borderRed += (argb shr 16) and 0xFF
                borderGreen += (argb shr 8) and 0xFF
                borderBlue += argb and 0xFF
            }
        }
        val bgRed = if (borderRed.isEmpty()) 0 else medianInt(borderRed)
        val bgGreen = if (borderGreen.isEmpty()) 0 else medianInt(borderGreen)
        val bgBlue = if (borderBlue.isEmpty()) 0 else medianInt(borderBlue)

        val buffer = ByteArray(windowWidth * windowHeight)
        var minVal = 255
        var maxVal = 0
        for (row in 0 until windowHeight) {
            val globalRowOffset = (windowTop + row) * imageWidth
            val localRowOffset = row * windowWidth
            for (col in 0 until windowWidth) {
                val argb = rgbaPixels[globalRowOffset + windowLeft + col]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val response = if (polarity == GridTargetPolarity.DARK) {
                    maxOf(0, bgRed - r, bgGreen - g, bgBlue - b)
                } else {
                    maxOf(0, r - bgRed, g - bgGreen, b - bgBlue)
                }.coerceIn(0, 255)
                buffer[localRowOffset + col] = response.toByte()
                if (response < minVal) minVal = response
                if (response > maxVal) maxVal = response
            }
        }
        destination.put(0, 0, buffer)
        return (maxVal - minVal).toDouble()
    }

    /** 第二遍执行中位尺寸、中心偏移和形状专用校验，返回首个未通过的具体原因（通过则为 null）。 */
    private fun rejectionReason(
        raw: RawSegment,
        contrast: Double,
        center: GridPoint,
        medianWidth: Int,
        medianHeight: Int,
        maximumCenterShift: Double,
        shape: ArrayUnitShape,
        config: ArrayUnitSegmentationConfig
    ): ArrayUnitFallbackReason? {
        val width = raw.bounds.width
        val height = raw.bounds.height
        val sizeAccepted = width >= config.minimumSizeMedianRatio * medianWidth &&
            width <= config.maximumSizeMedianRatio * medianWidth &&
            height >= config.minimumSizeMedianRatio * medianHeight &&
            height <= config.maximumSizeMedianRatio * medianHeight
        if (!sizeAccepted) return ArrayUnitFallbackReason.ABNORMAL_SIZE

        val boxCenterX = (raw.bounds.left + raw.bounds.right) / 2.0
        val boxCenterY = (raw.bounds.top + raw.bounds.bottom) / 2.0
        if (hypot(boxCenterX - center.x, boxCenterY - center.y) > maximumCenterShift) {
            return if (contrast >= config.minimumContrast * STRONG_FEATURE_CONTRAST_MULTIPLIER) {
                ArrayUnitFallbackReason.EXCESSIVE_CENTER_SHIFT
            } else {
                ArrayUnitFallbackReason.LOW_CONTRAST
            }
        }

        if (shape == ArrayUnitShape.CIRCLE || shape == ArrayUnitShape.POINT) {
            val aspectRatio = minOf(width, height).toDouble() / maxOf(width, height)
            val fillRatio = raw.componentAreaPx.toDouble() / (width * height).coerceAtLeast(1)
            if (aspectRatio < config.minimumCircleAspectRatio) return ArrayUnitFallbackReason.IRREGULAR_SHAPE
            if (fillRatio !in config.minimumCircleFillRatio..config.maximumCircleFillRatio) {
                return ArrayUnitFallbackReason.IRREGULAR_SHAPE
            }
        }
        return null
    }

    private fun buildSegmentedRegion(
        index: Int,
        columns: Int,
        raw: RawSegment,
        shape: ArrayUnitShape,
        imageWidth: Int,
        imageHeight: Int,
        insetPx: Int
    ): ArrayUnitRegion {
        val bounds = insetBounds(raw.bounds, insetPx, imageWidth, imageHeight)
        val mask = when (shape) {
            // 方形芯片必须保留紧致框内完整表面纹理，不能把 Otsu 纹理孔洞误删成背景。
            ArrayUnitShape.SQUARE -> ArrayUnitBitmapCropper.geometryMask(bounds.width, bounds.height, shape)
            ArrayUnitShape.CIRCLE, ArrayUnitShape.POINT, ArrayUnitShape.CUSTOM -> {
                cropComponentMask(raw, bounds).takeIf { candidate -> candidate.any { it.toInt() != 0 } }
                    ?: ArrayUnitBitmapCropper.geometryMask(bounds.width, bounds.height, shape)
            }
        }
        return ArrayUnitRegion(
            siteIndex = index,
            rowIndex = index / columns,
            columnIndex = index % columns,
            shape = shape,
            bounds = bounds,
            source = ArrayUnitRegionSource.SEGMENTED,
            foregroundMask = mask
        ).requireValid(imageWidth, imageHeight)
    }

    private fun buildFallbackRegion(
        index: Int,
        columns: Int,
        center: GridPoint,
        shape: ArrayUnitShape,
        medianWidth: Int,
        medianHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        insetPx: Int,
        fallbackReason: ArrayUnitFallbackReason = ArrayUnitFallbackReason.LOW_CONTRAST
    ): ArrayUnitRegion {
        val centered = centeredBounds(
            center = center,
            desiredWidth = medianWidth,
            desiredHeight = medianHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        val bounds = insetBounds(centered, insetPx, imageWidth, imageHeight)
        return ArrayUnitRegion(
            siteIndex = index,
            rowIndex = index / columns,
            columnIndex = index % columns,
            shape = shape,
            bounds = bounds,
            source = ArrayUnitRegionSource.FALLBACK_MEDIAN_GEOMETRY,
            foregroundMask = ArrayUnitBitmapCropper.geometryMask(bounds.width, bounds.height, shape),
            fallbackReason = fallbackReason
        ).requireValid(imageWidth, imageHeight)
    }

    /** 把原始连通域掩膜裁到 inset 后的最终边界。 */
    private fun cropComponentMask(raw: RawSegment, target: ArrayUnitBounds): ByteArray {
        val offsetX = target.left - raw.bounds.left
        val offsetY = target.top - raw.bounds.top
        return ByteArray(target.width * target.height) { index ->
            val x = index % target.width + offsetX
            val y = index / target.width + offsetY
            raw.componentMask[y * raw.bounds.width + x]
        }
    }

    private fun insetBounds(
        source: ArrayUnitBounds,
        insetPx: Int,
        imageWidth: Int,
        imageHeight: Int
    ): ArrayUnitBounds {
        val maximumInsetX = ((source.width - 1) / 2).coerceAtLeast(0)
        val maximumInsetY = ((source.height - 1) / 2).coerceAtLeast(0)
        val insetX = insetPx.coerceAtMost(maximumInsetX)
        val insetY = insetPx.coerceAtMost(maximumInsetY)
        return ArrayUnitBounds(
            left = (source.left + insetX).coerceIn(0, imageWidth - 1),
            top = (source.top + insetY).coerceIn(0, imageHeight - 1),
            right = (source.right - insetX).coerceIn(source.left + insetX + 1, imageWidth),
            bottom = (source.bottom - insetY).coerceIn(source.top + insetY + 1, imageHeight)
        )
    }

    /** 保持目标尺寸优先，把中位几何盒平移到图像内部。 */
    private fun centeredBounds(
        center: GridPoint,
        desiredWidth: Int,
        desiredHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): ArrayUnitBounds {
        val width = desiredWidth.coerceIn(1, imageWidth)
        val height = desiredHeight.coerceIn(1, imageHeight)
        var left = (center.x - width / 2.0).roundToInt()
        var top = (center.y - height / 2.0).roundToInt()
        left = left.coerceIn(0, imageWidth - width)
        top = top.coerceIn(0, imageHeight - height)
        return ArrayUnitBounds(left, top, left + width, top + height)
    }

    private fun stat(stats: Mat, component: Int, field: Int): Int {
        return stats.get(component, field)?.firstOrNull()?.roundToInt() ?: 0
    }

    private fun medianInt(values: List<Int>): Int {
        require(values.isNotEmpty()) { "中位数输入不能为空" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1] + sorted[middle]) / 2.0).roundToInt()
    }

    private data class LocalSegmentAttempt(
        val segment: RawSegment?,
        val fallbackReason: ArrayUnitFallbackReason?,
        val contrast: Double
    )

    private data class RawSegment(
        val bounds: ArrayUnitBounds,
        val componentAreaPx: Int,
        val componentMask: ByteArray
    )

    private companion object {
        const val MINIMUM_VALID_PITCH: Double = 4.0
        const val MINIMUM_WINDOW_HALF_PX: Int = 6
        const val MINIMUM_WINDOW_AREA_PX: Int = 25
        const val DEFAULT_FALLBACK_SIZE_PITCH_RATIO: Double = 0.5
        /** 灰度对比度已达到复合色差对比度的 75% 时保留原灰度路径，保证实拍回归逐像素一致。 */
        const val GRAY_RETENTION_RATIO: Double = 0.75
        /** 局部对比度达到最小阈值 2 倍以上仍触边或大幅偏心，才判定为强斑点溢流/错位异常。 */
        const val STRONG_FEATURE_CONTRAST_MULTIPLIER: Double = 2.0
    }
}
