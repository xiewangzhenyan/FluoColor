package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import kotlinx.coroutines.flow.Flow

/**
 * 项目与分析物关联数据访问接口
 */
@Dao
interface ProjectAnalyteJoinDao {
    /**
     * 插入一个项目-分析物关联
     * @param join 项目-分析物关联实体
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(join: ProjectAnalyteJoin)
    
    /**
     * 批量插入项目-分析物关联
     * @param joins 项目-分析物关联实体列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(joins: List<ProjectAnalyteJoin>)
    
    /**
     * 删除一个项目-分析物关联
     * @param join 项目-分析物关联实体
     */
    @Delete
    suspend fun delete(join: ProjectAnalyteJoin)
    
    /**
     * 根据项目ID和分析物ID删除关联
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     */
    @Query("DELETE FROM project_analytes_join WHERE projectId = :projectId AND analyteId = :analyteId")
    suspend fun deleteByProjectIdAndAnalyteId(projectId: String, analyteId: String)
    
    /**
     * 删除指定项目的所有分析物关联
     * @param projectId 项目ID
     */
    @Query("DELETE FROM project_analytes_join WHERE projectId = :projectId")
    suspend fun deleteByProjectId(projectId: String)
    
    /**
     * 删除指定分析物的所有项目关联
     * @param analyteId 分析物ID
     */
    @Query("DELETE FROM project_analytes_join WHERE analyteId = :analyteId")
    suspend fun deleteByAnalyteId(analyteId: String)
    
    /**
     * 获取指定项目关联的所有分析物
     * @param projectId 项目ID
     * @return 分析物列表流
     */
    @Transaction
    @Query("""
        SELECT a.* FROM analytes a 
        INNER JOIN project_analytes_join paj ON a.id = paj.analyteId 
        WHERE paj.projectId = :projectId
    """)
    fun getAnalytesByProjectId(projectId: String): Flow<List<Analyte>>
    
    /**
     * 检查项目是否关联了指定分析物
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     * @return 是否存在关联
     */
    @Query("SELECT EXISTS(SELECT 1 FROM project_analytes_join WHERE projectId = :projectId AND analyteId = :analyteId)")
    suspend fun exists(projectId: String, analyteId: String): Boolean
    
    /**
     * 获取项目的所有分析物配置
     * @param projectId 项目ID
     * @return 项目分析物配置列表
     */
    @Query("SELECT * FROM project_analytes_join WHERE projectId = :projectId")
    suspend fun getProjectAnalyteJoins(projectId: String): List<ProjectAnalyteJoin>
    
    /**
     * 获取项目的第一个分析物配置（用于获取默认的最大浓度和浓度单位）
     * @param projectId 项目ID
     * @return 项目分析物配置，如果不存在则返回null
     */
    @Query("SELECT * FROM project_analytes_join WHERE projectId = :projectId LIMIT 1")
    suspend fun getFirstProjectAnalyteJoin(projectId: String): ProjectAnalyteJoin?
} 