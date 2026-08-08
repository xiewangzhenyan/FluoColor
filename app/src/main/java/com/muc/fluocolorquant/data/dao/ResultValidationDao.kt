package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muc.fluocolorquant.data.model.ResultValidationRecord

/** 验证记录只追加新修订，不提供覆盖历史结果的更新接口。 */
@Dao
interface ResultValidationDao {
    @Query(
        "SELECT * FROM result_validation_records " +
            "WHERE runId = :runId ORDER BY analyteId ASC, revision DESC"
    )
    suspend fun getByRun(runId: String): List<ResultValidationRecord>

    @Query(
        "SELECT MAX(revision) FROM result_validation_records " +
            "WHERE runId = :runId AND analyteId = :analyteId"
    )
    suspend fun getLatestRevision(runId: String, analyteId: String): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ResultValidationRecord)
}
