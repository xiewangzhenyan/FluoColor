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

private const val CALIBRATION_STUDENT_T_DEGREES_OF_FREEDOM: Double = 4.0

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
        FittingFunction.HILL,
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
                fitRobustLinear(points, weights)?.let { parameters ->
                    buildCandidate(points, FittingFunction.LINEAR, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }

            // Hill 3PL 的低浓度渐近线固定为物理零点。只有数据中存在零浓度/空白，且
            // 空白响应相对总信号跨度确实接近零时才允许参加自动比较；普通原始灰度或
            // 基线未扣除的信号不会被错误套用这个约束。
            if (
                FittingFunction.HILL in supportedFunctions &&
                uniqueLevels >= MINIMUM_HILL_LEVELS &&
                hillModelIsSupported(points)
            ) {
                fitRobustHillModel(points, weights)?.let { parameters ->
                    buildCandidate(points, FittingFunction.HILL, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }

            // 4PL 有四个参数，至少需要五个独立浓度水平，确保仍有残差自由度。
            if (FittingFunction.RODBARD in supportedFunctions && uniqueLevels >= 5) {
                fitRobustLogisticModel(points, weights, fiveParameter = false)?.let { parameters ->
                    buildCandidate(points, FittingFunction.RODBARD, parameters, weighting, weights)
                        ?.let(candidates::add)
                }
            }

            // 5PL 有五个参数，至少需要六个独立浓度水平；更少数据不允许自动拟合。
            if (FittingFunction.LOGISTIC in supportedFunctions && uniqueLevels >= 6) {
                fitRobustLogisticModel(points, weights, fiveParameter = true)?.let { parameters ->
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
     * 从已有解出发，按同一鲁棒估计器重新拟合单个函数与权重方案。
     *
     * 留一水平验证每折只移出一个浓度水平，最优解就在全量解附近；从全量解热启动可省去
     * 多起点搜索，热启动失败时仍回到多起点。只返回与 [rank] 相同门槛下稳定的解，失败
     * 返回 null，由调用方按该水平验证失败处理。
     */
    fun refit(
        dataPoints: List<Pair<Double, Double>>,
        function: FittingFunction,
        weightingCode: Int,
        warmStart: Map<String, Double>
    ): Map<String, Double>? {
        val points = dataPoints
            .filter { (concentration, signal) ->
                concentration.isFinite() && concentration >= 0.0 && signal.isFinite()
            }
            .sortedBy { it.first }
        val uniqueLevels = points.map { it.first }.distinct().size
        if (points.size < 2 || uniqueLevels < 2) return null
        val weighting = CalibrationWeighting.entries
            .firstOrNull { it.metricCode.toInt() == weightingCode }
            ?: return null
        val weights = buildWeightSets(points)[weighting] ?: return null
        val parameters = when (function) {
            FittingFunction.LINEAR -> fitRobustLinear(points, weights)
            FittingFunction.HILL -> if (uniqueLevels >= MINIMUM_HILL_LEVELS && hillModelIsSupported(points)) {
                fitRobustHillModel(points, weights, warmStart)
            } else {
                null
            }
            FittingFunction.RODBARD -> if (uniqueLevels >= 5) {
                fitRobustLogisticModel(points, weights, fiveParameter = false, warmStart = warmStart)
            } else {
                null
            }
            FittingFunction.LOGISTIC -> if (uniqueLevels >= 6) {
                fitRobustLogisticModel(points, weights, fiveParameter = true, warmStart = warmStart)
            } else {
                null
            }
            else -> null
        } ?: return null
        val candidate = buildCandidate(points, function, parameters, weighting, weights) ?: return null
        return parameters.takeIf { candidate.diagnostics.stable }
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
        val acceptedHill = candidates.filter {
            it.function == FittingFunction.HILL && it.diagnostics.ichAccepted
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
        val acceptedNonlinear = acceptedHill + acceptedFourParameter + acceptedFiveParameter
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

    /** 使用固定 ν=4 的 Student-t 权重迭代细化线性拟合。 */
    private fun fitRobustLinear(
        points: List<Pair<Double, Double>>,
        baseWeights: List<Double>
    ): Map<String, Double>? {
        var parameters = fitLinear(points, baseWeights) ?: return null
        var workingWeights = baseWeights
        repeat(STUDENT_T_IRLS_ITERATIONS) {
            val refined = studentTWeights(
                points = points,
                baseWeights = baseWeights,
                function = FittingFunction.LINEAR,
                parameters = parameters
            ) ?: return@repeat
            if (weightsHaveConverged(workingWeights, refined)) return parameters
            parameters = fitLinear(points, refined) ?: return parameters
            workingWeights = refined
        }
        return parameters
    }

    /**
     * 约束 Hill 3PL：y = a·x^b/(c^b+x^b)，a、b、c 均通过对数参数化保证大于零。
     *
     * 旧 fitHill() 在优化失败时会返回猜测参数，因此不能用于科研自动推荐。这里的多起点
     * 优化只有真实收敛且通过参数范围校验时才返回结果，全部失败则明确返回 null。
     */
    private fun fitHillModel(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        warmStart: Map<String, Double>? = null
    ): Map<String, Double>? {
        val function = object : ParametricUnivariateFunction {
            override fun value(x: Double, parameters: DoubleArray): Double {
                val decoded = decodeHillParameters(parameters)
                return calculateSignal(FittingFunction.HILL, decoded, x)
            }

            override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                if (x <= 0.0) return doubleArrayOf(0.0, 0.0, 0.0)
                val upper = exp(parameters[0])
                val slope = exp(parameters[1])
                val center = exp(parameters[2])
                val logRatio = ln(x) - ln(center)
                val powered = safeExp(slope * logRatio)
                val denominator = 1.0 + powered
                val fraction = powered / denominator
                val common = upper * powered / denominator.pow(2)
                return doubleArrayOf(
                    upper * fraction,
                    common * logRatio * slope,
                    -common * slope
                )
            }
        }
        val observations = points.indices.map { index ->
            WeightedObservedPoint(weights[index], points[index].first, points[index].second)
        }
        val positiveLevels = points.map(Pair<Double, Double>::first).filter { it > 0.0 }
        val minimumPositive = positiveLevels.minOrNull() ?: return null
        val maximumPositive = positiveLevels.maxOrNull() ?: return null
        val geometricMiddle = sqrt(minimumPositive * maximumPositive)
        val observedMaximum = max(points.maxOf(Pair<Double, Double>::second), MINIMUM_SIGNAL_RANGE)
        val starts = warmStart?.let { listOf(encodeHillParameters(it)) } ?: buildList {
            listOf(1.05, 1.25, 1.75).forEach { upperRatio ->
                listOf(0.6, 1.2, 2.4).forEach { slope ->
                    listOf(geometricMiddle, minimumPositive, maximumPositive).distinct()
                        .forEach { center ->
                            add(
                                doubleArrayOf(
                                    ln(observedMaximum * upperRatio),
                                    ln(slope),
                                    ln(max(center, MINIMUM_POSITIVE_CONCENTRATION))
                                )
                            )
                        }
                }
            }
        }
        var best: Map<String, Double>? = null
        var bestSse = Double.POSITIVE_INFINITY
        starts.forEach { start ->
            try {
                val raw = SimpleCurveFitter.create(function, start)
                    .withMaxIterations(MAXIMUM_FIT_ITERATIONS)
                    .fit(observations)
                val parameters = decodeHillParameters(raw)
                if (!hillParametersAreStable(parameters, points)) return@forEach
                val sse = calculateWeightedSse(
                    points = points,
                    weights = weights,
                    function = FittingFunction.HILL,
                    parameters = parameters
                )
                if (sse.isFinite() && sse < bestSse) {
                    best = parameters
                    bestSse = sse
                }
            } catch (_: Exception) {
                // 多起点中的单个初值失败是正常数值事件；全部失败时函数返回 null。
            }
        }
        return best
    }

    /** 使用 Student-t IRLS 细化 Hill 3PL；重加权从上一轮解热启动。 */
    private fun fitRobustHillModel(
        points: List<Pair<Double, Double>>,
        baseWeights: List<Double>,
        warmStart: Map<String, Double>? = null
    ): Map<String, Double>? {
        var parameters = fitHillModelFrom(points, baseWeights, warmStart) ?: return null
        var workingWeights = baseWeights
        repeat(STUDENT_T_IRLS_ITERATIONS) {
            val refined = studentTWeights(
                points = points,
                baseWeights = baseWeights,
                function = FittingFunction.HILL,
                parameters = parameters
            ) ?: return@repeat
            if (weightsHaveConverged(workingWeights, refined)) return parameters
            parameters = fitHillModelFrom(points, refined, parameters) ?: return parameters
            workingWeights = refined
        }
        return parameters
    }

    /** 给定解时先单起点热启动，失败或未给定时回到多起点。 */
    private fun fitHillModelFrom(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        warmStart: Map<String, Double>?
    ): Map<String, Double>? =
        warmStart?.let { fitHillModel(points, weights, it) } ?: fitHillModel(points, weights)

    private fun decodeHillParameters(raw: DoubleArray): Map<String, Double> = mapOf(
        "a" to exp(raw[0]),
        "b" to exp(raw[1]),
        "c" to exp(raw[2])
    )

    private fun encodeHillParameters(parameters: Map<String, Double>): DoubleArray = doubleArrayOf(
        ln(parameters.getValue("a")),
        ln(parameters.getValue("b")),
        ln(parameters.getValue("c"))
    )

    /** Hill 3PL 只接受基线已归零、方向递增且不存在大幅局部折返的数据。 */
    private fun hillModelIsSupported(points: List<Pair<Double, Double>>): Boolean {
        val means = points.groupBy(Pair<Double, Double>::first)
            .toSortedMap()
            .mapValues { (_, repeats) -> repeats.map(Pair<Double, Double>::second).average() }
        val blank = means[0.0] ?: return false
        val positiveMeans = means.filterKeys { it > 0.0 }.values.toList()
        if (positiveMeans.size < MINIMUM_HILL_LEVELS - 1) return false
        val range = max(points.maxOf(Pair<Double, Double>::second) -
            points.minOf(Pair<Double, Double>::second), MINIMUM_SIGNAL_RANGE)
        if (abs(blank) > range * MAXIMUM_HILL_BLANK_RATIO) return false
        if (positiveMeans.last() - blank < range * MINIMUM_HILL_RESPONSE_RATIO) return false
        return (listOf(blank) + positiveMeans)
            .zipWithNext()
            .all { (left, right) -> right + range * MAXIMUM_HILL_LOCAL_DROP_RATIO >= left }
    }

    private fun hillParametersAreStable(
        parameters: Map<String, Double>,
        points: List<Pair<Double, Double>>
    ): Boolean {
        if (parameters.values.any { !it.isFinite() }) return false
        val upper = parameters.getValue("a")
        val slope = parameters.getValue("b")
        val center = parameters.getValue("c")
        if (upper <= 0.0 || slope !in MINIMUM_SLOPE..MAXIMUM_SLOPE || center <= 0.0) return false
        val positiveLevels = points.map(Pair<Double, Double>::first).filter { it > 0.0 }
        val minimumPositive = positiveLevels.minOrNull() ?: return false
        val maximumPositive = positiveLevels.maxOrNull() ?: return false
        if (center < minimumPositive * MINIMUM_CENTER_RATIO ||
            center > maximumPositive * MAXIMUM_CENTER_RATIO
        ) return false
        val observedMaximum = points.maxOf(Pair<Double, Double>::second)
        val range = signalRange(points)
        return upper >= observedMaximum * MINIMUM_HILL_UPPER_RATIO &&
            upper <= observedMaximum + range * MAXIMUM_ASYMPTOTE_RANGE_MULTIPLIER
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
        fiveParameter: Boolean,
        warmStart: Map<String, Double>? = null
    ): Map<String, Double>? {
        val model = logisticParametricFunction(fiveParameter)
        val observations = points.indices.map { index ->
            WeightedObservedPoint(weights[index], points[index].first, points[index].second)
        }
        val starts = warmStart?.let { listOf(encodeLogisticParameters(it, fiveParameter)) }
            ?: logisticStartPoints(points, fiveParameter)
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
        val fitted = bestParameters ?: return null
        return if (fiveParameter) {
            regularizeFiveParameter(points, weights, fitted, continueFromFree = warmStart != null)
        } else {
            fitted
        }
    }

    /**
     * 对 5PL 不对称参数 g 施加向 g=1（即4PL）的收缩。
     *
     * 首先取得自由 5PL 解，再在自由解与1之间生成确定性 g 网格；每个 g 固定后重新拟合
     * a/b/c/d，最终最小化“加权残差 + λ·ln(g)²”。这是真正进入拟合裁决的正则项，
     * 不是仅在结果排序阶段给复杂模型加一个标签。热启动时（[continueFromFree]）固定 g 的
     * 子拟合从自由解的 a/b/c/d 出发，失败时再回到多起点。
     */
    private fun regularizeFiveParameter(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        freeParameters: Map<String, Double>,
        continueFromFree: Boolean = false
    ): Map<String, Double> {
        val freeAsymmetry = freeParameters["g"]
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return freeParameters
        val logAsymmetry = ln(freeAsymmetry)
        val asymmetryGrid = linkedSetOf(1.0)
        listOf(0.25, 0.50, 0.75, 1.0).forEach { ratio ->
            asymmetryGrid += exp(logAsymmetry * ratio)
        }
        val candidates = buildList {
            add(freeParameters)
            asymmetryGrid.forEach { asymmetry ->
                val continued = if (continueFromFree) {
                    fitLogisticWithFixedAsymmetry(points, weights, asymmetry, continueFrom = freeParameters)
                } else {
                    null
                }
                (continued ?: fitLogisticWithFixedAsymmetry(points, weights, asymmetry))?.let(::add)
            }
        }
        val residualDegreesOfFreedom = max(
            points.size - parameterCount(FittingFunction.LOGISTIC),
            1
        )
        val freeWeightedSse = calculateWeightedSse(
            points = points,
            weights = weights,
            function = FittingFunction.LOGISTIC,
            parameters = freeParameters
        )
        // 正则强度必须随实验噪声而变化。若真实数据几乎由不对称5PL精确生成，残差方差
        // 接近零，不能用总信号跨度制造一个巨大的固定惩罚把 g 强行推回1；当残差较大时，
        // 对额外不对称自由度的收缩才应增强。
        val residualVariance = max(
            freeWeightedSse / residualDegreesOfFreedom.toDouble(),
            signalRange(points).pow(2) * FIVE_PARAMETER_MINIMUM_NOISE_RATIO
        )
        val penaltyScale = residualVariance * FIVE_PARAMETER_REGULARIZATION_RATIO
        return candidates.minByOrNull { parameters ->
            val sse = calculateWeightedSse(
                points = points,
                weights = weights,
                function = FittingFunction.LOGISTIC,
                parameters = parameters
            )
            sse + penaltyScale * ln(parameters.getValue("g")).pow(2)
        } ?: freeParameters
    }

    /** 在固定 g 下重新拟合其余四个参数，失败时明确返回 null。 */
    private fun fitLogisticWithFixedAsymmetry(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        asymmetry: Double,
        continueFrom: Map<String, Double>? = null
    ): Map<String, Double>? {
        if (!asymmetry.isFinite() || asymmetry !in MINIMUM_ASYMMETRY..MAXIMUM_ASYMMETRY) {
            return null
        }
        val model = logisticParametricFunction(
            fiveParameter = true,
            fixedAsymmetry = asymmetry
        )
        val observations = points.indices.map { index ->
            WeightedObservedPoint(weights[index], points[index].first, points[index].second)
        }
        var best: Map<String, Double>? = null
        var bestSse = Double.POSITIVE_INFINITY
        val starts = continueFrom?.let { listOf(encodeLogisticParameters(it, fiveParameter = false)) }
            ?: logisticStartPoints(points, fiveParameter = false)
        starts.forEach { start ->
            try {
                val raw = SimpleCurveFitter.create(model, start)
                    .withMaxIterations(MAXIMUM_FIT_ITERATIONS)
                    .fit(observations)
                val parameters = decodeLogisticParameters(
                    rawParameters = raw,
                    fiveParameter = true,
                    fixedAsymmetry = asymmetry
                )
                if (!logisticParametersAreStable(parameters, points, fiveParameter = true)) {
                    return@forEach
                }
                val sse = calculateWeightedSse(
                    points = points,
                    weights = weights,
                    function = FittingFunction.LOGISTIC,
                    parameters = parameters
                )
                if (sse.isFinite() && sse < bestSse) {
                    best = parameters
                    bestSse = sse
                }
            } catch (_: Exception) {
                // 固定 g 网格允许局部拟合失败；其余 g 候选仍可继续比较。
            }
        }
        return best
    }

    /**
     * 使用固定自由度 Student-t IRLS 细化 4PL/5PL，异常点只被柔性降权而不会被删除。
     *
     * 只有首轮在多起点中搜索；重加权只轻微改变目标函数，后续各轮从上一轮解热启动，
     * 热启动失败时才回到多起点。否则每轮重复全部初值，宽量程、多重复孔的标准系列在
     * 留一水平验证中会把拟合时间放大十余倍。
     */
    private fun fitRobustLogisticModel(
        points: List<Pair<Double, Double>>,
        baseWeights: List<Double>,
        fiveParameter: Boolean,
        warmStart: Map<String, Double>? = null
    ): Map<String, Double>? {
        val function = if (fiveParameter) FittingFunction.LOGISTIC else FittingFunction.RODBARD
        var parameters = fitLogisticModelFrom(points, baseWeights, fiveParameter, warmStart) ?: return null
        var workingWeights = baseWeights
        repeat(STUDENT_T_IRLS_ITERATIONS) {
            val refined = studentTWeights(
                points = points,
                baseWeights = baseWeights,
                function = function,
                parameters = parameters
            ) ?: return@repeat
            if (weightsHaveConverged(workingWeights, refined)) return parameters
            parameters = fitLogisticModelFrom(points, refined, fiveParameter, parameters) ?: return parameters
            workingWeights = refined
        }
        return parameters
    }

    /** 给定解时先单起点热启动，失败或未给定时回到多起点。 */
    private fun fitLogisticModelFrom(
        points: List<Pair<Double, Double>>,
        weights: List<Double>,
        fiveParameter: Boolean,
        warmStart: Map<String, Double>?
    ): Map<String, Double>? =
        warmStart?.let { fitLogisticModel(points, weights, fiveParameter, warmStart = it) }
            ?: fitLogisticModel(points, weights, fiveParameter)

    /** [decodeLogisticParameters] 的逆变换；4PL 忽略 g。 */
    private fun encodeLogisticParameters(
        parameters: Map<String, Double>,
        fiveParameter: Boolean
    ): DoubleArray {
        val common = mutableListOf(
            parameters.getValue("a"),
            ln(parameters.getValue("b")),
            ln(parameters.getValue("c")),
            parameters.getValue("d")
        )
        if (fiveParameter) common += ln(parameters["g"] ?: 1.0)
        return common.toDoubleArray()
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
    private fun logisticParametricFunction(
        fiveParameter: Boolean,
        fixedAsymmetry: Double? = null
    ): ParametricUnivariateFunction {
        return object : ParametricUnivariateFunction {
            override fun value(x: Double, parameters: DoubleArray): Double {
                val decoded = decodeLogisticParameters(
                    rawParameters = parameters,
                    fiveParameter = fiveParameter,
                    fixedAsymmetry = fixedAsymmetry
                )
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
                val optimizeAsymmetry = fiveParameter && fixedAsymmetry == null
                val asymmetry = fixedAsymmetry ?: if (optimizeAsymmetry) exp(parameters[4]) else 1.0
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
                return if (optimizeAsymmetry) {
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
        fiveParameter: Boolean,
        fixedAsymmetry: Double? = null
    ): Map<String, Double> {
        return buildMap {
            put("a", rawParameters[0])
            put("b", exp(rawParameters[1]))
            put("c", exp(rawParameters[2]))
            put("d", rawParameters[3])
            if (fiveParameter) {
                put("g", fixedAsymmetry ?: exp(rawParameters[4]))
            }
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
        // 数学安全只看曲线本身：在标准浓度范围内单调，且每个标准水平在曲线上可逆。
        // 重复孔信号因噪声越过渐近线时无法反算，只算作该孔复算未通过，降低接受率并影响
        // 验收与可信量程，不能据此丢弃整条曲线；否则跨多个数量级、平台区有重复孔的标准
        // 系列上，4PL/5PL 会被整条淘汰，界面只能显示兜底的“拟合未收敛”。
        val curveInvertible = positiveLevels.all { level ->
            val roundTrip = invertSignal(function, parameters, calculateSignal(function, parameters, level))
            roundTrip.isFinite() && roundTrip >= 0.0
        }
        val stable = monotonic && curveInvertible
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

            FittingFunction.HILL -> {
                val upper = parameters.getValue("a")
                val slope = parameters.getValue("b")
                val center = parameters.getValue("c")
                if (concentration <= 0.0) {
                    0.0
                } else {
                    val powered = safeExp(slope * (ln(concentration) - ln(center)))
                    upper * powered / (1.0 + powered)
                }
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

            FittingFunction.HILL -> {
                val upper = parameters.getValue("a")
                val slope = parameters.getValue("b")
                val center = parameters.getValue("c")
                if (upper <= 0.0 || slope <= 0.0 || center <= 0.0) return Double.NaN
                if (abs(signal) <= EPSILON) return 0.0
                val remaining = upper - signal
                if (signal <= 0.0 || remaining <= EPSILON) return Double.NaN
                center * (signal / remaining).pow(1.0 / slope)
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

    /**
     * 根据当前残差生成 Student-t IRLS 权重。
     *
     * 自由度固定为4，避免在只有少量浓度水平时再估计一个不可辨识的噪声参数。鲁棒尺度
     * 使用 MAD，并设置为总信号跨度的最小比例，防止近乎完美数据因除以极小数而产生
     * 极端权重。返回权重仍会归一化到均值1，保持不同基础权重方案可比较。
     */
    private fun studentTWeights(
        points: List<Pair<Double, Double>>,
        baseWeights: List<Double>,
        function: FittingFunction,
        parameters: Map<String, Double>
    ): List<Double>? {
        val residuals = points.map { (concentration, signal) ->
            signal - calculateSignal(function, parameters, concentration)
        }
        if (residuals.any { !it.isFinite() }) return null
        val residualMedian = residuals.median()
        val mad = residuals.map { abs(it - residualMedian) }.median()
        val scale = max(
            STUDENT_T_MAD_SCALE * mad,
            signalRange(points) * STUDENT_T_MINIMUM_SCALE_RATIO
        )
        if (!scale.isFinite() || scale <= EPSILON) return baseWeights
        val robustWeights = residuals.indices.map { index ->
            val standardized = residuals[index] / scale
            val influence = (CALIBRATION_STUDENT_T_DEGREES_OF_FREEDOM + 1.0) /
                (CALIBRATION_STUDENT_T_DEGREES_OF_FREEDOM + standardized.pow(2))
            baseWeights[index] * influence
        }
        return normalizeWeights(robustWeights)
    }

    private fun weightsHaveConverged(
        previous: List<Double>,
        current: List<Double>
    ): Boolean {
        if (previous.size != current.size) return false
        return previous.indices.maxOfOrNull { index ->
            abs(previous[index] - current[index]) / max(abs(previous[index]), EPSILON)
        }?.let { it <= STUDENT_T_WEIGHT_CONVERGENCE_RATIO } ?: true
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
    private const val MINIMUM_HILL_LEVELS = 5
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
    private const val FIVE_PARAMETER_REGULARIZATION_RATIO = 1.0
    private const val FIVE_PARAMETER_MINIMUM_NOISE_RATIO = 1e-8
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
    private const val MAXIMUM_HILL_BLANK_RATIO = 0.15
    private const val MINIMUM_HILL_RESPONSE_RATIO = 0.35
    private const val MAXIMUM_HILL_LOCAL_DROP_RATIO = 0.12
    private const val MINIMUM_HILL_UPPER_RATIO = 0.90
    private const val STUDENT_T_IRLS_ITERATIONS = 4
    private const val STUDENT_T_MAD_SCALE = 1.4826
    private const val STUDENT_T_MINIMUM_SCALE_RATIO = 0.01
    private const val STUDENT_T_WEIGHT_CONVERGENCE_RATIO = 1e-3
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
            "Weighting Scheme" to weighting.metricCode,
            // 数值指标表只能保存 Double，因此用固定自由度作为鲁棒目标已启用的稳定证据。
            "Student-t Degrees Of Freedom" to CALIBRATION_STUDENT_T_DEGREES_OF_FREEDOM
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
