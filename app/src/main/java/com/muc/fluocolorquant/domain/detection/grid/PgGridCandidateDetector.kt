package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.MSER
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** 单个单元候选在矫正图中的中心与形态学响应强度。 */
internal data class GridCandidate(
    val point: GridPoint,
    val weight: Double
)

internal data class RoundRim(val point: GridPoint, val conservativeRadius: Double, val strength: Double)

/**
 * PG-Grid 单元候选检测器。
 *
 * 与 Python 参考实现 `pg_grid._detect_dark_square_candidates` /
 * `_detect_bright_dot_candidates` / `_multi_threshold_blobs` 保持同一套几何闸值与
 * 阈值策略。这里只产出“图像上确实存在结构”的候选证据，不做任何晶格假设，
 * 晶格拟合与信任判定由调用方负责。
 */
internal object PgGridCandidateDetector {

    /** 传统行列法共用连通域候选。用孔距给面积和宽高定标，不套 10×10 芯片专用的暗方块像素阈值。 */
    fun componentCenters(gray: Mat, polarity: GridTargetPolarity, pitch: Double, multiThreshold: Boolean): List<GridCandidate> {
        val energy = Mat()
        val smooth = Mat()
        val contrast = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            gray.convertTo(energy, CvType.CV_32F, if (polarity == GridTargetPolarity.BRIGHT) 1.0 else -1.0,
                if (polarity == GridTargetPolarity.BRIGHT) 0.0 else 255.0)
            Imgproc.GaussianBlur(energy, smooth, Size(0.0, 0.0), .45 * pitch)
            Core.subtract(energy, smooth, contrast)
            contrast.convertTo(mask, CvType.CV_8U)
            if (Core.minMaxLoc(mask).maxVal < 3) return emptyList()
            // 整图 Otsu 会把左暗右亮的弱孔一起切掉。只在原路径失败时从实际背景噪声起分档，
            // 合并同孔候选；行、列和四边仍需有图像支撑，不把补点算入观测数。
            if (multiThreshold) {
                val minimumBox = maxOf(3, (.08 * pitch).roundToInt())
                return multiThresholdBlobs(
                    response = mask,
                    minimumArea = maxOf(1, ceil(.012 * pitch * pitch).toInt()),
                    maximumArea = (.72 * pitch * pitch).toInt(),
                    minimumBox = minimumBox,
                    maximumBox = (1.10 * pitch).toInt(),
                    aspectRange = .48..(1.0 / .48),
                    noiseFloor = interiorNoiseFloor(mask),
                    mergeDistancePx = maxOf(minimumBox.toDouble(), .18 * pitch)
                )
            }
            Imgproc.threshold(mask, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val components = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            val areaRange = .012*pitch*pitch.. .72*pitch*pitch
            return (1 until components).mapNotNull { index ->
                val width = stats.get(index, Imgproc.CC_STAT_WIDTH)[0]
                val height = stats.get(index, Imgproc.CC_STAT_HEIGHT)[0]
                val area = stats.get(index, Imgproc.CC_STAT_AREA)[0]
                val centerX = centroids.get(index, 0)[0]
                val centerY = centroids.get(index, 1)[0]
                if (area !in areaRange || maxOf(width,height) > pitch * 1.10 || minOf(width,height) < maxOf(width,height)*.48 ||
                    centerX < width / 2 || centerY < height / 2 || centerX >= gray.cols()-width/2 || centerY >= gray.rows()-height/2) null
                else GridCandidate(GridPoint(centerX,centerY), sqrt(area))
            }
        } finally {
            listOf(energy,smooth,contrast,mask,labels,stats,centroids).forEach(Mat::release)
        }
    }

    /** OpenCV 有序圆点阵列只返回中心；取值半径必须另有证据，且对称图案无法验证物理 A1。 */
    fun orderedCircleCenters(gray: Mat, bright: Boolean, rows: Int, columns: Int): List<GridPoint>? {
        val input = Mat()
        val centers = Mat()
        try {
            if (bright) Core.bitwise_not(gray, input) else gray.copyTo(input)
            val pattern = Size(columns.toDouble(), rows.toDouble())
            val flags = Calib3d.CALIB_CB_SYMMETRIC_GRID
            if (!Calib3d.findCirclesGrid(input, pattern, centers, flags) &&
                !Calib3d.findCirclesGrid(input, pattern, centers, flags or Calib3d.CALIB_CB_CLUSTERING)) return null
            val raw = MatOfPoint2f(centers)
            val points = try { raw.toArray().map { GridPoint(it.x, it.y) } } finally { raw.release() }
            if (points.size != rows * columns) return null
            val transposed = if (rows == columns) List(points.size) { index -> points[index % columns * columns + index / columns] } else null
            val aligned = listOfNotNull(points, transposed).firstOrNull { candidate ->
                kotlin.math.abs(candidate[columns-1].x-candidate[0].x) > kotlin.math.abs(candidate[columns-1].y-candidate[0].y) &&
                    kotlin.math.abs(candidate[(rows-1)*columns].y-candidate[0].y) > kotlin.math.abs(candidate[(rows-1)*columns].x-candidate[0].x)
            } ?: return null
            val reverseColumns = aligned[columns-1].x < aligned[0].x
            val reverseRows = aligned[(rows-1)*columns].y < aligned[0].y
            return List(points.size) { index ->
                val row = if (reverseRows) rows - 1 - index / columns else index / columns
                val column = if (reverseColumns) columns - 1 - index % columns else index % columns
                aligned[row * columns + column]
            }
        } finally {
            input.release()
            centers.release()
        }
    }

    /** MSER 在灰度与色差上找稳定连通斑点，灰度近似但颜色不同的方孔也能提供几何证据。 */
    fun stableSpots(bgr: Mat, gray: Mat, pitchHint: Double, count: Int, balanceContrast: Boolean): List<GridCandidate> {
        val channels = mutableListOf<Mat>()
        val upper = Mat()
        val lower = Mat()
        val chroma = Mat()
        val balanced = Mat()
        val boxes = MatOfRect()
        val regions = mutableListOf<MatOfPoint>()
        val maxArea = (pitchHint * pitchHint * .85).roundToInt().coerceAtLeast(25)
        // 弱光重试用较小的强度步长寻找稳定区；中心与邻域的对比仍从原工作面核对，增强调色不作证据。
        val detector = MSER.create(if (balanceContrast) 1 else 3, 9, maxArea, .43, .20)
        try {
            Core.split(bgr, channels)
            Core.max(channels[0], channels[1], upper)
            Core.max(upper, channels[2], upper)
            Core.min(channels[0], channels[1], lower)
            Core.min(lower, channels[2], lower)
            Core.subtract(upper, lower, chroma)
            val candidates = buildList {
                for (plane in listOf(gray, chroma)) {
                    val range = Core.minMaxLoc(plane)
                    if (range.maxVal - range.minVal < 3) continue
                    val work = if (balanceContrast) {
                        balanceSpotContrast(plane, balanced, pitchHint)
                        balanced
                    } else plane
                    detector.detectRegions(work, regions, boxes)
                    boxes.toArray().forEachIndexed { index, box ->
                        val short = minOf(box.width, box.height).toDouble()
                        val long = maxOf(box.width, box.height).toDouble()
                        val fill = regions[index].total() / (short * long)
                        if (short < maxOf(3.0, pitchHint*.07) || long > pitchHint*.86 || short < .59*long || fill < .53) return@forEachIndexed
                        val centerX = box.x + (box.width - 1) / 2.0
                        val centerY = box.y + (box.height - 1) / 2.0
                        val radius = long * .73
                        if (centerX < radius || centerY < radius || centerX >= plane.cols()-radius || centerY >= plane.rows()-radius) return@forEachIndexed
                        val center = plane.get(centerY.roundToInt(),centerX.roundToInt())[0]
                        val around = listOf(plane.get(centerY.roundToInt(), (centerX-radius).toInt())[0],
                            plane.get(centerY.roundToInt(), (centerX+radius).toInt())[0],
                            plane.get((centerY-radius).toInt(), centerX.roundToInt())[0],
                            plane.get((centerY+radius).toInt(), centerX.roundToInt())[0]).sorted()
                        val response = kotlin.math.abs(center - (around[1]+around[2]) / 2)
                        if (response >= 2.0) add(GridCandidate(GridPoint(centerX,centerY), fill * short/long * (1 + response.coerceAtMost(40.0)/40)))
                    }
                    regions.forEach(Mat::release)
                    regions.clear()
                }
            }
            val compact = mutableListOf<GridCandidate>()
            for (spot in candidates.sortedByDescending(GridCandidate::weight).take((count * 15).coerceAtMost(5000))) {
                // 嵌套阈值/颜色图中的同一斑点只保留一票，避免局部热点压过整行实际证据。
                if (compact.none { hypot(spot.point.x-it.point.x, spot.point.y-it.point.y) < pitchHint*.15 }) compact.add(spot)
                if (compact.size >= (count * 4).coerceAtMost(1200)) break
            }
            return compact
        } finally {
            detector.clear()
            (channels + regions + listOf(upper, lower, chroma, balanced, boxes)).forEach(Mat::release)
        }
    }

    /** 去掉缓变背景后按邻域 RMS 平衡斑点对比度；增益最高 6 倍，避免把纯背景拉伸成强孔。 */
    private fun balanceSpotContrast(source: Mat, output: Mat, pitch: Double) {
        val contrast = Mat()
        val background = Mat()
        val scale = Mat()
        try {
            source.convertTo(contrast, CvType.CV_32F)
            Imgproc.GaussianBlur(contrast, contrast, Size(0.0, 0.0), maxOf(0.8, .035 * pitch))
            Imgproc.GaussianBlur(contrast, background, Size(0.0, 0.0), .45 * pitch)
            Core.subtract(contrast, background, contrast)
            Core.multiply(contrast, contrast, scale)
            val window = (2 * pitch.roundToInt() + 1).toDouble()
            Imgproc.blur(scale, scale, Size(window, window))
            Core.sqrt(scale, scale)
            Core.max(scale, Scalar.all(5.0), scale)
            Core.divide(contrast, scale, contrast, 30.0)
            contrast.convertTo(output, CvType.CV_8U, 1.0, 128.0)
        } finally {
            listOf(contrast, background, scale).forEach(Mat::release)
        }
    }

    /**
     * 在有界工作图上用尺度归一化的高斯拉普拉斯找明/暗斑点，原图不参与任何增强或写回。
     * 灰度之外另看色差，避免漏掉亮度接近的显色反应。Hessian 行列式用于去除细长孔缘；
     * 多个尺度或通道对同一中心只投一票。尺度描述的是斑点，不是孔径，不能用作采样掩膜。
     */
    fun logSpots(bgr: Mat, gray: Mat, pitch: Double, expectedCount: Int, balanceContrast: Boolean): Map<GridTargetPolarity, List<GridCandidate>> {
        val channels = mutableListOf<Mat>()
        val upper = Mat()
        val lower = Mat()
        val chroma = Mat()
        val balanced = Mat()
        val plane = Mat()
        val smooth = Mat()
        val curvature = Mat()
        val secondX = Mat()
        val mixed = Mat()
        val amplitude = Mat()
        val maxima = Mat()
        val width = gray.cols()
        val height = gray.rows()
        val responseValues = FloatArray(width * height)
        val maximaValues = FloatArray(responseValues.size)
        val secondXValues = FloatArray(responseValues.size)
        val mixedValues = FloatArray(responseValues.size)
        val scales = listOf(.035, .055, .085, .13, .19, .28).map { maxOf(1.2, pitch * it) }.distinct()
        val limit = (expectedCount * 3).coerceAtMost(1200)
        val peaks = mutableListOf<LogPeak>()
        try {
            Core.split(bgr, channels)
            Core.max(channels[0], channels[1], upper)
            Core.max(upper, channels[2], upper)
            Core.min(channels[0], channels[1], lower)
            Core.min(lower, channels[2], lower)
            Core.subtract(upper, lower, chroma)
            for (channel in listOf(gray, chroma)) {
                val range = Core.minMaxLoc(channel)
                if (range.maxVal - range.minVal < 3.0) continue
                if (balanceContrast) {
                    balanceSpotContrast(channel, balanced, pitch)
                    balanced.convertTo(plane, CvType.CV_32F)
                } else channel.convertTo(plane, CvType.CV_32F)
                // 在未平滑的平面估计像素噪声。较大尺度的孔响应铺得很广，从比对响应自身估计会把圆心或方块中心误当噪声。
                Imgproc.Laplacian(plane, curvature, CvType.CV_32F, 1, 1.0, 0.0)
                curvature.get(0, 0, responseValues)
                val noiseSample = buildList {
                    for (row in 1 until height - 1 step 7) {
                        for (column in 1 until width - 1 step 7) add(kotlin.math.abs(responseValues[row * width + column]))
                    }
                }.sorted()
                // 4 邻域拉普拉斯的平方权重和为 20。按高斯 MAD 估计每像素噪声，再用归一化 LoG 核的 L2 范数缩放到各尺度。
                val pixelNoise = noiseSample[noiseSample.size / 2] / (.67449 * sqrt(20.0))
                for (sigma in scales) {
                    val border = ceil(2 * sigma).toInt()
                    if (2 * border >= minOf(width, height)) continue
                    val scale = sigma * sigma
                    Imgproc.GaussianBlur(plane, smooth, Size(0.0, 0.0), sigma)
                    Imgproc.Laplacian(smooth, curvature, CvType.CV_32F, 1, scale, 0.0)
                    Imgproc.Sobel(smooth, secondX, CvType.CV_32F, 2, 0, 1, scale, 0.0)
                    Imgproc.Sobel(smooth, mixed, CvType.CV_32F, 1, 1, 3, .25 * scale, 0.0)
                    Core.absdiff(curvature, Scalar.all(0.0), amplitude)
                    val window = (2 * maxOf(2, sigma.roundToInt()) + 1).toDouble()
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(window, window))
                    try { Imgproc.dilate(amplitude, maxima, kernel) } finally { kernel.release() }
                    curvature.get(0, 0, responseValues)
                    maxima.get(0, 0, maximaValues)
                    secondX.get(0, 0, secondXValues)
                    mixed.get(0, 0, mixedValues)
                    val threshold = maxOf(1.6, 4.0 * pixelNoise / (sqrt(2.0 * Math.PI) * sigma))
                    val scalePeaks = buildList {
                        for (row in border until height - border) {
                            for (column in border until width - border) {
                                val index = row * width + column
                                val response = responseValues[index]
                                val strength = kotlin.math.abs(response)
                                if (strength < threshold || strength < maximaValues[index]) continue
                                val horizontal = secondXValues[index]
                                val cross = mixedValues[index]
                                if (horizontal * (response - horizontal) - cross * cross <= .12 * response * response) continue
                                val polarity = if (response < 0f) GridTargetPolarity.BRIGHT else GridTargetPolarity.DARK
                                add(LogPeak(GridPoint(column.toDouble(), row.toDouble()), strength.toDouble(), sigma, polarity))
                            }
                        }
                    }
                    scalePeaks.groupBy(LogPeak::polarity).values.forEach { group ->
                        peaks += group.sortedByDescending(LogPeak::strength).take(limit)
                    }
                }
            }
        } finally {
            (channels + listOf(upper, lower, chroma, balanced, plane, smooth, curvature, secondX, mixed, amplitude, maxima)).forEach(Mat::release)
        }
        // 不能把亮孔中心与其周围的暗峰混成一批拟合，否则半孔距的伪峰会污染真实网格。
        return peaks.groupBy(LogPeak::polarity).mapValues { (_, group) ->
            val selected = mutableListOf<LogPeak>()
            for (peak in group.sortedByDescending(LogPeak::strength)) {
                if (selected.none { prior ->
                        val radius = maxOf(2.5, 1.55 * maxOf(prior.sigma, peak.sigma)).coerceAtMost(.34 * pitch)
                        hypot(peak.point.x - prior.point.x, peak.point.y - prior.point.y) < radius
                    }) selected += peak
                if (selected.size == limit) break
            }
            selected.map { GridCandidate(it.point, it.strength) }
        }
    }

    private data class LogPeak(val point: GridPoint, val strength: Double, val sigma: Double, val polarity: GridTargetPolarity)

    /** 直接从整板或矫正图找孔缘，不借用 YOLO 框。尺度以声明行列估算，并用重复半径排除孔内小反光。 */
    fun roundRims(gray: Mat, nominalPitch: Double, ellipse: Boolean, count: Int): List<RoundRim> {
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val circles = Mat()
        val minimum = maxOf(3.0, nominalPitch * 0.11)
        val maximum = nominalPitch * 0.52
        if (maximum <= minimum) return emptyList()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 1.1)
            val candidates = if (!ellipse) {
                Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.15, nominalPitch * 0.44,
                    80.0, 18.0, minimum.toInt(), maximum.toInt())
                List(circles.cols()) { index ->
                    val value = circles.get(0, index)
                    RoundRim(GridPoint(value[0], value[1]), value[2], 1.0 - index * 0.25 / maxOf(circles.cols(), 1))
                }
            } else {
                Imgproc.Canny(blurred, edges, 24.0, 76.0)
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.mapNotNull { contour ->
                    val bound = Imgproc.boundingRect(contour)
                    if (contour.total() < 5 || minOf(bound.width, bound.height) < minimum ||
                        maxOf(bound.width, bound.height) > maximum * 3) return@mapNotNull null
                    val floatPoints = org.opencv.core.MatOfPoint2f(*contour.toArray())
                    val fitted = try { Imgproc.fitEllipse(floatPoints) } finally { floatPoints.release() }
                    val short = minOf(fitted.size.width, fitted.size.height) / 2
                    val long = maxOf(fitted.size.width, fitted.size.height) / 2
                    val fill = Imgproc.contourArea(contour) / (kotlin.math.PI * short * long)
                    // 只把椭圆作为孔缘证据；在未完全矫正的图上用短轴估计保守内区，不把长轴当正圆半径。
                    if (short !in minimum..maximum || long > 1.40 * maximum || short < 0.56 * long || fill !in 0.73..1.14 ||
                        !fitted.center.x.isFinite() || !fitted.center.y.isFinite()) null
                    else RoundRim(GridPoint(fitted.center.x, fitted.center.y), short,
                        (short / long * (1 - kotlin.math.abs(1 - fill))).coerceIn(0.0, 1.0))
                }
            }.filter { rim ->
                val radius = rim.conservativeRadius
                rim.point.x >= radius && rim.point.x < gray.cols() - radius &&
                    rim.point.y >= radius && rim.point.y < gray.rows() - radius
            }
            val unique = mutableListOf<RoundRim>()
            val seenLimit = (count * 4).coerceAtMost(1800)
            for (rim in candidates.sortedByDescending(RoundRim::strength).take(seenLimit)) {
                if (unique.none { kotlin.math.hypot(rim.point.x - it.point.x, rim.point.y - it.point.y) < nominalPitch * 0.28 }) {
                    unique.add(rim)
                }
            }
            val referenceRadius = unique.map(RoundRim::conservativeRadius).sorted().let { radii ->
                radii.indices.maxByOrNull { index -> radii.count { it in radii[index] * 0.79..radii[index] * 1.24 } }?.let(radii::get)
            } ?: return emptyList()
            return unique.filter { it.conservativeRadius in referenceRadius * 0.67..referenceRadius * 1.45 }
                .take((count * 2).coerceAtMost(1200))
        } finally {
            contours.forEach { it.release() }
            listOf(blurred, edges, hierarchy, circles).forEach(Mat::release)
        }
    }

    /**
     * 斑点几何闸值的参考规格：历史系数全部是在 15×15 上标定的。
     *
     * 单元的像素尺寸正比于**单元间距** `pitch = side*(1-2*margin)/(count-1)`，而不是
     * 正比于画幅边长 `side`。按边长归一化的历史公式因此隐含了 `count≈15` 的假设：
     * 10×10 用 800px 矫正图、15×15 用 1200px，两者单元同为约 34px，但按边长算出的
     * `max_area` 却是 704 与 1584——同样大的单元在 10×10 被判超限，候选饥饿会一路
     * 传导为晶格拟合失败、支撑率为 0、区域仲裁失去区分度。
     */
    const val GATE_REFERENCE_GRID: Int = 15

    /**
     * 闸值换算使用的参考边距比例。
     *
     * 刻意固定为 0.085 而不是读取 [PgGridLocatorConfig.marginRatio]：下面两个倍率
     * 常量正是在该边距下从历史闸值反解出来的，跟随配置变化会让“15×15 逐值不变”
     * 这一恒等性失效。
     */
    const val REFERENCE_MARGIN_RATIO: Double = 0.085

    /**
     * 轴间距接受区间的下界倍率（相对名义间距）。
     *
     * 由历史闸值 `length*0.045` 反解：在参考规格 15×15 上按构造逐值不变。下界是防
     * “压缩/互补晶格”的那道门，不能改成围绕名义间距对称——放宽后会拟合出间距只有
     * 真值一半的压缩晶格，它照样落在真实单元上并拿到高支撑率，属于最危险的静默失败。
     */
    private val AXIS_PITCH_MIN_RATIO: Double =
        0.045 * (GATE_REFERENCE_GRID - 1) / (1.0 - 2.0 * REFERENCE_MARGIN_RATIO)

    /** 轴间距接受区间的上界倍率，由历史闸值 `length*0.095` 反解；留出区域框外扩余量。 */
    private val AXIS_PITCH_MAX_RATIO: Double =
        0.095 * (GATE_REFERENCE_GRID - 1) / (1.0 - 2.0 * REFERENCE_MARGIN_RATIO)

    /** 多阈值提取默认取的阈值档数。 */
    private const val DEFAULT_THRESHOLD_LEVELS: Int = 6

    /** 连通域中心距图像边缘的最小比例，用于剔除边框亮带和矫正插值边带。 */
    private const val EDGE_GUARD_RATIO: Double = 0.035

    /**
     * 把画幅边长换算成“等效 15×15 边长”，供几何闸值沿用历史系数。
     *
     * 换算只需按 `(参考行列数-1)/(实际行列数-1)` 缩放，因为边距因子与边长都已在比值中
     * 约掉。[count] 等于参考规格时返回 [side] 本身，因此 15×15 的闸值逐值不变——这一
     * 恒等性由构造保证，而不依赖取整的巧合。
     */
    fun gateReferenceSide(side: Double, count: Int?): Double {
        if (count == null || count < 2) return side
        return side * (GATE_REFERENCE_GRID - 1) / (count - 1).toDouble()
    }

    /** 轴向的名义单元间距，与 [RegularGridGeometry.generate] 的排布约定一致。 */
    fun expectedAxisPitch(length: Double, count: Int): Double {
        return length * (1.0 - 2.0 * REFERENCE_MARGIN_RATIO) / maxOf(count - 1, 1).toDouble()
    }

    /**
     * 轴间距的接受区间，按**行列数**而不是画幅边长定义。
     *
     * 历史写法 `[length*0.045, length*0.095]` 与斑点闸值犯的是同一个错误：间距正比于
     * `length/(count-1)`，按边长定区间就隐含了 `count≈15`。实测 10×10、length=800 时
     * 上界 76 相对名义间距 73.8 只剩 3% 余量（15×15 有 60%），主区域框稍一外扩，
     * 10×10 的轴拟合就整体被拒并退化为均分网格。
     */
    fun axisPitchBounds(length: Double, count: Int): ClosedFloatingPointRange<Double> {
        val expected = expectedAxisPitch(length, count)
        return (expected * AXIS_PITCH_MIN_RATIO)..(expected * AXIS_PITCH_MAX_RATIO)
    }

    /**
     * 选择用于闸值换算的行列数：矫正图较短边所对应的那个方向。
     *
     * 单元的像素尺寸由较短边上的间距决定（[gateReferenceSide] 的入参 `side` 取
     * `min(width, height)`），因此必须配对同一方向的行列数。方阵规格下两者相等，
     * 10×10 与 15×15 的行为与 Python 参考实现逐值一致。
     */
    fun gateCountFor(rows: Int, columns: Int, width: Int, height: Int): Int {
        return if (width <= height) columns else rows
    }

    /**
     * 定位暗色单元（亮面板上的暗方块）候选。
     *
     * 几何闸值**刻意保留按边长归一化的历史公式**，与亮点检测器不同：亮点检测器只在
     * 15×15 上标定过，换算到间距单位是纯收益；暗检测器则在 10×10 与 15×15 的实拍图上
     * 都已验证，任何重新锚定都必然改动其中一边。实测把它锚定到 15×15 后，10×10 的
     * `max_box` 从 44 放宽到 68，实拍板上的螺丝、连接器、通道线随之混入候选并带偏轴
     * 选择，支撑率从 0.88 塌到 0.37。因此这里不动公式。
     *
     * 唯一新增的是一条**安全钳制**：`max_box` 必须严格小于一个间距，因为它才是挡住
     * “两个相邻单元糊成一片”的那道门。按边长算出的 `max_box/pitch` 在 10×10 是 0.60、
     * 15×15 是 0.93，但行列数 ≥17 时会超过 1.0。钳制在 10/15 上不生效，只在更大规格
     * 上兜底。
     *
     * @param scaleSide 允许在放大画布上沿用原矫正图的尺度闸值：画布变大但单元像素尺寸
     *   不变，闸值若跟着画布放大会把真实单元全部过滤掉。
     * @param gateCount 较短边方向的行列数，仅用于 `max_box` 钳制。
     */
    fun detectDarkSquareCandidates(
        bgr: Mat,
        scaleSide: Double? = null,
        gateCount: Int? = null
    ): List<GridCandidate> {
        val gray = Mat()
        val response = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val width = gray.cols()
            val height = gray.rows()
            val side = scaleSide ?: minOf(width, height).toDouble()

            // 核尺寸要大于反应方块，黑帽变换才会突出方块而不是大面积背景渐变。
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
            Imgproc.threshold(response, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            val minimumBox = maxOf(8, (side * 0.010).toInt())
            var maximumBox = maxOf(24, (side * 0.055).toInt())
            if (gateCount != null && gateCount >= 2) {
                // 兜底：粘连的两个单元必须被拒。已知行列数 ≤15 时本就满足，此处不改变行为。
                maximumBox = minOf(maximumBox, (expectedAxisPitch(side, gateCount) * 0.95).toInt())
            }
            return connectedComponentCandidates(
                responsePixels = readUnsignedBytes(response),
                mask = mask,
                minimumBox = minimumBox,
                maximumBox = maximumBox,
                minimumArea = maxOf(50, (side * side * 0.00012).toInt()),
                maximumArea = maxOf(450, (side * side * 0.00120).toInt()),
                aspectRange = 0.45..1.80
            )
        } finally {
            gray.release()
            response.release()
            mask.release()
        }
    }

    /**
     * 定位亮点阵列中的小亮斑候选。
     *
     * 与暗方块检测对偶：顶帽变换增强“小而亮”的结构，抑制面板亮度本身与大面积光照渐变。
     * 几何闸值按**单元间距**归一化（见 [gateReferenceSide]）；不传 [gateCount] 时退化为
     * 按边长归一化的历史公式，旧调用点行为不变。
     *
     * 阈值改为多档提取（[multiThresholdBlobs]）：自发光/荧光阵列内部亮度可跨一两个
     * 数量级，单一全局 Otsu 会牺牲弱单元，局部自适应阈值又被邻近强单元抬高而同样失效。
     * 噪声基准只从内部区域估计——面板边缘的阶跃过渡在顶帽图中形成远强于亮点的窄亮带，
     * 若参与全图统计会把阈值抬到亮点响应之上导致全部漏检。
     */
    fun detectBrightDotCandidates(
        bgr: Mat,
        scaleSide: Double? = null,
        gateCount: Int? = null
    ): List<GridCandidate> {
        val gray = Mat()
        val response = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val width = gray.cols()
            val height = gray.rows()
            val side = scaleSide ?: minOf(width, height).toDouble()
            // 换算到等效 15×15 边长后，历史系数原样沿用。
            val gateSide = gateReferenceSide(side, gateCount)

            val kernelSize = oddAtLeast(25, (gateSide * 0.050).toInt())
            val minimumBox = maxOf(5, (gateSide * 0.006).toInt())
            // max_box 必须严格小于一个间距：它——而不是 max_area——才是挡住
            // “两个相邻单元糊成一片”的那道门。
            val maximumBox = maxOf(16, (gateSide * 0.045).toInt())
            val minimumArea = maxOf(20, (gateSide * gateSide * 0.00004).toInt())
            val maximumArea = maxOf(240, (gateSide * gateSide * 0.00110).toInt())

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(gray, response, Imgproc.MORPH_TOPHAT, kernel)
            } finally {
                kernel.release()
            }

            val noiseFloor = interiorNoiseFloor(response)
            val blobs = multiThresholdBlobs(
                response = response,
                minimumArea = minimumArea,
                maximumArea = maximumArea,
                minimumBox = minimumBox,
                maximumBox = maximumBox,
                aspectRange = 0.45..1.80,
                noiseFloor = noiseFloor
            )

            val edgeGuardX = width * EDGE_GUARD_RATIO
            val edgeGuardY = height * EDGE_GUARD_RATIO
            return blobs.filter { candidate ->
                candidate.point.x in edgeGuardX..(width - edgeGuardX) &&
                    candidate.point.y in edgeGuardY..(height - edgeGuardY)
            }
        } finally {
            gray.release()
            response.release()
        }
    }

    /**
     * 在一组几何递增的阈值上提取斑点并去重合并。
     *
     * 单一阈值无法处理跨数量级的亮度分布：自发光/荧光阵列里最亮与最暗单元可以相差
     * 一两个数量级，全局阈值会牺牲弱单元，局部自适应阈值又会被邻近强单元抬高统计量
     * 而同样失效。这里在噪声水平到峰值之间取若干阈值各提取一次，再按中心距离去重
     * （保留响应更强者）。强单元在高阈值处被干净地分离，弱单元在低阈值处被捕获；
     * 当单一阈值已经足够时各档结果重合，去重后与原来一致。
     *
     * @param noiseFloor 显式噪声基准；为 null 时按响应图的 `median + 3×1.4826×MAD` 估计。
     */
    fun multiThresholdBlobs(
        response: Mat,
        minimumArea: Int,
        maximumArea: Int,
        minimumBox: Int,
        maximumBox: Int,
        aspectRange: ClosedFloatingPointRange<Double> = 0.45..1.80,
        levels: Int = DEFAULT_THRESHOLD_LEVELS,
        noiseFloor: Double? = null,
        mergeDistancePx: Double = minimumBox.toDouble()
    ): List<GridCandidate> {
        require(response.type() == CvType.CV_8UC1) { "多阈值斑点提取要求 8 位单通道响应图" }
        val histogram = PgGridGrayHistogram.of(response)
        if (histogram.total == 0L) return emptyList()

        val base = noiseFloor ?: (histogram.median() + 3.0 * maxOf(1.0, 1.4826 * histogram.medianAbsoluteDeviation()))
        val peak = histogram.quantile(0.999)
        val safeBase = maxOf(base, 1e-6)
        val thresholds = if (peak <= safeBase) {
            listOf(safeBase)
        } else {
            geometricSpace(safeBase, peak, maxOf(2, levels))
        }

        // 响应图在各阈值档之间**不变**，因此像素只从 JNI 边界搬运一次。此前每档都重读
        // 一遍整幅响应图：1200×1200 的矫正图上是 6 次约 1.4 MB 的跨语言拷贝，纯属重复。
        val responsePixels = readUnsignedBytes(response)
        val collected = mutableListOf<GridCandidate>()
        val mask = Mat()
        try {
            thresholds.forEach { threshold ->
                Imgproc.threshold(response, mask, threshold, 255.0, Imgproc.THRESH_BINARY)
                collected += connectedComponentCandidates(
                    responsePixels = responsePixels,
                    mask = mask,
                    minimumBox = minimumBox,
                    maximumBox = maximumBox,
                    minimumArea = minimumArea,
                    maximumArea = maximumArea,
                    aspectRange = aspectRange,
                    applyEdgeGuard = false
                )
            }
        } finally {
            mask.release()
        }
        if (collected.isEmpty()) return emptyList()

        // 去重：同一单元会在多个阈值档各出现一次，保留响应最强的那次。
        val merged = mutableListOf<GridCandidate>()
        collected.sortedByDescending(GridCandidate::weight).forEach { candidate ->
            val separated = merged.all { kept ->
                hypot(candidate.point.x - kept.point.x, candidate.point.y - kept.point.y) > mergeDistancePx
            }
            if (separated) merged += candidate
        }
        return merged
    }

    /**
     * 从二值连通域提取候选中心与形态响应权重。
     *
     * 响应总和用一次标签扫描累加得到，而不是逐连通域做全图比较：阈值档数乘以连通域数
     * 会把后者退化成上千次全图扫描，是端侧单图耗时的大头。
     */
    private fun connectedComponentCandidates(
        responsePixels: IntArray,
        mask: Mat,
        minimumBox: Int,
        maximumBox: Int,
        minimumArea: Int,
        maximumArea: Int,
        aspectRange: ClosedFloatingPointRange<Double>,
        applyEdgeGuard: Boolean = true
    ): List<GridCandidate> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val componentCount =
                Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            if (componentCount <= 1) return emptyList()

            val width = mask.cols()
            val height = mask.rows()
            val edgeGuardX = if (applyEdgeGuard) width * EDGE_GUARD_RATIO else 0.0
            val edgeGuardY = if (applyEdgeGuard) height * EDGE_GUARD_RATIO else 0.0

            val pixelCount = width * height
            require(responsePixels.size == pixelCount) { "响应像素缓冲与掩膜尺寸不一致" }
            val labelBuffer = IntArray(pixelCount)
            labels.get(0, 0, labelBuffer)
            val componentSums = DoubleArray(componentCount)
            for (index in 0 until pixelCount) {
                componentSums[labelBuffer[index]] += responsePixels[index].toDouble()
            }

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
                if (aspect !in aspectRange) continue
                if (applyEdgeGuard) {
                    if (centerX !in edgeGuardX..(width - edgeGuardX)) continue
                    if (centerY !in edgeGuardY..(height - edgeGuardY)) continue
                }

                val strength = componentSums[index] / maxOf(area, 1) * sqrt(area.toDouble())
                candidates += GridCandidate(GridPoint(centerX, centerY), maxOf(strength, 1e-6))
            }
            return candidates
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    /** 只从内部 10%~90% 区域估计噪声基准，避开边框阶跃在顶帽图中形成的窄亮带。 */
    private fun interiorNoiseFloor(response: Mat): Double {
        val width = response.cols()
        val height = response.rows()
        val x0 = (width * 0.10).toInt()
        val x1 = (width * 0.90).toInt()
        val y0 = (height * 0.10).toInt()
        val y1 = (height * 0.90).toInt()
        val usable = x1 > x0 && y1 > y0
        val interior = if (usable) response.submat(y0, y1, x0, x1) else response
        return try {
            val histogram = PgGridGrayHistogram.of(interior)
            if (histogram.total == 0L) {
                0.0
            } else {
                histogram.median() + 3.0 * maxOf(1.0, 1.4826 * histogram.medianAbsoluteDeviation())
            }
        } finally {
            if (interior !== response) interior.release()
        }
    }

    /** 等比数列，语义与 `np.geomspace(start, stop, count)` 一致。 */
    private fun geometricSpace(start: Double, stop: Double, count: Int): List<Double> {
        if (count <= 1) return listOf(start)
        val logStart = ln(start)
        val logStop = ln(stop)
        return List(count) { index ->
            exp(logStart + (logStop - logStart) * index / (count - 1).toDouble())
        }
    }

    /**
     * 把 8 位单通道 Mat 一次性读成无符号像素数组。
     *
     * `Mat.get` 是 JNI 调用，在原图尺度上逐次读取会成为单图耗时的大头；把结果缓存下来
     * 复用可以让多阈值提取只付一次搬运成本。
     */
    private fun readUnsignedBytes(image: Mat): IntArray {
        require(image.type() == CvType.CV_8UC1) { "像素读取要求 8 位单通道输入" }
        val pixelCount = image.rows() * image.cols()
        val bytes = ByteArray(pixelCount)
        image.get(0, 0, bytes)
        return IntArray(pixelCount) { index -> bytes[index].toInt() and 0xFF }
    }

    private fun oddAtLeast(minimum: Int, raw: Int): Int {
        val candidate = maxOf(minimum, raw)
        return if (candidate % 2 == 0) candidate + 1 else candidate
    }
}
