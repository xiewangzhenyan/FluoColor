package com.muc.fluocolorquant.domain.signal

import com.muc.fluocolorquant.data.enums.DetectionModality
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

    /** 为新的稳定处理器生成持久化编码。 */
    fun v2Code(pixelType: PixelType): String = "pixel.v2.${pixelType.identifier}"

    fun definitionFor(pixelType: PixelType): SignalFeatureDefinition =
        requireNotNull(byPixelType[pixelType]) { "缺少像素特征定义: ${pixelType.name}" }

    fun definitionForCode(code: String?): SignalFeatureDefinition? = code?.let(byCode::get)

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
