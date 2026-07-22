package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * 基于用户 Python PG-Grid 参考实现移植的 OpenCV 微流控定位器。
 *
 * 实现坚持“物理规则晶格优先”：OpenCV 只负责芯片区域和局部候选证据，最终点位由
 * 全局单应晶格约束；没有局部证据的位点明确标记为模型补位。该类不执行任何比色或
 * 荧光增强，防止显示处理污染后续科学定量。
 */
class OpenCvPgGridLocator @Inject constructor() : PgGridLocator {

    override fun locate(bitmap: Bitmap, config: PgGridLocatorConfig): PgGridResult {
        require(bitmap.width > 0 && bitmap.height > 0) { "输入图片尺寸无效" }

        val rgba = Mat()
        val originalBgr = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            when (rgba.channels()) {
                4 -> Imgproc.cvtColor(rgba, originalBgr, Imgproc.COLOR_RGBA2BGR)
                3 -> rgba.copyTo(originalBgr)
                1 -> Imgproc.cvtColor(rgba, originalBgr, Imgproc.COLOR_GRAY2BGR)
                else -> error("不支持的图片通道数：${rgba.channels()}")
            }

            val region = detectChipRegion(originalBgr)
            val rectifiedWidth = config.resolvedRectifiedWidth()
            val rectifiedHeight = config.resolvedRectifiedHeight()
            val rectification = rectifyChip(
                image = originalBgr,
                region = region,
                outputWidth = rectifiedWidth,
                outputHeight = rectifiedHeight
            )
            try {
                val polarityFit = fitLatticeWithAutomaticPolarity(
                    rectified = rectification.image,
                    rows = config.rows,
                    columns = config.columns,
                    width = rectifiedWidth,
                    height = rectifiedHeight,
                    marginRatio = config.marginRatio,
                    maximumResidualPitchRatio = config.maximumResidualPitchRatio,
                    supportDistancePitchRatio = config.candidateSupportDistancePitchRatio,
                    preferredPolarity = config.targetPolarity
                )
                val actualPolarity = polarityFit.polarity
                val lattice = polarityFit.lattice

                val inverseMatrix = matToRowMajor(rectification.inverseMatrix)
                val localizedSites = lattice.points.mapIndexed { index, localizedPoint ->
                    val key = GridSiteKey(
                        rowIndex = index / config.columns,
                        columnIndex = index % config.columns
                    )
                    val originalPoint = RegularGridGeometry.project(
                        inverseMatrix,
                        localizedPoint.point
                    )
                    when (localizedPoint.source) {
                        GridPointSource.MODEL_IMPUTED -> GridLocalizedSite.modelImputed(
                            key = key,
                            siteIndex = index,
                            rectified = localizedPoint.point,
                            original = originalPoint
                        )

                        else -> GridLocalizedSite(
                            key = key,
                            siteIndex = index,
                            rectified = localizedPoint.point,
                            original = originalPoint,
                            confidence = localizedPoint.confidence,
                            source = localizedPoint.source,
                            flags = localizedPoint.flags
                        )
                    }
                }
                val frameQuality = evaluateFrameQuality(
                    rectified = rectification.image,
                    lattice = lattice,
                    polarity = actualPolarity,
                    rows = config.rows,
                    columns = config.columns
                )
                Log.d(
                    FRAME_QUALITY_LOG_TAG,
                    "rows=${config.rows} columns=${config.columns} polarity=$actualPolarity " +
                        "saturation=${frameQuality.saturationRatio} " +
                        "underRatio=${frameQuality.underExposureRatio} " +
                        "grayP01=${frameQuality.grayP01} grayP99=${frameQuality.grayP99} " +
                        "laplacianVariance=${frameQuality.laplacianVariance} " +
                        "normalizedSharpness=${frameQuality.normalizedSharpness} " +
                        "illuminationVariation=${frameQuality.illuminationVariation} " +
                        "perspectiveVariation=${frameQuality.perspectiveVariation} " +
                        "geometryRmsePitch=${frameQuality.geometryRmsePitchRatio}"
                )
                val frameQc = buildFrameQc(region, lattice, frameQuality)

                return PgGridResult(
                    rows = config.rows,
                    columns = config.columns,
                    rectifiedWidth = rectifiedWidth,
                    rectifiedHeight = rectifiedHeight,
                    targetPolarity = actualPolarity,
                    chipRegionMethod = region.method,
                    chipCorners = region.points,
                    homography = GridHomography(
                        forward = matToRowMajor(rectification.forwardMatrix).toList(),
                        inverse = inverseMatrix.toList()
                    ),
                    sites = localizedSites,
                    geometry = GridGeometryDiagnostics(
                        candidateSupportRatio = lattice.candidateSupportRatio,
                        trusted = lattice.trusted,
                        observedRatio = lattice.observedRatio,
                        geometryRmsePx = lattice.geometryRmsePx,
                        inlierCount = lattice.inlierCount,
                        outlierCount = lattice.outlierCount,
                        meanConfidence = localizedSites.map(GridLocalizedSite::confidence).average()
                    ),
                    frameQc = frameQc,
                    locatorName = LOCATOR_NAME,
                    locatorVersion = LOCATOR_VERSION
                ).requireValid()
            } finally {
                rectification.release()
            }
        } finally {
            rgba.release()
            originalBgr.release()
        }
    }

    /**
     * 两档芯片区域检测：先尝试高分位亮区，再用 Otsu 宽区域兜底。
     * 两档都失败时只返回中央区域并产生 QC 警告，不把兜底伪装成可靠芯片检测。
     */
    private fun detectChipRegion(image: Mat): ChipRegionCandidate {
        val height = image.rows()
        val width = image.cols()
        val gray = Mat()
        val blurred = Mat()
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 0.0)
            val percentileThreshold = percentile(blurred, 0.94)
            val otsuMask = Mat()
            val otsuThreshold = try {
                Imgproc.threshold(
                    blurred,
                    otsuMask,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
                )
            } finally {
                otsuMask.release()
            }

            val attempts = listOf(
                RegionAttempt(
                    threshold = maxOf(MIN_BRIGHTNESS_THRESHOLD, percentileThreshold),
                    maximumAreaRatio = 0.20,
                    method = "opencv_bright_region"
                ),
                RegionAttempt(
                    threshold = maxOf(
                        MIN_BRIGHTNESS_THRESHOLD,
                        minOf(percentileThreshold, otsuThreshold)
                    ),
                    maximumAreaRatio = 0.65,
                    method = "opencv_bright_region_wide"
                )
            )

            val kernelSize = oddAtLeast(
                minimum = 7,
                raw = (minOf(width, height) * 0.008).toInt()
            )
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                attempts.forEach { attempt ->
                    findBestRegionContour(
                        gray = gray,
                        blurred = blurred,
                        kernel = kernel,
                        width = width,
                        height = height,
                        attempt = attempt
                    )?.let { points ->
                        return ChipRegionCandidate(
                            points = expandAndClip(points, width, height, 1.10),
                            method = attempt.method
                        )
                    }
                }
            } finally {
                kernel.release()
            }
        } finally {
            gray.release()
            blurred.release()
        }
        return fallbackCenterRegion(width, height)
    }

    /** 为某一阈值档位选择面积与亮度综合得分最高的主区域。 */
    private fun findBestRegionContour(
        gray: Mat,
        blurred: Mat,
        kernel: Mat,
        width: Int,
        height: Int,
        attempt: RegionAttempt
    ): List<GridPoint>? {
        val mask = Mat()
        val hierarchy = Mat()
        val contours = arrayListOf<MatOfPoint>()
        try {
            Imgproc.threshold(blurred, mask, attempt.threshold, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(mask, mask, kernel)
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = width.toDouble() * height
            var bestScore = Double.NEGATIVE_INFINITY
            var bestPoints: Array<Point>? = null
            contours.forEach { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < imageArea * 0.002 || area > imageArea * attempt.maximumAreaRatio) {
                    return@forEach
                }
                val bounds = Imgproc.boundingRect(contour)
                val aspect = bounds.width.toDouble() / maxOf(bounds.height, 1)
                if (aspect !in 0.35..2.8) return@forEach

                val contourMask = Mat.zeros(gray.size(), CvType.CV_8UC1)
                val meanBrightness = try {
                    Imgproc.drawContours(contourMask, listOf(contour), -1, Scalar(255.0), -1)
                    Core.mean(gray, contourMask).`val`[0]
                } finally {
                    contourMask.release()
                }
                val score = sqrt(area) * (meanBrightness + 1.0)
                if (score > bestScore) {
                    bestScore = score
                    bestPoints = contour.toArray()
                }
            }

            val points = bestPoints ?: return null
            val contour2f = MatOfPoint2f(*points)
            return try {
                orderQuad(Imgproc.minAreaRect(contour2f))
            } finally {
                contour2f.release()
            }
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            mask.release()
        }
    }

    /** 把旋转矩形四角排序为左上、右上、右下、左下。 */
    private fun orderQuad(rectangle: RotatedRect): List<GridPoint> {
        val raw = Array(4) { Point() }
        rectangle.points(raw)
        val points = raw.map { GridPoint(it.x, it.y) }
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.maxBy { it.x - it.y }
        val bottomLeft = points.minBy { it.x - it.y }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun expandAndClip(
        points: List<GridPoint>,
        width: Int,
        height: Int,
        scale: Double
    ): List<GridPoint> {
        val centerX = points.map(GridPoint::x).average()
        val centerY = points.map(GridPoint::y).average()
        return points.map { point ->
            GridPoint(
                x = (centerX + (point.x - centerX) * scale).coerceIn(0.0, width - 1.0),
                y = (centerY + (point.y - centerY) * scale).coerceIn(0.0, height - 1.0)
            )
        }
    }

    private fun fallbackCenterRegion(width: Int, height: Int): ChipRegionCandidate {
        val sideWidth = width * 0.45
        val sideHeight = height * 0.45
        val centerX = width / 2.0
        val centerY = height / 2.0
        return ChipRegionCandidate(
            points = listOf(
                GridPoint(centerX - sideWidth / 2.0, centerY - sideHeight / 2.0),
                GridPoint(centerX + sideWidth / 2.0, centerY - sideHeight / 2.0),
                GridPoint(centerX + sideWidth / 2.0, centerY + sideHeight / 2.0),
                GridPoint(centerX - sideWidth / 2.0, centerY + sideHeight / 2.0)
            ),
            method = "fallback_center"
        )
    }

    /** 根据芯片四角生成原图→矫正图和矫正图→原图两组矩阵。 */
    private fun rectifyChip(
        image: Mat,
        region: ChipRegionCandidate,
        outputWidth: Int,
        outputHeight: Int
    ): RectificationResult {
        val source = MatOfPoint2f(*region.points.map { Point(it.x, it.y) }.toTypedArray())
        val destination = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outputWidth - 1.0, 0.0),
            Point(outputWidth - 1.0, outputHeight - 1.0),
            Point(0.0, outputHeight - 1.0)
        )
        try {
            val forward = Imgproc.getPerspectiveTransform(source, destination)
            val inverse = Imgproc.getPerspectiveTransform(destination, source)
            val rectified = Mat()
            Imgproc.warpPerspective(
                image,
                rectified,
                forward,
                Size(outputWidth.toDouble(), outputHeight.toDouble()),
                Imgproc.INTER_CUBIC
            )
            return RectificationResult(rectified, forward, inverse)
        } finally {
            source.release()
            destination.release()
        }
    }

    /** black-hat 增强“小而暗”的方形反应区，再按面积、尺寸和长宽比过滤。 */
    private fun detectDarkSquareCandidates(rectified: Mat): List<GridCandidate> {
        val gray = Mat()
        val response = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            val side = minOf(gray.cols(), gray.rows())
            val kernelSize = oddAtLeast(31, (side * 0.065).toInt())
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(gray, response, Imgproc.MORPH_BLACKHAT, kernel)
            } finally {
                kernel.release()
            }
            Imgproc.threshold(
                response,
                mask,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )
            return connectedComponentCandidates(
                response = response,
                mask = mask,
                minimumBox = maxOf(8, (side * 0.010).toInt()),
                maximumBox = maxOf(24, (side * 0.055).toInt()),
                minimumArea = maxOf(50, (side * side * 0.00012).toInt()),
                maximumArea = maxOf(450, (side * side * 0.00120).toInt())
            )
        } finally {
            gray.release()
            response.release()
            mask.release()
        }
    }

    /** top-hat 增强“小而亮”的点阵，Otsu 阈值只从内部区域估计以避开边缘亮带。 */
    private fun detectBrightDotCandidates(rectified: Mat): List<GridCandidate> {
        val gray = Mat()
        val response = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            val side = minOf(gray.cols(), gray.rows())
            val kernelSize = oddAtLeast(25, (side * 0.050).toInt())
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(gray, response, Imgproc.MORPH_TOPHAT, kernel)
            } finally {
                kernel.release()
            }

            val x0 = (response.cols() * 0.10).toInt()
            val x1 = (response.cols() * 0.90).toInt()
            val y0 = (response.rows() * 0.10).toInt()
            val y1 = (response.rows() * 0.90).toInt()
            val interior = response.submat(y0, y1, x0, x1)
            val thresholdProbe = Mat()
            val otsu = try {
                Imgproc.threshold(
                    interior,
                    thresholdProbe,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
                )
            } finally {
                thresholdProbe.release()
                interior.release()
            }
            Imgproc.threshold(response, mask, otsu, 255.0, Imgproc.THRESH_BINARY)
            return connectedComponentCandidates(
                response = response,
                mask = mask,
                minimumBox = maxOf(5, (side * 0.006).toInt()),
                maximumBox = maxOf(16, (side * 0.045).toInt()),
                minimumArea = maxOf(20, (side * side * 0.00004).toInt()),
                maximumArea = maxOf(240, (side * side * 0.00110).toInt())
            )
        } finally {
            gray.release()
            response.release()
            mask.release()
        }
    }

    /**
     * 同时尝试暗目标与亮目标，并用真实候选晶格是否成立裁决单元极性。
     *
     * `targetPolarity` 过去被当成由网格规格决定的硬参数，导致 15×15 EL 背光实拍图被
     * 强制按亮点处理。实际极性取决于成像方式：同一规格既可能是亮背景上的暗单元，也
     * 可能是暗背景上的亮单元。这里与 Python 参考实现保持同一决策顺序：
     *
     * 1. 暗/亮候选都提取，候选数量越接近理论位点数越优先；
     * 2. 数量接近时使用内部灰度“均值与中位数”提示消除互补晶格歧义；
     * 3. 只有候选轴与稳健单应真正成立才立即裁决；
     * 4. 两侧候选都无法形成晶格时，只使用统计提示的一侧走投影兜底，避免反极性的
     *    规则间隙产生看似可信、实际偏移半格的结果。
     *
     * [preferredPolarity] 仅作为证据完全并列时的最后偏好，保留旧项目兼容性，但不能再
     * 覆盖图像本身给出的极性证据。
     */
    private fun fitLatticeWithAutomaticPolarity(
        rectified: Mat,
        rows: Int,
        columns: Int,
        width: Int,
        height: Int,
        marginRatio: Double,
        maximumResidualPitchRatio: Double,
        supportDistancePitchRatio: Double,
        preferredPolarity: GridTargetPolarity
    ): PolarityFit {
        val expectedCount = rows * columns
        val statisticalHint = estimatePolarityHint(rectified)
        val options = listOf(
            PolarityCandidates(
                polarity = GridTargetPolarity.DARK,
                candidates = detectDarkSquareCandidates(rectified)
            ),
            PolarityCandidates(
                polarity = GridTargetPolarity.BRIGHT,
                candidates = detectBrightDotCandidates(rectified)
            )
        ).sortedWith(
            compareBy<PolarityCandidates> { option ->
                abs(option.candidates.size - expectedCount)
            }.thenBy { option ->
                if (option.polarity == statisticalHint) 0 else 1
            }.thenBy { option ->
                if (option.polarity == preferredPolarity) 0 else 1
            }
        )

        var hintFallback: LatticeFit? = null
        options.forEach { option ->
            val fitted = fitLattice(
                rectified = rectified,
                candidates = option.candidates,
                polarity = option.polarity,
                rows = rows,
                columns = columns,
                width = width,
                height = height,
                marginRatio = marginRatio,
                maximumResidualPitchRatio = maximumResidualPitchRatio,
                supportDistancePitchRatio = supportDistancePitchRatio
            )
            if (option.polarity == statisticalHint) hintFallback = fitted
            if (fitted.candidateLatticeApplied) {
                return PolarityFit(
                    polarity = option.polarity,
                    lattice = recoverLocalEvidence(
                        rectified = rectified,
                        lattice = fitted,
                        polarity = option.polarity,
                        rows = rows,
                        columns = columns
                    )
                )
            }
        }

        val fallback = requireNotNull(hintFallback) {
            "暗/亮候选列表必须包含统计提示对应的极性"
        }
        return PolarityFit(
            polarity = statisticalHint,
            lattice = recoverLocalEvidence(
                rectified = rectified,
                lattice = fallback,
                polarity = statisticalHint,
                rows = rows,
                columns = columns
            )
        )
    }

    /**
     * 用阵列内部区域的灰度分布估计极性提示。
     *
     * 单元只占面板少数面积，背景主导中位数；暗单元会把均值向低灰度方向拉动，亮单元
     * 则相反。忽略外围 10% 可避免透视矫正边带、边框和固定装置干扰统计。
     */
    private fun estimatePolarityHint(rectified: Mat): GridTargetPolarity {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            val x0 = (gray.cols() * 0.10).toInt()
            val x1 = (gray.cols() * 0.90).toInt().coerceAtLeast(x0 + 1)
            val y0 = (gray.rows() * 0.10).toInt()
            val y1 = (gray.rows() * 0.90).toInt().coerceAtLeast(y0 + 1)
            val interior = if (x1 <= gray.cols() && y1 <= gray.rows()) {
                gray.submat(y0, y1, x0, x1)
            } else {
                gray
            }
            return try {
                val mean = Core.mean(interior).`val`[0]
                val median = percentile(interior, 0.50)
                if (mean < median) GridTargetPolarity.DARK else GridTargetPolarity.BRIGHT
            } finally {
                if (interior !== gray) interior.release()
            }
        } finally {
            gray.release()
        }
    }

    /** 从二值连通域提取候选中心及形态响应权重。 */
    private fun connectedComponentCandidates(
        response: Mat,
        mask: Mat,
        minimumBox: Int,
        maximumBox: Int,
        minimumArea: Int,
        maximumArea: Int
    ): List<GridCandidate> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val componentCount = Imgproc.connectedComponentsWithStats(
                mask,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S
            )
            val width = mask.cols()
            val height = mask.rows()
            val edgeGuardX = width * 0.035
            val edgeGuardY = height * 0.035
            val candidates = mutableListOf<GridCandidate>()

            for (index in 1 until componentCount) {
                val componentWidth = stats.get(index, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val componentHeight = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(index, Imgproc.CC_STAT_AREA)[0].toInt()
                val centerX = centroids.get(index, 0)[0]
                val centerY = centroids.get(index, 1)[0]
                val aspect = componentWidth.toDouble() / maxOf(componentHeight, 1)

                if (area !in minimumArea..maximumArea) continue
                if (componentWidth !in minimumBox..maximumBox) continue
                if (componentHeight !in minimumBox..maximumBox) continue
                if (aspect !in 0.45..1.80) continue
                if (centerX !in edgeGuardX..(width - edgeGuardX)) continue
                if (centerY !in edgeGuardY..(height - edgeGuardY)) continue

                val componentMask = Mat()
                val meanResponse = try {
                    Core.compare(labels, Scalar(index.toDouble()), componentMask, Core.CMP_EQ)
                    Core.mean(response, componentMask).`val`[0]
                } finally {
                    componentMask.release()
                }
                val weight = maxOf(1e-6, meanResponse * sqrt(area.toDouble()))
                candidates += GridCandidate(GridPoint(centerX, centerY), weight)
            }
            return candidates
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    /**
     * 当连通域候选不足或轴组合无法成立时，从整幅形态能量投影恢复初始规则网格。
     *
     * 模糊会把暗方块的黑帽连通域面积扩大，Python/OpenCV 与 Android/OpenCV 在 Otsu
     * 边界像素上又可能产生少量版本差异，因此不能把“候选数不足”直接等价为“完全
     * 没有图像证据”。投影路径先扣除大尺度背景，再从行列累计能量中寻找规则峰；
     * 它只负责给后续局部精修一个足够接近的初值，不直接作为最终科学定位结果。
     */
    private fun fitProjectionGrid(
        rectified: Mat,
        polarity: GridTargetPolarity,
        rows: Int,
        columns: Int,
        marginRatio: Double
    ): List<GridPoint>? {
        val gray = Mat()
        val energy = Mat()
        val background = Mat()
        val enhanced = Mat()
        val xProjection = Mat()
        val yProjection = Mat()
        try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            gray.convertTo(energy, CvType.CV_32F)
            if (polarity == GridTargetPolarity.DARK) {
                // 暗目标转成高能量，便于与亮点共用后续背景扣除与投影逻辑。
                Core.multiply(energy, Scalar(-1.0), energy)
                Core.add(energy, Scalar(255.0), energy)
            }

            val blurSize = oddAtLeast(
                minimum = 31,
                raw = (minOf(gray.cols(), gray.rows()) * PROJECTION_BACKGROUND_BLUR_RATIO).toInt()
            )
            Imgproc.GaussianBlur(
                energy,
                background,
                Size(blurSize.toDouble(), blurSize.toDouble()),
                0.0
            )
            Core.subtract(energy, background, enhanced)
            Core.normalize(enhanced, enhanced, 0.0, 255.0, Core.NORM_MINMAX)
            Core.reduce(enhanced, xProjection, 0, Core.REDUCE_SUM, CvType.CV_32F)
            Core.reduce(enhanced, yProjection, 1, Core.REDUCE_SUM, CvType.CV_32F)

            val xs = findProjectionAxisCenters(
                projection = xProjection,
                count = columns,
                length = gray.cols(),
                marginRatio = marginRatio,
                horizontal = true
            ) ?: return null
            val ys = findProjectionAxisCenters(
                projection = yProjection,
                count = rows,
                length = gray.rows(),
                marginRatio = marginRatio,
                horizontal = false
            ) ?: return null
            return buildList(rows * columns) {
                ys.forEach { y -> xs.forEach { x -> add(GridPoint(x, y)) } }
            }
        } finally {
            gray.release()
            energy.release()
            background.release()
            enhanced.release()
            xProjection.release()
            yProjection.release()
        }
    }

    /** 从一维投影中贪心提取强峰，再交给规则组合搜索排除边缘光晕峰。 */
    private fun findProjectionAxisCenters(
        projection: Mat,
        count: Int,
        length: Int,
        marginRatio: Double,
        horizontal: Boolean
    ): List<Double>? {
        if (length < count * 2) return null
        val kernelSize = oddAtLeast(
            minimum = 5,
            raw = length / maxOf(count * PROJECTION_SMOOTHING_DIVISOR, 1)
        )
        val smooth = Mat()
        try {
            Imgproc.GaussianBlur(
                projection,
                smooth,
                if (horizontal) Size(kernelSize.toDouble(), 1.0) else Size(1.0, kernelSize.toDouble()),
                0.0
            )
            val values = DoubleArray(length) { index ->
                if (horizontal) smooth.get(0, index)[0] else smooth.get(index, 0)[0]
            }
            val start = (length * marginRatio * 0.5).toInt().coerceIn(0, length - 1)
            val endExclusive = (length * (1.0 - marginRatio * 0.5)).toInt()
                .coerceIn(start + 1, length)
            val expectedPitch = length * (1.0 - 2.0 * marginRatio) / maxOf(count - 1, 1)
            val minimumDistance = maxOf(3.0, expectedPitch * PROJECTION_MINIMUM_PEAK_DISTANCE_PITCH)
            val selected = mutableListOf<Double>()
            for (index in (start until endExclusive).sortedByDescending { values[it] }) {
                if (selected.all { existing -> abs(index - existing) >= minimumDistance }) {
                    selected += index.toDouble()
                }
                if (selected.size >= count + PROJECTION_EXTRA_PEAK_COUNT) break
            }
            if (selected.size < count) return null

            val peakClusters = selected.sorted().map { peak ->
                AxisCluster(
                    center = peak,
                    support = maxOf(values[peak.roundToInt()], 1e-6),
                    memberCount = 1
                )
            }
            selectRegularAxisFromClusters(peakClusters, count, length)?.let { return it }

            // 与参考实现一致保留保守兜底：若组合搜索失败，取最强峰并检查间距变异。
            val strongest = selected.take(count).sorted()
            if (count > 2) {
                val spacings = strongest.zipWithNext { first, second -> second - first }
                val mean = spacings.average()
                val standardDeviation = sqrt(spacings.map { (it - mean).pow(2) }.average())
                if (standardDeviation / maxOf(mean, 1e-6) > MAX_PROJECTION_SPACING_CV) return null
            }
            return strongest
        } finally {
            smooth.release()
        }
    }

    /**
     * 从候选点建立规则轴，再用 Tukey 单应平差吸收残余旋转、剪切和透视。
     * 候选不足时仍输出固定点数，但全部标记 unadjusted 且 trusted=false。
     */
    private fun fitLattice(
        rectified: Mat,
        candidates: List<GridCandidate>,
        polarity: GridTargetPolarity,
        rows: Int,
        columns: Int,
        width: Int,
        height: Int,
        marginRatio: Double,
        maximumResidualPitchRatio: Double,
        supportDistancePitchRatio: Double
    ): LatticeFit {
        val expectedCount = rows * columns
        fun fallbackPoints(): List<GridPoint> {
            return fitProjectionGrid(
                rectified = rectified,
                polarity = polarity,
                rows = rows,
                columns = columns,
                marginRatio = marginRatio
            ) ?: RegularGridGeometry.generate(
                rows = rows,
                columns = columns,
                width = width.toDouble(),
                height = height.toDouble(),
                marginRatio = marginRatio
            )
        }
        if (candidates.size < maxOf(4, ceil(expectedCount * MINIMUM_OBSERVED_RATIO).toInt())) {
            return unadjustedLattice(
                fallbackPoints(),
                candidates,
                rows,
                columns,
                supportDistancePitchRatio
            )
        }

        val alignmentAngle = estimateLatticeRotation(candidates, width, height)
        val alignedCandidates = candidates.map { candidate ->
            candidate.copy(point = rotate(candidate.point, alignmentAngle, width / 2.0, height / 2.0))
        }
        val xCenters = clusterAxis(
            values = alignedCandidates.map { it.point.x },
            weights = alignedCandidates.map(GridCandidate::weight),
            count = columns,
            length = width
        ) ?: return unadjustedLattice(
            fallbackPoints(),
            candidates,
            rows,
            columns,
            supportDistancePitchRatio
        )
        val yCenters = clusterAxis(
            values = alignedCandidates.map { it.point.y },
            weights = alignedCandidates.map(GridCandidate::weight),
            count = rows,
            length = height
        ) ?: return unadjustedLattice(
            fallbackPoints(),
            candidates,
            rows,
            columns,
            supportDistancePitchRatio
        )

        val alignedGrid = buildList(expectedCount) {
            yCenters.forEach { y -> xCenters.forEach { x -> add(GridPoint(x, y)) } }
        }
        val initialGrid = alignedGrid.map { rotate(it, -alignmentAngle, width / 2.0, height / 2.0) }
        val pitch = RegularGridGeometry.estimatePitch(initialGrid, rows, columns)
        val matchingPitch = minimumPositivePitch(pitch, width, height, rows, columns)
        val matchingThreshold = matchingPitch * supportDistancePitchRatio
        val observations = matchCandidates(initialGrid, candidates, matchingThreshold)
        val minimumRequired = maxOf(4, ceil(expectedCount * MINIMUM_OBSERVED_RATIO).toInt())
        if (observations.size < minimumRequired) {
            return unadjustedLattice(initialGrid, candidates, rows, columns, supportDistancePitchRatio)
        }

        val fit = RobustHomographyFitter.fit(
            source = observations.map { observation ->
                GridPoint(
                    x = (observation.siteIndex % columns).toDouble(),
                    y = (observation.siteIndex / columns).toDouble()
                )
            },
            destination = observations.map(Observation::point),
            expectedSiteCount = expectedCount
        )
        if (fit !is HomographyFitResult.Success) {
            return unadjustedLattice(initialGrid, candidates, rows, columns, supportDistancePitchRatio)
        }

        val predicted = List(expectedCount) { index ->
            RegularGridGeometry.project(
                fit.matrix,
                GridPoint(
                    x = (index % columns).toDouble(),
                    y = (index / columns).toDouble()
                )
            )
        }
        // 第一轮匹配只用于建立稳健单应；模型已经收敛后必须围绕最终预测再次寻找局部
        // 候选。否则初始轴存在几像素偏差时，会把真实存在的亮点/暗方块错误标为补位。
        val finalObservationBySite = matchCandidates(
            gridPoints = predicted,
            candidates = candidates,
            maximumDistance = matchingThreshold
        ).associateBy(Observation::siteIndex)
        val residualThreshold = maxOf(2.0, matchingPitch * maximumResidualPitchRatio)
        val finalPoints = predicted.mapIndexed { index, modelPoint ->
            val observation = finalObservationBySite[index]
            if (observation != null) {
                val residual = distance(modelPoint, observation.point)
                if (residual <= residualThreshold) {
                    LocalizedPoint(
                        point = observation.point,
                        confidence = exp(-((residual / residualThreshold).pow(2))),
                        source = GridPointSource.CANDIDATE_REFINED,
                        flags = emptySet()
                    )
                } else {
                    modelImputedPoint(modelPoint)
                }
            } else {
                modelImputedPoint(modelPoint)
            }
        }

        val supportRatio = candidateSupportRatio(
            points = finalPoints.map(LocalizedPoint::point),
            candidates = candidates,
            maximumDistance = matchingThreshold
        )
        val inlierCount = finalPoints.count { it.source == GridPointSource.CANDIDATE_REFINED }
        val outlierCount = expectedCount - inlierCount
        return LatticeFit(
            points = finalPoints,
            candidateSupportRatio = supportRatio,
            trusted = supportRatio >= TRUSTED_SUPPORT_RATIO,
            observedRatio = inlierCount.toDouble() / expectedCount,
            geometryRmsePx = fit.inlierRmsePx,
            inlierCount = inlierCount,
            outlierCount = outlierCount,
            candidateLatticeApplied = true
        )
    }

    private fun unadjustedLattice(
        points: List<GridPoint>,
        candidates: List<GridCandidate>,
        rows: Int,
        columns: Int,
        supportDistancePitchRatio: Double
    ): LatticeFit {
        val pitch = RegularGridGeometry.estimatePitch(points, rows, columns)
        val representative = minimumPositivePitch(
            pitch,
            points.maxOfOrNull(GridPoint::x)?.toInt()?.plus(1) ?: 1,
            points.maxOfOrNull(GridPoint::y)?.toInt()?.plus(1) ?: 1,
            rows,
            columns
        )
        val support = candidateSupportRatio(
            points,
            candidates,
            representative * supportDistancePitchRatio
        )
        return LatticeFit(
            points = points.map { point ->
                LocalizedPoint(
                    point = point,
                    confidence = 0.0,
                    source = GridPointSource.UNADJUSTED,
                    flags = setOf(GridSiteFlag.LOW_LOCAL_EVIDENCE)
                )
            },
            candidateSupportRatio = support,
            trusted = false,
            observedRatio = 0.0,
            geometryRmsePx = null,
            inlierCount = 0,
            outlierCount = 0,
            candidateLatticeApplied = false
        )
    }

    /**
     * 在全局晶格收敛后再次检查每个位点附近的局部图像证据。
     *
     * top-hat/black-hat 连通域可能因阈值或边缘插值漏掉真实点；Python 参考实现会在
     * 单应平差的两轮中重新计算局部亮/暗质心。这里对仍标记为补位的点执行同等小步
     * 精修，只有窗口具备足够对比度且质心位移不超过 0.65×radius 才恢复为观测点。
     */
    private fun recoverLocalEvidence(
        rectified: Mat,
        lattice: LatticeFit,
        polarity: GridTargetPolarity,
        rows: Int,
        columns: Int
    ): LatticeFit {
        if (lattice.points.none { it.source != GridPointSource.CANDIDATE_REFINED }) return lattice
        val pitch = RegularGridGeometry.estimatePitch(
            lattice.points.map(LocalizedPoint::point),
            rows,
            columns
        )
        val representativePitch = listOf(pitch.horizontalPx, pitch.verticalPx)
            .filter { it > 2.0 }
            .minOrNull()
            ?: return lattice
        val radius = maxOf(4, (representativePitch * 0.32).toInt())
        val gray = Mat()
        try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            val recoveredPoints = lattice.points.map { localized ->
                if (localized.source == GridPointSource.CANDIDATE_REFINED) {
                    localized
                } else {
                    val refined = refineLocalCenter(gray, localized.point, polarity, radius)
                    if (refined == null) {
                        localized
                    } else {
                        val shift = distance(localized.point, refined)
                        val maximumShift = maxOf(2.0, radius * 0.65)
                        LocalizedPoint(
                            point = refined,
                            confidence = exp(-((shift / maximumShift).pow(2))),
                            source = GridPointSource.CANDIDATE_REFINED,
                            flags = emptySet()
                        )
                    }
                }
            }
            val inlierCount = recoveredPoints.count {
                it.source == GridPointSource.CANDIDATE_REFINED
            }
            val recoveredObservedRatio = inlierCount.toDouble() / recoveredPoints.size
            return lattice.copy(
                points = recoveredPoints,
                // 轴候选不足时初始对象会标记 trusted=false；如果投影初值让局部重采样
                // 找回了足够多的真实结构，必须重新判定可信度，不能把旧降级状态永久
                // 带到最终结果。候选支撑与局部观测两项都达标才恢复可信。
                trusted = lattice.candidateSupportRatio >= TRUSTED_SUPPORT_RATIO &&
                    recoveredObservedRatio >= MINIMUM_OBSERVED_RATIO,
                observedRatio = recoveredObservedRatio,
                inlierCount = inlierCount,
                outlierCount = recoveredPoints.size - inlierCount
            )
        } finally {
            gray.release()
        }
    }

    /** 从局部灰度窗口计算亮点或暗点的加权质心，只允许物理上合理的小幅移动。 */
    private fun refineLocalCenter(
        gray: Mat,
        point: GridPoint,
        polarity: GridTargetPolarity,
        radius: Int
    ): GridPoint? {
        val x0 = maxOf(0, point.x.roundToInt() - radius)
        val x1 = minOf(gray.cols(), point.x.roundToInt() + radius + 1)
        val y0 = maxOf(0, point.y.roundToInt() - radius)
        val y1 = minOf(gray.rows(), point.y.roundToInt() + radius + 1)
        if (x1 <= x0 || y1 <= y0) return null
        val window = gray.submat(y0, y1, x0, x1)
        try {
            val values = ByteArray((window.total() * window.channels()).toInt())
            window.get(0, 0, values)
            if (values.isEmpty()) return null
            val unsigned = IntArray(values.size) { index -> values[index].toInt() and 0xFF }
            val minimum = unsigned.minOrNull() ?: return null
            val maximum = unsigned.maxOrNull() ?: return null
            if (maximum - minimum < MINIMUM_LOCAL_CONTRAST) return null
            val sorted = unsigned.copyOf().also(IntArray::sort)
            val percentileRatio = if (polarity == GridTargetPolarity.BRIGHT) 0.78 else 0.32
            val cutoff = sorted[((sorted.size - 1) * percentileRatio).toInt()]

            var weightSum = 0.0
            var weightedX = 0.0
            var weightedY = 0.0
            var index = 0
            for (localY in 0 until window.rows()) {
                for (localX in 0 until window.cols()) {
                    val value = unsigned[index++]
                    val weight = if (polarity == GridTargetPolarity.BRIGHT) {
                        maxOf(0.0, value - cutoff.toDouble())
                    } else {
                        maxOf(0.0, cutoff.toDouble() - value)
                    }
                    weightSum += weight
                    weightedX += (x0 + localX) * weight
                    weightedY += (y0 + localY) * weight
                }
            }
            if (weightSum <= 1e-6) return null
            val refined = GridPoint(weightedX / weightSum, weightedY / weightSum)
            val maximumShift = maxOf(2.0, radius * 0.65)
            return refined.takeIf { distance(it, point) <= maximumShift }
        } finally {
            window.release()
        }
    }

    /** 小角度扫描：晶格转正时 x/y 直方图会形成更尖锐的行列峰。 */
    private fun estimateLatticeRotation(
        candidates: List<GridCandidate>,
        width: Int,
        height: Int
    ): Double {
        if (candidates.size < 8) return 0.0
        var bestAngle = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var angle = -MAX_ROTATION_DEGREES
        while (angle <= MAX_ROTATION_DEGREES + 1e-9) {
            val rotated = candidates.map { rotate(it.point, angle, width / 2.0, height / 2.0) }
            val score = histogramSharpness(rotated.map(GridPoint::x), width.toDouble()) +
                histogramSharpness(rotated.map(GridPoint::y), height.toDouble())
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += ROTATION_STEP_DEGREES
        }
        return bestAngle
    }

    private fun histogramSharpness(values: List<Double>, length: Double): Double {
        val binWidth = maxOf(4.0, length * 0.01)
        val binCount = maxOf(4, (length / binWidth).toInt())
        val histogram = IntArray(binCount)
        values.forEach { value ->
            if (value in 0.0..<length) {
                val index = floor(value / length * binCount).toInt().coerceIn(0, binCount - 1)
                histogram[index]++
            }
        }
        return histogram.sumOf { count -> count.toDouble().pow(2) }
    }

    /**
     * 从候选坐标中选择满足物理间距约束的规则轴。
     *
     * 旧实现直接做固定 K 的加权 k-means。当边缘光晕、高光或遮挡产生额外强簇时，
     * k-means 仍会强行输出 K 个中心，可能丢掉第一行/列并把整张晶格平移一个 pitch；
     * 因为结果仍然“等距”，后续单应平差无法识别这类整数格错位。
     *
     * 这里与 Python 参考实现保持同一物理策略：先按近邻容差形成任意数量的轴向簇，
     * 再枚举其中 count 个簇的组合，拟合 start + pitch*i，并综合规则残差、簇响应和
     * 簇成员数选择最佳轴。组合数通过“只保留最强 count+4 个簇”限制在端侧可接受
     * 范围，10×10 最多 8008 组、15×15 最多 3876 组。
     */
    private fun clusterAxis(
        values: List<Double>,
        weights: List<Double>,
        count: Int,
        length: Int
    ): List<Double>? {
        if (values.size < count || values.size != weights.size) return null
        val tolerance = maxOf(
            MINIMUM_AXIS_CLUSTER_TOLERANCE_PX,
            length * AXIS_CLUSTER_TOLERANCE_RATIO
        )
        val sorted = values.indices
            .map { index -> WeightedAxisValue(values[index], maxOf(weights[index], 1e-6)) }
            .sortedBy(WeightedAxisValue::value)
        val rawClusters = mutableListOf<MutableList<WeightedAxisValue>>()
        sorted.forEach { candidate ->
            val current = rawClusters.lastOrNull()
            if (current == null) {
                rawClusters += mutableListOf(candidate)
            } else {
                val currentMean = current.map(WeightedAxisValue::value).average()
                if (abs(candidate.value - currentMean) <= tolerance) {
                    current += candidate
                } else {
                    rawClusters += mutableListOf(candidate)
                }
            }
        }

        val clusters = rawClusters.map { members ->
            val support = members.sumOf(WeightedAxisValue::weight)
            AxisCluster(
                center = members.sumOf { it.value * it.weight } / maxOf(support, 1e-6),
                support = support,
                memberCount = members.size
            )
        }
        return selectRegularAxisFromClusters(clusters, count, length)
    }

    /** 对已经形成的轴向簇执行规则组合搜索，候选连通域和投影峰共用同一判据。 */
    private fun selectRegularAxisFromClusters(
        inputClusters: List<AxisCluster>,
        count: Int,
        length: Int
    ): List<Double>? {
        if (inputClusters.size < count) return null

        val maximumClusterCount = maxOf(
            MINIMUM_AXIS_COMBINATION_CLUSTER_LIMIT,
            count + AXIS_FALSE_PEAK_ALLOWANCE
        )
        val clusters = if (inputClusters.size > maximumClusterCount) {
            inputClusters
                .sortedByDescending(AxisCluster::support)
                .take(maximumClusterCount)
                .sortedBy(AxisCluster::center)
        } else {
            inputClusters.sortedBy(AxisCluster::center)
        }

        val minimumPitch = length * MINIMUM_AXIS_PITCH_RATIO
        val maximumPitch = length * MAXIMUM_AXIS_PITCH_RATIO
        val minimumStart = length * MINIMUM_AXIS_START_RATIO
        val maximumStart = length * MAXIMUM_AXIS_START_RATIO
        val maximumEnd = length * MAXIMUM_AXIS_END_RATIO
        val maximumRmse = length * MAXIMUM_AXIS_RMSE_RATIO
        val indexMean = (count - 1) / 2.0
        val indexVariance = (0 until count).sumOf { index ->
            val centered = index - indexMean
            centered * centered
        }

        var bestScore = Double.POSITIVE_INFINITY
        var bestAxis: List<Double>? = null
        val selected = arrayOfNulls<AxisCluster>(count)

        fun evaluateSelection() {
            val chosen = selected.map { requireNotNull(it) }
            val centerMean = chosen.map(AxisCluster::center).average()
            val pitch = chosen.indices.sumOf { index ->
                (index - indexMean) * (chosen[index].center - centerMean)
            } / maxOf(indexVariance, 1e-9)
            val start = centerMean - pitch * indexMean
            if (pitch !in minimumPitch..maximumPitch) return

            val fitted = List(count) { index -> start + pitch * index }
            if (fitted.first() !in minimumStart..maximumStart || fitted.last() > maximumEnd) return
            val rmse = sqrt(chosen.indices.map { index ->
                val residual = chosen[index].center - fitted[index]
                residual * residual
            }.average())
            if (rmse > maximumRmse) return

            // 规则性优先；支持度和成员数只负责在多个同样规则的轴之间选出真实阵列。
            val supportScore = chosen.sumOf { cluster ->
                ln1p(cluster.support) + cluster.memberCount * AXIS_MEMBER_SUPPORT_WEIGHT
            }
            val score = rmse * AXIS_RMSE_SCORE_WEIGHT - supportScore
            if (score < bestScore) {
                bestScore = score
                bestAxis = fitted
            }
        }

        fun enumerate(startIndex: Int, depth: Int) {
            if (depth == count) {
                evaluateSelection()
                return
            }
            val remaining = count - depth
            val lastStart = clusters.size - remaining
            for (index in startIndex..lastStart) {
                selected[depth] = clusters[index]
                enumerate(index + 1, depth + 1)
            }
        }

        enumerate(startIndex = 0, depth = 0)
        return bestAxis
    }

    /** 贪心一对一匹配，避免同一强候选支撑多个模型位点。 */
    private fun matchCandidates(
        gridPoints: List<GridPoint>,
        candidates: List<GridCandidate>,
        maximumDistance: Double
    ): List<Observation> {
        val pairCandidates = buildList {
            gridPoints.forEachIndexed { siteIndex, gridPoint ->
                candidates.forEachIndexed { candidateIndex, candidate ->
                    val distance = distance(gridPoint, candidate.point)
                    if (distance <= maximumDistance) {
                        add(MatchPair(siteIndex, candidateIndex, distance))
                    }
                }
            }
        }.sortedBy(MatchPair::distance)

        val usedSites = hashSetOf<Int>()
        val usedCandidates = hashSetOf<Int>()
        val observations = mutableListOf<Observation>()
        pairCandidates.forEach { pair ->
            if (pair.siteIndex !in usedSites && pair.candidateIndex !in usedCandidates) {
                usedSites += pair.siteIndex
                usedCandidates += pair.candidateIndex
                observations += Observation(
                    siteIndex = pair.siteIndex,
                    point = candidates[pair.candidateIndex].point
                )
            }
        }
        return observations.sortedBy(Observation::siteIndex)
    }

    private fun candidateSupportRatio(
        points: List<GridPoint>,
        candidates: List<GridCandidate>,
        maximumDistance: Double
    ): Double {
        if (points.isEmpty() || candidates.isEmpty() || maximumDistance <= 0.0) return 0.0
        val supported = points.count { point ->
            candidates.any { candidate -> distance(point, candidate.point) <= maximumDistance }
        }
        return supported.toDouble() / points.size
    }

    private fun modelImputedPoint(point: GridPoint): LocalizedPoint {
        return LocalizedPoint(
            point = point,
            confidence = GridLocalizedSite.MODEL_IMPUTED_CONFIDENCE,
            source = GridPointSource.MODEL_IMPUTED,
            flags = setOf(GridSiteFlag.IMPUTED_POSITION)
        )
    }

    /**
     * 在最终晶格覆盖的芯片核心区域计算帧级成像质量。
     *
     * 质量统计不能直接使用整张矫正图：芯片区域检测会为安全起见外扩约 10%，边缘因此
     * 包含真实芯片外的暗背景，容易把正常图片误判为欠曝或光照不均。这里以最终位点
     * 包围盒向外扩半个 pitch 作为核心区域，既包含全部反应位，又排除外扩黑边。
     */
    private fun evaluateFrameQuality(
        rectified: Mat,
        lattice: LatticeFit,
        polarity: GridTargetPolarity,
        rows: Int,
        columns: Int
    ): FrameQualityMetrics {
        val points = lattice.points.map(LocalizedPoint::point)
        val pitch = RegularGridGeometry.estimatePitch(points, rows, columns)
        val representativePitch = minimumPositivePitch(
            pitch,
            rectified.cols(),
            rectified.rows(),
            rows,
            columns
        )
        val padding = representativePitch * FRAME_CORE_PADDING_PITCH
        val left = floor(points.minOf(GridPoint::x) - padding).toInt().coerceIn(0, rectified.cols() - 1)
        val top = floor(points.minOf(GridPoint::y) - padding).toInt().coerceIn(0, rectified.rows() - 1)
        val right = ceil(points.maxOf(GridPoint::x) + padding).toInt().coerceIn(left + 1, rectified.cols())
        val bottom = ceil(points.maxOf(GridPoint::y) + padding).toInt().coerceIn(top + 1, rectified.rows())
        val core = rectified.submat(Rect(left, top, right - left, bottom - top))
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val standardDeviation = MatOfDouble()
        try {
            Imgproc.cvtColor(core, gray, Imgproc.COLOR_BGR2GRAY)
            val grayBytes = ByteArray((gray.total() * gray.channels()).toInt())
            gray.get(0, 0, grayBytes)
            val grayValues = IntArray(grayBytes.size) { index -> grayBytes[index].toInt() and 0xFF }
                .also(IntArray::sort)
            val grayP01 = integerPercentile(grayValues, 0.01)
            val grayP99 = integerPercentile(grayValues, 0.99)
            // 稀疏亮点可能只占 1%~5% 像素，P95-P05 会退化为接近零；P99-P01 既能
            // 覆盖点阵/暗方块本体，又不会被单个热像素主导。
            val contrastSpan = maxOf(1.0, grayP99 - grayP01)

            val colorBytes = ByteArray((core.total() * core.channels()).toInt())
            core.get(0, 0, colorBytes)
            var saturatedPixels = 0
            var offset = 0
            while (offset + 2 < colorBytes.size) {
                val blue = colorBytes[offset].toInt() and 0xFF
                val green = colorBytes[offset + 1].toInt() and 0xFF
                val red = colorBytes[offset + 2].toInt() and 0xFF
                if (blue >= SATURATION_LEVEL || green >= SATURATION_LEVEL || red >= SATURATION_LEVEL) {
                    saturatedPixels++
                }
                offset += core.channels()
            }
            val pixelCount = maxOf(grayValues.size, 1)
            val saturationRatio = saturatedPixels.toDouble() / pixelCount
            val underExposureRatio = upperBound(grayValues, UNDER_EXPOSURE_LEVEL) / pixelCount.toDouble()

            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, standardDeviation)
            val laplacianVariance = standardDeviation.toArray().firstOrNull()?.pow(2) ?: 0.0
            val normalizedSharpness = laplacianVariance / (contrastSpan * contrastSpan)

            // 全局光照渐变应表现为左右或上下大面积边带的中位数差；局部遮挡、单个高光
            // 或少数异常位点不会改变 20% 宽边带的中位数，因此不会被误报为照明不均。
            val bandWidth = maxOf(1, (gray.cols() * ILLUMINATION_EDGE_BAND_RATIO).roundToInt())
            val bandHeight = maxOf(1, (gray.rows() * ILLUMINATION_EDGE_BAND_RATIO).roundToInt())
            val leftMedian = regionMedian(gray, Rect(0, 0, bandWidth, gray.rows()))
            val rightMedian = regionMedian(
                gray,
                Rect(gray.cols() - bandWidth, 0, bandWidth, gray.rows())
            )
            val topMedian = regionMedian(gray, Rect(0, 0, gray.cols(), bandHeight))
            val bottomMedian = regionMedian(
                gray,
                Rect(0, gray.rows() - bandHeight, gray.cols(), bandHeight)
            )
            val globalMedian = integerPercentile(grayValues, 0.50)
            val illuminationVariation = maxOf(
                abs(leftMedian - rightMedian),
                abs(topMedian - bottomMedian)
            ) / maxOf(globalMedian, 1.0)

            val geometryRmseRatio = lattice.geometryRmsePx?.div(maxOf(representativePitch, 1e-6))
            return FrameQualityMetrics(
                saturationRatio = saturationRatio,
                underExposureRatio = underExposureRatio,
                grayP01 = grayP01,
                grayP99 = grayP99,
                laplacianVariance = laplacianVariance,
                normalizedSharpness = normalizedSharpness,
                illuminationVariation = illuminationVariation,
                perspectiveVariation = calculatePerspectiveVariation(points, rows, columns),
                geometryRmsePitchRatio = geometryRmseRatio,
                polarity = polarity
            )
        } finally {
            standardDeviation.release()
            mean.release()
            laplacian.release()
            gray.release()
            core.release()
        }
    }

    /** 计算各行列相邻 pitch 的稳健变化范围，用于识别单应模型仍无法吸收的过强透视。 */
    private fun calculatePerspectiveVariation(
        points: List<GridPoint>,
        rows: Int,
        columns: Int
    ): Double {
        val horizontalByRow = if (columns > 1) {
            List(rows) { row ->
                medianDouble(
                    (0 until columns - 1).map { column ->
                        distance(points[row * columns + column], points[row * columns + column + 1])
                    }
                )
            }
        } else {
            emptyList()
        }
        val verticalByColumn = if (rows > 1) {
            List(columns) { column ->
                medianDouble(
                    (0 until rows - 1).map { row ->
                        distance(points[row * columns + column], points[(row + 1) * columns + column])
                    }
                )
            }
        } else {
            emptyList()
        }

        fun relativeRange(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val median = medianDouble(values)
            return (values.max() - values.min()) / maxOf(median, 1e-6)
        }
        return maxOf(relativeRange(horizontalByRow), relativeRange(verticalByColumn))
    }

    private fun integerPercentile(sorted: IntArray, ratio: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * ratio).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble()
    }

    /** 返回已排序数组中小于等于 threshold 的元素数量。 */
    private fun upperBound(sorted: IntArray, threshold: Int): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sorted[middle] <= threshold) low = middle + 1 else high = middle
        }
        return low
    }

    /** 读取灰度子区域中位数；边带用中位数可抵抗少数遮挡、高光和异常反应位。 */
    private fun regionMedian(gray: Mat, rectangle: Rect): Double {
        val region = gray.submat(rectangle)
        return try {
            val bytes = ByteArray((region.total() * region.channels()).toInt())
            region.get(0, 0, bytes)
            val values = IntArray(bytes.size) { index -> bytes[index].toInt() and 0xFF }
                .also(IntArray::sort)
            integerPercentile(values, 0.50)
        } finally {
            region.release()
        }
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun buildFrameQc(
        region: ChipRegionCandidate,
        lattice: LatticeFit,
        quality: FrameQualityMetrics
    ): List<GridFrameQcIssue> = buildList {
        if (region.method == "fallback_center") {
            add(GridFrameQcIssue(GridFrameQcCode.CHIP_REGION_FALLBACK, GridQcSeverity.WARNING))
        }
        if (lattice.candidateSupportRatio < TRUSTED_SUPPORT_RATIO) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.GRID_SUPPORT_LOW,
                    severity = if (lattice.candidateSupportRatio < FAILURE_SUPPORT_RATIO) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = lattice.candidateSupportRatio,
                    threshold = TRUSTED_SUPPORT_RATIO
                )
            )
        }
        val imputedRatio = lattice.outlierCount.toDouble() / maxOf(lattice.points.size, 1)
        if (imputedRatio > HIGH_IMPUTED_RATIO_THRESHOLD) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.HIGH_IMPUTED_RATIO,
                    severity = GridQcSeverity.WARNING,
                    measuredValue = imputedRatio,
                    threshold = HIGH_IMPUTED_RATIO_THRESHOLD
                )
            )
        }

        quality.geometryRmsePitchRatio?.let { ratio ->
            if (ratio >= GEOMETRY_RMSE_WARNING_PITCH_RATIO) {
                add(
                    GridFrameQcIssue(
                        code = GridFrameQcCode.GEOMETRY_RMSE_HIGH,
                        severity = if (ratio >= GEOMETRY_RMSE_FAILURE_PITCH_RATIO) {
                            GridQcSeverity.FAILURE
                        } else {
                            GridQcSeverity.WARNING
                        },
                        measuredValue = ratio,
                        threshold = GEOMETRY_RMSE_WARNING_PITCH_RATIO
                    )
                )
            }
        }

        if (quality.saturationRatio >= SATURATION_WARNING_RATIO) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.OVER_EXPOSED,
                    severity = if (quality.saturationRatio >= SATURATION_FAILURE_RATIO) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = quality.saturationRatio,
                    threshold = SATURATION_WARNING_RATIO
                )
            )
        }

        // 使用 P99 而不是全图均值：荧光芯片允许大面积暗背景，只要最亮的有效信号区域
        // 达到最低灰度就不应被误判为欠曝；整帧真正过暗时 P99 也会同步下降。
        if (quality.grayP99 <= UNDER_EXPOSURE_WARNING_P99) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.UNDER_EXPOSED,
                    severity = if (quality.grayP99 <= UNDER_EXPOSURE_FAILURE_P99) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = quality.grayP99,
                    threshold = UNDER_EXPOSURE_WARNING_P99
                )
            )
        }

        if (quality.laplacianVariance <= BLUR_WARNING_LAPLACIAN_VARIANCE) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.BLURRED,
                    severity = if (
                        quality.laplacianVariance <= BLUR_FAILURE_LAPLACIAN_VARIANCE
                    ) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = quality.laplacianVariance,
                    threshold = BLUR_WARNING_LAPLACIAN_VARIANCE
                )
            )
        }

        if (quality.illuminationVariation >= ILLUMINATION_WARNING_VARIATION) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.ILLUMINATION_NON_UNIFORM,
                    severity = if (quality.illuminationVariation >= ILLUMINATION_FAILURE_VARIATION) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = quality.illuminationVariation,
                    threshold = ILLUMINATION_WARNING_VARIATION
                )
            )
        }

        if (quality.perspectiveVariation >= PERSPECTIVE_WARNING_VARIATION) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.PERSPECTIVE_EXCESSIVE,
                    severity = if (quality.perspectiveVariation >= PERSPECTIVE_FAILURE_VARIATION) {
                        GridQcSeverity.FAILURE
                    } else {
                        GridQcSeverity.WARNING
                    },
                    measuredValue = quality.perspectiveVariation,
                    threshold = PERSPECTIVE_WARNING_VARIATION
                )
            )
        }
    }

    private fun percentile(gray: Mat, quantile: Double): Double {
        require(gray.type() == CvType.CV_8UC1) { "分位数输入必须是 8 位灰度图" }
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val values = IntArray(bytes.size) { index -> bytes[index].toInt() and 0xFF }
        values.sort()
        val position = ((values.size - 1) * quantile).toInt().coerceIn(0, values.lastIndex)
        return values[position].toDouble()
    }

    private fun matToRowMajor(matrix: Mat): DoubleArray {
        require(matrix.rows() == 3 && matrix.cols() == 3) { "OpenCV 单应矩阵必须是 3×3" }
        return DoubleArray(9) { index -> matrix.get(index / 3, index % 3)[0] }
    }

    private fun rotate(
        point: GridPoint,
        angleDegrees: Double,
        centerX: Double,
        centerY: Double
    ): GridPoint {
        val radians = Math.toRadians(angleDegrees)
        val shiftedX = point.x - centerX
        val shiftedY = point.y - centerY
        return GridPoint(
            x = shiftedX * cos(radians) - shiftedY * sin(radians) + centerX,
            y = shiftedX * sin(radians) + shiftedY * cos(radians) + centerY
        )
    }

    private fun distance(first: GridPoint, second: GridPoint): Double {
        return hypot(second.x - first.x, second.y - first.y)
    }

    private fun minimumPositivePitch(
        pitch: GridPitch,
        width: Int,
        height: Int,
        rows: Int,
        columns: Int
    ): Double {
        val positive = listOf(pitch.horizontalPx, pitch.verticalPx).filter { it > 2.0 }
        if (positive.isNotEmpty()) return positive.min()
        return minOf(
            width.toDouble() / maxOf(columns + 1, 2),
            height.toDouble() / maxOf(rows + 1, 2)
        )
    }

    private fun oddAtLeast(minimum: Int, raw: Int): Int {
        val candidate = maxOf(minimum, raw)
        return if (candidate % 2 == 0) candidate + 1 else candidate
    }

    private data class ChipRegionCandidate(
        val points: List<GridPoint>,
        val method: String
    )

    private data class RegionAttempt(
        val threshold: Double,
        val maximumAreaRatio: Double,
        val method: String
    )

    private data class RectificationResult(
        val image: Mat,
        val forwardMatrix: Mat,
        val inverseMatrix: Mat
    ) {
        fun release() {
            image.release()
            forwardMatrix.release()
            inverseMatrix.release()
        }
    }

    private data class GridCandidate(
        val point: GridPoint,
        val weight: Double
    )

    /** 单个候选在某一坐标轴上的坐标与形态学响应权重。 */
    private data class WeightedAxisValue(
        val value: Double,
        val weight: Double
    )

    /** 同一真实行或列附近候选形成的轴向簇。 */
    private data class AxisCluster(
        val center: Double,
        val support: Double,
        val memberCount: Int
    )

    /** 仅用于本次定位内部的帧级质量统计，最终以稳定原因码写入 [GridFrameQcIssue]。 */
    private data class FrameQualityMetrics(
        val saturationRatio: Double,
        val underExposureRatio: Double,
        val grayP01: Double,
        val grayP99: Double,
        val laplacianVariance: Double,
        val normalizedSharpness: Double,
        val illuminationVariation: Double,
        val perspectiveVariation: Double,
        val geometryRmsePitchRatio: Double?,
        val polarity: GridTargetPolarity
    )

    private data class LocalizedPoint(
        val point: GridPoint,
        val confidence: Double,
        val source: GridPointSource,
        val flags: Set<GridSiteFlag>
    )

    private data class LatticeFit(
        val points: List<LocalizedPoint>,
        val candidateSupportRatio: Double,
        val trusted: Boolean,
        val observedRatio: Double,
        val geometryRmsePx: Double?,
        val inlierCount: Int,
        val outlierCount: Int,
        /** true 表示候选轴和稳健单应均真实成立；false 表示投影或规则均分兜底。 */
        val candidateLatticeApplied: Boolean
    )

    /** 单一极性的候选集合，供自动裁决时按证据强度排序。 */
    private data class PolarityCandidates(
        val polarity: GridTargetPolarity,
        val candidates: List<GridCandidate>
    )

    /** 自动极性裁决后的实际极性和对应晶格。 */
    private data class PolarityFit(
        val polarity: GridTargetPolarity,
        val lattice: LatticeFit
    )

    private data class Observation(
        val siteIndex: Int,
        val point: GridPoint
    )

    private data class MatchPair(
        val siteIndex: Int,
        val candidateIndex: Int,
        val distance: Double
    )

    private companion object {
        const val LOCATOR_NAME: String = "OpenCV PG-Grid"
        const val LOCATOR_VERSION: String = "2.1.0"
        const val FRAME_QUALITY_LOG_TAG: String = "PgGridFrameQuality"
        const val MIN_BRIGHTNESS_THRESHOLD: Double = 22.0
        const val MINIMUM_OBSERVED_RATIO: Double = 0.4
        const val TRUSTED_SUPPORT_RATIO: Double = 0.6
        const val FAILURE_SUPPORT_RATIO: Double = 0.35
        const val HIGH_IMPUTED_RATIO_THRESHOLD: Double = 0.2
        const val MAX_ROTATION_DEGREES: Double = 6.0
        const val ROTATION_STEP_DEGREES: Double = 0.25
        const val MINIMUM_AXIS_CLUSTER_TOLERANCE_PX: Double = 10.0
        const val AXIS_CLUSTER_TOLERANCE_RATIO: Double = 0.0225
        const val MINIMUM_AXIS_COMBINATION_CLUSTER_LIMIT: Int = 16
        const val AXIS_FALSE_PEAK_ALLOWANCE: Int = 4
        const val MINIMUM_AXIS_PITCH_RATIO: Double = 0.045
        const val MAXIMUM_AXIS_PITCH_RATIO: Double = 0.095
        const val MINIMUM_AXIS_START_RATIO: Double = 0.050
        const val MAXIMUM_AXIS_START_RATIO: Double = 0.300
        const val MAXIMUM_AXIS_END_RATIO: Double = 0.950
        const val MAXIMUM_AXIS_RMSE_RATIO: Double = 0.020
        const val AXIS_MEMBER_SUPPORT_WEIGHT: Double = 0.15
        const val AXIS_RMSE_SCORE_WEIGHT: Double = 8.0
        const val PROJECTION_BACKGROUND_BLUR_RATIO: Double = 0.08
        const val PROJECTION_SMOOTHING_DIVISOR: Int = 8
        const val PROJECTION_MINIMUM_PEAK_DISTANCE_PITCH: Double = 0.45
        const val PROJECTION_EXTRA_PEAK_COUNT: Int = 8
        const val MAX_PROJECTION_SPACING_CV: Double = 0.38
        const val FRAME_CORE_PADDING_PITCH: Double = 0.50
        const val SATURATION_LEVEL: Int = 250
        const val UNDER_EXPOSURE_LEVEL: Int = 8
        const val ILLUMINATION_EDGE_BAND_RATIO: Double = 0.20
        const val GEOMETRY_RMSE_WARNING_PITCH_RATIO: Double = 0.12
        const val GEOMETRY_RMSE_FAILURE_PITCH_RATIO: Double = 0.20
        const val SATURATION_WARNING_RATIO: Double = 0.025
        const val SATURATION_FAILURE_RATIO: Double = 0.10
        const val UNDER_EXPOSURE_WARNING_P99: Double = 50.0
        const val UNDER_EXPOSURE_FAILURE_P99: Double = 32.0
        const val BLUR_WARNING_LAPLACIAN_VARIANCE: Double = 5.0
        const val BLUR_FAILURE_LAPLACIAN_VARIANCE: Double = 2.5
        const val ILLUMINATION_WARNING_VARIATION: Double = 0.05
        const val ILLUMINATION_FAILURE_VARIATION: Double = 0.15
        const val PERSPECTIVE_WARNING_VARIATION: Double = 0.012
        const val PERSPECTIVE_FAILURE_VARIATION: Double = 0.025
        const val MINIMUM_LOCAL_CONTRAST: Int = 8
    }
}
