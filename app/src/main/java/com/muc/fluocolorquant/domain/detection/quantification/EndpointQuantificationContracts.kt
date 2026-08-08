package com.muc.fluocolorquant.domain.detection.quantification

/** 端点量化决策和快照结构的稳定版本号，用于历史运行复算审计。 */
const val ENDPOINT_QUANTIFIER_VERSION: String = "endpoint-quantifier-v4"

/** 与旧 FittingEngine 共享正向公式时记录的公式引擎版本。 */
const val FORMULA_ENGINE_VERSION: String = "fitting-engine-v1"

/**
 * 反算浓度相对曲线标定范围和项目预期量程的位置。
 *
 * `BELOW_RANGE` / `ABOVE_RANGE` 表示已经得到有限浓度，但该结果属于标定范围外推；
 * `BELOW_PROJECT_RANGE` / `ABOVE_PROJECT_RANGE` 表示输入信号已经超出本次项目允许的有界
 * 反算域，量化器无法在不进行无限外推的情况下给出结果。
 */
enum class ReliableRangeStatus {
    WITHIN_RANGE,
    BELOW_RANGE,
    ABOVE_RANGE,
    BELOW_TRUSTED_RANGE,
    ABOVE_TRUSTED_RANGE,
    BELOW_PROJECT_RANGE,
    ABOVE_PROJECT_RANGE
}

/**
 * 浓度结果的科学可用级别。
 *
 * 范围关系和数值可用级别必须分开：一个高于项目预期范围的孔仍可能拥有可信估计，
 * 而一个位于项目范围内但严重饱和的孔也可能只能报告界限或完全不可用。
 */
enum class QuantificationState {
    QUANTIFIED,
    ESTIMATED,
    BOUND_ONLY,
    UNAVAILABLE
}

/** 单侧删失或界限的方向；NONE 表示信号被视为普通精确观测。 */
enum class QuantificationCensoringDirection {
    NONE,
    LOWER_BOUND,
    UPPER_BOUND
}

/**
 * 逐孔量化的富观测输入。
 *
 * `signalValue` 是真正参与曲线反算的冻结主特征；其余字段只提供质量与删失证据，
 * 不能在量化器内部重新读取图片或重新计算光度值。
 */
data class QuantificationObservation(
    val signalValue: Double,
    val qualityReliable: Boolean = true,
    val saturationRatio: Double? = null,
    val photometryFlags: Set<String> = emptySet(),
    val censoringDirection: QuantificationCensoringDirection =
        QuantificationCensoringDirection.NONE,
    val censoringSignalBound: Double? = null
)

/** 无法输出浓度时保存到运行记录的稳定机器原因。 */
enum class EndpointQuantificationReason {
    NON_FINITE_SIGNAL,
    UNSUPPORTED_MODEL_TYPE,
    MISSING_STANDARD_CURVE,
    INVALID_MODEL_DEFINITION,
    NON_MONOTONIC_MODEL,
    UNRELIABLE_OBSERVATION,
    SATURATED_WITHOUT_BOUND
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
        val modelSnapshotJson: String,
        val quantificationState: QuantificationState = QuantificationState.QUANTIFIED,
        val concentrationLowerBound: Double? = concentration,
        val concentrationUpperBound: Double? = concentration,
        val intervalConfidenceLevel: Double? = null
    ) : PreparedEndpointQuantificationResult

    data class OutOfRange(
        val rangeStatus: ReliableRangeStatus,
        val modelSnapshotJson: String,
        val quantificationState: QuantificationState = QuantificationState.BOUND_ONLY,
        val concentrationBound: Double? = null,
        val censoringDirection: QuantificationCensoringDirection =
            QuantificationCensoringDirection.NONE
    ) : PreparedEndpointQuantificationResult

    /** 位点信号存在，但严重质量问题使点浓度和单侧界限都不可解释。 */
    data class Unavailable(
        val reason: EndpointQuantificationReason,
        val modelSnapshotJson: String? = null,
        val quantificationState: QuantificationState = QuantificationState.UNAVAILABLE
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
        private val quantifyPreparedObservation:
            ((QuantificationObservation) -> PreparedEndpointQuantificationResult)? = null,
        private val quantifyPreparedSignal: (Double) -> PreparedEndpointQuantificationResult
    ) : PreparedStandardCurveQuantifier {
        /** 仅执行逐位点信号判定和反算，不重复解析或验证模型。 */
        fun quantify(signalValue: Double): PreparedEndpointQuantificationResult =
            quantifyPreparedSignal(signalValue)

        /** 使用冻结信号及其质量/删失证据执行一次逐孔反算。 */
        fun quantify(observation: QuantificationObservation): PreparedEndpointQuantificationResult =
            quantifyPreparedObservation?.invoke(observation)
                ?: quantifyPreparedSignal(observation.signalValue)
    }

    /** 模型级降级结果，与单个位点的非有限信号失败严格区分。 */
    data class SignalOnly(
        val reason: EndpointQuantificationReason
    ) : PreparedStandardCurveQuantifier
}

/**
 * 端点图主特征到浓度的反算结果。
 *
 * 标定范围外但仍落在项目量程内的结果使用 [Quantified] 携带浓度，并通过
 * [ReliableRangeStatus.BELOW_RANGE] 或 [ReliableRangeStatus.ABOVE_RANGE] 标记为外推。
 * [OutOfRange] 只保留给超出项目有界反算域、无法安全得到浓度的输入；[SignalOnly]
 * 则表示模型或当前位点信号本身不可执行。三者必须在结果页显示不同的解释和建议。
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
