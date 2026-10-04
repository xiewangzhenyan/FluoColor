package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.storage.ProjectFileCleaner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目仓库实现类
 * 实现项目数据的访问逻辑
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val projectFileCleaner: ProjectFileCleaner? = null
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

    override suspend fun createProjectWithAnalytes(
        project: Project,
        joins: List<ProjectAnalyteJoin>
    ) {
        projectDao.insertProjectWithAnalytes(project, joins)
    }
    
    override suspend fun updateProject(project: Project) {
        projectDao.updateProject(project)
    }
    
    override suspend fun deleteProject(projectId: String) {
        // 引用收集和数据库级联删除必须处于同一事务；否则并发写入可能产生未收集的孤儿文件。
        val deletionSnapshot = projectDao.collectReferencesAndDelete(projectId)
        val remainingReferences = projectDao.getAllPersistedFileReferences()
        projectFileCleaner?.let { cleaner ->
            // Room 的 suspend 查询会切换到数据库执行器，但普通文件 API 不会，必须显式离开主线程。
            withContext(Dispatchers.IO) {
                cleaner.clean(deletionSnapshot, remainingReferences)
            }
        }
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
