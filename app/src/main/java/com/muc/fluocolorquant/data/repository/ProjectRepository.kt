package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.Project

/**
 * 项目仓库接口
 * 定义项目数据访问的基本操作
 */
interface ProjectRepository {
    /**
     * 获取所有项目
     * @return 项目列表
     */
    suspend fun getAllProjects(): List<Project>
    
    /**
     * 根据ID获取项目
     * @param projectId 项目ID
     * @return 项目对象
     */
    suspend fun getProjectById(projectId: String): Project?
    
    /**
     * 创建新项目
     * @param project 项目对象
     */
    suspend fun createProject(project: Project)
    
    /**
     * 更新项目
     * @param project 项目对象
     */
    suspend fun updateProject(project: Project)
    
    /**
     * 删除项目
     * @param projectId 项目ID
     */
    suspend fun deleteProject(projectId: String)

    /**
     * 更新光谱配置（光源、光谱列数、列-分析物映射）
     */
    suspend fun updateSpectrumConfig(
        projectId: String,
        lightSource: String?,
        spectrumColumnCount: Int,
        spectrumColumnMappingJson: String?
    )
} 
