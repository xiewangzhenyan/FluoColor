package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
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
            primaryFeature in setOf(
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
                AnalysisPrimaryFeature.FLUORESCENCE_SNR
            )
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
    val primaryFeatureValue: Double,
    val qc: SitePhotometryQc
)

data class FluorescencePhotometryResult(
    val processorVersion: String,
    val channel: FluorescenceChannel,
    val sites: List<FluorescenceSitePhotometry>
)

/**
 * 荧光专用光度处理器。
 *
 * 净强度来自 ROI 中位数减局部背景环中位数，积分强度来自 ROI 全部像素的背景扣除和；
 * SNR 使用同一通道背景 MAD。这里没有固定黑电平、任意绿色通道放大或逐位点颜色
 * 归一化，显示增强图也不会进入本处理器。
 */
object FluorescencePhotometryProcessor {

    fun process(
        quant: PgQuantResult,
        config: FluorescenceProcessorConfig
    ): FluorescencePhotometryResult {
        quant.requireValid()
        val sites = quant.sites.map { base ->
            val rawRoi = channelValue(base.roiMedianRgb, base.roiMedianGray, config.channel)
            val correctedRoi = channelValue(
                base.correctedMedianRgb,
                base.correctedMedianGray,
                config.channel
            )
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
            val flatFieldScale = if (abs(rawRoi) <= EPSILON) 1.0 else correctedRoi / rawRoi
            val netIntensity = (rawRoi - background) * flatFieldScale
            val integratedIntensity = rawIntegrated * flatFieldScale
            val snr = (abs(netIntensity) /
                (abs(backgroundSigma * flatFieldScale) + EPSILON)).coerceAtMost(MAXIMUM_SNR)
            val flags = buildSet {
                addAll(base.qc.flags)
                if (base.hotPixelRatio > config.hotPixelRatioLimit) add(PhotometryFlag.HOT_PIXEL)
            }
            val qc = SitePhotometryQc.from(flags, snr, config.snrMinimum)
            val primaryValue = when (config.primaryFeature) {
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY -> netIntensity
                AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY -> integratedIntensity
                AnalysisPrimaryFeature.FLUORESCENCE_SNR -> snr
                else -> error("构造器已经阻止不兼容的荧光主特征")
            }
            FluorescenceSitePhotometry(
                base = base,
                channel = config.channel,
                netIntensity = netIntensity,
                integratedIntensity = integratedIntensity,
                signalToNoiseRatio = snr,
                primaryFeature = config.primaryFeature,
                primaryFeatureValue = primaryValue,
                qc = qc
            )
        }
        return FluorescencePhotometryResult(
            processorVersion = FLUORESCENCE_PROCESSOR_VERSION,
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

    private const val MAXIMUM_SNR: Double = 9999.0
    private const val EPSILON: Double = 1e-6
}
