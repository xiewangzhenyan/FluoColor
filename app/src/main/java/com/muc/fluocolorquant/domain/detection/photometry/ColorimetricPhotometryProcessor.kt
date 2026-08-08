package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.domain.signal.RgbSignalSample
import com.muc.fluocolorquant.domain.signal.SignalFeatureCatalog
import com.muc.fluocolorquant.domain.signal.SignalFeatureV2Extractor
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
        require(
            referenceSiteIndices.isNotEmpty() || !AnalysisFeaturePolicy.requiresReference(primaryFeature)
        ) { "当前比色主特征必须至少指定一个模板参考位" }
        require(
            AnalysisFeaturePolicy.isCompatible(
                com.muc.fluocolorquant.data.enums.DetectionModality.COLORIMETRIC,
                primaryFeature
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
    val deltaE2000: Double?,
    val opticalDensity: Double?,
    val primaryFeature: AnalysisPrimaryFeature,
    /** 低饱和度Hue、低分母比率等不稳定特征不伪造为0，而是明确返回空值。 */
    val primaryFeatureValue: Double?,
    val qc: SitePhotometryQc
)

/** 一次比色阵列处理结果；全阵列只保存一组参考白增益。 */
data class ColorimetricPhotometryResult(
    val processorVersion: String,
    val whiteBalanceGains: RgbPhotometry,
    /** 直接RGB/Lab等特征可以没有模板参考位，因此参考上下文必须允许为空。 */
    val referenceRgb: RgbPhotometry?,
    val referenceLab: LabPhotometry?,
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

        val rawReference = config.referenceSiteIndices.takeIf(Set<Int>::isNotEmpty)
            ?.sorted()
            ?.map(quant.sites::get)
            ?.map(BaseSitePhotometry::correctedMedianRgb)
            ?.let(::medianRgb)
        val gains = rawReference?.let { reference ->
            val referenceMean = (reference.red + reference.green + reference.blue) / 3.0
            RgbPhotometry(
                red = (referenceMean / maxOf(reference.red, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN),
                green = (referenceMean / maxOf(reference.green, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN),
                blue = (referenceMean / maxOf(reference.blue, EPSILON)).coerceIn(MIN_GAIN, MAX_GAIN)
            )
        } ?: RgbPhotometry(red = 1.0, green = 1.0, blue = 1.0)
        val balancedReference = rawReference?.let { applyGains(it, gains) }
        val referenceLab = balancedReference?.let(::rgbToLab)
        val referenceLuminance = balancedReference?.let(::luminance)

        val sites = quant.sites.map { base ->
            val balanced = applyGains(base.correctedMedianRgb, gains)
            val lab = rgbToLab(balanced)
            val deltaE = referenceLab?.let { deltaE2000(lab, it) }
            val opticalDensity = referenceLuminance?.let { referenceValue ->
                -log10(
                    (luminance(balanced) + EPSILON) /
                        (referenceValue + EPSILON)
                )
            }
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
            val primaryValue = primaryFeatureValue(
                feature = config.primaryFeature,
                balanced = balanced,
                deltaE2000 = deltaE,
                opticalDensity = opticalDensity
            )
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

    /**
     * 从统一白平衡后的RGB生成全部直接颜色特征。
     *
     * 96孔板和微流控都在进入本处理器前完成形状感知采样；这里不再关心圆孔或方块，
     * 只消费位点级RGB观测，因此两种载体能够真正共用同一套信号和标定引擎。
     */
    private fun primaryFeatureValue(
        feature: AnalysisPrimaryFeature,
        balanced: RgbPhotometry,
        deltaE2000: Double?,
        opticalDensity: Double?
    ): Double? {
        if (feature == AnalysisPrimaryFeature.DELTA_E_2000) return deltaE2000
        if (feature == AnalysisPrimaryFeature.OPTICAL_DENSITY) return opticalDensity
        val pixelType = SignalFeatureCatalog.pixelTypeForPrimaryFeature(feature) ?: return null
        val extraction = SignalFeatureV2Extractor.extract(
            listOf(
                RgbSignalSample(
                    red = balanced.red,
                    green = balanced.green,
                    blue = balanced.blue
                )
            )
        )
        return extraction.values[SignalFeatureCatalog.v2Code(pixelType)]?.takeIf(Double::isFinite)
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
