package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.domain.detection.array.ArrayLocator
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorConfig
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.PgGridCandidateDetector
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.hypot
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 新96孔板生产定位入口：YOLO候选→圆形精定位→方向评分→固定96孔晶格。 */
class Plate96Locator @Inject constructor(
    private val objectDetector: Plate96ObjectDetector,
    private val circleRefiner: Plate96CircleRefiner,
    private val orientationResolver: Plate96OrientationResolver,
    private val gridAssembler: Plate96GridAssembler
) : ArrayLocator {

    /** 定位页面需要保留圆候选和方向评分，以便用户确认A1时无需重新运行YOLO。 */
    data class Session(
        val circles: List<Plate96CircleCandidate>,
        val orientationResolution: Plate96OrientationResolution,
        val result: ArrayLocalizationResult,
        /** 保存当前方向下未经人工修改的定位基线，供单孔“恢复自动”精确回退。 */
        val automaticResult: ArrayLocalizationResult = result,
        /** 保存算法最初的方向裁决，用户修改A1后仍可以恢复自动建议。 */
        val automaticOrientationResolution: Plate96OrientationResolution = orientationResolution,
        /** 冻结本次用户选择的定位模式，供过程页和运行记录准确追溯。 */
        val locatorMode: ArrayLocatorMode = ArrayLocatorMode.AUTO
    )

    override suspend fun locate(
        sourceBitmap: Bitmap,
        config: ArrayLocatorConfig
    ): ArrayLocalizationResult = localizeSession(sourceBitmap, config).result

    suspend fun localizeSession(
        sourceBitmap: Bitmap,
        config: ArrayLocatorConfig = DEFAULT_CONFIG
    ): Session {
        require(config.canonicalRows == Plate96LayoutContract.ROWS) { "96孔板标准行数必须为8" }
        require(config.canonicalColumns == Plate96LayoutContract.COLUMNS) { "96孔板标准列数必须为12" }
        require(config.unitShape == ArrayUnitShape.CIRCLE) { "96孔板位点形状必须为圆形" }

        if (config.mode in listOf(ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID, ArrayLocatorMode.CIRCLES_GRID)) {
            return localizeRoundRims(sourceBitmap, config.mode)
        }
        // 对照只给中心，不给已确认的物理孔号和内径，不能借用 YOLO 或孔板采样会话包装。
        require(config.mode.usesYolo) { "当前方式不产生标准孔板自动定位会话" }

        val objectCandidates = objectDetector.detect(
            sourceBitmap = sourceBitmap,
            confidenceThreshold = config.confidenceThreshold,
            iouThreshold = config.iouThreshold
        )
        require(objectCandidates.size >= MINIMUM_LOCALIZATION_CANDIDATES) {
            "96孔板目标候选不足，无法形成稳定圆阵"
        }
        val circles = when (config.mode) {
            ArrayLocatorMode.OBJECT_DETECTION -> objectCandidates.map(::boxFallback)
            ArrayLocatorMode.AUTO,
            ArrayLocatorMode.GEOMETRIC_SHAPE -> circleRefiner.refine(sourceBitmap, objectCandidates)
            else -> error("该方法不经过 96 孔板 YOLO 链")
        }
        var resolvedCircles = circles
        var orientation = resolveOrientation(resolvedCircles)
        // 自动融合才执行第二轮缺孔恢复；“YOLO+圆孔”保留首轮局部霍夫/轮廓结果，
        // 让用户能够真实比较是否启用晶格引导的二次恢复，而不是两个按钮跑同一条链路。
        if (config.mode == ArrayLocatorMode.AUTO &&
            orientation.recommended.assignments.size < Plate96LayoutContract.SITE_COUNT
        ) {
            val missingProposals = buildMissingGridProposals(
                sourceWidth = sourceBitmap.width,
                sourceHeight = sourceBitmap.height,
                circles = resolvedCircles,
                resolution = orientation
            )
            val recovered = circleRefiner.refine(sourceBitmap, missingProposals)
                .filter { it.source != Plate96CircleSource.BOX_FALLBACK }
            if (recovered.isNotEmpty()) {
                val combined = resolvedCircles + recovered
                val refinedResolution = resolveOrientation(combined)
                if (refinedResolution.recommended.assignments.size > orientation.recommended.assignments.size) {
                    resolvedCircles = combined
                    orientation = refinedResolution
                }
            }
        }
        val result = gridAssembler.assemble(
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height,
            circles = resolvedCircles,
            resolution = orientation
        )
        return Session(
            circles = resolvedCircles,
            orientationResolution = orientation,
            result = result,
            automaticResult = result,
            automaticOrientationResolution = orientation,
            locatorMode = config.mode
        )
    }

    /** 圆缘方案从原图工作副本提出候选，不运行 YOLO。不能形成有真实外行/列的网格就拒绝。 */
    private fun localizeRoundRims(source: Bitmap, mode: ArrayLocatorMode): Session {
        val rgba = Mat()
        val work = Mat()
        val gray = Mat()
        val scale = minOf(1.0, 1200.0 / maxOf(source.width, source.height))
        val circles = try {
            Utils.bitmapToMat(source, rgba)
            val width = (source.width * scale).roundToInt()
            val height = (source.height * scale).roundToInt()
            require(minOf(width, height) >= 40) { "孔板工作图太小" }
            Imgproc.resize(rgba, work, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)
            val pitchHint = minOf(minOf(width, height) / 8.0, maxOf(width, height) / 12.0)
            val useEllipse = mode != ArrayLocatorMode.HOUGH_GRID
            val scaleX = source.width.toDouble() / width
            val scaleY = source.height.toDouble() / height
            val rims = PgGridCandidateDetector.roundRims(gray, pitchHint, useEllipse, Plate96LayoutContract.SITE_COUNT)
            val selected = if (mode == ArrayLocatorMode.CIRCLES_GRID) {
                val dimensions = if (width >= height) listOf(8 to 12, 12 to 8) else listOf(12 to 8, 8 to 12)
                val centers = dimensions.firstNotNullOfOrNull { (rows, columns) ->
                    PgGridCandidateDetector.orderedCircleCenters(gray, false, rows, columns)
                        ?: PgGridCandidateDetector.orderedCircleCenters(gray, true, rows, columns)
                } ?: error("OpenCV 未定位完整圆点阵列")
                // 完整圆点阵列只证明编号和中心；标准孔板使用独立孔缘证据确定采样内径，不填常数蒙混。
                centers.mapNotNull { center -> rims.minByOrNull { hypot(center.x-it.point.x, center.y-it.point.y) }
                    ?.takeIf { hypot(center.x-it.point.x, center.y-it.point.y) < .20*pitchHint }?.copy(point = center) }
            } else rims
            selected.mapIndexed { index, rim ->
                val centerX = (rim.point.x + .5) * scaleX - .5
                val centerY = (rim.point.y + .5) * scaleY - .5
                val radius = rim.conservativeRadius * minOf(scaleX, scaleY)
                Plate96CircleCandidate(index, centerX, centerY, radius, rim.strength,
                    if (useEllipse) Plate96CircleSource.CONTOUR else Plate96CircleSource.HOUGH,
                    ArrayImageBounds((centerX-radius).coerceAtLeast(0.0), (centerY-radius).coerceAtLeast(0.0),
                        (centerX+radius).coerceAtMost(source.width.toDouble()), (centerY+radius).coerceAtMost(source.height.toDouble())))
            }
        } finally {
            listOf(rgba, work, gray).forEach(Mat::release)
        }
        val orientation = resolveOrientation(circles)
        val result = gridAssembler.assemble(source.width, source.height, circles, orientation)
        val observed = result.sites.filter { it.source != ArraySiteLocalizationSource.GRID_IMPUTED }
        val candidate = orientation.recommended
        fun regular(centers: List<Double>): Boolean {
            val gaps = centers.zipWithNext { first, second -> second - first }
            val pitch = median(gaps) ?: return false
            return gaps.all { it in .61*pitch..1.43*pitch }
        }
        require(observed.size >= 64 && regular(candidate.rowCenters) && regular(candidate.columnCenters) &&
            observed.count { it.canonicalCoordinate.rowIndex == 0 } >= 3 &&
            observed.count { it.canonicalCoordinate.rowIndex == Plate96LayoutContract.ROWS-1 } >= 3 &&
            observed.count { it.canonicalCoordinate.columnIndex == 0 } >= 3 &&
            observed.count { it.canonicalCoordinate.columnIndex == Plate96LayoutContract.COLUMNS-1 } >= 3) {
            "圆缘不足以确认96孔板的行列"
        }
        return Session(circles, orientation, result.copy(locatorName = mode.name, locatorVersion = "1.0"), locatorMode = mode)
    }

    fun localizeManual(source: Bitmap, corners: List<GridPoint>, diameterRatio: Double): Session {
        val result = gridAssembler.assembleManual(source.width, source.height, corners, diameterRatio)
        val orientation = result.orientation
        // 手动方向由明确的物理孔号给定，不跑自动方向评分。占位对象只满足旧会话结构，UI 不展示算法置信度。
        val axes = Plate96OrientationCandidate(orientation.sourceRows, orientation.sourceColumns,
            0.0, 0.0, 0.0, 0.0, 0.0, List(orientation.sourceRows) { 0.0 }, List(orientation.sourceColumns) { 0.0 }, emptyMap())
        val resolution = Plate96OrientationResolution(axes, axes, orientation, ambiguous = false, requiresOriginConfirmation = false)
        return Session(emptyList(), resolution, result, locatorMode = ArrayLocatorMode.MANUAL_CORNERS)
    }

    /** 用户确认A1角落后只重建坐标和标准晶格，不重复执行YOLO或霍夫圆。 */
    fun reorientSession(
        sourceWidth: Int,
        sourceHeight: Int,
        session: Session,
        originCorner: ArrayOriginCorner
    ): Session {
        if (session.locatorMode == ArrayLocatorMode.MANUAL_CORNERS) return session
        val confirmedOrientation = Plate96LayoutContract.orientation(
            originCorner = originCorner,
            source = ArrayOrientationSource.USER_CONFIRMED,
            confidence = 1.0
        )
        require(
            confirmedOrientation.sourceRows == session.orientationResolution.recommended.sourceRows &&
                confirmedOrientation.sourceColumns == session.orientationResolution.recommended.sourceColumns
        ) { "所选A1角落与当前横竖方向不一致" }
        val confirmedResolution = session.orientationResolution.copy(
            orientation = confirmedOrientation,
            requiresOriginConfirmation = false
        )
        val reorientedResult = gridAssembler.assemble(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            circles = session.circles,
            resolution = confirmedResolution
        ).copy(locatorName = session.result.locatorName, locatorVersion = session.result.locatorVersion)
        return session.copy(
            orientationResolution = confirmedResolution,
            result = reorientedResult,
            automaticResult = reorientedResult
        )
    }

    /** 在标准方向坐标中更新单孔几何，定位模型和圆检测不会重新执行。 */
    fun adjustSite(
        session: Session,
        siteIndex: Int,
        normalizedCenter: ArrayImagePoint,
        radiusPx: Double
    ): Session {
        if (session.locatorMode == ArrayLocatorMode.MANUAL_CORNERS) return session
        return session.copy(
            result = gridAssembler.adjustSite(
                result = session.result,
                automaticResult = session.automaticResult,
                siteIndex = siteIndex,
                requestedNormalizedCenter = normalizedCenter,
                requestedRadiusPx = radiusPx
            )
        )
    }

    /** 恢复一个孔位的自动圆心和半径，其他孔位的人工修改保持不变。 */
    fun restoreAutomaticSite(session: Session, siteIndex: Int): Session {
        if (session.locatorMode == ArrayLocatorMode.MANUAL_CORNERS) return session
        return session.copy(
            result = gridAssembler.restoreAutomaticSite(
                result = session.result,
                automaticResult = session.automaticResult,
                siteIndex = siteIndex
            )
        )
    }

    /** 恢复算法最初的方向建议，并清除当前方向下的人工孔位调整。 */
    fun restoreAutomaticOrientation(
        sourceWidth: Int,
        sourceHeight: Int,
        session: Session
    ): Session {
        if (session.locatorMode == ArrayLocatorMode.MANUAL_CORNERS) return session
        val automaticResult = gridAssembler.assemble(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            circles = session.circles,
            resolution = session.automaticOrientationResolution
        ).copy(locatorName = session.result.locatorName, locatorVersion = session.result.locatorVersion)
        return session.copy(
            orientationResolution = session.automaticOrientationResolution,
            result = automaticResult,
            automaticResult = automaticResult
        )
    }

    private fun resolveOrientation(circles: List<Plate96CircleCandidate>): Plate96OrientationResolution {
        val observations = circles.mapIndexed { index, circle ->
            Plate96CircleObservation(
                x = circle.centerX,
                y = circle.centerY,
                radius = circle.radius,
                confidence = circle.confidence,
                observationIndex = index
            )
        }
        return orientationResolver.resolve(observations)
    }

    /**
     * 首轮候选已经形成稳定晶格后，只在缺失单元附近建立局部圆检测窗口。
     * 窗口限制在约0.9个pitch内，物理上不会同时包含相邻两个孔，显著降低整图霍夫误检。
     */
    private fun buildMissingGridProposals(
        sourceWidth: Int,
        sourceHeight: Int,
        circles: List<Plate96CircleCandidate>,
        resolution: Plate96OrientationResolution
    ): List<Plate96ObjectCandidate> {
        val candidate = resolution.recommended
        val pitch = median(
            candidate.rowCenters.zipWithNext { first, second -> second - first } +
                candidate.columnCenters.zipWithNext { first, second -> second - first }
        ) ?: return emptyList()
        val medianRadius = median(circles.map(Plate96CircleCandidate::radius)) ?: pitch * 0.34
        val halfWindow = minOf(
            pitch * GRID_REFINEMENT_HALF_WINDOW_PITCH_RATIO,
            maxOf(medianRadius * GRID_REFINEMENT_RADIUS_MULTIPLIER, MINIMUM_GRID_REFINEMENT_HALF_WINDOW_PX)
        )
        var nextIndex = (circles.maxOfOrNull(Plate96CircleCandidate::candidateIndex) ?: -1) + 1
        val circleByObservationIndex = circles.withIndex().associate { (index, circle) -> index to circle }
        return buildList {
            repeat(candidate.sourceRows) rowLoop@{ row ->
                repeat(candidate.sourceColumns) columnLoop@{ column ->
                    val coordinate = com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate(row, column)
                    val assignedCircle = candidate.assignments[coordinate]
                        ?.let { observation -> circleByObservationIndex[observation.observationIndex] }
                    if (assignedCircle != null && assignedCircle.source != Plate96CircleSource.BOX_FALLBACK) {
                        return@columnLoop
                    }
                    val centerX = candidate.columnCenters[column]
                    val centerY = candidate.rowCenters[row]
                    val left = (centerX - halfWindow).coerceIn(0.0, sourceWidth - 1.0)
                    val top = (centerY - halfWindow).coerceIn(0.0, sourceHeight - 1.0)
                    val right = (centerX + halfWindow).coerceIn(left + 1.0, sourceWidth.toDouble())
                    val bottom = (centerY + halfWindow).coerceIn(top + 1.0, sourceHeight.toDouble())
                    add(
                        Plate96ObjectCandidate(
                            candidateIndex = nextIndex++,
                            bounds = com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds(
                                left,
                                top,
                                right,
                                bottom
                            ),
                            confidence = GRID_REFINEMENT_PROPOSAL_CONFIDENCE
                        )
                    )
                }
            }
        }
    }

    private fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun boxFallback(candidate: Plate96ObjectCandidate): Plate96CircleCandidate {
        return Plate96CircleCandidate(
            candidateIndex = candidate.candidateIndex,
            centerX = (candidate.bounds.left + candidate.bounds.right) / 2.0,
            centerY = (candidate.bounds.top + candidate.bounds.bottom) / 2.0,
            radius = minOf(candidate.bounds.width, candidate.bounds.height) * BOX_RADIUS_RATIO,
            confidence = candidate.confidence,
            source = Plate96CircleSource.BOX_FALLBACK,
            objectBounds = candidate.bounds
        )
    }

    companion object {
        /** 96孔板调用方使用的固定配置，不允许项目图片方向改变科学规格。 */
        val DEFAULT_CONFIG: ArrayLocatorConfig = ArrayLocatorConfig(
            canonicalRows = Plate96LayoutContract.ROWS,
            canonicalColumns = Plate96LayoutContract.COLUMNS,
            unitShape = ArrayUnitShape.CIRCLE,
            mode = ArrayLocatorMode.AUTO
        )

        private const val MINIMUM_LOCALIZATION_CANDIDATES: Int = 12
        private const val BOX_RADIUS_RATIO: Double = 0.42
        private const val GRID_REFINEMENT_HALF_WINDOW_PITCH_RATIO: Double = 0.46
        private const val GRID_REFINEMENT_RADIUS_MULTIPLIER: Double = 1.55
        private const val MINIMUM_GRID_REFINEMENT_HALF_WINDOW_PX: Double = 12.0
        private const val GRID_REFINEMENT_PROPOSAL_CONFIDENCE: Double = 0.82
    }
}
