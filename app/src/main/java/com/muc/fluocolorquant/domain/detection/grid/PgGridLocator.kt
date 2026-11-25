package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PG-Grid 定位参数。
 *
 * rows/columns 来自项目冻结的载体档案；targetPolarity 只作为旧项目兼容偏好，生产
 * 定位器会同时检查暗目标和亮目标，并将实际裁决结果写入 PgGridResult。输出尺寸为空时
 * 按每格约 80 px 自动估算，并限制在端侧可接受范围。
 */
data class PgGridLocatorConfig(
    val rows: Int,
    val columns: Int,
    val targetPolarity: GridTargetPolarity,
    val rectifiedWidth: Int? = null,
    val rectifiedHeight: Int? = null,
    val marginRatio: Double = 0.085,
    val maximumResidualPitchRatio: Double = 0.18,
    val candidateSupportDistancePitchRatio: Double = 0.42,
    val mode: ArrayLocatorMode = ArrayLocatorMode.AUTO
) {
    init {
        require(rows > 0 && columns > 0) { "阵列行列必须大于 0" }
        require(rectifiedWidth == null || rectifiedWidth > 0) { "矫正图宽度必须大于 0" }
        require(rectifiedHeight == null || rectifiedHeight > 0) { "矫正图高度必须大于 0" }
        require(marginRatio in 0.0..<0.5) { "阵列边距比例必须位于 [0, 0.5)" }
        require(maximumResidualPitchRatio in 0.0..1.0) { "最大残差比例必须位于 0 到 1" }
        require(candidateSupportDistancePitchRatio in 0.0..1.0) {
            "候选支撑距离比例必须位于 0 到 1"
        }
    }

    fun resolvedRectifiedWidth(): Int = rectifiedWidth ?: defaultRectifiedDimension(columns)

    fun resolvedRectifiedHeight(): Int = rectifiedHeight ?: defaultRectifiedDimension(rows)

    private fun defaultRectifiedDimension(siteCount: Int): Int {
        return (siteCount * PIXELS_PER_SITE).coerceIn(MIN_RECTIFIED_DIMENSION, MAX_RECTIFIED_DIMENSION)
    }

    private companion object {
        const val PIXELS_PER_SITE: Int = 80
        const val MIN_RECTIFIED_DIMENSION: Int = 720
        const val MAX_RECTIFIED_DIMENSION: Int = 1600
    }
}

/** 微流控规则阵列定位接口；学习型四角/分割兜底将来可实现同一接口做离线 A/B。 */
fun interface PgGridLocator {
    fun locate(bitmap: Bitmap, config: PgGridLocatorConfig): PgGridResult
}

internal class TraditionalGridNotFound(mode: ArrayLocatorMode) :
    IllegalArgumentException("${mode.name} 没有形成四边均有观测的规则阵列")

/**
 * 芯片与圆孔板的对照只运行一次几何链，不做分割或光度，也不尝试别的算法。标准板可尝试
 * 横竖两种尺寸；此处仅给照片顺序，不推断真实 A1。只有预期的无网格才进入下一尺寸。
 */
internal suspend fun PgGridLocator.compareOnImage(
    bitmap: Bitmap,
    rows: Int,
    columns: Int,
    mode: ArrayLocatorMode,
    allowTransposed: Boolean = false
): PgGridResult? = withContext(Dispatchers.Default) {
    require(mode.isComparisonOnly) { "只有对照算法可进入只读网格预览" }
    require(rows >= 2 && columns >= 2) { "对照算法至少需要两行两列" }
    val dimensions = if (allowTransposed && rows != columns) {
        val landscape = maxOf(rows, columns) to minOf(rows, columns)
        if (bitmap.width >= bitmap.height) listOf(landscape.second to landscape.first, landscape)
        else listOf(landscape, landscape.second to landscape.first)
    } else listOf(rows to columns)
    for ((imageRows, imageColumns) in dimensions) {
        try {
            return@withContext locate(bitmap, PgGridLocatorConfig(imageRows, imageColumns, GridTargetPolarity.DARK, mode = mode))
        } catch (_: TraditionalGridNotFound) {
            // 第一种横竖规格没有足够的观测，继续尝试同一算法的另一种照片方向。
        }
    }
    null
}
