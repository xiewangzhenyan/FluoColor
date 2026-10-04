package com.muc.fluocolorquant.domain.spectrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 光谱领域处理器回归测试，防止 ViewModel 拆分后改变峰值和序列语义。 */
class SpectrumSignalProcessorTest {
    private val processor = SpectrumSignalProcessor()

    @Test
    fun `乱序和重复波长会确定性合并且全部序列保持等长`() {
        val samples = listOf(
            502.0 to 0.3,
            500.0 to 0.0,
            501.0 to 0.8,
            501.0 to 1.0,
            503.0 to 0.0
        )

        val first = processor.process(samples, smoothingLevel = 1, sensitivity = "High")
        val second = processor.process(samples, smoothingLevel = 1, sensitivity = "High")

        assertEquals(listOf(500.0, 501.0, 502.0, 503.0), first.wavelengths)
        assertEquals(first, second)
        assertEquals(first.wavelengths.size, first.rawIntensities.size)
        assertEquals(first.wavelengths.size, first.classicIntensities.size)
        assertEquals(first.wavelengths.size, first.enhancedIntensities.size)
        assertTrue(first.enhancedIntensities.all { it in 0.0..1.0 })
    }

    @Test
    fun `明显单峰会输出有限的增强峰诊断`() {
        val samples = (0..40).map { index ->
            val distance = (index - 20).toDouble()
            // 使用窄高斯峰，确保滚动基线代表背景而不是跟随一个覆盖全谱的宽斜坡。
            (480.0 + index) to kotlin.math.exp(-(distance * distance) / 8.0)
        }

        val result = processor.process(samples, smoothingLevel = 1, sensitivity = "High")
        val peak = result.enhancedPeaks.first()

        assertEquals(500.0, peak.wavelength, 1.0)
        assertTrue(peak.prominence > 0.0)
        assertTrue(peak.area.isFinite())
        assertTrue(peak.fullWidthHalfMax >= 0.0)
        assertTrue(peak.signalToNoise.isFinite())
    }
}
