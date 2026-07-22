package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshotMapper
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshotSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 使用数据库级只读事务加载运行、项目身份、附件和逐位点测量。
 *
 * 项目只提供标题和身份；模板、样本映射、模型、几何及处理版本全部从 DetectionRun 的
 * 冻结字段读取。这样即使管理员后来创建了新模板版本，历史结果仍保持原样。
 */
@Singleton
class ArrayResultRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : ArrayResultRepository {

    override suspend fun hasNewArrayResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return database.detectionRunDao().hasSiteMeasurements(runId)
    }

    override suspend fun loadSnapshot(runId: String): ArrayResultLoadResult {
        if (runId.isBlank()) {
            return ArrayResultLoadResult.Failure(ArrayResultErrorCode.RUN_NOT_FOUND)
        }
        val entities = try {
            database.withTransaction {
                val run = database.detectionRunDao().getDetectionRunById(runId)
                    ?: return@withTransaction LoadEntitiesResult.RunNotFound
                val project = database.projectDao().getProjectById(run.projectId)
                    ?: return@withTransaction LoadEntitiesResult.ProjectNotFound
                LoadEntitiesResult.Loaded(
                    run = run,
                    project = project,
                    artifacts = database.captureArtifactDao().getByRun(runId),
                    measurements = database.siteMeasurementDao().getByRun(runId)
                )
            }
        } catch (cancellation: CancellationException) {
            // 页面离开或任务取消必须继续向上游传播，不能伪装成数据库损坏。
            throw cancellation
        } catch (_: RuntimeException) {
            return ArrayResultLoadResult.Failure(ArrayResultErrorCode.DATABASE_READ_FAILED)
        }
        return when (entities) {
            LoadEntitiesResult.RunNotFound ->
                ArrayResultLoadResult.Failure(ArrayResultErrorCode.RUN_NOT_FOUND)
            LoadEntitiesResult.ProjectNotFound ->
                ArrayResultLoadResult.Failure(ArrayResultErrorCode.PROJECT_NOT_FOUND)
            is LoadEntitiesResult.Loaded -> ArrayResultSnapshotMapper.map(
                ArrayResultSnapshotSource(
                    run = entities.run,
                    project = entities.project,
                    artifacts = entities.artifacts,
                    measurements = entities.measurements
                )
            )
        }
    }

    override suspend fun getProjectRuns(projectId: String): List<DetectionRun> {
        if (projectId.isBlank()) return emptyList()
        return database.detectionRunDao().getDetectionRunsByProjectId(projectId)
    }

    /** 事务内部结果显式区分缺运行、缺项目和完整实体，避免用异常控制正常分支。 */
    private sealed interface LoadEntitiesResult {
        data object RunNotFound : LoadEntitiesResult
        data object ProjectNotFound : LoadEntitiesResult

        data class Loaded(
            val run: DetectionRun,
            val project: Project,
            val artifacts: List<CaptureArtifact>,
            val measurements: List<SiteMeasurement>
        ) : LoadEntitiesResult
    }
}
