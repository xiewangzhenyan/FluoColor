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

    /**
     * 在单个 Room 事务中读取一次运行的完整附件快照。
     *
     * 结果仓库不能收集 Flow 后再分别查询测量，否则查询间发生写入时可能拼出不一致的
     * 科研结果；固定排序也保证导出清单和历史页面可复现。
     */
    @Query("SELECT * FROM capture_artifacts WHERE runId = :runId ORDER BY capturedAt, revision, id")
    suspend fun getByRun(runId: String): List<CaptureArtifact>

    @Query("SELECT * FROM capture_artifacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CaptureArtifact?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: CaptureArtifact)

    /** 一次性写入同一运行的派生处理证据，由外层数据库事务保证与运行主档原子提交。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(artifacts: List<CaptureArtifact>)

    @Update
    suspend fun update(artifact: CaptureArtifact)
}
