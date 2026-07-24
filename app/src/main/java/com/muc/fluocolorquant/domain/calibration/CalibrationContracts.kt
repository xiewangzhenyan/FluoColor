package com.muc.fluocolorquant.domain.calibration

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction

/** 一个标准孔在一次现场标定中的浓度和多信号观测。 */
data class CalibrationStandardObservation(
    val siteIndex: Int,
    val concentration: Double,
    val signals: Map<AnalysisPrimaryFeature, Double?>
)

/**
 * 现场标定草稿。
 *
 * 草稿只在编辑和拟合期间存在，不写入曲线资源库。图像处理层应先一次性构建每个标准孔
 * 的信号矩阵，再交给通用阵列拟合引擎，禁止每比较一种函数就重新读取或处理图片。
 */
data class CalibrationDraft(
    val analyteId: String,
    val modality: DetectionModality,
    val concentrationUnit: String,
    val observations: List<CalibrationStandardObservation>,
    val requestedFeatures: Set<AnalysisPrimaryFeature>,
    val requestedFunctions: Set<FittingFunction>,
    val processorVersion: String,
    val policy: CalibrationPolicy,
    val inputFingerprint: String
)

/** 普通页面能够解释的候选不可用原因；UI负责映射为中英文字符串资源。 */
enum class CalibrationFailureReason {
    INSUFFICIENT_STANDARD_LEVELS,
    NO_VALID_SIGNAL,
    FIT_DID_NOT_CONVERGE,
    PARAMETERS_NOT_FINITE,
    CURVE_NOT_MONOTONIC,
    CONCENTRATION_NOT_INVERTIBLE,
    SLOPE_TOO_SMALL
}

/** 单个函数在结果页中的状态。 */
enum class CalibrationCandidateStatus {
    AVAILABLE,
    LOW_QUALITY,
    UNAVAILABLE
}

/**
 * 一个可以被用户选择并冻结的曲线候选。
 *
 * 原始 RMSE/MAE 只用于同一信号内部解释；跨信号排序使用 R²、标准化 RMSE 和反算浓度
 * 误差，避免把净荧光和 SNR 等不同量纲直接比较。
 */
data class CalibrationCandidate(
    val id: String,
    val analyteId: String,
    val primaryFeature: AnalysisPrimaryFeature,
    val function: FittingFunction,
    val parameters: Map<String, Double>,
    val standardPoints: List<Pair<Double, Double>>,
    val curvePoints: List<Pair<Double, Double>>,
    val latexFormula: String,
    val rSquared: Double,
    val rmse: Double?,
    val normalizedRmse: Double?,
    val mae: Double?,
    val backCalculatedRmsePercent: Double?,
    val acceptedStandardRatio: Double?,
    val weightingCode: Int,
    val accepted: Boolean,
    val status: CalibrationCandidateStatus
)

/** 结果页固定显示一个函数槽位；拟合失败时仍保留槽位和结构化原因。 */
data class CalibrationFunctionResult(
    val function: FittingFunction,
    val candidate: CalibrationCandidate? = null,
    val failureReasons: Set<CalibrationFailureReason> = emptySet()
) {
    val status: CalibrationCandidateStatus
        get() = candidate?.status ?: CalibrationCandidateStatus.UNAVAILABLE
}

/**
 * 一次现场拟合的完整结果集。
 *
 * 该对象永远非空。即使所有函数均不可用，也会返回线性、4PL、5PL三个带失败原因的
 * [CalibrationFunctionResult]，确保UI进入结果阶段而不是静默退回“开始拟合”。
 */
data class CalibrationResultSet(
    val analyteId: String,
    val inputFingerprint: String,
    val policySnapshot: CalibrationPolicy,
    val functionResults: List<CalibrationFunctionResult>,
    val recommendedCandidateId: String?,
    val processorVersion: String,
    val engineVersion: String
) {
    val candidates: List<CalibrationCandidate>
        get() = functionResults.mapNotNull(CalibrationFunctionResult::candidate)

    fun candidate(candidateId: String?): CalibrationCandidate? =
        candidates.firstOrNull { it.id == candidateId }
}

/** 单分析物最终使用的定量方式稳定编码。 */
enum class AnalyteQuantitationMethod {
    ONSITE_CALIBRATION,
    STANDARD_CURVE_RESOURCE,
    DEEP_LEARNING_MODEL,
    SIGNAL_ONLY
}

/** 应用现场曲线后冻结的不可变曲线内容。 */
data class AppliedCalibrationSnapshot(
    val primaryFeature: String,
    val fittingFunction: String,
    val parameters: Map<String, Double>,
    val standardPoints: List<Pair<Double, Double>>,
    val latexFormula: String,
    val reliableRangeMin: Double,
    val reliableRangeMax: Double,
    val rSquared: Double,
    val rmse: Double?,
    val normalizedRmse: Double?,
    val mae: Double?,
    val backCalculatedRmsePercent: Double?,
    val acceptedStandardRatio: Double?,
    val weightingCode: Int,
    val accepted: Boolean,
    val policySnapshot: CalibrationPolicy,
    val processorVersion: String,
    val engineVersion: String,
    val inputFingerprint: String
)

/**
 * 单个分析物在项目配置阶段形成的不可变定量快照。
 *
 * 它不是完整 DetectionRun；只有全部分析物完成并点击计算时，协调器才会把这些快照、
 * 布局、定位和采集信息组合成最终运行快照。
 */
data class AnalyteQuantitationSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val analyteId: String,
    val method: AnalyteQuantitationMethod,
    val concentrationUnit: String,
    val sourceResourceId: String? = null,
    val calibration: AppliedCalibrationSnapshot? = null,
    val processorVersion: String,
    val inputFingerprint: String
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * 现场标定明确状态机。
 *
 * 科学上没有可用曲线不是技术异常，而是 [Reviewing] 中所有函数均不可用；只有线程、
 * 图像或程序错误才进入 [TechnicalFailure]。
 */
sealed interface OnsiteCalibrationState {
    data object Editing : OnsiteCalibrationState

    data class Fitting(
        val requestId: String,
        val inputFingerprint: String
    ) : OnsiteCalibrationState

    data class Reviewing(
        val resultSet: CalibrationResultSet,
        val selectedCandidateId: String? = resultSet.recommendedCandidateId,
        val saveToLibrary: Boolean = false
    ) : OnsiteCalibrationState

    data class Applying(
        val resultSet: CalibrationResultSet,
        val selectedCandidateId: String,
        val saveToLibrary: Boolean
    ) : OnsiteCalibrationState

    data class Applied(
        val snapshot: AnalyteQuantitationSnapshot,
        val resultSet: CalibrationResultSet,
        val selectedCandidateId: String
    ) : OnsiteCalibrationState

    data class TechnicalFailure(
        val requestId: String,
        val inputFingerprint: String
    ) : OnsiteCalibrationState
}
