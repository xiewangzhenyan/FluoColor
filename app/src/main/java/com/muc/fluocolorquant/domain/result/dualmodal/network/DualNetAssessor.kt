package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

/** 一次运行在 DualNet 输入契约下的裁切来源；Android 实现读取冻结原图与 PG-Grid 几何。 */
interface DualNetCropSource {
    /**
     * 返回请求位点的裁切：RGB 行优先 HWC，每张 [DualNetSpec.CROP_BYTES] 字节。
     * 冻结原图缺失、摘要不一致或几何无法解析时抛 [DualNetUnavailableException]。
     */
    fun crops(run: ArrayResultSnapshot, siteIndices: Set<Int>): Map<Int, ByteArray>
}

/** DualNet 推理：输入 count × 24 × 32 × 32，输出 count × 6；模型不可用时抛 [DualNetUnavailableException]。 */
interface DualNetModelRunner {
    fun predict(input: FloatArray, count: Int): FloatArray
}

/** 同一块芯片的比色、荧光两次运行，已由配对检查确认逐位点版面一致。 */
data class DualNetRunPair(
    val colorimetric: ArrayResultSnapshot,
    val fluorescence: ArrayResultSnapshot
)

/** 按分析物追溯本批标定板的结果。 */
sealed interface DualNetCalibrationLookup {
    data class Found(val pair: DualNetRunPair) : DualNetCalibrationLookup
    data class Missing(val reason: DualNetUnavailableReason) : DualNetCalibrationLookup
}

/**
 * 在一对测试运行上执行 DualNet 判读。
 *
 * 顺序固定为：版面与单位门控 → 追溯标定板 → 每次运行只裁切一次 → 标定板推理与逐批重标定 →
 * 测试板推理与建议。任一步失败只让该分析物的网络判读不可用，并给出稳定原因；规则判定不受影响。
 * 本类是纯 Kotlin，不访问文件、图像或数据库，便于在 JVM 上用假裁切与假模型检验编排。
 */
class DualNetAssessor(
    private val cropSource: DualNetCropSource,
    private val modelRunner: DualNetModelRunner,
    private val thresholds: DualNetThresholds = DualNetThresholds(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) {

    suspend fun assess(
        test: DualNetRunPair,
        calibration: suspend (analyteId: String) -> DualNetCalibrationLookup
    ): DualNetAssessment {
        val fluorescenceAnalytes = test.fluorescence.analytes.associateBy(ArrayAnalyteResult::analyteId)
        val analytes = test.colorimetric.analytes
            .filter { it.analyteId in fluorescenceAnalytes }
            .sortedWith(compareBy(ArrayAnalyteResult::displayOrder, ArrayAnalyteResult::analyteId))

        // 第一步：逐分析物完成门控与标定板追溯，收集每次运行需要裁切的位点。
        val plans = analytes.map { analyte -> plan(test, analyte, calibration) }
        val requests = linkedMapOf<String, Pair<ArrayResultSnapshot, MutableSet<Int>>>()
        plans.filterIsInstance<Plan.Ready>().forEach { plan ->
            plan.request(requests, test.colorimetric, plan.samples)
            plan.request(requests, test.fluorescence, plan.samples)
            plan.request(requests, plan.calibration.colorimetric, plan.standards)
            plan.request(requests, plan.calibration.fluorescence, plan.standards)
        }

        // 第二步：每次运行只矫正、裁切一次；某次运行的证据失败只影响用到它的分析物。
        val crops = mutableMapOf<String, Map<Int, ByteArray>>()
        val cropFailures = mutableMapOf<String, DualNetUnavailableReason>()
        requests.forEach { (runId, request) ->
            try {
                crops[runId] = cropSource.crops(request.first, request.second)
            } catch (failure: DualNetUnavailableException) {
                cropFailures[runId] = failure.reason
            }
        }

        return DualNetAssessment(
            thresholds = thresholds,
            analytes = plans.map { plan ->
                when (plan) {
                    is Plan.Unavailable -> plan.assessment
                    is Plan.Ready -> evaluate(test, plan, crops, cropFailures)
                }
            }
        )
    }

    private sealed interface Plan {
        data class Unavailable(val assessment: DualNetAnalyteAssessment) : Plan
        data class Ready(
            val analyteId: String,
            val calibration: DualNetRunPair,
            val samples: List<DualNetSiteGroup>,
            val standards: List<DualNetSiteGroup>
        ) : Plan {
            fun request(
                requests: MutableMap<String, Pair<ArrayResultSnapshot, MutableSet<Int>>>,
                run: ArrayResultSnapshot,
                groups: List<DualNetSiteGroup>
            ) {
                val sites = requests.getOrPut(run.runId) { run to linkedSetOf() }.second
                groups.forEach { group -> group.sites.forEach { refs -> sites += refs.all() } }
            }
        }
    }

    private suspend fun plan(
        test: DualNetRunPair,
        analyte: ArrayAnalyteResult,
        calibration: suspend (String) -> DualNetCalibrationLookup
    ): Plan {
        fun unavailable(reason: DualNetUnavailableReason, pair: DualNetRunPair? = null) = Plan.Unavailable(
            unavailableAssessment(analyte.analyteId, reason, pair)
        )
        if (!DualNetLayout.gridSupported(test.colorimetric)) return unavailable(DualNetUnavailableReason.LAYOUT_NOT_SUPPORTED)
        if (!analyte.concentrationUnit.equals(DualNetSpec.CONCENTRATION_UNIT, ignoreCase = true)) {
            return unavailable(DualNetUnavailableReason.UNIT_NOT_SUPPORTED)
        }
        val samples = DualNetLayout.sampleGroups(test.colorimetric, analyte.analyteId)
        if (samples.isEmpty()) return unavailable(DualNetUnavailableReason.LAYOUT_NOT_SUPPORTED)
        val pair = when (val lookup = calibration(analyte.analyteId)) {
            is DualNetCalibrationLookup.Missing -> return unavailable(lookup.reason)
            is DualNetCalibrationLookup.Found -> lookup.pair
        }
        val standards = DualNetLayout.standardLevels(pair.colorimetric, analyte.analyteId)
        if (standards.size < DualNetSpec.MIN_RECALIBRATION_LEVELS) {
            return unavailable(DualNetUnavailableReason.CALIBRATION_LEVELS_INSUFFICIENT, pair)
        }
        return Plan.Ready(analyte.analyteId, pair, samples, standards)
    }

    private fun evaluate(
        test: DualNetRunPair,
        plan: Plan.Ready,
        crops: Map<String, Map<Int, ByteArray>>,
        cropFailures: Map<String, DualNetUnavailableReason>
    ): DualNetAnalyteAssessment {
        val runs = listOf(test.colorimetric, test.fluorescence, plan.calibration.colorimetric, plan.calibration.fluorescence)
        runs.firstNotNullOfOrNull { run -> cropFailures[run.runId] }?.let { reason ->
            return unavailableAssessment(plan.analyteId, reason, plan.calibration)
        }
        return try {
            val levels = plan.standards.map { group ->
                group.level!! to DualNetPostProcessor.reading(
                    predict(crops.getValue(plan.calibration.colorimetric.runId), crops.getValue(plan.calibration.fluorescence.runId), group.sites)
                )
            }
            val calColTrusted = plan.calibration.colorimetric.frame.geometry.trusted
            val calFluTrusted = plan.calibration.fluorescence.frame.geometry.trusted
            val headsUsable = buildSet {
                if (calColTrusted) add(DualNetHead.COLORIMETRIC)
                if (calFluTrusted) add(DualNetHead.FLUORESCENCE)
                if (calColTrusted && calFluTrusted) add(DualNetHead.FUSED)
            }
            val recalibrations = DualNetPostProcessor.recalibrate(levels, headsUsable)
            if (recalibrations.isEmpty()) {
                return unavailableAssessment(plan.analyteId, DualNetUnavailableReason.RECALIBRATION_FAILED, plan.calibration)
            }
            val availability = DualNetHeadAvailability(
                colorimetric = test.colorimetric.frame.geometry.trusted,
                fluorescence = test.fluorescence.frame.geometry.trusted
            )
            val readings = plan.samples.map { group ->
                val raw = DualNetPostProcessor.reading(
                    predict(crops.getValue(test.colorimetric.runId), crops.getValue(test.fluorescence.runId), group.sites)
                )
                DualNetPostProcessor.decide(plan.analyteId, group.key, raw, recalibrations, availability, thresholds)
            }
            DualNetAnalyteAssessment(
                analyteId = plan.analyteId,
                status = DualNetStatus.COMPLETED,
                unavailableReason = null,
                calibrationColorimetricRunId = plan.calibration.colorimetric.runId,
                calibrationFluorescenceRunId = plan.calibration.fluorescence.runId,
                recalibrations = recalibrations,
                readings = readings
            )
        } catch (failure: DualNetUnavailableException) {
            unavailableAssessment(plan.analyteId, failure.reason, plan.calibration)
        }
    }

    /** 按批推理，返回与 [sites] 同序的逐孔 6 维输出。 */
    private fun predict(
        colorimetric: Map<Int, ByteArray>,
        fluorescence: Map<Int, ByteArray>,
        sites: List<DualNetSiteRefs>
    ): List<FloatArray> = sites.chunked(batchSize).flatMap { batch ->
        val input = DualNetTensorAssembler.assemble(colorimetric, fluorescence, batch)
        val output = modelRunner.predict(input, batch.size)
        if (output.size != batch.size * DualNetSpec.OUTPUT_SIZE || output.any { !it.isFinite() }) {
            throw DualNetUnavailableException(DualNetUnavailableReason.INFERENCE_FAILED, "网络输出维度不符或含非有限值")
        }
        batch.indices.map { i -> output.copyOfRange(i * DualNetSpec.OUTPUT_SIZE, (i + 1) * DualNetSpec.OUTPUT_SIZE) }
    }

    private fun unavailableAssessment(
        analyteId: String,
        reason: DualNetUnavailableReason,
        calibration: DualNetRunPair?
    ) = DualNetAnalyteAssessment(
        analyteId = analyteId,
        status = DualNetStatus.UNAVAILABLE,
        unavailableReason = reason,
        calibrationColorimetricRunId = calibration?.colorimetric?.runId,
        calibrationFluorescenceRunId = calibration?.fluorescence?.runId,
        recalibrations = emptyList(),
        readings = emptyList()
    )

    private companion object {
        /** 64 个样本约 6.3 MB 输入，兼顾手机内存与推理吞吐。 */
        const val DEFAULT_BATCH_SIZE: Int = 64
    }
}
