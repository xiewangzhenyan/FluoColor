package com.muc.fluocolorquant.utils.math

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationDataParserTest {

    @Test
    fun `CSV标题会被忽略且两列数值按浓度信号解析`() {
        val result = CalibrationDataParser.parse(
            """
            concentration,signal
            0,12.5
            1,25
            10,88.2
            """.trimIndent()
        )

        assertEquals(listOf(0.0 to 12.5, 1.0 to 25.0, 10.0 to 88.2), result.points)
        assertEquals(1, result.ignoredLineCount)
    }

    @Test
    fun `制表符和空格格式均可导入且负浓度被拒绝`() {
        val result = CalibrationDataParser.parse("0\t1.5\n2 3.5\n-1,9")

        assertEquals(listOf(0.0 to 1.5, 2.0 to 3.5), result.points)
        assertEquals(1, result.ignoredLineCount)
    }
}
