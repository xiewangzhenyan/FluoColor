package com.muc.fluocolorquant.domain.calibration

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.linear.SingularValueDecomposition
import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 96孔板和微流控规则阵列共用的现场标定引擎。
 *
 * 载体形状、定位和裁切已经在上游完成。本类只消费“位点×信号特征”矩阵，不依赖圆孔或
 * 方块，也不引用 Android Bitmap，因此能够用纯 JVM 测试稳定验证推荐规则。
 */
class ArrayCalibrationEngine @Inject constructor() {

    fun fit(draft: CalibrationDraft): CalibrationResultSet {
        // 自动模式由调用方传入策略中的默认函数；专家模式可以明确指定其他现有函数。
        // 这里不再与 DEFAULT_FUNCTIONS 二次求交，否则用户选择二次、多项式、Hill 等函数
        // 后仍会被悄悄丢弃。插值没有可审计的标定域外方程，不进入现场拟合候选。
        val allowedFunctions = draft.requestedFunctions
            .filterTo(linkedSetOf()) { it != FittingFunction.INTERPOLATION }
            .ifEmpty {
                draft.policy.allowedFunctions.filterTo(linkedSetOf()) {
                    it != FittingFunction.INTERPOLATION
                }
            }
        val uniqueLevels = draft.observations.map { it.concentration }.distinct().size

        val functionResults = allowedFunctions.map { function ->
            val minimumLevels = minimumLevels(function, draft.policy)
            if (uniqueLevels < minimumLevels) {
                return@map CalibrationFunctionResult(
                    function = function,
                    failureReasons = setOf(CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS)
                )
            }

            val candidates = draft.requestedFeatures.flatMap { feature ->
                val observed = draft.observations.mapNotNull { observation ->
                    val signal = observation.signals[feature]?.takeIf(Double::isFinite)
                        ?: return@mapNotNull null
                    RichCalibrationPoint(
                        concentration = observation.concentration,
                        signal = signal,
                        qualityReliable = observation.qualityReliable,
                        severeSaturation = observation.saturationRatio
                            ?.let { it.isFinite() && it >= SEVERE_STANDARD_SATURATION_RATIO } == true
                    )
                }
                val censoredPoints = observed.filter { point ->
                    point.severeSaturation && featureSupportsUpperSignalCensoring(feature)
                }.map { it.concentration to it.signal }
                val points = observed.filter { point ->
                    point.qualityReliable != false && !point.severeSaturation
                }.map { it.concentration to it.signal }
                if (points.map { it.first }.distinct().size < minimumLevels) {
                    return@flatMap emptyList()
                }
                // 对数和幂函数的底层拟合器会过滤定义域外点。如果不在这里阻止，用户输入
                // 0浓度后算法可能悄悄丢掉该标准点并继续拟合，页面却仍让用户误以为全部
                // 标准点都参与了计算。现场标定必须保持输入集合可审计，因此整条候选不可用。
                if (!pointsSatisfyFunctionDomain(function, points)) {
                    return@flatMap emptyList()
                }
                // 统一入口确保现场标定与标准曲线库对同一组函数采用同一候选生成规则。
                val fittingResults = FittingEngine.fitRequestedCalibrationFunctions(
                    dataPoints = points,
                    allowedFunctions = setOf(function)
                )
                val baseCandidates = fittingResults
                    .filter(FittingResult::isSuccess)
                    .filter { result ->
                        result.params.isNotEmpty() && result.params.values.all(Double::isFinite)
                    }
                    .filter { result ->
                        val weighting = result.metrics["Weighting Scheme"]?.toInt() ?: 0
                        weighting in draft.policy.enabledWeightingCodes
                    }
                    .map { result ->
                        result.toCandidate(
                            analyteId = draft.analyteId,
                            feature = feature,
                            policy = draft.policy
                        )
                    }
                    .filter(::candidateIsMonotonicOverCalibrationRange)
                    .filter { candidate ->
                        candidateSatisfiesCensoredStandards(candidate, censoredPoints)
                    }
                val crossValidationByWeighting = calculateLeaveOneLevelValidation(
                    points = points,
                    function = function,
                    policy = draft.policy,
                    weightingCodes = baseCandidates.map(CalibrationCandidate::weightingCode).toSet()
                )
                baseCandidates.map { candidate ->
                    candidate.copy(
                        crossValidation = crossValidationByWeighting[candidate.weightingCode]
                    )
                }
            }
            val best = CalibrationRecommendationEngine.bestWithinFunction(
                candidates = candidates,
                policy = draft.policy
            )?.let { candidate ->
                candidate.copy(
                    trustedRange = calculateTrustedRange(candidate, draft)
                )
            }
            if (best == null) {
                CalibrationFunctionResult(
                    function = function,
                    failureReasons = inferFailureReasons(
                        function = function,
                        draft = draft,
                        minimumLevels = minimumLevels
                    )
                )
            } else {
                CalibrationFunctionResult(function = function, candidate = best)
            }
        }

        val recommended = CalibrationRecommendationEngine.recommend(
            candidates = functionResults.mapNotNull(CalibrationFunctionResult::candidate),
            policy = draft.policy
        )
        return CalibrationResultSet(
            analyteId = draft.analyteId,
            inputFingerprint = draft.inputFingerprint,
            policySnapshot = draft.policy,
            functionResults = functionResults,
            recommendedCandidateId = recommended?.id,
            processorVersion = draft.processorVersion,
            engineVersion = draft.policy.engineVersion
        )
    }

    private fun minimumLevels(function: FittingFunction, policy: CalibrationPolicy): Int =
        when (function) {
            FittingFunction.HILL -> 5
            FittingFunction.RODBARD -> policy.minimumFourParameterLevels
            FittingFunction.LOGISTIC -> policy.minimumFiveParameterLevels
            FittingFunction.LINEAR -> 2
            else -> maxOf(2, function.requiredParams.size + 1)
        }

    private fun FittingResult.toCandidate(
        analyteId: String,
        feature: com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature,
        policy: CalibrationPolicy
    ): CalibrationCandidate {
        val rawRmse = metrics["RMSE"]?.takeIf(Double::isFinite)
        val signalMinimum = standardPoints.minOfOrNull { it.second }
        val signalMaximum = standardPoints.maxOfOrNull { it.second }
        val signalRange = if (signalMinimum != null && signalMaximum != null) {
            abs(signalMaximum - signalMinimum)
        } else {
            0.0
        }
        val normalizedRmse = rawRmse?.takeIf { signalRange > SIGNAL_RANGE_EPSILON }
            ?.div(signalRange)
        val hasBackCalculationDecision = metrics.containsKey("ICH M10 Accepted")
        val backCalculationAccepted = if (hasBackCalculationDecision) {
            (metrics["ICH M10 Accepted"] ?: 0.0) >= 1.0
        } else {
            // 二次、指数、对数和幂函数目前沿用通用拟合器，不会生成ICH专用诊断字段。
            // 这些候选仍必须通过参数有限、实验范围单调和后续严格反算门槛；这里不能因为
            // “缺少某个指标键”就把数学上可执行的专家候选全部误判为低质量。
            true
        }
        // 反算通过率只说明标准点落入宽松误差窗，不能替代拟合相关性。本次真实数据
        // R²=0.1774、反算通过率=100% 就是典型反例，因此必须同时通过R²质量门槛。
        val accepted = backCalculationAccepted &&
            rSquared.isFinite() &&
            rSquared >= policy.lowQualityRSquaredThreshold
        val weightingCode = metrics["Weighting Scheme"]?.toInt() ?: 0
        return CalibrationCandidate(
            id = "${feature.code}:${function.identifier}:$weightingCode",
            analyteId = analyteId,
            primaryFeature = feature,
            function = function,
            parameters = params,
            standardPoints = standardPoints,
            curvePoints = curvePoints,
            latexFormula = FittingEngine.formatParametersToLatex(function, params),
            rSquared = rSquared,
            rmse = rawRmse,
            normalizedRmse = normalizedRmse,
            mae = metrics["MAE"]?.takeIf(Double::isFinite),
            backCalculatedRmsePercent = metrics["Back-calculated RMSE (%)"]
                ?.takeIf(Double::isFinite),
            acceptedStandardRatio = metrics["Accepted Standard Ratio"]
                ?.takeIf(Double::isFinite),
            weightingCode = weightingCode,
            accepted = accepted,
            status = if (accepted) {
                CalibrationCandidateStatus.AVAILABLE
            } else {
                CalibrationCandidateStatus.LOW_QUALITY
            },
            robustObjectiveVersion = if (metrics.containsKey("Student-t Degrees Of Freedom")) {
                ROBUST_OBJECTIVE_VERSION
            } else {
                null
            }
        )
    }

    /**
     * 以“完整浓度水平”为单位执行留一预测验证。
     *
     * 同一浓度的重复孔必须一起移出训练集，否则重复孔之间会泄漏信息并人为抬高验证表现。
     * 这里只验证正浓度水平；零浓度/空白继续留在训练中约束基线，但相对浓度误差在零点
     * 没有定义，不能为了得到一个好看的百分比而添加任意分母。
     */
    private fun calculateLeaveOneLevelValidation(
        points: List<Pair<Double, Double>>,
        function: FittingFunction,
        policy: CalibrationPolicy,
        weightingCodes: Set<Int>
    ): Map<Int, CalibrationCrossValidationMetrics> {
        if (weightingCodes.isEmpty()) return emptyMap()
        val validationLevels = points.map(Pair<Double, Double>::first)
            .filter { it > 0.0 }
            .distinct()
            .sorted()
        if (validationLevels.size < MINIMUM_CROSS_VALIDATION_LEVELS) return emptyMap()

        val fullLevels = points.map(Pair<Double, Double>::first).distinct().sorted()
        val fullSignalRange = max(
            points.maxOf(Pair<Double, Double>::second) -
                points.minOf(Pair<Double, Double>::second),
            SIGNAL_RANGE_EPSILON
        )
        val accumulators = weightingCodes.associateWith { CrossValidationAccumulator() }

        validationLevels.forEach levelLoop@{ heldLevel ->
            val trainingPoints = points.filterNot { (concentration, _) -> concentration == heldLevel }
            if (trainingPoints.map(Pair<Double, Double>::first).distinct().size <
                minimumLevels(function, policy)
            ) {
                return@levelLoop
            }
            val refittedByWeighting = FittingEngine.fitRequestedCalibrationFunctions(
                dataPoints = trainingPoints,
                allowedFunctions = setOf(function)
            ).asSequence()
                .filter(FittingResult::isSuccess)
                .filter { it.params.isNotEmpty() && it.params.values.all(Double::isFinite) }
                .filter { result ->
                    val weightingCode = result.metrics["Weighting Scheme"]?.toInt() ?: 0
                    weightingCode in weightingCodes && weightingCode in policy.enabledWeightingCodes
                }
                .associateBy { result -> result.metrics["Weighting Scheme"]?.toInt() ?: 0 }

            weightingCodes.forEach weightingLoop@{ weightingCode ->
                val refitted = refittedByWeighting[weightingCode] ?: return@weightingLoop
                val heldOut = points.filter { (concentration, _) -> concentration == heldLevel }
                val errors = heldOut.mapNotNull { (concentration, signal) ->
                    FittingEngine.invertCalibrationSignal(
                        function = function,
                        params = refitted.params,
                        signal = signal
                    )?.let { estimated ->
                        abs(estimated - concentration) / concentration * 100.0
                    }?.takeIf(Double::isFinite)
                }
                // 一个浓度水平的任一重复孔无法反算，都说明该留一模型没有稳定预测这个水平。
                // 不能只保留成功孔，否则会把失败重复孔静默排除并夸大成功率。
                if (errors.size != heldOut.size) return@weightingLoop

                val levelRmse = sqrt(errors.sumOf { it.pow(2) } / errors.size.toDouble())
                val predictionGrid = fullLevels.map { concentration ->
                    FittingEngine.calculate(function, refitted.params, concentration)
                }
                if (predictionGrid.any { !it.isFinite() }) return@weightingLoop
                accumulators.getValue(weightingCode).recordSuccess(
                    heldLevel = heldLevel,
                    relativeErrorPercent = levelRmse,
                    predictionGrid = predictionGrid
                )
            }
        }

        val firstLevel = validationLevels.first()
        val lastLevel = validationLevels.last()
        return accumulators.mapValues { (_, accumulator) ->
            val sortedErrors = accumulator.levelErrors.values.sorted()
            val successRatio = accumulator.levelErrors.size.toDouble() / validationLevels.size.toDouble()
            CalibrationCrossValidationMetrics(
                validationLevelCount = validationLevels.size,
                successfulLevelCount = accumulator.levelErrors.size,
                successRatio = successRatio,
                medianRelativeErrorPercent = sortedErrors.percentile(0.50),
                p90RelativeErrorPercent = sortedErrors.percentile(0.90),
                endpointRelativeErrorPercent = listOfNotNull(
                    accumulator.levelErrors[firstLevel],
                    accumulator.levelErrors[lastLevel]
                ).takeIf { it.size == 2 }?.maxOrNull(),
                parameterStabilityScore = predictionStabilityScore(
                    predictionGrids = accumulator.predictionGrids,
                    signalRange = fullSignalRange
                )
            )
        }
    }

    /**
     * 使用留一模型在原浓度网格上的响应漂移衡量参数稳定性。
     *
     * 直接比较 a/b/c/d/g 的方差会受到单位和参数尺度影响；预测网格归一化后可以在
     * 线性、3PL、4PL、5PL 之间形成同一量纲的 0～1 分数。
     */
    private fun predictionStabilityScore(
        predictionGrids: List<List<Double>>,
        signalRange: Double
    ): Double? {
        if (predictionGrids.size < 2) return null
        val width = predictionGrids.firstOrNull()?.size ?: return null
        if (width == 0 || predictionGrids.any { it.size != width }) return null
        val normalizedSpreads = (0 until width).map { index ->
            val values = predictionGrids.map { it[index] }
            ((values.maxOrNull() ?: return null) - (values.minOrNull() ?: return null)) / signalRange
        }
        val medianSpread = normalizedSpreads.sorted().percentile(0.50) ?: return null
        return (1.0 - medianSpread).coerceIn(0.0, 1.0)
    }

    /**
     * 生成候选的连续可信估计范围和可重建参数采样元数据。
     *
     * 该方法只消费标准点、冻结候选和项目预期范围；未知样品信号从未进入范围生成，
     * 因而不会出现“为了减少越界孔而扩大范围”的数据泄漏。
     */
    private fun calculateTrustedRange(
        candidate: CalibrationCandidate,
        draft: CalibrationDraft
    ): CalibrationTrustedRange? {
        if (candidate.function !in TRUSTED_RANGE_FUNCTIONS || !candidate.accepted) return null
        val crossValidation = candidate.crossValidation ?: return null
        if (
            crossValidation.successRatio < MINIMUM_LOO_SUCCESS_RATIO ||
            crossValidation.endpointRelativeErrorPercent == null ||
            (crossValidation.parameterStabilityScore ?: 0.0) < MINIMUM_PARAMETER_STABILITY_SCORE
        ) return null

        val calibrationMinimum = candidate.standardPoints.minOfOrNull(Pair<Double, Double>::first)
            ?: return null
        val calibrationMaximum = candidate.standardPoints.maxOfOrNull(Pair<Double, Double>::first)
            ?: return null
        if (calibrationMinimum < 0.0 || calibrationMaximum <= calibrationMinimum) return null
        val uncertainty = estimateParameterUncertainty(candidate, draft.inputFingerprint) ?: return null
        val sampledParameters = sampleParameters(
            function = candidate.function,
            mean = uncertainty.transformedMean,
            covariance = uncertainty.covariance,
            seed = uncertainty.seed,
            sampleCount = TRUSTED_PARAMETER_SAMPLE_COUNT,
            calibrationMinimum = calibrationMinimum,
            calibrationMaximum = calibrationMaximum
        )
        if (sampledParameters.size != TRUSTED_PARAMETER_SAMPLE_COUNT) return null

        val requestedMinimum = draft.projectRangeMin
            ?.takeIf(Double::isFinite)
            ?.coerceAtLeast(0.0)
            ?: calibrationMinimum
        val requestedMaximum = draft.projectRangeMax
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: calibrationMaximum
        val scanMinimum = min(calibrationMinimum, requestedMinimum)
        val scanMaximum = max(
            calibrationMaximum,
            requestedMaximum * TRUSTED_PROJECT_MAXIMUM_MULTIPLIER
        )
        val lowerScan = scanTrustedDirection(
            candidate = candidate,
            start = calibrationMinimum,
            end = scanMinimum,
            sampledParameters = sampledParameters,
            residualSignalScale = uncertainty.residualSignalScale
        )
        val upperScan = scanTrustedDirection(
            candidate = candidate,
            start = calibrationMaximum,
            end = scanMaximum,
            sampledParameters = sampledParameters,
            residualSignalScale = uncertainty.residualSignalScale
        )
        val trustedMinimum = lowerScan.boundary
        val trustedMaximum = upperScan.boundary
        val actuallyExtended = trustedMinimum < calibrationMinimum - TRUSTED_CONCENTRATION_EPSILON ||
            trustedMaximum > calibrationMaximum + TRUSTED_CONCENTRATION_EPSILON
        if (!actuallyExtended) return null

        return CalibrationTrustedRange(
            minimum = trustedMinimum.coerceAtLeast(0.0),
            maximum = trustedMaximum,
            confidenceLevel = TRUSTED_INTERVAL_CONFIDENCE_LEVEL,
            parameterSampleCount = TRUSTED_PARAMETER_SAMPLE_COUNT,
            validSampleRatio = min(lowerScan.minimumValidRatio, upperScan.minimumValidRatio),
            methodVersion = TRUSTED_RANGE_METHOD_VERSION,
            transformedParameterCovariance = uncertainty.covariance.data.map(DoubleArray::toList),
            samplingSeed = uncertainty.seed,
            residualSignalScale = uncertainty.residualSignalScale
        )
    }

    /**
     * 在变换参数空间用数值雅可比近似 Hessian，并拒绝条件数过大的病态候选。
     * 正参数全部以对数表示，采样后不会产生负斜率、负半效浓度或负不对称参数。
     */
    private fun estimateParameterUncertainty(
        candidate: CalibrationCandidate,
        inputFingerprint: String
    ): ParameterUncertainty? {
        val mean = encodeTransformedParameters(candidate.function, candidate.parameters) ?: return null
        val points = candidate.standardPoints
        if (points.size <= mean.size) return null
        val jacobianData = Array(points.size) { DoubleArray(mean.size) }
        points.indices.forEach { row ->
            val concentration = points[row].first
            mean.indices.forEach { column ->
                val step = max(abs(mean[column]), 1.0) * NUMERICAL_JACOBIAN_STEP_RATIO
                val plus = mean.copyOf().also { it[column] += step }
                val minus = mean.copyOf().also { it[column] -= step }
                val plusParameters = decodeTransformedParameters(candidate.function, plus) ?: return null
                val minusParameters = decodeTransformedParameters(candidate.function, minus) ?: return null
                val plusSignal = FittingEngine.calculate(candidate.function, plusParameters, concentration)
                val minusSignal = FittingEngine.calculate(candidate.function, minusParameters, concentration)
                if (!plusSignal.isFinite() || !minusSignal.isFinite()) return null
                jacobianData[row][column] = (plusSignal - minusSignal) / (2.0 * step)
            }
        }
        val jacobian = Array2DRowRealMatrix(jacobianData, false)
        val hessian = jacobian.transpose().multiply(jacobian)
        // 极小对角线仅用于抵抗浮点舍入，不负责“救活”真正不可辨识的模型；条件数门槛
        // 仍会在下方拒绝参数共线或信息不足的候选。
        repeat(mean.size) { index ->
            hessian.addToEntry(index, index, HESSIAN_NUMERIC_RIDGE)
        }
        val decomposition = SingularValueDecomposition(hessian)
        val conditionNumber = decomposition.conditionNumber
        if (!conditionNumber.isFinite() || conditionNumber > MAXIMUM_HESSIAN_CONDITION_NUMBER) {
            return null
        }
        val residuals = points.map { (concentration, signal) ->
            signal - FittingEngine.calculate(candidate.function, candidate.parameters, concentration)
        }
        if (residuals.any { !it.isFinite() }) return null
        val residualMedian = residuals.sorted().percentile(0.50) ?: return null
        val mad = residuals.map { abs(it - residualMedian) }.sorted().percentile(0.50) ?: return null
        val signalRange = max(
            points.maxOf(Pair<Double, Double>::second) -
                points.minOf(Pair<Double, Double>::second),
            SIGNAL_RANGE_EPSILON
        )
        val residualSignalScale = max(
            PARAMETER_MAD_SCALE * mad,
            signalRange * MINIMUM_RESIDUAL_SCALE_RATIO
        )
        val degreesOfFreedom = max(points.size - mean.size, 1)
        val residualVariance = max(
            residuals.sumOf { it.pow(2) } / degreesOfFreedom.toDouble(),
            residualSignalScale.pow(2)
        )
        val covariance = decomposition.solver.inverse.scalarMultiply(residualVariance)
        if (covariance.data.any { row -> row.any { value -> !value.isFinite() } }) return null
        return ParameterUncertainty(
            transformedMean = mean,
            covariance = covariance,
            residualSignalScale = residualSignalScale,
            seed = deterministicSamplingSeed(inputFingerprint, candidate.id)
        )
    }

    /** 生成确定性的多元正态参数样本；同一冻结输入在历史重开时必须得到完全相同的区间。 */
    private fun sampleParameters(
        function: FittingFunction,
        mean: DoubleArray,
        covariance: org.apache.commons.math3.linear.RealMatrix,
        seed: Long,
        sampleCount: Int,
        calibrationMinimum: Double,
        calibrationMaximum: Double
    ): List<Map<String, Double>?> {
        val eigen = EigenDecomposition(covariance)
        val eigenvalues = eigen.realEigenvalues
        if (eigenvalues.any { it < -COVARIANCE_NEGATIVE_EIGEN_TOLERANCE }) return emptyList()
        val squareRootDiagonal = Array2DRowRealMatrix(mean.size, mean.size)
        eigenvalues.indices.forEach { index ->
            squareRootDiagonal.setEntry(index, index, sqrt(max(eigenvalues[index], 0.0)))
        }
        val transform = eigen.v.multiply(squareRootDiagonal)
        val random = Random(seed)
        val mainDirection = curveDirection(
            function = function,
            parameters = decodeTransformedParameters(function, mean) ?: return emptyList(),
            minimum = calibrationMinimum,
            maximum = calibrationMaximum
        ) ?: return emptyList()
        return List(sampleCount) {
            val gaussian = DoubleArray(mean.size) { random.nextGaussian() }
            val delta = transform.operate(gaussian)
            val sampled = DoubleArray(mean.size) { index -> mean[index] + delta[index] }
            val parameters = decodeTransformedParameters(function, sampled)
            parameters?.takeIf {
                curveDirection(function, it, calibrationMinimum, calibrationMaximum) == mainDirection
            }
        }
    }

    /** 从标定边界向一个方向连续扫描；首次失败后立即停止，禁止形成中间断裂的可信区间。 */
    private fun scanTrustedDirection(
        candidate: CalibrationCandidate,
        start: Double,
        end: Double,
        sampledParameters: List<Map<String, Double>?>,
        residualSignalScale: Double
    ): TrustedDirectionScan {
        if (abs(end - start) <= TRUSTED_CONCENTRATION_EPSILON) {
            return TrustedDirectionScan(start, 1.0)
        }
        var boundary = start
        var minimumValidRatio = 1.0
        repeat(TRUSTED_RANGE_SCAN_STEPS) { index ->
            val ratio = (index + 1).toDouble() / TRUSTED_RANGE_SCAN_STEPS.toDouble()
            val concentration = start + (end - start) * ratio
            val signal = FittingEngine.calculate(candidate.function, candidate.parameters, concentration)
            if (!signal.isFinite() || !farEnoughFromAsymptote(
                    function = candidate.function,
                    parameters = candidate.parameters,
                    concentration = concentration,
                    signal = signal,
                    residualSignalScale = residualSignalScale
                )
            ) return TrustedDirectionScan(boundary, minimumValidRatio)

            val sampledConcentrations = sampledParameters.mapNotNull { parameters ->
                parameters?.let {
                    FittingEngine.invertCalibrationSignal(candidate.function, it, signal)
                }
            }.sorted()
            val validRatio = sampledConcentrations.size.toDouble() / sampledParameters.size.toDouble()
            if (validRatio < MINIMUM_PARAMETER_SAMPLE_VALID_RATIO) {
                return TrustedDirectionScan(boundary, minimumValidRatio)
            }
            val lower = sampledConcentrations.percentile(TRUSTED_LOWER_QUANTILE)
                ?: return TrustedDirectionScan(boundary, minimumValidRatio)
            val upper = sampledConcentrations.percentile(TRUSTED_UPPER_QUANTILE)
                ?: return TrustedDirectionScan(boundary, minimumValidRatio)
            val median = sampledConcentrations.percentile(0.50)
                ?: return TrustedDirectionScan(boundary, minimumValidRatio)
            val denominator = max(
                abs(median),
                max(candidate.standardPoints.maxOf(Pair<Double, Double>::first) *
                    TRUSTED_RELATIVE_WIDTH_FLOOR_RATIO, TRUSTED_CONCENTRATION_EPSILON)
            )
            val relativeWidth = (upper - lower) / denominator
            if (!relativeWidth.isFinite() || relativeWidth > MAXIMUM_TRUSTED_INTERVAL_RELATIVE_WIDTH) {
                return TrustedDirectionScan(boundary, minimumValidRatio)
            }
            boundary = concentration
            minimumValidRatio = min(minimumValidRatio, validRatio)
        }
        return TrustedDirectionScan(boundary, minimumValidRatio)
    }

    private fun farEnoughFromAsymptote(
        function: FittingFunction,
        parameters: Map<String, Double>,
        concentration: Double,
        signal: Double,
        residualSignalScale: Double
    ): Boolean {
        val distance = when (function) {
            FittingFunction.HILL -> abs(parameters.getValue("a") - signal)
            FittingFunction.RODBARD,
            FittingFunction.LOGISTIC -> {
                val center = parameters.getValue("c")
                val asymptote = if (concentration >= center) {
                    parameters.getValue("d")
                } else {
                    parameters.getValue("a")
                }
                abs(signal - asymptote)
            }
            FittingFunction.LINEAR -> return true
            else -> return false
        }
        return distance >= residualSignalScale * MINIMUM_ASYMPTOTE_RESIDUAL_MULTIPLIER
    }

    private fun encodeTransformedParameters(
        function: FittingFunction,
        parameters: Map<String, Double>
    ): DoubleArray? {
        return try {
            val encoded = when (function) {
            FittingFunction.LINEAR -> doubleArrayOf(
                parameters.getValue("a"),
                parameters.getValue("b")
            )
            FittingFunction.HILL -> doubleArrayOf(
                ln(parameters.getValue("a")),
                ln(parameters.getValue("b")),
                ln(parameters.getValue("c"))
            )
            FittingFunction.RODBARD -> doubleArrayOf(
                parameters.getValue("a"),
                ln(parameters.getValue("b")),
                ln(parameters.getValue("c")),
                parameters.getValue("d")
            )
            FittingFunction.LOGISTIC -> doubleArrayOf(
                parameters.getValue("a"),
                ln(parameters.getValue("b")),
                ln(parameters.getValue("c")),
                parameters.getValue("d"),
                ln(parameters.getValue("g"))
            )
                else -> return null
            }
            encoded.takeIf { values -> values.all(Double::isFinite) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decodeTransformedParameters(
        function: FittingFunction,
        transformed: DoubleArray
    ): Map<String, Double>? {
        return try {
            val decoded = when (function) {
            FittingFunction.LINEAR -> mapOf("a" to transformed[0], "b" to transformed[1])
            FittingFunction.HILL -> mapOf(
                "a" to exp(transformed[0]),
                "b" to exp(transformed[1]),
                "c" to exp(transformed[2])
            )
            FittingFunction.RODBARD -> mapOf(
                "a" to transformed[0],
                "b" to exp(transformed[1]),
                "c" to exp(transformed[2]),
                "d" to transformed[3]
            )
            FittingFunction.LOGISTIC -> mapOf(
                "a" to transformed[0],
                "b" to exp(transformed[1]),
                "c" to exp(transformed[2]),
                "d" to transformed[3],
                "g" to exp(transformed[4])
            )
                else -> return null
            }
            decoded.takeIf { parameters -> parameters.values.all(Double::isFinite) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun curveDirection(
        function: FittingFunction,
        parameters: Map<String, Double>,
        minimum: Double,
        maximum: Double
    ): Int? {
        val first = FittingEngine.calculate(function, parameters, minimum)
        val last = FittingEngine.calculate(function, parameters, maximum)
        if (!first.isFinite() || !last.isFinite()) return null
        val delta = last - first
        return when {
            delta > SIGNAL_RANGE_EPSILON -> 1
            delta < -SIGNAL_RANGE_EPSILON -> -1
            else -> null
        }
    }

    private fun deterministicSamplingSeed(inputFingerprint: String, candidateId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$inputFingerprint|$candidateId".toByteArray(StandardCharsets.UTF_8))
        return digest.take(Long.SIZE_BYTES).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xffL)
        }
    }

    private fun List<Double>.percentile(probability: Double): Double? {
        if (isEmpty()) return null
        val bounded = probability.coerceIn(0.0, 1.0)
        val index = (ceil(bounded * size.toDouble()).toInt() - 1).coerceIn(indices)
        return this[index]
    }

    /**
     * 根据原始浓度水平特征推断用户可理解的失败原因。
     *
     * 4PL、5PL以及多数剂量响应函数要求单调趋势；当所有有效信号在浓度水平均值上都
     * 明显来回折返时，显示“信号趋势不单调”，不能继续用笼统的“拟合未收敛”掩盖原因。
     */
    private fun inferFailureReasons(
        function: FittingFunction,
        draft: CalibrationDraft,
        minimumLevels: Int
    ): Set<CalibrationFailureReason> {
        val featurePoints = draft.requestedFeatures.mapNotNull { feature ->
            val points = draft.observations.mapNotNull { observation ->
                observation.signals[feature]?.takeIf(Double::isFinite)?.let { signal ->
                    observation.concentration to signal
                }
            }
            points.takeIf { it.map(Pair<Double, Double>::first).distinct().size >= minimumLevels }
        }
        if (featurePoints.isEmpty()) return setOf(CalibrationFailureReason.NO_VALID_SIGNAL)
        if (featurePoints.all { points -> !pointsSatisfyFunctionDomain(function, points) }) {
            return setOf(CalibrationFailureReason.INVALID_FUNCTION_DOMAIN)
        }
        if (functionRequiresMonotonicResponse(function) && featurePoints.all { points ->
                !levelMeansAreMonotonic(points)
            }
        ) {
            return setOf(CalibrationFailureReason.CURVE_NOT_MONOTONIC)
        }
        return setOf(CalibrationFailureReason.FIT_DID_NOT_CONVERGE)
    }

    /**
     * 校验拟合变换的数学定义域，并保证任何标准点都不会被底层拟合器静默丢弃。
     *
     * - 对数函数需要全部浓度严格大于0；
     * - 幂函数的对数线性化同时要求浓度和响应严格大于0。
     */
    private fun pointsSatisfyFunctionDomain(
        function: FittingFunction,
        points: List<Pair<Double, Double>>
    ): Boolean = when (function) {
        FittingFunction.LOG -> points.all { (concentration, _) -> concentration > 0.0 }
        FittingFunction.POWER -> points.all { (concentration, signal) ->
            concentration > 0.0 && signal > 0.0
        }
        else -> true
    }

    /** 标准浓度水平的均值趋势只用于解释失败，不代替最终曲线的严格数学验证。 */
    private fun levelMeansAreMonotonic(points: List<Pair<Double, Double>>): Boolean {
        val means = points.groupBy(Pair<Double, Double>::first)
            .toSortedMap()
            .values
            .map { repeats -> repeats.map(Pair<Double, Double>::second).average() }
        if (means.size < 2) return false
        val signalSpan = (means.maxOrNull() ?: return false) - (means.minOrNull() ?: return false)
        val tolerance = maxOf(abs(signalSpan) * 1e-9, SIGNAL_RANGE_EPSILON)
        val differences = means.zipWithNext { first, second -> second - first }
        return differences.all { it > tolerance } || differences.all { it < -tolerance }
    }

    /**
     * 专家函数必须在实际标定浓度闭区间保持单调，才能进入可应用候选。
     * 最终运行仍会由 StandardCurveQuantifier 使用解析导数再次严格验证；这里的采样检查
     * 用于提前给UI结构化反馈，避免用户选中后才在结果阶段整体退回仅信号。
     */
    private fun candidateIsMonotonicOverCalibrationRange(candidate: CalibrationCandidate): Boolean {
        val minimum = candidate.standardPoints.minOfOrNull(Pair<Double, Double>::first) ?: return false
        val maximum = candidate.standardPoints.maxOfOrNull(Pair<Double, Double>::first) ?: return false
        if (!minimum.isFinite() || !maximum.isFinite() || maximum <= minimum) return false
        val values = buildList(CANDIDATE_MONOTONIC_SAMPLE_COUNT) {
            repeat(CANDIDATE_MONOTONIC_SAMPLE_COUNT) { index ->
                val ratio = index.toDouble() / (CANDIDATE_MONOTONIC_SAMPLE_COUNT - 1)
                val concentration = minimum + (maximum - minimum) * ratio
                val signal = runCatching {
                    FittingEngine.calculate(candidate.function, candidate.parameters, concentration)
                }.getOrNull()?.takeIf(Double::isFinite) ?: return false
                add(signal)
            }
        }
        val signalSpan = (values.maxOrNull() ?: return false) - (values.minOrNull() ?: return false)
        val tolerance = maxOf(abs(signalSpan) * 1e-10, SIGNAL_RANGE_EPSILON)
        val differences = values.zipWithNext { first, second -> second - first }
        return differences.all { it >= -tolerance } && differences.any { it > tolerance } ||
            differences.all { it <= tolerance } && differences.any { it < -tolerance }
    }

    private fun functionRequiresMonotonicResponse(function: FittingFunction): Boolean = when (function) {
        FittingFunction.GAMMA_VARIATE,
        FittingFunction.GAUSSIAN -> false
        else -> true
    }

    /**
     * 只有与光子/像素累积强度单调对应的特征才能把贴顶解释为“真实信号不低于记录值”。
     * SNR、ΔE、光密度等派生量在饱和后方向并不唯一，必须只标记质量失败而不能伪造删失边界。
     */
    private fun featureSupportsUpperSignalCensoring(
        feature: com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
    ): Boolean = feature in setOf(
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.RED_INTENSITY,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.GREEN_INTENSITY,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.BLUE_INTENSITY,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.AVERAGE_RGB,
        com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature.GRAY_LUMINOSITY
    )

    /**
     * 严重饱和标准点是右删失信号：模型在该已知浓度的预测不得明显低于记录下界。
     * 约束只用于淘汰物理矛盾候选，不把删失值重新塞回普通平方残差。
     */
    private fun candidateSatisfiesCensoredStandards(
        candidate: CalibrationCandidate,
        censoredPoints: List<Pair<Double, Double>>
    ): Boolean {
        if (censoredPoints.isEmpty()) return true
        val signalRange = max(
            candidate.standardPoints.maxOf(Pair<Double, Double>::second) -
                candidate.standardPoints.minOf(Pair<Double, Double>::second),
            SIGNAL_RANGE_EPSILON
        )
        val tolerance = signalRange * CENSORED_SIGNAL_TOLERANCE_RATIO
        return censoredPoints.all { (concentration, lowerSignalBound) ->
            val predicted = runCatching {
                FittingEngine.calculate(candidate.function, candidate.parameters, concentration)
            }.getOrNull()
            predicted?.isFinite() == true && predicted + tolerance >= lowerSignalBound
        }
    }

    private companion object {
        const val SIGNAL_RANGE_EPSILON: Double = 1e-12
        const val CANDIDATE_MONOTONIC_SAMPLE_COUNT: Int = 257
        const val MINIMUM_CROSS_VALIDATION_LEVELS: Int = 3
        const val SEVERE_STANDARD_SATURATION_RATIO: Double = 0.25
        const val CENSORED_SIGNAL_TOLERANCE_RATIO: Double = 0.02
        const val ROBUST_OBJECTIVE_VERSION: String = "student-t-irls-v1-nu4"
        const val TRUSTED_RANGE_METHOD_VERSION: String = "hessian-sampling-v1"
        const val TRUSTED_PARAMETER_SAMPLE_COUNT: Int = 256
        const val TRUSTED_RANGE_SCAN_STEPS: Int = 64
        const val TRUSTED_INTERVAL_CONFIDENCE_LEVEL: Double = 0.95
        const val TRUSTED_LOWER_QUANTILE: Double = 0.025
        const val TRUSTED_UPPER_QUANTILE: Double = 0.975
        const val TRUSTED_PROJECT_MAXIMUM_MULTIPLIER: Double = 1.5
        const val MINIMUM_LOO_SUCCESS_RATIO: Double = 0.95
        const val MINIMUM_PARAMETER_STABILITY_SCORE: Double = 0.50
        const val MINIMUM_PARAMETER_SAMPLE_VALID_RATIO: Double = 0.95
        const val MAXIMUM_TRUSTED_INTERVAL_RELATIVE_WIDTH: Double = 0.50
        const val MINIMUM_ASYMPTOTE_RESIDUAL_MULTIPLIER: Double = 3.0
        const val TRUSTED_RELATIVE_WIDTH_FLOOR_RATIO: Double = 0.01
        const val NUMERICAL_JACOBIAN_STEP_RATIO: Double = 1e-5
        const val HESSIAN_NUMERIC_RIDGE: Double = 1e-12
        const val MAXIMUM_HESSIAN_CONDITION_NUMBER: Double = 1e10
        const val COVARIANCE_NEGATIVE_EIGEN_TOLERANCE: Double = 1e-10
        const val PARAMETER_MAD_SCALE: Double = 1.4826
        const val MINIMUM_RESIDUAL_SCALE_RATIO: Double = 1e-6
        const val TRUSTED_CONCENTRATION_EPSILON: Double = 1e-9

        val TRUSTED_RANGE_FUNCTIONS: Set<FittingFunction> = setOf(
            FittingFunction.LINEAR,
            FittingFunction.HILL,
            FittingFunction.RODBARD,
            FittingFunction.LOGISTIC
        )
    }
}

private data class ParameterUncertainty(
    val transformedMean: DoubleArray,
    val covariance: org.apache.commons.math3.linear.RealMatrix,
    val residualSignalScale: Double,
    val seed: Long
)

private data class TrustedDirectionScan(
    val boundary: Double,
    val minimumValidRatio: Double
)

/** 标准孔在进入精确拟合或单侧删失门控前的内部富观测。 */
private data class RichCalibrationPoint(
    val concentration: Double,
    val signal: Double,
    val qualityReliable: Boolean?,
    val severeSaturation: Boolean
)

/** 单个权重方案在浓度水平留一过程中的可变累加器，只在一次同步拟合调用内存在。 */
private class CrossValidationAccumulator {
    val levelErrors: MutableMap<Double, Double> = linkedMapOf()
    val predictionGrids: MutableList<List<Double>> = mutableListOf()

    fun recordSuccess(
        heldLevel: Double,
        relativeErrorPercent: Double,
        predictionGrid: List<Double>
    ) {
        levelErrors[heldLevel] = relativeErrorPercent
        predictionGrids += predictionGrid
    }
}

/** 不依赖UI或具体信号枚举的候选排序指标，旧96孔板也能复用。 */
data class CalibrationRankingMetrics(
    val function: FittingFunction,
    val rSquared: Double,
    val accepted: Boolean,
    val backCalculatedRmsePercent: Double? = null,
    val acceptedStandardRatio: Double? = null,
    val normalizedRmse: Double? = null,
    val mae: Double? = null,
    val weightingCode: Int = 0,
    val crossValidation: CalibrationCrossValidationMetrics? = null
)

/** 按冻结策略为可执行候选排序，微流控、孔板和曲线库必须复用这一实现。 */
object CalibrationRecommendationEngine {

    fun bestWithinFunction(
        candidates: List<CalibrationCandidate>,
        policy: CalibrationPolicy
    ): CalibrationCandidate? = rank(candidates, policy).firstOrNull()

    fun recommend(
        candidates: List<CalibrationCandidate>,
        policy: CalibrationPolicy
    ): CalibrationCandidate? = rank(candidates, policy).firstOrNull()

    fun rank(
        candidates: List<CalibrationCandidate>,
        policy: CalibrationPolicy
    ): List<CalibrationCandidate> = rankByMetrics(candidates, policy) { candidate ->
        CalibrationRankingMetrics(
            function = candidate.function,
            rSquared = candidate.rSquared,
            accepted = candidate.accepted,
            backCalculatedRmsePercent = candidate.backCalculatedRmsePercent,
            acceptedStandardRatio = candidate.acceptedStandardRatio,
            normalizedRmse = candidate.normalizedRmse,
            mae = candidate.mae,
            weightingCode = candidate.weightingCode,
            crossValidation = candidate.crossValidation
        )
    }

    /**
     * 通用排序入口。
     *
     * 调用方负责先完成数学安全过滤；本函数只执行用户可配置的推荐策略。返回列表首项为
     * 推荐结果，其余候选按同一质量指标稳定排列，避免不同页面各写一套比较器。
     */
    fun <T> rankByMetrics(
        candidates: List<T>,
        policy: CalibrationPolicy,
        metricsOf: (T) -> CalibrationRankingMetrics
    ): List<T> {
        if (candidates.isEmpty()) return emptyList()
        val pool = if (policy.strategy == CalibrationStrategy.R_SQUARED_FIRST) {
            candidates
        } else {
            candidates.filter { metricsOf(it).accepted }.ifEmpty { candidates }
        }
        val qualityComparator = candidateQualityComparator(metricsOf)
        val recommended = when (policy.strategy) {
            CalibrationStrategy.R_SQUARED_FIRST -> pool.sortedWith(
                compareByDescending<T> { metricsOf(it).rSquared }
                    .then(qualityComparator)
            ).first()

            CalibrationStrategy.SIMPLE_MODEL_FIRST -> pool.sortedWith(
                compareBy<T> { modelComplexity(metricsOf(it).function) }
                    .then(qualityComparator)
            ).first()

            CalibrationStrategy.ROBUST -> {
                if (pool.any { metricsOf(it).crossValidation != null }) {
                    // V2 鲁棒推荐必须由真正的留一预测表现裁决。R²只保留为最后的同分项，
                    // 不再先用训练集 R² 容差把预测更稳定的简单模型排除出候选池。
                    pool.sortedWith(qualityComparator).first()
                } else {
                    // 旧资源、专家函数和不足以执行留一的稀疏数据没有 V2 指标，继续沿用
                    // 原有 R² 近似区间与简单模型保护，保证历史行为可解释且不突然漂移。
                    val bestRSquared = pool.maxOf { metricsOf(it).rSquared }
                    pool.filter { candidate ->
                        bestRSquared - metricsOf(candidate).rSquared <=
                            policy.rSquaredSimplicityTolerance
                    }.sortedWith(
                        compareBy<T> { modelComplexity(metricsOf(it).function) }
                            .then(qualityComparator)
                    ).first()
                }
            }
        }
        return listOf(recommended) + candidates
            .filterNot { it === recommended || it == recommended }
            .sortedWith(qualityComparator)
    }

    private fun <T> candidateQualityComparator(
        metricsOf: (T) -> CalibrationRankingMetrics
    ): Comparator<T> = compareByDescending<T> { metricsOf(it).crossValidation != null }
        .thenByDescending { metricsOf(it).crossValidation?.successRatio ?: -1.0 }
        .thenBy {
            metricsOf(it).crossValidation?.endpointRelativeErrorPercent
                ?: Double.POSITIVE_INFINITY
        }
        .thenBy {
            metricsOf(it).crossValidation?.medianRelativeErrorPercent
                ?: Double.POSITIVE_INFINITY
        }
        .thenBy {
            metricsOf(it).crossValidation?.p90RelativeErrorPercent
                ?: Double.POSITIVE_INFINITY
        }
        .thenBy { metricsOf(it).backCalculatedRmsePercent ?: Double.POSITIVE_INFINITY }
        .thenByDescending { metricsOf(it).acceptedStandardRatio ?: 0.0 }
        .thenByDescending {
            metricsOf(it).crossValidation?.parameterStabilityScore ?: -1.0
        }
        .thenBy { modelComplexity(metricsOf(it).function) }
        .thenBy { metricsOf(it).normalizedRmse ?: Double.POSITIVE_INFINITY }
        .thenByDescending { metricsOf(it).rSquared }
        // 原始 MAE 保留给同一信号的结果详情展示，但不能参与跨信号自动推荐。
        // 净荧光、SNR、ΔE 等特征量纲不同，直接比较 MAE 会把数值尺度误当成质量差异。
        .thenBy { metricsOf(it).weightingCode }

    private fun modelComplexity(function: FittingFunction): Int = when (function) {
        FittingFunction.LINEAR -> 2
        FittingFunction.HILL -> 3
        FittingFunction.RODBARD -> 4
        FittingFunction.LOGISTIC -> 5
        else -> function.requiredParams.size
    }
}

/** 为异步结果防陈旧覆盖和资源幂等保存生成稳定输入指纹。 */
object CalibrationInputFingerprint {

    fun create(
        analyteId: String,
        concentrationUnit: String,
        processorVersion: String,
        observations: List<CalibrationStandardObservation>,
        requestedFeatures: Set<com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature>,
        requestedFunctions: Set<FittingFunction>,
        policy: CalibrationPolicy,
        projectRangeMin: Double? = null,
        projectRangeMax: Double? = null
    ): String {
        val canonical = buildString {
            append("analyte=").append(analyteId).append('\n')
            append("unit=").append(concentrationUnit).append('\n')
            append("processor=").append(processorVersion).append('\n')
            append("projectRange=")
                .append(projectRangeMin?.let(java.lang.Double::toHexString) ?: "null")
                .append('|')
                .append(projectRangeMax?.let(java.lang.Double::toHexString) ?: "null")
                .append('\n')
            append("policy=").append(policy.schemaVersion).append('|')
                .append(policy.strategy.name).append('|')
                .append(java.lang.Double.toHexString(policy.rSquaredSimplicityTolerance))
                .append('|').append(java.lang.Double.toHexString(policy.lowQualityRSquaredThreshold))
                .append('|').append(policy.minimumFourParameterLevels)
                .append('|').append(policy.minimumFiveParameterLevels)
                .append('|').append(policy.lowQualityAction.name)
                .append('|').append(policy.saveToLibraryByDefault)
                .append('|').append(policy.engineVersion).append('\n')
            append("policyFunctions=")
                .append(policy.allowedFunctions.map(FittingFunction::identifier).sorted().joinToString(","))
                .append('\n')
            append("policyColorFeatures=")
                .append(policy.colorimetricFeatures.map { it.code }.sorted().joinToString(","))
                .append('\n')
            append("policyFluorescenceFeatures=")
                .append(policy.fluorescenceFeatures.map { it.code }.sorted().joinToString(","))
                .append('\n')
            append("policyWeightings=")
                .append(policy.enabledWeightingCodes.sorted().joinToString(","))
                .append('\n')
            append("functions=")
                .append(requestedFunctions.map(FittingFunction::identifier).sorted().joinToString(","))
                .append('\n')
            append("features=")
                .append(requestedFeatures.map { it.code }.sorted().joinToString(","))
                .append('\n')
            observations.sortedBy(CalibrationStandardObservation::siteIndex).forEach { observation ->
                append(observation.siteIndex).append(':')
                    .append(java.lang.Double.toHexString(observation.concentration))
                observation.signals.toSortedMap(compareBy { it.code }).forEach { (feature, signal) ->
                    append('|').append(feature.code).append('=')
                        .append(signal?.let(java.lang.Double::toHexString) ?: "null")
                }
                append("|quality=").append(observation.qualityReliable?.toString() ?: "unknown")
                append("|saturation=").append(
                    observation.saturationRatio?.let(java.lang.Double::toHexString) ?: "null"
                )
                append("|flags=").append(observation.photometryFlags.sorted().joinToString(","))
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * 可复用标准曲线资源的稳定内容指纹。
 *
 * 名称、创建时间和数据库 UUID 不属于科学内容；同一候选在重复点击、页面重建或模板
 * 保存时必须得到完全相同的指纹，从而复用已有资源。
 */
object CalibrationResourceFingerprint {

    fun create(
        analyteId: String,
        modalityCode: String,
        concentrationUnit: String,
        candidate: CalibrationCandidate,
        processorVersion: String,
        engineVersion: String
    ): String {
        val canonical = buildString {
            append("analyte=").append(analyteId).append('\n')
            append("modality=").append(modalityCode).append('\n')
            append("unit=").append(concentrationUnit).append('\n')
            append("feature=").append(candidate.primaryFeature.code).append('\n')
            append("function=").append(candidate.function.identifier).append('\n')
            append("processor=").append(processorVersion).append('\n')
            append("engine=").append(engineVersion).append('\n')
            append("weighting=").append(candidate.weightingCode).append('\n')
            append("robust=").append(candidate.robustObjectiveVersion ?: "legacy").append('\n')
            candidate.crossValidation?.let { validation ->
                append("loo=")
                    .append(validation.validationLevelCount).append('|')
                    .append(validation.successfulLevelCount).append('|')
                    .append(java.lang.Double.toHexString(validation.successRatio)).append('|')
                    .append(validation.medianRelativeErrorPercent?.let(java.lang.Double::toHexString))
                    .append('|')
                    .append(validation.p90RelativeErrorPercent?.let(java.lang.Double::toHexString))
                    .append('|')
                    .append(validation.endpointRelativeErrorPercent?.let(java.lang.Double::toHexString))
                    .append('|')
                    .append(validation.parameterStabilityScore?.let(java.lang.Double::toHexString))
                    .append('\n')
            }
            candidate.trustedRange?.let { trusted ->
                append("trusted=")
                    .append(java.lang.Double.toHexString(trusted.minimum)).append('|')
                    .append(java.lang.Double.toHexString(trusted.maximum)).append('|')
                    .append(java.lang.Double.toHexString(trusted.confidenceLevel)).append('|')
                    .append(trusted.parameterSampleCount).append('|')
                    .append(trusted.samplingSeed).append('|')
                    .append(trusted.methodVersion).append('\n')
                trusted.transformedParameterCovariance.forEachIndexed { row, values ->
                    append("covariance:").append(row).append('=')
                        .append(values.joinToString(",") { java.lang.Double.toHexString(it) })
                        .append('\n')
                }
            }
            candidate.parameters.toSortedMap().forEach { (name, value) ->
                append("parameter:").append(name).append('=')
                    .append(java.lang.Double.toHexString(value)).append('\n')
            }
            candidate.standardPoints.forEachIndexed { index, (concentration, signal) ->
                append("point:").append(index).append('=')
                    .append(java.lang.Double.toHexString(concentration)).append(',')
                    .append(java.lang.Double.toHexString(signal)).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
