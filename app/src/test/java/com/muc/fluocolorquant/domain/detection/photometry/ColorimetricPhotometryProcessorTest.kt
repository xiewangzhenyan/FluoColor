package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 比色处理器测试：全局参考白、Lab、ΔE2000 和光密度必须保持真实显色差异。 */
class ColorimetricPhotometryProcessorTest {

    @Test
    fun `深色显色位点的色差与光密度均高于浅色位点`() {
        val quant = quantResult(
            listOf(
                baseSite(0, RgbPhotometry(220.0, 220.0, 220.0)),
                baseSite(1, RgbPhotometry(170.0, 190.0, 220.0)),
                baseSite(2, RgbPhotometry(75.0, 105.0, 175.0))
            )
        )

        val result = ColorimetricPhotometryProcessor.process(
            quant = quant,
            config = ColorimetricProcessorConfig(
                referenceSiteIndices = setOf(0),
                primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000
            )
        )

        val reference = result.sites[0]
        val shallow = result.sites[1]
        val deep = result.sites[2]
        assertEquals(0.0, reference.deltaE2000, 1e-8)
        assertTrue(deep.deltaE2000 > shallow.deltaE2000)
        assertTrue(deep.opticalDensity > shallow.opticalDensity)
        assertEquals(deep.deltaE2000, deep.primaryFeatureValue, 1e-9)
    }

    @Test
    fun `所有位点共享同一组参考白增益而不是逐ROI灰世界`() {
        val quant = quantResult(
            listOf(
                baseSite(0, RgbPhotometry(180.0, 210.0, 240.0)),
                baseSite(1, RgbPhotometry(100.0, 145.0, 210.0))
            )
        )

        val result = ColorimetricPhotometryProcessor.process(
            quant,
            ColorimetricProcessorConfig(
                referenceSiteIndices = setOf(0),
                primaryFeature = AnalysisPrimaryFeature.OPTICAL_DENSITY
            )
        )

        assertTrue(result.whiteBalanceGains.red != result.whiteBalanceGains.blue)
        // 样本经同一全局增益后仍保留明显蓝色分量，证明没有逐位点单独中和颜色。
        assertTrue(result.sites[1].whiteBalancedRgb.blue > result.sites[1].whiteBalancedRgb.red)
        assertEquals(result.sites[1].opticalDensity, result.sites[1].primaryFeatureValue, 1e-9)
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

    private fun baseSite(index: Int, rgb: RgbPhotometry): BaseSitePhotometry {
        val background = RgbPhotometry(35.0, 35.0, 35.0)
        val gray = 0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue
        return BaseSitePhotometry(
            siteIndex = index,
            rowIndex = 0,
            columnIndex = index,
            rectifiedCenter = GridPoint(40.0 + index * 70.0, 40.0),
            originalCenter = GridPoint(40.0 + index * 70.0, 40.0),
            roiMedianRgb = rgb,
            roiMedianGray = gray,
            backgroundMedianRgb = background,
            backgroundMedianGray = 35.0,
            backgroundSigmaRgb = RgbPhotometry(1.0, 1.0, 1.0),
            backgroundSigmaGray = 1.0,
            correctedMedianRgb = rgb,
            correctedMedianGray = gray,
            signalGray = gray - 35.0,
            signalRatio = (gray - 35.0) / 35.0,
            correctedSignalGray = gray - 35.0,
            integratedSignalRgb = RgbPhotometry(1000.0, 1000.0, 1000.0),
            integratedSignalGray = 1000.0,
            signalToNoiseRatio = 20.0,
            saturationRatio = 0.0,
            roiContaminationRatio = 0.0,
            hotPixelRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0,
            qc = SitePhotometryQc.from(emptySet(), 20.0, 3.0)
        )
    }
}
