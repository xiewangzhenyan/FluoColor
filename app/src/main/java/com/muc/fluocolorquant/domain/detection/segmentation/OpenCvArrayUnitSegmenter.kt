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

        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(rectifiedBitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            segmentGray(
                gray = gray,
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

    private fun segmentGray(
        gray: Mat,
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
        val rawSegments = points.map { center ->
            segmentSingleUnit(
                gray = gray,
                center = center,
                halfWindow = halfWindow,
                polarity = polarity,
                config = config
            )
        }
        val widths = rawSegments.mapNotNull { it?.bounds?.width }
        val heights = rawSegments.mapNotNull { it?.bounds?.height }
        val medianWidth = if (widths.isNotEmpty()) medianInt(widths) else {
            maxOf(MINIMUM_WINDOW_HALF_PX, (estimatedPitch * DEFAULT_FALLBACK_SIZE_PITCH_RATIO).roundToInt())
        }
        val medianHeight = if (heights.isNotEmpty()) medianInt(heights) else {
            maxOf(MINIMUM_WINDOW_HALF_PX, (estimatedPitch * DEFAULT_FALLBACK_SIZE_PITCH_RATIO).roundToInt())
        }

        val regions = points.mapIndexed { index, center ->
            val raw = rawSegments[index]
            val accepted = raw != null && isAccepted(
                raw = raw,
                center = center,
                medianWidth = medianWidth,
                medianHeight = medianHeight,
                maximumCenterShift = maximumCenterShift,
                shape = shape,
                config = config
            )
            if (accepted) {
                buildSegmentedRegion(
                    index = index,
                    columns = columns,
                    raw = requireNotNull(raw),
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
                    insetPx = config.insetPx
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

    /** 在单个晶格点附近执行极性感知 Otsu 和连通域选择。 */
    private fun segmentSingleUnit(
        gray: Mat,
        center: GridPoint,
        halfWindow: Int,
        polarity: GridTargetPolarity,
        config: ArrayUnitSegmentationConfig
    ): RawSegment? {
        val centerX = center.x.roundToInt()
        val centerY = center.y.roundToInt()
        val windowLeft = (centerX - halfWindow).coerceAtLeast(0)
        val windowTop = (centerY - halfWindow).coerceAtLeast(0)
        val windowRight = (centerX + halfWindow + 1).coerceAtMost(gray.cols())
        val windowBottom = (centerY + halfWindow + 1).coerceAtMost(gray.rows())
        val windowWidth = windowRight - windowLeft
        val windowHeight = windowBottom - windowTop
        if (windowWidth * windowHeight < MINIMUM_WINDOW_AREA_PX) return null

        val window = gray.submat(Rect(windowLeft, windowTop, windowWidth, windowHeight))
        val foreground = Mat()
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
            val extrema = Core.minMaxLoc(foreground)
            if (extrema.maxVal - extrema.minVal < config.minimumContrast) return null

            Imgproc.threshold(
                foreground,
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
            if (componentCount <= 1) return null

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
                if (bestIndex == 0) return null
                chosen = bestIndex
            }

            val x = stat(stats, chosen, Imgproc.CC_STAT_LEFT)
            val y = stat(stats, chosen, Imgproc.CC_STAT_TOP)
            val width = stat(stats, chosen, Imgproc.CC_STAT_WIDTH)
            val height = stat(stats, chosen, Imgproc.CC_STAT_HEIGHT)
            val area = stat(stats, chosen, Imgproc.CC_STAT_AREA)
            if (area < config.minimumComponentAreaPx) return null
            // 触碰局部窗口边界通常意味着抓到了跨窗背景结构，不能把它当作单元本体。
            if (x <= 0 || y <= 0 || x + width >= windowWidth || y + height >= windowHeight) return null

            val componentMask = ByteArray(width * height)
            for (localRow in 0 until height) {
                for (localColumn in 0 until width) {
                    val label = labels.get(y + localRow, x + localColumn)
                        ?.firstOrNull()?.toInt() ?: 0
                    if (label == chosen) componentMask[localRow * width + localColumn] = 1
                }
            }
            RawSegment(
                bounds = ArrayUnitBounds(
                    left = windowLeft + x,
                    top = windowTop + y,
                    right = windowLeft + x + width,
                    bottom = windowTop + y + height
                ),
                componentAreaPx = area,
                componentMask = componentMask
            )
        } finally {
            kernel.release()
            centroids.release()
            stats.release()
            labels.release()
            opened.release()
            mask.release()
            foreground.release()
            window.release()
        }
    }

    /** 第二遍执行中位尺寸、中心偏移和形状专用校验。 */
    private fun isAccepted(
        raw: RawSegment,
        center: GridPoint,
        medianWidth: Int,
        medianHeight: Int,
        maximumCenterShift: Double,
        shape: ArrayUnitShape,
        config: ArrayUnitSegmentationConfig
    ): Boolean {
        val width = raw.bounds.width
        val height = raw.bounds.height
        val sizeAccepted = width >= config.minimumSizeMedianRatio * medianWidth &&
            width <= config.maximumSizeMedianRatio * medianWidth &&
            height >= config.minimumSizeMedianRatio * medianHeight &&
            height <= config.maximumSizeMedianRatio * medianHeight
        if (!sizeAccepted) return false

        val boxCenterX = (raw.bounds.left + raw.bounds.right) / 2.0
        val boxCenterY = (raw.bounds.top + raw.bounds.bottom) / 2.0
        if (hypot(boxCenterX - center.x, boxCenterY - center.y) > maximumCenterShift) return false

        if (shape == ArrayUnitShape.CIRCLE || shape == ArrayUnitShape.POINT) {
            val aspectRatio = minOf(width, height).toDouble() / maxOf(width, height)
            val fillRatio = raw.componentAreaPx.toDouble() / (width * height).coerceAtLeast(1)
            if (aspectRatio < config.minimumCircleAspectRatio) return false
            if (fillRatio !in config.minimumCircleFillRatio..config.maximumCircleFillRatio) return false
        }
        return true
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
        insetPx: Int
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
            foregroundMask = ArrayUnitBitmapCropper.geometryMask(bounds.width, bounds.height, shape)
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
    }
}
