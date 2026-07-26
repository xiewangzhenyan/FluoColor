package com.muc.fluocolorquant.domain.result

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 运行级质量裁决与科学摘要。
 *
 * 结果页此前把 R²、重复孔 CV 和量程分布分散在"分析""验证"两个二级页里，首屏最显眼的
 * 位置反而给了载体名、8×12、圆形这类**配置信息**，三个指标卡也只是"已定量/估算/需复测"
 * 的处理状态计数。研究者打开结果页的第一个问题是"这批数据能不能用、测出来多少"，
 * 当前布局回答不了（AGENTS.md 7.3 要求结果页优先展示最重要的科学结论）。
 *
 * 本文件把该结论抽成纯函数：不依赖 Android、不依赖 Compose，可直接 JVM 单测，
 * 且 96 孔板与微流控共用同一套判定，避免两条链路各自演化出不同口径。
 *
 * 重要约束：
 * - 只做**归纳**，不重新计算科学量。R²、浓度值都来自运行冻结快照，此处不重新拟合。
 * - 缺少证据时返回 [ResultQualityLevel.UNKNOWN]，不猜测、不用默认值伪装成"良好"
 *   （AGENTS.md 11：数学上无法计算与低质量但可计算必须显示为不同状态）。
 */

/** 运行质量三态。顺序即严重程度，便于取多项判定中的最差值。 */
enum class ResultQualityLevel {
    /** 证据不足以判定，例如仅信号运行或没有标准曲线。 */
    UNKNOWN,

    /** 各项指标均达标。 */
    GOOD,

    /** 可以使用，但存在需要人工复核的指标。 */
    REVIEW,

    /** 关键指标不达标，不建议直接使用该批结果。 */
    UNRELIABLE
}

/**
 * 触发降级的具体原因。
 *
 * 用稳定的机器码而不是拼好的文案：用户可见文字必须资源化，且中英文由 UI 层决定
 * （AGENTS.md 5）。
 */
enum class ResultQualityIssue {
    /** 标准曲线 R² 低于阈值。 */
    LOW_R_SQUARED,

    /** 重复孔变异系数高于阈值。 */
    HIGH_REPEAT_CV,

    /** 需复测位点占比高于阈值。 */
    HIGH_RETEST_RATIO,

    /** 曲线外推得到的浓度占比偏高。 */
    HIGH_EXTRAPOLATION_RATIO
}

/**
 * 质量判定阈值。
 *
 * 默认值取生化检测常规：标准曲线 R² 0.99 以上视为良好、0.95 以下不建议使用；重复孔
 * CV 10% 是免疫比色/荧光常用上限；需复测比例 5% 对应 96 孔板约 5 个孔。
 *
 * 这些是经验值而非普适标准，不同分析物与试剂盒差异很大，因此做成可配置项由用户按
 * 自己的实验体系调整，而不是写死在判定逻辑里。
 */
data class ResultQualityThresholds(
    /** 达到该 R² 视为良好。 */
    val goodRSquared: Double = DEFAULT_GOOD_R_SQUARED,
    /** 低于该 R² 视为不建议使用；介于两者之间为待复核。 */
    val minimumRSquared: Double = DEFAULT_MINIMUM_R_SQUARED,
    /** 重复孔 CV 上限，单位为百分比。 */
    val maxRepeatCvPercent: Double = DEFAULT_MAX_REPEAT_CV_PERCENT,
    /** 需复测位点占比上限，取 0~1。 */
    val maxRetestRatio: Double = DEFAULT_MAX_RETEST_RATIO,
    /** 曲线外推浓度占比上限，取 0~1。 */
    val maxExtrapolationRatio: Double = DEFAULT_MAX_EXTRAPOLATION_RATIO
) {
    init {
        require(minimumRSquared <= goodRSquared) {
            "良好 R² 阈值不能低于最低可用 R² 阈值"
        }
        require(maxRepeatCvPercent > 0.0) { "CV 上限必须为正数" }
        require(maxRetestRatio in 0.0..1.0) { "需复测占比上限必须位于 0 到 1" }
        require(maxExtrapolationRatio in 0.0..1.0) { "外推占比上限必须位于 0 到 1" }
    }

    companion object {
        const val DEFAULT_GOOD_R_SQUARED: Double = 0.99
        const val DEFAULT_MINIMUM_R_SQUARED: Double = 0.95
        const val DEFAULT_MAX_REPEAT_CV_PERCENT: Double = 10.0
        const val DEFAULT_MAX_RETEST_RATIO: Double = 0.05
        const val DEFAULT_MAX_EXTRAPOLATION_RATIO: Double = 0.20

        val Default: ResultQualityThresholds = ResultQualityThresholds()
    }
}

/**
 * 单个分析物的运行质量摘要。
 *
 * 所有字段都可能为 null：仅信号运行没有浓度与曲线，旧历史快照可能缺少验证指标。
 * UI 必须按"缺失即隐藏"处理，不能用 0 或 "--" 冒充测量结果。
 */
data class ResultQualitySummary(
    val level: ResultQualityLevel,
    val issues: List<ResultQualityIssue>,
    /** 标准曲线决定系数；深度学习或仅信号运行为 null。 */
    val rSquared: Double?,
    /** 重复孔变异系数（百分比）；没有重复孔时为 null。 */
    val repeatCvPercent: Double?,
    /** 参与统计的浓度最小值。 */
    val concentrationMinimum: Double?,
    /** 参与统计的浓度最大值。 */
    val concentrationMaximum: Double?,
    /** 浓度中位数。相比均值更能抵抗个别离群孔的影响。 */
    val concentrationMedian: Double?,
    /** 在项目量程内的位点数。 */
    val inRangeCount: Int,
    /** 参与判定的有效位点总数。 */
    val evaluatedCount: Int,
    /** 需复测位点数。 */
    val retestCount: Int
) {
    /** 在量程内的比例；无有效位点时为 null，避免出现 0/0。 */
    val inRangeRatio: Double?
        get() = if (evaluatedCount <= 0) null else inRangeCount.toDouble() / evaluatedCount
}

/**
 * 计算质量摘要。
 *
 * @param rSquared 冻结快照中的 R²，仅信号或深度学习运行传 null。
 * @param concentrations 已成功定量的浓度列表，用于范围与中位数。
 * @param repeatGroups 重复孔分组的浓度列表，用于 CV；每组至少两个值才有意义。
 * @param evaluatedCount 参与判定的位点总数。
 * @param inRangeCount 位于项目量程内的位点数。
 * @param retestCount 需复测位点数。
 * @param extrapolatedCount 由曲线外推得到浓度的位点数。
 */
fun computeResultQualitySummary(
    rSquared: Double?,
    concentrations: List<Double>,
    repeatGroups: List<List<Double>>,
    evaluatedCount: Int,
    inRangeCount: Int,
    retestCount: Int,
    extrapolatedCount: Int,
    thresholds: ResultQualityThresholds = ResultQualityThresholds.Default
): ResultQualitySummary {
    val finite = concentrations.filter(Double::isFinite).sorted()
    val repeatCv = averageRepeatCvPercent(repeatGroups)

    val issues = mutableListOf<ResultQualityIssue>()
    var level = ResultQualityLevel.GOOD

    // R²：低于最低阈值直接判定为不可靠；介于两阈值之间为待复核。
    // 没有 R²（仅信号 / 深度学习）不构成降级理由，但会让整体判定退回 UNKNOWN。
    when {
        rSquared == null || !rSquared.isFinite() -> level = ResultQualityLevel.UNKNOWN
        rSquared < thresholds.minimumRSquared -> {
            issues += ResultQualityIssue.LOW_R_SQUARED
            level = ResultQualityLevel.UNRELIABLE
        }
        rSquared < thresholds.goodRSquared -> {
            issues += ResultQualityIssue.LOW_R_SQUARED
            level = level.degradeTo(ResultQualityLevel.REVIEW)
        }
    }

    if (repeatCv != null && repeatCv > thresholds.maxRepeatCvPercent) {
        issues += ResultQualityIssue.HIGH_REPEAT_CV
        level = level.degradeTo(ResultQualityLevel.REVIEW)
    }

    if (evaluatedCount > 0) {
        if (retestCount.toDouble() / evaluatedCount > thresholds.maxRetestRatio) {
            issues += ResultQualityIssue.HIGH_RETEST_RATIO
            level = level.degradeTo(ResultQualityLevel.REVIEW)
        }
        if (extrapolatedCount.toDouble() / evaluatedCount > thresholds.maxExtrapolationRatio) {
            issues += ResultQualityIssue.HIGH_EXTRAPOLATION_RATIO
            level = level.degradeTo(ResultQualityLevel.REVIEW)
        }
    }

    return ResultQualitySummary(
        level = level,
        issues = issues.toList(),
        rSquared = rSquared?.takeIf(Double::isFinite),
        repeatCvPercent = repeatCv,
        concentrationMinimum = finite.firstOrNull(),
        concentrationMaximum = finite.lastOrNull(),
        concentrationMedian = finite.median(),
        inRangeCount = inRangeCount,
        evaluatedCount = evaluatedCount,
        retestCount = retestCount
    )
}

/**
 * 只允许向更差的方向变化。
 *
 * UNKNOWN 表示证据不足，不应被后续任何一项"达标"改写成 GOOD；同样地，已经判定为
 * UNRELIABLE 也不会因为其他指标正常而回升。
 */
private fun ResultQualityLevel.degradeTo(target: ResultQualityLevel): ResultQualityLevel = when {
    this == ResultQualityLevel.UNKNOWN -> ResultQualityLevel.UNKNOWN
    target.ordinal > this.ordinal -> target
    else -> this
}

/**
 * 各重复孔组 CV 的平均值。
 *
 * 与结果导出使用同一口径：样本标准差（除以 n-1）除以均值。均值为 0 时 CV 无定义，
 * 该组跳过而不是记为 0——把无定义当成 0 会让"完全测不出信号"的孔看起来是完美重复。
 */
private fun averageRepeatCvPercent(repeatGroups: List<List<Double>>): Double? {
    val groupCvs = repeatGroups.mapNotNull { values ->
        val finite = values.filter(Double::isFinite)
        if (finite.size < 2) return@mapNotNull null
        val mean = finite.average()
        if (mean == 0.0) return@mapNotNull null
        val variance = finite.sumOf { value -> (value - mean) * (value - mean) } / (finite.size - 1)
        sqrt(variance) / abs(mean) * 100.0
    }
    return groupCvs.takeIf(List<Double>::isNotEmpty)?.average()
}

/** 已排序列表的中位数；偶数个取中间两值均值。 */
private fun List<Double>.median(): Double? = when {
    isEmpty() -> null
    size % 2 == 1 -> this[size / 2]
    else -> (this[size / 2 - 1] + this[size / 2]) / 2.0
}
