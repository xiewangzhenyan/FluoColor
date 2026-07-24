package com.muc.fluocolorquant.domain.detection.plate96

import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn
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
