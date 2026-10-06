package com.muc.fluocolorquant.domain.result.dualmodal

import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetAssessment

/**
 * 比色—荧光双模态判定的领域契约。
 *
 * 同一块芯片的比色与荧光是两个项目中的两次运行。判定只读取两次运行冻结的结果快照，
 * 不修改任何一侧的浓度，也不改写运行记录。输出按"事实＋建议"组织：页面先列两侧读数、
 * 相对离散度与质控证据，最后才给出按规则得到的建议，不替使用者下结论。
 */

/**
 * 判定规则的参数，随规则版本一起冻结进判定记录。
 *
 * 默认值来自论文第三章的物理建模仿真：τ 取训练批次洁净读数相对离散度的 P95，
 * 质控阈值取洁净最大偏差的 1.5 倍且不低于 0.15。真实使用前应以实测洁净样本重新标定。
 */
data class DualModalThresholds(
    /** 相对离散度 δ 的告警阈值（百分比）。 */
    val deltaPercent: Double = DEFAULT_DELTA_PERCENT,
    /** 阳控信号相对曲线预测信号的相对偏差阈值。 */
    val qcDeviation: Double = DEFAULT_QC_DEVIATION,
    /** 相对偏差分母的下限，避免预测信号接近零时把偏差放大。 */
    val qcDenominatorFloor: Double = DEFAULT_QC_DENOMINATOR_FLOOR,
    /** 阈值来源的稳定编码，页面据此提示是否需要重标。 */
    val source: String = SOURCE_SIMULATION
) {
    companion object {
        const val DEFAULT_DELTA_PERCENT: Double = 19.826
        const val DEFAULT_QC_DEVIATION: Double = 0.15
        const val DEFAULT_QC_DENOMINATOR_FLOOR: Double = 0.05
        const val SOURCE_SIMULATION: String = "SIMULATION_TRAINING_CLEAN"
    }
}

/** 一侧读数能否参与互证。 */
enum class DualModalSideStatus {
    USABLE,
    /** 至少一个阳控水平的偏差超过阈值。 */
    QC_ABNORMAL,
    /** 该侧定位被判为不可信，按规则视为不可用。 */
    LOCALIZATION_UNTRUSTED,
    /** 该样本在这一侧没有有效浓度。 */
    NO_CONCENTRATION
}

/** 规则给出的建议。 */
enum class DualModalDecision {
    /** 两侧都正常且一致，取两侧均值。 */
    FUSE,
    ADOPT_COLORIMETRIC,
    ADOPT_FLUORESCENCE,
    /** 两侧都异常，或两侧都正常但不一致、无法归因。 */
    RETEST
}

/** 建议的理由，页面据此生成说明文字。 */
enum class DualModalDecisionReason {
    CONSISTENT,
    COLORIMETRIC_UNUSABLE,
    FLUORESCENCE_UNUSABLE,
    BOTH_SIDES_UNUSABLE,
    DISCREPANCY_UNATTRIBUTED
}

/** 一个阳控水平的质控证据：实测信号与本次标定曲线在名义浓度处的预测信号之比较。 */
data class DualModalQcEvidence(
    val nominalConcentration: Double,
    val siteCount: Int,
    val measuredSignal: Double?,
    val predictedSignal: Double?,
    /** |实测 − 预测| / max(|预测|, 分母下限)；曲线或信号缺失时为空。 */
    val relativeDeviation: Double?,
    val exceeded: Boolean
)

/** 一个样本在某一侧的读数与证据。 */
data class DualModalSideReading(
    val runId: String,
    /** 有效位点浓度的中位数；没有有效位点时为空。 */
    val concentration: Double?,
    val validSiteCount: Int,
    val totalSiteCount: Int,
    val localizationTrusted: Boolean,
    val qcEvidence: List<DualModalQcEvidence>,
    val status: DualModalSideStatus
)

/** 一个（分析物，样本）读数的判定。 */
data class DualModalReading(
    val analyteId: String,
    val analyteName: String,
    val sampleKey: String,
    val concentrationUnit: String,
    val colorimetric: DualModalSideReading,
    val fluorescence: DualModalSideReading,
    /** 两侧都可用时的相对离散度（百分比）。 */
    val deltaPercent: Double?,
    val alarm: Boolean,
    val decision: DualModalDecision,
    val reason: DualModalDecisionReason,
    /** 建议采用的浓度；建议复检时为空。 */
    val suggestedConcentration: Double?
)

/** 一次完整的双模态判定。 */
data class DualModalAdjudication(
    val ruleVersion: String,
    val thresholds: DualModalThresholds,
    val colorimetricRunId: String,
    val fluorescenceRunId: String,
    val readings: List<DualModalReading>,
    /** 与规则并列的 DualNet 网络判读；旧修订或网络未运行时为空。 */
    val network: DualNetAssessment? = null
)

/** 两次运行不能配对的稳定原因。 */
enum class DualModalIncompatibility {
    /** 两次运行是同一种检测模式。 */
    SAME_DETECTION_MODE,
    /** 至少一侧不是比色或荧光（例如光谱）。 */
    UNSUPPORTED_DETECTION_MODE,
    GRID_SIZE_MISMATCH,
    CARRIER_MISMATCH,
    /** 位点的角色、分析物、样本槽或阳控名义浓度不一致。 */
    SITE_LAYOUT_MISMATCH,
    NO_SHARED_ANALYTE,
    UNIT_MISMATCH
}

/** 已保存的一次判定修订。 */
data class DualModalAdjudicationSnapshot(
    val adjudicationId: String,
    val revision: Int,
    val createdAtEpochMillis: Long,
    val inputFingerprint: String,
    val adjudication: DualModalAdjudication
)
