package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 晶格完整性检查：识别“整体错位一个间距”的幻影边缘。
 *
 * 这是**候选支撑率、包围率和观测率结构性看不见**的那种错误。整块晶格沿行或列平移一个
 * 间距后，绝大多数点仍然落在真实单元上——这三个信号都是共享行上的比值，几乎不动。
 * 唯一能看见它的地方在边界：平移会在一侧凭空造出一条本不存在的行，同时把另一侧真实的
 * 一行丢掉。
 */
internal object PgGridLatticeIntegrity {

    /** 边缘行相对候选点云的最小外伸量（单位：间距）。 */
    private const val OVERHANG_MIN: Double = 0.5

    /** 幻影边缘行自身允许的最大候选占有率。 */
    private const val EDGE_OCCUPANCY_MAX: Double = 0.20

    /** 内侧邻行必须达到的最小候选占有率。 */
    private const val INNER_OCCUPANCY_MIN: Double = 0.50

    /** 两轴间距失配的最小值。 */
    private const val ANISOTROPY_MIN: Double = 0.03

    /** 判定“点位有候选支撑”的距离容差相对间距的比例。 */
    private const val OCCUPANCY_TOLERANCE_PITCH_RATIO: Double = 0.25

    /**
     * 检出凭空多出一条边缘行/列的整格错位。
     *
     * 那条凭空的行有三个同时成立的特征：
     * 1. 它伸到候选点云之外（外伸 > 0.5 个间距）；
     * 2. 它自己几乎没有候选支撑（占有率 < 0.20）；
     * 3. 但它内侧的邻行支撑良好（占有率 > 0.50）。
     *
     * 第三条是必需的：整版都没有候选时（重模糊、检测器失效）前两条也会成立，但那属于
     * “检查手段缺席”而不是“网格错位”，不该据此判错。
     *
     * 前三条还不够——**边缘行只是碰巧很暗**时它们同样成立（强光照梯度下最暗的一行检不到
     * 候选，但网格其实是对的）。因此再加第四条：
     * 4. 两轴间距失配（各向异性 > 0.03）。
     *
     * 依据是几何：整格错位迫使拟合把 N 行硬塞进实际只容得下 N−1 行的跨度，那一轴必然被
     * **拉伸**；而边缘行变暗完全不改变几何。这一条只在前三条已成立时才施加——单独使用
     * 各向异性会误伤实拍板（连接器和通道线让区域框与阵列不齐，各向异性可达 0.08~0.11
     * 却定位正确），而那些图的外伸量是负的，压根进不到这一步。
     *
     * 两轴间距可比的前提是矫正图按行列数等比生成（见 [PgGridLocatorConfig]），因此本
     * 判据对非方阵同样成立。
     */
    fun detectPhantomEdge(
        points: List<GridPoint>,
        rows: Int,
        columns: Int,
        candidates: List<GridCandidate>,
        pitch: Double
    ): GridPhantomEdgeDiagnostics {
        if (rows < 3 || columns < 3) return GridPhantomEdgeDiagnostics(available = false)
        if (points.size != rows * columns) return GridPhantomEdgeDiagnostics(available = false)
        if (candidates.isEmpty()) return GridPhantomEdgeDiagnostics(available = false)

        val tolerance = maxOf(3.0, pitch * OCCUPANCY_TOLERANCE_PITCH_RATIO)
        fun at(row: Int, column: Int): GridPoint = points[row * columns + column]

        // 两轴间距失配：方阵经矫正后行列间距应当相等，错位拉伸会破坏这一点。
        val verticalSteps = buildList {
            for (row in 0 until rows - 1) {
                for (column in 0 until columns) {
                    add(distance(at(row, column), at(row + 1, column)))
                }
            }
        }
        val horizontalSteps = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns - 1) {
                    add(distance(at(row, column), at(row, column + 1)))
                }
            }
        }
        val rowPitch = median(verticalSteps)
        val columnPitch = median(horizontalSteps)
        val anisotropy = abs(rowPitch / maxOf(columnPitch, 1e-9) - 1.0)

        val edges = listOf(
            EdgeDefinition("row_lo", List(columns) { at(0, it) }, List(columns) { at(1, it) }),
            EdgeDefinition("row_hi", List(columns) { at(rows - 1, it) }, List(columns) { at(rows - 2, it) }),
            EdgeDefinition("col_lo", List(rows) { at(it, 0) }, List(rows) { at(it, 1) }),
            EdgeDefinition("col_hi", List(rows) { at(it, columns - 1) }, List(rows) { at(it, columns - 2) })
        )

        var flagged: String? = null
        var flaggedOverhang = Double.NEGATIVE_INFINITY
        edges.forEach { definition ->
            // 外法向：由内侧邻行指向边缘行。整条边取平均，个别点的精修抖动不至于把方向带偏。
            val normalX = definition.edge.map(GridPoint::x).average() - definition.inner.map(GridPoint::x).average()
            val normalY = definition.edge.map(GridPoint::y).average() - definition.inner.map(GridPoint::y).average()
            val norm = hypot(normalX, normalY)
            if (norm < 1e-6) return@forEach
            val unitX = normalX / norm
            val unitY = normalY / norm

            // 边缘行取中位投影而不是最大值：个别点的精修抖动不该主导判定。
            val edgeProjection = median(definition.edge.map { it.x * unitX + it.y * unitY })
            val cloudProjection = candidates.maxOf { it.point.x * unitX + it.point.y * unitY }
            val overhang = (edgeProjection - cloudProjection) / maxOf(pitch, 1e-6)
            if (overhang <= OVERHANG_MIN) return@forEach

            val edgeOccupancy = occupancy(definition.edge, candidates, tolerance)
            if (edgeOccupancy >= EDGE_OCCUPANCY_MAX) return@forEach
            val innerOccupancy = occupancy(definition.inner, candidates, tolerance)
            if (innerOccupancy <= INNER_OCCUPANCY_MIN) return@forEach
            if (anisotropy <= ANISOTROPY_MIN) return@forEach

            if (overhang > flaggedOverhang) {
                flaggedOverhang = overhang
                flagged = definition.name
            }
        }

        return GridPhantomEdgeDiagnostics(
            available = true,
            flagged = flagged,
            flaggedOverhang = flagged?.let { flaggedOverhang },
            anisotropy = anisotropy,
            rowPitchPx = rowPitch,
            columnPitchPx = columnPitch
        )
    }

    /** 一行/一列点位中，有多少比例在容差内找得到真实候选。 */
    private fun occupancy(
        line: List<GridPoint>,
        candidates: List<GridCandidate>,
        tolerance: Double
    ): Double {
        if (line.isEmpty()) return 0.0
        val supported = line.count { point ->
            candidates.any { candidate -> distance(point, candidate.point) <= tolerance }
        }
        return supported.toDouble() / line.size
    }

    private fun distance(first: GridPoint, second: GridPoint): Double {
        return hypot(second.x - first.x, second.y - first.y)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private data class EdgeDefinition(
        val name: String,
        val edge: List<GridPoint>,
        val inner: List<GridPoint>
    )
}
