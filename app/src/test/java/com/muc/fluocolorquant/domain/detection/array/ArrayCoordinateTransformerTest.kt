package com.muc.fluocolorquant.domain.detection.array

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrayCoordinateTransformerTest {
    @Test
    fun `96孔板标准标签和行优先索引保持稳定`() {
        assertEquals(0, Plate96LayoutContract.siteIndex(0, 0))
        assertEquals(9, Plate96LayoutContract.siteIndex(0, 9))
        assertEquals(95, Plate96LayoutContract.siteIndex(7, 11))
        assertEquals("A1", Plate96LayoutContract.displayLabel(0, 0))
        assertEquals("A10", Plate96LayoutContract.displayLabel(0, 9))
        assertEquals("H12", Plate96LayoutContract.displayLabel(7, 11))
        assertEquals(ArrayGridCoordinate(7, 11), Plate96LayoutContract.coordinate(95))
    }

    @Test
    fun `A1四角生成正确的原图规格和旋转方向`() {
        val topLeft = orientation(ArrayOriginCorner.TOP_LEFT)
        val bottomLeft = orientation(ArrayOriginCorner.BOTTOM_LEFT)
        val bottomRight = orientation(ArrayOriginCorner.BOTTOM_RIGHT)
        val topRight = orientation(ArrayOriginCorner.TOP_RIGHT)

        assertEquals(ArrayQuarterTurn.ROTATE_0, topLeft.rotation)
        assertEquals(8, topLeft.sourceRows)
        assertEquals(12, topLeft.sourceColumns)

        assertEquals(ArrayQuarterTurn.ROTATE_90_CW, bottomLeft.rotation)
        assertEquals(12, bottomLeft.sourceRows)
        assertEquals(8, bottomLeft.sourceColumns)

        assertEquals(ArrayQuarterTurn.ROTATE_180, bottomRight.rotation)
        assertEquals(8, bottomRight.sourceRows)
        assertEquals(12, bottomRight.sourceColumns)

        assertEquals(ArrayQuarterTurn.ROTATE_270_CW, topRight.rotation)
        assertEquals(12, topRight.sourceRows)
        assertEquals(8, topRight.sourceColumns)
    }

    @Test
    fun `标准A1和H12能够映射到四种原图方向`() {
        val a1 = ArrayGridCoordinate(0, 0)
        val h12 = ArrayGridCoordinate(7, 11)

        assertEquals(ArrayGridCoordinate(0, 0), sourceOf(a1, ArrayOriginCorner.TOP_LEFT))
        assertEquals(ArrayGridCoordinate(7, 11), sourceOf(h12, ArrayOriginCorner.TOP_LEFT))

        assertEquals(ArrayGridCoordinate(11, 0), sourceOf(a1, ArrayOriginCorner.BOTTOM_LEFT))
        assertEquals(ArrayGridCoordinate(0, 7), sourceOf(h12, ArrayOriginCorner.BOTTOM_LEFT))

        assertEquals(ArrayGridCoordinate(7, 11), sourceOf(a1, ArrayOriginCorner.BOTTOM_RIGHT))
        assertEquals(ArrayGridCoordinate(0, 0), sourceOf(h12, ArrayOriginCorner.BOTTOM_RIGHT))

        assertEquals(ArrayGridCoordinate(0, 7), sourceOf(a1, ArrayOriginCorner.TOP_RIGHT))
        assertEquals(ArrayGridCoordinate(11, 0), sourceOf(h12, ArrayOriginCorner.TOP_RIGHT))
    }

    @Test
    fun `所有96孔在四种旋转下均满足双向一一映射`() {
        ArrayOriginCorner.entries.forEach { corner ->
            val orientation = orientation(corner)
            val sourceCoordinates = buildSet {
                repeat(Plate96LayoutContract.SITE_COUNT) { siteIndex ->
                    val canonical = Plate96LayoutContract.coordinate(siteIndex)
                    val source = ArrayCoordinateTransformer.canonicalToSource(canonical, orientation)
                    add(source)
                    assertEquals(
                        canonical,
                        ArrayCoordinateTransformer.sourceToCanonical(source, orientation)
                    )
                }
            }
            assertEquals(Plate96LayoutContract.SITE_COUNT, sourceCoordinates.size)
        }
    }

    @Test
    fun `像素点在四种旋转及镜像下正逆矩阵保持一致`() {
        ArrayQuarterTurn.entries.forEach { rotation ->
            listOf(false, true).forEach { mirrored ->
                val transform = ArrayCoordinateTransformer.createImageTransform(
                    sourceWidth = 1200,
                    sourceHeight = 800,
                    rotation = rotation,
                    mirrored = mirrored
                )
                listOf(
                    ArrayImagePoint(0.0, 0.0),
                    ArrayImagePoint(1199.0, 0.0),
                    ArrayImagePoint(0.0, 799.0),
                    ArrayImagePoint(1199.0, 799.0),
                    ArrayImagePoint(431.25, 527.75)
                ).forEach { sourcePoint ->
                    val normalized = ArrayCoordinateTransformer.sourceToNormalized(sourcePoint, transform)
                    val restored = ArrayCoordinateTransformer.normalizedToSource(normalized, transform)
                    assertNear(sourcePoint.x, restored.x)
                    assertNear(sourcePoint.y, restored.y)
                }
            }
        }
    }

    @Test
    fun `90度旋转交换图像宽高且保持角点语义`() {
        val transform = ArrayCoordinateTransformer.createImageTransform(
            sourceWidth = 800,
            sourceHeight = 1200,
            rotation = ArrayQuarterTurn.ROTATE_90_CW,
            mirrored = false
        )

        assertEquals(1200, transform.normalizedWidth)
        assertEquals(800, transform.normalizedHeight)
        assertEquals(
            ArrayImagePoint(1199.0, 0.0),
            ArrayCoordinateTransformer.sourceToNormalized(ArrayImagePoint(0.0, 0.0), transform)
        )
        assertEquals(
            ArrayImagePoint(0.0, 799.0),
            ArrayCoordinateTransformer.sourceToNormalized(ArrayImagePoint(799.0, 1199.0), transform)
        )
    }

    private fun orientation(corner: ArrayOriginCorner): ArrayOrientationSnapshot {
        return Plate96LayoutContract.orientation(
            originCorner = corner,
            source = ArrayOrientationSource.USER_CONFIRMED,
            confidence = 1.0
        )
    }

    private fun sourceOf(
        canonical: ArrayGridCoordinate,
        corner: ArrayOriginCorner
    ): ArrayGridCoordinate {
        return ArrayCoordinateTransformer.canonicalToSource(canonical, orientation(corner))
    }

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue("期望$expected，实际$actual", kotlin.math.abs(expected - actual) <= 1e-9)
    }
}
