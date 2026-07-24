package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 分析模型兼容契约中使用的比色处理器稳定名称与版本。 */
const val COLORIMETRIC_PROCESSOR_NAME: String = "colorimetric-photometry"
const val COLORIMETRIC_PROCESSOR_VERSION: String = "v1"

/** CIE L*a*b* 颜色，D65 白点，L* 约为 0～100。 */
data class LabPhotometry(
    val lightness: Double,
    val a: Double,
    val b: Double
)

/**
 * 比色处理器配置。
 *
 * 参考位点来自模板冻结布局，不能在检测后随意选择。普通模型优先使用 ΔE2000 或
 * 光密度；为兼容旧96孔板数据和多列 CSV，也允许从统一白平衡后的 RGB 计算常用特征。
 */
data class ColorimetricProcessorConfig(
    val referenceSiteIndices: Set<Int>,
    val primaryFeature: AnalysisPrimaryFeature,
    val specularHighlightRatioLimit: Double = 0.02
) {
    init {
        require(referenceSiteIndices.isNotEmpty()) { "比色处理必须至少指定一个模板参考位" }
        require(
            primaryFeature in setOf(
                AnalysisPrimaryFeature.DELTA_E_2000,
                AnalysisPrimaryFeature.OPTICAL_DENSITY,
                AnalysisPrimaryFeature.GRAY_LUMINOSITY,
                AnalysisPrimaryFeature.RED_INTENSITY,
                AnalysisPrimaryFeature.GREEN_INTENSITY,
                AnalysisPrimaryFeature.BLUE_INTENSITY,
                AnalysisPrimaryFeature.AVERAGE_RGB
            )
        ) { "比色处理器收到不兼容的主特征" }
        require(specularHighlightRatioLimit in 0.0..1.0) { "反光比例阈值必须位于 0 到 1" }
    }
}

/** 单个位点的比色专用科学输出。 */
data class ColorimetricSitePhotometry(
    val base: BaseSitePhotometry,
    val whiteBalancedRgb: RgbPhotometry,
    val lab: LabPhotometry,
    val deltaE2000: Double,
    val opticalDensity: Double,
    val primaryFeature: AnalysisPrimaryFeature,
    val primaryFeatureValue: Double,
    val qc: SitePhotometryQc
)

/** 一次比色阵列处理结果；全阵列只保存一组参考白增益。 */
data class ColorimetricPhotometryResult(
    val processorVersion: String,
    val whiteBalanceGains: RgbPhotometry,
    val referenceRgb: RgbPhotometry,
    val referenceLab: LabPhotometry,
    val sites: List<ColorimetricSitePhotometry>
)

/**
 * 比色专用光度处理器。
 *
 * 白平衡只能从模板参考位聚合得到一组全局增益，并同样应用到全部样本；绝不对每个
 * ROI 独立执行灰世界，否则真实显色差异会被人为中和。随后计算校正 Lab、CIEDE2000
 * 和相对参考的光密度，供标准曲线或智能模型选择兼容主特征。
 */
object ColorimetricPhotometryProcessor {

    fun process(
        quant: PgQuantResult,
        config: ColorimetricProcessorConfig
    ): ColorimetricPhotometryResult {
        quant.requireValid()
        require(config.referenceSiteIndices.all { it in quant.sites.indices }) {
            "比色参考位索引超出阵列范围"
        }

        val referenceSites = config.referenceSiteIndices.sorted().map(quant.sites::get)
        val rawReference = medianRgb(referenceSites.map(BaseSitePhotometry::correctedMedianRgb))
        val referenceMean = (rawReference.red + rawReference.green + rawReference.blue) / 3.0
        val gains = RgbPhotometry(
            red = (referenceMean / maxOf(rawReference.red, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN),
            green = (referenceMean / maxOf(rawReference.green, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN),
            blue = (referenceMean / maxOf(rawReference.blue, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN)
        )
        val balancedReference = applyGains(rawReference, gains)
        val referenceLab = rgbToLab(balancedReference)
        val referenceLuminance = luminance(balancedReference)

        val sites = quant.sites.map { base ->
            val balanced = applyGains(base.correctedMedianRgb, gains)
            val lab = rgbToLab(balanced)
            val deltaE = deltaE2000(lab, referenceLab)
            val opticalDensity = -log10(
                (luminance(balanced) + EPSILON) /
                    (referenceLuminance + EPSILON)
            )
            val flags = buildSet {
                addAll(base.qc.flags)
                if (base.saturationRatio > config.specularHighlightRatioLimit) {
                    add(PhotometryFlag.SPECULAR_HIGHLIGHT)
                }
            }
            val qc = SitePhotometryQc.from(
                flags = flags,
                snr = base.signalToNoiseRatio,
                snrMinimum = quant.config.snrMinimum,
                hardFailure = !base.qc.qualityReliable ||
                    base.saturationRatio >= quant.config.severeSaturationRatioLimit ||
                    base.roiClipRatio >= quant.config.severeBorderClipRatioLimit ||
                    base.annulusClipRatio >= quant.config.severeBorderClipRatioLimit
            )
            val primaryValue = when (config.primaryFeature) {
                AnalysisPrimaryFeature.DELTA_E_2000 -> deltaE
                AnalysisPrimaryFeature.OPTICAL_DENSITY -> opticalDensity
                AnalysisPrimaryFeature.GRAY_LUMINOSITY -> luminance(balanced)
                AnalysisPrimaryFeature.RED_INTENSITY -> balanced.red
                AnalysisPrimaryFeature.GREEN_INTENSITY -> balanced.green
                AnalysisPrimaryFeature.BLUE_INTENSITY -> balanced.blue
                AnalysisPrimaryFeature.AVERAGE_RGB ->
                    (balanced.red + balanced.green + balanced.blue) / 3.0
                else -> error("构造器已经阻止不兼容的比色主特征")
            }
            ColorimetricSitePhotometry(
                base = base,
                whiteBalancedRgb = balanced,
                lab = lab,
                deltaE2000 = deltaE,
                opticalDensity = opticalDensity,
                primaryFeature = config.primaryFeature,
                primaryFeatureValue = primaryValue,
                qc = qc
            )
        }

        return ColorimetricPhotometryResult(
            processorVersion = COLORIMETRIC_PROCESSOR_VERSION,
            whiteBalanceGains = gains,
            referenceRgb = balancedReference,
            referenceLab = referenceLab,
            sites = sites
        )
    }

    private fun applyGains(rgb: RgbPhotometry, gains: RgbPhotometry): RgbPhotometry {
        return RgbPhotometry(
            red = (rgb.red * gains.red).coerceIn(0.0, 255.0),
            green = (rgb.green * gains.green).coerceIn(0.0, 255.0),
            blue = (rgb.blue * gains.blue).coerceIn(0.0, 255.0)
        )
    }

    /** sRGB（D65）转换到 CIE L*a*b*，不依赖 Android/OpenCV，便于 JVM 金标准测试。 */
    private fun rgbToLab(rgb: RgbPhotometry): LabPhotometry {
        val red = inverseSrgbCompanding(rgb.red / 255.0)
        val green = inverseSrgbCompanding(rgb.green / 255.0)
        val blue = inverseSrgbCompanding(rgb.blue / 255.0)

        val x = 0.4124564 * red + 0.3575761 * green + 0.1804375 * blue
        val y = 0.2126729 * red + 0.7151522 * green + 0.0721750 * blue
        val z = 0.0193339 * red + 0.1191920 * green + 0.9503041 * blue
        val fx = labPivot(x / D65_X)
        val fy = labPivot(y / D65_Y)
        val fz = labPivot(z / D65_Z)
        return LabPhotometry(
            lightness = 116.0 * fy - 16.0,
            a = 500.0 * (fx - fy),
            b = 200.0 * (fy - fz)
        )
    }

    private fun inverseSrgbCompanding(value: Double): Double {
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun labPivot(value: Double): Double {
        return if (value > LAB_DELTA_CUBE) value.pow(1.0 / 3.0)
        else value / (3.0 * LAB_DELTA.pow(2)) + 4.0 / 29.0
    }

    /** CIEDE2000 标准公式，权重参数 kL/kC/kH 均取 1。 */
    private fun deltaE2000(first: LabPhotometry, second: LabPhotometry): Double {
        val c1 = hypot(first.a, first.b)
        val c2 = hypot(second.a, second.b)
        val cMean = (c1 + c2) / 2.0
        val cMean7 = cMean.pow(7)
        val g = 0.5 * (1.0 - sqrt(cMean7 / (cMean7 + 25.0.pow(7))))
        val a1Prime = (1.0 + g) * first.a
        val a2Prime = (1.0 + g) * second.a
        val c1Prime = hypot(a1Prime, first.b)
        val c2Prime = hypot(a2Prime, second.b)
        val h1Prime = hueDegrees(first.b, a1Prime)
        val h2Prime = hueDegrees(second.b, a2Prime)

        val deltaLightness = second.lightness - first.lightness
        val deltaChroma = c2Prime - c1Prime
        val hueDifference = when {
            c1Prime * c2Prime == 0.0 -> 0.0
            abs(h2Prime - h1Prime) <= 180.0 -> h2Prime - h1Prime
            h2Prime - h1Prime > 180.0 -> h2Prime - h1Prime - 360.0
            else -> h2Prime - h1Prime + 360.0
        }
        val deltaHue = 2.0 * sqrt(c1Prime * c2Prime) * sin(Math.toRadians(hueDifference / 2.0))
        val lightnessMean = (first.lightness + second.lightness) / 2.0
        val chromaMean = (c1Prime + c2Prime) / 2.0
        val hueMean = when {
            c1Prime * c2Prime == 0.0 -> h1Prime + h2Prime
            abs(h1Prime - h2Prime) <= 180.0 -> (h1Prime + h2Prime) / 2.0
            h1Prime + h2Prime < 360.0 -> (h1Prime + h2Prime + 360.0) / 2.0
            else -> (h1Prime + h2Prime - 360.0) / 2.0
        }

        val t = 1.0 -
            0.17 * cos(Math.toRadians(hueMean - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hueMean)) +
            0.32 * cos(Math.toRadians(3.0 * hueMean + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hueMean - 63.0))
        val deltaTheta = 30.0 * kotlin.math.exp(-((hueMean - 275.0) / 25.0).pow(2))
        val chromaMean7 = chromaMean.pow(7)
        val rc = 2.0 * sqrt(chromaMean7 / (chromaMean7 + 25.0.pow(7)))
        val sl = 1.0 + 0.015 * (lightnessMean - 50.0).pow(2) /
            sqrt(20.0 + (lightnessMean - 50.0).pow(2))
        val sc = 1.0 + 0.045 * chromaMean
        val sh = 1.0 + 0.015 * chromaMean * t
        val rt = -sin(Math.toRadians(2.0 * deltaTheta)) * rc

        val lightnessTerm = deltaLightness / sl
        val chromaTerm = deltaChroma / sc
        val hueTerm = deltaHue / sh
        return sqrt(
            lightnessTerm.pow(2) +
                chromaTerm.pow(2) +
                hueTerm.pow(2) +
                rt * chromaTerm * hueTerm
        )
    }

    private fun hueDegrees(b: Double, aPrime: Double): Double {
        val degrees = Math.toDegrees(atan2(b, aPrime))
        return if (degrees >= 0.0) degrees else degrees + 360.0
    }

    private fun hypot(first: Double, second: Double): Double = sqrt(first * first + second * second)

    private fun luminance(rgb: RgbPhotometry): Double {
        return 0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue
    }

    private fun medianRgb(values: List<RgbPhotometry>): RgbPhotometry {
        return RgbPhotometry(
            red = median(values.map(RgbPhotometry::red)),
            green = median(values.map(RgbPhotometry::green)),
            blue = median(values.map(RgbPhotometry::blue))
        )
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "比色参考值不能为空" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private const val MIN_GAIN: Double = 0.25
    private const val MAX_GAIN: Double = 4.0
    private const val EPSILON: Double = 1e-6
    private const val D65_X: Double = 0.95047
    private const val D65_Y: Double = 1.0
    private const val D65_Z: Double = 1.08883
    private const val LAB_DELTA: Double = 6.0 / 29.0
    private val LAB_DELTA_CUBE: Double = LAB_DELTA.pow(3)
}
