package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalPairCheck
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetAssessment
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetAssessor
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetCalibrationLookup
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetCalibrationRuns
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetCropSource
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetModelRunner
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetRunPair
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetUnavailableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 在一对已配对的运行上执行 DualNet 网络判读。 */
interface DualNetAssessmentService {
    suspend fun assess(colorimetric: ArrayResultSnapshot, fluorescence: ArrayResultSnapshot): DualNetAssessment
}

/**
 * DualNet 判读的数据边界：负责从冻结快照追溯两侧标定板并加载它们，图像与推理交给领域层组装器。
 *
 * 标定板必须满足三条：两侧曲线各自追溯到唯一的标定运行；两次标定运行都已完成并能加载快照；
 * 两次标定运行本身能按双模态规则配对（同一块标定板的两种模态）。任何一条不满足，网络判读给出
 * 稳定原因，配对与规则判定照常完成。图像处理与推理在 Default 调度器上执行，不阻塞主线程。
 */
@Singleton
class DualNetAssessmentServiceImpl @Inject constructor(
    private val arrayResults: ArrayResultRepository,
    private val cropSource: DualNetCropSource,
    private val modelRunner: DualNetModelRunner
) : DualNetAssessmentService {

    override suspend fun assess(
        colorimetric: ArrayResultSnapshot,
        fluorescence: ArrayResultSnapshot
    ): DualNetAssessment = withContext(Dispatchers.Default) {
        DualNetAssessor(cropSource, modelRunner).assess(DualNetRunPair(colorimetric, fluorescence)) { analyteId ->
            lookupCalibration(colorimetric, fluorescence, analyteId)
        }
    }

    private suspend fun lookupCalibration(
        colorimetric: ArrayResultSnapshot,
        fluorescence: ArrayResultSnapshot,
        analyteId: String
    ): DualNetCalibrationLookup {
        val colorimetricRunId = DualNetCalibrationRuns.runIds(colorimetric, analyteId).singleOrNull()
        val fluorescenceRunId = DualNetCalibrationRuns.runIds(fluorescence, analyteId).singleOrNull()
        if (colorimetricRunId == null || fluorescenceRunId == null) {
            return DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND)
        }
        val calibrationColorimetric = load(colorimetricRunId, colorimetric)
            ?: return DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND)
        val calibrationFluorescence = load(fluorescenceRunId, fluorescence)
            ?: return DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND)
        return when (val check = DualModalAdjudicationEngine.check(calibrationColorimetric, calibrationFluorescence)) {
            is DualModalPairCheck.Incompatible ->
                DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_PAIRABLE)
            is DualModalPairCheck.Compatible -> {
                // 两条曲线必须分别来自比色、荧光标定板；若追溯结果的模态对调，说明数据不一致。
                if (check.colorimetric.runId != colorimetricRunId) {
                    DualNetCalibrationLookup.Missing(DualNetUnavailableReason.CALIBRATION_RUNS_NOT_PAIRABLE)
                } else {
                    DualNetCalibrationLookup.Found(DualNetRunPair(check.colorimetric, check.fluorescence))
                }
            }
        }
    }

    /** 标定板就是测试板本身（同一块板上既有标准品又有样本）时直接复用已加载的快照。 */
    private suspend fun load(runId: String, test: ArrayResultSnapshot): ArrayResultSnapshot? {
        if (runId == test.runId) return test
        return (arrayResults.loadSnapshot(runId) as? ArrayResultLoadResult.Success)?.snapshot
    }
}
