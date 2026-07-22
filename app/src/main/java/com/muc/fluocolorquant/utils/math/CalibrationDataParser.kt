package com.muc.fluocolorquant.utils.math

/** 标定点文本导入结果。 */
data class CalibrationDataParseResult(
    val points: List<Pair<Double, Double>>,
    val ignoredLineCount: Int
)

/**
 * 标准曲线 CSV/文本解析器。
 *
 * 支持逗号、分号、制表符或连续空白分隔的两列数据。首行可以是标题；解析器会自动跳过
 * 空行和无法转换为两个有限数字的行。第一列固定解释为浓度，第二列固定解释为信号值，
 * 从交互上消除旧页面“先指定每一列类型”的额外步骤。
 */
object CalibrationDataParser {

    fun parse(rawText: String): CalibrationDataParseResult {
        var ignoredLineCount = 0
        val points = buildList {
            rawText.lineSequence().forEach { rawLine ->
                val line = rawLine.trim().removePrefix("\uFEFF")
                if (line.isEmpty()) return@forEach

                val columns = splitColumns(line)
                val concentration = columns.getOrNull(0)?.toFiniteDoubleOrNull()
                val signal = columns.getOrNull(1)?.toFiniteDoubleOrNull()
                if (concentration == null || concentration < 0.0 || signal == null) {
                    ignoredLineCount += 1
                } else {
                    add(concentration to signal)
                }
            }
        }
        return CalibrationDataParseResult(
            points = points,
            ignoredLineCount = ignoredLineCount
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
}
