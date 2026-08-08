package com.muc.fluocolorquant.domain.calibration

import javax.inject.Inject

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
