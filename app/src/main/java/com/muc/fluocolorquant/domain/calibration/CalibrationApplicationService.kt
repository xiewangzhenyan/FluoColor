package com.muc.fluocolorquant.domain.calibration

import javax.inject.Inject

/** 新版现场曲线验证元数据的稳定 schema；内存应用与曲线库发布必须写入同一值。 */
const val CALIBRATION_ALGORITHM_SCHEMA_V2: String = "calibration-v2"

/**
 * 为现场曲线生成唯一的验证元数据快照。
 *
 * 该函数刻意放在现场标定领域层，并同时供“直接应用到本次运行”和“保存到曲线库”使用。
 * 两条链路若各自拼装 JSON，很容易再次出现页面预览可反算、保存后却因 schema 或可信范围
 * 丢失而整批退回仅信号的回归。内容指纹只属于持久化资源，因此允许调用方按需传入。
 */
fun buildCalibrationValidationMetrics(
    candidate: CalibrationCandidate,
    resultSet: CalibrationResultSet,
    contentFingerprint: String? = null
): Map<String, Any?> = linkedMapOf<String, Any?>(
    "R2" to candidate.rSquared,
    "RMSE" to candidate.rmse,
    "NORMALIZED_RMSE" to candidate.normalizedRmse,
    "MAE" to candidate.mae,
    "BACK_CALCULATED_RMSE_PERCENT" to candidate.backCalculatedRmsePercent,
    "ACCEPTED_STANDARD_RATIO" to candidate.acceptedStandardRatio,
    "WEIGHTING_CODE" to candidate.weightingCode,
    "ACCEPTED" to candidate.accepted,
    "INPUT_FINGERPRINT" to resultSet.inputFingerprint,
    "CALIBRATION_ENGINE_VERSION" to resultSet.engineVersion,
    "CALIBRATION_POLICY" to resultSet.policySnapshot,
    "CALIBRATION_ALGORITHM_SCHEMA" to CALIBRATION_ALGORITHM_SCHEMA_V2,
    "ROBUST_OBJECTIVE_VERSION" to candidate.robustObjectiveVersion,
    "CROSS_VALIDATION" to candidate.crossValidation,
    "TRUSTED_RANGE" to candidate.trustedRange
).apply {
    contentFingerprint?.let { put("CONTENT_FINGERPRINT", it) }
}

/**
 * 将用户已经审阅并选择的候选冻结为单分析物定量快照。
 *
 * 本服务不访问数据库，也不重新执行拟合。保存到曲线库只是调用方在同一候选基础上执行
 * 的可选副作用，不能改变这里生成的参数、标准点或推荐策略。
 */
class CalibrationApplicationService @Inject constructor() {

    fun freezeOnsiteCalibration(
        resultSet: CalibrationResultSet,
        selectedCandidateId: String,
        concentrationUnit: String,
        sourceResourceId: String? = null
    ): AnalyteQuantitationSnapshot {
        val candidate = requireNotNull(resultSet.candidate(selectedCandidateId)) {
            "选中的现场曲线候选不存在"
        }
        require(candidate.parameters.isNotEmpty() && candidate.parameters.values.all(Double::isFinite)) {
            "选中的现场曲线参数不可执行"
        }
        require(candidate.standardPoints.isNotEmpty()) { "现场曲线缺少标准点" }
        val rangeMinimum = candidate.standardPoints.minOf { it.first }
        val rangeMaximum = candidate.standardPoints.maxOf { it.first }
        val calibration = AppliedCalibrationSnapshot(
            primaryFeature = candidate.primaryFeature.code,
            fittingFunction = candidate.function.identifier,
            parameters = candidate.parameters,
            standardPoints = candidate.standardPoints,
            latexFormula = candidate.latexFormula,
            reliableRangeMin = rangeMinimum,
            reliableRangeMax = rangeMaximum,
            rSquared = candidate.rSquared,
            rmse = candidate.rmse,
            normalizedRmse = candidate.normalizedRmse,
            mae = candidate.mae,
            backCalculatedRmsePercent = candidate.backCalculatedRmsePercent,
            acceptedStandardRatio = candidate.acceptedStandardRatio,
            weightingCode = candidate.weightingCode,
            accepted = candidate.accepted,
            policySnapshot = resultSet.policySnapshot,
            processorVersion = resultSet.processorVersion,
            engineVersion = resultSet.engineVersion,
            inputFingerprint = resultSet.inputFingerprint,
            crossValidation = candidate.crossValidation,
            trustedRange = candidate.trustedRange,
            robustObjectiveVersion = candidate.robustObjectiveVersion
        )
        return AnalyteQuantitationSnapshot(
            analyteId = resultSet.analyteId,
            method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
            concentrationUnit = concentrationUnit,
            sourceResourceId = sourceResourceId,
            calibration = calibration,
            processorVersion = resultSet.processorVersion,
            inputFingerprint = resultSet.inputFingerprint
        )
    }
}
