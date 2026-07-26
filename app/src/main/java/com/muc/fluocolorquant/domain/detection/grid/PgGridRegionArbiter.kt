package com.muc.fluocolorquant.domain.detection.grid

/**
 * 区域假设仲裁规则。
 *
 * 单独成对象是为了让评分公式与切换保护成为**不依赖 OpenCV 的纯函数**，可以在 JVM 单元
 * 测试里直接覆盖门控边界；定位器只负责把图像证据喂进来。
 */
internal object PgGridRegionArbiter {

    /** 仲裁分中包围率的权重：抓“区域框截断”，是三项里最直接的一项。 */
    const val COVERAGE_WEIGHT: Double = 0.45

    /** 支撑率权重：抓“网格整体错位/虚构”。 */
    const val SUPPORT_WEIGHT: Double = 0.35

    /** 观测率权重：抓“拟合缺乏观测支撑”。 */
    const val OBSERVED_WEIGHT: Double = 0.20

    /** 晶格残差扣分的上限，避免单项残差把其余证据完全淹没。 */
    const val RESIDUAL_PENALTY_CAP: Double = 0.5

    /**
     * 换掉首选假设所需的最小分差。
     *
     * 仲裁分是几项带噪证据的加权和，零点几个百分点的领先不足以支持切换——实测有案例在
     * 0.428 vs 0.503 下切换后误差从 118% 恶化到 669%。
     */
    const val WIN_MARGIN: Double = 0.08

    /**
     * 最低证据线。
     *
     * 最优分低于此值说明没有任何假设拿到足够证据——通常是候选检测在该图上整体失效，
     * 让包围率与支撑率同时归零。“证据缺失”不等于“证据为负”，此时切换是赌博，应保留
     * 按可靠性排序的首选。
     */
    const val MINIMUM_EVIDENCE: Double = 0.55

    /**
     * 融合三项互补证据并按晶格残差扣分。
     *
     * 三项都已归一到 0~1；[residualRatio] 是晶格内点 RMSE 相对单元间距的比值，
     * 用来抓“勉强拟合但几何变形”的假设。
     */
    fun score(
        coverage: Double,
        support: Double,
        observed: Double,
        residualRatio: Double
    ): Double {
        return COVERAGE_WEIGHT * coverage +
            SUPPORT_WEIGHT * support +
            OBSERVED_WEIGHT * observed -
            minOf(residualRatio, RESIDUAL_PENALTY_CAP)
    }

    /**
     * 在多个假设中择优，带切换保护。
     *
     * [candidates] 必须按可靠性顺序给出，首项为默认假设。只有当最优项同时满足
     * “证据达到最低线”和“领先首选达到最小分差”时才发生切换。
     */
    fun <T> select(candidates: List<T>, score: (T) -> Double): T {
        require(candidates.isNotEmpty()) { "没有可选择的区域假设" }
        val first = candidates.first()
        val best = candidates.maxBy(score)
        if (best === first) return first
        if (score(best) < MINIMUM_EVIDENCE) return first
        if (score(best) - score(first) < WIN_MARGIN) return first
        return best
    }
}
