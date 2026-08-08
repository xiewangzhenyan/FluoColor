package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.linear.RealMatrix
import org.apache.commons.math3.linear.SingularValueDecomposition

/** 鲁棒单应拟合的显式结果；失败不会返回一个看似可用的单位矩阵。 */
sealed interface HomographyFitResult {
    data class Success(
        val matrix: DoubleArray,
        val residualsPx: List<Double>,
        val weights: List<Double>,
        val inlierIndices: Set<Int>,
        val inlierRmsePx: Double
    ) : HomographyFitResult

    data class InsufficientObservations(
        val minimumRequired: Int,
        val actualCount: Int
    ) : HomographyFitResult

    data class Degenerate(
        val reason: String
    ) : HomographyFitResult
}

/**
 * PG-Grid 使用的 Tukey-IRLS 单应平差。
 *
 * 参考 Python 工程先用归一化 DLT 拟合 3×3 单应矩阵，再根据重投影残差迭代更新
 * Tukey biweight。与普通最小二乘相比，遮挡、错误候选和局部高光产生的大偏移点会
 * 获得接近零的权重，不会把整张规则晶格一起拉偏。
 */
object RobustHomographyFitter {

    fun fit(
        source: List<GridPoint>,
        destination: List<GridPoint>,
        expectedSiteCount: Int,
        minimumObservationRatio: Double = DEFAULT_MINIMUM_OBSERVATION_RATIO,
        iterations: Int = DEFAULT_ITERATIONS
    ): HomographyFitResult {
        require(source.size == destination.size) { "源点与目标点数量必须一致" }
        require(expectedSiteCount > 0) { "预期位点数必须大于 0" }
        require(minimumObservationRatio in 0.0..1.0) { "最小观测比例必须位于 0 到 1" }
        require(iterations > 0) { "IRLS 迭代次数必须大于 0" }

        val minimumRequired = maxOf(
            MINIMUM_HOMOGRAPHY_POINT_COUNT,
            ceil(expectedSiteCount * minimumObservationRatio).toInt()
        )
        if (source.size < minimumRequired) {
            return HomographyFitResult.InsufficientObservations(
                minimumRequired = minimumRequired,
                actualCount = source.size
            )
        }

        var weights = DoubleArray(source.size) { 1.0 }
        var matrix: DoubleArray? = null
        repeat(iterations) {
            val fitted = fitWeightedNormalizedDlt(source, destination, weights)
                ?: return HomographyFitResult.Degenerate("归一化 DLT 无法求解")
            matrix = fitted
            val residuals = calculateResiduals(fitted, source, destination)
            val robustSigma = robustSigma(residuals)
            val tukeyCutoff = TUKEY_CONSTANT * robustSigma

            val updated = DoubleArray(residuals.size) { index ->
                val ratio = residuals[index] / tukeyCutoff
                if (!ratio.isFinite() || ratio >= 1.0) {
                    0.0
                } else {
                    (1.0 - ratio.pow(2)).pow(2)
                }
            }

            // 如果一次极端退化让有效权重少于 4 个，保留上一轮矩阵并返回明确失败，
            // 不能继续使用病态权重构造“可信”结果。
            if (updated.count { it > EFFECTIVE_WEIGHT_EPSILON } < MINIMUM_HOMOGRAPHY_POINT_COUNT) {
                return HomographyFitResult.Degenerate("Tukey 权重有效点不足")
            }
            weights = updated
        }

        val finalMatrix = fitWeightedNormalizedDlt(source, destination, weights)
            ?: matrix
            ?: return HomographyFitResult.Degenerate("单应矩阵未生成")
        val residuals = calculateResiduals(finalMatrix, source, destination)
        val inlierIndices = weights.indices.filterTo(linkedSetOf()) {
            weights[it] >= INLIER_WEIGHT_THRESHOLD
        }
        if (inlierIndices.size < MINIMUM_HOMOGRAPHY_POINT_COUNT) {
            return HomographyFitResult.Degenerate("最终内点不足")
        }
        val rmse = sqrt(inlierIndices.sumOf { residuals[it].pow(2) } / inlierIndices.size)
        return HomographyFitResult.Success(
            matrix = finalMatrix,
            residualsPx = residuals,
            weights = weights.toList(),
            inlierIndices = inlierIndices,
            inlierRmsePx = rmse
        )
    }

    /**
     * 使用归一化坐标构建加权 8 参数 DLT，固定 h33=1。
     *
     * 坐标先归一化到平均距离 sqrt(2)，可显著减小像素坐标量级差造成的条件数问题；
     * 解出归一化矩阵后再执行 `Tdst^-1 * H * Tsrc` 回到原像素坐标。
     */
    private fun fitWeightedNormalizedDlt(
        source: List<GridPoint>,
        destination: List<GridPoint>,
        weights: DoubleArray
    ): DoubleArray? {
        val sourceNormalization = normalize(source) ?: return null
        val destinationNormalization = normalize(destination) ?: return null
        val rowCount = source.size * 2
        val design = Array(rowCount) { DoubleArray(DLT_PARAMETER_COUNT) }
        val targets = DoubleArray(rowCount)

        source.indices.forEach { index ->
            val sourcePoint = sourceNormalization.points[index]
            val destinationPoint = destinationNormalization.points[index]
            val weightScale = sqrt(weights[index].coerceAtLeast(0.0))
            val firstRow = index * 2
            val secondRow = firstRow + 1

            design[firstRow][0] = sourcePoint.x * weightScale
            design[firstRow][1] = sourcePoint.y * weightScale
            design[firstRow][2] = weightScale
            design[firstRow][6] = -destinationPoint.x * sourcePoint.x * weightScale
            design[firstRow][7] = -destinationPoint.x * sourcePoint.y * weightScale
            targets[firstRow] = destinationPoint.x * weightScale

            design[secondRow][3] = sourcePoint.x * weightScale
            design[secondRow][4] = sourcePoint.y * weightScale
            design[secondRow][5] = weightScale
            design[secondRow][6] = -destinationPoint.y * sourcePoint.x * weightScale
            design[secondRow][7] = -destinationPoint.y * sourcePoint.y * weightScale
            targets[secondRow] = destinationPoint.y * weightScale
        }

        return try {
            val designMatrix = MatrixUtils.createRealMatrix(design)
            val decomposition = SingularValueDecomposition(designMatrix)
            // 设计矩阵通常是“行数远大于列数”的超定矩阵，此时 solver.isNonSingular
            // 会按方阵语义返回 false；真正需要检查的是 8 个未知量是否达到满列秩。
            if (decomposition.rank < DLT_PARAMETER_COUNT) return null
            val solver = decomposition.solver
            val parameters = solver.solve(MatrixUtils.createRealVector(targets)).toArray()
            val normalizedHomography = MatrixUtils.createRealMatrix(
                arrayOf(
                    doubleArrayOf(parameters[0], parameters[1], parameters[2]),
                    doubleArrayOf(parameters[3], parameters[4], parameters[5]),
                    doubleArrayOf(parameters[6], parameters[7], 1.0)
                )
            )
            val destinationInverse = LUDecomposition(destinationNormalization.transform).solver.inverse
            val denormalized = destinationInverse
                .multiply(normalizedHomography)
                .multiply(sourceNormalization.transform)
            flattenAndScale(denormalized)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 计算让平均点距为 sqrt(2) 的 Hartley 归一化。 */
    private fun normalize(points: List<GridPoint>): NormalizedPoints? {
        if (points.size < MINIMUM_HOMOGRAPHY_POINT_COUNT) return null
        val centerX = points.map(GridPoint::x).average()
        val centerY = points.map(GridPoint::y).average()
        val meanDistance = points.map { point ->
            kotlin.math.hypot(point.x - centerX, point.y - centerY)
        }.average()
        if (!meanDistance.isFinite() || meanDistance <= NORMALIZATION_EPSILON) return null

        val scale = sqrt(2.0) / meanDistance
        val transform = MatrixUtils.createRealMatrix(
            arrayOf(
                doubleArrayOf(scale, 0.0, -scale * centerX),
                doubleArrayOf(0.0, scale, -scale * centerY),
                doubleArrayOf(0.0, 0.0, 1.0)
            )
        )
        return NormalizedPoints(
            points = points.map { point ->
                GridPoint(
                    x = scale * (point.x - centerX),
                    y = scale * (point.y - centerY)
                )
            },
            transform = transform
        )
    }

    /** 把 Commons Math 矩阵转为行主序数组，并统一缩放到 h33=1。 */
    private fun flattenAndScale(matrix: RealMatrix): DoubleArray? {
        val scale = matrix.getEntry(2, 2)
        if (!scale.isFinite() || kotlin.math.abs(scale) <= NORMALIZATION_EPSILON) return null
        return DoubleArray(9) { index ->
            val value = matrix.getEntry(index / 3, index % 3) / scale
            if (!value.isFinite()) return null
            value
        }
    }

    private fun calculateResiduals(
        matrix: DoubleArray,
        source: List<GridPoint>,
        destination: List<GridPoint>
    ): List<Double> {
        return source.indices.map { index ->
            val projected = RegularGridGeometry.project(matrix, source[index])
            kotlin.math.hypot(
                destination[index].x - projected.x,
                destination[index].y - projected.y
            )
        }
    }

    /**
     * 用中位绝对偏差估计残差尺度，并保留很小的亚像素下限。
     * 下限只防止完美合成数据出现除零，不改变真实照片中的像素级残差阈值。
     */
    private fun robustSigma(residuals: List<Double>): Double {
        val median = median(residuals)
        val mad = median(residuals.map { kotlin.math.abs(it - median) })
        return maxOf(ROBUST_SIGMA_FLOOR_PX, 1.4826 * mad)
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "中位数输入不能为空" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private data class NormalizedPoints(
        val points: List<GridPoint>,
        val transform: RealMatrix
    )

    private const val DEFAULT_MINIMUM_OBSERVATION_RATIO: Double = 0.4
    private const val DEFAULT_ITERATIONS: Int = 5
    private const val MINIMUM_HOMOGRAPHY_POINT_COUNT: Int = 4
    private const val DLT_PARAMETER_COUNT: Int = 8
    private const val TUKEY_CONSTANT: Double = 4.685
    private const val EFFECTIVE_WEIGHT_EPSILON: Double = 1e-6
    private const val INLIER_WEIGHT_THRESHOLD: Double = 0.05
    private const val NORMALIZATION_EPSILON: Double = 1e-12
    private const val ROBUST_SIGMA_FLOOR_PX: Double = 0.05
}
