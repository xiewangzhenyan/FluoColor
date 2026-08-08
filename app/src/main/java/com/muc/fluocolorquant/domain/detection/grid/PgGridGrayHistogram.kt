package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.abs
import kotlin.math.floor
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * 8 位灰度直方图，用于在不排序百万像素的前提下取分位数、中位数和 MAD。
 *
 * 定位链路要在多个阈值档、多个区域假设上反复统计同一批灰度图，逐次排序会成为端侧
 * 单图耗时的大头。分位数按 `numpy.percentile` 的线性插值约定实现，保证与 Python
 * 参考实现在同一张图上取到一致的阈值；MAD 在“二倍整数距离”上统计，因此中位数落在
 * 半整数时依然精确，不引入取整偏差。
 */
internal class PgGridGrayHistogram private constructor(
    private val bins: LongArray,
    val total: Long
) {

    /** 第 [ratio] 分位（0~1），与 `np.percentile(values, ratio*100)` 同约定。 */
    fun quantile(ratio: Double): Double = orderStatistic(bins, total, (total - 1) * ratio)

    fun median(): Double = quantile(0.5)

    /** 中位绝对偏差；乘以 1.4826 即为对正态分布的稳健标准差估计。 */
    fun medianAbsoluteDeviation(): Double {
        if (total == 0L) return 0.0
        val doubledMedian = Math.round(median() * 2.0)
        val doubledBins = LongArray(MAX_DOUBLED_DISTANCE + 1)
        for (value in 0 until BIN_COUNT) {
            val count = bins[value]
            if (count == 0L) continue
            doubledBins[abs(value * 2L - doubledMedian).toInt()] += count
        }
        return orderStatistic(doubledBins, total, (total - 1) * 0.5) / 2.0
    }

    companion object {
        private const val BIN_COUNT: Int = 256
        private const val MAX_DOUBLED_DISTANCE: Int = 512

        fun of(gray: Mat): PgGridGrayHistogram {
            require(gray.type() == CvType.CV_8UC1) { "灰度直方图要求 8 位单通道输入" }
            val bins = LongArray(BIN_COUNT)
            val rowCount = gray.rows()
            val columnCount = gray.cols()

            // 连续内存时一次性整块读取：Mat.get 是 JNI 调用，逐行读会在原图尺度上产生
            // 数千次跨语言边界往返（4000×3000 即 3000 次），而结果与逐行读完全相同。
            // submat 在内存中不连续，那时才必须逐行读，否则会跨行读到无关像素。
            if (gray.isContinuous) {
                val buffer = ByteArray(rowCount * columnCount)
                gray.get(0, 0, buffer)
                for (value in buffer) {
                    bins[value.toInt() and 0xFF]++
                }
                return PgGridGrayHistogram(bins, buffer.size.toLong())
            }

            val buffer = ByteArray(columnCount)
            var total = 0L
            for (row in 0 until rowCount) {
                gray.get(row, 0, buffer)
                for (index in 0 until columnCount) {
                    bins[buffer[index].toInt() and 0xFF]++
                }
                total += columnCount
            }
            return PgGridGrayHistogram(bins, total)
        }

        /** 按 numpy 线性插值约定取第 [rank] 个（0 基，允许小数）顺序统计量。 */
        private fun orderStatistic(bins: LongArray, total: Long, rank: Double): Double {
            if (total == 0L) return 0.0
            val clamped = rank.coerceIn(0.0, (total - 1).toDouble())
            val lowerRank = floor(clamped).toLong()
            val fraction = clamped - lowerRank
            val lowerValue = valueAtRank(bins, lowerRank)
            if (fraction <= 0.0) return lowerValue.toDouble()
            val upperValue = valueAtRank(bins, lowerRank + 1)
            return lowerValue + (upperValue - lowerValue) * fraction
        }

        private fun valueAtRank(bins: LongArray, rank: Long): Int {
            var remaining = rank
            for (value in bins.indices) {
                val count = bins[value]
                if (remaining < count) return value
                remaining -= count
            }
            return bins.lastIndex
        }
    }
}
