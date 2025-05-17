package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.ui.screens.project.DetectionMode
import com.muc.fluocolorquant.ui.screens.project.RecognitionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    // 项目列表的StateFlow
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // 当前选中的项目
    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    init {
        // 初始化时加载所有项目
        loadProjects()
    }

    // 加载所有项目
    private fun loadProjects() {
        viewModelScope.launch {
            try {
                val allProjects = projectRepository.getAllProjects()
                _projects.value = allProjects
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    // 创建新项目
    suspend fun createProject(
        name: String,
        detectionMode: DetectionMode,
        recognitionType: RecognitionType,
        imageUri: String,
        maxConcentration: Double? = null,
        userId: String? = null
    ): String? {
        return try {
            val projectId = UUID.randomUUID().toString()
            val project = Project(
                id = projectId,
                name = name,
                detectionMode = detectionMode.name,
                recognitionType = recognitionType.name,
                imageUri = imageUri,
                maxConcentration = maxConcentration ?: 100.0, // 默认值为100.0 ng/ml
                createTime = Date(),
                userId = userId ?: "guest",
                lastRunTimestamp = null // 新项目还没有运行记录
            )
            
            projectRepository.createProject(project)
            
            // 刷新项目列表
            loadProjects()
            
            // 返回项目ID
            projectId
        } catch (e: Exception) {
            null
        }
    }

    // 获取单个项目详情
    fun getProject(projectId: String) {
        viewModelScope.launch {
            try {
                val project = projectRepository.getProjectById(projectId)
                _selectedProject.value = project
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    // 删除项目
    suspend fun deleteProject(projectId: String): Boolean {
        return try {
            projectRepository.deleteProject(projectId)
            
            // 刷新项目列表
            loadProjects()
            
            true
        } catch (e: Exception) {
            false
        }
    }
} 