package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muc.fluocolorquant.data.model.SiteMeasurement
import kotlinx.coroutines.flow.Flow

/** 新模态处理器输出的逐位点科学测量数据访问接口。 */
@Dao
interface SiteMeasurementDao {
    @Query("SELECT * FROM site_measurements WHERE runId = :runId ORDER BY siteIndex, id")
    fun observeByRun(runId: String): Flow<List<SiteMeasurement>>

    @Query("SELECT * FROM site_measurements WHERE runId = :runId AND analyteId = :analyteId ORDER BY siteIndex")
    suspend fun getByAnalyte(runId: String, analyteId: String): List<SiteMeasurement>

    /** 测试、导出和运行详情使用的固定快照查询，不依赖 Flow 收集时序。 */
    @Query("SELECT * FROM site_measurements WHERE runId = :runId ORDER BY siteIndex, id")
    suspend fun getByRun(runId: String): List<SiteMeasurement>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(measurements: List<SiteMeasurement>)

    @Query("DELETE FROM site_measurements WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)
}
