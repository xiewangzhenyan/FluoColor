package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
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
