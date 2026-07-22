package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin

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
     * 新模板项目使用严格插入，禁止 UUID 冲突时静默覆盖已有科研项目。
     *
     * 旧页面的兼容 CRUD 仍保留 [insertProject]，但模板优先流程只能调用
     * [insertProjectWithAnalytes]。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjectStrict(project: Project)

    /** 严格批量插入项目分析物关联，任一外键或主键失败都会触发整个事务回滚。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjectAnalytesStrict(joins: List<ProjectAnalyteJoin>)

    /**
     * 原子创建项目主档和全部分析物配置。
     *
     * 在任何数据库写入前先校验所有关联均属于当前项目，避免调用方传错 ID 后产生跨项目
     * 配置。Room 会把方法体包装在同一事务中，因此不存在只保存主档或只保存部分分析物
     * 的半成品状态。
     */
    @Transaction
    suspend fun insertProjectWithAnalytes(
        project: Project,
        joins: List<ProjectAnalyteJoin>
    ) {
        require(joins.all { it.projectId == project.id }) {
            "项目分析物关联必须属于同一项目"
        }
        insertProjectStrict(project)
        if (joins.isNotEmpty()) {
            insertProjectAnalytesStrict(joins)
        }
    }

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
