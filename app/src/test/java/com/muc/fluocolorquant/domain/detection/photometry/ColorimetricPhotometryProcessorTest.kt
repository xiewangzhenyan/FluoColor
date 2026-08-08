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
        assertEquals(0.0, requireNotNull(reference.deltaE2000), 1e-8)
        assertTrue(requireNotNull(deep.deltaE2000) > requireNotNull(shallow.deltaE2000))
        assertTrue(requireNotNull(deep.opticalDensity) > requireNotNull(shallow.opticalDensity))
        assertEquals(
            requireNotNull(deep.deltaE2000),
            requireNotNull(deep.primaryFeatureValue),
            1e-9
        )
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
        assertEquals(
            requireNotNull(result.sites[1].opticalDensity),
            requireNotNull(result.sites[1].primaryFeatureValue),
            1e-9
        )
    }

    @Test
    fun `没有参考位时经典灰度和绿色通道仍能直接计算`() {
        val rgb = RgbPhotometry(120.0, 180.0, 60.0)
        val quant = quantResult(listOf(baseSite(0, rgb)))

        val gray = ColorimetricPhotometryProcessor.process(
            quant,
            ColorimetricProcessorConfig(
                referenceSiteIndices = emptySet(),
                primaryFeature = AnalysisPrimaryFeature.GRAY_LUMINOSITY
            )
        )
        val green = ColorimetricPhotometryProcessor.process(
            quant,
            ColorimetricProcessorConfig(
                referenceSiteIndices = emptySet(),
                primaryFeature = AnalysisPrimaryFeature.GREEN_INTENSITY
            )
        )

        // 经典公式必须作为真实计算契约锁定，不能只在UI中显示公式却使用另一套权重。
        assertEquals(
            0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue,
            requireNotNull(gray.sites.single().primaryFeatureValue),
            1e-9
        )
        assertEquals(rgb.green, requireNotNull(green.sites.single().primaryFeatureValue), 1e-9)
        assertTrue(gray.referenceRgb == null && gray.referenceLab == null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `没有参考位时DeltaE明确拒绝而不是连带删除全部直接信号`() {
        ColorimetricPhotometryProcessor.process(
            quantResult(listOf(baseSite(0, RgbPhotometry(120.0, 180.0, 60.0)))),
            ColorimetricProcessorConfig(
                referenceSiteIndices = emptySet(),
                primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000
            )
        )
    }

    @Test
    fun `扩展颜色空间信号由统一处理器产生有限值`() {
        val quant = quantResult(listOf(baseSite(0, RgbPhotometry(80.0, 140.0, 210.0))))
        val features = listOf(
            AnalysisPrimaryFeature.CIE_L_STAR,
            AnalysisPrimaryFeature.CIE_A_STAR,
            AnalysisPrimaryFeature.CIE_B_STAR,
            AnalysisPrimaryFeature.HSV_HUE,
            AnalysisPrimaryFeature.YCBCR_CB,
            AnalysisPrimaryFeature.RED_GREEN_RATIO
        )

        features.forEach { feature ->
            val value = ColorimetricPhotometryProcessor.process(
                quant,
                ColorimetricProcessorConfig(emptySet(), feature)
            ).sites.single().primaryFeatureValue
            assertTrue("$feature 应输出有限信号", value?.isFinite() == true)
        }
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
