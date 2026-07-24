package com.muc.fluocolorquant.domain.detection.plate96

import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Plate96OrientationResolverTest {
    private val resolver = Plate96OrientationResolver()

    @Test
    fun `横向96孔板推荐8行12列`() {
        val result = resolver.resolve(grid(rows = 8, columns = 12, pitchX = 70.0, pitchY = 68.0))

        assertEquals(8, result.recommended.sourceRows)
        assertEquals(12, result.recommended.sourceColumns)
        assertEquals(ArrayQuarterTurn.ROTATE_0, result.orientation.rotation)
        assertEquals(96, result.recommended.assignments.size)
        assertFalse(result.ambiguous)
        assertTrue(result.requiresOriginConfirmation)
    }

    @Test
    fun `竖向96孔板推荐12行8列并建议顺时针校正`() {
        val result = resolver.resolve(grid(rows = 12, columns = 8, pitchX = 68.0, pitchY = 70.0))

        assertEquals(12, result.recommended.sourceRows)
        assertEquals(8, result.recommended.sourceColumns)
        assertEquals(ArrayQuarterTurn.ROTATE_90_CW, result.orientation.rotation)
        assertEquals(96, result.recommended.assignments.size)
        assertFalse(result.ambiguous)
    }

    @Test
    fun `少量缺孔和亚像素扰动仍能识别横向布局`() {
        val observations = grid(rows = 8, columns = 12, pitchX = 72.0, pitchY = 67.0)
            .filterNot { it.observationIndex in setOf(0, 11, 24, 47, 72, 95) }
            .mapIndexed { index, point ->
                point.copy(
                    x = point.x + ((index % 3) - 1) * 0.8,
                    y = point.y + ((index % 5) - 2) * 0.5
                )
            }

        val result = resolver.resolve(observations)

        assertEquals(8, result.recommended.sourceRows)
        assertEquals(12, result.recommended.sourceColumns)
        assertTrue(result.recommended.assignments.size >= 88)
        assertTrue(result.recommended.score > result.alternative.score)
    }

    @Test
    fun `对称阵列不能宣称自动识别A1`() {
        val result = resolver.resolve(grid(rows = 8, columns = 12, pitchX = 70.0, pitchY = 70.0))

        assertTrue(result.requiresOriginConfirmation)
    }

    private fun grid(
        rows: Int,
        columns: Int,
        pitchX: Double,
        pitchY: Double
    ): List<Plate96CircleObservation> {
        return buildList {
            var index = 0
            repeat(rows) { row ->
                repeat(columns) { column ->
                    add(
                        Plate96CircleObservation(
                            x = 100.0 + column * pitchX,
                            y = 80.0 + row * pitchY,
                            radius = 24.0,
                            confidence = 0.95,
                            observationIndex = index++
                        )
                    )
                }
            }
        }
    }
}
