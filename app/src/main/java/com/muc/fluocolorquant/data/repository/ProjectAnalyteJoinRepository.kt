package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ProjectAnalyteJoinDao
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目-分析物关联仓库接口
 */
interface ProjectAnalyteJoinRepository {
    /**
     * 添加项目-分析物关联
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     * @param maxConcentration 最大浓度
     * @param concentrationUnit 浓度单位
     * @param fkTemplateId 模板ID
     */
    suspend fun addProjectAnalyteJoin(
        projectId: String, 
        analyteId: String, 
        maxConcentration: Double? = null, 
        concentrationUnit: String? = null,
        fkTemplateId: String? = null
    )
    
    /**
     * 直接添加项目-分析物关联对象
     * @param join 关联对象
     */
    suspend fun addProjectAnalyteJoin(join: ProjectAnalyteJoin)
    
    /**
     * 批量添加项目-分析物关联
     * @param projectId 项目ID
     * @param analyteIds 分析物ID列表
     * @param maxConcentration 最大浓度
     * @param concentrationUnit 浓度单位
     */
    suspend fun addProjectAnalyteJoins(
        projectId: String, 
        analyteIds: List<String>, 
        maxConcentration: Double? = null, 
        concentrationUnit: String? = null
    )
    
    /**
     * 删除项目-分析物关联
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     */
    suspend fun removeProjectAnalyteJoin(projectId: String, analyteId: String)
    
    /**
     * 删除项目的所有分析物关联
     * @param projectId 项目ID
     */
    suspend fun removeAllAnalytesFromProject(projectId: String)
    
    /**
     * 获取项目关联的所有分析物
     * @param projectId 项目ID
     * @return 分析物列表流
     */
    fun getAnalytesByProjectId(projectId: String): Flow<List<Analyte>>
    
    /**
     * 检查项目是否关联了指定分析物
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     * @return 是否存在关联
     */
    suspend fun hasAnalyte(projectId: String, analyteId: String): Boolean
    
    /**
     * 获取项目的所有分析物配置
     * @param projectId 项目ID
     * @return 项目分析物配置列表
     */
    suspend fun getProjectAnalyteJoins(projectId: String): List<ProjectAnalyteJoin>
    
    /**
     * 获取项目的第一个分析物配置（用于获取默认的最大浓度和浓度单位）
     * @param projectId 项目ID
     * @return 项目分析物配置，如果不存在则返回null
     */
    suspend fun getFirstProjectAnalyteJoin(projectId: String): ProjectAnalyteJoin?
}

/**
 * 项目-分析物关联仓库实现
 */
@Singleton
class ProjectAnalyteJoinRepositoryImpl @Inject constructor(
    private val projectAnalyteJoinDao: ProjectAnalyteJoinDao
) : ProjectAnalyteJoinRepository {
    
    override suspend fun addProjectAnalyteJoin(
        projectId: String, 
        analyteId: String, 
        maxConcentration: Double?, 
        concentrationUnit: String?,
        fkTemplateId: String?
    ) {
        val join = ProjectAnalyteJoin(
            projectId = projectId, 
            analyteId = analyteId,
            maxConcentration = maxConcentration,
            concentrationUnit = concentrationUnit,
            fkTemplateId = fkTemplateId
        )
        projectAnalyteJoinDao.insert(join)
    }
    
    override suspend fun addProjectAnalyteJoin(join: ProjectAnalyteJoin) {
        projectAnalyteJoinDao.insert(join)
    }
    
    override suspend fun addProjectAnalyteJoins(
        projectId: String, 
        analyteIds: List<String>, 
        maxConcentration: Double?, 
        concentrationUnit: String?
    ) {
        val joins = analyteIds.map { analyteId -> 
            ProjectAnalyteJoin(
                projectId = projectId, 
                analyteId = analyteId,
                maxConcentration = maxConcentration,
                concentrationUnit = concentrationUnit,
                fkTemplateId = null
            )
        }
        projectAnalyteJoinDao.insertAll(joins)
    }
    
    override suspend fun removeProjectAnalyteJoin(projectId: String, analyteId: String) {
        // 这里我们需要先获取完整的对象，或者直接用SQL删除
        // 由于Room不支持按部分主键删除，我们需要先查询完整对象
        val exists = projectAnalyteJoinDao.exists(projectId, analyteId)
        if (exists) {
            // 使用SQL直接删除，避免构造不完整的对象
            projectAnalyteJoinDao.deleteByProjectIdAndAnalyteId(projectId, analyteId)
        }
    }
    
    override suspend fun removeAllAnalytesFromProject(projectId: String) {
        projectAnalyteJoinDao.deleteByProjectId(projectId)
    }
    
    override fun getAnalytesByProjectId(projectId: String): Flow<List<Analyte>> {
        return projectAnalyteJoinDao.getAnalytesByProjectId(projectId)
    }
    
    override suspend fun hasAnalyte(projectId: String, analyteId: String): Boolean {
        return projectAnalyteJoinDao.exists(projectId, analyteId)
    }
    
    override suspend fun getProjectAnalyteJoins(projectId: String): List<ProjectAnalyteJoin> {
        return projectAnalyteJoinDao.getProjectAnalyteJoins(projectId)
    }
    
    override suspend fun getFirstProjectAnalyteJoin(projectId: String): ProjectAnalyteJoin? {
        return projectAnalyteJoinDao.getFirstProjectAnalyteJoin(projectId)
    }
} 