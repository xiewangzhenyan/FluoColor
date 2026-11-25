package com.muc.fluocolorquant.domain.spectrum

import com.muc.fluocolorquant.utils.math.integrateSpectrumPeakArea
import com.muc.fluocolorquant.utils.math.interpolateSpectrumHalfMaxCrossing
import com.muc.fluocolorquant.utils.math.sortAndMergeSpectrumSamples
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/** 与 UI 无关的光谱峰值结果，单位分别为 nm、归一化强度、nm·强度和 nm。 */
data class SpectrumSignalPeak(
    val rank: Int,
    val wavelength: Double,
    val intensity: Double,
    val prominence: Double = 0.0,
    val area: Double = 0.0,
    val fullWidthHalfMax: Double = 0.0,
    val signalToNoise: Double = 0.0
)

/**
 * 单通道一次处理产生的全部确定性序列。
 *
 * 原始、经典平滑、基线及增强序列一起返回，避免 UI 为切换曲线模式重复执行算法。调用方
 * 应把本次 [SpectrumProcessingConfig] 与结果一同冻结，历史页不得换用当前全局设置重算。
 */
data class SpectrumProcessedSignal(
    val wavelengths: List<Double>,
    val rawIntensities: List<Double>,
    val classicIntensities: List<Double>,
    val rawNormalized: List<Double>,
    val baselineNormalized: List<Double>,
    val correctedNormalized: List<Double>,
    val enhancedIntensities: List<Double>,
    val rawPeaks: List<SpectrumSignalPeak>,
    val classicPeaks: List<SpectrumSignalPeak>,
    val enhancedPeaks: List<SpectrumSignalPeak>
)

/**
 * 纯领域光谱处理器。
 *
 * 本类不读取 Context、DataStore 或 Room，也不生成文案和颜色；同一输入与参数始终得到
 * 同一输出，因而可以单独回归算法并安全用于历史快照重建。
 */
@Singleton
class SpectrumSignalProcessor @Inject constructor() {

    fun process(
        samples: List<Pair<Double, Double>>,
        smoothingLevel: Int,
        sensitivity: String
    ): SpectrumProcessedSignal {
        val ordered = sortAndMergeSpectrumSamples(samples)
        val wavelengths = ordered.map(Pair<Double, Double>::first)
        val rawIntensities = ordered.map(Pair<Double, Double>::second)
        val rawPeaks = findClassicPeaks(wavelengths, rawIntensities, sensitivity)
        val classicIntensities = if (smoothingLevel > 0) {
            movingAverage(rawIntensities, smoothingLevel)
        } else {
            rawIntensities
        }
        val classicPeaks = findClassicPeaks(wavelengths, classicIntensities, sensitivity)
        val baselineCorrection = baselineCorrection(rawIntensities, smoothingLevel)
        val enhancedSource = if (smoothingLevel > 0) {
            movingAverage(baselineCorrection.corrected, smoothingLevel)
        } else {
            baselineCorrection.corrected
        }
        val enhancedIntensities = normalize(enhancedSource)
        return SpectrumProcessedSignal(
            wavelengths = wavelengths,
            rawIntensities = rawIntensities,
            classicIntensities = classicIntensities,
            rawNormalized = normalize(rawIntensities),
            baselineNormalized = normalize(baselineCorrection.baseline),
            correctedNormalized = normalize(baselineCorrection.corrected),
            enhancedIntensities = enhancedIntensities,
            rawPeaks = rawPeaks,
            classicPeaks = classicPeaks,
            enhancedPeaks = findEnhancedPeaks(wavelengths, enhancedIntensities, sensitivity)
        )
    }

    /** 对每个采样点使用对称窗口；边界处缩短窗口，保证输出长度与坐标严格一致。 */
    private fun movingAverage(data: List<Double>, level: Int): List<Double> {
        if (level <= 0 || data.size < 3) return data
        return data.indices.map { index ->
            val start = maxOf(0, index - level)
            val end = minOf(data.lastIndex, index + level)
            data.subList(start, end + 1).average()
        }
    }

    /** 滚动最小值近似背景基线，再轻度平滑，避免背景抬升被误认为分析峰。 */
    private fun baselineCorrection(
        data: List<Double>,
        smoothingLevel: Int
    ): BaselineCorrection {
        if (data.isEmpty()) return BaselineCorrection(emptyList(), emptyList())
        val radius = maxOf(6, smoothingLevel * 3, data.size / 28)
        val roughBaseline = data.indices.map { index ->
            val start = maxOf(0, index - radius)
            val end = minOf(data.lastIndex, index + radius)
            data.subList(start, end + 1).minOrNull() ?: data[index]
        }
        val baseline = movingAverage(roughBaseline, maxOf(2, radius / 4))
        val corrected = data.indices.map { index ->
            (data[index] - baseline[index]).coerceAtLeast(0.0)
        }
        return BaselineCorrection(baseline, corrected)
    }

    private fun normalize(data: List<Double>): List<Double> {
        if (data.isEmpty()) return emptyList()
        val minimum = data.minOrNull() ?: 0.0
        val maximum = data.maxOrNull() ?: minimum
        val range = (maximum - minimum).coerceAtLeast(MINIMUM_NUMERIC_RANGE)
        return data.map { value -> ((value - minimum) / range).coerceIn(0.0, 1.0) }
    }

    /** 经典寻峰沿用旧版本阈值语义，确保切换到冻结配置后历史结果可兼容解释。 */
    private fun findClassicPeaks(
        wavelengths: List<Double>,
        intensities: List<Double>,
        sensitivity: String
    ): List<SpectrumSignalPeak> {
        if (wavelengths.size < 3 || intensities.size < 3) return emptyList()
        val threshold = when {
            sensitivity.equals("High", ignoreCase = true) -> 0.20
            sensitivity.equals("Low", ignoreCase = true) -> 0.55
            else -> 0.35
        }
        val maximumPeakCount = when {
            sensitivity.equals("High", ignoreCase = true) -> 4
            sensitivity.equals("Low", ignoreCase = true) -> 2
            else -> 3
        }
        val minimumPeakDistance = maxOf(4, intensities.size / 18)
        val candidates = buildList {
            for (index in 1 until intensities.lastIndex) {
                val current = intensities[index]
                if (current >= threshold &&
                    current >= intensities[index - 1] &&
                    current >= intensities[index + 1]
                ) {
                    add(index to current)
                }
            }
        }
        if (candidates.isEmpty()) {
            val peakIndex = intensities.indices.maxByOrNull(intensities::get) ?: return emptyList()
            val intensity = intensities[peakIndex]
            return if (intensity >= threshold) {
                listOf(SpectrumSignalPeak(1, wavelengths[peakIndex], intensity))
            } else {
                emptyList()
            }
        }
        val separated = mutableListOf<Pair<Int, Double>>()
        candidates.sortedByDescending(Pair<Int, Double>::second).forEach { candidate ->
            if (separated.none { abs(it.first - candidate.first) < minimumPeakDistance }) {
                separated += candidate
            }
        }
        return separated.sortedByDescending(Pair<Int, Double>::second)
            .take(maximumPeakCount)
            .mapIndexed { rank, (peakIndex, intensity) ->
                SpectrumSignalPeak(rank + 1, wavelengths[peakIndex], intensity)
            }
    }

    /** 增强寻峰同时门控显著度和信噪比，避免把归一化后的细小噪声列为主峰。 */
    private fun findEnhancedPeaks(
        wavelengths: List<Double>,
        intensities: List<Double>,
        sensitivity: String
    ): List<SpectrumSignalPeak> {
        if (wavelengths.size < 3 || intensities.size < 3) return emptyList()
        val noiseStd = estimateNoiseStdDev(intensities)
        val prominenceThreshold = when {
            sensitivity.equals("High", ignoreCase = true) -> maxOf(0.045, noiseStd * 1.6)
            sensitivity.equals("Low", ignoreCase = true) -> maxOf(0.085, noiseStd * 2.7)
            else -> maxOf(0.06, noiseStd * 2.1)
        }
        val minimumSignalToNoise = when {
            sensitivity.equals("High", ignoreCase = true) -> 1.6
            sensitivity.equals("Low", ignoreCase = true) -> 2.6
            else -> 2.0
        }
        val maximumPeakCount = when {
            sensitivity.equals("High", ignoreCase = true) -> 4
            sensitivity.equals("Low", ignoreCase = true) -> 2
            else -> 3
        }
        val minimumPeakDistance = maxOf(4, intensities.size / 18)
        val candidates = mutableListOf<PeakCandidate>()
        for (index in 1 until intensities.lastIndex) {
            val current = intensities[index]
            if (current >= intensities[index - 1] && current >= intensities[index + 1] && current > 0.0) {
                val candidate = buildPeakCandidate(wavelengths, intensities, index, noiseStd)
                if (candidate.prominence >= prominenceThreshold &&
                    candidate.signalToNoise >= minimumSignalToNoise
                ) {
                    candidates += candidate
                }
            }
        }
        if (candidates.isEmpty()) {
            val peakIndex = intensities.indices.maxByOrNull(intensities::get) ?: return emptyList()
            val fallback = buildPeakCandidate(wavelengths, intensities, peakIndex, noiseStd)
            return if (fallback.prominence >= prominenceThreshold) {
                listOf(fallback.toSignalPeak(rank = 1))
            } else {
                emptyList()
            }
        }
        val separated = mutableListOf<PeakCandidate>()
        candidates.sortedByDescending(PeakCandidate::score).forEach { candidate ->
            if (separated.none { abs(it.index - candidate.index) < minimumPeakDistance }) {
                separated += candidate
            }
        }
        return separated.sortedByDescending(PeakCandidate::score)
            .take(maximumPeakCount)
            .mapIndexed { index, candidate -> candidate.toSignalPeak(index + 1) }
    }

    private fun estimateNoiseStdDev(intensities: List<Double>): Double {
        if (intensities.size < 3) return MINIMUM_NOISE_STD_DEV
        val deltas = intensities.zipWithNext { left, right -> right - left }
        if (deltas.isEmpty()) return MINIMUM_NOISE_STD_DEV
        val mean = deltas.average()
        val variance = deltas.sumOf { delta ->
            val difference = delta - mean
            difference * difference
        } / deltas.size.toDouble()
        return sqrt(variance).coerceAtLeast(MINIMUM_NOISE_STD_DEV)
    }

    private fun buildPeakCandidate(
        wavelengths: List<Double>,
        intensities: List<Double>,
        peakIndex: Int,
        noiseStd: Double
    ): PeakCandidate {
        val leftBaseIndex = findBaseIndex(intensities, peakIndex, direction = -1)
        val rightBaseIndex = findBaseIndex(intensities, peakIndex, direction = 1)
        val baseLevel = maxOf(intensities[leftBaseIndex], intensities[rightBaseIndex])
        val peakIntensity = intensities[peakIndex]
        val prominence = (peakIntensity - baseLevel).coerceAtLeast(0.0)
        val halfMaximum = baseLevel + prominence / 2.0
        val leftHalf = interpolateSpectrumHalfMaxCrossing(
            wavelengths, intensities, peakIndex, leftBaseIndex, halfMaximum, direction = -1
        )
        val rightHalf = interpolateSpectrumHalfMaxCrossing(
            wavelengths, intensities, peakIndex, rightBaseIndex, halfMaximum, direction = 1
        )
        val area = integrateSpectrumPeakArea(
            wavelengths, intensities, leftBaseIndex, rightBaseIndex, baseLevel
        )
        return PeakCandidate(
            index = peakIndex,
            wavelength = wavelengths[peakIndex],
            intensity = peakIntensity,
            prominence = prominence,
            area = area,
            fullWidthHalfMax = (rightHalf - leftHalf).coerceAtLeast(0.0),
            signalToNoise = (prominence / noiseStd).coerceAtLeast(0.0)
        )
    }

    private fun findBaseIndex(
        intensities: List<Double>,
        startIndex: Int,
        direction: Int
    ): Int {
        var index = startIndex
        var candidateIndex = startIndex
        var candidateValue = intensities[startIndex]
        while (true) {
            val nextIndex = index + direction
            if (nextIndex !in intensities.indices) break
            val nextValue = intensities[nextIndex]
            if (nextValue <= candidateValue) {
                candidateValue = nextValue
                candidateIndex = nextIndex
            }
            if (nextValue > intensities[index] && candidateIndex != startIndex) break
            index = nextIndex
        }
        return candidateIndex
    }

    private data class BaselineCorrection(
        val baseline: List<Double>,
        val corrected: List<Double>
    )

    private data class PeakCandidate(
        val index: Int,
        val wavelength: Double,
        val intensity: Double,
        val prominence: Double,
        val area: Double,
        val fullWidthHalfMax: Double,
        val signalToNoise: Double
    ) {
        val score: Double get() = prominence + intensity * 0.35

        fun toSignalPeak(rank: Int): SpectrumSignalPeak = SpectrumSignalPeak(
            rank = rank,
            wavelength = wavelength,
            intensity = intensity,
            prominence = prominence,
            area = area,
            fullWidthHalfMax = fullWidthHalfMax,
            signalToNoise = signalToNoise
        )
    }

    private companion object {
        const val MINIMUM_NUMERIC_RANGE = 1e-9
        const val MINIMUM_NOISE_STD_DEV = 0.01
    }
}
