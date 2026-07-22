package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/** 通用矩形阵列几何测试，覆盖理论点、横纵 pitch 和单应投影。 */
class RegularGridGeometryTest {

    @Test
    fun `四乘六理论网格按行优先生成且横纵边距独立`() {
        val points = RegularGridGeometry.generate(
            rows = 4,
            columns = 6,
            width = 700.0,
            height = 500.0,
            marginRatio = 0.1
        )

        assertEquals(24, points.size)
        assertPointEquals(GridPoint(70.0, 50.0), points.first())
        assertPointEquals(GridPoint(630.0, 450.0), points.last())
        assertPointEquals(GridPoint(70.0, 183.3333333333), points[6], tolerance = 1e-8)
    }

    @Test
    fun `非方阵横纵pitch分别按同行和同列相邻距离估计`() {
        val points = RegularGridGeometry.generate(
            rows = 3,
            columns = 5,
            width = 120.0,
            height = 100.0,
            marginRatio = 0.1
        )

        val pitch = RegularGridGeometry.estimatePitch(points, rows = 3, columns = 5)

        assertEquals(24.0, pitch.horizontalPx, 1e-9)
        assertEquals(40.0, pitch.verticalPx, 1e-9)
        assertEquals(32.0, pitch.representativePx, 1e-9)
    }

    @Test
    fun `三乘三单应矩阵按齐次坐标投影`() {
        val matrix = doubleArrayOf(
            1.2, 0.1, 10.0,
            -0.05, 1.1, 20.0,
            0.001, -0.0005, 1.0
        )

        val projected = RegularGridGeometry.project(matrix, GridPoint(100.0, 80.0))
        val denominator = 0.001 * 100.0 - 0.0005 * 80.0 + 1.0

        assertEquals((1.2 * 100.0 + 0.1 * 80.0 + 10.0) / denominator, projected.x, 1e-9)
        assertEquals((-0.05 * 100.0 + 1.1 * 80.0 + 20.0) / denominator, projected.y, 1e-9)
    }

    /** 使用数值容差比较像素点，避免浮点线性插值造成无意义的精确相等失败。 */
    private fun assertPointEquals(expected: GridPoint, actual: GridPoint, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }
}
