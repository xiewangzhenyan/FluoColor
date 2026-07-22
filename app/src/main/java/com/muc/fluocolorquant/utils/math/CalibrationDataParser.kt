package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import java.util.Locale

/** 标定点文本导入结果。 */
data class CalibrationDataParseResult(
    val points: List<Pair<Double, Double>>,
    val ignoredLineCount: Int
)

/** CSV 中可供用户确认的单列定义。 */
data class CalibrationTableColumn(
    val index: Int,
    val header: String,
    val detectedFeature: AnalysisPrimaryFeature? = null
)

/** CSV 中保留行号的数值行；空单元格以 null 表示，便于映射预览明确提示。 */
data class CalibrationTableRow(
    val sourceLineNumber: Int,
    val values: List<Double?>
)

/**
 * 宽表标定数据解析结果。
 *
 * 一个文件可以包含一列浓度和多列候选信号。解析器只负责确定性识别与保留原始列关系，
 * 不在这里静默选择“最佳”信号；最终选择由拟合器比较全部用户确认的候选列后完成。
 */
data class CalibrationTableParseResult(
    val columns: List<CalibrationTableColumn>,
    val rows: List<CalibrationTableRow>,
    val concentrationColumnIndex: Int?,
    val detectedSignalColumnIndices: List<Int>,
    val ignoredLineCount: Int,
    val headerDetected: Boolean
) {
    /** 将用户确认的两列转换成拟合点，重复浓度会原样保留用于重复性统计。 */
    fun pointsFor(
        concentrationColumnIndex: Int,
        signalColumnIndex: Int
    ): List<Pair<Double, Double>> = rows.mapNotNull { row ->
        val concentration = row.values.getOrNull(concentrationColumnIndex)
        val signal = row.values.getOrNull(signalColumnIndex)
        if (concentration != null && concentration >= 0.0 && signal != null) {
            concentration to signal
        } else {
            null
        }
    }
}

/**
 * 标准曲线 CSV/文本解析器。
 *
 * 支持逗号、分号、制表符或连续空白分隔的两列数据。首行可以是标题；解析器会自动跳过
 * 空行和无法转换为两个有限数字的行。第一列固定解释为浓度，第二列固定解释为信号值，
 * 从交互上消除旧页面“先指定每一列类型”的额外步骤。
 */
object CalibrationDataParser {

    /**
     * 兼容旧两列调用方。
     *
     * 新页面使用 [parseTable]；旧页面仍按“识别到的浓度列 + 第一列有效信号”读取，避免
     * 数据库升级期间突然破坏已有测试和历史导入入口。
     */
    fun parse(rawText: String): CalibrationDataParseResult {
        val table = parseTable(rawText)
        val concentrationIndex = table.concentrationColumnIndex ?: 0
        val signalIndex = table.detectedSignalColumnIndices.firstOrNull()
            ?: table.columns.firstOrNull { it.index != concentrationIndex }?.index
        val points = signalIndex?.let { table.pointsFor(concentrationIndex, it) }.orEmpty()
        return CalibrationDataParseResult(
            points = points,
            // 旧两列 API 历来把标题行计入 ignoredLineCount；宽表 API 单独暴露
            // headerDetected，因此这里只为兼容旧调用方补回这一计数语义。
            ignoredLineCount = table.ignoredLineCount +
                (table.rows.size - points.size) +
                if (table.headerDetected) 1 else 0
        )
    }

    /**
     * 解析包含标题、浓度列和任意数量信号列的 CSV/TSV/分号或空格分隔文本。
     *
     * 标题使用稳定机器别名自动识别；没有标题时生成 `Column 1` 等内部名称并把第一列
     * 作为浓度候选。歧义不会被猜测成某个科学特征，而是留给页面的列映射面板确认。
     */
    fun parseTable(rawText: String): CalibrationTableParseResult {
        val nonEmptyLines = rawText.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.trim().removePrefix("\uFEFF")
                line.takeIf(String::isNotEmpty)?.let { index + 1 to it }
            }
            .toList()
        if (nonEmptyLines.isEmpty()) {
            return CalibrationTableParseResult(
                columns = emptyList(),
                rows = emptyList(),
                concentrationColumnIndex = null,
                detectedSignalColumnIndices = emptyList(),
                ignoredLineCount = 0,
                headerDetected = false
            )
        }

        val firstTokens = splitColumns(nonEmptyLines.first().second)
        val headerDetected = firstTokens.any { it.toFiniteDoubleOrNull() == null }
        val dataLines = if (headerDetected) nonEmptyLines.drop(1) else nonEmptyLines
        val tokenRows = dataLines.map { (lineNumber, line) -> lineNumber to splitColumns(line) }
        val maximumColumnCount = maxOf(
            firstTokens.size,
            tokenRows.maxOfOrNull { it.second.size } ?: 0
        )
        val headers = List(maximumColumnCount) { index ->
            if (headerDetected) {
                firstTokens.getOrNull(index)?.takeIf(String::isNotBlank) ?: "Column ${index + 1}"
            } else {
                "Column ${index + 1}"
            }
        }
        val columns = headers.mapIndexed { index, header ->
            CalibrationTableColumn(
                index = index,
                header = header,
                detectedFeature = detectFeature(header)
            )
        }
        var ignoredLineCount = 0
        val rows = tokenRows.mapNotNull { (lineNumber, tokens) ->
            val values = List(maximumColumnCount) { index ->
                tokens.getOrNull(index)?.toFiniteDoubleOrNull()
            }
            if (values.none { it != null }) {
                ignoredLineCount += 1
                null
            } else {
                CalibrationTableRow(sourceLineNumber = lineNumber, values = values)
            }
        }
        val concentrationColumnIndex = columns.firstOrNull {
            isConcentrationHeader(it.header)
        }?.index ?: 0.takeIf { columns.size >= 2 }
        val signalColumnIndices = columns.mapNotNull { column ->
            column.index.takeIf {
                column.index != concentrationColumnIndex && column.detectedFeature != null
            }
        }
        return CalibrationTableParseResult(
            columns = columns,
            rows = rows,
            concentrationColumnIndex = concentrationColumnIndex,
            detectedSignalColumnIndices = signalColumnIndices,
            ignoredLineCount = ignoredLineCount,
            headerDetected = headerDetected
        )
    }

    private fun splitColumns(line: String): List<String> {
        val delimiter = when {
            '\t' in line -> Regex("\\t+")
            ',' in line -> Regex("\\s*,\\s*")
            ';' in line -> Regex("\\s*;\\s*")
            else -> Regex("\\s+")
        }
        return line.split(delimiter).map { token -> token.trim().trim('"') }
    }

    private fun String.toFiniteDoubleOrNull(): Double? {
        return toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    /** 标题归一化只用于机器别名匹配，不修改用户在预览中看到的原始标题。 */
    private fun normalizeHeader(header: String): String = header
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

    private fun isConcentrationHeader(header: String): Boolean {
        val normalized = normalizeHeader(header)
        return normalized in setOf(
            "concentration", "concentrationvalue", "conc", "浓度", "标准浓度", "x"
        ) || normalized.startsWith("concentration")
    }

    /** 已知标题自动映射；未识别标题必须由用户确认，禁止按列位置冒充某种信号。 */
    private fun detectFeature(header: String): AnalysisPrimaryFeature? {
        return when (normalizeHeader(header)) {
            "deltae2000", "de2000", "色差", "色差2000" ->
                AnalysisPrimaryFeature.DELTA_E_2000
            "opticaldensity", "od", "光密度" ->
                AnalysisPrimaryFeature.OPTICAL_DENSITY
            "grayluminosity", "greyluminosity", "grayscale", "gray", "grey",
            "0299r0587g0114b", "灰度", "亮度灰度" ->
                AnalysisPrimaryFeature.GRAY_LUMINOSITY
            "redintensity", "red", "channelr", "r", "红通道" ->
                AnalysisPrimaryFeature.RED_INTENSITY
            "greenintensity", "green", "channelg", "g", "绿通道" ->
                AnalysisPrimaryFeature.GREEN_INTENSITY
            "blueintensity", "blue", "channelb", "b", "蓝通道" ->
                AnalysisPrimaryFeature.BLUE_INTENSITY
            "averagergb", "rgbaverage", "rgbmean", "rgb平均值" ->
                AnalysisPrimaryFeature.AVERAGE_RGB
            "netfluorescenceintensity", "netfluorescence", "netintensity",
            "净荧光强度", "净强度" -> AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            "integratedfluorescenceintensity", "integratedfluorescence",
            "integratedintensity", "积分荧光强度", "积分强度" ->
                AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY
            "fluorescencesnr", "snr", "荧光信噪比", "信噪比" ->
                AnalysisPrimaryFeature.FLUORESCENCE_SNR
            "peakwavelengthnm", "peakwavelength", "峰值波长" ->
                AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM
            "deltapeakwavelengthnm", "deltapeakwavelength", "波长偏移" ->
                AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM
            else -> null
        }
    }
}
