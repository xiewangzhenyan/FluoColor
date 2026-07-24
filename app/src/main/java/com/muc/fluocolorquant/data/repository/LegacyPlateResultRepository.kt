package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.domain.result.plate96.LegacyPlateRunAdapter
import com.muc.fluocolorquant.domain.result.plate96.LegacyPlateRunSource
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultErrorCode
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** 旧96孔板历史的只读仓库；接口没有任何保存或更新方法。 */
interface LegacyPlateResultRepository {
    suspend fun loadSnapshot(runId: String): Plate96ResultLoadResult
}

/**
 * 在同一Room事务中读取旧运行关系图，再交给纯领域适配器。
 *
 * 旧表继续保留用于历史兼容，但新检测流程不得依赖本仓库写入数据。
 */
@Singleton
class LegacyPlateResultRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : LegacyPlateResultRepository {
    override suspend fun loadSnapshot(runId: String): Plate96ResultLoadResult {
        if (runId.isBlank()) return unavailable()
        val source = try {
            database.withTransaction {
                val run = database.detectionRunDao().getDetectionRunById(runId)
                    ?: return@withTransaction null
                val project = database.projectDao().getProjectById(run.projectId)
                    ?: return@withTransaction null
                val wells = database.wellResultDao().getWellResultsByRunId(runId)
                val joins = database.projectAnalyteJoinDao().getProjectAnalyteJoins(project.id)
                val analytes = (joins.map { it.analyteId } + wells.mapNotNull { it.fkAnalyteId })
                    .distinct()
                    .mapNotNull { id -> database.analyteDao().getAnalyteById(id)?.let { id to it } }
                    .toMap()
                val curves = joins.mapNotNull { join ->
                    join.fkCurveModelId?.let { id ->
                        database.curveModelDao().getCurveModelById(id)?.let { id to it }
                    }
                }.toMap()
                LegacyPlateRunSource(
                    run = run,
                    project = project,
                    wellResults = wells,
                    projectAnalytes = joins,
                    analytesById = analytes,
                    curveModelsById = curves,
                    artifacts = database.captureArtifactDao().getByRun(runId)
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return unavailable()
        }
        return source?.let(LegacyPlateRunAdapter::map) ?: unavailable()
    }

    private fun unavailable(): Plate96ResultLoadResult = Plate96ResultLoadResult.Failure(
        Plate96ResultErrorCode.SOURCE_NOT_AVAILABLE
    )
}
