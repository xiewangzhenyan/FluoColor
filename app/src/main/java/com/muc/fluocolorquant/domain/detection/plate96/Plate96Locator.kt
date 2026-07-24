package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.domain.detection.array.ArrayLocator
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorConfig
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import javax.inject.Inject

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
        val result: ArrayLocalizationResult
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
        }
        var resolvedCircles = circles
        var orientation = resolveOrientation(resolvedCircles)
        if (config.mode != ArrayLocatorMode.OBJECT_DETECTION &&
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
        return Session(resolvedCircles, orientation, result)
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
