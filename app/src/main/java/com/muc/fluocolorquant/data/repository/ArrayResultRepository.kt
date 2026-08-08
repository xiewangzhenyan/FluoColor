package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult

/**
 * 新阵列结果的只读数据边界。
 *
 * 仓库不提供更新结果的方法；重拍、重新分析或更换模型必须创建新的 DetectionRun，
 * 防止结果页面无意覆盖已经用于论文、报告或导出的历史科学数据。
 */
interface ArrayResultRepository {
    /** 判断运行是否拥有新 SiteMeasurement，用于新旧结果页面分流。 */
    suspend fun hasNewArrayResult(runId: String): Boolean

    /** 从一次运行的冻结证据重建完整阵列结果。 */
    suspend fun loadSnapshot(runId: String): ArrayResultLoadResult

    /** 同一项目的运行历史固定按时间倒序返回，切换历史不会写数据库。 */
    suspend fun getProjectRuns(projectId: String): List<DetectionRun>
}
