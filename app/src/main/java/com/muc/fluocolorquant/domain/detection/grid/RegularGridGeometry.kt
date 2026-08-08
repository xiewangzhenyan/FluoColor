package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.hypot

/** 横向、纵向和代表性网格间距，单位均为矫正图像素。 */
data class GridPitch(
    val horizontalPx: Double,
    val verticalPx: Double,
    val representativePx: Double
)

/**
 * 与图像库无关的规则阵列几何工具。
 *
 * Android 端必须支持独立 rows × columns，因此这里不接受单一 gridSize。所有点按
 * 行优先顺序返回，与模板位点、Room siteIndex、热力图和 Python V2.1 契约一致。
 */
object RegularGridGeometry {

    /**
     * 在给定矩形内生成带等比例边距的理论晶格点。
     *
     * 当某个维度只有一个位点时，该位点放在对应方向中心，避免除以零或偏到一侧。
     */
    fun generate(
        rows: Int,
        columns: Int,
        width: Double,
        height: Double,
        marginRatio: Double
    ): List<GridPoint> {
        require(rows > 0 && columns > 0) { "阵列行列必须大于 0" }
        require(width.isFinite() && width > 0.0) { "阵列宽度必须为正有限数值" }
        require(height.isFinite() && height > 0.0) { "阵列高度必须为正有限数值" }
        require(marginRatio.isFinite() && marginRatio in 0.0..<0.5) {
            "阵列边距比例必须位于 [0, 0.5)"
        }

        val xMargin = width * marginRatio
        val yMargin = height * marginRatio
        val xs = linearPositions(columns, xMargin, width - xMargin)
        val ys = linearPositions(rows, yMargin, height - yMargin)

        return buildList(rows * columns) {
            ys.forEach { y ->
                xs.forEach { x -> add(GridPoint(x = x, y = y)) }
            }
        }
    }

    /**
     * 从行优先点位估算横纵 pitch。
     *
     * 使用相邻点欧氏距离的中位数，可容忍轻微旋转和透视残差；横纵值分别保留，
     * 后续矩形阵列 ROI 可以使用两者较小值，不能只按图片边长和最大行列数猜测。
     */
    fun estimatePitch(points: List<GridPoint>, rows: Int, columns: Int): GridPitch {
        require(rows > 0 && columns > 0) { "阵列行列必须大于 0" }
        require(points.size == rows * columns) {
            "点数必须等于 rows × columns"
        }

        val horizontalDistances = mutableListOf<Double>()
        if (columns > 1) {
            for (row in 0 until rows) {
                for (column in 0 until columns - 1) {
                    val left = points[row * columns + column]
                    val right = points[row * columns + column + 1]
                    horizontalDistances += distance(left, right)
                }
            }
        }

        val verticalDistances = mutableListOf<Double>()
        if (rows > 1) {
            for (row in 0 until rows - 1) {
                for (column in 0 until columns) {
                    val top = points[row * columns + column]
                    val bottom = points[(row + 1) * columns + column]
                    verticalDistances += distance(top, bottom)
                }
            }
        }

        val horizontal = median(horizontalDistances)
        val vertical = median(verticalDistances)
        val available = listOf(horizontal, vertical).filter { it > 0.0 }
        val representative = if (available.isEmpty()) 0.0 else available.average()
        return GridPitch(
            horizontalPx = horizontal,
            verticalPx = vertical,
            representativePx = representative
        )
    }

    /** 使用行主序 3×3 单应矩阵投影一个二维点。 */
    fun project(matrix: DoubleArray, point: GridPoint): GridPoint {
        require(matrix.size == HOMOGRAPHY_ELEMENT_COUNT) { "单应矩阵必须包含 9 个元素" }
        require(matrix.all(Double::isFinite)) { "单应矩阵包含无效数值" }
        val denominator = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
        require(denominator.isFinite() && kotlin.math.abs(denominator) > HOMOGENEOUS_EPSILON) {
            "单应投影的齐次分母接近零"
        }
        return GridPoint(
            x = (matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / denominator,
            y = (matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / denominator
        )
    }

    /** 生成包含首尾的线性坐标序列，单点维度取矩形中心。 */
    private fun linearPositions(count: Int, start: Double, end: Double): List<Double> {
        if (count == 1) return listOf((start + end) / 2.0)
        val span = end - start
        return List(count) { index -> start + span * index / (count - 1).toDouble() }
    }

    private fun distance(first: GridPoint, second: GridPoint): Double {
        return hypot(second.x - first.x, second.y - first.y)
    }

    /** 空列表返回 0，便于 1×N 或 N×1 阵列只使用存在的方向。 */
    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private const val HOMOGRAPHY_ELEMENT_COUNT: Int = 9
    private const val HOMOGENEOUS_EPSILON: Double = 1e-12
}
