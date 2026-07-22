package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.apache.commons.math3.analysis.ParametricUnivariateFunction
import org.apache.commons.math3.distribution.FDistribution
import org.apache.commons.math3.fitting.SimpleCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 成熟标准曲线自动择优器。
 *
 * 这里有意只让线性、4PL 和 5PL 参加普通用户的自动比较。高阶多项式等函数仍由
 * [FittingEngine.fitSingle] 提供给专家手动选择，但不会再凭训练集 R² 自动获胜。
 *
 * 自动裁决遵循以下原则：
 * 1. 比较无权重、响应倒数、响应平方倒数；存在重复标准点时额外比较逆方差权重；
 * 2. 先检查曲线单调性、参数稳定性和反函数是否可计算；
 * 3. 主要依据标准点反算浓度偏差及 ICH M10 风格接受率；
 * 4. 线性模型足够时优先选择最简单模型；5PL 只有在 4PL 不合格，或同权重下显著改善
 *    拟合并降低反算误差时才允许优先。
 */
internal object CalibrationModelSelector {

    /** 普通用户“自动推荐”模式唯一允许参与比较的函数集合。 */
    val automaticFunctions: Set<FittingFunction> = linkedSetOf(
        FittingFunction.LINEAR,
        FittingFunction.RODBARD,
        FittingFunction.LOGISTIC
    )

    /**
     * 返回已排序候选；列表首项就是最终自动推荐结果。
     */
    fun rank(
        dataPoints: List<Pair<Double, Double>>,
        allowedFunctions: Set<FittingFunction> = automaticFunctions
    ): List<CalibrationFitCandidate> {
        val points = dataPoints
            .filter { (concentration, signal) ->
                concentration.isFinite() && concentration >= 0.0 && signal.isFinite()
            }
            .sortedBy { it.first }
        val uniqueLevels = points.map { it.first }.distinct().size
        if (points.size < 2 || uniqueLevels < 2) return emptyList()

        val supportedFunctions = allowedFunctions.intersect(automaticFunctions)
        if (supportedFunctions.isEmpty()) return emptyList()

        val weightSets = buildWeightSets(points)
        val candidates = mutableListOf<CalibrationFitCandidate>()
        weightSets.forEach { (weighting, weights) ->
            if (FittingFunction.LINEAR in supportedFunctions) {
                fitLinear(points, weights)?.let { parameters ->
                    buildCandidate(points, FittingFunction.LINEAR, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }

            // 4PL 有四个参数，至少需要五个独立浓度水平，确保仍有残差自由度。
            if (FittingFunction.RODBARD in supportedFunctions && uniqueLevels >= 5) {
                fitLogisticModel(points, weights, fiveParameter = false)?.let { parameters ->
                    buildCandidate(points, FittingFunction.RODBARD, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }

            // 5PL 有五个参数，至少需要六个独立浓度水平；更少数据不允许自动拟合。
            if (FittingFunction.LOGISTIC in supportedFunctions && uniqueLevels >= 6) {
                fitLogisticModel(points, weights, fiveParameter = true)?.let { parameters ->
                    buildCandidate(points, FittingFunction.LOGISTIC, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }
        }

        val stableCandidates = candidates.filter { it.diagnostics.stable }
        if (stableCandidates.isEmpty()) return emptyList()
        val preferred = choosePreferred(stableCandidates, points.size)
        val remaining = stableCandidates
            .filterNot { it === preferred }
            .sortedWith(candidateComparator())
        return listOf(preferred) + remaining
    }

    /**
     * 选择最终推荐模型。
     *
     * 已满足 ICH 风格标准点验收的线性模型拥有最高优先级。进入非线性分支后，5PL 必须
     * 通过额外的“不对称参数确有必要”检查，防止五参数模型只靠多一个自由度压低残差。
     */
    private fun choosePreferred(
        candidates: List<CalibrationFitCandidate>,
        observationCount: Int
    ): CalibrationFitCandidate {
        val acceptedLinear = candidates.filter {
            it.function == FittingFunction.LINEAR && it.diagnostics.ichAccepted
        }
        if (acceptedLinear.isNotEmpty()) {
            return acceptedLinear.minWithOrNull(candidateComparator()) ?: acceptedLinear.first()
        }

        val acceptedFourParameter = candidates.filter {
            it.function == FittingFunction.RODBARD && it.diagnostics.ichAccepted
        }
        val acceptedFiveParameter = candidates.filter { fiveParameter ->
            if (fiveParameter.function != FittingFunction.LOGISTIC ||
                !fiveParameter.diagnostics.ichAccepted
            ) {
                return@filter false
            }
            val matchingFourParameter = candidates.firstOrNull { fourParameter ->
                fourParameter.function == FittingFunction.RODBARD &&
                    fourParameter.weighting == fiveParameter.weighting
            }
            matchingFourParameter == null ||
                !matchingFourParameter.diagnostics.ichAccepted ||
                fiveParameterIsJustified(
                    fourParameter = matchingFourParameter,
                    fiveParameter = fiveParameter,
                    observationCount = observationCount
                )
        }
        val acceptedNonlinear = acceptedFourParameter + acceptedFiveParameter
        if (acceptedNonlinear.isNotEmpty()) {
            return acceptedNonlinear.minWithOrNull(candidateComparator())
                ?: acceptedNonlinear.first()
        }

        // 数据水平不足或所有模型未完全达到 ICH 门槛时仍返回最稳妥候选，避免旧项目直接失去
        // 拟合能力；排序仍以端点、标准点接受率和反算误差优先，R²不参与主裁决。
        return candidates.minWithOrNull(candidateComparator()) ?: candidates.first()
    }

    /** 候选排序：验收状态、端点、接受比例、反算误差、复杂度、AICc。 */
    private fun candidateComparator(): Comparator<CalibrationFitCandidate> {
        return compareByDescending<CalibrationFitCandidate> { it.diagnostics.ichAccepted }
            .thenByDescending { it.diagnostics.endpointPassCount }
            .thenByDescending { it.diagnostics.acceptedStandardRatio }
            .thenBy { it.diagnostics.backCalculatedRmsePercent }
            .thenBy { parameterCount(it.function) }
            .thenBy { it.diagnostics.aicc }
            .thenBy { it.weighting.preferenceOrder }
    }

    /**
     * 4PL 是 5PL 在不对称参数 g=1 时的嵌套模型。只有同一权重下额外平方和 F 检验显著，
     * 且反算 RMSE 至少下降 5%，才认为第五个参数带来了可解释收益。
     */
    private fun fiveParameterIsJustified(
        fourParameter: CalibrationFitCandidate,
        fiveParameter: CalibrationFitCandidate,
        observationCount: Int
    ): Boolean {
        if (observationCount <= 5) return false
        val fittedAsymmetry = fiveParameter.parameters["g"] ?: return false
        // g 接近1时，5PL在科学含义上已经退化为4PL；不能因浮点残差微小下降就选择更复杂模型。
        if (abs(ln(fittedAsymmetry)) < MINIMUM_LOG_ASYMMETRY_DISTANCE) return false
        val fourSse = fourParameter.diagnostics.weightedSse
        val fiveSse = fiveParameter.diagnostics.weightedSse
        if (!fourSse.isFinite() || !fiveSse.isFinite() || fiveSse >= fourSse) return false
        val denominatorDegreesOfFreedom = observationCount - 5
        if (denominatorDegreesOfFreedom <= 0 || fiveSse <= EPSILON) {
            return fiveParameter.diagnostics.backCalculatedRmsePercent <
                fourParameter.diagnostics.backCalculatedRmsePercent * MINIMUM_5PL_RMSE_RATIO
        }
        val fStatistic = ((fourSse - fiveSse) / 1.0) /
            (fiveSse / denominatorDegreesOfFreedom.toDouble())
        if (!fStatistic.isFinite() || fStatistic <= 0.0) return false
        val pValue = 1.0 - FDistribution(1.0, denominatorDegreesOfFreedom.toDouble())
            .cumulativeProbability(fStatistic)
        return pValue < FIVE_PARAMETER_SIGNIFICANCE_LEVEL &&
            fiveParameter.diagnostics.backCalculatedRmsePercent <
            fourParameter.diagnostics.backCalculatedRmsePercent * MINIMUM_5PL_RMSE_RATIO
    }

    /** 加权线性最小二乘，参数格式与 FittingFunction.LINEAR 保持一致。 */
    private fun fitLinear(
        points: List<Pair<Double, Double>>,
        weights: List<Double>
    ): Map<String, Double>? {
        val weightSum = weights.sum()
        if (!weightSum.isFinite() || weightSum <= EPSILON) return null
        val meanX = points.indices.sumOf { weights[it] * points[it].first } / weightSum
        val meanY = points.indices.sumOf { weights[it] * points[it].second } / weightSum
        val denominator = points.indices.sumOf { index ->
            weights[index] * (points[index].first - meanX).pow(2)
        }
        if (!denominator.isFinite() || denominator <= EPSILON) return null
        val numerator = points.indices.sumOf { index ->
            weights[index] * (points[index].first - meanX) * (points[index].second - meanY)
        }
        val slope = numerator / denominator
        val intercept = meanY - slope * meanX
        if (!slope.isFinite() || !intercept.isFinite() || abs(slope) <= EPSILON) return null
        return mapOf("a" to slope, "b" to intercept)
    }

    /**
     * 使用正参数重参数化拟合 4PL/5PL。
     *
     * 斜率、半效浓度和5PL不对称参数分别在优化空间中保存为对数，因此天然大于0；曲线
     * 增强或减弱由低、高浓度两个渐近值的相对大小表示，不需要允许负斜率制造数值奇点。
     */
    private fun fitLogisticModel(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        fiveParameter: Boolean
    ): Map<String, Double>? {
        val model = logisticParametricFunction(fiveParameter)
        val observations = points.indices.map { index ->
            WeightedObservedPoint(weights[index], points[index].first, points[index].second)
        }
        val starts = logisticStartPoints(points, fiveParameter)
        var bestParameters: Map<String, Double>? = null
        var bestWeightedSse = Double.POSITIVE_INFINITY

        starts.forEach { start ->
            try {
                val rawParameters = SimpleCurveFitter.create(model, start)
                    .withMaxIterations(MAXIMUM_FIT_ITERATIONS)
                    .fit(observations)
                val parameters = decodeLogisticParameters(rawParameters, fiveParameter)
                if (!logisticParametersAreStable(parameters, points, fiveParameter)) {
                    return@forEach
                }
                val weightedSse = calculateWeightedSse(
                    points = points,
                    weights = weights,
                    function = if (fiveParameter) FittingFunction.LOGISTIC else FittingFunction.RODBARD,
                    parameters = parameters
                )
                if (weightedSse.isFinite() && weightedSse < bestWeightedSse) {
                    bestWeightedSse = weightedSse
                    bestParameters = parameters
                }
            } catch (_: Exception) {
                // 多起点优化允许单个初值失败；只有全部初值失败才返回 null。
            }
        }
        return bestParameters
    }

    /** 生成覆盖不同斜率、半效浓度和不对称程度的有限多起点集合。 */
    private fun logisticStartPoints(
        points: List<Pair<Double, Double>>,
        fiveParameter: Boolean
    ): List<DoubleArray> {
        val groupedMeans = points.groupBy { it.first }
            .toSortedMap()
            .mapValues { (_, values) -> values.map { it.second }.average() }
        val lowResponse = groupedMeans.entries.first().value
        val highResponse = groupedMeans.entries.last().value
        val responseRange = max(abs(highResponse - lowResponse), signalRange(points))
        val direction = if (highResponse >= lowResponse) 1.0 else -1.0
        val lowAsymptote = lowResponse - direction * responseRange * 0.05
        val highAsymptote = highResponse + direction * responseRange * 0.05
        val midpointResponse = (lowResponse + highResponse) / 2.0
        val positiveLevels = groupedMeans.keys.filter { it > 0.0 }
        val minimumPositive = positiveLevels.minOrNull() ?: MINIMUM_POSITIVE_CONCENTRATION
        val maximumPositive = positiveLevels.maxOrNull() ?: minimumPositive
        val geometricMiddle = sqrt(minimumPositive * maximumPositive)
        val responseMiddle = groupedMeans.minByOrNull { abs(it.value - midpointResponse) }
            ?.key
            ?.takeIf { it > 0.0 }
            ?: geometricMiddle
        val centers = linkedSetOf(responseMiddle, geometricMiddle)
            .map { max(it, MINIMUM_POSITIVE_CONCENTRATION) }
        val slopes = listOf(0.6, 1.2, 2.4)
        val asymmetries = if (fiveParameter) listOf(0.6, 1.0, 1.8) else listOf(1.0)

        return buildList {
            centers.forEach { center ->
                slopes.forEach { slope ->
                    asymmetries.forEach { asymmetry ->
                        val common = mutableListOf(
                            lowAsymptote,
                            ln(slope),
                            ln(center),
                            highAsymptote
                        )
                        if (fiveParameter) common += ln(asymmetry)
                        add(common.toDoubleArray())
                    }
                }
            }
        }
    }

    /** 4PL/5PL 的解析函数与对数参数空间雅可比。 */
    private fun logisticParametricFunction(fiveParameter: Boolean): ParametricUnivariateFunction {
        return object : ParametricUnivariateFunction {
            override fun value(x: Double, parameters: DoubleArray): Double {
                val decoded = decodeLogisticParameters(parameters, fiveParameter)
                return calculateSignal(
                    function = if (fiveParameter) FittingFunction.LOGISTIC else FittingFunction.RODBARD,
                    parameters = decoded,
                    concentration = x
                )
            }

            override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                val a = parameters[0]
                val slope = exp(parameters[1])
                val logCenter = parameters[2]
                val d = parameters[3]
                val asymmetry = if (fiveParameter) exp(parameters[4]) else 1.0
                val logRatio = if (x <= 0.0) 0.0 else ln(x) - logCenter
                val powered = if (x <= 0.0) 0.0 else safeExp(slope * logRatio)
                val denominator = 1.0 + powered
                val denominatorPowered = denominator.pow(asymmetry)
                val commonDenominator = denominator * denominatorPowered
                val derivativeA = 1.0 / denominatorPowered
                val derivativeLogSlope = if (x <= 0.0) 0.0 else
                    -(a - d) * asymmetry * powered * logRatio * slope / commonDenominator
                val derivativeLogCenter = if (x <= 0.0) 0.0 else
                    (a - d) * asymmetry * slope * powered / commonDenominator
                val derivativeD = 1.0 - derivativeA
                return if (fiveParameter) {
                    val derivativeLogAsymmetry =
                        -(a - d) * ln(denominator) * asymmetry / denominatorPowered
                    doubleArrayOf(
                        derivativeA,
                        derivativeLogSlope,
                        derivativeLogCenter,
                        derivativeD,
                        derivativeLogAsymmetry
                    )
                } else {
                    doubleArrayOf(
                        derivativeA,
                        derivativeLogSlope,
                        derivativeLogCenter,
                        derivativeD
                    )
                }
            }
        }
    }

    /** 将优化器中的对数参数还原为项目数据库使用的 a/b/c/d/g 参数。 */
    private fun decodeLogisticParameters(
        rawParameters: DoubleArray,
        fiveParameter: Boolean
    ): Map<String, Double> {
        return buildMap {
            put("a", rawParameters[0])
            put("b", exp(rawParameters[1]))
            put("c", exp(rawParameters[2]))
            put("d", rawParameters[3])
            if (fiveParameter) put("g", exp(rawParameters[4]))
        }
    }

    /** 拒绝数值发散、半效浓度远离实验区间或斜率/不对称参数极端的解。 */
    private fun logisticParametersAreStable(
        parameters: Map<String, Double>,
        points: List<Pair<Double, Double>>,
        fiveParameter: Boolean
    ): Boolean {
        if (parameters.values.any { !it.isFinite() }) return false
        val slope = parameters.getValue("b")
        val center = parameters.getValue("c")
        val asymmetry = if (fiveParameter) parameters.getValue("g") else 1.0
        if (slope !in MINIMUM_SLOPE..MAXIMUM_SLOPE) return false
        if (asymmetry !in MINIMUM_ASYMMETRY..MAXIMUM_ASYMMETRY) return false
        val positiveLevels = points.map { it.first }.filter { it > 0.0 }
        val minimumPositive = positiveLevels.minOrNull() ?: MINIMUM_POSITIVE_CONCENTRATION
        val maximumConcentration = points.maxOf { it.first }
        if (center < minimumPositive * MINIMUM_CENTER_RATIO ||
            center > max(maximumConcentration, minimumPositive) * MAXIMUM_CENTER_RATIO
        ) {
            return false
        }
        val yMin = points.minOf { it.second }
        val yMax = points.maxOf { it.second }
        val range = max(yMax - yMin, MINIMUM_SIGNAL_RANGE)
        val lowerBound = yMin - range * MAXIMUM_ASYMPTOTE_RANGE_MULTIPLIER
        val upperBound = yMax + range * MAXIMUM_ASYMPTOTE_RANGE_MULTIPLIER
        return parameters.getValue("a") in lowerBound..upperBound &&
            parameters.getValue("d") in lowerBound..upperBound
    }

    /** 构造候选并完成单调性、反算和 ICH 风格接受诊断。 */
    private fun buildCandidate(
        points: List<Pair<Double, Double>>,
        function: FittingFunction,
        parameters: Map<String, Double>,
        weighting: CalibrationWeighting,
        weights: List<Double>
    ): CalibrationFitCandidate? {
        val predicted = points.map { (concentration, _) ->
            calculateSignal(function, parameters, concentration)
        }
        if (predicted.any { !it.isFinite() }) return null
        val monotonic = isMonotonic(function, parameters, points)
        val positivePoints = points.filter { it.first > 0.0 }
        if (positivePoints.isEmpty()) return null
        val positiveLevels = positivePoints.map { it.first }.distinct().sorted()
        val minimumLevel = positiveLevels.first()
        val maximumLevel = positiveLevels.last()
        val inverseResults = positivePoints.map { (concentration, signal) ->
            val estimated = invertSignal(function, parameters, signal)
            val relativeErrorPercent = if (estimated.isFinite() && estimated >= 0.0) {
                abs(estimated - concentration) / concentration * 100.0
            } else {
                Double.POSITIVE_INFINITY
            }
            BackCalculationPoint(
                concentration = concentration,
                estimatedConcentration = estimated,
                relativeErrorPercent = relativeErrorPercent,
                tolerancePercent = if (concentration == minimumLevel || concentration == maximumLevel) {
                    ENDPOINT_TOLERANCE_PERCENT
                } else {
                    INTERNAL_TOLERANCE_PERCENT
                }
            )
        }
        val finiteErrors = inverseResults.map { it.relativeErrorPercent }.filter { it.isFinite() }
        if (finiteErrors.isEmpty()) return null
        val levelAcceptance = inverseResults.groupBy { it.concentration }.mapValues { (_, levelPoints) ->
            levelPoints.count { it.relativeErrorPercent <= it.tolerancePercent }.toDouble() /
                levelPoints.size.toDouble() >= MINIMUM_LEVEL_REPLICATE_PASS_RATIO
        }
        val acceptedStandardRatio = inverseResults.count {
            it.relativeErrorPercent <= it.tolerancePercent
        }.toDouble() / inverseResults.size.toDouble()
        val endpointPassCount = listOf(minimumLevel, maximumLevel).count { level ->
            levelAcceptance[level] == true
        }
        val acceptedLevelCount = levelAcceptance.values.count { it }
        val backCalculatedRmsePercent = sqrt(
            finiteErrors.sumOf { it * it } / finiteErrors.size.toDouble()
        )
        val maximumRelativeErrorPercent = finiteErrors.maxOrNull() ?: Double.POSITIVE_INFINITY
        val weightedSse = calculateWeightedSse(points, weights, function, parameters)
        val unweightedSse = points.indices.sumOf { index ->
            (points[index].second - predicted[index]).pow(2)
        }
        val parameterCount = parameterCount(function)
        val aicc = calculateAicc(weightedSse, points.size, parameterCount)
        val stable = monotonic && inverseResults.all {
            it.estimatedConcentration.isFinite() && it.estimatedConcentration >= 0.0
        }
        val ichAccepted = stable &&
            positiveLevels.size >= MINIMUM_ICH_LEVELS &&
            endpointPassCount == 2 &&
            acceptedStandardRatio >= MINIMUM_STANDARD_PASS_RATIO &&
            acceptedLevelCount >= MINIMUM_ICH_LEVELS

        return CalibrationFitCandidate(
            function = function,
            parameters = parameters,
            weighting = weighting,
            diagnostics = CalibrationFitDiagnostics(
                stable = stable,
                ichAccepted = ichAccepted,
                acceptedStandardRatio = acceptedStandardRatio,
                acceptedLevelCount = acceptedLevelCount,
                uniquePositiveLevelCount = positiveLevels.size,
                endpointPassCount = endpointPassCount,
                backCalculatedRmsePercent = backCalculatedRmsePercent,
                maximumRelativeErrorPercent = maximumRelativeErrorPercent,
                weightedSse = weightedSse,
                unweightedSse = unweightedSse,
                aicc = aicc
            )
        )
    }

    /** 在实验浓度范围内检查曲线方向唯一，禁止存在局部反转和多值反函数。 */
    private fun isMonotonic(
        function: FittingFunction,
        parameters: Map<String, Double>,
        points: List<Pair<Double, Double>>
    ): Boolean {
        val minimum = points.minOf { it.first }
        val maximum = points.maxOf { it.first }
        if (maximum <= minimum) return false
        val values = (0..MONOTONICITY_SAMPLE_COUNT).map { index ->
            val concentration = minimum + (maximum - minimum) *
                index.toDouble() / MONOTONICITY_SAMPLE_COUNT.toDouble()
            calculateSignal(function, parameters, concentration)
        }
        if (values.any { !it.isFinite() }) return false
        val totalChange = values.last() - values.first()
        val tolerance = max(signalRange(points) * MONOTONICITY_TOLERANCE_RATIO, EPSILON)
        if (abs(totalChange) <= tolerance) return false
        return if (totalChange > 0.0) {
            values.zipWithNext().all { (first, second) -> second + tolerance >= first }
        } else {
            values.zipWithNext().all { (first, second) -> second - tolerance <= first }
        }
    }

    /** 计算线性、4PL、5PL 正向响应。 */
    private fun calculateSignal(
        function: FittingFunction,
        parameters: Map<String, Double>,
        concentration: Double
    ): Double {
        return when (function) {
            FittingFunction.LINEAR ->
                parameters.getValue("a") * concentration + parameters.getValue("b")

            FittingFunction.RODBARD,
            FittingFunction.LOGISTIC -> {
                val a = parameters.getValue("a")
                val slope = parameters.getValue("b")
                val center = parameters.getValue("c")
                val d = parameters.getValue("d")
                val asymmetry = if (function == FittingFunction.LOGISTIC) {
                    parameters.getValue("g")
                } else {
                    1.0
                }
                val powered = if (concentration <= 0.0) {
                    0.0
                } else {
                    safeExp(slope * (ln(concentration) - ln(center)))
                }
                d + (a - d) / (1.0 + powered).pow(asymmetry)
            }

            else -> Double.NaN
        }
    }

    /** 使用解析反函数反算浓度，避免旧固定 0.001～1000 二分范围造成伪结果。 */
    private fun invertSignal(
        function: FittingFunction,
        parameters: Map<String, Double>,
        signal: Double
    ): Double {
        return when (function) {
            FittingFunction.LINEAR -> {
                val slope = parameters.getValue("a")
                if (abs(slope) <= EPSILON) Double.NaN
                else (signal - parameters.getValue("b")) / slope
            }

            FittingFunction.RODBARD,
            FittingFunction.LOGISTIC -> {
                val a = parameters.getValue("a")
                val slope = parameters.getValue("b")
                val center = parameters.getValue("c")
                val d = parameters.getValue("d")
                val asymmetry = if (function == FittingFunction.LOGISTIC) {
                    parameters.getValue("g")
                } else {
                    1.0
                }
                val signalOffset = signal - d
                if (abs(signalOffset) <= EPSILON || slope <= 0.0 || center <= 0.0 || asymmetry <= 0.0) {
                    return Double.NaN
                }
                val ratio = (a - d) / signalOffset
                if (!ratio.isFinite() || ratio <= 0.0) return Double.NaN
                val powered = ratio.pow(1.0 / asymmetry) - 1.0
                if (!powered.isFinite() || powered < -EPSILON) return Double.NaN
                if (powered <= EPSILON) 0.0 else center * powered.pow(1.0 / slope)
            }

            else -> Double.NaN
        }
    }

    /** 构建有限、归一化后的候选权重集合。 */
    private fun buildWeightSets(
        points: List<Pair<Double, Double>>
    ): Map<CalibrationWeighting, List<Double>> {
        val responseFloor = max(
            signalRange(points) * RESPONSE_WEIGHT_FLOOR_RATIO,
            max(points.map { abs(it.second) }.median() * RESPONSE_WEIGHT_FLOOR_RATIO, EPSILON)
        )
        val result = linkedMapOf(
            CalibrationWeighting.NONE to List(points.size) { 1.0 },
            CalibrationWeighting.INVERSE_RESPONSE to normalizeWeights(
                points.map { 1.0 / max(abs(it.second), responseFloor) }
            ),
            CalibrationWeighting.INVERSE_RESPONSE_SQUARED to normalizeWeights(
                points.map { 1.0 / max(abs(it.second), responseFloor).pow(2) }
            )
        )

        val grouped = points.withIndex().groupBy { it.value.first }
        val variances = grouped.mapValues { (_, indexedPoints) ->
            if (indexedPoints.size < 2) return@mapValues null
            val values = indexedPoints.map { it.value.second }
            val mean = values.average()
            values.sumOf { (it - mean).pow(2) } / (values.size - 1).toDouble()
        }
        val positiveVariances = variances.values.filterNotNull().filter { it > EPSILON }
        if (positiveVariances.size >= MINIMUM_VARIANCE_LEVELS) {
            val fallbackVariance = positiveVariances.median()
            val floorVariance = max(fallbackVariance * VARIANCE_FLOOR_RATIO, EPSILON)
            val inverseVarianceWeights = points.map { point ->
                1.0 / max(variances[point.first] ?: fallbackVariance, floorVariance)
            }
            result[CalibrationWeighting.INVERSE_VARIANCE] = normalizeWeights(inverseVarianceWeights)
        }
        return result
    }

    private fun normalizeWeights(weights: List<Double>): List<Double> {
        val finiteWeights = weights.map { if (it.isFinite() && it > 0.0) it else EPSILON }
        val mean = finiteWeights.average().coerceAtLeast(EPSILON)
        return finiteWeights.map { (it / mean).coerceIn(MINIMUM_WEIGHT, MAXIMUM_WEIGHT) }
    }

    private fun calculateWeightedSse(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        function: FittingFunction,
        parameters: Map<String, Double>
    ): Double {
        return points.indices.sumOf { index ->
            val predicted = calculateSignal(function, parameters, points[index].first)
            weights[index] * (points[index].second - predicted).pow(2)
        }
    }

    private fun calculateAicc(sse: Double, observationCount: Int, parameterCount: Int): Double {
        if (!sse.isFinite() || sse <= 0.0 || observationCount <= parameterCount + 1) {
            return Double.POSITIVE_INFINITY
        }
        val n = observationCount.toDouble()
        val k = parameterCount.toDouble()
        return n * ln(sse / n) + 2.0 * k +
            (2.0 * k * (k + 1.0)) / (n - k - 1.0)
    }

    private fun parameterCount(function: FittingFunction): Int = function.requiredParams.size

    private fun signalRange(points: List<Pair<Double, Double>>): Double {
        return max(points.maxOf { it.second } - points.minOf { it.second }, MINIMUM_SIGNAL_RANGE)
    }

    private fun safeExp(value: Double): Double = exp(value.coerceIn(MINIMUM_EXPONENT, MAXIMUM_EXPONENT))

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private const val MAXIMUM_FIT_ITERATIONS = 10_000
    private const val MONOTONICITY_SAMPLE_COUNT = 200
    private const val MINIMUM_ICH_LEVELS = 6
    private const val MINIMUM_VARIANCE_LEVELS = 2
    private const val MINIMUM_STANDARD_PASS_RATIO = 0.75
    private const val MINIMUM_LEVEL_REPLICATE_PASS_RATIO = 0.5
    private const val ENDPOINT_TOLERANCE_PERCENT = 25.0
    private const val INTERNAL_TOLERANCE_PERCENT = 20.0
    private const val FIVE_PARAMETER_SIGNIFICANCE_LEVEL = 0.05
    private const val MINIMUM_5PL_RMSE_RATIO = 0.95
    private const val MINIMUM_LOG_ASYMMETRY_DISTANCE = 0.1
    private const val RESPONSE_WEIGHT_FLOOR_RATIO = 0.01
    private const val VARIANCE_FLOOR_RATIO = 0.1
    private const val MONOTONICITY_TOLERANCE_RATIO = 1e-8
    private const val MINIMUM_SLOPE = 0.05
    private const val MAXIMUM_SLOPE = 20.0
    private const val MINIMUM_ASYMMETRY = 0.1
    private const val MAXIMUM_ASYMMETRY = 10.0
    private const val MINIMUM_CENTER_RATIO = 0.01
    private const val MAXIMUM_CENTER_RATIO = 100.0
    private const val MAXIMUM_ASYMPTOTE_RANGE_MULTIPLIER = 5.0
    private const val MINIMUM_WEIGHT = 1e-4
    private const val MAXIMUM_WEIGHT = 1e4
    private const val MINIMUM_EXPONENT = -700.0
    private const val MAXIMUM_EXPONENT = 700.0
    private const val MINIMUM_POSITIVE_CONCENTRATION = 1e-9
    private const val MINIMUM_SIGNAL_RANGE = 1e-9
    private const val EPSILON = 1e-12
}

/** 自动拟合采用的权重方案；数值码用于写入 FittingResult 的诊断指标。 */
internal enum class CalibrationWeighting(
    val metricCode: Double,
    val preferenceOrder: Int
) {
    NONE(metricCode = 0.0, preferenceOrder = 0),
    INVERSE_RESPONSE(metricCode = 1.0, preferenceOrder = 1),
    INVERSE_RESPONSE_SQUARED(metricCode = 2.0, preferenceOrder = 2),
    INVERSE_VARIANCE(metricCode = 3.0, preferenceOrder = 3)
}

/** 已完成拟合与科学诊断的内部候选。 */
internal data class CalibrationFitCandidate(
    val function: FittingFunction,
    val parameters: Map<String, Double>,
    val weighting: CalibrationWeighting,
    val diagnostics: CalibrationFitDiagnostics
)

/** 标准曲线候选的反算浓度与验收诊断。 */
internal data class CalibrationFitDiagnostics(
    val stable: Boolean,
    val ichAccepted: Boolean,
    val acceptedStandardRatio: Double,
    val acceptedLevelCount: Int,
    val uniquePositiveLevelCount: Int,
    val endpointPassCount: Int,
    val backCalculatedRmsePercent: Double,
    val maximumRelativeErrorPercent: Double,
    val weightedSse: Double,
    val unweightedSse: Double,
    val aicc: Double
) {
    /** 转换为现有 FittingResult 可直接保存和展示的数值指标。 */
    fun asMetrics(weighting: CalibrationWeighting): Map<String, Double> {
        return mapOf(
            "ICH M10 Accepted" to if (ichAccepted) 1.0 else 0.0,
            "Accepted Standard Ratio" to acceptedStandardRatio,
            "Accepted Concentration Levels" to acceptedLevelCount.toDouble(),
            "Unique Concentration Levels" to uniquePositiveLevelCount.toDouble(),
            "Endpoint Pass Count" to endpointPassCount.toDouble(),
            "Back-calculated RMSE (%)" to backCalculatedRmsePercent,
            "Maximum Relative Error (%)" to maximumRelativeErrorPercent,
            "Weighted SSE" to weightedSse,
            "Unweighted SSE" to unweightedSse,
            "AICc" to aicc,
            "Weighting Scheme" to weighting.metricCode
        )
    }
}

/** 单个标准点的反算结果，仅用于自动择优诊断。 */
private data class BackCalculationPoint(
    val concentration: Double,
    val estimatedConcentration: Double,
    val relativeErrorPercent: Double,
    val tolerancePercent: Double
)
