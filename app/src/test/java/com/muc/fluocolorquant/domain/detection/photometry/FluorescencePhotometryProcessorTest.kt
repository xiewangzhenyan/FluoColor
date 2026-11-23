package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 荧光处理器测试：净强度、积分强度、SNR 与热点质量控制必须独立于比色。 */
class FluorescencePhotometryProcessorTest {

    @Test
    fun `绿色荧光强位点输出更高净强度积分强度和SNR`() {
        val weak = baseSite(index = 0, green = 62.0, integratedGreen = 600.0, hotPixelRatio = 0.0)
        val strong = baseSite(index = 1, green = 150.0, integratedGreen = 4800.0, hotPixelRatio = 0.0)

        val result = FluorescencePhotometryProcessor.process(
            quant = quantResult(listOf(weak, strong)),
            config = FluorescenceProcessorConfig(
                channel = FluorescenceChannel.GREEN,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            )
        )

        assertTrue(result.sites[1].netIntensity > result.sites[0].netIntensity)
        assertTrue(result.sites[1].integratedIntensity > result.sites[0].integratedIntensity)
        assertTrue(result.sites[1].signalToNoiseRatio > result.sites[0].signalToNoiseRatio)
    }

    @Test
    fun `热点位点保留结果并标记为建议复核`() {
        val hot = baseSite(index = 0, green = 130.0, integratedGreen = 3500.0, hotPixelRatio = 0.012)

        val result = FluorescencePhotometryProcessor.process(
            quant = quantResult(listOf(hot)),
            config = FluorescenceProcessorConfig(
                channel = FluorescenceChannel.GREEN,
                hotPixelRatioLimit = 0.005,
                primaryFeature = AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY
            )
        )

        assertTrue(PhotometryFlag.HOT_PIXEL in result.sites.single().qc.flags)
        assertTrue(result.sites.single().qc.qualityReliable)
    }

    @Test
    fun `荧光RGB和Lab及通道比率由生产处理器真实计算`() {
        val site = baseSite(index = 0, green = 60.0, integratedGreen = 600.0, hotPixelRatio = 0.0)
        val quant = quantResult(listOf(site))
        val features = listOf(
            AnalysisPrimaryFeature.RED_INTENSITY,
            AnalysisPrimaryFeature.GREEN_INTENSITY,
            AnalysisPrimaryFeature.BLUE_INTENSITY,
            AnalysisPrimaryFeature.CIE_L_STAR,
            AnalysisPrimaryFeature.CIE_A_STAR,
            AnalysisPrimaryFeature.CIE_B_STAR,
            AnalysisPrimaryFeature.RED_BLUE_RATIO
        )

        val values = features.associateWith { feature ->
            FluorescencePhotometryProcessor.process(
                quant = quant,
                config = FluorescenceProcessorConfig(
                    channel = FluorescenceChannel.GREEN,
                    primaryFeature = feature
                )
            ).sites.single().primaryFeatureValue
        }

        // 这些值不仅要出现在选择器中，还必须能由正式运行使用的同一个处理器生成。
        assertTrue(values.values.all { value -> value?.isFinite() == true })
        assertEquals(18.0 / 15.0, requireNotNull(values[AnalysisPrimaryFeature.RED_BLUE_RATIO]), 1e-9)
    }

    @Test
    fun `前景低于局部背景时有向SNR不触发假检出并标记负净信号`() {
        val darkerThanBg = baseSite(index = 0, green = 6.0, integratedGreen = -280.0, hotPixelRatio = 0.0)

        val result = FluorescencePhotometryProcessor.process(
            quant = quantResult(listOf(darkerThanBg)),
            config = FluorescenceProcessorConfig(
                channel = FluorescenceChannel.GREEN,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            )
        ).sites.single()

        assertEquals(-14.0, result.netIntensity, 1e-9)
        assertEquals(0.0, result.signalToNoiseRatio, 1e-9)
        assertTrue(result.directedSnr < -3.0)
        assertFalse(result.qc.signalDetectable)
        assertTrue(PhotometryFlag.NEGATIVE_NET_SIGNAL in result.qc.flags)
    }

    @Test
    fun `暗场背景MAD为零时量化底噪防止一灰阶差值爆出9999信噪比`() {
        val zeroSigmaSite = baseSite(index = 0, green = 21.0, integratedGreen = 20.0, hotPixelRatio = 0.0)
            .copy(
                backgroundSigmaRgb = RgbPhotometry(0.0, 0.0, 0.0),
                backgroundSigmaGray = 0.0,
                // 即使基础层有非 1 的乘性校正值，荧光净强度也不得被暗背景平场放大
                correctedMedianRgb = RgbPhotometry(36.0, 63.0, 45.0)
            )

        val result = FluorescencePhotometryProcessor.process(
            quant = quantResult(listOf(zeroSigmaSite)),
            config = FluorescenceProcessorConfig(
                channel = FluorescenceChannel.GREEN,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            )
        ).sites.single()

        assertEquals(1.0, result.netIntensity, 1e-9)
        assertEquals(2.0, result.signalToNoiseRatio, 1e-4)
        assertFalse(result.qc.signalDetectable)
    }

    private fun quantResult(sites: List<BaseSitePhotometry>): PgQuantResult {
        return PgQuantResult(
            rows = 1,
            columns = sites.size,
            pitchPx = 70.0,
            roiRadiusPx = 12.6,
            annulusInnerPx = 21.0,
            annulusOuterPx = 30.8,
            illuminationModel = "constant_fallback",
            illuminationUniformity = 1.0,
            config = PgQuantConfig(),
            sites = sites
        ).requireValid()
    }

    private fun baseSite(
        index: Int,
        green: Double,
        integratedGreen: Double,
        hotPixelRatio: Double
    ): BaseSitePhotometry {
        val background = RgbPhotometry(12.0, 20.0, 10.0)
        val roi = RgbPhotometry(18.0, green, 15.0)
        val gray = 0.299 * roi.red + 0.587 * roi.green + 0.114 * roi.blue
        return BaseSitePhotometry(
            siteIndex = index,
            rowIndex = 0,
            columnIndex = index,
            rectifiedCenter = GridPoint(40.0 + index * 70.0, 40.0),
            originalCenter = GridPoint(40.0 + index * 70.0, 40.0),
            roiMedianRgb = roi,
            roiMedianGray = gray,
            backgroundMedianRgb = background,
            backgroundMedianGray = 18.0,
            backgroundSigmaRgb = RgbPhotometry(2.0, 3.0, 2.0),
            backgroundSigmaGray = 2.5,
            correctedMedianRgb = roi,
            correctedMedianGray = gray,
            signalGray = gray - 18.0,
            signalRatio = (gray - 18.0) / 18.0,
            correctedSignalGray = gray - 18.0,
            integratedSignalRgb = RgbPhotometry(500.0, integratedGreen, 450.0),
            integratedSignalGray = integratedGreen * 0.587,
            signalToNoiseRatio = 10.0,
            saturationRatio = 0.0,
            roiContaminationRatio = 0.0,
            hotPixelRatio = hotPixelRatio,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0,
            qc = SitePhotometryQc.from(emptySet(), 10.0, 3.0)
        )
    }
}
