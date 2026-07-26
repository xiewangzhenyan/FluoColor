package com.muc.fluocolorquant.domain.detection.quantification

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import org.apache.commons.math3.analysis.solvers.LaguerreSolver

/**
 * 已发布标准曲线的端侧浓度反算器。
 *
 * 曲线实体保存的是“浓度 x → 科学信号 y”的正向拟合。量化器先通过 [prepare]
 * 一次性冻结并校验模型，再复用 [PreparedStandardCurveQuantifier.Ready] 处理同一分析物的
 * 多个位点。曲线主档中的范围表示标定范围；调用方可额外传入项目预期量程，量化器会
 * 在数学定义和单调性允许时，在项目量程内执行有界外推并返回明确的范围状态。
 */
object StandardCurveQuantifier {
    private val gson = Gson()

    /**
     * 将模型数据预编译为可复用的逐位点量化器。
     *
     * 此阶段完成模型类型、标准曲线、有限科学字段、快照、严格数学定义域、参数、
     * 单调性和插值可靠域检查。任何模型级问题都返回 [PreparedStandardCurveQuantifier.SignalOnly]，
     * 避免每个阵列位点重复解析 JSON、序列化快照和扫描曲线。
     */
    fun prepare(
        bundle: AnalysisModelBundle,
        projectRangeMin: Double? = null,
        projectRangeMax: Double? = null
    ): PreparedStandardCurveQuantifier {
        if (bundle.model.modelType != AnalysisModelType.STANDARD_CURVE.code) {
            return modelSignalOnly(EndpointQuantificationReason.UNSUPPORTED_MODEL_TYPE)
        }
        val definition = bundle.standardCurve
            ?: return modelSignalOnly(EndpointQuantificationReason.MISSING_STANDARD_CURVE)

        // 量化器必须独立验证数据包内部外键关系，不能假设所有调用都来自已执行协调器
        // 预检的正常链路。错配曲线或标定点属于模型定义损坏，禁止生成可审计快照和浓度。
        if (
            definition.analysisModelId != bundle.model.id ||
            bundle.calibrationPoints.any { it.analysisModelId != bundle.model.id }
        ) {
            return invalidPreparedDefinition()
        }

        val calibrationMinimum = bundle.model.reliableRangeMin
        val calibrationMaximum = bundle.model.reliableRangeMax
        val concentrationSpan = calibrationMaximum - calibrationMinimum
        if (
            !calibrationMinimum.isFinite() ||
            !calibrationMaximum.isFinite() ||
            calibrationMaximum <= calibrationMinimum ||
            !concentrationSpan.isFinite()
        ) {
            return invalidPreparedDefinition()
        }
        val domain = resolveQuantificationDomain(
            calibrationMinimum = calibrationMinimum,
            calibrationMaximum = calibrationMaximum,
            projectMinimum = projectRangeMin,
            projectMaximum = projectRangeMax
        ) ?: return invalidPreparedDefinition()

        // 调用方可能传入 MutableList。准备阶段必须复制标定点，确保同一 Ready 在后续
        // 多位点执行中不受外部集合增删影响，也不会悄然重新准备模型。
        val frozenBundle = try {
            bundle.copy(calibrationPoints = bundle.calibrationPoints.toList())
        } catch (_: RuntimeException) {
            return invalidPreparedDefinition()
        }
        if (!hasFiniteScientificInputs(definition, frozenBundle.calibrationPoints)) {
            return invalidPreparedDefinition()
        }
        val snapshotJson = modelSnapshot(frozenBundle) ?: return invalidPreparedDefinition()
        val concentrationTolerance = scaledTolerance(
            domain.executionMinimum,
            domain.executionMaximum
        )
            ?: return invalidPreparedDefinition()

        val function = FittingFunction.fromIdentifier(definition.fittingFunction)
            ?: return invalidPreparedDefinition()
        return if (function == FittingFunction.INTERPOLATION) {
            prepareInterpolation(
                bundle = frozenBundle,
                snapshotJson = snapshotJson,
                concentrationTolerance = concentrationTolerance,
                domain = domain
            )
        } else {
            prepareFittedCurve(
                bundle = frozenBundle,
                function = function,
                snapshotJson = snapshotJson,
                concentrationTolerance = concentrationTolerance,
                domain = domain
            )
        }
    }

    /**
     * 兼容旧调用点的便捷包装。
     *
     * 新的批量执行链路应显式调用 [prepare] 并复用 Ready；此包装仅保证原有单次调用语义。
     */
    fun quantify(
        bundle: AnalysisModelBundle,
        signalValue: Double
    ): EndpointQuantificationResult {
        return when (val prepared = prepare(bundle)) {
            is PreparedStandardCurveQuantifier.Ready -> when (
                val result = prepared.quantify(signalValue)
            ) {
                is PreparedEndpointQuantificationResult.Quantified ->
                    EndpointQuantificationResult.Quantified(
                        concentration = result.concentration,
                        unit = result.unit,
                        rangeStatus = result.rangeStatus,
                        modelSnapshotJson = result.modelSnapshotJson
                    )
                is PreparedEndpointQuantificationResult.OutOfRange ->
                    EndpointQuantificationResult.OutOfRange(
                        rangeStatus = result.rangeStatus,
                        modelSnapshotJson = result.modelSnapshotJson
                    )
                PreparedEndpointQuantificationResult.SiteSignalOnly ->
                    EndpointQuantificationResult.SignalOnly(
                        PreparedEndpointQuantificationResult.SiteSignalOnly.reason
                    )
                is PreparedEndpointQuantificationResult.ModelFailure ->
                    EndpointQuantificationResult.SignalOnly(result.reason)
            }
            is PreparedStandardCurveQuantifier.SignalOnly -> EndpointQuantificationResult.SignalOnly(
                prepared.reason
            )
        }
    }

    /** 准备共享正向公式、全可靠域采样结果和二分反算所需的不可变状态。 */
    private fun prepareFittedCurve(
        bundle: AnalysisModelBundle,
        function: FittingFunction,
        snapshotJson: String,
        concentrationTolerance: Double,
        domain: QuantificationDomain
    ): PreparedStandardCurveQuantifier {
        val definition = requireNotNull(bundle.standardCurve)
        val parameters = parseParameters(definition.parametersJson, function)
            ?: return invalidPreparedDefinition()
        val proofMinimum = minOf(domain.calibrationMinimum, domain.executionMinimum)
        val proofMaximum = maxOf(domain.calibrationMaximum, domain.executionMaximum)

        // 旧拟合页面为保证绘图连续，会对部分非法参数返回替代值。科研定量必须在调用
        // 共享公式前先拒绝这些无定义域或不可辨识参数。
        if (!hasStrictDefinition(function, parameters, proofMinimum, proofMaximum)) {
            return invalidPreparedDefinition()
        }
        val provenDirection = proveMonotonicDirection(
            function = function,
            parameters = parameters,
            minimum = proofMinimum,
            maximum = proofMaximum
        ) ?: return nonMonotonicPreparedModel()
        val direction = matchDeclaredDirection(
            declared = definition.monotonicDirection,
            inferred = provenDirection
        ) ?: return nonMonotonicPreparedModel()

        // 均匀采样只用于发现正向公式在可靠区间内的溢出、奇点或非有限结果。
        // 单调方向已经由解析导数在整个闭区间上证明，严禁再以采样点差值替代数学保证。
        val sampledSignals = sampleReliableCurve(
            function = function,
            parameters = parameters,
            minimum = domain.executionMinimum,
            maximum = domain.executionMaximum
        ) ?: return invalidPreparedDefinition()
        val minimumSignal = sampledSignals.first()
        val maximumSignal = sampledSignals.last()
        val signalTolerance = scaledTolerance(
            minimumSignal,
            maximumSignal
        ) ?: return invalidPreparedDefinition()
        val boundaryDelta = maximumSignal - minimumSignal
        val boundariesMatchProof = when (direction) {
            MonotonicDirection.INCREASING -> boundaryDelta > signalTolerance
            MonotonicDirection.DECREASING -> -boundaryDelta > signalTolerance
        }
        if (!boundariesMatchProof) return nonMonotonicPreparedModel()

        val immutableParameters = parameters.toMap()
        return PreparedStandardCurveQuantifier.Ready { signalValue ->
            quantifyPreparedFittedCurve(
                signalValue = signalValue,
                function = function,
                parameters = immutableParameters,
                domain = domain,
                minimumSignal = minimumSignal,
                maximumSignal = maximumSignal,
                direction = direction,
                signalTolerance = signalTolerance,
                concentrationTolerance = concentrationTolerance,
                unit = bundle.model.concentrationUnit,
                snapshotJson = snapshotJson
            )
        }
    }

    /** 准备重复均值、可靠区间边界插值和分段反算所需的不可变状态。 */
    private fun prepareInterpolation(
        bundle: AnalysisModelBundle,
        snapshotJson: String,
        concentrationTolerance: Double,
        domain: QuantificationDomain
    ): PreparedStandardCurveQuantifier {
        val definition = requireNotNull(bundle.standardCurve)
        val allPoints = averagedCalibrationPoints(bundle.calibrationPoints)
        if (allPoints.size < 2 || allPoints.any { !it.signal.isFinite() }) {
            return invalidPreparedDefinition()
        }

        // 插值没有可审计的标定域外方程，因此继续严格限制在真实标定范围内。
        val minimum = domain.calibrationMinimum
        val maximum = domain.calibrationMaximum
        val calibrationTolerance = scaledTolerance(
            allPoints.first().concentration,
            allPoints.last().concentration
        ) ?: return invalidPreparedDefinition()
        val minimumSignal = interpolateSignalAtConcentration(
            points = allPoints,
            concentration = minimum,
            domainTolerance = calibrationTolerance
        ) ?: return invalidPreparedDefinition()
        val maximumSignal = interpolateSignalAtConcentration(
            points = allPoints,
            concentration = maximum,
            domainTolerance = calibrationTolerance
        ) ?: return invalidPreparedDefinition()
        val reliablePoints = buildReliableInterpolationPoints(
            allPoints = allPoints,
            minimum = minimum,
            maximum = maximum,
            minimumSignal = minimumSignal,
            maximumSignal = maximumSignal
        )
        val signalTolerance = scaledTolerance(
            reliablePoints.minOf(AveragedPoint::signal),
            reliablePoints.maxOf(AveragedPoint::signal)
        ) ?: return invalidPreparedDefinition()
        val direction = resolvePointDirection(
            points = reliablePoints,
            declared = definition.monotonicDirection,
            tolerance = signalTolerance
        ) ?: return nonMonotonicPreparedModel()

        val immutablePoints = reliablePoints.toList()
        return PreparedStandardCurveQuantifier.Ready { signalValue ->
            quantifyPreparedInterpolation(
                signalValue = signalValue,
                points = immutablePoints,
                minimum = minimum,
                maximum = maximum,
                minimumSignal = minimumSignal,
                maximumSignal = maximumSignal,
                direction = direction,
                signalTolerance = signalTolerance,
                concentrationTolerance = concentrationTolerance,
                unit = bundle.model.concentrationUnit,
                snapshotJson = snapshotJson
            )
        }
    }

    /** Ready 的拟合曲线路径只处理单个位点，不再执行任何模型级准备。 */
    private fun quantifyPreparedFittedCurve(
        signalValue: Double,
        function: FittingFunction,
        parameters: Map<String, Double>,
        domain: QuantificationDomain,
        minimumSignal: Double,
        maximumSignal: Double,
        direction: MonotonicDirection,
        signalTolerance: Double,
        concentrationTolerance: Double,
        unit: String,
        snapshotJson: String
    ): PreparedEndpointQuantificationResult {
        if (!signalValue.isFinite()) return nonFiniteSiteSignal()
        val boundaryDecision = normalizeSignalToReliableBoundary(
            signal = signalValue,
            minimumConcentrationSignal = minimumSignal,
            maximumConcentrationSignal = maximumSignal,
            direction = direction,
            tolerance = signalTolerance,
            belowStatus = domain.belowExecutionStatus,
            aboveStatus = domain.aboveExecutionStatus
        )
        val targetSignal = when (boundaryDecision) {
            is SignalBoundaryDecision.Outside -> {
                return PreparedEndpointQuantificationResult.OutOfRange(
                    boundaryDecision.status,
                    snapshotJson
                )
            }
            is SignalBoundaryDecision.Within -> boundaryDecision.normalizedSignal
        }

        // 容差内落在信号边界外的输入必须精确夹到浓度端点，不能进入二分后产生负浓度。
        if (targetSignal == minimumSignal) {
            return quantified(
                concentration = domain.executionMinimum,
                unit = unit,
                snapshotJson = snapshotJson,
                rangeStatus = domain.statusOf(domain.executionMinimum)
            )
        }
        if (targetSignal == maximumSignal) {
            return quantified(
                concentration = domain.executionMaximum,
                unit = unit,
                snapshotJson = snapshotJson,
                rangeStatus = domain.statusOf(domain.executionMaximum)
            )
        }

        var lower = domain.executionMinimum
        var upper = domain.executionMaximum
        repeat(BISECTION_ITERATIONS) {
            val middle = lower + (upper - lower) / 2.0
            if (middle == lower || middle == upper) return@repeat
            val middleSignal = evaluateSafely(function, parameters, middle)
                ?: return invalidSiteDefinition()
            val moveLower = when (direction) {
                MonotonicDirection.INCREASING -> middleSignal < targetSignal
                MonotonicDirection.DECREASING -> middleSignal > targetSignal
            }
            if (moveLower) lower = middle else upper = middle
        }
        val concentration = normalizeConcentrationToReliableBoundary(
            concentration = lower + (upper - lower) / 2.0,
            minimum = domain.executionMinimum,
            maximum = domain.executionMaximum,
            tolerance = concentrationTolerance
        ) ?: return invalidSiteDefinition()
        return quantified(
            concentration = concentration,
            unit = unit,
            snapshotJson = snapshotJson,
            rangeStatus = domain.statusOf(concentration)
        )
    }

    /** Ready 的插值路径只在预先构造的可靠区间分段中查找，不接触原始标定域外的段。 */
    private fun quantifyPreparedInterpolation(
        signalValue: Double,
        points: List<AveragedPoint>,
        minimum: Double,
        maximum: Double,
        minimumSignal: Double,
        maximumSignal: Double,
        direction: MonotonicDirection,
        signalTolerance: Double,
        concentrationTolerance: Double,
        unit: String,
        snapshotJson: String
    ): PreparedEndpointQuantificationResult {
        if (!signalValue.isFinite()) return nonFiniteSiteSignal()
        val boundaryDecision = normalizeSignalToReliableBoundary(
            signal = signalValue,
            minimumConcentrationSignal = minimumSignal,
            maximumConcentrationSignal = maximumSignal,
            direction = direction,
            tolerance = signalTolerance
        )
        val targetSignal = when (boundaryDecision) {
            is SignalBoundaryDecision.Outside -> {
                return PreparedEndpointQuantificationResult.OutOfRange(
                    boundaryDecision.status,
                    snapshotJson
                )
            }
            is SignalBoundaryDecision.Within -> boundaryDecision.normalizedSignal
        }

        if (targetSignal == minimumSignal) return quantified(minimum, unit, snapshotJson)
        if (targetSignal == maximumSignal) return quantified(maximum, unit, snapshotJson)

        val segment = points.zipWithNext().firstOrNull { (first, second) ->
            targetSignal >= minOf(first.signal, second.signal) &&
                targetSignal <= maxOf(first.signal, second.signal)
        } ?: return invalidSiteDefinition()
        val first = segment.first
        val second = segment.second
        val signalSpan = second.signal - first.signal
        if (!signalSpan.isFinite() || abs(signalSpan) <= signalTolerance) {
            return nonMonotonicSiteModel()
        }
        val ratio = (targetSignal - first.signal) / signalSpan
        val rawConcentration = first.concentration +
            ratio * (second.concentration - first.concentration)
        val concentration = normalizeConcentrationToReliableBoundary(
            concentration = rawConcentration,
            minimum = minimum,
            maximum = maximum,
            tolerance = concentrationTolerance
        ) ?: return invalidSiteDefinition()
        return quantified(concentration, unit, snapshotJson)
    }

    /** 解析参数并验证当前拟合函数声明的全部必需参数。 */
    private fun parseParameters(
        json: String,
        function: FittingFunction
    ): Map<String, Double>? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java) ?: return null
            function.requiredParams.associateWith { key ->
                val value = root.get(key)?.takeIf { it.isJsonPrimitive }?.asDouble
                    ?: return null
                if (!value.isFinite()) return null
                value
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 在整个可靠区间均匀采样共享公式。
     *
     * 任一点异常或非有限都说明该模型不能稳定覆盖声明的可靠范围，应判定为无效定义，
     * 而不是误报为普通非单调模型。
     */
    private fun sampleReliableCurve(
        function: FittingFunction,
        parameters: Map<String, Double>,
        minimum: Double,
        maximum: Double
    ): List<Double>? {
        val span = maximum - minimum
        if (!span.isFinite()) return null
        return buildList(MONOTONIC_SAMPLE_COUNT) {
            repeat(MONOTONIC_SAMPLE_COUNT) { index ->
                val ratio = index.toDouble() / (MONOTONIC_SAMPLE_COUNT - 1)
                val concentration = minimum + span * ratio
                val signal = evaluateSafely(function, parameters, concentration) ?: return null
                add(signal)
            }
        }
    }

    /** 插值点必须逐段严格单调；相同或近乎相同信号会造成反函数不唯一。 */
    private fun resolvePointDirection(
        points: List<AveragedPoint>,
        declared: String,
        tolerance: Double
    ): MonotonicDirection? {
        if (points.size < 2) return null
        val signals = points.map(AveragedPoint::signal)
        val increasing = signals.zipWithNext().all { (first, second) ->
            second - first > tolerance
        }
        val decreasing = signals.zipWithNext().all { (first, second) ->
            first - second > tolerance
        }
        val inferred = when {
            increasing -> MonotonicDirection.INCREASING
            decreasing -> MonotonicDirection.DECREASING
            else -> return null
        }
        return matchDeclaredDirection(declared, inferred)
    }

    private fun matchDeclaredDirection(
        declared: String,
        inferred: MonotonicDirection
    ): MonotonicDirection? {
        val explicit = when {
            declared.equals("INCREASING", ignoreCase = true) -> MonotonicDirection.INCREASING
            declared.equals("DECREASING", ignoreCase = true) -> MonotonicDirection.DECREASING
            else -> null
        }
        return explicit?.takeIf { it == inferred } ?: if (
            declared.equals("AUTO", ignoreCase = true)
        ) inferred else null
    }

    /**
     * 将容差内的信号越界规范到端点，超出容差才报告可靠范围状态。
     *
     * 方向以“低浓度端 → 高浓度端”定义，因此递减曲线的信号高低与浓度范围状态相反。
     */
    private fun normalizeSignalToReliableBoundary(
        signal: Double,
        minimumConcentrationSignal: Double,
        maximumConcentrationSignal: Double,
        direction: MonotonicDirection,
        tolerance: Double,
        belowStatus: ReliableRangeStatus = ReliableRangeStatus.BELOW_RANGE,
        aboveStatus: ReliableRangeStatus = ReliableRangeStatus.ABOVE_RANGE
    ): SignalBoundaryDecision {
        return when (direction) {
            MonotonicDirection.INCREASING -> when {
                signal < minimumConcentrationSignal -> {
                    if (minimumConcentrationSignal - signal <= tolerance) {
                        SignalBoundaryDecision.Within(minimumConcentrationSignal)
                    } else {
                        SignalBoundaryDecision.Outside(belowStatus)
                    }
                }
                signal > maximumConcentrationSignal -> {
                    if (signal - maximumConcentrationSignal <= tolerance) {
                        SignalBoundaryDecision.Within(maximumConcentrationSignal)
                    } else {
                        SignalBoundaryDecision.Outside(aboveStatus)
                    }
                }
                else -> SignalBoundaryDecision.Within(signal)
            }
            MonotonicDirection.DECREASING -> when {
                signal > minimumConcentrationSignal -> {
                    if (signal - minimumConcentrationSignal <= tolerance) {
                        SignalBoundaryDecision.Within(minimumConcentrationSignal)
                    } else {
                        SignalBoundaryDecision.Outside(belowStatus)
                    }
                }
                signal < maximumConcentrationSignal -> {
                    if (maximumConcentrationSignal - signal <= tolerance) {
                        SignalBoundaryDecision.Within(maximumConcentrationSignal)
                    } else {
                        SignalBoundaryDecision.Outside(aboveStatus)
                    }
                }
                else -> SignalBoundaryDecision.Within(signal)
            }
        }
    }

    /**
     * 反算后的浓度必须为有限值且位于可靠区间。
     *
     * 仅舍入误差范围内的轻微越界允许夹到端点；更大越界说明准备数据或反算过程不可靠。
     */
    private fun normalizeConcentrationToReliableBoundary(
        concentration: Double,
        minimum: Double,
        maximum: Double,
        tolerance: Double
    ): Double? {
        if (!concentration.isFinite()) return null
        return when {
            concentration < minimum -> {
                if (minimum - concentration <= tolerance) minimum else null
            }
            concentration > maximum -> {
                if (concentration - maximum <= tolerance) maximum else null
            }
            else -> concentration
        }
    }

    /** 对同一浓度的未排除重复标定点求均值，并按浓度排序。 */
    private fun averagedCalibrationPoints(points: List<CalibrationPoint>): List<AveragedPoint> {
        return points.asSequence()
            .filterNot(CalibrationPoint::excluded)
            .groupBy(CalibrationPoint::concentration)
            .map { (concentration, repeats) ->
                AveragedPoint(
                    concentration = concentration,
                    signal = repeats.map(CalibrationPoint::signalValue).average()
                )
            }
            .sortedBy(AveragedPoint::concentration)
    }

    /**
     * 根据浓度在相邻标定点间做正向分段插值。
     *
     * 可靠边界若仅因浮点表示落在标定域端点外一个极小容差，可直接使用端点信号；
     * 超出该容差则拒绝，绝不调用绘图式外推。
     */
    private fun interpolateSignalAtConcentration(
        points: List<AveragedPoint>,
        concentration: Double,
        domainTolerance: Double
    ): Double? {
        val first = points.firstOrNull() ?: return null
        val last = points.last()
        val normalizedConcentration = when {
            concentration < first.concentration -> {
                if (first.concentration - concentration <= domainTolerance) {
                    first.concentration
                } else {
                    return null
                }
            }
            concentration > last.concentration -> {
                if (concentration - last.concentration <= domainTolerance) {
                    last.concentration
                } else {
                    return null
                }
            }
            else -> concentration
        }
        points.firstOrNull { it.concentration == normalizedConcentration }?.let {
            return it.signal
        }
        val segment = points.zipWithNext().firstOrNull { (left, right) ->
            normalizedConcentration >= left.concentration &&
                normalizedConcentration <= right.concentration
        } ?: return null
        val concentrationSpan = segment.second.concentration - segment.first.concentration
        if (!concentrationSpan.isFinite() || concentrationSpan <= 0.0) return null
        val signal = segment.first.signal +
            (segment.second.signal - segment.first.signal) *
            (normalizedConcentration - segment.first.concentration) / concentrationSpan
        return signal.takeIf(Double::isFinite)
    }

    /** 可靠区间只保留两个边界及区间内部标定点，反算永远不会选择区间外的标定段。 */
    private fun buildReliableInterpolationPoints(
        allPoints: List<AveragedPoint>,
        minimum: Double,
        maximum: Double,
        minimumSignal: Double,
        maximumSignal: Double
    ): List<AveragedPoint> {
        return buildList {
            add(AveragedPoint(minimum, minimumSignal))
            allPoints.filter {
                it.concentration > minimum && it.concentration < maximum
            }.forEach(::add)
            add(AveragedPoint(maximum, maximumSignal))
        }
    }

    /** 在快照序列化前校验所有显式科学数值，损坏数据只能模型级降级。 */
    private fun hasFiniteScientificInputs(
        definition: com.muc.fluocolorquant.data.model.StandardCurveDefinition,
        calibrationPoints: List<CalibrationPoint>
    ): Boolean {
        return definition.lod.isFiniteOrAbsent() &&
            definition.loq.isFiniteOrAbsent() &&
            calibrationPoints.all { point ->
                point.concentration.isFinite() && point.signalValue.isFinite()
            }
    }

    /**
     * 对端点反算施加比旧拟合 UI 更严格的数学前置条件。
     *
     * 单调性由 [proveMonotonicDirection] 的解析导数证明；[sampleReliableCurve] 只补充
     * 检查共享正向公式是否在整个可靠区间出现溢出、奇点或非有限结果。
     */
    private fun hasStrictDefinition(
        function: FittingFunction,
        parameters: Map<String, Double>,
        minimum: Double,
        maximum: Double
    ): Boolean {
        fun value(name: String): Double = requireNotNull(parameters[name])
        return try {
            when (function) {
                FittingFunction.POWER,
                FittingFunction.LOG -> minimum > 0.0
                FittingFunction.CUSTOM_LOG -> minimum > value("c")
                FittingFunction.RODBARD,
                FittingFunction.RODBARD_NIH,
                FittingFunction.LOGISTIC -> {
                    val slope = value("b")

                    // 4PL/5PL 的标准形式在 x=0 且 b>0 时有明确有限值：
                    // (0 / c)^b = 0，因此 y 正好等于低浓度渐近值 a。项目常见量程
                    // 会从 0 开始，不能因为标定点从正浓度起步，就把整条可执行曲线
                    // 误判为无效。只有负浓度，或 x=0 且 b<=0 导致零的非正幂时，
                    // 才属于不能安全覆盖的定义域。
                    minimum >= 0.0 &&
                        value("c") > 0.0 &&
                        (minimum > 0.0 || slope > 0.0)
                }
                FittingFunction.GAMMA_VARIATE ->
                    value("a") > 0.0 &&
                        value("c") > 0.0 &&
                        value("d") > 0.0 &&
                        minimum > value("b")
                FittingFunction.GAUSSIAN -> value("d") > 0.0
                FittingFunction.RICHARDS -> {
                    val leftBase = 1.0 + value("b") * exp(-value("c") * minimum)
                    val rightBase = 1.0 + value("b") * exp(-value("c") * maximum)
                    value("d") != 0.0 &&
                        leftBase.isFinite() &&
                        rightBase.isFinite() &&
                        leftBase > 0.0 &&
                        rightBase > 0.0
                }
                FittingFunction.HILL ->
                    minimum >= 0.0 &&
                        value("c") > 0.0 &&
                        (minimum > 0.0 || value("b") > 0.0)
                FittingFunction.GENERAL_GOMPERTZ ->
                    minimum >= 0.0 &&
                        (minimum > 0.0 || value("d") > 0.0)
                else -> true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 在整个可靠浓度闭区间上证明正向曲线的单调方向。
     *
     * 多项式通过求出导数的全部近实根，把区间切分为导数符号不变的开区间并逐段判号；
     * 其他拟合函数直接使用解析导数的符号因子和已知临界点。只要方向无法证明、导数
     * 恒为零或在区间内部发生符号反转，就返回 null 并拒绝反算。
     */
    private fun proveMonotonicDirection(
        function: FittingFunction,
        parameters: Map<String, Double>,
        minimum: Double,
        maximum: Double
    ): MonotonicDirection? {
        fun value(name: String): Double = requireNotNull(parameters[name])
        fun direct(vararg factors: Double): MonotonicDirection? =
            directionFromFactorSigns(*factors)
        fun reversed(vararg factors: Double): MonotonicDirection? =
            directionFromFactorSigns(*factors)?.opposite()

        return try {
            when (function) {
                FittingFunction.LINEAR,
                FittingFunction.QUADRATIC,
                FittingFunction.CUBIC,
                FittingFunction.QUARTIC -> provePolynomialDirection(
                    derivativeCoefficients = polynomialDerivativeCoefficients(function, parameters),
                    minimum = minimum,
                    maximum = maximum
                )
                FittingFunction.EXPONENTIAL -> direct(value("a"), value("b"))
                FittingFunction.POWER -> direct(value("a"), value("b"))
                FittingFunction.LOG -> direct(value("b"))
                FittingFunction.RODBARD ->
                    reversed(value("a") - value("d"), value("b"))
                FittingFunction.GAMMA_VARIATE -> directionAroundCriticalPoint(
                    minimum = minimum,
                    maximum = maximum,
                    criticalPoint = value("b") + value("c") * value("d"),
                    before = MonotonicDirection.INCREASING,
                    after = MonotonicDirection.DECREASING
                )
                FittingFunction.CUSTOM_LOG -> direct(value("b"))
                FittingFunction.RODBARD_NIH -> reversed(value("a"), value("b"))
                FittingFunction.EXPONENTIAL_WITH_OFFSET -> reversed(value("a"), value("b"))
                FittingFunction.GAUSSIAN -> {
                    val before = directionFromFactorSigns(value("b") - value("a"))
                        ?: return null
                    directionAroundCriticalPoint(
                        minimum = minimum,
                        maximum = maximum,
                        criticalPoint = value("c"),
                        before = before,
                        after = before.opposite()
                    )
                }
                FittingFunction.EXPONENTIAL_RECOVERY -> direct(value("a"), value("b"))
                FittingFunction.LOGISTIC ->
                    reversed(value("a") - value("d"), value("g"), value("b"))
                FittingFunction.GOMPERTZ -> direct(value("a"), value("b"), value("c"))
                FittingFunction.HILL -> direct(value("a"), value("b"))
                FittingFunction.GENERAL_GOMPERTZ ->
                    direct(value("a"), value("b"), value("c"), value("d"))
                FittingFunction.RICHARDS ->
                    direct(value("a"), value("b"), value("c"), value("d"))
                FittingFunction.INTERPOLATION -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 返回 LINEAR 到 QUARTIC 的导数多项式系数，按常数项到最高次项排列。 */
    private fun polynomialDerivativeCoefficients(
        function: FittingFunction,
        parameters: Map<String, Double>
    ): DoubleArray {
        fun value(name: String): Double = requireNotNull(parameters[name])
        return when (function) {
            FittingFunction.LINEAR -> doubleArrayOf(value("a"))
            FittingFunction.QUADRATIC -> doubleArrayOf(value("b"), 2.0 * value("a"))
            FittingFunction.CUBIC -> doubleArrayOf(
                value("c"),
                2.0 * value("b"),
                3.0 * value("a")
            )
            FittingFunction.QUARTIC -> doubleArrayOf(
                value("d"),
                2.0 * value("c"),
                3.0 * value("b"),
                4.0 * value("a")
            )
            else -> error("仅多项式可生成导数系数：$function")
        }
    }

    /**
     * 使用 LaguerreSolver 求导数全部根，并在端点与相邻近实根之间检查导数符号。
     * 偶重根两侧符号相同，因此孤立导数零可接受；奇重根造成符号反转时会被拒绝。
     */
    private fun provePolynomialDirection(
        derivativeCoefficients: DoubleArray,
        minimum: Double,
        maximum: Double
    ): MonotonicDirection? {
        if (derivativeCoefficients.any { !it.isFinite() }) return null
        var highestNonZero = derivativeCoefficients.lastIndex
        while (highestNonZero > 0 && derivativeCoefficients[highestNonZero] == 0.0) {
            highestNonZero -= 1
        }
        val coefficients = derivativeCoefficients.copyOf(highestNonZero + 1)
        if (coefficients.all { it == 0.0 }) return null
        if (coefficients.size == 1) return directionFromFactorSigns(coefficients.single())

        val rootTolerance = scaledTolerance(minimum, maximum) ?: return null
        val initial = minimum + (maximum - minimum) / 2.0
        val roots = try {
            LaguerreSolver().solveAllComplex(coefficients, initial)
        } catch (_: RuntimeException) {
            return null
        }
        val realRoots = roots.asSequence()
            .filter { root ->
                root.real.isFinite() &&
                    root.imaginary.isFinite() &&
                    abs(root.imaginary) <=
                    POLYNOMIAL_ROOT_RELATIVE_TOLERANCE * max(1.0, abs(root.real))
            }
            .map { root -> root.real }
            .filter { root -> root >= minimum - rootTolerance && root <= maximum + rootTolerance }
            .map { root -> root.coerceIn(minimum, maximum) }
            .sorted()
            .fold(mutableListOf<Double>()) { distinct, root ->
                if (distinct.isEmpty() || abs(root - distinct.last()) > rootTolerance) {
                    distinct += root
                }
                distinct
            }

        val boundaries = buildList {
            add(minimum)
            realRoots.filter { it > minimum && it < maximum }.forEach(::add)
            add(maximum)
        }
        var proven: MonotonicDirection? = null
        boundaries.zipWithNext().forEach { (left, right) ->
            if (right <= left) return@forEach
            val midpoint = left + (right - left) / 2.0
            if (midpoint == left || midpoint == right) return null
            val derivative = evaluatePolynomial(coefficients, midpoint)
            val intervalDirection = directionFromFactorSigns(derivative) ?: return null
            if (proven != null && proven != intervalDirection) return null
            proven = intervalDirection
        }
        return proven
    }

    /** 用 Horner 法求值导数多项式，避免显式高次幂放大额外舍入误差。 */
    private fun evaluatePolynomial(coefficients: DoubleArray, x: Double): Double {
        var result = 0.0
        for (index in coefficients.indices.reversed()) {
            result = result * x + coefficients[index]
        }
        return result
    }

    /** 根据非零有限因子的符号积确定方向，避免直接相乘导致上溢或下溢。 */
    private fun directionFromFactorSigns(vararg factors: Double): MonotonicDirection? {
        if (factors.isEmpty() || factors.any { !it.isFinite() || it == 0.0 }) return null
        val negativeCount = factors.count { it < 0.0 }
        return if (negativeCount % 2 == 0) {
            MonotonicDirection.INCREASING
        } else {
            MonotonicDirection.DECREASING
        }
    }

    /** 临界点位于区间端点时允许单侧单调；严格落在区间内部时反函数不唯一。 */
    private fun directionAroundCriticalPoint(
        minimum: Double,
        maximum: Double,
        criticalPoint: Double,
        before: MonotonicDirection,
        after: MonotonicDirection
    ): MonotonicDirection? {
        if (!criticalPoint.isFinite()) return null
        return when {
            maximum <= criticalPoint -> before
            minimum >= criticalPoint -> after
            else -> null
        }
    }

    /** 复用旧页面唯一正向公式，并把所有运行时异常和非有限结果收敛为 null。 */
    private fun evaluateSafely(
        function: FittingFunction,
        parameters: Map<String, Double>,
        concentration: Double
    ): Double? {
        return try {
            FittingEngine.calculate(function, parameters, concentration).takeIf(Double::isFinite)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 建立随数值尺度变化的比较容差。
     *
     * 相对项只基于比较区间跨度，避免大直流偏置吞掉小但有效的信号变化；ULP 项处理
     * 极大数值的相邻可表示数。Double.MIN_NORMAL 仅作为极小相对尺度下限，使真正近零
     * 的跨度稳定判为不可辨识，而不会重新引入 1e-9 一类固定业务绝对阈值。
     */
    private fun scaledTolerance(first: Double, second: Double): Double? {
        if (!first.isFinite() || !second.isFinite()) return null
        val span = abs(second - first)
        if (!span.isFinite()) return null
        val relativeTolerance = max(span, java.lang.Double.MIN_NORMAL) * RELATIVE_TOLERANCE
        val ulpTolerance = max(Math.ulp(first), Math.ulp(second)) * ULP_MULTIPLIER
        return max(relativeTolerance, ulpTolerance).takeIf(Double::isFinite)
    }

    /** 模型快照包含主档、曲线定义、原始标定点和两类稳定实现版本。 */
    private fun modelSnapshot(bundle: AnalysisModelBundle): String? {
        return try {
            gson.toJson(
                linkedMapOf(
                    "schemaVersion" to "endpoint-model-snapshot-v1",
                    "quantifierVersion" to ENDPOINT_QUANTIFIER_VERSION,
                    "formulaEngineVersion" to FORMULA_ENGINE_VERSION,
                    "model" to bundle.model,
                    "standardCurve" to bundle.standardCurve,
                    "calibrationPoints" to bundle.calibrationPoints
                )
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun quantified(
        concentration: Double,
        unit: String,
        snapshotJson: String,
        rangeStatus: ReliableRangeStatus = ReliableRangeStatus.WITHIN_RANGE
    ): PreparedEndpointQuantificationResult.Quantified {
        return PreparedEndpointQuantificationResult.Quantified(
            concentration = concentration,
            unit = unit,
            rangeStatus = rangeStatus,
            modelSnapshotJson = snapshotJson
        )
    }

    /**
     * 将曲线标定范围与项目预期量程组合为一次运行的有界反算域。
     *
     * 项目范围缺失时保持历史行为；只提供一个端点、端点非有限或上下限颠倒时拒绝执行，
     * 避免以隐式默认值污染科研结果。
     */
    private fun resolveQuantificationDomain(
        calibrationMinimum: Double,
        calibrationMaximum: Double,
        projectMinimum: Double?,
        projectMaximum: Double?
    ): QuantificationDomain? {
        if ((projectMinimum == null) != (projectMaximum == null)) return null
        val executionMinimum = projectMinimum ?: calibrationMinimum
        val executionMaximum = projectMaximum ?: calibrationMaximum
        if (
            !executionMinimum.isFinite() ||
            !executionMaximum.isFinite() ||
            executionMaximum <= executionMinimum
        ) {
            return null
        }
        return QuantificationDomain(
            calibrationMinimum = calibrationMinimum,
            calibrationMaximum = calibrationMaximum,
            executionMinimum = executionMinimum,
            executionMaximum = executionMaximum
        )
    }

    private fun modelSignalOnly(
        reason: EndpointQuantificationReason
    ): PreparedStandardCurveQuantifier.SignalOnly {
        return PreparedStandardCurveQuantifier.SignalOnly(reason)
    }

    private fun invalidPreparedDefinition(): PreparedStandardCurveQuantifier.SignalOnly =
        modelSignalOnly(EndpointQuantificationReason.INVALID_MODEL_DEFINITION)

    private fun nonMonotonicPreparedModel(): PreparedStandardCurveQuantifier.SignalOnly =
        modelSignalOnly(EndpointQuantificationReason.NON_MONOTONIC_MODEL)

    private fun nonFiniteSiteSignal(): PreparedEndpointQuantificationResult.SiteSignalOnly =
        PreparedEndpointQuantificationResult.SiteSignalOnly

    private fun invalidSiteDefinition(): PreparedEndpointQuantificationResult.ModelFailure =
        PreparedEndpointQuantificationResult.ModelFailure(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION
        )

    private fun nonMonotonicSiteModel(): PreparedEndpointQuantificationResult.ModelFailure =
        PreparedEndpointQuantificationResult.ModelFailure(
            EndpointQuantificationReason.NON_MONOTONIC_MODEL
        )

    private fun Double?.isFiniteOrAbsent(): Boolean = this == null || isFinite()

    private sealed interface SignalBoundaryDecision {
        data class Within(val normalizedSignal: Double) : SignalBoundaryDecision
        data class Outside(val status: ReliableRangeStatus) : SignalBoundaryDecision
    }

    private enum class MonotonicDirection {
        INCREASING,
        DECREASING;

        fun opposite(): MonotonicDirection = when (this) {
            INCREASING -> DECREASING
            DECREASING -> INCREASING
        }
    }

    private data class AveragedPoint(val concentration: Double, val signal: Double)

    /** 标定域用于判定外推，项目域用于限制本次运行允许的最大反算范围。 */
    private data class QuantificationDomain(
        val calibrationMinimum: Double,
        val calibrationMaximum: Double,
        val executionMinimum: Double,
        val executionMaximum: Double
    ) {
        val belowExecutionStatus: ReliableRangeStatus
            get() = if (executionMinimum == calibrationMinimum) {
                ReliableRangeStatus.BELOW_RANGE
            } else {
                ReliableRangeStatus.BELOW_PROJECT_RANGE
            }

        val aboveExecutionStatus: ReliableRangeStatus
            get() = if (executionMaximum == calibrationMaximum) {
                ReliableRangeStatus.ABOVE_RANGE
            } else {
                ReliableRangeStatus.ABOVE_PROJECT_RANGE
            }

        fun statusOf(concentration: Double): ReliableRangeStatus = when {
            concentration < calibrationMinimum -> ReliableRangeStatus.BELOW_RANGE
            concentration > calibrationMaximum -> ReliableRangeStatus.ABOVE_RANGE
            else -> ReliableRangeStatus.WITHIN_RANGE
        }
    }

    private const val BISECTION_ITERATIONS: Int = 80
    private const val MONOTONIC_SAMPLE_COUNT: Int = 257
    private const val RELATIVE_TOLERANCE: Double = 1e-12
    private const val ULP_MULTIPLIER: Double = 8.0
    private const val POLYNOMIAL_ROOT_RELATIVE_TOLERANCE: Double = 1e-9
}
