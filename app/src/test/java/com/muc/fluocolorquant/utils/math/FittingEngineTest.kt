package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 拟合公式的科研排版回归，防止负参数重新显示为相邻双符号。 */
class FittingEngineTest {

    @Test
    fun `线性公式负截距使用减号而不是加负号`() {
        val latex = FittingEngine.formatParametersToLatex(
            FittingFunction.LINEAR,
            mapOf("a" to -0.505, "b" to -70.923)
        )

        assertFalse(latex.contains("+ -"))
        assertEquals("y = -0.505x - 70.923", latex)
    }

    @Test
    fun `通用有界反算支持标定区间内单调二次函数`() {
        val concentration = FittingEngine.invertCalibrationSignal(
            function = FittingFunction.QUADRATIC,
            params = mapOf("a" to 2.0, "b" to 3.0, "c" to 4.0),
            signal = 69.0,
            concentrationMinimum = 0.0,
            concentrationMaximum = 10.0
        )

        assertEquals(5.0, requireNotNull(concentration), 1e-8)
    }

    @Test
    fun `通用有界反算拒绝区间内存在转折的二次函数`() {
        val concentration = FittingEngine.invertCalibrationSignal(
            function = FittingFunction.QUADRATIC,
            params = mapOf("a" to 1.0, "b" to -10.0, "c" to 25.0),
            signal = 9.0,
            concentrationMinimum = 0.0,
            concentrationMaximum = 10.0
        )

        assertNull(concentration)
    }
}
