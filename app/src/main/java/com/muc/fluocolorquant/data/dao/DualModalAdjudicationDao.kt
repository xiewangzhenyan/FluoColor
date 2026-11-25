package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muc.fluocolorquant.data.model.DualModalAdjudicationRecord

/** 双模态判定记录只追加新修订，不提供覆盖历史记录的更新接口。 */
@Dao
interface DualModalAdjudicationDao {
    /** 一次运行无论作为比色侧还是荧光侧，都返回它最近的一条判定修订。 */
    @Query(
        "SELECT * FROM dual_modal_adjudication_records " +
            "WHERE colorimetricRunId = :runId OR fluorescenceRunId = :runId " +
            "ORDER BY createdAt DESC, revision DESC LIMIT 1"
    )
    suspend fun getLatestForRun(runId: String): DualModalAdjudicationRecord?

    @Query(
        "SELECT MAX(revision) FROM dual_modal_adjudication_records " +
            "WHERE colorimetricRunId = :colorimetricRunId AND fluorescenceRunId = :fluorescenceRunId"
    )
    suspend fun getLatestRevision(colorimetricRunId: String, fluorescenceRunId: String): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: DualModalAdjudicationRecord)
}
