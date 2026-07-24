package com.muc.fluocolorquant.domain.detection.photometry

import com.google.gson.annotations.SerializedName
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult

/** 旧圆形固定 ROI 采样器版本，仅用于未提供单元分割结果的兼容调用。 */
const val PG_QUANT_LEGACY_PROCESSOR_VERSION: String = "pg-quant-android-v1"

/** 紧致单元前景掩膜采样器版本；生产微流控主链从本版本开始保存。 */
const val PG_QUANT_PROCESSOR_VERSION: String = "pg-quant-android-v2-unit-mask"

/** 全局参考原始证据使用的基础光度处理器稳定名称。 */
const val PG_QUANT_PROCESSOR_NAME: String = "pg-quant"

/** 比色位点校正信号外层包装版本，保证后续结果页可按版本重建参考校正来源。 */
const val COLORIMETRIC_CORRECTED_SIGNAL_SCHEMA_VERSION: String =
    "colorimetric-corrected-signal-v1"

/** 全局空白/参考物理位点原始证据 JSON 的稳定结构版本。 */
const val COLORIMETRIC_REFERENCE_EVIDENCE_SCHEMA_VERSION: String =
    "colorimetric-reference-evidence-v1"

/** 无分析物全局参考证据在 SiteMeasurement 中使用的稳定主特征机器名。 */
const val COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE: String =
    "COLORIMETRIC_REFERENCE_EVIDENCE"

/**
 * 位点级光度质量标志。
 *
 * [LOW_SNR] 只说明信号未达到检出阈值，不属于图像或 ROI 的硬质量失败；这一区分对
 * 空白、低浓度样本和 LOD 附近数据尤其重要，不能为了热力图好看把有效低读数删除。
 */
enum class PhotometryFlag {
    @SerializedName("saturated")
    SATURATED,

    @SerializedName("under_exposed")
    UNDER_EXPOSED,

    @SerializedName("low_snr")
    LOW_SNR,

    @SerializedName("roi_out_of_bounds")
    ROI_OUT_OF_BOUNDS,

    @SerializedName("non_uniform")
    NON_UNIFORM,

    @SerializedName("background_anomaly")
    BACKGROUND_ANOMALY,

    @SerializedName("hot_pixel")
    HOT_PIXEL,

    @SerializedName("specular_highlight")
    SPECULAR_HIGHLIGHT
}

/**
 * PG-Quant 的可追溯参数快照。
 *
 * 比例均相对于局部网格 pitch，而不是固定像素；因此 10×10、15×15、自定义行列以及
 * 不同输出分辨率可共用同一科学定义。构造时严格验证 ROI 与背景环不会重叠。
 */
data class PgQuantConfig(
    val roiRadiusPitchRatio: Double = 0.18,
    val annulusInnerPitchRatio: Double = 0.30,
    val annulusOuterPitchRatio: Double = 0.44,
    val saturationLevel: Int = 250,
    val saturationRatioLimit: Double = 0.05,
    val underExposedMedianLevel: Double = 8.0,
    val snrMinimum: Double = 3.0,
    val borderClipRatioLimit: Double = 0.05,
    val contaminationRatioLimit: Double = 0.35,
    val backgroundAnomalyRobustZ: Double = 3.5
) {
    init {
        require(roiRadiusPitchRatio > 0.0) { "信号 ROI 半径比例必须大于 0" }
        require(annulusInnerPitchRatio > roiRadiusPitchRatio) { "背景环内径必须大于信号 ROI 半径" }
        require(annulusOuterPitchRatio > annulusInnerPitchRatio) { "背景环外径必须大于内径" }
        require(annulusOuterPitchRatio < 0.5) { "背景环外径必须小于半个 pitch，避免触及相邻位点" }
        require(saturationLevel in 1..255) { "饱和灰度阈值必须位于 1 到 255" }
        require(saturationRatioLimit in 0.0..1.0) { "饱和比例阈值必须位于 0 到 1" }
        require(underExposedMedianLevel >= 0.0) { "欠曝阈值不能为负数" }
        require(snrMinimum > 0.0) { "SNR 阈值必须大于 0" }
        require(borderClipRatioLimit in 0.0..1.0) { "边界裁切阈值必须位于 0 到 1" }
        require(contaminationRatioLimit in 0.0..1.0) { "污染比例阈值必须位于 0 到 1" }
        require(backgroundAnomalyRobustZ > 0.0) { "背景异常 robust z 阈值必须大于 0" }
    }
}

/**
 * 位点的两类独立质量结论。
 *
 * `signalDetectable=false` 仍可以是质量可靠的低读数；`qualityReliable=false` 表示成像、
 * ROI 或局部背景本身存在问题，应从重复组聚合中排除，但保留原始记录和原因。
 */
data class SitePhotometryQc(
    val flags: Set<PhotometryFlag>,
    val signalDetectable: Boolean,
    val qualityReliable: Boolean
) {
    companion object {
        private val reliabilityFailureFlags = setOf(
            PhotometryFlag.SATURATED,
            PhotometryFlag.UNDER_EXPOSED,
            PhotometryFlag.ROI_OUT_OF_BOUNDS,
            PhotometryFlag.NON_UNIFORM,
            PhotometryFlag.BACKGROUND_ANOMALY,
            PhotometryFlag.HOT_PIXEL,
            PhotometryFlag.SPECULAR_HIGHLIGHT
        )

        /** 从原始标志和 SNR 生成两个互不替代的质量结论。 */
        fun from(
            flags: Set<PhotometryFlag>,
            snr: Double,
            snrMinimum: Double
        ): SitePhotometryQc {
            require(snr.isFinite() && snr >= 0.0) { "SNR 必须是非负有限数值" }
            require(snrMinimum.isFinite() && snrMinimum > 0.0) { "SNR 阈值必须大于 0" }
            return SitePhotometryQc(
                flags = flags,
                signalDetectable = snr >= snrMinimum,
                qualityReliable = flags.none(reliabilityFailureFlags::contains)
            )
        }
    }
}

/** RGB 三通道科学统计，统一使用 0～255 的 R/G/B 顺序。 */
data class RgbPhotometry(
    val red: Double,
    val green: Double,
    val blue: Double
)

/**
 * 单个位点的模态无关基础光度结果。
 *
 * 所有信号直接从原始定量图采样；`rectifiedCenter` 只描述规则晶格坐标，
 * `originalCenter` 用于原图叠加和追溯。比色/荧光处理器不得修改这些原始字段。
 */
data class BaseSitePhotometry(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val rectifiedCenter: GridPoint,
    val originalCenter: GridPoint,
    val roiMedianRgb: RgbPhotometry,
    val roiMedianGray: Double,
    val backgroundMedianRgb: RgbPhotometry,
    val backgroundMedianGray: Double,
    val backgroundSigmaRgb: RgbPhotometry,
    val backgroundSigmaGray: Double,
    val correctedMedianRgb: RgbPhotometry,
    val correctedMedianGray: Double,
    val signalGray: Double,
    val signalRatio: Double,
    val correctedSignalGray: Double,
    val integratedSignalRgb: RgbPhotometry,
    val integratedSignalGray: Double,
    val signalToNoiseRatio: Double,
    val saturationRatio: Double,
    val roiContaminationRatio: Double,
    val hotPixelRatio: Double,
    val roiClipRatio: Double,
    val annulusClipRatio: Double,
    val qc: SitePhotometryQc
)

/** 一次全阵列 PG-Quant 结果及平场诊断。 */
data class PgQuantResult(
    val processorVersion: String = PG_QUANT_PROCESSOR_VERSION,
    val rows: Int,
    val columns: Int,
    val pitchPx: Double,
    val roiRadiusPx: Double,
    val annulusInnerPx: Double,
    val annulusOuterPx: Double,
    val illuminationModel: String,
    val illuminationUniformity: Double,
    val config: PgQuantConfig,
    val sites: List<BaseSitePhotometry>,
    /**
     * 本次光度实际使用的单元区域；旧历史/测试可为空并继续解释为 v1 圆形 ROI。
     * 该对象只在当前检测会话中用于预览和处理证据，不写入逐位点科学结果 JSON。
     */
    val unitSegmentation: ArrayUnitSegmentationResult? = null
) {
    fun requireValid(): PgQuantResult = apply {
        require(rows > 0 && columns > 0) { "PG-Quant 行列必须大于 0" }
        require(sites.size == rows * columns) { "PG-Quant 位点数必须等于 rows × columns" }
        require(pitchPx > 0.0) { "PG-Quant pitch 必须大于 0" }
        require(roiRadiusPx > 0.0 && annulusInnerPx > roiRadiusPx && annulusOuterPx > annulusInnerPx) {
            "PG-Quant ROI/背景环像素半径无效"
        }
        require(illuminationModel.isNotBlank()) { "平场模型名称不能为空" }
        require(illuminationUniformity in 0.0..1.0) { "光照均匀度必须位于 0 到 1" }
        unitSegmentation?.let { segmentation ->
            segmentation.requireValid()
            require(segmentation.rows == rows && segmentation.columns == columns) {
                "单元分割规格必须与 PG-Quant 一致"
            }
        }
        sites.forEachIndexed { index, site ->
            require(site.siteIndex == index) { "PG-Quant 位点必须按行优先连续排列" }
            require(site.rowIndex == index / columns && site.columnIndex == index % columns) {
                "PG-Quant 位点行列与索引不一致"
            }
        }
    }
}
