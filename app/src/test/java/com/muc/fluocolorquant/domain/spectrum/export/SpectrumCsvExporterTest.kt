package com.muc.fluocolorquant.domain.spectrum.export

import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import com.muc.fluocolorquant.ui.components.charts.ChartData
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 光谱 CSV 内容契约测试。
 *
 * CSV 会进入论文附件和第三方分析脚本，因此列结构、数值精度、缺失值表示和区域无关性
 * 都属于科学契约，必须逐字节可断言——这正是把它从 ViewModel 里抽出来的目的。
 */
class SpectrumCsvExporterTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private fun channel(
        index: Int,
        analyte: String,
        wavelengths: List<Double>,
        intensities: List<Double>,
        peakWavelength: Float? = 520.5f,
        peakIntensity: Double? = 0.875
    ) = SpectrumChannelExportModel(
        channelIndex = index,
        analyteName = analyte,
        analyteId = "analyte-$index",
        peakWavelength = peakWavelength,
        peakIntensity = peakIntensity,
        dataPointCount = wavelengths.size,
        minWavelength = wavelengths.minOrNull() ?: 0.0,
        maxWavelength = wavelengths.maxOrNull() ?: 0.0,
        wavelengths = wavelengths,
        intensities = intensities,
        chartData = ChartData()
    )

    @Test
    fun `长表结构为每个数据点一行并带头部溯源注释`() {
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "SpectrumDemo",
            exportedAt = "2026-07-26 12:00:00",
            channels = listOf(
                channel(1, "CEA", listOf(500.0, 501.0), listOf(0.1, 0.2)),
                channel(2, "CA125", listOf(500.0), listOf(0.3))
            )
        )
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals("# Project: SpectrumDemo", lines[0])
        assertEquals("# Export Time: 2026-07-26 12:00:00", lines[1])
        assertEquals("", lines[2])
        assertEquals(SpectrumCsvExporter.HEADER, lines[3])
        // 2 个通道共 3 个数据点 → 3 行数据。
        assertEquals(4 + 3, lines.size)
        assertEquals("1,CEA,520.50,0.8750,500.00,0.1000", lines[4])
        assertEquals("1,CEA,520.50,0.8750,501.00,0.2000", lines[5])
        assertEquals("2,CA125,520.50,0.8750,500.00,0.3000", lines[6])
    }

    @Test
    fun `峰值缺失时写短横线而不是空串或零`() {
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "P",
            exportedAt = "T",
            channels = listOf(
                channel(1, "CEA", listOf(500.0), listOf(0.1), peakWavelength = null, peakIntensity = null)
            )
        )
        val row = csv.trimEnd('\n').split('\n').last()
        // 空串会被下游当成 0，短横线才明确表达“没有测到峰”。
        assertEquals("1,CEA,-,-,500.00,0.1000", row)
    }

    @Test
    fun `强度数量少于波长时补零而波长本身不会被伪造`() {
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "P",
            exportedAt = "T",
            channels = listOf(channel(1, "CEA", listOf(500.0, 501.0, 502.0), listOf(0.1)))
        )
        val rows = csv.trimEnd('\n').split('\n').drop(4)
        assertEquals(3, rows.size)
        assertTrue(rows[1].endsWith("501.00,0.0000"))
        assertTrue(rows[2].endsWith("502.00,0.0000"))
    }

    @Test
    fun `数值格式与系统区域无关`() {
        // 德语区域会把小数点写成逗号，直接撑破 CSV 列结构。
        Locale.setDefault(Locale.GERMANY)
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "P",
            exportedAt = "T",
            channels = listOf(channel(1, "CEA", listOf(500.25), listOf(0.125)))
        )
        val row = csv.trimEnd('\n').split('\n').last()
        assertEquals("1,CEA,520.50,0.8750,500.25,0.1250", row)
        assertEquals(5, row.count { it == ',' })
    }

    @Test
    fun `分析物名称含逗号或引号时按RFC4180转义`() {
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "P",
            exportedAt = "T",
            channels = listOf(channel(1, "CEA, \"free\"", listOf(500.0), listOf(0.1)))
        )
        val row = csv.trimEnd('\n').split('\n').last()
        assertTrue(row.startsWith("1,\"CEA, \"\"free\"\"\","))
        // 转义后列数必须仍然是 6 列（5 个分隔逗号在引号外）。
        assertEquals(6, parseCsvRow(row).size)
    }

    @Test
    fun `非有限数值写短横线不写 NaN`() {
        val csv = SpectrumCsvExporter.buildCsv(
            projectName = "P",
            exportedAt = "T",
            channels = listOf(channel(1, "CEA", listOf(Double.NaN), listOf(Double.POSITIVE_INFINITY)))
        )
        val row = csv.trimEnd('\n').split('\n').last()
        assertEquals("1,CEA,520.50,0.8750,-,-", row)
    }

    @Test
    fun `没有通道时仍输出头部使下游能识别为空结果`() {
        val csv = SpectrumCsvExporter.buildCsv("P", "T", emptyList())
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals(4, lines.size)
        assertEquals(SpectrumCsvExporter.HEADER, lines[3])
    }

    /** 极简 RFC 4180 行解析，仅用于断言转义后的列数。 */
    private fun parseCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < row.length) {
            val ch = row[index]
            when {
                ch == '"' && inQuotes && index + 1 < row.length && row[index + 1] == '"' -> {
                    current.append('"'); index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields += current.toString(); current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
