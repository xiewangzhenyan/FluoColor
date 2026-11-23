package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用矩形阵列几何测试，覆盖理论点、横纵 pitch 和单应投影。 */
class RegularGridGeometryTest {

    @Test
    fun `密度轴排除反光孤点但保留有核心邻居的边缘点`() {
        val positions = listOf(9.0, 10.0, 10.2, 24.0, 58.0, 59.0, 60.0, 109.0, 110.0, 111.0, 117.9, 137.0)
        val axis = requireNotNull(RegularGridGeometry.densityAxis(positions, count = 3, crossingCount = 8, nominalPitch = 50.0))
        assertEquals(listOf(10.0, 59.0, 110.0), axis.centers)
        assertEquals(positions.indices.toSet() - setOf(3, 10, 11), axis.members)
        val withBorder = positions.toMutableList().apply { this[10] = 116.5 }
        assertTrue(10 in requireNotNull(RegularGridGeometry.densityAxis(withBorder, 3, 8, 50.0)).members)
    }

    @Test
    fun `密度轴不补造缺失行也不任意选择重复的首行窗口`() {
        fun rowEvidence(vararg centers: Double) = centers.flatMap { center -> listOf(center - 0.5, center, center + 0.5) }
        assertNull(RegularGridGeometry.densityAxis(rowEvidence(15.0, 115.0), 3, 8, 50.0))
        assertNull(RegularGridGeometry.densityAxis(rowEvidence(15.0, 65.0, 115.0, 165.0), 3, 8, 50.0))
    }

    @Test
    fun `密度轴以观测支撑排除弱反光边且不改变孔号`() {
        // 两个窗口的孔距都合理，左端只是一条弱反光边。弱边够形成 DBSCAN 簇，不够和真实孔列等价。
        val glare = List(4) { 10.0 + it * .1 }
        val wells = listOf(60.0, 110.0, 160.0).flatMap { center -> List(12) { center + (it - 6) * .1 } }
        val chosen = requireNotNull(RegularGridGeometry.densityAxis(glare + wells, 3, 15, 50.0))
        assertEquals(listOf(59.95, 109.95, 159.95), chosen.centers)
        assertTrue(chosen.members.all { it >= glare.size })
    }

    @Test
    fun `人工角孔在透视下恢复等距物理阵列且历史保留角孔来源`() {
        // 独立投影构造真值：直接在原图四边做线性等分会与内部格点明显不符。
        val projection = doubleArrayOf(48.0, 5.0, 90.0, 3.0, 40.0, 75.0, 0.025, 0.018, 1.0)
        for ((rows, columns) in listOf(10 to 10, 15 to 15, 4 to 7, 2 to 2)) {
            fun expected(row: Int, column: Int) = RegularGridGeometry.project(projection, GridPoint(column.toDouble(), row.toDouble()))
            val corners = listOf(expected(0, 0), expected(0, columns-1), expected(rows-1, columns-1), expected(rows-1, 0))
            val grid = ManualGridGeometry.build(1100, 900, PgGridLocatorConfig(rows, columns, GridTargetPolarity.DARK), corners)
            val frozen = PgGridJsonCodec.decode(PgGridJsonCodec.encode(grid))
            assertEquals(corners, frozen.manualAnchorCenters)
            assertEquals(4, frozen.sites.count { it.source == GridPointSource.MANUAL_ANCHOR })
            assertEquals(rows*columns-4, frozen.sites.count { it.source == GridPointSource.MANUAL_INTERPOLATED })
            assertNull(frozen.geometry.geometryRmsePx)
            assertFalse(frozen.geometry.trusted)
            frozen.sites.forEach { site ->
                assertPointEquals(expected(site.key.rowIndex, site.key.columnIndex), site.original, 1e-6)
                assertPointEquals(site.rectified, RegularGridGeometry.project(frozen.homography.forward.toDoubleArray(), site.original), 1e-6)
            }
            assertTrue(frozen.sites.first().rectified.x > 0 && frozen.sites.first().rectified.y > 0)
        }
    }

    @Test
    fun `人工四角拒绝越界重复逆序交叉近共线和单行规格`() {
        val corners = listOf(GridPoint(10.0,10.0), GridPoint(190.0,10.0), GridPoint(190.0,190.0), GridPoint(10.0,190.0))
        val invalid = listOf(corners.take(3), corners.reversed(), listOf(corners[0], corners[2], corners[1], corners[3]),
            listOf(corners[0], corners[0], corners[2], corners[3]), corners.dropLast(1) + GridPoint(-1.0,40.0),
            listOf(GridPoint(10.0,10.0), GridPoint(30.0,10.0), GridPoint(50.0,10.001), GridPoint(20.0,10.003)))
        invalid.forEach { points -> assertTrue(runCatching { ManualGridGeometry.build(200,200,
            PgGridLocatorConfig(10,10,GridTargetPolarity.DARK),points) }.exceptionOrNull() is IllegalArgumentException) }
        assertTrue(runCatching { ManualGridGeometry.build(200,200,PgGridLocatorConfig(1,10,GridTargetPolarity.DARK),corners) }.isFailure)
        // 相机可以旋转，不能通过“左上角”排序擅自把用户明确指定的 A1 调到另一物理孔。
        val rotated = corners.drop(1) + corners.first()
        assertEquals(rotated.first(), ManualGridGeometry.build(200,200,PgGridLocatorConfig(4,4,GridTargetPolarity.DARK),rotated).sites.first().original)
    }

    @Test
    fun `人工标注的几何不依赖亮暗但裁切极性可由原图覆盖旧载体偏好`() {
        val corners = listOf(GridPoint(20.0,20.0),GridPoint(200.0,20.0),GridPoint(200.0,200.0),GridPoint(20.0,200.0))
        val grid = ManualGridGeometry.build(221,221,PgGridLocatorConfig(10,10,GridTargetPolarity.DARK),corners)
        assertEquals(GridTargetPolarity.BRIGHT, ManualGridGeometry.samplingPolarity(grid) { px, py ->
            if (grid.sites.any { kotlin.math.abs(px-it.original.x) < 4 && kotlin.math.abs(py-it.original.y)<4 }) 90.0 else 10.0
        })
        assertEquals(GridTargetPolarity.DARK, ManualGridGeometry.samplingPolarity(grid) { _, _ -> 0.0 })
    }

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
