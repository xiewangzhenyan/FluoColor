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
 * 删除项目前需要冻结的一条文件引用。
 *
 * [kind] 只用于区分普通路径、运行目录和光谱标定 JSON；真正删除前仍必须经过应用私有
 * 目录的 canonical path 校验，不能把数据库中的字符串直接交给文件系统。
 */
data class ProjectFileReference(
    val kind: String,
    val value: String
)

/** 数据库项目主档已经删除后，交给文件清理器处理的不可变引用快照。 */
data class ProjectDeletionSnapshot(
    val projectId: String,
    val references: List<ProjectFileReference>
)

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
     * 收集指定项目直接或间接拥有的全部文件引用。
     *
     * 这里刻意保留光谱标定的原始 JSON，由数据层在数据库事务结束后容错解析；SQLite
     * 不应承担 JSON 版本兼容逻辑。UNION ALL 不去重，文件清理器会在 canonical 化后去重。
     */
    @Query(
        """
        SELECT 'PROJECT_IMAGE' AS kind, imageUri AS value
        FROM projects
        WHERE id = :projectId AND imageUri != ''
        UNION ALL
        SELECT 'RUN_ID' AS kind, runId AS value
        FROM detection_runs
        WHERE projectId = :projectId
        UNION ALL
        SELECT 'CAPTURE_ORIGINAL' AS kind, capture_artifacts.originalPath AS value
        FROM capture_artifacts
        INNER JOIN detection_runs ON detection_runs.runId = capture_artifacts.runId
        WHERE detection_runs.projectId = :projectId AND capture_artifacts.originalPath != ''
        UNION ALL
        SELECT 'CAPTURE_DERIVED' AS kind, capture_artifacts.derivedPath AS value
        FROM capture_artifacts
        INNER JOIN detection_runs ON detection_runs.runId = capture_artifacts.runId
        WHERE detection_runs.projectId = :projectId
          AND capture_artifacts.derivedPath IS NOT NULL
          AND capture_artifacts.derivedPath != ''
        UNION ALL
        SELECT 'SPECTRUM_RESULT' AS kind, imagePath AS value
        FROM spectrum_results
        WHERE projectId = :projectId AND imagePath != ''
        UNION ALL
        SELECT 'SPECTRUM_CALIBRATION_JSON' AS kind, referencePoints AS value
        FROM spectrum_calibrations
        WHERE projectId = :projectId
          AND referencePoints IS NOT NULL
          AND referencePoints != ''
        UNION ALL
        SELECT 'WELL_CROP' AS kind, croppedImageIdentifier AS value
        FROM well_results
        WHERE projectId = :projectId
          AND croppedImageIdentifier IS NOT NULL
          AND croppedImageIdentifier != ''
        """
    )
    suspend fun getFileReferencesForProject(projectId: String): List<ProjectFileReference>

    /**
     * 获取当前数据库仍在使用的全部持久化路径。
     *
     * 项目删除完成后再次读取该集合，可以避免两个项目共享同一私有图片时误删仍在使用的
     * 文件。运行 ID 不属于路径，也不允许跨项目共享，因此不纳入保护集合。
     */
    @Query(
        """
        SELECT 'PROJECT_IMAGE' AS kind, imageUri AS value
        FROM projects
        WHERE imageUri != ''
        UNION ALL
        SELECT 'CAPTURE_ORIGINAL' AS kind, originalPath AS value
        FROM capture_artifacts
        WHERE originalPath != ''
        UNION ALL
        SELECT 'CAPTURE_DERIVED' AS kind, derivedPath AS value
        FROM capture_artifacts
        WHERE derivedPath IS NOT NULL AND derivedPath != ''
        UNION ALL
        SELECT 'SPECTRUM_RESULT' AS kind, imagePath AS value
        FROM spectrum_results
        WHERE imagePath != ''
        UNION ALL
        SELECT 'SPECTRUM_CALIBRATION_JSON' AS kind, referencePoints AS value
        FROM spectrum_calibrations
        WHERE referencePoints IS NOT NULL AND referencePoints != ''
        UNION ALL
        SELECT 'WELL_CROP' AS kind, croppedImageIdentifier AS value
        FROM well_results
        WHERE croppedImageIdentifier IS NOT NULL AND croppedImageIdentifier != ''
        """
    )
    suspend fun getAllPersistedFileReferences(): List<ProjectFileReference>

    /** 仅供 [collectReferencesAndDelete] 在同一 Room 事务内删除项目主档。 */
    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)

    /**
     * 在外键级联发生前收集引用，再删除项目主档。
     *
     * 如果数据库删除失败，Room 会回滚且不会返回快照；只有提交成功后仓库才会清理文件，
     * 从而避免出现“数据仍在但证据文件先被删除”的不可恢复状态。
     */
    @Transaction
    suspend fun collectReferencesAndDelete(projectId: String): ProjectDeletionSnapshot {
        val references = getFileReferencesForProject(projectId)
        deleteProject(projectId)
        return ProjectDeletionSnapshot(projectId = projectId, references = references)
    }
}
