package com.muc.fluocolorquant.utils.math

import kotlin.math.abs

/** 通用二维点行优先排序的结果，同时保留观测到的行数供 QC 诊断。 */
data class GridPointOrderingResult<T>(
    val items: List<T>,
    val observedRows: Int
)

/**
 * 将任意带二维中心点的数据按“从上到下、每行从左到右”排序。
 *
 * 该工具不包含 8 行、12 列或 96 位点先验；目标行数由调用者用于结果诊断，点排序
 * 本身只依据图像坐标。先按 Y 排序再聚类可消除检测器原始输出顺序对结果的影响。
 */
object GridPointOrdering {
    fun <T> sortRowMajor(
        items: List<T>,
        xSelector: (T) -> Float,
        ySelector: (T) -> Float,
        rowTolerance: Float
    ): GridPointOrderingResult<T> {
        require(rowTolerance > 0f) { "行聚类容差必须大于 0" }
        if (items.isEmpty()) return GridPointOrderingResult(emptyList(), observedRows = 0)

        val rows = mutableListOf<MutableList<T>>()
        items.sortedBy(ySelector).forEach { item ->
            val y = ySelector(item)
            val currentRow = rows.lastOrNull()
            if (currentRow == null) {
                rows += mutableListOf(item)
            } else {
                val averageY = currentRow.map(ySelector).average().toFloat()
                if (abs(y - averageY) < rowTolerance) {
                    currentRow += item
                } else {
                    rows += mutableListOf(item)
                }
            }
        }

        return GridPointOrderingResult(
            items = rows.flatMap { row -> row.sortedBy(xSelector) },
            observedRows = rows.size
        )
    }
}
