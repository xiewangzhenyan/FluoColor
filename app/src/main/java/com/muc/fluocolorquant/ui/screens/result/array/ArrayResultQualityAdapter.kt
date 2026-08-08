package com.muc.fluocolorquant.ui.screens.result.array

import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ResultRunSummary
import com.muc.fluocolorquant.domain.result.computeResultRunSummary

/**
 * 把冻结结果快照装配成运行质量裁决的输入。
 *
 * 放在 UI 层而不是 domain 层，是因为它依赖 [ArrayHeatmapModel] —— 那是展示用的派生模型，
 * 已经完成了"哪些位点算在量程内、哪些需复测"的判定。裁决逻辑本身仍在 domain 层且有单测，
 * 这里只做数据搬运，不引入新的科学判断。
 *
 * 96 孔板与微流控共用本适配：两者都以 `ArrayResultSnapshot` 为冻结数据，位点角色、
 * 重复孔分组和测量结构完全一致，没有理由维护两份口径。
 */

/** 只有真实样本参与统计，标准品与空白不计入浓度分布与重复孔 CV。 */
private const val SAMPLE_ROLE_CODE = "SAMPLE"

/**
 * 归纳当前分析物的运行摘要。
 *
 * @param model 已构建的热力图模型，提供超量程与外推计数。
 */
fun buildResultQualitySummary(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult,
    model: ArrayHeatmapModel
): ResultRunSummary {
    // 仅统计样本位点：把标准品浓度混进分布会让"浓度范围"直接等于标定范围，
    // 掩盖真实样本的实际分布。
    val sampleSites = snapshot.sites.filter { site ->
        site.enabled && site.roleCode == SAMPLE_ROLE_CODE
    }

    val concentrations = sampleSites.mapNotNull { site ->
        site.measurements
            .firstOrNull { it.analyteId == analyte.analyteId }
            ?.concentrationValue
            ?.takeIf(Double::isFinite)
    }

    // 重复孔按 repeatGroup 分组；未标注重复组的位点不参与 CV。
    val repeatGroups = sampleSites
        .filter { !it.repeatGroup.isNullOrBlank() }
        .groupBy { it.repeatGroup }
        .values
        .map { sites ->
            sites.mapNotNull { site ->
                site.measurements
                    .firstOrNull { it.analyteId == analyte.analyteId }
                    ?.concentrationValue
                    ?.takeIf(Double::isFinite)
            }
        }
        .filter { it.size >= 2 }

    // R² 的键在历史快照中出现过两种写法，兼容读取而不是只认其一。
    val rSquared = analyte.validationMetrics["R2"] ?: analyte.validationMetrics["R²"]

    // 仅信号运行没有浓度维度：不传 R² 与浓度，摘要条会据此显示"仅信号，未建立浓度"，
    // 而不是拿信号统计冒充浓度指标。
    val concentrationMode = model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION

    return computeResultRunSummary(
        rSquared = if (concentrationMode) rSquared else null,
        concentrations = if (concentrationMode) concentrations else emptyList(),
        repeatGroups = if (concentrationMode) repeatGroups else emptyList(),
        evaluatedCount = model.calculatedCount,
        outsideProjectRangeCount = model.outsideProjectRangeCount,
        extrapolatedCount = model.calibrationExtrapolatedCount
    )
}

/**
 * 仅信号运行的信号分布摘要。
 *
 * 没有标准曲线时浓度不存在，但信号本身仍有分布价值。这里单独返回信号的范围与中位数，
 * 供指标带在仅信号模式下替换浓度指标——信号绝不复用浓度的标签与单位，
 * 避免把原始信号伪装成浓度（AGENTS.md 11）。
 */
fun buildSignalDistribution(model: ArrayHeatmapModel): SignalDistribution {
    val values = model.cells.mapNotNull { it.displayValue?.takeIf(Double::isFinite) }.sorted()
    return SignalDistribution(
        minimum = values.firstOrNull(),
        maximum = values.lastOrNull(),
        median = when {
            values.isEmpty() -> null
            values.size % 2 == 1 -> values[values.size / 2]
            else -> (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        }
    )
}

/** 仅信号运行的信号分布；全部字段可空，没有有效信号时不得显示 0。 */
data class SignalDistribution(
    val minimum: Double?,
    val maximum: Double?,
    val median: Double?
)
