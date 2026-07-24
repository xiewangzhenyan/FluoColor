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
                    val circle = observation?.let { candidateByObservation[it.observationIndex] }
                    val sourceCenter = ArrayImagePoint(
                        x = circle?.centerX ?: resolution.recommended.columnCenters[sourceColumn],
                        y = circle?.centerY ?: resolution.recommended.rowCenters[sourceRow]
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
                    val minimumPitch = minimumPitchAt(
                        candidate = resolution.recommended,
                        sourceRow = sourceRow,
                        sourceColumn = sourceColumn
                    )
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

    private fun Plate96CircleCandidate?.toLocalizationSource(): ArraySiteLocalizationSource {
        return when (this?.source) {
            Plate96CircleSource.HOUGH -> ArraySiteLocalizationSource.SHAPE_REFINED
            Plate96CircleSource.CONTOUR -> ArraySiteLocalizationSource.CONTOUR_REFINED
            Plate96CircleSource.BOX_FALLBACK -> ArraySiteLocalizationSource.OBJECT_DETECTION
            null -> ArraySiteLocalizationSource.GRID_IMPUTED
        }
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
    }
}
