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
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
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
 *
 * V2.1 起主区域不再按固定优先级取一条：三条区域检测路径各有系统性偏好，因此改为
 * **惰性枚举多个假设 → 每个假设各自走完晶格链路 → 用图像证据仲裁**。仲裁分融合
 * 包围率、支撑率、观测率三项互补证据并按晶格残差扣分，另设切换保护，避免噪声证据
 * 上零点几个百分点的领先就推翻按可靠性排序的首选。
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

            val rectifiedWidth = config.resolvedRectifiedWidth()
            val rectifiedHeight = config.resolvedRectifiedHeight()
            // 闸值换算所用的行列数必须与矫正图较短边配对，见 gateCountFor 说明。
            val gateCount = PgGridCandidateDetector.gateCountFor(
                rows = config.rows,
                columns = config.columns,
                width = rectifiedWidth,
                height = rectifiedHeight
            )
            // 原图候选检测跨假设复用：各假设的区域跨度接近，闸值区间又有数倍余量，
            // 因此按极性缓存一次即可，不必为每个假设重复一遍全图形态学检测。
            val coverageCache = hashMapOf<GridTargetPolarity, List<GridCandidate>>()
            val solutions = mutableListOf<RegionSolution>()
            try {
                for (candidateRegion in PgGridRegionDetector.iterateHypotheses(originalBgr)) {
                    val solution = solveWithRegion(
                        original = originalBgr,
                        region = candidateRegion,
                        config = config,
                        rectifiedWidth = rectifiedWidth,
                        rectifiedHeight = rectifiedHeight,
                        gateCount = gateCount,
                        coverageCache = coverageCache
                    )
                    solutions += solution
                    // 短路：证据已经很强时不再展开后续假设。区域假设是惰性产出的，
                    // 因此这里提前结束会连带省下后面几个检测器的全部计算。
                    if (solution.coverage >= REGION_SHORT_CIRCUIT_COVERAGE &&
                        solution.lattice.candidateSupportRatio >= REGION_SHORT_CIRCUIT_SUPPORT
                    ) {
                        break
                    }
                }
                if (solutions.isEmpty()) {
                    // 三条证据路径全部失败：只能使用中央兜底框，并由 QC 如实告警。
                    solutions += solveWithRegion(
                        original = originalBgr,
                        region = PgGridRegionDetector.fallbackCenterRegion(
                            originalBgr.cols(),
                            originalBgr.rows()
                        ),
                        config = config,
                        rectifiedWidth = rectifiedWidth,
                        rectifiedHeight = rectifiedHeight,
                        gateCount = gateCount,
                        coverageCache = coverageCache
                    )
                }

                val best = selectRegionSolution(solutions)
                val region = best.region
                val rectification = best.rectification
                val actualPolarity = best.polarity
                val lattice = best.lattice
                val hypotheses = solutions.map { solution ->
                    GridRegionHypothesis(
                        method = solution.region.method,
                        coverage = solution.coverage,
                        support = solution.lattice.candidateSupportRatio,
                        score = solution.score,
                        selected = solution === best
                    )
                }
                // 包围率参与信任判定，但只用很保守的门限。它的绝对值有图像相关的基线：
                // 单元之外还有连接器、通道线等结构的版型天然低于纯净版型，因此高门限会
                // 误伤正确结果。0.55 只拦截“网格漏掉了近半已检出单元”这类明显截断；
                // 细粒度的优劣留给假设之间的相对比较。
                val coverageTruncated = best.coverage < REGION_MINIMUM_COVERAGE &&
                    lattice.supportCheck != GridSupportCheck.UNAVAILABLE
                val trusted = lattice.trusted && !coverageTruncated

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
                val frameQc = buildFrameQc(
                    region = region,
                    lattice = lattice,
                    quality = frameQuality,
                    coverage = best.coverage,
                    coverageTruncated = coverageTruncated
                )

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
                        trusted = trusted,
                        observedRatio = lattice.observedRatio,
                        geometryRmsePx = lattice.geometryRmsePx,
                        inlierCount = lattice.inlierCount,
                        outlierCount = lattice.outlierCount,
                        meanConfidence = localizedSites.map(GridLocalizedSite::confidence).average(),
                        supportCheck = lattice.supportCheck,
                        gridCoverageRatio = best.coverage,
                        regionHypotheses = hypotheses,
                        phantomEdge = lattice.phantomEdge
                    ),
                    frameQc = frameQc,
                    locatorName = LOCATOR_NAME,
                    locatorVersion = LOCATOR_VERSION
                ).requireValid()
            } finally {
                // 未被选中的假设同样持有矫正图和两组矩阵，必须一并释放。
                solutions.forEach { solution -> solution.rectification.release() }
            }
        } finally {
            rgba.release()
            originalBgr.release()
        }
    }

    /**
     * 在给定主区域假设下走完整条几何链路，并给出可比较的证据分。
     *
     * 评分融合三项互补证据（都已归一到 0~1）：
     * - **包围率**：原图中已检出单元被网格覆盖的比例，抓“区域框截断”；
     * - **支撑率**：网格点有真实单元支撑的比例，抓“网格整体错位/虚构”；
     * - **观测率**：获得局部图像证据的点位比例，抓“拟合缺乏观测支撑”。
     *
     * 再按晶格残差（相对间距）扣分，抓“勉强拟合但几何变形”。
     */
    private fun solveWithRegion(
        original: Mat,
        region: ChipRegionCandidate,
        config: PgGridLocatorConfig,
        rectifiedWidth: Int,
        rectifiedHeight: Int,
        gateCount: Int,
        coverageCache: MutableMap<GridTargetPolarity, List<GridCandidate>>
    ): RegionSolution {
        val rectification = rectifyChip(
            image = original,
            region = region,
            outputWidth = rectifiedWidth,
            outputHeight = rectifiedHeight
        )
        val polarityFit = fitLatticeWithAutomaticPolarity(
            rectified = rectification.image,
            rows = config.rows,
            columns = config.columns,
            width = rectifiedWidth,
            height = rectifiedHeight,
            marginRatio = config.marginRatio,
            maximumResidualPitchRatio = config.maximumResidualPitchRatio,
            supportDistancePitchRatio = config.candidateSupportDistancePitchRatio,
            preferredPolarity = config.targetPolarity,
            gateCount = gateCount
        )
        val lattice = polarityFit.lattice

        // 包围率必须在比区域框更大的视野里测量，否则看不到被框切掉的单元。做法是在
        // **原图**里检测一次单元，再把网格点反投影到原图坐标系比较，而不是为每个假设
        // 生成一张放大的矫正图——后者要多付一次透视变换加一次重复检测。
        val inverseMatrix = matToRowMajor(rectification.inverseMatrix)
        val projected = lattice.points.map { localized ->
            RegularGridGeometry.project(inverseMatrix, localized.point)
        }
        val originalPitch = RegularGridGeometry.estimatePitch(projected, config.rows, config.columns)
        val originalRepresentativePitch = minimumPositivePitch(
            pitch = originalPitch,
            width = original.cols(),
            height = original.rows(),
            rows = config.rows,
            columns = config.columns
        )
        val originalCandidates = cachedOriginalCandidates(
            original = original,
            polarity = polarityFit.polarity,
            region = region,
            gateCount = gateCount,
            cache = coverageCache
        )
        val coverage = measureGridCoverageRatio(
            gridPoints = projected,
            candidates = originalCandidates,
            pitch = maxOf(originalRepresentativePitch, 1.0)
        )

        val score = PgGridRegionArbiter.score(
            coverage = coverage,
            support = lattice.candidateSupportRatio,
            observed = lattice.observedRatio,
            residualRatio = (lattice.geometryRmsePx ?: 0.0) / maxOf(lattice.pitchPx, 1e-6)
        )

        return RegionSolution(
            region = region,
            rectification = rectification,
            polarity = polarityFit.polarity,
            lattice = lattice,
            coverage = coverage,
            score = score
        )
    }

    /**
     * 在多个区域假设的求解结果中择优，带切换保护。
     *
     * [solutions] 按可靠性顺序给出（首项为默认假设）。仲裁分是几项带噪证据的加权和，
     * 零点几个百分点的领先不足以支持切换；最优分低于最低证据线时说明没有任何假设拿到
     * 足够证据——通常是候选检测在该图上整体失效，让包围率与支撑率同时归零。
     * “证据缺失”不等于“证据为负”，此时切换是赌博，应保留按可靠性排序的首选。
     */
    private fun selectRegionSolution(solutions: List<RegionSolution>): RegionSolution {
        return PgGridRegionArbiter.select(solutions, RegionSolution::score)
    }

    /**
     * 在原图坐标系检测单元候选，跨区域假设复用。
     *
     * 检测器的几何闸值是相对“标准矫正图边长”定义的，而原图里单元要小得多（实拍图
     * 4000px 幅面上单元只有约 20px），直接套用会把真实单元全部过滤掉。因此用区域框在
     * 原图中的跨度作为尺度参考——它正是矫正图边长对应的原图长度。
     *
     * 包围率的判定容差是 0.35 个间距，用不着全分辨率：手机原图动辄 4000px 幅面，直接
     * 在上面做形态学检测会成为单图耗时的大头。先降采样再检测，最后把坐标缩放回原图尺度。
     */
    private fun cachedOriginalCandidates(
        original: Mat,
        polarity: GridTargetPolarity,
        region: ChipRegionCandidate,
        gateCount: Int,
        cache: MutableMap<GridTargetPolarity, List<GridCandidate>>
    ): List<GridCandidate> {
        cache[polarity]?.let { return it }

        val width = original.cols()
        val height = original.rows()
        val scale = minOf(1.0, COVERAGE_DETECT_MAX_SIDE / maxOf(width, height).toDouble())
        val scaled = Mat()
        val candidates = try {
            val source = if (scale < 1.0) {
                Imgproc.resize(
                    original,
                    scaled,
                    Size(
                        maxOf(1.0, floor(width * scale)),
                        maxOf(1.0, floor(height * scale))
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
                scaled
            } else {
                original
            }
            val xs = region.points.map(GridPoint::x)
            val ys = region.points.map(GridPoint::y)
            val span = maxOf(
                maxOf(xs.max() - xs.min(), ys.max() - ys.min()),
                1.0
            ) * scale
            val detected = when (polarity) {
                GridTargetPolarity.DARK -> PgGridCandidateDetector.detectDarkSquareCandidates(
                    bgr = source,
                    scaleSide = span,
                    gateCount = gateCount
                )

                GridTargetPolarity.BRIGHT -> PgGridCandidateDetector.detectBrightDotCandidates(
                    bgr = source,
                    scaleSide = span,
                    gateCount = gateCount
                )
            }
            if (scale < 1.0) {
                detected.map { candidate ->
                    candidate.copy(
                        point = GridPoint(candidate.point.x / scale, candidate.point.y / scale)
                    )
                }
            } else {
                detected
            }
        } finally {
            scaled.release()
        }
        cache[polarity] = candidates
        return candidates
    }

    /**
     * 图中被检测到的单元有多大比例被网格覆盖。
     *
     * 这是候选支撑率的**反方向**判据，两者合起来才完整：支撑率问“网格点旁边有没有真实
     * 单元”，对被区域框切掉的整行无感——那些单元根本没进矫正图，也就不会有网格点去问
     * 它们；包围率问“图里检测到的单元有没有被网格覆盖”，因此能直接抓住区域框截断。
     *
     * 只统计紧邻网格的候选：判据要回答的是“区域框外还有没有本该属于阵列的单元”，而不是
     * “图里还有没有别的亮/暗结构”。远处的连接器、螺丝、反光与阵列无关，把它们计入会让
     * 包围率无谓地偏低。
     */
    private fun measureGridCoverageRatio(
        gridPoints: List<GridPoint>,
        candidates: List<GridCandidate>,
        pitch: Double
    ): Double {
        if (gridPoints.isEmpty() || candidates.isEmpty()) return 0.0
        val minimumX = gridPoints.minOf(GridPoint::x) - COVERAGE_NEIGHBOURHOOD_PITCH * pitch
        val maximumX = gridPoints.maxOf(GridPoint::x) + COVERAGE_NEIGHBOURHOOD_PITCH * pitch
        val minimumY = gridPoints.minOf(GridPoint::y) - COVERAGE_NEIGHBOURHOOD_PITCH * pitch
        val maximumY = gridPoints.maxOf(GridPoint::y) + COVERAGE_NEIGHBOURHOOD_PITCH * pitch
        val near = candidates.filter { candidate ->
            candidate.point.x in minimumX..maximumX && candidate.point.y in minimumY..maximumY
        }
        if (near.isEmpty()) return 0.0

        val tolerance = maxOf(3.0, pitch * COVERAGE_TOLERANCE_PITCH_RATIO)
        val covered = near.count { candidate ->
            gridPoints.any { point -> distance(point, candidate.point) <= tolerance }
        }
        return covered.toDouble() / near.size
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

    /**
     * 同时尝试暗目标与亮目标，并用真实候选晶格是否成立裁决单元极性。
     *
     * `targetPolarity` 过去被当成由网格规格决定的硬参数，导致 15×15 EL 背光实拍图被
     * 强制按亮点处理。实际极性取决于成像方式：同一规格既可能是亮背景上的暗单元，也
     * 可能是暗背景上的亮单元。这里与 Python 参考实现保持同一决策顺序：
     *
     * 1. 暗/亮候选都提取，按[综合证据][orderPolaritiesByEvidence]排序后依次尝试；
     * 2. 只有候选轴与稳健单应真正成立才立即裁决——形状过滤过的候选无法从反极性的
     *    间隙结构里凑出合法晶格，误判风险低；
     * 3. 候选路径两极都失败时进入投影兜底，并用“点位 vs 间隙”对比度直接仲裁；
     * 4. 对比度也给不出正向证据时，保留排序首选的结果，由候选支撑率检查标记低置信。
     *
     * 第 3 步不能只信排序：候选拟合都失败说明候选证据本身不可靠（重模糊下两极候选数
     * 都远离理论值），此时排序依据已失去意义；而对比度是在候选网格位置上读取的图像
     * 证据，恰好能区分真实晶格与互补晶格。
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
        preferredPolarity: GridTargetPolarity,
        gateCount: Int
    ): PolarityFit {
        val expectedCount = rows * columns
        val hint = polarityHintWithStrength(rectified)

        // 候选检测按需求值并记忆化。亮点检测器现在是六档多阈值提取，是整条链路里最贵的
        // 一步；而提示显著时排序只看提示、根本不看候选数量，此时把两种极性都算出来纯属
        // 浪费。首选极性直接拟合成功（常规成像的主路径）时，另一极性一次都不会被检测。
        val candidateCache = HashMap<GridTargetPolarity, List<GridCandidate>>(2)
        fun candidatesFor(polarity: GridTargetPolarity): List<GridCandidate> =
            candidateCache.getOrPut(polarity) {
                when (polarity) {
                    GridTargetPolarity.DARK -> PgGridCandidateDetector.detectDarkSquareCandidates(
                        bgr = rectified,
                        gateCount = gateCount
                    )

                    GridTargetPolarity.BRIGHT -> PgGridCandidateDetector.detectBrightDotCandidates(
                        bgr = rectified,
                        gateCount = gateCount
                    )
                }
            }

        val order = orderPolaritiesByEvidence(
            expectedCount = expectedCount,
            hint = hint,
            preferredPolarity = preferredPolarity,
            candidateCountFor = { polarity -> candidatesFor(polarity).size }
        )

        order.forEach { polarity ->
            val fitted = fitLattice(
                rectified = rectified,
                candidates = candidatesFor(polarity),
                polarity = polarity,
                rows = rows,
                columns = columns,
                width = width,
                height = height,
                marginRatio = marginRatio,
                maximumResidualPitchRatio = maximumResidualPitchRatio,
                supportDistancePitchRatio = supportDistancePitchRatio,
                useProjectionFallback = false
            )
            if (fitted.candidateLatticeApplied) {
                return PolarityFit(
                    polarity = polarity,
                    lattice = recoverLocalEvidence(
                        rectified = rectified,
                        lattice = fitted,
                        candidates = candidatesFor(polarity),
                        polarity = polarity,
                        rows = rows,
                        columns = columns,
                        supportDistancePitchRatio = supportDistancePitchRatio
                    )
                )
            }
        }

        // 投影兜底：两个极性各拟合一次，用“点位 vs 间隙”对比度直接仲裁。
        val gray = Mat()
        val projections = try {
            Imgproc.cvtColor(rectified, gray, Imgproc.COLOR_BGR2GRAY)
            order.mapNotNull { polarity ->
                val projected = fitProjectionGrid(
                    rectified = rectified,
                    polarity = polarity,
                    rows = rows,
                    columns = columns,
                    marginRatio = marginRatio
                ) ?: return@mapNotNull null
                val contrast = measureGridPolarityContrast(gray, projected, rows, columns)
                // 对比度符号与该极性一致时才算作正向证据。
                val evidence = if (polarity == GridTargetPolarity.BRIGHT) contrast else -contrast
                ProjectionEvidence(polarity = polarity, points = projected, evidence = evidence)
            }
        } finally {
            gray.release()
        }

        val chosen = projections.maxByOrNull(ProjectionEvidence::evidence)
        // 两个极性都没有正向对比度证据时保留排序首选，由支撑率检查标记低置信；
        // 完全没有投影结果时退回均分网格。
        val resolved = when {
            chosen != null && chosen.evidence > 0.0 -> chosen
            else -> projections.firstOrNull { it.polarity == order.first() } ?: chosen
        }
        val fallbackPolarity = resolved?.polarity ?: order.first()
        val fallbackPoints = resolved?.points ?: RegularGridGeometry.generate(
            rows = rows,
            columns = columns,
            width = width.toDouble(),
            height = height.toDouble(),
            marginRatio = marginRatio
        )
        val fallbackCandidates = candidatesFor(fallbackPolarity)
        return PolarityFit(
            polarity = fallbackPolarity,
            lattice = recoverLocalEvidence(
                rectified = rectified,
                lattice = unadjustedLattice(
                    points = fallbackPoints,
                    candidates = fallbackCandidates,
                    rows = rows,
                    columns = columns,
                    supportDistancePitchRatio = supportDistancePitchRatio
                ),
                candidates = fallbackCandidates,
                polarity = fallbackPolarity,
                rows = rows,
                columns = columns,
                supportDistancePitchRatio = supportDistancePitchRatio
            )
        )
    }

    /**
     * 按证据强度给两种极性排序，返回优先尝试顺序。
     *
     * 两条证据的可靠性并不对等：
     * - **候选数量接近理论单元数**：直觉上合理，但形态学过滤的偶然性让它在实拍图上
     *   噪声很大（同一张图不同裁切下亮候选实测 79→164）；
     * - **内部“均值 vs 中位数”统计**：物理依据扎实（单元占面积远小于一半），实测符号
     *   在全部样例上都正确，但强度可能很弱。
     *
     * 因此提示显著时以提示为主键、候选数为次键；提示微弱时反过来。最终裁决仍是“晶格
     * 能否拟合成功”，本函数只决定尝试顺序。
     *
     * [candidateCountFor] 刻意设计为惰性回调而不是直接接收候选集合：提示显著时排序
     * 只依赖提示，一次都不会调用它，从而让调用方跳过另一极性的候选检测。
     */
    private fun orderPolaritiesByEvidence(
        candidateCountFor: (GridTargetPolarity) -> Int,
        expectedCount: Int,
        hint: PolarityHint,
        preferredPolarity: GridTargetPolarity
    ): List<GridTargetPolarity> {
        val other = if (hint.polarity == GridTargetPolarity.DARK) {
            GridTargetPolarity.BRIGHT
        } else {
            GridTargetPolarity.DARK
        }
        if (hint.strength >= POLARITY_HINT_SIGNIFICANT) return listOf(hint.polarity, other)
        return listOf(GridTargetPolarity.DARK, GridTargetPolarity.BRIGHT).sortedWith(
            compareBy<GridTargetPolarity> { polarity ->
                abs(candidateCountFor(polarity) - expectedCount)
            }.thenBy { polarity ->
                if (polarity == hint.polarity) 0 else 1
            }.thenBy { polarity ->
                // 载体档案的历史偏好只在图像证据完全并列时才起作用。
                if (polarity == preferredPolarity) 0 else 1
            }
        )
    }

    /**
     * 用阵列内部区域的灰度分布估计极性提示及其强度。
     *
     * 单元只占面板少数面积（10×10 约 11%、15×15 约 18%），背景决定灰度中位数，单元把
     * 均值拉向自己一侧（暗单元 → 均值 < 中位数）。忽略外围 10% 可避免透视矫正边带、
     * 边框和固定装置干扰统计。该统计量对模糊不敏感，是候选检测器整体失效时仍然可用的
     * 极性证据。
     *
     * 强度取 `|均值 − 中位数|`。实测全部样例的符号都正确，但强度差异很大（实拍暗单元
     * 8.9~34.3；小亮点合成图仅 0.9），因此强度决定这条证据能否压过候选数量证据。
     */
    private fun polarityHintWithStrength(rectified: Mat): PolarityHint {
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
                val median = PgGridGrayHistogram.of(interior).median()
                val delta = mean - median
                PolarityHint(
                    strength = abs(delta),
                    polarity = if (delta < 0.0) GridTargetPolarity.DARK else GridTargetPolarity.BRIGHT
                )
            } finally {
                if (interior !== gray) interior.release()
            }
        } finally {
            gray.release()
        }
    }

    /**
     * 测量候选网格的“点位 vs 间隙”对比度。
     *
     * 正值表示网格点比相邻点位的中点更亮（亮单元），负值表示更暗（暗单元）。
     *
     * 这是极性歧义的最终仲裁依据：暗单元阵列的亮间隙本身也构成规则晶格（互补晶格），
     * 投影峰同样整齐，仅凭规则性无法区分；但两者的点位落处对比度符号恰好相反。相比
     * 候选数量或全局灰度统计，本判据直接读取图像在候选网格位置上的证据，因此在候选
     * 检测退化（重模糊）时仍然可用。
     */
    private fun measureGridPolarityContrast(
        gray: Mat,
        points: List<GridPoint>,
        rows: Int,
        columns: Int
    ): Double {
        if (rows < 2 || columns < 2 || points.size != rows * columns) return 0.0
        val gaps = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns - 1) {
                    val left = points[row * columns + column]
                    val right = points[row * columns + column + 1]
                    add(GridPoint((left.x + right.x) / 2.0, (left.y + right.y) / 2.0))
                }
            }
            for (row in 0 until rows - 1) {
                for (column in 0 until columns) {
                    val top = points[row * columns + column]
                    val bottom = points[(row + 1) * columns + column]
                    add(GridPoint((top.x + bottom.x) / 2.0, (top.y + bottom.y) / 2.0))
                }
            }
        }
        return sampleMedianGray(gray, points) - sampleMedianGray(gray, gaps)
    }

    /** 取一组采样点处灰度的中位数；越界点直接跳过，全部越界时返回 0。 */
    private fun sampleMedianGray(gray: Mat, points: List<GridPoint>): Double {
        val buffer = ByteArray(1)
        val values = mutableListOf<Double>()
        points.forEach { point ->
            val x = point.x.roundToInt()
            val y = point.y.roundToInt()
            if (x in 0 until gray.cols() && y in 0 until gray.rows()) {
                gray.get(y, x, buffer)
                values += (buffer[0].toInt() and 0xFF).toDouble()
            }
        }
        return medianDouble(values)
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
     *
     * [useProjectionFallback] 为 false 时，候选路径失败只如实返回“候选晶格未成立”，
     * 由调用方统一进入带对比度仲裁的投影兜底；这样避免在极性尚未裁决前就用某一极性的
     * 投影结果污染判断。
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
        supportDistancePitchRatio: Double,
        useProjectionFallback: Boolean = true
    ): LatticeFit {
        val expectedCount = rows * columns
        fun fallbackPoints(): List<GridPoint> {
            val projected = if (useProjectionFallback) {
                fitProjectionGrid(
                    rectified = rectified,
                    polarity = polarity,
                    rows = rows,
                    columns = columns,
                    marginRatio = marginRatio
                )
            } else {
                null
            }
            return projected ?: RegularGridGeometry.generate(
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

        val inlierCount = finalPoints.count { it.source == GridPointSource.CANDIDATE_REFINED }
        val outlierCount = expectedCount - inlierCount
        val trust = evaluateLatticeTrust(
            points = finalPoints.map(LocalizedPoint::point),
            candidates = candidates,
            rows = rows,
            columns = columns,
            pitch = matchingPitch,
            supportDistance = matchingThreshold
        )
        return LatticeFit(
            points = finalPoints,
            candidateSupportRatio = trust.supportRatio,
            supportCheck = trust.supportCheck,
            trusted = trust.trusted,
            observedRatio = inlierCount.toDouble() / expectedCount,
            geometryRmsePx = fit.inlierRmsePx,
            pitchPx = matchingPitch,
            inlierCount = inlierCount,
            outlierCount = outlierCount,
            candidateLatticeApplied = true,
            phantomEdge = trust.phantomEdge
        )
    }

    /**
     * 支撑率三态判定 + 幻影边缘一票否决。
     *
     * 支撑率三态：
     * - `UNAVAILABLE`：该规格没有候选检测器，检查不适用；
     * - `INCONCLUSIVE`：检测器整体几乎无候选（如重模糊、单元尺寸超出闸值），而能走到
     *   这里说明逐点精修证据已充分——缺席的是**检查手段**而非网格质量，不据此判不可信；
     * - `OK`：候选充足，支撑率低于门限即判不可信（防规则但错误的网格）。
     *
     * 幻影边缘不参与三态判定而是直接推翻信任：它抓的正是支撑率结构性看不见的那种错误。
     */
    private fun evaluateLatticeTrust(
        points: List<GridPoint>,
        candidates: List<GridCandidate>,
        rows: Int,
        columns: Int,
        pitch: Double,
        supportDistance: Double
    ): LatticeTrust {
        val expectedCount = rows * columns
        val supportRatio = candidateSupportRatio(points, candidates, supportDistance)
        val minimumCheckCandidates = maxOf(
            MINIMUM_SUPPORT_CHECK_CANDIDATES,
            (expectedCount * MINIMUM_SUPPORT_CHECK_CANDIDATE_RATIO).toInt()
        )
        val supportCheck = when {
            candidates.size < minimumCheckCandidates -> GridSupportCheck.INCONCLUSIVE
            else -> GridSupportCheck.OK
        }
        val phantomEdge = PgGridLatticeIntegrity.detectPhantomEdge(
            points = points,
            rows = rows,
            columns = columns,
            candidates = candidates,
            pitch = pitch
        )
        val trustedBySupport = when (supportCheck) {
            GridSupportCheck.OK -> supportRatio >= TRUSTED_SUPPORT_RATIO
            else -> true
        }
        return LatticeTrust(
            supportRatio = supportRatio,
            supportCheck = supportCheck,
            phantomEdge = phantomEdge,
            trusted = trustedBySupport && phantomEdge.flagged == null
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
        val trust = evaluateLatticeTrust(
            points = points,
            candidates = candidates,
            rows = rows,
            columns = columns,
            pitch = representative,
            supportDistance = representative * supportDistancePitchRatio
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
            candidateSupportRatio = trust.supportRatio,
            supportCheck = trust.supportCheck,
            // 平差未执行时不能给出信任背书：这里只保留支撑与幻影证据，信任状态由
            // recoverLocalEvidence 在真正找回局部证据后重新判定。
            trusted = false,
            observedRatio = 0.0,
            geometryRmsePx = null,
            pitchPx = representative,
            inlierCount = 0,
            outlierCount = 0,
            candidateLatticeApplied = false,
            phantomEdge = trust.phantomEdge
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
        candidates: List<GridCandidate>,
        polarity: GridTargetPolarity,
        rows: Int,
        columns: Int,
        supportDistancePitchRatio: Double
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
            // 点位在精修中会移动，因此支撑率与幻影边缘必须基于恢复后的点位重算，
            // 不能沿用初始晶格的旧结论。
            val trust = evaluateLatticeTrust(
                points = recoveredPoints.map(LocalizedPoint::point),
                candidates = candidates,
                rows = rows,
                columns = columns,
                pitch = representativePitch,
                supportDistance = representativePitch * supportDistancePitchRatio
            )
            return lattice.copy(
                points = recoveredPoints,
                candidateSupportRatio = trust.supportRatio,
                supportCheck = trust.supportCheck,
                phantomEdge = trust.phantomEdge,
                // 轴候选不足时初始对象会标记 trusted=false；如果投影初值让局部重采样
                // 找回了足够多的真实结构，必须重新判定可信度，不能把旧降级状态永久
                // 带到最终结果。支撑三态与局部观测两项都通过才恢复可信。
                trusted = trust.trusted && recoveredObservedRatio >= MINIMUM_OBSERVED_RATIO,
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
        val centerX = width / 2.0
        val centerY = height / 2.0
        // 候选坐标只读一次并去中心化：角度扫描共 49 档，逐档重建 GridPoint 列表会在
        // 一次定位里产生十万量级的短命对象，GC 抖动比三角函数本身还贵。这里改为在
        // 复用的 DoubleArray 上就地旋转，数值与逐点 rotate() 完全一致。
        val count = candidates.size
        val offsetX = DoubleArray(count)
        val offsetY = DoubleArray(count)
        candidates.forEachIndexed { index, candidate ->
            offsetX[index] = candidate.point.x - centerX
            offsetY[index] = candidate.point.y - centerY
        }
        val rotatedX = DoubleArray(count)
        val rotatedY = DoubleArray(count)

        var bestAngle = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var angle = -MAX_ROTATION_DEGREES
        while (angle <= MAX_ROTATION_DEGREES + 1e-9) {
            val radians = Math.toRadians(angle)
            val cosine = cos(radians)
            val sine = sin(radians)
            for (index in 0 until count) {
                rotatedX[index] = offsetX[index] * cosine - offsetY[index] * sine + centerX
                rotatedY[index] = offsetX[index] * sine + offsetY[index] * cosine + centerY
            }
            val score = histogramSharpness(rotatedX, width.toDouble()) +
                histogramSharpness(rotatedY, height.toDouble())
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += ROTATION_STEP_DEGREES
        }
        return bestAngle
    }

    private fun histogramSharpness(values: DoubleArray, length: Double): Double {
        val binWidth = maxOf(4.0, length * 0.01)
        val binCount = maxOf(4, (length / binWidth).toInt())
        val histogram = IntArray(binCount)
        for (value in values) {
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

        // 间距接受区间按**行列数**而不是画幅边长定义：间距正比于 length/(count-1)，
        // 按边长定区间就隐含了 count≈15，会让 10×10 的合法轴被整体拒绝并退化为均分网格。
        val pitchBounds = PgGridCandidateDetector.axisPitchBounds(length.toDouble(), count)
        val minimumPitch = pitchBounds.start
        val maximumPitch = pitchBounds.endInclusive
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
        quality: FrameQualityMetrics,
        coverage: Double,
        coverageTruncated: Boolean
    ): List<GridFrameQcIssue> = buildList {
        if (region.method == PgGridRegionDetector.METHOD_FALLBACK_CENTER) {
            add(GridFrameQcIssue(GridFrameQcCode.CHIP_REGION_FALLBACK, GridQcSeverity.WARNING))
        }
        if (coverageTruncated) {
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.GRID_COVERAGE_LOW,
                    severity = GridQcSeverity.FAILURE,
                    measuredValue = coverage,
                    threshold = REGION_MINIMUM_COVERAGE
                )
            )
        }
        lattice.phantomEdge.flagged?.let { edge ->
            // 幻影边缘是独立的一票否决，必须以 FAILURE 呈现：定位可能整体错位一个间距，
            // 此时逐孔信号会系统性地取自相邻单元。
            add(
                GridFrameQcIssue(
                    code = GridFrameQcCode.PHANTOM_LATTICE_EDGE,
                    severity = GridQcSeverity.FAILURE,
                    measuredValue = lattice.phantomEdge.flaggedOverhang,
                    threshold = PHANTOM_EDGE_OVERHANG_THRESHOLD
                )
            )
            Log.w(FRAME_QUALITY_LOG_TAG, "疑似整体错位一个间距：edge=$edge phantom=${lattice.phantomEdge}")
        }
        // 支撑率三态：检查手段缺席（INCONCLUSIVE）时不能报“支撑不足”，否则会把
        // “没法检查”误传达为“网格错了”。
        if (lattice.supportCheck == GridSupportCheck.OK &&
            lattice.candidateSupportRatio < TRUSTED_SUPPORT_RATIO
        ) {
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

    /** 一个区域假设走完整条几何链路后的完整结果与可比较的证据分。 */
    private data class RegionSolution(
        val region: ChipRegionCandidate,
        val rectification: RectificationResult,
        val polarity: GridTargetPolarity,
        val lattice: LatticeFit,
        val coverage: Double,
        val score: Double
    )

    /** 极性统计提示及其强度；强度决定这条证据能否压过候选数量证据。 */
    private data class PolarityHint(
        val strength: Double,
        val polarity: GridTargetPolarity
    )

    /** 一个极性下的投影兜底网格及其“点位 vs 间隙”对比度证据。 */
    private data class ProjectionEvidence(
        val polarity: GridTargetPolarity,
        val points: List<GridPoint>,
        val evidence: Double
    )

    /** 支撑率三态、幻影边缘与最终信任结论。 */
    private data class LatticeTrust(
        val supportRatio: Double,
        val supportCheck: GridSupportCheck,
        val phantomEdge: GridPhantomEdgeDiagnostics,
        val trusted: Boolean
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
        val supportCheck: GridSupportCheck,
        val trusted: Boolean,
        val observedRatio: Double,
        val geometryRmsePx: Double?,
        /** 矫正图中的代表性单元间距，供区域仲裁把残差归一化到间距单位。 */
        val pitchPx: Double,
        val inlierCount: Int,
        val outlierCount: Int,
        /** true 表示候选轴和稳健单应均真实成立；false 表示投影或规则均分兜底。 */
        val candidateLatticeApplied: Boolean,
        val phantomEdge: GridPhantomEdgeDiagnostics
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

        /**
         * 处理器版本。
         *
         * 2.2.0 引入区域假设联合选择、闸值按行列数锚定、极性证据加权、支撑率三态和
         * 幻影边缘否决。定位结果会因此与 2.1.0 不同，因此必须提升版本并冻结进运行快照；
         * 历史运行仍按其冻结的旧版本解释，不重新定位。
         */
        const val LOCATOR_VERSION: String = "2.2.0"
        const val FRAME_QUALITY_LOG_TAG: String = "PgGridFrameQuality"
        const val MINIMUM_OBSERVED_RATIO: Double = 0.4

        // ---- 区域假设联合选择 ----
        // 评分公式与切换保护见 PgGridRegionArbiter（纯函数，便于 JVM 单元测试覆盖边界）。

        /** 包围率低于此值即判定区域框把阵列截断了。 */
        const val REGION_MINIMUM_COVERAGE: Double = 0.55

        /** 证据已经很强时提前结束假设枚举的门限。 */
        const val REGION_SHORT_CIRCUIT_COVERAGE: Double = 0.95
        const val REGION_SHORT_CIRCUIT_SUPPORT: Double = 0.90

        /**
         * 计算包围率时原图检测的最长边上限。
         *
         * 覆盖率只需分辨到 0.35 个间距，在 4000px 级原图上做全分辨率形态学检测纯属浪费。
         */
        const val COVERAGE_DETECT_MAX_SIDE: Double = 1600.0

        /** 统计包围率时，网格外扩多少个间距仍算“阵列邻域”。 */
        const val COVERAGE_NEIGHBOURHOOD_PITCH: Double = 1.5

        /** 判定“候选被网格覆盖”的距离容差相对间距的比例。 */
        const val COVERAGE_TOLERANCE_PITCH_RATIO: Double = 0.35

        // ---- 极性与支撑率证据 ----
        /**
         * 极性提示强度的显著性门限。
         *
         * 实测最弱的正确暗单元提示为 8.9，最强的弱提示（小亮点合成图）为 0.9，
         * 取 3.0 兼顾两侧安全边际。
         */
        const val POLARITY_HINT_SIGNIFICANT: Double = 3.0

        /** 支撑率检查所需的最小候选数（绝对下限）。 */
        const val MINIMUM_SUPPORT_CHECK_CANDIDATES: Int = 6

        /** 支撑率检查所需的最小候选数相对理论位点数的比例。 */
        const val MINIMUM_SUPPORT_CHECK_CANDIDATE_RATIO: Double = 0.3

        /** 幻影边缘的外伸量判定门限，仅用于 QC 展示，判定逻辑在 PgGridLatticeIntegrity。 */
        const val PHANTOM_EDGE_OVERHANG_THRESHOLD: Double = 0.5
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
