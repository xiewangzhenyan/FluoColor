package com.muc.fluocolorquant.utils.math

/**
 * 通用位点索引与坐标转换工具。
 *
 * 所有孔板、微流控芯片和自定义规则阵列都使用行优先顺序。列数必须由项目或载体
 * 显式传入，避免 10×10、15×15 和非方阵被旧的固定 12 列公式错误映射。
 */
object WellMappingUtils {
    /**
     * 将行优先线性索引转换为零基行列坐标。
     */
    fun mapRealToVirtualCoordinates(realIndex: Int, columns: Int): Pair<Int, Int> {
        require(realIndex >= 0) { "位点索引不能为负数" }
        require(columns > 0) { "列数必须大于 0" }
        return Pair(realIndex / columns, realIndex % columns)
    }

    /**
     * 将零基行列坐标转换为行优先线性索引。
     */
    fun mapVirtualToRealIndex(
        virtualRow: Int,
        virtualCol: Int,
        columns: Int
    ): Int {
        require(virtualRow >= 0) { "行索引不能为负数" }
        require(virtualCol >= 0) { "列索引不能为负数" }
        require(columns > 0) { "列数必须大于 0" }
        require(virtualCol < columns) { "列索引超出当前阵列范围" }
        return virtualRow * columns + virtualCol
    }

    /**
     * 获取孔板风格位点标识（如 A1、B2、AA3）。
     * 微流控新结果页仍使用中性 R01C01；该标签只服务旧孔板兼容页面。
     */
    fun getWellLabel(row: Int, col: Int): String {
        require(row >= 0) { "行索引不能为负数" }
        require(col >= 0) { "列索引不能为负数" }
        val rowLabel = getRowLabel(row)
        val colLabel = (col + 1).toString()
        return "$rowLabel$colLabel"
    }

    /** 根据行优先索引直接生成孔板风格标签。 */
    fun getWellLabelForIndex(index: Int, columns: Int): String {
        val (row, col) = mapRealToVirtualCoordinates(index, columns)
        return getWellLabel(row, col)
    }

    /**
     * 将零基行号转换为 Excel 风格字母，支持超过 26 行的自定义阵列。
     */
    fun getRowLabel(row: Int): String {
        require(row >= 0) { "行索引不能为负数" }
        var value = row + 1
        val label = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            label.append(('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return label.reverse().toString()
    }
    
    /**
     * 从孔位标识解析出行列索引
     * @param wellLabel 孔位标识（如A1, B2等）
     * @return Pair(行, 列)，表示坐标（从0开始）
     */
    fun parseWellLabel(wellLabel: String): Pair<Int, Int>? {
        val normalized = wellLabel.trim().uppercase()
        if (normalized.length < 2) return null

        val rowPart = normalized.takeWhile { it in 'A'..'Z' }
        if (rowPart.isEmpty() || rowPart.length == normalized.length) return null

        val colStr = normalized.substring(rowPart.length)
        val col = colStr.toIntOrNull()?.minus(1) ?: return null
        if (col < 0) return null

        var rowNumber = 0L
        rowPart.forEach { char ->
            rowNumber = rowNumber * 26L + (char - 'A' + 1)
            if (rowNumber > Int.MAX_VALUE.toLong()) return null
        }
        val row = (rowNumber - 1L).toInt()
        return Pair(row, col)
    }
}
