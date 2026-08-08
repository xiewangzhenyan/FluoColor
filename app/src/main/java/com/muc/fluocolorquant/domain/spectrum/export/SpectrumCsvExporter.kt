package com.muc.fluocolorquant.domain.spectrum.export

import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import java.util.Locale

/**
 * 光谱 CSV 内容生成。
 *
 * 与 `ArrayResultExporter` 保持同一形态：**不依赖任何 Android 类**，只把冻结数据转成
 * 文本。文件写入、FileProvider 和媒体扫描仍由 ViewModel 负责——那些是平台职责，而
 * "导出的科学内容长什么样"是领域职责，必须能在 JVM 上直接断言。
 *
 * 这一层过去内嵌在 `ExportViewModel` 的 3000 行里，只能靠仪器测试间接覆盖；数值格式、
 * 缺失值表示和长表结构这些真正决定下游能否复现的约定，反而没有任何直接测试。
 */
object SpectrumCsvExporter {

    /** 缺失值统一写成短横线，避免下游把空串当成 0。 */
    const val MISSING_VALUE: String = "-"

    /**
     * 长表列头：每行一个数据点，便于 Origin/Python 直接读取。
     *
     * 列名固定使用英文与显式单位，不随界面语言变化——CSV 会进入论文附件和第三方分析
     * 脚本，列名一旦随语言漂移就无法复现。
     */
    const val HEADER: String =
        "Channel,Analyte,PeakWavelength(nm),PeakIntensity,Wavelength(nm),NormalizedIntensity"

    /**
     * 生成完整 CSV 文本。
     *
     * @param projectName 项目名，写入头部注释供人工核对。
     * @param exportedAt 导出时间的**已格式化文本**。刻意由调用方传入而不是在这里取系统
     *   时间：领域函数保持纯净，同样的输入必定产出同样的输出，测试才能逐字节断言。
     */
    fun buildCsv(
        projectName: String,
        exportedAt: String,
        channels: List<SpectrumChannelExportModel>
    ): String {
        val builder = StringBuilder()
        builder.append("# Project: ").append(projectName).append('\n')
        builder.append("# Export Time: ").append(exportedAt).append('\n')
        builder.append('\n')
        builder.append(HEADER).append('\n')

        channels.forEach { channel ->
            val peakWavelength = channel.peakWavelength?.let { format(it.toDouble(), WAVELENGTH_SCALE) }
                ?: MISSING_VALUE
            val peakIntensity = channel.peakIntensity?.let { format(it, INTENSITY_SCALE) }
                ?: MISSING_VALUE
            // 波长与强度按索引配对；强度缺失时补 0 与历史行为一致，但波长缺失说明该点
            // 根本不存在，直接跳过而不是伪造一个坐标。
            channel.wavelengths.forEachIndexed { index, wavelength ->
                val intensity = channel.intensities.getOrNull(index) ?: 0.0
                builder.append(channel.channelIndex).append(',')
                    .append(escape(channel.analyteName)).append(',')
                    .append(peakWavelength).append(',')
                    .append(peakIntensity).append(',')
                    .append(format(wavelength, WAVELENGTH_SCALE)).append(',')
                    .append(format(intensity, INTENSITY_SCALE)).append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * 数值一律用 [Locale.US] 格式化。
     *
     * 中文/德语等区域会把小数点写成逗号，直接破坏 CSV 分隔并让下游解析出错位数据。
     */
    private fun format(value: Double, decimals: Int): String {
        if (!value.isFinite()) return MISSING_VALUE
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    /** 分析物名称可能含逗号或引号，按 RFC 4180 转义，否则会撑破列结构。 */
    private fun escape(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private const val WAVELENGTH_SCALE: Int = 2
    private const val INTENSITY_SCALE: Int = 4
}
