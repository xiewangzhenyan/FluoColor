package com.muc.fluocolorquant.domain.detection.quantification

/** 端点量化决策和快照结构的稳定版本号，用于历史运行复算审计。 */
const val ENDPOINT_QUANTIFIER_VERSION: String = "endpoint-quantifier-v2"

/** 与旧 FittingEngine 共享正向公式时记录的公式引擎版本。 */
const val FORMULA_ENGINE_VERSION: String = "fitting-engine-v1"

/** 浓度相对模型可靠范围的位置；超范围时不输出伪精确浓度。 */
enum class ReliableRangeStatus {
    WITHIN_RANGE,
    BELOW_RANGE,
    ABOVE_RANGE
}

/** 无法输出浓度时保存到运行记录的稳定机器原因。 */
enum class EndpointQuantificationReason {
    NON_FINITE_SIGNAL,
    UNSUPPORTED_MODEL_TYPE,
    MISSING_STANDARD_CURVE,
    INVALID_MODEL_DEFINITION,
    NON_MONOTONIC_MODEL
}

/**
 * 已准备模型处理单个位点时的内部强类型结果。
 *
 * 该类型刻意与兼容层 [EndpointQuantificationResult] 分离：只有 [SiteSignalOnly] 能表示
 * 当前位点输入损坏，且原因被固定为 NON_FINITE_SIGNAL；正向公式异常、非有限求值、二分
 * 或插值反算失败都必须返回 [ModelFailure]，由批量协调器撤销同分析物的全部部分浓度。
 */
sealed interface PreparedEndpointQuantificationResult {
    data class Quantified(
        val concentration: Double,
        val unit: String,
        val rangeStatus: ReliableRangeStatus = ReliableRangeStatus.WITHIN_RANGE,
        val modelSnapshotJson: String
    ) : PreparedEndpointQuantificationResult

    data class OutOfRange(
        val rangeStatus: ReliableRangeStatus,
        val modelSnapshotJson: String
    ) : PreparedEndpointQuantificationResult

    /** 非有限原始位点信号是唯一允许的位点级仅信号结果。 */
    data object SiteSignalOnly : PreparedEndpointQuantificationResult {
        val reason: EndpointQuantificationReason = EndpointQuantificationReason.NON_FINITE_SIGNAL
    }

    /** Ready 执行过程中暴露的模型或反算故障，必须按整个分析物回退。 */
    data class ModelFailure(
        val reason: EndpointQuantificationReason
    ) : PreparedEndpointQuantificationResult
}

/**
 * 标准曲线的不可变准备结果。
 *
 * [Ready] 表示模型级校验和准备已经完成，调用方可复用同一实例处理多个位点；
 * [SignalOnly] 表示模型本身不可用于定量，调用方应为该分析物保留原始科学信号。
 */
sealed interface PreparedStandardCurveQuantifier {
    /** 已准备完成的逐位点量化器；构造函数仅由量化器核心创建。 */
    class Ready internal constructor(
        private val quantifyPreparedSignal: (Double) -> PreparedEndpointQuantificationResult
    ) : PreparedStandardCurveQuantifier {
        /** 仅执行逐位点信号判定和反算，不重复解析或验证模型。 */
        fun quantify(signalValue: Double): PreparedEndpointQuantificationResult =
            quantifyPreparedSignal(signalValue)
    }

    /** 模型级降级结果，与单个位点的非有限信号失败严格区分。 */
    data class SignalOnly(
        val reason: EndpointQuantificationReason
    ) : PreparedStandardCurveQuantifier
}

/**
 * 端点图主特征到浓度的反算结果。
 *
 * [OutOfRange] 与 [SignalOnly] 都不携带浓度：前者说明曲线有效但样本落在可靠范围外，
 * 后者说明模型本身不可用于本次执行。两者必须在结果页显示不同的解释和建议。
 */
sealed interface EndpointQuantificationResult {
    data class Quantified(
        val concentration: Double,
        val unit: String,
        val rangeStatus: ReliableRangeStatus = ReliableRangeStatus.WITHIN_RANGE,
        val modelSnapshotJson: String
    ) : EndpointQuantificationResult

    data class OutOfRange(
        val rangeStatus: ReliableRangeStatus,
        val modelSnapshotJson: String
    ) : EndpointQuantificationResult

    data class SignalOnly(
        val reason: EndpointQuantificationReason
    ) : EndpointQuantificationResult
}
