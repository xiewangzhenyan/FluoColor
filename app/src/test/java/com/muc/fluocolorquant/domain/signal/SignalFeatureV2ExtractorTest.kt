package com.muc.fluocolorquant.domain.signal

import com.muc.fluocolorquant.data.enums.PixelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/** 锁定 V2 处理器最容易出现平台回归的颜色数学与无效值规则。 */
class SignalFeatureV2ExtractorTest {

    @Test
    fun hueAcrossZero_usesCircularMeanInsteadOfArithmeticMean() {
        val result = SignalFeatureV2Extractor.extract(
            listOf(
                RgbSignalSample(255.0, 0.0, 4.0),
                RgbSignalSample(255.0, 4.0, 0.0)
            )
        )

        val hue = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.HUE)])
        val distanceToZero = min(hue, 360.0 - hue)
        assertTrue("跨越 0° 的色相均值应接近红色 0°，实际为 $hue", distanceToZero < 2.0)
    }

    @Test
    fun grayscaleHue_isMarkedInvalidInsteadOfBeingStoredAsZero() {
        val result = SignalFeatureV2Extractor.extract(
            listOf(
                RgbSignalSample(120.0, 120.0, 120.0),
                RgbSignalSample(180.0, 180.0, 180.0)
            )
        )
        val hueCode = SignalFeatureCatalog.v2Code(PixelType.HUE)

        assertFalse(result.values.containsKey(hueCode))
        assertEquals(SignalInvalidReason.LOW_SATURATION, result.invalidReasons[hueCode])
    }

    @Test
    fun lab_usesStandardDisplayRangeWithSignedAxes() {
        val result = SignalFeatureV2Extractor.extract(
            listOf(RgbSignalSample(255.0, 0.0, 0.0))
        )

        val lightness = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.CIE_L)])
        val a = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.CIE_a)])
        val b = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.CIE_b)])
        assertTrue(lightness in 0.0..100.0)
        assertTrue("红色的标准 Lab a* 应为正值", a > 0.0)
        assertTrue("红色的标准 Lab b* 应为正值", b > 0.0)
    }

    @Test
    fun yCbCrRedSample_hasCrHigherThanCb() {
        val result = SignalFeatureV2Extractor.extract(
            listOf(RgbSignalSample(255.0, 0.0, 0.0))
        )

        val cb = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.YCBCR_CB)])
        val cr = requireNotNull(result.values[SignalFeatureCatalog.v2Code(PixelType.YCBCR_CR)])
        assertTrue("红色样本应当 Cr 高于 Cb，防止 YCrCb 通道再次写反", cr > cb)
    }

    @Test
    fun ratioWithNearZeroDenominator_isNotEmitted() {
        val result = SignalFeatureV2Extractor.extract(
            listOf(RgbSignalSample(200.0, 1.0, 1.0))
        )
        val ratioRg = SignalFeatureCatalog.v2Code(PixelType.RATIO_RG)
        val ratioRb = SignalFeatureCatalog.v2Code(PixelType.RATIO_RB)

        assertFalse(result.values.containsKey(ratioRg))
        assertFalse(result.values.containsKey(ratioRb))
        assertEquals(SignalInvalidReason.UNSTABLE_RATIO_DENOMINATOR, result.invalidReasons[ratioRg])
        assertEquals(SignalInvalidReason.UNSTABLE_RATIO_DENOMINATOR, result.invalidReasons[ratioRb])
    }

    @Test
    fun catalog_keepsEveryLegacyPixelTypeAddressable() {
        PixelType.values().forEach { pixelType ->
            val definition = SignalFeatureCatalog.definitionFor(pixelType)
            assertEquals(pixelType, definition.legacyPixelType)
            assertEquals(SignalFeatureCatalog.V2_PROCESSOR_VERSION, definition.processorVersion)
            assertTrue(definition.code.startsWith("pixel.v2."))
        }
    }

    @Test
    fun versionedLookup_doesNotFallbackWhenV2SignalIsInvalid() {
        val legacyValues = mapOf(PixelType.HUE.identifier to 180.0)
        val resolved = SignalFeatureCatalog.resolveValue(
            values = legacyValues,
            signalFeatureCode = SignalFeatureCatalog.v2Code(PixelType.HUE),
            legacyPixelType = PixelType.HUE
        )

        assertEquals("V2 Hue 无效时不能回退旧算术均值", null, resolved)
    }

    @Test
    fun blankCorrection_onlySubtractsAdditiveV2Signals() {
        val greenCode = SignalFeatureCatalog.v2Code(PixelType.GREEN)
        val labCode = SignalFeatureCatalog.v2Code(PixelType.CIE_L)
        val corrected = SignalFeatureCatalog.applyV2BlankCorrection(
            values = mapOf(greenCode to 120.0, labCode to 60.0),
            blankValues = mapOf(greenCode to 20.0, labCode to 10.0)
        )

        assertEquals(100.0, corrected.getValue(greenCode), 0.0001)
        assertEquals("Lab 不是可直接相减的加性信号", 60.0, corrected.getValue(labCode), 0.0001)
    }
}
