package com.muc.fluocolorquant.data.dao

import androidx.room.*
import com.muc.fluocolorquant.data.model.Project

/**
 * 项目数据访问对象
 * 提供对项目表的基本CRUD操作
 */
@Dao
interface ProjectDao {
    /**
     * 获取所有项目，按创建时间降序排列
     */
    @Query("SELECT * FROM projects ORDER BY createTime DESC")
    suspend fun getAllProjects(): List<Project>
    
    /**
     * 获取最近创建的项目
     */
    @Query("SELECT * FROM projects ORDER BY createTime DESC LIMIT 1")
    suspend fun getLatestProject(): Project?
    
    /**
     * 根据ID获取项目
     */
    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: String): Project?
    
    /**
     * 获取指定用户的所有项目
     */
    @Query("SELECT * FROM projects WHERE userId = :userId ORDER BY createTime DESC")
    suspend fun getProjectsByUserId(userId: String): List<Project>
    
    /**
     * 插入项目
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: Project)
    
    /**
     * 更新项目
     */
    @Update
    suspend fun updateProject(project: Project)
    
    /**
     * 删除项目
     */
    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)
} 