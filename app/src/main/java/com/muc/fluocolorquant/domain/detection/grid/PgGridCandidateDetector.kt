package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 单个单元候选在矫正图中的中心与形态学响应强度。 */
internal data class GridCandidate(
    val point: GridPoint,
    val weight: Double
)

/**
 * PG-Grid 单元候选检测器。
 *
 * 与 Python 参考实现 `pg_grid._detect_dark_square_candidates` /
 * `_detect_bright_dot_candidates` / `_multi_threshold_blobs` 保持同一套几何闸值与
 * 阈值策略。这里只产出“图像上确实存在结构”的候选证据，不做任何晶格假设，
 * 晶格拟合与信任判定由调用方负责。
 */
internal object PgGridCandidateDetector {

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
        noiseFloor: Double? = null
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
                hypot(candidate.point.x - kept.point.x, candidate.point.y - kept.point.y) > minimumBox
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
