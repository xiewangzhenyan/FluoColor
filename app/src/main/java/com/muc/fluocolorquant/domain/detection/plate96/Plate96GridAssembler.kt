package com.muc.fluocolorquant.domain.detection.plate96

import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayBackgroundAnnulus
import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationDiagnostics
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizedSite
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationFlag
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegionSource
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 把方向裁决后的真实圆孔观测组装为固定96孔标准晶格。
 *
 * 已观测孔位使用实际圆心和半径；未观测孔位只使用行列中心与成功圆孔中位半径补齐。
 * 最终列表始终按A1～H12排列，同时保留每个孔在原图中的源行列和源像素区域。
 */
class Plate96GridAssembler @Inject constructor() {

    fun assemble(
        sourceWidth: Int,
        sourceHeight: Int,
        circles: List<Plate96CircleCandidate>,
        resolution: Plate96OrientationResolution
    ): ArrayLocalizationResult {
        require(sourceWidth > 0 && sourceHeight > 0) { "96孔板原图尺寸无效" }
        val orientation = resolution.orientation.requireValid()
        val transform = ArrayCoordinateTransformer.createImageTransform(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotation = orientation.rotation,
            mirrored = orientation.mirrored
        )
        val candidateByObservation = circles.withIndex().associate { (index, circle) -> index to circle }
        val observedCircles = resolution.recommended.assignments.values.mapNotNull { observation ->
            candidateByObservation[observation.observationIndex]
        }
        val fallbackRadius = median(observedCircles.map { it.radius })
            ?: estimateFallbackRadius(resolution.recommended)

        val sites = buildList {
            repeat(resolution.recommended.sourceRows) { sourceRow ->
                repeat(resolution.recommended.sourceColumns) { sourceColumn ->
                    val sourceCoordinate = ArrayGridCoordinate(sourceRow, sourceColumn)
                    val canonicalCoordinate = ArrayCoordinateTransformer.sourceToCanonical(
                        sourceCoordinate,
                        orientation
                    )
                    val siteIndex = Plate96LayoutContract.siteIndex(
                        canonicalCoordinate.rowIndex,
                        canonicalCoordinate.columnIndex
                    )
                    val observation = resolution.recommended.assignments[sourceCoordinate]
                    val assignedCircle = observation?.let { candidateByObservation[it.observationIndex] }
                    val predictedCenter = ArrayImagePoint(
                        x = resolution.recommended.columnCenters[sourceColumn],
                        y = resolution.recommended.rowCenters[sourceRow]
                    )
                    val minimumPitch = minimumPitchAt(
                        candidate = resolution.recommended,
                        sourceRow = sourceRow,
                        sourceColumn = sourceColumn
                    )
                    // 圆阵拟合能够给出稳定的理论中心。最终组装前再复核一次圆心偏移和半径，
                    // 防止孔内反光小圆、文字圆点或相邻结构虽然被分到单元，却污染真实裁切。
                    val circle = assignedCircle?.takeIf {
                        isPlausibleAssignedCircle(
                            circle = it,
                            predictedCenter = predictedCenter,
                            fallbackRadius = fallbackRadius,
                            minimumPitch = minimumPitch
                        )
                    }
                    val sourceCenter = ArrayImagePoint(
                        x = circle?.centerX ?: predictedCenter.x,
                        y = circle?.centerY ?: predictedCenter.y
                    )
                    val radius = circle?.radius ?: fallbackRadius
                    val normalizedCenter = ArrayCoordinateTransformer.sourceToNormalized(sourceCenter, transform)
                    val sourceBounds = circleBounds(sourceCenter, radius, sourceWidth, sourceHeight)
                    val normalizedBounds = circleBounds(
                        normalizedCenter,
                        radius,
                        transform.normalizedWidth,
                        transform.normalizedHeight
                    )
                    val localizationSource = circle.toLocalizationSource()
                    val annulusInnerRadius = radius * BACKGROUND_INNER_RADIUS_RATIO
                    val annulusOuterRadius = maxOf(
                        annulusInnerRadius + MINIMUM_ANNULUS_WIDTH_PX,
                        minimumPitch * BACKGROUND_OUTER_PITCH_RATIO
                    )
                    val flags = buildSet {
                        when (localizationSource) {
                            ArraySiteLocalizationSource.GRID_IMPUTED -> add(ArraySiteLocalizationFlag.GRID_IMPUTED)
                            ArraySiteLocalizationSource.OBJECT_DETECTION -> add(ArraySiteLocalizationFlag.OBJECT_DETECTION_ONLY)
                            else -> Unit
                        }
                        if (assignedCircle != null && circle == null) {
                            add(ArraySiteLocalizationFlag.LOW_GEOMETRIC_SUPPORT)
                        }
                    }
                    val regionSource = if (localizationSource == ArraySiteLocalizationSource.GRID_IMPUTED) {
                        ArrayUnitRegionSource.FALLBACK_MEDIAN_GEOMETRY
                    } else {
                        ArrayUnitRegionSource.DETECTED_GEOMETRY
                    }
                    add(
                        ArrayLocalizedSite(
                            siteIndex = siteIndex,
                            displayLabel = Plate96LayoutContract.displayLabel(
                                canonicalCoordinate.rowIndex,
                                canonicalCoordinate.columnIndex
                            ),
                            canonicalCoordinate = canonicalCoordinate,
                            sourceCoordinate = sourceCoordinate,
                            normalizedCenter = normalizedCenter,
                            sourceCenter = sourceCenter,
                            normalizedBounds = normalizedBounds,
                            sourceBounds = sourceBounds,
                            normalizedBackgroundAnnulus = ArrayBackgroundAnnulus(
                                center = normalizedCenter,
                                innerRadiusPx = annulusInnerRadius,
                                outerRadiusPx = annulusOuterRadius
                            ),
                            sourceBackgroundAnnulus = ArrayBackgroundAnnulus(
                                center = sourceCenter,
                                innerRadiusPx = annulusInnerRadius,
                                outerRadiusPx = annulusOuterRadius
                            ),
                            radiusPx = radius,
                            confidence = circle?.confidence ?: GRID_IMPUTED_CONFIDENCE,
                            source = localizationSource,
                            flags = flags,
                            normalizedRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                                siteIndex = siteIndex,
                                rowIndex = canonicalCoordinate.rowIndex,
                                columnIndex = canonicalCoordinate.columnIndex,
                                shape = ArrayUnitShape.CIRCLE,
                                bounds = normalizedBounds.toIntegerBounds(
                                    transform.normalizedWidth,
                                    transform.normalizedHeight
                                ),
                                regionSource = regionSource
                            ),
                            sourceRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                                siteIndex = siteIndex,
                                rowIndex = canonicalCoordinate.rowIndex,
                                columnIndex = canonicalCoordinate.columnIndex,
                                shape = ArrayUnitShape.CIRCLE,
                                bounds = sourceBounds.toIntegerBounds(sourceWidth, sourceHeight),
                                regionSource = regionSource
                            )
                        )
                    )
                }
            }
        }.sortedBy(ArrayLocalizedSite::siteIndex)

        val observedCount = sites.count { it.source != ArraySiteLocalizationSource.GRID_IMPUTED }
        val refinedCount = sites.count {
            it.source == ArraySiteLocalizationSource.SHAPE_REFINED ||
                it.source == ArraySiteLocalizationSource.CONTOUR_REFINED
        }
        return ArrayLocalizationResult(
            locatorName = "plate96-yolo-circle-grid",
            locatorVersion = LOCATOR_VERSION,
            orientation = orientation,
            imageTransform = transform,
            sites = sites,
            diagnostics = ArrayLocalizationDiagnostics(
                observedSiteCount = observedCount,
                shapeRefinedSiteCount = refinedCount,
                imputedSiteCount = Plate96LayoutContract.SITE_COUNT - observedCount,
                orientationScore = resolution.recommended.score,
                orientationAlternativeScore = resolution.alternative.score,
                orientationAmbiguous = resolution.ambiguous,
                meanConfidence = sites.map(ArrayLocalizedSite::confidence).average().coerceIn(0.0, 1.0)
            )
        ).requireValid()
    }

    /**
     * 在标准方向坐标中微调单个圆孔，并同步重建原图坐标、裁切边界、前景掩膜和背景环。
     *
     * 手动微调必须继续服从阵列物理约束：圆心最多离开自动结果约0.28个孔距，半径不得
     * 接触相邻孔。这样既给用户足够的像素级修正空间，也避免一次误触把孔位拖到相邻孔。
     */
    fun adjustSite(
        result: ArrayLocalizationResult,
        automaticResult: ArrayLocalizationResult,
        siteIndex: Int,
        requestedNormalizedCenter: ArrayImagePoint,
        requestedRadiusPx: Double
    ): ArrayLocalizationResult {
        result.requireValid()
        automaticResult.requireValid()
        require(result.orientation == automaticResult.orientation) { "手动微调基线与当前方向不一致" }
        val currentSite = result.sites.getOrNull(siteIndex) ?: error("96孔板位点索引越界")
        val automaticSite = automaticResult.sites.getOrNull(siteIndex) ?: error("96孔板自动基线缺失")
        val pitch = nearestPitch(automaticResult, automaticSite)
        val maximumCenterShift = pitch * MAXIMUM_MANUAL_CENTER_SHIFT_PITCH_RATIO

        // 先限制相对自动定位结果的偏移，再限制到图像有效区域，防止生成越界裁切。
        val rawOffsetX = requestedNormalizedCenter.x - automaticSite.normalizedCenter.x
        val rawOffsetY = requestedNormalizedCenter.y - automaticSite.normalizedCenter.y
        val rawDistance = hypot(rawOffsetX, rawOffsetY)
        val shiftScale = if (rawDistance > maximumCenterShift && rawDistance > 0.0) {
            maximumCenterShift / rawDistance
        } else {
            1.0
        }
        val radius = requestedRadiusPx.coerceIn(
            pitch * MINIMUM_MANUAL_RADIUS_PITCH_RATIO,
            pitch * MAXIMUM_MANUAL_RADIUS_PITCH_RATIO
        )
        val normalizedCenter = ArrayImagePoint(
            x = (automaticSite.normalizedCenter.x + rawOffsetX * shiftScale).coerceIn(
                radius,
                result.imageTransform.normalizedWidth - 1.0 - radius
            ),
            y = (automaticSite.normalizedCenter.y + rawOffsetY * shiftScale).coerceIn(
                radius,
                result.imageTransform.normalizedHeight - 1.0 - radius
            )
        )
        val sourceCenter = ArrayCoordinateTransformer.normalizedToSource(
            normalizedCenter,
            result.imageTransform
        )
        val normalizedBounds = circleBounds(
            normalizedCenter,
            radius,
            result.imageTransform.normalizedWidth,
            result.imageTransform.normalizedHeight
        )
        val sourceBounds = circleBounds(
            sourceCenter,
            radius,
            result.imageTransform.sourceWidth,
            result.imageTransform.sourceHeight
        )
        val annulusInnerRadius = radius * BACKGROUND_INNER_RADIUS_RATIO
        val annulusOuterRadius = maxOf(
            annulusInnerRadius + MINIMUM_ANNULUS_WIDTH_PX,
            pitch * BACKGROUND_OUTER_PITCH_RATIO
        )
        val adjustedSite = currentSite.copy(
            normalizedCenter = normalizedCenter,
            sourceCenter = sourceCenter,
            normalizedBounds = normalizedBounds,
            sourceBounds = sourceBounds,
            normalizedBackgroundAnnulus = ArrayBackgroundAnnulus(
                center = normalizedCenter,
                innerRadiusPx = annulusInnerRadius,
                outerRadiusPx = annulusOuterRadius
            ),
            sourceBackgroundAnnulus = ArrayBackgroundAnnulus(
                center = sourceCenter,
                innerRadiusPx = annulusInnerRadius,
                outerRadiusPx = annulusOuterRadius
            ),
            radiusPx = radius,
            source = ArraySiteLocalizationSource.USER_ADJUSTED,
            flags = currentSite.flags + ArraySiteLocalizationFlag.USER_ADJUSTED,
            normalizedRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                siteIndex = siteIndex,
                rowIndex = currentSite.canonicalCoordinate.rowIndex,
                columnIndex = currentSite.canonicalCoordinate.columnIndex,
                shape = ArrayUnitShape.CIRCLE,
                bounds = normalizedBounds.toIntegerBounds(
                    result.imageTransform.normalizedWidth,
                    result.imageTransform.normalizedHeight
                )
            ),
            sourceRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                siteIndex = siteIndex,
                rowIndex = currentSite.canonicalCoordinate.rowIndex,
                columnIndex = currentSite.canonicalCoordinate.columnIndex,
                shape = ArrayUnitShape.CIRCLE,
                bounds = sourceBounds.toIntegerBounds(
                    result.imageTransform.sourceWidth,
                    result.imageTransform.sourceHeight
                )
            )
        ).requireValid()
        return result.copy(
            sites = result.sites.toMutableList().apply { this[siteIndex] = adjustedSite }
        ).requireValid()
    }

    /** 只恢复指定孔位，不影响用户已经完成的其他孔位微调。 */
    fun restoreAutomaticSite(
        result: ArrayLocalizationResult,
        automaticResult: ArrayLocalizationResult,
        siteIndex: Int
    ): ArrayLocalizationResult {
        result.requireValid()
        automaticResult.requireValid()
        require(result.orientation == automaticResult.orientation) { "恢复基线与当前方向不一致" }
        val automaticSite = automaticResult.sites.getOrNull(siteIndex) ?: error("96孔板自动基线缺失")
        return result.copy(
            sites = result.sites.toMutableList().apply { this[siteIndex] = automaticSite }
        ).requireValid()
    }

    private fun Plate96CircleCandidate?.toLocalizationSource(): ArraySiteLocalizationSource {
        return when (this?.source) {
            Plate96CircleSource.HOUGH -> ArraySiteLocalizationSource.SHAPE_REFINED
            Plate96CircleSource.CONTOUR -> ArraySiteLocalizationSource.CONTOUR_REFINED
            Plate96CircleSource.BOX_FALLBACK -> ArraySiteLocalizationSource.OBJECT_DETECTION
            null -> ArraySiteLocalizationSource.GRID_IMPUTED
        }
    }

    /**
     * 最终圆孔必须同时接近晶格中心并接近全板中位半径。
     *
     * 这里不依赖绝对像素阈值，因此横竖照片、不同拍摄距离和后续更高分辨率图片使用同一语义。
     */
    private fun isPlausibleAssignedCircle(
        circle: Plate96CircleCandidate,
        predictedCenter: ArrayImagePoint,
        fallbackRadius: Double,
        minimumPitch: Double
    ): Boolean {
        val centerOffset = hypot(
            circle.centerX - predictedCenter.x,
            circle.centerY - predictedCenter.y
        )
        val radiusRatio = circle.radius / fallbackRadius
        return centerOffset <= minimumPitch * MAXIMUM_FINAL_CENTER_OFFSET_PITCH_RATIO &&
            radiusRatio in MINIMUM_FINAL_RADIUS_MEDIAN_RATIO..MAXIMUM_FINAL_RADIUS_MEDIAN_RATIO
    }

    private fun circleBounds(
        center: ArrayImagePoint,
        radius: Double,
        imageWidth: Int,
        imageHeight: Int
    ): ArrayImageBounds {
        val left = (center.x - radius).coerceIn(0.0, imageWidth - 1.0)
        val top = (center.y - radius).coerceIn(0.0, imageHeight - 1.0)
        val right = (center.x + radius).coerceIn(left + 1.0, imageWidth.toDouble())
        val bottom = (center.y + radius).coerceIn(top + 1.0, imageHeight.toDouble())
        return ArrayImageBounds(left, top, right, bottom).requireValid()
    }

    private fun ArrayImageBounds.toIntegerBounds(imageWidth: Int, imageHeight: Int): ArrayUnitBounds {
        val integerLeft = floor(left).toInt().coerceIn(0, imageWidth - 1)
        val integerTop = floor(top).toInt().coerceIn(0, imageHeight - 1)
        val integerRight = ceil(right).toInt().coerceIn(integerLeft + 1, imageWidth)
        val integerBottom = ceil(bottom).toInt().coerceIn(integerTop + 1, imageHeight)
        return ArrayUnitBounds(integerLeft, integerTop, integerRight, integerBottom)
    }

    private fun estimateFallbackRadius(candidate: Plate96OrientationCandidate): Double {
        val rowPitch = median(candidate.rowCenters.zipWithNext { first, second -> second - first })
        val columnPitch = median(candidate.columnCenters.zipWithNext { first, second -> second - first })
        return minOf(rowPitch ?: Double.MAX_VALUE, columnPitch ?: Double.MAX_VALUE)
            .takeIf { it.isFinite() && it > 0.0 }
            ?.times(DEFAULT_RADIUS_PITCH_RATIO)
            ?: DEFAULT_RADIUS_PX
    }

    private fun minimumPitchAt(
        candidate: Plate96OrientationCandidate,
        sourceRow: Int,
        sourceColumn: Int
    ): Double {
        val rowGaps = buildList {
            if (sourceRow > 0) add(candidate.rowCenters[sourceRow] - candidate.rowCenters[sourceRow - 1])
            if (sourceRow < candidate.sourceRows - 1) {
                add(candidate.rowCenters[sourceRow + 1] - candidate.rowCenters[sourceRow])
            }
        }
        val columnGaps = buildList {
            if (sourceColumn > 0) {
                add(candidate.columnCenters[sourceColumn] - candidate.columnCenters[sourceColumn - 1])
            }
            if (sourceColumn < candidate.sourceColumns - 1) {
                add(candidate.columnCenters[sourceColumn + 1] - candidate.columnCenters[sourceColumn])
            }
        }
        return (rowGaps + columnGaps)
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull()
            ?: (estimateFallbackRadius(candidate) / DEFAULT_RADIUS_PITCH_RATIO)
    }

    /** 使用自动结果中最近的相邻孔距作为当前孔的安全微调尺度。 */
    private fun nearestPitch(
        automaticResult: ArrayLocalizationResult,
        site: ArrayLocalizedSite
    ): Double {
        return automaticResult.sites.asSequence()
            .filter { it.siteIndex != site.siteIndex }
            .map {
                hypot(
                    it.normalizedCenter.x - site.normalizedCenter.x,
                    it.normalizedCenter.y - site.normalizedCenter.y
                )
            }
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull()
            ?: ((site.radiusPx ?: DEFAULT_RADIUS_PX) / DEFAULT_RADIUS_PITCH_RATIO)
    }

    private fun median(values: List<Double>): Double? {
        val finite = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (finite.isEmpty()) return null
        val middle = finite.size / 2
        return if (finite.size % 2 == 1) finite[middle] else (finite[middle - 1] + finite[middle]) / 2.0
    }

    private companion object {
        const val LOCATOR_VERSION: String = "plate96-locator-v1"
        const val GRID_IMPUTED_CONFIDENCE: Double = 0.30
        const val DEFAULT_RADIUS_PITCH_RATIO: Double = 0.36
        const val DEFAULT_RADIUS_PX: Double = 12.0
        const val BACKGROUND_INNER_RADIUS_RATIO: Double = 1.12
        const val BACKGROUND_OUTER_PITCH_RATIO: Double = 0.48
        const val MINIMUM_ANNULUS_WIDTH_PX: Double = 1.0
        const val MAXIMUM_MANUAL_CENTER_SHIFT_PITCH_RATIO: Double = 0.28
        const val MINIMUM_MANUAL_RADIUS_PITCH_RATIO: Double = 0.18
        const val MAXIMUM_MANUAL_RADIUS_PITCH_RATIO: Double = 0.46
        const val MAXIMUM_FINAL_CENTER_OFFSET_PITCH_RATIO: Double = 0.20
        const val MINIMUM_FINAL_RADIUS_MEDIAN_RATIO: Double = 0.70
        const val MAXIMUM_FINAL_RADIUS_MEDIAN_RATIO: Double = 1.35
    }
}
