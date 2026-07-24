package com.muc.fluocolorquant.domain.detection.plate96

import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationFlag
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Plate96GridAssemblerTest {
    private val resolver = Plate96OrientationResolver()
    private val assembler = Plate96GridAssembler()

    @Test
    fun `横向圆阵组装为A1到H12的固定96孔`() {
        val circles = circles(rows = 8, columns = 12)
        val resolution = resolver.resolve(circles.toObservations())

        val result = assembler.assemble(1200, 800, circles, resolution)

        assertEquals(96, result.sites.size)
        assertEquals("A1", result.sites.first().displayLabel)
        assertEquals("H12", result.sites.last().displayLabel)
        assertEquals(ArrayGridCoordinate(0, 0), result.sites.first().sourceCoordinate)
        assertEquals(96, result.diagnostics.observedSiteCount)
        assertEquals(0, result.diagnostics.imputedSiteCount)
        assertEquals(ArrayQuarterTurn.ROTATE_0, result.orientation.rotation)
        assertTrue(
            result.sites.all {
                it.sourceBackgroundAnnulus.outerRadiusPx > it.sourceBackgroundAnnulus.innerRadiusPx
            }
        )
    }

    @Test
    fun `缺失圆孔由晶格补位但仍输出完整96孔`() {
        val circles = circles(rows = 8, columns = 12)
            .filterNot { it.candidateIndex in setOf(0, 47, 95) }
        val resolution = resolver.resolve(circles.toObservations())

        val result = assembler.assemble(1200, 800, circles, resolution)

        assertEquals(96, result.sites.size)
        assertEquals(3, result.diagnostics.imputedSiteCount)
        assertEquals(ArraySiteLocalizationSource.GRID_IMPUTED, result.sites[0].source)
        assertEquals(ArraySiteLocalizationSource.GRID_IMPUTED, result.sites[47].source)
        assertEquals(ArraySiteLocalizationSource.GRID_IMPUTED, result.sites[95].source)
        assertTrue(result.sites.all { (it.radiusPx ?: 0.0) > 0.0 })
    }

    @Test
    fun `竖向照片的原图左下孔映射为标准A1`() {
        val circles = circles(rows = 12, columns = 8)
        val resolution = resolver.resolve(circles.toObservations())

        val result = assembler.assemble(800, 1200, circles, resolution)

        assertEquals(ArrayQuarterTurn.ROTATE_90_CW, result.orientation.rotation)
        assertEquals(ArrayGridCoordinate(11, 0), result.sites.first().sourceCoordinate)
        assertEquals("A1", result.sites.first().displayLabel)
        assertEquals(1200, result.imageTransform.normalizedWidth)
        assertEquals(800, result.imageTransform.normalizedHeight)
    }

    @Test
    fun `手动微调同步更新圆心裁切掩膜和原图坐标并可单孔恢复`() {
        val circles = circles(rows = 8, columns = 12)
        val automatic = assembler.assemble(1200, 800, circles, resolver.resolve(circles.toObservations()))
        val originalA1 = automatic.sites.first()
        val unchangedA2 = automatic.sites[1]

        val adjusted = assembler.adjustSite(
            result = automatic,
            automaticResult = automatic,
            siteIndex = 0,
            requestedNormalizedCenter = ArrayImagePoint(
                originalA1.normalizedCenter.x + 4.0,
                originalA1.normalizedCenter.y + 3.0
            ),
            requestedRadiusPx = 30.0
        )
        val adjustedA1 = adjusted.sites.first()

        assertEquals(ArraySiteLocalizationSource.USER_ADJUSTED, adjustedA1.source)
        assertTrue(ArraySiteLocalizationFlag.USER_ADJUSTED in adjustedA1.flags)
        assertEquals(originalA1.normalizedCenter.x + 4.0, adjustedA1.normalizedCenter.x, 1e-9)
        assertEquals(originalA1.normalizedCenter.y + 3.0, adjustedA1.normalizedCenter.y, 1e-9)
        assertEquals(adjustedA1.normalizedCenter, adjustedA1.normalizedBackgroundAnnulus.center)
        assertEquals(adjustedA1.sourceCenter, adjustedA1.sourceBackgroundAnnulus.center)
        assertTrue(
            adjustedA1.normalizedRegion.contains(
                adjustedA1.normalizedCenter.x.toInt(),
                adjustedA1.normalizedCenter.y.toInt()
            )
        )
        assertEquals(unchangedA2, adjusted.sites[1])
        assertEquals(originalA1, automatic.sites.first())

        val restored = assembler.restoreAutomaticSite(adjusted, automatic, 0)
        assertEquals(originalA1, restored.sites.first())
        assertEquals(unchangedA2, restored.sites[1])
    }

    @Test
    fun `过大的手动偏移和半径会被限制在相邻孔安全范围内`() {
        val circles = circles(rows = 8, columns = 12)
        val automatic = assembler.assemble(1200, 800, circles, resolver.resolve(circles.toObservations()))
        val original = automatic.sites[40]

        val adjusted = assembler.adjustSite(
            result = automatic,
            automaticResult = automatic,
            siteIndex = 40,
            requestedNormalizedCenter = ArrayImagePoint(10_000.0, 10_000.0),
            requestedRadiusPx = 10_000.0
        ).sites[40]

        val centerShift = kotlin.math.hypot(
            adjusted.normalizedCenter.x - original.normalizedCenter.x,
            adjusted.normalizedCenter.y - original.normalizedCenter.y
        )
        assertTrue(centerShift < 30.0)
        assertTrue((adjusted.radiusPx ?: 0.0) < 41.0)
        assertTrue(adjusted.normalizedBounds.right <= automatic.imageTransform.normalizedWidth)
        assertTrue(adjusted.normalizedBounds.bottom <= automatic.imageTransform.normalizedHeight)
    }

    @Test
    fun `用户修改A1后可以恢复最初的自动方向与自动孔位基线`() {
        val circles = circles(rows = 8, columns = 12)
        val resolution = resolver.resolve(circles.toObservations())
        val automatic = assembler.assemble(1200, 800, circles, resolution)
        val locator = Plate96Locator(
            objectDetector = object : Plate96ObjectDetector {
                override suspend fun detect(
                    sourceBitmap: android.graphics.Bitmap,
                    confidenceThreshold: Float,
                    iouThreshold: Float
                ): List<Plate96ObjectCandidate> = emptyList()
            },
            circleRefiner = Plate96CircleRefiner(),
            orientationResolver = resolver,
            gridAssembler = assembler
        )
        val automaticSession = Plate96Locator.Session(circles, resolution, automatic)
        val reversed = locator.reorientSession(
            sourceWidth = 1200,
            sourceHeight = 800,
            session = automaticSession,
            originCorner = ArrayOriginCorner.BOTTOM_RIGHT
        )

        val restored = locator.restoreAutomaticOrientation(1200, 800, reversed)

        assertEquals(ArrayOriginCorner.TOP_LEFT, restored.result.orientation.originCorner)
        assertEquals(ArrayOrientationSource.AUTO, restored.result.orientation.source)
        automatic.sites.zip(restored.result.sites).forEach { (expected, actual) ->
            assertEquals(expected.siteIndex, actual.siteIndex)
            assertEquals(expected.sourceCenter, actual.sourceCenter)
            assertEquals(expected.normalizedCenter, actual.normalizedCenter)
            assertEquals(expected.radiusPx, actual.radiusPx)
            assertEquals(expected.source, actual.source)
        }
        assertTrue(restored.result === restored.automaticResult)
    }

    @Test
    fun `孔内反光小圆即使被分配到晶格也不会污染最终圆孔裁切`() {
        val contaminatedIndex = 17
        val circles = circles(rows = 8, columns = 12).map { circle ->
            if (circle.candidateIndex != contaminatedIndex) {
                circle
            } else {
                circle.copy(
                    centerX = circle.centerX + 25.0,
                    centerY = circle.centerY - 18.0,
                    radius = 6.0,
                    confidence = 0.99
                )
            }
        }
        val result = assembler.assemble(
            1200,
            800,
            circles,
            resolver.resolve(circles.toObservations())
        )
        val sanitized = result.sites[contaminatedIndex]

        assertEquals(ArraySiteLocalizationSource.GRID_IMPUTED, sanitized.source)
        assertTrue(ArraySiteLocalizationFlag.GRID_IMPUTED in sanitized.flags)
        assertTrue(ArraySiteLocalizationFlag.LOW_GEOMETRIC_SUPPORT in sanitized.flags)
        assertTrue((sanitized.radiusPx ?: 0.0) > 20.0)
        assertTrue(kotlin.math.abs(sanitized.sourceCenter.x - (75.0 + 5 * 88.0)) < 10.0)
        assertTrue(kotlin.math.abs(sanitized.sourceCenter.y - (65.0 + 1 * 82.0)) < 10.0)
    }

    private fun circles(rows: Int, columns: Int): List<Plate96CircleCandidate> {
        return buildList {
            var index = 0
            repeat(rows) { row ->
                repeat(columns) { column ->
                    val centerX = 75.0 + column * 88.0
                    val centerY = 65.0 + row * 82.0
                    add(
                        Plate96CircleCandidate(
                            candidateIndex = index++,
                            centerX = centerX,
                            centerY = centerY,
                            radius = 26.0,
                            confidence = 0.94,
                            source = Plate96CircleSource.HOUGH,
                            objectBounds = ArrayImageBounds(
                                centerX - 30.0,
                                centerY - 30.0,
                                centerX + 30.0,
                                centerY + 30.0
                            )
                        )
                    )
                }
            }
        }
    }

    private fun List<Plate96CircleCandidate>.toObservations(): List<Plate96CircleObservation> {
        return mapIndexed { index, circle ->
            Plate96CircleObservation(
                x = circle.centerX,
                y = circle.centerY,
                radius = circle.radius,
                confidence = circle.confidence,
                observationIndex = index
            )
        }
    }
}
