package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.WellResult

/**
 * 孔位结果数据访问对象
 * 提供对孔位结果表的基本CRUD操作
 */
@Dao
interface WellResultDao {
    /**
     * 获取所有孔位结果
     */
    @Query("SELECT * FROM well_results")
    suspend fun getAllWellResults(): List<WellResult>
    
    /**
     * 根据ID获取孔位结果
     */
    @Query("SELECT * FROM well_results WHERE resultId = :resultId")
    suspend fun getWellResultById(resultId: Long): WellResult?
    
    /**
     * 获取指定项目的所有孔位结果
     */
    @Query("SELECT * FROM well_results WHERE projectId = :projectId")
    suspend fun getWellResultsByProjectId(projectId: String): List<WellResult>
    
    /**
     * 获取指定运行的所有孔位结果
     */
    @Query("SELECT * FROM well_results WHERE runId = :runId ORDER BY wellIndex")
    suspend fun getWellResultsByRunId(runId: String): List<WellResult>
    
    /**
     * 获取指定项目的手动模式孔位结果 (wellIndex = -1)
     */
    @Query("SELECT * FROM well_results WHERE projectId = :projectId AND wellIndex = -1")
    suspend fun getManualWellResultsByProjectId(projectId: String): List<WellResult>
    
    /**
     * 批量插入孔位结果
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWellResults(wellResults: List<WellResult>)
    
    /**
     * 插入单个孔位结果
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWellResult(wellResult: WellResult): Long
    
    /**
     * 更新孔位结果
     */
    @Update
    suspend fun updateWellResult(wellResult: WellResult)
    
    /**
     * a批量更新孔位结果
     */
    @Update
    suspend fun updateWellResults(wellResults: List<WellResult>)
    
    /**
     * 删除指定运行的所有孔位结果
     */
    @Query("DELETE FROM well_results WHERE runId = :runId")
    suspend fun deleteWellResultsByRunId(runId: String)
    
    /**
     * 删除指定项目的所有孔位结果
     */
    @Query("DELETE FROM well_results WHERE projectId = :projectId")
    suspend fun deleteWellResultsByProjectId(projectId: String)
    
    /**
     * 删除单个孔位结果
     */
    @Query("DELETE FROM well_results WHERE resultId = :resultId")
    suspend fun deleteWellResult(resultId: Long)

    /**
     * 根据运行ID和分析物ID获取孔位结果
     * @param runId 运行ID
     * @param analyteId 分析物ID
     * @return 孔位结果列表
     */
    @Query("SELECT * FROM well_results WHERE runId = :runId AND fkAnalyteId = :analyteId")
    suspend fun getWellResultsByRunIdAndAnalyteId(runId: String, analyteId: String): List<WellResult>
} 