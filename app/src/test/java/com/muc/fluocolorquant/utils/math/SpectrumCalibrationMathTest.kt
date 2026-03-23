package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationIssue
import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationQualityLevel
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

    @Test
    fun resolveAutoCalibrationQualityLevel_matchesThresholds() {
        assertEquals(
            SpectrumAutoCalibrationQualityLevel.EXCELLENT,
            SpectrumCalibrationMath.resolveAutoCalibrationQualityLevel(92)
        )
        assertEquals(
            SpectrumAutoCalibrationQualityLevel.USABLE,
            SpectrumCalibrationMath.resolveAutoCalibrationQualityLevel(76)
        )
        assertEquals(
            SpectrumAutoCalibrationQualityLevel.REVIEW,
            SpectrumCalibrationMath.resolveAutoCalibrationQualityLevel(58)
        )
    }

    @Test
    fun collectAutoCalibrationIssues_capturesKeyWarnings() {
        val issues = SpectrumCalibrationMath.collectAutoCalibrationIssues(
            fitRmse = 22.0,
            effectiveCoverage = 0.12,
            usedFallbackAlignment = true,
            hasImageQualityWarning = true
        )

        assertTrue(issues.contains(SpectrumAutoCalibrationIssue.FIT_RMSE_HIGH))
        assertTrue(issues.contains(SpectrumAutoCalibrationIssue.EFFECTIVE_HEIGHT_LOW))
        assertTrue(issues.contains(SpectrumAutoCalibrationIssue.FALLBACK_ALIGNMENT))
        assertTrue(issues.contains(SpectrumAutoCalibrationIssue.IMAGE_QUALITY_WARNING))
    }

    @Test
    fun formatNormalizedCalibrationEquation_keepsNormalizedVariable() {
        val equation = SpectrumCalibrationMath.formatNormalizedCalibrationEquation(
            doubleArrayOf(0.0, -220.0, 640.0)
        )

        assertTrue(equation.contains("λ(t)"))
        assertTrue(equation.contains("640.000"))
    }
    @Test
    fun buildResidualPoints_returnsPerReferenceResiduals() {
        val coefficients = doubleArrayOf(0.0, -220.0, 640.0)
        val residualPoints = SpectrumCalibrationMath.buildResidualPoints(
            points = listOf(
                0.0 to 640.0,
                0.5 to 530.0,
                1.0 to 420.0
            ),
            coefficients = coefficients
        )

        assertEquals(3, residualPoints.size)
        assertEquals(1, residualPoints.first().rank)
        assertEquals(0.0, residualPoints.first().residual, 1e-9)
        assertEquals(0.0, residualPoints[1].residual, 1e-9)
    }
}
