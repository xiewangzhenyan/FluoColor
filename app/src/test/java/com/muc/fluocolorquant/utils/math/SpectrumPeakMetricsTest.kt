package com.muc.fluocolorquant.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumPeakMetricsTest {

    @Test
    fun `sortAndMergeSpectrumSamples 会按波长排序并合并重复点`() {
        val samples = listOf(
            430.0 to 0.62,
            400.02 to 0.42,
            400.00 to 0.38,
            420.0 to 0.55
        )

        val merged = sortAndMergeSpectrumSamples(samples, mergeToleranceNm = 0.05)

        assertEquals(3, merged.size)
        assertTrue(merged.zipWithNext().all { (left, right) -> left.first < right.first })
        assertEquals(0.40, merged.first().second, 1e-6)
    }

    @Test
    fun `interpolateSpectrumHalfMaxCrossing 能计算左右半高交点`() {
        val wavelengths = listOf(400.0, 410.0, 420.0, 430.0, 440.0)
        val intensities = listOf(0.0, 1.0, 2.0, 1.0, 0.0)

        val left = interpolateSpectrumHalfMaxCrossing(
            wavelengths = wavelengths,
            intensities = intensities,
            startIndex = 2,
            boundaryIndex = 0,
            target = 1.0,
            direction = -1
        )
        val right = interpolateSpectrumHalfMaxCrossing(
            wavelengths = wavelengths,
            intensities = intensities,
            startIndex = 2,
            boundaryIndex = 4,
            target = 1.0,
            direction = 1
        )

        assertEquals(410.0, left, 1e-6)
        assertEquals(430.0, right, 1e-6)
        assertEquals(20.0, right - left, 1e-6)
    }

    @Test
    fun `integrateSpectrumPeakArea 会返回非负峰面积`() {
        val wavelengths = listOf(400.0, 410.0, 420.0, 430.0, 440.0)
        val intensities = listOf(0.0, 1.0, 2.0, 1.0, 0.0)

        val area = integrateSpectrumPeakArea(
            wavelengths = wavelengths,
            intensities = intensities,
            leftIndex = 0,
            rightIndex = 4,
            baseLevel = 0.0
        )

        assertEquals(40.0, area, 1e-6)
        assertTrue(area > 0.0)
    }
}
