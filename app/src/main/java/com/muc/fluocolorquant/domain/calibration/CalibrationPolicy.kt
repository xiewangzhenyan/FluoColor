package com.muc.fluocolorquant.domain.calibration

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction

/**
 * 自动推荐曲线时采用的用户级策略。
 *
 * 数学安全条件（参数有限、可反算、单调、非零斜率等）不属于可配置策略，任何模式都必须
 * 先通过这些条件。本枚举只决定已经可执行的候选之间如何排序。
 */
enum class CalibrationStrategy {
    /** R² 优先；R²近似相等时选择更简单的模型，并使用反算误差继续裁决。 */
    ROBUST,

    /** 严格选择 R² 最大的可执行候选，不启用简单模型保护。 */
    R_SQUARED_FIRST,

    /** 在质量可接受的候选中优先线性，其次4PL，最后5PL。 */
    SIMPLE_MODEL_FIRST
}

/** 低质量但数学上仍可执行的曲线应如何呈现。 */
enum class LowQualityCalibrationAction {
    /** 使用轻量提示，但允许科研用户自行确认应用。 */
    WARN_AND_ALLOW,

    /** 应用前要求用户再次确认。 */
    REQUIRE_CONFIRMATION,

    /** 仅允许查看，不允许用于计算浓度。 */
    VIEW_ONLY
}

/** 低质量候选点击“应用”时的统一业务裁决。 */
enum class CalibrationApplicationDecision {
    /** 候选可以直接冻结为本次运行曲线。 */
    APPLY,

    /** 必须先取得用户本次明确确认，不能仅依赖页面提示文字。 */
    REQUIRE_CONFIRMATION,

    /** 允许查看拟合结果，但禁止用于浓度计算。 */
    BLOCK
}

/**
 * 一次拟合使用的完整策略快照。
 *
 * 默认值只允许在这里定义。设置页、仓库和拟合引擎都必须读取 [DEFAULT]，避免多个页面
 * 分别维护一套看似相同、实际逐渐漂移的阈值。
 */
data class CalibrationPolicy(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val strategy: CalibrationStrategy = CalibrationStrategy.ROBUST,
    val allowedFunctions: Set<FittingFunction> = DEFAULT_FUNCTIONS,
    val rSquaredSimplicityTolerance: Double = 0.002,
    /** 低于该值的候选即使标准点反算通过，也只能作为低质量候选供用户复核。 */
    val lowQualityRSquaredThreshold: Double = 0.950,
    val minimumFourParameterLevels: Int = 5,
    val minimumFiveParameterLevels: Int = 6,
    val lowQualityAction: LowQualityCalibrationAction =
        LowQualityCalibrationAction.WARN_AND_ALLOW,
    val colorimetricFeatures: Set<AnalysisPrimaryFeature> = DEFAULT_COLORIMETRIC_FEATURES,
    val fluorescenceFeatures: Set<AnalysisPrimaryFeature> = DEFAULT_FLUORESCENCE_FEATURES,
    /** 是否允许无权重、1/Y、1/Y²和重复孔逆方差全部参加后台比较。 */
    val enabledWeightingCodes: Set<Int> = DEFAULT_WEIGHTING_CODES,
    /** 新打开的现场拟合结果是否默认开启“保存到曲线库”；用户仍可在结果页临时修改。 */
    val saveToLibraryByDefault: Boolean = false,
    val engineVersion: String = CALIBRATION_ENGINE_VERSION
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "不支持的曲线拟合策略版本" }
        require(rSquaredSimplicityTolerance in 0.0..0.020) { "R²近似阈值超出范围" }
        require(lowQualityRSquaredThreshold in 0.0..1.0) { "低质量R²阈值超出范围" }
        require(minimumFourParameterLevels >= 5) { "4PL至少需要5个浓度水平" }
        require(minimumFiveParameterLevels >= 6) { "5PL至少需要6个浓度水平" }
        require(allowedFunctions.isNotEmpty()) { "至少启用一种候选函数" }
        require(colorimetricFeatures.isNotEmpty()) { "比色至少启用一种候选信号" }
        require(fluorescenceFeatures.isNotEmpty()) { "荧光至少启用一种候选信号" }
        require(enabledWeightingCodes.isNotEmpty()) { "至少启用一种权重方案" }
        require(enabledWeightingCodes.all { it in 0..3 }) { "存在不支持的权重方案" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2

        val DEFAULT_FUNCTIONS: Set<FittingFunction> = linkedSetOf(
            FittingFunction.LINEAR,
            FittingFunction.QUADRATIC,
            FittingFunction.EXPONENTIAL,
            FittingFunction.LOG,
            FittingFunction.POWER,
            FittingFunction.RODBARD,
            FittingFunction.LOGISTIC
        )

        val DEFAULT_COLORIMETRIC_FEATURES: Set<AnalysisPrimaryFeature> = linkedSetOf(
            // 经典加权灰度是论文和既有96孔板中最常见的比色信号，必须位于推荐池首项。
            AnalysisPrimaryFeature.GRAY_LUMINOSITY,
            AnalysisPrimaryFeature.DELTA_E_2000,
            AnalysisPrimaryFeature.OPTICAL_DENSITY,
            AnalysisPrimaryFeature.RED_INTENSITY,
            AnalysisPrimaryFeature.GREEN_INTENSITY,
            AnalysisPrimaryFeature.BLUE_INTENSITY,
            AnalysisPrimaryFeature.AVERAGE_RGB,
            AnalysisPrimaryFeature.CIE_L_STAR,
            AnalysisPrimaryFeature.CIE_A_STAR,
            AnalysisPrimaryFeature.CIE_B_STAR
        )

        val DEFAULT_FLUORESCENCE_FEATURES: Set<AnalysisPrimaryFeature> = linkedSetOf(
            AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
            AnalysisPrimaryFeature.FLUORESCENCE_SNR
        )

        val DEFAULT_WEIGHTING_CODES: Set<Int> = linkedSetOf(0, 1, 2, 3)

        val DEFAULT: CalibrationPolicy = CalibrationPolicy()
    }
}

/** 新拟合必须写入资源和运行快照的标定引擎版本。 */
const val CALIBRATION_ENGINE_VERSION: String = "ArrayCalibration-v1"

/**
 * 根据拟合时冻结的策略裁决候选是否能够应用。
 *
 * 已通过验收的候选始终可直接应用；低质量候选才读取 [CalibrationPolicy.lowQualityAction]。
 * 该函数同时供 Compose 和 ViewModel 使用，避免页面允许但业务层拒绝，或绕过页面直接应用。
 */
fun CalibrationPolicy.applicationDecision(
    candidateAccepted: Boolean,
    userConfirmed: Boolean = false
): CalibrationApplicationDecision {
    if (candidateAccepted) return CalibrationApplicationDecision.APPLY
    return when (lowQualityAction) {
        LowQualityCalibrationAction.WARN_AND_ALLOW -> CalibrationApplicationDecision.APPLY
        LowQualityCalibrationAction.REQUIRE_CONFIRMATION -> {
            if (userConfirmed) {
                CalibrationApplicationDecision.APPLY
            } else {
                CalibrationApplicationDecision.REQUIRE_CONFIRMATION
            }
        }
        LowQualityCalibrationAction.VIEW_ONLY -> CalibrationApplicationDecision.BLOCK
    }
}
