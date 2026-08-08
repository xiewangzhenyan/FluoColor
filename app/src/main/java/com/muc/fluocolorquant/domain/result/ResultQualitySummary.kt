package com.muc.fluocolorquant.domain.result

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 运行级科学摘要。
 *
 * 只做**事实归纳**，不做好坏判定。
 *
 * 早先的版本在这里实现过"良好 / 需复核 / 不建议使用"三态裁决。那个设计有两个实质错误：
 *
 * 1. **把样本超量程当成了数据质量问题。** 超量程是实验设计与样本浓度不匹配的正常现象，
 *    处理办法是稀释重测或补高浓度标准点，并不代表这次测量不可信。把它算作质量降级，
 *    等于用错误的科学口径给正常实验判刑。
 * 2. **阈值是凭"生化常规"臆断的**（超量程占比 5%、R² 0.99）。真实体系里 96 孔板出现
 *    几十个超量程孔完全正常，结果是每次打开结果页都在报警——既没帮上忙，也让软件显得
 *    在指责用户。
 *
 * 现在这里只统计事实：R²、重复孔 CV、浓度分布、各类计数。好坏由研究者按自己的实验体系
 * 判断，软件不代替他做这个判断，也不设任何阈值。
 *
 * 保持为 domain 层纯函数：不依赖 Android 与 Compose，可直接 JVM 单测，96 孔板与微流控
 * 共用同一口径，避免两条链路各自演化。
 *
 * 重要约束：只归纳，不重新计算科学量。R² 与浓度值都来自运行冻结快照，此处不重新拟合。
 */

/**
 * 单个分析物的运行摘要。
 *
 * 所有统计量都可能为 null：仅信号运行没有浓度与曲线，旧历史快照可能缺少验证指标，
 * 没有重复孔就没有 CV。UI 必须按"缺失即隐藏"处理，不能用 0 或占位数字冒充测量结果
 * （AGENTS.md 11）。
 */
data class ResultRunSummary(
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
    /** 参与统计的有效位点总数。 */
    val evaluatedCount: Int,
    /**
     * 超出**项目量程**的位点数。
     *
     * 与超出曲线标定范围分开统计：项目量程是用户在项目或模板里声明的关注范围，改设置即可；
     * 超出曲线标定范围才是真的无法可靠定量，需要补标准点。两者处理方式完全不同，
     * 合并成一个"需复测"数字会掩盖该差别。
     */
    val outsideProjectRangeCount: Int,
    /** 由曲线外推得到浓度的位点数。 */
    val extrapolatedCount: Int
)

/**
 * 归纳运行摘要。
 *
 * @param rSquared 冻结快照中的 R²，仅信号或深度学习运行传 null。
 * @param concentrations 已成功定量的浓度列表，用于范围与中位数。
 * @param repeatGroups 重复孔分组的浓度列表；每组至少两个值才有意义。
 * @param evaluatedCount 参与统计的位点总数。
 * @param outsideProjectRangeCount 超出项目量程的位点数。
 * @param extrapolatedCount 由曲线外推得到浓度的位点数。
 */
fun computeResultRunSummary(
    rSquared: Double?,
    concentrations: List<Double>,
    repeatGroups: List<List<Double>>,
    evaluatedCount: Int,
    outsideProjectRangeCount: Int,
    extrapolatedCount: Int
): ResultRunSummary {
    val finite = concentrations.filter(Double::isFinite).sorted()
    return ResultRunSummary(
        rSquared = rSquared?.takeIf(Double::isFinite),
        repeatCvPercent = averageRepeatCvPercent(repeatGroups),
        concentrationMinimum = finite.firstOrNull(),
        concentrationMaximum = finite.lastOrNull(),
        concentrationMedian = finite.median(),
        evaluatedCount = evaluatedCount,
        outsideProjectRangeCount = outsideProjectRangeCount,
        extrapolatedCount = extrapolatedCount
    )
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
