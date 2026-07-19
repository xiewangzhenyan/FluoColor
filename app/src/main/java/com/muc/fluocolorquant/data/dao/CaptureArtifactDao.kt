package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.CaptureArtifact
import kotlinx.coroutines.flow.Flow

/** 检测运行原始采集附件的数据访问接口。 */
@Dao
interface CaptureArtifactDao {
    @Query("SELECT * FROM capture_artifacts WHERE runId = :runId ORDER BY capturedAt, revision")
    fun observeByRun(runId: String): Flow<List<CaptureArtifact>>

    @Query("SELECT * FROM capture_artifacts WHERE runId = :runId AND captureRole = :role ORDER BY revision DESC")
    suspend fun getByRole(runId: String, role: String): List<CaptureArtifact>

    @Query("SELECT * FROM capture_artifacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CaptureArtifact?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: CaptureArtifact)

    @Update
    suspend fun update(artifact: CaptureArtifact)
}
