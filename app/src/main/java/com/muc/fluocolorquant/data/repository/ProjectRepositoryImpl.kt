package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.model.Project
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目仓库实现类
 * 实现项目数据的访问逻辑
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao
) : ProjectRepository {
    
    override suspend fun getAllProjects(): List<Project> {
        return projectDao.getAllProjects()
    }
    
    override suspend fun getProjectById(projectId: String): Project? {
        return projectDao.getProjectById(projectId)
    }
    
    override suspend fun createProject(project: Project) {
        projectDao.insertProject(project)
    }
    
    override suspend fun updateProject(project: Project) {
        projectDao.updateProject(project)
    }
    
    override suspend fun deleteProject(projectId: String) {
        projectDao.deleteProject(projectId)
    }

    override suspend fun updateSpectrumConfig(
        projectId: String,
        lightSource: String?,
        spectrumColumnCount: Int,
        spectrumColumnMappingJson: String?
    ) {
        projectDao.updateSpectrumConfig(projectId, lightSource, spectrumColumnCount, spectrumColumnMappingJson)
    }
} 
