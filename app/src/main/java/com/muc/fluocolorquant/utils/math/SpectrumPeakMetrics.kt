package com.muc.fluocolorquant.utils.math

import kotlin.math.abs

/**
 * 将光谱序列按波长升序整理，并合并过于接近的重复波长点。
 * 这样可以避免自动标定后局部波长重复导致曲线在同一 x 位置垂直跳变。
 */
fun sortAndMergeSpectrumSamples(
    samples: List<Pair<Double, Double>>,
    mergeToleranceNm: Double = 0.15
): List<Pair<Double, Double>> {
    if (samples.isEmpty()) return emptyList()

    val sorted = samples.sortedBy { it.first }
    val merged = mutableListOf<Pair<Double, Double>>()
    val group = mutableListOf<Pair<Double, Double>>()

    fun flushGroup() {
        if (group.isEmpty()) return
        merged += group.map { it.first }.average() to group.map { it.second }.average()
        group.clear()
    }

    sorted.forEach { sample ->
        val previous = group.lastOrNull()
        if (previous == null || abs(sample.first - previous.first) <= mergeToleranceNm) {
            group += sample
        } else {
            flushGroup()
            group += sample
        }
    }
    flushGroup()

    return merged
}

/**
 * 计算峰值在半高位置与左右侧曲线的交点波长。
 */
fun interpolateSpectrumHalfMaxCrossing(
    wavelengths: List<Double>,
    intensities: List<Double>,
    startIndex: Int,
    boundaryIndex: Int,
    target: Double,
    direction: Int
): Double {
    if (startIndex !in wavelengths.indices || boundaryIndex !in wavelengths.indices) {
        return 0.0
    }

    var index = startIndex
    while (index != boundaryIndex) {
        val nextIndex = index + direction
        if (nextIndex !in wavelengths.indices) break

        val current = intensities[index]
        val next = intensities[nextIndex]
        val currentDelta = current - target
        val nextDelta = next - target

        if (abs(currentDelta) <= 1e-9) {
            return wavelengths[index]
        }

        if (currentDelta * nextDelta <= 0.0) {
            val denominator = current - next
            if (abs(denominator) <= 1e-9) {
                return wavelengths[nextIndex]
            }
            val ratio = ((current - target) / denominator).coerceIn(0.0, 1.0)
            return wavelengths[index] + (wavelengths[nextIndex] - wavelengths[index]) * ratio
        }

        index = nextIndex
    }

    return wavelengths[boundaryIndex]
}

/**
 * 使用梯形积分计算峰面积，并保证面积为非负值。
 */
fun integrateSpectrumPeakArea(
    wavelengths: List<Double>,
    intensities: List<Double>,
    leftIndex: Int,
    rightIndex: Int,
    baseLevel: Double
): Double {
    if (wavelengths.size < 2 || intensities.size < 2) return 0.0

    val startIndex = minOf(leftIndex, rightIndex).coerceAtLeast(0)
    val endIndex = maxOf(leftIndex, rightIndex).coerceAtMost(wavelengths.lastIndex)
    if (endIndex <= startIndex) return 0.0

    var area = 0.0
    for (index in startIndex until endIndex) {
        val leftValue = (intensities[index] - baseLevel).coerceAtLeast(0.0)
        val rightValue = (intensities[index + 1] - baseLevel).coerceAtLeast(0.0)
        val deltaX = abs(wavelengths[index + 1] - wavelengths[index])
        area += (leftValue + rightValue) * deltaX / 2.0
    }

    return area.coerceAtLeast(0.0)
}
