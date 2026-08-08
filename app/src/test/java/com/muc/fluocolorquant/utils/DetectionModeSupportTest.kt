package com.muc.fluocolorquant.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户确认旧孔板的比色与荧光暂时共用同一个真实存在的浓度模型。 */
class DetectionModeSupportTest {

    @Test
    fun `比色和荧光返回同一个共享模型路径`() {
        val fluorescence = DetectionModeSupport.concentrationModelCandidates(
            DetectionModeKind.FLUORESCENCE
        )
        val colorimetric = DetectionModeSupport.concentrationModelCandidates(
            DetectionModeKind.COLORIMETRIC
        )

        val expected = listOf("models/improved_concentration_model_lite.ptl")
        assertEquals(expected, fluorescence)
        assertEquals(expected, colorimetric)
        assertTrue(fluorescence == colorimetric)
    }

    @Test
    fun `光谱不返回RGB浓度模型`() {
        assertEquals(
            emptyList<String>(),
            DetectionModeSupport.concentrationModelCandidates(DetectionModeKind.SPECTRUM)
        )
    }
}
