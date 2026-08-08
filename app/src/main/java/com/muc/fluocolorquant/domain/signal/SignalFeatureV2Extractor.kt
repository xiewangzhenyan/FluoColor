package com.muc.fluocolorquant.domain.signal

import com.muc.fluocolorquant.data.enums.PixelType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 一个不依赖 Android Bitmap 的 RGB 观测，便于 JVM 回归测试锁定处理器数学语义。 */
data class RgbSignalSample(
    val red: Double,
    val green: Double,
    val blue: Double
) {
    init {
        require(red in 0.0..255.0 && green in 0.0..255.0 && blue in 0.0..255.0) {
            "RGB 信号必须位于 0～255"
        }
    }
}

/** V2 未输出某一特征时的结构化原因，避免把无效值伪装成 0。 */
enum class SignalInvalidReason {
    NO_VALID_PIXEL,
    LOW_SATURATION,
    UNSTABLE_RATIO_DENOMINATOR,
    RATIO_OUT_OF_RANGE,
    NEAR_BLACK_CHROMATICITY,
    NON_FINITE_RESULT
}

data class SignalFeatureExtraction(
    val values: Map<String, Double>,
    val invalidReasons: Map<String, SignalInvalidReason>,
    val processorVersion: String = SignalFeatureCatalog.V2_PROCESSOR_VERSION
)

/**
 * 版本化像素信号 V2 纯计算核心。
 *
 * 与 Legacy 的主要区别：Hue 使用圆周均值并排除低饱和度像素；Lab 输出标准 CIE Lab
 * 数值；YCbCr 明确按 Y/Cb/Cr 保存；通道比率设置噪声下限与上限；所有非线性特征都
 * 明确采用“逐像素计算后聚合”或“通道均值后计算”，不再混用隐含语义。
 */
object SignalFeatureV2Extractor {
    private const val MIN_RATIO_DENOMINATOR = 5.0
    private const val MAX_STABLE_RATIO = 20.0
    private const val MIN_HUE_SATURATION = 0.05
    private const val MIN_HUE_VALID_FRACTION = 0.5
    private const val MIN_CIRCULAR_RESULTANT = 0.1
    private const val MIN_XYZ_SUM = 1e-6

    fun extract(samples: List<RgbSignalSample>): SignalFeatureExtraction {
        if (samples.isEmpty()) {
            return SignalFeatureExtraction(
                values = emptyMap(),
                invalidReasons = SignalFeatureCatalog.definitions.associate {
                    it.code to SignalInvalidReason.NO_VALID_PIXEL
                }
            )
        }

        val values = linkedMapOf<String, Double>()
        val invalid = linkedMapOf<String, SignalInvalidReason>()
        val averageRed = samples.map(RgbSignalSample::red).average()
        val averageGreen = samples.map(RgbSignalSample::green).average()
        val averageBlue = samples.map(RgbSignalSample::blue).average()

        put(values, invalid, PixelType.RED, averageRed)
        put(values, invalid, PixelType.GREEN, averageGreen)
        put(values, invalid, PixelType.BLUE, averageBlue)
        put(values, invalid, PixelType.GRAY_LUMINOSITY, samples.map(::grayLuminosity).average())
        put(values, invalid, PixelType.AVERAGE_RGB, samples.map { (it.red + it.green + it.blue) / 3.0 }.average())
        put(values, invalid, PixelType.EUCLIDEAN_NORM, samples.map { sqrt(it.red.pow(2) + it.green.pow(2) + it.blue.pow(2)) }.average())
        put(values, invalid, PixelType.INVERSE_RB_AVG, samples.map { ((255.0 - it.red) + (255.0 - it.blue)) / 2.0 }.average())
        put(values, invalid, PixelType.RB_DIFF, samples.map { it.red - it.blue + 255.0 }.average())

        putStableRatio(values, invalid, PixelType.RATIO_RG, averageRed, averageGreen)
        putStableRatio(values, invalid, PixelType.RATIO_RB, averageRed, averageBlue)
        putStableRatio(values, invalid, PixelType.RATIO_GB, averageGreen, averageBlue)

        val hsvSamples = samples.map(::rgbToHsv)
        put(values, invalid, PixelType.SATURATION_HSV, hsvSamples.map(Hsv::saturation).average())
        put(values, invalid, PixelType.VALUE_HSV, hsvSamples.map(Hsv::value).average())
        putCircularHue(values, invalid, hsvSamples)

        val hslSamples = samples.map(::rgbToHsl)
        put(values, invalid, PixelType.SATURATION_HSL, hslSamples.map(Hsl::saturation).average())
        put(values, invalid, PixelType.LIGHTNESS_HSL, hslSamples.map(Hsl::lightness).average())

        val xyzSamples = samples.map(::rgbToXyz)
        val averageX = xyzSamples.map(Xyz::x).average()
        val averageY = xyzSamples.map(Xyz::y).average()
        val averageZ = xyzSamples.map(Xyz::z).average()
        put(values, invalid, PixelType.CIE_X, averageX)
        put(values, invalid, PixelType.CIE_Y, averageY)
        put(values, invalid, PixelType.CIE_Z, averageZ)
        val xyzSum = averageX + averageY + averageZ
        if (xyzSum > MIN_XYZ_SUM) {
            put(values, invalid, PixelType.CIE_x, averageX / xyzSum)
            put(values, invalid, PixelType.CIE_y, averageY / xyzSum)
        } else {
            invalid[SignalFeatureCatalog.v2Code(PixelType.CIE_x)] = SignalInvalidReason.NEAR_BLACK_CHROMATICITY
            invalid[SignalFeatureCatalog.v2Code(PixelType.CIE_y)] = SignalInvalidReason.NEAR_BLACK_CHROMATICITY
        }

        val labSamples = xyzSamples.map(::xyzToLab)
        put(values, invalid, PixelType.CIE_L, labSamples.map(Lab::lightness).average())
        put(values, invalid, PixelType.CIE_a, labSamples.map(Lab::a).average())
        put(values, invalid, PixelType.CIE_b, labSamples.map(Lab::b).average())

        val yCbCrSamples = samples.map(::rgbToYCbCr)
        put(values, invalid, PixelType.YCBCR_Y, yCbCrSamples.map(YCbCr::y).average())
        put(values, invalid, PixelType.YCBCR_CB, yCbCrSamples.map(YCbCr::cb).average())
        put(values, invalid, PixelType.YCBCR_CR, yCbCrSamples.map(YCbCr::cr).average())

        val cmykSamples = samples.map(::rgbToCmyk)
        put(values, invalid, PixelType.CYAN, cmykSamples.map(Cmyk::cyan).average())
        put(values, invalid, PixelType.MAGENTA, cmykSamples.map(Cmyk::magenta).average())
        put(values, invalid, PixelType.YELLOW, cmykSamples.map(Cmyk::yellow).average())
        put(values, invalid, PixelType.BLACK, cmykSamples.map(Cmyk::black).average())

        return SignalFeatureExtraction(values = values, invalidReasons = invalid)
    }

    private fun put(
        values: MutableMap<String, Double>,
        invalid: MutableMap<String, SignalInvalidReason>,
        pixelType: PixelType,
        value: Double
    ) {
        val code = SignalFeatureCatalog.v2Code(pixelType)
        val definition = SignalFeatureCatalog.definitionFor(pixelType)
        if (value.isFinite() && definition.valueRange.contains(value)) {
            values[code] = value
        } else {
            invalid[code] = SignalInvalidReason.NON_FINITE_RESULT
        }
    }

    private fun putStableRatio(
        values: MutableMap<String, Double>,
        invalid: MutableMap<String, SignalInvalidReason>,
        pixelType: PixelType,
        numerator: Double,
        denominator: Double
    ) {
        val code = SignalFeatureCatalog.v2Code(pixelType)
        if (denominator < MIN_RATIO_DENOMINATOR) {
            invalid[code] = SignalInvalidReason.UNSTABLE_RATIO_DENOMINATOR
            return
        }
        val ratio = numerator / denominator
        if (!ratio.isFinite() || ratio > MAX_STABLE_RATIO) {
            invalid[code] = SignalInvalidReason.RATIO_OUT_OF_RANGE
            return
        }
        put(values, invalid, pixelType, ratio)
    }

    private fun putCircularHue(
        values: MutableMap<String, Double>,
        invalid: MutableMap<String, SignalInvalidReason>,
        hsvSamples: List<Hsv>
    ) {
        val code = SignalFeatureCatalog.v2Code(PixelType.HUE)
        val valid = hsvSamples.filter { it.saturation >= MIN_HUE_SATURATION }
        if (valid.size.toDouble() / hsvSamples.size < MIN_HUE_VALID_FRACTION) {
            invalid[code] = SignalInvalidReason.LOW_SATURATION
            return
        }

        val meanSin = valid.map { sin(it.hueDegrees * PI / 180.0) }.average()
        val meanCos = valid.map { cos(it.hueDegrees * PI / 180.0) }.average()
        val resultant = sqrt(meanSin * meanSin + meanCos * meanCos)
        if (resultant < MIN_CIRCULAR_RESULTANT) {
            invalid[code] = SignalInvalidReason.LOW_SATURATION
            return
        }
        val degrees = (atan2(meanSin, meanCos) * 180.0 / PI + 360.0) % 360.0
        put(values, invalid, PixelType.HUE, degrees)
    }

    private fun grayLuminosity(sample: RgbSignalSample): Double =
        0.299 * sample.red + 0.587 * sample.green + 0.114 * sample.blue

    private data class Hsv(val hueDegrees: Double, val saturation: Double, val value: Double)
    private data class Hsl(val saturation: Double, val lightness: Double)
    private data class Xyz(val x: Double, val y: Double, val z: Double)
    private data class Lab(val lightness: Double, val a: Double, val b: Double)
    private data class YCbCr(val y: Double, val cb: Double, val cr: Double)
    private data class Cmyk(val cyan: Double, val magenta: Double, val yellow: Double, val black: Double)

    private fun rgbToHsv(sample: RgbSignalSample): Hsv {
        val r = sample.red / 255.0
        val g = sample.green / 255.0
        val b = sample.blue / 255.0
        val maximum = maxOf(r, g, b)
        val minimum = minOf(r, g, b)
        val delta = maximum - minimum
        val hue = when {
            delta <= 1e-12 -> 0.0
            maximum == r -> (60.0 * ((g - b) / delta) + 360.0) % 360.0
            maximum == g -> 60.0 * ((b - r) / delta) + 120.0
            else -> 60.0 * ((r - g) / delta) + 240.0
        }
        val saturation = if (maximum <= 1e-12) 0.0 else delta / maximum
        return Hsv(hue, saturation, maximum)
    }

    private fun rgbToHsl(sample: RgbSignalSample): Hsl {
        val r = sample.red / 255.0
        val g = sample.green / 255.0
        val b = sample.blue / 255.0
        val maximum = maxOf(r, g, b)
        val minimum = minOf(r, g, b)
        val delta = maximum - minimum
        val lightness = (maximum + minimum) / 2.0
        val saturation = if (delta <= 1e-12) {
            0.0
        } else {
            delta / (1.0 - kotlin.math.abs(2.0 * lightness - 1.0))
        }
        return Hsl(saturation.coerceIn(0.0, 1.0), lightness)
    }

    /** 标准 sRGB（D65）去 gamma 后转换到 CIE XYZ，输出采用 0～100 标度。 */
    private fun rgbToXyz(sample: RgbSignalSample): Xyz {
        fun linearize(channel: Double): Double {
            val normalized = channel / 255.0
            return if (normalized <= 0.04045) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }

        val r = linearize(sample.red)
        val g = linearize(sample.green)
        val b = linearize(sample.blue)
        return Xyz(
            x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) * 100.0,
            y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) * 100.0,
            z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) * 100.0
        )
    }

    /** 将 D65 XYZ 转换为用户熟悉的标准 CIE Lab 显示语义。 */
    private fun xyzToLab(xyz: Xyz): Lab {
        fun transform(value: Double): Double {
            val delta = 6.0 / 29.0
            return if (value > delta.pow(3)) {
                value.pow(1.0 / 3.0)
            } else {
                value / (3.0 * delta.pow(2)) + 4.0 / 29.0
            }
        }

        val fx = transform(xyz.x / 95.047)
        val fy = transform(xyz.y / 100.0)
        val fz = transform(xyz.z / 108.883)
        return Lab(
            lightness = 116.0 * fy - 16.0,
            a = 500.0 * (fx - fy),
            b = 200.0 * (fy - fz)
        )
    }

    /** BT.601 全范围 YCbCr；字段顺序始终是 Y、Cb、Cr。 */
    private fun rgbToYCbCr(sample: RgbSignalSample): YCbCr = YCbCr(
        y = grayLuminosity(sample).coerceIn(0.0, 255.0),
        cb = (128.0 - 0.168736 * sample.red - 0.331264 * sample.green + 0.5 * sample.blue)
            .coerceIn(0.0, 255.0),
        cr = (128.0 + 0.5 * sample.red - 0.418688 * sample.green - 0.081312 * sample.blue)
            .coerceIn(0.0, 255.0)
    )

    private fun rgbToCmyk(sample: RgbSignalSample): Cmyk {
        val red = sample.red / 255.0
        val green = sample.green / 255.0
        val blue = sample.blue / 255.0
        val black = 1.0 - maxOf(red, green, blue)
        if (black >= 1.0 - 1e-12) return Cmyk(0.0, 0.0, 0.0, 1.0)
        val denominator = 1.0 - black
        return Cmyk(
            cyan = (1.0 - red - black) / denominator,
            magenta = (1.0 - green - black) / denominator,
            yellow = (1.0 - blue - black) / denominator,
            black = black
        )
    }
}
