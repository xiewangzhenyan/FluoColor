package com.muc.fluocolorquant.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumCalibrationMathTest {

    @Test
    fun normalizeAbsoluteY_mapsBottomBoundaryToOne() {
        val normalized = SpectrumCalibrationMath.normalizeAbsoluteY(
            y = 109.0,
            top = 10,
            bottomExclusive = 110
        )

        assertEquals(1.0, normalized, 1e-9)
    }

    @Test
    fun normalizedPolynomial_keepsSameWavelengthAcrossDifferentHeights() {
        val coefficients = doubleArrayOf(0.0, -220.0, 640.0)
        val smallTrackRows = 101
        val largeTrackRows = 1001

        val smallCenter = SpectrumCalibrationMath.normalizeRelativeRow(50, smallTrackRows)
        val largeCenter = SpectrumCalibrationMath.normalizeRelativeRow(500, largeTrackRows)

        val smallWavelength = SpectrumCalibrationMath.evaluatePolynomial(coefficients, smallCenter)
        val largeWavelength = SpectrumCalibrationMath.evaluatePolynomial(coefficients, largeCenter)

        assertEquals(smallWavelength, largeWavelength, 1e-9)
    }

    @Test
    fun qualityScore_penalizesFallbackAndHighRmse() {
        val highQuality = SpectrumCalibrationMath.calculateAutoCalibrationQualityScore(
            detectedPeakCount = 3,
            referencePeakCount = 3,
            fitRmse = 0.01,
            effectiveCoverage = 0.42,
            usedFallbackAlignment = false
        )
        val lowQuality = SpectrumCalibrationMath.calculateAutoCalibrationQualityScore(
            detectedPeakCount = 2,
            referencePeakCount = 3,
            fitRmse = 0.18,
            effectiveCoverage = 0.95,
            usedFallbackAlignment = true
        )

        assertTrue(highQuality > lowQuality)
        assertTrue(highQuality in 0..100)
        assertTrue(lowQuality in 0..100)
    }

    @Test
    fun qualityScore_currentAutoCalibrationCase_staysAboveEighty() {
        val score = SpectrumCalibrationMath.calculateAutoCalibrationQualityScore(
            detectedPeakCount = 3,
            referencePeakCount = 3,
            fitRmse = 18.7033,
            effectiveCoverage = 0.87,
            usedFallbackAlignment = false
        )

        assertTrue(score >= 80)
    }
}
