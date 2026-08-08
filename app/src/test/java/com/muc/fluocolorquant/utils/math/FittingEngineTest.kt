package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
