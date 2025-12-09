package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.Project

/**
 * 项目数据访问对象，提供基础 CRUD。
 */
@Dao
interface ProjectDao {
    /**
     * 获取全部项目，按创建时间倒序
     */
    @Query("SELECT * FROM projects ORDER BY createTime DESC")
    suspend fun getAllProjects(): List<Project>

    /**
     * 获取最新项目
     */
    @Query("SELECT * FROM projects ORDER BY createTime DESC LIMIT 1")
    suspend fun getLatestProject(): Project?

    /**
     * 根据ID获取项目
     */
    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: String): Project?

    /**
     * 获取指定用户的全部项目
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
     * 更新光谱模式相关配置
     */
    @Query(
        """
        UPDATE projects
        SET lightSource = :lightSource,
            spectrumColumnCount = :spectrumColumnCount,
            spectrumColumnMappingJson = :spectrumColumnMappingJson
        WHERE id = :projectId
        """
    )
    suspend fun updateSpectrumConfig(
        projectId: String,
        lightSource: String?,
        spectrumColumnCount: Int,
        spectrumColumnMappingJson: String?
    )

    /**
     * 删除项目
     */
    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)
}
