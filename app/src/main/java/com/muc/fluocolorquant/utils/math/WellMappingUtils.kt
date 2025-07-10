package com.muc.fluocolorquant.utils.math

/**
 * 孔位映射工具类 (最终修正版)
 * 在虚拟布局(固定8x12)和真实孔位(行列可变)之间进行索引和坐标转换
 */
object WellMappingUtils {

    // 虚拟布局的固定尺寸
    private const val VIRTUAL_COLS = 12

    /**
     * 将【真实孔位索引】映射到【虚拟8x12布局坐标】
     * 核心：无论真实板如何，都将其"拍平"成一个8x12的虚拟视图。
     */
    fun mapRealToVirtualCoordinates(realIndex: Int): Pair<Int, Int> {
        val virtualRow = realIndex / VIRTUAL_COLS
        val virtualCol = realIndex % VIRTUAL_COLS
        return Pair(virtualRow, virtualCol)
    }

    /**
     * 将【虚拟8x12布局坐标】映射到【真实孔位索引】
     */
    fun mapVirtualToRealIndex(
        virtualRow: Int,
        virtualCol: Int
    ): Int {
        return virtualRow * VIRTUAL_COLS + virtualCol
    }

    /**
     * 获取孔位的字母数字标识（如A1, B2等），始终基于8x12虚拟布局
     */
    fun getWellLabel(row: Int, col: Int): String {
        val rowLabel = ('A' + row).toString()
        val colLabel = (col + 1).toString()
        return "$rowLabel$colLabel"
    }
    
    /**
     * 从孔位标识解析出行列索引
     * @param wellLabel 孔位标识（如A1, B2等）
     * @return Pair(行, 列)，表示坐标（从0开始）
     */
    fun parseWellLabel(wellLabel: String): Pair<Int, Int>? {
        if (wellLabel.length < 2) return null
        
        val rowChar = wellLabel[0].uppercaseChar()
        if (rowChar !in 'A'..'Z') return null
        
        val colStr = wellLabel.substring(1)
        val col = colStr.toIntOrNull()?.minus(1) ?: return null
        if (col < 0) return null
        
        val row = rowChar - 'A'
        return Pair(row, col)
    }
} 