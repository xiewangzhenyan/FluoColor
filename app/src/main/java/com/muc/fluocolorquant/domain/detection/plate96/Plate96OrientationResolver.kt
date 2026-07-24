package com.muc.fluocolorquant.domain.detection.plate96

import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import javax.inject.Inject

/** 方向评分所消费的圆心观测；保持纯Kotlin以便桌面单元测试。 */
data class Plate96CircleObservation(
    val x: Double,
    val y: Double,
    val radius: Double,
    val confidence: Double,
    val observationIndex: Int
) {
    init {
        require(x.isFinite() && y.isFinite()) { "圆心坐标必须为有限数值" }
        require(radius.isFinite() && radius > 0.0) { "圆孔半径必须为正有限数值" }
        require(confidence in 0.0..1.0) { "圆孔置信度必须位于0到1" }
        require(observationIndex >= 0) { "观测索引不能为负数" }
    }
}

/** 一个方向候选的完整评分和圆心到源图网格的分配。 */
data class Plate96OrientationCandidate(
    val sourceRows: Int,
    val sourceColumns: Int,
    val score: Double,
    val occupiedCellRatio: Double,
    val axisCoverageRatio: Double,
    val residualScore: Double,
    val aspectScore: Double,
    val rowCenters: List<Double>,
    val columnCenters: List<Double>,
    val assignments: Map<ArrayGridCoordinate, Plate96CircleObservation>
) {
    init {
        require(sourceRows > 0 && sourceColumns > 0) { "方向候选行列必须大于0" }
        require(score in 0.0..1.0) { "方向候选评分必须位于0到1" }
        require(rowCenters.size == sourceRows && columnCenters.size == sourceColumns) {
            "方向候选轴中心数量与行列不一致"
        }
    }
}

/** 只根据孔阵几何判断横竖版；A1角落必须由载体标记或用户确认。 */
data class Plate96OrientationResolution(
    val recommended: Plate96OrientationCandidate,
    val alternative: Plate96OrientationCandidate,
    val orientation: ArrayOrientationSnapshot,
    val ambiguous: Boolean,
    val requiresOriginConfirmation: Boolean
)

/**
 * 96孔板8×12/12×8方向裁决器。
 *
 * 算法分别拟合“8行12列”和“12行8列”两个一维聚类组合，并综合唯一占位率、轴覆盖、
 * 归一化残差、包围盒长宽比和候选数量。它不会声称从完全对称的圆阵中识别出了A1。
 */
class Plate96OrientationResolver @Inject constructor() {

    fun resolve(observations: List<Plate96CircleObservation>): Plate96OrientationResolution {
        require(observations.size >= MINIMUM_OBSERVATION_COUNT) { "至少需要12个圆孔观测才能判断96孔板方向" }
        val landscape = scoreCandidate(observations, sourceRows = 8, sourceColumns = 12)
        val portrait = scoreCandidate(observations, sourceRows = 12, sourceColumns = 8)
        val recommended = if (landscape.score >= portrait.score) landscape else portrait
        val alternative = if (recommended === landscape) portrait else landscape
        val scoreGap = recommended.score - alternative.score
        val confidence = (0.5 + scoreGap * 2.5).coerceIn(0.5, 0.99)
        val ambiguous = scoreGap < AMBIGUOUS_SCORE_GAP || recommended.score < MINIMUM_CLEAR_SCORE

        // 竖版只能从几何判断“需要旋转90度”，无法判断顺时针还是逆时针。
        // 当前采用顺时针作为预览建议，并强制保留A1确认状态，后续UI可一键切换另一方向。
        val suggestedCorner = if (recommended.sourceRows == 8) {
            ArrayOriginCorner.TOP_LEFT
        } else {
            ArrayOriginCorner.BOTTOM_LEFT
        }
        val orientation = Plate96LayoutContract.orientation(
            originCorner = suggestedCorner,
            source = ArrayOrientationSource.AUTO,
            confidence = confidence
        )
        return Plate96OrientationResolution(
            recommended = recommended,
            alternative = alternative,
            orientation = orientation,
            ambiguous = ambiguous,
            requiresOriginConfirmation = true
        )
    }

    private fun scoreCandidate(
        observations: List<Plate96CircleObservation>,
        sourceRows: Int,
        sourceColumns: Int
    ): Plate96OrientationCandidate {
        val rowClusters = clusterAxis(observations.map { it.y }, sourceRows)
        val columnClusters = clusterAxis(observations.map { it.x }, sourceColumns)
        val selectedByCell = mutableMapOf<ArrayGridCoordinate, Plate96CircleObservation>()
        observations.forEachIndexed { index, observation ->
            val coordinate = ArrayGridCoordinate(
                rowIndex = rowClusters.assignment[index],
                columnIndex = columnClusters.assignment[index]
            )
            val previous = selectedByCell[coordinate]
            if (previous == null || observation.confidence > previous.confidence) {
                selectedByCell[coordinate] = observation
            }
        }

        val occupiedCellRatio = selectedByCell.size.toDouble() / Plate96LayoutContract.SITE_COUNT
        val axisCoverageRatio = (
            rowClusters.nonEmptyClusterCount.toDouble() / sourceRows +
                columnClusters.nonEmptyClusterCount.toDouble() / sourceColumns
            ) / 2.0
        val residualScore = 1.0 / (1.0 + rowClusters.normalizedRmse + columnClusters.normalizedRmse)
        val width = (observations.maxOf { it.x } - observations.minOf { it.x }).coerceAtLeast(1.0)
        val height = (observations.maxOf { it.y } - observations.minOf { it.y }).coerceAtLeast(1.0)
        val observedAspect = width / height
        val expectedAspect = (sourceColumns - 1.0) / (sourceRows - 1.0)
        val aspectScore = kotlin.math.exp(-abs(ln(observedAspect / expectedAspect)))
        val countScore = 1.0 - (abs(observations.size - Plate96LayoutContract.SITE_COUNT).toDouble() /
            Plate96LayoutContract.SITE_COUNT).coerceAtMost(1.0)
        val score = (
            occupiedCellRatio * 0.35 +
                axisCoverageRatio * 0.25 +
                residualScore * 0.20 +
                aspectScore * 0.15 +
                countScore * 0.05
            ).coerceIn(0.0, 1.0)

        return Plate96OrientationCandidate(
            sourceRows = sourceRows,
            sourceColumns = sourceColumns,
            score = score,
            occupiedCellRatio = occupiedCellRatio,
            axisCoverageRatio = axisCoverageRatio,
            residualScore = residualScore,
            aspectScore = aspectScore,
            rowCenters = rowClusters.centers,
            columnCenters = columnClusters.centers,
            assignments = selectedByCell
        )
    }

    /** 对单轴执行固定簇数K-means；空簇会保留等距先验中心并降低覆盖得分。 */
    private fun clusterAxis(values: List<Double>, clusterCount: Int): AxisClusters {
        require(values.isNotEmpty()) { "聚类输入不能为空" }
        val minimum = values.min()
        val maximum = values.max()
        val span = (maximum - minimum).coerceAtLeast(1.0)
        var centers = List(clusterCount) { index ->
            if (clusterCount == 1) (minimum + maximum) / 2.0
            else minimum + span * index / (clusterCount - 1.0)
        }
        var assignments = IntArray(values.size)
        repeat(K_MEANS_ITERATIONS) {
            assignments = IntArray(values.size) { valueIndex ->
                centers.indices.minBy { centerIndex -> abs(values[valueIndex] - centers[centerIndex]) }
            }
            val sums = DoubleArray(clusterCount)
            val counts = IntArray(clusterCount)
            values.forEachIndexed { valueIndex, value ->
                val cluster = assignments[valueIndex]
                sums[cluster] += value
                counts[cluster]++
            }
            val updated = centers.mapIndexed { index, previous ->
                if (counts[index] == 0) previous else sums[index] / counts[index]
            }
            if (updated.indices.all { abs(updated[it] - centers[it]) < CENTER_EPSILON }) {
                centers = updated
                return@repeat
            }
            centers = updated
        }

        // 重新按坐标排序簇中心，保证行列索引始终从上到下、从左到右。
        val oldToNew = centers.indices.sortedBy { centers[it] }
            .withIndex()
            .associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val sortedCenters = centers.sorted()
        val sortedAssignments = IntArray(assignments.size) { index -> oldToNew.getValue(assignments[index]) }
        val counts = IntArray(clusterCount)
        sortedAssignments.forEach { counts[it]++ }
        val pitch = sortedCenters.zipWithNext { left, right -> right - left }
            .filter { it > CENTER_EPSILON }
            .sorted()
            .let { gaps -> if (gaps.isEmpty()) span / max(clusterCount - 1, 1) else gaps[gaps.size / 2] }
            .coerceAtLeast(1.0)
        val squaredError = values.indices.sumOf { index ->
            val delta = values[index] - sortedCenters[sortedAssignments[index]]
            delta * delta
        }
        val normalizedRmse = sqrt(squaredError / values.size) / pitch
        return AxisClusters(
            centers = sortedCenters,
            assignment = sortedAssignments,
            nonEmptyClusterCount = counts.count { it > 0 },
            normalizedRmse = normalizedRmse
        )
    }

    private data class AxisClusters(
        val centers: List<Double>,
        val assignment: IntArray,
        val nonEmptyClusterCount: Int,
        val normalizedRmse: Double
    )

    private companion object {
        const val MINIMUM_OBSERVATION_COUNT: Int = 12
        const val K_MEANS_ITERATIONS: Int = 20
        const val CENTER_EPSILON: Double = 1e-6
        const val AMBIGUOUS_SCORE_GAP: Double = 0.06
        const val MINIMUM_CLEAR_SCORE: Double = 0.70
    }
}
