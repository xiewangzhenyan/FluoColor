package com.muc.fluocolorquant.domain.detection.photometry

import com.google.gson.annotations.SerializedName
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult
import kotlin.math.roundToInt

/** 旧圆形固定 ROI 采样器版本，仅用于未提供单元分割结果的兼容调用。 */
const val PG_QUANT_LEGACY_PROCESSOR_VERSION: String = "pg-quant-android-v1"

/** 紧致单元前景掩膜与 GenePix 隔离背景环/交叉质控采样器版本；生产主链从本版本开始保存。 */
const val PG_QUANT_PROCESSOR_VERSION: String = "pg-quant-android-v3-genepix-qc"

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

    @SerializedName("readout_unavailable")
    READOUT_UNAVAILABLE,

    @SerializedName("non_uniform")
    NON_UNIFORM,

    @SerializedName("background_anomaly")
    BACKGROUND_ANOMALY,

    @SerializedName("hot_pixel")
    HOT_PIXEL,

    @SerializedName("specular_highlight")
    SPECULAR_HIGHLIGHT,

    @SerializedName("segmentation_anomaly")
    SEGMENTATION_ANOMALY,

    @SerializedName("negative_net_signal")
    NEGATIVE_NET_SIGNAL,

    @SerializedName("background_insufficient")
    BACKGROUND_INSUFFICIENT,

    @SerializedName("manual_bad")
    MANUAL_BAD
}

/** 取值只改变科学采样区域和统计，不改变定位掩膜或深度模型的图像裁切。 */
enum class WellReadoutMode {
    FULL_MEDIAN,
    FULL_MEAN,
    CENTER_MEDIAN,
    CENTER_MEAN,
    FIVE_REGION_MEAN,
    CENTER_PIXEL
}

/** 固定五区依次为中心、上、下、左、右。保留原始窗口值，不只保留平均后的一个数。 */
data class WellRegionReadout(val pixelCount: Int, val rgb: RgbPhotometry, val gray: Double)

data class WellReadoutEvidence(
    val mode: WellReadoutMode,
    val raw: WellRegionReadout,
    val correctedRgb: RgbPhotometry,
    val correctedGray: Double,
    val subregions: List<WellRegionReadout> = emptyList()
)

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
    /** 少量饱和只提示复核，达到该比例才认为信号主体已经无法可靠恢复。 */
    val severeSaturationRatioLimit: Double = 0.25,
    val underExposedMedianLevel: Double = 8.0,
    val snrMinimum: Double = 3.0,
    val borderClipRatioLimit: Double = 0.05,
    /** 轻度边界裁切保留结果，ROI 或背景环丢失达到该比例才判为硬失败。 */
    val severeBorderClipRatioLimit: Double = 0.20,
    val contaminationRatioLimit: Double = 0.35,
    val backgroundAnomalyRobustZ: Double = 3.5,
    val readoutMode: WellReadoutMode = WellReadoutMode.FULL_MEDIAN,
    val centerScale: Double = 0.80,
    /** 真实单元外边界与局部背景环之间的半影隔离谷区比例（相对 pitch），防止采入弯月面和腔壁晕圈。 */
    val valleyPitchRatio: Double = 0.06
) {
    /** 中心区滑块只允许整百分比；版本来自实际参数，旧全孔中位数原样保留。 */
    fun versionFor(base: String): String = when (readoutMode) {
        WellReadoutMode.FULL_MEDIAN -> base
        WellReadoutMode.CENTER_MEDIAN,
        WellReadoutMode.CENTER_MEAN -> "$base-readout1-${readoutMode.name}-${(centerScale * 100).roundToInt()}"
        else -> "$base-readout1-${readoutMode.name}"
    }

    init {
        require(centerScale in 0.50..0.90) { "中心区线性比例必须位于 50% 到 90%" }
        require(kotlin.math.abs(centerScale * 100 - (centerScale * 100).roundToInt()) < 1e-8) {
            "中心区范围必须是整百分比"
        }
        require(valleyPitchRatio in 0.0..0.20) { "背景隔离谷区比例必须位于 0 到 20%" }
        require(roiRadiusPitchRatio > 0.0) { "信号 ROI 半径比例必须大于 0" }
        require(annulusInnerPitchRatio > roiRadiusPitchRatio) { "背景环内径必须大于信号 ROI 半径" }
        require(annulusOuterPitchRatio > annulusInnerPitchRatio) { "背景环外径必须大于内径" }
        require(annulusOuterPitchRatio < 0.5) { "背景环外径必须小于半个 pitch，避免触及相邻位点" }
        require(saturationLevel in 1..255) { "饱和灰度阈值必须位于 1 到 255" }
        require(saturationRatioLimit in 0.0..1.0) { "饱和比例阈值必须位于 0 到 1" }
        require(severeSaturationRatioLimit in saturationRatioLimit..1.0) {
            "严重饱和阈值不能低于普通饱和提示阈值"
        }
        require(underExposedMedianLevel >= 0.0) { "欠曝阈值不能为负数" }
        require(snrMinimum > 0.0) { "SNR 阈值必须大于 0" }
        require(borderClipRatioLimit in 0.0..1.0) { "边界裁切阈值必须位于 0 到 1" }
        require(severeBorderClipRatioLimit in borderClipRatioLimit..1.0) {
            "严重边界裁切阈值不能低于普通裁切提示阈值"
        }
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
        /**
         * 从原始标志和 SNR 生成两个互不替代的质量结论。
         *
         * flags 保存全部复核证据；[hardFailure] 只由具备比例信息的采样器判定严重饱和、
         * 严重裁切或空 ROI。这样热点、背景不均、轻度高光不会再把有效浓度一票否决。
         */
        fun from(
            flags: Set<PhotometryFlag>,
            snr: Double,
            snrMinimum: Double,
            hardFailure: Boolean = false
        ): SitePhotometryQc {
            require(snr.isFinite() && snr >= 0.0) { "SNR 必须是非负有限数值" }
            require(snrMinimum.isFinite() && snrMinimum > 0.0) { "SNR 阈值必须大于 0" }
            return SitePhotometryQc(
                flags = flags,
                signalDetectable = snr >= snrMinimum,
                qualityReliable = !hardFailure
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
    val qc: SitePhotometryQc,
    /** 空值既可表示旧全孔中位数，也可表示本次 ROI 缺失；后者由质量标记明确区分。 */
    val readout: WellReadoutEvidence? = null,
    /** 前景灰度均值（借鉴 GenePix F Mean，与中位数对照识别气泡、灰尘高光和咖啡环）。 */
    val roiMeanGray: Double = roiMedianGray,
    /** 前景灰度稳健标准差。 */
    val roiSigmaGray: Double = 0.0,
    /** 局部背景灰度均值（借鉴 GenePix B Mean，与中位数对照识别背景划痕或邻孔溢光）。 */
    val backgroundMeanGray: Double = backgroundMedianGray,
    /** 前景像素偏离背景中位数超过 2 倍有效背景噪声的比例（对应 GenePix `% > B + 2SD`）。 */
    val pctAboveBgPlus2Sd: Double = 0.0,
    /** 背景均值相对中位数的归一化偏度 $|B_{\text{mean}} - B_{\text{median}}| / \sigma_B$。 */
    val backgroundMeanMedianSkew: Double = 0.0,
    /** 前景均值相对中位数的归一化偏度 $|F_{\text{mean}} - F_{\text{median}}| / \sigma_F$。 */
    val foregroundMeanMedianSkew: Double = 0.0,
    /** 实际采样的前景有效像素数（对应 GenePix `F Pixels`）。 */
    val roiPixelCount: Int = 0,
    /** 实际采样的局部背景有效像素数（对应 GenePix `B Pixels`）。 */
    val annulusPixelCount: Int = 0,
    /** 单元区域来源（如 `SEGMENTED`、`FALLBACK_MEDIAN_GEOMETRY`），供过程检视和导出追溯。 */
    val segmentationSource: String? = null,
    /** 当区域为兜底几何时记录具体原因（如 `LOW_CONTRAST`、`WINDOW_BOUNDARY_TOUCH`）。 */
    val segmentationFallbackReason: String? = null,
    /** 保存未经人工标记覆盖的算法原始可靠性，使界面切换人工 `Bad` 标记时可精确恢复。 */
    val autoQualityReliable: Boolean = qc.qualityReliable
) {
    val foregroundRgb get() = readout?.raw?.rgb ?: roiMedianRgb
    val foregroundGray get() = readout?.raw?.gray ?: roiMedianGray
    val correctedForegroundRgb get() = readout?.correctedRgb ?: correctedMedianRgb
    val correctedForegroundGray get() = readout?.correctedGray ?: correctedMedianGray
    val readoutAvailable get() = PhotometryFlag.READOUT_UNAVAILABLE !in qc.flags
    val manuallyFlaggedBad get() = PhotometryFlag.MANUAL_BAD in qc.flags
}

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

    /**
     * 应用或取消用户在定位复核页标记的人工异常孔（借鉴 GenePix `Flag Bad`）。
     *
     * 被标为 `MANUAL_BAD` 的位点保留全部原始信号证据，但 `qualityReliable` 置为 `false`，
     * 从而自动退出比色参考白平衡、现场标准曲线拟合与样本复孔均值/中位数聚合；取消标记时
     * 严格恢复该孔原本的自动质控结论 [BaseSitePhotometry.autoQualityReliable]。
     */
    fun withManualBadSites(badSiteIndices: Set<Int>): PgQuantResult {
        val updatedSites = sites.map { site ->
            val markBad = site.siteIndex in badSiteIndices
            val updatedFlags = if (markBad) {
                site.qc.flags + PhotometryFlag.MANUAL_BAD
            } else {
                site.qc.flags - PhotometryFlag.MANUAL_BAD
            }
            val updatedReliable = if (markBad) false else site.autoQualityReliable
            site.copy(
                qc = site.qc.copy(
                    flags = updatedFlags,
                    qualityReliable = updatedReliable
                )
            )
        }
        return copy(sites = updatedSites).requireValid()
    }
}
