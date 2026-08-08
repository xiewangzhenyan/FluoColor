package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.SiteMeasurement

/** 一次新阵列检测需要原子保存的全部实体。 */
data class GridDetectionPersistenceBundle(
    val run: DetectionRun,
    val endpointArtifact: CaptureArtifact,
    val measurements: List<SiteMeasurement>,
    val diagnosticArtifacts: List<CaptureArtifact> = emptyList()
) {
    /** 在打开事务前拒绝跨运行混写，减少数据库异常后才发现调用错误的成本。 */
    fun requireValid(): GridDetectionPersistenceBundle = apply {
        require(endpointArtifact.runId == run.runId) { "终点附件必须属于当前检测运行" }
        require(diagnosticArtifacts.all { it.runId == run.runId }) {
            "全部处理证据附件必须属于当前检测运行"
        }
        require(
            (listOf(endpointArtifact) + diagnosticArtifacts)
                .map(CaptureArtifact::id)
                .distinct()
                .size == diagnosticArtifacts.size + 1
        ) {
            "一次运行中的附件 ID 不能重复"
        }
        require(measurements.all { it.runId == run.runId }) { "全部位点测量必须属于当前检测运行" }
        require(measurements.map(SiteMeasurement::siteIndex).all { it >= 0 }) {
            "位点索引不能为负数"
        }
    }
}

/** 新微流控检测运行的持久化边界。 */
interface GridDetectionRunRepository {
    suspend fun save(bundle: GridDetectionPersistenceBundle)
}
