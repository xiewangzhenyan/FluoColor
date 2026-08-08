package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.domain.result.plate96.LegacyPlateRunAdapter
import com.muc.fluocolorquant.domain.result.plate96.LegacyPlateRunSource
import com.muc.fluocolorquant.domain.result.plate96.LEGACY_DEFAULT_ANALYTE_ID
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultErrorCode
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.plateWellLabel
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** 旧96孔板历史的只读仓库；接口没有任何保存或更新方法。 */
interface LegacyPlateResultRepository {
    suspend fun loadSnapshot(runId: String): Plate96ResultLoadResult

    /** 读取旧结果页已经保存的样本真值，用于恢复预测精度验证，不修改旧表。 */
    suspend fun loadValidationPoints(runId: String): Map<String, List<ResultValidationPoint>>
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

    override suspend fun loadValidationPoints(
        runId: String
    ): Map<String, List<ResultValidationPoint>> {
        if (runId.isBlank()) return emptyMap()
        return try {
            database.wellResultDao().getWellResultsByRunId(runId)
                .filter { result ->
                    result.wellIndex in 0 until 96 &&
                        result.predictedConcentration?.isFinite() == true &&
                        result.trueConcentration?.isFinite() == true
                }
                // 旧版本可能对同一孔和分析物留下多条记录，结果页一直以最新主键为准。
                .groupBy { result ->
                    val analyteId = result.fkAnalyteId
                        ?.takeIf(String::isNotBlank) ?: LEGACY_DEFAULT_ANALYTE_ID
                    analyteId to result.wellIndex
                }
                .mapValues { (_, records) -> records.maxBy { result -> result.resultId } }
                .values
                .groupBy { result ->
                    result.fkAnalyteId?.takeIf(String::isNotBlank) ?: LEGACY_DEFAULT_ANALYTE_ID
                }
                .mapValues { (_, records) ->
                    records.sortedBy { result -> result.wellIndex }.map { result ->
                        val row = result.wellIndex / 12
                        val column = result.wellIndex % 12
                        ResultValidationPoint(
                            siteIndex = result.wellIndex,
                            siteLabel = plateWellLabel(row, column),
                            predictedValue = requireNotNull(result.predictedConcentration),
                            referenceValue = requireNotNull(result.trueConcentration)
                        )
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            emptyMap()
        }
    }

    private fun unavailable(): Plate96ResultLoadResult = Plate96ResultLoadResult.Failure(
        Plate96ResultErrorCode.SOURCE_NOT_AVAILABLE
    )
}
