package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标准曲线创建、项目匹配和生产检测必须共享同一套信号特征契约。 */
class AnalysisFeaturePolicyTest {

    @Test
    fun `比色和荧光特征不会跨模态混用`() {
        assertTrue(
            AnalysisFeaturePolicy.isCompatible(
                DetectionModality.COLORIMETRIC,
                AnalysisPrimaryFeature.GRAY_LUMINOSITY
            )
        )
        assertFalse(
            AnalysisFeaturePolicy.isCompatible(
                DetectionModality.COLORIMETRIC,
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            )
        )
        assertTrue(
            AnalysisFeaturePolicy.isCompatible(
                DetectionModality.FLUORESCENCE,
                AnalysisPrimaryFeature.FLUORESCENCE_SNR
            )
        )
        assertFalse(
            AnalysisFeaturePolicy.isCompatible(
                DetectionModality.FLUORESCENCE,
                AnalysisPrimaryFeature.RED_INTENSITY
            )
        )
    }

    @Test
    fun `八位比色通道严格限制在零到二百五十五`() {
        val range = AnalysisFeaturePolicy.signalRange(AnalysisPrimaryFeature.RED_INTENSITY)

        assertTrue(range.contains(0.0))
        assertTrue(range.contains(255.0))
        assertFalse(range.contains(-0.01))
        assertFalse(range.contains(255.01))
    }

    @Test
    fun `默认信号与生产处理器身份保持稳定`() {
        assertEquals(
            AnalysisPrimaryFeature.DELTA_E_2000,
            AnalysisFeaturePolicy.defaultFeature(DetectionModality.COLORIMETRIC)
        )
        assertEquals(
            AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            AnalysisFeaturePolicy.defaultFeature(DetectionModality.FLUORESCENCE)
        )
        assertEquals(
            "fluorescence-photometry" to "v1",
            AnalysisFeaturePolicy.processorIdentity(DetectionModality.FLUORESCENCE)
        )
    }
}
