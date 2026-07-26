package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.hypot
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 一个主区域假设：四角按左上、右上、右下、左下排序，[score] 只用于同一路径内择优。 */
internal data class ChipRegionCandidate(
    val points: List<GridPoint>,
    val method: String,
    val score: Double = 0.0
)

/**
 * PG-Grid 主区域（芯片平面）检测。
 *
 * 三条检测路径各有系统性偏好，没有一条在所有成像条件下占优：
 * - **亮区路径**：面板本身可见时最准，荧光下常常只抓到局部亮块；
 * - **微弱基底轮廓**：独立于单元亮度分布，但会被棋盘/稀疏图案和不均匀基底带偏；
 * - **发光点云**：基底完全不可见时的唯一线索，但只有亮单元参与，暗单元成片缺失时
 *   框会偏向亮的那一侧。
 *
 * 因此本对象只负责**产出假设**，不做优先级裁决。调用方应通过 [iterateHypotheses]
 * 惰性取用，让每个假设各自走完晶格拟合后再用图像证据择优。兜底框不在假设之列——
 * 它不是证据，是所有路径都失败时的最后手段。
 */
internal object PgGridRegionDetector {

    const val METHOD_BRIGHT_REGION: String = "opencv_bright_region"
    const val METHOD_BRIGHT_REGION_WIDE: String = "opencv_bright_region_wide"
    const val METHOD_FAINT_SUBSTRATE: String = "opencv_faint_substrate"
    const val METHOD_EMITTING_DOTS: String = "opencv_emitting_dots"
    const val METHOD_FALLBACK_CENTER: String = "fallback_center"

    /** 目标平面必须落在的长宽比区间；三条路径共用同一形状先验。 */
    private val ASPECT_RANGE: ClosedFloatingPointRange<Double> = 0.35..2.8

    /** 亮面板亮度阈值的下限，避免整幅暗图把阈值压到噪声里。 */
    private const val MINIMUM_BRIGHTNESS_THRESHOLD: Double = 22.0

    /**
     * 惰性产出主区域假设，按可靠性顺序排列。
     *
     * 惰性很重要：亮区路径命中且证据充分时（多数常规成像），后面两个检测器根本不必
     * 运行。一次性全部计算会白白付出微弱基底与发光点云两次形态学检测的代价。
     */
    fun iterateHypotheses(bgr: Mat): Sequence<ChipRegionCandidate> {
        require(bgr.channels() == 3) { "主区域假设枚举需要 BGR 彩色图像" }
        val seen = hashSetOf<String>()
        // 刻意让每条路径各自申请并释放灰度图，而不是在序列外层持有一个 Mat：调用方在
        // 证据充分时会提前终止迭代，`sequence {}` 的 finally 在放弃迭代器时不会执行，
        // 那样会静默泄漏原图尺度的 Mat。多做的两次灰度转换远小于一次形态学检测。
        val builders: List<(Mat) -> ChipRegionCandidate?> = listOf(
            { image -> detectChipRegion(image).takeIf { it.method != METHOD_FALLBACK_CENTER } },
            ::detectRegionFromFaintSubstrate,
            ::detectRegionFromEmittingDots
        )
        return builders.asSequence()
            .mapNotNull { builder -> builder(bgr) }
            .filter { region -> seen.add(region.method) }
    }

    /** 微弱基底轮廓路径的独立入口，供仲裁与离线对照单独调用。 */
    fun detectRegionFromFaintSubstrate(bgr: Mat): ChipRegionCandidate? {
        val gray = Mat()
        return try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            detectRegionFromFaintSubstrate(gray, bgr.cols(), bgr.rows())
        } finally {
            gray.release()
        }
    }

    /** 发光点云路径的独立入口，供仲裁与离线对照单独调用。 */
    fun detectRegionFromEmittingDots(bgr: Mat): ChipRegionCandidate? {
        val gray = Mat()
        return try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            detectRegionFromEmittingDots(gray, bgr.cols(), bgr.rows())
        } finally {
            gray.release()
        }
    }

    /**
     * 单一结果入口：按可靠性顺序退化，供不需要多假设仲裁的调用方使用。
     *
     * 需要仲裁的生产链路请走 [iterateHypotheses]。
     */
    fun detectChipRegion(bgr: Mat): ChipRegionCandidate {
        require(bgr.channels() == 3) { "主区域检测需要 BGR 彩色图像" }
        val gray = Mat()
        return try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            detectChipRegion(gray, bgr.cols(), bgr.rows())
        } finally {
            gray.release()
        }
    }

    private fun detectChipRegion(gray: Mat, width: Int, height: Int): ChipRegionCandidate {
        return detectBrightRegion(gray, width, height)
            ?: detectRegionFromFaintSubstrate(gray, width, height)
            ?: detectRegionFromEmittingDots(gray, width, height)
            ?: fallbackCenterRegion(width, height)
    }

    /**
     * 亮度阈值 + 形态学闭运算把均匀背景下的目标平面合并为主轮廓。
     *
     * 阈值策略必须与“目标占整图多大比例”无关：用户裁掉四周背景是完全合理的操作，
     * 却会把目标占比从百分之十几推到百分之八十。高分位阈值按定义只保留最亮的固定
     * 比例像素，隐含“目标只占一小部分”的假设，背景被裁掉后阈值被迫抬高，掩膜会切在
     * 面板内部而不是面板边界；Otsu 最大化类间方差、不预设面积比例，对“暗背景 + 亮面板”
     * 这类双峰分布始终落在两峰之间。
     *
     * 因此这里**同时评估两档阈值的全部候选并统一评分**，而不是“高分位档有候选就直接
     * 返回”——那样 Otsu 档永远不会被评估，正是裁切后定位塌陷的根因。
     */
    private fun detectBrightRegion(gray: Mat, width: Int, height: Int): ChipRegionCandidate? {
        val blurred = Mat()
        val mask = Mat()
        val hierarchy = Mat()
        val contourMask = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 0.0)
            val percentileThreshold = PgGridGrayHistogram.of(blurred).quantile(0.94)
            val otsuProbe = Mat()
            val otsuThreshold = try {
                Imgproc.threshold(blurred, otsuProbe, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            } finally {
                otsuProbe.release()
            }

            val attempts = listOf(
                // 高分位档：保留历史行为，面积上限 20%（真实成像视野图中目标约占
                // 5%~12%，更大的候选通常是成像视野圆环或暗箱反光区域）。
                Triple(maxOf(MINIMUM_BRIGHTNESS_THRESHOLD, percentileThreshold), 0.20, METHOD_BRIGHT_REGION),
                // Otsu 档：面积无关阈值，上限放宽到 92% 以接受被裁到几乎只剩目标的图。
                Triple(
                    maxOf(MINIMUM_BRIGHTNESS_THRESHOLD, minOf(percentileThreshold, otsuThreshold)),
                    0.92,
                    METHOD_BRIGHT_REGION_WIDE
                )
            )

            val kernelSize = oddAtLeast(7, (minOf(width, height) * 0.008).toInt())
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            val imageArea = width.toDouble() * height
            var bestScore = Double.NEGATIVE_INFINITY
            var bestRect: RotatedRect? = null
            var bestMethod: String? = null
            var bestArea = 0.0
            try {
                attempts.forEach { (threshold, maximumAreaRatio, method) ->
                    val contours = arrayListOf<MatOfPoint>()
                    try {
                        Imgproc.threshold(blurred, mask, threshold, 255.0, Imgproc.THRESH_BINARY)
                        // 合并孔洞、流道、局部反光，但不要把圆形视野外圈也合进去。
                        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                        Imgproc.dilate(mask, mask, kernel)
                        Imgproc.findContours(
                            mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
                        )

                        // 显式标签：内层与外层都是 forEach，隐式 return@forEach 会产生歧义警告。
                        contours.forEach contour@{ contour ->
                            val area = Imgproc.contourArea(contour)
                            // 面积下限 1%：能容纳整个阵列的目标面板不可能更小。这条下限
                            // 专门挡住“只有少数单元很亮时它们连成的小块”——实测荧光样本里
                            // 这种误检只占整图 0.6%，一旦被采用，真值几乎全部落到矫正图外。
                            if (area < imageArea * 0.010 || area > imageArea * maximumAreaRatio) return@contour
                            val bounds = Imgproc.boundingRect(contour)
                            val aspect = bounds.width.toDouble() / maxOf(bounds.height, 1)
                            if (aspect !in ASPECT_RANGE) return@contour

                            val contour2f = MatOfPoint2f(*contour.toArray())
                            val rect = try {
                                Imgproc.minAreaRect(contour2f)
                            } finally {
                                contour2f.release()
                            }
                            val rectArea = rect.size.width * rect.size.height
                            if (rectArea < 1.0) return@contour
                            // 矩形填充度：目标平面是矩形，阈值切在边界上时轮廓接近矩形；
                            // 切在目标内部时轮廓沿亮度等值线破碎，填充度显著下降。该判据
                            // 只看形状，与目标占图比例无关，正是裁切场景所需。
                            val fillRatio = area / rectArea

                            contourMask.create(gray.size(), CvType.CV_8UC1)
                            contourMask.setTo(Scalar(0.0))
                            Imgproc.drawContours(contourMask, listOf(contour), -1, Scalar(255.0), -1)
                            val meanBrightness = Core.mean(gray, contourMask).`val`[0]

                            // 面积与亮度避免选到暗箱圆环反光，填充度避免选到目标内部的碎块。
                            val score = sqrt(area) * (meanBrightness + 1.0) * (0.35 + 0.65 * fillRatio)
                            if (score > bestScore) {
                                bestScore = score
                                bestRect = rect
                                bestMethod = method
                                bestArea = rectArea
                            }
                        }
                    } finally {
                        contours.forEach(MatOfPoint::release)
                    }
                }
            } finally {
                kernel.release()
            }

            val rect = bestRect ?: return null
            val method = bestMethod ?: return null
            // 适度外扩，确保目标点和目标平面边缘不会被裁掉。
            return ChipRegionCandidate(
                points = expandAndClip(orderQuadPoints(rect), width, height, 1.10),
                method = method,
                score = bestArea
            )
        } finally {
            contourMask.release()
            hierarchy.release()
            mask.release()
            blurred.release()
        }
    }

    /**
     * 由发光单元下方的微弱基底轮廓界定主区域（荧光成像首选路径）。
     *
     * 荧光成像中基底自发荧光通常只比暗背景亮 1~2 个灰度级，单像素上完全淹没在噪声里；
     * 但它是几十万像素的**连续区域**，只要先把亮单元擦掉、再做空间平均，这点差异就
     * 足够分离。
     *
     * 用形态学开运算而不是低通滤波：高斯模糊会把亮单元的能量摊到基底上，反而抬高基底
     * 读数、破坏基底与背景的对比；开运算的结构元只要大于单元尺寸，亮单元就被整体腐蚀掉，
     * 基底作为大面积平台被保留。
     *
     * 这个几何参考的关键优势是**独立于单元亮度分布**：发光点云路径会因为暗单元检测不到
     * 而把框缩到亮单元那一侧，基底轮廓不会。
     */
    private fun detectRegionFromFaintSubstrate(gray: Mat, width: Int, height: Int): ChipRegionCandidate? {
        val side = minOf(width, height)
        // 结构元必须大于单元尺寸，否则单元擦不干净。
        val kernelSize = oddAtLeast(21, (side * 0.030).toInt())
        val opened = Mat()
        val denoised = Mat()
        val normalized = Mat()
        val mask = Mat()
        val hierarchy = Mat()
        val contours = arrayListOf<MatOfPoint>()
        try {
            val openKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(gray, opened, Imgproc.MORPH_OPEN, openKernel)
            } finally {
                openKernel.release()
            }
            // 中值滤波压掉残余噪声，再按分位数拉伸——基底与背景的差异往往只有个位数
            // 灰度级，不拉伸则 Otsu 无法工作。
            Imgproc.medianBlur(opened, denoised, 9)
            val histogram = PgGridGrayHistogram.of(denoised)
            val low = histogram.quantile(0.02)
            val high = histogram.quantile(0.98)
            if (high - low < 1.0) return null
            val scale = 255.0 / (high - low)
            denoised.convertTo(normalized, CvType.CV_8UC1, scale, -low * scale)

            val otsuProbe = Mat()
            val otsuThreshold = try {
                Imgproc.threshold(normalized, otsuProbe, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            } finally {
                otsuProbe.release()
            }
            Imgproc.threshold(normalized, mask, otsuThreshold, 255.0, Imgproc.THRESH_BINARY)
            val closeKernel = Mat.ones(kernelSize, kernelSize, CvType.CV_8UC1)
            try {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel)
            } finally {
                closeKernel.release()
            }

            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val best = contours.maxByOrNull(Imgproc::contourArea) ?: return null
            val area = Imgproc.contourArea(best)
            val imageArea = width.toDouble() * height
            if (area < imageArea * 0.05 || area > imageArea * 0.95) return null

            val contour2f = MatOfPoint2f(*best.toArray())
            val rect = try {
                Imgproc.minAreaRect(contour2f)
            } finally {
                contour2f.release()
            }
            val aspect = rect.size.width / maxOf(rect.size.height, 1e-6)
            if (aspect !in ASPECT_RANGE) return null

            // 基底轮廓已经是面板边界（不像点云那样贴着最外圈单元中心），因此只做与
            // 亮面板路径一致的小幅外扩。
            return ChipRegionCandidate(
                points = expandAndClip(orderQuadPoints(rect), width, height, 1.02),
                method = METHOD_FAINT_SUBSTRATE,
                score = area
            )
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            mask.release()
            normalized.release()
            denoised.release()
            opened.release()
        }
    }

    /**
     * 由离散发光点云界定主区域（荧光/自发光成像路径）。
     *
     * 适用场景：暗背景 + 一片规则排布的发光单元，没有连续亮面板可供阈值分割。此时
     * 目标区域的物理定义就是“发光单元的分布范围”。
     *
     * 做法：顶帽增强小亮斑 → 多阈值提取点云 → 用点云的最小外接矩形作为主区域。多阈值
     * 是必需的：荧光阵列内部亮度可跨一两个数量级，单一阈值会漏掉弱单元，导致点云只
     * 覆盖亮的那部分、外接框严重偏小。
     */
    private fun detectRegionFromEmittingDots(gray: Mat, width: Int, height: Int): ChipRegionCandidate? {
        val side = minOf(width, height)
        val kernelSize = oddAtLeast(15, (side * 0.030).toInt())
        val tophat = Mat()
        try {
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble())
            )
            try {
                Imgproc.morphologyEx(gray, tophat, Imgproc.MORPH_TOPHAT, kernel)
            } finally {
                kernel.release()
            }
            if (Core.minMaxLoc(tophat).maxVal < 8.0) return null

            val sideSquared = side.toDouble() * side
            val blobs = PgGridCandidateDetector.multiThresholdBlobs(
                response = tophat,
                minimumArea = maxOf(4, (sideSquared * 2e-6).toInt()),
                maximumArea = maxOf(5, (sideSquared * 0.004).toInt()),
                minimumBox = maxOf(3, (side * 0.003).toInt()),
                maximumBox = maxOf(12, (side * 0.06).toInt()),
                aspectRange = 0.3..3.3
            )
            // 阵列至少 10×10，允许大量漏检，但太少就不足以界定区域。
            if (blobs.size < MINIMUM_CLOUD_POINTS) return null

            val kept = rejectIsolatedPoints(blobs.map(GridCandidate::point))
            if (kept.size < MINIMUM_CLOUD_POINTS) return null

            val cloud = MatOfPoint2f(*kept.map { Point(it.x, it.y) }.toTypedArray())
            val rect = try {
                Imgproc.minAreaRect(cloud)
            } finally {
                cloud.release()
            }
            val rectArea = rect.size.width * rect.size.height
            val imageArea = width.toDouble() * height
            if (rectArea < imageArea * 0.002 || rectArea > imageArea * 0.92) return null
            val aspect = rect.size.width / maxOf(rect.size.height, 1e-6)
            if (aspect !in ASPECT_RANGE) return null

            // 点云外接框贴着最外圈单元的中心（而不是面板边界），必须外扩出余量，否则
            // 矫正后最外圈单元贴边，会被后续轴选择的位置约束判为不合法。取 1.205 使
            // 阵列中心范围落在矫正图的 8.5%~91.5%，与亮面板路径后的版面一致。
            return ChipRegionCandidate(
                points = expandAndClip(orderQuadPoints(rect), width, height, 1.205),
                method = METHOD_EMITTING_DOTS,
                score = rectArea
            )
        } finally {
            tophat.release()
        }
    }

    /**
     * 剔除孤立点（热像素、孤立反光、图像边缘杂散）。
     *
     * 判据是“最近邻距离”而不是“到中心的距离”或单轴 IQR：阵列内的点彼此相距一个间距，
     * 而热像素/杂散点是孤立的，最近邻距离远大于间距。这利用了“阵列”这一结构先验，对
     * 少量极端离群点也灵敏——散布型判据会被多数真实点主导而失效，少数几个热像素足以
     * 把外接框撑大数百像素。
     */
    private fun rejectIsolatedPoints(points: List<GridPoint>): List<GridPoint> {
        if (points.size < 2) return points
        val nearest = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
        for (i in points.indices) {
            for (j in points.indices) {
                if (i == j) continue
                val distance = hypot(points[i].x - points[j].x, points[i].y - points[j].y)
                if (distance < nearest[i]) nearest[i] = distance
            }
        }
        val pitchEstimate = median(nearest.toList())
        val limit = maxOf(3.0, pitchEstimate * 2.5)
        return points.filterIndexed { index, _ -> nearest[index] <= limit }
    }

    /**
     * 目标平面定位失败时使用的中央兜底区域。
     *
     * 这不是理想定位，但能保证流程不直接失败，并由质量控制给出明确 warning——它是
     * 兜底而不是证据，因此不会进入区域假设仲裁。
     */
    fun fallbackCenterRegion(width: Int, height: Int): ChipRegionCandidate {
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
            method = METHOD_FALLBACK_CENTER
        )
    }

    /**
     * 把旋转矩形四角排序为左上、右上、右下、左下。
     *
     * 这个顺序对透视变换至关重要，否则矫正后的目标平面会翻转或扭曲。
     */
    fun orderQuadPoints(rectangle: RotatedRect): List<GridPoint> {
        val raw = Array(4) { Point() }
        rectangle.points(raw)
        val points = raw.map { GridPoint(it.x, it.y) }
        return listOf(
            points.minBy { it.x + it.y },
            points.maxBy { it.x - it.y },
            points.maxBy { it.x + it.y },
            points.minBy { it.x - it.y }
        )
    }

    /** 以四角形心为中心按 [scale] 外扩，并裁剪到图像边界内。 */
    fun expandAndClip(points: List<GridPoint>, width: Int, height: Int, scale: Double): List<GridPoint> {
        val centerX = points.map(GridPoint::x).average()
        val centerY = points.map(GridPoint::y).average()
        return points.map { point ->
            GridPoint(
                x = (centerX + (point.x - centerX) * scale).coerceIn(0.0, width - 1.0),
                y = (centerY + (point.y - centerY) * scale).coerceIn(0.0, height - 1.0)
            )
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun oddAtLeast(minimum: Int, raw: Int): Int {
        val candidate = maxOf(minimum, raw)
        return if (candidate % 2 == 0) candidate + 1 else candidate
    }

    /** 点云至少要有这么多点才足以界定区域；阵列最小规格 10×10 允许大量漏检。 */
    private const val MINIMUM_CLOUD_POINTS: Int = 24
}
