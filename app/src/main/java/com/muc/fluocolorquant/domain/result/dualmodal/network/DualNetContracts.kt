package com.muc.fluocolorquant.domain.result.dualmodal.network

/**
 * 比色—荧光双模态位点判读网络 DualNet 的领域契约。
 *
 * DualNet 来自论文第四章 4.3 节（W3）：在 W2 物理建模仿真数据上训练，输入同一位点比色、荧光
 * 两路裁切图及同行参照孔，输出比色头、荧光头与融合头的 log10 浓度均值与对数方差。它在系统中是
 * 双模态判定之上的第二种"派生建议"：与第三章规则并列显示，只读取两次运行冻结的原图与定位结果，
 * 不改写任何一侧的浓度；网络结果随判定修订一起冻结，历史页不重新推理。
 */

/**
 * 冻结的模型身份、输入契约与后处理参数。
 *
 * 所有数值都来自 W3 的 fold=none 模型（训练含全部八类干扰）与 `w3_report.json` 的逐批重标定口径；
 * 改动其中任何一项都必须更换 [MODEL_VERSION]，否则历史记录与新结果无法区分。
 */
object DualNetSpec {
    const val MODEL_ID: String = "dualnet-w3-fold-none"
    /** 页面显示用名称；可追溯身份以 [MODEL_ID]、版本与摘要为准。 */
    const val MODEL_DISPLAY_NAME: String = "DualNet"
    const val MODEL_VERSION: Int = 1
    const val MODEL_ASSET_PATH: String = "models/dualnet_lite.ptl"
    const val MODEL_SHA256: String = "a0f0543378a6655a7e51b4c663405f1077354d9032c9e916d2de54c9255522e9"

    /** 训练数据只有 15×15 微流控芯片。 */
    const val GRID_SIZE: Int = 15
    const val CROP_SIZE: Int = 32
    const val CROP_BYTES: Int = CROP_SIZE * CROP_SIZE * 3
    /** 每个模态 4 张 RGB 裁切：位点、同行阴性参照、同行中水平阳控、同行高水平阳控。 */
    const val CHANNELS: Int = 24
    const val OUTPUT_SIZE: Int = 6

    /** 训练版面中两只阳控的名义浓度（ng/mL）；版面不同时网络的参照含义不成立。 */
    const val QC_MID_NOMINAL: Double = 25.0
    const val QC_HIGH_NOMINAL: Double = 250.0
    const val NOMINAL_RELATIVE_TOLERANCE: Double = 0.01
    /** 网络输出 log10(ng/mL)，只接受这一单位。 */
    const val CONCENTRATION_UNIT: String = "ng/mL"

    /** 逐批重标定使用的标准水平范围（W3：标定板第 5–11 列，1.4–219 ng/mL）。 */
    const val RECALIBRATION_MIN_LEVEL: Double = 1.4
    const val RECALIBRATION_MAX_LEVEL: Double = 219.0
    /** 少于 3 个水平时直线拟合没有检验余量，视为无法重标定。 */
    const val MIN_RECALIBRATION_LEVELS: Int = 3

    /** 与训练损失相同的对数方差截断范围。 */
    const val LOG_VARIANCE_MIN: Double = -9.0
    const val LOG_VARIANCE_MAX: Double = 4.0

    /** W3 计分所用的可报告范围（ng/mL）；范围外的网络读数没有经过评价。 */
    const val EVALUATED_RANGE_MIN: Double = 5.0
    const val EVALUATED_RANGE_MAX: Double = 61.64

    /** PG-Grid 参考实现把矫正图存为 JPEG（OpenCV 默认质量 95），训练裁切就来自这张 JPEG。 */
    const val RECTIFIED_JPEG_QUALITY: Int = 95
}

/** 拒判阈值：u 取验证批次洁净读数的 P95，δ 取 P95 且不低于 10%（W3 fold=none，逐批重标定）。 */
data class DualNetThresholds(
    val colorimetricUncertainty: Double = 0.1381470876610327,
    val fluorescenceUncertainty: Double = 0.15920754877757165,
    val fusedUncertainty: Double = 0.1311678299481276,
    val deltaPercent: Double = 19.37972564329817
)

enum class DualNetHead { COLORIMETRIC, FLUORESCENCE, FUSED }

/** 一个输出头在本批的 log10 线性重标定：log10(c) = intercept + slope × μ。 */
data class DualNetRecalibration(
    val head: DualNetHead,
    val intercept: Double,
    val slope: Double,
    val levelCount: Int
)

/** 一个样本在某个输出头上的读数。 */
data class DualNetHeadReading(
    val head: DualNetHead,
    /** 该头在本批是否可用：对应侧定位可信、标定板对应侧可信且重标定成功。 */
    val available: Boolean,
    /** 重标定后的浓度（ng/mL）；不可用时为空。 */
    val concentration: Double?,
    /** 重标定后的不确定度 u（log10 单位，单孔方差与孔间离散合成）；不可用时为空。 */
    val uncertainty: Double?,
    /** u 是否未超过该头的拒判阈值。 */
    val withinThreshold: Boolean
)

enum class DualNetDecision {
    /** 融合头不确定度与两单模态头一致性都合格。 */
    ADOPT_FUSED,
    /** 融合头不可用时退回比色头。 */
    ADOPT_COLORIMETRIC,
    /** 融合头不可用、比色侧也不可用时退回荧光头。 */
    ADOPT_FLUORESCENCE,
    RETEST
}

enum class DualNetDecisionReason {
    CONSISTENT,
    /** 融合头不确定度超过阈值。 */
    FUSED_UNCERTAIN,
    /** 两个单模态头的相对离散度 δ 超过阈值。 */
    HEADS_DISAGREE,
    /** 融合头不可用（荧光侧定位不可信或本批重标定失败），退回比色头。 */
    COLORIMETRIC_FALLBACK,
    /** 融合头不可用（比色侧定位不可信或本批重标定失败），退回荧光头。 */
    FLUORESCENCE_FALLBACK,
    /** 只剩一侧可用，但它的不确定度超过阈值。 */
    FALLBACK_UNCERTAIN,
    /** 两侧都不可用。 */
    NO_USABLE_HEAD
}

/** 一个（分析物，样本）的网络读数与建议。 */
data class DualNetReading(
    val analyteId: String,
    val sampleKey: String,
    /** 参与读数的重复位点数（同行参照孔齐全的样本位点）。 */
    val siteCount: Int,
    val colorimetric: DualNetHeadReading,
    val fluorescence: DualNetHeadReading,
    val fused: DualNetHeadReading,
    /** 两个单模态头都可用时的相对离散度（百分比）。 */
    val deltaPercent: Double?,
    val decision: DualNetDecision,
    val reason: DualNetDecisionReason,
    val suggestedConcentration: Double?,
    /** 建议浓度是否在 W3 评价过的范围内；没有建议浓度时为空。 */
    val withinEvaluatedRange: Boolean?
)

enum class DualNetStatus { COMPLETED, UNAVAILABLE }

/** 网络无法给出读数的稳定原因；规则判定不受影响。 */
enum class DualNetUnavailableReason {
    /** 不是 15×15 芯片，或样本行缺少阴性参照与 25、250 ng/mL 两只阳控。 */
    LAYOUT_NOT_SUPPORTED,
    /** 浓度单位不是 ng/mL。 */
    UNIT_NOT_SUPPORTED,
    /** 两侧所用曲线追溯不到唯一的标定板运行。 */
    CALIBRATION_RUNS_NOT_FOUND,
    /** 两块标定板运行不能互相配对（版面、载体或单位不一致）。 */
    CALIBRATION_RUNS_NOT_PAIRABLE,
    /** 标定板在 1.4–219 ng/mL 内的标准水平少于 3 个。 */
    CALIBRATION_LEVELS_INSUFFICIENT,
    /** 三个输出头的重标定都不可用：标定板两侧定位不可信，或拟合斜率不为正。 */
    RECALIBRATION_FAILED,
    /** 冻结原图缺失、校验不一致或定位几何无法解析。 */
    EVIDENCE_UNAVAILABLE,
    /** 模型文件缺失、摘要不一致或无法加载。 */
    MODEL_UNAVAILABLE,
    INFERENCE_FAILED
}

/** 一个分析物的网络判读。 */
data class DualNetAnalyteAssessment(
    val analyteId: String,
    val status: DualNetStatus,
    val unavailableReason: DualNetUnavailableReason?,
    val calibrationColorimetricRunId: String?,
    val calibrationFluorescenceRunId: String?,
    val recalibrations: List<DualNetRecalibration>,
    val readings: List<DualNetReading>
)

/** 一次配对上的完整网络判读，随判定修订冻结。 */
data class DualNetAssessment(
    val modelId: String = DualNetSpec.MODEL_ID,
    val modelVersion: Int = DualNetSpec.MODEL_VERSION,
    val modelSha256: String = DualNetSpec.MODEL_SHA256,
    val thresholds: DualNetThresholds = DualNetThresholds(),
    val analytes: List<DualNetAnalyteAssessment>
)

/** 冻结原图、定位几何或模型基础设施失败；由组装器映射为 [DualNetUnavailableReason]。 */
class DualNetUnavailableException(
    val reason: DualNetUnavailableReason,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
