package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.DetectionRun

/**
 * 检测运行数据访问对象
 * 提供对检测运行表的基本CRUD操作
 */
@Dao
interface DetectionRunDao {
    /**
     * 获取所有检测运行记录，按时间戳降序排列
     */
    @Query("SELECT * FROM detection_runs ORDER BY timestamp DESC")
    suspend fun getAllDetectionRuns(): List<DetectionRun>
    
    /**
     * 根据ID获取检测运行记录
     */
    @Query("SELECT * FROM detection_runs WHERE runId = :runId")
    suspend fun getDetectionRunById(runId: String): DetectionRun?
    
    /**
     * 获取指定项目的所有检测运行记录
     */
    @Query("SELECT * FROM detection_runs WHERE projectId = :projectId ORDER BY timestamp DESC")
    suspend fun getDetectionRunsByProjectId(projectId: String): List<DetectionRun>

    /** 新结果网关只按真实位点测量存在性分流，不根据10×10/15×15行列猜测载体。 */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM site_measurements
            WHERE runId = :runId
            LIMIT 1
        )
        """
    )
    suspend fun hasSiteMeasurements(runId: String): Boolean
    
    /**
     * 获取指定项目的最新检测运行记录
     */
    @Query("SELECT * FROM detection_runs WHERE projectId = :projectId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestDetectionRunByProjectId(projectId: String): DetectionRun?
    
    /**
     * 插入检测运行记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetectionRun(detectionRun: DetectionRun)
    
    /**
     * 更新检测运行记录
     */
    @Update
    suspend fun updateDetectionRun(detectionRun: DetectionRun)
    
    /**
     * 删除检测运行记录
     */
    @Query("DELETE FROM detection_runs WHERE runId = :runId")
    suspend fun deleteDetectionRun(runId: String)
    
    /**
     * 删除指定项目的所有检测运行记录
     */
    @Query("DELETE FROM detection_runs WHERE projectId = :projectId")
    suspend fun deleteDetectionRunsByProjectId(projectId: String)
}
