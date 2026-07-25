package com.muc.fluocolorquant.domain.calibration

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

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
                val points = draft.observations.mapNotNull { observation ->
                    val signal = observation.signals[feature]?.takeIf(Double::isFinite)
                        ?: return@mapNotNull null
                    observation.concentration to signal
                }
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
                fittingResults
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
            }
            val best = CalibrationRecommendationEngine.bestWithinFunction(
                candidates = candidates,
                policy = draft.policy
            )
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
            }
        )
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

    private companion object {
        const val SIGNAL_RANGE_EPSILON: Double = 1e-12
        const val CANDIDATE_MONOTONIC_SAMPLE_COUNT: Int = 257
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
    val weightingCode: Int = 0
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
            weightingCode = candidate.weightingCode
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
            CalibrationStrategy.R_SQUARED_FIRST -> pool.sortedWith(qualityComparator).first()

            CalibrationStrategy.SIMPLE_MODEL_FIRST -> pool.sortedWith(
                compareBy<T> { modelComplexity(metricsOf(it).function) }
                    .then(qualityComparator)
            ).first()

            CalibrationStrategy.ROBUST -> {
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
        return listOf(recommended) + candidates
            .filterNot { it === recommended || it == recommended }
            .sortedWith(qualityComparator)
    }

    private fun <T> candidateQualityComparator(
        metricsOf: (T) -> CalibrationRankingMetrics
    ): Comparator<T> = compareByDescending<T> { metricsOf(it).rSquared }
        .thenBy { metricsOf(it).backCalculatedRmsePercent ?: Double.POSITIVE_INFINITY }
        .thenByDescending { metricsOf(it).acceptedStandardRatio ?: 0.0 }
        .thenBy { metricsOf(it).normalizedRmse ?: Double.POSITIVE_INFINITY }
        // 原始 MAE 保留给同一信号的结果详情展示，但不能参与跨信号自动推荐。
        // 净荧光、SNR、ΔE 等特征量纲不同，直接比较 MAE 会把数值尺度误当成质量差异。
        .thenBy { metricsOf(it).weightingCode }

    private fun modelComplexity(function: FittingFunction): Int = when (function) {
        FittingFunction.LINEAR -> 2
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
        policy: CalibrationPolicy
    ): String {
        val canonical = buildString {
            append("analyte=").append(analyteId).append('\n')
            append("unit=").append(concentrationUnit).append('\n')
            append("processor=").append(processorVersion).append('\n')
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
