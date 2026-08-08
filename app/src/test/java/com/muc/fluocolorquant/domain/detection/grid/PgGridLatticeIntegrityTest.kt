package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幻影边缘（晶格整体错位一个间距）检测的判据测试。
 *
 * 这类错误对支撑率、包围率和观测率全部免疫，是唯一必须靠边界几何才能发现的失败模式，
 * 因此四条判据的“同时成立”与“单独不成立”都要有确定性用例。
 */
class PgGridLatticeIntegrityTest {

    private val gridSize = 6
    private val pitch = 40.0
    private val origin = 100.0

    /** 生成真实单元点云；行距与列距分开给出，便于构造“某一轴被拉伸”的错位场景。 */
    private fun realUnits(
        rows: Int,
        columns: Int,
        rowPitch: Double = pitch,
        columnPitch: Double = pitch
    ): List<GridCandidate> = buildList {
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                add(
                    GridCandidate(
                        point = GridPoint(origin + column * columnPitch, origin + row * rowPitch),
                        weight = 1.0
                    )
                )
            }
        }
    }

    /** 生成 [gridSize] 见方的规则晶格；行距可独立设定以构造两轴间距失配。 */
    private fun lattice(rowPitch: Double = pitch): List<GridPoint> = buildList {
        for (row in 0 until gridSize) {
            for (column in 0 until gridSize) {
                add(GridPoint(origin + column * pitch, origin + row * rowPitch))
            }
        }
    }

    @Test
    fun `与真实单元完全重合的晶格不会被判为幻影`() {
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice(),
            rows = gridSize,
            columns = gridSize,
            candidates = realUnits(gridSize, gridSize),
            pitch = pitch
        )
        assertTrue(result.available)
        assertNull(result.flagged)
        assertEquals(0.0, result.anisotropy!!, 1e-9)
    }

    @Test
    fun `多出一条无支撑边缘行且行距被拉伸时判为幻影边缘`() {
        // 真实阵列只有 5 行（行距 44、列距 40），拟合却把 6 行硬塞了进去：
        // 前 5 行仍然精确落在真实单元上（内侧邻行支撑良好），底部多出的第 6 行
        // 伸到点云之外且自身毫无支撑，同时两轴间距失配达到 0.1。
        // 这正是支撑率/包围率/观测率全都看不见、只能在边界发现的失败模式。
        val stretchedRowPitch = pitch * 1.10
        val candidates = realUnits(
            rows = gridSize - 1,
            columns = gridSize,
            rowPitch = stretchedRowPitch,
            columnPitch = pitch
        )
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice(rowPitch = stretchedRowPitch),
            rows = gridSize,
            columns = gridSize,
            candidates = candidates,
            pitch = pitch
        )
        assertTrue(result.available)
        assertEquals("row_hi", result.flagged)
        assertNotNull(result.flaggedOverhang)
        assertTrue(result.flaggedOverhang!! > 0.5)
        assertTrue(result.anisotropy!! > 0.03)
    }

    @Test
    fun `仅边缘行变暗但几何未拉伸时不判为幻影`() {
        // 强光照梯度下最暗的一行检不到候选，前三条判据同样成立，但几何完全没有变形。
        // 各向异性这条判据正是为了不误伤这种情况。
        val candidates = realUnits(rows = gridSize, columns = gridSize)
            .filter { it.point.y < origin + (gridSize - 1) * pitch - 1.0 }
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice(),
            rows = gridSize,
            columns = gridSize,
            candidates = candidates,
            pitch = pitch
        )
        assertTrue(result.available)
        assertNull(result.flagged)
        assertTrue(result.anisotropy!! <= 0.03)
    }

    @Test
    fun `没有候选时检查不可用而不是判定通过`() {
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice(),
            rows = gridSize,
            columns = gridSize,
            candidates = emptyList(),
            pitch = pitch
        )
        assertFalse(result.available)
        assertNull(result.flagged)
    }

    @Test
    fun `行列数不足三时不做判定`() {
        val small = listOf(
            GridPoint(0.0, 0.0), GridPoint(10.0, 0.0),
            GridPoint(0.0, 10.0), GridPoint(10.0, 10.0)
        )
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = small,
            rows = 2,
            columns = 2,
            candidates = realUnits(2, 2),
            pitch = 10.0
        )
        assertFalse(result.available)
    }

    @Test
    fun `点数与行列不符时不做判定`() {
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice().dropLast(1),
            rows = gridSize,
            columns = gridSize,
            candidates = realUnits(gridSize, gridSize),
            pitch = pitch
        )
        assertFalse(result.available)
    }

    @Test
    fun `幻影诊断在标记时必须通过契约校验`() {
        val stretchedRowPitch = pitch * 1.10
        val result = PgGridLatticeIntegrity.detectPhantomEdge(
            points = lattice(rowPitch = stretchedRowPitch),
            rows = gridSize,
            columns = gridSize,
            candidates = realUnits(
                rows = gridSize - 1,
                columns = gridSize,
                rowPitch = stretchedRowPitch,
                columnPitch = pitch
            ),
            pitch = pitch
        )
        assertEquals("row_hi", result.flagged)
        // 契约要求：标记幻影时检查必须可用，且数值均为有限值。
        result.requireValid()
    }
}
