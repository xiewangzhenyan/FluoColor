package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `宽表自动识别一列浓度和多列已知信号`() {
        val result = CalibrationDataParser.parseTable(
            """
            浓度,0.299R+0.587G+0.114B,channel_r,净荧光强度
            0,240,245,12
            1,180,190,36
            """.trimIndent()
        )

        assertEquals(0, result.concentrationColumnIndex)
        assertEquals(
            listOf(
                AnalysisPrimaryFeature.GRAY_LUMINOSITY,
                AnalysisPrimaryFeature.RED_INTENSITY,
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            ),
            result.columns.drop(1).map { it.detectedFeature }
        )
        assertEquals(listOf(1, 2, 3), result.detectedSignalColumnIndices)
    }

    @Test
    fun `重复浓度原样保留且未知信号标题不被静默猜测`() {
        val result = CalibrationDataParser.parseTable(
            """
            concentration,my_custom_signal
            1,10
            1,11
            5,50
            """.trimIndent()
        )

        assertNull(result.columns[1].detectedFeature)
        assertEquals(emptyList<Int>(), result.detectedSignalColumnIndices)
        assertEquals(
            listOf(1.0 to 10.0, 1.0 to 11.0, 5.0 to 50.0),
            result.pointsFor(concentrationColumnIndex = 0, signalColumnIndex = 1)
        )
    }
}
