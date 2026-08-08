package com.muc.fluocolorquant.domain.detection.photometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PG-Quant 配置和可靠性语义的纯 JVM 测试。 */
class PgQuantSamplerTest {

    @Test
    fun `低SNR不单独判定图像质量不可靠`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.LOW_SNR),
            snr = 1.2,
            snrMinimum = 3.0
        )

        assertFalse(qc.signalDetectable)
        assertTrue(qc.qualityReliable)
    }

    @Test
    fun `轻度饱和保留结果并作为复核标志`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.SATURATED),
            snr = 25.0,
            snrMinimum = 3.0
        )

        assertTrue(qc.signalDetectable)
        assertTrue(qc.qualityReliable)
    }

    @Test
    fun `严重饱和由采样器显式判定为硬失败`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.SATURATED),
            snr = 25.0,
            snrMinimum = 3.0,
            hardFailure = true
        )

        assertTrue(qc.signalDetectable)
        assertFalse(qc.qualityReliable)
    }

    @Test
    fun `默认ROI和背景环比例与Python参考契约一致`() {
        val config = PgQuantConfig()

        assertEquals(0.18, config.roiRadiusPitchRatio, 1e-12)
        assertEquals(0.30, config.annulusInnerPitchRatio, 1e-12)
        assertEquals(0.44, config.annulusOuterPitchRatio, 1e-12)
        assertEquals(3.0, config.snrMinimum, 1e-12)
        assertEquals(0.35, config.contaminationRatioLimit, 1e-12)
        assertEquals(0.25, config.severeSaturationRatioLimit, 1e-12)
        assertEquals(0.20, config.severeBorderClipRatioLimit, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `背景环内径不得小于信号ROI半径`() {
        PgQuantConfig(
            roiRadiusPitchRatio = 0.35,
            annulusInnerPitchRatio = 0.30
        )
    }
}
