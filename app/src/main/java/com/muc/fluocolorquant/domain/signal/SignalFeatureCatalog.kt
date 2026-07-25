package com.muc.fluocolorquant.domain.signal

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.PixelType

/**
 * 信号特征在普通界面中的推荐等级。
 *
 * 这些等级只决定默认候选池和设置页分组，不会删除任何旧 [PixelType]。旧项目仍可通过
 * [SignalFeatureTier.LEGACY] 或 [SignalFeatureTier.EXPERIMENTAL] 特征完整复现。
 */
enum class SignalFeatureTier {
    RECOMMENDED,
    EXTENDED,
    LEGACY,
    EXPERIMENTAL
}

/** 明确记录非线性特征如何从单元内像素聚合，防止处理器升级后语义悄然变化。 */
enum class SignalAggregationMethod {
    CHANNEL_MEAN,
    CHANNEL_MEAN_THEN_TRANSFORM,
    PER_PIXEL_THEN_MEAN,
    CIRCULAR_MEAN
}

/**
 * 空白孔对某个特征的校正规则。
 *
 * 只有具有加性强度语义的信号才能直接做“样本值－空白值”。Hue、Lab、通道比率等特征
 * 若直接相减会破坏物理含义，因此 V2 明确保持原值。更高级的逐像素背景校正应由生产
 * 光度处理器在 RGB 前景层完成，而不是在拟合页面对派生特征做盲目相减。
 */
enum class SignalBlankCorrection {
    SUBTRACT_SIGNAL,
    KEEP_EXTRACTED_VALUE
}

/** 可供输入校验、图表范围和测试共同使用的信号理论范围。 */
data class SignalFeatureRange(
    val minimum: Double? = null,
    val maximum: Double? = null
) {
    fun contains(value: Double): Boolean {
        if (!value.isFinite()) return false
        if (minimum != null && value < minimum) return false
        if (maximum != null && value > maximum) return false
        return true
    }
}

/**
 * 一个稳定、可持久化的信号特征定义。
 *
 * [code] 是新曲线真正冻结的科学标识；[legacyPixelType] 仅用于旧 UI 显示和历史枚举兼容。
 * 因此即使以后改进算法，也必须新增处理器版本或新编码，不能覆盖既有编码的数学语义。
 */
data class SignalFeatureDefinition(
    val code: String,
    val legacyPixelType: PixelType,
    val supportedModalities: Set<DetectionModality>,
    val tier: SignalFeatureTier,
    val aggregationMethod: SignalAggregationMethod,
    val blankCorrection: SignalBlankCorrection,
    val valueRange: SignalFeatureRange,
    val processorVersion: String
)

/**
 * 旧 96 孔板与新阵列现场标定共享的静态信号特征目录。
 *
 * 这里刻意不做动态插件系统：当前应用只需要一份可审计的固定注册表。所有旧枚举都保留，
 * 新曲线统一使用带 `pixel.v2.` 前缀的稳定编码，从而同时满足向后兼容和未来升级。
 */
object SignalFeatureCatalog {
    const val LEGACY_PROCESSOR_VERSION: String = "pixel-feature-legacy-v1"
    const val V2_PROCESSOR_VERSION: String = "pixel-feature-v2"

    private val arrayModalities = setOf(
        DetectionModality.COLORIMETRIC,
        DetectionModality.FLUORESCENCE
    )

    private fun definition(
        pixelType: PixelType,
        tier: SignalFeatureTier,
        aggregationMethod: SignalAggregationMethod,
        range: SignalFeatureRange,
        blankCorrection: SignalBlankCorrection = SignalBlankCorrection.KEEP_EXTRACTED_VALUE
    ): SignalFeatureDefinition = SignalFeatureDefinition(
        code = v2Code(pixelType),
        legacyPixelType = pixelType,
        supportedModalities = arrayModalities,
        tier = tier,
        aggregationMethod = aggregationMethod,
        blankCorrection = blankCorrection,
        valueRange = range,
        processorVersion = V2_PROCESSOR_VERSION
    )

    /** 所有 V2 定义；顺序同时作为高级设置页的稳定显示顺序。 */
    val definitions: List<SignalFeatureDefinition> = listOf(
        definition(PixelType.GRAY_LUMINOSITY, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.AVERAGE_RGB, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.RED, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.CHANNEL_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.GREEN, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.CHANNEL_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.BLUE, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.CHANNEL_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.CIE_L, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 100.0)),
        definition(PixelType.CIE_a, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(-128.0, 127.0)),
        definition(PixelType.CIE_b, SignalFeatureTier.RECOMMENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(-128.0, 127.0)),
        definition(PixelType.EUCLIDEAN_NORM, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 441.673)),
        definition(PixelType.RATIO_RG, SignalFeatureTier.EXTENDED, SignalAggregationMethod.CHANNEL_MEAN_THEN_TRANSFORM, SignalFeatureRange(0.0, 20.0)),
        definition(PixelType.RATIO_RB, SignalFeatureTier.EXTENDED, SignalAggregationMethod.CHANNEL_MEAN_THEN_TRANSFORM, SignalFeatureRange(0.0, 20.0)),
        definition(PixelType.RATIO_GB, SignalFeatureTier.EXTENDED, SignalAggregationMethod.CHANNEL_MEAN_THEN_TRANSFORM, SignalFeatureRange(0.0, 20.0)),
        definition(PixelType.HUE, SignalFeatureTier.EXTENDED, SignalAggregationMethod.CIRCULAR_MEAN, SignalFeatureRange(0.0, 360.0)),
        definition(PixelType.SATURATION_HSV, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.VALUE_HSV, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.SATURATION_HSL, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.LIGHTNESS_HSL, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.CIE_x, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.CIE_y, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.YCBCR_Y, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0), SignalBlankCorrection.SUBTRACT_SIGNAL),
        definition(PixelType.YCBCR_CB, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0)),
        definition(PixelType.YCBCR_CR, SignalFeatureTier.EXTENDED, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0)),
        definition(PixelType.INVERSE_RB_AVG, SignalFeatureTier.LEGACY, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 255.0)),
        definition(PixelType.RB_DIFF, SignalFeatureTier.LEGACY, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 510.0)),
        definition(PixelType.CIE_X, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 110.0)),
        definition(PixelType.CIE_Y, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 110.0)),
        definition(PixelType.CIE_Z, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 110.0)),
        definition(PixelType.CYAN, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.MAGENTA, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.YELLOW, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0)),
        definition(PixelType.BLACK, SignalFeatureTier.EXPERIMENTAL, SignalAggregationMethod.PER_PIXEL_THEN_MEAN, SignalFeatureRange(0.0, 1.0))
    )

    private val byCode = definitions.associateBy(SignalFeatureDefinition::code)
    private val byPixelType = definitions.associateBy(SignalFeatureDefinition::legacyPixelType)

    /**
     * 新阵列主特征与既有 PixelType 的显式映射。
     *
     * 这里是96孔板和微流控共享颜色特征的唯一映射表。禁止在页面、协调器和设置页分别
     * 维护另一套映射，否则旧曲线兼容和新现场拟合很容易再次出现同名不同义。
     */
    private val primaryFeatureToPixelType: Map<AnalysisPrimaryFeature, PixelType> = linkedMapOf(
        AnalysisPrimaryFeature.GRAY_LUMINOSITY to PixelType.GRAY_LUMINOSITY,
        AnalysisPrimaryFeature.RED_INTENSITY to PixelType.RED,
        AnalysisPrimaryFeature.GREEN_INTENSITY to PixelType.GREEN,
        AnalysisPrimaryFeature.BLUE_INTENSITY to PixelType.BLUE,
        AnalysisPrimaryFeature.AVERAGE_RGB to PixelType.AVERAGE_RGB,
        AnalysisPrimaryFeature.EUCLIDEAN_RGB_NORM to PixelType.EUCLIDEAN_NORM,
        AnalysisPrimaryFeature.INVERSE_RB_AVERAGE to PixelType.INVERSE_RB_AVG,
        AnalysisPrimaryFeature.RED_BLUE_DIFFERENCE to PixelType.RB_DIFF,
        AnalysisPrimaryFeature.RED_GREEN_RATIO to PixelType.RATIO_RG,
        AnalysisPrimaryFeature.RED_BLUE_RATIO to PixelType.RATIO_RB,
        AnalysisPrimaryFeature.GREEN_BLUE_RATIO to PixelType.RATIO_GB,
        AnalysisPrimaryFeature.HSV_HUE to PixelType.HUE,
        AnalysisPrimaryFeature.HSV_SATURATION to PixelType.SATURATION_HSV,
        AnalysisPrimaryFeature.HSV_VALUE to PixelType.VALUE_HSV,
        AnalysisPrimaryFeature.HSL_SATURATION to PixelType.SATURATION_HSL,
        AnalysisPrimaryFeature.HSL_LIGHTNESS to PixelType.LIGHTNESS_HSL,
        AnalysisPrimaryFeature.CIE_X_TRISTIMULUS to PixelType.CIE_X,
        AnalysisPrimaryFeature.CIE_Y_TRISTIMULUS to PixelType.CIE_Y,
        AnalysisPrimaryFeature.CIE_Z_TRISTIMULUS to PixelType.CIE_Z,
        AnalysisPrimaryFeature.CIE_X_CHROMATICITY to PixelType.CIE_x,
        AnalysisPrimaryFeature.CIE_Y_CHROMATICITY to PixelType.CIE_y,
        AnalysisPrimaryFeature.CIE_L_STAR to PixelType.CIE_L,
        AnalysisPrimaryFeature.CIE_A_STAR to PixelType.CIE_a,
        AnalysisPrimaryFeature.CIE_B_STAR to PixelType.CIE_b,
        AnalysisPrimaryFeature.YCBCR_Y to PixelType.YCBCR_Y,
        AnalysisPrimaryFeature.YCBCR_CB to PixelType.YCBCR_CB,
        AnalysisPrimaryFeature.YCBCR_CR to PixelType.YCBCR_CR,
        AnalysisPrimaryFeature.CMYK_CYAN to PixelType.CYAN,
        AnalysisPrimaryFeature.CMYK_MAGENTA to PixelType.MAGENTA,
        AnalysisPrimaryFeature.CMYK_YELLOW to PixelType.YELLOW,
        AnalysisPrimaryFeature.CMYK_BLACK to PixelType.BLACK
    )
    private val pixelTypeToPrimaryFeature = primaryFeatureToPixelType.entries.associate {
        (feature, pixelType) -> pixelType to feature
    }

    /** 为新的稳定处理器生成持久化编码。 */
    fun v2Code(pixelType: PixelType): String = "pixel.v2.${pixelType.identifier}"

    fun definitionFor(pixelType: PixelType): SignalFeatureDefinition =
        requireNotNull(byPixelType[pixelType]) { "缺少像素特征定义: ${pixelType.name}" }

    fun definitionForCode(code: String?): SignalFeatureDefinition? = code?.let(byCode::get)

    /** 返回阵列主特征对应的版本化像素定义；ΔE、光密度和荧光信号没有 PixelType。 */
    fun definitionForPrimaryFeature(
        feature: AnalysisPrimaryFeature
    ): SignalFeatureDefinition? = primaryFeatureToPixelType[feature]?.let(::definitionFor)

    fun pixelTypeForPrimaryFeature(feature: AnalysisPrimaryFeature): PixelType? =
        primaryFeatureToPixelType[feature]

    fun primaryFeatureForPixelType(pixelType: PixelType): AnalysisPrimaryFeature? =
        pixelTypeToPrimaryFeature[pixelType]

    /**
     * 从像素 JSON Map 读取冻结曲线要求的信号。
     *
     * 一旦曲线保存了 [signalFeatureCode]，就只读取该精确编码；若该值因低饱和度或低分母
     * 被判无效，不允许静默回退 Legacy 值。只有旧曲线没有版本字段时才读取历史键。
     */
    fun resolveValue(
        values: Map<String, Double>,
        signalFeatureCode: String?,
        legacyPixelType: PixelType
    ): Double? {
        if (!signalFeatureCode.isNullOrBlank()) {
            return values[signalFeatureCode]?.takeIf(Double::isFinite)
        }
        return (values[legacyPixelType.identifier] ?: values[legacyPixelType.name])
            ?.takeIf(Double::isFinite)
    }

    /** 对 V2 特征执行受控空白扣除；Legacy 键由旧流程继续维持原行为。 */
    fun applyV2BlankCorrection(
        values: Map<String, Double>,
        blankValues: Map<String, Double>
    ): Map<String, Double> = values.mapValues { (code, value) ->
        val definition = definitionForCode(code)
        if (definition?.blankCorrection == SignalBlankCorrection.SUBTRACT_SIGNAL) {
            value - (blankValues[code] ?: 0.0)
        } else {
            value
        }
    }
}
