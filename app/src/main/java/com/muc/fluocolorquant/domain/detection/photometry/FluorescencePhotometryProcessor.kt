package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy
import com.muc.fluocolorquant.domain.signal.RgbSignalSample
import com.muc.fluocolorquant.domain.signal.SignalFeatureCatalog
import com.muc.fluocolorquant.domain.signal.SignalFeatureV2Extractor
import kotlin.math.abs

/** 分析模型兼容契约中使用的荧光处理器稳定名称与版本。 */
const val FLUORESCENCE_PROCESSOR_NAME: String = "fluorescence-photometry"
const val FLUORESCENCE_PROCESSOR_VERSION: String = "v1"

/** 荧光读出通道由实验模板/采集设备档案锁定，不在结果页临时切换科学语义。 */
enum class FluorescenceChannel {
    RED,
    GREEN,
    BLUE,
    GRAY
}

/** 荧光专用处理参数。 */
data class FluorescenceProcessorConfig(
    val channel: FluorescenceChannel,
    val primaryFeature: AnalysisPrimaryFeature,
    val snrMinimum: Double = 3.0,
    val hotPixelRatioLimit: Double = 0.005
) {
    init {
        require(
            AnalysisFeaturePolicy.isCompatible(DetectionModality.FLUORESCENCE, primaryFeature)
        ) { "荧光处理器收到不兼容的主特征" }
        require(snrMinimum > 0.0) { "荧光 SNR 阈值必须大于 0" }
        require(hotPixelRatioLimit in 0.0..1.0) { "热点比例阈值必须位于 0 到 1" }
    }
}

/** 单个位点的荧光专用输出。 */
data class FluorescenceSitePhotometry(
    val base: BaseSitePhotometry,
    val channel: FluorescenceChannel,
    val netIntensity: Double,
    val integratedIntensity: Double,
    val signalToNoiseRatio: Double,
    val primaryFeature: AnalysisPrimaryFeature,
    /** 低分母通道比率等数学上不稳定的颜色特征明确为空，禁止伪造为0。 */
    val primaryFeatureValue: Double?,
    val qc: SitePhotometryQc,
    /** 有向信噪比（保留正负号）：负值表示孔内强度低于局部背景，仅供污染/气泡溯源，不触发检出。 */
    val directedSnr: Double = signalToNoiseRatio
)

data class FluorescencePhotometryResult(
    val processorVersion: String,
    val channel: FluorescenceChannel,
    val sites: List<FluorescenceSitePhotometry>
)

/**
 * 荧光专用光度处理器。
 *
 * 借鉴 GenePix Pro 的荧光背景扣除与有向检出语义：
 * 1. 暗场荧光图像中孔间低灰度像素属于 CMOS 黑电平、暗电流与杂散漏光（加性偏置），并非入射激发光强
 *    增益，因此净强度与积分强度直接执行干净的加性背景扣除，禁止乘上由近零暗背景拟合的除法平场系数；
 * 2. 信噪比采用有向定义 $(F - B) / \sigma_{\text{eff}}$，发射方向 SNR 截断非负用于 `signalDetectable`
 *    门控，避免 `F < B`（孔内暗气泡或背景受邻孔溢光污染）因 `abs()` 误判为可检出；
 * 3. 分母强制执行 8-bit 量化底噪保护（0.5 LSB），消除整数同值 `MAD = 0` 导致的 `9999.0` 爆表。
 */
object FluorescencePhotometryProcessor {

    /**
     * 生成绑定孔内取值口径与荧光通道身份的处理器版本号。
     *
     * 默认绿色通道（[FluorescenceChannel.GREEN]）保持无通道后缀（如 `v1`），保证既有标准曲线、
     * 实验模板与自动化测试 100% 兼容；非绿色通道（`RED / BLUE / GRAY`）显式追加 `-ch-{CHANNEL}`，
     * 防止跨发射通道误套用标准曲线。
     */
    fun processorVersionFor(
        quantConfig: PgQuantConfig,
        channel: FluorescenceChannel = FluorescenceChannel.GREEN
    ): String {
        val baseVersion = quantConfig.versionFor(FLUORESCENCE_PROCESSOR_VERSION)
        return if (channel == FluorescenceChannel.GREEN) {
            baseVersion
        } else {
            "$baseVersion-ch-${channel.name}"
        }
    }

    fun process(
        quant: PgQuantResult,
        config: FluorescenceProcessorConfig
    ): FluorescencePhotometryResult {
        quant.requireValid()
        val sites = quant.sites.map { base ->
            val rawRoi = channelValue(base.foregroundRgb, base.foregroundGray, config.channel)
            val background = channelValue(
                base.backgroundMedianRgb,
                base.backgroundMedianGray,
                config.channel
            )
            val backgroundSigma = channelValue(
                base.backgroundSigmaRgb,
                base.backgroundSigmaGray,
                config.channel
            )
            val rawIntegrated = channelValue(
                base.integratedSignalRgb,
                base.integratedSignalGray,
                config.channel
            )
            val netIntensity = rawRoi - background
            val integratedIntensity = rawIntegrated
            val effectiveSigma = maxOf(abs(backgroundSigma), QUANTIZATION_NOISE_FLOOR_8BIT)
            val rawDirectedSnr = (netIntensity / (effectiveSigma + EPSILON))
                .coerceIn(-MAXIMUM_SNR, MAXIMUM_SNR)
            // 暗场发射荧光（背景灰度 < 45）严格要求正向发光（F > B），防止暗气泡或背景溢光伪造高 SNR；
            // 亮基底猝灭/背光暗单元芯片（背景灰度 >= 45）保留双侧猝灭幅度以兼容暗斑荧光猝灭实验。
            val isDarkFieldEmission = base.backgroundMedianGray < DARK_FIELD_BACKGROUND_CEILING
            val snr = if (isDarkFieldEmission) {
                rawDirectedSnr.coerceAtLeast(0.0)
            } else {
                abs(rawDirectedSnr)
            }
            val flags = buildSet {
                addAll(base.qc.flags)
                // 通道级荧光 SNR 替代基础灰度的 LOW_SNR 判定，避免蓝/红通道发光正常却因灰度权重低残留假低信噪比。
                remove(PhotometryFlag.LOW_SNR)
                if (snr < config.snrMinimum) add(PhotometryFlag.LOW_SNR)
                if (isDarkFieldEmission && rawDirectedSnr <= -config.snrMinimum) {
                    add(PhotometryFlag.NEGATIVE_NET_SIGNAL)
                }
                if (base.hotPixelRatio > config.hotPixelRatioLimit) add(PhotometryFlag.HOT_PIXEL)
            }
            val qc = SitePhotometryQc.from(
                flags = flags,
                snr = snr,
                snrMinimum = config.snrMinimum,
                hardFailure = !base.qc.qualityReliable ||
                    base.saturationRatio >= quant.config.severeSaturationRatioLimit ||
                    base.roiClipRatio >= quant.config.severeBorderClipRatioLimit ||
                    base.annulusClipRatio >= quant.config.severeBorderClipRatioLimit
            )
            val primaryValue = when (config.primaryFeature) {
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY -> netIntensity
                AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY -> integratedIntensity
                AnalysisPrimaryFeature.FLUORESCENCE_SNR -> snr
                else -> directColorFeatureValue(
                    feature = config.primaryFeature,
                    correctedRgb = base.foregroundRgb
                )
            }
            FluorescenceSitePhotometry(
                base = base,
                channel = config.channel,
                netIntensity = netIntensity,
                integratedIntensity = integratedIntensity,
                signalToNoiseRatio = snr,
                primaryFeature = config.primaryFeature,
                primaryFeatureValue = primaryValue.takeIf { base.readoutAvailable },
                qc = qc,
                directedSnr = rawDirectedSnr
            )
        }
        return FluorescencePhotometryResult(
            processorVersion = processorVersionFor(quant.config, config.channel),
            channel = config.channel,
            sites = sites
        )
    }

    private fun channelValue(
        rgb: RgbPhotometry,
        gray: Double,
        channel: FluorescenceChannel
    ): Double {
        return when (channel) {
            FluorescenceChannel.RED -> rgb.red
            FluorescenceChannel.GREEN -> rgb.green
            FluorescenceChannel.BLUE -> rgb.blue
            FluorescenceChannel.GRAY -> gray
        }
    }

    /**
     * 从平场校正后的 ROI RGB 生成荧光颜色候选。
     *
     * 平场模型可能在图像边缘把通道值轻微放大到 255 以上，而 RGB/Lab 的定义域仍是
     * 8 位 sRGB。这里只对有界颜色描述量夹到传感器范围；净强度和积分强度保持原始
     * 背景扣除数值，不会因为颜色候选的显示域而被截断。
     */
    private fun directColorFeatureValue(
        feature: AnalysisPrimaryFeature,
        correctedRgb: RgbPhotometry
    ): Double? {
        val pixelType = SignalFeatureCatalog.pixelTypeForPrimaryFeature(feature) ?: return null
        val extraction = SignalFeatureV2Extractor.extract(
            listOf(
                RgbSignalSample(
                    red = correctedRgb.red.coerceIn(0.0, 255.0),
                    green = correctedRgb.green.coerceIn(0.0, 255.0),
                    blue = correctedRgb.blue.coerceIn(0.0, 255.0)
                )
            )
        )
        return extraction.values[SignalFeatureCatalog.v2Code(pixelType)]?.takeIf(Double::isFinite)
    }

    private const val DARK_FIELD_BACKGROUND_CEILING: Double = 45.0
    private const val QUANTIZATION_NOISE_FLOOR_8BIT: Double = 0.5
    private const val MAXIMUM_SNR: Double = 9999.0
    private const val EPSILON: Double = 1e-6
}
